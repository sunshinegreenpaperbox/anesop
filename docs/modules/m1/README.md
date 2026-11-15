# M1 手术排程管理（模块说明）

> 状态：M2-M1-01「手术申请接收与校验」+ M2-M2-01「排程工作台与冲突检测」+ M2-M2-02「停改台与急诊加台」+ M2-M6-01「排程分析」落地版（2026-11-13）。
> 契约：`packages/contracts/openapi/scheduling.yaml` 0.6.0、`patient.yaml` 0.3.0；
> 方案书：§2.4.2（M1 手术排程）、§2.7.1（HIS 集成）、§3.4.2/§3.4.3（实体与数据库）、
> §3.5.3（I-01a/I-01b）、§3.9.2（RBAC/数据权限）、§3.9.3（加密脱敏）。
> 全部示例、演示账号、患者、术间与申请均为 **SYNTHETIC** 合成数据。

## 1. 职责与边界

M1 负责围术期排程上游的“手术申请接收与校验”和“术间/台次排程”：

- 接收 HIS 手术申请（当前为 Mock 适配器，EXT-01 未就绪）与手工/测试创建；
- 以 `his_application_no` 为入站幂等键落库，重复导入不增行、手工重复提交 409；
- 提供申请分页/详情/取消与状态历史，向下游排程、术前访视（M2-M4-01）供数；
- 提供排程/申请所需的患者脱敏摘要（姓名、性别、出生日期/年龄、脱敏住院号、科室/病区/床号），
  **不提供证件号与手机号**（含脱敏值）；
- 维护术间主数据与日排程台次，提供规则引擎冲突检测（创建/调整事务内复检，409 + conflicts[]）；
- 停台/改台/急诊加台与变更留痕：原因码白名单、审批矩阵、抢占择期台次、通知 outbox → 默认通知矩阵 → m0 站内消息（M9-01）。

非目标：设备依赖与人员资质主数据（M3）、真实短信/企业微信通知（EXT）。排程分析
（M2-M6-01）为只读聚合，口径与已知限制见 §12。

## 2. 代码结构

```text
apps/api/modules/m1/
├── api/  com.anesop.m1.api
│   ├── application/  SurgeryApplicationService、查询条件、异常、状态语义
│   ├── schedule/     SchedulingService、SchedulingConflictException、校验/语义异常
│   ├── analytics/    SchedulingAnalyticsService（利用率/准点/取消率/CSV 导出）
│   ├── his/          HisSurgeryApplicationPort 端口与入站记录（模拟标记）
│   ├── patient/      PatientQueryService（患者脱敏摘要）
│   └── security/     M1Permissions（m1:application:* / m1:patient:read / m1:schedule:* / m1:analytics:read）
└── impl/ com.anesop.m1.impl
    ├── application/  SurgeryApplicationServiceImpl（事务/幂等/状态机/审计）
    ├── schedule/     SchedulingServiceImpl（事务/锁/规则引擎调用/审计）
    │   └── rule/     SchedulingRule、注册表与 5 条规则（重叠/术者/患者/类型/时间窗）
    ├── analytics/    SchedulingAnalyticsServiceImpl（只读 SQL 聚合 + 脱敏 CSV）
    ├── patient/      PatientQueryServiceImpl（数据范围过滤）
    ├── his/          MockHisApplicationAdapter（SYNTHETIC 生成器）
    ├── persistence/  实体与 Mapper（m1_sched 五表；术间行 FOR UPDATE）
    └── support/      InpatientNoMasker / InpatientNoHasher
```

模块边界：`m1-impl` 只依赖 `m1-api`、`m0-api`（认证主体、数据范围、审计）与基础设施；
不访问其他模块 impl，不跨 schema 读写 M0 表。`api-bootstrap` 是唯一依赖 `m1-impl` 的装配点。

## 3. 接口（scheduling 0.6.0 与 patient 0.3.0）

### 3.1 手术申请与患者摘要

| 方法 | 路径 | operationId | 权限 | 说明 |
|---|---|---|---|---|
| GET | `/v1/scheduling/applications` | `listSurgeryApplications` | `m1:application:read` | 分页/状态/紧急度/科室过滤，按数据范围裁剪 |
| POST | `/v1/scheduling/applications` | `submitSurgeryApplication` | `m1:application:write` | 手工/测试创建；重复申请号 409，未知患者 422 |
| GET | `/v1/scheduling/applications/{applicationId}` | `getSchedulingApplication` | `m1:application:read` | 详情 + 完整状态历史；越界/不存在 404 |
| POST | `/v1/scheduling/applications/import` | `importSurgeryApplications` | `m1:application:write` | 模拟 HIS 拉取导入；幂等，响应 `simulated=true` |
| POST | `/v1/scheduling/applications/{applicationId}/cancel` | `cancelSchedulingApplication` | `m1:application:write` | 取消 + 状态历史 + 审计；终态/版本冲突 409 |

