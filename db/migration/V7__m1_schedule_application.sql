-- =============================================================================
-- ANESOP 数据库迁移 V7：M1 手术申请接收与校验（M2-M1-01）
--
-- 设计依据：
--   方案书 §2.4.2（M1 手术排程：HIS 申请接收、校验、状态流转）、§2.7.1（HIS 集成）
--   方案书 §3.4.2（M1 排程/申请实体）、§3.4.3（表/索引/约束命名、timestamptz）
--   方案书 §3.5.3（I-01a/I-01b：HIS 手术申请拉取与回写）、§3.9.3（字段加密与脱敏）
--   契约：packages/contracts/openapi/scheduling.yaml 0.3.0 / patient.yaml 0.3.0
--   任务卡：docs/ai/tasks/M2-backlog.md M2-M1-01（手术申请接收与校验）
--
-- 内容（expand-contract 的 expand 段：只新增 schema/表/索引/权限点，不改既有表）：
--   1) m1_sched.patient_snapshot：患者主索引的排程侧快照。只保存最小必要的展示字段
--      （姓名、性别、出生日期/年龄、脱敏住院号、科室/病区/床号）+ 住院号 HMAC 哈希；
--      **不保存身份证与手机号**（含脱敏值），住院号明文不入库（§3.9.3）。
--   2) m1_sched.surgery_application：手术申请单。his_application_no 为入站幂等键（唯一索引），
--      patient_id 为同 schema 内物理外键；科室/院区标识为跨模块逻辑外键（不建物理外键），
--      仅用于数据范围（DEPT/SITE）过滤。
--   3) m1_sched.application_status_history：append-only 状态历史；每次创建/状态变更写一行，
--      记录 from_status/to_status/reason_code/reason/operator_id/occurred_at；reason_code 与
--      reason 分列（分别 varchar(64)/varchar(500)），避免原因码+说明拼接超长；触发器拒绝
--      UPDATE/DELETE/TRUNCATE（复用 V1 审计 append-only 的风格与 SQLSTATE 55000）。
--   4) 权限点 m1:application:read / m1:application:write / m1:patient:read
--      （三段式 <模块>:<资源>:<动作>）：application read 授予 SYS_ADMIN、SYS_OR_MANAGER、
--      SYS_SURGEON；write 授予 SYS_ADMIN、SYS_OR_MANAGER；patient read（患者摘要最小必要字段）
--      授予 SYS_ADMIN、SYS_SURGEON、SYS_OR_MANAGER（最小权限：其他角色不可见患者摘要）。
--      V2 的 m1:access 菜单占位权限保留不动，不改变既有角色与既有授权（不破坏 V2–V6 基线）。
--
-- 安全与脱敏约定：
--   - 住院号哈希使用 HMAC-SHA-256（应用层计算，pepper 由环境变量注入），仅用于匹配/去重，
--     不可逆、不出接口；展示统一走 inpatient_no_masked（前 4 + **** + 后 4）。
--   - 审计四件套（created_at/created_by/updated_at/updated_by）+ version + deleted 与 M0 实体一致；
--     申请与患者快照不做物理删除，状态历史 append-only。
--
-- 迁移纪律：本脚本已合入后禁止修改；变更一律新增 V<版本> 脚本（expand-contract）。
-- 回滚策略（forward-only，仓库无 undo 脚本）：见 docs/db/README.md §4.2。概要：
--   1) 应用回滚：停止读写 m1_sched 三表（M1 路由下线），M0 认证/权限/审计不受影响；
--   2) 库层前向修复：DROP TABLE m1_sched.application_status_history；
--      DROP TABLE m1_sched.surgery_application；DROP TABLE m1_sched.patient_snapshot；
--      DROP SCHEMA m1_sched；DELETE 两个新权限点及其授权；
--   3) 回滚前必须备份 m1_sched（含状态历史），并在演练库验证；生产执行须人签字。
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS m1_sched;

COMMENT ON SCHEMA m1_sched IS
    'M1 手术排程 schema：患者排程快照、手术申请、状态历史；仅 M1 读写，其他模块经 m1-api 访问';

