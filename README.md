# ANESOP — 手术麻醉信息系统（围术期）

> 一家三甲医院（30 间手术间、年手术量约 3.5 万例）的围术期临床记录与管理平台：
> 覆盖手术申请与排程、术前访视、术中麻醉记录与设备采集、复苏、术后随访、麻精药品、
> 耗材、绩效与质控。**临床记录以本系统为源**，患者主索引/药品/耗材分别以 HIS、药房、SPD 为权威源。

| 项 | 现状（2026-11-12） |
|---|---|
| 阶段 | **S0 工程基建完成（S0-01…S0-12）**；M1 工程骨架就绪，业务功能待 M1 起实现 |
| 里程碑 | M1 工程骨架：2026-12-18 ｜ M2 MVP：2027-01-29 ｜ 验收：2027-07-02 |
| 技术栈 | Java 21 + Spring Boot 3.4 + MyBatis-Plus + Flyway + PostgreSQL 16；Vue 3.5 + TS + Element Plus；独立采集网关 |
| 形态 | 模块化单体（`apps/api/modules/m0…m8`）+ 独立设备采集网关 + Nginx；单院私有化部署 |
| 门禁 | `./scripts/check.sh` 本地全绿（7 步，实测 416s）；CI 10 个 job 定义就绪（远端仓库未建，未在 runner 实跑） |
| 环境限制 | **Docker 不可用（EXT-08）**：Compose 未实跑，镜像 digest 待补；本机无 PostgreSQL，运行时需自备 |

跨会话状态、任务勾选与阻塞以 [`PROJECT_STATE.md`](PROJECT_STATE.md) 为唯一准；本 README 不重复维护进度。

## 快速开始（5 分钟上手 / 全量门禁约 7 分钟，含实测耗时）

### 前置条件

| 依赖 | 要求 | 检查 |
|---|---|---|
| JDK 21 | 解压到 `.toolchain/jdk21`（新 clone 不含，已被 gitignore） | `bash scripts/dev-check.sh` |
| Maven | 3.9+；**一律经 `./scripts/mvn.sh`**（系统默认 JDK 可能不是 21） | 同上 |
| Node / pnpm | Node ≥20（CI 用 22）、pnpm ≥10（CI 用 11） | 同上 |
| 网络 | 首次安装依赖/下载 Maven 构件；之后可离线 | — |
| PostgreSQL 16 | **跑 API 与演示数据时才需要**；`check.sh` 用嵌入式 PG，不需要 | `bash scripts/dev-check.sh` 看 5432 |
| Docker | 可选；本机不可用（EXT-08） | — |

> home 只读：所有缓存必须落在仓库内（`.m2repo`、`.npm-cache`、`.pnpm-store`、`.playwright`）。
> 不要写 `~/.m2`、`~/.npm`，也不要裸调系统 `mvn`/`java`。

### 三步跑通（不依赖数据库）

```bash
# 1) 加载工具链（每个新终端都要）
source scripts/env.sh
# 预期：ANESOP env: JAVA_HOME=<repo>/.toolchain/jdk21 ... / openjdk version "21.0.2"

# 2) 安装前端依赖（lockfile 必须一致）
pnpm install --frozen-lockfile
# 预期：Done / Already up to date；实测缓存就绪时 1s（冷缓存需下载依赖，未单独计时）

# 3) 本地全量门禁：后端 verify + 前端 lint/类型/单测 + 契约 + 规则
./scripts/check.sh
# 预期末行：check.sh: OK / 耗时：416s（6m56s）
```

`check.sh` 的 7 步：后端 `mvn verify`（含 Checkstyle/SpotBugs/JaCoCo）→ `pnpm install --frozen-lockfile`
→ ESLint+Prettier → `vue-tsc` → Vitest 覆盖率 → spectral/oasdiff/生成物漂移 → 规则检查。
任一步失败即非 0 退出并打印失败环节。

**2026-11-12 实测**：

