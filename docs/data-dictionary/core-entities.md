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
| `status` | varchar(16) | 是 | `DRAFT/ONGOING/LOCKED/REVISED`（契约映射 `DRAFT/IN_PROGRESS/LOCKED/REVISED`） | §2.4.3；V17 扩展；未锁单只允许 DRAFT/ONGOING，锁单只允许 `ONGOING → LOCKED` |
| `anesthesia_type` | varchar(16) | 是 | `GENERAL/REGIONAL/COMBINED/LOCAL/SEDATION` | §2.4.3；未锁定可改，锁单后不可改（更正只写入修订快照） |
| `started_at` | timestamptz | 否 | 记录开始（首个 `ANESTHESIA_START` 事件时间） | §2.4.3；DRAFT 为 NULL，写入后不可改 |
| `ended_at` | timestamptz | 否 | 记录结束（`ANESTHESIA_END` 事件时间） | §2.4.3；不得早于开始，锁单后不可改 |
| `locked_at` / `locked_by` | timestamptz / uuid | 否 | **V17** 锁单时间/操作人（同时写入，写一次不可改） | §2.4.3、§3.9.4；`ck_anesthesia_record_lock_consistency` |
| `current_revision` | integer | 是 | **V17** 当前修订版本号（未锁单 0，每次修订 +1，只增） | 对应 `record_revision.revision_no` |
| `lock_signature_ref` | varchar(128) | 否 | **V17** 锁单签名占位标识（`SIM-CA-<记录单标识>`，CA 占位 EXT-05） | §3.9.4 |
| `lock_signature_hash` | varchar(64) | 否 | **V17** 锁单签名摘要：规范化记录 JSON 的 SHA-256（不含患者标识/审计时间/签名字段） | §3.9.4；`^[0-9a-f]{64}$` |
| `lock_ca_status` | varchar(16) | 否 | **V17** 锁单 CA 状态（EXT-05 为 `SIMULATED`，解除后才有 `VERIFIED`） | §3.9.4 |
| `version` | bigint | 是 | 乐观锁版本（从 1 起，更新必须回传） | §3.4.3 |
| 审计字段 | — | 是 | `created_at/created_by/updated_at/updated_by` | §3.4.3 |
| `deleted` | boolean | 是 | 逻辑删除（本期不提供删除接口） | §3.4.3；锁单后不可删除，触发器拒绝物理删除 |

说明：锁单后不可直接改（V17 触发器 + 服务层 409 双保险），修改只能新增修订版本并保留原值、修改人、时间、原因（§2.4.3）；锁单前置（三方核查完成、当前有效 SIGNED 同意、开始/结束时间完整、至少 1 个事件）由服务层校验，未满足 409 并逐项列出。签名口径与稳定序列化见 `docs/modules/m2/README.md` §14.3。

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
| `leader` | jsonb 对象（NOT NULL） | **V16**：抢救负责人 `{userId?, name}`，name 必填（CHECK `ck_resuscitation_leader`）；锁单后禁止新增（Q-2） |
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

### 3.4.6 修订版本 `m2_anes.record_revision`（V17 已实现，append-only，M3-M2-03）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键（gen_random_uuid） | 接口 `signatureRef` 前缀使用修订标识 |
| `record_id` | uuid | 是 | 所属记录单（FK；记录必须已锁单才能新增修订） | §2.4.3 |
| `revision_no` | integer | 是 | 修订版本号（同一记录单从 1 递增，`(record_id, revision_no)` 唯一） | 服务层在记录单行锁内分配 |
| `reason_code` | varchar(32) | 是 | 白名单（0.8.1）`ANESTHESIA_TYPE_CHANGE/OTHER`；`OMISSION`/`TIMING_CORRECTION` 待结构化更正能力（Q-17） | 契约 `CreateRevisionRequest.reasonCode` |
| `reason` | varchar(500) | 是 | 修订原因说明（只落库/授权用户可见，不写日志） | §2.4.3 |
| `changes` | varchar(500) | 是 | 变更摘要（版本历史展示） | 契约 `changes` |
| `snapshot` | jsonb | 是 | 全量快照：`record/events/drugs/fluids/rescues/ioSummary/signatures`；数组按 (时间,id) 稳定排序，不含患者标识、审计时间、抢救 created_at/created_by 与签名字段本身；设备 vital_sign 明细不在范围 | 哈希输入见 §14.3 |
| `previous_revision_no` / `previous_signature_hash` | integer / varchar(64) | 否 | 上一修订版本号与摘要（首版为 NULL，后续成对） | 版本链校验；CHECK 约束 |
| `signature_ref` | varchar(128) | 是 | 修订签名占位标识（`SIM-CA-<修订标识>`，CA 占位 EXT-05） | §3.9.4 |
| `signature_hash` | varchar(64) | 是 | 修订快照规范化 JSON 的 SHA-256（`^[0-9a-f]{64}$`） | §3.9.4 |
| `ca_status` | varchar(16) | 是 | `SIMULATED`（EXT-05）/`VERIFIED`（解除后）/`PENDING`/`FAILED` | §3.9.4 |
| `created_at` / `created_by` | timestamptz / uuid | 是 | 修订时间与创建人（服务端时间/当前登录用户） | §3.4.3 |

说明：修订版本永久 append-only（UPDATE/DELETE/TRUNCATE 由触发器拒绝，SQLSTATE 55000）；原记录与历史修订不可改，首版差异对比 `previous_revision_no=0` 的锁单原始内容。`record_revision` BEFORE INSERT 链守卫校验父记录已锁单、`revision_no=current_revision+1`、`previous_signature_hash` 与上一版一致（伪造/跳号/断链 55000）。记录锁单后禁止 INSERT `record_event`/`drug_administration`/`fluid_record`/`resuscitation_record`（V17 BEFORE INSERT 触发器）。

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

### 3.6 复苏记录 `m2_anes.pacu_record`（V18 已实现，M3-M10-01）

