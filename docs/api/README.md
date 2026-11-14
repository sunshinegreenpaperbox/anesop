# API 契约（OpenAPI 3.1）

> 状态：S0-05 契约基线 + M2-M0-01 数据范围 + M2-M0-02 用户管理 + M2-M1-01 手术申请 + M2-M2-01 排程工作台 + M2-M2-02 停改台/急诊加台 + M2-M4-01 术前访视与评估 + M2-M5-01 知情同意与三方核查 + M2-M6-01 排程分析 + M3-M2-01 麻醉记录单/时间轴 + M3-M8-01 设备采集接入 + M3-M2-02 术中预警与抢救记录 + M3-M2-03 锁单/修订/CA 签名（模拟） + M3-M10-01 复苏记录与转出（Aldrete/SBAR） + M3-M11-01 术后随访与镇痛 + M4-M15-01 药品目录与麻精五专（含 M3 T-4 收口）
（`auth 0.5.0`、`patient 0.3.0`、`scheduling 0.6.0`、`anesthesia 0.10.0`、`device 0.1.0`、`drug 0.1.0`）。契约是接口的唯一事实源，前端 `apps/web/src/api/generated/` 与后端 DTO 都必须由契约派生，禁止手改生成物。
>
> 事实来源：方案书 §3.4（领域模型）、§3.5（接口与集成）、§3.9.2（RBAC 与数据权限）、§2.4.1（M0）、§2.4.2（M1）、§2.4.3（M2）。

## 1. 目录与契约清单

```text
packages/contracts/
├── openapi/
│   ├── auth.yaml          # 认证、令牌、当前用户、会话
│   ├── patient.yaml       # 患者主索引、手术申请
│   ├── scheduling.yaml    # 术间、排程、冲突、急诊加台、停台、排程分析
│   ├── anesthesia.yaml    # 术前访视与评估、记录单、锁单/修订、三方核查、随访
│   ├── device.yaml        # 设备登记、网关批量上报（幂等）、体征窗口查询、采集健康摘要
│   ├── drug.yaml          # 药品目录、麻精专册/交接/基数/日结、药房适配器状态
│   └── baseline/          # 当前已发布兼容基线（6 份快照）
├── rules/error-responses.js
├── generated/typescript/  # openapi-typescript 生成物（禁止手改）
├── .spectral.yaml
└── package.json
```

| 契约 | 路径数 | operationId 数 | 覆盖能力 | 方案书对应 |
|---|---:|---:|---|---|
| `auth.yaml` | 10 | 12 | 登录、刷新、登出、当前用户、改密、会话列表、数据范围示例、用户管理（列表/详情/创建/更新/重置口令） | §2.4.1、§3.9.2、§3.9.3、§3.9.5 |
| `patient.yaml` | 7 | 7 | 患者摘要查询/详情（POST `/search` 检索；GET 标记 deprecated）、兼容字段、历史手术申请端点（0.3.0 标记 deprecated） | §2.4.1、§2.4.2、§3.5.3 I-01a/I-01b |
| `scheduling.yaml` | 17 | 20 | 手术申请创建/列表/详情/模拟导入/取消、术间列表、排程查询/创建/详情/调整、冲突即时检测、急诊加台（审批 + 抢占）、停台、变更历史、排程分析（利用率/准点/取消率/CSV 导出） | §2.4.2、§2.3.2、§2.7.2、§3.4.2 |
| `anesthesia.yaml` | 37 | 54 | 术前访视与评估（0.3.0）、知情同意模板/停用/记录/签署/拒绝/撤销（0.4.0）、申请维度三方核查（0.4.0）、麻醉记录单创建/查询/保存与时间轴/用药/输液出入量（0.5.0）、预警规则版本化/告警评估与确认忽略/抢救记录 append-only（0.6.0）、实时触发/评估水位/规则待确认与历史版本/抢救人员留痕（0.7.0）、锁单/锁单前置清单/修订版本/版本差异/当前有效值投影与 CA 占位签名（0.8.0→0.8.1）、复苏记录入室/Aldrete 评估时间序列/转出 SBAR 与达标快照（0.9.0）、旧访视骨架读写、记录维度三方核查读写、随访 | §2.4.3、§3.4.2、§3.9.4 |
| `device.yaml` | 4 | 5 | 设备登记与术间绑定（唯一 gatewayId+deviceId）、网关批量上报（batchKey 批次幂等 + 行级唯一键）、体征窗口查询（质量过滤/分页）、设备离线判定与采集健康摘要 | §3.6、§3.6.5 |
| `drug.yaml` | 10 | 15 | 药品目录（列表/检索/详情/创建/更新）、五专科室选择、麻精专册登记、专柜交接、基数维护、日结/班结（平账）、药房适配器状态（EXT-02 SIMULATED） | §2.5、§3.4、§3.9.2 |
| **合计** | **84** | **112** | 6 个限界上下文骨架 | — |

每份契约均定义共享组件：`Error`（`code/message/traceId/details`）、`PageMeta`、`AuditFields`（`createdAt/createdBy/updatedAt/updatedBy/version`）、`IdResponse`（按域按需定义，auth 已移除未使用组件），以及 `401 Unauthorized`、`403 Forbidden`、`400 BadRequest`、`404 NotFound`、`409 Conflict`、`422 UnprocessableEntity` 响应组件。各域只保留实际使用到的组件，`oas3-unused-component` 保持 0 warning。

## 2. 基础约定

- **Base URL**：`https://anesop.local/api`；路径带主版本 `/v1/...`，完整示例 `https://anesop.local/api/v1/auth/login`（§3.5.1）。
- **协议**：HTTPS + JSON（`application/json; charset=utf-8`）；生产网无互联网出口，患者数据不出院。
- **时间**：RFC 3339，服务端统一 `Asia/Shanghai` 展示；库内 `timestamptz`（§3.4.3）。
- **标识**：雪花 ID 在接口层统一序列化为字符串，避免前端 19 位整数精度丢失（§3.4.3）。
- **审计与并发**：业务实体返回 `AuditFields`；更新请求必须回传 `version`，冲突返回 409，不静默覆盖。
- **逻辑删除**：临床数据不物理删除；契约暂不暴露 `deletedAt`，后续按迁移与契约评审扩展。
- **脱敏返回**：M1 排程视图只返回 `PatientSummary`/`Patient` 的最小必要字段（姓名、性别、出生日期/年龄、脱敏住院号、科室/病区/床号）；**不返回证件号与手机号（含脱敏值）**，住院号明文与哈希不出接口。规则见第 6 节。

## 3. 认证与授权

- 全局 `security: [{ bearerAuth: [] }]`；`bearerAuth` 为 HTTP Bearer + JWT（§3.9.5）。
- `POST /v1/auth/login` 与 `POST /v1/auth/refresh` 使用 `security: []`，其余接口均需访问令牌；`logout` 成功返回 `204`（无响应体），仅声明 204/401。
- 访问令牌短时效（示例 1800 秒），刷新令牌轮换；默认会话 30 分钟无操作失效，连续 5 次失败锁定 30 分钟（§3.9.1）。
- 权限粒度到“菜单 + 操作”（如 `anesthesia.record.lock`）；数据权限四类：`ALL`、`DEPARTMENT`、`ROOM`、`OWN_PATIENTS`（§3.9.2）。
- 令牌与日志不得包含姓名、身份证、住院号、手机号；审计日志保留 ≥6 个月，业务角色不可删除（§3.9.1）。
- **用户管理（M2-M0-02，0.5.0 additive）**：`/v1/auth/users` 系列端点需要 `m0:user:read` /
  `m0:user:write`（V6 仅授予 `SYS_ADMIN`）。手机号只以 `phoneMasked`（如 `138****5678`）返回，
  契约中**没有**手机号明文、`phone_enc` 密文、`phone_hash` 哈希字段；创建与管理员重置口令都强制
  `mustChangePassword=true`，重置的一次性初始口令仅本次响应返回，并在同一事务撤销该用户全部
  刷新令牌；更新工号撞院内唯一键返回 409。示例全部为 SYNTHETIC。

### 3.1 手术申请与患者摘要（M2-M1-01，0.3.0 additive）

