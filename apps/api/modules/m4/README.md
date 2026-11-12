# m4 — 耗材仓库管理

**一句话职责**：主数据与 UDI、出入库与效期、术间领用与消耗、高值一物一码、库存预警与盘点、供应商证照、SPD 对账。

## 数据归属

- schema：`m4_supply`（本模块独占；其他模块只能经 `m4-api` 访问，禁止直连本 schema 的表）

## 目录与结构

```text
m4/
├── api/    # artifactId=m4-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m4-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m4-impl` → `m4-api`；其他模块 → `m4-api`；
- 禁止：任何模块 → `m4-impl`；`m4-impl` → 其他模块 `impl`；模块间循环依赖。

## 如何运行

本模块不单独启动，由 `api-bootstrap` 统一装配：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
```

当前为骨架，启动后没有 M4 端点；运行需 PostgreSQL 16（Docker 不可用，EXT-08）。

## 如何测试

```bash
# 本模块及其依赖（单元 + 集成；集成测试用 Zonky 嵌入式 PG16）
./scripts/mvn.sh -B -pl apps/api/modules/m4/impl -am test
```

- 单元 JUnit 5 + AssertJ；集成 Spring Boot Test + 嵌入式 PG；契约端点 100% 有用例；
- 覆盖率：整体行 ≥70%/分支 ≥55%，新增代码 ≥80%/70%；
- SPD 适配器属外部接口映射（高风险），需适配器契约测试 + 人工逐行评审。

## 关键契约

- 本域暂无 OpenAPI 契约：M4 启动时先评审耗材主数据/UDI 模型与迁移，再新增契约
  （顺序见 [`docs/api/README.md`](../../../../docs/api/README.md)）。
- SPD/HRP 对接属 EXT-06（SIMULATED），适配器放在 `impl` 的 adapter 包，禁止把外部 DTO
  泄漏到 `m4-api`；跨模块只依赖 `m4-api`。

## 负责人（占位）

- Owner：`@anesop/m4-supply`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
- 状态：S0 空模块；随 M4 里程碑启动。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
