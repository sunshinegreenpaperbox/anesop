-- =============================================================================
-- ANESOP 数据库迁移 V10：M2 术前访视与评估（M2-M4-01）
--
-- 设计依据：
--   方案书 §2.4.3（M2 术前访视：病史、气道 Mallampati、ASA、RCRI/Apfel/STOP-BANG/DASI、
--   麻醉计划；评分项与阈值可按院方口径配置）、§3.4.2（麻醉记录单与访视实体）、
--   §3.4.3（审计字段/乐观锁/逻辑删除）、§3.9.2（RBAC/数据权限）、§3.9.3（脱敏）
--   契约：packages/contracts/openapi/anesthesia.yaml 0.3.0
--   任务卡：docs/ai/tasks/M2-backlog.md M2-M4-01（术前访视与评估）
--
-- 内容（expand 段：只新增表/索引/权限点，不改写 V1–V9 既有列与数据）：
--   1) m2_anes.preop_assessment：术前访视与评估单。评分输入以 jsonb 结构化保存
--      （score_input：RCRI 6 项/Apfel 4 项/STOP-BANG 8 项/DASI 12 项勾选），
--      服务端计算后的分数与风险等级落列（rcri_score/apfel_score/stopbang_score/
--      dasi_score/risk_level 等），并保存 calculator_version 以便口径升级后复算与审计。
--      patient_snapshot/airway_details 同样用 jsonb 保存结构化快照。
--   2) 同一申请允许保留多份最终化历史（最终化后不可改，只能新建版本）；另建部分唯一索引
--      uk_preop_assessment_application_draft，同一申请同时最多一份 DRAFT（并发重复创建兜底）。
--   3) 权限点 m2:preop:read / m2:preop:write（三段式编码）：授予 SYS_ADMIN、
--      SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST（麻醉医师/麻醉护士为访视执行人）。
--   4) 最终化不可变（第二道防线）：FINALIZED 行拒绝任何 UPDATE，DELETE/TRUNCATE 一律拒绝，
--      SQLSTATE 55000；finalize_note 保存最终化说明（≤500 字符，只落库不入日志）。
--
-- 临床口径与安全约定：
--   - 评分由服务端计算，客户端只提交条目勾选；本表不保存"客户端提交的分数"，
--     scores 列为服务端计算结果（只读）。
--   - RCRI（Lee 1999，6 项×1 分）、Apfel（1999，4 项）、STOP-BANG（Chung 2008，8 项）、
--     DASI（Hlatky 1989，12 项权重，合计 58.2）均为公开量表；院方口径与综合风险合成
--     规则【待确认】，见 docs/modules/m2/README.md §4。
--   - 无 PHI 日志：表内 patient_snapshot 只含姓名/性别/年龄/院内患者号/科室病区床号，
--     不含证件号、手机号、住院号明文；审计 detail 不写姓名与病史原文。
--   - 不物理删除：deleted 逻辑删除；最终化单只读，修订通过新增版本记录保留原单；
--     数据库触发器对 FINALIZED 行的 UPDATE 与任何 DELETE/TRUNCATE 返回 55000。
--
-- 迁移纪律：本脚本已合入后禁止修改；变更一律新增 V<版本> 脚本（expand-contract）。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.5）：
--   1) 应用回滚：下线 /v1/anesthesia/preop-assessments* 路由与前端术前访视页；
--      其余麻醉记录/锁单/随访能力不受影响（V4 采集表不动）。
--   2) 库层前向修复（需 DBA 审批 + 备份，顺序不可颠倒）：
--      DROP TABLE m2_anes.preop_assessment；
--      DROP FUNCTION IF EXISTS m2_anes.fn_preop_assessment_finalized_immutable()；
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id IN ('b0000000-0000-4000-8000-00000000002a',
--                                'b0000000-0000-4000-8000-00000000002b')；
--      DELETE FROM m0_platform.sys_permission
--        WHERE code IN ('m2:preop:read', 'm2:preop:write')；
--   3) 回滚前导出 m2_anes.preop_assessment（访视与评分留痕）并校验行数；临床记录不得丢失，
--      须先归档到离线审计库；生产执行须人签字。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. preop_assessment 术前访视与评估单
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.preop_assessment (
    id                      uuid          NOT NULL DEFAULT gen_random_uuid(),
    application_id          uuid          NOT NULL,
    patient_id              uuid          NOT NULL,
    patient_snapshot        jsonb         NOT NULL,
    history_summary         text          NOT NULL,
    asa_class               varchar(8)    NOT NULL,
    mallampati              varchar(8)    NOT NULL,
    airway_details          jsonb         NOT NULL,
    score_input             jsonb         NOT NULL,
    rcri_score              smallint      NOT NULL,
    apfel_score             smallint      NOT NULL,
    stopbang_score          smallint      NOT NULL,
    dasi_score              numeric(5, 2) NOT NULL,
    rcri_risk               varchar(16)   NOT NULL,
    apfel_risk              varchar(16)   NOT NULL,
    stopbang_risk           varchar(16)   NOT NULL,
    functional_capacity_low boolean       NOT NULL,
    risk_level              varchar(16)   NOT NULL,
    calculator_version      varchar(16)   NOT NULL,
    scores                  jsonb         NOT NULL,
    plan_type               varchar(32)   NOT NULL,
    airway_plan             varchar(500),
    plan_notes              text,
    status                  varchar(16)   NOT NULL DEFAULT 'DRAFT',
    finalize_note           varchar(500),
    finalized_at            timestamptz,
    finalized_by            uuid,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    created_by              uuid,
    updated_at              timestamptz   NOT NULL DEFAULT now(),
    updated_by              uuid,
    version                 bigint        NOT NULL DEFAULT 1,
    deleted                 boolean       NOT NULL DEFAULT false,
    CONSTRAINT pk_preop_assessment PRIMARY KEY (id),
    CONSTRAINT fk_preop_assessment_application
        FOREIGN KEY (application_id) REFERENCES m1_sched.surgery_application (id),
    CONSTRAINT ck_preop_assessment_asa_class
        CHECK (asa_class IN ('I', 'II', 'III', 'IV', 'V', 'VI')),
    CONSTRAINT ck_preop_assessment_mallampati
        CHECK (mallampati IN ('I', 'II', 'III', 'IV')),
    CONSTRAINT ck_preop_assessment_rcri_score CHECK (rcri_score BETWEEN 0 AND 6),
    CONSTRAINT ck_preop_assessment_apfel_score CHECK (apfel_score BETWEEN 0 AND 4),
    CONSTRAINT ck_preop_assessment_stopbang_score CHECK (stopbang_score BETWEEN 0 AND 8),
    CONSTRAINT ck_preop_assessment_dasi_score CHECK (dasi_score BETWEEN 0 AND 58.2),
    CONSTRAINT ck_preop_assessment_rcri_risk
        CHECK (rcri_risk IN ('LOW', 'MODERATE', 'HIGH')),
    CONSTRAINT ck_preop_assessment_apfel_risk
        CHECK (apfel_risk IN ('LOW', 'MODERATE', 'HIGH')),
    CONSTRAINT ck_preop_assessment_stopbang_risk
        CHECK (stopbang_risk IN ('LOW', 'MODERATE', 'HIGH')),
    CONSTRAINT ck_preop_assessment_risk_level
        CHECK (risk_level IN ('LOW', 'MODERATE', 'HIGH')),
    CONSTRAINT ck_preop_assessment_plan_type
        CHECK (plan_type IN ('GENERAL', 'REGIONAL', 'COMBINED',
                             'MONITORED_ANESTHESIA_CARE', 'SEDATION')),
    CONSTRAINT ck_preop_assessment_status CHECK (status IN ('DRAFT', 'FINALIZED')),
    -- 状态与最终化字段一致：FINALIZED 必须有最终化人/时间，DRAFT 必须没有。
    CONSTRAINT ck_preop_assessment_finalized
        CHECK ((status = 'FINALIZED' AND finalized_at IS NOT NULL AND finalized_by IS NOT NULL)
            OR (status = 'DRAFT' AND finalized_at IS NULL AND finalized_by IS NULL)),
    -- jsonb 必须是对象（防止数组/标量误写），保证结构化复算。
    CONSTRAINT ck_preop_assessment_json_objects
        CHECK (jsonb_typeof(patient_snapshot) = 'object'
           AND jsonb_typeof(airway_details) = 'object'
           AND jsonb_typeof(score_input) = 'object'
           AND jsonb_typeof(scores) = 'object'),
    CONSTRAINT ck_preop_assessment_history_length
        CHECK (char_length(history_summary) BETWEEN 1 AND 4000),
    CONSTRAINT ck_preop_assessment_version CHECK (version >= 1)
);

