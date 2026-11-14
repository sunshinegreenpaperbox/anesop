# M2 麻醉信息管理（模块说明）

> 状态：M2-M4-01「术前访视与评估」+ M2-M5-01「知情同意与三方核查」+ M3-M2-01
> 「麻醉记录单与时间轴」+ M3-M2-02「术中预警规则与抢救记录」+ M3-M2-03
> 「锁单/修订/CA 签名（模拟）」+ M3-M10-01「复苏记录与转出（Aldrete、SBAR 交接）」+
> M3-M11-01「术后随访与镇痛（24/48h、PCA、并发症）」落地版（2026-11-14）。
> 契约：`packages/contracts/openapi/anesthesia.yaml` 0.10.0；
> 迁移：`db/migration/V10__m2_preop_assessment.sql`、
> `db/migration/V11__m2_consent_threeway.sql`、
> `db/migration/V13__m2_anesthesia_record.sql`、
> `db/migration/V15__m2_alert_rescue.sql`、
> `db/migration/V16__m2_alert_realtime_watermark_rescue_leader.sql`、
> `db/migration/V17__m2_record_lock_revision.sql`、
> `db/migration/V18__m2_pacu_recovery.sql`、
> `db/migration/V19__m2_followup_pain.sql`；
> 方案书：§2.4.3（M2 术前访视/知情同意/三方核查/麻醉计划）、§3.4.2（麻醉记录单与访视实体）、
> §3.4.3（审计字段/乐观锁/逻辑删除）、§3.9.2（RBAC/数据权限）、§3.9.3（脱敏）、
> §3.9.4（CA 签名；EXT-05 CA 不可用，本期为占位/自签哈希）。
> 全部示例、患者、申请与评分为 **SYNTHETIC** 合成数据，禁止写入真实患者数据。

## 1. 职责与边界

M2 当前交付「术前访视与评估」与「知情同意与三方核查」：把 M1 排程/申请下游的术前
临床记录与安全核查落成结构化留痕。

- 术前访视：采集病史要点、气道评估（Mallampati/张口度/颈椎活动度）、ASA 分级；采集
  RCRI（6 项）、Apfel（4 项）、STOP-BANG（8 项）、DASI（12 项）量表条目；**服务端计算**
  四项分数、各量表风险分级与综合风险等级，结果只读并随记录冻结；记录麻醉计划；
  草稿 → 最终化状态机：最终化后不可修改，只能为同一申请新建版本，原单保留复算快照与审计。
- 知情同意：模板与版本不可变（新版本另建）、按“申请 + 模板版本”创建待签署记录、
  签署（CA 占位 SIMULATED）/拒绝/撤销状态机；签署记录冻结模板版本与签名留痕，
  拒绝/撤销必须填写原因，已签署/已撤销不可改写。
- 三方核查：手术申请维度的 Time-out / Sign-out，append-only 版本化（修正必须新增版本），
  最新版本同时含 Time-out 与 Sign-out 视为完成；`ThreeWayCheckService
  .isThreeWayCheckCompleted(applicationId)` 是 M3 锁单前置校验的集成点。

- 麻醉记录单（M3-M2-01）：按台次唯一记录单；append-only 时间轴事件（麻醉开始/诱导/插管/
  切皮/自定义关键事件/拔管/麻醉结束，seq 服务端分配）；用药（麻精类双人核对）；输液与
  出入量明细及服务端汇总（入量/出量/平衡）；体征占位由 M3-M8-01 采集接入后填充。

非目标（后续任务）：生命体征真实接入与质量标记（M3-M8-01，§12）、术中预警与抢救记录
（M3-M2-02）、三方核查**记录单(record)维度**实现（既有 `ThreeWayCheck` 契约保持不动，
实现留 M3）、独立评分阈值配置表
（当前以 `anesop.m2.preop.*` 配置属性实现，院方口径与配置表结构【待确认】）、
真实 CA 有效签名（EXT-05，见 §11）、患者端电子签名外设。

模块边界：`m2-impl` 只依赖 `m2-api`、`m0-api`（认证主体/审计/数据范围）与 `m1-api`
（申请可见性）；**不访问 m1 表或 Mapper**，不跨模块传实体。`api-bootstrap` 是唯一依赖
`m2-impl` 的装配点。前端 `apps/web/src/modules/m2` 不引用其他 `modules/mX` 内部文件。

## 2. 代码结构

```text
apps/api/modules/m2/
├── api/  com.anesop.m2.api
│   ├── preop/      PreopAssessmentService、PreopValidationException（400）、
│   │               PreopConflictException（409）、PreopUnprocessableException（422）
│   ├── consent/    ConsentService（模板/创建/签署/拒绝/撤销）
│   ├── threeway/   ThreeWayCheckService（录入/查询/isThreeWayCheckCompleted）
│   ├── followup/   FollowupService（术后随访/镇痛，append-only）
│   ├── error/      M2ValidationException（400）、M2ConflictException（409）、
│   │               M2UnprocessableException（422），供同意/核查/随访复用
│   └── security/   M2Permissions（m2:preop|consent|threeway|pacu|followup:read|write）
└── impl/ com.anesop.m2.impl
    ├── preop/      PreopAssessmentServiceImpl（事务/状态机/可见性/审计）
    │               PreopScoreCalculator（RCRI/Apfel/STOP-BANG/DASI，临床规则高风险）
    ├── consent/    ConsentServiceImpl（CA 占位签名、状态机、模板版本冻结、审计）
    ├── threeway/   ThreeWayCheckServiceImpl（append-only 版本、完成判定、时间校验）
    ├── followup/   FollowupServiceImpl（术后前置/评分边界/时间单调/镇痛参数/脱敏/审计）
    ├── support/    AnesthesiaApplicationSummaries（复用 m1-api 申请快照，不读 m1 表）
    │               PhiMasker（随访自由文本脱敏：手机号/身份证号形似串）
    └── persistence/  PreopAssessmentEntity/Mapper、ConsentTemplateEntity/Mapper、
                      ConsentRecordEntity/Mapper、ThreeWayCheckEntity/Mapper
```

前端：

```text
apps/web/src/modules/m2/
├── api/m2.ts                     anesthesia 0.4.0 生成类型与 13 个接口
├── utils/preopScores.ts          评分实时预览（与服务端同口径，最终以服务端为准）
├── views/PreopAssessmentView.vue 病史/气道/ASA/评分/计划/最终化
├── views/PreopEntryView.vue      菜单入口（从申请进入的说明）
├── views/ConsentView.vue         模板选择/创建/签署/拒绝/撤销 + CA 占位标识
├── views/ConsentEntryView.vue    知情同意菜单入口
├── views/ThreeWayCheckView.vue   Time-out/Sign-out/标本/植入物/问题项 + 版本历史
├── views/ThreeWayCheckEntryView.vue 三方核查菜单入口
├── views/PacuView.vue           PACU 入室/Aldrete 评分表与时间序列/转出（SBAR）
├── views/FollowupView.vue       术后随访（24/48h、NRS/PONV/并发症/反馈）与镇痛（PCA）
└── __tests__/                    Vitest（术前访视 4 + 同意 5 + 核查 7 + PACU 4 + 随访 6）
```

## 3. 接口（anesthesia 0.8.1）

> 0.5.0 为 additive：新增记录单创建/按台次查询、时间轴事件读写、用药读写、输液/出入量
> 读写与出入量汇总共 9 个 operation、新增 13 个 schema，并在 `AnesthesiaRecord` 增加只读
> `vitalSigns` 占位数组；未删除或改类型既有定义。0.4.0 的知情同意 8 个 operation 与
> 手术申请维度三方核查 2 个 operation 保持不变；记录单(record)维度
> `/v1/anesthesia/records/{recordId}/three-way-checks` 保持原契约，实现留 M3。
> 0.8.1（M3-M2-03）新增锁单状态/锁单/修订列表/修订详情/创建修订/当前有效值 6 个 operation 与
> `RecordLockStatus`/`LockPrecondition`/`RecordRevision`/`RevisionDiff` 等 schema，
> 并在 `AnesthesiaRecord` 增加只读锁单字段（`lockedAt/lockedBy/currentRevision/
> signatureRef/signatureHash/caStatus`）；`LockRecordRequest` 新增可选 `confirmChecklist`，
> `LockRecordResult` 新增 `signatureRef/signatureHash/caStatus/currentRevision`，均为 additive。

| 方法 | 路径 | operationId | 权限 | 说明 |
|---|---|---|---|---|
| GET | `/v1/anesthesia/preop-assessments?applicationId=` | `listPreopAssessments` | `m2:preop:read` | 申请上下文 + 访视单列表（创建时间倒序）；申请不可见 403（集合语义） |
| POST | `/v1/anesthesia/preop-assessments` | `createPreopAssessment` | `m2:preop:write` | 创建草稿（201）；申请不可见 422、重复草稿 409 |
| GET | `/v1/anesthesia/preop-assessments/{assessmentId}` | `getPreopAssessment` | `m2:preop:read` | 详情；越界/不存在 404 |
| PUT | `/v1/anesthesia/preop-assessments/{assessmentId}` | `updatePreopAssessment` | `m2:preop:write` | 全量保存草稿 + 重算评分；版本过期/已最终化 409 |
| POST | `/v1/anesthesia/preop-assessments/{assessmentId}/finalize` | `finalizePreopAssessment` | `m2:preop:write` | 最终化（DRAFT→FINALIZED）；已最终化/版本过期 409 |
| GET | `/v1/anesthesia/consent-templates` | `listConsentTemplates` | `m2:consent:read` | 模板与版本（倒序）；默认只返回 ACTIVE，`includeRetired=true` 含停用版本；内容不可变 |
| POST | `/v1/anesthesia/consent-templates/{templateId}/retire` | `retireConsentTemplate` | `m2:consent:write` | 停用模板版本（ACTIVE→RETIRED 一次）；重复停用 422；审计 `CONSENT_TEMPLATE_RETIRE` |
| GET | `/v1/anesthesia/consents?applicationId=` | `listConsents` | `m2:consent:read` | 申请上下文 + 同意记录（含历史）；越权 403（集合语义） |
| POST | `/v1/anesthesia/consents` | `createConsent` | `m2:consent:write` | 创建待签署记录（201）；重复有效记录 409、申请不可见/模板不匹配/模板已停用 422 |
| GET | `/v1/anesthesia/consents/{consentId}` | `getConsent` | `m2:consent:read` | 详情；越界/不存在 404 |
| POST | `/v1/anesthesia/consents/{consentId}/sign` | `signConsent` | `m2:consent:write` | 签署（CA 占位 SIMULATED）；重复签署/已有未撤销 SIGNED/申请非活动/版本过期 409 |
| POST | `/v1/anesthesia/consents/{consentId}/decline` | `declineConsent` | `m2:consent:write` | 拒绝（必须填原因）；非待签署 409 |
| POST | `/v1/anesthesia/consents/{consentId}/revoke` | `revokeConsent` | `m2:consent:write` | 撤销已签署（必须填原因）；非已签署 409 |
| GET | `/v1/anesthesia/applications/{applicationId}/three-way-checks` | `listApplicationThreeWayChecks` | `m2:threeway:read` | 核查版本（倒序）+ completed；申请不可见 404 |
| POST | `/v1/anesthesia/applications/{applicationId}/three-way-checks` | `recordApplicationThreeWayCheck` | `m2:threeway:write` | 新增核查版本（201，append-only）；可省略 timeOutAt 继承上一版本；申请不可见 404、状态不允许/Time-out 变更未确认/并发版本冲突 409 |
| GET | `/v1/anesthesia/records?scheduleId=` | `listAnesthesiaRecords` | `m2:record:read` | 按台次查询记录单（0/1 条）；台次不可见 403（集合语义） |
| POST | `/v1/anesthesia/records` | `createAnesthesiaRecord` | `m2:record:write` | 按台次创建记录单（201，DRAFT）；重复创建 409、台次不可见 422、已取消/已完成 409 |
| GET | `/v1/anesthesia/records/{recordId}` | `getAnesthesiaRecord` | `m2:record:read` | 记录单详情（含兼容 events、体征占位、三方核查摘要）；越界 404 |
| PUT | `/v1/anesthesia/records/{recordId}` | `updateAnesthesiaRecord` | `m2:record:write` | 未锁定可改（乐观锁 version，过期 409）；非空 events 400（append-only） |
| GET | `/v1/anesthesia/records/{recordId}/events` | `listRecordEvents` | `m2:record:read` | 时间轴（occurredAt 升序，同刻按 seq）；越界 404 |
| POST | `/v1/anesthesia/records/{recordId}/events` | `appendRecordEvent` | `m2:record:write` | 追加事件（201）；早于记录开始 400、重复开始/结束 409 |
| GET | `/v1/anesthesia/records/{recordId}/drugs` | `listDrugAdministrations` | `m2:record:read` | 用药记录（给药时间倒序） |
| POST | `/v1/anesthesia/records/{recordId}/drugs` | `recordDrugAdministration` | `m2:record:write` | 用药记录（201）；字典未收录/声明与字典不一致 400、字典歧义 422；麻精缺双人核对/同人核对/非麻精带核对字段 400 |
| GET | `/v1/anesthesia/records/{recordId}/fluids` | `listFluidRecords` | `m2:record:read` | 输液/出入量明细（记录时间倒序） |
| POST | `/v1/anesthesia/records/{recordId}/fluids` | `recordFluidRecord` | `m2:record:write` | 入量/出量明细（201）；amountMl ≤ 0 400 |
| GET | `/v1/anesthesia/records/{recordId}/io-summary` | `getIntakeOutputSummary` | `m2:record:read` | 出入量汇总（服务端计算 balanceMl = 入量 - 出量） |
| GET | `/v1/anesthesia/records/{recordId}/lock-status` | `getRecordLockStatus` | `m2:record:read` | 锁单状态与六项前置清单（服务端评估）；越界 404 |
| POST | `/v1/anesthesia/records/{recordId}/lock` | `lockAnesthesiaRecord` | `m2:record:lock` | 锁单（version + confirmChecklist）；前置未满足 409 逐项列出、重复/过期 409、未确认 400 |
| GET | `/v1/anesthesia/records/{recordId}/revisions` | `listRecordRevisions` | `m2:record:read` | 修订版本列表（revisionNo 升序） |
| GET | `/v1/anesthesia/records/{recordId}/revisions/{revisionNo}` | `getRecordRevision` | `m2:record:read` | 修订详情（快照 + 签名摘要 + 与上一版差异）；版本不存在 404 |
| POST | `/v1/anesthesia/records/{recordId}/revisions` | `createRecordRevision` | `m2:record:revise` | 创建修订版本（201）；未锁单/版本过期 409、原因码非法/原因缺失 400 |

旧 `GET/PUT /v1/anesthesia/preop-visits/{visitId}`（0.2.0 骨架）保留不动、不删除既有字段
（ADR-0011 弃用窗口）；新代码使用 preop-assessments。契约 0.3.0 为 additive：新增路径与
schema，未修改既有字段，`contract-check.sh` 的 baseline/HEAD baseline/漂移三门禁全 PASS。

