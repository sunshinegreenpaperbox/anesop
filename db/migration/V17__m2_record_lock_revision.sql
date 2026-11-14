-- =============================================================================
-- ANESOP 数据库迁移 V17：M2 麻醉记录单锁单、修订版本与 CA 占位签名（M3-M2-03）
-- 目标库：PostgreSQL 16；schema：m2_anes
--
-- 设计依据：
--   方案书 §2.4.3（锁单后修改生成修订版本，原值/修改人/时间/原因全部保留；三方核查
--   未完成不允许锁单）、§3.4.3（审计字段/乐观锁/逻辑删除）、§3.9.4（CA 签名与时间戳；
--   EXT-05 CA 不可用，本期为占位/自签哈希）、§3.9.2（RBAC/数据权限）
--   契约：packages/contracts/openapi/anesthesia.yaml 0.8.0
--   任务卡：docs/ai/tasks/M3-backlog.md M3-M2-03（锁单/修订/CA 签名）
--   ADR：docs/adr/0016-麻醉记录与采集接入口径.md（锁单后只能新建修订版本）
--
-- 内容（expand 段：只新增列/表/索引/权限点与更严格的守卫，不改写 V1–V16 既有列与数据）：
--   1) m2_anes.anesthesia_record 增加锁单留痕：locked_at/locked_by（锁单时间/操作人）、
--      current_revision（当前修订版本号，未锁单为 0）、lock_signature_ref/hash/ca_status
--      （锁单 CA 占位签名）。status 取值扩展为 DRAFT/ONGOING/LOCKED/REVISED；
--      LOCKED/REVISED 必须有 started_at（记录已开始），未锁单仍只允许 DRAFT/ONGOING。
--   2) m2_anes.record_revision 修订版本表：锁单后修正的唯一入口。每次修订写入一条不可变
--      记录（revision_no 从 1 递增、原因码/原因/变更摘要、全量快照 jsonb、CA 占位签名），
--      (record_id, revision_no) 唯一；append-only 触发器拒绝 UPDATE/DELETE/TRUNCATE。
--   3) 锁单不可变 DB 强制：记录 locked_at 非空后，禁止 INSERT record_event /
--      drug_administration / fluid_record / resuscitation_record（BEFORE INSERT 触发器，55000）；
--      record_revision BEFORE INSERT 链守卫校验父记录已锁单、版本号连续、上一版摘要一致；
--      记录单内容列
--      （麻醉方式/开始结束时间/台次/申请/患者/创建留痕）UPDATE 亦被守卫拒绝；只允许
--      修订字段（current_revision 逐次 +1、status LOCKED→REVISED、updated_*/version）
--      与锁字段写一次，历史值与修订版本不可改。
--   4) 权限点 m2:record:lock、m2:record:revise（三段式编码）：授 SYS_ADMIN、
--      SYS_ANESTHESIOLOGIST；护士（SYS_NURSE_ANESTHETIST）默认不授，是否允许护士修订
--      待临床确认（【待确认】，见 docs/modules/m2/README.md §14）。
--
-- 临床口径与安全约定：
--   - 锁单前置由服务层统一校验（三方核查完成、当前有效 SIGNED 同意、开始/结束时间完整、
--     至少 1 个时间轴事件），数据库只保证“锁单后不可变”这一硬约束，不重复业务判断。
--   - 修订不修改锁单时的原始行：更正后的内容只写入修订快照（首版差异对比
--     previous_revision_no=0 的锁单原始记录），因此历史版本与原值始终可追溯、可比对。
--   - CA 不可用（EXT-05）：只保存 signature_ref（SIM-CA-<id>）与 signature_hash
--     （规范化记录 JSON 的 SHA-256，排除患者标识/审计时间/签名字段）+ ca_status=SIMULATED；
--     不保存签名原文/图片/证书链，也不产生 PDF 或可信时间戳。
--   - 无 PHI：record_revision.snapshot 不含 patientId/patientNo/patientName；reason/changes
--     为用户填写的说明文本，只落库与授权用户可见，不写入日志/审计原文。
--   - 不物理删除：record_revision 永久 append-only；anesthesia_record 仍为逻辑删除，
--     锁单后不可删除。
--
-- 运行时权限要求：锁单不可变依赖触发器与函数，应用运行账号不得拥有 DDL 权限
-- （CREATE/ALTER/DROP），否则可先 ALTER TABLE ... DISABLE TRIGGER 再改写历史记录；
-- 迁移使用独立 DDL 账号，运行账号仅授业务所需 DML（详见 docs/db/README.md §5.3）。
--
-- 迁移纪律：本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.12）：
--   1) 应用回滚：下线 POST /records/{recordId}/lock、GET /records/{recordId}/lock-status、
--      GET /records/{recordId}/effective、GET/POST /records/{recordId}/revisions 与前端
--      锁单/修订/版本历史/有效值界面；记录单仍可读写
--      （锁单字段与修订版本保留在库中，不影响旧代码读列）。锁单/修订审计保留。
--   2) 库层前向修复（需 DBA 审批 + 备份 + 临床归档，顺序不可颠倒）：
--      DROP TRIGGER IF EXISTS trg_record_event_locked_insert ON m2_anes.record_event;
--      DROP TRIGGER IF EXISTS trg_drug_administration_locked_insert
--          ON m2_anes.drug_administration;
--      DROP TRIGGER IF EXISTS trg_fluid_record_locked_insert ON m2_anes.fluid_record;
--      DROP TRIGGER IF EXISTS trg_resuscitation_record_locked_insert
--          ON m2_anes.resuscitation_record;
--      DROP FUNCTION IF EXISTS m2_anes.fn_record_child_locked_guard();
--      DROP TRIGGER IF EXISTS trg_record_revision_chain_insert ON m2_anes.record_revision;
--      DROP FUNCTION IF EXISTS m2_anes.fn_record_revision_chain_guard();
--      DROP TABLE m2_anes.record_revision;
--      DROP FUNCTION IF EXISTS m2_anes.fn_record_revision_append_only();
--      ALTER TABLE m2_anes.anesthesia_record
--          DROP CONSTRAINT IF EXISTS ck_anesthesia_record_lock_consistency,
--          DROP CONSTRAINT IF EXISTS ck_anesthesia_record_lock_hash,
--          DROP CONSTRAINT IF EXISTS ck_anesthesia_record_started,
--          DROP CONSTRAINT IF EXISTS ck_anesthesia_record_status;
--      UPDATE m2_anes.anesthesia_record SET locked_at = NULL, locked_by = NULL,
--          lock_signature_ref = NULL, lock_signature_hash = NULL,
--          lock_ca_status = NULL, current_revision = 0,
--          status = CASE WHEN status IN ('LOCKED', 'REVISED') THEN 'ONGOING' ELSE status END
--          -- 与 docs/db/README.md §4.12 一致：LOCKED/REVISED 都回退 ONGOING，再重建
--          -- 仅 DRAFT/ONGOING 的 ck_anesthesia_record_status（否则 CHECK 立即失败）。
--      ALTER TABLE m2_anes.anesthesia_record
--          ADD CONSTRAINT ck_anesthesia_record_status CHECK (status IN ('DRAFT', 'ONGOING')),
--          ADD CONSTRAINT ck_anesthesia_record_started CHECK (
--              (status = 'DRAFT' AND started_at IS NULL)
--              OR (status = 'ONGOING' AND started_at IS NOT NULL));
--      ALTER TABLE m2_anes.anesthesia_record
--          DROP COLUMN lock_signature_ref,
--          DROP COLUMN lock_signature_hash,
--          DROP COLUMN lock_ca_status,
--          DROP COLUMN current_revision,
--          DROP COLUMN locked_by,
--          DROP COLUMN locked_at;
--      回滚前必须导出 record_revision 与 anesthesia_record 并校验行数；锁单与修订属临床
--      留痕，须先归档到离线审计库，生产执行须人签字（不可逆脚本）。
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id IN ('b0000000-0000-4000-8000-000000000061',
--                                'b0000000-0000-4000-8000-000000000062');
--      DELETE FROM m0_platform.sys_permission
--        WHERE code IN ('m2:record:lock', 'm2:record:revise');
--   3) 回滚后 V13 守卫函数需重新执行（CREATE OR REPLACE）以恢复“仅 DRAFT/ONGOING”语义；
--      见 V13 脚本 fn_anesthesia_record_guard。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. anesthesia_record 锁单留痕与修订版本号
-- -----------------------------------------------------------------------------
ALTER TABLE m2_anes.anesthesia_record
    ADD COLUMN locked_at timestamp with time zone,
    ADD COLUMN locked_by uuid,
    ADD COLUMN current_revision integer NOT NULL DEFAULT 0,
    ADD COLUMN lock_signature_ref varchar(128),
    ADD COLUMN lock_signature_hash varchar(64),
    ADD COLUMN lock_ca_status varchar(16);

