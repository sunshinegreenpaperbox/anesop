# ANESOP 上线部署手册（M6-M20-01）

> 适用：单院私有化部署（1 家医院、≤30 间手术间，方案书 §3.7.2）。
> 本文是**发布执行手册**：环境前置 → 配置 → 构建制品 → 发布 → 验证 → 回滚入口。
> 部署拓扑、Compose 服务细节、端口/卷、日志与监控见 [`README.md`](README.md)；
> 备份恢复见 [`backup-restore.md`](backup-restore.md)；回滚见 [`ROLLBACK.md`](ROLLBACK.md)；
> 上线逐项勾选见 [`GO-LIVE-CHECKLIST.md`](GO-LIVE-CHECKLIST.md)；数据初始化见
> [`DATA-INIT.md`](DATA-INIT.md)。
>
> **上线闸门（不可越过）**：生产发布须 `release.yml` 变更单审批 + 院方信息科人工签字；
> 自动化施工不执行生产部署，也不替代签字（`AGENTS.md`「上线闸门」）。
> 本机 Docker 不可用（EXT-08）：镜像构建/Trivy/Compose 实跑未在本仓库环境验证，
> 只能用静态校验替代，见 §4.3。

## 1. 两种部署形态

| 形态 | 适用 | 入口 | 说明 |
|---|---|---|---|
| A. 单机进程（无 Docker） | 试点首套、信息科已有 PG/nginx | 本文 §2–§6 | JDK 21 进程 + 静态资源 + 外部 PG16 + 院方 nginx；最少依赖，便于排障 |
| B. Compose 基线 | 具备 Docker 24+ 的部署机 | [`README.md`](README.md) §3 | 9 服务、健康检查、命名卷；首次部署约 10 分钟 |

两种形态**共用**同一份迁移、同一套环境变量语义、同一套验证与回滚口径；不要混用
（例如 Compose 库与进程库指向同一实例却用不同 Flyway locations）。

## 2. 环境前置

### 2.1 硬件与系统

| 项 | 最低 | 建议 | 核对命令 |
|---|---|---|---|
| CPU/内存（应用） | 8C/16G | 2 台 8C/16G（应用双实例） | `nproc`、`free -h` |
| 数据库 | 4C/16G，SSD 1TB | 主备流复制 | `df -h`、`pg_isready` |
| 监控 | 4C/8G | 独立一台 | — |
| OS | Linux x86_64 | 院方标准镜像（信创适配见 EXT-09） | `uname -a` |
| 时钟 | NTP 已同步 | 双源 NTP | `timedatectl` |
| 磁盘 | 应用 ≥50G、备份独立盘 | 备份与生产盘分离 | `df -h` |

### 2.2 软件依赖

| 依赖 | 版本 | 用途 | 核对命令 |
|---|---|---|---|
| JDK | **21 LTS** | 运行 API | `java -version`（必须 21） |
| PostgreSQL | **16.x** | 业务库 + Flyway | `psql --version`、`pg_isready` |
| Redis | 7.2.x | 会话/权限缓存（生产必配） | `redis-cli -a *** ping` |
| RabbitMQ | 3.13.x | 领域事件/异步任务（生产必配） | `rabbitmq-diagnostics ping` |
| MinIO | RELEASE.2024-08-17… | 文书/波形/导出对象存储 | `mc admin info` |
| nginx | **1.27** | TLS 终止、SPA、`/api/` 反代 | `nginx -v` |
| Node/pnpm | Node ≥20、pnpm ≥10 | **仅构建机**需要，运行机不需要 | `node -v`、`pnpm -v` |
| curl/openssl | 系统自带 | 健康检查/证书检查 | `curl --version`、`openssl version` |

> 试点最小可用：API + PostgreSQL 即可启动并登录；Redis/RabbitMQ/MinIO 缺失时相关
> 异步/对象存储能力不可用，**不得**作为生产验收通过。演示环境可仅 PG（见
> [`../guides/demo.md`](../guides/demo.md)）。

### 2.3 网络、端口与证书

- 终端 → nginx `443/tcp`；`80/tcp` 仅 `/healthz` 与 301 跳转；
- nginx → API `8080/tcp`（内网）；API → PG `5432`、Redis `6379`、RabbitMQ `5672`、
  MinIO `9000`；数据库/缓存/消息/对象存储**不得**暴露到临床区或 DMZ（方案书 §3.7.1）；
- 证书：院方 CA 签发的 `tls.crt`/`tls.key`，私钥 `chmod 600`；仓库
  [`deploy/nginx/certs/`](../../deploy/nginx/certs/README.md) 只放说明，不含真实证书；
