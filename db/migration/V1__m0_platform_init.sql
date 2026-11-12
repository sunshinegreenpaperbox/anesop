-- =============================================================================
-- ANESOP 数据库迁移 V1：M0 公共支撑基线
-- 目标库：PostgreSQL 16；schema：m0_platform
--
-- 设计依据：
--   方案书 §3.4.2（核心实体）、§3.4.3（数据库设计策略）、§3.4.5（多院区与时间同步）
--   方案书 §3.9.1（安全审计不可删除、日志留存 ≥ 6 个月）、§3.9.2（RBAC 与数据权限）
--   方案书 §2.4.1（M0 公共支撑：身份、RBAC、字典、审计）
--
-- 关键约定：
--   1) 表名单数、字段小写下划线；索引 idx_<表>_<字段>，唯一索引 uk_<表>_<字段>（§3.4.3）。
--   2) 所有时间列统一 timestamptz，应用层按 Asia/Shanghai 展示（§3.4.3 / §3.4.5）。
--   3) 主键 uuid + gen_random_uuid()（PG 13 起为内核函数，无需 pgcrypto 扩展）。
--      M0 内部关联表使用物理外键保证引用完整性；跨模块关系仍保持逻辑外键（§3.4.3）。
--   4) PG 专有类型（uuid / jsonb / inet）只出现在本 schema，便于 D-04 信创适配时
--      集中映射；适配说明见 docs/db/README.md。
--   5) sys_audit_log 为 append-only：触发器拒绝 UPDATE / DELETE / TRUNCATE，
--      在线留存 ≥ 6 个月（等保三级）；按月分区/归档见 §3.4.4，由后续版本落地。
--
-- 迁移纪律：本脚本已合入后禁止修改；变更一律新增 V<版本> 脚本（expand-contract），
--           回滚 = 备份恢复或前向修复，不使用 undo 脚本（见 docs/db/README.md）。
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS m0_platform;

COMMENT ON SCHEMA m0_platform IS
    'M0 公共支撑 schema：身份、RBAC、审计、字典；仅 M0 读写，其他模块经 m0-api 访问';

-- -----------------------------------------------------------------------------
-- 1. sys_user 用户
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_user (
    id                   uuid         NOT NULL DEFAULT gen_random_uuid(),
    username             varchar(64)  NOT NULL,
    password_hash        varchar(255) NOT NULL,
    display_name         varchar(100) NOT NULL,
    employee_no          varchar(64),
    phone_enc            varchar(512),
    status               smallint     NOT NULL DEFAULT 1,
    must_change_password boolean      NOT NULL DEFAULT true,
    failed_attempts      integer      NOT NULL DEFAULT 0,
    locked_until         timestamptz,
    last_login_at        timestamptz,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    updated_by           uuid,
    version              bigint       NOT NULL DEFAULT 0,
    deleted              boolean      NOT NULL DEFAULT false,
    CONSTRAINT pk_sys_user PRIMARY KEY (id),
    CONSTRAINT ck_sys_user_status CHECK (status IN (0, 1)),
    CONSTRAINT ck_sys_user_failed_attempts CHECK (failed_attempts >= 0)
);

-- 登录名大小写不敏感全局唯一：表达式唯一索引 lower(username)，防止 Admin/admin 并存；
-- 软删除不释放登录名，避免历史审计指向歧义（S0-07 认证服务仍负责规范化输入）。
CREATE UNIQUE INDEX uk_sys_user_username ON m0_platform.sys_user (lower(username));
-- 工号可空，但非空时院内唯一。
CREATE UNIQUE INDEX uk_sys_user_employee_no
    ON m0_platform.sys_user (employee_no) WHERE employee_no IS NOT NULL;
CREATE INDEX idx_sys_user_status ON m0_platform.sys_user (status);
CREATE INDEX idx_sys_user_deleted ON m0_platform.sys_user (deleted);

COMMENT ON TABLE m0_platform.sys_user IS
    '用户账号：登录、口令、失败锁定、软删除与乐观锁；审计四件套由拦截器填充（S0-07）';
COMMENT ON COLUMN m0_platform.sys_user.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m0_platform.sys_user.username IS '登录名，全局唯一；软删除后不释放';
COMMENT ON COLUMN m0_platform.sys_user.password_hash IS
    '口令哈希（BCrypt $2y$）；禁止明文/可逆加密；API 序列化必须忽略';
