# 核心实体与字段初稿（M0 / M1 / M2）

> **初稿，随迁移/契约演进。** 字段只写方案书或契约已确认的内容；未确定处标【待确认】，不猜测落库。
>
> 类型为 PostgreSQL 16 建议类型；主键雪花 ID 在接口层序列化为字符串。**字段名为按 §3.4.3 命名约定给出的建议名**，方案书未逐字给出的在“来源/备注”列标【待确认】。所有业务表默认带审计字段与 `deleted_at`（§3.4.3），下表不再逐行重复时会在实体说明中注明。

## 1. M0 公共支撑

### 1.1 患者 `patient`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `patient_id` | bigint | 是 | 主键，雪花 ID | §3.4.2 |
| `patient_no` | varchar | 是 | 院内患者编号 | 契约 `Patient.patientNo`；格式与唯一性【待确认】 |
| `name` | varchar | 是 | 患者姓名（直接标识） | §3.9.3；按角色脱敏展示 |
| `gender` | varchar | 【待确认】 | 性别 | 契约暂列；编码字典【待确认】 |
| `birth_date` | date | 【待确认】 | 出生日期 | 契约仅详情返回，列表不返回；精度【待确认】 |
| `id_card_cipher` | text | 否 | 证件号密文（AES-256-GCM） | §3.9.3；接口返回 `idCardMasked`（前 3 + 11\* + 后 4，不含出生日期段） |
| `id_card_hash` | varchar | 否 | 证件号哈希，供精确匹配 | §3.9.3 |
| `phone_enc` | text | 否 | 手机号密文 | §3.9.3 |
| `phone_hash` | varchar(128) | 否 | 手机号 HMAC-SHA-256 哈希（精确检索/去重） | §3.9.3；字段口径与 `sys_user.phone_hash` 一致 |
| `inpatient_no` | varchar | 否 | 住院号（直接标识） | §2.7.1、§3.4.2；按数据权限控制并记审计 |
| `department_id` | bigint | 是 | 所属科室 | §3.9.2 |
| `site_id` | bigint | 是 | 院区 | §3.4.5 |
| `tenant_id` | bigint | 是 | 租户 | §3.4.5 |
| 审计字段 | — | 是 | `created_by/created_at/updated_by/updated_at` | §3.4.3 |
| `deleted_at` | timestamptz | 否 | 软删除时间 | §3.4.3 |

说明：患者主索引以 HIS 为权威源（I-01a），本系统只读同步；证件号、手机号只存密文与哈希，接口返回脱敏值（§3.9.3）。列表接口返回 `PatientSummary`，不含 `birthDate` 与住院号明文；详情接口返回 `Patient`，住院号为 `x-restricted` 字段（仅授权角色、传输加密、展示脱敏、访问记审计）。

### 1.2 术间 `operating_room`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `room_id` | bigint | 是 | 主键 | §3.4.2 |
| `room_no` | varchar | 是 | 术间编号，院内唯一 | §3.4.2、§3.4.5 |
| `room_name` | varchar | 【待确认】 | 术间名称 | 契约字段；命名与必填【待确认】 |
| `site_id` | bigint | 是 | 院区 | §3.4.5 |
| `tenant_id` | bigint | 是 | 租户 | §3.4.5 |
| `isolation_required` | boolean | 【待确认】 | 是否要求隔离 | §3.4.2“含隔离与设备配置”；字段名【待确认】 |
| `equipment_config` | jsonb | 【待确认】 | 设备依赖配置 | §3.4.2；JSONB 结构或独立关联表【待确认】 |
| 审计字段 | — | 是 | 审计字段 | §3.4.3 |
| `deleted_at` | timestamptz | 否 | 软删除 | §3.4.3 |

说明：术间为静态数据（30 条量级）；实时状态（空闲/准备/麻醉中/手术中/复苏/清洁）属于运行态，不落在本静态表，具体承载方式【待确认】（§2.4.2、§3.4.2）。

### 1.3 用户与权限 `sys_user` / `sys_role` / `sys_permission` / `sys_role_data_scope`

| 实体 | 关键字段 | 说明 | 来源/备注 |
|---|---|---|---|
| `sys_user` | `user_id`、`username`、`display_name`、`staff_id`、`status`、`password_hash`、`phone_enc`、`phone_hash`、`must_change_password`、审计字段、`deleted_at` | 账号；与 M3 人员档案关联；`phone_enc` 密文、`phone_hash` 精确检索（V3 已加列，写路径 M1） | §2.4.1、§3.9.2、§3.9.3；口令哈希算法【待确认】 |
| `sys_role` | `role_id`、`role_code`、`role_name`、`status`、审计字段 | 角色 | §3.9.2 |
| `sys_permission` | `permission_id`、`permission_code`、`permission_type`、`parent_id`、审计字段 | 权限点，粒度到“菜单 + 操作” | §3.9.2；编码规范【待确认】 |
| `sys_role_permission` | `role_id`、`permission_id` | 角色—权限点关联 | §3.9.2 |
| `sys_role_data_scope` | `role_id`、`scope_type`、`scope_id`、审计字段 | 数据范围：全院/科室/术间/本人患者 | §3.9.2 |

说明：表名为建议名，最终以 M0 迁移为准【待确认】；权限变更即时生效，审计不可删（§3.4.1）。