错误语义：400 字段/枚举/区间/缺项（评分条目为 null 直接拒绝，不按 0 分兜底）；401 未认证；
403 缺权限或数据范围为空（写 `ACCESS_DENIED` 审计）；404 单对象不存在/越界（不泄露存在性）；
409 已最终化、版本过期、同申请重复草稿、申请已取消/驳回；422 创建时申请不存在/不可见
（请求体参数，无路径参数）。

数据范围复用 M1 申请可见性（`SurgeryApplicationService.get`）：DEPT/SITE/ROOM/SELF 语义由
M1 解析，M2 不重复实现，也不直接读 `m1_sched` 表。

## 4. 评分口径（临床规则，需人工逐行）

> 评分实现位于 `PreopScoreCalculator`，每个量表方法 Javadoc 引用公开文献并标注切点。
> 未获院方确认的阈值一律标【待确认】；系统结果不得替代医师判断（AGENTS H-06）。

### 4.1 RCRI（修订版心脏风险指数）

- 6 个预测因子，各 1 分，总分 0–6：高危手术（腹腔内/胸腔内/腹股沟以上血管）、缺血性心脏病、
  充血性心力衰竭、脑血管病、胰岛素依赖型糖尿病、肾功能不全（肌酐 > 2.0 mg/dL 或 177 µmol/L）。
- 风险分级：0=低危、1=中危、≥2=高危（公开研究 MACE 约 3.9%/6.0%/≥10%）。**院方切点【待确认】**。
- 来源：Lee TH, et al. Circulation. 1999;100(10):1043-1049.

### 4.2 Apfel（PONV 简化风险评分）

- 4 个预测因子，各 1 分，总分 0–4：女性、既往 PONV/晕动症史、不吸烟、术后使用阿片类药物。
- 风险分级：0–1=低危、2=中危、≥3=高危（对应 10%/21%/39%/61%/79%）。**院方切点【待确认】**。
- 来源：Apfel CC, et al. Anesthesiology. 1999;91(3):693-700.

### 4.3 STOP-BANG（OSA 筛查）

- 8 个条目，各 1 分，总分 0–8：打鼾、白天嗜睡/疲乏、目击呼吸暂停、高血压、BMI > 35、
  年龄 > 50、颈围 > 40 cm、男性。
- 风险分级：0–2=低危、3–4=中危、5–8=高危。**院方切点【待确认】**。
- 来源：Chung F, et al. Anesthesiology. 2008;108(5):812-821；Chung F, et al. Chest.
  2016;149(3):631-638.

### 4.4 DASI（杜克活动状态指数）

- 12 个活动项，按 Hlatky 1989 权重求和，通行范围 **0–58.2**（保留 2 位小数）：

| 活动项 | 权重 | 活动项 | 权重 |
|---|---:|---|---:|
| 自理（进食/穿衣/洗澡/如厕） | 2.75 | 轻家务（除尘/洗碗） | 2.70 |
| 室内行走 | 1.75 | 中等家务（吸尘/扫地/提杂货） | 3.50 |
| 平地步行 1–2 街区 | 2.75 | 重家务（擦地/搬重物） | 8.00 |
| 上一层楼/爬坡 | 5.50 | 庭院劳动（耙叶/除草/推割草机） | 4.50 |
| 短距离跑 | 8.00 | 性生活 | 5.25 |
| 中等娱乐（高尔夫/保龄球/双打网球） | 6.00 | 剧烈运动（游泳/单打网球/滑雪） | 7.50 |

- 功能容量偏低：按 `VO2peak(mL/kg/min) = 0.43 × DASI + 9.6` 估算 < 14（≈4 METs）。
  **该换算与 4 METs 切点用于术前风险评估的适用性【待确认】**；该布尔值不参与综合风险合成。
- 来源：Hlatky MA, et al. Am J Cardiol. 1989;64(10):651-654.

### 4.5 ASA 与 Mallampati

- ASA：I–VI，服务端校验枚举；ASA 是独立分级，不折算分数、不参与综合风险合成。
- Mallampati：改良 I–IV（Samsoon & Young 1987），服务端校验；张口度（0–10 cm）与颈椎活动度
  为描述性字段，仅记录不评分，困难气道综合判断【待确认】。

### 4.6 最高单项风险（原“综合风险”，展示约定，【待确认】）

- 输出 LOW/MODERATE/HIGH，取 RCRI/Apfel/STOP-BANG 三者分级的**最高级**（保守取高）；
  因此 PONV 风险（Apfel）与 OSA 风险（STOP-BANG）**同样计入**，不只是心脏风险。
- 为避免误解为“复合模型/加权模型”，该字段在文档与评审中改称“**最高单项风险**”
  （契约字段名仍为 `riskLevel`，Q-2）。
- 该合成规则未经临床验证，仅为界面提示；不得替代医师综合判断，也不作为麻醉禁忌/排程阻断条件。
- 评分结果随记录保存 `calculator_version`（默认 `1.0.0`）与 `scores` JSON 快照，便于口径升级后复算；
  版本来自配置 `anesop.m2.preop.calculator-version`。

### 4.7 评分口径配置（S-1）

切点与量表版本不再硬编码，统一由 `anesop.m2.preop.*` 配置（`application.yml` 默认值 = 上表当前口径）：

| 配置键 | 默认值 | 含义 |
|---|---|---|
| `calculator-version` | `1.0.0` | 量表实现版本，写入 `scores.calculatorVersion` |
| `composite-risk-strategy` | `MAX_COMPONENT` | 最高单项风险合成策略（当前仅此一种） |
| `rcri-moderate-min` / `rcri-high-min` | `1` / `2` | RCRI 中危/高危起始分 |
| `apfel-moderate-min` / `apfel-high-min` | `2` / `3` | Apfel 中危/高危起始分 |
| `stopbang-moderate-min` / `stopbang-high-min` | `3` / `5` | STOP-BANG 中危/高危起始分 |
| `vo2-slope` / `vo2-intercept` | `0.43` / `9.6` | DASI → VO2peak 回归式 |
| `four-mets-vo2-threshold` | `14` | 功能容量偏低阈值（≈4 METs） |

配置非法（切点越界、`moderate ≥ high`、未知策略、非正回归系数）时启动即失败（fail-closed），
不会静默产出评分。改口径必须同步升级 `calculator-version` 并保留旧快照。

### 4.8 待临床确认清单

1. RCRI/Apfel/STOP-BANG 的院方风险切点（当前用公开量表切点）。
2. DASI 院方口径与 0–58.2 范围；功能容量 <4 METs 的换算与切点。
3. 综合风险“取最高级”的合成规则是否被院方接受。
4. 张口度、颈椎活动度是否纳入困难气道评分及切点。
5. 评分阈值配置表结构（当前以 `anesop.m2.preop.*` 属性配置；独立配置表与变更审批后续补齐）。
6. ASA/Mallampati 是否需要在最终化前强制双人核对（当前无）。

## 5. 状态机与并发

```text
(创建) ─→ DRAFT ──finalize──→ FINALIZED（只读）
            │                     │
            └── update（重算）      └── 只能为同一申请新建版本（新记录，保留原单）
```

- 同一申请同时最多一份 `DRAFT`（部分唯一索引 `uk_preop_assessment_application_draft`）；
  最终化后索引释放，允许新建版本；并发重复创建由数据库兜底 409。
- 更新/最终化使用 `version` 条件更新（`WHERE id=? AND version=? AND status='DRAFT'`），
  返回 0 行即 409（版本过期/已最终化/并发修改），不静默覆盖。
- 申请状态必须为 `SUBMITTED/ACCEPTED/SCHEDULED`；取消/驳回后继续访视 409。
- 第二道防线（V10 触发器）：`FINALIZED` 行任何 UPDATE 被拒绝（SQLSTATE 55000），
  `DELETE/TRUNCATE` 任何状态一律拒绝；草稿 UPDATE 与最终化 UPDATE 仍允许。

### 5.1 知情同意状态机（M2-M5-01）

```text
(创建) ─→ WAITING_SIGN ──sign──→ SIGNED ──revoke(reason)──→ REVOKED
                │
                └──decline(reason)──→ DECLINED

DRAFT：预留（纸质同意书回填草稿，本期未开放 API），仅存在于库枚举与契约枚举。
```

- **当前有效同意**：同一申请同时最多一条 `status='SIGNED'` 的记录（部分唯一索引
  `uk_consent_record_signed_application` 强制）；`DECLINED`/`REVOKED` 不占位。下游 M3 取该申请
  `signed_at` 最新的 `SIGNED` 记录（即“最新有效 SIGNED”），不得取 `WAITING_SIGN`/`DECLINED`/`REVOKED`。
- 同一申请 + 同一模板版本同时最多一条有效记录（`WAITING_SIGN`/`SIGNED`，部分唯一索引
  `uk_consent_record_active`）；拒绝/撤销后索引释放，允许重新知情同意（新记录，原记录留痕）。
  已有未撤销 SIGNED 时再签署其他记录返回 409（须先撤销原记录）。
- 签署前校验申请仍为 `SUBMITTED/ACCEPTED/SCHEDULED`；取消/驳回后签署 409。
- 状态变更使用 `WHERE id=? AND version=? AND status=?` 条件更新，返回 0 行即 409
  （重复签署、版本过期、并发修改）；`SIGNED` 后签名留痕不可改写，`DECLINED`/`REVOKED` 终态。
- 第二道防线（V11 触发器 `fn_consent_record_guard`）：申请/患者/模板列不可变；
  **`signer_name`/`signer_relation`/`sign_method` 在所有状态（WAITING_SIGN/SIGNED/DECLINED/REVOKED）
  不可变**；终态拒绝 UPDATE；`SIGNED` 只允许携带原因/操作人/时间的一次撤销；
  `DELETE/TRUNCATE` 一律拒绝（55000）。
- 模板内容不可变（`consent_template` 触发器）：新增版本必须插入新的 `(template_code, version)`；
  仅允许一次 `ACTIVE→RETIRED`（停用），停用后不可用于新签署（创建 422），历史记录仍引用旧版本；
  签署记录冻结 `template_version`，模板升级/停用不影响已签记录。

### 5.2 三方核查 append-only 与完成判定（M2-M5-01）

```text
POST（Time-out）      → check_version=1  time_out_at=…, sign_out_at=NULL
POST（补录 Sign-out） → check_version=2  time_out_at=…, sign_out_at=…
GET                   → items（版本倒序）+ completed（最新版本是否有 sign_out_at）
```

- 每次录入新增一行 `check_version = max + 1`；并发冲突由唯一约束
  `uk_three_way_check_application_version` 兜底（服务层 409，提示重试）。
- append-only（V11 触发器）：任何 UPDATE/DELETE/TRUNCATE 拒绝（SQLSTATE 55000）；
  修正必须新增版本，历史版本永久保留。
- 跨版本 Time-out 时序：新增版本省略 `timeOutAt` 时继承上一版本；首版必须显式填写（否则 400）；
  显式值与上一版本不一致时必须 `confirmTimeOutChange=true`，否则 409（防止无痕改写已完成核查）。
  UI 预填上一版本时间，修改需二次确认。
- 完成判定：最新版本同时有 `time_out_at` 与 `sign_out_at`；`sign_out_at` 不得早于
  `time_out_at`，且不得晚于当前时间 5 分钟以上（容忍客户端时钟偏差）。
- **M3 锁单集成点**：M3 锁单时必须调用 `ThreeWayCheckService.isThreeWayCheckCompleted(
  applicationId)`（同步调用 m2-api，不直接读 m2 表）；未完成或标识非法返回 `false`
  （fail-safe，宁可阻止锁单）。

### 5.3 麻醉记录单状态机与乐观锁（M3-M2-01）

```text
(创建，按台次唯一) ─→ DRAFT ──ANESTHESIA_START──→ ONGOING ──ANESTHESIA_END──→ ONGOING(endedAt)
                          │                            │
                          └─ PUT（麻醉方式）            └─ PUT / 事件追加（version+1）
契约状态映射：DRAFT → DRAFT，ONGOING → IN_PROGRESS
锁单（V17/0.8.1）：ONGOING ──锁单──→ LOCKED ──首次修订──→ REVISED（此后保持 REVISED）
锁单后内容只读，current_revision 每次修订 +1；DRAFT 不可锁单（需先记录开始/结束）
```

- 一个台次（`schedule_id`）只允许一张记录单：服务层先查重（409）+ 唯一索引
  `uk_anesthesia_record_schedule` 兜底（并发 → `DuplicateKeyException` → 409）。
- 创建时校验台次可见且状态为 `PLANNED/PUBLISHED/DELAYED/IN_PROGRESS`；已取消/已完成 409；
  台次不可见 422（标识在请求体，不回显存在性）。申请/患者由台次解析，不信任客户端。
- 更新（PUT）使用 `WHERE id=? AND version=?` 条件更新，返回 0 行即 409；每次追加事件也递增
  `version`，保证「事件 → 记录单」的时间轴版本一致；不静默覆盖。
- V13 触发器 `fn_anesthesia_record_guard`（单向守卫）：`id/schedule_id/application_id/patient_id/created_*`
  不可变、`status` 不得回退（DRAFT→ONGOING 单向）、`started_at` 写入后不得清空/改写、
  `version` 只增；已逻辑删除行不可改；`DELETE/TRUNCATE` 一律拒绝（均 55000）。守卫依赖数据库
  触发器，生产运行账号不得拥有 DDL 权限（否则可 `ALTER TABLE ... DISABLE TRIGGER` 绕过），
  迁移与运行账号分离见 `docs/db/README.md` §5.3。
- 锁单后「不可修改」由 V17 触发器与服务层双保险强制（§14.4）；锁单前记录单可改，但时间轴/
  用药/出入量明细已 append-only 留痕。

### 5.4 时间轴、append-only 与麻精双人核对（M3-M2-01）

- **时间轴**：事件类型 `ANESTHESIA_START/INDUCTION/INTUBATION/INCISION/CUSTOM/EXTUBATION/
  ANESTHESIA_END`；`occurredAt` 必填且不得早于记录开始（已开始取 `started_at`，未开始取
  `created_at`，否则 400），不得晚于服务端当前时间 + 容差（默认 600 秒，
  `anesop.m2.record.time-tolerance-seconds`，否则 400），不得晚于 `ANESTHESIA_END`（409）；
  DRAFT 记录只允许 `ANESTHESIA_START`/`CUSTOM`，术中事件必须先记录开始事件（409）；
  补录（早于最新事件或早于 now − 容差）必须填写 `entryReason`（否则 400，字段
  `entryReason`）；`seq` 由服务端在 `SELECT ... FOR UPDATE` 锁定的记录单行内取
  `MAX(seq)+1` 并插入，同一记录单的并发追加被串行化，唯一约束
  `uk_record_event_record_seq` 仅作最后兜底（命中映射 409，不做重试）；列表按
  `occurred_at ASC, seq ASC` 稳定排序。
- **append-only**：`record_event`、`drug_administration`、`fluid_record` 的 UPDATE/DELETE/
  TRUNCATE 均由触发器拒绝（SQLSTATE 55000）；修正只能新增事件并在 `payload.entryReason`
  说明。`AnesthesiaRecord.events`（0.4.0 兼容字段）只映射经典类型，CUSTOM→OTHER，
  `ANESTHESIA_START/END` 只在 `/events` 完整时间轴中出现。