一条麻醉记录单同时最多一条未转出记录（部分唯一索引 `uk_pacu_record_active`）；入室信息与
身份列写入后不可改，`DISCHARGED` 为终态（`READY→MONITORING` 回落是否允许待临床确认，
见 `docs/modules/m2/README.md` §15.9 Q-8）。

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键（gen_random_uuid） | 契约 `PacuRecord.pacuId` |
| `anesthesia_record_id` | uuid | 是 | 所属麻醉记录单（FK，写入后不可改） | §2.4.3；数据范围沿记录单 |
| `patient_id` | uuid | 是 | 患者标识（由记录单解析，不存姓名/证件号/住院号明文） | §3.9.3 |
| `admitted_at` | timestamptz | 是 | 入 PACU 时间（≥ 记录 startedAt） | 服务层 400 |
| `discharged_at` | timestamptz | 否 | 转出时间（写一次，与 DISCHARGED 同置） | CHECK `ck_pacu_record_discharged_state` |
| `status` | varchar(16) | 是 | `ADMITTED/MONITORING/READY/DISCHARGED` | 契约 `status` |
| `admitted_vital_signs` | jsonb | 是 | 入室生命体征对象（可缺项） | 契约 `PacuVitalSigns` |
| `consciousness` | varchar(16) | 是 | `ALERT/DROWSY/SEDATED/UNRESPONSIVE`（演示口径【待确认】） | 契约 `consciousness` |
| `pain_score` | smallint | 是 | NRS 0–10（量表口径【待确认】） | CHECK 0–10 |
| `nausea` | boolean | 是 | 入室恶心/呕吐 | 契约 `nausea` |
| `airway` | varchar(24) | 是 | `NATURAL/ORAL_AIRWAY/NASAL_AIRWAY/LARYNGEAL_MASK/ENDOTRACHEAL/TRACHEOSTOMY`（演示口径【待确认】） | 契约 `airway` |
| 审计字段 | — | 是 | `created_at/created_by/updated_at/updated_by/version/deleted` | §3.4.3 |

说明：入室生命体征/意识/疼痛/恶心/气道与身份列不可改（DB 守卫 55000），入室信息有误须重新
入室；状态推进/转出使用条件更新（`WHERE id=? AND version=? AND status<>'DISCHARGED'`），
0 行即 409。DELETE/TRUNCATE 拒绝（55000）。

### 3.6.1 Aldrete 评估 `m2_anes.pacu_assessment`（V18 已实现，append-only）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键（gen_random_uuid） | 契约 `PacuAssessment.assessmentId` |
| `pacu_id` | uuid | 是 | 所属复苏记录（FK；父记录已转出后拒插 55000） | — |
| `assessed_at` | timestamptz | 是 | 评估时间（不得早于入室时间与上一条评估） | 服务层 400 |
| `aldrete_activity` | smallint | 是 | 活动能力 0–2（0 不能活动肢体 / 1 可活动两个肢体 / 2 可活动四个肢体） | CHECK |
| `aldrete_respiration` | smallint | 是 | 呼吸 0–2（0 呼吸暂停 / 1 呼吸困难或受限 / 2 能深呼吸并有效咳嗽） | CHECK |
| `aldrete_circulation` | smallint | 是 | 循环 0–2（血压偏离术前 >50% / 20%–50% / <20%） | CHECK |
| `aldrete_consciousness` | smallint | 是 | 意识 0–2（0 无反应 / 1 呼之可醒 / 2 完全清醒） | CHECK |
| `aldrete_oxygenation` | smallint | 是 | 氧合 0–2（0 吸氧下 <90% / 1 需吸氧 >90% / 2 空气下 >92%） | CHECK |
| `aldrete_total` | smallint | 是 | 总分 0–10，**等于五项之和**（服务端计算） | CHECK `ck_pacu_assessment_total_sum` |
| `notes` | varchar(500) | 否 | 评估备注（只落库/授权用户可见，不写日志/审计原文） | 契约 `notes` |
| `created_at` / `created_by` | timestamptz / uuid | 是 | 录入时间与录入人 | §3.4.3 |

说明：append-only（UPDATE/DELETE/TRUNCATE 由触发器拒绝，55000），更正只能新增评估；时间序列
按 `assessed_at ASC, created_at ASC, id ASC` 稳定排序，latest 按
`(assessed_at, created_at, id)` 倒序取 1 条。总分达到配置阈值（`anesop.m2.pacu.aldrete-threshold`，
默认 9，院方阈值【待确认】）时推进 `pacu_record.status=READY`，否则 `MONITORING`；状态推进
以 latest 为准，`READY→MONITORING` 回落是否允许待临床确认（§15.9 Q-8）。

### 3.6.2 转出交接 `m2_anes.pacu_discharge`（V18 已实现，append-only）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键（gen_random_uuid） | 契约 `PacuDischarge.dischargeId` |
| `pacu_id` | uuid | 是 | 所属复苏记录（FK，**唯一**：每条最多一条转出） | `uk_pacu_discharge_pacu` |
| `discharged_at` | timestamptz | 是 | 转出时间（服务端时间） | — |
| `destination` | varchar(16) | 是 | `WARD/ICU/HDU/DAY_SURGERY/HOME`（演示口径【待确认】） | CHECK |
| `handover` | jsonb | 是 | SBAR：`{situation,background,assessment,recommendation}`（四段必填 ≤2000） | 契约 `PacuHandover` |
| `criteria_snapshot` | jsonb | 是 | 转出时 Aldrete 达标快照：`{aldreteThreshold,aldreteTotal,meetsThreshold,itemsComplete,latestAssessmentId,latestAssessmentAt}` | 阈值调整后可追溯当时依据 |
| `created_at` / `created_by` | timestamptz / uuid | 是 | 转出时间与操作人 | §3.4.3 |

说明：append-only（55000）；转出前置由服务端评估（存在评估、五项齐全、总分 ≥ 阈值），未满足
409 并逐项列出条件码；SBAR 原文与评估备注不写日志/审计。未处理严重告警是否阻断转出【待确认】
（未实现）。

### 3.7 术后随访 `m2_anes.followup_record`（V19 已实现，M3-M11-01，append-only）

随访只能录入到已结束（`ended_at` 非空）且 `patient_id` 与父记录一致的麻醉记录单（BEFORE
INSERT 守卫 55000）；同一记录单同一随访类型可多次随访，但 `assessed_at` 不得早于上一条
（时间序列只增），H24/H48 序列相互独立。父表被 `pain_management` 外键引用，
`TRUNCATE ... CASCADE` 仍由 append-only 触发器拒绝（55000）。

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键（gen_random_uuid） | 契约 `PostopFollowup.followupId` |
| `anesthesia_record_id` | uuid | 是 | 所属麻醉记录单（FK；必须已结束） | CHECK 由触发器守卫（55000） |
| `patient_id` | uuid | 是 | 患者标识（由记录单解析，不存姓名/证件号/住院号明文） | §3.9.3 |
| `followup_type` | varchar(8) | 是 | `H24`/`H48`（随访窗口容差【待确认】） | CHECK `ck_followup_record_type` |
| `assessed_at` | timestamptz | 是 | 随访时间（≥ 记录 ended_at；同类型不早于上一条） | 服务层 400 + 触发器守卫 |
| `nrs_score` | smallint | 是 | NRS 疼痛评分 0–10（量表口径【待确认】） | CHECK `ck_followup_record_nrs` |
| `ponv_score` | smallint | 是 | PONV 评分 0–3（演示分级【待确认】） | CHECK `ck_followup_record_ponv` |
| `complications` | jsonb | 是 | 并发症码数组（≤20，白名单见契约 `FollowupComplication`） | CHECK 数组类型/长度 |
| `complication_notes` | varchar(500) | 否 | 并发症说明（服务端脱敏后落库） | 契约 `complicationNotes` |
| `patient_feedback` | varchar(500) | 否 | 患者反馈（服务端脱敏后落库） | 契约 `patientFeedback` |
| `followed_up_by` | varchar(64) | 是 | 随访人姓名摘要（操作人另记 created_by） | 契约 `followedUpBy` |
| `created_at` / `created_by` | timestamptz / uuid | 是 | 录入时间与录入人 | §3.4.3 |
| `version` | bigint | 是 | 版本号（append-only 恒为 1，通用审计字段） | CHECK `version >= 1` |