患者摘要（patient 0.3.0）：`POST /v1/patients/search`（检索条件在 JSON 请求体，避免 PHI 进入
URL/访问日志）与 `GET /v1/patients/{patientId}`，需要 `m1:patient:read`（Q-3 最小权限：
SYS_ADMIN/SYS_SURGEON/SYS_OR_MANAGER）并受数据范围约束；旧 `GET /v1/patients` 标记 deprecated，
生产禁用、仅兼容。

### 3.2 术间与排程工作台

| 方法 | 路径 | operationId | 权限 | 说明 |
|---|---|---|---|---|
| GET | `/v1/scheduling/rooms?siteId=&status=` | `listOperatingRooms` | `m1:schedule:read` | 术间编号/名称/类型/状态；按 `siteId`/`status` 过滤（非法 400），受 DEPT/ROOM 数据范围约束 |
| GET | `/v1/scheduling/schedules?date=` | `listSchedules` | `m1:schedule:read` | 按日（`Asia/Shanghai`）分页查询，可按 `roomId`/`status` 过滤 |
| GET | `/v1/scheduling/schedules/{scheduleId}` | `getSchedule` | `m1:schedule:read` | 台次详情；越界/不存在 404 |
| POST | `/v1/scheduling/schedules` | `createSchedule` | `m1:schedule:write` | 创建台次；冲突 409 + `conflicts[]` |
| PUT | `/v1/scheduling/schedules/{scheduleId}` | `rescheduleSchedule` | `m1:schedule:write` | 调整时间/术间/台次序号；必须回传 `version`，冲突/过期 409 |
| POST | `/v1/scheduling/schedules/{scheduleId}/cancel` | `cancelSchedule` | `m1:schedule:write` | 停台/取消；原因码 + 原因 + 版本 |
| POST | `/v1/scheduling/schedules/conflicts` | `detectScheduleConflicts` | `m1:schedule:read` | 只读冲突预检（不落库），供工作台即时提示 |
| POST | `/v1/scheduling/schedules/{scheduleId}/stop` | `stopSchedule` | `m1:schedule:write`（IN_PROGRESS 另需 `m1:schedule:approve`） | 停台状态机 + 原因码白名单 + 变更/审计/通知；返回 CANCELLED 台次 |
| GET | `/v1/scheduling/schedules/{scheduleId}/changes` | `listScheduleChanges` | `m1:schedule:read` | 变更历史（STOP/MODIFY/EMERGENCY + 审批/抢占关系），受数据范围约束 |
| POST | `/v1/scheduling/emergency-cases` | `addEmergencyCase` | `m1:schedule:approve` | 急诊加台（201 + approval）；可抢占冲突中的择期台次 |

### 3.3 排程分析（M2-M6-01，scheduling 0.6.0 additive）

| 方法 | 路径 | operationId | 权限 | 说明 |
|---|---|---|---|---|
| GET | `/v1/scheduling/analytics/utilization?from=&to=&deptId=` | `getSchedulingUtilization` | `m1:analytics:read` | 术间利用率汇总 + 按术间；`from/to` 必填、跨度 ≤ 92 天 |
| GET | `/v1/scheduling/analytics/on-time?from=&to=&deptId=` | `getSchedulingOnTimeRate` | `m1:analytics:read` | 首台准点率汇总 + 按术间；阈值默认 ±15 分钟 |
| GET | `/v1/scheduling/analytics/cancellations?from=&to=&deptId=` | `getSchedulingCancellationRate` | `m1:analytics:read` | 取消率汇总 + 原因码分组 + 按术间 |
| GET | `/v1/scheduling/analytics/export?metric=&from=&to=&format=csv&deptId=` | `exportSchedulingAnalytics` | `m1:analytics:read` | 脱敏 CSV（UTF-8 BOM、流式）；仅聚合值与术间/日期 |

- 时间范围按 `Asia/Shanghai` 本地日期闭区间解释；`from/to` 缺失、格式非法、`from > to`、
  跨度 > 92 天、`deptId` 非法、`metric`/`format` 非法统一 400（`PARAM_INVALID`）。
- 数据范围与排程工作台一致：`ALL` 全院；`DEPT/SITE` 本科室/院区术间；`ROOM` 绑定术间；
  未绑定、无可见术间或 `SELF` → 403（fail-closed）；导出同样受范围约束。
- 审计 `ANALYTICS_QUERY` / `ANALYTICS_EXPORT`（`resource_type=ANALYTICS`），detail 只含
  metric/from/to/timezone/roomCount/计数，不含患者姓名、住院号、诊断、申请号。
- 权限点 `m1:analytics:read`（V12 授 SYS_ADMIN/SYS_OR_MANAGER/SYS_QC_MANAGER，只读；
  不授予排程写/审批权限）。前端路由 `/m1/analytics` 与菜单同权限点。

旧 `POST /v1/scheduling/conflicts/check`（`checkScheduleConflict`）标记 `deprecated`，计划 1.0.0
删除；**0.4.0 仍映射到同一 `checkConflicts` 服务方法**（响应与新端点逐字节一致），不得删除
（ADR-0011 弃用窗口）。新代码使用 `schedules/conflicts`。冲突响应结构：
`conflicts[].conflictType/severity/message/conflictingScheduleId`；`conflictType` 使用
`x-extensible-enum`（开放枚举，客户端必须容忍未知值）。契约 0.4.0 由 `enum` 放宽为
`x-extensible-enum` 属**非严格 additive**（JSON 不变、TS 由字面量联合变 `string`），已在
ADR-0011 增补与 `docs/api/README.md` §3.2 登记；强类型诉求记为 Q，待契约 1.0.0 评估。