- `scheduling.yaml` 新增 `/v1/scheduling/applications`（列表/创建）、`/import`（模拟 HIS 拉取）、
  `/{applicationId}`（详情）、`/{applicationId}/cancel`（取消）；权限点 `m1:application:read|write`
  （V7：read 授 SYS_ADMIN/SYS_OR_MANAGER/SYS_SURGEON，write 授 SYS_ADMIN/SYS_OR_MANAGER）。
- 申请以 `hisApplicationNo` 为幂等键：模拟导入重复执行按 `ON CONFLICT DO NOTHING` 跳过，
  响应 `simulated=true`/`adapter=MOCK_HIS`；手工重复提交 409。
- 状态机 `SUBMITTED → ACCEPTED → SCHEDULED`，`CANCELLED`/`REJECTED` 终态；每次创建/变更返回
  `statusHistory`（列表为空数组，详情为完整历史）。
- `patient.yaml` 0.3.0：新增 `POST /v1/patients/search`（检索条件在 JSON 请求体，避免 PHI 进入
  URL/访问日志）；`GET /v1/patients` 标记 `deprecated`（查询参数可能含 PHI，生产禁用，仅兼容）；
  列表/详情收敛为脱敏摘要，**不返回证件号/手机号真实值**。为满足“字段只增不删”，0.3.0 保留
  `idCardMasked`/`phoneMasked`/`inpatientNo` 三个响应字段并标 `deprecated: true`，实现固定返回
  空字符串（不返回真实值），计划 1.0.0 major 删除。
- 编号口径统一 `hisPatientNo`：`patientNo` 在 patient 契约保留但标记 `deprecated` 并回填
  `hisPatientNo`；scheduling 的 `PatientSnapshot` 中 `patientNo` 标记 `deprecated` 并回填 `hisPatientNo`（1.0.0 删除）。`patientId` 统一为 UUID 字符串。
- 创建/导入无资源路径参数，未知患者按语义返回 **422**；单对象查询（患者/申请详情）仍返回 404；
  导入接口不声明不可达的 422，保留 400/403/409。
- 历史 `/v1/surgery-applications*` 端点标记 `deprecated`，由 scheduling 同名能力取代，计划 1.0.0 下线。

### 3.2 排程工作台与冲突检测（M2-M2-01，scheduling 0.4.0 additive）

- 新增/启用 `GET /v1/scheduling/rooms`、`GET/POST /v1/scheduling/schedules`、
  `GET/PUT /v1/scheduling/schedules/{scheduleId}`、`POST /v1/scheduling/schedules/{scheduleId}/cancel`、
  `POST /v1/scheduling/schedules/conflicts`；权限点 `m1:schedule:read|write`（V8 仅授
  SYS_ADMIN/SYS_OR_MANAGER，最小权限）。
- `OperatingRoom` 新增可选 `roomType`（GENERAL/HYBRID/INTERVENTION）；`SurgeryApplication` 与
  创建请求新增可选 `requiredRoomType`（NULL=不限），供术间类型匹配规则使用。
- 冲突：创建/调整在事务内复检，409 `Error.conflicts[]` 返回
  `conflictType/severity/message/conflictingScheduleId`；`details[]` 同步便于前端直接展示。
  `Conflict.conflictType` 改为 **`x-extensible-enum` 开放枚举**（新增值不破坏客户端，客户端必须
  容忍未知值），已登记 ROOM_OVERLAP/STAFF_CONFLICT/PATIENT_CONFLICT/ROOM_TYPE_MISMATCH/
  TIME_WINDOW_INVALID/APPLICATION_ALREADY_SCHEDULED/VERSION_CONFLICT/STATE_CONFLICT 及后续
  设备/隔离/资质预留值。**注意**：enum→x-extensible-enum 属"非严格 additive"（JSON 不变，
  TS 生成类型由字面量联合放宽为 `string`），已在 ADR-0011 增补登记；契约强类型诉求记为 Q，
  1.0.0 前保持可扩展枚举，前端按字符串分支。
- 服务级冲突类型不再复用规则类型：申请已有生效台次=`APPLICATION_ALREADY_SCHEDULED`、乐观锁
  过期=`VERSION_CONFLICT`、状态不允许=`STATE_CONFLICT`；时间窗非法在 API 边界统一 400，
  `TIME_WINDOW_INVALID` 仅保留内部规则防御。`CreateScheduleRequest.priority` 落库并在响应回显；
  `ConflictCheckRequest.staffIds/equipmentTags` 标 `x-deferred: true`（M2-M2-02/M3 实现）。
- 旧 `POST /v1/scheduling/conflicts/check` 标记 `deprecated`（由 `schedules/conflicts` 取代，
  计划 1.0.0 删除）；0.4.0 仍映射同一服务方法（响应一致），不删除任何既有字段/端点。
- `GET /v1/scheduling/rooms` 按契约实现 `siteId`/`status` 查询参数过滤，非法值 400。

### 3.3 停改台、急诊加台与变更留痕（M2-M2-02，scheduling 0.5.0）

> 0.5.0 尚未发布：其中「改台 reasonCode 必填」与「移除急诊端点不可达的 200」相对 HEAD 的
> 0.4.0 是**已评审的未发布窗口收紧**（ADR-0012）；合入前需把评审通过的 0.5.0 契约复制到
> baseline 并提交，HEAD baseline 门禁才会转绿。

- **停台** `POST /v1/scheduling/schedules/{scheduleId}/stop`（`stopSchedule`）：原因码必填且
  必须在白名单内（`PATIENT_CONDITION`/`SURGEON_UNAVAILABLE`/`ROOM_EQUIPMENT_FAILURE`/
  `ANESTHESIA_RISK`/`SCHEDULE_CHANGE`/`OTHER`），`reason` 可选；返回 200 + `Schedule`
  （status=`CANCELLED`）。`PLANNED`/`PUBLISHED`/`DELAYED` 直接停台；`IN_PROGRESS` 术中停台
  必须持 `m1:schedule:approve`（否则 403）；终态 409、版本过期 409。成功写
  `schedule_change_request`（STOP/APPROVED）、`SCHEDULE_STOP` 审计与通知 outbox
  （`SCHEDULE_STOPPED`，payload 无 PHI）。旧 `POST .../cancel` 保留为 0.4.0 兼容（描述注明
  新流程用 `/stop`），计划 1.0.0 收敛；兼容端点同样复用停台留痕路径，写 STOP 变更记录与
  outbox，不绕过变更历史（S-1）。
- **改台** 复用 `PUT /v1/scheduling/schedules/{scheduleId}`：`reasonCode` **必填**（白名单
  `SCHEDULE_ADJUST`/`PATIENT_CONDITION`/`SURGEON_UNAVAILABLE`/`ROOM_EQUIPMENT_FAILURE`/
  `ANESTHESIA_RISK`/`OTHER`；缺失/非法 400），成功写 MODIFY 变更、`SCHEDULE_UPDATE` 审计与
  `SCHEDULE_MODIFIED` 通知；前端不预填、必须由操作人选择（Q-1，方案书 §2.4.2）。冲突/版本
  语义不变（409）。
- **急诊加台** `POST /v1/scheduling/emergency-cases`（`addEmergencyCase`）：需
  `m1:schedule:approve`（V9 仅授 SYS_ADMIN/SYS_OR_MANAGER），缺失 403；请求新增
  `expectedEnd`（缺省按申请时长推算）、`sequenceNo`、`preemptScheduleId`，`roomId/expectedStart`
  MVP 必填。成功 **201** + `EmergencyAddResult.approval`（changeType=EMERGENCY、status=APPROVED、
  申请人/审批人/时间、抢占关系），急诊台次 `priority=EMERGENCY`。冲突且未指定抢占 → 409
  `Error.conflicts[]`（`conflictingScheduleId` 为可抢占候选）；指定抢占且命中冲突中的
  PLANNED/PUBLISHED 择期台次 → 原台次置 `CANCELLED`（reason=`EMERGENCY_PREEMPT`）并写 STOP
  变更/审计/通知，再落急诊台次；人员/患者冲突不可抢占（仍 409）。成功响应仅 **201**
  （不可达的 200 已移除，Q-3）。抢占目标在事务内 `SELECT ... FOR UPDATE` 锁定后复检状态/
  优先级/术间/时段，任一变化返回 409 `PREEMPT_CONFLICT`，不会误停并发改台移走的台次（B-1）。