- **麻精双人核对**：`narcotic=true` 必须同时提供 `secondCheckerId` 与 `secondCheckerName`，
  缺失 400；核对人不得与给药人（当前登录用户）相同，同人核对 400；非麻精类携带核对字段
  同样 400（避免被误认为已核对）。V13 CHECK 约束为第二道防线
  （`ck_drug_administration_narcotic_checker`、`ck_drug_administration_second_checker_distinct`）。
- **入口与路由**：前端菜单「麻醉记录」→ `/m2/records`（说明页）→ `/m2/records/:scheduleId`
  （记录单页）。排程/申请页内的跳转按钮需修改 `modules/m1` 视图，属 M1 写域，未在本任务改动；
  记录单页可直接以台次标识访问。
- **出入量**：明细 `direction IN (INTAKE, OUTPUT)`、`amount_ml > 0`；汇总 SQL 按方向 FILTER
  聚合，`balanceMl = intakeMl - outputMl`、`fluidCount` 为明细条数；前端只展示服务端结果，
  不自行累加覆盖。
- **锁单集成（M3-M2-03 已落地）**：锁单时校验（1）三方核查完成
  （`ThreeWayCheckService.isThreeWayCheckCompleted`）、（2）当前有效 SIGNED 同意、
  （3）记录开始/结束时间完整且至少 1 个事件、（4）版本一致；锁单后事件/用药/出入量追加
  统一返回 409，数据库 BEFORE INSERT 触发器（V17）拒绝绕过服务层的直写（§14）。

## 6. 数据、审计与脱敏

- 表 `m2_anes.preop_assessment`：患者快照、气道明细、评分输入、评分结果均为 jsonb 结构化保存
  （`patient_snapshot`/`airway_details`/`score_input`/`scores`），分数另落列便于查询；
  审计字段/`version`/`deleted` 与 V1–V9 一致；中文注释齐全。
- 患者快照只含院内患者号、姓名、性别、出生日期/年龄、科室/病区/床号；**不含证件号、手机号、
  住院号明文**（与 V7 患者快照口径一致）。
- 患者快照由服务端回填：created 时姓名/性别/年龄/住院号脱敏值/科室病区床号一律取 M1 申请快照，
  客户端提交值不落库；仅 `patientId`/`hisPatientNo` 用于一致性校验，不一致 400。
- 最终化说明 `FinalizePreopAssessmentRequest.note` 落库 `finalize_note`（≤500 字符），
  只落库、不写入日志与审计 detail。
- 审计动作 `PREOP_CREATE`/`PREOP_UPDATE`/`PREOP_FINALIZE`（resource_type=`PREOP_ASSESSMENT`），
  与业务同事务；detail 只含标识、状态、版本、四项分数/风险/量表版本，不含姓名、病史原文、
  证件号/住院号/手机号。`ACCESS_DENIED` 由统一异常处理器写。
- 知情同意表 `m2_anes.consent_template`（模板与版本，内容 append-only；`status` ACTIVE/RETIRED
  与 `retired_at` 仅允许停用一次）与
  `m2_anes.consent_record`（状态机、签名占位/摘要、拒绝/撤销原因）。`signature_ref` 为
  `SIM-CA-<uuid>` 占位标识，`signature_hash` 为 SHA-256（同意元数据 + 模板正文摘要，
  输入不含姓名等 PHI）；**不保存签名原文/图片/证书链**（契约中无相应字段）。
- 三方核查表 `m2_anes.three_way_check`：申请维度、append-only、`issues jsonb` 字符串数组
  （最多 20 条，每条 ≤200 字）；审计与接口只暴露数量，不写问题项原文。
- 审计动作 `CONSENT_CREATE`/`CONSENT_SIGN`/`CONSENT_DECLINE`/`CONSENT_REVOKE`
  （resource_type=`CONSENT_RECORD`）、`CONSENT_TEMPLATE_RETIRE`（resource_type=`CONSENT_TEMPLATE`）
  与 `THREEWAY_RECORD`（resource_type=`THREE_WAY_CHECK`），成功与业务同事务；detail 只含标识、
  模板版本、状态、CA 状态、签名占位标识、核对结果与问题项数量，
  **不含签署人姓名、拒绝/撤销原因原文、签名摘要、问题项原文**。
- 失败路径（S-4）：sign/decline/revoke 与三方核查录入的 409/422（及三方核查 400）以
  `result=0` 写同动作审计；使用 `REQUIRES_NEW` 独立事务，避免业务回滚带走审计；detail 只有
  动作、标识与错误类别（`STATE_CONFLICT`/`BUSINESS_RULE_VIOLATION`/`PARAM_INVALID`），无 PHI。
- 麻醉记录表 `m2_anes.anesthesia_record`（按台次唯一、DRAFT/ONGOING、乐观锁、逻辑删除）与
  `record_event`（append-only、`seq`、`occurred_at`、`payload jsonb`）、
  `drug_administration`（append-only、麻精双人核对 CHECK）、`fluid_record`（append-only、
  `amount_ml > 0`）。记录单只存 `patient_id` 标识，不存姓名/证件号/手机号/住院号明文。
- 审计动作 `RECORD_CREATE`/`RECORD_UPDATE`/`EVENT_RECORD`/`DRUG_ADMIN`/`FLUID_RECORD`
  （resource_type=`ANESTHESIA_RECORD`）与业务同事务；`RECORD_UPDATE` 的 detail 记录
  `before`/`after` 摘要（状态、麻醉方式、开始/结束时间、版本）；detail 只含记录/台次/申请标识、
  状态、版本、事件类型与 seq、是否补录（`backfilled`，不写原因原文）、药品名/剂量/单位/途径、
  核对人标识、出入量方向与数值；**不含患者姓名/院内患者号/核对人姓名与备注/补录原因原文**
  （药品名保留用于追溯）。用药审计键为 `administrationId`（给药行）+ `dictionaryDrugId`
  （字典药品），两者分列，避免同键覆盖（M4 复审 S-1）。
- 失败路径（S-3）：麻精双人核对缺失、同人、核对人不存在/停用/无 `m2:record:write` 的 400
  以 `result=0` 写 `DRUG_ADMIN` 审计（`ClinicalFailureAuditor` 独立事务，业务回滚不影响留痕），
  detail 只有记录标识、`narcotic=true` 与失败原因码
  （`CHECKER_REQUIRED`/`CHECKER_SAME_PERSON`/`CHECKER_NOT_FOUND`/`CHECKER_DISABLED`/
  `CHECKER_NO_PERMISSION`），无核对人姓名。字典侧失败同样写 `result=0`：
  `DRUG_NOT_IN_DICTIONARY`/`NARCOTIC_MISMATCH`（400）与 `DRUG_AMBIGUOUS`（422，
  detail 只记命中数与原因码，不记候选药品名）。
- 日志不打印病史/姓名/签名；测试与示例均为 SYNTHETIC。

## 7. 权限

| 权限点 | 授予角色 | 用途 |
|---|---|---|
| `m2:preop:read` | SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST | 列表/详情 |
| `m2:preop:write` | 同上 | 创建/更新/最终化 |
| `m2:consent:read` | 同上 | 模板列表、同意记录列表/详情 |
| `m2:consent:write` | 同上 | 创建/签署/拒绝/撤销 |
| `m2:threeway:read` | 同上 | 三方核查查询与完成标记 |
| `m2:threeway:write` | 同上 | 三方核查录入（新增版本） |
| `m2:record:read` | 同上 | 记录单列表/详情、时间轴/用药/出入量列表、出入量汇总 |
| `m2:record:write` | 同上 | 创建记录单、保存记录单、追加事件、用药、输液/出入量、手动评估告警 |
| `m2:alert:read` | SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST | 预警规则列表、记录单告警列表 |
| `m2:alert:ack` | SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST | 告警确认/忽略（临床处置） |
| `m2:alert:configure` | **仅 SYS_ADMIN** | 预警规则配置（代录临床顾问确认后的阈值） |
| `m2:rescue:write` | SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST | 抢救记录书写（append-only） |

V10/V11/V13/V15 只新增权限点与授权，不改写 V2 既有角色/权限数据。`m2:alert:configure`
**仅授 SYS_ADMIN**（“信息科代录 + 临床顾问确认”的配置入口）；SYS_ADMIN 同时持有
ack/rescue 是 M0“禁止越权授权”校验的必然结果（管理员只能授予自身已具备的权限，
否则无法创建麻醉医师/护士账号，详见 V15 注释）。如需科主任直接维护规则，须新迁移显式授权
并同步本文档。

## 8. 测试

- `PreopScoreCalculatorTest`（45 用例）：每量表正常/边界/缺项 + 枚举校验；DASI 12 项权重逐项
  表驱动与总和 58.2；配置覆盖生效（`Binder` 绑定 `anesop.m2.preop.*` 后切点/版本改变）、非法配置
  拒绝启动、重复 DASI 项 400。
- `PreopAssessmentServiceImplTest`（28 用例）：创建/列表/详情/更新/最终化、申请不可见 403/422、
  跨对象 404、患者不一致 400、患者快照服务端回填（客户端改名/年龄被忽略）、最终化说明落库、
  重复草稿 409、版本过期/已最终化/条件更新 0 行 409、审计动作与 detail 无 PHI。
- `PreopAssessmentApiIntegrationTest`（7 用例，嵌入式 PG16）：主流程（评分/锁定/新建版本/审计/
  最终化说明）、非法输入 400/422/409、无权限 403 与跨科室 404、V10 结构/权限/注释/触发器断言、
  服务端回填与客户端 scores 忽略、重复 dasiActivities 400、FINALIZED 行 UPDATE/DELETE/TRUNCATE
  被 55000 拒绝。
- `ConsentServiceImplTest`（32 用例）：模板列表/默认排除 RETIRED/停用与重复停用、创建/重复/
  模板版本不匹配/未生效/已停用、签署（SIMULATED、摘要 64 位十六进制、审计无姓名）、
  重复签署/已有未撤销 SIGNED/申请非活动/已拒绝/版本过期/条件更新 0 行 409、
  `uk_consent_record_signed_application` 并发冲突映射、拒绝/撤销原因与终态、
  409/422 失败审计 detail 白名单、数据范围 403/404。
- `ThreeWayCheckServiceImplTest`（20 用例）：空列表/版本映射与完成判定、首版默认执行人、
  Sign-out 补录、跨版本 Time-out 继承/首版必填/变更未确认 409/确认后放行、
  未来时间/时序/执行人/问题项数量与长度校验、申请不可见 404/状态不允许 409/失败审计、
  并发版本冲突 409、`isThreeWayCheckCompleted` 各分支。
- `ConsentThreeWayApiIntegrationTest`（10 用例，嵌入式 PG16）：模板不可变（UPDATE/DELETE=55000、
  FK 保护 TRUNCATE）、模板停用（默认列表排除/`includeRetired`/新签署 422/历史引用/重复停用 422）、
  签署/撤销全流程与数据库留痕（无签名原文列）、签署人字段在
  WAITING_SIGN/SIGNED/DECLINED/REVOKED 全状态不可变（55000）、重复签署 409、
  同一申请最多一条有效 SIGNED（撤销后重新签署）、取消申请不可签署、失败路径 result=0 审计、
  拒绝后不可再签/可重新知情同意、三方核查 append-only 与 completed→M3 集成点、
  跨版本 Time-out 继承/变更确认、权限 403/跨科室 404、V11 结构/权限/注释/触发器断言、
  审计 detail 无 PHI。
- `AnesthesiaRecordServiceImplTest`（31 用例）：唯一记录单（先查/并发唯一索引 → 409）、
  台次不可见 422/已取消 409、列表 403/非法标识 400、锁行后 seq 分配（冲突只映射 409、不重试）、
  未来时间/早于记录开始 400、DRAFT 术中事件先于开始 409、结束之后追加 409、
  补录缺 `entryReason` 400（早于最新事件/早于 now−容差两条路径）并带原因成功、
  重复开始/结束 409、版本条件更新 0 行 409、PUT 非空 events 400、
  麻精缺双人核对/同人核对/核对人不存在/停用/无书写权限 400（失败审计 result=0）、
  非麻精带核对字段 400、出入量 amountMl 校验、IO 汇总手算（1500/400/1100）、
  RECORD_UPDATE before/after 摘要、审计 detail 无 PHI 且保留药品名。
- `AnesthesiaRecordApiIntegrationTest`（4 用例，嵌入式 PG16）：创建/重复 409 → 事件顺序与
  append-only（UPDATE/DELETE=55000）→ 乐观锁 409 → 未来时间/补录 400 → 补录带原因成功 →
  麻精双人核对 400（缺核对/同人/无权限/停用/不存在，result=0 审计）→ 出入量 1500/400/1100
  → 结束之后追加 409 → 记录单身份列不可改、守卫单向（状态回退/清空开始时间/version 不增
  均 55000）→ 审计动作齐全且无 PHI；双线程并发追加（CyclicBarrier）断言 seq 连续 1–2、
  两事件均保存、version=3；权限 403 与跨科室 403/422/404；V13 结构/唯一索引/CHECK/触发器/
  权限/注释断言。
- `RecordRevisionSnapshotBuilderTest`（5 用例）：规范化 JSON 键排序/null 省略/数字归一化 →
  “同内容同摘要、不同内容不同摘要”；快照排除患者标识与审计时间；差异叶子路径与无差异摘要；
  解析后快照与内存快照摘要一致（10.000 vs 10.0 不误报）。
- `RecordLockServiceImplTest`（10 用例）：前置清单六项与 fail-safe（同意不可读）、未满足 409
  条件码 + result=0 审计、确认清单 400、重复/过期锁单 409、锁单写锁列与 SIMULATED 签名 +
  RECORD_LOCK/RECORD_SIGN 审计、条件更新 0 行 409、未锁单修订 409、修订快照/差异/签名落库、
  版本不存在 404。
- `RecordLockRevisionApiIntegrationTest`（5 用例，嵌入式 PG16）：前置清单与 409 明细/确认 400/
  锁单成功与重复 409/审计无 PHI；锁后事件/用药/出入量/保存 409 + 数据库直写 55000；修订
  原因码 400、全量快照（事件/用药/出入量/汇总/锁单摘要）、差异、版本链摘要、append-only 55000、
  原记录内容不变；双线程并发修订 revisionNo=1/2 且 current_revision=2；越权 403/跨科室 404、
  护士默认无 lock/revise 授权、V17 结构/权限/触发器/注释断言。
- `AldreteCalculatorTest`（4 用例）+ `PacuServiceImplTest`（29 用例）+
  `PacuRecoveryApiIntegrationTest`（5 用例，嵌入式 PG16：入室→评估→转出、评估时间单调 400、
  阈值 409、append-only 55000、权限/跨科室、V18 结构/审计）——详见 §15.8。
- `FollowupServiceImplTest`（20 用例）+ `PhiMaskerTest`（6 用例）+ `FollowupPainApiIntegrationTest`
  （4 用例，嵌入式 PG16：记录单未结束 409、NRS/PONV 边界与越界 400、同类型时间单调 400、
  镇痛参数 400、多格式脱敏落库、append-only 55000 与 TRUNCATE CASCADE、patient_id 守卫、
  权限/跨科室、V19 结构/审计）——详见 §16.8。