COMMENT ON COLUMN m2_anes.anesthesia_record.locked_at IS
    '锁单时间（timestamptz，写入一次后不可改）；NULL=未锁单，锁单后记录内容不可直接修改';
COMMENT ON COLUMN m2_anes.anesthesia_record.locked_by IS
    '锁单操作人用户标识；与 locked_at 同时写入，写入后不可改';
COMMENT ON COLUMN m2_anes.anesthesia_record.current_revision IS
    '当前修订版本号（未锁单 0；每次修订 +1，与 record_revision.revision_no 对应）；只增';
COMMENT ON COLUMN m2_anes.anesthesia_record.lock_signature_ref IS
    '锁单签名占位标识（EXT-05：SIM-CA-<记录单标识>）；不保存签名原文/图片/证书链';
COMMENT ON COLUMN m2_anes.anesthesia_record.lock_signature_hash IS
    '锁单签名摘要：规范化记录 JSON（字段排序、排除患者标识/审计时间/签名字段）的 SHA-256 '
    '十六进制（64 字符）';
COMMENT ON COLUMN m2_anes.anesthesia_record.lock_ca_status IS
    '锁单 CA 状态：SIMULATED=占位签名（EXT-05）/VERIFIED=真实 CA（EXT-05 解除后）';

-- 状态与锁字段一致性：
--   * 未锁单：status 只允许 DRAFT/ONGOING，锁字段全空且 current_revision=0；
--   * 已锁单：status 只允许 LOCKED（current_revision=0）或 REVISED（current_revision>=1），
--     锁字段齐全；lock_ca_status 只允许 SIMULATED/VERIFIED。
ALTER TABLE m2_anes.anesthesia_record
    DROP CONSTRAINT IF EXISTS ck_anesthesia_record_status,
    DROP CONSTRAINT IF EXISTS ck_anesthesia_record_started,
    DROP CONSTRAINT IF EXISTS ck_anesthesia_record_lock_consistency,
    DROP CONSTRAINT IF EXISTS ck_anesthesia_record_lock_hash,
    ADD CONSTRAINT ck_anesthesia_record_status
        CHECK (status IN ('DRAFT', 'ONGOING', 'LOCKED', 'REVISED')),
    ADD CONSTRAINT ck_anesthesia_record_started CHECK (
        (status = 'DRAFT' AND started_at IS NULL)
        OR (status IN ('ONGOING', 'LOCKED', 'REVISED') AND started_at IS NOT NULL)
    ),
    ADD CONSTRAINT ck_anesthesia_record_lock_consistency CHECK (
        (locked_at IS NULL
            AND locked_by IS NULL
            AND lock_signature_ref IS NULL
            AND lock_signature_hash IS NULL
            AND lock_ca_status IS NULL
            AND current_revision = 0
            AND status IN ('DRAFT', 'ONGOING'))
        OR (locked_at IS NOT NULL
            AND locked_by IS NOT NULL
            AND lock_signature_ref IS NOT NULL
            AND lock_signature_hash IS NOT NULL
            AND lock_ca_status IN ('SIMULATED', 'VERIFIED')
            AND current_revision >= 0
            AND ((current_revision = 0 AND status = 'LOCKED')
                OR (current_revision >= 1 AND status = 'REVISED')))
    ),
    ADD CONSTRAINT ck_anesthesia_record_lock_hash
        CHECK (lock_signature_hash IS NULL OR lock_signature_hash ~ '^[0-9a-f]{64}$');

