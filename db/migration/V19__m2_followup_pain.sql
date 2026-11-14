-- =============================================================================
-- ANESOP 数据库迁移 V19：M2 术后随访、镇痛与并发症（M3-M11-01）
-- 目标库：PostgreSQL 16；schema：m2_anes
--
-- 设计依据：
--   方案书 §2.4.3（术后随访：24/48 小时随访、镇痛/PONV/并发症、PCA 与神经阻滞随访）、
--   §3.4.2（随访记录实体）、§3.4.3（审计字段/乐观锁）、§3.9.2（RBAC/数据权限）、
--   §3.9.3（脱敏）
--   契约：packages/contracts/openapi/anesthesia.yaml 0.10.0
--   任务卡：docs/ai/tasks/M3-backlog.md M3-M11-01（术后随访与镇痛）
--   ADR：docs/adr/0016-麻醉记录与采集接入口径.md（记录不可变/审计留痕原则）
--
-- 内容（expand 段：只新增表/索引/约束/触发器/权限点，不改写 V1–V18 既有列与数据）：
--   1) m2_anes.followup_record 术后随访单：所属麻醉记录单（FK）、随访类型 H24/H48、
--      随访时间、NRS 疼痛评分 0–10、PONV 评分 0–3、并发症多选（jsonb 数组）、
--      并发症说明与患者反馈（脱敏后文本）、随访人、审计字段与 version；append-only。
--   2) m2_anes.pain_management 镇痛记录：所属随访单（FK）、镇痛方式
--      PCIA/PCEA/ORAL/IV/NERVE_BLOCK/OTHER、药物、bolus/background 剂量、
--      lockout 分钟、效果、调整记录（jsonb 数组）、审计字段；append-only。
--   3) 随访前置守卫（BEFORE INSERT，55000）：父麻醉记录单必须存在、patient_id 与父记录
--      一致、已结束（ended_at 非空），且 assessed_at ≥ ended_at；随访只能在术后录入。
--   4) append-only 守卫：两张表 UPDATE/DELETE/TRUNCATE 一律拒绝（55000），
--      更正只能新增记录（随访按时间序列追加，镇痛按次数追加）。
--   5) 权限点 m2:followup:read、m2:followup:write（三段式编码）：授 SYS_ADMIN、
--      SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST（与记录单/PACU 同口径）。
--
-- 临床口径与安全约定：
--   - **随访窗口（H24/H48 允许的提前/延后容差）与超时提醒未实现**：本期只按类型记录、
--     要求同一记录同一类型的随访时间不早于上一条（服务层 400），窗口口径【待确认】
--     （见 docs/modules/m2/README.md §16.9 Q-1）。
--   - **PCA 参数（bolus/background/lockout）默认值与单位未内置**：只做物理校验
--     （bolus > 0、background ≥ 0、lockout > 0 分钟），默认方案由临床/设备科录入时决定
--     【待确认】（§16.9 Q-2）。系统不推荐参数，不替代医师处方（AGENTS H-06）。
--   - **并发症字典为演示口径【待确认】**（§16.9 Q-3）：jsonb 数组元素取值由服务层白名单
--     校验，数据库只校验数组类型与长度；院方字典确认后以新增迁移扩展白名单。
--   - **脱敏**：随访说明与患者反馈文本由服务层脱敏（手机号/身份证号形似串遮蔽）后落库；
--     只落库/授权用户可见，不写入日志与审计原文。PONV 评分 0–3 与 NRS 0–10 由服务层
--     校验，数据库 CHECK 为第二道防线；量表口径【待确认】（§16.9 Q-4）。
--   - 无 PHI：两张表只存 patient_id 标识与结构化字段；随访人存姓名摘要（≤64），
--     不含患者姓名/证件号/住院号。
--
-- 运行时权限要求：append-only 与前置守卫依赖触发器与函数，应用运行账号不得拥有 DDL
-- 权限（CREATE/ALTER/DROP），否则可先 ALTER TABLE ... DISABLE TRIGGER 再改写临床留痕；
-- 迁移使用独立 DDL 账号，运行账号仅授业务所需 DML（详见 docs/db/README.md §5.3）。
--
-- 迁移纪律：本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.14）：
--   1) 应用回滚：下线 /v1/anesthesia/followups* 端点与前端随访页面入口；记录单/PACU
--      仍可读写（两张新增表保留在库中，不影响旧代码）。随访与镇痛审计保留。
--   2) 库层前向修复（需 DBA 审批 + 备份 + 临床归档，顺序不可颠倒）：
--      DROP TRIGGER IF EXISTS trg_followup_record_no_update ON m2_anes.followup_record;
--      DROP TRIGGER IF EXISTS trg_followup_record_no_delete ON m2_anes.followup_record;
--      DROP TRIGGER IF EXISTS trg_followup_record_no_truncate ON m2_anes.followup_record;
--      DROP TRIGGER IF EXISTS trg_followup_record_guard_insert ON m2_anes.followup_record;
--      DROP FUNCTION IF EXISTS m2_anes.fn_followup_record_append_only();
--      DROP FUNCTION IF EXISTS m2_anes.fn_followup_record_no_truncate();
--      DROP FUNCTION IF EXISTS m2_anes.fn_followup_record_guard_insert();
--      DROP TRIGGER IF EXISTS trg_pain_management_no_update
--          ON m2_anes.pain_management;
--      DROP TRIGGER IF EXISTS trg_pain_management_no_delete
--          ON m2_anes.pain_management;
--      DROP TRIGGER IF EXISTS trg_pain_management_no_truncate
--          ON m2_anes.pain_management;
--      DROP FUNCTION IF EXISTS m2_anes.fn_pain_management_append_only();
--      DROP FUNCTION IF EXISTS m2_anes.fn_pain_management_no_truncate();
--      DROP TABLE m2_anes.pain_management;
--      DROP TABLE m2_anes.followup_record;
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id IN ('b0000000-0000-4000-8000-000000000065',
--                                'b0000000-0000-4000-8000-000000000066');
--      DELETE FROM m0_platform.sys_permission
--        WHERE code IN ('m2:followup:read', 'm2:followup:write');
--      回滚前必须导出两张表并校验行数；随访与镇痛属临床留痕，须先归档到离线审计库，
--      生产执行须人签字（不可逆脚本）。
--   3) 随访与镇痛记录不得回灌（append-only 语义）：如需恢复，按原标识/原时间重新录入并
--      在说明中注明补录原因（须临床确认）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. followup_record 术后随访单（24/48 小时随访）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.followup_record (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    anesthesia_record_id uuid        NOT NULL,
    patient_id           uuid        NOT NULL,
    followup_type        varchar(8)  NOT NULL,
    assessed_at          timestamptz NOT NULL,
    nrs_score            smallint    NOT NULL,
    ponv_score           smallint    NOT NULL,
    complications        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    complication_notes   varchar(500),
    patient_feedback     varchar(500),
    followed_up_by       varchar(64) NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    created_by           uuid,
    version              bigint      NOT NULL DEFAULT 1,
    CONSTRAINT pk_followup_record PRIMARY KEY (id),
    CONSTRAINT fk_followup_record_anesthesia_record FOREIGN KEY (anesthesia_record_id)
        REFERENCES m2_anes.anesthesia_record (id) ON DELETE RESTRICT,
    CONSTRAINT ck_followup_record_type CHECK (followup_type IN ('H24', 'H48')),
    CONSTRAINT ck_followup_record_nrs CHECK (nrs_score BETWEEN 0 AND 10),
    CONSTRAINT ck_followup_record_ponv CHECK (ponv_score BETWEEN 0 AND 3),
    CONSTRAINT ck_followup_record_complications
        CHECK (jsonb_typeof(complications) = 'array' AND jsonb_array_length(complications) <= 20),
    CONSTRAINT ck_followup_record_complication_notes
        CHECK (complication_notes IS NULL OR length(complication_notes) <= 500),
    CONSTRAINT ck_followup_record_feedback
        CHECK (patient_feedback IS NULL OR length(patient_feedback) <= 500),
    CONSTRAINT ck_followup_record_followed_up_by
        CHECK (btrim(followed_up_by) <> '' AND length(followed_up_by) <= 64),
    CONSTRAINT ck_followup_record_version CHECK (version >= 1)
);