### 1.4 审计日志 `audit_log`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `audit_id` | bigserial | 是 | 主键（日志表用 bigserial） | §3.4.3 |
| `user_id` | bigint | 是 | 操作人 | §3.9.1 |
| `action` | varchar | 是 | 操作类型 | §3.9.1 |
| `object_type` | varchar | 是 | 对象类型 | §3.9.1 |
| `object_id` | varchar | 是 | 对象标识 | §3.9.1 |
| `client_ip` | inet | 否 | 客户端 IP | §3.9.1 |
| `occurred_at` | timestamptz | 是 | 操作时间 | §3.9.1 |
| `before_value` | jsonb | 否 | 变更前值（脱敏） | §3.9.1；脱敏规则【待确认】 |
| `after_value` | jsonb | 否 | 变更后值（脱敏） | §3.9.1；脱敏规则【待确认】 |
| `trace_id` | varchar | 否 | 链路追踪标识 | 契约 `Error.traceId` 关联 |

说明：只追加、不可删；按月分区，在线保留 12 个月（≥6 个月合规要求），归档到 MinIO（§3.4.4、§3.9.1）。

## 2. M1 手术排程

> 已实现：V7（2026-11-13）落地 `m1_sched.patient_snapshot`、`surgery_application`、
> `application_status_history` 三表，对应契约 `scheduling.yaml` / `patient.yaml` 0.3.0。
> 排程/台次（`schedule`）与排程变更仍待 M2-M2-01，下表保留方案书建议口径。

### 2.1 患者排程快照 `m1_sched.patient_snapshot`（V7 已实现）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | uuid | 是 | 主键（gen_random_uuid） |
| `his_patient_no` | varchar(64) | 是 | HIS 患者号，唯一（入站幂等键）；编号统一口径 |
| `name` | varchar(100) | 是 | 患者姓名（展示用；仅 SYNTHETIC 测试数据） |
| `gender` | varchar(16) | 是 | MALE/FEMALE/UNKNOWN（CHECK） |
| `birth_date` | date | 否 | 出生日期；HIS 缺失时拒绝创建/导入（400），不用 now() 伪造 |
| `age` | integer | 否 | 年龄 0–150；只取 HIS 值，缺失保持 null |
| `inpatient_no_masked` | varchar(64) | 是 | 脱敏住院号（前 4 + **** + 后 4），明文禁止入库 |
| `inpatient_no_hash` | varchar(128) | 是 | 住院号 HMAC-SHA-256，唯一；pepper 走 `ANESOP_INPATIENT_PEPPER` |
| `dept_name` | varchar(100) | 是 | 患者所属科室名称 |
| `dept_id` | uuid | 否 | 逻辑外键 → `m0_platform.sys_org.id`（DEPT 数据范围，不建物理外键） |
| `ward` / `bed_no` | varchar | 否 | 病区 / 床号 |
| `site_id` | uuid | 否 | 逻辑外键 → `m0_platform.sys_org.id`（SITE 数据范围） |
| 审计字段 | — | 是 | `created_at/created_by/updated_at/updated_by/version/deleted` |

约束：`uk_patient_snapshot_his_patient_no`、`uk_patient_snapshot_inpatient_no_hash` 唯一；
`dept_id`/`site_id`/`deleted` 建索引；不存身份证、手机号（含脱敏值）。

### 2.2 手术申请 `m1_sched.surgery_application`（V7 已实现）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | uuid | 是 | 主键 |
| `his_application_no` | varchar(64) | 是 | HIS 申请号，唯一（`uk_surgery_application_his_no`，入站幂等键） |
| `patient_id` | uuid | 是 | FK → `m1_sched.patient_snapshot.id`（同 schema 物理外键，ON DELETE RESTRICT） |
| `surgery_name` | varchar(200) | 是 | 拟施术式 |
| `surgery_code` | varchar(64) | 否 | 术式编码 |
| `urgency` | varchar(16) | 是 | ELECTIVE/URGENT/EMERGENCY（CHECK） |
| `planned_duration_min` | integer | 是 | 预计时长 1–1440（CHECK） |
| `dept_name` | varchar(100) | 是 | 申请科室名称（取自患者快照） |
| `dept_id` / `site_id` | uuid | 否 | 逻辑外键 → `m0_platform.sys_org.id`（数据范围，不建物理外键） |
| `surgeon_name` | varchar(100) | 否 | 术者（演示文本） |
| `preop_diagnosis` | varchar(500) | 否 | 术前诊断；不得入日志/审计 |
| `status` | varchar(16) | 是 | SUBMITTED/ACCEPTED/SCHEDULED/CANCELLED/REJECTED（CHECK） |
| `submitted_at` | timestamptz | 是 | 提交时间（列表默认倒序） |
| `source` | varchar(16) | 是 | HIS / MANUAL（CHECK） |
| `remark` | varchar(500) | 否 | 备注（演示文本） |
| 审计字段 | — | 是 | `created_at/created_by/updated_at/updated_by/version/deleted` |

约束/索引：患者/状态/紧急度/科室/院区/提交时间/状态+提交时间/deleted 索引；
状态机 `SUBMITTED → ACCEPTED → SCHEDULED`，`CANCELLED/REJECTED` 终态。

### 2.3 申请状态历史 `m1_sched.application_status_history`（V7 已实现，append-only）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | uuid | 是 | 主键 |
| `application_id` | uuid | 是 | FK → `m1_sched.surgery_application.id`（ON DELETE RESTRICT） |
| `from_status` | varchar(16) | 否 | 变更前状态；首次创建为 null |
| `to_status` | varchar(16) | 是 | 变更后状态（CHECK） |
| `reason_code` | varchar(64) | 否 | 原因码（字典值）；取消时必填，创建/导入为空 |
| `reason` | varchar(500) | 否 | 原因说明；与 `reason_code` 分列，避免拼接超长 |
| `operator_id` | uuid | 否 | 逻辑外键 → `m0_platform.sys_user.id` |
| `occurred_at` | timestamptz | 是 | 状态变更时间 |