-- -----------------------------------------------------------------------------
-- 2. record_revision 修订版本（append-only，锁单后修正唯一入口）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.record_revision (
    id                       uuid         NOT NULL DEFAULT gen_random_uuid(),
    record_id                uuid         NOT NULL,
    revision_no              integer      NOT NULL,
    reason_code              varchar(32)  NOT NULL,
    reason                   varchar(500) NOT NULL,
    changes                  varchar(500) NOT NULL,
    snapshot                 jsonb        NOT NULL,
    previous_revision_no     integer,
    previous_signature_hash  varchar(64),
    signature_ref            varchar(128) NOT NULL,
    signature_hash           varchar(64)  NOT NULL,
    ca_status                varchar(16)  NOT NULL DEFAULT 'SIMULATED',
    created_at               timestamptz  NOT NULL DEFAULT now(),
    created_by               uuid         NOT NULL,
    CONSTRAINT pk_record_revision PRIMARY KEY (id),
    CONSTRAINT fk_record_revision_record FOREIGN KEY (record_id)
        REFERENCES m2_anes.anesthesia_record (id) ON DELETE RESTRICT,
    -- 同一记录单修订版本号唯一：并发修订由记录单行锁串行化，唯一索引为最后一道防线。
    CONSTRAINT uk_record_revision_record_no UNIQUE (record_id, revision_no),
    CONSTRAINT ck_record_revision_no CHECK (revision_no >= 1),
    -- 0.8.1：原因码收缩为与当前更正能力一致的集合；OMISSION/TIMING_CORRECTION 需结构化
    -- 更正能力，登记为 M3-QA 前待实现（docs/decisions.md Q 台账 / docs/modules/m2 §14.9）。
    CONSTRAINT ck_record_revision_reason_code CHECK (
        reason_code IN ('ANESTHESIA_TYPE_CHANGE', 'OTHER')),
    CONSTRAINT ck_record_revision_reason_length
        CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_record_revision_changes_length
        CHECK (char_length(btrim(changes)) BETWEEN 1 AND 500),
    CONSTRAINT ck_record_revision_snapshot_object CHECK (jsonb_typeof(snapshot) = 'object'),
    -- 首版无上一版（两列 NULL）；后续版本必须成对记录上一版版本号与签名摘要。
    CONSTRAINT ck_record_revision_previous CHECK (
        (revision_no = 1
            AND previous_revision_no IS NULL
            AND previous_signature_hash IS NULL)
        OR (revision_no > 1
            AND previous_revision_no = revision_no - 1
            AND previous_signature_hash IS NOT NULL)
    ),
    CONSTRAINT ck_record_revision_signature_hash
        CHECK (signature_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_record_revision_previous_hash
        CHECK (previous_signature_hash IS NULL
            OR previous_signature_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_record_revision_ca_status
        CHECK (ca_status IN ('PENDING', 'SIMULATED', 'VERIFIED', 'FAILED'))
);

CREATE INDEX idx_record_revision_record_no
    ON m2_anes.record_revision (record_id, revision_no);
CREATE INDEX idx_record_revision_created_by
    ON m2_anes.record_revision (created_by);
CREATE INDEX idx_record_revision_created_at
    ON m2_anes.record_revision (created_at);

COMMENT ON TABLE m2_anes.record_revision IS
    '麻醉记录单修订版本：锁单后修正的唯一入口；append-only（UPDATE/DELETE/TRUNCATE 拒绝，55000）；'
    '原记录与历史版本不可改，快照含全部子记录与签名摘要';
COMMENT ON COLUMN m2_anes.record_revision.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.record_revision.record_id IS
    '所属麻醉记录单（FK；数据范围沿记录单）；记录必须已锁单才能新增修订';
COMMENT ON COLUMN m2_anes.record_revision.revision_no IS
    '修订版本号（同一记录单从 1 递增，唯一；由服务层在记录单行锁内分配，数据库兜底）';
COMMENT ON COLUMN m2_anes.record_revision.reason_code IS
    '修订原因码白名单（0.8.1）：ANESTHESIA_TYPE_CHANGE/OTHER；OMISSION/TIMING_CORRECTION '
    '待结构化更正能力（M3-QA 前）';
COMMENT ON COLUMN m2_anes.record_revision.reason IS
    '修订原因说明（≤500 字符；只落库与授权用户可见，不写入日志/审计原文）';
COMMENT ON COLUMN m2_anes.record_revision.changes IS
    '变更摘要（≤500 字符，用于版本历史展示；不写入日志/审计原文）';
COMMENT ON COLUMN m2_anes.record_revision.snapshot IS
    '全量快照 jsonb：record/events/drugs/fluids/ioSummary/signatures；不含患者标识、审计时间与签名字段本身';
COMMENT ON COLUMN m2_anes.record_revision.previous_revision_no IS
    '对比的上一修订版本号（首版为 NULL；后续版本 = revision_no-1）';
COMMENT ON COLUMN m2_anes.record_revision.previous_signature_hash IS
    '上一修订版本的签名摘要（首版为 NULL），用于校验版本链未被改写';
COMMENT ON COLUMN m2_anes.record_revision.signature_ref IS
    '修订签名占位标识（EXT-05：SIM-CA-<修订标识>）；不保存签名原文/图片/证书链';
COMMENT ON COLUMN m2_anes.record_revision.signature_hash IS
    '修订签名摘要：修订快照规范化 JSON 的 SHA-256（十六进制 64 字符）';
COMMENT ON COLUMN m2_anes.record_revision.ca_status IS
    'CA 状态：SIMULATED=占位签名（EXT-05）/VERIFIED=真实 CA（EXT-05 解除后）/PENDING/FAILED';
COMMENT ON COLUMN m2_anes.record_revision.created_at IS '修订创建时间（服务端时间）';
COMMENT ON COLUMN m2_anes.record_revision.created_by IS '修订创建人用户标识（当前登录用户，必填）';

-- 修订版本 append-only：任何 UPDATE/DELETE/TRUNCATE 拒绝（修正 = 新增下一修订版本）。
CREATE OR REPLACE FUNCTION m2_anes.fn_record_revision_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'm2_anes.record_revision is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '修订版本不可修改或删除；修正请新增下一修订版本并说明原因';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_record_revision_append_only() IS
    '修订版本 append-only 触发器函数：拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000';

CREATE TRIGGER trg_record_revision_no_update
    BEFORE UPDATE ON m2_anes.record_revision
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_record_revision_append_only();

CREATE TRIGGER trg_record_revision_no_delete
    BEFORE DELETE ON m2_anes.record_revision
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_record_revision_append_only();

CREATE TRIGGER trg_record_revision_no_truncate
    BEFORE TRUNCATE ON m2_anes.record_revision
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_record_revision_append_only();

-- -----------------------------------------------------------------------------
-- 2b. 修订版本链 BEFORE INSERT 守卫（服务层之外的第二道防线）
--     校验：父记录已锁单、revision_no = current_revision + 1、previous_signature_hash 与
--     上一修订版本摘要一致（首版 previous 为空）。伪造/跳号/断链直插一律 55000。
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION m2_anes.fn_record_revision_chain_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_locked_at timestamptz;
    parent_revision integer;
    previous_hash    varchar(64);
BEGIN
    SELECT r.locked_at, r.current_revision
      INTO parent_locked_at, parent_revision
      FROM m2_anes.anesthesia_record r
     WHERE r.id = NEW.record_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'm2_anes.record_revision parent record % does not exist', NEW.record_id
            USING ERRCODE = '55000',
                  HINT = '修订必须挂在已存在的麻醉记录单上';
    END IF;
    IF parent_locked_at IS NULL THEN
        RAISE EXCEPTION
            'm2_anes.record_revision requires a locked parent record'
            USING ERRCODE = '55000',
                  HINT = '记录单未锁单，不能新增修订版本';
    END IF;
    IF NEW.revision_no <> parent_revision + 1 THEN
        RAISE EXCEPTION
            'm2_anes.record_revision revision_no must be current_revision + 1: % -> %',
            parent_revision, NEW.revision_no
            USING ERRCODE = '55000',
                  HINT = '修订版本号必须由记录单当前修订号逐次 +1，不得跳号或伪造';
    END IF;
    IF NEW.revision_no > 1 THEN
        SELECT rv.signature_hash INTO previous_hash
          FROM m2_anes.record_revision rv
         WHERE rv.record_id = NEW.record_id
           AND rv.revision_no = NEW.revision_no - 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION
                'm2_anes.record_revision previous version % is missing',
                NEW.revision_no - 1
                USING ERRCODE = '55000',
                      HINT = '版本链必须连续；缺少上一修订版本时不得写入';
        END IF;
        IF NEW.previous_signature_hash IS DISTINCT FROM previous_hash THEN
            RAISE EXCEPTION
                'm2_anes.record_revision previous_signature_hash does not match version %',
                NEW.revision_no - 1
                USING ERRCODE = '55000',
                      HINT = 'previous_signature_hash 必须等于上一修订版本的签名摘要，禁止断链';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_record_revision_chain_guard() IS
    '修订版本链守卫（V17）：父记录已锁单、revision_no=current_revision+1、'
    'previous_signature_hash 与上一版一致；伪造/跳号/断链拒绝（55000）';

CREATE TRIGGER trg_record_revision_chain_insert
    BEFORE INSERT ON m2_anes.record_revision
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_record_revision_chain_guard();

-- -----------------------------------------------------------------------------
-- 3. 记录单守卫升级（锁定不可变 + 锁字段写一次 + 修订号只增）
--    V13 的 fn_anesthesia_record_guard 只允许 DRAFT/ONGOING；本节 CREATE OR REPLACE
--    扩展为 LOCKED/REVISED，并保证：
--      a) 身份列/创建留痕不可改（沿用 V13）；
--      b) version 只增（沿用 V13）；已逻辑删除行不可改、DELETE/TRUNCATE 拒绝；
--      c) 锁单前：current_revision 必须为 0，status 只允许 DRAFT/ONGOING（不得直接写
--         LOCKED/REVISED）；写入锁字段必须一次性写全，且锁单不改变记录内容；
--      d) 锁单后：锁字段与记录内容列一律不可改；只允许 status LOCKED→REVISED（一次）、
--         current_revision 逐次 +1（与修订版本号一致）、version 递增与 updated_* 更新；
--      e) started_at 写入后不得清空/改写（沿用 V13）。
--    SQLSTATE 55000。
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION m2_anes.fn_anesthesia_record_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.schedule_id IS DISTINCT FROM OLD.schedule_id
           OR NEW.application_id IS DISTINCT FROM OLD.application_id
           OR NEW.patient_id IS DISTINCT FROM OLD.patient_id
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
            RAISE EXCEPTION
                'm2_anes.anesthesia_record identity columns are immutable'
                USING ERRCODE = '55000',
                      HINT = '记录单的台次/申请/患者/创建留痕不可修改；请新建更正流程';
        END IF;
        IF OLD.deleted THEN
            RAISE EXCEPTION
                'm2_anes.anesthesia_record is deleted: UPDATE is not permitted'
                USING ERRCODE = '55000',
                      HINT = '已删除记录单不可再修改';
        END IF;
        IF NEW.version <= OLD.version THEN
            RAISE EXCEPTION
                'm2_anes.anesthesia_record version must increase: % -> %', OLD.version, NEW.version
                USING ERRCODE = '55000',
                      HINT = '记录单更新必须递增 version（乐观锁），不得静默覆盖';
        END IF;

        IF OLD.locked_at IS NOT NULL THEN
            -- 已锁单：锁字段与记录内容一律不可变，只允许修订元数据推进。
            IF NEW.locked_at IS DISTINCT FROM OLD.locked_at
               OR NEW.locked_by IS DISTINCT FROM OLD.locked_by
               OR NEW.lock_signature_ref IS DISTINCT FROM OLD.lock_signature_ref
               OR NEW.lock_signature_hash IS DISTINCT FROM OLD.lock_signature_hash
               OR NEW.lock_ca_status IS DISTINCT FROM OLD.lock_ca_status THEN
                RAISE EXCEPTION
                    'm2_anes.anesthesia_record lock columns are write-once'
                    USING ERRCODE = '55000',
                          HINT = '锁单时间/操作人/签名摘要不可改写；修订请新增 record_revision';
            END IF;
            IF NEW.anesthesia_type IS DISTINCT FROM OLD.anesthesia_type
               OR NEW.started_at IS DISTINCT FROM OLD.started_at
               OR NEW.ended_at IS DISTINCT FROM OLD.ended_at
               OR NEW.room_id IS DISTINCT FROM OLD.room_id
               OR NEW.deleted IS DISTINCT FROM OLD.deleted THEN
                RAISE EXCEPTION
                    'm2_anes.anesthesia_record is locked: content columns are immutable'
                    USING ERRCODE = '55000',
                          HINT = '锁单后记录内容不可改；修正请新增修订版本（record_revision）';
            END IF;
            IF NEW.status IS DISTINCT FROM OLD.status
               AND NOT (OLD.status = 'LOCKED' AND NEW.status = 'REVISED') THEN
                RAISE EXCEPTION
                    'm2_anes.anesthesia_record is locked: status % -> % is not permitted',
                    OLD.status, NEW.status
                    USING ERRCODE = '55000',
                          HINT = '锁单后状态只允许 LOCKED→REVISED（首次修订），且不可回退';
            END IF;
            IF NEW.current_revision < OLD.current_revision THEN
                RAISE EXCEPTION
                    'm2_anes.anesthesia_record current_revision cannot go backwards: % -> %',
                    OLD.current_revision, NEW.current_revision
                    USING ERRCODE = '55000',
                          HINT = '修订版本号只增不减';
            END IF;
            IF NEW.current_revision > OLD.current_revision THEN
                IF NEW.current_revision <> OLD.current_revision + 1 THEN
                    RAISE EXCEPTION
                        'm2_anes.anesthesia_record current_revision must increase by 1: % -> %',
                        OLD.current_revision, NEW.current_revision
                        USING ERRCODE = '55000',
                              HINT = '每次修订只推进一个版本号';
                END IF;
                IF NEW.status <> 'REVISED' THEN
                    RAISE EXCEPTION
                        'm2_anes.anesthesia_record revision requires status REVISED'
                        USING ERRCODE = '55000',
                              HINT = '推进 current_revision 必须同时把状态置为 REVISED';
                END IF;
            END IF;
            RETURN NEW;
        END IF;

        -- 未锁单分支：不允许提前写修订号，也不允许绕过锁单直接写 LOCKED/REVISED。
        IF NEW.current_revision <> 0 THEN
            RAISE EXCEPTION
                'm2_anes.anesthesia_record current_revision must be 0 before lock'
                USING ERRCODE = '55000',
                      HINT = '修订版本号只能由锁单后的修订流程推进';
        END IF;
        IF OLD.started_at IS NOT NULL AND NEW.started_at IS DISTINCT FROM OLD.started_at THEN
            RAISE EXCEPTION
                'm2_anes.anesthesia_record started_at is write-once'
                USING ERRCODE = '55000',
                      HINT = '记录开始时间一旦写入不得清空或改写';
        END IF;

        IF NEW.locked_at IS NOT NULL THEN
            -- 锁单一次性写入：锁字段齐全、状态 LOCKED、修订号 0，且不改变记录内容。
            IF NEW.locked_by IS NULL
               OR NEW.lock_signature_ref IS NULL
               OR NEW.lock_signature_hash IS NULL
               OR NEW.lock_ca_status IS NULL THEN
                RAISE EXCEPTION
                    'm2_anes.anesthesia_record lock requires actor and signature'
                    USING ERRCODE = '55000',
                          HINT = '锁单必须一次性写入锁单人与 CA 占位签名摘要';
            END IF;
            IF NEW.status <> 'LOCKED' THEN
                RAISE EXCEPTION
                    'm2_anes.anesthesia_record lock requires status LOCKED'
                    USING ERRCODE = '55000',
                          HINT = '锁单必须同时把记录状态置为 LOCKED';
            END IF;
            IF NEW.anesthesia_type IS DISTINCT FROM OLD.anesthesia_type
               OR NEW.started_at IS DISTINCT FROM OLD.started_at
               OR NEW.ended_at IS DISTINCT FROM OLD.ended_at
               OR NEW.room_id IS DISTINCT FROM OLD.room_id THEN
                RAISE EXCEPTION
                    'm2_anes.anesthesia_record lock cannot change record content'
                    USING ERRCODE = '55000',
                          HINT = '锁单只写锁字段，不改变麻醉方式/时间；如需更正请走修订';
            END IF;
            IF NOT (OLD.status = 'ONGOING' AND NEW.status = 'LOCKED') THEN
                RAISE EXCEPTION
                    'm2_anes.anesthesia_record lock requires ONGOING -> LOCKED: % -> %',
                    OLD.status, NEW.status
                    USING ERRCODE = '55000',
                          HINT = '只有进行中的记录单可以锁单（DRAFT 需先记录麻醉开始/结束）';
            END IF;
            RETURN NEW;
        END IF;

        -- 未锁单且未锁单写入：status 只允许 DRAFT→ONGOING 单向流转。
        IF array_position(ARRAY['DRAFT', 'ONGOING']::text[], NEW.status::text)
           < array_position(ARRAY['DRAFT', 'ONGOING']::text[], OLD.status::text) THEN
            RAISE EXCEPTION
                'm2_anes.anesthesia_record status cannot go backwards: % -> %', OLD.status, NEW.status
                USING ERRCODE = '55000',
                      HINT = '记录单状态只允许向前流转（DRAFT→ONGOING→LOCKED→REVISED）';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION
        'm2_anes.anesthesia_record is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '临床记录单不得物理删除；请保留历史并走锁单/修订流程';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_anesthesia_record_guard() IS
    '记录单守卫（V17 升级）：身份列不可变、锁字段写一次、锁单后内容不可变、只允许 LOCKED→REVISED '
    '与 current_revision 逐次 +1、version 只增；拒绝 DELETE/TRUNCATE（55000）';