-- 同一申请同时最多一份草稿；最终化后索引释放，允许新建版本（临床修订留痕）。
-- 并发重复创建由数据库兜底（应用层先查后写，冲突映射 409）。
CREATE UNIQUE INDEX uk_preop_assessment_application_draft
    ON m2_anes.preop_assessment (application_id)
    WHERE status = 'DRAFT' AND deleted = false;

CREATE INDEX idx_preop_assessment_application_id
    ON m2_anes.preop_assessment (application_id);
CREATE INDEX idx_preop_assessment_patient_id
    ON m2_anes.preop_assessment (patient_id);
CREATE INDEX idx_preop_assessment_status
    ON m2_anes.preop_assessment (status);
CREATE INDEX idx_preop_assessment_created_at
    ON m2_anes.preop_assessment (created_at);
CREATE INDEX idx_preop_assessment_deleted
    ON m2_anes.preop_assessment (deleted);

COMMENT ON TABLE m2_anes.preop_assessment IS
    '术前访视与评估单：病史/气道/ASA/评分输入（jsonb）与服务端评分结果；最终化后只读';
COMMENT ON COLUMN m2_anes.preop_assessment.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.preop_assessment.application_id IS
    '关联手术申请（逻辑外键 + 物理 FK 到 m1_sched.surgery_application，数据范围沿申请可见性）';
