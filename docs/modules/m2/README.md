# M2 麻醉信息管理（模块说明）

> 状态：M2-M4-01「术前访视与评估」落地版（2026-11-13）。
> 契约：`packages/contracts/openapi/anesthesia.yaml` 0.3.0；
> 迁移：`db/migration/V10__m2_preop_assessment.sql`；
> 方案书：§2.4.3（M2 术前访视/风险评分/麻醉计划）、§3.4.2（麻醉记录单与访视实体）、
> §3.4.3（审计字段/乐观锁/逻辑删除）、§3.9.2（RBAC/数据权限）、§3.9.3（脱敏）。
> 全部示例、患者、申请与评分为 **SYNTHETIC** 合成数据，禁止写入真实患者数据。

## 1. 职责与边界

M2 当前交付「术前访视与评估」：把 M1 排程/申请下游的术前访视落成结构化临床记录。

- 采集病史要点、气道评估（Mallampati/张口度/颈椎活动度）、ASA 分级；
- 采集 RCRI（6 项）、Apfel（4 项）、STOP-BANG（8 项）、DASI（12 项）量表条目；
- **服务端计算**四项分数、各量表风险分级与综合风险等级，结果只读并随记录冻结；
- 记录麻醉计划（类型/气道方案/备注）；
- 草稿 → 最终化状态机：最终化后不可修改，只能为同一申请新建版本，原单保留复算快照与审计。

非目标（后续任务）：麻醉记录单/事件/生命体征（M2 记录）、知情同意与三方核查（M2-M5-01）、
独立评分阈值配置表（当前以 `anesop.m2.preop.*` 配置属性实现，院方口径与配置表结构【待确认】）、
真实 CA 签名。

模块边界：`m2-impl` 只依赖 `m2-api`、`m0-api`（认证主体/审计/数据范围）与 `m1-api`
（申请可见性）；**不访问 m1 表或 Mapper**，不跨模块传实体。`api-bootstrap` 是唯一依赖
`m2-impl` 的装配点。前端 `apps/web/src/modules/m2` 不引用其他 `modules/mX` 内部文件。

## 2. 代码结构

```text
apps/api/modules/m2/
├── api/  com.anesop.m2.api
│   ├── preop/      PreopAssessmentService、PreopValidationException（400）、
│   │               PreopConflictException（409）、PreopUnprocessableException（422）
│   └── security/   M2Permissions（m2:preop:read|write）
└── impl/ com.anesop.m2.impl
    ├── preop/      PreopAssessmentServiceImpl（事务/状态机/可见性/审计）
    │               PreopScoreCalculator（RCRI/Apfel/STOP-BANG/DASI，临床规则高风险）
    └── persistence/  PreopAssessmentEntity、PreopAssessmentMapper（jsonb CAST + 条件更新）
```

前端：

```text
apps/web/src/modules/m2/
├── api/m2.ts                    anesthesia 0.3.0 生成类型与 5 个接口
├── utils/preopScores.ts         评分实时预览（与服务端同口径，最终以服务端为准）
├── views/PreopAssessmentView.vue 病史/气道/ASA/评分/计划/最终化
├── views/PreopEntryView.vue      菜单入口（从申请进入的说明）
└── __tests__/                    Vitest（分数展示/最终化/403/预览边界）
```

## 3. 接口（anesthesia 0.3.0）

| 方法 | 路径 | operationId | 权限 | 说明 |
|---|---|---|---|---|
| GET | `/v1/anesthesia/preop-assessments?applicationId=` | `listPreopAssessments` | `m2:preop:read` | 申请上下文 + 访视单列表（创建时间倒序）；申请不可见 403（集合语义） |
| POST | `/v1/anesthesia/preop-assessments` | `createPreopAssessment` | `m2:preop:write` | 创建草稿（201）；申请不可见 422、重复草稿 409 |
| GET | `/v1/anesthesia/preop-assessments/{assessmentId}` | `getPreopAssessment` | `m2:preop:read` | 详情；越界/不存在 404 |
| PUT | `/v1/anesthesia/preop-assessments/{assessmentId}` | `updatePreopAssessment` | `m2:preop:write` | 全量保存草稿 + 重算评分；版本过期/已最终化 409 |
| POST | `/v1/anesthesia/preop-assessments/{assessmentId}/finalize` | `finalizePreopAssessment` | `m2:preop:write` | 最终化（DRAFT→FINALIZED）；已最终化/版本过期 409 |

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
- 日志不打印病史/姓名；测试与示例均为 SYNTHETIC。