说明：append-only（UPDATE/DELETE/TRUNCATE 由触发器拒绝，55000）；审计
`FOLLOWUP_CREATE` 只记结构化字段，不含说明/反馈原文与 PHI；手机号（容忍 `+86`/空格/
短横线/括号）、18 位（含末位 X）/15 位身份证号（容忍空格/短横线）形似串由 `PhiMasker`
遮蔽后落库。并发症字典、PONV 分级、随访窗口与超时提醒均【待确认】
（`docs/modules/m2/README.md` §16.9）。

### 3.7.1 术后镇痛 `m2_anes.pain_management`（V19 已实现，append-only）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键（gen_random_uuid） | 契约 `PainManagement.painManagementId` |
| `followup_id` | uuid | 是 | 所属随访单（FK） | 契约 `followupId` |
| `modality` | varchar(16) | 是 | `PCIA/PCEA/ORAL/IV/NERVE_BLOCK/OTHER`（演示口径【待确认】） | CHECK |
| `drug` | varchar(128) | 是 | 药物/方案名称（服务端脱敏后落库，不含患者标识） | CHECK 非空/长度；`PhiMasker` |
| `bolus_dose` | numeric(10,2) | 是 | 单次追加剂量（> 0；单位【待确认】） | CHECK `ck_pain_management_bolus` |
| `background_dose` | numeric(10,2) | 是 | 背景输注剂量（≥ 0；单位【待确认】） | CHECK |
| `lockout_minutes` | integer | 是 | 锁定时间（分钟，> 0；默认值【待确认】） | CHECK `ck_pain_management_lockout` |
| `effect` | varchar(16) | 是 | `EFFECTIVE/PARTIAL/INEFFECTIVE/NOT_ASSESSED`（演示口径【待确认】） | CHECK |
| `adjustments` | jsonb | 是 | 调整记录数组 `[{adjustedAt, description}]`（≤20；说明脱敏） | CHECK 数组类型/长度 |
| `created_at` / `created_by` | timestamptz / uuid | 是 | 录入时间与录入人 | §3.4.3 |
| `version` | bigint | 是 | 版本号（append-only 恒为 1） | CHECK `version >= 1` |

说明：append-only（55000）；同一随访可多次追加（参数调整即新增），历史不可改写；审计
`PAIN_MANAGE` 只记方式/参数/效果/调整条数，不含调整说明原文与 PHI；PCA 默认值与剂量单位
未内置【待确认】（`docs/modules/m2/README.md` §16.9 Q-2）。

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

### 3.11 药品目录 `m4_drug.drug` / `m4_drug.drug_alias`（V20 已实现，M4-M15-01）

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `id` | uuid | 是 | 主键 | 契约 `Drug.drugId` |
| `code` | varchar(64) | 是 | 院内药品编码（lower 唯一，创建后不可改） | §2.5 |
| `name` | varchar(200) | 是 | 药品名称（trim+lower 唯一；麻醉记录按名称解析麻精属性） | §2.5、T-4 |
| `generic_name` | varchar(200) | 否 | 通用名（trim+lower 部分唯一，NULL 除外；参与名称解析） | §2.5 |
| `specification` / `unit` / `dosage_form` / `category` | varchar | 是 | 规格/单位/剂型/分类（剂型与分类字典【待确认】） | §2.5 |
| `narcotic` | boolean | 是 | 是否麻精药品（**判定唯一权威**） | §2.5、ADR-0018 |
| `high_risk` | boolean | 是 | 是否高危（高警示）药品，仅提示 | §2.5 |
| `status` | varchar(16) | 是 | ACTIVE/INACTIVE（停用只读历史，不删除） | M4-M15-01 |
| `version` | integer | 是 | 乐观锁（更新必须回传） | §3.4.3 |
| 审计字段 | — | 是 | `created_at/created_by/updated_at/updated_by` | §3.4.3 |
| `drug_alias.alias` | varchar(64) | 是 | 别名（同药品内唯一 + 全局 lower(btrim) 唯一；与其他药品名称/通用名双向互斥） | T-4 名称解析前提 |

说明：目录全院可读；别名唯一性是“按名称判定麻精”的语义前提，不是可选项。

### 3.12 麻精五专 `narcotic_register` / `cabinet_handover` / `drug_baseline` / `daily_close`（V20 已实现）

| 表 | 关键字段 | 说明 | 来源/备注 |
|---|---|---|---|
| `m4_drug.narcotic_register` | `drug_id`、`drug_name` 快照、`batch_no`、`quantity>0`、`unit`、`prescription_no`、`used_by`(≤64)、`department_id`、`usage_at`、`destination`、`first_signer_*`、`second_signer_*` | 专册登记；**双人签字不同人**；append-only | §2.5 麻精五专 |
| `m4_drug.cabinet_handover` | `cabinet_code`、`department_id`、`shift(DAY/NIGHT)`、`handed_over_at`、`handover_from_*`、`handover_to_*`、`items` jsonb、`first_signer_*`、`second_signer_*` | 专柜交接；交班 ≠ 接班、双人签字不同人；append-only | §2.5 |
| `m4_drug.drug_baseline` | `drug_id + cabinet_code` 唯一、`department_id`、`quantity≥0`、`version` | 基数；品种/柜/科室不可改、version 只增；跨科 upsert 409/403 | §2.5 |
| `m4_drug.daily_close` | `close_date`、`shift`、`cabinet_code`、`department_id`、`opening/inbound/used/returned/closing_quantity` | 日结/班结；`期初+入库−使用−退回=期末` CHECK；同柜+日+班次唯一；append-only | §2.5【待确认】Q-2/Q-3 |
| `m4_drug.pharmacy_adapter_log` | `adapter_code(EXT-02)`、`operation`、`success`、`degraded`、`detail` jsonb | 药房适配器（SIMULATED）同步留痕；不存凭据/处方明细 | §3.9 集成 EXT-02 |