CREATE INDEX idx_followup_record_record_type_time
    ON m2_anes.followup_record (anesthesia_record_id, followup_type, assessed_at DESC);
CREATE INDEX idx_followup_record_patient_id ON m2_anes.followup_record (patient_id);
CREATE INDEX idx_followup_record_created_at ON m2_anes.followup_record (created_at);

COMMENT ON TABLE m2_anes.followup_record IS
    '术后随访单（24/48 小时）：父记录单必须已结束且 patient_id 一致，assessed_at ≥ ended_at；'
    'append-only'
    '（UPDATE/DELETE/TRUNCATE 拒绝，55000）；并发症/反馈文本脱敏后落库';
COMMENT ON COLUMN m2_anes.followup_record.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.followup_record.anesthesia_record_id IS
    '所属麻醉记录单（FK）；数据范围沿记录单/台次可见性，越界 404/422';
COMMENT ON COLUMN m2_anes.followup_record.patient_id IS
    '患者标识（由记录单解析，不存姓名/证件号/住院号明文）';
COMMENT ON COLUMN m2_anes.followup_record.followup_type IS
    '随访类型：H24=术后 24 小时，H48=术后 48 小时；窗口容差待确认（Q-1）';
COMMENT ON COLUMN m2_anes.followup_record.assessed_at IS
    '随访评估时间（≥ 记录 ended_at；同记录同类型不得早于上一条；不得晚于服务端当前时间+容差）';
