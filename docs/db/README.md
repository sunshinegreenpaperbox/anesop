# 数据库迁移与 schema 规范（db）

> 状态：S0-06 基线 + V3（认证令牌）+ V4（M2 采集骨架）+ V5（数据范围绑定，M2-M0-01）
> + V6（手机号加解密与用户管理，M2-M0-02）+ V7（M1 手术申请接收，M2-M1-01）
> + V8（M1 术间与排程台次，M2-M2-01）
> + V9（M1 停改台/急诊加台与通知 outbox，M2-M2-02）
> + V10（M2 术前访视与评估，M2-M4-01）
> + V11（M2 知情同意与三方核查，M2-M5-01）
> + V12（M1 排程分析权限点，M2-M6-01，data-only）
> + V13（M2 麻醉记录单/时间轴/用药/出入量，M3-M2-01）
> + V14（M2 设备登记与采集接入，M3-M8-01）
> + V15（M2 术中预警规则/告警留痕/抢救记录，M3-M2-02）
> + V16（M2 预警实时触发水位/记录术间快照/规则待确认/抢救人员留痕，M3-M2-02 评审修复）
> + V17（M2 锁单/修订/CA 占位签名，M3-M2-03）
> + V18（M2 复苏记录/Aldrete 评估/转出交接，M3-M10-01）
> + V19（M2 术后随访/镇痛与并发症，M3-M11-01）；
> 演示种子为独立 location。权威依据：方案书 §3.4（数据设计）、§3.9（安全）、
> `AGENTS.md`「契约优先与数据变更」，决策 D-04（数据库与信创）。
> 迁移脚本目录：`db/migration/`；生产挂载点：`/opt/anesop/db/migration`。

## 1. 目录与命名

```text
db/
├── migration/                           # 生产 locations：只允许本目录
│   ├── V1__m0_platform_init.sql         # M0 结构基线（表/约束/索引/触发器）
│   ├── V2__m0_platform_seed_synthetic.sql  # 平台预置基线（角色/权限/字典，无账号）
│   ├── V3__m0_auth_token.sql               # 刷新令牌表 + m0:auth:* 权限点 + sys_user.phone_hash（S0-07）
│   ├── V4__m2_anes_acquisition_init.sql    # M2 采集骨架（S0-09）
│   ├── V5__m0_data_scope.sql               # 数据范围绑定表 sys_org/sys_user_org/sys_user_room + m0:org:*（M2-M0-01）
│   ├── V6__m0_user_phone_crypto.sql        # sys_user.phone_key_id/phone_updated_at + 一致性约束 + m0:user:*（M2-M0-02）
│   ├── V7__m1_schedule_application.sql     # m1_sched 患者快照/手术申请/状态历史（含 append-only 触发器）+ m1:application:* / m1:patient:read（M2-M1-01）
│   ├── V8__m1_schedule_room_plan.sql       # m1_sched 术间/排程台次 + surgery_application.required_room_type + m1:schedule:*（M2-M2-01）
│   ├── V9__m1_schedule_change_emergency.sql # m1_sched 变更记录/通知 outbox + m1:schedule:approve（M2-M2-02）
│   ├── V10__m2_preop_assessment.sql        # m2_anes 术前访视与评估 + m2:preop:*（M2-M4-01）
│   ├── V11__m2_consent_threeway.sql        # m2_anes 知情同意/三方核查 + m2:consent:* / m2:threeway:*（M2-M5-01）
│   ├── V12__m1_analytics_permission.sql    # m1:analytics:read（data-only，无表/视图变更；M2-M6-01）
│   ├── V13__m2_anesthesia_record.sql       # m2_anes 记录单/时间轴/用药/出入量 + m2:record:*（M3-M2-01）
│   ├── V14__m2_device_registry.sql         # m2_anes 设备登记/上报幂等日志 + m2:device:*（M3-M8-01）
│   ├── V15__m2_alert_rescue.sql            # m2_anes 预警规则/告警/抢救记录 + m2:alert:* / m2:rescue:write（M3-M2-02）
│   ├── V16__m2_alert_realtime_watermark_rescue_leader.sql # 记录术间快照/评估水位/规则待确认/抢救人员（M3-M2-02 评审修复）
│   ├── V17__m2_record_lock_revision.sql    # m2_anes 修订版本/锁单留痕 + m2:record:lock/revise（M3-M2-03）
│   ├── V18__m2_pacu_recovery.sql           # m2_anes 复苏记录/Aldrete 评估/转出 + m2:pacu:*（M3-M10-01）
│   ├── V19__m2_followup_pain.sql           # m2_anes 术后随访/镇痛 + m2:followup:*（M3-M11-01）
│   └── README.md
└── seed/demo/                           # 仅 dev/test 的独立 Flyway location
    └── V100__demo_admin_synthetic.sql   # demo_admin/demo_dept/demo_self + SITE/DEPT 组织（SYNTHETIC，禁止进入生产）
```

- 命名固定为 `V<版本>__<描述>.sql`（双下划线，描述小写下划线，如
  `V3__m1_sched_add_room_index.sql`）；`db/migration` 内版本号从 1 连续递增，不跳号、不复用；
  `db/seed/demo` 独立编号（V100 起），不参与生产版本序列。
- **forward-only**：仓库不存放 `U<版本>__*.sql`（Flyway undo）。回滚策略见 §4。
- 已合入并执行过的脚本**禁止修改**（Flyway checksum 会失败）；任何修正一律新增版本。
- 一个脚本只写一个模块的 schema；跨 schema 变更必须拆脚本并说明依赖顺序。
- `db/seed/demo/` **不属于** `db/migration/`：它是演示专用 location，生产 locations
  只含 `db/migration`。演示账号清单与启用方式见 `docs/db/seed.md`。
- 脚本提交前必须通过 `FlywayMigrationTest`（空库 + 幂等 + 升级路径 + 种子 + 审计只追加）。

## 2. schema 归属

| schema | 模块 | 内容 |
|---|---|---|
| `m0_platform` | M0 公共支撑 | 用户、角色、权限、审计、字典（本基线） |
| `m1_sched` | M1 手术排程 | 手术申请、排程/台次、术间 |
| `m2_anes` | M2 麻醉记录 | 术前访视（V10）、知情同意/三方核查（V11）、记录单/事件/用药/出入量（V13）、设备登记与体征（V4/V14）、预警/抢救（V15/V16）、锁单/修订（V17）、复苏记录/Aldrete/转出（V18）、术后随访/镇痛（V19） |
| `m3_staff` | M3 人员资质 | 人员档案、授权 |
| `m4_supply` | M4 耗材追溯 | 批次、消耗 |
| `m5_drug` | M5 药品管理 | 麻精台账、处方 |
| `m6_stats` | M6 工作量统计 | 统计周期与结果 |
| `m7_qc` | M7 质量控制 | 指标、不良事件 |
| `m8_dash` | M8 驾驶舱 | 只读投影 |

其他模块只能经 `m0-api` 访问 M0 数据，禁止直连 `m0_platform` 表（AGENTS 模块边界）。

## 3. expand-contract 与破坏性变更

字段只增不删。任何删除列、改类型、改语义都按 expand-contract 分多次发布：

1. **expand**：新增可空列/新表/新索引（`CREATE INDEX CONCURRENTLY` 用于大表），
   应用双写新旧字段；旧代码继续可读。
2. **migrate**：分批回填历史数据（每批提交、可重入、记录进度与校验和），
   期间新写入继续双写。