- 前端 Vitest：术前访视 4 用例 + 知情同意 5 用例（加载/创建/签署/撤销/403）+
  三方核查 7 用例（空态/Time-out/Sign-out 完成/必选逐项确认/二次确认取消/Time-out 预填/403）+
  麻醉记录单 6 用例（空态创建/时间轴与汇总/追加事件/麻精双人核对拦截/出入量/只读态）+
  PACU 4 用例（空态入室/评分表实时总分与提交/转出 SBAR 与达标确认/阈值不足 409 展示）；
  E2E：签署 + CA 占位、核对逐项确认 + 二次确认 + 核查完成、记录单创建→事件→出入量汇总、
  麻精双人核对拦截与提交、E2E-03 锁单→编辑被拒→带原因修订→新版本可见。
- 实测覆盖率见验收报告（`./scripts/mvn.sh -B -f apps/api/pom.xml verify` 的 JaCoCo 汇总），
  m2-impl 保持在核心域 ≥80/70 门禁以上（新增 S-1…S-7 用例后重新实测）；
  根 pom 的 `coverage-core-m2` profile 已按 `src/main/java/com/anesop/m2/impl/package-info.java`
  存在自动激活，未修改根 pom。

## 9. 已知限制与后续

1. **数据范围沿用 M1**：V2 种子 `SYS_ANESTHESIOLOGIST` 为 SELF 范围，只能看到本人创建的申请；
   HIS/手术室创建的申请需 DEPT/ALL 或 M3 的排班/任务分派范围（M3 承接，【待确认】）。
   这是当前“从申请进入”在真实账号下的主要限制，不影响数据安全（不会放大可见范围）。
2. 评分阈值已集中到 `anesop.m2.preop.*` 配置（§4.7），但尚无独立配置表与变更审批流（§4.8-5）；
   评分口径变更必须升级 `calculator-version` 并保留旧快照。
3. 前端 `utils/preopScores.ts` 是服务端公式的镜像，仅用于实时预览；改公式必须同步两端与本文档。
4. 最终化不等于知情同意/三方核查；知情同意与申请维度三方核查已由 M2-M5-01 落地，
   记录单(record)维度核查与锁单联动在 M3（集成点见 §11）。
5. 旧 `preop-visits` 骨架未删除（ADR-0011 弃用窗口），计划 1.0.0 收敛。
6. CA 真实签名不可用（EXT-05）：签署为占位/自签哈希（SIMULATED），不能作为法律意义上的
   电子签名；生产若要求合规 CA，须接入院内 CA 并改造签名与验签流程（§11）。
7. 知情同意模板为 SYNTHETIC 演示文本，真实院方文本尚未经医务处/法务审核，不得直接用于临床。
8. 锁单/修订已落地（§14），但 CA 仍为 SIMULATED 占位：无 PDF 生成、可信时间戳、证书链与
   验签链路；锁单后仅禁止事件/用药/出入量追加，抢救记录/告警处置是否也应冻结待临床确认
   （§14.9 Q-3）。
9. 生命体征为占位：`AnesthesiaRecord.vitalSigns` 本期固定空数组，真实采集/质量标记由
   M3-M8-01 接入（§12）；设备数据未接入前不得在记录单展示伪造体征。
10. 用药只记录药品字典名/剂量/途径与麻精核对，不含配伍、输注泵速率、批号/效期与库存联动；
   真实麻精药品「五专」管理已由 M4-M15-01 落地（专册/交接/基数/日结），处方与空安瓿回收
   仍由药事系统/线下流程承接，【待确认】。M2 用药的麻精判定已收口 T-4：服务端按药品字典
   判定（别名/通用名/大小写/首尾空白归一化），解析歧义 422 `DRUG_AMBIGUOUS`；用药页接
   `/v1/drugs/search` 字典联想，麻精标志只读、来自字典。

## 10. Q 登记（待临床 / M3 确认，只登记不改实现）

| 编号 | 事项 | 当前实现 | 待确认点 / 决策人 |
|---|---|---|---|
| Q-1 | 切点与 Javadoc 引用 | RCRI `0/1/≥2`、Apfel `0–1/2/≥3`、STOP-BANG `0–2/3–4/≥5`，默认值即公开量表口径；Javadoc/契约均标【待确认】 | Lee 1999 原文报告的是 RCRI 分层 MACE 风险，常被二次文献引作 **3.9%/6.0%/≥10%**；需临床确认院方是否采用该切点与该组数字，以及 Javadoc 引用格式（临床/医务处） |
| Q-2 | “综合风险”改称“最高单项风险” | `riskLevel` 取 RCRI/Apfel/STOP-BANG 最高级；PONV（Apfel）与 OSA（STOP-BANG）**亦计入** | 是否接受该展示约定；**DASI < 34**（Wijeysundera 2020）作为功能容量切点替代当前 VO2 < 14（≈4 METs）待确认（麻醉科/临床） |
| Q-3 | 数据范围与最终化权限 | `SYS_ANESTHESIOLOGIST` 为 SELF 数据范围，只能看到本人创建申请；`m2:preop:write` 允许麻醉护士直接 finalize | SELF 限制由 **M3** 排班/任务分派范围承接；护士 finalize 是否需医师复核（双人/二次确认）待 **M3** 落实（医务处/护理部） |
| Q-4 | 同意书模板文本与版本生效 | V11 内置 3 条 SYNTHETIC 演示模板（ANES_GENERAL v1/v2、ANES_SEDATION v1）；模板内容不可变、新版本另建 | 正式文本、生效日期、版本审批流由**医务处/法务**提供；模板管理界面与审批流后续任务 |
| Q-5 | 签署人与关系校验 | `signerRelation` 枚举 SELF/SPOUSE/PARENT/CHILD/LEGAL_GUARDIAN/OTHER；`signerName` 仅落库 | 无民事行为能力患者必须法定代理人签署的强制校验、代理关系证明留存方式待**医务处**确认 |
| Q-6 | 撤销后重新知情同意 | 撤销释放唯一索引，允许为同一申请 + 模板版本新建记录；原记录保留 | 是否需要“再次知情同意”独立模板/二次告知留痕，待**医务处**确认 |
| Q-7 | 三方核查完成与问题项 | 最新版本同时有 Time-out/Sign-out 即完成；`issues` 非空不阻断完成 | 问题项未闭环是否阻断锁单、Sign-out 是否必须与 Time-out 同一执行人，待**麻醉科/医务处**确认（M3 锁单前定稿） |
| Q-8 | 麻精药品判定口径 | `narcotic` **当前由客户端声明**（`AdministerDrugRequest.narcotic`）；服务端只校验核对人资格，无法识别"舒芬太尼填 false"绕过双人核对 | 药品字典 **I-07** 落地后由 M5 按药品编码服务端判定麻精属性，客户端声明仅作兼容输入；过渡期在界面/审计提示风险（麻醉科/药学部/M5） |
| Q-9 | 临床角色如何获取本台体征 | 记录单 `vitalSigns` 本期固定空数组；设备域体征查询需 `m2:device:read`（仅 SYS_ADMIN/SYS_OR_MANAGER），麻醉医师/护士当前无权按术间读取本台设备体征 | M3-QA/M4 权限细化：临床角色按"本台次/本术间"读取本台设备体征的映射口径（记录单 `vitalSigns` ← `anesthesia_vital`，按记录单台次绑定术间与设备时间窗）待临床与架构确认 |
| Q-10 | 事件时间容差 | 未来/补录容差默认 600 秒（`anesop.m2.record.time-tolerance-seconds`）；超出容差的补录必须填原因 | 院方对"补录时限/原因必填范围"的临床要求（如术后 24h 内补记是否需上级确认）待**麻醉科/医务处**确认；E2E-02 断网补传与并发追加专项由 **M3-QA-01** 承接 |

### 10.1 知情同意与三方核查复审 Q 登记（M2-M5-01，编号 Q-1…Q-4）

> 本节为 M2-M5-01 独立复审新增的临床/M3 待确认项，编号在本小节内独立；与上表术前访视
> Q-1…Q-7 不冲突。只登记，不改变当前实现。

| 编号 | 事项 | 当前实现 | 待确认点 / 决策人 |
|---|---|---|---|
| Q-1 | 同意有效期、撤回通知与开场阻断 | 同意记录无有效期字段；撤销仅落库 + 审计，不主动通知手术室/排程；M3 锁单仅校验三方核查完成，未校验“当前有效 SIGNED” | 同意自签署起有效多久（本次住院/本次手术/固定天数）？“撤回同意”是否必须实时通知手术室并阻断开场（M3 锁单/开场前二次校验）？由**医务处/麻醉科/M3**定稿 |
| Q-2 | 是否只允许最新生效模板 | 当前列表返回全部 ACTIVE 版本，创建时可选任意已生效 ACTIVE 版本；未强制“只允许最新版本” | 临床是否要求同一 templateCode 只能选最新生效版本（旧版本仅历史追溯/补签）？若要求，由**医务处**确认后改为服务端强制 |
| Q-3 | 知情同意告知是否限麻醉医师 | 权限 `m2:consent:write` 同时授予 SYS_ANESTHESIOLOGIST 与 SYS_NURSE_ANESTHETIST，护士可创建/签署/拒绝/撤销 | 告知与签署是否必须由麻醉医师本人完成、护士仅可录入/见证？是否需医师二次确认？由**医务处/护理部**确认 |
| Q-4 | Time-out 逐项确认 | 当前只记录 Time-out/Sign-out 时间与标本/植入物核对布尔值 + 问题项；未逐项记录患者身份/术式/部位/过敏/影像等核对清单 | M3 是否补齐 WHO 三方核查逐项确认（身份/术式/部位/过敏/影像/标本/植入物/器械）并作为锁单前置？由**麻醉科/M3**承接 |

## 11. CA 占位（EXT-05）与 M3 锁单集成点

### 11.1 CA 不可用（EXT-05）下的签名实现

- 环境约束：院内 CA/电子签名服务不可用（EXT-05），本期**不模拟真实 CA 有效签名**。
- 签署时服务端生成：
  - `signatureRef = SIM-CA-<consentId>`（占位标识，可追溯到同意记录）；
  - `signedAt = 当前服务端时间`（毫秒精度）与 `signedBy = 当前操作人`；
  - `signatureHash = SHA-256(consentId|applicationId|templateId|templateVersion|
    SHA-256(templateContentText)|signedAt|signMethod)`，十六进制 64 字符。
    输入**不含签署人姓名/证件号等 PHI**（避免小空间哈希可逆），也不重复存储模板正文；
  - `caStatus = SIMULATED`，契约与界面均显式标注“CA 占位（SIMULATED / EXT-05）”。
- 不保存签名原文/图片/证书链：契约 `ConsentRecord` 与表 `consent_record` 只有
  `signatureRef` 与 `signatureHash` 两个签名字段（集成测试断言列名集合）。
- 就诊记录侧的“签名是否有效”不得依赖 `SIMULATED` 状态做法律判断；关闭 EXT-05 后需新增
  `VERIFIED` 流程（CA 验签、证书链、时间戳）并以新迁移/契约版本落地。

### 11.2 M3 锁单集成点（M3-M2-03 已落地）

- “当前有效同意”（锁单读取口径）：同一申请下 `status='SIGNED'` 且 `signed_at` 最新的一条
  记录；同一申请最多一条 SIGNED 由 `uk_consent_record_signed_application` 强制，
  `DECLINED`/`REVOKED` 不占位。锁单不得取 `WAITING_SIGN`、已拒绝或已撤销记录，也不得按
  “最新创建”推断。
- 锁单前置（`RecordLockServiceImpl` 统一评估，§14.2）：
  1. `ThreeWayCheckService.isThreeWayCheckCompleted(applicationId) == true`；
  2. 申请存在当前有效 SIGNED 同意（读不到同意记录时按未完成 fail-safe 处理）；
  3. 记录开始/结束时间完整（`ANESTHESIA_START`/`ANESTHESIA_END`）且至少 1 个时间轴事件；
  4. 尚未锁单。
- 调用约定：同步调用 `m2-api` 接口（不读 `m2_anes.three_way_check` / `consent_record` 表、
  不复制查询）；核查方法对非法标识/无记录返回 `false`。
- 未满足时锁单返回 409（`details[]` 逐项列出未满足条件码），并以独立事务写
  `RECORD_LOCK result=0` 失败审计；`isThreeWayCheckCompleted` 的“未完成禁止锁单”已由
  `RecordLockRevisionApiIntegrationTest` 覆盖。
- 记录单(record)维度的 `ThreeWayCheck` 契约/表实现仍留 M3 后续任务；当前只交付申请维度
  核查，两者通过 `applicationId` 关联。

## 12. 设备采集接入（M3-M8-01）

契约 `packages/contracts/openapi/device.yaml 0.1.0`（新增 device 域，additive）；表结构
`db/migration/V14__m2_device_registry.sql`；代码在 `apps/api/modules/m2/{api,impl}` 的
`acquisition` 子包与 `apps/api/api-bootstrap` 的 `DeviceController`。

### 12.1 接口与权限

| 端点 | operationId | 权限 | 说明 |
|---|---|---|---|
| `GET /v1/devices` | `listDevices` | `m2:device:read` | 台账 + 推导状态 + 质量统计（可按网关/术间/状态过滤） |
| `POST /v1/devices` | `registerDevice` | `m2:device:write` | 登记/换台绑定；首次 201，幂等或换台 200，并发 409 |
| `GET /v1/devices/{gatewayId}/{deviceId}/vitals` | `listDeviceVitals` | `m2:device:read` | 窗口查询（≤24h）+ 质量过滤 + 按采样帧分页 |
| `GET /v1/devices/health` | `getDeviceHealth` | `m2:device:read` | 网关/设备在线与近 24h 上报摘要（运维） |
| `POST /v1/devices/{gatewayId}/{deviceId}/ingest` | `ingestDeviceVitals` | `X-Gateway-Token`（无 JWT） | 网关批量上报，批次/行级双层幂等 |

- V14 权限授予 SYS_ADMIN（运维排障/登记）与 SYS_OR_MANAGER（设备台账与术间绑定）；
  临床角色默认无设备权限，越权返回 403。
- ingest 路径在安全配置中 permitAll，由服务层校验内部令牌：未配置密钥/缺失/不匹配一律 401
  （fail-closed），令牌不落日志与审计。
- 设备登记唯一键 `(gateway_id, device_id)`；`room_id` 为逻辑外键（→ `m1_sched.operating_room`），
  跨模块不建物理外键，未绑定为 null。
- **数据范围（B-1）**：服务层注入 m0 `DataScopeResolver`，非 ALL 范围经 m1-api
  `SchedulingService.listRooms` 取可见术间集合。台账只返回可见术间设备；`roomId` 过滤越权
  403；单设备体征查询越界/不存在统一 404；登记/换台目标术间必须可见，未绑定术间仅 ALL
  可操作（非 ALL 403）。无数据范围或无可见术间一律 403（fail-closed）。
- **共享令牌绑定风险（S-5）**：`X-Gateway-Token` 为部署级共享密钥，**未与 `gatewayId` 绑定**，
  任一持有令牌的网关可伪造其他 `gatewayId/deviceId` 上报；生产前须实现 token→gateway 映射或
  "单网关一密钥 + 轮换"，并叠加 mTLS/网段 allowlist（本期登记，不实现）。

