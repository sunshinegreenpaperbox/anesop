# S0 任务卡（工程基建，对应 M1 里程碑：2026-12-18）

> 使用方式：总控会话按依赖顺序把任务派给队友或自己执行；每个任务开始前把状态改为 `In Progress`，完成后改为 `Done` 并在 `PROJECT_STATE.md` 勾选。
> 写域是硬约束：同一时间同一路径只允许一个写入者；跨写域改动先拆任务。

## 任务总览

| ID | 任务 | 写域 | 依赖 | 预估 | 状态 |
|---|---|---|---|---|---|
| S0-01 | 仓库初始化与协作资产 | 根目录、`.github/` | — | 0.5 天 | Done |
| S0-02 | monorepo 骨架 | `apps/`、`packages/`、`services/`、`db/`、`deploy/`、`pom.xml`、`package.json` | S0-01 | 2 天 | Done |
| S0-03 | 静态检查与提交规范 | 根配置文件、`scripts/` | S0-02 | 1 天 | Done |
| S0-04 | CI 流水线 | `.github/workflows/`、`scripts/` | S0-02/03/11 | 1.5 天 | Ready |
| S0-05 | 契约基线（OpenAPI 3.1） | `packages/contracts/`、`docs/api/` | S0-02 | 2 天 | In Progress |
| S0-06 | 数据库迁移基线 | `db/migration/`、`apps/api/modules/m0/` | S0-02 | 1.5 天 | In Progress |
| S0-07 | 认证与权限骨架 | `apps/api/modules/m0/`、`apps/web/src/modules/m0/` | S0-05/06 | 3 天 | Ready |
| S0-08 | 前端壳与设计令牌 | `apps/web/src/{layout,router,stores,api}/`、`packages/ui/` | S0-05/07 | 3 天 | Ready |
| S0-09 | 采集网关 POC | `services/device-gateway/`、`db/migration/` | S0-02/06 | 3 天 | Ready |
| S0-10 | 部署环境（Compose） | `deploy/compose/`、`scripts/` | S0-02 | 1.5 天 | In Progress |
| S0-11 | 测试基座 | 各模块 `src/test/`、`e2e/` | S0-02 | 2 天 | Ready |
| S0-12 | 开发文档与一键起 | `README.md`、`docs/`、`scripts/` | S0-02/10 | 1 天 | Ready |

**并行建议**：01→02 串行；之后 03/05/06/10 并行；再 04/07/09；最后 08/11/12。总控最多同时开 3 个队友。

---

## S0-01 仓库初始化与协作资产

- 目标：`git init`，建立 `.gitignore`、`README.md`、`.github/CODEOWNERS`、Issue/PR 模板、分支保护说明（远端未建时先写 `docs/branch-protection.md`）。
- 验收：首个提交存在；`git status` 干净；四类 Issue 模板可渲染；CODEOWNERS 覆盖 m0–m8、契约、迁移、安全、部署。
- 写域：根目录文件、`.github/`、`docs/branch-protection.md`。
- 非目标：不配置远端 CI、不建 GitHub 组织。

## S0-02 monorepo 骨架

- 目标：按方案书 §3.2 建目录：`apps/api`（Spring Boot 3.4 多模块：bootstrap + `modules/m0..m8` 各 `api`/`impl`）、`apps/web`（Vue 3.5 + Vite + TS 路由壳）、`packages/contracts`、`services/device-gateway`、`db/migration`、`deploy/`。
- 验收：`mvn -B verify` 空跑通过（含 ArchUnit 用例占位）；`pnpm install && pnpm -C apps/web build` 通过；目录与 §3.2 一致；每个模块有 README。
- 写域：`apps/`、`packages/`、`services/`、`db/`、`deploy/`、根 `pom.xml`、`package.json`、`pnpm-workspace.yaml`。
- 非目标：不实现业务功能。

## S0-03 静态检查与提交规范

- 目标：ESLint + Prettier + vue-tsc；Checkstyle + SpotBugs；EditorConfig；commitlint；`scripts/check.sh` 一键本地检查。
- 验收：故意写一个违规文件能被拦下；`./scripts/check.sh` 全绿；耗时 <3 分钟。
- 写域：根配置、`scripts/`。

## S0-04 CI 流水线