COMMENT ON COLUMN m2_anes.followup_record.nrs_score IS
    'NRS 疼痛评分 0–10（0 无痛、10 剧痛；量表口径【待确认】）';
COMMENT ON COLUMN m2_anes.followup_record.ponv_score IS
    'PONV 评分 0–3（演示口径【待确认】：0 无、1 轻度恶心、2 呕吐、3 严重呕吐）';
COMMENT ON COLUMN m2_anes.followup_record.complications IS
    '并发症多选 jsonb 数组（白名单见契约 FollowupComplication，演示字典【待确认】）';
COMMENT ON COLUMN m2_anes.followup_record.complication_notes IS
    '并发症自由文本说明（≤500；服务层脱敏后落库，不写日志/审计原文）';
COMMENT ON COLUMN m2_anes.followup_record.patient_feedback IS
    '患者反馈（≤500；服务层脱敏后落库，只落库/授权用户可见，不写审计原文）';
COMMENT ON COLUMN m2_anes.followup_record.followed_up_by IS
    '随访人姓名摘要（≤64；操作人另记 created_by）';
COMMENT ON COLUMN m2_anes.followup_record.created_at IS '录入时间（服务端时间）';
COMMENT ON COLUMN m2_anes.followup_record.created_by IS '录入人用户标识';
COMMENT ON COLUMN m2_anes.followup_record.version IS '版本号（append-only 恒为 1，保留通用审计字段）';

