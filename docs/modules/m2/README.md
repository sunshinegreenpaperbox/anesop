# M2 麻醉信息管理（模块说明）

> 状态：M2-M4-01「术前访视与评估」+ M2-M5-01「知情同意与三方核查」落地版（2026-11-13）。
> 契约：`packages/contracts/openapi/anesthesia.yaml` 0.4.0；
> 迁移：`db/migration/V10__m2_preop_assessment.sql`、
> `db/migration/V11__m2_consent_threeway.sql`；
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

非目标（后续任务）：麻醉记录单/事件/生命体征（M2 记录，M3）、三方核查**记录单(record)
维度**实现（既有 `ThreeWayCheck` 契约保持不动，实现留 M3）、独立评分阈值配置表
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
│   ├── error/      M2ValidationException（400）、M2ConflictException（409）、
│   │               M2UnprocessableException（422），供同意/核查复用
│   └── security/   M2Permissions（m2:preop|consent|threeway:read|write）
└── impl/ com.anesop.m2.impl
    ├── preop/      PreopAssessmentServiceImpl（事务/状态机/可见性/审计）
    │               PreopScoreCalculator（RCRI/Apfel/STOP-BANG/DASI，临床规则高风险）
    ├── consent/    ConsentServiceImpl（CA 占位签名、状态机、模板版本冻结、审计）
    ├── threeway/   ThreeWayCheckServiceImpl（append-only 版本、完成判定、时间校验）
    ├── support/    AnesthesiaApplicationSummaries（复用 m1-api 申请快照，不读 m1 表）
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
└── __tests__/                    Vitest（术前访视 4 + 同意 5 + 核查 7）
```

## 3. 接口（anesthesia 0.4.0）

> 0.4.0 为 additive：新增知情同意 8 个 operation 与手术申请维度三方核查 2 个 operation，
> 未修改既有 `PreopVisit`/`ThreeWayCheck`/`UpsertThreeWayCheckRequest` 定义；
> 记录单(record)维度 `/v1/anesthesia/records/{recordId}/three-way-checks` 保持原契约，
> 实现留 M3。

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

V10/V11 只新增权限点与授权，不改写 V2 既有角色/权限数据。

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
- 前端 Vitest：术前访视 4 用例 + 知情同意 5 用例（加载/创建/签署/撤销/403）+
  三方核查 7 用例（空态/Time-out/Sign-out 完成/必选逐项确认/二次确认取消/Time-out 预填/403）；
  E2E 2 条（签署 + CA 占位、核对逐项确认 + 二次确认 + 核查完成）。
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

### 11.2 M3 锁单集成点

- “当前有效同意”（M3 读取口径）：同一申请下 `status='SIGNED'` 且 `signed_at` 最新的一条记录；
  同一申请最多一条 SIGNED 由 `uk_consent_record_signed_application` 强制，`DECLINED`/`REVOKED`
  不占位。M3 不得取 `WAITING_SIGN`、已拒绝或已撤销记录，也不得按“最新创建”推断。
- M3 锁单前必须校验：
  1. 记录单本身完成（事件/生命体征/签名）；
  2. `m2-api` 的 `ThreeWayCheckService.isThreeWayCheckCompleted(applicationId) == true`；
  3. 存在上述“最新有效 SIGNED”同意（是否阻断锁单/开场待 Q-1 确认，当前只登记）。
- 调用约定：同步调用 `m2-api` 接口（不读 `m2_anes.three_way_check` 表、不复制查询）；
  方法对非法标识/无记录返回 `false`（fail-safe）。
- 未完成时锁单返回 409（沿用 `record lock` 既有语义），并在审计 detail 记录
  `threeWayCheckCompleted=false`；M3 需补一条集成用例覆盖“核查未完成禁止锁单”。
- 记录单(record)维度的 `ThreeWayCheck` 契约/表实现留 M3；本任务只交付申请维度核查，
  两者通过 `applicationId` 关联，M3 落地时须保证完成判定口径一致（§5.2）。