3. **switch**：读路径切到新字段，观察至少一个发布窗口。
4. **contract**：确认无回滚需求后，才在后续版本删除旧列/旧索引；删除前须备份并演练恢复。

硬性规则：

- 不写 `DROP COLUMN`、`ALTER COLUMN TYPE`、`TRUNCATE`、无条件 `DELETE` 作为“顺手清理”。
- 不重命名已上线列；用“新增 + 双写 + 切换 + 删旧”替代。
- 不在迁移脚本里调用外部服务、不写业务逻辑、不依赖应用代码。
- 大表加索引用 `CONCURRENTLY`（需 `flyway.mixed=true`/非事务执行时单独评审）。
- 不可逆脚本必须附回滚演练记录，并在 PR「回滚方案」字段声明；生产执行须人签字。

## 4. 回滚策略（forward-only）

- Flyway undo 不进入交付物：仓库无 `U` 脚本，测试 `MigrationUpgradePathTest` 会断言这一点。
- 迁移失败：停止版本发布，**前向修复**（新增 V(N+1) 脚本）或按备份恢复。
- 数据损坏/不可前向修复：用最近的全量备份 + WAL/PITR 恢复到新库，校验行数与校验和，
  切换连接串后再回滚应用版本；恢复演练每季度一次（§3.9.1 备份恢复）。
- 禁止在生产对 `m0_platform.sys_audit_log` 执行 `DELETE`/`UPDATE`/`TRUNCATE`；
  归档删除只能走受控维护流程并先写归档台账（§3.4.4）。

### 4.1 V6（手机号加解密）回滚说明

V6 属 expand 段：只新增可空列 `phone_key_id`/`phone_updated_at`、四列一致性约束
`ck_sys_user_phone_crypto`、部分索引 `idx_sys_user_phone_hash` 与两个权限点。

- **应用回滚**：旧版本应用不识别新列，列可空、约束对“四列全空”放行，因此旧应用可直接运行；
  但旧应用不会写 `phone_key_id`，与本版混跑期间**禁止**用它写手机号（会触发约束失败，属预期保护）。
- **库层回滚**（前向修复脚本，需 DBA 审批 + 备份）：
  `DROP CONSTRAINT ck_sys_user_phone_crypto` → `DROP INDEX idx_sys_user_phone_hash` →
  `ALTER TABLE ... DROP COLUMN phone_key_id, DROP COLUMN phone_updated_at` →
  `DELETE FROM sys_role_permission WHERE permission_id IN ('...22','...23')` →
  `DELETE FROM sys_permission WHERE code IN ('m0:user:read','m0:user:write')`。
  顺序不可颠倒（先拆约束/索引再删列），否则删列会被约束依赖阻塞。
- **数据不可回退为明文**：已加密手机号按单向设计处理，回滚后如需保留数据，只能由受控的
  离线工具按 `phone_key_id` 选择历史密钥解密到受控介质（应用内只持单把当前密钥，没有
  `key_id → key` 映射）；不得把明文写回 `sys_user`，也不得导出到无审批的下游。
- **密钥丢失**：`ANESOP_FIELD_KEY` 丢失等于密文永久不可解密（无后门、无兜底明文）；
  轮换需 M1 离线双密钥重加密工具，备份/轮换说明见 `docs/modules/m0/README.md` 第 5 节。

### 4.2 V7（M1 手术申请）回滚说明

V7 属 expand 段：只新增 `m1_sched` schema 与三张表（患者快照/手术申请/状态历史）、
状态历史 append-only 触发器及三个权限点（m1:application:read/write、m1:patient:read），
不修改 V1–V6 任何表或列。回滚不影响 M0 认证、权限、审计与其他模块。

- **应用回滚**：下线 `/v1/scheduling/applications*` 与 `/v1/patients*`（M1 路由/前端入口），
  停止写入 `m1_sched`；旧版本应用（不识别 V7）可继续运行，因为 M0 表未变更。
- **库层回滚**（前向修复脚本，需 DBA 审批 + 备份；顺序不可颠倒）：
  `DROP TABLE m1_sched.application_status_history` →
  `DROP TABLE m1_sched.surgery_application` →
  `DROP TABLE m1_sched.patient_snapshot` → `DROP SCHEMA m1_sched`（若为空）→
  `DELETE FROM m0_platform.sys_role_permission WHERE permission_id IN ('...24','...25')` →
  `DELETE FROM m0_platform.sys_permission WHERE code IN ('m1:application:read','m1:application:write')`。
- **数据保全**：回滚前必须导出 `m1_sched` 三表（含状态历史）到受控介质并校验行数/校验和；
  历史表 append-only，不接受“只删申请不删历史”的部分回滚。
- **不可逆项**：`inpatient_no_hash` 为单向哈希，回滚后无法还原住院号明文；如业务需要，
  只能由 HIS 重新推送（患者快照按 `his_patient_no` 重建），不得从哈希反推。
- **重放**：修复后重新执行 V7 会重建空表；已导出数据不得直接回灌（会与 Flyway 版本/唯一键冲突），
  须走受控数据修复流程并记录操作人与时间。

### 4.3 V8（M1 术间与排程台次）回滚说明

V8 属 expand 段：新增 `m1_sched.operating_room` / `m1_sched.schedule` 两表、索引与两个权限点
（`m1:schedule:read|write`；read 授 SYS_ADMIN/SYS_OR_MANAGER/SYS_SURGEON，write 仅授
SYS_ADMIN/SYS_OR_MANAGER），`schedule.priority smallint NOT NULL DEFAULT 0`（0=ELECTIVE/
1=URGENT/2=EMERGENCY，`ck_schedule_priority`），并给 `surgery_application` 增加**可空**列
`required_room_type`；不修改 V1–V7 已执行脚本，也不改写既有列与数据。跨术间术者/患者并发由
应用层事务级 advisory lock（`pg_advisory_xact_lock`，确定性排序）串行化。

- **应用回滚**：下线排程工作台路由（`/m1/schedule` 与 `/v1/scheduling/{rooms,schedules*}`），
  停止读写 `m1_sched.schedule`/`operating_room`；申请/患者/麻醉记录能力不受影响。
- **库层回滚**（前向修复脚本，需 DBA 审批 + 备份；顺序不可颠倒）：
  `DROP TABLE m1_sched.schedule` → `DROP TABLE m1_sched.operating_room` →
  `ALTER TABLE m1_sched.surgery_application DROP COLUMN required_room_type` →
  `DELETE FROM m0_platform.sys_role_permission WHERE permission_id IN ('...27','...28')` →
  `DELETE FROM m0_platform.sys_permission WHERE code IN ('m1:schedule:read','m1:schedule:write')`。
- **数据保全**：回滚前导出 `schedule`/`operating_room` 并校验行数；台次取消保留原因，不物理删除。
- **种子**：V8 内置 5 个 SYNTHETIC 术间（含 1 HYBRID、1 INTERVENTION），回滚后需重建；
  真实术间主数据不得由种子代替，须走院方主数据维护流程。
- **重放**：修复后重新执行 V8 会重建空表并重新灌入 5 个合成术间；已导出排程数据不得直接回灌。


### 4.4 V9（M1 停改台/急诊加台与通知 outbox）回滚说明

