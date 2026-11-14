-- =============================================================================
-- ANESOP 数据库迁移 V18：M2 复苏记录、Aldrete 评估与转出交接（M3-M10-01）
-- 目标库：PostgreSQL 16；schema：m2_anes
--
-- 设计依据：
--   方案书 §2.4.3（术后复苏/PACU、Aldrete 评分、转出条件与交接）、§3.4.2（记录实体）、
--   §3.4.3（审计字段/乐观锁/逻辑删除）、§3.9.2（RBAC/数据权限）、§3.9.3（脱敏）
--   契约：packages/contracts/openapi/anesthesia.yaml 0.9.0
--   任务卡：docs/ai/tasks/M3-backlog.md M3-M10-01（复苏记录与转出）
--   ADR：docs/adr/0016-麻醉记录与采集接入口径.md（记录不可变/审计留痕原则）
--
-- 内容（expand 段：只新增表/索引/约束/触发器/权限点，不改写 V1–V17 既有列与数据）：
--   1) m2_anes.pacu_record 复苏记录：一条麻醉记录单同时最多一条未转出（部分唯一索引），
--      入室生命体征/意识/疼痛/恶心/气道、状态 ADMITTED/MONITORING/READY/DISCHARGED、
--      审计字段与乐观锁 version、逻辑删除 deleted。
--   2) m2_anes.pacu_assessment Aldrete 评估：activity/respiration/circulation/consciousness/
--      oxygenation 各 0–2，aldrete_total = 五项之和（0–10），append-only（UPDATE/DELETE/
--      TRUNCATE 拒绝，55000）；父记录已转出后禁止新增评估（BEFORE INSERT 触发器，55000）。
--   3) m2_anes.pacu_discharge 转出留痕：每条复苏记录最多一条（pacu_id 唯一），转出时间、
--      去向、SBAR 交接 jsonb、转出时 Aldrete 达标快照 jsonb；append-only。
--   4) 复苏记录守卫（BEFORE UPDATE/DELETE/TRUNCATE）：身份列与入室信息不可改、DISCHARGED
--      为终态（READY→MONITORING 回落是否允许待临床确认，Q-8）、discharged_at 写一次且必须与
--      DISCHARGED 同置、version 只增；DELETE/TRUNCATE 一律拒绝（55000）。
--   5) 权限点 m2:pacu:read、m2:pacu:write（三段式编码）：授 SYS_ADMIN、
--      SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST。PACU 专职护士角色（SYS_PACU_NURSE）
--      当前未落库种子，是否单独授权待临床/护理部确认（【待确认】）。
--
-- 临床口径与安全约定：
--   - Aldrete 五项分值与总分由**服务端**计算/校验，数据库 CHECK 为第二道防线
--     （每项 0–2、总分 0–10 且等于五项之和）；客户端提交的总分一律忽略（契约无该字段）。
--   - **转出阈值不写入数据库、不硬编码**：由配置 anesop.m2.pacu.aldrete-threshold（默认 9）
--     提供，院方口径【待确认】；转出时的阈值与总分以快照形式冻结在 criteria_snapshot，
--     便于阈值调整后追溯“当时依据”。
--   - 未处理严重告警是否阻断转出属临床规则，本期未实现（【待确认】，见
--     docs/modules/m2/README.md §15.9 Q-3），数据库与服务层均不做隐式阻断。
--   - 转出后不可再评估：服务层 409 + 数据库 BEFORE INSERT 触发器 55000 双保险。
--   - 无 PHI：三张表只存 patient_id 标识与结构化字段；SBAR 交接 text 只落库与授权用户可见，
--     不写入日志/审计；Aldrete 备注(notes)同理。
--   - 不物理删除：评估与转出永久 append-only；pacu_record 仍为逻辑删除（本期无删除接口）。
--
-- 运行时权限要求：append-only 与状态守卫依赖触发器与函数，应用运行账号不得拥有 DDL 权限
-- （CREATE/ALTER/DROP），否则可先 ALTER TABLE ... DISABLE TRIGGER 再改写临床留痕；
-- 迁移使用独立 DDL 账号，运行账号仅授业务所需 DML（详见 docs/db/README.md §5.3）。
--
-- 迁移纪律：本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.13）：
--   1) 应用回滚：下线 /v1/anesthesia/pacu-records* 端点与前端 PACU 页面入口；记录单仍可读写
--      （三张新增表保留在库中，不影响旧代码）。PACU 审计保留。
--   2) 库层前向修复（需 DBA 审批 + 备份 + 临床归档，顺序不可颠倒）：
--      DROP TRIGGER IF EXISTS trg_pacu_record_no_delete ON m2_anes.pacu_record;
--      DROP TRIGGER IF EXISTS trg_pacu_record_no_truncate ON m2_anes.pacu_record;
--      DROP TRIGGER IF EXISTS trg_pacu_record_guard_update ON m2_anes.pacu_record;
--      DROP FUNCTION IF EXISTS m2_anes.fn_pacu_record_guard();
--      DROP TRIGGER IF EXISTS trg_pacu_assessment_discharged_insert
--          ON m2_anes.pacu_assessment;
--      DROP FUNCTION IF EXISTS m2_anes.fn_pacu_assessment_guard();
--      DROP TABLE m2_anes.pacu_discharge;
--      DROP FUNCTION IF EXISTS m2_anes.fn_pacu_discharge_append_only();
--      DROP TABLE m2_anes.pacu_assessment;
--      DROP FUNCTION IF EXISTS m2_anes.fn_pacu_assessment_append_only();
--      DROP TABLE m2_anes.pacu_record;
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id IN ('b0000000-0000-4000-8000-000000000063',
--                                'b0000000-0000-4000-8000-000000000064');
--      DELETE FROM m0_platform.sys_permission
--        WHERE code IN ('m2:pacu:read', 'm2:pacu:write');
--      回滚前必须导出三张表并校验行数；Aldrete 评估与转出交接属临床留痕，须先归档到
--      离线审计库，生产执行须人签字（不可逆脚本）。
--   3) 评估与转出记录不得回灌（append-only 语义）：如需恢复，按原标识/原时间重新录入并
--      在评估备注说明补录原因（须临床确认）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. pacu_record 复苏记录（一条麻醉记录单同时最多一条未转出）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.pacu_record (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    anesthesia_record_id uuid        NOT NULL,
    patient_id           uuid        NOT NULL,
    admitted_at          timestamptz NOT NULL,
    discharged_at        timestamptz,
    status               varchar(16) NOT NULL DEFAULT 'ADMITTED',
    admitted_vital_signs jsonb       NOT NULL DEFAULT '{}'::jsonb,
    consciousness        varchar(16) NOT NULL,
    pain_score           smallint    NOT NULL,
    nausea               boolean     NOT NULL,
    airway               varchar(24) NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    updated_by           uuid,
    version              bigint      NOT NULL DEFAULT 1,
    deleted              boolean     NOT NULL DEFAULT false,
    CONSTRAINT pk_pacu_record PRIMARY KEY (id),
    CONSTRAINT fk_pacu_record_anesthesia_record FOREIGN KEY (anesthesia_record_id)
        REFERENCES m2_anes.anesthesia_record (id) ON DELETE RESTRICT,
    CONSTRAINT ck_pacu_record_status
        CHECK (status IN ('ADMITTED', 'MONITORING', 'READY', 'DISCHARGED')),
    CONSTRAINT ck_pacu_record_consciousness
        CHECK (consciousness IN ('ALERT', 'DROWSY', 'SEDATED', 'UNRESPONSIVE')),
    CONSTRAINT ck_pacu_record_airway CHECK (airway IN (
        'NATURAL', 'ORAL_AIRWAY', 'NASAL_AIRWAY', 'LARYNGEAL_MASK', 'ENDOTRACHEAL',
        'TRACHEOSTOMY')),
    CONSTRAINT ck_pacu_record_pain_score CHECK (pain_score BETWEEN 0 AND 10),
    CONSTRAINT ck_pacu_record_version CHECK (version >= 1),
    CONSTRAINT ck_pacu_record_vitals CHECK (jsonb_typeof(admitted_vital_signs) = 'object'),
    CONSTRAINT ck_pacu_record_time_window
        CHECK (discharged_at IS NULL OR discharged_at >= admitted_at),
    -- 转出状态与转出时间必须同置：DISCHARGED <=> discharged_at 非空。
    CONSTRAINT ck_pacu_record_discharged_state
        CHECK ((status = 'DISCHARGED') = (discharged_at IS NOT NULL))
);

