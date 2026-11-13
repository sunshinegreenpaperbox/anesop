# 追溯矩阵（需求 → 实现 → 测试）

> 每完成一个模块追加行；验收时按此表抽查。章节号对应方案书。

| 需求/决策 | 章节 | 契约 | 后端 | 前端 | 测试 | 状态 |
|---|---|---|---|---|---|---|
| 排程冲突检测 | 2.4.1 | scheduling.yaml | m1/scheduling | 排程工作台 | SchedulingConflictTest | 待验 |
| 术前访视与评分 | 2.4.2 | anesthesia.yaml | m2/preop | 访视单 | PreopScoreTest | 待验 |
| 麻醉记录时间轴 | 2.4.2 | anesthesia.yaml | m2/intraop | 记录单 | TimelineTest | 待验 |
| 采集断网补传 | 3.6 | device.yaml | gateway | — | GatewayBufferTest | 待验 |
| 锁单与修订 | 2.4.2 | anesthesia.yaml | m2/record | 记录单 | LockRevisionTest | 待验 |
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