- 域名解析到部署机；启用 HSTS 后不可回退 HTTP 登录；
- 防火墙双向白名单；采集网关到 API 的 ingest 路径单独放行（内部令牌，不用用户 JWT）。

### 2.4 静态资源与反向代理

前端构建产物 `apps/web/dist/` 放到 nginx 根目录（如 `/opt/anesop/web`），
`deploy/nginx/anesop.conf` 的 `root` 指向该目录；`/api/` 反代到 API 8080，`proxy_pass`
必须以 `/` 结尾以剥离 `/api` 前缀（后端路径为 `/v1/**`，未配置 servlet context-path；
缺失末尾斜杠会导致生产环境全部接口 404，`compose-check.sh` 已加回归断言）。
nginx 必须保留 `/api/` 的自定义访问日志格式 `anesop_no_query`（不含 query/参数），
禁止改回 combined（见 [`README.md`](README.md) §2.1 B-3）。

## 3. 配置项清单

生产配置全部经环境变量注入；`.env`/`EnvironmentFile` 权限 `600`，由院方口令管理系统
生成与保管，**不入库、不进镜像、不进日志/工单/截图**。

| 变量 | 必填 | 生成/来源 | 说明 |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | 是 | `prod` | 禁止 `dev`/`test`；生产守卫会拒绝演示账号 |
| `SPRING_DATASOURCE_URL` | 是 | `jdbc:postgresql://<pg>:5432/<db>` | 库名不得含 demo/dev/test（初始化脚本会拒绝） |
| `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | 是 | 口令管理系统 | 最小权限账号；口令 ≥16 位 |
| `SPRING_DATA_REDIS_HOST` / `PASSWORD` | 是 | 同上 | 单实例缓存；集群需改 Redis 限流（遗留-1） |
| `SPRING_RABBITMQ_*` | 是 | 同上 | 领域事件；队列积压纳入监控 |
| `ANESOP_JWT_SECRET` | 是 | `openssl rand -base64 48` | Base64 解码 ≥32 字节；缺失/占位/低熵拒绝启动 |
| `ANESOP_FIELD_KEY` | 是 | `openssl rand -base64 32` | AES-256-GCM 字段密钥，解码**恰好 32 字节**；丢失=手机号密文不可解密 |
| `ANESOP_FIELD_KEY_ID` | 是 | `v1`，轮换递增 | 写入 `sys_user.phone_key_id`；与密钥版本一致 |
| `ANESOP_PHONE_PEPPER` | 是 | `openssl rand -base64 48` | 手机号 HMAC pepper；与字段密钥分别生成、不得相同 |
| `ANESOP_INPATIENT_PEPPER` | 是 | `openssl rand -base64 48` | 住院号匹配 pepper；丢失将无法匹配历史哈希 |
| `ANESOP_M2_ACQUISITION_GATEWAY_TOKEN` | 采集必填 | `openssl rand -base64 32` | 网关内部令牌；未配置时 ingest 一律 401（fail-closed） |
| `ANESOP_RATE_LIMIT_*` | 否 | 默认 20 次/分钟 | 登录/刷新限流；集群须改 Redis 令牌桶 |
| `SPRING_FLYWAY_LOCATIONS` | 是 | `filesystem:/opt/anesop/db/migration` | **只含迁移，不含 `db/seed/demo`** |
| `TZ` | 是 | `Asia/Shanghai` | 与数据库时区一致；审计/事件时间依赖 NTP |
| `ANESOP_M2_ACQUISITION_SYNTHETIC` | 是 | 真实设备接入后 `false` | 采集数据合成标记；POC/演示为 `true` |
| `ANESOP_M1_*` / `ANESOP_M2_*` 口径项 | 否 | 见 `application.yml` | 排程分析/评分/PACU 阈值；变更走配置与版本号，不改代码 |

生产 fail-closed 核对（缺一即启动失败，属预期）：

```bash
# 不启动服务，只读检查环境文件是否仍有占位/空值（不要 echo 值）
grep -E '^(ANESOP_JWT_SECRET|ANESOP_FIELD_KEY|ANESOP_PHONE_PEPPER|ANESOP_INPATIENT_PEPPER)=' \
  /etc/anesop/anesop.env | awk -F= 'length($2)<16 {print $1 " 仍为空/过短"}'