## 7. 权限

| 权限点 | 授予角色 | 用途 |
|---|---|---|
| `m2:preop:read` | SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST | 列表/详情 |
| `m2:preop:write` | 同上 | 创建/更新/最终化 |

V10 只新增权限点与授权，不改写 V2 既有角色/权限数据。

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
- 前端 Vitest：术前访视 4 用例（分数展示/最终化二次确认/保存失败后成功清错误/403）+
  申请列表/详情 3 用例（术前访视入口与权限隐藏）；E2E 2 条（最终化锁定、403 权限态）。
- 实测 m2-impl 覆盖率 **LINE 94.96%（358/377）、BRANCH 74.67%（112/150）**，满足核心域 ≥80/70；
  根 pom 的 `coverage-core-m2` profile 已按 `src/main/java/com/anesop/m2/impl/package-info.java`
  存在自动激活，未修改根 pom。

## 9. 已知限制与后续

1. **数据范围沿用 M1**：V2 种子 `SYS_ANESTHESIOLOGIST` 为 SELF 范围，只能看到本人创建的申请；
   HIS/手术室创建的申请需 DEPT/ALL 或 M3 的排班/任务分派范围（M3 承接，【待确认】）。
   这是当前“从申请进入”在真实账号下的主要限制，不影响数据安全（不会放大可见范围）。
2. 评分阈值已集中到 `anesop.m2.preop.*` 配置（§4.7），但尚无独立配置表与变更审批流（§4.8-5）；
   评分口径变更必须升级 `calculator-version` 并保留旧快照。
3. 前端 `utils/preopScores.ts` 是服务端公式的镜像，仅用于实时预览；改公式必须同步两端与本文档。
4. 最终化不等于知情同意/三方核查；锁单前置联动在 M2-M5-01。
5. 旧 `preop-visits` 骨架未删除（ADR-0011 弃用窗口），计划 1.0.0 收敛。

## 10. Q 登记（待临床 / M3 确认，只登记不改实现）

| 编号 | 事项 | 当前实现 | 待确认点 / 决策人 |
|---|---|---|---|
| Q-1 | 切点与 Javadoc 引用 | RCRI `0/1/≥2`、Apfel `0–1/2/≥3`、STOP-BANG `0–2/3–4/≥5`，默认值即公开量表口径；Javadoc/契约均标【待确认】 | Lee 1999 原文报告的是 RCRI 分层 MACE 风险，常被二次文献引作 **3.9%/6.0%/≥10%**；需临床确认院方是否采用该切点与该组数字，以及 Javadoc 引用格式（临床/医务处） |
| Q-2 | “综合风险”改称“最高单项风险” | `riskLevel` 取 RCRI/Apfel/STOP-BANG 最高级；PONV（Apfel）与 OSA（STOP-BANG）**亦计入** | 是否接受该展示约定；**DASI < 34**（Wijeysundera 2020）作为功能容量切点替代当前 VO2 < 14（≈4 METs）待确认（麻醉科/临床） |
| Q-3 | 数据范围与最终化权限 | `SYS_ANESTHESIOLOGIST` 为 SELF 数据范围，只能看到本人创建申请；`m2:preop:write` 允许麻醉护士直接 finalize | SELF 限制由 **M3** 排班/任务分派范围承接；护士 finalize 是否需医师复核（双人/二次确认）待 **M3** 落实（医务处/护理部） |