错误语义：400 字段/枚举/日期格式/时长/原因码格式（`reasonCode` 须匹配 `^[A-Z_]{2,32}$`）；
401 未认证；403 缺权限或数据范围为空（写 `ACCESS_DENIED` 审计）；404 单对象不存在或越界
（不泄露存在性）；409 排程冲突/重复申请/非法状态流转/乐观锁（`conflictType` 分别为
`APPLICATION_ALREADY_SCHEDULED`/`STATE_CONFLICT`/`VERSION_CONFLICT` 等）；422 申请不存在或
状态不允许排程。时间窗非法（结束不晚于开始或时长不在 1–1440 分钟）统一 400，不再以 409
`TIME_WINDOW_INVALID` 返回（该枚举值保留给内部规则防御）。

### 3.2.1 台次卡片 → 各环节入口（前端）

排程工作台 `/m1/schedule` 的台次卡片提供「流程入口」下拉（Element Plus `el-dropdown`，
`data-testid="flow-menu"`），按当前账号权限**逐项渲染**，无权限项不渲染，避免把用户送到 403 页面：

| 入口 | 权限点 | 路由 | 参数来源 |
|---|---|---|---|
| 术前访视 | `m2:preop:read` | `/m2/preop/{applicationId}` | 台次 `applicationId` |
| 知情同意 | `m2:consent:read` | `/m2/consent/{applicationId}` | 台次 `applicationId` |
| 三方核查 | `m2:threeway:read` | `/m2/three-way/{applicationId}` | 台次 `applicationId` |
| 麻醉记录 | `m2:record:read` | `/m2/records/{scheduleId}` | 台次 `scheduleId` |
| 复苏（PACU） | `m2:pacu:read` | `/m2/pacu/{recordId}` | 先 `GET /v1/anesthesia/records?scheduleId=` 取记录单 |
| 术后随访 | `m2:followup:read` | `/m2/followups/{recordId}` | 同上取记录单 |

- 申请维度入口（访视/同意/三方核查）直接使用台次上的 `applicationId`；记录维度入口
  （复苏/随访）先按 `scheduleId` 查询该台次记录单并取第一条：无记录单时提示
  `请先创建麻醉记录单` 且不跳转；查询失败在页面内展示错误码/追踪号，卡片保留。
- 麻醉记录保留原快捷按钮（`data-testid="record-link"`），下拉内同时提供同一入口；
  两处均按 `m2:record:read` 显示。
- 权限点归属 M2，M1 不新增权限；入口仅做前端可见性控制，服务端仍按各模块契约鉴权。
- 记录页 `/m2/records/{scheduleId}` 的状态区已提供「复苏记录（PACU）」「术后随访」按钮
  （同样按 `m2:pacu:read`/`m2:followup:read` 显示），使用当前 `recordId` 跳转，与排程工作台入口一致。

## 4. 状态机

### 4.1 手术申请

```text
                 ┌──────────── cancel ────────────┐
                 ▼                                │
(创建/导入) ─→ SUBMITTED ─→ ACCEPTED ─→ SCHEDULED ─┴─→ CANCELLED（终态）
                    │            │
                    └────────────┴──────────────→ REJECTED（终态）
```

- 初始状态固定 `SUBMITTED`，来源 `HIS`（模拟导入）或 `MANUAL`（手工）；
- `CANCELLED`/`REJECTED` 为终态，再次取消返回 409；
- 每次创建/状态变更写 `m1_sched.application_status_history`（append-only：from_status/to_status/
  reason_code/reason/operator_id/occurred_at），同时写 `SURGERY_APPLICATION_*` 审计；
- 取消必须回传 `version`，库层乐观锁失败（并发改状态）返回 409。

### 4.2 排程台次

```text
PLANNED ─→ PUBLISHED ─→ IN_PROGRESS ─→ COMPLETED
   │            │
   └────────────┴──→ DELAYED ─→ (可继续/取消)
   └──────────────────────────→ CANCELLED（终态）
```

- 仅 `SUBMITTED`/`ACCEPTED` 申请可排程；`CANCELLED`/`REJECTED` 申请返回 422，`SCHEDULED`
  申请再次排程返回 409 `APPLICATION_ALREADY_SCHEDULED`（避免重复建台）；本任务不自动改写申请
  状态，申请状态由后续流程联动；
- 创建时 `CreateScheduleRequest.priority` 落库为 `schedule.priority`（0=ELECTIVE/1=URGENT/
  2=EMERGENCY），响应按库值回显；`staffIds`/`equipmentTags` 在契约标 `x-deferred: true`
  （M2-M2-02/M3 实现，当前服务端忽略）；
- `IN_PROGRESS`/`COMPLETED`/`CANCELLED` 台次不允许原始 0.4.0 `cancel`（409）；新停台端点
  的矩阵见 §4.3；
