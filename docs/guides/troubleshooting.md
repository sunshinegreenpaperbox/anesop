# 故障排查（本机开发与门禁）

> 先跑 `bash scripts/dev-check.sh`，多数环境问题会在末行给出 `FAIL` 与修复命令。
> 本文件按「症状 → 原因 → 处理」组织；命令默认在仓库根执行，且已 `source scripts/env.sh`。
> 相关：[`quickstart.md`](quickstart.md)、[`dev-workflow.md`](dev-workflow.md)、
> [`../testing/README.md`](../testing/README.md)、[`../deploy/README.md`](../deploy/README.md)。

## 0. 一分钟定位

```bash
source scripts/env.sh
bash scripts/dev-check.sh
java -version; mvn -v | head -3; node --version; pnpm --version
git status --short
ss -ltnp 2>/dev/null | grep -E ':(8080|5173|8081|19090|5432)\b' || true
```

## 1. JDK 版本不对（最常见）

**症状**：`invalid target release: 21`、`class file has wrong version 65.0`、Checkstyle/SpotBugs
报 `Unsupported class file major version`，或 `mvn -v` 显示 Java 27。

**原因**：系统默认 JDK 不是 21；或没有 `source scripts/env.sh` 就裸调 `mvn`。

**处理**：

```bash
source scripts/env.sh            # JAVA_HOME=.toolchain/jdk21，PATH 前置
java -version                    # 必须是 21.x
mvn -v | grep 'Java version'     # 必须是 21.x
./scripts/mvn.sh -B verify       # 一律经脚本，不要裸调 mvn
```

若 `.toolchain/jdk21` 不存在（新 clone 不含工具链，已被 `.gitignore` 排除）：
从 Adoptium 下载 Temurin 21 压缩包，解压后保证存在 `.toolchain/jdk21/bin/java`
（目录名必须是 `jdk21`）。离线环境从 teammate 拷贝整个 `.toolchain/jdk21`。
`scripts/dev-check.sh` 会同时检查仓库内 JDK 与 PATH 上的 java。

## 2. `pnpm install --frozen-lockfile` 失败

**症状**：`ERR_PNPM_OUTDATED_LOCKFILE` / `Cannot install with "frozen-lockfile" because
pnpm-lock.yaml is not up to date`。

**原因**：改了 `package.json`（增删依赖或改版本）但没有更新锁文件；或使用了不同 pnpm 主版本。

**处理**：

```bash
pnpm install                     # 不带 frozen，更新 pnpm-lock.yaml
git diff --stat pnpm-lock.yaml   # 确认只包含本次依赖变化
pnpm install --frozen-lockfile   # 再验证
```

依赖必须精确版本（禁 `^`/`~`）；新增依赖先写 ADR 并更新 `docs/deps/allowlist.md`。
不要为了装上去删锁文件或 `--no-frozen-lockfile` 后直接提交。

## 3. 嵌入式 PostgreSQL 启动/下载失败

**症状**：后端测试报 `Could not start embedded postgres`、`Failed to download`、
`Unable to extract`，或 Zonky 在 `/tmp` 下报 `Permission denied`/`Text file busy`。

**背景**：Docker 不可用（EXT-08），集成测试默认用 Zonky 嵌入式 PostgreSQL 16；
首次运行需要 `embedded-postgres-binaries-linux-amd64:16.15.0` 已缓存在 `.m2repo`
（或可访问 Maven Central）。二进制会解压到临时目录。

**处理**：

```bash
# 1) 确认依赖已缓存（离线时从 teammate 拷贝对应目录）
ls .m2repo/io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64/16.15.0/

# 2) 临时目录不可执行/不可写时，把 java.io.tmpdir 指到仓库内
mkdir -p .cache/tmp
MAVEN_OPTS="$MAVEN_OPTS -Djava.io.tmpdir=$PWD/.cache/tmp" ./scripts/mvn.sh -B -pl apps/api/modules/m0/impl -am test

# 3) 确认不是端口冲突（嵌入式 PG 用随机端口，一般不会撞 5432）
```

仍失败时先跑单个测试类定位：`./scripts/mvn.sh -B -pl apps/api/modules/m0/impl -am -Dtest=FlywayMigrationTest test`。
Testcontainers 路径需要 Docker，当前不可用（EXT-08），不要用它替代本地验证。

## 4. Playwright 浏览器缺失

**症状**：`Executable doesn't exist at .../.playwright/chromium-.../chrome-linux/chrome`，
或 `pnpm e2e` 在启动 webServer 后立刻失败。

**处理**：

```bash
source scripts/env.sh                 # 设定 PLAYWRIGHT_BROWSERS_PATH=<repo>/.playwright
pnpm exec playwright install chromium # 首次/升级 Playwright 后执行
pnpm e2e                              # 3 条壳冒烟
```

浏览器落在仓库内 `.playwright/`（已 gitignore）。无外网时从 teammate 拷贝该目录。
CI 使用 `pnpm exec playwright install --with-deps chromium`，不要提交浏览器文件。

## 5. Docker 不可用 / Compose 与 E2E

**症状**：`docker compose up` 报 `Cannot connect to the Docker daemon`；
`docker compose config` 失败；Testcontainers 测试报 `Could not find a valid Docker environment`。

**现状**：施工环境登记 **EXT-08**，Docker daemon 不可用；这是已知限制，不是代码缺陷。

**处理**：

- 部署清单只做静态校验：`bash scripts/compose-check.sh`（不启动容器）；
- 本地集成测试用 Zonky 嵌入式 PG，不要依赖 Testcontainers；
- E2E 冒烟不依赖 Docker 与后端：Playwright 用 Vite dev server + 路由 mock（见
  `apps/web/e2e/home.spec.ts`）；需要真实登录的 E2E-01–05 待 M1/M2；
