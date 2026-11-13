-- =============================================================================
-- ANESOP 数据库迁移 V8：M1 术间与排程台次（M2-M2-01）
--
-- 设计依据：
--   方案书 §2.4.2（M1 排程：术间/台次/优先级/资质规则、冲突检测）、§2.3.2（急诊优先）
--   方案书 §3.4.2（排程/台次实体）、§3.4.3（表/索引/约束命名、timestamptz）
--   方案书 §3.9.2（RBAC 与数据权限：DEPT/ROOM 范围）
--   契约：packages/contracts/openapi/scheduling.yaml 0.4.0
--   任务卡：docs/ai/tasks/M2-backlog.md M2-M2-01（排程工作台与冲突检测）
--
-- 内容（expand 段：只新增表/列/索引/权限点，不改写既有列与既有数据）：
--   1) m1_sched.operating_room：术间主数据（编号院内唯一、名称、类型
--      GENERAL/HYBRID/INTERVENTION、运行状态、所属科室、院区、审计字段/乐观锁）。
--   2) m1_sched.schedule：排程台次（申请 FK、术间 FK、start_at/end_at timestamptz、
--      sequence、状态、原因、审计字段/乐观锁）；ck_schedule_time_window 保证 end_at > start_at。
--   3) 索引：room_id+start_at（同室重叠查询/日排程）、application_id（申请反查）、
--      status（工作台过滤）、dept_id（DEPT 数据范围）。
--      另建部分唯一索引 uk_schedule_application_active：同一申请同时只允许一个未取消台次，
--      并发重复排程由数据库兜底（业务层另做事务内复检）。
--   4) surgery_application 增加可选列 required_room_type（申请要求的术间类型；NULL=不限），
--      供排程规则 ROOM_TYPE_MISMATCH 使用。列可空，旧数据无需回填（expand）。
--   5) 权限点 m1:schedule:read / m1:schedule:write（三段式编码）：
--      read 授予 SYS_ADMIN、SYS_OR_MANAGER、SYS_SURGEON（临床只读：术间与日排程查看；
--      实际可见范围仍按 SELF/ROOM 数据范围 fail-closed）；write 仅授予 SYS_ADMIN、
--      SYS_OR_MANAGER（最小权限：外科医师/麻醉医师不直接改排程）。V7 的
--      m1:application:* / m1:patient:read 授权不变。
--   6) 5 个 SYNTHETIC 术间种子（含 1 个 HYBRID、1 个 INTERVENTION）：
--      c1–c4 属“麻醉科（合成）”，c5 属“手术室（合成）”，用于 DEPT 数据范围正/负例；
--      room_id 固定 UUID，供 m0 sys_user_room（ROOM 范围）绑定与测试复用。
--
-- 安全与临床约定：
--   - 术间编号、名称、科室均为演示合成数据（SYNTHETIC），禁止写入真实院区/科室名称。
--   - 排程不物理删除：取消写 status=CANCELLED + reason，保留审计链。
--   - 同术间不重叠由应用层“锁定术间行（SELECT ... FOR UPDATE）+ 事务内复检”保证；
--     跨术间的术者/患者维度由事务级 advisory lock（pg_advisory_xact_lock，确定性排序获取）
--     串行化；数据库层用 ck_schedule_time_window 与部分唯一索引作为第二道防线。
--   - 数据范围：schedule 的 room_id → operating_room；DEPT/SITE 过滤看 operating_room.dept_id/
--     site_id，ROOM 过滤直接看 room_id（与 V5 sys_user_room 逻辑外键对应）。
--
-- 迁移纪律：本脚本已合入后禁止修改；变更一律新增 V<版本> 脚本（expand-contract）。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.3）：
--   1) 应用回滚：停止读写排程工作台（M1 排程路由下线），申请/患者能力不受影响；
--   2) 库层前向修复：DROP TABLE m1_sched.schedule；DROP TABLE m1_sched.operating_room；
--      ALTER TABLE m1_sched.surgery_application DROP COLUMN required_room_type；
--      DELETE 两个权限点及其授权；
--   3) 回滚前备份 m1_sched.schedule 与 operating_room，并在演练库验证；生产执行须人签字。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. operating_room 术间主数据
-- -----------------------------------------------------------------------------
CREATE TABLE m1_sched.operating_room (
    id                 uuid         NOT NULL DEFAULT gen_random_uuid(),
    room_no            varchar(32)  NOT NULL,
    name               varchar(100) NOT NULL,
    room_type          varchar(16)  NOT NULL,
    status             varchar(16)  NOT NULL DEFAULT 'IDLE',
    dept_name          varchar(100) NOT NULL,
    dept_id            uuid,
    site_id            uuid,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    updated_by         uuid,
    version            bigint       NOT NULL DEFAULT 0,
    deleted            boolean      NOT NULL DEFAULT false,
    CONSTRAINT pk_operating_room PRIMARY KEY (id),
    CONSTRAINT ck_operating_room_type
        CHECK (room_type IN ('GENERAL', 'HYBRID', 'INTERVENTION')),
    CONSTRAINT ck_operating_room_status
        CHECK (status IN ('IDLE', 'PREPARING', 'ANESTHESIA', 'SURGERY', 'PACU', 'CLEANING'))
);