| 步骤 | 命令 | 耗时 | 结果 |
|---|---|---|---|
| 工具链 | `source scripts/env.sh` | <1s | JDK 21.0.2，缓存指向仓库内 |
| 前端依赖 | `pnpm install --frozen-lockfile` | 1s（缓存就绪） | lockfile 一致 |
| 全量门禁 | `./scripts/check.sh` | **416s（6m56s）** | OK：121 个后端用例 + 32 个前端用例 + 契约 0 error/0 warning |
| 合计 | 快速开始三步 | **约 7 分钟** | 全部通过 |

若只想先确认环境（约 10 秒）：`bash scripts/dev-check.sh`，末行 `dev-check: PASS`。

### 只跑前端

```bash
source scripts/env.sh
pnpm install --frozen-lockfile
pnpm -C apps/web dev                 # http://localhost:5173，/api 代理到 localhost:8080
pnpm lint                            # ESLint + Prettier
pnpm -C apps/web exec vue-tsc --noEmit
pnpm test:unit                       # Vitest + v8 覆盖率门禁
pnpm e2e                             # Playwright 3 条壳冒烟（不依赖后端/Docker）
```

前端可独立启动；没有后端时登录会失败，壳页面（登录页/404/重定向）可正常渲染。

### 只跑后端

```bash
source scripts/env.sh

# 编译 + 单测 + 覆盖率 + Checkstyle/SpotBugs（不需要 PG：测试用嵌入式 PG16）
./scripts/mvn.sh -B verify
# 只跑 API 模块及其依赖
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am test

# 启动 API（需要 PostgreSQL 16；dev profile 允许演示账号与进程内 JWT 密钥）
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
export ANESOP_DB_URL=jdbc:postgresql://127.0.0.1:5432/anesop
export ANESOP_DB_USER=anesop ANESOP_DB_PASSWORD=anesop
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
curl -s http://localhost:8080/actuator/health     # 预期 {"status":"UP",...}
```

没有 PostgreSQL 时，后端**测试仍可全绿**，但 API 启动会在 Flyway/数据源阶段失败；先按
[`docs/guides/demo.md`](docs/guides/demo.md) 第 1 步初始化，或见
[`docs/guides/troubleshooting.md`](docs/guides/troubleshooting.md)。

### 需要登录/演示时

1. 准备一个 PostgreSQL 16（本机安装或远程 dev 库），把连接参数写入环境变量：
   `cp .env.example .env` 后按需修改，再 `set -a; source .env; set +a`
   （脚本与应用不会自动读取 `.env`）；
2. `bash scripts/seed-demo.sh --url jdbc:postgresql://127.0.0.1:5432/anesop --user anesop --password anesop`
   （幂等；打印演示账号 `demo_admin` 与“必须改密”提示）；
3. 按 [`docs/guides/demo.md`](docs/guides/demo.md) 启动 API/前端并走登录 → 改密 → 首页 → 审计。

`.env.example` 是本地 dev 模板；生产 Compose 用 [`deploy/compose/.env.example`](deploy/compose/.env.example)，
两者的差异与红线写在前者头部注释中。

## 目录结构

```text
anesop/
├── AGENTS.md                  # L0 工程规则（唯一主文件；CLAUDE.md 等只是适配层）
├── AUTONOMY.md                # 全自动施工规则（不提问/默认决策/阻塞挂号）
├── PROJECT_STATE.md           # 跨会话状态：阶段/任务/阻塞/下一步
├── README.md                  # 本文件
├── .env.example               # 本地 dev 环境变量模板（.env 不入库）
├── apps/
│   ├── api/                   # Spring Boot 3.4 多模块
│   │   ├── api-bootstrap/     # 唯一可执行装配点（8080）
│   │   ├── api-contracts/     # 契约生成的 Java DTO（生成物不入库）
│   │   └── modules/m0…m8/     # 每模块 api（对外接口）+ impl（实现/数据访问）
│   └── web/                   # Vue 3.5 + Vite + TS；src/{api,layout,router,stores,views,modules}
├── packages/
│   ├── contracts/             # OpenAPI 3.1、spectral 规则、TS/Java 生成
│   └── ui/                    # @anesop/ui：设计令牌（tokens.css / tokens.ts）
├── services/device-gateway/   # 独立采集网关（TCP→解析→标准化→本地缓冲→幂等入库）
├── db/
│   ├── migration/             # Flyway V1…V4（生产 locations 只含本目录）
│   └── seed/demo/             # V100 demo_admin（仅 dev/test 的独立 location）
├── deploy/                    # Compose 9 服务、Nginx、Prometheus/Grafana/Loki
├── docs/                      # guides / ai / acceptance / adr / api / ci / db / deploy /
│                              # gateway / modules / plan / testing / ui / data-dictionary
├── scripts/                   # env.sh、mvn.sh、check.sh、dev-check.sh、seed-demo.sh、
│                              # ci-local.sh、contract-check.sh、gen-contracts.sh 等
├── config/                    # Checkstyle / SpotBugs 规则
├── .github/                   # CODEOWNERS、Issue/PR 模板、ci.yml（10 job）
├── .toolchain/                # 本地 JDK 21（不入库）
└── .m2repo/ .npm-cache/ …     # 仓库内缓存（不入库）
```