V9 属 expand 段：新增 `m1_sched.schedule_change_request`（台次变更申请/审批留痕）与
`m1_sched.notification_outbox`（模拟通知发件箱）两表、索引与一个权限点
`m1:schedule:approve`（仅授 SYS_ADMIN/SYS_OR_MANAGER）；不修改 V1–V8 已执行脚本，也不改写
既有列与数据。关键约束：`ck_change_request_approval`（PENDING 无审批人、APPROVED/REJECTED
必须有审批人与时间）、`ck_change_request_type`（STOP/MODIFY/EMERGENCY）、
`ck_notification_outbox_no_phi`（payload 禁止患者姓名/住院号/证件号/手机号/诊断等键）、
`ck_notification_outbox_no_phi_value`（值级拒绝 15 位以上连续数字）；`schedule_change_request`
为完全 append-only，触发器 `trg_schedule_change_request_no_update/no_delete/no_truncate` 拒绝
改写与删除（SQLSTATE 55000，B-2）。

- **应用回滚**：下线 `/v1/scheduling/schedules/{scheduleId}/stop`、
  `/v1/scheduling/schedules/{scheduleId}/changes`、`/v1/scheduling/emergency-cases` 与工作台
  停台/急诊入口；保留原 `POST .../cancel` 与排程查询/创建/调整能力，申请/患者/麻醉记录不受影响。
- **库层回滚**（前向修复脚本，需 DBA 审批 + 备份；顺序不可颠倒）：
  `DROP TABLE m1_sched.notification_outbox` → `DROP TABLE m1_sched.schedule_change_request` →
  `DELETE FROM m0_platform.sys_role_permission WHERE permission_id = '...29'` →
  `DELETE FROM m0_platform.sys_permission WHERE code = 'm1:schedule:approve'`。
- **数据保全**：回滚前必须导出两张表（审批留痕与待发通知）到受控介质并校验行数；审批留痕
  不得丢失，须先归档到离线审计库；待发通知可直接丢弃（模拟端口，EXT 未接入）。
- **不可逆项**：`schedule_change_request` 为只增留痕，回滚后无法从台次表反推审批人/时间；
  如需保留，只能依赖导出归档。
- **重放**：修复后重新执行 V9 会重建空表；已导出的审批记录不得直接回灌（会与 Flyway 版本/
  外键冲突），须走受控数据修复流程并记录操作人与时间。

### 4.5 V10（M2 术前访视与评估）回滚说明

V10 属 expand 段：新增 `m2_anes.preop_assessment`（术前访视与评估单）与
`uk_preop_assessment_application_draft`（同一申请最多一份草稿的部分唯一索引）、5 个普通索引、
2 个权限点 `m2:preop:read|write`（授予 SYS_ADMIN/SYS_ANESTHESIOLOGIST/SYS_NURSE_ANESTHETIST）；
不修改 V1–V9 已执行脚本，也不改写既有列与数据。关键约束：`fk_preop_assessment_application`
（申请逻辑外键 + 物理 FK）、`ck_preop_assessment_finalized`（FINALIZED 必须有最终化人/时间，
DRAFT 必须没有）、`ck_preop_assessment_json_objects`（患者快照/气道/评分输入/评分结果必须是
JSON 对象）、各项分数范围 CHECK、`finalize_note varchar(500)`（最终化说明，只落库不入日志）；
评分输入与结果均以 jsonb 结构化保存，便于复算。最终化不可变由数据库触发器
`trg_preop_assessment_no_update_finalized`（FINALIZED 行拒绝 UPDATE）、
`trg_preop_assessment_no_delete`、`trg_preop_assessment_no_truncate`（DELETE/TRUNCATE 一律拒绝）
兜底，SQLSTATE 55000；草稿 UPDATE 仍允许。

- **应用回滚**：下线 `/v1/anesthesia/preop-assessments*` 五个路由与前端 `/m2/preop*` 页面
  （含菜单项）；V4 采集表、麻醉记录/锁单/随访能力不受影响。
- **库层回滚**（前向修复脚本，需 DBA 审批 + 备份；顺序不可颠倒）：
  `DROP TABLE m2_anes.preop_assessment` →
  `DROP FUNCTION IF EXISTS m2_anes.fn_preop_assessment_finalized_immutable()` →
  `DELETE FROM m0_platform.sys_role_permission WHERE permission_id IN
  ('b0000000-0000-4000-8000-00000000002a','b0000000-0000-4000-8000-00000000002b')` →
  `DELETE FROM m0_platform.sys_permission WHERE code IN ('m2:preop:read','m2:preop:write')`。
- **数据保全**：回滚前必须导出 `m2_anes.preop_assessment`（访视、评分输入与结果快照）并校验
  行数；临床记录不得丢失，须先归档到离线审计库。
- **不可逆项**：最终化单的评分快照与审计链无法从其他表反推；回滚后只能依赖导出归档。
- **重放**：修复后重新执行 V10 会重建空表并重新灌入 6 条权限授权；已导出的访视数据不得直接
  回灌（会与 Flyway 版本/外键冲突），须走受控数据修复流程并记录操作人与时间。

### 4.6 V11（M2 知情同意与三方核查）回滚说明

V11 属 expand 段：新增 `m2_anes.consent_template`（模板与版本，内容 append-only + 可停用）、
`m2_anes.consent_record`（同意记录与签署状态机）、`m2_anes.three_way_check`（手术申请维度
三方核查，append-only 版本化）、`uk_consent_template_code_version`、
`uk_consent_record_active`（同一申请 + 模板版本最多一条有效同意，部分唯一索引）、
`uk_consent_record_signed_application`（同一申请最多一条 SIGNED，部分唯一索引）、
`uk_three_way_check_application_version` 与 12 个普通索引、4 个权限点
`m2:consent:read|write`、`m2:threeway:read|write`（授予 SYS_ADMIN、
SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST）；写入 3 条 SYNTHETIC 演示模板
（ANES_GENERAL v1/v2、ANES_SEDATION v1）。不修改 V1–V10 已执行脚本，也不改写既有列与数据。

关键约束：

- `consent_template`：`UNIQUE (template_code, version)`；`status`（ACTIVE/RETIRED）+
  `retired_at/retired_by`，`ck_consent_template_retired` 保证状态与停用留痕一致；
  内容不可变由触发器 `trg_consent_template_no_update/no_delete/no_truncate` 兜底
  （SQLSTATE 55000），唯一允许一次 `ACTIVE→RETIRED`（重复停用 422）；因 `consent_record` 外键引用，
  TRUNCATE 先被 PostgreSQL 以 0A000 拒绝。
- `consent_record`：`uk_consent_record_signed_application`（`WHERE status='SIGNED'`，
  同一申请最多一条当前有效同意，撤销/拒绝不占位）、`fk_consent_record_application`、
  `fk_consent_record_template`、
  `ck_consent_record_status_fields`（SIGNED 必须有签署人/时间/签名标识与摘要且
  `ca_status IN ('SIMULATED','VERIFIED')`；DECLINED 必须有原因/时间；REVOKED 必须有
  原因/操作人/时间且保留签署留痕）、`signature_hash` 十六进制 64 位 CHECK；
  守卫触发器 `fn_consent_record_guard`：身份列不可变、签署人姓名/关系/方式在所有状态不可变、
  DECLINED/REVOKED 终态拒绝 UPDATE、SIGNED 只允许带原因的撤销且签名留痕不可改写、
  DELETE/TRUNCATE 一律拒绝（55000）。