说明：五专表只存使用人姓名摘要与处方号，不含患者标识；班次两班制、日结数据来源与差异
容忍度均为【待确认】（见 `docs/modules/m4/README.md` §11 与 ADR-0018）。科室必须为启用
`org_type=DEPT` 组织；SELF 范围按本人所属科室推导（B-3/S-5）。

### 3.13 人员档案与分级授权 `staff_profile` / `staff_qualification` / `staff_authorization` / `staff_schedule` / `staff_training`（V21 + V22 已实现，M4-M13-01）

| 表 | 关键字段 | 说明 | 来源/备注 |
|---|---|---|---|
| `m4_staff.staff_profile` | `staff_no`（lower(btrim) 唯一、创建后不可改）、`user_id`（可空，非空唯一）、`name`、`department_id`、`site_id`、`title`、`job_grade`、`employment_status(ACTIVE/INACTIVE)`、`specialties` jsonb 数组、`version` | 人员档案；关联 m0 用户与科室；离职只读历史 | §2.4.4、§3.4.1 |
| `m4_staff.staff_qualification` | `staff_id`、`qualification_type(PRACTICE_LICENSE/SPECIALTY_CERT/NURSE_LICENSE/OTHER)`、`certificate_no`、`issuing_authority`、`valid_from`、`valid_to`（NULL=长期）、`status(ACTIVE/REVOKED)` | 执业资质；同人员同证书号唯一；`expired` 由 `valid_to` 计算 | §2.4.4 |
| `m4_staff.staff_authorization` | `staff_id + version` 唯一、`level(R1–R5)`、`surgery_scopes` jsonb 数组（`*`=全部）、`age_min/age_max`、`asa_max(ASA1–ASA5)`、`valid_from/valid_to`、`status(ACTIVE/REVOKED)`、`approved_by`、`approved_at`、`second_approver_id/second_approved_at/second_approver_reason`（V22 双人审批）、`revoked_at/revoked_by`（V22 撤销时点）、`change_reason` | **append-only 授权历史**：每次授予/撤销写新版本；当前版本视图 `staff_authorization_current`；回溯按 `approved_at/valid_from/revoked_at` 取当时生效版本 | §2.4.4、§3.4.1、ADR-0019 |
| `m4_staff.staff_schedule` | `staff_id`、`schedule_date`、`shift(DAY/NIGHT/ON_CALL)`、`room_id`、`department_id`、`status(PLANNED/CANCELLED)`、`cancelled_at/by`、`cancel_reason`、`version` | 排班；同人员同日同班次、同术间同日同班次唯一（仅 PLANNED 占用，取消释放） | §2.4.4、ADR-0019 |
| `m4_staff.staff_training` | `staff_id + course_code` 唯一、`course_name`、`completed_on`、`expires_on`、`certificate_no` | 培训/继续教育；到期状态 VALID/EXPIRING/EXPIRED/NO_EXPIRY（30 天窗口） | §2.4.4 |

说明：本域只存**在职人员**档案字段，不含患者任何标识；审计 detail 不含姓名与证书号原文。
R1–R5 的业务含义、级别 → 术式/年龄/ASA 默认映射、职称/职级字典、班次定义与术间是否允许
多人均为【待确认】（ADR-0019）；授权范围以显式字段为权威，系统不内置臆造映射。
V22 起授权审批人与操作者/被授权人分离；R1/R2、范围 `*` 或 ASA≥4 必须两名不同审批人。
资质 `status/valid_to` 当前只用于档案展示/提醒，不参与授权判定（Q-21）。
`staff_authorization` 的 UPDATE/DELETE/TRUNCATE 一律 55000；`staff_profile`/`staff_schedule`
守卫创建留痕不可改、version 只增，排班取消后不可复活。

### 3.14 报表读模型（只读投影，无表，M4-M16-01）

工作量/多维报表**不新增表**（V24 为 data-only 权限迁移，ADR-0021）：报表是对既有台次、
麻醉记录单与人员档案的只读聚合，字段来源如下（契约 `report.yaml` 0.1.0）。

| 报表字段 | 来源 | 说明 |
|---|---|---|
| `from` / `to` / `timezone` | 请求参数 / 固定 `Asia/Shanghai` | 本地日期闭区间，跨度 ≤ 366 天 |
| 台次（`caseCount`） | `m1_sched.schedule` | `deleted=false`、`status ≠ CANCELLED`；按 `start_at` 本地日期归集，跨日只计首日 |
| `anesthesiaMinutes` | `m2_anes.anesthesia_record.started_at/ended_at` | `Σ floor((ended_at − started_at) / 60s)`；无记录单/未结束计 0 |
| `unrecordedCaseCount` | 同上 | 有台次但无可用时长（缺记录单/未结束），数据质量字段 |
| `weightedCaseCount` | 台次 + 权重配置 | `Σ 权重`，默认 1.0，可按术式编码配置；绩效口径【待确认】 |
| `DEPT` 维度 | `m1_sched.surgery_application.dept_id/dept_name` | 申请科室；缺失归 `UNASSIGNED` |
| `STAFF` 维度 | `m2_anes.anesthesia_record.created_by` → `m4_staff.staff_profile.user_id` | 麻醉医师（记录单创建人近似）；无记录/无档案归 `UNASSIGNED` |
| `SURGERY` 维度 | `m1_sched.surgery_application.surgery_code/surgery_name` | 术式；缺失归 `UNSPECIFIED` |
| `ROOM` 维度 | `m1_sched.operating_room.room_no` | 术间；缺失归 `UNASSIGNED` |
| 术式分布 | 上述台次 + 术式字段 | 桶内按术式编码聚合，台次倒序/编码升序，最多 50 项 |
| `totals`（多维） | 全部分组 | 不受分页影响；空数据 `groupCount=0` |

说明：报表投影只含聚合维度与时间，不含患者姓名、住院号、证件号、手机号、术前诊断或 HIS
申请号（导出脱敏见契约 §导出）；人员工号与姓名属院内在册人员数据（非患者标识）。跨 schema
读取只在装配层 `ReportCaseSourceAdapter` 的单条只读 SELECT 中发生，不复制 m1/m2 Mapper/实体。

### 3.15 质控指标、不良事件与病历抽查（V25 已实现，M4-M17-01，ADR-0022）