- 目标：GitHub Actions：lint、typecheck、单测、契约（spectral/oasdiff）、构建、SAST（Semgrep）、依赖与许可证、镜像构建；缓存与超时；10 分钟内完成。
- 验收：PR 上 10 个必需检查可见；故意制造红灯能被阻断；缓存命中。
- 写域：`.github/workflows/`、`scripts/`。
- 依赖：S0-02/03/11；远端仓库未就绪时先用 `act` 或本地脚本模拟，工作流文件先落地。

## S0-05 契约基线

- 目标：`packages/contracts/openapi/` 下 auth、patient、scheduling、anesthesia 四域 OpenAPI 3.1 骨架；`spectral` 规则；TS/Java 类型生成脚本；`oasdiff` 兼容检查。
- 验收：`npx spectral lint` 通过；生成物可编译；破坏性变更能被 oasdiff 拦下；契约与 `docs/data-dictionary` 初稿一致。
- 写域：`packages/contracts/`、`scripts/`、`docs/api/`。

## S0-06 数据库迁移基线

- 目标：Flyway V1：用户/角色/权限、审计日志、字典表；Testcontainers 集成测试；回滚说明。
- 验收：空库迁移成功；重复执行幂等；升级路径（V1→V2 占位）通过；测试容器可在 CI 跑。
- 写域：`db/migration/`、`apps/api/modules/m0/`、`apps/api/api-bootstrap/src/test/`。

## S0-07 认证与权限骨架

- 目标：JWT/SSO 占位、RBAC + 数据权限、审计切面、登录接口、越权用例。
- 验收：登录→拿 token→访问受保护接口；越权返回 403 且记审计；单测覆盖正常/越权/过期。
- 写域：`apps/api/modules/m0/`、`apps/web/src/modules/m0/`。

## S0-08 前端壳与设计令牌

- 目标：登录页、主布局、路由与权限菜单、请求封装（token 刷新）、错误处理、设计令牌（色板/字号/间距）。
- 验收：登录后进入首页；无权限菜单不显示；接口 401 自动跳登录；`pnpm build` 通过。
- 写域：`apps/web/src/{layout,router,stores,api}/`、`packages/ui/`。

## S0-09 采集网关 POC

- 目标：模拟设备（TCP 或串口）→ 解析 → 标准化 → 入库；断网本地缓存 ≥4 小时；幂等补传；健康检查。
- 验收：1 台模拟设备端到端 10 分钟数据不丢；断网 5 分钟后恢复自动补传且不重复；监控指标可见。
- 写域：`services/device-gateway/`、`db/migration/`（采集表）。
- 说明：本任务只做 POC，不做多协议全量适配；设备协议清单待 D-06。

## S0-10 部署环境

- 目标：Docker Compose：PostgreSQL 16、Redis 7.2、RabbitMQ 3.13、MinIO、Nginx、API、Web；健康检查与依赖顺序；`docker compose up -d` 一键起。
- 验收：干净机器 10 分钟内起全栈；健康检查全绿；数据卷与配置分离；README 有排障。
- 写域：`deploy/compose/`、`scripts/`。

## S0-11 测试基座

- 目标：JUnit 5 + Testcontainers；Vitest + Playwright 冒烟；覆盖率门禁（整体 ≥70%/55%，核心域 ≥80%/70%）；测试数据工厂。
- 验收：`mvn verify` 与 `pnpm test` 产出覆盖率报告；门禁生效；E2E 冒烟跑通登录。
- 写域：各模块 `src/test/`、`apps/web/e2e/`、根测试配置。

## S0-12 开发文档与一键起

- 目标：README（快速开始、目录、命令）、`.env.example`、模块 README、`docs/ai` 使用说明、语料刷新脚本说明。
- 验收：新人按 README 30 分钟内跑起来；环境变量无缺失；链接有效。
- 写域：`README.md`、`docs/`、`scripts/`。

## S0 退出准则（M1 判定）

1. `docker compose up -d` 后登录→首页→一个受保护接口打通；
2. CI 10 个检查全绿，覆盖率达标；
3. 契约 lint + 生成 + 兼容检查通过；
4. 空库与升级库迁移均通过；
5. 采集 POC 断网补传通过；
6. 新人按 README 30 分钟内可运行。
