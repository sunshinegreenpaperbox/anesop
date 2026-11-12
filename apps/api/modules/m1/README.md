# m1 — 手术排程管理

**一句话职责**：申请接收与校验、规则排程、拖拽工作台与冲突检测、急诊加台与停改台、术前准备联动、通知与回执、排程分析。

## 数据归属

- schema：`m1_sched`（本模块独占；其他模块只能经 `m1-api` 访问，禁止直连本 schema 的表）

## 目录与结构

```text
m1/
├── api/    # artifactId=m1-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m1-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m1-impl` → `m1-api`；其他模块 → `m1-api`；
- 禁止：任何模块 → `m1-impl`；`m1-impl` → 其他模块 `impl`；模块间循环依赖。

## 如何运行

本模块不单独启动，由 `api-bootstrap` 统一装配：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
```

当前为骨架，启动后没有 M1 端点；接口以契约评审结果为准。运行需 PostgreSQL 16
（Docker 不可用，EXT-08；见 `docs/guides/troubleshooting.md`）。

## 如何测试

```bash
# 本模块及其依赖（单元 + 集成；集成测试用 Zonky 嵌入式 PG16）
./scripts/mvn.sh -B -pl apps/api/modules/m1/impl -am test
```

- 单元 JUnit 5 + AssertJ；集成 Spring Boot Test + 嵌入式 PG；契约端点 100% 有用例；
- 覆盖率：整体行 ≥70%/分支 ≥55%，新增代码 ≥80%/70%；
- 模块边界由 `ModuleBoundaryTest`（ArchUnit，位于 api-bootstrap 测试）校验。

## 关键契约

- 本域契约：`packages/contracts/openapi/scheduling.yaml`（M1 排程）；接收手术申请涉及
  `packages/contracts/openapi/patient.yaml` 的申请端点。当前均为基线骨架，服务端未实现。
- 跨模块只依赖 `m1-api` 的接口、DTO、领域事件；禁止依赖其他模块 `impl` 或直连其表。
- 契约变更顺序与门禁见 [`docs/api/README.md`](../../../../docs/api/README.md)、
  [`docs/guides/dev-workflow.md`](../../../../docs/guides/dev-workflow.md)。

## 负责人（占位）

- Owner：`@anesop/m1-scheduling`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
- 状态：S0 空模块，首个业务任务随 M1 里程碑启动。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