-- 一条麻醉记录单同时最多一条未转出记录（转出后可再次入室；并发入室由唯一索引兜底）。
CREATE UNIQUE INDEX uk_pacu_record_active
    ON m2_anes.pacu_record (anesthesia_record_id)
    WHERE status <> 'DISCHARGED' AND deleted = false;
CREATE INDEX idx_pacu_record_anesthesia_record
    ON m2_anes.pacu_record (anesthesia_record_id, created_at DESC);
CREATE INDEX idx_pacu_record_patient_id ON m2_anes.pacu_record (patient_id);
CREATE INDEX idx_pacu_record_status ON m2_anes.pacu_record (status);
CREATE INDEX idx_pacu_record_created_at ON m2_anes.pacu_record (created_at);
CREATE INDEX idx_pacu_record_deleted ON m2_anes.pacu_record (deleted);

COMMENT ON TABLE m2_anes.pacu_record IS
    'PACU 复苏记录：一条麻醉记录单同时最多一条未转出（uk_pacu_record_active）；'
    '状态 ADMITTED/MONITORING/READY/DISCHARGED，入室信息与身份列写入后不可改';
COMMENT ON COLUMN m2_anes.pacu_record.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.pacu_record.anesthesia_record_id IS
    '所属麻醉记录单（FK）；数据范围沿记录单/台次可见性，越界 404';
