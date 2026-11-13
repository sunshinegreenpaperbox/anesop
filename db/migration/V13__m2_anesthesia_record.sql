-- =============================================================================
-- ANESOP 数据库迁移 V13：M2 麻醉记录单与时间轴（M3-M2-01）
--
-- 设计依据：
--   方案书 §2.4.3（麻醉记录单：时间轴事件、用药/输液、出入量、麻精双人核对、
--   记录不可变与锁单留痕）、§3.4.2（麻醉记录单/事件实体）、
--   §3.4.3（审计字段/乐观锁/逻辑删除）、§3.9.2（RBAC/数据权限）、§3.9.4（CA 签名占位）
--   契约：packages/contracts/openapi/anesthesia.yaml 0.5.0
--   任务卡：docs/ai/tasks/M3-backlog.md M3-M2-01（记录单/时间轴/用药/输液/出入量）
--
-- 内容（expand 段：只新增表/索引/权限点，不改写 V1–V12 既有列与数据）：
--   1) m2_anes.anesthesia_record：麻醉记录单。按台次（schedule_id）唯一，一个台次一张记录单；
--      状态 DRAFT/ONGOING（契约映射 DRAFT/IN_PROGRESS），未锁定可改（乐观锁 version）。
--      锁单（LOCKED/REVISED）由 M3-M2-03 以新迁移扩展状态约束与锁单列，本脚本不实现锁单。
--   2) m2_anes.record_event：时间轴事件，append-only（UPDATE/DELETE/TRUNCATE 一律拒绝，
--      SQLSTATE 55000）；seq 由服务端按记录单分配（(record_id, seq) 唯一），
--      occurred_at 为事件实际发生时间，payload 为 jsonb 结构化明细（备注/补录原因等）。
--   3) m2_anes.drug_administration：用药记录，append-only；麻精类（narcotic=true）必须
--      同时具备 second_checker_id 与 second_checker_name，且核对人不得与给药人相同
--      （数据库 CHECK 为第二道防线，服务层先返回 400）。
--   4) m2_anes.fluid_record：输液/出入量明细（INTAKE/OUTPUT），append-only；
--      amount_ml > 0，出入量汇总由服务端按明细计算，不落冗余合计列。
--   5) 权限点 m2:record:read|write（三段式编码）授予 SYS_ADMIN、SYS_ANESTHESIOLOGIST、
--      SYS_NURSE_ANESTHETIST（记录单由麻醉医师/麻醉护士书写，系统管理员用于运维排障）。
--
-- 临床口径与安全约定：
--   - 时间轴以事件时间为准（occurred_at），录入时间（created_at）单独保留；事件只能追加，
--     修正必须新增事件并在 payload.entryReason 说明，禁止改写历史事件。
--   - 一个台次唯一记录单：uk_anesthesia_record_schedule 唯一索引 + 服务层 409 兜底；
--     并发重复创建由唯一索引拒绝（DuplicateKeyException → 409）。
--   - 麻精双人核对：narcotic=true 时 second_checker_id/second_checker_name 必填，
--     且 second_checker_id <> created_by（同人核对被数据库拒绝）；核对人姓名只落库，
--     不写日志/审计原文。
--   - 无 PHI 日志：表内只存 patient_id（标识）与 schedule_id/application_id，不存姓名/
--     证件号/手机号/住院号明文；用药只存药品字典名与剂量，不含患者标识。
--   - 不物理删除：anesthesia_record 用 deleted 逻辑删除（本期不提供删除接口）；
--     record_event/drug_administration/fluid_record 永久 append-only。
--
-- 迁移纪律：本脚本已合入后禁止修改；变更一律新增 V<版本> 脚本（expand-contract）。
-- 回滚策略（forward-only，详见 docs/db/README.md）：
--   1) 应用回滚：下线 /v1/anesthesia/records（按台次创建/查询）与
--      /v1/anesthesia/records/{recordId}/events|drugs|fluids|io-summary 路由，以及前端
--      麻醉记录单页；术前访视/知情同意/三方核查、申请与排程不受影响。
--   2) 库层前向修复（需 DBA 审批 + 备份，顺序不可颠倒）：
--      DROP TABLE m2_anes.fluid_record;
--      DROP TABLE m2_anes.drug_administration;
--      DROP TABLE m2_anes.record_event;
--      DROP TABLE m2_anes.anesthesia_record;
--      DROP FUNCTION IF EXISTS m2_anes.fn_fluid_record_append_only();
--      DROP FUNCTION IF EXISTS m2_anes.fn_drug_administration_append_only();
--      DROP FUNCTION IF EXISTS m2_anes.fn_record_event_append_only();
--      DROP FUNCTION IF EXISTS m2_anes.fn_anesthesia_record_guard();
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id IN ('b0000000-0000-4000-8000-000000000031',
--                                'b0000000-0000-4000-8000-000000000032');
--      DELETE FROM m0_platform.sys_permission
--        WHERE code IN ('m2:record:read', 'm2:record:write');
--   3) 回滚前导出四张表并校验行数；麻醉记录属临床留痕，须先归档到离线审计库，
--      生产执行须人签字。V14（采集）与本脚本无表级依赖，可独立回滚。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. anesthesia_record 麻醉记录单（一个台次一张，未锁定可改）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.anesthesia_record (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    schedule_id    uuid         NOT NULL,
    application_id uuid         NOT NULL,
    patient_id     uuid         NOT NULL,
    status         varchar(16)  NOT NULL DEFAULT 'DRAFT',
    anesthesia_type varchar(16) NOT NULL DEFAULT 'GENERAL',
    started_at     timestamptz,
    ended_at       timestamptz,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    updated_by     uuid,
    version        bigint       NOT NULL DEFAULT 1,
    deleted        boolean      NOT NULL DEFAULT false,
    CONSTRAINT pk_anesthesia_record PRIMARY KEY (id),
    CONSTRAINT fk_anesthesia_record_schedule FOREIGN KEY (schedule_id)
        REFERENCES m1_sched.schedule (id) ON DELETE RESTRICT,
    CONSTRAINT fk_anesthesia_record_application FOREIGN KEY (application_id)
        REFERENCES m1_sched.surgery_application (id),
    CONSTRAINT ck_anesthesia_record_status CHECK (status IN ('DRAFT', 'ONGOING')),
    CONSTRAINT ck_anesthesia_record_anesthesia_type
        CHECK (anesthesia_type IN ('GENERAL', 'REGIONAL', 'COMBINED', 'LOCAL', 'SEDATION')),
    CONSTRAINT ck_anesthesia_record_version CHECK (version >= 1),
    -- 记录结束不得早于开始；未结束（ended_at NULL）时开始时间可有可无。
    CONSTRAINT ck_anesthesia_record_time_window
        CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at),
    -- ONGOING 必须有开始时间；DRAFT 尚未开始。
    CONSTRAINT ck_anesthesia_record_started CHECK (
        (status = 'DRAFT' AND started_at IS NULL)
        OR (status = 'ONGOING' AND started_at IS NOT NULL)
    )
);