append-only：无 `updated_at/updated_by/deleted`；`trg_application_status_history_no_update/
no_delete/no_truncate` 触发器拒绝改写（SQLSTATE 55000），更正只能追加新事件。
索引：`(application_id, occurred_at)`、`occurred_at DESC`。

### 2.4 排程/台次 `schedule`（待 M2-M2-01，未建表）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `schedule_id` | uuid | 是 | 主键（按 V7 口径 UUID，替代雪花 bigint 建议） | §3.4.2 |
| `application_id` | uuid | 是 | 手术申请 | §3.4.2 |
| `room_id` | uuid | 是 | 术间 | §3.4.2 |
| `sequence_no` | smallint | 是 | 台次序号 | §3.4.2；唯一约束【待确认】 |
| `planned_start` / `planned_end` | timestamptz | 是 | 计划起止 | §3.4.2 |
| `actual_start` / `actual_end` | timestamptz | 否 | 实际起止 | §3.4.2 |
| `status` | varchar | 是 | 排程状态 | 契约枚举；状态机【待确认】 |
| `priority` | varchar | 是 | 择期/紧急/急诊 | §2.4.2；急诊优先于择期 |
| `change_reason` | varchar | 否 | 最近改台/停台原因 | §3.4.2 |
| `version` | integer | 是 | 乐观锁 | §3.4.3 |
| 审计字段 | — | 是 | 审计字段 | §3.4.3 |
| `deleted_at` | timestamptz | 否 | 软删除 | §3.4.3 |

约束：同术间时段不得重叠；冲突不得强行保存，覆盖须审批留痕（§3.4.1、§2.4.2）。
排程变更 `schedule_change` 仍为建议表，是否独立【待确认】。

## 3. M2 麻醉信息

### 3.1 术前访视与评估 `m2_anes.preop_assessment`（V10 已实现，M2-M4-01）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键 | 契约 `PreopAssessment.assessmentId` |
| `application_id` | uuid | 是 | 手术申请（物理 FK + 逻辑外键） | §2.4.3；可见性沿用 M1 申请数据范围 |
| `patient_id` | uuid | 是 | 患者标识 | 与申请患者一致；不存住院号明文 |
| `patient_snapshot` | jsonb | 是 | 患者快照（院内患者号/姓名/性别/年龄/住院号脱敏值/科室病区床号） | §3.9.3；无证件号/手机号；created 时由 M1 申请服务端回填 |
| `history_summary` | text | 是 | 病史要点 | §2.4.3；自由文本，禁止入日志/审计 |
| `asa_class` | varchar(8) | 是 | ASA 分级 I–VI | §2.4.3、glossary |
| `mallampati` | varchar(8) | 是 | 气道 Mallampati 分级 I–IV | §2.4.3、glossary |
| `airway_details` | jsonb | 是 | 张口度 cm/颈椎活动度/气道备注 | §2.4.3；描述性字段不评分 |
| `score_input` | jsonb | 是 | RCRI 6/Apfel 4/STOP-BANG 8/DASI 12 项勾选 | 复算与审计事实源 |
| `rcri_score` / `apfel_score` / `stopbang_score` | smallint | 是 | 服务端计算的分数（0–6 / 0–4 / 0–8） | §2.4.3；客户端不得直接写 |
| `dasi_score` | numeric(5,2) | 是 | DASI 指数 0–58.2（12 项权重） | §2.4.3；院方口径【待确认】 |
| `rcri_risk` / `apfel_risk` / `stopbang_risk` / `risk_level` | varchar(16) | 是 | 各量表与综合风险分级 LOW/MODERATE/HIGH | 综合规则【待确认】 |
| `functional_capacity_low` | boolean | 是 | DASI 估算 <4 METs | 切点【待确认】，不参与综合风险 |
| `calculator_version` | varchar(16) | 是 | 量表实现版本 | 复算依据，当前 1.0.0 |
| `scores` | jsonb | 是 | 服务端评分结果快照（只读） | 含分数/分级/计算时间/版本 |
| `plan_type` | varchar(32) | 是 | 麻醉计划类型 | GENERAL/REGIONAL/COMBINED/MAC/SEDATION |
| `airway_plan` | varchar(500) | 否 | 气道方案 | 描述性文本 |
| `plan_notes` | text | 否 | 计划备注 | 禁止入日志 |
| `status` | varchar(16) | 是 | DRAFT/FINALIZED | 最终化后只读，只能新建版本；DB 触发器拒绝最终化行 UPDATE |
| `finalize_note` | varchar(500) | 否 | 最终化说明 | 只落库，不写入日志/审计 |
| `finalized_at` / `finalized_by` | timestamptz / uuid | 否 | 最终化时间/操作人 | 与 status 一致性 CHECK |
| 审计字段 | — | 是 | created_at/by、updated_at/by、version、deleted | §3.4.3 |

说明：旧契约 `PreopVisit`（0.2.0 骨架）保留但未落表；新接口使用 `PreopAssessment`（0.3.0）。
评分项与阈值可按院方口径配置（§2.4.3）：当前以 `anesop.m2.preop.*` 配置属性实现
（RCRI/Apfel/STOP-BANG 三级切点、DASI <4 METs 换算、量表版本），阈值配置表结构【待确认】；
评分口径与 Q 登记详见 `docs/modules/m2/README.md` §4、§10。