-- 术间编号院内唯一；软删除不释放编号，避免历史排程指向歧义。
CREATE UNIQUE INDEX uk_operating_room_room_no ON m1_sched.operating_room (room_no);
CREATE INDEX idx_operating_room_dept_id ON m1_sched.operating_room (dept_id);
CREATE INDEX idx_operating_room_site_id ON m1_sched.operating_room (site_id);
CREATE INDEX idx_operating_room_status ON m1_sched.operating_room (status);
CREATE INDEX idx_operating_room_deleted ON m1_sched.operating_room (deleted);

COMMENT ON TABLE m1_sched.operating_room IS
    '术间主数据：编号院内唯一，类型 GENERAL/HYBRID/INTERVENTION，状态按契约运行态；仅 M1 读写';
COMMENT ON COLUMN m1_sched.operating_room.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m1_sched.operating_room.room_no IS '术间编号（院内唯一，如 OR-01）';
COMMENT ON COLUMN m1_sched.operating_room.name IS '术间名称（演示合成文本）';
COMMENT ON COLUMN m1_sched.operating_room.room_type IS
    '术间类型：GENERAL=普通，HYBRID=杂交，INTERVENTION=介入；用于申请要求匹配规则';
COMMENT ON COLUMN m1_sched.operating_room.status IS
    '术间运行状态：IDLE/PREPARING/ANESTHESIA/SURGERY/PACU/CLEANING（契约 OperatingRoom.status）';
COMMENT ON COLUMN m1_sched.operating_room.dept_name IS '所属科室名称（演示合成）';
COMMENT ON COLUMN m1_sched.operating_room.dept_id IS
    '所属科室 UUID，逻辑外键 → m0_platform.sys_org.id；DEPT 数据范围过滤用（不建物理外键）';
COMMENT ON COLUMN m1_sched.operating_room.site_id IS
    '院区 UUID，逻辑外键 → m0_platform.sys_org.id；SITE 数据范围过滤用（不建物理外键）';
COMMENT ON COLUMN m1_sched.operating_room.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m1_sched.operating_room.created_by IS '创建人 UUID';
COMMENT ON COLUMN m1_sched.operating_room.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m1_sched.operating_room.updated_by IS '最后更新人 UUID';
COMMENT ON COLUMN m1_sched.operating_room.version IS '乐观锁版本号（MyBatis-Plus @Version）';
COMMENT ON COLUMN m1_sched.operating_room.deleted IS '软删除标记：true=已删除；术间不物理删除';