```

- 演示账号：生产 `anesop.security.demo-accounts-allowed=false`，启动守卫拒绝 `demo_*`；
  数据库层面 `init-data.sh --mode prod` 还会断言 `demo_*` 账号数为 0。
- 密钥轮换：先备份并演练；字段密钥轮换需离线双密钥重加密工具（见 `docs/modules/m0/README.md`），
  不得直接覆盖旧值。

## 4. 构建与制品（Docker 不可用路径）

### 4.1 本地构建（构建机，不需要 Docker）

```bash
source scripts/env.sh

# 后端可执行 jar（含 api-bootstrap 与全部模块）
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package

# 前端静态资源
pnpm install --frozen-lockfile
pnpm -C apps/web build

# 冒烟：jar 存在且可读、dist 有 index.html
ls -l apps/api/api-bootstrap/target/api-bootstrap-*.jar
test -f apps/web/dist/index.html && echo "web dist OK"
```

### 4.2 打包为可交付 tar（离线转运）

```bash
VERSION="$(date +%Y.%m.%d)-1"          # 例：2026.11.15-1；禁止 latest
STAGE="dist/anesop-$VERSION"
mkdir -p "$STAGE/app" "$STAGE/web" "$STAGE/db" "$STAGE/deploy/nginx" "$STAGE/deploy/compose"

cp "$(ls apps/api/api-bootstrap/target/api-bootstrap-*.jar | head -n1)" \
  "$STAGE/app/api-bootstrap.jar"
cp -R apps/web/dist/. "$STAGE/web/"
cp -R db/migration "$STAGE/db/"
cp deploy/nginx/anesop.conf "$STAGE/deploy/nginx/"
cp deploy/compose/docker-compose.yml deploy/compose/.env.example "$STAGE/deploy/compose/"

{
  echo "version=$VERSION"
  echo "git=$(git rev-parse HEAD)"
  echo "built_at=$(date -Iseconds)"
  echo "java=$(java -version 2>&1 | head -n1)"
} >"$STAGE/MANIFEST.txt"

tar -C dist -czf "dist/anesop-$VERSION.tar.gz" "anesop-$VERSION"
sha256sum "dist/anesop-$VERSION.tar.gz" | tee "dist/anesop-$VERSION.tar.gz.sha256"
```

交付物至少包含：jar、`web/dist`、`db/migration`、nginx 配置、Compose 清单/环境变量模板、
`MANIFEST.txt`、SHA-256。**不得**包含 `.env`、证书私钥、`db/seed/demo`（生产包）。

### 4.3 Docker 可用时的镜像构建与离线转运

在具备 Docker 24+ 的构建机（不是生产机）执行：

```bash
VERSION="2026.11.15-1"
docker build -f deploy/compose/api.Dockerfile -t "anesop/api:$VERSION" .
docker build -f deploy/compose/web.Dockerfile -t "anesop/web:$VERSION" .

# 离线转运（内网/无外网环境）
docker save "anesop/api:$VERSION" "anesop/web:$VERSION" -o "dist/anesop-images-$VERSION.tar"
sha256sum "dist/anesop-images-$VERSION.tar" >"dist/anesop-images-$VERSION.tar.sha256"
# 部署机
docker load -i "dist/anesop-images-$VERSION.tar"
```

- EXT-08：本仓库施工环境 **Docker 不可用**，上述 build/save/load 未实跑；仅通过
  `bash scripts/compose-check.sh` 做清单/nginx/Dockerfile 静态校验（35 PASS）。
- 首次可运行环境必须：补镜像 `@sha256:` digest、跑 `trivy image --severity HIGH,CRITICAL`
  （有可修复项即阻断）、记录自建镜像 RepoDigest 进发布单。步骤见
  [`README.md`](README.md) §9。
- 未完成镜像扫描与 digest 固定前，**不得**把镜像推入生产或开启公网暴露。

## 5. 发布步骤（迁移 → 后端 → 前端 → nginx → 验证）

> 发布窗口：周二/周四 20:00–22:00；先完成备份与 `release.yml` 审批。
> 以下命令以形态 A（`/opt/anesop`）为例；Compose 形态把启动/重启换成
> `docker compose -f deploy/compose/docker-compose.yml up -d --no-deps api web`。

### 5.1 发布前（T-30 min）

```bash
# 1) 记录当前版本与制品校验
cat /opt/anesop/MANIFEST.txt
sha256sum -c dist/anesop-<上一版本>.tar.gz.sha256

# 2) 备份数据库（详见 docs/deploy/backup-restore.md）
#    确认最新全备可读 + WAL 归档连续；未演练的备份视为不可用

