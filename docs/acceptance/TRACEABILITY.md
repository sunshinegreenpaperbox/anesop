# 追溯矩阵（需求 → 实现 → 测试）

> 每完成一个模块追加行；验收时按此表抽查。章节号对应方案书。

| 需求/决策 | 章节 | 契约 | 后端 | 前端 | 测试 | 状态 |
|---|---|---|---|---|---|---|
| 排程冲突检测 | 2.4.1 | scheduling.yaml | m1/scheduling | 排程工作台 | SchedulingConflictTest | 待验 |
| 术前访视与评分 | 2.4.2 | anesthesia.yaml | m2/preop | 访视单 | PreopScoreTest | 待验 |
| 麻醉记录时间轴 | 2.4.2 | anesthesia.yaml | m2/intraop | 记录单 | TimelineTest | 待验 |
| 采集断网补传 | 3.6 | device.yaml | gateway | — | GatewayBufferTest | 待验 |
| 锁单与修订 | 2.4.2 | anesthesia.yaml | m2/record | 记录单 | LockRevisionTest | 待验 |
| 麻精双人核对 | 2.4.5 | drug.yaml | m5 | 药品台账 | NarcoticDoubleCheckTest | 待验 |
| 权限与数据范围 | 3.9 | auth.yaml | m0/auth | 菜单/按钮 | PermissionTest | 待验 |
| 质控指标计算 | 2.4.7 | quality.yaml | m7 | 质控看板 | QcIndicatorTest | 待验 |
| 驾驶舱态势 | 2.4.8 | dashboard.yaml | m8 | 大屏 | DashboardSmokeTest | 待验 |