- `three_way_check`：`UNIQUE (application_id, check_version)`、
  `ck_three_way_check_sign_out`（Sign-out 时间与执行人成对）、
  `ck_three_way_check_time_order`（Sign-out 不早于 Time-out）、
  `ck_three_way_check_issues_array`（jsonb 数组）；append-only 触发器
  `fn_three_way_check_append_only` 拒绝 UPDATE/DELETE/TRUNCATE（55000）。
- 中文表/列注释齐全（`m2_anes` 全 schema 无缺失注释，集成测试断言）。

- **应用回滚**：下线 `/v1/anesthesia/consent-templates`、`/v1/anesthesia/consents*`、
  `/v1/anesthesia/consent-templates/{templateId}/retire`、
  `/v1/anesthesia/applications/{applicationId}/three-way-checks` 共 10 个 operation 与前端
  `/m2/consent*`、`/m2/three-way*` 页面（含菜单项）；术前访视、申请与排程能力不受影响。
- **库层回滚**（前向修复脚本，需 DBA 审批 + 备份；顺序不可颠倒）：
  `DROP TABLE m2_anes.three_way_check` → `DROP TABLE m2_anes.consent_record` →
  `DROP TABLE m2_anes.consent_template` →
  `DROP FUNCTION IF EXISTS m2_anes.fn_consent_template_append_only()` →
  `DROP FUNCTION IF EXISTS m2_anes.fn_consent_record_guard()` →
  `DROP FUNCTION IF EXISTS m2_anes.fn_three_way_check_append_only()` →
  `DELETE FROM m0_platform.sys_role_permission WHERE permission_id IN
  ('b0000000-0000-4000-8000-00000000002c','b0000000-0000-4000-8000-00000000002d',
   'b0000000-0000-4000-8000-00000000002e','b0000000-0000-4000-8000-00000000002f')` →
  `DELETE FROM m0_platform.sys_permission WHERE code IN
  ('m2:consent:read','m2:consent:write','m2:threeway:read','m2:threeway:write')`。
- **数据保全**：回滚前必须导出 `consent_template`/`consent_record`/`three_way_check` 并校验行数；
  同意与核查记录属临床留痕，须先归档到离线审计库，生产执行须人签字。
- **不可逆项**：签名摘要、拒绝/撤销原因与核查历史无法从其他表反推；回滚后只能依赖导出归档。
- **重放**：修复后重新执行 V11 会重建空表并重新灌入 3 条演示模板与 4 个权限点；
  已导出数据不得直接回灌（会与 Flyway 版本/外键冲突），须走受控数据修复流程。

### 4.7 V12（M1 排程分析权限点）回滚说明

V12 属 **data-only** 迁移：不建表、不改既有表/列/索引、不创建视图或物化视图，仅新增
`m1:analytics:read` 权限点并授予 SYS_ADMIN/SYS_OR_MANAGER/SYS_QC_MANAGER。M2-M6-01 的
利用率/准点率/取消率均为对 `m1_sched` 既有表的只读聚合，**不需要新增读模型对象**；本迁移仅补齐
权限种子（V1–V11 已合入不可修改，权限点必须由迁移写入）。

- **应用回滚**：下线 `/v1/scheduling/analytics/*` 四个只读端点与前端 `/m1/analytics` 路由/菜单；
  排程工作台、申请、术前/同意/核查能力不受影响。
- **库层回滚**（前向修复，需 DBA 审批）：
  `DELETE FROM m0_platform.sys_role_permission WHERE permission_id =
  'b0000000-0000-4000-8000-000000000030'` →
  `DELETE FROM m0_platform.sys_permission WHERE code = 'm1:analytics:read'`。
- **数据保全**：本迁移不改变业务数据，回滚无需备份 `m1_sched`；审计 `ANALYTICS_QUERY/EXPORT`
  记录按审计留存要求保留 ≥6 个月，不得随权限回滚删除。
- **重放**：修复后重新执行 V12 会重新插入同一权限与三条角色授权（`ON CONFLICT` 不适用，
  需先清理再重放），已产生的分析审计不受影响。

### 4.8 V13（M2 麻醉记录单与时间轴）回滚说明

V13 属 expand 段：新增 `m2_anes.anesthesia_record`（一个台次一张，`uk_anesthesia_record_schedule`
唯一）、`m2_anes.record_event`（append-only，`(record_id, seq)` 唯一）、
`m2_anes.drug_administration`（append-only，麻精双人核对 CHECK）、
`m2_anes.fluid_record`（append-only，`amount_ml > 0`），并新增权限点 `m2:record:read|write`
（授予 SYS_ADMIN/SYS_ANESTHESIOLOGIST/SYS_NURSE_ANESTHETIST）。不改写 V1–V12 既有列与数据，
V14 与本脚本无表级依赖。

- **应用回滚**：下线 `/v1/anesthesia/records`（按台次创建/查询）与
  `/v1/anesthesia/records/{recordId}/events|drugs|fluids|io-summary` 路由、前端 `/m2/records*`
  路由/菜单；术前访视、知情同意、三方核查、申请与排程不受影响（排程页内「麻醉记录」跳转
  需 M1 写域任务，本任务写域不含 `apps/web/src/modules/m1`）。
- **库层回滚**（前向修复，需 DBA 审批 + 备份，顺序不可颠倒）：
  `DROP TABLE m2_anes.fluid_record;` → `DROP TABLE m2_anes.drug_administration;` →
  `DROP TABLE m2_anes.record_event;` → `DROP TABLE m2_anes.anesthesia_record;` →
  `DROP FUNCTION IF EXISTS m2_anes.fn_fluid_record_append_only();` /
  `fn_drug_administration_append_only()` / `fn_record_event_append_only()` /
  `fn_anesthesia_record_guard();` →
  `DELETE FROM m0_platform.sys_role_permission WHERE permission_id IN
  ('b0000000-0000-4000-8000-000000000031','b0000000-0000-4000-8000-000000000032');` →
  `DELETE FROM m0_platform.sys_permission WHERE code IN ('m2:record:read','m2:record:write');`。
- **不可逆项**：时间轴事件、用药与出入量明细为 append-only 临床留痕，无法从其他表反推；
  回滚前必须导出四张表并校验行数，先归档到离线审计库，生产执行须人签字。
- **重放**：修复后重新执行 V13 会重建空表并重新灌入 2 个权限点；已导出数据不得直接回灌。
- **锁单演进**：V13 的 `status` 约束只允许 `DRAFT/ONGOING`；M3-M2-03 锁单/修订以新迁移
  （V17+）扩展 `LOCKED/REVISED` 与锁单列，禁止回改本脚本。
- **单向守卫**：`fn_anesthesia_record_guard` 拒绝状态回退、`started_at` 清空/改写与
  `version` 不增；守卫依赖触发器，运行账号不得有 DDL 权限（见 §4.9 运行时权限）。

### 4.9 V14（M2 设备登记与采集接入）回滚说明

V14 属 expand 段：新增 `m2_anes.device_registry`（登记与术间绑定，唯一
`(gateway_id, device_id)`）、`m2_anes.device_ingest_log`（网关批量上报幂等日志，唯一
`(gateway_id, batch_key)`）与权限点 `m2:device:read|write`（授予 SYS_ADMIN/SYS_OR_MANAGER）。
不改写 V1–V13 既有表/列/约束，V13 与本脚本无表级依赖，可独立回滚。

