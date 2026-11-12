# ANESOP 部署手册（单院 Docker Compose 基线）

> 适用：1 家医院、≤30 间手术间的院内私有化部署（方案书 §3.7.2 单院形态）。
> 状态：S0-10 完成清单与静态校验；**本机 Docker 不可用（EXT-08），未实跑**。
> 上线闸门：生产发布须 `release.yml` 变更单审批 + 院方信息科人工签字（AGENTS「上线闸门」），
> 自动化施工不执行生产部署，也不替代签字。

## 1. 组成与目录

| 路径 | 内容 |
|---|---|
| `deploy/compose/docker-compose.yml` | 9 个服务、2 个网络、7 个命名卷、健康检查与依赖顺序 |
| `deploy/compose/.env.example` | 环境变量模板（只有 `CHANGE_ME` 占位，无真实密钥） |
| `deploy/compose/api.Dockerfile` | Java 21 多阶段构建，运行阶段非 root |
| `deploy/compose/web.Dockerfile` | pnpm 构建 + Nginx 1.27 运行 |
| `deploy/compose/*.Dockerfile.dockerignore` | 构建上下文裁剪（Docker 23+/BuildKit） |
| `deploy/nginx/anesop.conf` | TLS 1.2/1.3、安全头、SPA fallback、`/api/` 反代 |
| `deploy/nginx/certs/` | 证书挂载点说明（仓库不含真实证书/私钥） |
| `deploy/prometheus/prometheus.yml` | 采集 API 指标（exporter 为占位） |
| `deploy/grafana/provisioning/` | Prometheus + Loki 数据源、仪表盘 provider |
| `deploy/loki/loki-config.yml` | 单机 filesystem，30 天保留 |
| `scripts/compose-check.sh` | 纯静态校验（本文件第 9 节） |
| `docs/deploy/backup-restore.md` | 备份与恢复手册（RPO/RTO、演练） |

服务与内网端口（均不映射宿主，除 web 与监控 loopback）：

| 服务 | 镜像（精确 tag） | 内网端口 | 作用 |
|---|---|---|---|
| `postgres` | `postgres:16.4` | 5432 | 业务库 + Flyway 元数据 |
| `redis` | `redis:7.2.5` | 6379 | 会话、权限缓存、排程快照 |
| `rabbitmq` | `rabbitmq:3.13.7-management` | 5672/15672 | 领域事件、异步任务 |
| `minio` | `minio/minio:RELEASE.2024-08-17T01-24-54Z` | 9000/9001 | 对象存储（文书、波形、导出） |
| `api` | `anesop/api:${ANESOP_API_TAG:-1.0.0}` | 8080 | Spring Boot 3.4 应用 |
| `web` | `anesop/web:${ANESOP_WEB_TAG:-1.0.0}` | 80/443（宿主） | Vue SPA + Nginx 入口/反代 |
| `prometheus` | `prom/prometheus:v2.55.1` | 9090（127.0.0.1） | 指标 |
| `grafana` | `grafana/grafana:11.2.2` | 3000（127.0.0.1） | 仪表盘 |
| `loki` | `grafana/loki:3.1.1` | 3100（127.0.0.1） | 日志存储 |

监控三件套在 `monitoring` profile 中，默认不启动。

## 2. 前置条件

- 硬件（§3.7.2 单院最小配置）：应用/数据 2 台 8C16G 起，PostgreSQL SSD 1TB；监控 1 台 4C8G；
  本基线假定单机验证，生产按表 3-15 拆分主备。
- 操作系统：Linux x86_64（信创适配见 D-04/EXT-09，当前未验证）。
- Docker 24+、Compose v2（`docker compose` 插件）；Docker 未就绪即 EXT-08。
- 构建启用 BuildKit（Docker 23+ 默认）：`*.Dockerfile.dockerignore` 与 Maven 缓存挂载
  依赖 BuildKit；若禁用，构建上下文会包含宿主 `node_modules`/`target`，构建显著变慢。
- 时间同步：NTP 已配置（审计与事件时间依赖）。
- 网络：终端到 web 的 443 放行；临床区到应用区仅经 Nginx；数据库/缓存/消息/对象存储
  不得暴露到临床区或 DMZ（§3.7.1）。