COMMENT ON COLUMN m2_anes.pacu_record.patient_id IS
    '患者标识（由记录单解析，不存姓名/证件号/住院号明文）';
COMMENT ON COLUMN m2_anes.pacu_record.admitted_at IS
    '入 PACU 时间（不得早于记录单开始时间；服务层校验 400）';
COMMENT ON COLUMN m2_anes.pacu_record.discharged_at IS
    '转出时间（服务端时间，写一次；与 status=DISCHARGED 同置）';
COMMENT ON COLUMN m2_anes.pacu_record.status IS
    '复苏状态：ADMITTED=已入室，MONITORING=监测中，READY=达转出阈值（可回落待确认 Q-8），'
    'DISCHARGED=已转出（终态）';
COMMENT ON COLUMN m2_anes.pacu_record.admitted_vital_signs IS
    '入室生命体征 jsonb：{systolicBp,diastolicBp,heartRate,spo2,respiratoryRate,temperature}（可缺项）';
COMMENT ON COLUMN m2_anes.pacu_record.consciousness IS
    '入室意识：ALERT/DROWSY/SEDATED/UNRESPONSIVE（演示口径【待确认】）';
COMMENT ON COLUMN m2_anes.pacu_record.pain_score IS '入室疼痛评分 NRS 0–10（量表口径【待确认】）';
COMMENT ON COLUMN m2_anes.pacu_record.nausea IS '入室是否存在恶心/呕吐';
COMMENT ON COLUMN m2_anes.pacu_record.airway IS
    '入室气道：NATURAL/ORAL_AIRWAY/NASAL_AIRWAY/LARYNGEAL_MASK/ENDOTRACHEAL/TRACHEOSTOMY（演示口径【待确认】）';
COMMENT ON COLUMN m2_anes.pacu_record.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m2_anes.pacu_record.created_by IS '创建人用户标识';
COMMENT ON COLUMN m2_anes.pacu_record.updated_at IS '最后更新时间（状态推进/转出）';
COMMENT ON COLUMN m2_anes.pacu_record.updated_by IS '最后修改人用户标识';
COMMENT ON COLUMN m2_anes.pacu_record.version IS '乐观锁版本号（只增）';
COMMENT ON COLUMN m2_anes.pacu_record.deleted IS '逻辑删除标记（本期无删除接口）';