- **应用回滚**：下线 `/v1/devices*` 端点与前端设备台账入口，网关
  `anesop.gateway.platform.mode` 改回 `jdbc`（继续直连 PG 写 `anesthesia_vital`）；设备采集
  质量看板暂不可用，临床记录单不受影响。
- **库层回滚**（前向修复，需 DBA 审批 + 备份）：
  `DELETE FROM m0_platform.sys_role_permission WHERE permission_id IN
  ('b0000000-0000-4000-8000-000000000040','b0000000-0000-4000-8000-000000000041');` →
  `DELETE FROM m0_platform.sys_permission WHERE code IN ('m2:device:read','m2:device:write');` →
  `DROP TABLE m2_anes.device_ingest_log;` → `DROP TABLE m2_anes.device_registry;`。
- **不可逆项**：设备登记与上报日志是运维辅助数据（不含体征明细与患者标识），回滚前
  `pg_dump -t` 归档两张表；`anesthesia_vital`（V4）为临床体征留痕，**不得随 V14 删除**。
- **重放**：修复后重新执行 V14 会重建空表并重新灌入 2 个权限点；归档数据不得直接回灌。
- **运行时权限（S-4）**：`anesthesia_record` 的单向守卫依赖 PL/pgSQL 函数与触发器，
  **生产运行账号不得拥有 CREATE/ALTER/DROP 等 DDL 权限**——否则可 `ALTER TABLE ...
  DISABLE TRIGGER USER` 后改写已锁定/已结束的临床记录。运行账号只授业务所需
  SELECT/INSERT/UPDATE/DELETE（审计表只授 SELECT/INSERT），DDL 仅迁移账号持有。

### 4.10 V15（M2 术中预警规则、告警留痕与抢救记录）回滚说明

V15 属 expand 段：新增 `m2_anes.alert_rule`（规则版本行，唯一 `(code, version)` + 部分唯一索引
`(code) WHERE enabled`）、`m2_anes.alert`（告警留痕，唯一 `dedupe_key`）、
`m2_anes.resuscitation_record`（抢救记录，append-only）与权限点
`m2:alert:read|ack|configure`、`m2:rescue:write`（configure 仅 SYS_ADMIN），并灌入 5 条
**SYNTHETIC** 默认规则（阈值全部【待确认】）。不改写 V1–V14 既有表/列/约束，V13/V14 与本脚本
无表级依赖，可独立回滚。

- **应用回滚**：下线 `/v1/anesthesia/alert-rules*`、`/records/{id}/alerts*`、
  `/alerts/{id}/acknowledge`、`/records/{id}/resuscitations` 路由与前端告警/抢救面板、
  预警规则页；记录单、时间轴、用药、出入量与采集不受影响。
- **库层回滚**（前向修复，需 DBA 审批 + 备份，顺序不可颠倒）：
  `DELETE FROM m0_platform.sys_role_permission WHERE permission_id IN
  ('b0000000-0000-4000-8000-000000000051','b0000000-0000-4000-8000-000000000052',
   'b0000000-0000-4000-8000-000000000053','b0000000-0000-4000-8000-000000000054');` →
  `DELETE FROM m0_platform.sys_permission WHERE code IN ('m2:alert:read','m2:alert:ack',
  'm2:alert:configure','m2:rescue:write');` →
  `DROP TABLE m2_anes.resuscitation_record;` → `DROP TABLE m2_anes.alert;` →
  `DROP TABLE m2_anes.alert_rule;` → 删除三个触发器函数
  （`fn_resuscitation_record_append_only`、`fn_alert_guard`、`fn_alert_rule_guard`）。
- **不可逆项**：告警与抢救记录属临床留痕，删除前须导出并归档到离线审计库（含规则版本快照
  `rule_id/rule_code/rule_version`）；抢救记录 `event_summary`/`drugs` 为 jsonb，归档用
  `pg_dump -t` + `COPY ... WITH (FORMAT csv)` 双份。
- **重放**：修复后重新执行 V15 会重建空表并重新灌入 4 个权限点与 5 条默认规则；归档数据不得
  直接回灌（规则版本行与历史告警的 `rule_id` 对应关系须人工核对）。
- **临床规则变更**：默认阈值仅为演示，生产启用前必须由临床顾问确认后由 SYS_ADMIN 通过
  `PUT /v1/anesthesia/alert-rules/{ruleId}` 生成新版本；禁止直接 UPDATE 规则行（触发器只允许
  “启用→停用”，配置列不可原地修改）。

### 4.11 V16（M2 预警实时触发/评估水位/抢救人员）回滚说明

V16 属 expand 段：`anesthesia_record` 增加 `room_id`（创建时经 m1-api 快照，已有记录按台次
回填）；新增 `m2_anes.alert_watermark`（每记录单已评估 `measured_at` 水位，迁移前记录从迁移
时刻起评估）；`alert_rule` 增加 `pending_confirmation`（阈值/时长变更待临床确认）并把默认种子
`created_by/updated_by` 回填为系统用户 UUID；`resuscitation_record` 增加 `leader`（必填对象）
与 `rescuers`（≥1 数组），append-only 触发器不变。不改写 V1–V15 既有列语义。

- **应用回滚**：恢复 V15 时代的“查询时评估 + 手动补偿”与无人员字段的抢救表单；
  记录单/时间轴/用药/出入量/采集不受影响（`room_id` 与水位表对旧代码是可忽略的新增）。
- **库层回滚**（前向修复，需 DBA 审批 + 备份，顺序不可颠倒）：
  `DROP TABLE m2_anes.alert_watermark;` → 删除 `ck_resuscitation_leader` /
  `ck_resuscitation_rescuers` 约束与 `leader`/`rescuers` 列（先导出抢救记录归档）→
  `DROP INDEX m2_anes.idx_anesthesia_record_room_active;` →
  `ALTER TABLE m2_anes.anesthesia_record DROP COLUMN room_id;` →
  `ALTER TABLE m2_anes.alert_rule DROP COLUMN pending_confirmation;`。
- **不可逆项**：`leader`/`rescuers` 与水位属临床留痕/评估状态，删除前 `pg_dump -t` 归档；
  回填的种子 `created_by` 系统 UUID 不需要回滚（只影响“创建人”展示）。
- **重放**：修复后重新执行 V16 会重建水位表并重新回填 room_id；水位重置为迁移时刻意味着
  从该时刻起重新评估，**不会**补发历史告警（符合“不回评历史窗口”）。
- **临床风险**：阈值变更的 `pending_confirmation` 不会自动清除；生产启用前须临床顾问逐项
  确认（M5 权限细化承接复核流程），不得把演示默认阈值当成临床决策依据。

### 4.12 V17（M2 锁单/修订/CA 占位签名）回滚说明

V17 属 expand 段：`anesthesia_record` 增加锁单留痕（`locked_at/locked_by/current_revision/
lock_signature_ref/lock_signature_hash/lock_ca_status`），`status` 取值扩展为
`DRAFT/ONGOING/LOCKED/REVISED` 并新增锁字段一致性 CHECK；新增 `m2_anes.record_revision`
（append-only 修订版本，`(record_id, revision_no)` 唯一、原因码白名单 CHECK、签名摘要 CHECK）；
以 `CREATE OR REPLACE` 升级 `fn_anesthesia_record_guard`（锁单不可变、锁字段写一次、
`current_revision` 逐次 +1、仅允许 `LOCKED→REVISED`）；新增
`fn_record_child_locked_guard` 与四个 BEFORE INSERT 触发器（锁单后禁止追加事件/用药/出入量/
抢救记录）；`record_revision` 新增 BEFORE INSERT 链守卫
`fn_record_revision_chain_guard`（父记录已锁单、`revision_no=current_revision+1`、
`previous_signature_hash` 与上一版一致）；修订原因码白名单为
`ANESTHESIA_TYPE_CHANGE/OTHER`（0.8.1）；新增权限点 `m2:record:lock`、`m2:record:revise`
（授 SYS_ADMIN/SYS_ANESTHESIOLOGIST）。
不改写 V1–V16 既有列与数据；历史记录行迁移后 `locked_at` 为 NULL、`current_revision=0`。