### 3.2 麻醉记录单 `m2_anes.anesthesia_record`（V13 已实现，M3-M2-01）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键（gen_random_uuid） | §3.4.2；接口 `recordId` |
| `schedule_id` | uuid | 是 | 排程台次（**唯一**，一个台次一张记录单） | §2.4.3；`uk_anesthesia_record_schedule` |
| `application_id` | uuid | 是 | 手术申请（由台次解析，不信任客户端） | §3.4.2 |
| `patient_id` | uuid | 是 | 患者标识（不存姓名/证件号/住院号明文） | §3.9.3 |
| `room_id` | uuid | 否 | **V16**：创建时经 m1-api 快照的术间；采集落库事件按它匹配 ONGOING 记录（不跨模块读 m1 表） | §2.4.3、ADR-0017；`idx_anesthesia_record_room_active` |
| `status` | varchar(16) | 是 | `DRAFT/ONGOING`（契约映射 `DRAFT/IN_PROGRESS`） | §2.4.3；锁单 `LOCKED/REVISED` 由 M3-M2-03 新迁移扩展 |
| `anesthesia_type` | varchar(16) | 是 | `GENERAL/REGIONAL/COMBINED/LOCAL/SEDATION` | §2.4.3；未锁定可改 |
| `started_at` | timestamptz | 否 | 记录开始（首个 `ANESTHESIA_START` 事件时间） | §2.4.3；DRAFT 为 NULL |
| `ended_at` | timestamptz | 否 | 记录结束（`ANESTHESIA_END` 事件时间） | §2.4.3；不得早于开始 |
| `version` | bigint | 是 | 乐观锁版本（从 1 起，更新必须回传） | §3.4.3 |
| 审计字段 | — | 是 | `created_at/created_by/updated_at/updated_by` | §3.4.3 |
| `deleted` | boolean | 是 | 逻辑删除（本期不提供删除接口） | §3.4.3；触发器拒绝物理删除 |

说明：锁单后不可直接改，修改生成修订版本并保留原值、修改人、时间、原因（§2.4.3），锁单/修订表与 `record_hash` 算法【待确认】（M3-M2-03）；三方核查未完成不允许锁单（集成点见 `docs/modules/m2/README.md` §5.4）。

### 3.3 时间轴事件 `m2_anes.record_event`（V13 已实现，append-only）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键 | §3.4.2；接口 `eventId` |
| `record_id` | uuid | 是 | 所属记录单（FK） | §3.4.2 |
| `seq` | integer | 是 | 记录单内创建序号（从 1 起，`(record_id, seq)` 唯一） | 同一 `occurred_at` 按 seq 升序，保证时间轴稳定 |
| `event_type` | varchar(32) | 是 | `ANESTHESIA_START/INDUCTION/INTUBATION/INCISION/CUSTOM/EXTUBATION/ANESTHESIA_END` | §2.4.3；CHECK 约束 |
| `occurred_at` | timestamptz | 是 | 事件发生时间（不得早于记录开始） | §3.4.5、§2.4.3 |
| `source` | varchar(16) | 是 | `MANUAL/DEVICE` | §2.4.3；设备采集由 M3-M8-01 接入 |
| `payload` | jsonb | 是 | 结构化明细（`note`/`entryReason` 等），不得含患者直接标识 | §2.4.3；jsonb 对象 CHECK |
| `created_at` | timestamptz | 是 | 录入时间（与 `occurred_at` 分离） | §2.4.3 |
| `created_by` | uuid | 否 | 录入人 | §2.4.3 |

说明：append-only（触发器拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000）；修正必须新增事件并在 `payload.entryReason` 说明。`entryReason` 为**条件必填**：`occurred_at` 早于该记录单最新事件时间、或早于服务端当前时间 − 容差（默认 600 秒）时必须填写，否则 400；未来时间超过 now + 容差同样 400。契约 `AnesthesiaEvent`（0.4.0 兼容）只映射经典类型，CUSTOM→OTHER；完整时间轴走 `RecordEvent`。事件按 `(record_id, occurred_at, seq)` 建索引，按记录单分区留待采集量评估【待确认】。

### 3.4 用药记录 `m2_anes.drug_administration`（V13 已实现，append-only）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键 | §3.4.2；接口 `drugId` |
| `record_id` | uuid | 是 | 记录单（FK） | §3.4.2 |
| `drug_name` | varchar(200) | 是 | 药品名称（院内字典名，不含患者标识） | §3.9.3；药品目录编码对接药房 I-07【待确认】 |
| `dose` | numeric(10,3) | 是 | 剂量（必须 > 0） | §2.4.3；单位见 `unit` |
| `unit` | varchar(16) | 是 | 剂量单位（mg/ug/ml 等） | 单位字典【待确认】 |
| `route` | varchar(32) | 是 | 给药途径（IV/INHALATION/EPIDURAL 等） | 途径字典【待确认】 |
| `administered_at` | timestamptz | 是 | 给药时间（与录入时间分离） | §2.4.3 |
| `narcotic` | boolean | 是 | 是否麻精药品 | §2.4.6 |
| `second_checker_id` | uuid | 否 | 第二核对人；麻精必填、非麻精必须为空 | §3.9.2；CHECK 约束 |
| `second_checker_name` | varchar(64) | 否 | 第二核对人姓名（只落库，不入日志/审计） | §3.9.3 |
| `note` | varchar(500) | 否 | 备注（不得含患者直接标识） | — |
| `created_at` | timestamptz | 是 | 录入时间 | §3.4.3 |
| `created_by` | uuid | 否 | 给药人（核对人不得与其相同，CHECK） | §3.9.2 |