-- -----------------------------------------------------------------------------
-- 2. pacu_assessment Aldrete 评估（append-only 时间序列）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.pacu_assessment (
    id                      uuid        NOT NULL DEFAULT gen_random_uuid(),
    pacu_id                 uuid        NOT NULL,
    assessed_at             timestamptz NOT NULL,
    aldrete_activity        smallint    NOT NULL,
    aldrete_respiration     smallint    NOT NULL,
    aldrete_circulation     smallint    NOT NULL,
    aldrete_consciousness   smallint    NOT NULL,
    aldrete_oxygenation     smallint    NOT NULL,
    aldrete_total           smallint    NOT NULL,
    notes                   varchar(500),
    created_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    CONSTRAINT pk_pacu_assessment PRIMARY KEY (id),
    CONSTRAINT fk_pacu_assessment_pacu FOREIGN KEY (pacu_id)
        REFERENCES m2_anes.pacu_record (id) ON DELETE RESTRICT,
    CONSTRAINT ck_pacu_assessment_activity CHECK (aldrete_activity BETWEEN 0 AND 2),
    CONSTRAINT ck_pacu_assessment_respiration CHECK (aldrete_respiration BETWEEN 0 AND 2),
    CONSTRAINT ck_pacu_assessment_circulation CHECK (aldrete_circulation BETWEEN 0 AND 2),
    CONSTRAINT ck_pacu_assessment_consciousness CHECK (aldrete_consciousness BETWEEN 0 AND 2),
    CONSTRAINT ck_pacu_assessment_oxygenation CHECK (aldrete_oxygenation BETWEEN 0 AND 2),
    CONSTRAINT ck_pacu_assessment_total CHECK (aldrete_total BETWEEN 0 AND 10),
    -- 总分必须等于五项之和（服务端计算，数据库第二道防线）。
    CONSTRAINT ck_pacu_assessment_total_sum CHECK (aldrete_total =
        aldrete_activity + aldrete_respiration + aldrete_circulation
        + aldrete_consciousness + aldrete_oxygenation),
    CONSTRAINT ck_pacu_assessment_notes CHECK (notes IS NULL OR length(notes) <= 500)
);

CREATE INDEX idx_pacu_assessment_pacu_time
    ON m2_anes.pacu_assessment (pacu_id, assessed_at, id);
CREATE INDEX idx_pacu_assessment_created_at ON m2_anes.pacu_assessment (created_at);

COMMENT ON TABLE m2_anes.pacu_assessment IS
    'Aldrete 评估：五项各 0–2、总分 0–10 且等于五项之和；append-only（UPDATE/DELETE/TRUNCATE 拒绝，55000）';
COMMENT ON COLUMN m2_anes.pacu_assessment.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.pacu_assessment.pacu_id IS
    '所属复苏记录（FK）；父记录已转出后拒绝新增评估（55000）';
COMMENT ON COLUMN m2_anes.pacu_assessment.assessed_at IS
    '评估时间（不得早于入室时间与上一条评估，不得晚于服务端当前时间+容差；服务层校验 400）';
COMMENT ON COLUMN m2_anes.pacu_assessment.aldrete_activity IS
    '活动能力 0–2（0 不能活动肢体，1 可活动两个肢体，2 可活动四个肢体；自主或遵嘱）';
COMMENT ON COLUMN m2_anes.pacu_assessment.aldrete_respiration IS
    '呼吸 0–2（0 呼吸暂停，1 呼吸困难或呼吸受限，2 能深呼吸并有效咳嗽）';
COMMENT ON COLUMN m2_anes.pacu_assessment.aldrete_circulation IS
    '循环 0–2（血压较术前偏离幅度：>50% / 20%–50% / <20%）';
COMMENT ON COLUMN m2_anes.pacu_assessment.aldrete_consciousness IS
    '意识 0–2（0 无反应，1 呼之可醒，2 完全清醒）';
COMMENT ON COLUMN m2_anes.pacu_assessment.aldrete_oxygenation IS
    '氧合 0–2（0 吸氧下 SpO2 <90%，1 需吸氧维持 >90%，2 空气下 >92%）';
COMMENT ON COLUMN m2_anes.pacu_assessment.aldrete_total IS
    '总分（服务端计算：五项之和，0–10）；转出阈值默认 9【待确认】';
COMMENT ON COLUMN m2_anes.pacu_assessment.notes IS
    '评估备注（≤500 字符；只落库/授权用户可见，不写日志与审计原文）';
COMMENT ON COLUMN m2_anes.pacu_assessment.created_at IS '录入时间（服务端时间）';
COMMENT ON COLUMN m2_anes.pacu_assessment.created_by IS '录入人用户标识';

