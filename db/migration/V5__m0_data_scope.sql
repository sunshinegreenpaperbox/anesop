-- =============================================================================
-- ANESOP 数据库迁移 V5：M0 数据权限范围绑定表（M2-M0-01）
--
-- 设计依据：
--   方案书 §3.9.2（RBAC 与数据权限：全院/院区/科室/术间/本人患者，越权记审计）
--   方案书 §3.4.3（表/索引/唯一索引命名、timestamptz、逻辑外键）、§2.4.1（M0 公共支撑）
--   契约：packages/contracts/openapi/auth.yaml 0.4.0（DataScope.scopeIds）
--   任务卡：docs/ai/tasks/M2-backlog.md M2-M0-01（数据范围绑定表与解析）
--
-- 内容（expand-contract 的 expand 段：只新增表/索引/权限点，不改既有列）：
--   1) sys_org：组织机构（org_type = SITE 院区 / DEPT 科室），树形（parent_id + path）。
--      M0 只建“数据范围用的组织骨架”；人员资质/排班主数据仍在 M3，术间主数据在 M1。
--   2) sys_user_org：用户—组织绑定（数据范围 SITE/DEPT 的 scopeIds 来源），
--      复合主键防重复授权；is_primary 标记主科室，一个用户最多一个主科室。
--   3) sys_user_room：用户—术间绑定（数据范围 ROOM 的 scopeIds 来源）。
--      术间主数据由 M2-M2-01 在 m1_sched 建立，因此 room_id 为逻辑外键（不建物理外键）。
--   4) 权限点 m0:org:read / m0:org:write（组织维护，M2 组织 CRUD 使用），仅授予 SYS_ADMIN，
--      不改变既有角色与既有种子授权（不破坏 V2/V3 基线）。
--
-- 迁移纪律：本脚本已合入后禁止修改；变更一律新增 V<版本> 脚本（expand-contract）。
-- 回滚策略：forward-only。回滚 = 应用侧停止读取绑定表（数据范围 fail-closed 到 NONE）
--           + 前向修复脚本；不删除本表（删除会丢失数据范围证据，且 Flyway 不执行 undo）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. sys_org 组织机构（数据范围对象：SITE 院区 / DEPT 科室）
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_org (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    code       varchar(64)  NOT NULL,
    name       varchar(100) NOT NULL,
    org_type   varchar(16)  NOT NULL,
    parent_id  uuid,
    path       varchar(512) NOT NULL,
    sort       integer      NOT NULL DEFAULT 0,
    status     smallint     NOT NULL DEFAULT 1,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by uuid,
    updated_at timestamptz  NOT NULL DEFAULT now(),
    updated_by uuid,
    version    bigint       NOT NULL DEFAULT 0,
    deleted    boolean      NOT NULL DEFAULT false,
    CONSTRAINT pk_sys_org PRIMARY KEY (id),
    CONSTRAINT ck_sys_org_org_type CHECK (org_type IN ('SITE', 'DEPT')),
    CONSTRAINT ck_sys_org_status CHECK (status IN (0, 1)),
    CONSTRAINT ck_sys_org_path CHECK (path LIKE '/%'),
    CONSTRAINT fk_sys_org_parent FOREIGN KEY (parent_id)
        REFERENCES m0_platform.sys_org (id) ON DELETE RESTRICT
);

-- 组织编码全局唯一；软删除不释放编码，避免历史审计与范围绑定指向歧义（与 sys_user.username 一致）。
CREATE UNIQUE INDEX uk_sys_org_code ON m0_platform.sys_org (code);
CREATE INDEX idx_sys_org_parent_id ON m0_platform.sys_org (parent_id);
-- 子树查询（path 前缀）与按类型取范围对象（数据范围解析只取启用的 SITE/DEPT）。
CREATE INDEX idx_sys_org_path ON m0_platform.sys_org (path);
CREATE INDEX idx_sys_org_type_status ON m0_platform.sys_org (org_type, status);
CREATE INDEX idx_sys_org_deleted ON m0_platform.sys_org (deleted);

COMMENT ON TABLE m0_platform.sys_org IS
    '组织机构（数据范围对象）：SITE=院区，DEPT=科室；树形结构用 parent_id + path，仅 M0 读写（§3.9.2）';
COMMENT ON COLUMN m0_platform.sys_org.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m0_platform.sys_org.code IS
    '组织编码，全局唯一；软删除后不释放（历史审计/绑定仍指向同一编码）';
COMMENT ON COLUMN m0_platform.sys_org.name IS '组织名称（中文，界面展示）';
COMMENT ON COLUMN m0_platform.sys_org.org_type IS '组织类型：SITE=院区，DEPT=科室；与数据范围 SITE/DEPT 对应';
COMMENT ON COLUMN m0_platform.sys_org.parent_id IS
    '父组织 UUID，FK → sys_org.id；院区为根（NULL），科室挂在院区下';
COMMENT ON COLUMN m0_platform.sys_org.path IS
    '层级路径（以 / 开头的编码串，如 /SITE-01/ANES），供子树前缀查询与展示；不参与权限判定';
COMMENT ON COLUMN m0_platform.sys_org.sort IS '同级排序号，升序展示';
COMMENT ON COLUMN m0_platform.sys_org.status IS '状态：1=启用，0=停用；停用组织不再进入数据范围解析';
COMMENT ON COLUMN m0_platform.sys_org.created_at IS '创建时间（timestamptz，服务端时间）';
COMMENT ON COLUMN m0_platform.sys_org.created_by IS '创建人 UUID；系统初始化可为空';
COMMENT ON COLUMN m0_platform.sys_org.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_org.updated_by IS '最后更新人 UUID；系统任务可为空';
COMMENT ON COLUMN m0_platform.sys_org.version IS '乐观锁版本号（MyBatis-Plus @Version）';
COMMENT ON COLUMN m0_platform.sys_org.deleted IS '软删除标记：true=已删除；组织不物理删除，保留历史指向';