COMMENT ON COLUMN m2_anes.preop_assessment.patient_id IS '患者标识（与申请患者一致，不存住院号明文）';
COMMENT ON COLUMN m2_anes.preop_assessment.patient_snapshot IS
    '患者快照（jsonb）：院内患者号/姓名/性别/年龄/科室病区床号；不含证件号/手机号/住院号明文';
COMMENT ON COLUMN m2_anes.preop_assessment.history_summary IS '病史要点（自由文本，禁止入日志/审计）';
COMMENT ON COLUMN m2_anes.preop_assessment.asa_class IS 'ASA 分级 I–VI';
COMMENT ON COLUMN m2_anes.preop_assessment.mallampati IS '气道 Mallampati 分级 I–IV';
COMMENT ON COLUMN m2_anes.preop_assessment.airway_details IS
    '气道评估结构化明细（jsonb）：张口度 cm/颈椎活动度/备注';
COMMENT ON COLUMN m2_anes.preop_assessment.score_input IS
    '评分输入（jsonb）：RCRI 6 项/Apfel 4 项/STOP-BANG 8 项/DASI 12 项勾选，用于复算与审计';
COMMENT ON COLUMN m2_anes.preop_assessment.rcri_score IS 'RCRI 得分 0–6（服务端计算）';
COMMENT ON COLUMN m2_anes.preop_assessment.apfel_score IS 'Apfel PONV 得分 0–4（服务端计算）';
COMMENT ON COLUMN m2_anes.preop_assessment.stopbang_score IS 'STOP-BANG 得分 0–8（服务端计算）';
COMMENT ON COLUMN m2_anes.preop_assessment.dasi_score IS
    'DASI 指数 0–58.2（服务端按量表权重求和；院方口径【待确认】）';
COMMENT ON COLUMN m2_anes.preop_assessment.rcri_risk IS 'RCRI 风险分级 LOW/MODERATE/HIGH';
COMMENT ON COLUMN m2_anes.preop_assessment.apfel_risk IS 'Apfel 风险分级 LOW/MODERATE/HIGH';
COMMENT ON COLUMN m2_anes.preop_assessment.stopbang_risk IS 'STOP-BANG 风险分级 LOW/MODERATE/HIGH';
COMMENT ON COLUMN m2_anes.preop_assessment.functional_capacity_low IS
    '功能容量偏低（DASI 估算 <4 METs，切点【待确认】；不参与综合风险合成）';
COMMENT ON COLUMN m2_anes.preop_assessment.risk_level IS
    '综合风险等级（取 RCRI/Apfel/STOP-BANG 最高级，展示约定，【待确认】）';
COMMENT ON COLUMN m2_anes.preop_assessment.calculator_version IS '量表实现版本（复算依据）';
COMMENT ON COLUMN m2_anes.preop_assessment.scores IS
    '服务端评分结果快照（jsonb，只读）：分数/分级/综合风险/计算时间/量表版本';