说明：append-only；麻精药品必须双人核对（`ck_drug_administration_narcotic_checker`）且同人核对被拒绝（`ck_drug_administration_second_checker_distinct`），服务层先返回 400。空安瓿回收、余液处置、处方号与批号/效期在 M5 药事台账细化【待确认】。

### 3.4.1 输液/出入量 `m2_anes.fluid_record`（V13 已实现，append-only）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键 | 接口 `fluidId` |
| `record_id` | uuid | 是 | 记录单（FK） | §3.4.2 |
| `direction` | varchar(8) | 是 | `INTAKE/OUTPUT`（CHECK） | §2.4.3 |
| `fluid_type` | varchar(64) | 是 | 液体/出量类型（晶体液/胶体液/尿量等） | §2.4.3；字典【待确认】 |
| `amount_ml` | integer | 是 | 量（毫升，必须 > 0） | §2.4.3 |
| `recorded_at` | timestamptz | 是 | 记录时间（缺省取服务端时间） | §2.4.3 |
| `note` | varchar(200) | 否 | 备注 | — |
| `created_at` / `created_by` | timestamptz / uuid | 是/否 | 录入时间与录入人 | §3.4.3 |

说明：append-only；**不落冗余合计列**，出入量汇总由服务端按明细聚合（`balanceMl = intakeMl - outputMl`，契约 `IntakeOutputSummary`），避免合计与明细不一致。

### 3.4.2 预警规则 `m2_anes.alert_rule`（V15 已实现；V16 增待确认标记，M3-M2-02）

规则版本化：同一 `code` 可有多个 `version`，仅一个 `enabled`（部分唯一索引
`uk_alert_rule_enabled_code`）；保存规则 = 插入 `version+1` 新行并停用旧行，配置列/版本号
不可原地修改（触发器拒绝，55000），DELETE/TRUNCATE 拒绝。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | uuid PK | 规则版本行标识 |
| `code` | varchar(64) | 规则编码（`[A-Z][A-Z0-9_]`，如 `SPO2_LOW`），唯一键 `(code, version)` |
| `name` | varchar(200) | 中文名称 |
| `metric` | varchar(16) | `HR`/`SPO2`/`MAP`/`ETCO2`/`TEMP`（MAP 由无创血压推导） |
| `operator` | varchar(16) | `LT`/`GT`/`OUT_OF_RANGE`；等于阈值不触发 |
| `threshold_low` / `threshold_high` | numeric(8,2) | 与算子自洽（CHECK `ck_alert_rule_thresholds`） |
| `duration_seconds` | integer | 连续越界秒数 0–3600（0=立即） |
| `severity` | varchar(16) | `INFO`/`WARN`/`CRITICAL` |
| `version` | integer | 规则版本（从 1 只增）；同时作为 PUT 乐观锁 |
| `enabled` | boolean | 是否启用；停用生成新版本 |
| `pending_confirmation` | boolean | **V16**：阈值/持续时长变更后的临床待确认标记；仅名称/严重度/启用变化沿用上一版本；默认种子全为 true |
| 审计列 | — | `created_at/by`、`updated_at/by`；默认种子 `created_by/updated_by` 为系统用户 UUID `00000000-0000-4000-8000-000000000000`（契约必填，不是真人） |

默认种子 5 条（SpO2<90、HR<50、HR>120、MAP<65、EtCO2 30–50）**阈值全部【待确认】**。
阈值按指标物理范围校验（SpO2 0–100、HR 0–300、MAP 0–250、EtCO2 0–150、TEMP 25–45，越界 400），
不臆造临床阈值。

### 3.4.3 告警留痕 `m2_anes.alert`（V15 已实现，append-only + 一次性处置）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | uuid PK | 告警标识 |
| `record_id` | uuid FK | 所属麻醉记录单；数据范围沿记录单 |
| `rule_id` | uuid FK | 触发时的规则版本行 |
| `rule_code` / `rule_version` | varchar(64) / integer | 规则快照（规则升级不追溯） |
| `metric` / `value` / `unit` | varchar(16) / numeric(8,2) / varchar(16) | 指标、实测值、单位 |
| `severity` | varchar(16) | 严重度快照 |
| `triggered_at` | timestamptz | 达到持续时长的那一采样时刻 |
| `dedupe_key` | varchar(160) | `recordId:ruleCode:窗口起始秒`，唯一 `uk_alert_dedupe` |
| `status` | varchar(16) | `TRIGGERED`/`ACKNOWLEDGED`/`IGNORED`，只允许一次流转 |
| `acknowledged_by/at` / `acknowledge_reason` | uuid/timestamptz/varchar(500) | 处置三件套同时有/无（CHECK） |
| 审计/版本列 | — | `created_at/by`、`updated_at/by`、`version`（确认/忽略乐观锁） |

