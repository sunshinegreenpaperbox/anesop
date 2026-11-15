# M9 维护迭代任务板（M9-01 收口三项遗留）

- 建立日期：2026-11-15
- 输入：`docs/acceptance/FINAL-REPORT.md` §6.2 三项遗留（消息业务投递、记录单 `vitalSigns`、
  抢救用药麻精判定）、ADR-0025
- 约束：不新增外部依赖；不写真实数据/PHI；不改 V1–V28；临床/管理口径未确认项一律标
  **【待确认】** 并登记 Q；`SYNTHETIC` 数据仅用于测试/演示。

## M9-01 三项遗留收口（本次）

| 子项 | 目标 | 写域 | 验收 | 状态 |
|---|---|---|---|---|
| M9-01-A 消息业务投递 | m1 `NotificationPort.deliver` 从“日志模拟”扩展为写 m0 消息中心：停改台/急诊审批、m2 CRITICAL 告警按默认通知矩阵投递到角色启用用户；同业务事件+接收人+模板去重；无 PHI | `apps/api/modules/{m0,m1,m2}/**`、`db/migration/V29`、契约 `platform.yaml`/baseline/生成 TS、文档 | 审批→消息落库、去重、无 PHI、无权限角色不收、消息中心可见未读 | 完成 |
| M9-01-B 体征映射 | 记录单 `vitalSigns` 返回记录时间窗内该台次术间设备采样（metric/value/unit/measuredAt/quality/qualityFlags），按时间升序、≤2000 条并标记截断 | `apps/api/modules/m2/**`、契约 `anesthesia.yaml` 0.11.0、前端记录页、文档 | 窗口过滤/质量标记/空数据/截断、无 PHI；前端体征表与趋势占位 | 完成 |
| M9-01-C 抢救麻精 | 抢救 `drugs` 每项经 m4 药品字典解析；未收录 400；麻精必须第二核对人（缺失/同人/不存在/停用/无权限 400），复用记录单资格规则 | `apps/api/modules/m2/**`、契约 `anesthesia.yaml` 0.11.0、前端抢救表单、文档 | 声明 false 被字典纠正/拒绝、缺核对 400、成功留痕、审计无姓名 | 完成 |

## 遗留与后续（不在 M9-01 范围）

| 编号 | 事项 | 说明 |
|---|---|---|
| M9-02 | 通知频率/免打扰/升级路径 | 见 ADR-0025 Q-M9-01；院方管理口径确认后实现，可能新增调度配置或聚合表 |
| M9-03 | SYS_ADMIN 是否收取临床告警 | Q-M9-02；只需追加路由角色 |
| M9-04 | 体征聚合/抽稀与独立体征查询端点 | Q-M9-03；如页面性能不达标，additive 新增 `GET /records/{id}/vitals` 分页 |
| M9-05 | 抢救麻精历史回溯 | Q-M9-04；医务/药学确认后再定，不回溯改写历史 jsonb |
| M9-06 | 真实短信/企业微信通道 | EXT；接入时替换 `NotificationPort` 实现，不改业务代码与契约 |
| M9-07 | 契约 0.x deprecated 清理 | ADR-0011；1.0.0 大版本处理 |

## 验收命令

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml clean verify
bash scripts/gen-contracts.sh && bash scripts/gen-contracts.sh --java
bash scripts/contract-check.sh
pnpm test:unit && pnpm build && pnpm lint && pnpm e2e
```

## 证据索引

- 消息：`ScheduleChangeApiIntegrationTest#stopDeliversDeduplicatedMessagesToRoleUsersAndIsVisibleUnread`、
  `AlertRescueApiIntegrationTest#criticalAlertIsDeliveredToRoleUsersAsMessageWithoutPhi`、
  `MessageCenterNotificationPortTest`、`PlatformMessageServiceTest`（去重/接收人目录）
- 体征：`AnesthesiaRecordApiIntegrationTest#recordVitalSignsAreMappedFromRoomDevicesWithinWindow`、
  `...draftRecordWithoutStartedAtHasEmptyVitalSigns`、`RecordView.spec`（体征表/质量标记/截断）
- 抢救：`ResuscitationServiceImplTest`、`AlertRescueApiIntegrationTest#resuscitationDrugNarcoticIsDeterminedByDictionaryAndRequiresSecondChecker`、
  `RecordView.spec`（字典联想/第二核对人）