-- 一个台次只允许一张记录单（并发重复创建的第二道防线，服务层先返回 409）。
CREATE UNIQUE INDEX uk_anesthesia_record_schedule
    ON m2_anes.anesthesia_record (schedule_id);
CREATE INDEX idx_anesthesia_record_application_id
    ON m2_anes.anesthesia_record (application_id);
CREATE INDEX idx_anesthesia_record_patient_id ON m2_anes.anesthesia_record (patient_id);
CREATE INDEX idx_anesthesia_record_status ON m2_anes.anesthesia_record (status);
CREATE INDEX idx_anesthesia_record_created_at ON m2_anes.anesthesia_record (created_at);
CREATE INDEX idx_anesthesia_record_deleted ON m2_anes.anesthesia_record (deleted);

COMMENT ON TABLE m2_anes.anesthesia_record IS
    '麻醉记录单：一个台次一张（uk_anesthesia_record_schedule），状态 DRAFT/ONGOING，未锁定可改（乐观锁 version）；锁单留 M3-M2-03';
COMMENT ON COLUMN m2_anes.anesthesia_record.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.anesthesia_record.schedule_id IS
    '关联排程台次（FK，唯一）；数据范围沿台次/申请可见性，越界 404/403';
COMMENT ON COLUMN m2_anes.anesthesia_record.application_id IS '关联手术申请（FK；由台次解析，不信任客户端）';
COMMENT ON COLUMN m2_anes.anesthesia_record.patient_id IS '患者标识（取台次患者快照，不存证件号/手机号/住院号明文）';
COMMENT ON COLUMN m2_anes.anesthesia_record.status IS
    '记录单状态：DRAFT=草稿（未开始），ONGOING=进行中（首个 ANESTHESIA_START 后）；契约映射 DRAFT/IN_PROGRESS，锁单状态由 M3-M2-03 扩展';