- 具备 Docker 的宿主机上按 [`../deploy/README.md`](../deploy/README.md) 首次部署，
  并补齐镜像 digest（当前为 `TODO(digest,EXT-08)`）。

## 6. 端口被占用

**症状**：API 启动报 `Port 8080 was already in use`；`pnpm e2e` 报 5173 被占用；
网关启动报 8081/19090 绑定失败。

**处理**：

```bash
ss -ltnp | grep -E ':(8080|5173|8081|19090)\b'
# 确认是本项目的旧进程后再停止；不要盲杀未知进程
kill <pid>
```

| 端口 | 用途 | 说明 |
|---|---|---|
| 8080 | API | Spring Boot；`server.port` |
| 5173 | Vite dev | E2E 使用 `--strictPort`，被占用会直接失败而不是换端口 |
| 8081 | 网关 HTTP | actuator health/metrics |
| 19090 | 网关 TCP | 模拟设备接入 |
| 5432 | PostgreSQL | 本地/容器 PG；未监听时 API 无法启动，但不影响 `check.sh` |

## 7. 权限与只读 home

**症状**：`EACCES: permission denied, mkdir '/home/<user>/.m2'`、
`ERR_PNPM_... EACCES`、`Read-only file system`、缓存写入失败。

**原因**：本工作区 home 只读，所有缓存必须落在仓库内；或仓库目录属主不对。

**处理**：

```bash
source scripts/env.sh    # .m2repo / .npm-cache / .pnpm-store / .playwright 都在仓库内
ls -ld .m2repo .npm-cache .pnpm-store .pnpm-home .playwright
# 属主/权限不对时修正（不要用 sudo 写仓库）
chmod -R u+rwX .m2repo .npm-cache .pnpm-store .pnpm-home .playwright
```

不要设置 `MAVEN_OPTS=-Dmaven.repo.local=~/.m2`，也不要 `npm config set cache ~/.npm`。

## 8. Flyway 迁移失败

| 症状 | 原因 | 处理 |
|---|---|---|
| `Migration checksum mismatch` | 改了已执行过的迁移脚本 | 恢复原脚本；修正走新版本 `V<n+1>`，不要 `flyway repair` 掩盖 |
| `Detected applied migration not resolved locally: 100` | 演示库应用过 V100，却用生产 locations 启动 | 演示库不得当生产库；重新初始化，生产 locations 只含 `db/migration` |
| `Found non-empty schema ... without schema history table` | 目标库已有表但无 Flyway 历史 | 确认是否空库；不要开 `baselineOnMigrate` 绕过评审 |
| 连接被拒 | PG 未启动、端口/库名错、pg_hba/SSL 限制 | 先 `bash scripts/dev-check.sh` 看 5432；再用 `seed-demo.sh` 的报错定位 |
| `prod` 启动即失败，提示 demo 账号/密钥 | 安全守卫按设计 fail-closed | 生产用干净库 + `ANESOP_JWT_SECRET`；不要关闭守卫 |

演示数据初始化：

```bash
bash scripts/seed-demo.sh --url jdbc:postgresql://127.0.0.1:5432/anesop --user anesop --password anesop
# 幂等：再跑一次 migrationsExecuted=0；账号不会重复插入
```

## 9. 前端请求 404 / 登录失败

| 症状 | 检查 |
|---|---|
| 浏览器请求 `/api/v1/...` 返回 404 | 后端是否在 8080；Vite proxy 会去掉 `/api` 前缀 |
| 直连 `curl localhost:8080/api/v1/auth/login` 404 | 后端没有 context-path，正确路径是 `/v1/auth/login` |
| 设置了 `VITE_API_BASE_URL` 后跨域失败 | 后端未开 CORS；本地建议留空走 `/api` 代理 |
| 改了根 `.env` 但前端没生效 | Vite 只读 `apps/web` 下的 `.env*` 或进程环境变量；先 `set -a; source .env; set +a` |
| 登录 401/422 | 是否用了 `demo_admin` 且未改密；改密后需重新登录；连续失败会锁定 30 分钟 |
| 刷新后掉登录 | dev 未配置 `ANESOP_JWT_SECRET` 时密钥是进程内的，重启后旧令牌失效，重新登录即可 |

## 10. JWT 启动失败（prod）

**症状**：启动报密钥缺失/占位/低熵/长度不足。

**处理**：`openssl rand -base64 48` 生成，写入部署机 `.env` 的 `ANESOP_JWT_SECRET`；
Base64 解码后必须 ≥32 字节，禁止 `CHANGE_ME` 一类占位值。dev 可留空
（`allow-dev-generated=true` 生成进程内随机密钥）。

## 11. 其他高频问题

- **`./scripts/mvn.sh: Permission denied`**：`chmod +x scripts/*.sh`（脚本应保留可执行位）。
- **`mvn: command not found`**：未 `source scripts/env.sh`，或系统未装 Maven 3.9+。
- **构建很慢/重复下载**：确认 `.m2repo` 与 `.pnpm-store` 在仓库内且被复用；
  不要每次清空缓存。
- **`git diff --check` 报行尾空白**：`pnpm format` 或手工清理；`check.sh`/Prettier 会拦
  （当前仓库只有 `commit-msg` 钩子，没有 pre-commit 钩子，提交前请手动跑门禁）。
- **中文路径下工具异常**：本仓库路径含中文时 JDK/Maven/pnpm 均已验证可用；
  若第三方工具仍报编码错误，可把仓库放到纯 ASCII 路径再试，并在交接单记录。
- **需要完整重置本地环境**：删除 `node_modules/`、各模块 `target/` 后重跑
  `pnpm install --frozen-lockfile && ./scripts/check.sh`；**不要**删除 `pnpm-lock.yaml`。
