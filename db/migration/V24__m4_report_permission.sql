-- =============================================================================
-- ANESOP 数据库迁移 V24：M4 工作量统计/多维报表/导出权限点（M4-M16-01 / E-M16）
-- 目标库：PostgreSQL 16；**data-only 迁移，无任何 DDL**
--
-- 设计依据：
--   方案书 §2.4.7（M6 工作量统计：指标口径、多维度统计、绩效口径配置、报表导出）、
--   §2.7.2（表 2-9 工作量月报：例数/台次/麻醉时长/难度系数）、§3.8.1（月度工作量报表 < 10s）
--   契约：packages/contracts/openapi/report.yaml 0.1.0
--   任务卡：docs/ai/tasks/M4-backlog.md M4-M16-01
--   ADR：docs/adr/0021-工作量统计与报表口径.md
--   决策：D-21（系统出数、绩效规则院方另定）
--
-- 内容（只追加权限点与最小授权，不改写 V1–V23 既有表结构、权限与角色）：
--   1) 权限点 m4:report:read   工作量统计与多维报表只读（GET /api/v1/reports/*）
--   2) 权限点 m4:report:export 报表 CSV 导出（GET /api/v1/reports/export，另写审计 REPORT_EXPORT）
--   最小授予（三段式 <模块>:<资源>:<动作>，与既有 m4 权限点风格一致）：
--     - SYS_ADMIN        : read + export（运维排障与初始化）
--     - SYS_OR_MANAGER   : read + export（手术室管理员，DEPT 数据范围，本科室工作量）
--     - SYS_QC_MANAGER   : read + export（质控专员，全院或授权范围报表复核）
--   不授予 SYS_ANESTHESIOLOGIST / SYS_SURGEON / SYS_NURSE_ANESTHETIST / SYS_PHARMACIST：
--   工作量报表是管理口径数据（D-21），临床个人视图待院方确认绩效方案后另开（Q-7）。
--
-- 为什么没有 DDL：
--   报表是对既有 m1_sched.schedule / m1_sched.surgery_application / m2_anes.anesthesia_record
--   与 m4_staff.staff_profile 的**只读聚合**（口径 report-1.0.0），不新增报表结果表、不做
--   物化视图、不落库缓存（避免与源数据不一致、避免锁定后的历史报表重算）。
--   报表查询与导出均写 m0_platform.sys_audit_log（REPORT_QUERY / REPORT_EXPORT），
--   复用 V1 既有审计表与 append-only 触发器，无需新增表。
--   若后续院方要求“报表数据锁定/定时生成”（方案书 §2.4.7），再以 V25+ 新增
--   m6_stats 读模型与锁定表，不在本次迁移内预置。
--
-- 迁移纪律：本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>。
-- 回滚策略（forward-only；本脚本可逆，详见 docs/db/README.md §4.19）：
--   1) 应用回滚：下线 /v1/reports* 端点与前端“工作量/多维报表”菜单；报表为只读，
--      不产生业务数据，回滚不丢数据、不影响 M1/M2/M3 记录与排程。
--   2) 库层回滚（纯 data-only，DELETE 即可，需 DBA 审批 + 备份）：
--      DELETE FROM m0_platform.sys_role_permission
--       WHERE permission_id IN ('b0000000-0000-4000-8000-000000000077',
--                               'b0000000-0000-4000-8000-000000000078');
--      DELETE FROM m0_platform.sys_permission
--       WHERE code IN ('m4:report:read', 'm4:report:export');
--   3) 回滚顺序：先删授权再删权限点（外键 sys_role_permission.permission_id）；
--      回滚后重新登录以刷新 JWT 权限 claim（令牌中的权限点不会追溯失效）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 权限点（只读查询 / 导出）
--    path/http_method 与契约路径一致：read 用通配覆盖 workload 与 multi，
--    export 精确到导出路径（不新增权限码）。
--    两条 INSERT 均带 ON CONFLICT DO NOTHING，便于演练重复执行时不因主键冲突中断。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-000000000077', 'm4:report:read',
     'M4 工作量统计与多维报表只读', 'API', '/api/v1/reports/*', 'GET', NULL, 146),
    ('b0000000-0000-4000-8000-000000000078', 'm4:report:export',
     'M4 工作量与多维报表导出', 'API', '/api/v1/reports/export', 'GET', NULL, 147)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 2. 最小授权（管理口径：管理员 / 手术室管理员 / 质控专员）
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：全院报表只读 + 导出
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000077'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000078'),
    -- SYS_OR_MANAGER（DEPT 数据范围）：本科室工作量只读 + 导出
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000077'),
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000078'),
    -- SYS_QC_MANAGER：质控复核只读 + 导出（数据范围由角色 data_scope 决定）
    ('a0000000-0000-4000-8000-000000000006', 'b0000000-0000-4000-8000-000000000077'),
    ('a0000000-0000-4000-8000-000000000006', 'b0000000-0000-4000-8000-000000000078')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3. 迁移自检：权限点与最小授权必须齐全（fail-fast，避免半套权限上线）
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    missing_permissions integer;
    missing_grants integer;
BEGIN
    SELECT count(*) INTO missing_permissions
      FROM (VALUES ('m4:report:read'), ('m4:report:export')) AS p(code)
     WHERE NOT EXISTS (
         SELECT 1 FROM m0_platform.sys_permission sp WHERE sp.code = p.code
     );
    IF missing_permissions > 0 THEN
        RAISE EXCEPTION 'V24 自检失败：缺少 % 个权限点', missing_permissions;
    END IF;

    SELECT count(*) INTO missing_grants
      FROM (VALUES
            ('a0000000-0000-4000-8000-000000000001', 'm4:report:read'),
            ('a0000000-0000-4000-8000-000000000001', 'm4:report:export'),
            ('a0000000-0000-4000-8000-000000000005', 'm4:report:read'),
            ('a0000000-0000-4000-8000-000000000005', 'm4:report:export'),
            ('a0000000-0000-4000-8000-000000000006', 'm4:report:read'),
            ('a0000000-0000-4000-8000-000000000006', 'm4:report:export')) AS g(role_id, code)
     WHERE NOT EXISTS (
         SELECT 1
           FROM m0_platform.sys_role_permission rp
           JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
          WHERE rp.role_id = g.role_id::uuid AND sp.code = g.code
     );
    IF missing_grants > 0 THEN
        RAISE EXCEPTION 'V24 自检失败：缺少 % 条最小授权', missing_grants;
    END IF;
END;
$$;