- 证书：院方 CA 签发的 `tls.crt` / `tls.key`，放 `deploy/nginx/certs/`，私钥 `chmod 600`。
- 域名：`<院方域名>` 解析到部署机；HSTS 启用后不可回退到 HTTP 登录。
- `.env.example` 是唯一允许入库的 env 文件（`.gitignore` 对 `.env`/`.env.*` 有例外规则）；
  提交前用 `git status` 确认没有把真实 `.env` 或证书带进来。

## 3. 首次部署（一键起，首次约 10 分钟）

> 以下命令在**具备 Docker 的部署机**执行；本仓库施工环境未执行（EXT-08）。

```bash
# 1) 取代码（发布用 tag，不用分支头）
git clone <仓库地址> anesop && cd anesop
git checkout <release-tag>

# 2) 环境变量：复制后逐项替换 CHANGE_ME，.env 不入库
cp deploy/compose/.env.example deploy/compose/.env
${EDITOR:-vi} deploy/compose/.env
chmod 600 deploy/compose/.env

# 3) 证书（院方提供；联调可自签，见 deploy/nginx/certs/README.md）
ls -l deploy/nginx/certs/tls.crt deploy/nginx/certs/tls.key

# 4) 静态校验（不启动容器；有 Docker daemon 时附加 compose config）
bash scripts/compose-check.sh

# 5) 构建并启动（健康检查会阻塞依赖启动）
docker compose -f deploy/compose/docker-compose.yml build
docker compose -f deploy/compose/docker-compose.yml up -d

# 6) 查看状态与健康
docker compose -f deploy/compose/docker-compose.yml ps
```

启动顺序由 `depends_on.condition: service_healthy` 保证：
`postgres/redis/rabbitmq/minio → api → web`；监控为 `api → prometheus → grafana`、`loki → grafana`。

启用监控：

```bash
docker compose -f deploy/compose/docker-compose.yml --profile monitoring up -d
```

监控端口只绑 `127.0.0.1`，运维经跳板机 SSH 隧道访问，例如
`ssh -L 3000:127.0.0.1:3000 <运维账号>@<部署机>`。

## 4. 健康检查与首次验收

| 检查 | 命令 | 期望 |
|---|---|---|
| 容器状态 | `docker compose ps` | 全部 `running (healthy)` |
| Web 存活 | `curl -fsS http://127.0.0.1/healthz` | `ok` |
| TLS | `curl -fsS https://<院方域名>/healthz --cacert <CA>` | `ok` |
| API | `docker compose exec api curl -fsS http://127.0.0.1:8080/actuator/health` | `{"status":"UP"}` |
| DB | `docker compose exec postgres pg_isready -U anesop -d anesop` | `accepting connections` |
| Nginx 配置 | `docker compose exec web nginx -t` | `syntax is ok` |
| 登录冒烟 | 浏览器访问 `https://<院方域名>/`，用合成账号登录 | 进入首页，受保护接口 200 |

首次上线还必须完成：镜像漏洞扫描（HIGH/CRITICAL 阻断）、`release.yml` 审批、
备份策略生效验证、监控告警连通性测试。

## 5. 端口、网络与卷

宿主端口（仅这些）：

| 端口 | 绑定 | 用途 |
|---|---|---|
| 80/tcp | 0.0.0.0 | `/healthz` 与 301 到 443 |
| 443/tcp | 0.0.0.0 | SPA 与 `/api/` 反代 |
| 9090/3000/3100 | 127.0.0.1 | Prometheus/Grafana/Loki（仅跳板机隧道） |

网络：

- `anesop-net`：业务面，web/api/数据组件；
- `anesop-monitoring`：监控面，prometheus/grafana/loki；prometheus 同时接两边以抓取 api。

命名卷（数据与配置分离）：