## 常用命令

```bash
source scripts/env.sh                    # 每个新终端先执行

bash scripts/dev-check.sh                # 环境自检（版本/缓存/文件/端口），末行 PASS/FAIL
./scripts/check.sh                       # 本地全量门禁（提交前必跑）
./scripts/mvn.sh -B verify               # 后端编译/单测/覆盖率/Checkstyle/SpotBugs
./scripts/mvn.sh -B -f services/device-gateway/pom.xml verify   # 采集网关 39 用例

pnpm install --frozen-lockfile           # 前端依赖（禁止改 lockfile 绕过）
pnpm lint                                # ESLint + Prettier
pnpm -C apps/web exec vue-tsc --noEmit   # 类型检查
pnpm test:unit                           # Vitest + 覆盖率
pnpm e2e                                 # Playwright 冒烟
pnpm -C apps/web build                   # 生产构建 → apps/web/dist

bash scripts/contract-check.sh           # spectral + oasdiff + 生成物漂移
bash scripts/gen-contracts.sh [--java]   # 契约生成（禁止手改生成物）
bash scripts/check-rules.sh              # 适配层漂移/临时条款过期
bash scripts/ci-local.sh --fast          # 本地 CI 等价（无远端时）
bash scripts/seed-demo.sh --url …        # 演示数据（需 PG；幂等；生产禁用）
bash scripts/compose-check.sh            # Compose 静态校验（EXT-08，不启动容器）
bash scripts/install-hooks.sh            # 启用 commitlint（commit-msg 钩子）
```

## 演示入口

| 入口 | 说明 |
|---|---|
| [`docs/guides/demo.md`](docs/guides/demo.md) | ACCEPTANCE C 演示脚本：初始化→登录/改密→（M1 后）排程→术前→术中→复苏→随访→统计→质控→驾驶舱；**明确标注 S0 已可用与待 M1/M2 的步骤** |
| [`scripts/seed-demo.sh`](scripts/seed-demo.sh) | 合成数据初始化（Flyway `db/migration` + `db/seed/demo`）；幂等；支持 `--url` 与 `--compose`；无 PG 时清晰报错 |
| [`docs/db/seed.md`](docs/db/seed.md) | 演示账号 `demo_admin`、角色/权限/字典基线、生产隔离红线 |
| [`docs/guides/quickstart.md`](docs/guides/quickstart.md) | 新人 30 分钟路径（环境→门禁→前后端→演示→排错） |

S0 阶段可演示：登录/强制改密/当前用户/会话/登出、审计留痕与 append-only、采集网关
（模拟设备→断网缓冲→幂等补传）。排程、麻醉记录、统计、质控、驾驶舱为 M1/M2/M6/M7/M8 范围。

## 测试与覆盖率