-- -----------------------------------------------------------------------------
-- 1. patient_snapshot 患者排程快照
--    只保留排程/申请展示所需的最小必要字段；证件号与手机号不进入本 schema。
-- -----------------------------------------------------------------------------
CREATE TABLE m1_sched.patient_snapshot (
    id                  uuid         NOT NULL DEFAULT gen_random_uuid(),
    his_patient_no      varchar(64)  NOT NULL,
    name                varchar(100) NOT NULL,
    gender              varchar(16)  NOT NULL,
    birth_date          date,
    age                 integer,
    inpatient_no_masked varchar(64)  NOT NULL,
    inpatient_no_hash   varchar(128) NOT NULL,
    dept_name           varchar(100) NOT NULL,
    dept_id             uuid,
    ward                varchar(100),
    bed_no              varchar(32),
    site_id             uuid,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    updated_by          uuid,
    version             bigint       NOT NULL DEFAULT 0,
    deleted             boolean      NOT NULL DEFAULT false,
    CONSTRAINT pk_patient_snapshot PRIMARY KEY (id),
    CONSTRAINT ck_patient_snapshot_gender CHECK (gender IN ('MALE', 'FEMALE', 'UNKNOWN')),
    CONSTRAINT ck_patient_snapshot_age CHECK (age IS NULL OR (age >= 0 AND age <= 150))
);

-- HIS 患者号是患者快照的入站幂等键：同一 HIS 患者只保留一行。
CREATE UNIQUE INDEX uk_patient_snapshot_his_patient_no
    ON m1_sched.patient_snapshot (his_patient_no);
-- 住院号哈希用于跨申请匹配同一患者；不可逆、不出接口（明文禁止入库）。
CREATE UNIQUE INDEX uk_patient_snapshot_inpatient_no_hash
    ON m1_sched.patient_snapshot (inpatient_no_hash);
CREATE INDEX idx_patient_snapshot_dept_id ON m1_sched.patient_snapshot (dept_id);
CREATE INDEX idx_patient_snapshot_site_id ON m1_sched.patient_snapshot (site_id);
CREATE INDEX idx_patient_snapshot_deleted ON m1_sched.patient_snapshot (deleted);

COMMENT ON TABLE m1_sched.patient_snapshot IS
    '患者排程侧快照：最小必要展示字段 + 住院号哈希；不含身份证/手机号（§3.9.3）';
COMMENT ON COLUMN m1_sched.patient_snapshot.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m1_sched.patient_snapshot.his_patient_no IS
    'HIS 患者号，全局唯一；HIS 申请导入时按此匹配患者快照（幂等键）';
COMMENT ON COLUMN m1_sched.patient_snapshot.name IS '患者姓名（展示用；禁止写入真实患者测试数据）';
COMMENT ON COLUMN m1_sched.patient_snapshot.gender IS '性别：MALE/FEMALE/UNKNOWN';
COMMENT ON COLUMN m1_sched.patient_snapshot.birth_date IS
    '出生日期；与年龄/科室组合可能重识别，接口按最小必要返回';
COMMENT ON COLUMN m1_sched.patient_snapshot.age IS '年龄（岁），由出生日期计算；0–150';
COMMENT ON COLUMN m1_sched.patient_snapshot.inpatient_no_masked IS
    '脱敏住院号（前 4 + 4 个 * + 后 4，如 SYNT****0001）；住院号明文禁止入库';
COMMENT ON COLUMN m1_sched.patient_snapshot.inpatient_no_hash IS
    '住院号 HMAC-SHA-256 哈希（应用层计算）；用于匹配/去重，不可逆、不出接口';
COMMENT ON COLUMN m1_sched.patient_snapshot.dept_name IS '患者所属科室名称';
COMMENT ON COLUMN m1_sched.patient_snapshot.dept_id IS
    '患者所属科室 UUID，逻辑外键 → m0_platform.sys_org.id；仅用于 DEPT 数据范围过滤（跨模块不建物理外键）';
COMMENT ON COLUMN m1_sched.patient_snapshot.ward IS '病区名称';
COMMENT ON COLUMN m1_sched.patient_snapshot.bed_no IS '床号；未分配为空';
COMMENT ON COLUMN m1_sched.patient_snapshot.site_id IS
    '院区 UUID，逻辑外键 → m0_platform.sys_org.id；仅用于 SITE 数据范围过滤';
