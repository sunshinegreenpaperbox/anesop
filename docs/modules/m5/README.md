# m5 — 实时态势与 KPI 看板（E-M18）

> 状态：M5-M18-01 完成（2026-11-15，契约 `dashboard.yaml 0.1.0`、V26 data-only、m5-api/impl、
> 前端实时看板页）。本文件是 M5 驾驶舱域的模块口径说明；实现细节以契约与迁移为准，冲突时按
> “契约 → 迁移 → 本文件”的顺序裁决。口径版本 **dashboard-1.0.0**，决策见
> [`ADR-0023`](../../adr/0023-实时看板口径.md)。

## 1. 一句话职责

对 M1 术间/台次、M2 麻醉记录单/术中告警/设备登记做**只读聚合**，输出实时态势（术间状态、
台次进度、未确认/严重告警、设备在线、数据时效）与当日运营 KPI（台次/准点/取消/利用率/
告警/平均麻醉时长），供驾驶舱大屏与移动摘要使用；**不产生任何业务数据、不含 PHI**。

## 2. 数据归属与边界

- **无自有 schema、无新表、无结果快照**：V26 为 data-only 权限迁移（只有 `m5:dashboard:read`
  与 4 条最小授权），看板每次请求对源数据只读聚合。
- 依赖方向：`m5-impl → m5-api → m0-api/api-contracts`；`api-bootstrap → m5-api + m5-impl`
  （唯一装配点）。禁止任何模块依赖 `m5-impl`，`m5-impl` 不依赖 m1/m2/m4 的 api 或 impl。
- 跨模块只读投影：`m5-api` 定义四个端口，`api-bootstrap` 的适配器用**单条只读 SELECT** 实现
  （不复制其他模块 Mapper/实体、不写任何表、不做时区换算）：

| 端口 | 适配器 | 投影来源 |
|---|---|---|
| `DashboardCaseSource` | `DashboardCaseSourceAdapter` | `m1_sched.schedule` + `m1_sched.surgery_application` + `m2_anes.anesthesia_record` |
| `DashboardRoomSource` | `DashboardRoomSourceAdapter` | `m1_sched.operating_room`（含软删除） |
| `DashboardAlertSource` | `DashboardAlertSourceAdapter` | `m2_anes.alert` → 记录单 → 台次 → 申请 |
| `DashboardDeviceSource` | `DashboardDeviceSourceAdapter` | `m2_anes.device_registry`（+ 术间用于范围） |

## 3. 权限与数据范围

| 权限点 | 授予角色（V26 最小集） | 端点 |
|---|---|---|
| `m5:dashboard:read` | SYS_ADMIN、SYS_OR_MANAGER、SYS_ANESTHESIOLOGIST、SYS_QC_MANAGER | `GET /v1/dashboard/realtime`、`GET /v1/dashboard/kpi` |

- 数据范围 fail-closed（`DataScopeResolver`）：`ALL` 全院；`SITE`/`DEPT` 按术间院区/科室
  （台次另按申请科室/院区）；`ROOM` 按术间；`SELF` 按**本人创建麻醉记录单的当日台次所涉
  术间**（聚合无 PHI，重复应用同一范围结果不变，即幂等）。
- 无可用范围、SELF 缺用户标识、范围解析失败 → **403**；绝不退化为全院查询。
  适配器再拦一次“非全院且三条件全空”，防止未来调用方绕过服务层。
- 不授予 SYS_SURGEON / SYS_NURSE_ANESTHETIST / SYS_PHARMACIST / SYS_OR_NURSE
  （管理视角聚合数据，是否向术间护士开放待院方确认，ADR-0023 Q-4）。

## 4. 实时态势口径（`GET /v1/dashboard/realtime`）

- **窗口**：当日 `Asia/Shanghai` 自然日；台次为整日（含未开始），告警为 `[00:00, now]`。
- **术间状态**：M1 `operating_room.status` 映射 `IN_SURGERY`（ANESTHESIA/SURGERY）、
  `IDLE`、`CLEANING`、`PREPARING`、`RECOVERY`（PACU）；软删除术间按 `OUT_OF_SERVICE`
  （停用）计入——M1 无独立停用状态【待确认】；未知状态同样按停用处理。
  `caseInProgress` = 该术间存在 `IN_PROGRESS` 台次；`plannedCaseCount` = 当日非取消台次数。
- **台次进度**：当日总数（含取消）、`activeCount`（IN_PROGRESS）与按状态计数
  （PLANNED/PUBLISHED/IN_PROGRESS/COMPLETED/CANCELLED/DELAYED，只输出计数 > 0 的状态）。
- **告警**：未确认（TRIGGERED）总数 = 严重 + 警告 + 提示；已确认/已忽略单列；
  `latestTriggeredAt` 为窗口内最近触发时间。**不返回**告警值、规则原文、处置原因。
- **设备**：在线 = `status ≠ MAINTENANCE` 且 `last_seen_at > now − 120s`（查询期推导，不写回）；
  离线 = 总数 − 在线 − 维护中。
- **数据时效**：`dataDelaySeconds` = 服务端当前时间 − 可见范围最新业务数据时间
  （台次/记录单 `updated_at`、告警 `triggered_at`、设备 `last_seen_at` 的最大值）；
  `dataQuality` = `NO_DATA` / `OK`（≤120s）/ `DEGRADED`（>120s）。**无数据返回 0 + NO_DATA**。

## 5. KPI 口径（`GET /v1/dashboard/kpi?date=YYYY-MM-DD`）

