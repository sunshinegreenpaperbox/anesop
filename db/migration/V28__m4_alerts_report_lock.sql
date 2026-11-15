-- =============================================================================
-- ANESOP 数据库迁移 V28：M4 耗材库存预警阈值 + 报表数据锁定/快照（M8-M4-02）
-- 目标库：PostgreSQL 16；schema：m4_material（ALTER）、m4_report（新增）
--
-- 设计依据：
--   方案书 §2.4.7（月度报表锁定/修订）、§2.5（药品与耗材管理：库存预警）、
--   §3.9.2（RBAC 与数据权限）、§3.9.3（脱敏）
--   契约：packages/contracts/openapi/material.yaml 0.2.0、
--         packages/contracts/openapi/report.yaml 0.2.0
--   任务卡：docs/ai/tasks/M8-backlog.md M8-M4-02
--   遗留：docs/acceptance/FINAL-REPORT.md §6.2（耗材库存预警、报表数据锁定）
--   ADR：docs/adr/0020-耗材UDI追溯与SPD口径.md、0021-工作量统计与报表口径.md
--   决策：D-21（系统出数、绩效规则院方另定）
--
-- 内容（expand 段：只新增列/表/索引/约束/触发器/权限点，不改写 V1–V27 既有列与数据）：
--   1) m4_material.material.low_stock_threshold numeric(12,2)：
--      库存预警阈值；**可空 = 不按阈值预警**（仍参与零库存/临期预警）。
--   2) m4_report.report_snapshot：报表锁定快照（append-only），
--      payload/params 为 jsonb，params_hash/payload_hash 为 sha256 十六进制，
--      唯一 (report, period, params_hash)；锁定后同参数查询/导出默认读快照。
--   3) 权限点 m4:report:lock（三段式），最小授予 SYS_ADMIN / SYS_OR_MANAGER /
--      SYS_QC_MANAGER，与 m4:report:read|export 的授予面一致（管理口径，D-21）。
--
-- 口径约定与待确认（**不臆造业务阈值**）：
--   - **预警阈值来源【待确认】**：low_stock_threshold 由耗材管理员/科室库管按院内补货
--     规则（日均消耗 × 采购周期 + 安全库存）录入，本期不内置任何默认阈值、不按分类推断；
--     可空表示“不按阈值预警”，此时仅零库存与临期两类预警仍然生效。
--   - **阈值粒度【待确认】**：本列挂在耗材主数据上（全院一个阈值）。若院方要求“按科室
--     分别设阈值”，需另行新增 material_stock 粒度阈值表（expand 迁移），不在本期预置。
--   - **临期天数【待确认】**：预警接口固定 90 天（契约 material.yaml 0.2.0 写明），
--     不开放配置；院方确认（如高值 30 天/普通 90 天）后再以配置项或迁移固化。
--   - **零库存口径**：按 材料 + 科室 的批次库存合计 = 0；仅对**已发生过出入库**（存在
--     material_stock 行）的 材料 + 科室 判定，不返回“全目录 × 全科室”的笛卡尔积空行
--     【待确认：是否需要覆盖从未入库的耗材】。
--   - **建议补货量**：阈值存在时为 max(0, threshold - 可用量)；阈值缺失时为 null
--     （不臆造补货量），由库管按经验补货；临期预警不产生补货量。
--   - **快照保留期【待确认】**：表内不做自动清理（append-only），保留期与归档策略待院方
--     与财务口径确认（月度报表通常按会计年度保留）；确认前不得物理删除快照。
--   - **锁定语义**：锁定即冻结“该报告 + 该期间 + 该参数（含数据范围指纹）”的聚合结果；
--     之后同参数查询/导出默认返回快照（`source=SNAPSHOT`），源数据变化不影响已锁定值；
--     重新锁定同一参数组合返回 409（如需重出数，院方先确认“解锁/新版本”流程【待确认】）。
--   - **脱敏**：快照 payload 只含聚合值与维度标签（科室/术间/术式/院内在册人员工号姓名），
--     不含患者姓名、住院号、院内患者号、身份证号、手机号、诊断与 HIS 申请号；
--     审计只记 report/period/参数摘要/哈希与行数，不记患者标识。
--
-- 迁移纪律：本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>（expand-contract）。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.23）：
--   1) 应用回滚：下线 /v1/reports/locks* 与 /v1/materials/stock-alerts 端点、前端锁定按钮与
--      预警卡片；报表回到“纯实时聚合”，库存阈值列保留但不参与业务（不影响出入库）。
--   2) 库层前向修复（需 DBA 审批 + 备份 + 先导出 report_snapshot 并校验行数）：
--      DROP TRIGGER IF EXISTS trg_report_snapshot_no_update ON m4_report.report_snapshot;
--      DROP TRIGGER IF EXISTS trg_report_snapshot_no_delete ON m4_report.report_snapshot;
--      DROP TRIGGER IF EXISTS trg_report_snapshot_no_truncate ON m4_report.report_snapshot;
--      DROP FUNCTION IF EXISTS m4_report.fn_reject_mutation();
--      DROP FUNCTION IF EXISTS m4_report.fn_reject_truncate();
--      DROP TABLE m4_report.report_snapshot;
--      DROP SCHEMA IF EXISTS m4_report;
--      ALTER TABLE m4_material.material DROP CONSTRAINT IF EXISTS ck_material_low_stock_threshold;
--      ALTER TABLE m4_material.material DROP COLUMN IF EXISTS low_stock_threshold;
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id = 'b0000000-0000-4000-8000-00000000008b';
--      DELETE FROM m0_platform.sys_permission WHERE code = 'm4:report:lock';
--      回滚顺序：先删授权再删权限点（外键）；报表快照属运营口径留痕，删除前必须归档。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 耗材库存预警阈值（可空 = 不按阈值预警）
-- -----------------------------------------------------------------------------
ALTER TABLE m4_material.material
    ADD COLUMN low_stock_threshold numeric(12,2);

