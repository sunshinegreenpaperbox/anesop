# 新人快速开始（30 分钟）

> 目标：从 clone 到“本地门禁全绿 + 知道前后端怎么单独跑”，并完成一次最小演示登录。
> 预计 30 分钟（缓存就绪时约 12 分钟）；**不需要 Docker**；只有第 6 步需要 PostgreSQL。
> 卡住时先跑 `bash scripts/dev-check.sh`，再查 [`troubleshooting.md`](troubleshooting.md)。

## 时间盒

| 时间 | 做什么 | 预计 |
|---|---|---|
| 0–5 min | 工具链、`source scripts/env.sh`、`dev-check.sh` | 1–2 min |
| 5–10 min | `pnpm install --frozen-lockfile`、前端 lint/类型/单测 | 1–2 min（冷缓存更久） |
| 10–20 min | `./scripts/check.sh` 全量门禁 | 7 min（本机实测 416s） |
| 20–25 min | 只跑前端 / 只跑后端，确认开发方式 | 2 min |
| 25–30 min | 阅读演示入口，按需准备 PG | — |

## 1. 前置条件

| 依赖 | 要求 | 说明 |
|---|---|---|
| JDK 21 | 目录 `.toolchain/jdk21/bin/java` | 新 clone 不含 `.toolchain`（已 gitignore）；从 Adoptium 下载 Temurin 21 解压，或从 teammate 拷贝 |
| Maven | 3.9+ | 统一用 `./scripts/mvn.sh`，不要裸调 `mvn` |
| Node | ≥20（CI 22） | `node --version` |
| pnpm | ≥10（CI 11） | `corepack enable pnpm` 或独立安装 |
| 网络 | 首次依赖下载 | 之后可用仓库内缓存离线 |
| PostgreSQL 16 | 仅运行 API/演示时需要 | `check.sh` 不需要 |

home 只读：所有缓存由 `scripts/env.sh` 固定到仓库内（`.m2repo`/`.npm-cache`/`.pnpm-store`/`.playwright`）。

## 2. 加载工具链（每个新终端）

```bash
cd <repo>
source scripts/env.sh
```

预期输出：

```text
ANESOP env: JAVA_HOME=<repo>/.toolchain/jdk21  MAVEN_REPO=<repo>/.m2repo
openjdk version "21.0.2" 2024-01-16
```

然后自检：

```bash
bash scripts/dev-check.sh
```

预期末行：`dev-check: PASS —— 通过 N，警告 M，失败 0`（WARN 可能是端口占用或 Docker 不可用）。
若 FAIL，按提示修复后重跑；常见项：JDK 不是 21、pnpm <10、缓存目录不可写、缺 `pnpm-lock.yaml`。

## 3. 安装依赖并跑快速检查（不编译后端）

```bash
pnpm install --frozen-lockfile
pnpm lint
pnpm -C apps/web exec vue-tsc --noEmit
pnpm test:unit
```

预期：install `Done`（缓存就绪约 1s）；lint 无输出错误；类型检查 0 error；
Vitest `Test Files 10 passed / Tests 32 passed` + 覆盖率表（行 88%+）。

## 4. 全量门禁（提交前必跑）

```bash
./scripts/check.sh
```

它会依次执行 7 步，任一步失败立刻非 0 退出并打印失败环节：

| 步骤 | 内容 | 本机实测 |
|---|---|---|
| 1/7 | 后端 `mvn verify`（编译、121 用例、JaCoCo、Checkstyle、SpotBugs） | **316s**（单独计时） |
| 2/7–5/7 | `pnpm install --frozen-lockfile` + lint + `vue-tsc` + Vitest | **合计 44s**（install 1s，其余 43s） |
| 6/7–7/7 | `contract-check.sh`（spectral/oasdiff/漂移）+ `check-rules.sh` | 约 55s（含脚本与收尾开销，由总时长推算） |

预期末行：

```text
check.sh: OK
耗时：416s（6m56s）
```

首次运行会下载 Maven/Node 依赖，可能超过 10 分钟；之后命中仓库内缓存。若只想快速验证
后端某个模块：`./scripts/mvn.sh -B -pl apps/api/modules/m0/impl -am test`。

