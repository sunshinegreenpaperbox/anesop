# 2026-11-15 本地静态/干跑基线演练记录

> 按 [`DRILL-RECORD-TEMPLATE.md`](../DRILL-RECORD-TEMPLATE.md) 简化填写。
> 场景：M6 上线准备工具可用性（非生产、无 Docker、EXT-08）。

## 1. 基本信息

| 项 | 内容 |
|---|---|
| 演练编号 | DR-20261115-01 |
| 场景 | 部署静态校验 + 数据初始化/演练脚本干跑 |
| 日期与时间 | 2026-11-15（施工机本地） |
| 环境 | 施工机（Linux，JDK 21，无 Docker daemon） |
| 版本 | 工作区 `main`（M6 变更未提交） |
| 参与人 | 施工代理（自动执行）；人工复核待补 |
| 关联 | `docs/ai/tasks/M6-backlog.md` M6-M20-01/02/05 |

## 2. 执行与结果

| 检查 | 命令 | 结果 |
|---|---|---|
| Compose/nginx/Dockerfile 静态校验 | `bash scripts/compose-check.sh` | PASS 34 / FAIL 0 / SKIP 1（Docker 运行时，EXT-08） |
| 数据初始化计划 | `bash scripts/init-data.sh --dry-run` | PASS（prod 模式计划；未连库） |
| 健康检查（静态项） | `bash scripts/drill-health.sh` | PASS 2 / SKIP 3（API/Web/DB 未提供） |
| 健康检查计划 | `bash scripts/drill-health.sh --dry-run` | PASS |
| 备份恢复计划 | `bash scripts/drill-backup-restore.sh --dry-run` | PASS（pg_dump/pg_restore/psql 未安装，SKIP） |
| 断网补传计划 | `bash scripts/drill-offline-replay.sh --dry-run` | PASS（未发请求） |
| 演练脚本帮助 | `--help`（三个脚本） | PASS，退出码 0 |

## 3. 未执行项与原因

| 项 | 状态 | 原因/解除条件 |
|---|---|---|
| 容器构建/启动、`docker compose config` | SKIP | EXT-08 Docker 不可用；具备 Docker 24+ 的宿主机上执行 |
| 真实 API/Web/DB 健康检查 | SKIP | 本机无部署实例；发布后在预发/生产执行 |
| 备份恢复实测 | SKIP | 本机无 PG16 客户端/实例；在演练环境用 `--execute` |
| 断网补传实测 | SKIP | 需已登记设备与网关令牌；在联调/预发执行 |
| 应用回滚 ≤10 分钟 | 未演练 | 需预发环境与上一版本制品；发布前完成 |

## 4. 结论

- 静态/干跑基线 PASS；**实时演练未完成**，不得据此宣称上线就绪。
- 上线前须在预发/演练环境完成：应用回滚、备份恢复、断网补传、健康检查实时项，
  并按模板补齐参与人与签字。