### 12.2 幂等与质量

- 批次层：`device_ingest_log` 唯一键 `(gateway_id, batch_key)`；重复批次 `acceptedRows=0`、
  `duplicateBatch=true`、`duplicateRows=frames.length`，不重复写库、不重复累加统计。
- 行层：`anesthesia_vital` 唯一键 `(device_id, seq, measured_at)`；新批次携带旧帧只计
  `duplicateRows`。两层在同一事务：日志计数回填失败即整批回滚，不会出现"日志已记、体征未写"。
- 质量：`quality`（0/1/2/3）与 `quality_flags` 原样入库；可疑/缺失帧不丢弃。
  仅格式非法帧被拒绝并计数，原因码为 `SEQ_INVALID`/`MEASURED_AT_MISSING`/
  `QUALITY_OUT_OF_RANGE`/`VALUE_OUT_OF_RANGE`/`SOURCE_INVALID`/`FLAGS_TOO_LONG`，
  响应与日志都不回显报文原文。
- 质量统计写 `device_registry.quality_stats`（jsonb，原子累加 batches/accepted/duplicate/
  rejected/suspect/missing + 最近批次）；比例在查询期计算，不存储派生值。
- 单位以网关规范单位为准（bpm / % / mmHg / ℃）；平台不二次换算，避免双重换算误差。

### 12.3 离线判定与健康摘要

- `GET /v1/devices` 的 `status` 是**查询期推导**：`MAINTENANCE` 显式状态优先；
  其余按 `last_seen_at + anesop.m2.acquisition.offline-timeout-seconds`（默认 120s）判定
  ONLINE/OFFLINE。推导不写回数据库，读操作不产生写放大。`last_seen_at` **仅在本批
  `acceptedRows>0` 时推进**（全部重复/全部拒绝的批次不刷新在线时间）。
- 登记缺省 `status` 不覆盖已有状态：首次登记默认 `ONLINE`，重复登记/换台未带 `status` 时保留
  当前状态（`MAINTENANCE` 不会被改回 `ONLINE`）；请求 `status` 是运维显式设置，响应 `status`
  是查询期推导，二者语义不同（契约已分列说明）。
- `GET /v1/devices/health`：按网关聚合设备在线/离线/维护数、近 N 小时批次与接受/重复/拒绝帧数、
  质量异常比例；N 默认 24h、可配置 1–168（`anesop.m2.acquisition.health-window-hours`）。
  质量比例分子/分母使用同一窗口 `measured_at ∈ [now−N, now]`（上界排除设备时钟异常的未来帧）；
  整体状态 `HEALTHY` / `DEGRADED`（存在 STALE/NO_INGEST 网关或拒绝帧）/
  `NO_DATA`（尚无登记设备）。网关本机缓冲指标仍由 `/actuator/metrics` 暴露（EXT-04）。

### 12.4 审计与无 PHI

- 审计动作：`DEVICE_REGISTER`（REGISTER/REBIND）、`DEVICE_INGEST`（含重复批次）、
  `DEVICE_QUERY`（台账列表与体征窗口查询）；`GET /health` 为运维轮询摘要，不写业务审计。
- detail 仅含 gatewayId/deviceId/batchKey、批次与质量计数、窗口/分页参数；不含报文原文、
  令牌、患者标识。设备域本身不接收姓名/住院号/证件号。
- 日志：令牌与报文原值不落日志；失败日志只记录异常类别与 SQLSTATE/HTTP 状态。

### 12.5 配置

```yaml
anesop:
  m2:
    acquisition:
      gateway-token: ${ANESOP_M2_ACQUISITION_GATEWAY_TOKEN:}   # 空=ingest 一律 401
      offline-timeout-seconds: 120
      max-query-window-hours: 24
      health-window-hours: 24                                  # 健康摘要窗口，1–168
      synthetic: true                                          # 真实接入后改 false
```

### 12.6 测试

- 单测（`AcquisitionServiceImplTest`、`GatewayTokenVerifierTest`）：登记幂等/换台/并发 409、
  批次与行级幂等、拒绝原因、窗口/质量/分页校验、离线推导、健康聚合、令牌 fail-closed。
- 集成（`DeviceAcquisitionApiIntegrationTest`，真实嵌入式 PG）：V14 结构/权限、登记唯一、
  ingest 幂等（重复批次不重复行）、401/403/404、质量标记与拒绝计数、vitals 过滤分页、
  离线判定、审计 detail 无 PHI；数据范围（DEPT 跨科室 403/404）、健康窗口边界、
  登记缺省不覆盖 MAINTENANCE、`last_seen` 仅在 accepted>0 时推进。
- 网关（`PlatformVitalReporterTest`、`PlatformVitalUploadTest`）：适配器开关、令牌头、
  批次键确定性、5xx 保留缓冲、恢复补传、重放幂等。

### 12.7 局限与后续

- 真实设备协议/采集盒未接入（EXT-04，SIMULATED）；平台 ingest 认证为**共享令牌且未绑定
  `gatewayId`**（任一网关可伪造其他网关上报），生产前必须实现 token→gateway 映射或单网关
  独立密钥 + 轮换，并叠加 mTLS/网段 allowlist（本期只登记，见 §12.1 与 `docs/gateway/README.md`
  §11.2）。
- 设备健康摘要为运维聚合视图（含全院设备计数），当前仅按 `m2:device:read` 授权、未按数据范围
  裁剪；临床角色不授予该权限，后续如需按科室展示由 M3-QA/M4 权限细化承接。
- vitals 分页按**采样帧**计数（一帧可展开多条指标行），前端展示需按需聚合；
  波形数据不在本域（方案书 §3.6.3），后续单独设计。
- `room_id` 未做术间存在性校验（逻辑外键）：术间台账变更由 M1 负责，M3 记录单关联时再校验。

## 13. 术中预警与抢救记录（M3-M2-02）

### 13.1 接口与权限（契约 anesthesia 0.7.0，V15 + V16）

| 端点 | 权限 | 说明 |
|---|---|---|
| `GET /v1/anesthesia/alert-rules` | `m2:alert:read` | 每个规则编码的当前版本、阈值、严重度、启用状态、`pendingConfirmation` |
| `GET /v1/anesthesia/alert-rules/{ruleId}/versions` | `m2:alert:read` | **0.7.0 additive**：按 code 返回历史版本（version 倒序），供 `ruleVersion` 追溯 |
| `PUT /v1/anesthesia/alert-rules/{ruleId}` | `m2:alert:configure` | 保存规则 = 生成 `version+1` 新版本并停用旧版本；`name` 可改；阈值按指标范围校验；过期/非当前版本 409 |
| `GET /v1/anesthesia/records/{recordId}/alerts` | `m2:alert:read` | **只读**告警列表（实时评估已由采集事件落库） |
| `POST /v1/anesthesia/records/{recordId}/alerts/evaluate` | `m2:record:write` | 手动/调试评估：按水位只处理新采样，不重评历史窗口 |
| `POST /v1/anesthesia/alerts/{alertId}/acknowledge` | `m2:alert:ack` | `CONFIRMED`/`IGNORED` + 必填 reason；只允许一次流转（409） |
| `GET /v1/anesthesia/records/{recordId}/resuscitations` | `m2:record:read` | 抢救记录列表（复用记录单可见性，不新增只读权限点） |
| `POST /v1/anesthesia/records/{recordId}/resuscitations` | `m2:rescue:write` | 新增抢救记录（append-only、时间窗校验、负责人/参与人员必填） |

数据范围沿用记录单/台次可见性：记录单或台次不可见统一 **404**（不泄露存在性）；方法级
无权限 **403**。V15 新增预警/抢救表与权限；V16（评审修复）只新增列/表/索引/回填，不改 V1–V15
既有语义。

### 13.2 触发入口（评审 B-1 / ADR-0017）

告警以“采集落库后领域事件”为主、前端轮询兜底、手动评估仅调试：

1. `AcquisitionServiceImpl.ingest` 在同一事务内完成批次/行级幂等落库后发布
   `VitalsIngestedEvent`；`@TransactionalEventListener(AFTER_COMMIT)` 的
   `VitalIngestAlertListener` 在提交后调用 `AlertIngestTrigger`（独立 `REQUIRES_NEW` 事务）。
   采集回滚不产生告警；评估失败只记 WARN，不影响采集响应。
2. 记录单创建时经 m1-api `Schedule.roomId` 快照 `anesthesia_record.room_id`（V16）；事件按
   `device_registry.room_id` → `room_id` 匹配 **`status=ONGOING 且 started_at IS NOT NULL、
   ended_at IS NULL`** 的记录。不跨模块读 m1 表。
3. 记录页每 8s 轮询 `GET /records/{id}/alerts`（权限隔离：无 `m2:alert:read` 不请求）；
   “手动评估”按钮调用 evaluate，仅用于联调/补偿与页面兜底。
4. 读取告警列表**不评估**：不会出现“打开旧记录时按当前规则追溯生成历史告警”。

### 13.3 规则口径与间歇指标（评审 B-2）

- 指标：`HR`（bpm）、`SPO2`（%）、`MAP`（mmHg，由无创血压推导 `dia + (sys − dia)/3`，
  V4 只落收缩/舒张压）、`ETCO2`（mmHg）、`TEMP`（℃）。
- 算子：`LT` = `value < thresholdLow`；`GT` = `value > thresholdHigh`；
  `OUT_OF_RANGE` = 低于下限或高于上限。**等于阈值不触发**（严格不等）。
- 持续时长只用**该指标非空样本**的测量时间衡量：该指标空帧（如 NIBP 两次袖带测量之间）与
  质量码 ≥2 的帧**跳过且不重置窗口**；相邻有效测量的间隔超过该指标容忍才切新窗口。
- 每类指标独立间隔容忍（默认值【待确认】，配置见 §13.9）：

| 指标 | 类型 | 默认 gap | 说明 |
|---|---|---:|---|
| HR | 连续波形 | 10s | 1 Hz 波形；间隔 >10s 视为断连 |
| SPO2 | 连续波形 | 10s | 同上 |
| MAP（NIBP） | 间歇测量 | 300s | 袖带测量间隔；>300s 视为新窗口 |
| ETCO2 | 间歇/波形 | 300s | 默认按间歇口径，临床确认后可调 |
| TEMP | 间歇测量 | 300s | 体温探头测量间隔 |

- **重复值不累加**：间歇指标在 gap 容忍内重复上报同一测得值时，视为同一次测量的重复帧，
  不推进窗口、不累加持续时长（避免监护仪重复上报同一条 NIBP 造成“持续低血压”假告警）；
  达到 gap 边界（如 300s）视为新一次测量，可推进并触发。
- 质量码：`quality ≥ 2`（缺失/超时）不参与判定；`quality = 1`（可疑）仍参与。
- **默认种子阈值全部【待确认】**：SpO2 < 90（CRITICAL，立即）、HR < 50 / > 120（WARN，30s）、
  MAP < 65（WARN，60s）、EtCO2 超出 30–50（WARN，30s）。仅演示引擎与页面，**不是临床决策依据**。

### 13.4 水位增量与 DRAFT（评审 B-1/B-3）

- `alert_watermark.evaluated_through` = 该记录单已评估到的最大 `measured_at`；每次评估：
  - 采样窗口 = `max(startedAt, 水位 − 回看窗口) .. (endedAt 或 now)`，回看窗口 =
    最大规则时长 3600s + 最大指标 gap；只用于保持跨事件越界窗口连续；
  - 只落库 `triggeredAt > 水位` 的判定，**不用当前规则回评历史窗口**；评估后水位推进到本次
    读到的最大 `measured_at`（只增，`GREATEST`）。
- 长台次不再受单次 20000 样本截断影响：水位 + 回看窗口保证早于水位的窗口起点仍参与本窗口
  连续计算（例：首轮 0–20s 未满 30s，水位 20s；新样本 30s 时窗口起点仍是 0s）。
- **DRAFT/未开始不评估**：`status != ONGOING` 或 `started_at == null` 一律返回空；
  评估窗口一律从 `startedAt` 起，**禁止 createdAt 兜底**，避免把同术间上一台次患者体征算入
  本记录。V16 把迁移前已存在记录单的水位初始化为迁移时刻，从迁移后新采样开始评估。
- `createdAt` 仅作为记录创建留痕，不参与告警窗口。

### 13.5 去重窗口与幂等

- `dedupe_key = recordId:ruleCode:窗口起始秒`（越界窗口首个有效测量时间），唯一索引
  `uk_alert_dedupe` 兜底；`INSERT ... ON CONFLICT DO NOTHING`。
- 重复事件/重复评估同一窗口不重复插入；处置后**复发的新窗口**（起始时间不同）生成新告警。
- 规则快照：告警行保存 `rule_id/rule_code/rule_version`，规则升级后新告警使用新版本，
  历史告警不被追溯修改。

### 13.6 规则治理（评审 S-2/S-4/S-5）

- 规则不可原地修改：保存 = 同一 code 插入 `version+1` 新行并停用旧行（触发器只允许
  “启用→停用”，DELETE/TRUNCATE 拒绝 55000；部分唯一索引保证同一 code 仅一个启用版本）。
- `name` 可随新版本更新；阈值/持续时长按指标物理范围校验（SpO2 0–100、HR 0–300、
  MAP 0–250、EtCO2 0–150、TEMP 25–45），越界 400（不臆造临床阈值，只做范围校验）。
- `pendingConfirmation`：阈值或持续时长较上一版本变化时置 true；仅名称/严重度/启用变化沿用
  上一版本；默认种子阈值全部【待确认】。清除标记须临床顾问确认后由 M5 复核流程写入。
- 历史版本查询端点（additive）供告警 `ruleVersion` 追溯“当时依据哪一版规则”。
- 规则变更需第二人/临床复核：当前由线下流程保证，M5 权限细化承接“临床提交 + 医务处审批”。

### 13.7 抢救留痕（append-only + 人员，评审 B-4）

- `resuscitation_record` 只 INSERT/查询，触发器拒绝 UPDATE/DELETE/TRUNCATE（55000）；
  更正只能新增记录并在事件摘要说明。
- 时间窗：`startedAt ≥ 记录单开始`、`endedAt ≥ startedAt`、`endedAt ≤ 记录单结束`（未结束时
  `≤ now + anesop.m2.record.time-tolerance-seconds`），违反 400（字段级）。
- **人员留痕（V16）**：`leader` 为 `{userId?, name}` 对象且 name 必填；`rescuers` 为
  `[{userId?, name}]` 数组且至少 1 人（≤20）；缺失/空 400。姓名只落库，日志/审计只记
  `leaderUserId` 与 `rescuerCount`，不写姓名原文。
- 结构化：`eventSummary = {summary, interventions[]}`（摘要 ≤2000 字、干预 ≤50 条）、
  `drugs = [{drugName,dose,unit,route,administeredAt}]`（≤50 条，剂量 > 0，给药时间须在抢救
  时间窗内）；表内不存患者标识。
- 类型/转归枚举为【待确认】临床口径，仅演示。

### 13.8 审计与无 PHI