-- -----------------------------------------------------------------------------
-- 2. pain_management 镇痛记录（PCA/口服/静脉/神经阻滞随访）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.pain_management (
    id               uuid          NOT NULL DEFAULT gen_random_uuid(),
    followup_id      uuid          NOT NULL,
    modality         varchar(16)   NOT NULL,
    drug             varchar(128)  NOT NULL,
    bolus_dose       numeric(10,2) NOT NULL,
    background_dose  numeric(10,2) NOT NULL DEFAULT 0,
    lockout_minutes  integer       NOT NULL,
    effect           varchar(16)   NOT NULL,
    adjustments      jsonb         NOT NULL DEFAULT '[]'::jsonb,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       uuid,
    version          bigint        NOT NULL DEFAULT 1,
    CONSTRAINT pk_pain_management PRIMARY KEY (id),
    CONSTRAINT fk_pain_management_followup FOREIGN KEY (followup_id)
        REFERENCES m2_anes.followup_record (id) ON DELETE RESTRICT,
    CONSTRAINT ck_pain_management_modality CHECK (modality IN (
        'PCIA', 'PCEA', 'ORAL', 'IV', 'NERVE_BLOCK', 'OTHER')),
    CONSTRAINT ck_pain_management_effect CHECK (effect IN (
        'EFFECTIVE', 'PARTIAL', 'INEFFECTIVE', 'NOT_ASSESSED')),
    CONSTRAINT ck_pain_management_drug
        CHECK (btrim(drug) <> '' AND length(drug) <= 128),
    -- 剂量单位由录入口径决定（mg/μg/ml）【待确认】；此处只做物理校验，不设临床阈值。
    CONSTRAINT ck_pain_management_bolus CHECK (bolus_dose > 0),
    CONSTRAINT ck_pain_management_background CHECK (background_dose >= 0),
    CONSTRAINT ck_pain_management_lockout CHECK (lockout_minutes > 0),
    CONSTRAINT ck_pain_management_adjustments
        CHECK (jsonb_typeof(adjustments) = 'array' AND jsonb_array_length(adjustments) <= 20),
    CONSTRAINT ck_pain_management_version CHECK (version >= 1)
);

CREATE INDEX idx_pain_management_followup_time
    ON m2_anes.pain_management (followup_id, created_at, id);
CREATE INDEX idx_pain_management_created_at ON m2_anes.pain_management (created_at);

COMMENT ON TABLE m2_anes.pain_management IS
    '术后镇痛记录：方式/药物/bolus/background/lockout/效果/调整记录；append-only'
    '（UPDATE/DELETE/TRUNCATE 拒绝，55000）；同一次随访可多次追加（调整即新增记录）';
COMMENT ON COLUMN m2_anes.pain_management.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.pain_management.followup_id IS
    '所属随访单（FK）；随访不可见时按不存在处理（404）';
COMMENT ON COLUMN m2_anes.pain_management.modality IS
    '镇痛方式：PCIA/PCEA/ORAL/IV/NERVE_BLOCK/OTHER（演示口径【待确认】）';
COMMENT ON COLUMN m2_anes.pain_management.drug IS '药物名称（≤128，脱敏：不含患者标识）';
COMMENT ON COLUMN m2_anes.pain_management.bolus_dose IS
    '单次追加剂量（> 0；单位随药物口径【待确认】，不设临床阈值）';
COMMENT ON COLUMN m2_anes.pain_management.background_dose IS
    '背景输注剂量（≥ 0；单位随药物口径【待确认】）';
COMMENT ON COLUMN m2_anes.pain_management.lockout_minutes IS
    '锁定时间（分钟，> 0；默认值未内置【待确认】）';
COMMENT ON COLUMN m2_anes.pain_management.effect IS
    '镇痛效果：EFFECTIVE/PARTIAL/INEFFECTIVE/NOT_ASSESSED（演示口径【待确认】）';
COMMENT ON COLUMN m2_anes.pain_management.adjustments IS
    '调整记录 jsonb 数组：[{adjustedAt, description}]（自由文本脱敏后落库，不写审计原文）';
COMMENT ON COLUMN m2_anes.pain_management.created_at IS '录入时间（服务端时间）';
COMMENT ON COLUMN m2_anes.pain_management.created_by IS '录入人用户标识';
COMMENT ON COLUMN m2_anes.pain_management.version IS '版本号（append-only 恒为 1，保留通用审计字段）';

