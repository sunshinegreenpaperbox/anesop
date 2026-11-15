# ANESOP 运行手册（Runbook）索引

> 适用：试点上线后的值班、故障处置与应急。目标：**先保临床，再保系统；先止血，再归因**。
> 关联：部署 [`../deploy/README.md`](../deploy/README.md)、回滚
> [`../deploy/ROLLBACK.md`](../deploy/ROLLBACK.md)、备份
> [`../deploy/backup-restore.md`](../deploy/backup-restore.md)、上线检查
> [`../deploy/GO-LIVE-CHECKLIST.md`](../deploy/GO-LIVE-CHECKLIST.md)。

## 1. 文档

| 文档 | 用途 |
|---|---|
| [`EMERGENCY-PLAYBOOK.md`](EMERGENCY-PLAYBOOK.md) | 六类应急预案：数据库/应用/采集/CA/网络分区/疑似数据泄露 |
| [`FAULT-PLAYBOOK.md`](FAULT-PLAYBOOK.md) | 常见故障症状 → 判断 → 处置（值班速查） |
| [`CONTACTS.md`](CONTACTS.md) | 联系人、通知链、升级路径、通知模板 |
| [`../deploy/DRILL-RECORD-TEMPLATE.md`](../deploy/DRILL-RECORD-TEMPLATE.md) | 演练记录模板 |
| [`../deploy/drills/`](../deploy/drills/README.md) | 已归档演练记录 |

## 2. 事件分级与响应

| 级别 | 判定 | 首次响应 | 目标恢复 | 通知 |
|---|---|---|---|---|
| **P0** | 记录丢失、签名失效、越权、数据泄露；全系统不可用 | **2 小时内**响应 | 立即止血，24 小时内修复 | 电话 + 短信：信息科负责人、项目经理、信息安全负责人、医务处 |
| **P1** | 业务不可用/采集全断/主库故障 | 立即 | 尽快（按 RTO/RPO） | 电话 + 企业微信 |
| **P2** | 接口失败率升高/主备切换/备份失败 | 30 分钟内 | 当班处理 | 企业微信 |
| **P3** | 磁盘/队列/证书到期/单点异常 | 当日 | 计划处理 | 邮件/工单 |

> P0 的安全事件（疑似泄露）按 `AGENTS.md`：立即停止、保留证据、**30 分钟内**上报信息安全
> 负责人与项目经理；不得自行删除日志/数据。

## 3. 通用处置流程（五步）

1. **发现与确认**：监控/用户报障 → 记录时间、影响范围、症状、traceId/截图（脱敏）；
2. **止血**：停止扩散（停导出/停写入/切只读/回滚/隔离账号），优先保临床；
3. **恢复/切换**：按预案回滚、切主备、补传、重启单服务；不整机重启数据库；
4. **验证**：健康、登录、关键路径、数据一致性、审计完整；
5. **留痕与复盘**：事故单（时间线/根因/影响/措施）、变更单、演练记录；P0/P1 24 小时内初判。

## 4. 值班纪律

- 只通过堡垒机/受控终端操作；不在个人设备保存患者数据；
- 变更先备份、先评审；紧急变更事后补单；
- 不猜测、不静默重启、不删除证据；不确定时升级；
- 交接班写清：未闭环告警、临时措施、下次检查时间；
- 对外沟通统一由发布负责人/信息科出口，避免多头信息。

## 5. 常用入口

```bash
# 健康（示例域名/地址按院方替换）
curl -fsS https://<院方域名>/healthz
curl -fsS http://127.0.0.1:8080/actuator/health

# 演练脚本（只读/计划）
bash scripts/drill-health.sh --dry-run
bash scripts/drill-backup-restore.sh --dry-run
bash scripts/drill-offline-replay.sh --dry-run
```

> 真实部署的命令（Compose/进程）见 [`../deploy/DEPLOYMENT.md`](../deploy/DEPLOYMENT.md)；
> 本手册不重复拓扑细节，避免两处漂移。
