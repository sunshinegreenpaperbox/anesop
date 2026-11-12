-- =============================================================================
-- ANESOP 演示种子 V100：demo_admin（SYNTHETIC）
--
-- !!! 仅限 dev/test：本目录是独立 Flyway location，禁止进入生产 locations !!!
--
-- 生产 / staging / uat 的 Flyway locations 只允许：
--   filesystem:/opt/anesop/db/migration
-- 本地演示才追加本目录：
--   filesystem:db/migration,filesystem:db/seed/demo
--
-- 说明：
--   1) 演示账号不属于平台预置基线，故意放在 db/migration 之外，避免生产迁移
--      自动创建高权限账号（S0-06 评审 B-1）。S0-07 生产启动断言会拒绝 demo_* 账号。
--   2) V100 仅保证在本 location 内排在 V1/V2 之后；依赖 V2 已创建的 SYS_ADMIN 角色。
--   3) 口令哈希由 `htpasswd -bnBC 10 "" 'Anesop@2026' | tr -d ':\n'` 生成
--      （BCrypt，cost=10，$2y$ 前缀）；初始口令见 docs/db/seed.md，首次登录强制改密。
--   4) 全部字段为合成数据：固定 UUID、SYN- 工号、无手机号、无真实姓名。
-- =============================================================================

INSERT INTO m0_platform.sys_user (
    id, username, password_hash, display_name, employee_no, phone_enc,
    status, must_change_password, failed_attempts, locked_until, last_login_at,
    created_at, created_by, updated_at, updated_by, version, deleted
) VALUES (
    'e0000000-0000-4000-8000-000000000001',
    'demo_admin',
    '$2y$10$miTTPo10LatZjpihp0Gx0O2pVhVQuPK9HFgz.m7iFbpyyvztia6ue',
    '演示管理员（合成）',
    'SYN-DEMO-0001',
    NULL,
    1, true, 0, NULL, NULL,
    now(), NULL, now(), NULL, 0, false
);

INSERT INTO m0_platform.sys_user_role (user_id, role_id) VALUES
    ('e0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001');