-- -----------------------------------------------------------------------------
-- 2. schedule 排程台次
-- -----------------------------------------------------------------------------
CREATE TABLE m1_sched.schedule (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    application_id uuid         NOT NULL,
    room_id        uuid         NOT NULL,
    start_at       timestamptz  NOT NULL,
    end_at         timestamptz  NOT NULL,
    sequence       integer      NOT NULL,
    priority       smallint     NOT NULL DEFAULT 0,
    status         varchar(16)  NOT NULL DEFAULT 'PLANNED',
    reason         varchar(500),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    updated_by     uuid,
    version        bigint       NOT NULL DEFAULT 0,
    deleted        boolean      NOT NULL DEFAULT false,
    CONSTRAINT pk_schedule PRIMARY KEY (id),
    CONSTRAINT fk_schedule_application FOREIGN KEY (application_id)
        REFERENCES m1_sched.surgery_application (id) ON DELETE RESTRICT,
    CONSTRAINT fk_schedule_room FOREIGN KEY (room_id)
        REFERENCES m1_sched.operating_room (id) ON DELETE RESTRICT,
    CONSTRAINT ck_schedule_time_window CHECK (end_at > start_at),
    CONSTRAINT ck_schedule_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_schedule_priority CHECK (priority BETWEEN 0 AND 2),
    CONSTRAINT ck_schedule_status
        CHECK (status IN ('PLANNED', 'PUBLISHED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'DELAYED'))
);

-- 同术间按开始时间排日程序列（重叠检测 WHERE room_id=? AND start_at < ? AND end_at > ?）。
CREATE INDEX idx_schedule_room_start ON m1_sched.schedule (room_id, start_at);
CREATE INDEX idx_schedule_application_id ON m1_sched.schedule (application_id);
CREATE INDEX idx_schedule_status ON m1_sched.schedule (status);
CREATE INDEX idx_schedule_start_at ON m1_sched.schedule (start_at);
CREATE INDEX idx_schedule_deleted ON m1_sched.schedule (deleted);
-- 同一申请同时只允许一个未取消台次（并发重复排程的数据库兜底）。
CREATE UNIQUE INDEX uk_schedule_application_active
    ON m1_sched.schedule (application_id)
    WHERE status <> 'CANCELLED' AND deleted = false;

COMMENT ON TABLE m1_sched.schedule IS
    '排程台次：申请+术间+计划时段+台次序号；同术间时段不得重叠，取消保留原因与审计';
COMMENT ON COLUMN m1_sched.schedule.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m1_sched.schedule.application_id IS
    '手术申请 UUID，FK → m1_sched.surgery_application.id；未取消台次唯一';
COMMENT ON COLUMN m1_sched.schedule.room_id IS
    '术间 UUID，FK → m1_sched.operating_room.id；同室不重叠由事务内锁+复检保证';
COMMENT ON COLUMN m1_sched.schedule.start_at IS '计划开始时间（timestamptz，含时区语义）';
COMMENT ON COLUMN m1_sched.schedule.end_at IS '计划结束时间（timestamptz），必须晚于 start_at';
COMMENT ON COLUMN m1_sched.schedule.sequence IS '台次序号（同术间当日排序，从 1 开始）';
COMMENT ON COLUMN m1_sched.schedule.priority IS
    '优先级：0=ELECTIVE，1=URGENT，2=EMERGENCY（契约枚举映射；创建时取 CreateScheduleRequest.priority）';
COMMENT ON COLUMN m1_sched.schedule.status IS
    '台次状态：PLANNED/PUBLISHED/IN_PROGRESS/COMPLETED/CANCELLED/DELAYED；CANCELLED 为终态';
COMMENT ON COLUMN m1_sched.schedule.reason IS '最近一次调整/取消原因（演示文本，不含患者标识）';
COMMENT ON COLUMN m1_sched.schedule.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m1_sched.schedule.created_by IS '创建人 UUID';
COMMENT ON COLUMN m1_sched.schedule.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m1_sched.schedule.updated_by IS '最后更新人 UUID';
COMMENT ON COLUMN m1_sched.schedule.version IS '乐观锁版本号：创建为 1；调整/取消必须回传，过期 409';
COMMENT ON COLUMN m1_sched.schedule.deleted IS '软删除标记：true=已删除；排程取消不物理删除';

-- -----------------------------------------------------------------------------
-- 3. 申请要求术间类型（可选，expand：可空列，不回填）
--    NULL=不限；有值时排程规则要求候选术间 room_type 一致。
-- -----------------------------------------------------------------------------
ALTER TABLE m1_sched.surgery_application
    ADD COLUMN required_room_type varchar(16);

ALTER TABLE m1_sched.surgery_application
    ADD CONSTRAINT ck_surgery_application_required_room_type
    CHECK (required_room_type IS NULL OR required_room_type IN ('GENERAL', 'HYBRID', 'INTERVENTION'));

COMMENT ON COLUMN m1_sched.surgery_application.required_room_type IS
    '申请要求的术间类型（可选）：GENERAL/HYBRID/INTERVENTION；NULL=不限；排程规则 ROOM_TYPE_MISMATCH 用';

-- -----------------------------------------------------------------------------
-- 4. 权限点 m1:schedule:read / m1:schedule:write
--    read 授予 SYS_ADMIN、SYS_OR_MANAGER；write 仅授予 SYS_ADMIN、SYS_OR_MANAGER（最小权限）。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort) VALUES
    ('b0000000-0000-4000-8000-000000000027', 'm1:schedule:read', 'M1 排程工作台只读', 'API',
     '/api/v1/scheduling/rooms', 'GET', NULL, 118),
    ('b0000000-0000-4000-8000-000000000028', 'm1:schedule:write', 'M1 排程工作台维护', 'API',
     '/api/v1/scheduling/schedules', 'POST', NULL, 119);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：全院排程读写
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000027'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000028'),
    -- SYS_OR_MANAGER（DEPT 数据范围）：本科室术间排程读写
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000027'),
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000028'),
    -- SYS_SURGEON（SELF 数据范围）：排程工作台只读（临床只读合理；不授 write）
    ('a0000000-0000-4000-8000-000000000004', 'b0000000-0000-4000-8000-000000000027');