-- -----------------------------------------------------------------------------
-- 4. 锁单后禁止追加时间轴事件/用药/出入量（BEFORE INSERT，55000）
--    append-only 函数只拒绝 UPDATE/DELETE/TRUNCATE；锁单后的新增由本触发器拦截。
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION m2_anes.fn_record_child_locked_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    locked_at timestamptz;
BEGIN
    SELECT r.locked_at INTO locked_at
      FROM m2_anes.anesthesia_record r
     WHERE r.id = NEW.record_id;
    IF locked_at IS NOT NULL THEN
        RAISE EXCEPTION
            'm2_anes.% is immutable after lock: INSERT is not permitted', TG_TABLE_NAME
            USING ERRCODE = '55000',
                  HINT = '记录已锁单：禁止追加事件/用药/出入量；请走修订版本流程';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_record_child_locked_guard() IS
    '锁单后子记录写入守卫：记录 locked_at 非空时拒绝 INSERT record_event/drug_administration/'
    'fluid_record/resuscitation_record（55000）';

CREATE TRIGGER trg_record_event_locked_insert
    BEFORE INSERT ON m2_anes.record_event
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_record_child_locked_guard();

CREATE TRIGGER trg_drug_administration_locked_insert
    BEFORE INSERT ON m2_anes.drug_administration
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_record_child_locked_guard();

