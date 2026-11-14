-- =============================================================================
-- ANESOP 数据库迁移 V22：M4 分级授权治理（M4-M13-01 评审 B-1/B-2/B-4/S-3）
-- 目标库：PostgreSQL 16；schema：m4_staff
--
-- 背景：V21 已交付人员档案/分级授权/排班/培训。独立评审发现授权治理缺口：
--   B-1 授予/撤销未校验审批人资格、未禁止自授、高等级授权未双人审批；
--   B-2 SYS_ANESTHESIOLOGIST 同时持有 m4:staff:write/m4:training:manage，职责过宽；
--   B-4 回溯校验按“最新版本”而非“当时生效版本”，且撤销无生效时点；
--   S-3 资质吊销/过期尚未参与授权判定，V21 注释表述过强。
--
-- 内容（expand 段；V21 已合入不可改，故一律新增 V22，不改 V1–V21）：
--   1) staff_authorization 增加第二审批人与撤销生效字段（append-only 表只新增列，
--      不回写历史行）：second_approver_id/second_approved_at/second_approver_reason/
--      revoked_at/revoked_by；新增 (staff_id, approved_at DESC) 索引供回溯查询；
--      CREATE OR REPLACE 当前版本视图以包含新列（不改 V21 已有列顺序）。
--   2) 权限点 m4:staff:authorize（授权审批）与角色 SYS_AUTH_MANAGER（data_scope=DEPT，
--      授权管理员）：承载 m4:staff:read/write、m4:training:manage、m4:staff:authorize。
--   3) 收紧既有角色：SYS_ANESTHESIOLOGIST 收回 m4:staff:write/m4:training:manage，
--      保留 m4:staff:read（只读）；SYS_ADMIN 追加 m4:staff:authorize（信息科不代替临床
--      审批，但保留排障能力，双人审批规则同样生效）。
--   4) 纠正资质状态注释：REVOKED/过期是否阻断授权判定待 Q-21 确认，当前仅档案/提醒用途。
--
-- 回滚策略（forward-only，详见 docs/db/README.md §4.16）：
--   应用回滚：下线 /v1/staff/{id}/authorizations 的审批治理逻辑前，先恢复 V21 应用版本；
--   V22 新增列对旧代码无影响（旧代码 INSERT 不写新列，值为 NULL）。
--   库层回滚（需 DBA 双签）：删除 V22 授权与角色，并 DROP 新增列与索引；
--   staff_authorization 历史不得删除（append-only），回滚前必须全量归档。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 授权版本：第二审批人与撤销生效时点
--    历史行保持 NULL（旧版本只有第一审批人）；新写入由服务层填值。
-- -----------------------------------------------------------------------------
ALTER TABLE m4_staff.staff_authorization
    ADD COLUMN IF NOT EXISTS second_approver_id uuid,
    ADD COLUMN IF NOT EXISTS second_approved_at timestamptz,
    ADD COLUMN IF NOT EXISTS second_approver_reason varchar(200),
    ADD COLUMN IF NOT EXISTS revoked_at timestamptz,
    ADD COLUMN IF NOT EXISTS revoked_by uuid;

CREATE INDEX IF NOT EXISTS idx_staff_authorization_effective
    ON m4_staff.staff_authorization (staff_id, approved_at DESC);

-- 当前版本视图同步包含 V22 新列（CREATE OR REPLACE 只追加列，不改 V21 已有列顺序）。
CREATE OR REPLACE VIEW m4_staff.staff_authorization_current AS
SELECT DISTINCT ON (staff_id) *
  FROM m4_staff.staff_authorization
 ORDER BY staff_id, version DESC;

COMMENT ON COLUMN m4_staff.staff_authorization.second_approver_id IS
    '第二审批人用户标识（R1/R2、范围*或 ASA≥4 时必填；须与第一审批人、操作者、被授权人不同）';
COMMENT ON COLUMN m4_staff.staff_authorization.second_approved_at IS
    '第二审批时间（服务端时间；单人审批版本为 NULL）';
COMMENT ON COLUMN m4_staff.staff_authorization.second_approver_reason IS
    '第二审批人复核意见（可选，不含患者信息）';
COMMENT ON COLUMN m4_staff.staff_authorization.revoked_at IS
    '撤销生效时间（仅 REVOKE 版本有值）；回溯校验按该时点判断撤销是否已生效，撤销不追溯既往（Q-21）';
COMMENT ON COLUMN m4_staff.staff_authorization.revoked_by IS
    '撤销操作人用户标识（仅 REVOKE 版本有值）';

-- S-3：V21 注释“吊销后不可用于执业判断”与当前实现不符（授权判定尚未消费资质状态）。
-- 以 V22 注释纠正，避免把“待确认口径”当成已实现规则；是否阻断登记为 Q-21。
COMMENT ON COLUMN m4_staff.staff_qualification.status IS
    '状态：ACTIVE=有效，REVOKED=已撤销（保留记录）。当前仅用于档案展示/到期提醒，是否阻断授权判定待 Q-21 确认；授权校验暂不消费该字段';