-- -----------------------------------------------------------------------------
-- 2. sys_user_org 用户—组织绑定（数据范围 SITE/DEPT 的 scopeIds 来源）
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_user_org (
    user_id    uuid        NOT NULL,
    org_id     uuid        NOT NULL,
    is_primary boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    CONSTRAINT pk_sys_user_org PRIMARY KEY (user_id, org_id),
    CONSTRAINT fk_sys_user_org_user FOREIGN KEY (user_id)
        REFERENCES m0_platform.sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_user_org_org FOREIGN KEY (org_id)
        REFERENCES m0_platform.sys_org (id) ON DELETE CASCADE
);

-- 复合主键已覆盖 user_id 前缀查询；为反向查询（组织→成员）单独建索引。
CREATE INDEX idx_sys_user_org_org_id ON m0_platform.sys_user_org (org_id);
-- 主科室唯一：一个用户最多一条 is_primary=true；未绑定或全部为 false 时按无主科室处理。
CREATE UNIQUE INDEX uk_sys_user_org_primary
    ON m0_platform.sys_user_org (user_id) WHERE is_primary;

COMMENT ON TABLE m0_platform.sys_user_org IS
    '用户—组织绑定（多对多）：数据范围 SITE/DEPT 的 scopeIds 来源；未绑定时解析 fail-closed 为 NONE';
COMMENT ON COLUMN m0_platform.sys_user_org.user_id IS '用户 UUID，FK → sys_user.id；用户删除时级联清理';
COMMENT ON COLUMN m0_platform.sys_user_org.org_id IS '组织 UUID，FK → sys_org.id；组织删除时级联清理';
COMMENT ON COLUMN m0_platform.sys_user_org.is_primary IS
    '是否主科室：true=主科室（每用户最多一条，唯一索引 uk_sys_user_org_primary）';
COMMENT ON COLUMN m0_platform.sys_user_org.created_at IS '绑定时间（timestamptz，服务端时间）';
COMMENT ON COLUMN m0_platform.sys_user_org.created_by IS '授权操作人 UUID；系统初始化可为空';

-- -----------------------------------------------------------------------------
-- 3. sys_user_room 用户—术间绑定（数据范围 ROOM 的 scopeIds 来源）
--    术间主数据由 M2-M2-01 在 m1_sched 建立，跨模块关系按 §3.4.3 使用逻辑外键：
--    room_id 不建物理外键，避免 M0 与 M1 的 schema 循环依赖。
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_user_room (
    user_id    uuid        NOT NULL,
    room_id    uuid        NOT NULL,
    status     smallint    NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid,
    CONSTRAINT pk_sys_user_room PRIMARY KEY (user_id, room_id),
    CONSTRAINT fk_sys_user_room_user FOREIGN KEY (user_id)
        REFERENCES m0_platform.sys_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_sys_user_room_status CHECK (status IN (0, 1))
);

-- 复合主键已覆盖 user_id 前缀查询；为反向查询（术间→授权用户）单独建索引。
CREATE INDEX idx_sys_user_room_room_id ON m0_platform.sys_user_room (room_id);
CREATE INDEX idx_sys_user_room_status ON m0_platform.sys_user_room (status);

COMMENT ON TABLE m0_platform.sys_user_room IS
    '用户—术间绑定：数据范围 ROOM 的 scopeIds 来源；唯一 (user_id, room_id)，status=0 视为已撤销授权';
COMMENT ON COLUMN m0_platform.sys_user_room.user_id IS '用户 UUID，FK → sys_user.id；用户删除时级联清理';
COMMENT ON COLUMN m0_platform.sys_user_room.room_id IS
    '术间 UUID，逻辑外键 → m1_sched 术间主数据（M2-M2-01 建立）；M0 不建物理外键';
COMMENT ON COLUMN m0_platform.sys_user_room.status IS '授权状态：1=生效，0=已撤销；只解析 status=1 的绑定';
COMMENT ON COLUMN m0_platform.sys_user_room.created_at IS '授权时间（timestamptz，服务端时间）';
COMMENT ON COLUMN m0_platform.sys_user_room.created_by IS '授权操作人 UUID；系统初始化可为空';
COMMENT ON COLUMN m0_platform.sys_user_room.updated_at IS '最后更新时间（timestamptz）；撤销/恢复授权时更新';
COMMENT ON COLUMN m0_platform.sys_user_room.updated_by IS '最后更新人 UUID；系统任务可为空';

-- -----------------------------------------------------------------------------
-- 4. 权限点 m0:org:read / m0:org:write（组织维护，M2 组织 CRUD 使用）
--    编码统一三段式 <模块>:<资源>:<动作>；仅授予 SYS_ADMIN，不改变 V2/V3 既有授权。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort) VALUES
    ('b0000000-0000-4000-8000-000000000020', 'm0:org:read', 'M0 组织机构只读', 'API',
     '/api/v1/orgs', 'GET', NULL, 111),
    ('b0000000-0000-4000-8000-000000000021', 'm0:org:write', 'M0 组织机构维护', 'API',
     '/api/v1/orgs', 'POST', NULL, 112);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000020'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000021');
