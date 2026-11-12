# m7 — 质量控制

**一句话职责**：指标体系、自动计算与钻取、不良事件与 RCA/PDCA、质控看板与报告、病历质量抽查、规则版本管理。

## 数据归属

- schema：`m7_qc`（本模块独占；其他模块只能经 `m7-api` 访问，禁止直连本 schema 的表）

## 目录与结构

```text
m7/
├── api/    # artifactId=m7-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m7-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m7-impl` → `m7-api`；其他模块 → `m7-api`；
- 禁止：任何模块 → `m7-impl`；`m7-impl` → 其他模块 `impl`；模块间循环依赖。

## 如何运行

本模块不单独启动，由 `api-bootstrap` 统一装配：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
```

当前为骨架，启动后没有 M7 端点；运行需 PostgreSQL 16（Docker 不可用，EXT-08）。

## 如何测试

```bash
# 本模块及其依赖（单元 + 集成；集成测试用 Zonky 嵌入式 PG16）
./scripts/mvn.sh -B -pl apps/api/modules/m7/impl -am test
```

- 指标计算需有“口径版本 + 边界值 + 缺失数据”用例；规则版本变更要能回归；
- 覆盖率：整体行 ≥70%/分支 ≥55%，新增代码 ≥80%/70%；
- 质控上报属 EXT-07（SIMULATED），导出文件需脱敏并留审计。

## 关键契约

- 本域暂无 OpenAPI 契约：M7 启动时先定指标模型、规则版本与不良事件流程，再新增契约
  （顺序见 [`docs/api/README.md`](../../../../docs/api/README.md)）。
- 质控数据来自 M2/M6 等模块的 `api` 或事件，不直连其表；上报导出不得含患者直接标识；
- 跨模块只依赖 `m7-api`。

## 负责人（占位）

- Owner：`@anesop/m7-quality`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
- 状态：S0 空模块；随 M7 里程碑启动。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
