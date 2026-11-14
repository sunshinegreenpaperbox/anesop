# ANESOP 数据字典（初稿）

> **初稿，随迁移/契约演进。** 本目录只记录已由方案书或契约确认的实体与字段；未确定的字段不写，必要处标【待确认】。正式 DDL 以 `db/migration/` 的 Flyway 脚本为准，字段口径以 `packages/contracts/openapi/` 为准。
>
> 事实来源：方案书 §3.4（领域模型与数据设计）、§3.5（接口与集成）、§3.9（安全设计）、§2.2.2（功能清单）、§2.4.1–§2.4.3（M0–M2）、§2.7.1（核心数据实体）。

## 1. 范围与权威源

- 本初稿覆盖 **M0 公共支撑、M1 手术排程、M2 麻醉信息、M4 药品与人员管理** 的核心实体；
  M3/M5–M8 在对应模块任务中补充。
- 主数据权威源：患者主索引 HIS、药品目录药房、耗材主数据 SPD；临床记录以本系统为源，EMR 只归档（`CONTEXT_CORE.md` §2）。
- 同一字段一期只允许一个权威来源；跨系统同步字段在“来源”列注明接口编号（§3.5.3）。
- 契约与字典的对应关系见 `core-entities.md` 第 4 节；契约新增字段必须先评审，再补迁移与字典。

## 2. 命名与类型约定

| 项目 | 约定 | 来源 |
|---|---|---|
| 表/字段命名 | 小写下划线、表名单数；索引 `idx_表_字段` | §3.4.3 |
| 主键 | 业务表 `bigint` 雪花 ID；日志表 `bigserial`；接口层序列化为字符串 | §3.4.3 |
| 外键 | 逻辑外键，不建物理外键 | §3.4.3 |
| 软删除 | `deleted_at` 默认 NULL；临床数据不物理删除 | §3.4.3 |
| 乐观锁 | 高并发写表带 `version`，冲突明确提示 | §3.4.3 |
| 审计字段 | `created_by/created_at/updated_by/updated_at` | §3.4.3 |
| 多院区 | 业务表带 `tenant_id`、`site_id`；唯一索引带 `tenant_id` 前缀 | §3.4.5 |
| 时区 | 库统一 `timestamptz`，应用层 `Asia/Shanghai` | §3.4.3 |
| 大字段 | 不存 `bytea`，只存 MinIO 对象键 | §3.4.3 |
| 敏感字段 | 证件号、手机号、住址、银行卡号应用层 AES-256-GCM，另存 `*_hash` 供精确匹配 | §3.9.3 |
| 审计保留 | 审计日志只追加，在线保留 12 个月，满足等保 ≥6 个月 | §3.4.4、§3.9.1 |
| 分区 | 生命体征按天、审计日志按月；原始报文只留 7 天 | §3.4.4 |

> 下表与 `core-entities.md` 中的字段名为按 §3.4.3 命名约定给出的建议名；方案书未逐字给出的字段在备注列标【待确认】，不视为已冻结口径。

## 3. 实体索引