COMMENT ON COLUMN m1_sched.patient_snapshot.created_at IS '创建时间（timestamptz，服务端时间）';
COMMENT ON COLUMN m1_sched.patient_snapshot.created_by IS '创建人 UUID；HIS 导入为系统操作人';
COMMENT ON COLUMN m1_sched.patient_snapshot.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m1_sched.patient_snapshot.updated_by IS '最后更新人 UUID';
COMMENT ON COLUMN m1_sched.patient_snapshot.version IS '乐观锁版本号（MyBatis-Plus @Version）';
COMMENT ON COLUMN m1_sched.patient_snapshot.deleted IS '软删除标记：true=已删除；患者快照不物理删除';

-- -----------------------------------------------------------------------------
-- 2. surgery_application 手术申请
-- -----------------------------------------------------------------------------
CREATE TABLE m1_sched.surgery_application (
    id                   uuid         NOT NULL DEFAULT gen_random_uuid(),
    his_application_no   varchar(64)  NOT NULL,
    patient_id           uuid         NOT NULL,
    surgery_name         varchar(200) NOT NULL,
    surgery_code         varchar(64),
    urgency              varchar(16)  NOT NULL,
    planned_duration_min integer      NOT NULL,
    dept_name            varchar(100) NOT NULL,
    dept_id              uuid,
    site_id              uuid,
    surgeon_name         varchar(100),
    preop_diagnosis      varchar(500),
    status               varchar(16)  NOT NULL DEFAULT 'SUBMITTED',
    submitted_at         timestamptz  NOT NULL DEFAULT now(),
    source               varchar(16)  NOT NULL,
    remark               varchar(500),
    created_at           timestamptz  NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    updated_by           uuid,
    version              bigint       NOT NULL DEFAULT 0,
    deleted              boolean      NOT NULL DEFAULT false,
    CONSTRAINT pk_surgery_application PRIMARY KEY (id),
    CONSTRAINT fk_surgery_application_patient FOREIGN KEY (patient_id)
        REFERENCES m1_sched.patient_snapshot (id) ON DELETE RESTRICT,
    CONSTRAINT ck_surgery_application_urgency
        CHECK (urgency IN ('ELECTIVE', 'URGENT', 'EMERGENCY')),
    CONSTRAINT ck_surgery_application_status
        CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'SCHEDULED', 'CANCELLED', 'REJECTED')),
    CONSTRAINT ck_surgery_application_source CHECK (source IN ('HIS', 'MANUAL')),
    CONSTRAINT ck_surgery_application_duration
        CHECK (planned_duration_min > 0 AND planned_duration_min <= 1440)
);

-- HIS 申请号是手术申请的入站幂等键：重复导入 ON CONFLICT DO NOTHING，手工重复提交返回 409。
CREATE UNIQUE INDEX uk_surgery_application_his_no
    ON m1_sched.surgery_application (his_application_no);
CREATE INDEX idx_surgery_application_patient_id
    ON m1_sched.surgery_application (patient_id);
CREATE INDEX idx_surgery_application_status
    ON m1_sched.surgery_application (status);
CREATE INDEX idx_surgery_application_urgency
    ON m1_sched.surgery_application (urgency);
CREATE INDEX idx_surgery_application_dept_id
    ON m1_sched.surgery_application (dept_id);
CREATE INDEX idx_surgery_application_site_id
    ON m1_sched.surgery_application (site_id);
-- 工作台默认按提交时间倒序；与状态组合查询。
CREATE INDEX idx_surgery_application_submitted_at
    ON m1_sched.surgery_application (submitted_at DESC);
CREATE INDEX idx_surgery_application_status_submitted
    ON m1_sched.surgery_application (status, submitted_at DESC);
CREATE INDEX idx_surgery_application_deleted
    ON m1_sched.surgery_application (deleted);

COMMENT ON TABLE m1_sched.surgery_application IS
    '手术申请单：HIS 导入/手工创建，his_application_no 幂等；状态机 SUBMITTED→ACCEPTED→SCHEDULED，CANCELLED/REJECTED 为终态';
COMMENT ON COLUMN m1_sched.surgery_application.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m1_sched.surgery_application.his_application_no IS
    'HIS 申请号，全局唯一（入站幂等键）；重复导入跳过，手工重复提交 409';