- 每个申请同一时刻只允许一个未取消台次（V8 部分唯一索引 `uk_schedule_application_active`）；
- 创建/调整/旧取消分别写 `SCHEDULE_CREATE/SCHEDULE_UPDATE/SCHEDULE_CANCEL` 审计。

### 4.3 停台状态机与审批矩阵（M2-M2-02）

```text
PLANNED / PUBLISHED / DELAYED ──stop（m1:schedule:write）──→ CANCELLED
IN_PROGRESS ──stop（须 m1:schedule:approve）──────────────→ CANCELLED
COMPLETED / CANCELLED ──stop──────────────────────────────→ 409 STATE_CONFLICT
任意状态 + version 过期 ─────────────────────────────────→ 409 VERSION_CONFLICT
```

| 操作 | 所需权限 | 变更记录 | 审计 | 通知 outbox |
|---|---|---|---|---|
| 计划停台（PLANNED/PUBLISHED/DELAYED） | `m1:schedule:write` | STOP/APPROVED | `SCHEDULE_STOP` | `SCHEDULE_STOPPED` |
| 术中停台（IN_PROGRESS） | `m1:schedule:write` + `m1:schedule:approve`，缺 approve 403 | STOP/APPROVED | `SCHEDULE_STOP` | `SCHEDULE_STOPPED` |
| 改台（PUT） | `m1:schedule:write`；`reasonCode` **必填**且在白名单 | MODIFY/APPROVED | `SCHEDULE_UPDATE` | `SCHEDULE_MODIFIED` |
| 旧取消（`/cancel`，0.4.0 兼容） | `m1:schedule:write`；IN_PROGRESS 409 | STOP/APPROVED | `SCHEDULE_CANCEL` | `SCHEDULE_STOPPED` |
| 急诊加台 | `m1:schedule:approve`，缺权限 403 | EMERGENCY/APPROVED | `SCHEDULE_EMERGENCY_CREATE` | `EMERGENCY_SCHEDULED` |
| 急诊抢占择期台次 | `m1:schedule:approve`；目标须 PLANNED/PUBLISHED 且 priority=ELECTIVE | 原台次 STOP + 急诊 EMERGENCY（各一条） | `SCHEDULE_PREEMPT` | `SCHEDULE_PREEMPTED` |

- 原因码白名单：停台 `PATIENT_CONDITION`/`SURGEON_UNAVAILABLE`/`ROOM_EQUIPMENT_FAILURE`/
  `ANESTHESIA_RISK`/`SCHEDULE_CHANGE`/`OTHER`；改台另含 `SCHEDULE_ADJUST`（0.5.0 起必填，
  不预填、不静默补默认码）；急诊 `EMERGENCY_GREEN_CHANNEL`/`EMERGENCY_PREEMPT`/`OTHER`。
  缺失/非白名单 400（`details[].field=reasonCode`）。
- 变更记录 `m1_sched.schedule_change_request`：MVP 由执行人直接批准（status=APPROVED，
  requested_by=approved_by），PENDING/REJECTED 预留给后续双人审批（EXT，需院方确认审批流）。
  表为**完全 append-only**：审批结果在同一事务内以 APPROVED 行写入，V9 触发器拒绝
  UPDATE/DELETE/TRUNCATE（SQLSTATE 55000），事后更正只能新增记录（B-2）。
- **上线前风险登记（Q-2）**：MVP 单人审批存在自我审批风险；正式上线前必须引入独立审批角色/
  双人双控（M6 审批流），并以 ADR-0012 为前置条件，不得以当前单人审批直接用于生产。
- 急诊抢占规则：仅 `ROOM_OVERLAP` 冲突可通过抢占消除；术者/患者/术间类型冲突仍 409。未指定
  `preemptScheduleId` 时返回 409 + `conflicts[]`，前端按 `conflictingScheduleId` 提示候选；
  抢占成功后原择期台次 `CANCELLED`、reason=`EMERGENCY_PREEMPT`、版本 +1，保留原时段供统计，
  并写 STOP 变更记录（`preempt_schedule_id`=急诊台次）便于双向追溯。抢占目标在事务内
  `SELECT ... FOR UPDATE` 锁定后复检状态/优先级/术间/时段，任一变化返回 409
  `PREEMPT_CONFLICT`，并与并发改台/停台按“advisory → 术间行 → 被抢占行”顺序加锁（B-1）；
  STOP 留痕说明可配置（`anesop.m1.schedule.preempt-reason`），默认中性文案、不含 PHI（S-2）。
- 通知端口 **站内消息中心投递**（M9-01）：`NotificationPort.publish` 写
  `m1_sched.notification_outbox`（PENDING，与业务事务同提交），`NotificationDispatcher` 每次
  drain 1 条（`FOR UPDATE SKIP LOCKED`）→ `MessageCenterNotificationPort.deliver` 按默认通知
  矩阵写 m0 `sys_message` → SENT；失败按 60s×次数退避，3 次后 FAILED（固定文案，不含 PHI）。
  payload 只含台次/申请/术间/变更标识、时间与原因码，数据库 `ck_notification_outbox_no_phi`
  拒绝 PHI 键、`ck_notification_outbox_no_phi_value` 拒绝 15 位以上连续数字（身份证/长住院号
  值级兜底）。真实短信/企业微信仍为 **EXT**（待院方）；可用
  `anesop.m1.notification.scheduler-enabled=false` 关闭派发做应用回滚。