### 3.4.4 抢救记录 `m2_anes.resuscitation_record`（V15 已实现；V16 增人员留痕，append-only）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | uuid PK | 抢救记录标识 |
| `record_id` | uuid FK | 所属麻醉记录单 |
| `resus_type` | varchar(32) | `CPR`/`AIRWAY`/`VASOPRESSOR`/`DEFIBRILLATION`/`OTHER`（【待确认】） |
| `started_at` / `ended_at` | timestamptz | 抢救时间窗（落在记录单时间窗内，服务层校验 400） |
| `event_summary` | jsonb 对象 | `{summary, interventions[]}`，不写患者直接标识 |
| `drugs` | jsonb 数组 | `[{drugName,dose,unit,route,administeredAt}]`（≤50 条） |
| `leader` | jsonb 对象（NOT NULL） | **V16**：抢救负责人 `{userId?, name}`，name 必填（CHECK `ck_resuscitation_leader`） |
| `rescuers` | jsonb 数组（NOT NULL） | **V16**：参与人员 `[{userId?, name}]`，≥1 人（CHECK `ck_resuscitation_rescuers`）；姓名只落库、不入日志/审计 |
| `outcome` | varchar(16) | `SUCCESS`/`PARTIAL`/`FAILED`/`UNKNOWN`（【待确认】） |
| `created_at/by` | timestamptz/uuid | 录入留痕 |

UPDATE/DELETE/TRUNCATE 由触发器拒绝（SQLSTATE 55000）。

### 3.4.5 告警评估水位 `m2_anes.alert_watermark`（V16 已实现）

| 字段 | 类型 | 说明 |
|---|---|---|
| `record_id` | uuid PK/FK | 麻醉记录单标识（一张记录单一行水位） |
| `evaluated_through` | timestamptz | 已评估到的最大 `measured_at`；实时评估只对水位之后的新采样判定，水位只增（GREATEST） |
| `updated_at` | timestamptz | 水位最近推进时间 |

说明：采集 ingest 成功提交后（`@TransactionalEventListener(AFTER_COMMIT)`）按记录单快照 `room_id`
匹配 `ONGOING 且 started_at IS NOT NULL` 的记录做增量评估；读取告警列表不评估，手动 evaluate
仅调试/补偿。迁移前已存在的记录单水位初始化为迁移时刻，不回评历史窗口（ADR-0017）。

### 3.5 生命体征 `m2_anes.anesthesia_vital`（V4 已实现；V14 采集接入）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `vital_id` | bigint | 是 | 主键 | §3.4.2 |
| `record_id` | bigint | 是 | 记录单 | §3.4.2 |
| `metric_code` | varchar | 是 | 指标编码（HR/SpO2/NIBP 等） | §3.4.2；编码表【待确认】 |
| `value` | numeric | 是 | 数值 | §3.6.3；单位【待确认】 |
| `unit` | varchar | 是 | 单位 | §3.6.3；单位表【待确认】 |
| `measured_at` | timestamptz | 是 | 测量时间（服务端校准） | §3.4.5、§3.6.3 |
| `source` | varchar | 是 | 设备/手工 | §3.6.3 |
| `device_id` | varchar | 否 | 采集设备标识 | §3.6.3；设备主数据在 M0/M4【待确认】 |

说明：生命体征按天分区，热分区 90 天在线（§3.4.4）；波形不与数值同表，按 D-04 二选一（TimescaleDB 或分区表 + MinIO），最终方案【待确认】。采样间隔默认 5 分钟、抢救时加密（§2.4.3）。

### 3.6 复苏记录 `pacu_record`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `pacu_id` | bigint | 是 | 主键 | 契约暂未单列；§2.7.1 |
| `record_id` | bigint | 是 | 麻醉记录单 | §2.7.1 |
| `admission_at` | timestamptz | 是 | 入 PACU 时间 | §2.4.3 |
| `aldrete_score` | smallint | 是 | Aldrete 评分 | §2.4.3、glossary |
| `complications` | text | 否 | 并发症处理 | §2.4.3 |
| `discharge_at` | timestamptz | 否 | 转出时间 | §2.4.3 |
| `disposition` | varchar | 否 | 转归（病房/ICU 等） | §2.4.3；枚举【待确认】 |
| 审计字段 | — | 是 | 审计字段 | §3.4.3 |

说明：Steward 评分、转出阈值校验等字段在 M2 细化【待确认】。

### 3.7 随访记录 `follow_up`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `follow_up_id` | bigint | 是 | 主键 | 契约 `FollowUp` |
| `record_id` | bigint | 是 | 记录单 | §2.4.3 |
| `follow_up_hours` | smallint | 是 | 24/48 小时 | §2.4.3 |
| `pain_score` | smallint | 是 | 疼痛评分 NRS 0–10 | §2.4.3；量表口径【待确认】 |
| `ponv` | boolean | 是 | 术后恶心呕吐 | §2.4.3、glossary |
| `cognition` | text | 是 | 认知状态 | §2.4.3 |
| `satisfaction` | smallint | 是 | 满意度 1–5 | §2.4.3；量表口径【待确认】 |
| `completed_at` | timestamptz | 是 | 随访完成时间 | §2.4.3 |
| 审计字段 | — | 是 | 审计字段 | §3.4.3 |

说明：随访任务由 M2 生成并超时提醒；PCA 与神经阻滞随访字段在 M2 细化【待确认】。