| 层 | 技术 | 现状（2026-11-12） |
|---|---|---|
| 后端单元/集成 | JUnit 5 + AssertJ + Spring Boot Test + Zonky 嵌入式 PG16 | **121** 用例全绿（m0 权限/审计、迁移、采集网关、bootstrap） |
| 前端单元 | Vitest + Vue Test Utils + v8 coverage | **32** 用例 / 10 文件；行 88.3%、分支 80.21% |
| 契约 | spectral（0 error/0 warning）+ oasdiff + 生成物漂移 | 4 域 23 path/28 operation 全过 |
| E2E | Playwright Chromium | 3 条壳冒烟（首页/重定向/404）；E2E-01–05 待 M1/M2 |
| 覆盖率门禁 | JaCoCo + Vitest v8 | 整体行 ≥70%/分支 ≥55%；核心域 ≥80%/70%；m0/网关有到期 ratchet（2026-12-18） |

```bash
# 覆盖率报告
open apps/api/api-bootstrap/target/site/jacoco-aggregate/index.html   # m0 聚合
open services/device-gateway/target/site/jacoco/index.html            # 网关
open apps/web/coverage/index.html                                    # 前端
```

细则与 ratchet 记录见 [`docs/testing/README.md`](docs/testing/README.md)。

## CI

工作流 [`.github/workflows/ci.yml`](.github/workflows/ci.yml) 定义 **10 个必需检查**：
`ci/lint`、`ci/typecheck`、`ci/unit-test`、`ci/contract`、`ci/build`、`ci/sast`、
`ci/deps-license`、`ci/secret-scan`、`ci/image-scan`、`ci/e2e-smoke`；权限只读、并发取消、
缓存落在仓库内路径。远端 GitHub 组织尚未建立，**尚未在真实 runner 跑过**；本地等价用
`bash scripts/ci-local.sh`（无 Docker/Semgrep/gitleaks 时对应 job 明确 SKIP，不掩盖失败）。
细节与例外（EXT-08 镜像构建、3 条 dev-only 审计例外）见 [`docs/ci/README.md`](docs/ci/README.md)。

## 文档索引

| 文档 | 内容 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | L0 规则：技术栈锁定、模块边界、契约优先、测试、PR、AI 与患者数据红线 |
| [`AUTONOMY.md`](AUTONOMY.md) | 全自动施工规则与决策/合并/上线/数据四道闸门 |
| [`PROJECT_STATE.md`](PROJECT_STATE.md) | 阶段、S0 任务勾选、阻塞、下一步 |
| [`docs/guides/quickstart.md`](docs/guides/quickstart.md) | 新人 30 分钟上手（含预期输出与时间盒） |
| [`docs/guides/dev-workflow.md`](docs/guides/dev-workflow.md) | 分支/提交/门禁/契约/迁移/交接单/链接校验 |
| [`docs/guides/demo.md`](docs/guides/demo.md) | ACCEPTANCE C 演示脚本与步骤可用性 |
| [`docs/guides/troubleshooting.md`](docs/guides/troubleshooting.md) | JDK、pnpm、嵌入式 PG、Playwright、Docker、端口、权限等 |
| [`docs/acceptance/ACCEPTANCE.md`](docs/acceptance/ACCEPTANCE.md) | A–E 验收清单与判定规则 |
| [`docs/acceptance/STATUS.md`](docs/acceptance/STATUS.md) | 施工状态看板（每 Round 更新） |
| [`docs/acceptance/EXTERNAL-DEPS.md`](docs/acceptance/EXTERNAL-DEPS.md) | EXT-01…EXT-11 外部依赖与解除条件 |
| [`docs/plan/BASELINE.md`](docs/plan/BASELINE.md) | 方案 V1.0 基线（冻结） |
| [`docs/decisions.md`](docs/decisions.md) | D-01–D-22 决策与 Q 待确认（唯一决策台账） |
| [`docs/adr/`](docs/adr/) | ADR-0001…0010（模块布局、静态检查、契约、部署、迁移、网关、认证、测试、前端、CI） |
| [`docs/api/README.md`](docs/api/README.md) | OpenAPI 契约、认证/错误/分页约定、版本流程 |
| [`docs/db/README.md`](docs/db/README.md) | 迁移规范、schema 归属、expand-contract、回滚 |
| [`docs/db/seed.md`](docs/db/seed.md) | 演示账号与种子隔离 |
| [`docs/testing/README.md`](docs/testing/README.md) | 测试分层、覆盖率门禁、ratchet、E2E 计划 |
| [`docs/ci/README.md`](docs/ci/README.md) | 10 job 矩阵、缓存、依赖许可证与漏洞策略 |
| [`docs/deploy/README.md`](docs/deploy/README.md) | 单院 Compose 部署、备份恢复、上线闸门 |
| [`docs/ui/README.md`](docs/ui/README.md) | 前端壳、设计令牌、会话存储与 401 刷新 |
| [`docs/gateway/README.md`](docs/gateway/README.md) | 采集网关协议、缓冲/幂等、指标、容量与演进 |
| [`docs/modules/m0/README.md`](docs/modules/m0/README.md) | M0 认证/RBAC/审计接口与权限码 |
| [`docs/ai/README.md`](docs/ai/README.md) | 任务卡/交接单/会话日志/语料使用与刷新 |
| [`docs/ai/SESSION_STARTER.md`](docs/ai/SESSION_STARTER.md) | 新会话启动词（总控/模块/站会/收尾） |
| [`docs/ai/tasks/S0-backlog.md`](docs/ai/tasks/S0-backlog.md) | S0 任务卡与 M1 退出准则 |
| [`docs/ai/corpus/README.md`](docs/ai/corpus/README.md) | 方案书语料包（生成物，勿手改） |
| [`docs/branch-protection.md`](docs/branch-protection.md) | main 分支保护与 CODEOWNERS 落地步骤 |
| [`apps/api/README.md`](apps/api/README.md) | 后端模块边界、数据归属、运行与测试 |
| [`apps/web/README.md`](apps/web/README.md) | 前端工程、页面分包、运行与测试 |
| [`packages/contracts/README.md`](packages/contracts/README.md) | 契约目录、规则、生成与兼容检查 |
| [`packages/ui/README.md`](packages/ui/README.md) | 设计令牌使用与约束 |
| [`services/device-gateway/README.md`](services/device-gateway/README.md) | 采集网关运行、模拟设备与边界 |