COMMENT ON COLUMN m1_sched.surgery_application.patient_id IS
    '患者 UUID，FK → m1_sched.patient_snapshot.id；同 schema 内物理外键';
COMMENT ON COLUMN m1_sched.surgery_application.surgery_name IS '拟施术式';
COMMENT ON COLUMN m1_sched.surgery_application.surgery_code IS '术式编码（字典/院内编码）';
COMMENT ON COLUMN m1_sched.surgery_application.urgency IS
    '紧急度：ELECTIVE=择期，URGENT=限期，EMERGENCY=急诊；急诊优先于择期（§2.4.2）';
COMMENT ON COLUMN m1_sched.surgery_application.planned_duration_min IS
    '预计手术时长（分钟），必须 1–1440';
COMMENT ON COLUMN m1_sched.surgery_application.dept_name IS '申请科室名称（取自患者快照）';
COMMENT ON COLUMN m1_sched.surgery_application.dept_id IS
    '申请科室 UUID，逻辑外键 → m0_platform.sys_org.id；DEPT 数据范围过滤用（不建物理外键）';
COMMENT ON COLUMN m1_sched.surgery_application.site_id IS
    '院区 UUID，逻辑外键 → m0_platform.sys_org.id；SITE 数据范围过滤用（不建物理外键）';
COMMENT ON COLUMN m1_sched.surgery_application.surgeon_name IS '申请/主刀医师姓名（演示文本）';
COMMENT ON COLUMN m1_sched.surgery_application.preop_diagnosis IS
    '术前诊断（演示文本）；日志与审计不得输出诊断原文';
COMMENT ON COLUMN m1_sched.surgery_application.status IS
    '申请状态：SUBMITTED/ACCEPTED/SCHEDULED/CANCELLED/REJECTED；CANCELLED/REJECTED 终态';
COMMENT ON COLUMN m1_sched.surgery_application.submitted_at IS
    '提交时间（timestamptz，服务端时间）；列表默认按此倒序';
COMMENT ON COLUMN m1_sched.surgery_application.source IS
    '来源：HIS=接口导入（含模拟适配器），MANUAL=手工/测试创建';
COMMENT ON COLUMN m1_sched.surgery_application.remark IS '备注（演示文本）';
COMMENT ON COLUMN m1_sched.surgery_application.created_at IS '创建时间（timestamptz，服务端时间）';
COMMENT ON COLUMN m1_sched.surgery_application.created_by IS '创建人 UUID；HIS 导入为系统操作人';
COMMENT ON COLUMN m1_sched.surgery_application.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m1_sched.surgery_application.updated_by IS '最后更新人 UUID';
COMMENT ON COLUMN m1_sched.surgery_application.version IS
    '乐观锁版本号：创建为 1；取消/状态变更必须回传当前版本，冲突 409';
COMMENT ON COLUMN m1_sched.surgery_application.deleted IS '软删除标记：true=已删除；申请不物理删除';

-- -----------------------------------------------------------------------------
-- 3. application_status_history 状态历史（append-only）
-- -----------------------------------------------------------------------------
CREATE TABLE m1_sched.application_status_history (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    application_id uuid         NOT NULL,
    from_status    varchar(16),
    to_status      varchar(16)  NOT NULL,
    reason_code    varchar(64),
    reason         varchar(500),
    operator_id    uuid,
    occurred_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_application_status_history PRIMARY KEY (id),
    CONSTRAINT fk_application_status_history_application FOREIGN KEY (application_id)
        REFERENCES m1_sched.surgery_application (id) ON DELETE RESTRICT,
    CONSTRAINT ck_application_status_history_from
        CHECK (from_status IS NULL OR from_status IN ('SUBMITTED', 'ACCEPTED', 'SCHEDULED', 'CANCELLED', 'REJECTED')),
    CONSTRAINT ck_application_status_history_to
        CHECK (to_status IN ('SUBMITTED', 'ACCEPTED', 'SCHEDULED', 'CANCELLED', 'REJECTED'))
);

CREATE INDEX idx_application_status_history_application
    ON m1_sched.application_status_history (application_id, occurred_at);
CREATE INDEX idx_application_status_history_occurred_at
    ON m1_sched.application_status_history (occurred_at DESC);