| 卷 | 挂载点 | 备份方式 |
|---|---|---|
| `anesop-pgdata` | postgres `/var/lib/postgresql/data` | 每日全备 + WAL（见备份手册） |
| `anesop-redisdata` | redis `/data` | 可重建，不单独备份（缓存非唯一数据源） |
| `anesop-rabbitmqdata` | rabbitmq `/var/lib/rabbitmq` | 每日导出定义；消息以业务补偿为准 |
| `anesop-miniodata` | minio `/data` | 版本化 + 每日镜像/快照 |
| `anesop-promdata` | prometheus `/prometheus` | 可不备份（指标可重采） |
| `anesop-grafanadata` | grafana `/var/lib/grafana` | 每周导出仪表盘 |
| `anesop-lokidata` | loki `/loki` | 按审计要求保留，见备份手册 |

配置（nginx、prometheus、grafana、loki）只读挂载，改动走 Git + 重载，不在容器内改。

## 6. 日常运维

```bash
C="docker compose -f deploy/compose/docker-compose.yml"

$C ps                       # 状态
$C logs -f --tail=200 api   # 应用日志（JSON 行，含 traceId）
$C restart api              # 单服务重启
$C stop                     # 停止（保留容器与卷）
$C down                     # 移除容器与网络，保留命名卷
```

- 日志：容器 stdout 以 json-file 落盘（`max-size`/`max-file` 轮转），应用输出结构化 JSON；
  审计日志单独入库，不依赖容器日志（§3.8.3）。
- 巡检：每日看容器健康、磁盘、备份成功、证书剩余天数；每周看慢查询与队列积压。
- 变更：配置改动先改仓库文件，再 `docker compose up -d <service>`；禁止 `docker exec` 手改后不留痕。

## 7. 升级与回滚

镜像 tag 通过 `ANESOP_API_TAG` / `ANESOP_WEB_TAG` 指定（禁止 `latest`）。

升级步骤：

1. 备份数据库与对象存储，确认备份可读（见 `docs/deploy/backup-restore.md`）；
2. 拉取/构建新版本镜像并打发布 tag，例如 `ANESOP_API_TAG=2026.11.12-1`；
3. 只更新应用层：`docker compose up -d --no-deps api web`；
4. 等待健康检查通过，跑登录与关键路径冒烟；
5. 观察 30 分钟（错误率、P95、队列深度），保留旧镜像与旧 env 备份；
6. 通过后再清理旧镜像（保留最近 2 个发布版本）。

回滚（目标 ≤10 分钟，§3.8.4）：

```bash
# 应用回滚：把 tag 指回上一发布版本，重建对应容器
ANESOP_API_TAG=<上一版本> ANESOP_WEB_TAG=<上一版本> \
  docker compose -f deploy/compose/docker-compose.yml up -d --no-deps api web
```

数据库变更只做向前兼容：先加列/加表、后改逻辑，破坏性变更分两次发布（AGENTS「发布」）。
禁止用旧镜像配新库结构；需要回退 schema 时走备份恢复流程，不在生产执行逆向 SQL。

## 8. 日志、监控与告警

- Prometheus 抓取 `api:8080/actuator/prometheus`、自身；Grafana 预置 Prometheus/Loki 数据源；
- Loki 单机 filesystem，保留 720h（30 天）；审计日志按等保 ≥6 个月单独归档；
- 告警分级沿用 §3.8.3：P1 业务不可用/采集全断（短信+电话）、P2 接口失败率/主备切换（企业微信）、
  P3 磁盘/队列积压/证书到期（邮件）；
- 当前**未接入** Alertmanager 与日志采集端（Promtail/Alloy），见第 9 节。

## 9. EXT-08 受限项与解除步骤

| 受限项 | 现状 | 解除条件/动作 |
|---|---|---|
| 镜像 digest | 仅精确 tag，未固定 `@sha256:` | 首次可运行环境按下方步骤补全并提交 |
| Compose 实跑 | 未执行 pull/build/up | 具备 Docker daemon 的宿主机跑第 3、4 节 |
| 镜像漏洞扫描 | 未扫描 | `trivy image --severity HIGH,CRITICAL <image>`，有可修复项即阻断 |
| PostgreSQL/Redis/RabbitMQ/MinIO exporter | 仅注释占位 | 评审后加入 allowlist 与 compose，再启用 job |
| API 指标端点 | 骨架未引入 `micrometer-registry-prometheus` | 后端任务补依赖并暴露 `prometheus` 端点，否则 scrape 404 |
| 日志采集 | 无 Promtail/Alloy | 引入采集端或改 Docker loki logging driver |
| Alertmanager | 未部署 | 按告警分级接入短信/企业微信通道 |
| 证书 | 仓库无证书 | 院方提供后挂载并 `nginx -t` |