- **变更历史** `GET /v1/scheduling/schedules/{scheduleId}/changes`（`listScheduleChanges`）：
  `m1:schedule:read` + 数据范围；返回 STOP/MODIFY/EMERGENCY 的 `ScheduleChange[]`（原因码、
  审批状态/审批人、`preemptScheduleId`、版本），按 `requestedAt` 倒序；越界/不存在 404。
  支持 `page`/`size`（缺省 1/50，上限 200）与 `meta` 分页，属 additive（S-7）。
- **通知端口（SIMULATED）**：`NotificationPort` 当前实现只写 `m1_sched.notification_outbox`
  并记录无 PHI 日志，`NotificationDispatcher` 轮询 PENDING → SENT（失败退避，3 次后 FAILED）；
  真实短信/企业微信通道为 **EXT**，接入前 payload 白名单与数据库 `ck_notification_outbox_no_phi`
  双重禁止 PHI。

### 3.4 术前访视与评估（M2-M4-01，anesthesia 0.3.0 additive）

> 0.3.0 只新增路径与 schema，未修改既有 `PreopVisit` 字段；旧
> `GET/PUT /v1/anesthesia/preop-visits/{visitId}` 保留但已标 `deprecated: true`（ADR-0011
> 弃用窗口），新代码使用 preop-assessments。示例中的资源标识统一为 UUID 字符串。
> baseline/HEAD baseline/生成物漂移三门禁全 PASS。

- **列表** `GET /v1/anesthesia/preop-assessments?applicationId=`（`listPreopAssessments`）：
  `m2:preop:read`；返回 `application`（申请上下文：患者快照/术式/科室/申请状态）与 `items`
  （访视单，创建时间倒序）。申请不存在或超出数据范围按集合语义 **403**（不返回空列表、
  不泄露存在性）；缺 `applicationId` 400。
- **创建** `POST /v1/anesthesia/preop-assessments`（`createPreopAssessment`）：`m2:preop:write`；
  **201** + `PreopAssessment`。请求只提交 `scoreInput`（RCRI 6 项/Apfel 4 项/STOP-BANG 8 项/
  DASI 12 项勾选），**不接收分数**（客户端提交 `scores` 被忽略，服务端重算）；`dasiActivities`
  重复项不做静默去重，显式 400；患者快照由服务端从申请回填（客户端姓名/年龄等被忽略），
  仅 `patientId/hisPatientNo` 必须与申请一致，否则 400；申请不存在/不可见 422；
  同一申请已有草稿 409。
- **详情** `GET /v1/anesthesia/preop-assessments/{assessmentId}`（`getPreopAssessment`）：
  `m2:preop:read`；不存在或超出数据范围 404。
- **保存草稿** `PUT /v1/anesthesia/preop-assessments/{assessmentId}`（`updatePreopAssessment`）：
  `m2:preop:write`；全量替换病史/气道/ASA/评分输入/计划并重算评分；必须回传 `version`；
  已最终化或版本过期 409。
- **最终化** `POST /v1/anesthesia/preop-assessments/{assessmentId}/finalize`
  （`finalizePreopAssessment`）：`m2:preop:write`；DRAFT→FINALIZED，记录 `finalizedAt/By`
  与可选的 `note`（落库 `finalize_note`，不入日志）；已最终化或版本过期 409；最终化后只能为
  同一申请新建版本（新记录），原单保留评分快照与审计；数据库触发器拒绝 FINALIZED 行 UPDATE
  与任何 DELETE/TRUNCATE（SQLSTATE 55000）。
- **评分结果只读**：`scores` 含 `rcriScore/rcriRisk`、`apfelScore/apfelRisk`、
  `stopBangScore/stopBangRisk`、`dasiScore`（0–58.2，保留 2 位小数）、`functionalCapacityLow`、
  `riskLevel`、`calculatedAt`、`calculatorVersion`；契约中 `scores` 标 `readOnly: true`。
  口径由 `anesop.m2.preop.*` 配置，【待确认】项见
  `docs/modules/m2/README.md` §4；客户端不得直接写分数。
- **权限与审计**：V10 新增 `m2:preop:read|write`（SYS_ADMIN/SYS_ANESTHESIOLOGIST/
  SYS_NURSE_ANESTHETIST）；写操作审计 `PREOP_CREATE/PREOP_UPDATE/PREOP_FINALIZE`，detail 不含
  姓名与病史原文。

### 3.5 知情同意与三方核查（M2-M5-01，anesthesia 0.4.0 additive）

> 0.4.0 只新增 8 条路径 / 10 个 operation 与 12 个 schema，**未修改**既有 `ThreeWayCheck`、
> `UpsertThreeWayCheckRequest`（记录单维度，实现留 M3）与 `PreopVisit` 骨架；
> baseline/HEAD baseline/生成物漂移三门禁全 PASS。示例均为 SYNTHETIC。

- **同意模板** `GET /v1/anesthesia/consent-templates`（`listConsentTemplates`）：
  `m2:consent:read`；`templateCode`/`effectiveOnly` 过滤，默认只返回 ACTIVE（`includeRetired=true`
  含停用版本）；返回 `ConsentTemplate[]`（含 `status`/`retiredAt`）。模板内容不可变：新版本以新的
  `(templateCode, version)` 另建，已签记录冻结 `templateVersion`。V11 内置 3 条 SYNTHETIC 演示模板，
  真实文本须医务处/法务审核（【待确认】Q-4）。
- **模板停用** `POST /v1/anesthesia/consent-templates/{templateId}/retire`
  （`retireConsentTemplate`）：`m2:consent:write`；ACTIVE→RETIRED 仅一次，重复停用 422；
  停用后不可用于新签署（创建 422），历史记录仍引用旧版本；审计 `CONSENT_TEMPLATE_RETIRE`。
- **同意记录列表** `GET /v1/anesthesia/consents?applicationId=`（`listConsents`）：
  `m2:consent:read`；返回 `application`（申请上下文，含患者快照）+ `items`（含已拒绝/
  已撤销历史）。申请不可见按集合语义 **403**（不返回空列表）。
- **创建** `POST /v1/anesthesia/consents`（`createConsent`）：`m2:consent:write`；**201**，
  初始 `WAITING_SIGN`、`caStatus=PENDING`。`templateVersion` 必须与 `templateId` 对应且模板
  已生效且未停用；同一申请 + 模板版本已有有效记录 409；申请不可见/模板版本不匹配/已停用 422。
- **详情** `GET /v1/anesthesia/consents/{consentId}`（`getConsent`）：越界/不存在 404。
- **签署** `POST /v1/anesthesia/consents/{consentId}/sign`（`signConsent`）：`m2:consent:write`；
  `WAITING_SIGN → SIGNED`，返回 `caStatus=SIMULATED` 的占位签名（EXT-05）：
  `signatureRef=SIM-CA-<consentId>`、`signatureHash`=SHA-256（同意元数据 + 模板正文摘要，
  不含姓名）、`signedAt/signedBy`。**不保存签名原文/图片/证书链**；签署前校验申请仍为活动状态。
  同一申请最多一条有效 SIGNED（`uk_consent_record_signed_application`），已有未撤销 SIGNED 时
  重复签署 409；重复签署、版本过期、已拒绝/已撤销、申请已取消/驳回 409。
- **拒绝** `POST …/decline`（`declineConsent`）：`WAITING_SIGN → DECLINED`，`reason` 必填
  （≤500 字，只落库不入日志/审计）；非待签署 409。
- **撤销** `POST …/revoke`（`revokeConsent`）：`SIGNED → REVOKED`，`reason` 必填；撤销后不可再签，
  签名留痕不可改写；非已签署 409。
- **申请维度三方核查** `GET/POST /v1/anesthesia/applications/{applicationId}/three-way-checks`
  （`listApplicationThreeWayChecks` / `recordApplicationThreeWayCheck`）：`m2:threeway:read|write`。
  POST 新增版本（201，`check_version = max+1`），字段 `timeOutAt/By`、`signOutAt/By`、
  `specimenChecked`、`implantChecked`、`issues[]`；Time-out 先录，Sign-out 可在出室后以新版本
  补录（不得早于 Time-out）；申请不可见 404，状态不允许/并发版本冲突 409。GET 返回版本倒序
  `items` 与 `completed`（最新版本是否同时有 Time-out 与 Sign-out），供 M3 锁单前置校验。