### 4.4 通知矩阵与站内消息投递（M9-01）【待确认】

> 默认矩阵只固化“事件→角色→站内消息”，**频率、免打扰、未读升级路径均为【待确认】**；
> 口径见 [ADR-0025](../../adr/0025-消息投递与体征映射口径.md)。SYS_ADMIN 是否纳入临床告警
> 由院方确认（Q-M9-02），本期不投递。

| 业务事件（outbox event_type） | 模板编码 | 消息类型 | 接收角色（启用用户） | 参数白名单（无 PHI） |
|---|---|---|---|---|
| `SCHEDULE_MODIFIED` / `SCHEDULE_PREEMPTED` | `SCHEDULE_CHANGE` | `SCHEDULE` | SYS_ANESTHESIOLOGIST、SYS_OR_MANAGER | scheduleId、roomId、startAt、reasonCode |
| `SCHEDULE_STOPPED` | `SCHEDULE_STOP` | `SCHEDULE` | 同上 | scheduleId、roomId、reasonCode |
| `EMERGENCY_SCHEDULED` | `EMERGENCY` | `SCHEDULE` | 同上 | scheduleId、roomId、startAt、reasonCode |
| `ALERT_CRITICAL`（含未确认，来自 m2） | `CRITICAL_ALERT` | `ALERT` | 同上 | scheduleId、roomId、alertId、metric、severity、triggeredAt |

- 接收人：按角色编码经 m0-api `NotificationRecipientDirectory` 取**启用**用户（`status=1`、
  未删除），m1 不访问 m0 用户/角色表；角色下无启用用户时只记元数据日志，不重试、不改投。
- 去重：`sys_message.dedupe_key = OUTBOX:<outboxId>:<recipientUserId>:<templateCode>`（V29
  唯一索引），同一业务事件+接收人+模板只落一条；重试/并发派发命中唯一键时返回原消息。
- 无 PHI：只从模板白名单键取值，payload 其他字段丢弃；日志只记事件类型、outbox 标识与接收人数。
- 事件到消息的时延 = 调度轮询间隔（默认 30s，`anesop.m1.notification.poll-interval-ms`）；
  **是否要求实时/免打扰/升级【待确认】**（Q-M9-01）。

## 5. HIS 适配器与 EXT-01

- 端口：`com.anesop.m1.api.his.HisSurgeryApplicationPort#pull(count, seed)`，返回
  `HisSurgeryApplicationBatch(simulated, adapter, applications)`；
- 当前实现：`MockHisApplicationAdapter`，只生成 SYNTHETIC 数据，固定 `simulated=true`、
  `adapter=MOCK_HIS`，条数严格 1–5（请求多少返回多少，越界 400），相同 `seed` 生成相同申请号
  （保证重复导入幂等）；
- 配置：`anesop.m1.his.mock.base-seed`（默认 20260101）、`department-name`、`department-id`、`site-id`
  （测试 profile 指向 V100 演示组织；生产未配置时申请不带科室标识，DEPT 用户不可见）；
- EXT-01（真实 HIS HL7 v2/REST 对接）未就绪：真实适配器接入时只替换端口实现，服务层与契约不变；
- 响应中的 `simulated`/`adapter`/`message` 明确标识“模拟”，禁止在验收/演示中把模拟数据当作真实 HIS 数据。

## 6. 数据与脱敏

- `m1_sched.patient_snapshot`：`his_patient_no` 唯一；只存姓名、性别、出生日期/年龄、
  `inpatient_no_masked`、`inpatient_no_hash`、科室/病区/床号；**不存身份证、手机号、住院号明文**；
- `inpatient_no_hash`：HMAC-SHA-256（pepper 来自 `anesop.m1.inpatient-pepper.value` /
  `ANESOP_INPATIENT_PEPPER`，Base64 且解码后 ≥32 字节），仅用于匹配/去重，唯一索引、不可逆、
  不出接口；缺失/非法 Base64/长度不足默认拒绝启动（fail-closed），只有 dev/test 显式
  `anesop.m1.inpatient-pepper.allow-dev-generated=true` 才生成进程内随机 pepper 并 WARN；
- HIS 导入患者 upsert 使用单条 `INSERT ... ON CONFLICT (his_patient_no) DO UPDATE ... RETURNING id`，
  并发导入不在已中止事务内回查；`birthDate` 缺失拒绝导入/创建（400），年龄不用 now() 伪造；
- 状态历史 `reason_code varchar(64)` 与 `reason varchar(500)` 分列；表级触发器拒绝
  UPDATE/DELETE/TRUNCATE（append-only，SQLSTATE 55000）；
- 排程表 `m1_sched.operating_room` / `schedule`（V8）：时间统一 `timestamptz`，`end_at > start_at`
  由 `ck_schedule_time_window` 约束；取消保留 `reason` 与审计，不物理删除；