-- -----------------------------------------------------------------------------
-- 3. pacu_discharge 转出留痕（每条复苏记录最多一条，append-only）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.pacu_discharge (
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    pacu_id           uuid        NOT NULL,
    discharged_at     timestamptz NOT NULL,
    destination       varchar(16) NOT NULL,
    handover          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    criteria_snapshot jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    CONSTRAINT pk_pacu_discharge PRIMARY KEY (id),
    CONSTRAINT uk_pacu_discharge_pacu UNIQUE (pacu_id),
    CONSTRAINT fk_pacu_discharge_pacu FOREIGN KEY (pacu_id)
        REFERENCES m2_anes.pacu_record (id) ON DELETE RESTRICT,
    CONSTRAINT ck_pacu_discharge_destination
        CHECK (destination IN ('WARD', 'ICU', 'HDU', 'DAY_SURGERY', 'HOME')),
    CONSTRAINT ck_pacu_discharge_handover CHECK (jsonb_typeof(handover) = 'object'),
    CONSTRAINT ck_pacu_discharge_criteria CHECK (jsonb_typeof(criteria_snapshot) = 'object')
);

CREATE INDEX idx_pacu_discharge_created_at ON m2_anes.pacu_discharge (created_at);

COMMENT ON TABLE m2_anes.pacu_discharge IS
    'PACU 转出留痕：每条复苏记录最多一条（uk_pacu_discharge_pacu）；append-only（UPDATE/DELETE/TRUNCATE 拒绝，55000）';
COMMENT ON COLUMN m2_anes.pacu_discharge.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.pacu_discharge.pacu_id IS
    '所属复苏记录（FK，唯一）；转出后父记录状态置 DISCHARGED';
COMMENT ON COLUMN m2_anes.pacu_discharge.discharged_at IS '转出时间（服务端时间）';
COMMENT ON COLUMN m2_anes.pacu_discharge.destination IS
    '转出去向：WARD/ICU/HDU/DAY_SURGERY/HOME（演示口径【待确认】）';
COMMENT ON COLUMN m2_anes.pacu_discharge.handover IS
    'SBAR 交接 jsonb：{situation,background,assessment,recommendation}；只落库/授权用户可见';
COMMENT ON COLUMN m2_anes.pacu_discharge.criteria_snapshot IS
    '转出时 Aldrete 达标快照 jsonb：{aldreteThreshold,aldreteTotal,meetsThreshold,'
    'itemsComplete,latestAssessmentId,latestAssessmentAt}（阈值调整后可追溯当时依据）';
COMMENT ON COLUMN m2_anes.pacu_discharge.created_at IS '录入时间（服务端时间）';
COMMENT ON COLUMN m2_anes.pacu_discharge.created_by IS '转出操作人用户标识';

-- -----------------------------------------------------------------------------
-- 4. append-only 与状态守卫
-- -----------------------------------------------------------------------------
-- 4.1 pacu_assessment：任何 UPDATE/DELETE/TRUNCATE 拒绝（55000）。
CREATE OR REPLACE FUNCTION m2_anes.fn_pacu_assessment_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'm2_anes.pacu_assessment is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = 'Aldrete 评估不可修改或删除；更正请新增一条评估';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_pacu_assessment_append_only() IS
    'Aldrete 评估 append-only 触发器函数：拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000';

CREATE TRIGGER trg_pacu_assessment_no_update
    BEFORE UPDATE ON m2_anes.pacu_assessment
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_pacu_assessment_append_only();

CREATE TRIGGER trg_pacu_assessment_no_delete
    BEFORE DELETE ON m2_anes.pacu_assessment
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_pacu_assessment_append_only();

CREATE TRIGGER trg_pacu_assessment_no_truncate
    BEFORE TRUNCATE ON m2_anes.pacu_assessment
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_pacu_assessment_append_only();

-- 4.2 pacu_assessment：父记录已转出后禁止新增评估（服务层 409 的第二道防线）。
CREATE OR REPLACE FUNCTION m2_anes.fn_pacu_assessment_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    parent_status varchar(16);
BEGIN
    SELECT status INTO parent_status
      FROM m2_anes.pacu_record
     WHERE id = NEW.pacu_id;
    IF parent_status IS NULL THEN
        RAISE EXCEPTION 'm2_anes.pacu_assessment: 复苏记录不存在'
            USING ERRCODE = '55000';
    END IF;
    IF parent_status = 'DISCHARGED' THEN
        RAISE EXCEPTION 'm2_anes.pacu_assessment: 已转出，不可再记录评估'
            USING ERRCODE = '55000',
                  HINT = '转出后评估不可追加；如需继续监测必须重新入室（新建复苏记录）';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_pacu_assessment_guard() IS
    'Aldrete 评估插入守卫：父复苏记录已转出（DISCHARGED）时拒绝新增，SQLSTATE 55000';