| 表 | 关键字段 | 约束/说明 |
|---|---|---|
| `m4_qc.qc_indicator` | `code`/`version`/`name`/`category`(STRUCTURE/PROCESS/OUTCOME)/`definition`/`data_source`/`numerator_label`/`denominator_label`/`unit`(PERCENT/PER_1000_CASES)/`threshold_operator`(GTE/LTE)/`threshold_value`/`effective_from`/`effective_to`/`status`(ACTIVE/RETIRED)/`provisional`/`in_target_statistics` | `(code, version)` 唯一；口径/阈值/身份列/`provisional`/`in_target_statistics` 不可改（55000），只允许 `ACTIVE→RETIRED`；`DELETE`/`TRUNCATE` 拒绝（55000）；计算按 `effective_from/effective_to` 选择版本，同 `code` 周期内重叠 → 409；语义变更新增版本行；`provisional=true` 为合成参考指标【待确认】；`in_target_statistics=false`（`QC-SYN-PROC-004` 结构合规率）不计入达标统计 |
| `m4_qc.qc_indicator_result` | `indicator_id`/`indicator_code`/`indicator_version`/`period_from`/`period_to`/`dept_id`/`numerator`/`denominator`/`value`/`target_met`/`computed_at` | `(indicator_id, period_from, period_to, dept_id)` 唯一；`dept_id` 全 0 哨兵=全院；重算覆盖同一快照（upsert），跨版本历史独立保留；`numerator/denominator/value ≥ 0` |
| `m4_qc.adverse_event` | `event_no`/`event_type`/`severity`/`occurred_at`/`dept_id`/`dept_name`/`schedule_id`/`description`/`reporter_id`/`reporter_name`/`anonymous`/`status`/`version` | 业务内容 append-only；状态 `REPORTED→RCA→RECTIFYING→CLOSED` 逐级前移且 `version+1`（越级/回退/删除/清空 55000）；`event_no` 唯一；`description` ≤500 且必须已脱敏 |
| `m4_qc.adverse_rca` | `event_id`/`event_no`/`timeline`(jsonb 1–20)/`root_cause`/`corrective_action`/`responsible_name`/`due_date`/`status`(OPEN/CLOSED)/`reviewer_*`/`reviewed_at`/`review_conclusion`/`closed_at` | 一条事件一条 RCA（`event_id` 唯一）；内容不可改；复核/闭环字段只从 NULL 写一次；`OPEN→CLOSED` 一次性 |
| `m4_qc.chart_review` | `review_no`/`schedule_id`/`record_id`/`dept_id`/`reviewer_name`/`reviewed_at`/`items`(jsonb 1–12)/`total_score`/`max_score`/`grade`(A/B/C/D)/`defect_count`/`status`/`version` | 总分/满分/等级/缺陷由服务端计算（`total_score ≤ max_score`）；评分内容不可改；`OPEN→RECTIFYING→CLOSED` 且 `version+1`；`review_no` 唯一 |
| `m4_qc.rectification` | `review_id`/`review_no`/`corrective_action`/`responsible_name`/`due_date`/`status`/`reviewer_*`/`closed_at` | 一条抽查一条整改（`review_id` 唯一）；内容不可改；闭环字段一次性写入；`OPEN→CLOSED` |

- **脱敏**：本域不存患者标识；`description`/`defect_note` 为已脱敏文本（服务端拒绝身份证/
  住院号/手机号关键词与连续 11–19 位数字）；导出不输出任何自由文本原文。
- **跨模块**：`schedule_id`/`record_id` 为逻辑关联（不建外键）；指标计数由装配层只读投影
  从 M2 排程、M3 记录单/访视/三方核查、M4 麻精/高值耗材流水获取（ADR-0022）。
- **权限**：`m4:qc:read|write|review|export`（V25，最小授予 SYS_ADMIN/SYS_QC_MANAGER 四项、
  SYS_OR_MANAGER 读+写）；审计动作见 `docs/modules/m4/README.md` §48。

### 3.16 实时态势、KPI、风险与资源看板读模型（只读投影，无表，M5-M18-01/M5-M18-02，ADR-0023）

看板 **不新增表**（V26 为 data-only 权限迁移；0.2.0 亦未新增迁移）：看板是对既有术间、台次、
麻醉记录单、术中告警、设备登记、术前评估与人员排班的只读聚合，字段来源如下
（契约 `dashboard.yaml` 0.2.0，口径 dashboard-1.1.0）。