COMMENT ON TABLE m1_sched.application_status_history IS
    '手术申请状态历史：append-only，每次创建/状态变更写一行；无 updated_at/deleted';
COMMENT ON COLUMN m1_sched.application_status_history.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m1_sched.application_status_history.application_id IS
    '手术申请 UUID，FK → m1_sched.surgery_application.id';
COMMENT ON COLUMN m1_sched.application_status_history.from_status IS
    '变更前状态；首次创建（初始 SUBMITTED）为 NULL';
COMMENT ON COLUMN m1_sched.application_status_history.to_status IS '变更后状态';
COMMENT ON COLUMN m1_sched.application_status_history.reason_code IS
    '变更原因码（字典值，≤64 字符）；取消时必填，创建/导入为空';
COMMENT ON COLUMN m1_sched.application_status_history.reason IS
    '变更原因说明（演示文本，≤500 字符）；与 reason_code 分列，不含患者标识';
COMMENT ON COLUMN m1_sched.application_status_history.operator_id IS
    '操作人 UUID，逻辑外键 → m0_platform.sys_user.id（不建物理外键）';
COMMENT ON COLUMN m1_sched.application_status_history.occurred_at IS
    '状态变更时间（timestamptz，服务端时间）';

-- 状态历史 append-only：任何 UPDATE / DELETE / TRUNCATE 在数据库层被拒绝（第二道防线）。
CREATE OR REPLACE FUNCTION m1_sched.fn_application_status_history_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'm1_sched.application_status_history is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '状态历史只能 INSERT；更正请写新的状态事件，不得改写历史';
END;
$$;

COMMENT ON FUNCTION m1_sched.fn_application_status_history_append_only() IS
    '手术申请状态历史 append-only 触发器函数：拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000';

CREATE TRIGGER trg_application_status_history_no_update
    BEFORE UPDATE ON m1_sched.application_status_history
    FOR EACH ROW EXECUTE FUNCTION m1_sched.fn_application_status_history_append_only();

CREATE TRIGGER trg_application_status_history_no_delete
    BEFORE DELETE ON m1_sched.application_status_history
    FOR EACH ROW EXECUTE FUNCTION m1_sched.fn_application_status_history_append_only();

CREATE TRIGGER trg_application_status_history_no_truncate
    BEFORE TRUNCATE ON m1_sched.application_status_history
    FOR EACH STATEMENT EXECUTE FUNCTION m1_sched.fn_application_status_history_append_only();

-- -----------------------------------------------------------------------------
-- 4. 权限点 m1:application:read / m1:application:write / m1:patient:read
--    端点路径与契约 scheduling.yaml 0.3.0 的 /v1/scheduling/applications 一致；
--    API 前缀 /api 由网关统一加。read 授予系统管理员/手术室管理员/外科医师，
--    write 仅授予系统管理员与手术室管理员（最小权限；HIS 导入使用系统管理员账号）。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort) VALUES
    ('b0000000-0000-4000-8000-000000000024', 'm1:application:read', 'M1 手术申请只读', 'API',
     '/api/v1/scheduling/applications', 'GET', NULL, 115),
    ('b0000000-0000-4000-8000-000000000025', 'm1:application:write', 'M1 手术申请维护', 'API',
     '/api/v1/scheduling/applications', 'POST', NULL, 116),
    ('b0000000-0000-4000-8000-000000000026', 'm1:patient:read', 'M1 患者摘要只读', 'API',
     '/api/v1/patients', 'GET', NULL, 117);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：读写（HIS 模拟导入与运维）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000024'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000025'),
    -- SYS_OR_MANAGER（DEPT 数据范围）：排程工作台读写
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000024'),
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000025'),
    -- SYS_SURGEON：申请只读（申请由 HIS/手术室创建，外科医师查看）+ 患者摘要只读
    ('a0000000-0000-4000-8000-000000000004', 'b0000000-0000-4000-8000-000000000024'),
    -- m1:patient:read（Q-3 最小权限）：SYS_ADMIN / SYS_OR_MANAGER / SYS_SURGEON
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000026'),
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000026'),
    ('a0000000-0000-4000-8000-000000000004', 'b0000000-0000-4000-8000-000000000026');