CREATE TRIGGER trg_fluid_record_locked_insert
    BEFORE INSERT ON m2_anes.fluid_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_record_child_locked_guard();

-- Q-2：锁单后抢救记录同样冻结（与“记录内容不可变”一致；临床确需补录走下一修订版本）。
CREATE TRIGGER trg_resuscitation_record_locked_insert
    BEFORE INSERT ON m2_anes.resuscitation_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_record_child_locked_guard();

-- -----------------------------------------------------------------------------
-- 5. 权限点 m2:record:lock、m2:record:revise
--    授 SYS_ADMIN（运维/排障）与 SYS_ANESTHESIOLOGIST（锁单与修订责任人）；
--    SYS_NURSE_ANESTHETIST 默认不授：护士是否可修订待临床确认（【待确认】）。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort) VALUES
    ('b0000000-0000-4000-8000-000000000061', 'm2:record:lock', 'M2 麻醉记录单锁单', 'API',
     '/api/v1/anesthesia/records/{recordId}/lock', 'POST', NULL, 138),
    ('b0000000-0000-4000-8000-000000000062', 'm2:record:revise', 'M2 麻醉记录单修订', 'API',
     '/api/v1/anesthesia/records/{recordId}/revisions', 'POST', NULL, 139);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：锁单与修订（运维/排障）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000061'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000062'),
    -- SYS_ANESTHESIOLOGIST：锁单与修订责任人
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000061'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000062');