- **应用回滚**：下线 `POST /records/{recordId}/lock`、`GET /records/{recordId}/lock-status`、
  `GET/POST /records/{recordId}/revisions` 与前端锁单/修订/版本历史界面；旧代码读
  `anesthesia_record` 时新增列可忽略，锁单/修订审计保留。
- **库层回滚**（前向修复，需 DBA 审批 + 备份 + 临床归档，顺序不可颠倒）：
  `DROP TRIGGER trg_record_event_locked_insert` / `trg_drug_administration_locked_insert` /
  `trg_fluid_record_locked_insert` / `trg_resuscitation_record_locked_insert` →
  `DROP FUNCTION fn_record_child_locked_guard()` →
  `DROP TRIGGER trg_record_revision_chain_insert ON m2_anes.record_revision` →
  `DROP FUNCTION fn_record_revision_chain_guard()` →
  `DROP TABLE m2_anes.record_revision`（先 `pg_dump -t` 导出并校验行数）→
  `DROP FUNCTION fn_record_revision_append_only()` → 删除 `ck_anesthesia_record_lock_consistency` /
  `ck_anesthesia_record_lock_hash` / `ck_anesthesia_record_started` / `ck_anesthesia_record_status` →
  把 `status` 回退（`REVISED→ONGOING`、`LOCKED→ONGOING`）并清空锁列/`current_revision` →
  按 V13 定义重建 `ck_anesthesia_record_status`（仅 DRAFT/ONGOING）与
  `ck_anesthesia_record_started` → 删除新增列 → 重新执行 V13 的
  `CREATE OR REPLACE FUNCTION fn_anesthesia_record_guard` 恢复“仅 DRAFT/ONGOING”守卫 →
  删除权限点与角色授权（先 `DELETE FROM sys_role_permission`，再 `DELETE FROM sys_permission`）。
- **不可逆项**：`record_revision` 与锁单留痕属临床留痕，删除前必须导出到离线审计库；
  锁单后已生成的事件/用药/出入量不会被回滚删除（append-only 语义不变）。
- **重放**：修复后重新执行 V17 会重建修订表与触发器；已存在的锁单记录行若被回滚为
  `locked_at IS NULL`，重放不会恢复其锁单状态（需从离线归档按记录标识重新锁单，生产须人签字）。
- **临床风险**：回滚窗口内锁单记录可能重新变为“可编辑”，必须在停用相关接口与前端入口后
  执行；`m2:record:lock/revise` 权限与审计（RECORD_LOCK/RECORD_REVISE/RECORD_SIGN）不得
  随回滚删除（审计留存要求）。
- **回滚演练记录（2026-11-14，M3-M2-03 复审 S-4）**：当前环境 Docker 不可用（EXT-08），
  在嵌入式 PostgreSQL 16（`api-bootstrap` 集成测试同一实例）上执行了等价回滚 SQL 的静态
  演练：依次重建状态 CHECK、把 `LOCKED/REVISED` 统一回退为 `ONGOING`、清空锁列与
  `current_revision`、删除链守卫/冻结触发器与 `record_revision`，再按 V13 定义恢复
  `fn_anesthesia_record_guard`；演练确认 `LOCKED→ONGOING` 与 `REVISED→ONGOING` 均不会触发
  CHECK 失败（旧注释只回退 REVISED 会导致 LOCKED 行违反状态约束，已修正）。生产完整回滚
  演练（含备份恢复与临床归档）待 staging 具备 Docker/PG 后按 §4 流程执行并由 DBA 签字；
  未完成前不得对生产执行 V17 回滚。


### 4.13 V18（M2 复苏记录、Aldrete 评估与转出交接）回滚说明

V18 属 expand 段：新增 `m2_anes.pacu_record`（一条麻醉记录单同时最多一条未转出，部分唯一索引
`uk_pacu_record_active`）、`m2_anes.pacu_assessment`（append-only Aldrete 评估，五项 0–2、
总分 0–10 且等于五项之和）、`m2_anes.pacu_discharge`（每条复苏记录最多一条 append-only 转出，
`pacu_id` 唯一）；触发器：评估/转出 append-only（55000）、父记录转出后禁新增评估（55000）、
复苏记录守卫（身份/入室信息不可改、`DISCHARGED` 终态、`READY→MONITORING` 回落待确认 Q-8、
转出时间写一次且与 DISCHARGED 同置、DELETE/TRUNCATE 拒绝）；权限点 `m2:pacu:read`、
`m2:pacu:write`（授 SYS_ADMIN/SYS_ANESTHESIOLOGIST/SYS_NURSE_ANESTHETIST）。不改写
V1–V17 既有列与数据。

- **应用回滚**：下线 `/v1/anesthesia/pacu-records*` 全部端点与前端 PACU 页面入口
  （记录页“复苏记录（PACU）”按钮）；麻醉记录单仍可读写，新增表保留在库中不影响旧代码；
  PACU 审计（`PACU_ADMIT/PACU_ASSESS/PACU_DISCHARGE`）不得随回滚删除。
- **库层回滚**（前向修复，需 DBA 审批 + 备份 + 临床归档，顺序不可颠倒）：
  `DROP TRIGGER trg_pacu_assessment_discharged_insert ON m2_anes.pacu_assessment` →
  `DROP FUNCTION fn_pacu_assessment_guard()` →
  `DROP TRIGGER trg_pacu_assessment_no_update/no_delete/no_truncate` →
  `DROP TABLE m2_anes.pacu_discharge`（先 `pg_dump -t` 导出并校验行数）→
  `DROP FUNCTION fn_pacu_discharge_append_only()` →
  `DROP TABLE m2_anes.pacu_assessment` →
  `DROP FUNCTION fn_pacu_assessment_append_only()` →
  `DROP TRIGGER trg_pacu_record_guard_update/no_delete/no_truncate ON m2_anes.pacu_record` →
  `DROP FUNCTION fn_pacu_record_guard()` / `fn_pacu_record_no_truncate()` →
  `DROP TABLE m2_anes.pacu_record` →
  删除权限点与角色授权（先 `DELETE FROM sys_role_permission`，再 `DELETE FROM sys_permission`）。
- **重放**：修复后重新执行 V18 会重建空表与触发器；已归档的评估/转出**不得直接回灌**
  （append-only 语义），如需恢复须按原标识/原时间重新录入并在备注说明补录原因（须临床确认）。
- **临床风险**：回滚窗口内“转出质检”能力缺失，须通知 PACU 恢复纸质/其他系统记录；
  已转出与评估留痕属临床证据，回滚前必须归档到离线审计库并由医务处/麻醉科签字。
- **运行时权限**：append-only 与状态守卫依赖触发器，应用运行账号不得拥有 DDL 权限
  （见 §5.3）。

### 4.14 V19（M2 术后随访、镇痛与并发症）回滚说明