COMMENT ON COLUMN m2_anes.anesthesia_record.anesthesia_type IS
    '麻醉方式：GENERAL/REGIONAL/COMBINED/LOCAL/SEDATION（未锁定可改）';
COMMENT ON COLUMN m2_anes.anesthesia_record.started_at IS '记录开始时间（首个 ANESTHESIA_START 事件时间）；DRAFT 为 NULL';
COMMENT ON COLUMN m2_anes.anesthesia_record.ended_at IS '记录结束时间（ANESTHESIA_END 事件时间）；未结束为 NULL';
COMMENT ON COLUMN m2_anes.anesthesia_record.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m2_anes.anesthesia_record.created_by IS '创建人用户标识';
COMMENT ON COLUMN m2_anes.anesthesia_record.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m2_anes.anesthesia_record.updated_by IS '最后修改人用户标识';
COMMENT ON COLUMN m2_anes.anesthesia_record.version IS '乐观锁版本号（从 1 起；更新必须回传，过期 409）';
COMMENT ON COLUMN m2_anes.anesthesia_record.deleted IS
    '逻辑删除标记（本期不提供删除接口；临床留痕不得物理删除）';

-- 记录单守卫（单向，M3-M2-01 复审 S-4）：
--   a) 身份列（id/schedule/application/patient/created_*）不可改；
--   b) 状态不得回退（DRAFT < ONGOING；锁单状态由 M3-M2-03 新迁移扩展时须同步本函数）；
--   c) 已写入的 started_at 不得被清空或改写；
--   d) version 只增（NEW.version 必须大于 OLD.version，防止绕过乐观锁静默覆盖）；
--   e) 已逻辑删除的行不可再改；DELETE/TRUNCATE 一律拒绝（临床记录不得物理删除）。
-- SQLSTATE 55000。
--
-- 运行时权限要求：以上守卫依赖 DDL 创建的函数/触发器，应用运行账号不得拥有 DDL 权限
-- （CREATE/ALTER/DROP），否则可先 ALTER TABLE ... DISABLE TRIGGER 再改写历史记录；
-- 迁移使用独立 DDL 账号，运行账号仅授业务所需 DML（详见 docs/db/README.md §5.3）。
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
        IF array_position(ARRAY['DRAFT', 'ONGOING']::text[], NEW.status::text)
           < array_position(ARRAY['DRAFT', 'ONGOING']::text[], OLD.status::text) THEN
            RAISE EXCEPTION
                'm2_anes.anesthesia_record status cannot go backwards: % -> %', OLD.status, NEW.status
                USING ERRCODE = '55000',
                      HINT = '记录单状态只允许向前流转（DRAFT→ONGOING）；锁单/修订由 M3-M2-03 扩展';
        END IF;
        IF OLD.started_at IS NOT NULL AND NEW.started_at IS DISTINCT FROM OLD.started_at THEN
            RAISE EXCEPTION
                'm2_anes.anesthesia_record started_at is write-once'
                USING ERRCODE = '55000',
                      HINT = '记录开始时间一旦写入不得清空或改写';
        END IF;
        IF NEW.version <= OLD.version THEN
            RAISE EXCEPTION
                'm2_anes.anesthesia_record version must increase: % -> %', OLD.version, NEW.version
                USING ERRCODE = '55000',
                      HINT = '记录单更新必须递增 version（乐观锁），不得静默覆盖';
        END IF;
        IF OLD.deleted THEN
            RAISE EXCEPTION
                'm2_anes.anesthesia_record is deleted: UPDATE is not permitted'
                USING ERRCODE = '55000',
                      HINT = '已删除记录单不可再修改';
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
    '记录单守卫（单向）：身份列不可变、状态不得回退、started_at 只写一次、version 只增；已删除行不可改；拒绝 DELETE/TRUNCATE（55000）';

CREATE TRIGGER trg_anesthesia_record_guard_update
    BEFORE UPDATE ON m2_anes.anesthesia_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_anesthesia_record_guard();

CREATE TRIGGER trg_anesthesia_record_guard_delete
    BEFORE DELETE ON m2_anes.anesthesia_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_anesthesia_record_guard();