- **记录维度保持**：`GET/POST /v1/anesthesia/records/{recordId}/three-way-checks` 的
  `ThreeWayCheck` 定义不变（`recordId` 必填、无 `applicationId`/`issues`），实现留在 M3；
  M3 落地时以 `applicationId` 关联两维度并统一完成判定口径。
- **错误语义**：400 字段/枚举/时间/问题项校验；401 未认证；403 缺权限或集合查询越权；
  404 单对象越界/不存在；409 重复签署、终态再流转、版本过期、重复有效同意、核查并发版本冲突；
  422 创建时申请不可见或模板版本不存在。错误响应统一 `$ref` 共享组件，`details` 不回显
  签署人姓名、原因原文或问题项原文。


### 3.6 排程分析（M2-M6-01，scheduling 0.6.0 additive）

> 0.6.0 只新增 4 条路径 / 4 个 operation 与 10 个分析 schema，并在 `OperatingRoom` 上新增可选
> `deptId/deptName`（供科室筛选展示）；未修改既有端点的请求/响应结构。baseline/HEAD baseline/
> 生成物漂移三门禁全 PASS。

- **利用率** `GET /v1/scheduling/analytics/utilization?from=&to=&deptId=`
  （`getSchedulingUtilization`）：`m1:analytics:read`；`from`/`to` 必填，按
  `Asia/Shanghai` 本地日期闭区间解释，跨度默认 92 天（配置可减小，但上限夹紧为 92 天）；
  返回汇总与按术间明细。分子为状态非 `CANCELLED` 台次与区间重叠的**计划**分钟（非实际占用，
  【待确认】；跨日裁剪），分母为可见术间数 × 天数 × `room-daily-available-minutes`
  （默认 600，【待确认】；含区间内全部自然日，未排除未来/停诊日【待确认】）；空数据 0。
- **准点率** `GET /v1/scheduling/analytics/on-time?...`（`getSchedulingOnTimeRate`）：
  每术间每本地日**计划开始最早**台次的 `|实际开始 - 计划开始| ≤ 阈值`
  （默认 ±15 分钟，`on-time-threshold-minutes` 可配置）。该计划首台取消/未开始（或缺实际开始）
  时整日**不纳入评估**（分母排除），计入 `excludedDays`，不以下一台次替代；
  `firstCaseCount = evaluatedCaseCount + excludedDays`。**实际开始 MVP 以台次
  `updated_at` 近似**（V8 无独立 actual_start 列、HIS 未回写、系统内实际开台数据不足，
  【待确认】），响应 `actualStartSource=SCHEDULE_UPDATED_AT` 明示；`evaluatedCaseCount=0`
  时前端展示“暂无数据”。
- **取消率** `GET /v1/scheduling/analytics/cancellations?...`
  （`getSchedulingCancellationRate`）：`CANCELLED` 台次 / 区间内总台次（已取消也进分母），
  按最近 `STOP` 变更记录的原因码分组；无记录归 `UNSPECIFIED`；急诊抢占计入
  `EMERGENCY_PREEMPT`；分母 0 时比率为 0。
- **CSV 导出** `GET /v1/scheduling/analytics/export?metric=&from=&to=&format=csv`
  （`exportSchedulingAnalytics`）：流式 `text/csv;charset=UTF-8`，带 UTF-8 BOM；列仅含
  指标/日期/术间编号/科室/原因码/聚合计数与比率，**不含**患者姓名、住院号、证件号、
  手机号、诊断、申请号。对以 `=`、`+`、`-`、`@`、制表/回车开头的单元格前置单引号 `'`
  再按 CSV 规则转义，防 Excel/Sheets 公式注入。`metric` ∈ utilization/on-time/cancellations，
  `format` 仅 csv；跨度默认 92 天（配置上限夹紧 92），非法参数 400。
- **数据范围与权限**：DEPT 本科室术间、ROOM 绑定术间，未绑定/无可见术间/SELF 403；
  权限点 `m1:analytics:read`（V12 授 SYS_ADMIN/SYS_OR_MANAGER/SYS_QC_MANAGER，只读）。
- **审计**：`ANALYTICS_QUERY`/`ANALYTICS_EXPORT`（`resource_type=ANALYTICS`）只记
  指标/日期/时区/术间数/计数，不含患者标识。
- **口径文档**：`docs/modules/m1/README.md` §12；口径版本 `analytics-1.0.0`，
  配置项见 `application.yml` 的 `anesop.m1.analytics.*`。

### 3.7 麻醉记录单与时间轴（M3-M2-01，anesthesia 0.5.0 additive）

> 0.5.0 只新增路径/schema/可选字段，未删除或改类型既有定义；`anesthesia.yaml` 与
> `baseline/anesthesia.yaml` 同步更新，`oasdiff`（baseline → 当前、HEAD baseline → 当前）
> 均无破坏性变更。0.8.0（M3-M2-03）已实现锁单/修订，0.8.1 复审修正（见 §3.10）。

- **记录单（按台次唯一）**：`GET /v1/anesthesia/records?scheduleId=`（`listAnesthesiaRecords`，
  `m2:record:read`）集合语义：台次不可见 403，未创建时返回空 `items`；
  `POST /v1/anesthesia/records`（`createAnesthesiaRecord`，`m2:record:write`）按台次创建
  DRAFT 记录单，重复创建 409，台次不可见 422、已取消/已完成 409。
- **记录单详情/保存**：`GET/PUT /v1/anesthesia/records/{recordId}`（既有 operationId
  `getAnesthesiaRecord`/`updateAnesthesiaRecord` 本次实现）。响应新增只读 `vitalSigns`
  占位数组（本期固定空数组，M3-M8-01 采集接入后填充）；`events` 为 0.4.0 兼容字段，
  完整时间轴走 `/events`。PUT 必须回传 `version`（过期 409），且**不再接受非空 `events`**
  （400：事件为 append-only，请走 `POST /events`；行为已在契约描述声明）。
- **时间轴**：`GET /v1/anesthesia/records/{recordId}/events`（`listRecordEvents`）按
  `occurredAt` 升序、同刻按 `seq` 升序；`POST .../events`（`appendRecordEvent`）append-only，
  `eventType ∈ {ANESTHESIA_START, INDUCTION, INTUBATION, INCISION, CUSTOM, EXTUBATION,
  ANESTHESIA_END}`，`occurredAt` 必填且不得早于记录开始（400），重复开始/结束 409；
  `ANESTHESIA_START` 置记录 `IN_PROGRESS` 并写开始时间，`ANESTHESIA_END` 写结束时间。
- **用药**：`GET/POST /v1/anesthesia/records/{recordId}/drugs`
  （`listDrugAdministrations`/`recordDrugAdministration`）。麻精类 `narcotic=true` 必须
  `secondCheckerId` + `secondCheckerName`，缺失或与给药人同人 400；非麻精类携带核对字段
  400（避免误认为已核对）。
- **输液/出入量**：`GET/POST /v1/anesthesia/records/{recordId}/fluids`
  （`listFluidRecords`/`recordFluidRecord`，`direction ∈ {INTAKE, OUTPUT}`、`amountMl > 0`）；
  `GET .../io-summary`（`getIntakeOutputSummary`）服务端按明细汇总
  `balanceMl = intakeMl - outputMl`，前端不得自行累加覆盖。
- **状态映射**：契约 `AnesthesiaRecord.status` 沿用 0.4.0 枚举 `DRAFT/IN_PROGRESS/LOCKED/REVISED`；
  数据库 `m2_anes.anesthesia_record.status` 为 `DRAFT/ONGOING`，服务端把 `ONGOING → IN_PROGRESS`
  映射输出（0.5.0 未扩展契约枚举，避免响应枚举新增带来的兼容告警）。
- **错误码**：400 `PARAM_INVALID`（字段级：`occurredAt`/`secondCheckerId`/`events`/`amountMl` 等）、
  403 `AUTH_FORBIDDEN`（无 `m2:record:*` 或台次超出数据范围）、404 `RESOURCE_NOT_FOUND`
  （记录单不存在/越界）、409 `STATE_CONFLICT`（重复记录单、版本过期、重复开始/结束）、
  422 `BUSINESS_RULE_VIOLATION`（创建时台次不可见）。