## 5. 只跑前端 / 只跑后端

### 前端

```bash
source scripts/env.sh
pnpm -C apps/web dev
# 浏览器打开 http://localhost:5173（登录页）
```

- `/api` 由 Vite 代理到 `http://localhost:8080` 并去掉前缀；后端未启动时登录会失败，这是预期。
- 壳冒烟：`pnpm e2e`（Playwright，首次需 `pnpm exec playwright install chromium`）。
- 生产构建：`pnpm -C apps/web build` → `apps/web/dist/`。

### 后端

```bash
source scripts/env.sh
./scripts/mvn.sh -B verify                         # 不需要 PG（嵌入式 PG16）
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am test   # 只跑 API 链路
```

启动 API（**需要 PG**）：

```bash
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
export ANESOP_DB_URL=jdbc:postgresql://127.0.0.1:5432/anesop
export ANESOP_DB_USER=anesop ANESOP_DB_PASSWORD=anesop
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
curl -s http://localhost:8080/actuator/health
# 预期：{"status":"UP",...}
```

`dev` profile 会加载 `db/migration` + `db/seed/demo`，并允许 `demo_admin` 与进程内 JWT 密钥。
`prod` profile 关闭文档、只加载 `db/migration`，缺失 `ANESOP_JWT_SECRET` 会拒绝启动。

## 6. 最小演示：初始化 → 登录 → 改密（可选，需 PG）

```bash
# 1) 初始化合成数据（幂等；无 PG 会以清晰错误退出）
bash scripts/seed-demo.sh --url jdbc:postgresql://127.0.0.1:5432/anesop --user anesop --password anesop
# 预期：本次执行的迁移数：5；演示账号：demo_admin；mustChange=true

# 2) 启动 API（见第 5 步），然后登录
BASE=http://localhost:8080
LOGIN=$(curl -s -X POST "$BASE/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo_admin","password":"Anesop@2026"}')
ACCESS=$(printf '%s' "$LOGIN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
curl -s "$BASE/v1/auth/me" -H "Authorization: Bearer $ACCESS"

# 3) 首次登录必须改密（否则其他接口 403）
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/v1/auth/password" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"oldPassword":"Anesop@2026","newPassword":"Anesop@2026#Demo"}'
# 预期：204；随后用新口令重新登录
```

完整演示（含审计、采集网关、M1/M2 步骤可用性）见 [`demo.md`](demo.md)。

## 7. 接下来读什么

1. [`../../AGENTS.md`](../../AGENTS.md)：技术栈锁定、模块边界、契约/测试/PR/AI 红线；
2. [`dev-workflow.md`](dev-workflow.md)：分支、提交、门禁、契约变更、迁移、交接单；
3. [`../ai/README.md`](../ai/README.md) 与 [`../ai/SESSION_STARTER.md`](../ai/SESSION_STARTER.md)：
   AI 协作方式、任务卡、语料；
4. [`../../PROJECT_STATE.md`](../../PROJECT_STATE.md)：当前阶段与下一步；
5. 所在模块的 README：`apps/api/modules/mX/README.md`、`apps/web/src/modules/mX/README.md`。

## 8. 常见卡点

| 现象 | 处理 |
|---|---|
| `java -version` 不是 21 / `mvn` 用错 JDK | 重新 `source scripts/env.sh`，用 `./scripts/mvn.sh` |
| pnpm 报 lockfile 过期 | `pnpm install` 更新并提交 `pnpm-lock.yaml`，不要删锁文件 |
| 嵌入式 PG 启动失败 | 见 [`troubleshooting.md`](troubleshooting.md) 第 3 节（缓存/临时目录） |
| Playwright 缺浏览器 | `pnpm exec playwright install chromium` |
| 8080/5173 被占用 | `ss -ltnp` 找到旧进程并停止；E2E 的 5173 是 strictPort |
| 没有 PG / Docker 不可用 | `check.sh` 不受影响；API 与演示需自备 PG16（EXT-08） |
| 权限/只读 home | 确认已 `source scripts/env.sh`，不要写 `~/.m2`/`~/.npm` |
