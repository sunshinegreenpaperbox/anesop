# ANESOP 回滚手册（应用 ≤10 分钟 / 数据库向前兼容）

> 依据：方案书 §3.8.4（发布与回滚，应用回滚目标 **≤10 分钟**）、§3.7.3（备份恢复）、
> `AGENTS.md`「发布」（数据库先加字段后改逻辑、分两次发布）。
> 关联：[`DEPLOYMENT.md`](DEPLOYMENT.md)（发布步骤）、[`backup-restore.md`](backup-restore.md)
> （PITR）、[`GO-LIVE-CHECKLIST.md`](GO-LIVE-CHECKLIST.md)（回滚演练项）、
> [`../runbook/EMERGENCY-PLAYBOOK.md`](../runbook/EMERGENCY-PLAYBOOK.md)（故障处置）。

## 1. 原则与边界

1. **应用可回滚，数据库只向前**：迁移使用 expand-contract；回滚应用时数据库保持新结构，
   旧应用必须能容忍新增列/表/权限（发布前已验证）。
2. **不回退 schema**：禁止在生产执行逆向 SQL、删除列/表；需要回退数据时走备份恢复/PITR。
3. **先止血后归因**：业务不可用优先恢复服务，再定位根因；不得为"查清楚"延长停机。
4. **回滚也是变更**：须记录决策人、时间、版本、验证证据，事后补事故单/变更单。
5. **10 分钟只覆盖应用层**：切 jar/镜像 + 重载 nginx + 健康检查；数据库恢复按 RTO ≤30 分钟
   单独计时，不混入 10 分钟目标。

## 2. 触发条件与决策

| 触发 | 示例 | 动作 |
|---|---|---|
| P0 功能阻断 | 登录全失败、记录单无法保存、锁单/签名异常、越权或 PHI 泄露 | **立即回滚**，同时按应急预案止血 |
| 发布后错误率/P95 显著恶化 | 5xx >1%、P95 翻倍、队列持续积压 | 10 分钟内未定位则回滚 |
| 迁移导致应用不兼容 | 启动失败、关键查询报错 | 先回滚应用；schema 不兼容则评估 PITR |
| 配置/证书错误 | nginx 起不来、HSTS/证书错误 | 回滚配置/证书，不一定要回滚应用 |
| 单点数据错误 | 个别记录异常 | 评估数据修复，不整体回滚 |

决策人：发布负责人（信息科值班 + 项目经理）；P0 安全事件同时通知信息安全负责人。
**不确定时默认回滚**——可用性优先于"再看一眼"。

## 3. 应用回滚（目标 ≤10 分钟）

### 3.1 形态 A：单机进程

```bash
# T+0：记录当前版本与时间，宣布回滚
date -Iseconds | tee /tmp/anesop-rollback-start.txt
cat /opt/anesop/MANIFEST.txt

# T+2：停应用、换回上一版本 jar（发布时已保留 api-bootstrap.prev.jar）
sudo systemctl stop anesop-api
sudo cp /opt/anesop/app/api-bootstrap.prev.jar /opt/anesop/app/api-bootstrap.jar
sudo systemctl start anesop-api

# T+4：换回上一版本前端静态资源
sudo mv /opt/anesop/web /opt/anesop/web.failed
sudo mv /opt/anesop/web.prev /opt/anesop/web

# T+5：配置/nginx（若本次改过）
sudo nginx -t && sudo systemctl reload nginx

# T+6：健康检查（见 §5）
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS https://<院方域名>/healthz
```

### 3.2 形态 B：Compose

```bash
# 镜像 tag 指回上一发布版本（禁止 latest；发布时已保留上一版本镜像）
ANESOP_API_TAG=<上一版本> ANESOP_WEB_TAG=<上一版本> \
  docker compose -f deploy/compose/docker-compose.yml up -d --no-deps api web

# 等待健康检查（依赖顺序由 compose 保证）
docker compose -f deploy/compose/docker-compose.yml ps
docker compose -f deploy/compose/docker-compose.yml exec api \
  curl -fsS http://127.0.0.1:8080/actuator/health
```

前端若单独更新（未换 web 镜像），回滚静态资源目录同 §3.1，再
`docker compose exec web nginx -s reload`。

### 3.3 配置/证书回滚

- `.env`/`EnvironmentFile`：从口令管理系统取上一版本，`chmod 600` 后重启 API；
  **不要**在回滚时轮换密钥（会扩大影响）。
- nginx：`git`/配置备份取上一版本 → `nginx -t` → `reload`；证书回滚需确认旧证书仍在有效期。
- 演示账号/生产守卫相关配置：保持 `prod` 语义，禁止为排障临时放开 `demo-accounts-allowed`。

## 4. 数据库回滚（expand-contract 与 PITR）

### 4.1 首选：应用回退 + 数据库保持新结构

发布遵循"先加字段/表/权限，后改逻辑"：

