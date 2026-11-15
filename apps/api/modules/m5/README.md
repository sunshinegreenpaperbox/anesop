# m5 — 实时态势与 KPI 看板（后端）

**一句话职责**：对 M1 术间/台次、M2 麻醉记录单/术中告警/设备登记/术前评估、M4 人员排班做
只读聚合，输出实时态势、当日运营 KPI、风险分层、资源态势与按小时趋势；无自有 schema、
无新表、无 PHI。口径与决策见
[`docs/modules/m5/README.md`](../../../../docs/modules/m5/README.md)、
[ADR-0023](../../../../docs/adr/0023-实时看板口径.md)。

## 数据归属

- 无 schema、无表：V26 为 **data-only** 权限迁移（`m5:dashboard:read` + 4 条最小授权）。
- 只读来源（经装配层单条 SELECT 投影，禁止直连其他模块 Mapper/表）：
  `m1_sched.operating_room` / `schedule` / `surgery_application`、
  `m2_anes.anesthesia_record` / `alert` / `device_registry` / `preop_assessment`、
  `m4_staff.staff_schedule`。

## 目录与结构

```text
m5/
├── api/    # artifactId=m5-api：DashboardService、六个只读端口、DashboardScope、权限点
└── impl/   # artifactId=m5-impl：DashboardServiceImpl、口径配置、审计采样
```

- `api`：契约生成的 DTO（`dashboard 0.2.0`）+ 端口（`DashboardCaseSource` /
  `DashboardRoomSource` / `DashboardAlertSource`（含 `countAlertsByHour`）/
  `DashboardDeviceSource` / `DashboardRiskSource` / `DashboardStaffSource`）；
- `impl`：数据范围解析（ALL/SITE/DEPT/ROOM/SELF，fail-closed）、实时/KPI/风险/资源/趋势聚合、
  确定性资源缺口规则、`DASHBOARD_VIEW` 采样审计、口径阈值（`anesop.m5.dashboard.*`，
  含 `overdue-grace-minutes`）。

## 依赖边界

- 允许：`m5-impl` → `m5-api`（→ `m0-api`、`api-contracts`）；`api-bootstrap` → `m5-api` + `m5-impl`；
- 禁止：任何模块 → `m5-impl`；`m5-impl` → 其他模块 api/impl；模块间循环依赖。

## 如何运行

本模块不单独启动，由 `api-bootstrap` 统一装配：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
```

端点（均需 `m5:dashboard:read`）：`GET /v1/dashboard/realtime`、
`GET /v1/dashboard/kpi?date=YYYY-MM-DD`、`GET /v1/dashboard/risk`、
`GET /v1/dashboard/resources`、`GET /v1/dashboard/trends?hours=24`。

## 如何测试

```bash
# 模块单测（手算聚合/空数据/403/延迟/审计采样/无 PHI）
./scripts/mvn.sh -B -pl apps/api/modules/m5/impl -am test
# 端到端（MockMvc + 嵌入式 PG16 + Flyway V26）
./scripts/mvn.sh -B -pl apps/api/api-bootstrap -am test \
  -Dtest=M5DashboardApiIntegrationTest,M5DashboardRiskResourceApiIntegrationTest
```

覆盖率：新增代码行 ≥80%/分支 ≥70%（根 `pom.xml` JaCoCo 门禁）。

## 关键契约

- `packages/contracts/openapi/dashboard.yaml` 0.2.0（口径 dashboard-1.1.0）——契约是唯一事实源；
- 刷新：10s 前端轮询（后端无状态、无 SSE/WebSocket）；
- 前端页面：[`apps/web/src/modules/m5`](../../../../apps/web/src/modules/m5)。

## 负责人（占位）

- Owner：`@anesop/m5-dashboard`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
