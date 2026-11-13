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

### 3.2 麻醉记录单 `anesthesia_record`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `record_id` | bigint | 是 | 主键 | §3.4.2 |
| `schedule_id` | bigint | 是 | 排程 | §3.4.2 |
| `patient_id` | bigint | 是 | 患者 | §3.4.2 |
| `status` | varchar | 是 | `DRAFT/IN_PROGRESS/LOCKED/REVISED` | §2.4.3；状态机与修订版本表【待确认】 |
| `anesthesia_type` | varchar | 是 | 麻醉方式 | §2.4.3；字典【待确认】 |
| `started_at` | timestamptz | 否 | 记录开始 | §2.4.3 |
| `ended_at` | timestamptz | 否 | 记录结束 | §2.4.3 |
| `locked_at` | timestamptz | 否 | 锁单时间 | §2.4.3 |
| `locked_by` | bigint | 否 | 锁单人 | §2.4.3 |
| `signature_status` | varchar | 否 | CA 签名状态 | §3.9.4；`SIGNED/PENDING/FAILED` |
| `record_hash` | varchar | 【待确认】 | 记录哈希链校验值 | §3.9.1、§3.9.4；算法与字段【待确认】 |
| 审计字段 | — | 是 | 审计字段 | §3.4.3 |
| `deleted_at` | timestamptz | 否 | 软删除 | §3.4.3 |

说明：锁单后不可直接改，修改生成修订版本并保留原值、修改人、时间、原因（§2.4.3）；三方核查未完成不允许锁单，核查记录见 3.8。

### 3.3 麻醉事件 `anesthesia_event`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `event_id` | bigint | 是 | 主键 | §3.4.2 |
| `record_id` | bigint | 是 | 记录单 | §3.4.2 |
| `event_type` | varchar | 是 | 诱导/插管/切皮/拔管/出室/抢救等 | §2.4.3；枚举以契约 `AnesthesiaEvent.eventType` 为准 |
| `occurred_at` | timestamptz | 是 | 事件发生时间（服务端校准） | §3.4.5、§2.4.3 |
| `source` | varchar | 是 | `DEVICE/MANUAL` | §2.4.3；设备采集与手工补录区分 |
| `note` | text | 否 | 事件备注 | §2.4.3 |
| `entry_reason` | varchar | 否 | 补录原因（手工补录必填） | §2.4.3 |
| `recorded_at` | timestamptz | 是 | 录入时间 | §2.4.3 |
| `recorded_by` | bigint | 是 | 录入人 | §2.4.3 |

说明：事件按记录单分区、时间倒序索引（§3.4.2）；补录必须标注录入时间与原因（§2.4.3）。

### 3.4 用药记录 `medication_record`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `med_id` | bigint | 是 | 主键 | §3.4.2 |
| `record_id` | bigint | 是 | 记录单 | §3.4.2 |
| `drug_id` | bigint | 是 | 药品（目录来自药房 I-07） | §3.4.2、§2.4.6 |
| `dose` | numeric | 是 | 剂量 | §2.4.3；单位【待确认】 |
| `dose_unit` | varchar | 【待确认】 | 剂量单位 | §2.4.3；字典【待确认】 |
| `route` | varchar | 【待确认】 | 给药途径 | §2.4.3；字典【待确认】 |
| `given_at` | timestamptz | 是 | 给药时间 | §2.4.3 |
| `is_narcotic` | boolean | 是 | 是否麻精药品 | §2.4.6 |
| `first_check_by` | bigint | 否 | 双人核对第一人 | §3.9.2；字段名【待确认】，麻精必填 |
| `first_check_at` | timestamptz | 否 | 第一人核对时间 | §3.9.2 |
| `second_check_by` | bigint | 否 | 双人核对第二人 | §3.9.2；字段名【待确认】，麻精必填 |
| `second_check_at` | timestamptz | 否 | 第二人核对时间 | §3.9.2 |
| 审计字段 | — | 是 | 审计字段 | §3.4.3 |

说明：麻精药品每次使用必须关联患者、术间与处方并双人核对（§2.4.6）；空安瓿回收与余液处置字段在 M5 台账中细化。

### 3.5 生命体征 `vital_sign`

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

### 3.8 三方核查 `three_way_check`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `check_id` | bigint | 是 | 主键 | 契约 `ThreeWayCheck` |
| `record_id` | bigint | 是 | 麻醉记录单；1:1 | §2.4.3 |
| `time_out_at` | timestamptz | 是 | 切皮前 Time-out 完成时间 | §2.4.3 |
| `time_out_by` | bigint | 是 | Time-out 执行人 | §2.4.3、§3.9.2 |
| `sign_out_at` | timestamptz | 是 | 出室前 Sign-out 完成时间 | §2.4.3 |
| `sign_out_by` | bigint | 是 | Sign-out 执行人 | §2.4.3、§3.9.2 |
| `specimen_checked` | boolean | 是 | 标本核对是否一致 | §2.4.3 |
| `implant_checked` | boolean | 是 | 植入物与耗材核对是否一致 | §2.4.3 |
| 审计字段 | — | 是 | 审计字段 | §3.4.3 |

说明：三方核查是锁单前置条件，`time_out_at` 与 `sign_out_at` 均记录后才允许锁单（§2.4.3）；记录单接口通过 `threeWayCheck*` 只读摘要字段暴露完成状态。核查记录是否独立成表【待确认】。

## 4. 与契约的对应

| 契约 Schema | 数据库实体 | 说明 |
|---|---|---|
| `Patient` | `patient` | 只读同步；脱敏字段对外 |
| `SurgeryApplication` | `surgery_application` | `applicationNo` 幂等 |
| `OperatingRoom` | `operating_room` | 静态配置；实时状态另承载 |
| `Schedule` | `schedule` | 冲突检测与版本 |
| `PreopAssessment` | `m2_anes.preop_assessment` | 评分输入/结果、麻醉计划、最终化（V10） |
| `PreopVisit`（0.2.0 骨架） | 未落表 | 保留兼容，计划 1.0.0 收敛 |
| `AnesthesiaRecord` | `anesthesia_record` | 状态机与锁单 |
| `ThreeWayCheck` | `three_way_check` | 三方核查，锁单前置条件 |
| `AnesthesiaEvent` | `anesthesia_event` | 事件时间/录入时间分离 |
| `FollowUp` | `follow_up` | 24/48 小时随访 |
| `CurrentUser`、`DataScope` | `sys_user`、`sys_role_data_scope` 等 | 权限投影，不是单表 |
| `Session` | 会话存储（Redis 7.2） | 具体键设计【待确认】，不落 PostgreSQL |

## 5. 【待确认】汇总

1. 患者编号、住院号格式与唯一性；`gender`/`birth_date` 编码与返回精度。
2. 术间设备配置结构、实时状态承载、隔离字段命名。
3. 角色/权限/数据范围四张表的最终表名、权限点编码规范。
4. 排程 `sequence_no` 唯一约束、跨天排程与改台记录是否独立成表。
5. 麻醉记录单修订版本表、`record_hash` 算法与 CA 签名字段；三方核查是否独立成表。
6. DASI 评分范围（V10 起按通行 0–58.2 实现）与院方口径、功能容量 <4 METs 切点，待临床确认。
7. 用药剂量单位/途径字典、麻精双人核对签名字段与空安瓿台账。
8. 生命体征指标编码、单位、采样间隔、波形存储（D-04）。
9. 复苏记录评分项与转出阈值、随访量表口径。