| 动作 | 资源（resourceType/resourceId） | detail 白名单 |
|---|---|---|
| `ALERT_TRIGGERED` | `ALERT` / **告警主键** | alertId/recordId/ruleCode/ruleVersion/metric/value/severity/triggeredAt/triggerSource |
| `ALERT_ACK` | `ALERT` / 告警主键 | alertId/recordId/ruleCode/ruleVersion/metric/severity/decision/status |
| `ALERT_RULE_UPDATE` | `ALERT_RULE` / 规则版本行主键 | ruleId/code/fromVersion/toVersion/name/metric/operator/thresholds/duration/severity/enabled/pendingConfirmation |
| `RESCUE_RECORD` | `RESUSCITATION_RECORD` / 抢救记录主键 | recordId/resuscitationId/resusType/startedAt/endedAt/outcome/drugCount/interventionCount/leaderUserId/rescuerCount |

不含患者姓名/证件号/住院号/手机号，不含处理原因（`acknowledge_reason`）与事件摘要/药品明细/
人员姓名原文。自动评估的 `ALERT_TRIGGERED` actor 为空（`actorName=alert-engine`）；
手工评估记录调用人（S-1）。

### 13.9 配置

```yaml
anesop:
  m2:
    alert:
      hr-gap-seconds: 10        # 连续波形：HR 相邻有效采样最大间隔，1–3600
      spo2-gap-seconds: 10      # 连续波形：SpO2
      map-gap-seconds: 300      # NIBP 推导 MAP 的测量间隔
      etco2-gap-seconds: 300    # EtCO2
      temp-gap-seconds: 300     # 体温
      max-samples: 20000        # 单设备单次评估采样上限，100–200000
```

### 13.10 测试

- 单测：`AlertRuleEngineTest`（等于阈值不触发、GT/LT/OUT_OF_RANGE、持续时长、空帧不重置、
  NIBP 重复值不累加、300s/301s 边界、波形 10s 切窗、水位 emitAfter）；`AlertEvaluatorTest`
  （DRAFT 空、无 startedAt 空、水位增量与只增、审计 alertId/actor）；`AlertRuleServiceImplTest`
  （版本化、名称更新、pendingConfirmation、指标范围 400、历史版本、409）；
  `AlertServiceImplTest`（只读列表、手动评估委托、状态机）；`ResuscitationServiceImplTest`
  （时间窗、用药、负责人/参与人员必填、审计无姓名）。
- 集成（`AlertRescueApiIntegrationTest`，真实嵌入式 PG）：V15/V16 结构/索引/触发器/权限/种子；
  **ingest 事件实时触发**（无需手动 evaluate）、**水位增量保留早期窗口起点**、
  **DRAFT + 同术间上一台次体征不评估**、规则版本化/名称/范围/历史版本、告警状态机 409、
  抢救人员必填与 append-only、审计关联 alert 主键、403/404。
- 前端：`RecordView.spec.ts`（轮询/手动评估/权限隔离/抢救人员表单）、
  `AlertRuleConfigView.spec.ts`（列表/待确认/新版本）、`apps/web/e2e/m2-alerts.spec.ts`。

### 13.11 局限、待确认与升级路径

1. **待确认 Q（必须回写）**：Q-1 quality=1（可疑）帧是否可触发 CRITICAL；Q-2 ACK 后是否按
   N 分钟升级再提醒；Q-3 默认阈值与各指标 gap；Q-4 抢救负责人/参与人员是否必须绑定当前登录
   用户与 CA 签名（M5/M3-M2-03）。
2. 规则配置无独立审批流：阈值变更由 SYS_ADMIN 代录，`pendingConfirmation` 只做标记，清除与
   第二人复核由 M5 权限细化承接。
3. 告警无通知渠道（短信/大屏/声音），通知去重/升级策略未设计。
4. 告警处置不可撤销（一次性流转），如需更正处置须新增更正记录（后续版本）。
5. 采集事件评估为同步 AFTER_COMMIT（每批一次增量查询）；高并发多设备/多术间时如出现延迟，
   升级路径为事件异步化（RabbitMQ）+ 批量合并，接口与水位语义不变。
6. DRAFT 不评估是临床安全选择；若未来需要“术前预警”，应新增独立规则集与契约，不在本记录
   窗口内扩展。

## 14. 锁单、修订与 CA 占位签名（M3-M2-03，契约 0.8.1 + V17）

### 14.1 接口与权限

| 能力 | 端点（operationId） | 权限 |
|---|---|---|
| 锁单状态与前置清单 | `GET /records/{recordId}/lock-status`（`getRecordLockStatus`） | `m2:record:read` |
| 锁单 | `POST /records/{recordId}/lock`（`lockAnesthesiaRecord`） | `m2:record:lock`（V17 授 SYS_ADMIN/SYS_ANESTHESIOLOGIST） |
| 修订列表/详情 | `GET /records/{recordId}/revisions`、`GET .../revisions/{revisionNo}` | `m2:record:read` |
| 当前有效值 | `GET /records/{recordId}/effective`（`getEffectiveAnesthesiaRecord`） | `m2:record:read` |
| 创建修订 | `POST /records/{recordId}/revisions`（`createRecordRevision`） | `m2:record:revise`（V17 授 SYS_ADMIN/SYS_ANESTHESIOLOGIST） |

- 数据范围沿台次/申请可见性：单记录越界/跨科室 404；缺权限由方法级授权 403。
- 护士（SYS_NURSE_ANESTHETIST）默认**不授** `m2:record:lock`/`m2:record:revise`：
  锁单与修订的责任人是麻醉医师；护士是否可修订待临床确认（§14.9 Q-1）。
- SYS_ADMIN 保留锁单/修订能力作为 break-glass（运维/排障），审计 detail 标注
  `administratorOperation=true`（“管理员操作”）；是否仅麻醉医师可执行待临床确认（Q-3）。

### 14.2 锁单前置、幂等与并发

- 六项前置固定顺序（服务端评估、前端只展示）：`THREE_WAY_CHECK_COMPLETED`、
  `CONSENT_SIGNED`、`RECORD_STARTED`、`RECORD_ENDED`、`AT_LEAST_ONE_EVENT`、`NOT_LOCKED`。
- 未满足 → 409 `STATE_CONFLICT`，`details[]` 每项 `field=条件码`、`reason=中文说明`；
  同时以独立事务写 `RECORD_LOCK result=0` 失败审计（只记条件码，不含 PHI/原因原文）。
- `confirmChecklist` 缺省/false → 400（服务端仍重新校验全部前置，确认字段不能替代校验）。
- 锁单在 `SELECT ... FOR UPDATE` 记录单行内执行：重复锁单 409、version 过期 409、
  条件更新 0 行 409；锁单只写锁字段与状态，不改变麻醉方式/开始结束时间（数据库守卫拒绝）。
- 锁单前读不到同意记录（不可见/异常）按“未满足”fail-safe 处理，宁可阻止锁单。

### 14.3 CA 占位签名与稳定序列化（EXT-05）

- CA 不可用（EXT-05）：**不模拟真实 CA 有效签名**，只生成
  `signatureRef = SIM-CA-<记录单/修订标识>`、`ca_status = SIMULATED` 与 SHA-256 摘要；
  不保存签名原文/图片/证书链，不产生 PDF/可信时间戳。
- 摘要输入为规范化快照 JSON，规则：
  1. 对象键按字典序排序；
  2. 值为 null 的对象字段不参与（数组内元素顺序保持服务端稳定排序）；
  3. 数字去尾零（`BigDecimal.stripTrailingZeros().toPlainString()`，10.000 → `10`）；
  4. 时间统一 `+08:00` 毫秒精度 ISO-8601；
  5. 排除患者标识（`patientId/patientNo/patientName`）、台次/申请标识、审计时间
     （`createdAt/createdBy/updatedAt/updatedBy` 与录入时间）与签名字段本身。
- 快照结构：`record`（anesthesiaType/startedAt/endedAt）、`events`（全部时间轴，含 note/
  entryReason）、`drugs`、`fluids`、`rescues`（全部抢救记录，含 eventSummary/drugs/
  leader/rescuers/outcome）、`ioSummary`（intake/output/balance/fluidCount）、
  `signatures`（`lockedSignatureHash`、`previousRevisionSignatureHash`）。
- 快照数组按 `(时间, 标识)` 稳定升序：事件 `occurredAt+seq+id`、用药 `administeredAt+id`、
  出入量 `recordedAt+id`、抢救 `startedAt+id`；不依赖 Mapper 查询顺序，避免同毫秒乱序导致
  摘要不稳定。抢救记录的 `createdAt/createdBy` 属审计字段，不入快照。
- “全量”边界：只含人工书写/确认的临床内容；设备采集的 `vital_sign` 明细（高频采样、质量
  标记、原始帧）**不在快照范围**——由采集链路按幂等键独立留痕、不参与锁单签名，且会放大
  快照体量。`contentHash(snapshot)` 只对临床内容取摘要（排除 `signatures`），用于验证
  “改回旧内容 → 内容摘要复原”；正式 `signatureHash` 仍含版本链。
- 修订摘要包含上一修订摘要 → 版本哈希链（首版 `previousRevisionSignatureHash=null`）；
  锁单摘要不含签名摘要（锁单时尚未签名）。实现类 `RecordRevisionSnapshotBuilder`，
  单测 `RecordRevisionSnapshotBuilderTest` 断言“同内容同摘要、不同内容不同摘要”。

### 14.4 锁单不可变（服务层 + 数据库双保险）

- 服务层：`update`/`appendEvent`/`recordDrug`/`recordFluid` 在锁单后统一 409
  `STATE_CONFLICT`；用药/出入量写入前先锁记录单行，避免与并发锁单竞态。
- 数据库（V17）：
  - `fn_record_child_locked_guard` + 四个 BEFORE INSERT 触发器：记录 `locked_at` 非空时拒绝
    `record_event`/`drug_administration`/`fluid_record`/`resuscitation_record` 插入
    （SQLSTATE 55000）；抢救记录锁单后冻结（Q-2），临床确需补录走下一修订版本能力；
  - `fn_anesthesia_record_guard`（CREATE OR REPLACE）：锁字段写一次、锁单后内容列不可变、
    `status` 只允许 `ONGOING→LOCKED`、`LOCKED→REVISED`，`current_revision` 每次只能 +1 且
    与 `REVISED` 一致，`version` 只增；DELETE/TRUNCATE 一律拒绝；
  - `record_revision` append-only（UPDATE/DELETE/TRUNCATE 拒绝，55000）；新增 BEFORE INSERT
    链守卫：父记录已锁单、`revision_no = current_revision + 1`、`previous_signature_hash`
    与上一修订摘要一致（首版为空），伪造/跳号/断链直插一律 55000。
- 运行账号不得拥有 DDL 权限，否则可 `DISABLE TRIGGER` 绕过（见 `docs/db/README.md` §5.3）。

### 14.5 修订模型（原值保留、可比对）

- 修订只写 `record_revision` 快照，**不修改锁单时的记录行**：原始麻醉方式/时间/事件保持
  锁单值；更正后的内容只存在于修订快照，首版差异对比 `previousRevisionNo=0`（锁单原始记录），
  后续版本对比上一修订快照。
- `reasonCode` 白名单（0.8.1 收缩）：`ANESTHESIA_TYPE_CHANGE`（更正麻醉方式）、`OTHER`
  （须在原因说明）；`OMISSION`/`TIMING_CORRECTION` 需结构化更正能力，登记为 M3-QA 前待实现
  （`docs/decisions.md` Q-17），不保留“只能写说明、不能结构化改内容”的误导性枚举。
  `reason` 与 `changes` 必填（≤500，只落库/授权用户可见，不写日志/审计原文）。
- 可选 `corrections.anesthesiaType` 仅写快照：时间轴/用药/出入量为 append-only，不能经修订
  改写（漏记须新增事件并填 `entryReason`）；后续修订未提交 corrections 时沿用上一版快照的
  麻醉方式（更正是累积的，不会“改回”锁单原值）。
- 版本号在记录单行锁内分配（`current_revision+1`），`(record_id, revision_no)` 唯一兜底；
  并发修订串行化为 1、2…（集成测试双线程验证）。
- 当前有效版本投影：`GET /effective` 返回 `original`（锁单原始行字段）与 `effective`
  （叠加最新修订更正后的值，当前仅 `anesthesiaType`）及 `corrections[]` 来源；记录详情页/
  打印使用 `effective`，原记录行与历史修订始终不可变。未锁单/未修订时二者相同。

### 14.6 审计与无 PHI

- 动作：`RECORD_LOCK`（锁单成功/失败前置）、`RECORD_REVISE`（修订成功）、
  `RECORD_SIGN`（锁单/修订签名，scope=LOCK/REVISION，含 `signatureRef/signatureHash/caStatus`）。
- detail 只含记录/台次标识、状态、版本号、原因码、拒绝码、签名占位标识与摘要；不含患者姓名/
  院内患者号、原因/变更摘要原文、签名人员姓名。重复锁单/版本过期/未锁单修订/并发冲突同样写
  `result=0` 失败审计（独立事务，只记拒绝码）；SYS_ADMIN 的 break-glass 标注
  `administratorOperation=true`。
- 前端界面显式标注“CA 占位 SIMULATED / EXT-05，非真实 CA 有效签名”；不得用于法律意义上的
  电子签名判断。

### 14.7 测试

见 §8 的 `RecordRevisionSnapshotBuilderTest`（抢救入快照、同毫秒排序稳定、改回旧内容
contentHash 复原）/ `RecordLockServiceImplTest`（失败审计）/ `ResuscitationServiceImplTest`
（锁后抢救 409）/ `RecordLockRevisionApiIntegrationTest`（真实 PG16：先录抢救→锁单→修订、
hash 随抢救内容变化、effective 投影、锁后抢救 409/直插 55000、链触发器拒绝伪造版本）、
前端 `RecordLockView.spec.ts` 与 E2E-03（锁单→编辑被拒→带原因修订→新版本可见、effective
展示修订值且原值仍可追溯）。

### 14.8 局限与后续

1. CA 占位：无真实签名/时间戳/证书链与验签工具；关闭 EXT-05 后需新增 `VERIFIED` 流程与契约。
2. 无 PDF 生成与打印留痕；锁单后“未签名记录质控高亮”未实现（方案书 §3.9.4）。
3. 锁单后冻结事件/用药/出入量/抢救记录；告警处置是否随锁单冻结待临床确认（Q-2/Q-3）。
   如临床确需锁单后补录抢救，需下一修订版本能力（登记 Q-18），不绕过冻结。
4. 修订仅允许更正麻醉方式；更复杂的字段更正（时间/用药/事件）需结构化更正契约与临床规则，
   `OMISSION`/`TIMING_CORRECTION` 原因码暂不开放（Q-17）。
5. 锁单前置未纳入“生命体征已采集/麻醉小结完成”等记录完整性项（契约 0.8.1 只列六项）。

### 14.9 Q 登记（待临床确认，只登记不改实现）