-- -----------------------------------------------------------------------------
-- 6. 迁移自检（fail fast）
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'm2_anes' AND table_name = 'anesthesia_record'
          AND column_name = 'locked_at'
    ) THEN
        RAISE EXCEPTION 'V17 迁移自检失败：anesthesia_record.locked_at 未创建';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'm2_anes' AND table_name = 'anesthesia_record'
          AND column_name = 'current_revision'
    ) THEN
        RAISE EXCEPTION 'V17 迁移自检失败：anesthesia_record.current_revision 未创建';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_record_revision_record_no'
          AND conrelid = 'm2_anes.record_revision'::regclass
    ) THEN
        RAISE EXCEPTION 'V17 迁移自检失败：record_revision (record_id, revision_no) 唯一约束缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_record_event_locked_insert'
          AND tgrelid = 'm2_anes.record_event'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V17 迁移自检失败：record_event 锁单写入守卫缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_record_revision_no_update'
          AND tgrelid = 'm2_anes.record_revision'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V17 迁移自检失败：record_revision append-only 触发器缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_record_revision_chain_insert'
          AND tgrelid = 'm2_anes.record_revision'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V17 迁移自检失败：record_revision 版本链守卫缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_resuscitation_record_locked_insert'
          AND tgrelid = 'm2_anes.resuscitation_record'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V17 迁移自检失败：resuscitation_record 锁单冻结守卫缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_record_revision_reason_code'
          AND pg_get_constraintdef(oid) LIKE '%ANESTHESIA_TYPE_CHANGE%'
    ) THEN
        RAISE EXCEPTION 'V17 迁移自检失败：修订原因码白名单未收缩到 0.8.1 集合';
    END IF;
    IF (SELECT count(*) FROM m0_platform.sys_permission
         WHERE code IN ('m2:record:lock', 'm2:record:revise')) <> 2 THEN
        RAISE EXCEPTION 'V17 迁移自检失败：权限点 m2:record:lock/revise 未全部写入';
    END IF;
    IF (SELECT count(*) FROM m0_platform.sys_role_permission rp
          JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
         WHERE p.code IN ('m2:record:lock', 'm2:record:revise')
           AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001',
                              'a0000000-0000-4000-8000-000000000002')) <> 4 THEN
        RAISE EXCEPTION 'V17 迁移自检失败：锁单/修订权限未授予 SYS_ADMIN/SYS_ANESTHESIOLOGIST';
    END IF;
END
$$;