| 模块 | 实体 | 建议表名 | 聚合根/说明 | 契约 | 来源 |
|---|---|---|---|---|---|
| M0 | 患者 | `patient` | 患者主索引只读同步 | `Patient` | §3.4.2、§3.5.3 I-01a |
| M0 | 术间 | `operating_room` | 静态配置，含隔离与设备 | `OperatingRoom` | §3.4.2、§2.4.2 |
| M0 | 用户 | `sys_user` | 账号与人员关联 | `CurrentUser` | §2.4.1、§3.9.2 |
| M0 | 角色/权限/数据范围 | `sys_role`、`sys_permission`、`sys_role_permission`、`sys_role_data_scope` | RBAC + 数据权限 | `DataScope` | §3.9.2 |
| M0 | 审计日志 | `audit_log` | 只追加、按月分区 | `Error.traceId` 关联 | §3.9.1、§3.4.4 |
| M1 | 手术申请 | `surgery_application` | HIS 申请映射，`application_no` 幂等 | `SurgeryApplication` | §3.4.2、§3.5.3 I-01b |
| M1 | 排程/台次 | `schedule` | 术间时段不重叠 | `Schedule` | §3.4.2、§2.4.2 |
| M1 | 排程变更 | `schedule_change` | 停台/改台/换台原因与前后值 | 契约暂未单列 | §2.4.2、§3.4.2 |
| M2 | 术前访视与评估 | `m2_anes.preop_assessment` | 病史、气道、ASA、评分输入/结果、计划、最终化 | `PreopAssessment` | §2.4.3、§2.7.1 |
| M2 | 麻醉记录单 | `anesthesia_record` | 状态机 + 修订版本 | `AnesthesiaRecord` | §3.4.2、§2.4.3 |
| M2 | 麻醉事件 | `anesthesia_event` | 按记录单分区，时间倒序索引 | `AnesthesiaEvent` | §3.4.2、§2.4.3 |
| M2 | 知情同意模板 | `m2_anes.consent_template` | 模板与版本，内容不可变（append-only） | `ConsentTemplate` | §2.4.3、V11 |
| M2 | 知情同意记录 | `m2_anes.consent_record` | 签署状态机、模板版本冻结、CA 占位签名 | `ConsentRecord` | §2.4.3、§3.9.4、V11 |
| M2 | 三方核查 | `m2_anes.three_way_check` | 申请维度 Time-out/Sign-out，append-only 版本，锁单前置 | `ApplicationThreeWayCheck`（记录维度 `ThreeWayCheck` 留 M3） | §2.4.3、V11 |
| M2 | 用药记录 | `medication_record` | 麻精双人核对字段必填 | 契约暂未单列 | §3.4.2、§2.4.6 |
| M2 | 生命体征 | `vital_sign` | 按天分区；波形不与数值同表 | 契约暂未单列 | §3.4.2、§3.4.4 |
| M2 | 复苏记录 | `m2_anes.pacu_record` | PACU 入室信息/状态机；Aldrete 评估 `m2_anes.pacu_assessment`（append-only）；转出交接 `m2_anes.pacu_discharge`（append-only） | `PacuRecord`、`PacuAssessment`、`PacuDischarge` | §2.4.3、V18 |
| M2 | 术后随访 | `m2_anes.followup_record` | 24/48 小时随访、NRS/PONV、并发症、脱敏说明/反馈（append-only） | `PostopFollowup` | §2.4.3、V19 |
| M2 | 术后镇痛 | `m2_anes.pain_management` | 镇痛方式/PCA 参数/效果/调整记录（append-only） | `PainManagement` | §2.4.3、V19 |
| M4 | 人员档案 | `m4_staff.staff_profile` | 工号唯一、关联 m0 用户、职称/职级/专长、在职状态 | `Staff` | §2.4.4、V21 |
| M4 | 执业资质 | `m4_staff.staff_qualification` | 同人员同证书号唯一、有效期 | `StaffQualification` | §2.4.4、V21 |
| M4 | 分级授权 | `m4_staff.staff_authorization` | append-only 版本链，当前版本视图；级别/术式/年龄/ASA/有效期 | `StaffAuthorization` | §2.4.4、§3.4.1、V21、ADR-0019 |
| M4 | 人员排班 | `m4_staff.staff_schedule` | 同人员同日同班次、同术间同日同班次唯一 | `StaffSchedule` | §2.4.4、V21 |
| M4 | 培训记录 | `m4_staff.staff_training` | 课程/完成/到期/证书号；到期提醒 | `StaffTraining` | §2.4.4、V21 |

> “契约暂未单列”表示该实体已由方案书确认，但 S0-05 的四个域契约只覆盖主链路；后续任务按契约优先顺序补接口，禁止先写后端 DTO。

## 4. 契约与数据库的对应原则

- 契约字段名使用 camelCase，数据库使用 snake_case；映射由后端完成，不把数据库命名泄露到接口。
- 契约 `AuditFields` 对应表上的 `created_at/created_by/updated_at/updated_by/version`。
- 契约 `IdResponse.id` 对应各实体的雪花主键字符串；不新增 `id` 之外的别名。
- 契约中的 `status`、`urgency`、`priority`、`eventType` 等枚举值必须与字典表/枚举常量一致，新增值按兼容变更处理。
- 契约未暴露但数据库需要的字段（如 `tenant_id`、`deleted_at`、加密密文与 `*_hash`）只在字典与迁移中出现，不进入对外响应。

## 5. 更新流程

```text
领域模型评审 → db/migration/V<版本>__<描述>.sql → packages/contracts/openapi/<域>.yaml
→ docs/data-dictionary/ 更新 → 后端/前端按契约编码
```

1. 字段只增不删；删除/改类型升 major，双写过渡并写 ADR（AGENTS「契约优先与数据变更」）。
2. 迁移遵循 expand-contract，不可逆脚本附回滚演练记录。
3. 本字典与迁移、契约同 PR 更新；未定字段写【待确认】，不得以猜测值落库。
4. 每次迁移合入后，在本目录补充“迁移版本/日期”，并标注与契约的差异。

## 6. 【待确认】

- 患者 `gender` 编码、`birth_date` 精度（列表不返回出生日期，详情返回，涉及重识别风险，§3.9.3）。
- 患者编号 `patient_no`、住院号 `inpatient_no` 的院方格式与唯一性规则。
- 术间设备配置、隔离要求的存储结构（JSONB 结构或独立关联表）。
- 角色/权限/数据范围四张表的最终表名与权限点编码规范。
- 用药剂量单位、给药途径、麻精双人核对签名字段的最终口径（§2.4.6）。
- 生命体征指标编码、单位、采样间隔与波形存储方案（§3.4.4、§3.6.3）。
- 记录单哈希链算法、CA 签名与时间戳字段（§3.9.4，厂商对接方式标【待确认】）。
- 审计日志 `before_value/after_value` 的脱敏与保留策略。