COMMENT ON COLUMN m0_platform.sys_user.display_name IS '显示姓名';
COMMENT ON COLUMN m0_platform.sys_user.employee_no IS '员工工号，非空时院内唯一；与 M3 人员档案按工号对齐';
COMMENT ON COLUMN m0_platform.sys_user.phone_enc IS
    '手机号密文（应用层 AES-256-GCM，§3.9.3）；明文禁止入库';
COMMENT ON COLUMN m0_platform.sys_user.status IS
    '账号状态：1=启用，0=停用（等保失败处理/离职停用）';
COMMENT ON COLUMN m0_platform.sys_user.must_change_password IS
    '首次登录/重置后必须改密（等保身份鉴别）';
COMMENT ON COLUMN m0_platform.sys_user.failed_attempts IS
    '连续认证失败次数；达 5 次锁定 30 分钟（§3.9.1），成功后清零';
COMMENT ON COLUMN m0_platform.sys_user.locked_until IS '账号锁定截止时间；NULL 表示未锁定';
COMMENT ON COLUMN m0_platform.sys_user.last_login_at IS '最近成功登录时间（服务端时间）';
COMMENT ON COLUMN m0_platform.sys_user.created_at IS '创建时间（timestamptz，服务端时间）';
COMMENT ON COLUMN m0_platform.sys_user.created_by IS '创建人 UUID；系统初始化可为空';
COMMENT ON COLUMN m0_platform.sys_user.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_user.updated_by IS '最后更新人 UUID；系统任务可为空';
COMMENT ON COLUMN m0_platform.sys_user.version IS '乐观锁版本号（MyBatis-Plus @Version，S0-07 装配）';
COMMENT ON COLUMN m0_platform.sys_user.deleted IS '软删除标记：true=已删除；临床与审计数据不物理删除';

-- -----------------------------------------------------------------------------
-- 2. sys_role 角色（RBAC + 数据范围）
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_role (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    code        varchar(64)  NOT NULL,
    name        varchar(100) NOT NULL,
    description varchar(500),
    data_scope  varchar(16)  NOT NULL DEFAULT 'SELF',
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  uuid,
    CONSTRAINT pk_sys_role PRIMARY KEY (id),
    CONSTRAINT ck_sys_role_data_scope
        CHECK (data_scope IN ('ALL', 'SITE', 'DEPT', 'ROOM', 'SELF'))
);

CREATE UNIQUE INDEX uk_sys_role_code ON m0_platform.sys_role (code);

COMMENT ON TABLE m0_platform.sys_role IS
    '角色：功能权限集合 + 数据范围；权限变更即时生效（§3.4.1 平台与身份不变量）';
COMMENT ON COLUMN m0_platform.sys_role.id IS '主键 UUID';
COMMENT ON COLUMN m0_platform.sys_role.code IS '角色编码，全局唯一；预置角色以 SYS_ 前缀标识';
COMMENT ON COLUMN m0_platform.sys_role.name IS '角色名称';
COMMENT ON COLUMN m0_platform.sys_role.description IS '角色说明';
COMMENT ON COLUMN m0_platform.sys_role.data_scope IS
    '数据范围：ALL=全院，SITE=院区，DEPT=科室，ROOM=术间，SELF=本人患者（§3.9.2）';
COMMENT ON COLUMN m0_platform.sys_role.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_role.created_by IS '创建人 UUID';
COMMENT ON COLUMN m0_platform.sys_role.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_role.updated_by IS '最后更新人 UUID';