- 展示：住院号统一 `前 4 + **** + 后 4`（如 `SYNT****0001`）；日志与审计 detail 不含姓名、
  诊断原文、住院号（明文或脱敏值）、证件号、手机号；冲突文案只含术间编号/台次序号/时段；
- 数据范围：创建/调整/冲突预检在术间范围外**再校验申请与患者可见性**，不可见统一 404 且响应
  不含 `patientName/hisPatientNo`；ROOM 范围的文档例外——ROOM 用户无组织绑定，申请可见性按其
  绑定术间的 `dept_id/site_id` 推导（仅用于申请可见性，不扩大术间范围）。
  `ALL` 不过滤；`DEPT/SITE` 按申请/患者/术间的 `dept_id`/`site_id`（逻辑外键 →
  `m0_platform.sys_org.id`）；`ROOM` 按 `sys_user_room` 绑定术间；`SELF` 按申请创建人；
  `ROOM`/无有效绑定/`SELF` 看术间 → 403 或不可见，fail-closed；单对象越界一律 404。

- 演示数据：`scripts/seed-demo-data.sh` 通过 M1 REST API 生成 HIS 模拟导入、手术申请
  （择期/限期/急诊、取消）与排程台次（改台/停台/急诊抢占/冲突解决），全部为 `SYNTHETIC`；
  用法见 [`docs/guides/demo.md`](../../guides/demo.md) §6。

## 7. 权限与审计

- V7 新增 `m1:application:read`（SYS_ADMIN、SYS_OR_MANAGER、SYS_SURGEON）、
  `m1:application:write`（SYS_ADMIN、SYS_OR_MANAGER）与 `m1:patient:read`
  （SYS_ADMIN、SYS_SURGEON、SYS_OR_MANAGER，患者摘要最小权限）；V2 的 `m1:access` 菜单占位保留；
- V8 新增 `m1:schedule:read`/`m1:schedule:write`，**仅授 SYS_ADMIN、SYS_OR_MANAGER**（最小权限：
  外科/麻醉医师不直接改排程）；V9 新增 `m1:schedule:approve`（同样仅授 SYS_ADMIN、
  SYS_OR_MANAGER），用于术中停台与急诊加台/抢占；V12 新增 `m1:analytics:read`
  （SYS_ADMIN/SYS_OR_MANAGER/SYS_QC_MANAGER），仅用于排程分析只读与导出；
- 审计动作：`SURGERY_APPLICATION_CREATE/IMPORT/CANCEL`（resource_type=`SURGERY_APPLICATION`）、
  `SCHEDULE_CREATE/UPDATE/CANCEL/STOP/PREEMPT/EMERGENCY_CREATE`（resource_type=`SCHEDULE`，
  detail 只含 applicationId/roomId/时段/序号/reasonCode/changeId）与
  `ANALYTICS_QUERY/ANALYTICS_EXPORT`（resource_type=`ANALYTICS`，detail 只含聚合口径与计数）；
- 403 由 `ApiExceptionHandler` 写 `ACCESS_DENIED` 审计；审计 detail 只含状态、来源、原因码等非 PHI 字段。

## 8. 排程规则引擎（`SchedulingRule` + 注册表）

| 规则 | code / conflictType | 阻断 | 判定 |
|---|---|---|---|
| 术间时段重叠 | `ROOM_OVERLAP` | 是 | 同术间、半开区间 `[start,end)` 真重叠；**相邻相接（end=start）不算冲突** |
| 术者冲突 | `STAFF_CONFLICT` | 是 | 申请术者姓名相同且窗口重叠（跨术间）；术者缺失不评估 |
| 患者冲突 | `PATIENT_CONFLICT` | 是 | 同一 patientId 窗口重叠（跨术间）；患者未知不评估 |
| 术间类型匹配 | `ROOM_TYPE_MISMATCH` | 是 | 申请 `requiredRoomType` 非空且与术间 `roomType` 不一致 |
| 时间窗/时长 | `TIME_WINDOW_INVALID` | 是 | 保留为内部防御；API 边界在进入规则前统一 400，正常请求不会命中 |

- 规则实现为 Spring Bean（`@Order` 排序），`SchedulingRuleRegistry` 汇总；新增规则只新增 Bean，
  服务/控制器不感知具体规则（可配置扩展点）；注册表亦支持显式注入固定规则列表用于测试。
- 服务级状态冲突使用专用类型，避免误用规则类型：`APPLICATION_ALREADY_SCHEDULED`（申请已有
  生效台次）、`VERSION_CONFLICT`（乐观锁版本过期）、`STATE_CONFLICT`（台次状态不允许操作）。
- 设备/隔离/资质（`EQUIPMENT_CONFLICT`/`ISOLATION_CONFLICT`/`QUALIFICATION_MISMATCH`）为后续
  规则预留枚举值，当前不产生。
- 冲突检测在创建/调整事务内与只读预检共用同一注册表，避免“预检通过、保存失败”口径漂移。

## 9. 并发策略（高风险：事务与并发）