CREATE TRIGGER trg_anesthesia_record_guard_truncate
    BEFORE TRUNCATE ON m2_anes.anesthesia_record
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_anesthesia_record_guard();

-- -----------------------------------------------------------------------------
-- 2. record_event 时间轴事件（append-only，seq 服务端分配）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.record_event (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    record_id    uuid         NOT NULL,
    seq          integer      NOT NULL,
    event_type   varchar(32)  NOT NULL,
    occurred_at  timestamptz  NOT NULL,
    source       varchar(16)  NOT NULL DEFAULT 'MANUAL',
    payload      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   uuid,
    CONSTRAINT pk_record_event PRIMARY KEY (id),
    CONSTRAINT fk_record_event_record FOREIGN KEY (record_id)
        REFERENCES m2_anes.anesthesia_record (id) ON DELETE RESTRICT,
    -- 同一记录单内序号唯一：并发追加竞争时由唯一索引拒绝，服务层重试为下一个 seq（不覆盖）。
    CONSTRAINT uk_record_event_record_seq UNIQUE (record_id, seq),
    CONSTRAINT ck_record_event_seq CHECK (seq >= 1),
    CONSTRAINT ck_record_event_type CHECK (event_type IN (
        'ANESTHESIA_START', 'INDUCTION', 'INTUBATION', 'INCISION',
        'CUSTOM', 'EXTUBATION', 'ANESTHESIA_END')),
    CONSTRAINT ck_record_event_source CHECK (source IN ('MANUAL', 'DEVICE')),
    CONSTRAINT ck_record_event_payload_object CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX idx_record_event_record_occurred
    ON m2_anes.record_event (record_id, occurred_at, seq);
CREATE INDEX idx_record_event_type ON m2_anes.record_event (event_type);
CREATE INDEX idx_record_event_created_at ON m2_anes.record_event (created_at);

COMMENT ON TABLE m2_anes.record_event IS
    '麻醉时间轴事件：append-only（UPDATE/DELETE/TRUNCATE 拒绝，55000）；seq 服务端按记录单分配，occurred_at 为事件实际时间';
COMMENT ON COLUMN m2_anes.record_event.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.record_event.record_id IS '所属麻醉记录单（FK；数据范围沿记录单）';
COMMENT ON COLUMN m2_anes.record_event.seq IS
    '记录单内创建序号（从 1 起；同一 occurred_at 按 seq 升序，保证时间轴稳定）；append-only';
COMMENT ON COLUMN m2_anes.record_event.event_type IS
    '事件类型：ANESTHESIA_START/INDUCTION/INTUBATION/INCISION/CUSTOM/EXTUBATION/ANESTHESIA_END';
COMMENT ON COLUMN m2_anes.record_event.occurred_at IS
    '事件实际发生时间（不得早于记录开始时间；服务层校验 400）';
COMMENT ON COLUMN m2_anes.record_event.source IS '数据来源：MANUAL=手工录入，DEVICE=设备采集（M3-M8-01）';
COMMENT ON COLUMN m2_anes.record_event.payload IS
    '结构化明细（jsonb 对象）：note/entryReason 等；不得写入患者直接标识';
COMMENT ON COLUMN m2_anes.record_event.created_at IS '录入时间（服务端时间，与 occurred_at 分离）';
COMMENT ON COLUMN m2_anes.record_event.created_by IS '录入人用户标识';

CREATE OR REPLACE FUNCTION m2_anes.fn_record_event_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'm2_anes.record_event is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '时间轴事件不可修改或删除；修正请新增事件并填写 entryReason';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_record_event_append_only() IS
    '时间轴事件 append-only 触发器函数：拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000';

CREATE TRIGGER trg_record_event_no_update
    BEFORE UPDATE ON m2_anes.record_event
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_record_event_append_only();

CREATE TRIGGER trg_record_event_no_delete
    BEFORE DELETE ON m2_anes.record_event
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_record_event_append_only();

CREATE TRIGGER trg_record_event_no_truncate
    BEFORE TRUNCATE ON m2_anes.record_event
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_record_event_append_only();

-- -----------------------------------------------------------------------------
-- 3. drug_administration 用药记录（append-only，麻精双人核对必填）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.drug_administration (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    record_id          uuid          NOT NULL,
    drug_name          varchar(200)  NOT NULL,
    dose               numeric(10,3) NOT NULL,
    unit               varchar(16)   NOT NULL,
    route              varchar(32)   NOT NULL,
    administered_at    timestamptz   NOT NULL,
    narcotic           boolean       NOT NULL DEFAULT false,
    second_checker_id  uuid,
    second_checker_name varchar(64),
    note               varchar(500),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         uuid,
    CONSTRAINT pk_drug_administration PRIMARY KEY (id),
    CONSTRAINT fk_drug_administration_record FOREIGN KEY (record_id)
        REFERENCES m2_anes.anesthesia_record (id) ON DELETE RESTRICT,
    CONSTRAINT ck_drug_administration_dose CHECK (dose > 0),
    CONSTRAINT ck_drug_administration_name_length
        CHECK (char_length(drug_name) BETWEEN 1 AND 200),
    -- 麻精类必须双人核对：核对人标识与姓名同时必填；非麻精类两者均为空。
    CONSTRAINT ck_drug_administration_narcotic_checker CHECK (
        (narcotic = false AND second_checker_id IS NULL AND second_checker_name IS NULL)
        OR (narcotic = true AND second_checker_id IS NOT NULL AND second_checker_name IS NOT NULL)
    ),
    -- 同人核对拒绝：核对人不得与给药人（created_by）相同（服务层先 400，数据库为第二道防线）。
    CONSTRAINT ck_drug_administration_second_checker_distinct
        CHECK (second_checker_id IS NULL OR created_by IS NULL OR second_checker_id <> created_by)
);

CREATE INDEX idx_drug_administration_record_time
    ON m2_anes.drug_administration (record_id, administered_at DESC);
CREATE INDEX idx_drug_administration_narcotic
    ON m2_anes.drug_administration (record_id) WHERE narcotic = true;
CREATE INDEX idx_drug_administration_created_at ON m2_anes.drug_administration (created_at);

COMMENT ON TABLE m2_anes.drug_administration IS
    '用药记录：append-only；麻精类 narcotic=true 必须双人核对（second_checker_id/name），同人核对被拒绝';
COMMENT ON COLUMN m2_anes.drug_administration.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.drug_administration.record_id IS '所属麻醉记录单（FK；数据范围沿记录单）';
COMMENT ON COLUMN m2_anes.drug_administration.drug_name IS
    '药品名称（院内字典名，不含患者标识；审计可保留用于追溯）';
COMMENT ON COLUMN m2_anes.drug_administration.dose IS '剂量（numeric(10,3)，必须大于 0）';
COMMENT ON COLUMN m2_anes.drug_administration.unit IS '剂量单位（mg/ug/ml 等）';
COMMENT ON COLUMN m2_anes.drug_administration.route IS '给药途径（IV/INHALATION/EPIDURAL 等）';
COMMENT ON COLUMN m2_anes.drug_administration.administered_at IS '给药时间（临床时间，与录入时间分离）';
COMMENT ON COLUMN m2_anes.drug_administration.narcotic IS
    '是否麻精类药品：true 时双人核对字段必填，且核对人不得与给药人相同';
COMMENT ON COLUMN m2_anes.drug_administration.second_checker_id IS
    '第二核对人用户标识；麻精类必填，非麻精类为 NULL';
COMMENT ON COLUMN m2_anes.drug_administration.second_checker_name IS
    '第二核对人姓名（只落库，不写入日志/审计原文）；麻精类必填';
COMMENT ON COLUMN m2_anes.drug_administration.note IS '备注（≤500 字符；可为空，不得含患者直接标识）';
COMMENT ON COLUMN m2_anes.drug_administration.created_at IS '录入时间（服务端时间）';
COMMENT ON COLUMN m2_anes.drug_administration.created_by IS '给药人用户标识（当前登录用户；核对人不得与其相同）';

CREATE OR REPLACE FUNCTION m2_anes.fn_drug_administration_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'm2_anes.drug_administration is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '用药记录不可修改或删除；更正请新增记录并在备注说明';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_drug_administration_append_only() IS
    '用药记录 append-only 触发器函数：拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000';

CREATE TRIGGER trg_drug_administration_no_update
    BEFORE UPDATE ON m2_anes.drug_administration
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_drug_administration_append_only();

CREATE TRIGGER trg_drug_administration_no_delete
    BEFORE DELETE ON m2_anes.drug_administration
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_drug_administration_append_only();

CREATE TRIGGER trg_drug_administration_no_truncate
    BEFORE TRUNCATE ON m2_anes.drug_administration
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_drug_administration_append_only();

-- -----------------------------------------------------------------------------
-- 4. fluid_record 输液/出入量明细（append-only）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.fluid_record (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    record_id   uuid         NOT NULL,
    direction   varchar(8)   NOT NULL,
    fluid_type  varchar(64)  NOT NULL,
    amount_ml   integer      NOT NULL,
    recorded_at timestamptz  NOT NULL,
    note        varchar(200),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid,
    CONSTRAINT pk_fluid_record PRIMARY KEY (id),
    CONSTRAINT fk_fluid_record_record FOREIGN KEY (record_id)
        REFERENCES m2_anes.anesthesia_record (id) ON DELETE RESTRICT,
    CONSTRAINT ck_fluid_record_direction CHECK (direction IN ('INTAKE', 'OUTPUT')),
    CONSTRAINT ck_fluid_record_amount CHECK (amount_ml > 0),
    CONSTRAINT ck_fluid_record_type_length
        CHECK (char_length(fluid_type) BETWEEN 1 AND 64)
);

CREATE INDEX idx_fluid_record_record_time
    ON m2_anes.fluid_record (record_id, recorded_at DESC);
CREATE INDEX idx_fluid_record_direction ON m2_anes.fluid_record (record_id, direction);
CREATE INDEX idx_fluid_record_created_at ON m2_anes.fluid_record (created_at);

COMMENT ON TABLE m2_anes.fluid_record IS
    '输液/出入量明细：append-only；入量/出量按 direction 区分，汇总由服务端按明细计算（不落冗余合计列）';
COMMENT ON COLUMN m2_anes.fluid_record.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.fluid_record.record_id IS '所属麻醉记录单（FK；数据范围沿记录单）';
COMMENT ON COLUMN m2_anes.fluid_record.direction IS '方向：INTAKE=入量，OUTPUT=出量（尿量/引流量/失血等）';
COMMENT ON COLUMN m2_anes.fluid_record.fluid_type IS '液体/出量类型（晶体液、胶体液、尿量等，≤64 字符）';
COMMENT ON COLUMN m2_anes.fluid_record.amount_ml IS '量（毫升，必须大于 0）';
COMMENT ON COLUMN m2_anes.fluid_record.recorded_at IS '记录时间（临床时间，缺省取服务端时间）';
COMMENT ON COLUMN m2_anes.fluid_record.note IS '备注（≤200 字符；可为空）';
COMMENT ON COLUMN m2_anes.fluid_record.created_at IS '录入时间（服务端时间）';
COMMENT ON COLUMN m2_anes.fluid_record.created_by IS '录入人用户标识';

CREATE OR REPLACE FUNCTION m2_anes.fn_fluid_record_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'm2_anes.fluid_record is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '出入量明细不可修改或删除；更正请新增明细并备注说明';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_fluid_record_append_only() IS
    '出入量明细 append-only 触发器函数：拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000';

CREATE TRIGGER trg_fluid_record_no_update
    BEFORE UPDATE ON m2_anes.fluid_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_fluid_record_append_only();

CREATE TRIGGER trg_fluid_record_no_delete
    BEFORE DELETE ON m2_anes.fluid_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_fluid_record_append_only();

CREATE TRIGGER trg_fluid_record_no_truncate
    BEFORE TRUNCATE ON m2_anes.fluid_record
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_fluid_record_append_only();

-- -----------------------------------------------------------------------------
-- 5. 权限点 m2:record:read|write
--    授予 SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST：
--    麻醉记录单由麻醉医师/麻醉护士书写，系统管理员用于运维与排障。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort) VALUES
    ('b0000000-0000-4000-8000-000000000031', 'm2:record:read', 'M2 麻醉记录单只读', 'API',
     '/api/v1/anesthesia/records', 'GET', NULL, 136),
    ('b0000000-0000-4000-8000-000000000032', 'm2:record:write', 'M2 麻醉记录单书写', 'API',
     '/api/v1/anesthesia/records', 'POST', NULL, 137);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：读写（运维/排障）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000031'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000032'),
    -- SYS_ANESTHESIOLOGIST：记录单书写人
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000031'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000032'),
    -- SYS_NURSE_ANESTHETIST：记录单书写人（用药/出入量/麻精核对）
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000031'),
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000032');