-- -----------------------------------------------------------------------------
-- 3. sys_permission 权限点（菜单 + 操作 + API）
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_permission (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    code        varchar(128) NOT NULL,
    name        varchar(100) NOT NULL,
    type        varchar(16)  NOT NULL,
    path        varchar(255),
    http_method varchar(16),
    parent_id   uuid,
    sort        integer      NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  uuid,
    CONSTRAINT pk_sys_permission PRIMARY KEY (id),
    CONSTRAINT ck_sys_permission_type CHECK (type IN ('MENU', 'API', 'BUTTON')),
    CONSTRAINT fk_sys_permission_parent FOREIGN KEY (parent_id)
        REFERENCES m0_platform.sys_permission (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uk_sys_permission_code ON m0_platform.sys_permission (code);
CREATE INDEX idx_sys_permission_parent_id ON m0_platform.sys_permission (parent_id);
CREATE INDEX idx_sys_permission_type ON m0_platform.sys_permission (type);

COMMENT ON TABLE m0_platform.sys_permission IS
    '权限点：菜单/按钮/API 三类，粒度到“记录单—锁单”（§3.9.2）；模块占位以 mN:access 编码';
COMMENT ON COLUMN m0_platform.sys_permission.id IS '主键 UUID';
COMMENT ON COLUMN m0_platform.sys_permission.code IS '权限编码，全局唯一，如 role:write、m2:access';
COMMENT ON COLUMN m0_platform.sys_permission.name IS '权限名称（中文，界面展示）';
COMMENT ON COLUMN m0_platform.sys_permission.type IS '权限类型：MENU=菜单，API=接口，BUTTON=按钮操作';
COMMENT ON COLUMN m0_platform.sys_permission.path IS '前端路由或接口路径；无路径的按钮权限可为空';
COMMENT ON COLUMN m0_platform.sys_permission.http_method IS
    'HTTP 方法（API 类型使用）：GET/POST/PUT/PATCH/DELETE；其他类型为空';
COMMENT ON COLUMN m0_platform.sys_permission.parent_id IS '父权限 UUID，形成菜单树；根节点为空';
COMMENT ON COLUMN m0_platform.sys_permission.sort IS '同级排序号，升序展示';
COMMENT ON COLUMN m0_platform.sys_permission.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_permission.created_by IS '创建人 UUID';
COMMENT ON COLUMN m0_platform.sys_permission.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_permission.updated_by IS '最后更新人 UUID';

-- -----------------------------------------------------------------------------
-- 4. sys_user_role 用户—角色关联
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_user_role (
    user_id    uuid        NOT NULL,
    role_id    uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    CONSTRAINT pk_sys_user_role PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id)
        REFERENCES m0_platform.sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id)
        REFERENCES m0_platform.sys_role (id) ON DELETE CASCADE
);

-- 复合主键已覆盖 user_id 前缀查询；为反向查询（角色→用户）单独建索引。
CREATE INDEX idx_sys_user_role_role_id ON m0_platform.sys_user_role (role_id);

COMMENT ON TABLE m0_platform.sys_user_role IS '用户—角色关联（多对多），复合主键防重复授权';
COMMENT ON COLUMN m0_platform.sys_user_role.user_id IS '用户 UUID，FK → sys_user.id';
COMMENT ON COLUMN m0_platform.sys_user_role.role_id IS '角色 UUID，FK → sys_role.id';
COMMENT ON COLUMN m0_platform.sys_user_role.created_at IS '授权时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_user_role.created_by IS '授权操作人 UUID';

