-- =============================================================================
-- ANESOP 演示种子 V101：M4 药品字典 SYNTHETIC 演示药品（M4-M15-01 / E-M15）
--
-- !!! 仅限 dev/test/uat 演示与自动化测试，禁止进入生产 locations !!!
--
-- 生产 Flyway locations 只包含 db/migration（V20 只建表/权限/触发器，不写演示药品）。
-- 本地演示/自动化测试才追加本目录：
--   filesystem:db/migration,filesystem:db/seed/demo
--
-- 数据口径：
--   1) 舒芬太尼（麻精）/丙泊酚（非麻精高危）/吗啡（麻精）3 条，固定 UUID，名称与别名与
--      M2 麻醉记录 T-4 集成用例一致；麻精标志是字典的权威值，测试据此验证“声明 false 被拒”。
--   2) 全部为 SYNTHETIC 合成数据，不含真实药品商品名、价格、供应商或患者信息；生产环境由
--      药房经药品维护页录入真实目录并双人复核。
--   3) 依赖 V20 已创建 m4_drug.drug / drug_alias 与唯一索引（名称/通用名/别名 trim+lower 唯一）。
-- =============================================================================

INSERT INTO m4_drug.drug
    (id, code, name, generic_name, specification, unit, dosage_form, category,
     narcotic, high_risk, status, remark, created_at, updated_at, version)
VALUES
    ('2a000000-0000-4000-8000-000000000001', 'DRUG-SUF-001', '舒芬太尼（演示）',
     '枸橼酸舒芬太尼注射液（演示）', '50ug/1ml', '支', 'INJECTION', 'OPIOID',
     true, true, 'ACTIVE', 'SYNTHETIC 演示药品：麻精类（用于 T-4 麻精判定用例）',
     '2026-11-14T08:00:00+08:00', '2026-11-14T08:00:00+08:00', 1),
    ('2a000000-0000-4000-8000-000000000002', 'DRUG-PRO-001', '丙泊酚（演示）',
     '丙泊酚乳状注射液（演示）', '200mg/20ml', '支', 'INJECTION', 'SEDATIVE',
     false, true, 'ACTIVE', 'SYNTHETIC 演示药品：非麻精高危（用于用药对照用例）',
     '2026-11-14T08:00:00+08:00', '2026-11-14T08:00:00+08:00', 1),
    ('2a000000-0000-4000-8000-000000000003', 'DRUG-MOR-001', '吗啡（演示）',
     '盐酸吗啡注射液（演示）', '10mg/1ml', '支', 'INJECTION', 'OPIOID',
     true, true, 'ACTIVE', 'SYNTHETIC 演示药品：麻精类',
     '2026-11-14T08:00:00+08:00', '2026-11-14T08:00:00+08:00', 1);

INSERT INTO m4_drug.drug_alias (id, drug_id, alias, created_at) VALUES
    ('2a100000-0000-4000-8000-000000000001', '2a000000-0000-4000-8000-000000000001',
     '舒芬（演示）', '2026-11-14T08:00:00+08:00'),
    ('2a100000-0000-4000-8000-000000000002', '2a000000-0000-4000-8000-000000000002',
     '丙泊酚注射液（演示）', '2026-11-14T08:00:00+08:00'),
    ('2a100000-0000-4000-8000-000000000003', '2a000000-0000-4000-8000-000000000003',
     '吗啡注射液（演示）', '2026-11-14T08:00:00+08:00');

-- 演示种子自检：3 条药品与 3 条别名必须齐全（fail-fast，避免半套演示数据）。
DO $$
DECLARE
    drug_count integer;
    alias_count integer;
BEGIN
    SELECT count(*) INTO drug_count FROM m4_drug.drug WHERE id::text LIKE '2a000000-%';
    SELECT count(*) INTO alias_count FROM m4_drug.drug_alias WHERE drug_id::text LIKE '2a000000-%';
    IF drug_count < 3 OR alias_count < 3 THEN
        RAISE EXCEPTION 'V101 自检失败：演示药品 % 条、别名 % 条', drug_count, alias_count;
    END IF;
END;
$$;