- **权限**：`m2:record:read` / `m2:record:write`（V13 授予 SYS_ADMIN、
  SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST）。
- **示例**：全部 SYNTHETIC（`SYNTH-P-0001`/“演示患者”/演示药品名），不含真实患者数据。

### 3.8 设备采集接入（M3-M8-01，device 0.1.0 新增域）

> 新增 device 域（无历史包袱，0.1.0 首版）；契约与 baseline 同步，`oasdiff` 无破坏性变更。

- **设备台账**：`GET /v1/devices`（`listDevices`，`m2:device:read`）按网关/术间/推导状态过滤；
  数据范围只返回可见术间设备（未绑定术间仅 ALL 可见），roomId 过滤越权 403。
- **登记/换台**：`POST /v1/devices`（`registerDevice`，`m2:device:write`）唯一
  `(gatewayId, deviceId)`，首次 201、幂等/换台 200、并发 409；目标术间必须在调用者数据范围内
  （未绑定仅 ALL），否则 403；请求缺省 `status` 不覆盖已有 `MAINTENANCE`。
- **网关上报**：`POST /v1/devices/{gatewayId}/{deviceId}/ingest`（`ingestDeviceVitals`，
  `X-Gateway-Token` 内部令牌，无 JWT）批次幂等 + 行级幂等；共享令牌未绑定 gatewayId 的生产
  风险见 `docs/gateway/README.md` §11.2。
- **体征查询**：`GET .../vitals`（`listDeviceVitals`，`m2:device:read`）窗口/质量/分页；
  设备不存在或超出数据范围统一 404。
- **健康摘要**：`GET /v1/devices/health`（`getDeviceHealth`）窗口默认 24h、可配置 1–168；
  质量比例分子/分母同一窗口，`last_seen_at` 仅在 `acceptedRows>0` 时推进。
- **示例**：全部 SYNTHETIC（`SIM-*` 设备标识、演示术间 UUID），不含患者标识。

### 3.9 术中预警与抢救记录（M3-M2-02，anesthesia 0.7.0 additive）

> 0.6.0 交付预警规则/告警/抢救记录；0.7.0（M3-M2-02 评审修复）把告警触发改为“采集落库后
> 领域事件 + 已评估 measured_at 水位增量评估”，读取告警列表不再评估、不回评历史窗口，
> DRAFT 不评估；规则名称可更新、阈值按指标范围校验、`pendingConfirmation` 标记与历史版本
> 查询；抢救记录新增 `leader`/`rescuers`（服务端必填）。
> 两版都只新增路径/可选字段，未删除或改类型既有定义（`AnesthesiaRecord.vitalSigns` 占位语义
> 不变）；`anesthesia.yaml` 与 `baseline/anesthesia.yaml` 同步更新，`oasdiff`（baseline → 当前与
> HEAD baseline → 当前）均无破坏性变更；示例全部 SYNTHETIC，默认阈值标【待确认】。

- **规则列表** `GET /v1/anesthesia/alert-rules`（`listAlertRules`，`m2:alert:read`）：
  每个规则编码返回当前版本（`code/metric/operator/thresholdLow/thresholdHigh/durationSeconds/
  severity/version/enabled`）。
- **规则配置** `PUT /v1/anesthesia/alert-rules/{ruleId}`（`updateAlertRule`，
  `m2:alert:configure`，V15 仅 SYS_ADMIN）：保存即生成 `version+1` 新版本并停用旧版本；
  算子与阈值必须自洽（LT 需下限、GT 需上限、OUT_OF_RANGE 需上下限且 low<high，否则 400）；
  版本过期或请求的不是当前版本 409。
- **规则历史** `GET /v1/anesthesia/alert-rules/{ruleId}/versions`（`listAlertRuleVersions`，
  `m2:alert:read`）：按 code 返回全部历史版本（version 倒序），供告警 `ruleVersion` 追溯。
- **告警列表** `GET /v1/anesthesia/records/{recordId}/alerts`（`listRecordAlerts`，
  `m2:alert:read`）：**只读**，返回 `ruleCode/ruleVersion/metric/value/severity/triggeredAt/status`
  等；实时告警由 ingest 成功提交后的领域事件评估落库（只评估 ONGOING 且 startedAt 非空记录，
  按记录单快照 roomId 匹配设备），前端 5–10s 轮询兜底；记录单不可见 404。
- **手动评估** `POST /v1/anesthesia/records/{recordId}/alerts/evaluate`
  （`evaluateRecordAlerts`，`m2:record:write`）：调试/采集补偿；同一引擎、去重与水位，
  只处理新采样，返回 `evaluatedRules/sampledFrames/createdAlerts/evaluatedThrough`。
- **告警处置** `POST /v1/anesthesia/alerts/{alertId}/acknowledge`（`acknowledgeAlert`，
  `m2:alert:ack`）：`decision=CONFIRMED|IGNORED` + 必填 `reason` + 当前 `version`；只允许
  `TRIGGERED → ACKNOWLEDGED/IGNORED` 一次流转，重复处理/版本过期 409。
- **抢救记录** `GET/POST /v1/anesthesia/records/{recordId}/resuscitations`
  （`listResuscitations` 复用 `m2:record:read`；`createResuscitation` 需 `m2:rescue:write`）：
  append-only（数据库触发器拒绝 UPDATE/DELETE，SQLSTATE 55000）；时间窗必须落在记录单时间窗内
  （违反 400）；`eventSummary` 为对象、`drugs` 为数组（≤50 条）；`leader`（负责人）必填、
  `rescuers`（参与人员）至少 1 人（服务端校验，缺失 400；人员姓名不入审计原文）。
- **阈值口径**：严格不等（等于阈值不触发）；`durationSeconds` 为连续越界时长；
  `MAP = dia + (sys − dia)/3` 由无创血压推导。详见 `docs/modules/m2/README.md` §13.2。

### 3.10 锁单、修订与 CA 占位签名（M3-M2-03，anesthesia 0.8.1 additive）

> 0.8.1 只新增路径/可选字段/新 schema，未删除或改类型既有定义；`anesthesia.yaml` 与
> `baseline/anesthesia.yaml` 同步更新，`oasdiff`（baseline → 当前、HEAD baseline → 当前）均无
> 破坏性变更。0.8.1 相对未发布的 0.8.0 收缩修订原因码、明确 `confirmChecklist` 运行时必填、
> 统一 `anesthesiaType` 拼写与 409 示例；旧客户端若仍发 DOCUMENTATION_ERROR 等旧枚举会收到
> 400（0.8.0 未提交发布，无存量调用方）。CA 不可用（EXT-05）：签名为 **SIMULATED 占位**，
> 不模拟真实 CA 有效签名。

- **锁单前置清单** `GET /v1/anesthesia/records/{recordId}/lock-status`（`getRecordLockStatus`，
  `m2:record:read`）：返回 `locked/lockedAt/lockedBy/status/currentRevision/canLock/
  signatureRef/signatureHash/caStatus` 与固定顺序的 `preconditions[]`（`code/label/satisfied/
  detail`）。条件码：
  - `THREE_WAY_CHECK_COMPLETED`：`ThreeWayCheckService.isThreeWayCheckCompleted(applicationId)`；
  - `CONSENT_SIGNED`：申请存在当前有效 `SIGNED` 同意（WAITING_SIGN/DECLINED/REVOKED 不算）；
  - `RECORD_STARTED` / `RECORD_ENDED`：已记录 `ANESTHESIA_START` / `ANESTHESIA_END`；
  - `AT_LEAST_ONE_EVENT`：时间轴至少 1 个事件；
  - `NOT_LOCKED`：尚未锁单。
  只读接口；前端只展示，**不得自行推导**前置条件。
- **当前有效值** `GET /v1/anesthesia/records/{recordId}/effective`
  （`getEffectiveAnesthesiaRecord`，`m2:record:read`）：返回 `original`（锁单原始行临床字段）、
  `effective`（叠加最新修订更正后的当前有效值，当前仅 `anesthesiaType`）、`corrections[]`
  （字段/原值/有效值/来源 revisionNo/reasonCode）与锁单签名留痕。记录详情页/打印使用
  `effective`；原记录行与历史修订保持不变。未锁单/未修订时 `effective` 等于 `original`。