V19 属 expand 段：新增 `m2_anes.followup_record`（24/48 小时随访：类型 H24/H48、随访时间、
NRS 0–10、PONV 0–3、并发症 jsonb 数组、脱敏说明/反馈、随访人、version；append-only）与
`m2_anes.pain_management`（所属随访 FK、方式、药物、bolus/background/lockout、效果、
调整记录 jsonb；append-only）；触发器：两表 UPDATE/DELETE/TRUNCATE 一律 55000，
`followup_record` BEFORE INSERT 守卫要求父麻醉记录单存在、`patient_id` 与父记录一致、已结束
且 `assessed_at ≥ ended_at`（55000，评审 S-4）；
权限点 `m2:followup:read`、`m2:followup:write`（授 SYS_ADMIN/SYS_ANESTHESIOLOGIST/
SYS_NURSE_ANESTHETIST）。不改写 V1–V18 既有列与数据。

- **应用回滚**：下线 `/v1/anesthesia/followups*` 全部端点与前端随访页面入口（记录页
  “术后随访”按钮）；麻醉记录单/PACU 仍可读写，两张新增表保留在库中不影响旧代码；
  随访/镇痛审计（`FOLLOWUP_CREATE`/`PAIN_MANAGE`）不得随回滚删除。
- **库层回滚**（前向修复，需 DBA 审批 + 备份 + 临床归档，顺序不可颠倒）：
  `DROP TRIGGER trg_followup_record_guard_insert/no_update/no_delete/no_truncate ON
  m2_anes.followup_record` → `DROP FUNCTION fn_followup_record_guard_insert()` /
  `fn_followup_record_append_only()` / `fn_followup_record_no_truncate()` →
  `DROP TRIGGER trg_pain_management_no_update/no_delete/no_truncate ON m2_anes.pain_management`
  → `DROP FUNCTION fn_pain_management_append_only()` / `fn_pain_management_no_truncate()` →
  `DROP TABLE m2_anes.pain_management`（先 `pg_dump -t` 导出并校验行数）→
  `DROP TABLE m2_anes.followup_record` → 删除权限点与角色授权（先
  `DELETE FROM sys_role_permission`，再 `DELETE FROM sys_permission`）。
- **重放**：修复后重新执行 V19 会重建空表与触发器；已归档的随访/镇痛**不得直接回灌**
  （append-only 语义），如需恢复须按原标识/原时间重新录入并在说明注明补录原因（须临床确认）。
- **临床风险**：回滚窗口内 24/48h 随访与镇痛随访能力缺失，须通知麻醉科/护理部改用
  纸质/其他系统记录；随访与镇痛留痕属临床证据，回滚前必须归档到离线审计库并由
  医务处/麻醉科签字。
- **运行时权限**：append-only 与随访前置守卫依赖触发器，应用运行账号不得拥有 DDL 权限
  （见 §5.3）。

## 5. 运行方式

### 5.1 本地 / CI（无 Docker，EXT-08）

集成测试用 Zonky 嵌入式 PostgreSQL 16 真实执行迁移，不使用 H2：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -pl apps/api/modules/m0/impl -am test
# 全量后端验收
./scripts/mvn.sh -B -f apps/api/pom.xml verify
```

测试断言分两条路径：

- **生产路径**（locations 只含 `db/migration`）：空库 `migrate()` 成功、Flyway history 9 条（V1–V9）、
  二次 `migrate()` 执行 0 条、V1→V2 升级成功、约束/索引/注释存在、平台预置种子正确、
  `sys_user=0` 且无 `demo_admin`、审计表 UPDATE/DELETE/TRUNCATE 被数据库拒绝；
- **演示路径**（追加 `db/seed/demo`）：V100 后 `demo_admin`（ALL）、`demo_dept`（DEPT，绑定麻醉科）、
  `demo_self`（SELF）存在，`demo_admin` 绑定 `SYS_ADMIN`、`must_change_password=true`、BCrypt 校验演示口令通过；
- **数据范围**（`DataScopePersistenceIntegrationTest`，12 用例）：真实 PG 验证 ALL/DEPT/SITE/ROOM/SELF、
  未绑定 fail-closed、SITE 按 `path` 覆盖子树且不越界兄弟院区、撤销绑定（status=0）与停用组织不再生效、
  停用用户不出现在 DEPT/ROOM 成员结果、并发解析一致；`DataScopeApiIntegrationTest`（9 用例）
  验证 `/me` 返回真实 scopeIds、跨科室 403 + ACCESS_DENIED 审计、缺 `m0:org:read` 403；
- **Mapper 集成**（`MybatisPersistenceTest`）：jsonb/inet TypeHandler 往返、软删除过滤、
  旧 version 更新影响行数为 0、审计触发器在 Mapper 路径仍拒绝改写。

### 5.2 本地真实 PostgreSQL

```bash
# 1) 空库（示例；生产禁止用 postgres 超级用户跑应用）
createdb -h localhost -U postgres anesop

# 2) 由应用启动迁移（推荐：与应用同一套 Flyway）
SPRING_FLYWAY_LOCATIONS=filesystem:db/migration \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/anesop \
SPRING_DATASOURCE_USERNAME=anesop_app \
SPRING_DATASOURCE_PASSWORD='***' \
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar
```

### 5.3 生产

- 迁移脚本随交付物挂载到 **`/opt/anesop/db/migration`**（只读，属主 root，应用账号只读）。
- 通过环境变量指定位置，不把 SQL 打进 jar：

```bash
SPRING_FLYWAY_LOCATIONS=filesystem:/opt/anesop/db/migration
SPRING_FLYWAY_ENABLED=true
SPRING_FLYWAY_BASELINE_ON_MIGRATE=false   # 空库首次迁移；既有库升级须单独 baseline 评审
SPRING_FLYWAY_CLEAN_DISABLED=true         # 生产禁止 clean（默认即 true，显式写出）
SPRING_FLYWAY_VALIDATE_ON_MIGRATE=true
SPRING_FLYWAY_TABLE=flyway_schema_history
```

- 应用账号与迁移账号分离：迁移用具备 DDL 权限的专用账号；应用运行账号只授予
  `SELECT/INSERT/UPDATE/DELETE` 中业务必需项，**审计表只授予 SELECT/INSERT**。
- **生产 locations 只含 `db/migration`**，禁止出现 `db/seed/demo`；演示种子是独立
  Flyway location，仅本地/演示/集成测试显式追加：
  ```bash
  # 本地/演示（仅此场景允许第二个 location）
  SPRING_FLYWAY_LOCATIONS=filesystem:db/migration,filesystem:db/seed/demo
  # 生产（不得追加任何 seed 目录）
  SPRING_FLYWAY_LOCATIONS=filesystem:/opt/anesop/db/migration
  ```
- **启动安全守卫（S0-07，B-2）**：`anesop.security.demo-accounts-allowed` 默认 **false**
  （不依赖 profile），启动时校验 `m0_platform.sys_user` 中不存在 `demo_*` 账号
  （`username LIKE 'demo\_%'`，精确转义，`demouser` 不命中），命中即拒绝启动并告警。
  dev/test 的 `application-dev.yml` / `application-test.yml` 显式置 true 才允许演示账号。
  生产首次部署迁移后 `sys_user` 应为 0；后续账号只能来自院方身份源或实施管理员创建，
  不得来自演示种子。JWT 密钥同理（`anesop.security.jwt.allow-dev-generated` 默认 false）。
- 发布窗口内先跑迁移再启应用；迁移失败即停止发布，禁止应用“带病启动”。
- 迁移前必须有一次可验证的备份；跨大版本升级先在 staging 用同规模数据演练。

## 6. PostgreSQL 特性与 D-04 信创适配

V1 使用的 PG 特性**集中在 `m0_platform` 一个 schema**，避免扩散到 M1–M8：

| 特性 | 用途 | 信创适配方式（D-04） |
|---|---|---|
| `uuid` + `gen_random_uuid()` | 主键 | 国产库原生 UUID 或 `char(36)`；生成改应用侧 UUIDv7/雪花并由适配层注入 |
| `jsonb` | 审计 `detail` 前后值 | `json`/`text` + 应用侧序列化；查询能力降级需评估 |
| `inet` | 审计来源 IP | `varchar(45)`，应用侧校验 IPv4/IPv6 |
| `timestamptz` | 全部时间列 | 统一 `timestamp with time zone` 等价类型；禁止裸 `timestamp` |
| PL/pgSQL 触发器 | 审计 append-only | 国产库触发器/规则等价实现，或仅靠权限 + 应用审计网关；须重跑 append-only 用例 |
| 部分唯一索引 | `employee_no` 非空唯一 | 国产库支持度不一；退化为普通唯一索引 + 应用校验 |
| `COMMENT ON` | 中文表/列注释 | 多数国产库兼容；不兼容时落 `docs/data-dictionary/` |

约定：

- 不在迁移脚本使用 PG 数组、range、自定义类型、`RETURNING` 之外的扩展语法；
- 不使用 `serial/bigserial`（主键统一 uuid；日志表未来分区另议）；
- 所有时间由服务端写入，应用层统一 `Asia/Shanghai` 展示；客户端时间只作展示（§3.4.5）；
- 应用启动校验 NTP 偏差（>500ms 告警、>5s 拒绝写入）由 S0-07 之后的运行时实现，
  数据库层只保证 `timestamptz` 存储。

## 7. 审计留存与权限

- `m0_platform.sys_audit_log` 无 `updated_at`、无 `deleted`，只追加；
- 触发器 `trg_sys_audit_log_no_update/no_delete/no_truncate` 拒绝改写，
  SQLSTATE `55000`，测试 `FlywayMigrationTest#auditLogRejectsUpdateDeleteAndTruncate` 覆盖；
