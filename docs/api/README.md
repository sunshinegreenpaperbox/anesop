# API 契约（OpenAPI 3.1）

> 状态：S0-05 契约基线 + M2-M0-01 数据范围 + M2-M0-02 用户管理 + M2-M1-01 手术申请 + M2-M2-01 排程工作台 + M2-M2-02 停改台/急诊加台 + M2-M4-01 术前访视与评估 + M2-M5-01 知情同意与三方核查 + M2-M6-01 排程分析（`auth 0.5.0`、`patient 0.3.0`、`scheduling 0.6.0`、`anesthesia 0.4.0`）。契约是接口的唯一事实源，前端 `apps/web/src/api/generated/` 与后端 DTO 都必须由契约派生，禁止手改生成物。
>
> 事实来源：方案书 §3.4（领域模型）、§3.5（接口与集成）、§3.9.2（RBAC 与数据权限）、§2.4.1（M0）、§2.4.2（M1）、§2.4.3（M2）。

## 1. 目录与契约清单

```text
packages/contracts/
├── openapi/
│   ├── auth.yaml          # 认证、令牌、当前用户、会话
│   ├── patient.yaml       # 患者主索引、手术申请
│   ├── scheduling.yaml    # 术间、排程、冲突、急诊加台、停台、排程分析
│   ├── anesthesia.yaml    # 术前访视与评估、记录单、锁单、三方核查、随访
│   └── baseline/          # 当前已发布兼容基线（4 份快照）
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
| `anesthesia.yaml` | 17 | 25 | 术前访视与评估（0.3.0）、知情同意模板/停用/记录/签署/拒绝/撤销（0.4.0）、申请维度三方核查（0.4.0）、旧访视骨架读写、记录单读写、锁单、记录维度三方核查读写、随访 | §2.4.3、§3.4.2、§3.9.4 |
| **合计** | **51** | **64** | 4 个限界上下文骨架 | — |

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
| 409 | 状态/版本/排程/锁单/唯一键冲突（`Conflict`） | 仅状态变更操作（带 `requestBody`）声明；`logout`、登录/刷新不声明。用户管理：创建账号重复、更新工号撞 `uk_sys_user_employee_no` |
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
  `anesthesia 0.4.0`（0.6.0/M2-M6-01 仅新增排程分析 4 条只读路径、10 个 schema 与
  `OperatingRoom.deptId/deptName` 可选字段，属 additive；0.5.0 的停改台/急诊与改台 reasonCode
  必填见 §3.3/ADR-0012）。路径主版本为 `/v1`。
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
