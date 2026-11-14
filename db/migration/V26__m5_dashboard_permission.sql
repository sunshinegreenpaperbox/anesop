-- =============================================================================
-- ANESOP 数据库迁移 V26：M5 实时态势与 KPI 看板只读权限点（M5-M18-01 / E-M18）
-- 目标库：PostgreSQL 16；**data-only 迁移，无任何 DDL**
--
-- 设计依据：
--   方案书 §2.4.9（M8 驾驶舱：实时手术态势、临床风险、资源态势、运营 KPI、质量与安全、
--   大屏与移动摘要）、§2.4.9 关键规则（驾驶舱只读、指标口径与 M6/M7 同源、实时数据延迟
--   超过 2 分钟须标注）、§3.4.3（时间与时区）
--   契约：packages/contracts/openapi/dashboard.yaml 0.1.0（口径 dashboard-1.0.0）
--   任务卡：docs/ai/tasks/M5-backlog.md M5-M18-01
--   ADR：docs/adr/0023-实时看板口径.md
--   上游口径：ADR-0015（排程分析：利用率/准点/取消）、ADR-0021（工作量报表：台次/麻醉时长）
--   决策：D-14（运营 T+1）、D-21（系统出数、绩效规则院方另定）
--
-- 内容（只追加 1 个权限点与 4 条最小授权，不改写 V1–V25 既有表结构、权限与角色）：
--   1) 权限点 m5:dashboard:read 实时态势与 KPI 看板只读
--      （GET /api/v1/dashboard/realtime，GET /api/v1/dashboard/kpi）
--   最小授予（三段式 <模块>:<资源>:<动作>）：
--     - SYS_ADMIN（信息科管理员）：运维排障与初始化；
--     - SYS_OR_MANAGER（手术室管理员，DEPT 数据范围）：本科室术间/台次态势；
--     - SYS_ANESTHESIOLOGIST（麻醉医师，数据范围多为 SELF）：本人当日台次所涉术间态势；
--     - SYS_QC_MANAGER（质控专员）：全院或授权范围态势用于质控复盘。
--   不授予 SYS_SURGEON / SYS_NURSE_ANESTHETIST / SYS_PHARMACIST / SYS_OR_NURSE 等：
--   驾驶舱为管理视角聚合数据，按最小权限原则随院方确认后再评估（ADR-0023 Q-4）。
--
-- 为什么没有 DDL：
--   实时态势与 KPI 都是对既有 m1_sched.schedule / m1_sched.operating_room /
--   m1_sched.surgery_application、m2_anes.anesthesia_record / m2_anes.alert /
--   m2_anes.device_registry 的**只读聚合**，不新增看板结果表、不做物化视图、不落库快照
--   （避免与源数据不一致；驾驶舱只读、口径与 M1/M4 同源）。
--   查询写 m0_platform.sys_audit_log（DASHBOARD_VIEW，按用户采样），复用 V1 既有审计表与
--   append-only 触发器，无需新增表。
--   若后续院方要求“看板结果固化/历史回放”，再以 V27+ 新增 m5_dashboard 读模型与快照表，
--   不在本次迁移内预置。
--
-- 迁移纪律：本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>。
-- 回滚策略（forward-only；本脚本可逆，详见 docs/db/README.md §4.21）：
--   1) 应用回滚：下线 /v1/dashboard/* 端点与前端“实时看板”菜单；看板为只读，不产生业务
--      数据，回滚不丢数据、不影响 M1/M2/M3 记录、排程与报表。
--   2) 库层回滚（纯 data-only，DELETE 即可，需 DBA 审批 + 备份）：
--      DELETE FROM m0_platform.sys_role_permission
--       WHERE permission_id = 'b0000000-0000-4000-8000-00000000007d';
--      DELETE FROM m0_platform.sys_permission
--       WHERE code = 'm5:dashboard:read';
--   3) 回滚顺序：先删授权再删权限点（外键 sys_role_permission.permission_id）；
--      回滚后重新登录以刷新 JWT 权限 claim（令牌中的权限点不会追溯失效）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 权限点（只读看板；一个权限码覆盖 realtime 与 kpi 两个只读端点）
--    path 用通配覆盖 /api/v1/dashboard/*，不新增第二个权限码。
--    ON CONFLICT DO NOTHING 便于演练重复执行时不因主键冲突中断。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-00000000007d', 'm5:dashboard:read',
     'M5 实时态势与 KPI 看板只读', 'API', '/api/v1/dashboard/*', 'GET', NULL, 152)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 2. 最小授权（管理视角：管理员 / 手术室管理员 / 麻醉医师 / 质控专员）
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：全院看板只读
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000007d'),
    -- SYS_OR_MANAGER（DEPT 数据范围）：本科室术间/台次态势
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-00000000007d'),
    -- SYS_ANESTHESIOLOGIST（SELF 数据范围）：本人台次所涉术间态势
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-00000000007d'),
    -- SYS_QC_MANAGER：全院或授权范围态势（质控复盘）
    ('a0000000-0000-4000-8000-000000000006', 'b0000000-0000-4000-8000-00000000007d')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3. 迁移自检：权限点与最小授权必须齐全（fail-fast，避免半套权限上线）
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    permission_count integer;
    missing_grants integer;
BEGIN
    SELECT count(*) INTO permission_count
      FROM m0_platform.sys_permission sp
     WHERE sp.code = 'm5:dashboard:read';
    IF permission_count <> 1 THEN
        RAISE EXCEPTION 'V26 自检失败：m5:dashboard:read 权限点数量应为 1，实际 %', permission_count;
    END IF;

    SELECT count(*) INTO missing_grants
      FROM (VALUES
            ('a0000000-0000-4000-8000-000000000001', 'm5:dashboard:read'),
            ('a0000000-0000-4000-8000-000000000005', 'm5:dashboard:read'),
            ('a0000000-0000-4000-8000-000000000002', 'm5:dashboard:read'),
            ('a0000000-0000-4000-8000-000000000006', 'm5:dashboard:read')) AS g(role_id, code)
     WHERE NOT EXISTS (
         SELECT 1
           FROM m0_platform.sys_role_permission rp
           JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
          WHERE rp.role_id = g.role_id::uuid AND sp.code = g.code
     );
    IF missing_grants > 0 THEN
        RAISE EXCEPTION 'V26 自检失败：缺少 % 条最小授权', missing_grants;
    END IF;
END;
$$;