- 旧应用不读取新增列/表即可正常启动；
- 新增权限点对旧应用无影响（旧代码不校验新权限）；
- 因此**应用回滚不需要数据库回退**，这是 10 分钟目标成立的前提。

回滚后核对：

```bash
# 当前 schema 版本（应等于发布时迁移后的版本，而不是回退）
PGPASSWORD=*** psql "postgresql://<pg>:5432/<db>" -Atc \
  "select version, description, success from flyway_schema_history order by installed_rank desc limit 3"
```

### 4.2 迁移本身有误（不可逆/破坏性）

| 情形 | 处理 | 计时 |
|---|---|---|
| 迁移未提交/可前向修复 | 新增 `V<n+1>` 修复脚本，评审后重跑 `init-data.sh --mode prod` | 按变更单 |
| 迁移已写入且影响启动、无法前向修复 | 停写 → 用最近全备 + WAL 恢复到目标时间点 → 启动旧版本 → 核对 | RTO ≤30 min、RPO ≤5 min |
| 单表误删/单点数据错误 | 恢复到临时库，导出目标表回填；不覆盖生产 | 按备份手册 §2.4 |

PITR 步骤见 [`backup-restore.md`](backup-restore.md) §2.3；**先在生产外演练成功**。
恢复期间：

1. 公告停机，`systemctl stop anesop-api`（或 `docker compose stop api web`）；
2. 保留现场（旧数据卷/目录改名，不删除）；
3. 按手册恢复、校验 `flyway_schema_history` 完整、关键表行数、最新审计时间；
4. 启动上一版本应用，跑登录与关键路径冒烟；
5. 记录恢复起点/目标时间/实际耗时/数据损失窗口。

### 4.3 禁止

- 直接执行 `DROP/ALTER ... DROP COLUMN` 逆向回退；
- 用旧镜像配"新库结构但缺列"的半迁移状态；
- 恢复演练未通过就用于生产；
- 回滚后直接再次发布同一版本（须先定位根因、补测试/评审）。

## 5. 回滚后验证（必须全过）

| 检查 | 命令/方法 | 期望 |
|---|---|---|
| 进程/容器 | `systemctl status anesop-api` / `compose ps` | running/healthy |
| API 健康 | `curl -fsS http://127.0.0.1:8080/actuator/health` | `{"status":"UP"}` |
| 入口 | `curl -fsS https://<域名>/healthz` | `ok` |
| 登录 | 浏览器/接口，合成或实施账号 | 200，进入首页 |
| 关键路径 | 申请列表 → 排程 → 术前 → 记录单 | 页面可开、保存成功 |
| 审计 | 查 `sys_audit_log` 最近记录 | 有登录/关键操作，无 PHI/口令 |
| 采集 | `GET /v1/devices` 或网关补传 | 在线/补传幂等 |
| 版本 | `MANIFEST.txt` / 镜像 tag | 等于上一发布版本 |
| 数据 | 关键表行数、最新业务时间 | 无异常丢失/重复 |

自动化辅助：

```bash
bash scripts/drill-health.sh --api-url http://127.0.0.1:8080 \
  --web-url https://<院方域名> --db-url jdbc:postgresql://<pg>:5432/<db> \
  --record docs/deploy/drills/$(date +%F)-rollback-health.md
```

## 6. 10 分钟时间盒（演练按此计时）

| 时间 | 动作 | 负责人 | 记录 |
|---|---|---|---|
| T+0–1 | 确认触发、宣布回滚、通知相关方 | 发布负责人 | 决策时间/原因 |
| T+1–2 | 记录当前版本、保留现场 | 运维 | 版本/时间戳 |
| T+2–5 | 切回上一版本应用/静态资源 | 运维 | 命令与输出 |
| T+5–6 | nginx 校验并重载 | 运维 | `nginx -t` |
| T+6–8 | 健康/登录/关键路径冒烟 | 临床联络人 + 运维 | 截图/结果 |
| T+8–10 | 宣布恢复/继续观察、更新变更单 | 发布负责人 | 实际耗时 |
| T+10–30 | 观察错误率/队列/采集；根因初判 | 值班 | 监控截图 |

> 超过 10 分钟未恢复：升级为 P1，启动应急预案；数据库恢复并行评估，不阻塞应用回退。

## 7. 演练与签字

- 每次发布前做一次"应用回滚"桌面/预发演练，季度做一次真实切换；用
  [`DRILL-RECORD-TEMPLATE.md`](DRILL-RECORD-TEMPLATE.md) 记录。
- 演练通过标准：10 分钟内恢复健康与登录；数据无丢失/重复；审计完整；参与人明确。
- 上线检查清单对应项：[`GO-LIVE-CHECKLIST.md`](GO-LIVE-CHECKLIST.md) §K。

| 项 | 结论 | 证据 | 签字/日期 |
|---|---|---|---|
| 应用回滚演练 ≤10 分钟 | 待演练 | | |
| 数据库 PITR 演练 ≤30 分钟 | 待演练 | | |
| 回滚后关键路径冒烟 | 待演练 | | |