### 3.8 三方核查 `m2_anes.three_way_check`（V11 已实现，M2-M5-01）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键（gen_random_uuid） | 契约 `ApplicationThreeWayCheck.checkId` |
| `application_id` | uuid | 是 | 手术申请（FK `m1_sched.surgery_application`） | §2.4.3、V11 |
| `check_version` | integer | 是 | 申请内版本号（从 1 递增，唯一） | append-only |
| `time_out_at` | timestamptz | 是 | 切皮前 Time-out 完成时间 | §2.4.3 |
| `time_out_by` | uuid | 是 | Time-out 执行人（用户标识） | §2.4.3、§3.9.2 |
| `sign_out_at` | timestamptz | 否 | 出室前 Sign-out 完成时间；未完成为 NULL | §2.4.3 |
| `sign_out_by` | uuid | 否 | Sign-out 执行人；与 `sign_out_at` 成对 | §2.4.3、§3.9.2 |
| `specimen_checked` | boolean | 是 | 标本核对是否一致 | §2.4.3 |
| `implant_checked` | boolean | 是 | 植入物与耗材核对是否一致 | §2.4.3 |
| `issues` | jsonb | 是 | 问题项字符串数组（最多 20 条，每条 ≤200 字） | V11 |
| 审计字段 | — | 是 | `created_at/created_by/updated_at/updated_by`（append-only，updated=created） | §3.4.3 |

说明：三方核查是锁单前置条件。本期实现**手术申请维度**的 append-only 版本化核查：每次录入新增
`check_version`，记录不可 UPDATE/DELETE/TRUNCATE（触发器 SQLSTATE 55000）；最新版本同时含
`time_out_at` 与 `sign_out_at` 视为完成，M3 锁单通过
`ThreeWayCheckService.isThreeWayCheckCompleted(applicationId)` 读取（不直接读表）。记录单
(record)维度的 `ThreeWayCheck` 契约不变，其表结构与 1:1 关系实现留 M3；两维度通过
`application_id` 关联。

### 3.9 知情同意模板 `m2_anes.consent_template`（V11 已实现，M2-M5-01）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键 | 契约 `ConsentTemplate.templateId` |
| `template_code` | varchar(64) | 是 | 模板编码（如 ANES_GENERAL） | §2.4.3 |
| `version` | integer | 是 | 模板业务版本（从 1 起）；`UNIQUE (template_code, version)` | §2.4.3 |
| `title` | varchar(200) | 是 | 模板标题 | V11 |
| `content_text` | text | 是 | 模板正文（患者签署的告知文本，不可修改） | §2.4.3 |
| `effective_at` | timestamptz | 是 | 生效时间；未生效版本不用于新签署 | V11 |
| `status` | varchar(16) | 是 | ACTIVE（可选用）/RETIRED（已停用，仅历史引用） | M2-M5-01 复审 |
| `retired_at` / `retired_by` | timestamptz / uuid | 否 | 停用时间/操作人；仅 RETIRED 有 `retired_at` | M2-M5-01 复审 |
| 审计字段 | — | 是 | `created_at/created_by/updated_at/updated_by`（内容 append-only） | §3.4.3 |

说明：模板内容不可变，新版本以新的 `(template_code, version)` 另建（触发器拒绝内容
UPDATE/DELETE/TRUNCATE）；唯一允许一次 `ACTIVE→RETIRED`（停用），停用后不可用于新签署，
历史记录仍引用旧版本。V11 内置 3 条 SYNTHETIC 演示模板；正式文本、生效日期与版本审批流
须医务处/法务确认（Q-4）。

### 3.10 知情同意记录 `m2_anes.consent_record`（V11 已实现，M2-M5-01）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键 | 契约 `ConsentRecord.consentId` |
| `application_id` | uuid | 是 | 手术申请（FK；数据范围沿申请可见性） | §2.4.3 |
| `patient_id` | uuid | 是 | 患者标识（取申请快照，无证件号/手机号） | §3.9.3 |
| `template_id` | uuid | 是 | 同意模板（FK） | §2.4.3 |
| `template_version` | integer | 是 | 签署时冻结的模板版本 | §2.4.3 |
| `status` | varchar(16) | 是 | DRAFT/WAITING_SIGN/SIGNED/DECLINED/REVOKED | M2-M5-01 |
| `signer_name` | varchar(64) | 否 | 签署人姓名（只落库，不入日志/审计） | §3.9.3 |
| `signer_relation` | varchar(32) | 否 | SELF/SPOUSE/PARENT/CHILD/LEGAL_GUARDIAN/OTHER | M2-M5-01 |
| `sign_method` | varchar(16) | 否 | PAPER/ELECTRONIC（ELECTRONIC 当前为 CA 占位） | M2-M5-01 |
| `signed_at` | timestamptz | 否 | 签署完成时间 | §2.4.3 |
| `signed_by` | uuid | 否 | 签署操作人 | §3.9.2 |
| `signature_ref` | varchar(128) | 否 | 占位签名标识 `SIM-CA-<uuid>`；无签名原文/图片 | §3.9.4、EXT-05 |
| `signature_hash` | varchar(64) | 否 | SHA-256 十六进制摘要（元数据 + 模板正文摘要，不含姓名） | §3.9.4、EXT-05 |
| `ca_status` | varchar(16) | 是 | PENDING/SIMULATED/VERIFIED/FAILED | §3.9.4 |
| `decline_reason` / `declined_at` | varchar(500) / timestamptz | 否 | 拒绝原因与时间（仅 DECLINED） | M2-M5-01 |
| `revoked_at` / `revoked_by` / `revoke_reason` | timestamptz / uuid / varchar(500) | 否 | 撤销时间/操作人/原因（仅 REVOKED，原因必填） | M2-M5-01 |
| 审计字段 | — | 是 | `created_at/created_by/updated_at/updated_by/version/deleted` | §3.4.3 |