## 已知限制（2026-11-12）

- **Docker 不可用（EXT-08）**：`docker compose up` 未实跑，`deploy/compose` 仅静态校验；
  镜像 digest 待首次可运行环境补齐；Testcontainers 路径暂不可用，测试用 Zonky 嵌入式 PG16。
- **本机无 PostgreSQL**：`check.sh`/单测可全绿；API 启动与 `seed-demo.sh` 需自备 PG16，
  否则以清晰错误退出（见 [`docs/guides/troubleshooting.md`](docs/guides/troubleshooting.md)）。
- **远端 GitHub 未建**：CI 未在真实 runner 运行；CODEOWNERS 团队名为占位符，审批未生效。
- **业务功能未实现**：M1–M8 模块仅有骨架/契约；排程、麻醉记录、复苏随访、麻精、统计、
  质控、驾驶舱待对应里程碑。E2E-01–05 未落地。
- **覆盖率 ratchet**：m0-impl 单模块与网关分支阈值为临时值，到期 2026-12-18，需在此前补齐。
- **语料刷新依赖缺失**：`scripts/build_corpus.py` 需要 `python-docx`/`pyyaml`/`tiktoken`，
  当前环境未安装（S0-12 未重跑语料）；刷新方法见 [`docs/ai/README.md`](docs/ai/README.md)。
- 外部系统（HIS/EMR/LIS/PACS/CA/SPD/上报）均为 SIMULATED，解除条件见
  [`docs/acceptance/EXTERNAL-DEPS.md`](docs/acceptance/EXTERNAL-DEPS.md)。

## 协作与红线（摘要）

- 规则主文件 [`AGENTS.md`](AGENTS.md)；变更走 Issue → 任务卡（写域）→ PR（八字段 + AI 声明）
  → 门禁 → 评审。AI 不产生 `APPROVE`、不直推 main、不跳过测试、不臆造接口字段。
- **患者数据不出院**：日志、截图、测试夹具不得含真实标识；测试只用 `SYNTHETIC` 数据；
  生产网段禁止访问外部模型 API。
- 生产发布与真实数据迁移必须人工签字；自动化施工不执行生产部署。