-- -----------------------------------------------------------------------------
-- 2. 权限点与授权管理角色（B-2）
--    SYS_AUTH_MANAGER 具 DEPT 数据范围：只能维护/审批本科室人员，不能跨科室。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES ('b0000000-0000-4000-8000-000000000085', 'm4:staff:authorize',
        'M4 分级授权审批', 'API', '/api/v1/staff/*/authorizations', 'PUT', NULL, 154)
ON CONFLICT (id) DO NOTHING;

INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
VALUES ('a0000000-0000-4000-8000-000000000008', 'SYS_AUTH_MANAGER',
        '分级授权管理员',
        'SYNTHETIC 演示角色：按科室维护人员档案/培训并审批分级授权（双人审批规则仍须满足）',
        'DEPT')
ON CONFLICT (id) DO NOTHING;

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
SELECT 'a0000000-0000-4000-8000-000000000008', sp.id
  FROM m0_platform.sys_permission sp
 WHERE sp.code IN ('m4:staff:read', 'm4:staff:write', 'm4:training:manage',
                   'm4:staff:authorize')
ON CONFLICT DO NOTHING;

-- SYS_ADMIN 保留排障能力，追加授权审批权限（双人规则不因管理员身份豁免）。
INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
SELECT 'a0000000-0000-4000-8000-000000000001', sp.id
  FROM m0_platform.sys_permission sp
 WHERE sp.code = 'm4:staff:authorize'
ON CONFLICT DO NOTHING;

-- 麻醉医师只读：收回 V21 授予的写与培训管理（临床资质审批归 SYS_AUTH_MANAGER）。
DELETE FROM m0_platform.sys_role_permission rp
 USING m0_platform.sys_permission sp
 WHERE rp.permission_id = sp.id
   AND rp.role_id = 'a0000000-0000-4000-8000-000000000002'
   AND sp.code IN ('m4:staff:write', 'm4:training:manage');

-- 角色说明由 sys_role.description 承载（PostgreSQL COMMENT ON ROLE 针对数据库角色，不适用）。

-- -----------------------------------------------------------------------------
-- 3. 迁移自检（fail-fast）：列/索引/权限/角色/授权矩阵必须齐全
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    missing_columns integer;
    missing_permission integer;
    missing_role integer;
    missing_grants integer;
    stale_grants integer;
BEGIN
    SELECT count(*) INTO missing_columns
      FROM (VALUES ('second_approver_id'), ('second_approved_at'),
                   ('second_approver_reason'), ('revoked_at'), ('revoked_by')) AS c(name)
     WHERE NOT EXISTS (
         SELECT 1 FROM information_schema.columns
          WHERE table_schema = 'm4_staff' AND table_name = 'staff_authorization'
            AND column_name = c.name
     );
    IF missing_columns > 0 THEN
        RAISE EXCEPTION 'V22 自检失败：staff_authorization 缺少 % 个治理列', missing_columns;
    END IF;

    SELECT count(*) INTO missing_permission
      FROM m0_platform.sys_permission WHERE code = 'm4:staff:authorize';
    IF missing_permission <> 1 THEN
        RAISE EXCEPTION 'V22 自检失败：缺少权限点 m4:staff:authorize';
    END IF;

    SELECT count(*) INTO missing_role
      FROM m0_platform.sys_role WHERE code = 'SYS_AUTH_MANAGER' AND data_scope = 'DEPT';
    IF missing_role <> 1 THEN
        RAISE EXCEPTION 'V22 自检失败：缺少 DEPT 范围的 SYS_AUTH_MANAGER 角色';
    END IF;

    SELECT count(*) INTO missing_grants
      FROM (VALUES ('m4:staff:read'), ('m4:staff:write'), ('m4:training:manage'),
                   ('m4:staff:authorize')) AS g(code)
     WHERE NOT EXISTS (
         SELECT 1
           FROM m0_platform.sys_role_permission rp
           JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
          WHERE rp.role_id = 'a0000000-0000-4000-8000-000000000008'
            AND sp.code = g.code
     );
    IF missing_grants > 0 THEN
        RAISE EXCEPTION 'V22 自检失败：SYS_AUTH_MANAGER 缺少 % 条授权', missing_grants;
    END IF;

    SELECT count(*) INTO stale_grants
      FROM m0_platform.sys_role_permission rp
      JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
     WHERE rp.role_id = 'a0000000-0000-4000-8000-000000000002'
       AND sp.code IN ('m4:staff:write', 'm4:training:manage');
    IF stale_grants > 0 THEN
        RAISE EXCEPTION 'V22 自检失败：SYS_ANESTHESIOLOGIST 仍持有 % 条人员写权限', stale_grants;
    END IF;
END;
$$;
