-- =============================================================================
-- ANESOP 数据库迁移 V3：M0 认证与权限骨架（S0-07）
--
-- 设计依据：
--   方案书 §3.9.1（身份鉴别/失败处理/会话）、§3.9.2（RBAC 与数据权限）、
--   §3.9.5（OAuth2/JWT：短时效令牌 + 刷新令牌）、§2.4.1（M0 公共支撑）
--   契约：packages/contracts/openapi/auth.yaml 0.3.0（login/refresh/logout/password/me/sessions）
--
-- 内容：
--   1) sys_refresh_token：刷新令牌服务端状态（DB 存储，不依赖 Redis）；
--      只存 SHA-256 哈希，不存明文令牌；轮换时旧行 revoked_at 置值。
--   2) 权限点 m0:auth:read / m0:auth:write：M0 认证子域的读写操作位（S0-07 示例授权点，
--      三段式 <模块>:<资源>:<动作>）。
--   3) 仅授予 SYS_ADMIN，其他角色保持最小权限（越权 403 用例依赖此差异）。
--
-- 迁移纪律：本脚本已合入后禁止修改；变更一律新增 V<版本> 脚本（expand-contract）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. sys_refresh_token 刷新令牌（服务端会话状态）
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_refresh_token (
    id          uuid         NOT NULL DEFAULT gen_random_uuid(),
    user_id     uuid         NOT NULL,
    token_hash  varchar(64)  NOT NULL,
    issued_at   timestamptz  NOT NULL DEFAULT now(),
    expires_at  timestamptz  NOT NULL,
    revoked_at  timestamptz,
    client_info varchar(512),
    CONSTRAINT pk_sys_refresh_token PRIMARY KEY (id),
    CONSTRAINT fk_sys_refresh_token_user FOREIGN KEY (user_id)
        REFERENCES m0_platform.sys_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_sys_refresh_token_expires CHECK (expires_at > issued_at)
);

-- 令牌哈希全局唯一：同一令牌只能存在一条有效记录，轮换后旧哈希保留以便审计。
CREATE UNIQUE INDEX uk_sys_refresh_token_token_hash
    ON m0_platform.sys_refresh_token (token_hash);
-- 会话列表按用户 + 签发时间倒序分页（GET /v1/auth/sessions）。
CREATE INDEX idx_sys_refresh_token_user_issued_at
    ON m0_platform.sys_refresh_token (user_id, issued_at DESC);
-- 过期清理任务按 expires_at 扫描（M1 定时任务使用）。
CREATE INDEX idx_sys_refresh_token_expires_at
    ON m0_platform.sys_refresh_token (expires_at);

COMMENT ON TABLE m0_platform.sys_refresh_token IS
    '刷新令牌服务端状态：SHA-256 哈希、有效期与撤销时间；轮换即撤销，支持登出与强制下线（§3.9.5）';
COMMENT ON COLUMN m0_platform.sys_refresh_token.id IS '主键 UUID，同时作为会话标识（Session.sessionId）';
COMMENT ON COLUMN m0_platform.sys_refresh_token.user_id IS '所属用户 UUID，FK → sys_user.id；用户删除时级联清理';
COMMENT ON COLUMN m0_platform.sys_refresh_token.token_hash IS
    '刷新令牌 SHA-256 十六进制哈希（64 字符）；禁止存明文令牌';
COMMENT ON COLUMN m0_platform.sys_refresh_token.issued_at IS '签发时间（timestamptz，服务端时间）';
COMMENT ON COLUMN m0_platform.sys_refresh_token.expires_at IS '过期时间（timestamptz）；默认签发后 7 天';
COMMENT ON COLUMN m0_platform.sys_refresh_token.revoked_at IS
    '撤销时间（timestamptz）；NULL=有效。刷新轮换、登出、强制下线时置值，旧令牌立即失效';
COMMENT ON COLUMN m0_platform.sys_refresh_token.client_info IS
    '客户端信息 JSON（客户端 IP、User-Agent 摘要）；用于会话列表展示，不含患者信息';

-- -----------------------------------------------------------------------------
-- 2. M0 认证子域读写权限点（S0-07 示例授权点：/v1/auth/sessions 需 m0:auth:read）
--    编码统一三段式 <模块>:<资源>:<动作>（V2 历史权限点为 <模块>:<动作>，M1 起新增统一三段式）
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort) VALUES
    ('b0000000-0000-4000-8000-000000000018', 'm0:auth:read', 'M0 认证子域只读', 'API',
     '/api/v1/auth/sessions', 'GET', NULL, 109),
    ('b0000000-0000-4000-8000-000000000019', 'm0:auth:write', 'M0 认证子域维护', 'API',
     NULL, NULL, NULL, 110);

-- 仅系统管理员获得 M0 读写权限：其他角色调用 /v1/auth/sessions 返回 403 并记审计（越权用例）。
INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000018'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000019');

-- -----------------------------------------------------------------------------
-- 3. sys_user.phone_hash（expand，S-11）：手机号精确检索/去重
--    与 phone_enc（密文）配套：phone_enc 供展示前解密，phone_hash 供等值检索，二者均不入日志。
--    V3 尚未提交，直接在本脚本 expand 加列；写路径（PhoneHashing，HMAC-SHA-256 + 密钥配置）
--    在 M1 落地，本阶段只保证列已就绪。
-- -----------------------------------------------------------------------------
ALTER TABLE m0_platform.sys_user ADD COLUMN phone_hash varchar(128);

COMMENT ON COLUMN m0_platform.sys_user.phone_hash IS
    '手机号 HMAC-SHA-256 哈希（128 字符上限，hex/base64url 均可）；供精确检索与去重，不可逆；写路径 M1';