ALTER TABLE m4_material.material
    ADD CONSTRAINT ck_material_low_stock_threshold
    CHECK (low_stock_threshold IS NULL OR low_stock_threshold >= 0);

COMMENT ON COLUMN m4_material.material.low_stock_threshold IS
    '库存预警阈值（可空=不按阈值预警）；来源与按科室粒度【待确认】，本期不内置默认值';

-- -----------------------------------------------------------------------------
-- 2. 报表锁定快照（append-only；同参数唯一）
-- -----------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS m4_report;

CREATE TABLE m4_report.report_snapshot (
    id             uuid          NOT NULL DEFAULT gen_random_uuid(),
    report         varchar(16)   NOT NULL,
    period         varchar(64)   NOT NULL,
    from_date      date          NOT NULL,
    to_date        date          NOT NULL,
    params         jsonb         NOT NULL,
    params_hash    char(64)      NOT NULL,
    payload        jsonb         NOT NULL,
    payload_hash   char(64)      NOT NULL,
    data_scope     varchar(32)   NOT NULL,
    scope_hash     char(64)      NOT NULL,
    row_count      integer       NOT NULL DEFAULT 0,
    locked_by      uuid          NOT NULL,
    locked_by_name varchar(64)   NOT NULL,
    locked_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_report_snapshot PRIMARY KEY (id),
    CONSTRAINT ck_report_snapshot_report CHECK (report IN ('workload', 'multi')),
    CONSTRAINT ck_report_snapshot_period CHECK (btrim(period) <> ''),
    CONSTRAINT ck_report_snapshot_range CHECK (to_date >= from_date),
    CONSTRAINT ck_report_snapshot_hashes CHECK (
        params_hash ~ '^[0-9a-f]{64}$'
        AND payload_hash ~ '^[0-9a-f]{64}$'
        AND scope_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_report_snapshot_row_count CHECK (row_count >= 0),
    CONSTRAINT ck_report_snapshot_locked_by CHECK (
        locked_by IS NOT NULL AND btrim(locked_by_name) <> ''
    ),
    CONSTRAINT uk_report_snapshot_params UNIQUE (report, period, params_hash)
);

-- 查询/导出按 (report, params_hash) 定位最新快照（唯一约束含 period，这里补查找索引）。
CREATE INDEX idx_report_snapshot_lookup ON m4_report.report_snapshot (report, params_hash);
CREATE INDEX idx_report_snapshot_period ON m4_report.report_snapshot (from_date, to_date);

COMMENT ON TABLE m4_report.report_snapshot IS
    '报表锁定快照（append-only）：锁定后同参数查询/导出默认读快照，源数据变化不影响已锁定值';
COMMENT ON COLUMN m4_report.report_snapshot.report IS '报表类型：workload=工作量、multi=多维报表';
COMMENT ON COLUMN m4_report.report_snapshot.period IS
    '归一化期间：YYYY-MM（整月）或 YYYY-MM-DD~YYYY-MM-DD（自定义闭区间）';
COMMENT ON COLUMN m4_report.report_snapshot.params IS
    '归一化锁定参数（含 report/from/to/deptId/维度/指标与数据范围指纹），不含患者标识';
COMMENT ON COLUMN m4_report.report_snapshot.params_hash IS
    'params 的 sha256（十六进制 64 位）；与 report+period 组成唯一键，用于同参数查询命中';
COMMENT ON COLUMN m4_report.report_snapshot.payload IS
    '快照数据（聚合值与维度标签，含多维度全部行与合计）；不含患者姓名/住院号/诊断';
COMMENT ON COLUMN m4_report.report_snapshot.payload_hash IS 'payload 的 sha256，用于完整性核对';
COMMENT ON COLUMN m4_report.report_snapshot.data_scope IS
    '锁定人数据范围摘要（ALL/SITE/DEPT/ROOM/SELF+对象数），用于说明数据口径';
COMMENT ON COLUMN m4_report.report_snapshot.scope_hash IS
    '数据范围指纹的 sha256；同参数不同范围不共用一个快照（避免跨范围读数）';
COMMENT ON COLUMN m4_report.report_snapshot.row_count IS '快照数据行数（workload=维度桶数，multi=分组数）';
COMMENT ON COLUMN m4_report.report_snapshot.locked_by IS '锁定人用户标识';
COMMENT ON COLUMN m4_report.report_snapshot.locked_by_name IS '锁定人姓名摘要（≤64，非患者信息）';
COMMENT ON COLUMN m4_report.report_snapshot.locked_at IS '锁定时间（Asia/Shanghai 展示）';

-- append-only：禁止 UPDATE/DELETE/TRUNCATE（快照属运营口径留痕，不得改写）。
CREATE OR REPLACE FUNCTION m4_report.fn_reject_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'm4_report.% is append-only: % rejected', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000',
              HINT = '报表快照不得修改或删除；如需重出数请新增锁定并保留原快照';
END;
$$;

COMMENT ON FUNCTION m4_report.fn_reject_mutation() IS
    '报表快照 append-only 守卫：UPDATE/DELETE 一律 55000';

CREATE OR REPLACE FUNCTION m4_report.fn_reject_truncate()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'm4_report.% is append-only: TRUNCATE rejected', TG_TABLE_NAME
        USING ERRCODE = '55000',
              HINT = '报表快照不得 TRUNCATE';
END;
$$;

COMMENT ON FUNCTION m4_report.fn_reject_truncate() IS
    '报表快照 append-only 守卫：TRUNCATE 一律 55000';

CREATE TRIGGER trg_report_snapshot_no_update
    BEFORE UPDATE ON m4_report.report_snapshot
    FOR EACH ROW EXECUTE FUNCTION m4_report.fn_reject_mutation();

CREATE TRIGGER trg_report_snapshot_no_delete
    BEFORE DELETE ON m4_report.report_snapshot
    FOR EACH ROW EXECUTE FUNCTION m4_report.fn_reject_mutation();

CREATE TRIGGER trg_report_snapshot_no_truncate
    BEFORE TRUNCATE ON m4_report.report_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION m4_report.fn_reject_truncate();

-- -----------------------------------------------------------------------------
-- 3. 权限点与最小授权（m4:report:lock）
--    path/http_method 与契约路径一致：POST /api/v1/reports/locks；
--    读取锁定列表/详情复用 m4:report:read（V24 已授予同一角色面）。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-00000000008b', 'm4:report:lock',
     'M4 报表数据锁定（生成快照）', 'API', '/api/v1/reports/locks', 'POST', NULL, 148)
