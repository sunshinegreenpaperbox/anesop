# ANESOP 故障处置速查（值班）

> 用途：值班第一时间的"症状 → 判断 → 动作 → 升级"。不替代
> [`EMERGENCY-PLAYBOOK.md`](EMERGENCY-PLAYBOOK.md)（重大故障按预案走）。
> 原则：先保临床；不整机重启数据库；不删日志/队列；不确定就升级。

## 1. 通用排查顺序

1. **入口**：`curl -fsS https://<域名>/healthz`；浏览器 502/504/空白？
2. **应用**：`curl -fsS http://127.0.0.1:8080/actuator/health`；进程/容器状态；
3. **依赖**：PG `pg_isready`、Redis `ping`、RabbitMQ `ping`、MinIO `mc admin info`；
4. **变更**：最近发布/配置/证书/网络变更？刚变更优先回滚；
5. **证据**：时间、范围、traceId、错误码、脱敏截图；不要贴患者信息。

## 2. 症状速查

| 症状 | 可能原因 | 动作 | 升级 |
|---|---|---|---|
| 502 Bad Gateway | API 未启动/端口错/健康失败 | `systemctl status anesop-api` / `compose ps`；看 API 日志；必要时回滚 | P1 |
| 504 Gateway Timeout | API 慢/DB 锁/队列阻塞 | 查 DB 会话与慢查询、线程池、GC；先限流/停批处理 | P1 |
| 登录 401 | 口令错/账号停用/令牌过期 | 确认账号状态；重新登录；不要连续试（会 429/锁定） | P2 |
| 登录 429 | 触发登录限流（IP+用户名） | 等 `Retry-After`；查脚本重试；不要狂点 | P3 |
| 页面 403 | 缺权限/数据范围越界 | 查角色与绑定；按最小权限补；不借管理员账号 | P2 |
| 页面 404 | 对象不存在或不在范围（防泄露） | 核对对象/范围；不是重试能解决 | P3 |
| 500 | 代码/数据/依赖异常 | 取 traceId 查日志；可复现则建缺陷；影响面大先回滚 | P0/P1 视影响 |
| 页面空白/转圈 | 静态资源/会话/网络/API | 刷新、清缓存、查 `/healthz`、查浏览器控制台 | P2 |
| 报表慢/超时 | 数据量大/缺索引/并发 | 缩小范围；查慢查询；停批量；必要时限流 | P2 |
| 看板 10s 不更新 | 轮询暂停/终端休眠/网络/会话 | 点"继续/立即刷新"；查网络与终端电源 | P3 |
| 队列积压 | 消费者停/DB 慢/事件风暴 | 查 RabbitMQ 队列与消费者；先恢复消费；不要清空队列 | P2 |
| 磁盘满 | 日志/镜像/备份/Loki | 清轮转日志/旧镜像（保留 2 版）；扩盘；查备份盘 | P2 |
| 证书过期/告警 | 未续签 | 续签 → `nginx -t` → reload；HSTS 下尽快 | P1 |
| 时间偏移 | NTP 未同步/终端时区 | `timedatectl`；校时；审计时间异常要记录 | P2 |
| Redis 不可用 | 实例/口令/网络 | 查 `redis-cli ping`；应用降级（会话/缓存）评估；勿清库 | P1 |
| RabbitMQ 不可用 | 实例/磁盘/连接 | 查磁盘/连接；恢复；事件补偿；勿删队列 | P1 |
| MinIO 不可用 | 实例/磁盘/凭据 | 查 `mc admin info`；恢复；文书/波形读写受影响 | P1 |
| ingest 401 | 网关令牌不匹配/未配置 | 核对 `ANESOP_M2_ACQUISITION_GATEWAY_TOKEN`；不打印令牌 | P1 |
| ingest 404 | 设备未登记 | 先登记设备再采集；核对 gatewayId/deviceId | P2 |
| ingest 409 | 并发/批次冲突 | 按 batchKey 重试（幂等）；查重复批次 | P2 |
| 导出为空/列少 | 无权限/范围/区间无数据 | 查 `*:export` 与日期；不要改库补数 | P3 |
| 锁单 409/400 | 前置未满足/未确认清单 | 按 `details[]` 补齐；锁后只读走修订 | P3（业务） |
| Flyway 校验失败 | 迁移被改/顺序错/演示库混用 | 停止发布；核对 `flyway_schema_history`；演示库不得当生产 | P1 |
| 备份失败 | 口令/磁盘/权限/网络 | 按备份手册查；连续失败 P2；未演练备份视为不可用 | P2 |

## 3. 单服务重启纪律

```bash
# 进程形态：一次重启，观察 60s；失败即停手并回滚/升级
sudo systemctl restart anesop-api
sudo systemctl status anesop-api --no-pager

# Compose：只重启目标服务，不 down 整个栈
docker compose -f deploy/compose/docker-compose.yml restart api
```

禁止：循环重启、`down -v`、删除卷、清空队列、生产执行逆向 SQL、临时关闭鉴权/审计。

## 4. 需要立即升级的信号

- 记录保存失败/丢失、签名异常、越权或疑似泄露（P0）；
- 数据库主库不可用、全系统不可用、采集全断（P1）；
- 发布后 10 分钟内错误率持续上升；
- 同一症状 30 分钟未定位，或临时措施无效；
- 任何"需要改数据/改 schema"的操作。

## 5. 交接模板（值班）

```text
时间：
影响范围（科室/术间/功能）：
症状与错误码/traceId：
已做动作与结果：
当前状态（恢复/降级/仍异常）：
未闭环告警与下次检查时间：
需要下一班跟进：
```

> 详细命令与拓扑见 [`../deploy/README.md`](../deploy/README.md) §11、
> 开发环境问题见 [`../guides/troubleshooting.md`](../guides/troubleshooting.md)。