-- -----------------------------------------------------------------------------
-- 5. SYNTHETIC 术间种子（5 间，含 1 HYBRID、1 INTERVENTION）
--    c1–c4：麻醉科（f1000000-...0002）；c5：手术室（f1000000-...0003），
--    用于 DEPT 数据范围正例（麻醉科可见 4 间）与负例（不可见手术室 c5）。
-- -----------------------------------------------------------------------------
INSERT INTO m1_sched.operating_room
    (id, room_no, name, room_type, status, dept_name, dept_id, site_id, created_by, updated_by,
     version, deleted)
VALUES
    ('f2000000-0000-4000-8000-0000000000c1', 'OR-01', '演示 1 号手术间（合成）', 'GENERAL', 'IDLE',
     '麻醉科（合成）', 'f1000000-0000-4000-8000-000000000002',
     'f1000000-0000-4000-8000-000000000001', NULL, NULL, 1, false),
    ('f2000000-0000-4000-8000-0000000000c2', 'OR-02', '演示 2 号杂交手术间（合成）', 'HYBRID', 'IDLE',
     '麻醉科（合成）', 'f1000000-0000-4000-8000-000000000002',
     'f1000000-0000-4000-8000-000000000001', NULL, NULL, 1, false),
    ('f2000000-0000-4000-8000-0000000000c3', 'OR-03', '演示 3 号介入手术间（合成）', 'INTERVENTION', 'IDLE',
     '麻醉科（合成）', 'f1000000-0000-4000-8000-000000000002',
     'f1000000-0000-4000-8000-000000000001', NULL, NULL, 1, false),
    ('f2000000-0000-4000-8000-0000000000c4', 'OR-04', '演示 4 号手术间（合成）', 'GENERAL', 'IDLE',
     '麻醉科（合成）', 'f1000000-0000-4000-8000-000000000002',
     'f1000000-0000-4000-8000-000000000001', NULL, NULL, 1, false),
    ('f2000000-0000-4000-8000-0000000000c5', 'OR-05', '演示 5 号手术间（合成）', 'GENERAL', 'IDLE',
     '手术室（合成）', 'f1000000-0000-4000-8000-000000000003',
     'f1000000-0000-4000-8000-000000000001', NULL, NULL, 1, false);
