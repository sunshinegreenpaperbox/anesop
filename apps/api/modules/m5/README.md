# m5 — 药品统计

**一句话职责**：药品目录与麻精分类、五专管理、基数与请领、日结班结、消耗统计、配伍与异常提醒、上报报表。

## 数据归属

- schema：`m5_drug`（本模块独占；其他模块只能经 `m5-api` 访问，禁止直连本 schema 的表）

## 目录与结构

```text
m5/
├── api/    # artifactId=m5-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m5-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m5-impl` → `m5-api`；其他模块 → `m5-api`；
- 禁止：任何模块 → `m5-impl`；`m5-impl` → 其他模块 `impl`；模块间循环依赖。

## 如何运行

本模块不单独启动，由 `api-bootstrap` 统一装配：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
```

当前为骨架，启动后没有 M5 端点；运行需 PostgreSQL 16（Docker 不可用，EXT-08）。

## 如何测试

```bash
# 本模块及其依赖（单元 + 集成；集成测试用 Zonky 嵌入式 PG16）
./scripts/mvn.sh -B -pl apps/api/modules/m5/impl -am test
```

- 单元 JUnit 5 + AssertJ；集成 Spring Boot Test + 嵌入式 PG；契约端点 100% 有用例；
- **麻精药品双人核对（E2E-05）为发布准入项**，必须有双人/单人越权的正反用例；
- 覆盖率：整体行 ≥70%/分支 ≥55%，新增代码 ≥80%/70%。

## 关键契约

- 本域暂无 OpenAPI 契约：M5 启动时先评审麻精五专/基数/日结模型与迁移，再新增契约
  （顺序见 [`docs/api/README.md`](../../../../docs/api/README.md)）。
- 药房对接属 EXT-06（SIMULATED）；麻精台账为审计重点，所有写操作必须留痕；
- 跨模块只依赖 `m5-api`，禁止直连 `m5_drug` 表。

## 负责人（占位）

- Owner：`@anesop/m5-pharmacy`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
- 状态：S0 空模块；随 M5 里程碑启动。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