| 看板字段 | 来源 | 说明 |
|---|---|---|
| `updatedAt` / `timezone` | 服务端时钟 / 固定 `Asia/Shanghai` | RFC 3339 |
| `dataDelaySeconds` | 台次 `updated_at`、记录单 `updated_at`、告警 `triggered_at`、设备 `last_seen_at` | 服务端当前时间 − 可见范围最新业务数据时间（向下取整）；`dataQuality` 按 120s 阈值标 `OK`/`DEGRADED`，无数据 `NO_DATA` |
| `dataScope` | M0 `sys_role.data_scope` + `sys_user_org`/`sys_user_room` | ALL/SITE/DEPT/ROOM/SELF（取声明中最高优先级） |
| `roomStatusCounts` / `rooms[].status` | `m1_sched.operating_room.status` + `deleted` | `ANESTHESIA`/`SURGERY`→`IN_SURGERY`、`IDLE`→`IDLE`、`CLEANING`→`CLEANING`、`PREPARING`→`PREPARING`、`PACU`→`RECOVERY`、软删除→`OUT_OF_SERVICE`（停用语义【待确认】） |
| `rooms[].plannedCaseCount` | `m1_sched.schedule.status ≠ CANCELLED` 按 `room_id` 计数 | 当日台次（含未开始） |
| `rooms[].caseInProgress` | `m1_sched.schedule.status = IN_PROGRESS` | 无台次标识/患者字段 |
| `caseProgress` | `m1_sched.schedule.status` | 当日总数（含取消）、进行中数量与按状态计数 |
| `alerts` | `m2_anes.alert.status/severity/triggered_at` | 未确认（TRIGGERED）按严重度分列；已确认/已忽略单列；无告警值/处置原因 |
| `devices` | `m2_anes.device_registry.status/last_seen_at` | 在线 = `status ≠ MAINTENANCE` 且 `last_seen_at > now − 120s`（查询期推导，不写回） |
| KPI 台次/已开始/已完成/取消 | `m1_sched.schedule` + `m2_anes.anesthesia_record.started_at/ended_at` | 口径同 report-1.0.0；已开始/已完成优先看记录单时间，其次台次状态 |
| KPI 准点率 | 每术间当日计划最早台次 + 实际开始时间 | 实际开始优先取记录单 `started_at`，缺失回退台次 `updated_at`【待确认】；首台取消/未开始不评估 |
| KPI 取消率 | `m1_sched.schedule.status` | 取消台次 / 当日总台次（分母含取消） |
| KPI 利用率 | 台次计划起止与当日窗口重叠分钟 + 术间数 | 分母 = 可见术间数 × 每术间每日可用分钟（默认 600）；计划 vs 实际口径【待确认】 |
| KPI 告警数 | `m2_anes.alert` | 当日触发告警数与其中 CRITICAL 数 |
| KPI 平均麻醉时长 | `m2_anes.anesthesia_record.started_at/ended_at` | `Σ floor((ended_at − started_at)/60s) / 有记录台次`（2 位小数），无时长台次单列 |
| 风险 `asaClassCounts` / `riskLevelCounts` / `assessedCaseCount` | `m2_anes.preop_assessment.asa_class/risk_level/status/finalized_at` | 取每份申请**最新一份 FINALIZED 且未删除**的评估，按 `(asa_class, risk_level)` 分组计数；未评估台次只计入 `unassessedCaseCount`；不返回评分明细 |
| 风险 `highRiskCaseCount` | 同上 | ASA ≥ III 或 `risk_level = HIGH`（按台次去重） |
| 风险 `severeAlertCount` | `m2_anes.alert.severity/status/triggered_at` | 当日未确认（TRIGGERED）且 CRITICAL 的告警数 |
| 风险 `delayedStartCount` / `overrunCaseCount` / `overdueCaseCount` | `m1_sched.schedule.start_at/end_at/status` + 记录单 `started_at/ended_at` | 计划开始/结束超过宽限仍未开始/未完成；`overdueCaseCount` 为两类并集（去重）；宽限默认 0（0–120 分钟）【待确认 Q-11】 |
| 资源 `roomUtilization` | 台次计划起止 + `m1_sched.operating_room.deleted` | 与 KPI 利用率同源（可见术间数 × 每日可用分钟） |
| 资源 `devices` / `deviceOnlineRate` | `m2_anes.device_registry.status/last_seen_at` | 在线率 = 在线 / 总数（查询期推导，不写回） |
| 资源 `staffOnDuty` | `m4_staff.staff_schedule.staff_id/shift/schedule_date/status` | 当日 `PLANNED`：`total` 按 `staff_id` 去重，`byShift` 按班次分组（班组之和可能 > total）；不返回人员标识【待确认 Q-12】 |
| 资源 `resourceGaps` | 上述聚合结果 | 固定编码（设备离线/维护、停用术间、排程超载、当班为 0、无可见术间）+ 计数 + 中文提示；展示辅助，非决策 |
| 趋势 `points[]` | 告警 `triggered_at` + 台次计划起止/状态 + 术间数 | 按 `Asia/Shanghai` 整点分桶；告警数不限处置状态、台次按计划开始归桶；不返回时效字段 |
| 审计 | `m0_platform.sys_audit_log` | `DASHBOARD_VIEW` 按用户采样（默认 300s），`resource_id=realtime|kpi|risk|resources|trends`，detail 仅看板类型/日期/范围/计数/延迟 |

说明：看板响应**不含**患者姓名、住院号/院内患者号、证件号、手机号、术前诊断、术式自由文本、
记录单自由文本、术前评估评分明细、人员标识与告警处置原因（只有计数/状态/术间/科室/班组）。
跨 schema 读取只在装配层 `DashboardCaseSourceAdapter`/`DashboardRoomSourceAdapter`/
`DashboardAlertSourceAdapter`/`DashboardDeviceSourceAdapter`/`DashboardRiskSourceAdapter`/
`DashboardStaffSourceAdapter` 的单条只读 SELECT 中发生，不复制其他模块 Mapper/实体。

### 3.17 字典、消息与文件（V27 已实现，M8-M0-04，ADR-0024）

字典表 `sys_dict_type`/`sys_dict_item` 自 V1 已存在（本节补口径），V27 只补读索引；
消息与文件元数据为 V27 新增。字段以 `db/migration/V27__m0_dict_message_file.sql` 为准。

#### `m0_platform.sys_dict_type`（V1）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | uuid | 主键 |
| `code` | varchar(64) | 类型编码，全局唯一（如 `ASA`/`SEX`） |
| `name` | varchar(100) | 类型中文名 |
| `description` | varchar(500) | 说明（口径/来源） |
| `status` | smallint | 1=启用，0=停用 |
| `created_at/created_by/updated_at/updated_by` | timestamptz/uuid | 审计字段 |

#### `m0_platform.sys_dict_item`（V1；V27 补 `idx_sys_dict_item_type_sort`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | uuid | 主键（接口 `itemId`） |
| `dict_type_id` | uuid | FK → `sys_dict_type.id`（级联删除） |
| `code` | varchar(64) | 项编码，**同类型内唯一**（`uk_sys_dict_item_type_code`），创建后不可改 |
| `label` | varchar(100) | 显示名称（接口 `name`） |
| `sort` | integer | 同类型内排序号（0–9999） |
| `status` | smallint | 1=启用，0=停用（接口 `enabled`；**逻辑停用，不物理删除**） |
| `remark` | varchar(255) | 备注/口径说明（禁止患者标识与临床自由文本） |
| `created_at/created_by/updated_at/updated_by` | timestamptz/uuid | 审计字段 |

#### `m0_platform.sys_message`（V27）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | uuid | 主键（接口 `messageId`） |
| `recipient_user_id` | uuid | 收件人，FK → `sys_user.id`；查询强制 = 当前用户 |
| `message_type` | varchar(16) | SYSTEM/SCHEDULE/ALERT/QC/TASK（CHECK） |
| `template_code` | varchar(64) | 模板编码（前端本地化） |
| `title` | varchar(200) | 标题（模板 + 参数白名单渲染，禁止 PHI） |
| `params` | jsonb | 模板参数，必须是对象；键白名单 roomNo/deptName/startAt/endAt/count/url |
| `read_at` | timestamptz | 已读时间（NULL=未读；幂等更新，不删除消息） |
| `created_at` | timestamptz | 创建时间 |
| `created_by` | uuid | 写入方（系统广播为用户；领域事件可为 NULL） |

索引：`(recipient_user_id, created_at DESC, id DESC)` + 未读部分索引
`(recipient_user_id) WHERE read_at IS NULL`。