1. 创建/调整显式 `@Transactional(isolation = READ_COMMITTED)`（PostgreSQL 默认隔离级别）；
2. **全局锁顺序（避免死锁）**：先人员/患者维度 advisory 锁，再术间行锁。
   - 人员/患者：`pg_advisory_xact_lock(hashtext('schedule:surgeon:<name>'))`、
     `pg_advisory_xact_lock(hashtext('schedule:patient:<id>'))`；多个 key 去重后按**字典序**
     升序获取（`TreeSet`），任意两事务的加锁顺序一致，不会交叉等待；锁随事务提交/回滚释放。
   - 术间：`SELECT ... FROM m1_sched.operating_room WHERE id=? FOR UPDATE`，只锁目标术间。
3. 锁内查询重叠台次并执行规则引擎；后到事务在前一事务提交后读到已提交台次，复检到冲突；
   冲突抛 `SchedulingConflictException`，不写库，Web 层返回 409 + `conflicts[]`；
4. 数据库第二道防线：`ck_schedule_time_window`（end>start）、`ck_schedule_priority` 与部分唯一
   索引（申请唯一活动台次）。哈希碰撞只会导致无关排程短暂串行，不影响正确性。
- 并发测试（`CyclicBarrier` 制造真实竞争，两线程同时进入 `create`）：
  同室同窗、跨术间同术者、跨术间同患者三个用例 → **恰一个成功、一个 409**，输出
  `success=1 ... rows=1`。

## 10. 测试

- 后端单测（`m1-impl`）：服务状态机/幂等/数据范围/校验、Masker/Hasher、Mock 适配器、
  排程 5 条规则（含边界相接）与排程服务（冲突/版本/状态/审计/数据范围/原因码/时间窗）；
  m1-impl 共 142 用例，JaCoCo 行 93.86%、分支 71.69%（阈值 80/70）；
- 后端集成（`api-bootstrap`，嵌入式 PG16）：模拟导入幂等、脱敏与无明文、创建/列表/筛选/详情、
  取消状态历史 + 审计、403、DEPT 数据范围、患者摘要接口；排程 14 用例：术间列表/`siteId`+
  `status` 过滤与非法参数 400、创建→日查询→详情→调整→取消 + 3 条审计、409 + conflicts[]
  （重叠/术者/患者/类型）、冲突预检与新/旧端点响应一致、时间窗 400/申请状态（409/422）、
  403/DEPT/ROOM 越界与跨科室申请 404（无 PHI 泄露）、并发同室/同术者/同患者恰一成功、
  1000 条日排程查询 <2s 本地基准；后端 m1 相关用例全绿（m1-impl 142；api-bootstrap 含
  排程分析 11（10 功能 + 1 大区间性能）及其余集成用例）；
- 前端 Vitest：申请列表渲染/筛选/错误/权限按钮隐藏、详情与 404；排程工作台加载/冲突预检/
  409 提示（优先解析 `conflicts[]` 并保留 `conflictingScheduleId`，缺失时回退 `details`）/
  冲突解决/仅 PLANNED/PUBLISHED 显示调整取消/本地时区日期/只读权限；
- Playwright E2E：`m1-applications.spec.ts`（4 条）+ `m1-schedule-conflict.spec.ts`
  （E2E-01：制造冲突→提示→改术间解决；只读权限）；
- 迁移/安全：V7/V8 结构断言（`uk_schedule_application_active`/`ck_schedule_time_window`/
  `ck_schedule_priority`/权限授予/5 个 SYNTHETIC 术间）、状态历史 append-only 负向用例、
  并发导入仅建一行、缺 pepper 启动失败；
- 排程分析：m1-impl 单测 15（公式/阈值/空数据/数据范围/CSV 脱敏与公式注入/计划首台排除/
  配置夹紧/审计）、api-bootstrap 集成 11（手算期望、计划首台取消与未开始排除、跨日与 23:59、
  DEPT/ROOM/未绑定 403、参数校验、CSV 行数/无 PHI、审计、空数据 0、92 天 3000 台次性能）、
  Vitest 7（含 SYS_QC_MANAGER 科室下拉、evaluatedCaseCount=0 暂无数据）、E2E 2
  （SYS_QC_MANAGER 分析页/无权限 403）；
- 契约：`bash scripts/gen-contracts.sh` + `bash scripts/contract-check.sh`（spectral/oasdiff/HEAD
  baseline/漂移全绿）。

## 11. 已知限制与后续

- 真实 HIS 适配器（HL7 v2/REST）与回写为 EXT-01，接入前只允许模拟导入；
- `SITE` 数据范围当前按 `site_id` 精确匹配（DEPT 按 `dept_id`），未展开科室子树；
- 申请状态 `ACCEPTED`/`SCHEDULED` 的推进接口由 M2-M2-02 承接；本任务只落地排程台次，不改写申请状态；
- 设备依赖、隔离要求与医师资质规则待 M3 人员资质主数据与 M2-M2-02 评审后接入（枚举已预留；
  `ConflictCheckRequest.staffIds/equipmentTags` 契约标 `x-deferred: true`，服务端当前忽略）；