| 编号 | 事项 | 当前实现 | 待确认点 |
|---|---|---|---|
| Q-1 | 护士能否锁单/修订 | 仅授 SYS_ADMIN/SYS_ANESTHESIOLOGIST | 麻醉护士是否可修订记录？由麻醉科/护理部确认 |
| Q-2 | 锁单后冻结范围 | 事件/用药/出入量/抢救记录均冻结 | 抢救记录锁单后补录是否走下一修订版本？由麻醉科确认（§14.8-3） |
| Q-3 | 是否仅麻醉医师可锁单/修订 | 保留 SYS_ADMIN break-glass 并审计标注 | 管理员是否只应排障、不参与临床签名？由医务处/麻醉科确认 |
| Q-4 | 记录完整性前置 | 开始/结束 + ≥1 事件 | 是否要求麻醉小结/生命体征/签名完成后才允许锁单？ |
| Q-5 | 修订原因码 | 0.8.1 仅 ANESTHESIA_TYPE_CHANGE/OTHER | OMISSION/TIMING_CORRECTION 待结构化更正能力（M3-QA 前，Q-17） |

## 15. 复苏记录与转出（M3-M10-01，契约 0.9.0 + V18）

### 15.1 接口与权限

| 端点（operationId） | 权限 | 说明 |
|---|---|---|
| `GET /v1/anesthesia/pacu-records?recordId=`（`listPacuRecords`） | `m2:pacu:read` | 按麻醉记录单查询复苏记录（创建时间倒序）；`recordId` 缺失/非 UUID 400；记录单不可见 403（集合语义） |
| `POST /v1/anesthesia/pacu-records`（`admitPacuRecord`） | `m2:pacu:write` | 入室（201）；记录单未开始 409、不可见 422、重复未转出记录 409、时间窗 400 |
| `GET /v1/anesthesia/pacu-records/{pacuId}`（`getPacuRecord`） | `m2:pacu:read` | 复苏记录详情（含最近 Aldrete 总分与服务端阈值）；越界/不存在 404 |
| `GET /v1/anesthesia/pacu-records/{pacuId}/assessments`（`listPacuAssessments`） | `m2:pacu:read` | Aldrete 时间序列（assessedAt 升序） |
| `POST /v1/anesthesia/pacu-records/{pacuId}/assessments`（`recordPacuAssessment`） | `m2:pacu:write` | 记录评估（201，append-only）；单项越界/缺项 400、已转出 409 |
| `GET /v1/anesthesia/pacu-records/{pacuId}/discharge`（`getPacuDischarge`） | `m2:pacu:read` | 转出留痕（未转出 404） |
| `POST /v1/anesthesia/pacu-records/{pacuId}/discharge`（`dischargePacuRecord`） | `m2:pacu:write` | 转出（201）；Aldrete 未评估/未达阈值 409（details 条件码）、未确认/SBAR 缺失 400 |

数据范围沿用记录单/台次可见性（只调 m1-api，不读 m1 表）：按记录单列表为集合语义
（越权 403），单对象操作越界 404，入室时记录单不可见 422。V18 权限点
`m2:pacu:read|write` 授予 SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST
（与记录单读写同口径）；PACU 专职护士角色 `SYS_PACU_NURSE` 当前未落库种子，是否单独授权
待护理部确认（§15.9 Q-2）。

### 15.2 入室与状态机

```text
(入室) ──→ ADMITTED ──首次评估──→ MONITORING / READY
              │                      │        │
              │                      │        └── 再次评估 < 阈值 → MONITORING（可回落）
              │                      └── 评估 ≥ 阈值 → READY
              └────────── 转出（前置全部满足）──→ DISCHARGED（终态）
```

- 入室必须关联**已开始**的麻醉记录单（`ONGOING/LOCKED/REVISED` 且 `started_at` 非空）；
  DRAFT 或未开始 409。患者标识由记录单解析，不信任客户端。
- 一条麻醉记录单同时最多一条未转出记录（服务层先查 + 部分唯一索引 `uk_pacu_record_active`
  兜底 → 409）；转出后可再次入室（新记录，历史保留）。
- 时间窗：`admittedAt ≥ 记录 startedAt`，且 `≤ 服务端当前时间 + 容差`
  （`anesop.m2.record.time-tolerance-seconds`，默认 600s），违反 400。
- 入室信息（生命体征/意识/疼痛/恶心/气道）与身份列写入后不可改（DB 守卫 55000）：
  入室信息有误须重新入室并说明，不回改历史。
- 状态机：`ADMITTED → MONITORING/READY → DISCHARGED`，`DISCHARGED` 为终态，不可回退为
  监测/达标。`READY→MONITORING` 当前实现允许（最新评估因病情变化低于阈值时回落），
  **是否允许待临床确认（§15.9 Q-8）**；状态推进以 latest 评估为准。

### 15.3 Aldrete 评分口径（临床规则，需人工逐行）

> 实现：`AldreteCalculator`（五项合法性/求和）与 `PacuServiceImpl`（字段级 400 / 总分计算 /
> 状态推进）。分值与切点来自公开量表，**院方转出阈值【待确认】**；系统结果不得替代医师判断
> （AGENTS H-06）。

| 项 | 0 分 | 1 分 | 2 分 |
|---|---|---|---|
| activity 活动能力 | 不能活动肢体（自主或遵嘱） | 可活动两个肢体（自主或遵嘱） | 可活动四个肢体（自主或遵嘱） |
| respiration 呼吸 | 呼吸暂停 | 呼吸困难或呼吸受限 | 能深呼吸并有效咳嗽 |
| circulation 循环 | 血压偏离术前 >50% | 偏离 20%–50% | 偏离 <20% |
| consciousness 意识 | 无反应 | 呼之可醒 | 完全清醒 |
| oxygenation 氧合 | 吸氧下 SpO₂ <90% | 需吸氧维持 SpO₂ >90% | 空气下 SpO₂ >92% |

- 分项定义按 Aldrete 1995 修订版原文校准（activity 1=活动两个肢体、respiration 0=呼吸暂停、
  consciousness 1=呼之可醒等）；契约 description、`AldreteCalculator` Javadoc、V18 列注释、
  前端评分表选项保持同一口径。
- 每项 0–2（缺项/越界 400），**总分由服务端计算**（五项之和 0–10），契约无客户端总分字段；
  数据库 `ck_pacu_assessment_total_sum` 为第二道防线。
- 来源：Aldrete JA, Kroulik D. Anesth Analg. 1970;49(6):924-934；修订版 Aldrete JA.
  J Clin Monit. 1995;11(1):41-46。
- **转出阈值**：`anesop.m2.pacu.aldrete-threshold`，默认 **9**（0–10 校验，非法配置启动即失败），
  院方口径【待确认】（Q-1）。阈值调整只改配置；转出时阈值与总分冻结在
  `pacu_discharge.criteria_snapshot`，可追溯“当时依据”。
- 评估 **append-only**：表只 INSERT/查询，UPDATE/DELETE/TRUNCATE 由触发器拒绝（55000）；
  更正只能新增评估；已转出后再评估 409（DB BEFORE INSERT 守卫 55000）。
- 时间序列按 `assessed_at ASC, created_at ASC, id ASC` 稳定排序；latest 统一按
  `(assessed_at, created_at, id)` 倒序取 1 条，状态推进以 latest 为准。
- 评估时间不得早于入室、**不得早于上一条评估**（否则 400）、不得晚于当前时间 + 容差（400）；
  时间序列只追加不回溯补录。总分达到阈值时 latest 状态推进为 READY，否则 MONITORING
  （READY→MONITORING 回落待临床确认，§15.9 Q-8）。

### 15.4 转出前置、SBAR 与达标快照

- 请求：`destination`（`WARD/ICU/HDU/DAY_SURGERY/HOME`，演示口径【待确认】）、
  `handover`（SBAR 四段各必填 ≤2000 字符）、`criteriaConfirmed=true`（缺省/false 400，
  不能替代服务端校验）。
- 服务端转出前置（固定顺序、`details[]` 逐项列出条件码）：
  1. `ALDRETE_ASSESSED`：存在最近一次 Aldrete 评估；
  2. `ALDRETE_ITEMS_COMPLETE`：五项齐全且各在 0–2；
  3. `ALDRETE_THRESHOLD_MET`：总分 ≥ 阈值（含等于）。
- 未满足 → 409 `STATE_CONFLICT`，并以独立事务写 `PACU_DISCHARGE result=0` 失败审计
  （只记条件码）；不写转出、不放宽阈值。
- 满足后在复苏记录行锁内：写 append-only 转出留痕（去向/SBAR/达标快照）→ 置 DISCHARGED 并
  写 `discharged_at`（服务端时间）；重复转出 409，转出后不可再评估 409。
- **未处理严重告警是否阻断转出**属临床规则，本期**未实现**（Q-3），不得据此认为“已排除风险”。

### 15.5 不可变与并发（V18 触发器）

- `pacu_assessment` / `pacu_discharge`：UPDATE/DELETE/TRUNCATE 一律 55000（append-only）。
- `pacu_assessment` BEFORE INSERT：父记录已 `DISCHARGED` 时拒绝新增（55000）。
- `pacu_record` 守卫：身份列与入室信息不可改、`DISCHARGED` 为终态（`READY→MONITORING`
  回落待临床确认，§15.9 Q-8）、`discharged_at` 写一次且必须与 `DISCHARGED` 同置、`version`
  只增、DELETE/TRUNCATE 拒绝（55000）。
- 并发：入室靠部分唯一索引 → 409；评估/转出用 `WHERE id=? AND version=? AND status<>'DISCHARGED'`
  条件更新，0 行即 409（不静默覆盖）。
- 运行账号不得拥有 DDL 权限（否则可 DISABLE TRIGGER 绕过，见 `docs/db/README.md` §5.3）。

### 15.6 审计与无 PHI

| 动作 | 资源 | detail 白名单 |
|---|---|---|
| `PACU_ADMIT` | `PACU_RECORD` / 复苏记录标识 | pacuId/recordId/status/admittedAt/aldreteThreshold |
| `PACU_ASSESS` | `PACU_RECORD` / 复苏记录标识 | pacuId/recordId/assessmentId/五项分值/aldreteTotal/threshold/status |
| `PACU_DISCHARGE` | `PACU_RECORD` / 复苏记录标识 | pacuId/recordId/dischargeId/destination/aldreteTotal/threshold/latestAssessmentId/handoverSections |

不含患者姓名/证件号/住院号/手机号，**不含 SBAR 交接原文与评估备注原文**；冲突/未达标路径以
独立事务写 `result=0`（只含拒绝码与未满足条件码）。SBAR 与备注只落库、授权用户可见。

### 15.7 配置

```yaml
anesop:
  m2:
    pacu:
      # Aldrete 转出阈值（0–10）。院方阈值【待确认】，默认 9 仅为演示口径。
      aldrete-threshold: ${ANESOP_M2_PACU_ALDRETE_THRESHOLD:9}
```

时间容差复用 `anesop.m2.record.time-tolerance-seconds`（默认 600s）。阈值越界启动即失败
（fail-closed），不会静默放宽转出条件。

### 15.8 测试

- `AldreteCalculatorTest`（4）：总分边界 0/10、单项 0/2 与越界拒绝、阈值含等于判定。
- `PacuServiceImplTest`（29）：入室时间窗/未开始 409/不可见 422/重复入室 409/唯一键并发 409、
  入室必填与物理范围 400、Aldrete 服务端计算与 READY/MONITORING 推进、单项缺项/越界 400、
  评估时间窗 400、**评估时间早于上一条 400**、**状态推进以 latest 而非本次提交分值**、
  已转出评估 409、备注超长 400、版本冲突 409、转出未评估/未达阈值 409（条件码）、转出成功
  快照与审计、SBAR/去向/确认 400、重复转出 409、越界 404、列表 403。
- `PacuRecoveryApiIntegrationTest`（5，真实嵌入式 PG16）：入室→9 分/10 分评估→转出（快照/
  SBAR/阈值）、**评估时间早于上一条 400**、转出后再评估 409、重复转出 409、append-only 55000
  （评估/转出/复苏记录）、转出后直插评估 55000、审计 4 条成功且 detail 无 PHI；未评估/8 分
  转出 409 与 result=0 审计；入室/评估字段 400、重复入室 409、非法标识 404；权限 403 与
  跨科室 404/403/422；V18 结构/触发器/权限/注释；总分≠五项之和、单项越界、转出时间未同置、
  重复未转出记录由 DB 拒绝。
- 前端 `PacuView.spec.ts`（5）：空态入室（体征不预填、缺项不可提交）、**Aldrete 未选分项
  不可提交/显式选择后提交**、转出 SBAR 与达标确认（展示服务端 latest 评估）、阈值不足 409
  展示服务端条件；`RecordView.spec.ts` 覆盖记录页 PACU 入口按 `m2:pacu:read` 显示/隐藏。

### 15.9 局限与 Q 登记（只登记不改实现）

1. **Aldrete 转出阈值默认 9 为演示口径，院方阈值【待确认】**（Q-1）；阈值变更需临床顾问确认，
   建议同时走配置审批并记录变更依据。
2. 入室意识/气道/转出去向枚举均为演示口径（Q-4），需麻醉科/护理部确认院方字典。
3. 未处理严重告警是否阻断转出（Q-3）未实现；当前只有 Aldrete 阈值与 SBAR/确认校验。
4. PACU 专职护士（`SYS_PACU_NURSE`）未在 V2 种子落库，权限暂授麻醉医师/麻醉护士/
   系统管理员（Q-2）。
5. 入室信息写入后不可改（只可重新入室）；是否需要“入室信息更正（带原因）”待临床确认。
6. 评估/转出未与麻醉记录锁单（§14）联动：锁单后仍可记录 PACU 复苏（PACU 属术后独立阶段，
   不进麻醉记录单修订快照，Q-5）。
7. 无 PACU 床位/转出排队、无接收科室确认回执；与 M1 排程/病房衔接仅以记录单台次为纽带
   （Q-6）。
8. **转出前 N 分钟时效窗未实现**（Q-7）：当前取任意最近一次 Aldrete 评估，不校验评估距转出
   的时间间隔；是否要求“转出前 N 分钟内重新评估”待临床确认。
9. **READY→MONITORING 回落**当前允许（最新评估低于阈值时回落），是否允许由临床确认（Q-8）；
   文档统一表述为“DISCHARGED 终态，READY 可回 MONITORING 待确认”，不再笼统写“状态只进不退”。
10. **入室体征必须项**：前端要求 SBP/DBP/HR/SpO₂/RR/TEMP 六项显式录入（不预填），但契约
    `PacuVitalSigns` 与服务端仍按“可缺项、只校验已提供值”实现；是否改为服务端强制必填待
    临床确认（Q-9）。