#### `m0_platform.sys_file`（V27）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | uuid | 主键（接口 `fileId`） |
| `storage_key` | varchar(255) | 存储适配器相对键（内容寻址），**不出接口** |
| `content_type` | varchar(128) | 白名单 CHECK：application/pdf、text/csv、text/plain、image/png、image/jpeg |
| `size_bytes` | bigint | 1–20971520（20 MiB，CHECK） |
| `sha256` | char(64) | 内容 SHA-256（服务端计算、索引，用于完整性校验与内容去重） |
| `business_type` | varchar(16) | TEMPLATE/REPORT/ATTACHMENT/SCHEDULE/RECORD（CHECK，决定可见性） |
| `business_id` | varchar(64) | 业务关联标识（UUID/大写业务号；逻辑关联，不建跨 schema 外键） |
| `uploader_id` | uuid | 上传者，FK → `sys_user.id`；上传者始终可下载 |
| `scan_status` | varchar(16) | NOT_SCANNED（当前占位 EXT-07）/CLEAN/INFECTED（拒绝下载） |
| `deleted` | boolean | 逻辑删除（接口 404；保留审计可追溯性） |
| `created_at` | timestamptz | 上传时间 |

索引：`sha256`、`(uploader_id, created_at DESC)`、`(business_type, business_id)`。
**文件内容不入库**（`.cache/files` 本地 FS；生产 compose 持久卷 `m0filedata`；MinIO 占位
EXT-06）；**不保存客户端原始文件名**（展示名服务端生成 `file-<id>.<ext>`）；**不得存 PHI**
（禁 DICOM/视频/身份证明/含患者标识表格）。权限点与可见性规则见 `docs/modules/m0/README.md`
§10 与 ADR-0024。

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
| `ResuscitationRecord`、`ResuscitationRecordList`、`CreateResuscitationRequest`、`ResuscitationDrug`、`ResuscitationEventSummary`、`ResuscitationParticipant` | `m2_anes.resuscitation_record` | 抢救记录 append-only、时间窗校验、负责人/参与人员（V16）；锁单后冻结（V17） |
| `RecordRevision`、`RecordRevisionSummary`、`RecordRevisionList`、`CreateRevisionRequest`、`RevisionCorrections`、`RevisionDiff` | `m2_anes.record_revision` | 锁单后修订 append-only、版本链签名、差异（V17） |
| `RecordEffectiveView`、`RecordEffectiveValues`、`RecordEffectiveCorrection` | 读时投影（无新表） | `original` + 最新修订 `corrections` 的当前有效值；详情/打印使用，原行不变（0.8.1） |
| `PacuRecord` | `m2_anes.pacu_record` | 复苏记录：入室信息、状态机、阈值展示（V18） |
| `PacuAssessment`、`PacuAssessmentList`、`RecordPacuAssessmentRequest` | `m2_anes.pacu_assessment` | Aldrete 五项/总分 append-only 时间序列（V18） |
| `PacuDischarge`、`PacuDischargeRequest`、`PacuDischargeCriteria`、`PacuHandover` | `m2_anes.pacu_discharge` | 转出留痕、SBAR 交接与达标快照（V18） |
| `PostopFollowup`、`PostopFollowupList`、`CreatePostopFollowupRequest`、`FollowupType`、`FollowupComplication` | `m2_anes.followup_record` | 24/48 小时随访、NRS/PONV、并发症、脱敏说明/反馈（V19，append-only） |
| `PainManagement`、`PainManagementList`、`RecordPainManagementRequest`、`PainAdjustment`、`PainManagementModality`、`PainManagementEffect` | `m2_anes.pain_management` | 镇痛方式/参数/效果/调整记录（V19，append-only） |
| `FollowUp`、`FollowUpPage`、`CreateFollowUpRequest`（旧占位） | 未落表 | 早期 `/follow-ups` 契约占位，未实现；0.10.0 实现的是 `/followups`（见 §16.9 Q-6） |
| `CurrentUser`、`DataScope` | `sys_user`、`sys_role_data_scope` 等 | 权限投影，不是单表 |
| `Drug`、`DrugList`、`CreateDrugRequest`、`UpdateDrugRequest` | `m4_drug.drug`、`m4_drug.drug_alias` | 药品目录、别名唯一、麻精/高危标志、乐观锁（V20） |
| `NarcoticRegister`、`NarcoticRegisterList`、`CreateNarcoticRegisterRequest` | `m4_drug.narcotic_register` | 麻精专册 append-only、双人签字不同人（V20） |
| `CabinetHandover`、`CabinetHandoverList`、`CreateCabinetHandoverRequest`、`CabinetHandoverItem` | `m4_drug.cabinet_handover` | 专柜交接 append-only、品种数量明细 jsonb（V20） |
| `DrugBaseline`、`DrugBaselineList`、`UpdateDrugBaselinesRequest` | `m4_drug.drug_baseline` | 基数（品种 + 柜唯一、乐观锁）（V20） |
| `DailyClose`、`CreateDailyCloseRequest` | `m4_drug.daily_close` | 日结/班结、平账 CHECK、append-only（V20） |
| `PharmacyAdapterStatus` | `m4_drug.pharmacy_adapter_log` | 药房适配器（EXT-02 SIMULATED）状态与降级留痕（V20） |
| `Staff`、`StaffList`、`CreateStaffRequest`、`UpdateStaffRequest`、`StaffQualification`、`StaffQualificationInput`、`DepartmentOption`、`DepartmentList` | `m4_staff.staff_profile`、`m4_staff.staff_qualification` | 人员档案、执业资质、科室选项、乐观锁（V21） |
| `Material`、`MaterialList`、`CreateMaterialRequest`、`UpdateMaterialRequest`、`MaterialStatusRequest` | `m4_material.material` | 耗材目录、编码/名称/UDI-DI 唯一、高值/效期标志、启停（ACTIVE/INACTIVE）、乐观锁（V23） |
| `MaterialStockLevel`、`MaterialStockLevelList` | `m4_material.material_stock` | 批次库存（材料 + 批次 + 科室唯一、数量 >= 0）（V23） |
| `StockMovement`、`StockMovementList`、`CreateStockMovementRequest` | `m4_material.stock_movement` | 出入库流水 IN/OUT/RETURN/ADJUST、append-only、余额快照；RETURN=退回供应商/中心库（V23） |
| `UdiInstance`、`RegisterUdiInstanceRequest`、`UseUdiInstanceRequest`、`UdiTrace`、`MaterialTraceSummary`、`RecordLink` | `m4_material.udi_instance` | UDI 一物一码、状态机 IN_STOCK/USED/RETURNED、recordId/scheduleId 逻辑关联（V23） |
| `SpdAdapterStatus`、`SpdSyncResult` | `m4_material.spd_sync_log` | SPD 适配器（EXT-03 SIMULATED）状态与降级留痕（V23） |
| `DepartmentOption`、`DepartmentList`（material） | 读时投影（`m0_platform.sys_org` / `sys_user_org`） | 出入库/UDI 登记的科室选项，按数据范围收敛（V23） |
| `StaffAuthorization`、`StaffAuthorizationHistory`、`UpdateStaffAuthorizationRequest` | `m4_staff.staff_authorization`（+ 视图 `staff_authorization_current`） | append-only 授权版本链、当前版本视图（V21） |
| `AuthorizationCheckRequest`、`AuthorizationCheckResult`、`AuthorizationCheckReason` | 读时判定（无新表） | 授权校验（排程/术中集成点），原因码见契约枚举（V21） |
| `StaffSchedule`、`StaffScheduleList`、`CreateStaffScheduleRequest`、`UpdateStaffScheduleRequest`、`CancelStaffScheduleRequest` | `m4_staff.staff_schedule` | 排班冲突唯一约束、取消留痕（V21） |
| `StaffTraining`、`StaffTrainingList`、`ExpiringTrainingList`、`CreateStaffTrainingRequest` | `m4_staff.staff_training` | 培训记录、到期提醒（V21） |
| `WorkloadReport`、`WorkloadBucket`、`SurgeryDistribution` | 读时投影（无新表） | 工作量：台次/麻醉时长/加权台次/无时长台次/术式分布（口径 report-1.0.0，V24 data-only） |
| `MultiReport`、`MultiRow`、`ReportTotals` | 读时投影（无新表） | 多维报表：组合维度 × 指标、稳定排序 + 分页 + 全量合计 |
| `Session` | 会话存储（Redis 7.2） | 具体键设计【待确认】，不落 PostgreSQL |

