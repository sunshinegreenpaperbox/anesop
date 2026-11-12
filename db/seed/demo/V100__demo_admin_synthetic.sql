-- =============================================================================
-- ANESOP 演示种子 V100：demo_admin + 数据范围演示账号（SYNTHETIC）
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
--   5) M2-M0-01 追加数据范围演示：SITE/DEPT 组织、demo_admin 院区绑定，
--      demo_dept（SYS_OR_MANAGER，DEPT）绑定麻醉科，demo_self（SYS_ANESTHESIOLOGIST，SELF）
--      按角色生效并附一条术间绑定（角色非 ROOM，不放大范围，用于验证 fail-closed）。
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
),
(
    'e0000000-0000-4000-8000-000000000002',
    'demo_dept',
    '$2y$10$miTTPo10LatZjpihp0Gx0O2pVhVQuPK9HFgz.m7iFbpyyvztia6ue',
    '演示科室管理员（合成）',
    'SYN-DEMO-0002',
    NULL,
    1, true, 0, NULL, NULL,
    now(), NULL, now(), NULL, 0, false
),
(
    'e0000000-0000-4000-8000-000000000003',
    'demo_self',
    '$2y$10$miTTPo10LatZjpihp0Gx0O2pVhVQuPK9HFgz.m7iFbpyyvztia6ue',
    '演示麻醉医师（合成）',
    'SYN-DEMO-0003',
    NULL,
    1, true, 0, NULL, NULL,
    now(), NULL, now(), NULL, 0, false
);

INSERT INTO m0_platform.sys_user_role (user_id, role_id) VALUES
    -- demo_admin：SYS_ADMIN（data_scope=ALL）
    ('e0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001'),
    -- demo_dept：SYS_OR_MANAGER（data_scope=DEPT），配合麻醉科绑定
    ('e0000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-000000000005'),
    -- demo_self：SYS_ANESTHESIOLOGIST（data_scope=SELF），无需组织绑定
    ('e0000000-0000-4000-8000-000000000003', 'a0000000-0000-4000-8000-000000000002');

-- 组织骨架：1 个院区 + 2 个科室（数据范围 SITE/DEPT 的对象）。
INSERT INTO m0_platform.sys_org (id, code, name, org_type, parent_id, path, sort, status) VALUES
    ('f1000000-0000-4000-8000-000000000001', 'SITE-01', '演示院区（合成）', 'SITE',
     NULL, '/SITE-01', 1, 1),
    ('f1000000-0000-4000-8000-000000000002', 'DEPT-ANES', '麻醉科（合成）', 'DEPT',
     'f1000000-0000-4000-8000-000000000001', '/SITE-01/DEPT-ANES', 1, 1),
    ('f1000000-0000-4000-8000-000000000003', 'DEPT-OR', '手术室（合成）', 'DEPT',
     'f1000000-0000-4000-8000-000000000001', '/SITE-01/DEPT-OR', 2, 1);

INSERT INTO m0_platform.sys_user_org
    (user_id, org_id, is_primary, created_at, created_by, updated_at, updated_by) VALUES
    -- demo_admin：院区绑定（ALL 不消费绑定，仅演示数据形状）
    ('e0000000-0000-4000-8000-000000000001', 'f1000000-0000-4000-8000-000000000001',
     true, now(), NULL, now(), NULL),
    -- demo_dept：主科室=麻醉科（DEPT 的 scopeIds 来源）
    ('e0000000-0000-4000-8000-000000000002', 'f1000000-0000-4000-8000-000000000002',
     true, now(), NULL, now(), NULL),
    -- demo_self：组织绑定存在但 SELF 角色不消费（不得放大为 DEPT 可见范围）
    ('e0000000-0000-4000-8000-000000000003', 'f1000000-0000-4000-8000-000000000002',
     false, now(), NULL, now(), NULL);

-- 术间主数据由 M2-M2-01 建立（m1_sched）；此处 room_id 为逻辑外键的合成占位值。
INSERT INTO m0_platform.sys_user_room (user_id, room_id, status) VALUES
    ('e0000000-0000-4000-8000-000000000003', 'f2000000-0000-4000-8000-000000000001', 1);