CREATE TRIGGER trg_pacu_assessment_discharged_insert
    BEFORE INSERT ON m2_anes.pacu_assessment
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_pacu_assessment_guard();

-- 4.3 pacu_discharge：任何 UPDATE/DELETE/TRUNCATE 拒绝（55000）。
CREATE OR REPLACE FUNCTION m2_anes.fn_pacu_discharge_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'm2_anes.pacu_discharge is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '转出记录不可修改或删除；如需重新入室请新建复苏记录';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_pacu_discharge_append_only() IS
    '转出留痕 append-only 触发器函数：拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000';

CREATE TRIGGER trg_pacu_discharge_no_update
    BEFORE UPDATE ON m2_anes.pacu_discharge
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_pacu_discharge_append_only();

CREATE TRIGGER trg_pacu_discharge_no_delete
    BEFORE DELETE ON m2_anes.pacu_discharge
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_pacu_discharge_append_only();

CREATE TRIGGER trg_pacu_discharge_no_truncate
    BEFORE TRUNCATE ON m2_anes.pacu_discharge
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_pacu_discharge_append_only();

-- 4.4 pacu_record：身份/入室信息不可改、DISCHARGED 终态、discharged_at 写一次。
CREATE OR REPLACE FUNCTION m2_anes.fn_pacu_record_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'm2_anes.pacu_record: DELETE 不允许（临床记录不可物理删除）'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.id <> OLD.id
        OR NEW.anesthesia_record_id <> OLD.anesthesia_record_id
        OR NEW.patient_id <> OLD.patient_id
        OR NEW.admitted_at <> OLD.admitted_at
        OR NEW.admitted_vital_signs <> OLD.admitted_vital_signs
        OR NEW.consciousness <> OLD.consciousness
        OR NEW.pain_score <> OLD.pain_score
        OR NEW.nausea <> OLD.nausea
        OR NEW.airway <> OLD.airway
        OR NEW.created_at <> OLD.created_at
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
        OR NEW.deleted <> OLD.deleted
    THEN
        RAISE EXCEPTION 'm2_anes.pacu_record: 身份/入室信息不可修改'
            USING ERRCODE = '55000',
                  HINT = '入室信息有误时须重新入室（新建复苏记录并说明）；不可回改历史';
    END IF;

    IF NEW.version < OLD.version THEN
        RAISE EXCEPTION 'm2_anes.pacu_record: version 只增不减'
            USING ERRCODE = '55000';
    END IF;

    -- DISCHARGED 为终态：不可回退到监测/达标状态。
    IF OLD.status = 'DISCHARGED' AND NEW.status <> 'DISCHARGED' THEN
        RAISE EXCEPTION 'm2_anes.pacu_record: 已转出记录状态不可回退（当前 %）', OLD.status
            USING ERRCODE = '55000',
                  HINT = '需要继续监测时必须重新入室（新建复苏记录）';
    END IF;

    -- discharged_at 只能由 NULL 变为非空，且必须与状态 DISCHARGED 同置；写入后不可改。
    IF OLD.discharged_at IS NOT NULL AND NEW.discharged_at IS DISTINCT FROM OLD.discharged_at THEN
        RAISE EXCEPTION 'm2_anes.pacu_record: 转出时间写入后不可修改'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.discharged_at IS NOT NULL AND NEW.status <> 'DISCHARGED' THEN
        RAISE EXCEPTION 'm2_anes.pacu_record: 转出时间与 DISCHARGED 状态必须同置'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_pacu_record_guard() IS
    '复苏记录守卫：身份/入室信息不可改、DISCHARGED 终态（READY→MONITORING 回落待确认 Q-8）、'
    '转出时间写一次且与 DISCHARGED 同置、version 只增；DELETE/TRUNCATE 拒绝（55000）';

