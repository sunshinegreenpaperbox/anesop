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
| `phone_cipher` | text | 否 | 手机号密文 | §3.9.3 |
| `phone_hash` | varchar | 否 | 手机号哈希 | §3.9.3 |
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
| `sys_user` | `user_id`、`username`、`display_name`、`staff_id`、`status`、`password_hash`、审计字段、`deleted_at` | 账号；与 M3 人员档案关联 | §2.4.1、§3.9.2；口令哈希算法【待确认】 |
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

### 2.1 手术申请 `surgery_application`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `application_id` | bigint | 是 | 主键 | §3.4.2 |
| `application_no` | varchar | 是 | HIS 申请号，入站幂等键 | §3.5.1、§3.5.3 I-01b |
| `patient_id` | bigint | 是 | 患者 | §3.4.2 |
| `diagnosis` | varchar/text | 是 | 术前诊断 | §2.4.2、§3.5.3 I-01b |
| `procedure_name` | varchar | 是 | 拟施术式 | §2.4.2 |
| `planned_surgery_date` | date | 是 | 拟手术日期 | §3.5.3 I-01b |
| `urgency` | varchar | 是 | 急缓：择期/急诊 | §2.4.2；枚举值以契约 `ELECTIVE/EMERGENCY` 为准 |
| `status` | varchar | 是 | 申请状态 | 契约 `RECEIVED/VALIDATED/SCHEDULED/CANCELLED`；完整状态机【待确认】 |
| `department_id` | bigint | 是 | 申请科室 | §2.4.2 |
| `site_id` | bigint | 是 | 院区 | §3.4.5 |
| `tenant_id` | bigint | 是 | 租户 | §3.4.5 |
| 审计字段 | — | 是 | 审计字段 | §3.4.3 |
| `deleted_at` | timestamptz | 否 | 软删除 | §3.4.3 |

说明：`(tenant_id, application_no)` 唯一，重复提交返回首次结果（§3.5.1）。术前检查完整性、术式与诊断匹配作为接收校验（§2.4.2）。

### 2.2 排程/台次 `schedule`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `schedule_id` | bigint | 是 | 主键 | §3.4.2 |
| `application_id` | bigint | 是 | 手术申请 | §3.4.2 |
| `room_id` | bigint | 是 | 术间 | §3.4.2 |
| `sequence_no` | smallint | 是 | 台次序号 | §3.4.2；唯一约束【待确认】 |
| `planned_start` | timestamptz | 是 | 计划开始 | §3.4.2 |
| `planned_end` | timestamptz | 是 | 计划结束 | §3.4.2 |
| `actual_start` | timestamptz | 否 | 实际开始 | §3.4.2 |
| `actual_end` | timestamptz | 否 | 实际结束 | §3.4.2 |
| `status` | varchar | 是 | 排程状态 | 契约枚举；状态机【待确认】 |
| `priority` | varchar | 是 | 优先级：择期/紧急/急诊 | §2.4.2；急诊优先于择期 |
| `change_reason` | varchar | 否 | 最近改台/停台原因 | §3.4.2 |
| `version` | integer | 是 | 乐观锁 | §3.4.3 |
| 审计字段 | — | 是 | 审计字段 | §3.4.3 |
| `deleted_at` | timestamptz | 否 | 软删除 | §3.4.3 |

约束：同术间时段不得重叠；冲突不得强行保存，覆盖须审批留痕（§3.4.1、§2.4.2）。

### 2.3 排程变更 `schedule_change`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `change_id` | bigint | 是 | 主键 | 建议表，是否独立【待确认】 |
| `schedule_id` | bigint | 是 | 排程 | §2.4.2 |
| `change_type` | varchar | 是 | 停台/改台/换台/并台/急诊加台 | §2.4.2；枚举【待确认】 |
| `reason_code` | varchar | 是 | 原因码，来自字典 | §2.4.2 |
| `reason` | varchar | 否 | 原因说明 | §2.4.2 |
| `before_value` | jsonb | 否 | 变更前快照 | §3.9.1 审计思路 |
| `after_value` | jsonb | 否 | 变更后快照 | §3.9.1 审计思路 |
| `operator_id` | bigint | 是 | 操作人 | §3.9.1 |
| `occurred_at` | timestamptz | 是 | 变更时间 | §3.9.1 |

说明：也可由审计日志承载；是否单表【待确认】。

## 3. M2 麻醉信息

### 3.1 术前访视 `preop_visit`

| 字段 | 类型 | 必填 | 说明 | 来源/备注 |
|---|---|---|---|---|
| `visit_id` | bigint | 是 | 主键 | 契约 `PreopVisit` |
| `record_id` | bigint | 是 | 麻醉记录单 | §2.4.3 |
| `schedule_id` | bigint | 是 | 排程 | §2.4.3 |
| `patient_id` | bigint | 是 | 患者 | §2.4.3 |
| `history` | text | 是 | 病史摘要 | §2.4.3；自由文本，禁止入日志 |
| `mallampati` | varchar | 是 | 气道 Mallampati 分级 I–IV | §2.4.3、glossary |
| `asa_class` | varchar | 是 | ASA 分级 I–VI | §2.4.3、glossary |
| `rcri_score` | smallint | 是 | RCRI 评分 | §2.4.3 |
| `apfel_score` | smallint | 是 | Apfel PONV 评分 | §2.4.3 |
| `stop_bang_score` | smallint | 是 | STOP-BANG 评分 | §2.4.3 |
| `dasi_score` | numeric | 是 | DASI 指数 | §2.4.3；按院方口径配置，通行 DASI 0–58.2，【待确认】 |
| `anesthesia_plan` | text | 是 | 麻醉计划 | §2.4.3 |
| `consent_signed` | boolean | 是 | 知情同意是否签署 | §2.4.3 |
| `status` | varchar | 是 | 访视状态 | 契约枚举 |
| 审计字段 | — | 是 | 审计字段 | §3.4.3 |

说明：评分项与阈值可按院方口径配置（§2.4.3）；阈值配置表结构【待确认】。

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
| `PreopVisit` | `preop_visit` | 评分与麻醉计划 |
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
6. DASI 评分范围（通行 0–58.2）与院方口径，待临床确认。
7. 用药剂量单位/途径字典、麻精双人核对签名字段与空安瓿台账。
8. 生命体征指标编码、单位、采样间隔、波形存储（D-04）。
9. 复苏记录评分项与转出阈值、随访量表口径。