ON CONFLICT DO NOTHING;

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：运维排障与初始化
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000008b'),
    -- SYS_OR_MANAGER（DEPT 数据范围）：本科室月度报表锁定
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-00000000008b'),
    -- SYS_QC_MANAGER：质控/运营报表复核后锁定
    ('a0000000-0000-4000-8000-000000000006', 'b0000000-0000-4000-8000-00000000008b')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 4. 迁移自检：列/表/函数/触发器/权限与最小授权必须齐全（fail-fast）
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    has_column integer;
    has_table integer;
    missing_functions integer;
    missing_triggers integer;
    missing_permissions integer;
    missing_grants integer;
BEGIN
    SELECT count(*) INTO has_column
      FROM information_schema.columns
     WHERE table_schema = 'm4_material' AND table_name = 'material'
       AND column_name = 'low_stock_threshold'
       AND data_type = 'numeric';
    IF has_column = 0 THEN
        RAISE EXCEPTION 'V28 自检失败：缺少 m4_material.material.low_stock_threshold';
    END IF;

    IF to_regclass('m4_report.report_snapshot') IS NULL THEN
        RAISE EXCEPTION 'V28 自检失败：缺少 m4_report.report_snapshot';
    END IF;

    SELECT count(*) INTO has_table
      FROM pg_constraint
     WHERE conname = 'uk_report_snapshot_params';
    IF has_table = 0 THEN
        RAISE EXCEPTION 'V28 自检失败：缺少快照唯一约束 (report, period, params_hash)';
    END IF;

    SELECT count(*) INTO missing_functions
      FROM (VALUES
            ('m4_report.fn_reject_mutation()'),
            ('m4_report.fn_reject_truncate()')) AS f(name)
     WHERE to_regprocedure(f.name) IS NULL;
    IF missing_functions > 0 THEN
        RAISE EXCEPTION 'V28 自检失败：缺少 % 个函数', missing_functions;
    END IF;

    SELECT count(*) INTO missing_triggers
      FROM (VALUES
            ('trg_report_snapshot_no_update'),
            ('trg_report_snapshot_no_delete'),
            ('trg_report_snapshot_no_truncate')) AS t(name)
     WHERE NOT EXISTS (
         SELECT 1 FROM pg_trigger tg WHERE tg.tgname = t.name AND NOT tg.tgisinternal
     );
    IF missing_triggers > 0 THEN
        RAISE EXCEPTION 'V28 自检失败：缺少 % 个 append-only 触发器', missing_triggers;
    END IF;

    SELECT count(*) INTO missing_permissions
      FROM (VALUES ('m4:report:lock')) AS p(code)
     WHERE NOT EXISTS (
         SELECT 1 FROM m0_platform.sys_permission sp WHERE sp.code = p.code
     );
    IF missing_permissions > 0 THEN
        RAISE EXCEPTION 'V28 自检失败：缺少 % 个权限点', missing_permissions;
    END IF;

    SELECT count(*) INTO missing_grants
      FROM (VALUES
            ('a0000000-0000-4000-8000-000000000001', 'm4:report:lock'),
            ('a0000000-0000-4000-8000-000000000005', 'm4:report:lock'),
            ('a0000000-0000-4000-8000-000000000006', 'm4:report:lock')) AS g(role_id, code)
     WHERE NOT EXISTS (
         SELECT 1
           FROM m0_platform.sys_role_permission rp
           JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
          WHERE rp.role_id = g.role_id::uuid AND sp.code = g.code
     );
    IF missing_grants > 0 THEN
        RAISE EXCEPTION 'V28 自检失败：缺少 % 条最小授权', missing_grants;
    END IF;
END;
$$;