COMMENT ON COLUMN m2_anes.preop_assessment.plan_type IS '麻醉计划类型';
COMMENT ON COLUMN m2_anes.preop_assessment.airway_plan IS '气道方案（描述性文本）';
COMMENT ON COLUMN m2_anes.preop_assessment.plan_notes IS '麻醉计划备注（演示文本，不入日志）';
COMMENT ON COLUMN m2_anes.preop_assessment.status IS '状态：DRAFT=草稿，FINALIZED=已最终化（只读）';
COMMENT ON COLUMN m2_anes.preop_assessment.finalize_note IS
    '最终化说明（可选，≤500 字符，演示文本；只落库，不写入日志与审计 detail）';
COMMENT ON COLUMN m2_anes.preop_assessment.finalized_at IS '最终化时间；草稿为 NULL';
COMMENT ON COLUMN m2_anes.preop_assessment.finalized_by IS '最终化操作人用户标识；草稿为 NULL';
COMMENT ON COLUMN m2_anes.preop_assessment.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m2_anes.preop_assessment.created_by IS '创建人用户标识';
COMMENT ON COLUMN m2_anes.preop_assessment.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m2_anes.preop_assessment.updated_by IS '最后修改人用户标识';
COMMENT ON COLUMN m2_anes.preop_assessment.version IS '乐观锁版本号（从 1 起）';
COMMENT ON COLUMN m2_anes.preop_assessment.deleted IS '逻辑删除标记（临床数据不物理删除）';

-- -----------------------------------------------------------------------------
-- 2. 权限点 m2:preop:read / m2:preop:write
--    授予 SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST：
--    访视与评分由麻醉医师/麻醉护士执行，系统管理员用于运维与排障。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort) VALUES
    ('b0000000-0000-4000-8000-00000000002a', 'm2:preop:read', 'M2 术前访视只读', 'API',
     '/api/v1/anesthesia/preop-assessments', 'GET', NULL, 130),
    ('b0000000-0000-4000-8000-00000000002b', 'm2:preop:write', 'M2 术前访视维护', 'API',
     '/api/v1/anesthesia/preop-assessments', 'POST', NULL, 131);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：读写（运维/排障）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000002a'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000002b'),
    -- SYS_ANESTHESIOLOGIST：访视执行人
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-00000000002a'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-00000000002b'),
    -- SYS_NURSE_ANESTHETIST：访视执行人
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-00000000002a'),
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-00000000002b');

-- -----------------------------------------------------------------------------
-- 3. 最终化不可变（第二道防线，M2-M4-01 S-3）
--    FINALIZED 行只读：任何 UPDATE 均被拒绝（含状态/审计列，避免“最终化后又被改写”）；
--    DELETE/TRUNCATE 一律拒绝（临床记录只做逻辑删除/新增版本，不物理删除）。
--    草稿行仍允许 UPDATE（保存草稿与最终化需要），由应用层 version 条件更新保证乐观锁。
--    SQLSTATE 55000 与 V1/V7/V9 的 append-only 约定一致，便于迁移测试断言。
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION m2_anes.fn_preop_assessment_finalized_immutable()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'FINALIZED' THEN
            RAISE EXCEPTION
                'm2_anes.preop_assessment is immutable once FINALIZED: % is not permitted', TG_OP
                USING ERRCODE = '55000',
                      HINT = '已最终化的访视单不可修改；请为同一申请新建版本';
        END IF;
        -- 草稿行允许 UPDATE（保存草稿/最终化），乐观锁由应用层 version 条件更新保证。
        RETURN NEW;
    END IF;
    -- DELETE/TRUNCATE：任何状态均拒绝，保证临床记录不被物理删除。
    RAISE EXCEPTION
        'm2_anes.preop_assessment is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '临床记录不得物理删除；请使用逻辑删除或新建版本';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_preop_assessment_finalized_immutable() IS
    '术前访视单最终化不可变触发器函数：FINALIZED 行拒绝 UPDATE，任何 DELETE/TRUNCATE 拒绝，SQLSTATE 55000';

CREATE TRIGGER trg_preop_assessment_no_update_finalized
    BEFORE UPDATE ON m2_anes.preop_assessment
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_preop_assessment_finalized_immutable();

CREATE TRIGGER trg_preop_assessment_no_delete
    BEFORE DELETE ON m2_anes.preop_assessment
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_preop_assessment_finalized_immutable();

CREATE TRIGGER trg_preop_assessment_no_truncate
    BEFORE TRUNCATE ON m2_anes.preop_assessment
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_preop_assessment_finalized_immutable();
