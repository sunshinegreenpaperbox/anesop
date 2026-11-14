# m4 — 药品目录与麻精五专

**一句话职责**：药品主数据（编码/名称/别名/规格/麻精/高危）与麻精五专（专册登记、专柜交接、
基数、日结/班结），并提供药房适配器（EXT-02，SIMULATED）状态；同时是麻醉记录麻精判定的
字典权威（收口 M3 T-4）。

## 数据归属

- schema：`m4_drug`（本模块独占；其他模块只能经 `m4-api` 访问，禁止直连本 schema 的表）
- 表（V20）：`drug`、`drug_alias`、`narcotic_register`、`cabinet_handover`、`drug_baseline`、
  `daily_close`、`pharmacy_adapter_log`

## 目录与结构

```text
m4/
├── api/    # artifactId=m4-api：DrugCatalogService/DrugDictionaryService/NarcoticService/
│           # PharmacyAdapterService、权限点与错误类型（跨模块唯一可依赖层）
└── impl/   # artifactId=m4-impl：服务实现、Mapper、实体；只依赖 m4-api + spring-context/tx/mybatis
```

包结构：`com.anesop.m4.api.{drug,narcotic,adapter,security,error}`、
`com.anesop.m4.impl.{drug,narcotic,adapter,persistence}`。

## 依赖边界

- 允许：`m4-impl → m4-api`；其他模块 → `m4-api`（`m2-impl` 只调 `DrugDictionaryService`，
  不访问 m4 表/Mapper）；`api-bootstrap` 是唯一装配 `m4-impl` 的模块；
- 禁止：任何模块 → `m4-impl`；`m4-impl` → 其他模块 `impl`；模块间循环依赖。

## 如何运行

本模块不单独启动，由 `api-bootstrap` 统一装配：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
```

运行需 PostgreSQL 16（Docker 不可用，EXT-08；测试用 Zonky 嵌入式 PG16 + Flyway V1–V20，
演示路径额外加载 `db/seed/demo/V100/V101`）；生产路径 V20 不写演示药品。

## 如何测试

```bash
# 本模块及其依赖（单元测试；集成测试在 api-bootstrap）
./scripts/mvn.sh -B -pl apps/api/modules/m4/impl -am test

# 集成（真实嵌入式 PG16 + MockMvc）
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am test -Dtest=M4DrugApiIntegrationTest
```

- 单测 29 例覆盖目录归一化唯一/反向冲突/乐观锁、五专双人不同人/科室范围/SELF 推导/
  基数跨科/日结平账与不平、药品未收录、适配器降级；集成 `M4DrugApiIntegrationTest` 覆盖
  CRUD、权限 403、append-only 55000 + TRUNCATE、日结 409 + details、失败审计、科室校验、
  基数跨科与 V20 结构；
- T-4 集成用例在 `AnesthesiaRecordApiIntegrationTest`（别名指向麻精 + 声明 false → 400、
  大小写/空白解析、字典歧义 → 422 `DRUG_AMBIGUOUS`、未收录 → 400、麻精 + 双人核对 → 201）；
- 覆盖率（2026-11-14 全量 verify）：行 91.7%、分支 73.9%。

## 关键契约与文档

- 契约：`packages/contracts/openapi/drug.yaml` 0.1.0（10 路径 / 15 operation；Java/TS 生成
  已接入 `gen-contracts.sh` 与 `contract-check.sh`）；基线 `openapi/baseline/drug.yaml`。
- 模块口径：`docs/modules/m4/README.md`；决策：`docs/adr/0018-药品字典与麻精五专口径.md`；
  迁移：`db/migration/V20__m4_drug_dictionary.sql`（`docs/db/README.md` §4.15）。
- 外部适配器：EXT-02 药房系统 **SIMULATED**，不直连生产库、不写外部系统；失败降级本地字典。

## 负责人（占位）

- Owner：`@anesop/m4-drug`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`

## 状态

M4-M15-01 完成（2026-11-14），进入 Review；班次/日结数据来源/差异容忍度等口径见
`docs/modules/m4/README.md` §11 待确认清单。