说明：状态机 WAITING_SIGN → SIGNED | DECLINED；SIGNED → REVOKED。同一申请 + 同一模板版本
同时最多一条有效记录（`uk_consent_record_active`；拒绝/撤销后可重新知情同意并保留原记录）；
**同一申请同时最多一条 SIGNED**（`uk_consent_record_signed_application`，`WHERE status='SIGNED'`），
即“当前有效同意”，下游 M3 取 `signed_at` 最新的 SIGNED，DECLINED/REVOKED 不占位。
V11 守卫触发器保证身份列与 `signer_name/signer_relation/sign_method` 在所有状态不可变、
终态不可改、已签署只能携带原因撤销且签名留痕不可改写、DELETE/TRUNCATE 拒绝。
CA 不可用（EXT-05）：`caStatus=SIMULATED` 仅为占位留痕，不构成法律意义上的电子签名。

## 4. 与契约的对应

| 契约 Schema | 数据库实体 | 说明 |
|---|---|---|
| `Patient` | `patient` | 只读同步；脱敏字段对外 |
| `SurgeryApplication` | `surgery_application` | `applicationNo` 幂等 |
| `OperatingRoom` | `operating_room` | 静态配置；实时状态另承载 |
| `Schedule` | `schedule` | 冲突检测与版本 |
| `PreopAssessment` | `m2_anes.preop_assessment` | 评分输入/结果、麻醉计划、最终化（V10） |
| `PreopVisit`（0.2.0 骨架） | 未落表 | 保留兼容，计划 1.0.0 收敛 |
| `AnesthesiaRecord` | `m2_anes.anesthesia_record` | 按台次唯一、状态机（V13）；锁单 M3-M2-03 |
| `ApplicationThreeWayCheck` | `m2_anes.three_way_check` | 申请维度三方核查（V11，append-only 版本） |
| `ThreeWayCheck`（记录维度） | 未落表（M3） | 记录单维度核查，契约保持不变，实现留 M3 |
| `ConsentTemplate` | `m2_anes.consent_template` | 模板与版本，内容不可变（V11） |
| `ConsentRecord` | `m2_anes.consent_record` | 同意记录与签署状态机、CA 占位签名（V11） |
| `AnesthesiaEvent`（0.4.0 兼容） | `m2_anes.record_event` | 兼容视图：只映射经典事件类型（V13） |
| `RecordEvent`、`CreateRecordEventRequest` | `m2_anes.record_event` | append-only 时间轴、seq、payload jsonb（V13） |
| `DrugAdministration`、`AdministerDrugRequest` | `m2_anes.drug_administration` | 用药与麻精双人核对（V13） |
| `FluidRecord`、`RecordFluidRequest`、`IntakeOutputSummary` | `m2_anes.fluid_record` | 出入量明细与汇总（V13，汇总不落库） |
| `VitalSignSample` | 未落表（M3-M8-01） | 记录单 `vitalSigns` 占位，本期固定空数组 |
| `AlertRule`、`AlertRuleList`、`UpdateAlertRuleRequest` | `m2_anes.alert_rule` | 规则版本化、阈值/持续时长/严重度（V15） |
| `Alert`、`AlertList`、`AcknowledgeAlertRequest`、`AlertEvaluationResult` | `m2_anes.alert` | 告警留痕与一次性处置、去重键（V15） |
| `ResuscitationRecord`、`ResuscitationRecordList`、`CreateResuscitationRequest`、`ResuscitationDrug`、`ResuscitationEventSummary`、`ResuscitationParticipant` | `m2_anes.resuscitation_record` | 抢救记录 append-only、时间窗校验、负责人/参与人员（V16） |
| `FollowUp` | `follow_up` | 24/48 小时随访 |
| `CurrentUser`、`DataScope` | `sys_user`、`sys_role_data_scope` 等 | 权限投影，不是单表 |
| `Session` | 会话存储（Redis 7.2） | 具体键设计【待确认】，不落 PostgreSQL |

## 5. 【待确认】汇总

1. 患者编号、住院号格式与唯一性；`gender`/`birth_date` 编码与返回精度。
2. 术间设备配置结构、实时状态承载、隔离字段命名。
3. 角色/权限/数据范围四张表的最终表名、权限点编码规范。
4. 排程 `sequence_no` 唯一约束、跨天排程与改台记录是否独立成表。
5. 麻醉记录单修订版本表与 `record_hash` 算法；记录单维度三方核查（M3）的表结构与
   1:1 关系；CA 有效签名（EXT-05）解除后的证书链/时间戳字段。
6. DASI 评分范围（V10 起按通行 0–58.2 实现）与院方口径、功能容量 <4 METs 切点，待临床确认。
7. 用药剂量单位/途径字典、麻精双人核对签名字段与空安瓿台账（V13 先落 `second_checker_id/name`，
   电子签名与处方关联在 M5 细化）。
7a. 记录单锁单表/修订版本表、`record_hash` 算法、事件按记录单分区策略（V13 未落，M3-M2-03
   与新迁移承接）。
8. 生命体征指标编码、单位、采样间隔、波形存储（D-04）。
9. 复苏记录评分项与转出阈值、随访量表口径。
10. 术中预警默认阈值（SpO2<90、HR<50/>120、MAP<65、EtCO2 30–50）与持续时长全部【待确认】，
    须临床顾问逐条确认；抢救类型/转归枚举（`CPR/AIRWAY/VASOPRESSOR/DEFIBRILLATION/OTHER`、
    `SUCCESS/PARTIAL/FAILED/UNKNOWN`）同为【待确认】。