CREATE TRIGGER trg_pacu_record_guard_update
    BEFORE UPDATE ON m2_anes.pacu_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_pacu_record_guard();

CREATE TRIGGER trg_pacu_record_no_delete
    BEFORE DELETE ON m2_anes.pacu_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_pacu_record_guard();

CREATE OR REPLACE FUNCTION m2_anes.fn_pacu_record_no_truncate()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'm2_anes.pacu_record: TRUNCATE 不允许（临床记录不可物理删除）'
        USING ERRCODE = '55000';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_pacu_record_no_truncate() IS
    '复苏记录 TRUNCATE 守卫：拒绝物理清空，SQLSTATE 55000';

CREATE TRIGGER trg_pacu_record_no_truncate
    BEFORE TRUNCATE ON m2_anes.pacu_record
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_pacu_record_no_truncate();

-- -----------------------------------------------------------------------------
-- 5. 权限点 m2:pacu:read、m2:pacu:write
--    授 SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST（与记录单读写同口径）；
--    PACU 专职护士角色（SYS_PACU_NURSE）当前未落库种子，是否单独授权【待确认】。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-000000000063', 'm2:pacu:read', 'M2 复苏记录只读', 'API',
     '/api/v1/anesthesia/pacu-records', 'GET', NULL, 140),
    ('b0000000-0000-4000-8000-000000000064', 'm2:pacu:write', 'M2 复苏记录与转出', 'API',
     '/api/v1/anesthesia/pacu-records', 'POST', NULL, 141);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：读写（运维/排障）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000063'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000064'),
    -- SYS_ANESTHESIOLOGIST：PACU 医疗责任人
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000063'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000064'),
    -- SYS_NURSE_ANESTHETIST：PACU 监测与评分执行
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000063'),
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000064');

-- -----------------------------------------------------------------------------
-- 6. 迁移自检（fail fast）
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF to_regclass('m2_anes.pacu_record') IS NULL THEN
        RAISE EXCEPTION 'V18 迁移自检失败：pacu_record 未创建';
    END IF;
    IF to_regclass('m2_anes.pacu_assessment') IS NULL THEN
        RAISE EXCEPTION 'V18 迁移自检失败：pacu_assessment 未创建';
    END IF;
    IF to_regclass('m2_anes.pacu_discharge') IS NULL THEN
        RAISE EXCEPTION 'V18 迁移自检失败：pacu_discharge 未创建';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_pacu_assessment_total_sum'
          AND conrelid = 'm2_anes.pacu_assessment'::regclass
    ) THEN
        RAISE EXCEPTION 'V18 迁移自检失败：Aldrete 总分=五项之和约束缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'm2_anes' AND indexname = 'uk_pacu_record_active'
    ) THEN
        RAISE EXCEPTION 'V18 迁移自检失败：未转出复苏记录部分唯一索引缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_pacu_assessment_discharged_insert'
          AND tgrelid = 'm2_anes.pacu_assessment'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V18 迁移自检失败：转出后禁止评估触发器缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_pacu_assessment_no_update'
          AND tgrelid = 'm2_anes.pacu_assessment'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V18 迁移自检失败：评估 append-only 触发器缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_pacu_discharge_no_update'
          AND tgrelid = 'm2_anes.pacu_discharge'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V18 迁移自检失败：转出 append-only 触发器缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_pacu_record_guard_update'
          AND tgrelid = 'm2_anes.pacu_record'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V18 迁移自检失败：复苏记录状态守卫缺失';
    END IF;
    IF (SELECT count(*) FROM m0_platform.sys_permission
         WHERE code IN ('m2:pacu:read', 'm2:pacu:write')) <> 2 THEN
        RAISE EXCEPTION 'V18 迁移自检失败：权限点 m2:pacu:read/write 未全部写入';
    END IF;
    IF (SELECT count(*) FROM m0_platform.sys_role_permission rp
          JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
         WHERE p.code IN ('m2:pacu:read', 'm2:pacu:write')
           AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001',
                              'a0000000-0000-4000-8000-000000000002',
                              'a0000000-0000-4000-8000-000000000003')) <> 6 THEN
        RAISE EXCEPTION 'V18 迁移自检失败：PACU 权限未授予三个临床/管理角色';
    END IF;
END
$$;