- 在线留存 **≥ 6 个月**（等保三级 §3.9.1），方案要求按月分区、在线 12 个月后归档
  MinIO（Parquet，按年打包）；分区与归档作业在 M8/运维任务落地，本基线先建索引；
- 运行时账号只授予 `SELECT/INSERT`；表属主/超级用户仍被触发器拦截（第二道防线）；
- 归档删除须先写归档台账（行数、校验和、操作人、时间），保留可审计证据；
- 审计 `detail` 禁止写入患者姓名、证件号、住院号、手机号等直接标识（§3.9.3）。

## 8. 已知边界与后续待办（编号承接）

以下事项为 S0-06 评审登记、**本任务不实现**，按编号在后续任务承接：

| 编号 | 事项 | 承接点 |
|---|---|---|
| S-1 | user → dept/room 绑定表（用户与科室/术间的数据范围绑定） | **已完成**：V5 + `DefaultDataScopeResolver`（M2-M0-01） |
| S-2 | `SYS_ADMIN` 权限拆分（平台管理员与业务管理员分离） | M1 实施基线 |
| S-5 | `phone_hash` 列已由 V3 expand 就绪（varchar(128)，与 `phone_enc` 配套）；加密/HMAC 写路径 | M1 |
| S-9 | 数据库最小权限 GRANT / initdb 脚本（迁移账号与应用账号分离） | S0-07 或 S0-10 |
| Q-2 | 患者/术间表归属哪个 schema（M1 与 M2 边界） | M1 |
| Q-3 | 多院区 `tenant_id`/`site_id` 字段引入时点（expand-contract） | M1 前 |
| Q-4 | 审计按月分区/归档的门槛（行数、留存窗口与运维流程） | 首个真实数据前 |

### 8.1 数据范围绑定表（V5，M2-M0-01）

| 表 | 内容 | 关键约束 |
|---|---|---|
| `sys_org` | 组织机构（数据范围对象）：`org_type` = SITE 院区 / DEPT 科室，树形 `parent_id` + `path` | `uk_sys_org_code` 唯一；`ck_sys_org_org_type`；软删除不释放编码 |
| `sys_user_org` | 用户—组织绑定：SITE/DEPT 范围 `scopeIds` 来源；`updated_at/updated_by` 记录授权变更 | 复合主键 `(user_id, org_id)`；`uk_sys_user_org_primary` 保证每用户最多一个主科室 |
| `sys_user_room` | 用户—术间绑定：ROOM 范围 `scopeIds` 来源 | 复合主键 `(user_id, room_id)`；`status=1` 才生效；`room_id` 为逻辑外键（术间主数据 M2-M2-01 在 `m1_sched` 建立） |

- 解析语义（fail-closed）：角色声明 SITE/DEPT/ROOM 但无生效绑定、角色范围未知或查询异常 →
  一律 `NONE`（接口 403），不得降级为 ALL/SELF；任一角色 `ALL` 即全院可见。
- **SITE 子树（Q-1）**：`data_scope=SITE` 时范围覆盖 `sys_org.path` 前缀匹配的整棵子树
  （院区自身 + 下属全部科室），不是只取院区直属成员；`DEPT` 只精确匹配本科室。
- 成员口径统一（S-5）：DEPT/ROOM 成员查询与 ALL 路径一致，只返回 `sys_user.status=1` 且未删除用户。
- 生产迁移路径只建结构，不写组织/绑定数据（`FlywayMigrationTest#dataScopeBindingTablesAreReady` 断言为 0）；
  演示/测试数据只在 `db/seed/demo` 与测试夹具中出现，均为 SYNTHETIC。
- 回滚：forward-only。回滚时应用侧停止读取绑定表（范围 fail-closed 到 NONE）+ 前向修复；
  不删除表（删除会丢失范围绑定证据，且 Flyway 不执行 undo）。
- 后续：组织 CRUD 接口（`m0:org:read` 已由 V5 授予 SYS_ADMIN 与 SYS_OR_MANAGER，`m0:org:write`
  仅 SYS_ADMIN）在 M2 组织管理任务落地；
  MyBatis 数据权限拦截器自动注入条件在 M1 排程/记录查询落地。

补充说明：

- V1 **尚未包含** `tenant_id`/`site_id`（Q-3）：多院区上线前必须用 expand-contract 增加
  两列并重建唯一索引（如 `username` 改为 `(tenant_id, username)`），否则与 §3.4.5 不一致；
- 审计按月分区、大表归档、`CREATE INDEX CONCURRENTLY` 尚未落地（Q-4），属 M8/运维任务；
- 认证服务、审计写入服务、MyBatis 拦截器与 `@Version` 自动填充的运行时装配由 S0-07 负责；
  `UuidTypeHandler`/`JsonbTypeHandler`/`InetTypeHandler` 需要在 S0-07 的 MyBatis 配置中注册
  （uuid 全局注册；jsonb/inet 已由实体 `@TableField(typeHandler=...)` 绑定）；
- Testcontainers 用例因 Docker 不可用标记 `EXT-08`，恢复后补跑容器化路径（与嵌入式 PG 二选一）。