- **锁单** `POST /v1/anesthesia/records/{recordId}/lock`（`lockAnesthesiaRecord`，
  `m2:record:lock`，V17 授予 SYS_ADMIN/SYS_ANESTHESIOLOGIST）：body
  `{version, signatureRequested, confirmChecklist}`；`confirmChecklist` 为运行时必填，
  缺省/false 返回 400（schema 未列 required 是为保持 HEAD baseline 兼容，服务端按必填校验），
  服务端仍会重新校验前置条件（不能以确认字段替代校验）。未满足 409 `STATE_CONFLICT`，
  `details[]` 逐项列出未满足条件码；version 过期/重复锁单 409。成功返回锁单时间/操作人、
  `signatureStatus=SIGNED`、`caStatus=SIMULATED`、`signatureRef=SIM-CA-<recordId>`、
  `signatureHash`（64 位十六进制）与 `currentRevision=0`。
- **修订版本列表/详情** `GET /v1/anesthesia/records/{recordId}/revisions`
  （`listRecordRevisions`）与 `GET .../revisions/{revisionNo}`（`getRecordRevision`，
  `m2:record:read`）：摘要含 `revisionNo/reasonCode/reason/changes/signatureRef/signatureHash/
  caStatus/createdAt`；详情额外含 `snapshot`（全量快照）与 `diff`
  （`previousRevisionNo/summary/changedFields[]`，首版 `previousRevisionNo=0` 对比锁单原始记录）。
- **创建修订** `POST /v1/anesthesia/records/{recordId}/revisions`（`createRecordRevision`，
  `m2:record:revise`）：`reasonCode` 白名单（0.8.1）`ANESTHESIA_TYPE_CHANGE/OTHER`
  （`OMISSION`/`TIMING_CORRECTION` 待结构化更正能力，M3-QA 前，Q-17），`reason` 与 `changes`
  必填（≤500）；可选 `corrections.anesthesiaType`
  只写入修订快照（时间轴/用药/出入量为 append-only，不可经修订改写）。服务端在记录单行锁内
  分配 `revisionNo`（从 1 递增），返回 201；未锁单/version 过期/并发版本冲突 409。原记录与历史
  修订**不可改**（数据库触发器 55000）。
- **签名口径**（EXT-05）：`signatureHash = SHA-256(规范化快照 JSON)`。快照含记录单字段、
  时间轴、用药、出入量、抢救记录（含 leader/rescuers）与出入量汇总，数组按 (时间, id) 稳定
  排序；设备采集 `vital_sign` 明细不在快照范围（采集链路独立留痕，避免高频采样放大快照）。
  规范化规则：对象键字典序、
  null 对象字段省略、数字去尾零、时间统一 `+08:00` 毫秒 ISO-8601；排除患者标识
  （patientId/patientNo/patientName）、台次/申请标识、审计时间与签名字段本身。修订快照的
  `signatures.previousRevisionSignatureHash` 指向上一次修订摘要（首版为 null），形成版本哈希链；
  锁单摘要不含签名摘要（锁单时尚未签名）。详见 `docs/modules/m2/README.md` §14.3。
- **示例**：全部 SYNTHETIC（`SYNTH-*` / 演示患者 / 演示麻醉方式），不含真实患者数据。

### 3.11 复苏记录与转出（M3-M10-01，anesthesia 0.9.0 additive）

> 0.9.0 只新增路径与 schema（7 个 operation、11 个 schema），未删除或改类型既有定义；
> `anesthesia.yaml` 与 `baseline/anesthesia.yaml` 同步更新，`oasdiff`（baseline → 当前、
> HEAD baseline → 当前）均无破坏性变更。Aldrete 转出阈值由服务端配置
> （`anesop.m2.pacu.aldrete-threshold`，默认 9，**院方阈值【待确认】**），契约中通过
> `aldreteThreshold` 暴露给前端展示，不接受客户端写入。

- **入室** `POST /v1/anesthesia/pacu-records`（`admitPacuRecord`，`m2:pacu:write`）：
  body `{recordId, admittedAt, vitalSigns, consciousness, painScore, nausea, airway}`；
  记录单必须已开始（DRAFT/未开始 409）、不可见 422、同一记录单已有未转出记录 409、
  `admittedAt` 早于记录开始或晚于服务端当前时间+容差 400。
- **查询** `GET /v1/anesthesia/pacu-records?recordId=`（`listPacuRecords`）与
  `GET /v1/anesthesia/pacu-records/{pacuId}`（`getPacuRecord`，均 `m2:pacu:read`）：
  返回状态（`ADMITTED/MONITORING/READY/DISCHARGED`）、入室信息、最近一次评估投影
  （`latestAssessmentId/At/latestAldreteTotal`）与服务端阈值 `aldreteThreshold`；
  列表 `recordId` 缺失/非 UUID 400。
- **Aldrete 评估** `POST /v1/anesthesia/pacu-records/{pacuId}/assessments`
  （`recordPacuAssessment`，`m2:pacu:write`）：五项
  `activity/respiration/circulation/consciousness/oxygenation` 各 0–2（缺项/越界 400），
  **总分为服务端计算**（0–10）；append-only（数据库 55000），已转出后再评估 409；
  评估时间早于入室/早于上一条评估/晚于当前时间+容差 400，latest 按
  `(assessed_at, created_at, id)` 判定且状态推进以 latest 为准。
  `GET .../assessments`（`listPacuAssessments`）返回 `assessedAt` 升序时间序列。
- **转出** `POST /v1/anesthesia/pacu-records/{pacuId}/discharge`（`dischargePacuRecord`，
  `m2:pacu:write`）：body `{destination, handover:{situation,background,assessment,
  recommendation}, criteriaConfirmed}`；SBAR 四段必填、`criteriaConfirmed` 缺省/false 400。
  服务端校验最近一次评估存在、五项齐全、总分 ≥ 阈值；未满足 409 `STATE_CONFLICT`，
  `details[]` 逐项列出 `ALDRETE_ASSESSED`/`ALDRETE_ITEMS_COMPLETE`/`ALDRETE_THRESHOLD_MET`。
  成功返回 `PacuDischarge`：去向、SBAR、`criteria` 达标快照（阈值/总分/依据评估标识与时间）
  与转出时间；转出记录 append-only，一次一条，重复转出 409。
  `GET .../discharge`（`getPacuDischarge`）未转出 404。
- **示例**：全部 SYNTHETIC（演示患者/演示 SBAR/演示分项），不含真实患者数据。

### 3.12 术后随访与镇痛（M3-M11-01，anesthesia 0.10.0 additive）

> 0.10.0 只新增路径与 schema（5 个 operation、11 个 schema：`PostopFollowup`/
> `PostopFollowupList`/`CreatePostopFollowupRequest`/`FollowupType`/`FollowupComplication`/
> `PainManagement`/`PainManagementList`/`RecordPainManagementRequest`/`PainAdjustment`/
> `PainManagementModality`/`PainManagementEffect`），未删除或改类型既有定义；
> `anesthesia.yaml` 与 `baseline/anesthesia.yaml` 同步更新，`oasdiff`（baseline → 当前、
> HEAD baseline → 当前）均无破坏性变更。**注意**：契约中旧占位 `/v1/anesthesia/follow-ups`
> （带连字符、`FollowUp`/`FollowUpPage`）保留未实现并已标 `deprecated: true`，本期实现的是
> `/v1/anesthesia/followups`（`PostopFollowup`）；两者不得混用，旧占位下线已登记为独立任务
> （见 `docs/modules/m2/README.md` §16.9 Q-6）。随访窗口、PONV 分级、并发症字典、PCA 默认值
> 与剂量单位均为**演示口径【待确认】**，系统不替代临床判断（H-06）。

