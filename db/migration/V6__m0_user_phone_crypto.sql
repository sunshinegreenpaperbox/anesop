-- =============================================================================
-- ANESOP 数据库迁移 V6：M0 用户手机号加解密写路径与用户管理（M2-M0-02）
--
-- 设计依据：
--   方案书 §3.9.3（字段加密与脱敏：手机号/身份证等敏感字段应用层加密，展示脱敏）
--   方案书 §3.9.1（安全审计不可删除、敏感操作留痕）、§3.9.2（RBAC 与数据权限）
--   方案书 §3.4.3（表/索引/约束命名、timestamptz、expand-contract）
--   契约：packages/contracts/openapi/auth.yaml 0.5.0（用户管理端点 + phoneMasked）
--   任务卡：docs/ai/tasks/M2-backlog.md M2-M0-02（手机号加密/hash 写路径与用户管理）
--
-- 内容（expand-contract 的 expand 段：只新增列/约束/索引/权限点，不改既有列语义）：
--   1) sys_user.phone_key_id：手机号密文所用字段密钥的版本标识（≤32 字符，如 v1）。
--      应用运行期只持当前字段密钥：行内 key_id 与当前 field-key-id 不等即拒绝解密（读路径降级
--      为不展示）。密钥轮换需 M1 交付的离线双密钥重加密工具，应用内没有 key_id → 历史密钥映射；
--      key_id 不是密钥本身，可入库、可入日志（值域为受控短标识）。
--   2) sys_user.phone_updated_at：手机号密文/哈希最后一次写入时间（轮换与审计追溯）。
--   3) 一致性约束 ck_sys_user_phone_crypto：phone_enc / phone_hash / phone_key_id /
--      phone_updated_at 必须同时为空或同时非空，从库层杜绝“只写密文不写 hash”或
--      “换了明文但 key_id 未更新”的脏数据（写路径见 PhoneCrypto/PhoneHasher）。
--   4) phone_hash 等值检索索引（V3 只建列未建索引）：手机号精确查询/去重使用
--      HMAC-SHA-256 十六进制哈希，部分索引只覆盖非空值。
--   5) 权限点 m0:user:read / m0:user:write（三段式 <模块>:<资源>:<动作>），
--      仅授予 SYS_ADMIN；V2 的历史权限点 user:read / user:write 保留不动（见下方说明）。
--
-- 关于 V2 旧权限点 user:read / user:write：
--   V2 的 <模块>:<动作> 形式（user:read/user:write）是 M1 收口前的历史编码，已随契约与
--   V2 基线写入数据库并可能被既有角色/前端引用。按 expand-contract 纪律，本版本不删除、
--   不改语义，只新增三段式权限点；两者并存期间，用户管理端点只检查 m0:user:read /
--   m0:user:write（更细粒度）。M1 完成“权限码三段式收敛”后，再以新的 V 版本（只删授权、
--   不删权限定义）下掉旧权限点，删除前置条件：全量角色授权盘点 + 前端菜单/按钮引用清零。
--
-- 迁移纪律：本脚本已合入后禁止修改；变更一律新增 V<版本> 脚本（expand-contract）。
-- 回滚策略（forward-only，仓库无 undo 脚本）：
--   - 应用回滚：把 phone_key_id / phone_updated_at 的读写回退到不写（旧版本应用不识别新列，
--     列可空、约束对“全空”放行，因此旧应用仍可运行）；
--   - 库层回滚：前向修复脚本 DROP CONSTRAINT ck_sys_user_phone_crypto → DROP INDEX
--     idx_sys_user_phone_hash → ALTER TABLE ... DROP COLUMN phone_key_id / phone_updated_at，
--     并 DELETE 两个新权限点与其授权；执行前须备份并演练（见 docs/db/README.md §4）；
--   - 已加密手机号无法回退为明文（单向设计）；回滚后如需保留数据，只能由受控离线工具
--     按 key_id 选择历史密钥解密导出到受控介质，不得落回 sys_user 明文列。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. sys_user 手机号密文元数据（phone_enc / phone_hash 已由 V1 / V3 就绪，不重复建）
-- -----------------------------------------------------------------------------
ALTER TABLE m0_platform.sys_user ADD COLUMN phone_key_id varchar(32);
ALTER TABLE m0_platform.sys_user ADD COLUMN phone_updated_at timestamptz;

COMMENT ON COLUMN m0_platform.sys_user.phone_key_id IS
    '手机号密文所用字段密钥版本标识（≤32 字符，如 v1）；应用只持当前密钥，旧行仅由 M1 离线工具按版本重加密';
COMMENT ON COLUMN m0_platform.sys_user.phone_updated_at IS
    '手机号密文/哈希最后写入时间（timestamptz）；创建或修改手机号时与 hash/key_id 同事务更新';

-- 四列同生同灭：避免“只有密文没有 hash”“改了明文但 key_id 未更新”等轮换/检索不一致。
ALTER TABLE m0_platform.sys_user
    ADD CONSTRAINT ck_sys_user_phone_crypto CHECK (
        (phone_enc IS NULL AND phone_hash IS NULL
            AND phone_key_id IS NULL AND phone_updated_at IS NULL)
        OR (phone_enc IS NOT NULL AND phone_hash IS NOT NULL
            AND phone_key_id IS NOT NULL AND phone_updated_at IS NOT NULL)
    );

COMMENT ON CONSTRAINT ck_sys_user_phone_crypto ON m0_platform.sys_user IS
    '手机号密文四列一致性：phone_enc/phone_hash/phone_key_id/phone_updated_at 必须同时为空或同时非空';

-- 手机号精确查询/去重走 HMAC 哈希等值匹配；部分索引只覆盖已填手机号的用户。
CREATE INDEX idx_sys_user_phone_hash
    ON m0_platform.sys_user (phone_hash) WHERE phone_hash IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 2. 权限点 m0:user:read / m0:user:write（用户管理端点，仅 SYS_ADMIN）
--    端点路径与契约 auth.yaml 0.5.0 的 /v1/auth/users 一致；API 前缀 /api 由网关统一加。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort) VALUES
    ('b0000000-0000-4000-8000-000000000022', 'm0:user:read', 'M0 用户管理只读', 'API',
     '/api/v1/auth/users', 'GET', NULL, 113),
    ('b0000000-0000-4000-8000-000000000023', 'm0:user:write', 'M0 用户管理维护', 'API',
     '/api/v1/auth/users', 'POST', NULL, 114);

-- 只有平台系统管理员（ALL 数据范围）可读写用户：用户管理涉及账号、角色与手机号，
-- 属核心域权限，不随科室/术间管理员扩散。
INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000022'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000023');
