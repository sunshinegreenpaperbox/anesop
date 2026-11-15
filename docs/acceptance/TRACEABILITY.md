# 追溯矩阵（需求 → 实现 → 测试）

> 每完成一个模块追加行；验收时按此表抽查。章节号对应方案书。

| 需求/决策 | 章节 | 契约 | 后端 | 前端 | 测试 | 状态 |
|---|---|---|---|---|---|---|
| 排程冲突检测 | 2.4.1 | scheduling.yaml | m1/scheduling | 排程工作台 | SchedulingConflictTest | 待验 |
| 术前访视与评分 | 2.4.2 | anesthesia.yaml | m2/preop | 访视单 | PreopScoreTest | 待验 |
| 麻醉记录时间轴 | 2.4.2 | anesthesia.yaml | m2/intraop | 记录单 | AnesthesiaRecordApiIntegrationTest / ClinicalRecordAcceptanceTest | 已实现（M3-M2-01/M3-QA-01，append-only+并发 seq） |
| 采集断网补传 | 3.6 | device.yaml | gateway + m2/acquisition | — | DeviceAcquisitionApiIntegrationTest / ClinicalRecordAcceptanceTest / m2-device-ingest.spec.ts（E2E-02） | 已实现（M3-M8-01/M3-QA-01；E2E 为 UI+契约层，真实幂等以集成测试为准） |
| 锁单与修订 | 2.4.2 | anesthesia.yaml | m2/record | 记录单 | RecordLockRevisionApiIntegrationTest / ClinicalRecordAcceptanceTest / m2-record-lock.spec.ts（E2E-03） | 已实现（M3-M2-03/M3-QA-01；CA 占位 EXT-05） |
| 麻精双人核对 | 2.4.6 / 3.4.2 | drug.yaml（M5 建） | m5 | 药品台账 | NarcoticDoubleCheckTest | 待验 |
| 权限与数据范围 | 3.9.2 | auth.yaml | m0/auth | 菜单/按钮 | PermissionTest | 待验（S0-07） |
| 质控指标计算 | 2.4.8 | quality.yaml（M7 建） | m7 | 质控看板 | QcIndicatorTest | 待验 |
| 驾驶舱态势 | 2.4.9 | dashboard.yaml（M8 建） | m8 | 大屏 | DashboardSmokeTest | 待验 |
| 登录/刷新/撤销 | 3.9.2/3.9.5 | auth.yaml | m0/auth | 登录/改密页 | AuthApiIntegrationTest（bootstrap） | 已实现（S0-07） |
| 权限越权 403 + 审计 | 3.9.1/3.9.2 | auth.yaml | m0/audit | 403 页/菜单过滤 | AuthApiIntegrationTest（ACCESS_DENIED） | 已实现（S0-07） |
| 审计 append-only | 3.9.1 | — | db/V1 触发器 | — | FlywayMigrationTest | 已实现（S0-06） |
| 演示数据隔离 | 3.9.1 | — | db/seed/demo | — | DemoSeedMigrationTest | 已实现（S0-06） |
| 契约兼容与生成物漂移 | 3.5 | 4 域 | — | 生成 TS/Java DTO | contract-check（lint/breaking/drift） | 已实现（S0-05/S0-07） |
| 前端 401 刷新重放 | 3.9.5 | auth.yaml | — | http.ts | http.spec.ts | 已实现（S0-08） |
| 登录→首页→受保护接口 | ACCEPTANCE M1 | auth.yaml | m0/auth | 登录/主布局 | e2e + 真实联调 | 已实现（S0-08/S0-12，Docker 受限） |
| 数据范围绑定与解析 | 3.9.2 | auth.yaml 0.4.0 | m0/security | — | DataScopePersistenceIntegrationTest / DataScopeApiIntegrationTest | 已实现（M2-M0-01，review PASS） |
| 手机号加密与脱敏 | 3.9.3 | auth.yaml 0.5.0 | m0/crypto + 用户管理 | — | PhoneCrypto/PhoneHasher/UserAdminApiIntegrationTest | 已实现（M2-M0-02，review PASS） |
| E2E-04 权限越权（UI 级） | 3.9.2 | auth.yaml | m0 权限 | 前端 403/菜单过滤 | apps/web/e2e/auth.spec.ts | 已实现（M2-M0-03；真实 API 越权由集成测试覆盖） |
| 登录提交 E2E | ACCEPTANCE C | auth.yaml | m0/auth | 登录页 | apps/web/e2e/auth.spec.ts | 已实现（M2-M0-03，API 拦截） |
| 手术申请接收/导入/取消 | 2.4.2 | scheduling.yaml 0.3.0 | m1/application + HIS 适配器 | m1 列表/详情 | SurgeryApplicationApiIntegrationTest / m1-applications.spec.ts | 已实现（M2-M1-01，review 清障） |
| 患者摘要与脱敏检索 | 2.4.2/3.9.3 | patient.yaml 0.3.0 | m1/patient | m1 列表 | SurgeryApplicationApiIntegrationTest（masked） | 已实现（M2-M1-01） |
| 术前访视与风险评分 | 2.4.3 | anesthesia.yaml 0.3.0 | m2/preop | 术前访视页 | PreopScoreCalculatorTest / PreopAssessmentApiIntegrationTest | 已实现（M2-M4-01，review PASS；阈值待临床确认） |
| 知情同意与三方核查 | 2.4.3 | anesthesia.yaml 0.4.0 | m2/consent+threeway | 同意/核查页 | ConsentApiIntegrationTest | 已实现（M2-M5-01，review PASS；CA 占位 EXT-05） |
| 排程分析指标 | 2.4.2/2.7.2 | scheduling.yaml 0.6.0 | m1/analytics | 排程分析页 | SchedulingAnalyticsApiIntegrationTest | 已实现（M2-M6-01；口径待确认 ADR-0015） |
| 术中预警与抢救记录 | 2.4.3 | anesthesia.yaml 0.7.0 | m2/alert+rescue | 记录单告警面板 | AlertRescueApiIntegrationTest | 已实现（M3-M2-02；阈值待临床确认 ADR-0017） |
| 复苏记录与转出（Aldrete） | 2.4.3 | anesthesia.yaml 0.9.0 | m2/pacu | PACU 页面 | PacuRecoveryApiIntegrationTest | 已实现（M3-M10-01；阈值待确认） |
| 术后随访与镇痛（PCA） | 2.4.3 | anesthesia.yaml 0.10.0 | m2/followup | 随访页 | FollowupPainApiIntegrationTest | 已实现（M3-M11-01；PHI 脱敏已评审修复） |
| E2E-01 排程冲突 | 2.4.1 | scheduling.yaml | m1/scheduling | 排程工作台 | m1-schedule-conflict.spec.ts | 已实现（M2-M2-01，API 拦截） |
| E2E-02 采集断网补传 | 3.6 | device.yaml | gateway + m2/acquisition | 记录单体征 | m2-device-ingest.spec.ts + DeviceAcquisitionApiIntegrationTest + ClinicalRecordAcceptanceTest | 已实现（M3-QA-01，2026-11-14） |
| E2E-03 记录锁单修订 | 2.4.2 | anesthesia.yaml 0.8.1 | m2/record | 记录单 | m2-record-lock.spec.ts + RecordLockRevisionApiIntegrationTest | 已实现（M3-M2-03/M3-QA-01） |
| 临床记录专项（发布准入） | AGENTS.md | anesthesia.yaml + device.yaml | m2/record + m2/acquisition | 记录单/PACU | ClinicalRecordAcceptanceTest（时间轴/补传/并发/签名锁定）+ 专项矩阵 docs/testing §9 | 已实现（M3-QA-01，2026-11-14；失败即阻断发布） |
| 药品目录与麻精五专 | 2.5 | drug.yaml 0.1.0 | m4/drug | 药品/五专页 | M4DrugApiIntegrationTest | 已实现（M4-M15-01；T-4 收口；EXT-02 模拟） |
| 人员档案与分级授权/排班/培训 | 2.5 | staff.yaml 0.1.0 | m4/staff | 人员/授权/排班页 | M4StaffApiIntegrationTest | 已实现（M4-M13-01；R1–R5 映射待确认） |
| 耗材主数据与 UDI 追溯/SPD | 2.5 | material.yaml 0.1.0 | m4/material | 耗材/追溯页 | M4MaterialApiIntegrationTest | 已实现（M4-M14-01；EXT-03 模拟） |
| 工作量与多维报表/导出 | 2.7.2 | report.yaml 0.1.0 | m4/report | 报表页 | M4ReportApiIntegrationTest | 已实现（M4-M16-01；权重口径待确认） |
| 质控指标/不良事件 RCA/病历抽查/上报导出 | 2.4.8/2.7.2 | qc.yaml 0.1.0 | m4/qc | 质控三页 | M4QcApiIntegrationTest / M4AcceptanceTest | 已实现（M4-M17-01；指标清单与阈值待确认 ADR-0022） |
| M4 管理域专项（发布准入） | AGENTS.md 权限/导出脱敏/追溯/测试要求 | drug/material/report/qc + auth | m4 + m0 权限/审计 | M4 列表/五专/追溯/报表/质控页 | M4AcceptanceTest（权限矩阵/授权治理/DataScope/5 类 CSV/五专对账/UDI 追溯/报表与质控手算）+ m4-narcotic-reconcile.spec.ts + m4-qc-export.spec.ts + 专项矩阵 docs/testing §11 | 已实现（M4-QA-01，2026-11-15；药品/耗材 CSV 导出契约未定义，登记遗留项） |
| 风险与资源大屏 | E-M18 | dashboard.yaml 0.2.0 | m5/dashboard | 风险大屏 | DashboardRiskResourcesServiceImplTest | 已实现（M5-M18-02） |