- **录入随访** `POST /v1/anesthesia/followups`（`createPostopFollowup`，`m2:followup:write`）：
  body `{anesthesiaRecordId, followupType:H24|H48, assessedAt, nrsScore:0–10,
  ponvScore:0–3, complications[], complicationNotes?, patientFeedback?, followedUpBy}`。
  记录单必须**已结束**（`endedAt` 非空，否则 409 `STATE_CONFLICT`）；记录单不存在或不可见
  统一 422（不泄露存在性）；`assessedAt` 早于记录结束/晚于当前时间+容差/早于同记录同类型
  上一条 400；评分越界、并发症不在白名单、文本 >500、随访人缺失/超长 400。随访
  append-only（数据库 55000），同记录同类型可多次随访；说明与反馈由服务端脱敏后落库：
  手机号容忍 `+86`/空格/短横线/括号（11 位），身份证号容忍空格/短横线（18 位含末位 X、
  15 位旧版），日期/剂量等普通数字不受影响。
- **查询** `GET /v1/anesthesia/followups?recordId=`（`listPostopFollowups`）与
  `GET /v1/anesthesia/followups/{followupId}`（`getPostopFollowup`，均
  `m2:followup:read`）：返回类型、时间、NRS/PONV、并发症、脱敏说明/反馈、随访人；
  列表 `assessedAt` 倒序，`recordId` 缺失/非 UUID 400，记录单不可见 403，单对象越界 404。
- **镇痛记录** `POST /v1/anesthesia/followups/{followupId}/pain-management`
  （`recordPainManagement`，`m2:followup:write`）：body `{modality, drug, bolusDose>0,
  backgroundDose≥0, lockoutMinutes>0, effect, adjustments[]}`；方式
  `PCIA/PCEA/ORAL/IV/NERVE_BLOCK/OTHER`、效果 `EFFECTIVE/PARTIAL/INEFFECTIVE/
  NOT_ASSESSED`（演示口径）；参数越界/药物缺失或超长/调整记录 >20 条或说明非法 400；
  随访不存在/越界 404。append-only，参数调整只能新增记录，不得改写历史。药物名称与
  调整说明属自由文本，落库与审计前经 `PhiMasker` 脱敏（评审 S-1）。
  `GET .../pain-management`（`listPainManagement`）按录入时间升序返回。
- **示例**：全部 SYNTHETIC（演示随访护士/演示并发症/演示镇痛方案），不含真实患者标识。

### 3.13 药品目录与麻精五专（M4-M15-01，drug 0.1.0 新增域）

> 0.1.0 是**新增域**（10 条路径 / 15 个 operation / 21 个 schema；复审追加
> `/v1/narcotic/departments` 与 `DepartmentOption/DepartmentList`），不改动既有五份契约；
> `drug.yaml` 与 `baseline/drug.yaml` 同步落地，`oasdiff`（baseline → 当前、HEAD baseline →
> 当前）均无破坏性变更（HEAD 无该域快照时自动 SKIP）。**T-4 收口**：麻醉记录用药的
> `narcotic` 改为服务端按本域字典判定（见 §3.7 补充说明与 ADR-0018）。

- **药品目录**（`m4:drug:read` 读 / `m4:drug:write` 写）：`GET/POST /v1/drugs`、
  `GET/PUT /v1/drugs/{drugId}`、`GET /v1/drugs/search?q=`。编码/名称/通用名按
  trim + 忽略大小写唯一，重复 409；别名全局唯一且与其他药品名称/通用名双向互斥，冲突 409；
  编码创建后不可改；
  更新必须回传 `version`（过期 409）；不存在 404。目录全院可读，维护仅 SYS_ADMIN/
  SYS_PHARMACIST（V20）。`narcotic`/`highRisk` 是主数据布尔字段，`narcotic` 是麻醉记录
  麻精判定的唯一权威。
- **麻精五专**（全部需 `m4:narcotic:manage`，并按科室数据范围过滤）：
  - `POST/GET /v1/narcotic/registers`：专册登记（药品/批号/数量/处方号/使用人/去向 +
    双人签字）。药品未收录 422；双人同一人 400（`details[0].field=secondSignerId`）；
    科室越界 403；append-only（数据库 55000）。
  - `POST/GET /v1/narcotic/cabinet-handovers`：专柜交接（班次/交班/接班/品种数量明细 +
    双人签字）。交班 = 接班 400（`handoverToId`）、签字同一人 400（`secondSignerId`）。
  - `GET/PUT /v1/narcotic/baselines`：基数（品种 + 柜唯一，数量 ≥ 0；更新回传 `version`，
    过期 409）。
  - `POST /v1/narcotic/daily-closes`：日结/班结。`期初 + 入库 − 使用 − 退回 = 期末`；
    不平 409，`details[0]` 给出期望值/实盘值；同柜 + 业务日 + 班次重复 409；append-only。
  - `GET /v1/narcotic/daily-closes/{closeId}`：详情；不存在或越界统一 404。
- **药房适配器** `GET /v1/drugs/pharmacy-adapter/status`（`m4:drug:read`）：EXT-02
  `mode=SIMULATED`，每次调用执行无外部 I/O 的模拟探测并留痕；失败时
  `available=false, degraded=true`（本地字典继续可用），审计 `ADAPTER_SYNC`。
- **数据范围**：药品目录不按数据范围过滤；五专按科室（ALL 全院 / SITE/DEPT 授权组织 /
  SELF/ROOM fail-closed 403）。最小实现与待确认项见 `docs/modules/m4/README.md` 与 ADR-0018。
- **示例**：全部 SYNTHETIC（演示药品/批号/处方号/使用人/签字人），不含真实患者标识。

## 4. 错误模型

统一返回 `Error`；所有 4xx/5xx 响应必须 `$ref` 到 `components.responses` 共享组件，禁止内联定义。

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | string | 稳定错误码，前端分支与告警使用，如 `AUTH_UNAUTHORIZED`、`SCHEDULE_CONFLICT` |
| `message` | string | 可安全展示的中文提示 |
| `traceId` | string | 与日志/审计关联的追踪标识 |
| `details` | array | `{field, reason}` 明细；无明细为空数组 |

| 状态码 | 语义 | 声明条件 |
|---|---|---|
| 400 | 入参校验失败（`BadRequest`） | 带 `requestBody` 的 operation 必备；`details` 为字段级错误数组 |
| 401 | 未认证/令牌失效（`Unauthorized`） | 所有 operation 必备 |
| 403 | 无权限/超出数据范围（`Forbidden`） | 需认证的 operation 必备；`security: []` 的登录/刷新与 `logout` 除外 |
| 404 | 资源不存在或不可见（`NotFound`） | 仅路径含 `{资源参数}` 的单资源 operation；集合查询、创建接口不声明 |
| 409 | 状态/版本/排程/锁单前置/转出前置/唯一键冲突（`Conflict`） | 仅状态变更操作（带 `requestBody`）声明；`logout`、登录/刷新不声明。用户管理：创建账号重复、更新工号撞 `uk_sys_user_employee_no`；锁单前置未满足时 `details[]` 逐项列出条件码（`THREE_WAY_CHECK_COMPLETED` 等）；PACU 转出未达标时 `details[]` 列出 `ALDRETE_THRESHOLD_MET` 等条件码 |
| 422 | 语义不合法（`UnprocessableEntity`） | 字段格式正确但业务规则拒绝（如患者不在主索引、术间不满足隔离要求） |
| 201 | 创建成功 | 创建类接口；响应体返回创建后的实体（`POST three-way-checks` 首次创建为 201，更新为 200） |
| 204 | 成功无响应体 | `POST /v1/auth/logout` |

越权语义固定为：**集合查询越权 → 403；单对象越界/不存在 → 404**（不泄露存在性，记审计并告警）。契约中 403 的描述统一为“无权限或超出数据范围”，404 的描述统一为“不存在或不可见”。

`scripts/contract-check.sh` 的 spectral 规则会校验：错误响应是否 `$ref` 到共享组件、状态码与组件名是否匹配、401 是否必备、需认证接口是否声明 403、带请求体是否声明 400、含路径参数是否声明 404，以及 404/409 是否越界声明。错误信息不得包含堆栈、SQL 实参、内部路径与患者标识；示例统一使用 `SYNTH-*` 合成值。

## 5. 分页约定

- 查询参数 `page`（从 1 开始，默认 1）与 `size`（默认 20 或 50，最大 200）。
- 响应统一为 `{ items: [...], meta: PageMeta }`；`PageMeta = { page, size, total, totalPages }`。
- 非分页列表（如术间列表）返回 `{ items: [...] }`，不返回 `meta`。
- 排序默认按业务时间倒序；如需显式排序，后续以新增查询参数方式扩展，不改变既有字段。