# 3) 冻结写入/公告：停止新排程与记录写入，或选择业务低峰
```

### 5.2 数据库迁移（先于应用）

```bash
cd /opt/anesop
bash scripts/init-data.sh --dry-run        # 复核模式/locations/基线
bash scripts/init-data.sh --mode prod \
  --url "jdbc:postgresql://<pg>:5432/<db>" \
  --user anesop --confirm-prod
```

- 迁移遵循 expand-contract：只加字段/表/权限，不改旧列语义；破坏性清理留到下一次发布。
- 失败处理：已应用迁移不回滚；修复后重跑（Flyway 幂等）。若迁移不可逆且影响启动，
  按 [`ROLLBACK.md`](ROLLBACK.md) §4 走备份/PITR，不在生产执行逆向 SQL。
- 迁移后核对：脚本输出角色/权限/字典计数与 `demo_* = 0`；`sys_org=0` 时按
  [`DATA-INIT.md`](DATA-INIT.md) §4 导入组织/术间/人员。

### 5.3 后端

```bash
# 形态 A：替换 jar（保留上一版本用于回滚）
sudo systemctl stop anesop-api
sudo cp /opt/anesop/app/api-bootstrap.jar /opt/anesop/app/api-bootstrap.prev.jar
sudo install -m 0640 dist/anesop-$VERSION/app/api-bootstrap.jar /opt/anesop/app/api-bootstrap.jar
sudo systemctl start anesop-api
sudo systemctl status anesop-api --no-pager

# 形态 B（Compose）
ANESOP_API_TAG=$VERSION docker compose -f deploy/compose/docker-compose.yml up -d --no-deps api
```

`anesop-api.service` 参考（部署机 `/etc/systemd/system/anesop-api.service`）：

```ini
[Unit]
Description=ANESOP API
After=network-online.target

[Service]
User=anesop
EnvironmentFile=/etc/anesop/anesop.env
ExecStart=/usr/lib/jvm/java-21/bin/java -jar /opt/anesop/app/api-bootstrap.jar --spring.profiles.active=prod
Restart=on-failure
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

### 5.4 前端与 nginx

```bash
# 静态资源原子切换（保留上一版本目录）
sudo mv /opt/anesop/web /opt/anesop/web.prev
sudo cp -R dist/anesop-$VERSION/web /opt/anesop/web
sudo chown -R nginx:nginx /opt/anesop/web

# 配置校验后重载（禁止未校验直接 reload）
sudo nginx -t
sudo systemctl reload nginx
```

Compose 形态：`docker compose exec web nginx -t && docker compose exec web nginx -s reload`。

### 5.5 验证（发布后 0–30 min）

```bash
# 1) 存活/健康
curl -fsS https://<院方域名>/healthz        # ok
curl -fsS http://127.0.0.1:8080/actuator/health   # {"status":"UP"}

# 2) 经 nginx 的 API 路径（不能只看直连 8080）：期望 400/401，不是 404
curl -sS -o /dev/null -w '%{http_code}\n' -X POST https://<院方域名>/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{}'

# 3) 登录与受保护接口（合成账号；生产用实施管理员）
#    浏览器登录 → 首页 → 打开一个受权限保护的菜单

# 4) 关键路径冒烟：申请列表 → 排程 → 术前访视 → 记录单 → 锁单
#    以及审计：关键操作在 sys_audit_log 可查（无 PHI/口令/令牌）

# 5) 采集补传幂等（有采集网关时）
bash scripts/drill-offline-replay.sh --base-url http://127.0.0.1:8080 \
  --gateway-id <已登记网关> --device-id <已登记设备>

# 6) 健康检查演练记录
bash scripts/drill-health.sh --api-url http://127.0.0.1:8080 \
  --web-url https://<院方域名> --db-url jdbc:postgresql://<pg>:5432/<db> \
  --record docs/deploy/drills/$(date +%F)-go-live-health.md
```

观察 30 分钟：错误率、P95、队列深度、采集在线率、审计写入。异常按
[`../runbook/EMERGENCY-PLAYBOOK.md`](../runbook/EMERGENCY-PLAYBOOK.md) 处置；
需要撤回时立即执行 [`ROLLBACK.md`](ROLLBACK.md)（目标 ≤10 分钟）。

## 6. 发布记录

| 项 | 内容 |
|---|---|
| 版本/制品 SHA-256 | |
| 变更单（release.yml） | |
| 迁移版本（Flyway schema version） | |
| 开始/结束时间、实际耗时 | |
| 验证结果（健康/登录/关键路径/审计/采集） | |
| 回滚预案与决策人 | |
| 签字（信息科/项目经理/医务处） | |

> 发布单、验证截图与演练记录一并归档；`git status` 确认未把 `.env`、证书、备份文件带进仓库。