-- -----------------------------------------------------------------------------
-- 3. 随访前置守卫（BEFORE INSERT）：父记录单必须存在、patient_id 一致、已结束
--    且随访时间不早于结束时间
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION m2_anes.fn_followup_record_guard_insert()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_ended_at   timestamptz;
    v_patient_id uuid;
BEGIN
    SELECT ended_at, patient_id INTO v_ended_at, v_patient_id
      FROM m2_anes.anesthesia_record
     WHERE id = NEW.anesthesia_record_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'm2_anes.followup_record: 麻醉记录单不存在（%）', NEW.anesthesia_record_id
            USING ERRCODE = '23503';
    END IF;
    IF NEW.patient_id IS DISTINCT FROM v_patient_id THEN
        RAISE EXCEPTION 'm2_anes.followup_record: patient_id 与麻醉记录单不一致（%）',
            NEW.patient_id
            USING ERRCODE = '55000',
                  HINT = '随访患者必须与父麻醉记录单一致，服务端由记录单回填（评审 S-4）';
    END IF;
    IF v_ended_at IS NULL THEN
        RAISE EXCEPTION 'm2_anes.followup_record: 麻醉记录单尚未结束，不能录入术后随访'
            USING ERRCODE = '55000',
                  HINT = '随访只能在麻醉结束后进行；请先记录 ANESTHESIA_END';
    END IF;
    IF NEW.assessed_at < v_ended_at THEN
        RAISE EXCEPTION 'm2_anes.followup_record: 随访时间不得早于麻醉结束时间'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_followup_record_guard_insert() IS
    '随访前置守卫：父麻醉记录单必须存在、patient_id 与父记录一致、已结束（ended_at 非空）'
    '且 assessed_at ≥ ended_at，违反 55000（服务层 422/409/400 的第二道防线）';

CREATE TRIGGER trg_followup_record_guard_insert
    BEFORE INSERT ON m2_anes.followup_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_followup_record_guard_insert();

-- -----------------------------------------------------------------------------
-- 4. append-only 守卫（两张表 UPDATE/DELETE/TRUNCATE 一律 55000）
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION m2_anes.fn_followup_record_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'm2_anes.followup_record: 术后随访记录 append-only，不允许 %', TG_OP
        USING ERRCODE = '55000',
              HINT = '更正只能新增随访记录（按时间序列追加），不得改写历史';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_followup_record_append_only() IS
    '随访记录 append-only 守卫：UPDATE/DELETE 拒绝，SQLSTATE 55000';

CREATE OR REPLACE FUNCTION m2_anes.fn_followup_record_no_truncate()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'm2_anes.followup_record: TRUNCATE 不允许（临床记录不可物理删除）'
        USING ERRCODE = '55000';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_followup_record_no_truncate() IS
    '随访记录 TRUNCATE 守卫：拒绝物理清空，SQLSTATE 55000';

CREATE TRIGGER trg_followup_record_no_update
    BEFORE UPDATE ON m2_anes.followup_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_followup_record_append_only();

CREATE TRIGGER trg_followup_record_no_delete
    BEFORE DELETE ON m2_anes.followup_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_followup_record_append_only();

CREATE TRIGGER trg_followup_record_no_truncate
    BEFORE TRUNCATE ON m2_anes.followup_record
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_followup_record_no_truncate();

CREATE OR REPLACE FUNCTION m2_anes.fn_pain_management_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'm2_anes.pain_management: 镇痛记录 append-only，不允许 %', TG_OP
        USING ERRCODE = '55000',
              HINT = '参数调整只能新增镇痛记录，不得改写历史';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_pain_management_append_only() IS
    '镇痛记录 append-only 守卫：UPDATE/DELETE 拒绝，SQLSTATE 55000';

CREATE OR REPLACE FUNCTION m2_anes.fn_pain_management_no_truncate()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'm2_anes.pain_management: TRUNCATE 不允许（临床记录不可物理删除）'
        USING ERRCODE = '55000';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_pain_management_no_truncate() IS
    '镇痛记录 TRUNCATE 守卫：拒绝物理清空，SQLSTATE 55000';