## 6. 示例与合成数据

- 所有示例均为 **SYNTHETIC** 合成数据：患者编号固定 `SYNTH-P-0001`，姓名固定“演示患者”，住院号 `SYNTH-ZY-*`，HIS 申请号 `SYNTH-HIS-APP-*`。
- 禁止在契约、示例、截图、测试夹具中出现真实患者姓名、身份证、住院号、手机号、人脸或可重识别的组合。
- 需要新增示例时，沿用 `SYNTH-*` 前缀并在 PR 中声明“示例为合成数据”。

### 6.1 脱敏字段

脱敏三问（是否含直接标识、能否组合重识别、是否必要）任一不过即只给字段名或改用合成数据（§3.9.3）。证件号不得保留出生日期段。

| 字段 | 返回范围 | 规则 |
|---|---|---|
| `name` | 列表/详情 | 患者姓名；仅授权角色可见，示例统一“演示患者” |
| `gender` | 列表/详情 | MALE/FEMALE/UNKNOWN |
| `birthDate` | 仅详情 | 与年龄/科室组合可能重识别，按最小必要返回 |
| `age` | 列表/详情 | 由出生日期计算，不单独构成直接标识 |
| `inpatientNoMasked` | 列表/详情 | 前 4 + `****` + 后 4，如 `SYNT****0001`；住院号明文与哈希均不出接口 |
| `idCard*` / `phone*` / `inpatientNo` | 兼容占位（空字符串） | M2 起不返回真实值；字段仅为“只增不删”保留并标 `deprecated`，计划 1.0.0 删除 |

## 7. 兼容性检查（含 HEAD baseline）

- `scripts/contract-check.sh` 第 2 步：工作区 baseline → 当前契约；
- 第 3 步（ADR-0011）：用 **HEAD 中的 baseline 快照** → 当前契约复跑 oasdiff，防止在同一个
  未评审变更里覆盖 baseline "自证兼容"；git 不可用或首次落地时跳过并提示。
- 第 4 步：生成物漂移校验（重新生成 TS 后 `git diff` 必须为空）。

 版本与破坏性变更流程

- **契约兼容性：0.3.0 为 additive（仅新增端点/字段；删除类变更推迟到 1.0.0 并走 major+ADR）。**
  已删除的安全字段以 `deprecated: true` 兼容占位恢复，实现不返回真实值；历史端点同样只标
  `deprecated` 不物理删除。任何删除/改类型必须先完成 1.0.0 major 评审与 ADR，禁止在 0.x 内私删。
- `info.version` 采用语义化版本；当前 `auth 0.5.0`、`patient 0.3.0`、`scheduling 0.6.0`、
  `anesthesia 0.7.0`、`device 0.1.0`（0.7.0/M3-M2-02 评审修复新增规则历史 1 条只读路径、
  `pendingConfirmation`/`evaluatedThrough`/`leader`/`rescuers` 等可选字段与
  `UpdateAlertRuleRequest.name` 可选字段，属 additive；0.6.0/M2-M6-01 的排程分析 4 条只读
  路径见 §3.6）。路径主版本为 `/v1`。
- **兼容变更**（只增不删）：新增可选字段、新增接口、新增响应字段、新增枚举值（调用方需容忍未知值）；更新 `minor`/`patch`，评审通过后同步 baseline。
- **阻断性变更**（oasdiff `--fail-on WARN` 即失败）：删除路径或 operation；请求体新增必填字段；请求字段类型变更；响应必填字段删除；响应字段类型变更。删除/收紧枚举值、改变字段语义同样按破坏性变更处理。
- **兼容例外（M2 契约冻结前）**：响应新增必填字段按 warning 处理、不阻断，但必须在 PR 说明调用方兼容方案，并在下表登记；M2 契约冻结前清零。

| 已登记例外 | oasdiff 行为 | 处理与截止 |
|---|---|---|
| 响应新增必填字段 | warning | 不阻断；PR 说明调用方兼容方案，M2 契约冻结前清零 |
| 改台 `reasonCode` 由可选改必填（0.5.0 未发布） | error（仅 HEAD baseline） | ADR-0012；提交评审通过的 0.5.0 baseline 后 HEAD 门禁清零，不绕过 |
| 移除急诊不可达 200（0.5.0 未发布） | error（仅 HEAD baseline） | ADR-0012；提交评审通过的 0.5.0 baseline 后 HEAD 门禁清零，不绕过 |

- **基线**：`openapi/baseline/<域>.yaml` 是上一次人工评审通过的兼容快照。只有契约评审通过且确认兼容后，才可人工复制当前契约覆盖 baseline，并在**同一 PR** 说明版本、评审结论与差异；禁止为了过检而修改 baseline，禁止脚本自动覆盖。
- **拦截**：`scripts/contract-check.sh` 以 spectral（0 error/0 warning）+ oasdiff breaking（`--fail-on WARN`）+ 生成物漂移（重跑 `gen:ts` 并对比前后 `git diff`，干净树上等价于 `git diff --exit-code`）三道门禁；任一失败即非 0 退出。
- 破坏性变更 PR 必须附兼容说明、影响面、回滚方案与双人评审；不满足即阻断合并（§3.5.1、AGENTS「契约优先与数据变更」）。

## 8. 校验与生成命令

```bash
source scripts/env.sh

# 1) 契约 lint（--fail-severity=warn，必须 0 error / 0 warning）
pnpm -C packages/contracts lint

# 2) 契约 → TS 类型（生成物带 DO NOT EDIT 头）
bash scripts/gen-contracts.sh
pnpm -C packages/contracts gen:ts          # 等价入口

# 3) 生成物可编译
pnpm exec tsc --noEmit --strict packages/contracts/generated/typescript/*.d.ts

# 4) 契约门禁：lint + 破坏性变更 + 生成物漂移
bash scripts/contract-check.sh
pnpm -C packages/contracts check:breaking   # 仅 breaking
```

`scripts/check.sh` 第 6 步调用 `contract-check.sh`；漂移检查在 `--breaking-only`/`--lint-only` 模式下跳过，全量模式执行。

## 9. 工具与依赖

| 工具 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| `@stoplight/spectral-cli` | 6.16.0 | Apache-2.0 | OpenAPI 3.1 lint |
| `openapi-typescript` | 7.13.0 | MIT | 契约 → TS 类型 |
| `@oasdiff-js/oasdiff-js` | 1.0.0 | Apache-2.0 | Node 包装的 oasdiff 1.15.0，破坏性变更检测 |
| Node.js | ≥20.17（本机 26.7.0） | — | 工具运行环境 |

上述三项依赖均已引入、版本在 `pnpm-lock.yaml` 锁定，并在 `docs/deps/allowlist.md` 按“已引入”登记；`openapi-diff 0.24.1` 因不支持 OpenAPI 3.1 未采用。allowlist 由 S0-03 维护，本节只记录实际使用状态，不重复维护白名单。

## 10. 待办与【待确认】

- **角色/权限映射待办**：角色 R1–R12 与权限码（如 `anesthesia.record.lock`）的对应关系由 S0-07 定稿并回写契约与权限矩阵；本契约不自造映射表。
- CA 签名与时间戳的厂商、接口与验签方式（§3.9.4，标【待确认】）；当前契约只保留 `signatureStatus`，不写外部 CA 接口。
- DASI 取值范围按院方口径配置：0.3.0 起服务端按通行 DASI 0–58.2 计算（12 项权重、保留 2 位小数），
  功能容量 <4 METs 的换算与院方切点【待确认】；契约示例与校验上限为 58.2，配置表后续落地。
- 会话与刷新令牌的最终时效、是否强制双因素，需安全评审确认（当前示例 1800 秒 / 30 分钟）。
- 排程冲突已落地时间重叠/术者/患者/术间类型/时间窗五类规则；设备依赖、隔离与人员资质的
  字段与字典编码在 M2-M2-02/M3 资质模块评审后补充（枚举值已预留）。
- 生命体征高频序列与波形查询接口不在本基线，按 §3.4.4 的分区/归档方案在 M2 细化。