## 5. 【待确认】汇总

0. **分级授权与排班口径**：R1–R5 的业务含义与“级别 → 术式/年龄/ASA”默认映射、ASA 上限
   是否按级别固定、职称/职级字典、班次（三班制/节假日班/换班审批）、术间是否允许同时多人，
   待院方（医务处/麻醉科/手术室/人事）确认；见 ADR-0019。
1. 患者编号、住院号格式与唯一性；`gender`/`birth_date` 编码与返回精度。
2. 术间设备配置结构、实时状态承载、隔离字段命名。
3. 角色/权限/数据范围四张表的最终表名、权限点编码规范。
4. 排程 `sequence_no` 唯一约束、跨天排程与改台记录是否独立成表。
5. 记录单维度三方核查（M3）的表结构与 1:1 关系；CA 有效签名（EXT-05）解除后的
   证书链/时间戳字段与真实验签流程。修订版本表与摘要算法已由 V17/`§14.3` 落地：
   `signature_hash = SHA-256(规范化快照 JSON)`，修订快照含上一版摘要形成版本链。
6. DASI 评分范围（V10 起按通行 0–58.2 实现）与院方口径、功能容量 <4 METs 切点，待临床确认。
7. 用药剂量单位/途径字典、麻精双人核对签名字段与空安瓿台账（V13 先落 `second_checker_id/name`，
   电子签名与处方关联在 M5 细化）。
7a. 事件按记录单分区策略（V13 未落，后续迁移）；`record_revision` 已由 V17 落地。
   `OMISSION`/`TIMING_CORRECTION` 原因码需结构化更正能力（M3-QA 前，Q-17）；锁单后抢救
   补录是否开放走下一修订版本待临床确认（Q-18）。
8. 生命体征指标编码、单位、采样间隔、波形存储（D-04）。
9. 复苏记录：Aldrete 转出阈值（默认 9）、入室意识/气道/转出去向枚举、未处理严重告警是否
   阻断转出（Q-1/Q-3/Q-4）待临床确认；随访量表口径待 M3-M11-01。
10. 术中预警默认阈值（SpO2<90、HR<50/>120、MAP<65、EtCO2 30–50）与持续时长全部【待确认】，
    须临床顾问逐条确认；抢救类型/转归枚举（`CPR/AIRWAY/VASOPRESSOR/DEFIBRILLATION/OTHER`、
    `SUCCESS/PARTIAL/FAILED/UNKNOWN`）同为【待确认】。

11. **Q-1 UDI 解析规则**：GS1/HIBCC 分隔符与校验位、PI 内嵌批号/效期/序列号解析、扫码枪
    前后缀；本期内部编码为 `UDI-DI^UDI-PI` 且只做字符串唯一（V23/ADR-0020）。
12. **Q-2 批次策略**：同批号跨供应商合并、跨科室调拨/合并记账、近效期阈值与预警渠道。
13. **Q-3 高值口径**：高值耗材金额阈值与目录范围；`material.high_value` 由院内维护，
    不内置金额字段（避免把财务口径写死成临床规则）。
14. **Q-4 SPD 口径**：EXT-03 真实接口协议、同步频率、失败重试与对账；本期为模拟适配器，
    失败降级为本地库存可用并留痕。
15. **Q-5 计费衔接**：UDI 使用是否触发计费/耗材计费单据、退费与红冲流程（M5/财务域）。
16. **Q-6 角色分工**：高值到货验收由护士还是库管执行（`m4:udi:manage` 当前授予 OR 管理员
    与麻醉护士，职责分离口径待确认）。
17. **Q-7 院内退库/复库**：本期 RETURN 只表示退回供应商/中心库（UDI 终态 RETURNED，单向）；
    退回二级库后复库再用、退库后重新入库是否复用原 UDI/批次/效期、是否需退库单与审批，
    待院方确认（ADR-0020 Q-6）。
18. **Q-8 报表权重与难度系数**：术式权重来源（绩效方案/手术分级）、ASA/年龄/急诊加成与
    二次分配规则；本期默认 1.0 + 可配置映射，不内置绩效公式（ADR-0021 Q-1）。
19. **Q-9 报表数据来源与时长口径**：`STAFF` 用记录单创建人还是排班/实际执行人、`DEPT` 用
    申请科室还是手术/患者科室、`SURGERY` 用 HIS 还是院内分级编码；麻醉时长是否扣除准备/
    复苏时间、跨零点如何展示（ADR-0021 Q-2/Q-3）。
20. **Q-10 看板口径**：准点率“实际开始时间”来源（记录单 `started_at` vs 台次 `updated_at`）、
    利用率计划/实际占用口径、停用术间语义（M1 无独立停用状态，暂以软删除表示）、SELF 范围
    （本人台次所涉术间）是否满足麻醉医师个人看板；见 ADR-0023 Q-1–Q-4。
21. **Q-11 超时台次口径**：宽限分钟数、是否区分“延迟开始/超时结束”、是否按科室排名；
    见 ADR-0023 Q-5。
22. **Q-12 资源口径**：备班（`ON_CALL`）是否计入当班、人员 SELF 范围按“本人台次所涉科室”
    裁剪是否可接受、缺口规则阈值与是否分科室展开、设备在线率是否按设备类型分列；
    见 ADR-0023 Q-6。
