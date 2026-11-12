# ANESOP 手术麻醉信息系统（围术期）

> 状态：**M1 工程骨架就绪进行中**，当前处于 Sprint 0（S0 工程基建）。完整任务状态见 [`PROJECT_STATE.md`](PROJECT_STATE.md)。

## 项目简介

ANESOP 为一家三甲医院（30 间手术间、年手术量约 3.5 万例）建设围术期手术麻醉信息系统，覆盖手术申请与排程、术前访视评估、术中麻醉记录与设备采集、复苏、术后随访、麻精药品、耗材、绩效与质控管理全流程。系统定位为“临床记录系统 + 围术期管理平台”，采用模块化单体 + 独立设备采集网关，单院私有化部署，一期交付 M0 公共支撑与 M1–M8 业务模块。临床记录以本系统为源，患者主索引、药品目录、耗材目录分别以 HIS、药房、SPD 为权威来源。

## 技术栈摘要

| 层 | 选型 |
|---|---|
| 后端 | Java 21 LTS、Spring Boot 3.4、MyBatis-Plus 3.5.x、Flyway 10.x、springdoc-openapi 2.x |
| 数据与中间件 | PostgreSQL 16、Redis 7.2、RabbitMQ 3.13、MinIO |
| 集成 | Apache Camel 4 + HAPI HL7v2 2.x，独立 `services/device-gateway` 采集网关 |
| 前端 | Vue 3.5、TypeScript 5.6、Element Plus 2.9.x、Pinia 3、Vue Router 4、Vite 6、ECharts 5.5.x |
| 运行与观测 | Docker 24 + Compose（单院）、Nginx 1.27、Prometheus + Grafana + Loki |

版本锁定：根 `pom.xml` 的 `dependencyManagement` 统一管理，`package.json` 使用精确版本（禁用 `^`/`~`）并提交 `pnpm-lock.yaml`。新增或升级依赖先写 ADR 并更新 `docs/deps/allowlist.md`。

## 目录结构

S0-02 完成后为以下布局（当前部分目录尚未创建）：

```text
anesop/
├── AGENTS.md                  # L0 工程规则（唯一主文件）
├── AUTONOMY.md                # 全自动施工规则
├── PROJECT_STATE.md           # 跨会话状态：阶段/任务/阻塞/下一步
├── README.md                  # 本文件
├── apps/
│   ├── api/                   # Spring Boot 3.4 多模块：bootstrap + modules/m0..m8（各含 api/impl）
│   └── web/                   # Vue 3.5 + Vite + TS，按 modules/m0..m8 分包
├── packages/
│   ├── contracts/             # OpenAPI 3.1 契约、生成类型、spectral/oasdiff 规则
│   └── ui/                    # 共享组件与设计令牌
├── services/device-gateway/   # 设备采集网关（独立进程，断网缓存 + 幂等补传）
├── db/migration/              # Flyway V<版本>__<描述>.sql
├── deploy/                    # Compose、Nginx、监控配置
├── docs/
│   ├── plan/                  # 方案书 V0.9 与基线（BASELINE.md）
│   ├── decisions.md           # D-01–D-22 决策台账
│   ├── adr/                   # 架构决策记录
│   ├── acceptance/            # 验收标准与追溯矩阵
│   └── ai/                    # AI 语料、任务卡、交接单、会话日志
├── scripts/                   # env.sh / mvn.sh / check.sh / build_corpus.py
└── .github/                   # CODEOWNERS、Issue/PR 模板（CI 由 S0-04 落地）
```

## 快速开始

> **S0 进行中：以下命令随 S0 各任务完成逐步可用**，未就绪的命令会因缺少 `pom.xml`、`package.json` 等而失败，属预期。

| 步骤 | 命令 | 可用时点 |
|---|---|---|
| 1. 加载工具链 | `source scripts/env.sh` | ✅ 现在 |
| 2. 后端编译与测试 | `./scripts/mvn.sh -B verify` | S0-02 起 |
| 3. 前端依赖 | `pnpm install --frozen-lockfile` | S0-02 起 |
| 4. 前端校验 | `pnpm lint` | S0-03 起 |
| 5. 本地全量门禁 | `./scripts/check.sh` | S0-03 起 |
| 6. 本地全栈 | `docker compose -f deploy/compose/docker-compose.yml up -d` | S0-10 起 |

环境约束：

- 必须先 `source scripts/env.sh`。它把 JDK 21（`.toolchain/jdk21`）、Maven 本地仓库（`.m2repo`）、npm/pnpm 缓存固定到仓库内；home 只读，**禁止写 `~/.m2`、`~/.npm`**。
- Maven 一律用 `./scripts/mvn.sh`（系统默认 JDK 非 21，不要裸调 `mvn`）。
- **Docker 当前不可用**（EXT-08）：Compose 启动与 Testcontainers 相关命令暂不可执行，只做静态校验，不阻塞主线；集成测试用 H2/嵌入式 PG 或 mock 替代。

## 常用命令

```bash
source scripts/env.sh                          # 必须先执行

# 后端（仓库根）
./scripts/mvn.sh -B verify                     # 编译、单测、覆盖率门禁
./scripts/check.sh                             # 本地全量门禁（后端 + 前端 + 契约）

# 前端（仓库根）
pnpm install --frozen-lockfile
pnpm lint                                      # ESLint + Prettier
pnpm -C apps/web exec vue-tsc --noEmit         # 类型检查
pnpm test:unit --coverage
pnpm e2e                                       # Playwright

# 契约、规则与提交前检查
npx spectral lint packages/contracts/openapi/*.yaml
./scripts/check-rules.sh                       # 适配层漂移、临时条款过期
git diff --check

# 本地全栈（Docker 当前不可用，S0-10 后）
docker compose -f deploy/compose/docker-compose.yml up -d
```