| 编号 | 事项 | 当前实现 | 待确认点 / 决策人 |
|---|---|---|---|
| Q-1 | Aldrete 转出阈值 | 默认 9（配置项，可 0–10） | 院方阈值与“是否按年龄/术式分层”由麻醉科/医务处确认 |
| Q-2 | PACU 护士权限 | `m2:pacu:read|write` 授 3 个角色，未含独立 PACU 角色 | 是否新增 `SYS_PACU_NURSE` 并单独授权（护理部/M0） |
| Q-3 | 未处理严重告警阻断转出 | 未实现 | 是否要求 CRITICAL 未处置告警阻断转出（麻醉科/M3-M2-02 联动） |
| Q-4 | 意识/气道/去向枚举 | ALERT/DROWSY/SEDATED/UNRESPONSIVE；NATURAL…TRACHEOSTOMY；WARD/ICU/HDU/DAY_SURGERY/HOME | 院方字典与是否允许“其他/自定义”（麻醉科/护理部/信息科） |
| Q-5 | 锁单与 PACU 关系 | PACU 不参与麻醉记录修订快照 | 锁单后 PACU 是否允许继续记录（当前允许）由医务处确认 |
| Q-6 | 病房交接回执 | 无 | 是否需接收科室签收/床位预约（M1/M4 衔接） |
| Q-7 | 转出前评估时效窗 | 未实现（取最近一次评估，不限制间隔） | 是否要求转出前 N 分钟内重新评估，N 由麻醉科/医务处确认 |
| Q-8 | READY→MONITORING 回落 | 允许（最新评估低于阈值时回落） | READY 后是否只能维持/转出、不得回退 MONITORING（麻醉科/医务处） |
| Q-9 | 入室体征必须项 | 前端要求六项关键体征；契约/服务端允许缺项 | 哪些体征强制必填、缺项是否阻断入室（麻醉科/护理部/信息科） |

## 16. 术后随访与镇痛（M3-M11-01，契约 0.10.0 + V19）

### 16.1 接口与权限

| 端点（operationId） | 权限 | 说明 |
|---|---|---|
| `GET /v1/anesthesia/followups?recordId=`（`listPostopFollowups`） | `m2:followup:read` | 按记录单查询随访（assessedAt 倒序）；`recordId` 缺失/非 UUID 400；记录单不可见 403（集合语义） |
| `POST /v1/anesthesia/followups`（`createPostopFollowup`） | `m2:followup:write` | 录入随访（201，append-only）；记录单不存在/不可见 422（不泄露存在性）、未结束 409、时间/评分/文本校验 400 |
| `GET /v1/anesthesia/followups/{followupId}`（`getPostopFollowup`） | `m2:followup:read` | 随访详情；不存在/越界 404 |
| `GET /v1/anesthesia/followups/{followupId}/pain-management`（`listPainManagement`） | `m2:followup:read` | 镇痛记录（录入时间升序）；随访不存在/越界 404 |
| `POST /v1/anesthesia/followups/{followupId}/pain-management`（`recordPainManagement`） | `m2:followup:write` | 追加镇痛记录（201，append-only）；参数校验 400、随访越界 404 |

数据范围沿用记录单/台次可见性（只调 m1-api，不读 m1 表）：按记录单列表为集合语义
（越权 403），单对象操作越界 404，录入时记录单不可见 422。V19 权限点
`m2:followup:read|write` 授予 SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST
（与记录单/PACU 同口径）；病房护士等其他角色是否可随访待护理部/麻醉科确认（§16.9 Q-5）。

> 契约中另存在早期占位的 `/v1/anesthesia/follow-ups`（带连字符，`FollowUp`/`FollowUpPage`，
> 含 patientName/patientNo 字段）**未实现**，0.10.0 已在契约中标注 `deprecated: true`，
> 本期实现的是 `/followups`（无连字符）与 `PostopFollowup`。两者不得混用；旧占位的
> 下线/收敛登记为独立任务（§16.9 Q-6），需兼容性评估与契约评审。

### 16.2 随访前置与时间口径

- 随访只能录入到**已结束**的麻醉记录单（`ended_at` 非空）；未结束 409 `STATE_CONFLICT`，
  并以独立事务写 `FOLLOWUP_CREATE result=0`（只含 `rejection=RECORD_NOT_ENDED`）。
- `assessedAt`：≥ 记录 `endedAt`（否则 400，是否应改 409 待确认，§16.9 Q-8）；≤ 服务端当前时间 + 容差
  （`anesop.m2.record.time-tolerance-seconds`，默认 600s，否则 400）；同一记录单同一随访类型
  **不得早于上一条**（400，同刻允许）。latest 按
  `assessed_at DESC, created_at DESC, id DESC` 判定。
- 并发：录入事务先 `SELECT ... FOR UPDATE` 锁麻醉记录单行，把同一记录的并发随访串行化，
  保证“同类型时间单调”检查在同一把锁下成立。
- 同一记录单同类型允许多次随访（如 24h 首次 + 补录），H24 与 H48 的时间序列相互独立；
  更正只能新增随访，不得改写历史。
- **随访窗口（允许提前/延后多少小时）与超时提醒未实现**，只按类型记录，口径【待确认】
  （§16.9 Q-1）。

### 16.3 评分与并发症口径（临床规则，需人工逐行）

| 字段 | 取值 | 说明 |
|---|---|---|
| `nrsScore` | 0–10 | NRS 疼痛评分（0 无痛、10 剧痛）；量表口径【待确认】（Q-4） |
| `ponvScore` | 0–3 | PONV 评分（演示分级：0 无、1 轻度恶心、2 呕吐、3 严重呕吐）【待确认】（Q-4） |
| `complications` | 多选（≤20，去重） | 白名单 `FollowupComplication`（演示字典【待确认】，Q-3） |
| `complicationNotes` | ≤500 | 自由文本，服务端脱敏后落库 |
| `patientFeedback` | ≤500 | 患者反馈，服务端脱敏后落库 |
| `followedUpBy` | ≤64 | 随访人姓名摘要（操作人另记 `createdBy`） |

- 服务层校验（越界/缺项 400），数据库 `ck_followup_record_nrs`（0–10）与
  `ck_followup_record_ponv`（0–3）为第二道防线；**分值/分级不替代临床判断**（H-06）。
- 并发症字典当前为演示口径（PONV、镇痛不全、呼吸抑制、气道损伤、低血压、高血压、心律失常、
  寒战、苏醒延迟、神经损伤、手术部位出血、感染、其他）；院方字典确认后以 additive 方式扩展。

### 16.4 镇痛/PCA 参数口径

- 方式：`PCIA/PCEA/ORAL/IV/NERVE_BLOCK/OTHER`（演示口径【待确认】）；效果：
  `EFFECTIVE/PARTIAL/INEFFECTIVE/NOT_ASSESSED`。
- 药物名称属自由文本，落库与写审计前经 `PhiMasker` 脱敏（与说明/反馈同口径，评审 S-1）；
  参数只做物理校验：`bolusDose > 0`、`backgroundDose ≥ 0`、`lockoutMinutes > 0`（分钟）；
  **PCA 默认值与剂量单位（mg/μg/ml）未内置【待确认】**（Q-2），系统不推荐处方、不替代医师判断。
- 调整记录 `adjustments[]`（≤20 条，每条说明 ≤200）：随镇痛记录追加；**参数调整只能新增
  镇痛记录**，不得 UPDATE 历史（append-only，数据库 55000）。
- 同一随访可多次追加镇痛记录（多模式/调整时间序列），按 `created_at ASC, id ASC` 展示。

### 16.5 脱敏与无 PHI

- 统一入口：`complicationNotes`、`patientFeedback`、`adjustments[].description` 与 `drug`
  在落库、回显与写审计前都经 `PhiMasker` 脱敏（评审 B-1/S-1）；空白文本归一为 null。
- 手机号：容忍 `+86`/`0086` 前缀、空格、短横线、括号，按 11 位数字识别并整段遮蔽
  （`+8613800138000`、`138-0013-8000`、`138 0013 8000`、`(138) 0013-8000`
  → `138****8000`）。
- 身份证号：容忍空格/短横线，18 位（末位可为 `X/x`，保留前 6 后 4）与 15 位旧版
  （保留前 6 后 3）；如 `110101 19900307 4567 → 110101********4567`、
  `110101 900307 456 → 110101******456`。
- 识别顺序为 18 位身份证 → 15 位身份证 → 手机号；日期/剂量/订单号等普通数字（<11 位或
  非 `1[3-9]` 开头）保持不变，已脱敏串（如 `138****8000`）不会二次遮蔽。
- 局限：姓名、住院号无固定格式，**无法可靠自动识别**；前端必须提示“不得录入姓名/住院号/
  身份证号/手机号”，是否在 M3-QA 上线前置接入院内 DLP/姓名识别待确认（§16.9 Q-10，
  关联 Q-5）。日志与审计不得输出原文。
- 审计（与业务同事务）：

| 动作 | 资源 | detail 白名单 |
|---|---|---|
| `FOLLOWUP_CREATE` | `FOLLOWUP_RECORD` / 随访标识 | followupId/recordId/followupType/assessedAt/nrsScore/ponvScore/complicationCodes/hasComplicationNotes/hasPatientFeedback |
| `PAIN_MANAGE` | `PAIN_MANAGEMENT` / 镇痛标识 | painManagementId/followupId/recordId/modality/drug/bolusDose/backgroundDose/lockoutMinutes/effect/adjustmentCount |

不含患者姓名/证件号/住院号/手机号，**不含并发症说明、患者反馈与调整说明原文**；拒绝路径
（记录未结束）以独立事务写 `result=0`，只含拒绝码。

### 16.6 不可变与并发（V19 触发器）

- `followup_record` / `pain_management`：UPDATE/DELETE/TRUNCATE 一律 55000（append-only）；
  父表 `followup_record` 被 `pain_management` 外键引用，`TRUNCATE ... CASCADE` 仍由两张表的
  BEFORE TRUNCATE 触发器以 55000 拒绝（集成测试覆盖，评审 S-5）。
- `followup_record` BEFORE INSERT 守卫：父麻醉记录单必须存在、`patient_id` 与父记录一致
  （防止直插串患者，评审 S-4）、`ended_at` 非空且 `assessed_at ≥ ended_at`，违反 55000
  （服务层 422/409/400 的第二道防线）。
- 随访/镇痛均无更新接口；`version` 为通用审计字段，append-only 语义下恒为 1。
- 并发：随访录入锁记录单行；镇痛追加仅 INSERT，不需要版本条件更新。
- 运行账号不得拥有 DDL 权限（否则可 DISABLE TRIGGER 绕过，见 `docs/db/README.md` §5.3）。

### 16.7 前端

- 路由 `/m2/followups/:recordId`（记录页「术后随访」按钮按 `m2:followup:read` 显示）。
- 页面：随访列表 + 详情（NRS/PONV/并发症/说明/反馈）+ 脱敏提示；录入对话框（H24/H48、
  时间、评分、多选并发症、说明、反馈、随访人）；镇痛记录表 + 追加对话框
  （方式/药物/bolus/background/lockout/效果/调整说明）。
- 前端只做必填与物理范围预校验（bolus > 0、lockout > 0 等），最终以服务端 400/409 为准；
  不自行判断临床阈值/PCA 处方。403/404/网络错误展示契约 `code/message/traceId`。

### 16.8 测试

- `FollowupServiceImplTest`（20）：脱敏落库 + 审计无原文、药物名称脱敏（评审 S-1）、记录未
  结束 409 + result=0 审计、不可见 422、时间早于结束/未来 400、同类型时间单调 400（同刻
  允许）、NRS 0/10 与 PONV 0/3 边界、越界 400、并发症去重与上限、随访人/文本长度 400、
  列表/详情 403/404、镇痛参数（bolus/background/lockout）400、调整记录时间/长度/上限 400、
  镇痛审计无原文。
- `PhiMaskerTest`（6，独立纯函数用例，评审 S-5）：`+86`/空格/短横线/括号手机号、18 位含 X
  与 15 位身份证号、自由文本夹带姓名、日期/剂量/订单号不误伤、已脱敏串不二次遮蔽、
  `containsMaskableIdentifier` 正负例、null/空白。
- `FollowupPainApiIntegrationTest`（4，真实嵌入式 PG16）：随访→镇痛→列表/详情、分隔写法
  文本脱敏（响应与数据库均不含原始串，`PhiMasker.containsMaskableIdentifier(DB 值)=false`）、
  药物与调整说明脱敏、审计 2 条且 detail 无 PHI、append-only 55000（随访/镇痛
  UPDATE/DELETE/`TRUNCATE ... CASCADE`）、`patient_id` 与父记录不一致直插 55000、NRS/PONV
  边界与越界 400、同类型时间单调 400、记录未结束 409 + result=0 审计 + 数据库直插 55000、
  镇痛参数 400 与多次追加、权限 403、跨科室 404/403/422、V19 结构/约束/触发器/权限/注释与
  CHECK 第二道防线。
- 前端 `FollowupView.spec.ts`（6）：加载列表/详情/镇痛与脱敏提示、只读权限隐藏写入入口、
  录入随访提交体与刷新、必填拦截、409 展示服务端错误、镇痛参数校验与提交刷新。
  E2E `m2-followup.spec.ts`：空态→录入 H48 随访→追加 PCIA 镇痛→列表展示。

### 16.9 Q 登记（待临床确认，只登记不改实现）

| 编号 | 事项 | 当前实现 | 待确认点 / 决策人 |
|---|---|---|---|
| Q-1 | 随访窗口与超时提醒 | 只按 H24/H48 记录；无窗口容差、无待办提醒 | 允许提前/延后范围、超时升级规则（麻醉科/护理部/M7） |
| Q-2 | PCA 参数默认值与剂量单位 | 仅物理校验；无默认方案/单位换算 | 各模式默认 bolus/background/lockout、单位（麻醉科/设备科/M5） |
| Q-3 | 并发症字典 | 演示 13 项白名单 | 院方并发症/不良事件字典与是否对接 M7（麻醉科/质控） |
| Q-4 | NRS/PONV 量表口径 | NRS 0–10、PONV 0–3 演示分级 | 量表版本与分级定义（麻醉科） |
| Q-5 | 随访角色与脱敏深度 | 授 3 个现有角色；自动遮蔽手机号/身份证号 | 病房护士是否可随访；是否接入 DLP/姓名识别（护理部/信息科） |
| Q-6 | 旧 `/follow-ups` 契约占位 | 保留未实现（含 patientName/patientNo 字段）；契约已标 `deprecated: true`，登记为**独立下线任务** | 下线/收敛到 0.10.0 端点的时间窗（M3-QA 后；需兼容性评估与契约评审） |
| Q-7 | 随访与麻醉总结/质控 | 不联动麻醉总结、不良事件上报 | 是否自动生成 M7 不良事件/质控指标（麻醉科/质控/M7） |
| Q-8 | 早于 `endedAt` 的拒绝口径（评审 Q-1 登记） | 服务层按字段校验返回 400（字段 `assessedAt`），数据库触发器 55000；不返回 409 | 是否应改为 409 `STATE_CONFLICT`（“记录未结束/时间不合法”的状态语义）由麻醉科/医务处确认 |
| Q-9 | 24/48h 窗口本期不校验（评审 Q-2 登记） | 只记录 H24/H48 并校验同类型时间单调；**不校验**随访时间与手术结束的 24/48h 偏离，无超时提醒（与 Q-1 同一议题） | 是否在 M3-QA 上线前置强制窗口容差/超时升级（麻醉科/护理部/M7） |
| Q-10 | 姓名/住院号脱敏与 DLP（评审 Q-3 登记） | `PhiMasker` 仅识别手机号/身份证号（容忍 `+86`、空格、短横线、括号）；姓名/住院号无固定格式不自动识别 | 是否在 M3-QA 上线前置接入院内 DLP/姓名识别，及阻断策略（信息科/护理部；关联 Q-5） |