| 指标 | 公式 | 上游口径 |
|---|---|---|
| `caseCount` | 当日非取消台次 | report-1.0.0 |
| `totalCaseCount` / `cancelledCount` | 当日全部台次 / 取消台次 | analytics-1.0.0 |
| `startedCount` | 记录单 `started_at` 非空 或 状态 IN_PROGRESS/COMPLETED（非取消台次） | 本 ADR |
| `completedCount` | 记录单 `ended_at` 非空 或 状态 COMPLETED | 本 ADR |
| `onTimeRate` | 准点首台数 / 已评估首台数 | analytics-1.0.0（实际开始来源不同，Q-1） |
| `cancellationRate` | 取消台次 / 当日总台次（分母含取消） | analytics-1.0.0 |
| `utilizationRate` | 当日重叠手术分钟 /（可见术间数 × 每日可用分钟 600） | analytics-1.0.0（Q-2） |
| `alertCount` / `criticalAlertCount` | 当日触发告警数 / 其中 CRITICAL | 本 ADR |
| `averageAnesthesiaMinutes` | Σ floor((ended−started)/60s) / 有记录台次（2 位小数） | report-1.0.0 |

- 比率一律 **0–1 小数（4 位小数）**，分母为 0 返回 0；`utilizationRate` 可 > 1。
- `date` 缺省为服务端当日，允许 `[today−366, today+31]`，格式非法或超范围 → 400
  （`details[0].field=date`）。
- `pendingConfirmations` 随响应返回未确认口径（准点实际开始来源、利用率计划/实际、停用语义、
  SELF 范围），前端展示“口径待确认”标识。

## 6. 空数据与性能

- 空数据：计数与时长 0、数组为空、比率 0、`dataQuality=NO_DATA`，不返回 404。
- 单次请求 = 4 条只读 SELECT（台次/术间/告警/设备各一条，均带索引条件）+ 应用内聚合，
  在单院数据量级（百级术间、千级台次/日）下为毫秒级；看板首屏满足方案书 §3.8.1
  驾驶舱首屏 < 3s、普通页面 P95 < 2s，10s 轮询不构成写入路径压力。
- 前端轮询间隔固定 10s（`REFRESH_INTERVAL_MS`），可“暂停刷新/继续刷新/立即刷新”；
  轮询失败保留上一次快照并叠加错误提示，避免大屏闪断；不使用 SSE/WebSocket（ADR-0023）。

## 7. 审计

- 每次查询（采样）写 `m0_platform.sys_audit_log`：`action=DASHBOARD_VIEW`、
  `resource_type=DASHBOARD`、`resource_id=realtime|kpi`。
- 采样：按“用户 + 看板类型”默认 300s 一条（`anesop.m5.dashboard.audit-sample-seconds`，0=每次），
  避免 10s 轮询把审计刷成噪声。
- detail 仅 `kind/date/timezone/dataScope/roomCount/caseCount/alertCount/dataDelaySeconds`，
  **不含**患者标识、告警处置原因与任何自由文本。

## 8. 前端

- 路由 `/m5/dashboard`（菜单“实时看板”，权限 `m5:dashboard:read`），页面
  `apps/web/src/modules/m5/views/DashboardView.vue`。
- 仅用 Element Plus 卡片/进度条/表格，**不引入 ECharts**（避免新增依赖与审批）；
  布局为自适应网格，大屏（1920×1080）与笔记本均可读。
- 状态：权限态（无权限不发请求）、错误态（message + code + traceId）、空态、加载态、
  数据时效标识（`dataQuality` + 延迟秒数）。

## 9. 迁移与回滚（V26）

- V26 **data-only**：`INSERT` 1 个权限点 + 4 条最小授权，带 `ON CONFLICT DO NOTHING` 与
  fail-fast 自检；不改 V1–V25。
- 应用回滚：下线 `/v1/dashboard/*` 与前端“实时看板”菜单；看板只读，不丢业务数据。
- 库层回滚：先删 `sys_role_permission` 再删 `sys_permission`；回滚后重新登录刷新 JWT claim。
  详见 [`docs/db/README.md`](../../db/README.md) §4.21。

## 10. 测试与验收

- 单测（m5-impl）：手算实时态势与 KPI 聚合、空数据 0/NO_DATA、延迟阈值（OK/DEGRADED）、
  数据范围 403（拒绝/空范围/SELF 缺用户）、SCOPE 下传、审计采样、响应与审计无 PHI。
- 集成（api-bootstrap）：MockMvc + 嵌入式 PG16 + Flyway（含 V26），覆盖手算 KPI/实时态势、
  告警计数、延迟字段、`date` 400、缺权限 403、无组织绑定（DataScope）403、V26 权限基线。
- 前端：Vitest 6 例（加载/权限态/错误态/空态/轮询与暂停/延迟标识）；E2E 2 例
  （加载 → 手动刷新 → 10s 自动刷新 → 暂停；无权限跳 403）。
- 覆盖率：新增代码行 ≥80%/分支 ≥70%（JaCoCo 门禁见根 `pom.xml`）。

## 11. 待确认（Q）

- **Q-1** 准点率实际开始时间：记录单 `started_at` 优先、缺失回退台次 `updated_at`。
- **Q-2** 利用率计划/实际口径；停诊日/未来日是否计入分母（ADR-0015 Q-3）。
- **Q-3** 停用术间语义：暂以软删除表示，是否需要独立停用/维修状态。
- **Q-4** 权限范围：SELF（本人台次所涉术间）是否满足麻醉医师看板；是否向术间护士/手术医师
  开放只读看板。

## 12. 负责人（占位）

- Owner：`@anesop/m5-dashboard`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
