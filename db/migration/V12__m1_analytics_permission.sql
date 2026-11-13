-- =============================================================================
-- ANESOP 数据库迁移 V12：M1 排程分析权限点（M2-M6-01）
--
-- 设计依据：
--   方案书 §2.4.2（M1 排程分析：术间利用率/准点开台率/取消率）、§2.7.2（报表清单）、
--   §3.9.2（RBAC 与数据权限）、D-21（系统出数、绩效规则院方另定）
--   契约：packages/contracts/openapi/scheduling.yaml 0.6.0
--   任务卡：docs/ai/tasks/M2-backlog.md M2-M6-01（排程分析）
--
-- 内容（**data-only**：不建表、不改既有表/列/索引，只新增一个权限点及其角色授权）：
--   1) 权限点 m1:analytics:read（三段式编码，API 类型，路径 /api/v1/scheduling/analytics）；
--   2) 授权 SYS_ADMIN、SYS_OR_MANAGER、SYS_QC_MANAGER：
--      - SYS_ADMIN：全院统计与导出；
--      - SYS_OR_MANAGER：手术室运营统计（实际可见范围仍按 DEPT/ROOM 数据范围 fail-closed）；
--      - SYS_QC_MANAGER：质控口径统计（只读，不授排程写权限）。
--
-- 为什么需要本迁移（范围说明）：
--   M2-M6-01 为只读聚合，本不需要表结构变更；但权限点必须由迁移种子写入，V1–V11 已合入
--   不可修改，因此以最小化的 V12 补充权限数据。本脚本不创建任何表/视图/物化视图，也不改变
--   既有排程数据；分析查询直接只读聚合 m1_sched 既有表，无新增读模型对象。
--
-- 安全与口径约定：
--   - 只读权限：不授予 m1:schedule:write/approve，统计分析不得修改排程；
--   - 数据范围仍由 DataScopeResolver 在服务层解析：DEPT 只统计本科室术间、ROOM 只统计绑定
--     术间，未绑定/无可见术间 403；导出同样受范围约束且只含聚合值（无 PHI）；
--   - 审计 ANALYTICS_QUERY/ANALYTICS_EXPORT 只记指标/日期/术间数/计数，不含患者标识。
--
-- 迁移纪律：本脚本已合入后禁止修改；变更一律新增 V<版本> 脚本（expand-contract）。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.7）：
--   1) 应用回滚：下线 /v1/scheduling/analytics/* 与前端 /m1/analytics 路由；
--   2) 库层前向修复：
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id = 'b0000000-0000-4000-8000-000000000030';
--      DELETE FROM m0_platform.sys_permission
--        WHERE code = 'm1:analytics:read';
--   3) 本迁移不改变业务数据，回滚无需备份 m1_sched；生产执行仍须人签字。
-- =============================================================================

INSERT INTO m0_platform.sys_permission
    (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-000000000030', 'm1:analytics:read', 'M1 排程分析只读', 'API',
     '/api/v1/scheduling/analytics', 'GET', NULL, 120);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：全院排程分析
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000030'),
    -- SYS_OR_MANAGER（DEPT 数据范围）：本科室术间运营分析
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000030'),
    -- SYS_QC_MANAGER：质控统计（只读）
    ('a0000000-0000-4000-8000-000000000006', 'b0000000-0000-4000-8000-000000000030');
