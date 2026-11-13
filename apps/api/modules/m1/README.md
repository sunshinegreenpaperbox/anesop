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

已实现 M2-M1-01 手术申请接收与校验：HIS 模拟导入、手工创建、列表/详情/取消、患者摘要查询。
运行需 PostgreSQL 16（Docker 不可用，EXT-08；见 `docs/guides/troubleshooting.md`），并注入
`ANESOP_INPATIENT_PEPPER`（dev/test 可显式允许进程内生成）。

## 如何测试

```bash
# 本模块及其依赖（单元 + 集成；集成测试用 Zonky 嵌入式 PG16）
./scripts/mvn.sh -B -pl apps/api/modules/m1/impl -am test
```

- 单元 JUnit 5 + AssertJ；集成 Spring Boot Test + 嵌入式 PG；契约端点 100% 有用例；
- 覆盖率：整体行 ≥70%/分支 ≥55%，新增代码 ≥80%/70%；
- 模块边界由 `ModuleBoundaryTest`（ArchUnit，位于 api-bootstrap 测试）校验。

## 关键契约

- 本域契约：`packages/contracts/openapi/scheduling.yaml` 0.3.0（`/v1/scheduling/applications*`）
  与 `packages/contracts/openapi/patient.yaml` 0.3.0（患者摘要、POST `/v1/patients/search`）。
- 权限点：`m1:application:read|write`、`m1:patient:read`（V7，最小权限）。
- 跨模块只依赖 `m1-api` 的接口、DTO、领域事件；禁止依赖其他模块 `impl` 或直连其表。
- 契约变更顺序与门禁见 [`docs/api/README.md`](../../../../docs/api/README.md)、
  [`docs/guides/dev-workflow.md`](../../../../docs/guides/dev-workflow.md)。

## 负责人（占位）

- Owner：`@anesop/m1-scheduling`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
- 状态：M2-M1-01 已实现（2026-11-13），后续由 M2-M2-01 排程冲突继续扩展。

## 状态

已落地：`m1_sched.patient_snapshot` / `surgery_application` / `application_status_history`
（V7）、HIS 模拟适配器、患者摘要与申请列表/详情/取消、`m1:application:*` 与 `m1:patient:read`
权限、单测/集成/E2E。待办：真实 HIS 适配器（EXT-01）、排程冲突与工作台（M2-M2-01）。