CREATE TRIGGER trg_pain_management_no_update
    BEFORE UPDATE ON m2_anes.pain_management
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_pain_management_append_only();

CREATE TRIGGER trg_pain_management_no_delete
    BEFORE DELETE ON m2_anes.pain_management
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_pain_management_append_only();

CREATE TRIGGER trg_pain_management_no_truncate
    BEFORE TRUNCATE ON m2_anes.pain_management
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_pain_management_no_truncate();

-- -----------------------------------------------------------------------------
-- 5. 权限点 m2:followup:read、m2:followup:write
--    授 SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST（与记录单/PACU 同口径）；
--    随访是否允许其他角色（如病房护士）待护理部/麻醉科确认（§16.9 Q-5）。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-000000000065', 'm2:followup:read', 'M2 术后随访只读', 'API',
     '/api/v1/anesthesia/followups', 'GET', NULL, 142),
    ('b0000000-0000-4000-8000-000000000066', 'm2:followup:write', 'M2 术后随访与镇痛', 'API',
     '/api/v1/anesthesia/followups', 'POST', NULL, 143);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：读写（运维/排障）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000065'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000066'),
    -- SYS_ANESTHESIOLOGIST：随访与镇痛医疗责任人
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000065'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000066'),
    -- SYS_NURSE_ANESTHETIST：随访执行与记录
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000065'),
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000066');

-- -----------------------------------------------------------------------------
-- 6. 迁移自检（fail fast）
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF to_regclass('m2_anes.followup_record') IS NULL THEN
        RAISE EXCEPTION 'V19 迁移自检失败：followup_record 未创建';
    END IF;
    IF to_regclass('m2_anes.pain_management') IS NULL THEN
        RAISE EXCEPTION 'V19 迁移自检失败：pain_management 未创建';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_followup_record_nrs'
          AND conrelid = 'm2_anes.followup_record'::regclass
    ) THEN
        RAISE EXCEPTION 'V19 迁移自检失败：NRS 0–10 约束缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_followup_record_ponv'
          AND conrelid = 'm2_anes.followup_record'::regclass
    ) THEN
        RAISE EXCEPTION 'V19 迁移自检失败：PONV 0–3 约束缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_pain_management_bolus'
          AND conrelid = 'm2_anes.pain_management'::regclass
    ) THEN
        RAISE EXCEPTION 'V19 迁移自检失败：bolus 剂量约束缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_pain_management_lockout'
          AND conrelid = 'm2_anes.pain_management'::regclass
    ) THEN
        RAISE EXCEPTION 'V19 迁移自检失败：lockout 约束缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_followup_record_guard_insert'
          AND tgrelid = 'm2_anes.followup_record'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V19 迁移自检失败：随访前置守卫触发器缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_followup_record_no_update'
          AND tgrelid = 'm2_anes.followup_record'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V19 迁移自检失败：随访 append-only 触发器缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_pain_management_no_update'
          AND tgrelid = 'm2_anes.pain_management'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V19 迁移自检失败：镇痛 append-only 触发器缺失';
    END IF;
    IF (SELECT count(*) FROM m0_platform.sys_permission
         WHERE code IN ('m2:followup:read', 'm2:followup:write')) <> 2 THEN
        RAISE EXCEPTION 'V19 迁移自检失败：权限点 m2:followup:read/write 未全部写入';
    END IF;
    IF (SELECT count(*) FROM m0_platform.sys_role_permission rp
          JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
         WHERE p.code IN ('m2:followup:read', 'm2:followup:write')
           AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001',
                              'a0000000-0000-4000-8000-000000000002',
                              'a0000000-0000-4000-8000-000000000003')) <> 6 THEN
        RAISE EXCEPTION 'V19 迁移自检失败：随访权限未授予三个临床/管理角色';
    END IF;
END
$$;