- 1000 条日排程查询本地基准 <2s（`SchedulingApiIntegrationTest#thousandScheduleQueryStaysUnderLocalBaseline`，
  嵌入式 PG16 + MockMvc，含 JSON 序列化；仅作本机回归基线，非生产容量承诺）；
- `patient.yaml` 中 `/v1/surgery-applications*` 为 S0-05 历史端点，已在 0.3.0 标记 `deprecated`，
  由 scheduling 的 `/v1/scheduling/applications*` 取代，M1 契约收敛时统一下线；
- 排程分析准点率的实际开始时间：V8 无独立 `actual_start_at` 列、HIS 未回写，MVP 以台次
  `updated_at` 近似（`actualStartSource=SCHEDULE_UPDATED_AT`），已完成台次可能被后续状态更新
  覆盖；【待确认】正式口径须由 HIS/M3 回写真实开台/切皮时间后替换（见 §12）。
- **准点率数据不足（Q-1）**：准点率依赖 M3/HIS 回写实际开台时间与真实台次状态；当前系统内
  V8 只有计划时间与 `updated_at`，HIS 未接入，**实际开台数据不足**。因此
  `evaluatedCaseCount=0` 时前端显示“暂无数据”而非 0.0%，该指标在 HIS 接入前不得用于绩效考核。

## 12. 排程分析口径（M2-M6-01）

> 口径版本 `analytics-1.0.0`；时区固定 `Asia/Shanghai`（无 DST，跨日边界按本地日计算）。
> 决策 D-21：系统出数、绩效规则院方另定；D-14：运营指标 T+1。以下阈值/可用时长为默认值，
> 院方确认后只改配置，不改代码分支。口径决策与待确认项见 `docs/adr/0015-排程分析口径与待确认项.md`。

### 12.1 术间利用率

- 公式：`总手术时长 / 可用时长`。
- 分子：状态非 `CANCELLED` 的台次与查询区间重叠部分的**计划**分钟数（非实际占用/麻醉时长，
  【待确认】）；跨日/跨区间按重叠裁剪，同一台次不重复计入次日。
- 分母：可见术间数 × 区间天数（含首尾）× `anesop.m1.analytics.room-daily-available-minutes`
  （默认 **600 分钟/间/日**）。分母按区间内**全部自然日**计算，当前**未排除未来日期、停诊日
  或节假日**，院方实际开放时长与开诊日历均【待确认】。
- 空数据集返回 0，不做除零；利用率可大于 1（超排）。按术间与汇总同时返回。

### 12.2 首台准点开台率

- 公式：`准点首台台次 / 已评估首台台次`。
- 首台：同一术间、同一本地日期内**计划开始最早**的台次（不论状态，按 `start_at` 排序，
  相同则按 `sequence`、`id` 稳定排序）。
- 不纳入评估（Q-2）：若该计划首台为 `CANCELLED` 或未开始（`PLANNED`/`PUBLISHED`/`DELAYED`），
  或缺少实际开始时间，则该术间-日**不进入分母**，计入 `excludedDays`；
  **不以下一台次替代**。恒等式 `firstCaseCount = evaluatedCaseCount + excludedDays`。
- 判定：`abs(实际开始 - 计划开始) ≤ 阈值`；阈值
  `anesop.m1.analytics.on-time-threshold-minutes` 默认 **15 分钟**（可配置 0–1440）。
- 实际开始：**MVP 以台次 `updated_at` 近似**（V8 无独立 `actual_start_at`，HIS 未回写；
  【待确认】）。已完成台次若后续有状态更新，`updated_at` 可能晚于实际开台，指标会偏保守；
  正式口径须由 HIS/M3 回写真实切皮/开台时间后切换，并通过 `actualStartSource` 标识来源。
  当前系统内实际开台数据不足（见 §11）。
- 空数据集/无可评估首台返回 0，不做除零；`evaluatedCaseCount=0` 时前端展示“暂无数据”
  而非 0.0%。

### 12.3 取消率

- 公式：`取消台次 / 总台次`。
- 统计区间内（按台次计划开始日期归属）全部未删除台次；已取消台次也进入分母。
- 取消定义 `status=CANCELLED`，含急诊抢占择期台次（原因码 `EMERGENCY_PREEMPT`）。
- 原因码取该台次最近一条 `STOP` 变更记录；无记录的历史数据归 `UNSPECIFIED`，不臆造原因。
- 按原因码 `shareOfCancelled` 与按术间同时返回；分母为 0 时比率为 0。

### 12.4 配置（application.yml）

| 配置 | 默认 | 说明 |
|---|---:|---|
| `anesop.m1.analytics.on-time-threshold-minutes` | 15 | 准点阈值（±分钟） |
| `anesop.m1.analytics.room-daily-available-minutes` | 600 | 每术间每日可用分钟（利用率分母） |
| `anesop.m1.analytics.max-range-days` | 92 | 查询/导出最大跨度（含首尾）；配置 >92 时夹紧为 92 并 WARN |

非法值（阈值 <0/>1440、可用分钟 <1/>1440、跨度 <1/>366）启动即拒绝（fail-closed）；
跨度配置 93–366 可启动但实际按 92 生效并留 WARN 日志（契约上限固定 92，不随配置放宽）。