-- -----------------------------------------------------------------------------
-- 5. sys_role_permission 角色—权限关联
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_role_permission (
    role_id       uuid        NOT NULL,
    permission_id uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    CONSTRAINT pk_sys_role_permission PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_sys_role_permission_role FOREIGN KEY (role_id)
        REFERENCES m0_platform.sys_role (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_role_permission_permission FOREIGN KEY (permission_id)
        REFERENCES m0_platform.sys_permission (id) ON DELETE CASCADE
);

-- 复合主键已覆盖 role_id 前缀查询；为反向查询（权限→角色）单独建索引。
CREATE INDEX idx_sys_role_permission_permission_id
    ON m0_platform.sys_role_permission (permission_id);

COMMENT ON TABLE m0_platform.sys_role_permission IS '角色—权限关联（多对多），复合主键防重复授权';
COMMENT ON COLUMN m0_platform.sys_role_permission.role_id IS '角色 UUID，FK → sys_role.id';
COMMENT ON COLUMN m0_platform.sys_role_permission.permission_id IS '权限 UUID，FK → sys_permission.id';
COMMENT ON COLUMN m0_platform.sys_role_permission.created_at IS '授权时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_role_permission.created_by IS '授权操作人 UUID';

-- -----------------------------------------------------------------------------
-- 6. sys_audit_log 审计日志（append-only）
--    无 updated_at、无 deleted：审计事实一经写入不可修改。
--    actor_id 不建物理外键：账号可停用/软删除，审计事实必须保留（§3.4.3 逻辑外键）。
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_audit_log (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    actor_id      uuid,
    actor_name    varchar(100),
    action        varchar(64)  NOT NULL,
    resource_type varchar(64),
    resource_id   varchar(128),
    result        smallint     NOT NULL DEFAULT 1,
    detail        jsonb,
    ip            inet,
    user_agent    varchar(512),
    trace_id      varchar(64),
    occurred_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sys_audit_log PRIMARY KEY (id),
    CONSTRAINT ck_sys_audit_log_result CHECK (result IN (0, 1))
);

CREATE INDEX idx_sys_audit_log_occurred_at
    ON m0_platform.sys_audit_log (occurred_at DESC);
CREATE INDEX idx_sys_audit_log_actor_occurred_at
    ON m0_platform.sys_audit_log (actor_id, occurred_at DESC);
-- 对象维度回溯：按资源类型 + 资源标识查询某对象的操作历史（S0-06 评审 S-6）。
CREATE INDEX idx_sys_audit_log_resource
    ON m0_platform.sys_audit_log (resource_type, resource_id, occurred_at DESC);
CREATE INDEX idx_sys_audit_log_trace_id
    ON m0_platform.sys_audit_log (trace_id) WHERE trace_id IS NOT NULL;

COMMENT ON TABLE m0_platform.sys_audit_log IS
    '审计日志：只追加（UPDATE/DELETE/TRUNCATE 被触发器拒绝），含操作人、时间、IP、对象、结果与明细；'
    '在线留存 ≥ 6 个月（等保三级 §3.9.1），按月分区/归档 MinIO 见 §3.4.4';
COMMENT ON COLUMN m0_platform.sys_audit_log.id IS '主键 UUID';
COMMENT ON COLUMN m0_platform.sys_audit_log.actor_id IS
    '操作人 UUID；逻辑关联 sys_user.id，不建物理外键以保留历史';
COMMENT ON COLUMN m0_platform.sys_audit_log.actor_name IS
    '操作人名称快照（账号改名/删除后审计仍可读）';
COMMENT ON COLUMN m0_platform.sys_audit_log.action IS '操作动作，如 LOGIN、USER_CREATE、ROLE_GRANT、RECORD_UNLOCK';
COMMENT ON COLUMN m0_platform.sys_audit_log.resource_type IS '资源类型，如 USER、ROLE、RECORD';
COMMENT ON COLUMN m0_platform.sys_audit_log.resource_id IS '资源标识（字符串以兼容 UUID/雪花 ID/业务号）';
COMMENT ON COLUMN m0_platform.sys_audit_log.result IS '结果：1=成功，0=失败（失败也须留痕，越权访问记审计并告警）';
COMMENT ON COLUMN m0_platform.sys_audit_log.detail IS
    '变更明细 JSONB（前后值、字段级轨迹）；禁止写入患者直接标识明文（§3.9.3）';
COMMENT ON COLUMN m0_platform.sys_audit_log.ip IS '来源 IP（inet，IPv4/IPv6）';
COMMENT ON COLUMN m0_platform.sys_audit_log.user_agent IS '客户端 User-Agent（截断至 512 字符）';
COMMENT ON COLUMN m0_platform.sys_audit_log.trace_id IS '链路追踪 ID，用于与日志/指标关联';
COMMENT ON COLUMN m0_platform.sys_audit_log.occurred_at IS
    '发生时间（timestamptz，服务端时间；客户端时间仅展示，§3.4.5）';

-- 审计表只追加：任何 UPDATE / DELETE / TRUNCATE 在数据库层被拒绝（第二道防线）。
CREATE OR REPLACE FUNCTION m0_platform.fn_sys_audit_log_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'm0_platform.sys_audit_log is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '审计日志只能 INSERT；修订请写新的审计事件，不得改写历史';
END;
$$;

COMMENT ON FUNCTION m0_platform.fn_sys_audit_log_append_only() IS
    '审计日志 append-only 触发器函数：拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000';

CREATE TRIGGER trg_sys_audit_log_no_update
    BEFORE UPDATE ON m0_platform.sys_audit_log
    FOR EACH ROW EXECUTE FUNCTION m0_platform.fn_sys_audit_log_append_only();

CREATE TRIGGER trg_sys_audit_log_no_delete
    BEFORE DELETE ON m0_platform.sys_audit_log
    FOR EACH ROW EXECUTE FUNCTION m0_platform.fn_sys_audit_log_append_only();

CREATE TRIGGER trg_sys_audit_log_no_truncate
    BEFORE TRUNCATE ON m0_platform.sys_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION m0_platform.fn_sys_audit_log_append_only();

-- 运行时账号（S0-07 创建）只授予 SELECT/INSERT；不授予 UPDATE/DELETE。
-- 触发器是表属主/超级用户路径下的第二道防线；归档删除只能由受控的维护流程临时停用触发器，
-- 且必须先写入归档台账（§3.4.4）。

-- -----------------------------------------------------------------------------
-- 7. sys_dict_type 字典类型
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_dict_type (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    code        varchar(64)  NOT NULL,
    name        varchar(100) NOT NULL,
    description varchar(500),
    status      smallint     NOT NULL DEFAULT 1,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  uuid,
    CONSTRAINT pk_sys_dict_type PRIMARY KEY (id),
    CONSTRAINT ck_sys_dict_type_status CHECK (status IN (0, 1))
);

CREATE UNIQUE INDEX uk_sys_dict_type_code ON m0_platform.sys_dict_type (code);

COMMENT ON TABLE m0_platform.sys_dict_type IS
    '字典类型：性别、ASA 分级、手术紧急度、术间状态、病例状态、角色类型等（§2.4.1）';
COMMENT ON COLUMN m0_platform.sys_dict_type.id IS '主键 UUID';
COMMENT ON COLUMN m0_platform.sys_dict_type.code IS '字典类型编码，全局唯一，如 ASA、SEX';
COMMENT ON COLUMN m0_platform.sys_dict_type.name IS '字典类型名称（中文）';
COMMENT ON COLUMN m0_platform.sys_dict_type.description IS '字典类型说明';
COMMENT ON COLUMN m0_platform.sys_dict_type.status IS '状态：1=启用，0=停用';
COMMENT ON COLUMN m0_platform.sys_dict_type.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_dict_type.created_by IS '创建人 UUID';
COMMENT ON COLUMN m0_platform.sys_dict_type.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_dict_type.updated_by IS '最后更新人 UUID';

-- -----------------------------------------------------------------------------
-- 8. sys_dict_item 字典项
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_dict_item (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    dict_type_id uuid         NOT NULL,
    code         varchar(64)  NOT NULL,
    label        varchar(100) NOT NULL,
    sort         integer      NOT NULL DEFAULT 0,
    status       smallint     NOT NULL DEFAULT 1,
    remark       varchar(255),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   uuid,
    CONSTRAINT pk_sys_dict_item PRIMARY KEY (id),
    CONSTRAINT ck_sys_dict_item_status CHECK (status IN (0, 1)),
    CONSTRAINT fk_sys_dict_item_type FOREIGN KEY (dict_type_id)
        REFERENCES m0_platform.sys_dict_type (id) ON DELETE CASCADE
);

-- 同一字典类型内编码唯一；该唯一索引同时覆盖 dict_type_id 前缀，故无需再建 FK 单列索引。
CREATE UNIQUE INDEX uk_sys_dict_item_type_code
    ON m0_platform.sys_dict_item (dict_type_id, code);
CREATE INDEX idx_sys_dict_item_status ON m0_platform.sys_dict_item (status);

COMMENT ON TABLE m0_platform.sys_dict_item IS
    '字典项：同一类型内 code 唯一；字典变更留版本，历史记录按当时版本解释（§2.4.1）';
COMMENT ON COLUMN m0_platform.sys_dict_item.id IS '主键 UUID';
COMMENT ON COLUMN m0_platform.sys_dict_item.dict_type_id IS '字典类型 UUID，FK → sys_dict_type.id';
COMMENT ON COLUMN m0_platform.sys_dict_item.code IS '字典项编码（对外稳定标识），同类型内唯一';
COMMENT ON COLUMN m0_platform.sys_dict_item.label IS '字典项显示名称（中文）';
COMMENT ON COLUMN m0_platform.sys_dict_item.sort IS '同类型内排序号，升序展示';
COMMENT ON COLUMN m0_platform.sys_dict_item.status IS '状态：1=启用，0=停用（停用不删除，保证历史可解释）';
COMMENT ON COLUMN m0_platform.sys_dict_item.remark IS '备注';
COMMENT ON COLUMN m0_platform.sys_dict_item.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_dict_item.created_by IS '创建人 UUID';
COMMENT ON COLUMN m0_platform.sys_dict_item.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_dict_item.updated_by IS '最后更新人 UUID';