## 工程规则摘要

规则主文件为 [`AGENTS.md`](AGENTS.md)（适配层 `CLAUDE.md`、`.cursor/rules/` 等不得新增规则）；全自动施工规则见 [`AUTONOMY.md`](AUTONOMY.md)；方案基线见 [`docs/plan/BASELINE.md`](docs/plan/BASELINE.md)；验收口径见 [`docs/acceptance/`](docs/acceptance/)。

- **模块边界**：`apps/api/modules/m0..m8` 各分 `api`/`impl`，跨模块只依赖对方 `api`；禁访问对方表或 Mapper、禁循环依赖，由 ArchUnit + 依赖分析在 CI 检查。
- **契约优先**：领域模型 → Flyway 迁移 → OpenAPI 3.1 契约，三者评审后编码；字段只增不删，破坏性变更升 major 并双人评审；`apps/web/src/api/generated/` 禁止手改。
- **提交与 PR**：`main` 唯一长期分支，`feat/` ≤3 天、`fix/`/`chore/` ≤2 天；只 squash merge，禁 force push；Conventional Commits 1.0.0；PR 必填八字段（关联 Issue、变更类型、变更说明、影响面、测试证据、回滚方案、检查清单、AI 参与声明），单 PR 有效改动 ≤400 行。
- **评审**：普通 PR 1 人批准；`modules/m2/`、`modules/m0/` 权限与审计、`db/migration/`、安全、契约破坏性变更 2 人批准且至少 1 名 CODEOWNERS。AI 不产生 `APPROVE`、不进入 CODEOWNERS。
- **测试**：覆盖率整体行 ≥70%/分支 ≥55%，核心域与新增代码 ≥80%/70%，契约端点 100%；E2E-01–05 与临床记录专项（时间轴、断网采集、并发修改、签名锁定）为发布准入项。
- **数据红线**：患者数据不出院；日志、截图、测试夹具不得含真实患者标识；测试只用标记 `SYNTHETIC` 的合成数据。
- **AI 红线**：不直推 main、不跳过测试、不臆造接口与字段、不引入未审批依赖、不替代临床决策；七类高风险代码须人工逐行。

## 当前里程碑状态

| 项 | 状态 |
|---|---|
| 里程碑 | M1 工程骨架就绪：**2026-12-18**；后续 M2 2027-01-29 … 验收 2027-07-02 |
| 阶段 | **Sprint 0 进行中**（M1/S0）；S0-01 仓库初始化与协作资产本任务 |
| 方案 | V1.0 基线已冻结（2026-11-12），见 `docs/plan/BASELINE.md` |
| 已知约束 | Docker 不可用（EXT-08）；远端 GitHub 组织未建，CODEOWNERS 与分支保护暂用占位团队名 |

当前任务、阻塞与下一步以 [`PROJECT_STATE.md`](PROJECT_STATE.md) 为准，本 README 不重复维护进度。

## 文档索引

| 路径 | 内容 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | L0 工程规则：技术栈锁定、模块边界、契约、测试、PR、AI 与患者数据红线 |
| [`AUTONOMY.md`](AUTONOMY.md) | 全自动施工规则：不提问、默认决策、阻塞挂号、最终统一验收 |
| [`PROJECT_STATE.md`](PROJECT_STATE.md) | 跨会话状态：阶段、完成项、进行中、阻塞、下一步 |
| [`docs/plan/`](docs/plan/) | 方案书 V0.9（docx/PDF）、事实卡、[`BASELINE.md`](docs/plan/BASELINE.md) 基线 |
| [`docs/decisions.md`](docs/decisions.md) | D-01–D-22 决策状态与 Q-01–Q-15 客户确认状态（唯一决策台账） |
| [`docs/adr/`](docs/adr/) | 架构决策记录，模板见 `docs/adr/TEMPLATE.md` |
| [`docs/ai/tasks/`](docs/ai/tasks/) | 任务卡与 S0 待办（写域、验收、非目标） |
| [`docs/ai/corpus/`](docs/ai/corpus/) | AI 常驻语料、结构化 YAML、章节分片与检索块（生成物勿手改） |
| [`docs/acceptance/`](docs/acceptance/) | 验收标准、追溯矩阵、外部依赖与最终报告 |
| [`docs/branch-protection.md`](docs/branch-protection.md) | `main` 分支保护规则与远端建立后的落地步骤 |
| [`.github/`](.github/) | CODEOWNERS、Issue Forms、PR 模板 |

## AI 协作会话

- **总控会话**：负责目标、拆任务、仲裁与验收，不写业务代码。
- **模块会话**：负责一个模块（M0–M8）或一个工作流（契约、采集、CI），按写域领取任务卡。
- **换会话前**：先写交接单并更新 `PROJECT_STATE.md`；新会话从交接单接续。启动词见 [`docs/ai/SESSION_STARTER.md`](docs/ai/SESSION_STARTER.md)。
- 仓库是唯一共享记忆：口头结论无效，所有结论必须落文件。