digest 补全步骤（每个镜像执行一次）：

```bash
# 方式 A：不落盘，直接取多架构 manifest digest
docker buildx imagetools inspect postgres:16.4 --format '{{json .Manifest.Digest}}'

# 方式 B：拉取后读 RepoDigests
docker pull postgres:16.4
docker inspect --format='{{index .RepoDigests 0}}' postgres:16.4
```

把结果写回 `docker-compose.yml`：`image: postgres:16.4@sha256:<digest>`，
并在 `deploy/compose/api.Dockerfile`、`web.Dockerfile` 的每个 `FROM` 后补 digest；
删除对应 `# TODO(digest,EXT-08)` 注释；重新运行 `bash scripts/compose-check.sh`。
自建镜像（api/web）发布后记录 `docker inspect` 的 RepoDigest 进发布单，便于回滚校验。

## 10. 生产差异与安全加固

本文件是**单机基线**，生产部署须评估以下差异：

1. 高可用：应用 2 实例 + Nginx 双活/VIP、PostgreSQL 流复制主备、Redis 主从+Sentinel、
   RabbitMQ 镜像/quorum 队列、MinIO 多盘（表 3-15）；本 compose 单实例不满足高可用。
2. 密钥：`.env` 会注入每个服务，`docker inspect` 可见；生产建议拆分为每服务 env_file，
   进一步切 Docker secrets / Vault，并定期轮换 JWT 与数据库口令。
3. 容器加固：`read_only: true` + `tmpfs`、`cap_drop: [ALL]`、资源 reservations、
   镜像签名（cosign）与 SBOM 随交付。
4. 网络：按 §3.7.1 做防火墙双向白名单，数据库/缓存/消息/对象存储仅应用区可达；
   DMZ 只放接口前置，不放数据组件。
5. TLS：证书由院方 CA 签发；启用 HSTS 前确认全站 HTTPS；CSP 按前端资源清单启用。
6. 审计与日志：审计入库、保留 ≥6 个月；容器日志轮转上限按等保调整；NTP 必须启用。
7. 备份：异地保存，季度恢复演练（见备份手册）；未演练的备份视为不可用。
8. 发布：固定窗口（周二/周四 20:00–22:00）、`release.yml` 审批、人工签字。

## 11. 排障

| 现象 | 排查 |
|---|---|
| 容器 `unhealthy` | `docker compose logs <svc>`；确认 healthcheck 命令与账号口令一致 |
| `web` 起不来 | 证书缺失/权限错；`docker compose exec web nginx -t`；确认 80/443 未被占用 |
| 502/504 | api 未 healthy 或超时；看 `api` 日志、`/actuator/health`、连接池与 GC |
| API 连不上库 | `.env` 口令与卷内旧口令不一致（初始化后改口令无效）；必要时按备份流程重建 |
| 挂载权限拒绝 | 命名卷属主与容器 UID 不匹配；`docker compose down` 后修正卷权限 |
| 时间不对 | `TZ=Asia/Shanghai` 与宿主 NTP；数据库时区与应用时区须一致 |
| 磁盘涨满 | 日志轮转、Loki 保留、镜像清理；设置磁盘告警（P3） |
| 监控 404 | API 未暴露 `prometheus` 端点（见第 9 节），不是网络问题 |

## 12. 验收记录（人工签字）

| 项 | 结论 | 证据 | 签字/日期 |
|---|---|---|---|
| `bash scripts/compose-check.sh` 通过 | 待签字 | 脚本输出 | |
| `docker compose config -q` 通过 | 待签字 | 命令输出 | |
| 全栈 10 分钟内启动且健康 | 待签字 | `docker compose ps` 截图 | |
| 登录→首页→受保护接口 | 待签字 | 冒烟记录 | |
| 镜像扫描无 HIGH/CRITICAL | 待签字 | 扫描报告 | |
| 备份与恢复演练 | 待签字 | 演练记录 | |

> 以上任一项未完成，不得进入生产发布；生产发布仍须 `release.yml` 与院方书面确认。
