# ANESOP · AGENTS.md 片段（v1.0）

并入仓库根 `AGENTS.md` 作为 L0 规则正文；模块细节放 `docs/modules/<域>/AGENTS.md`。规则版本 v1.0，对齐方案书 V0.9（2026-11-11 编制）；红线或技术栈变更升 major，新增条款升 minor，措辞修正升 patch。

## 技术栈锁定

- 后端：Java 21 LTS、Spring Boot 3.4、MyBatis-Plus 3.5.x + Flyway 10.x、PostgreSQL 16、Redis 7.2、RabbitMQ 3.13、MinIO、Apache Camel 4 + HAPI HL7v2 2.x、springdoc-openapi 2.x。
- 前端：Vue 3.5、TypeScript 5.6、Element Plus 2.9.x、Pinia 3、Vue Router 4、Vite 6.x、ECharts 5.5.x。
- 运行：Docker 24 + Compose（单院）、Nginx 1.27、Prometheus + Grafana + Loki。
- 锁定：根 `pom.xml` 的 `dependencyManagement` 统一版本；`package.json` 精确版本 + `pnpm-lock.yaml`，禁用 `^` 与 `~`；镜像按 digest 引用。
- 变更：新增或升级依赖先写 ADR，再更新 `docs/deps/allowlist.md`（名称、版本、许可证、理由、责任人），同 PR 提交；GPL/AGPL 不得进入交付物，LGPL 需法务结论。
- 准入：直接依赖限于 MIT、Apache-2.0、BSD、ISC、0BSD；依赖漏洞 CVSS ≥7 未处置、镜像存在 HIGH/CRITICAL 可修复漏洞即阻断。

## 模块边界与依赖禁令

- 目录归属：`apps/api/modules/m0`…`m8`（各含 `api`、`impl` 子模块）、`apps/web/src/modules/m0`…`m8`、`packages/contracts/`、`services/device-gateway/`、`db/migration/`。
- 允许通信：同步只调对方 `api` 子模块接口；异步走领域事件（进程内 Spring 事件、跨进程 RabbitMQ）。
- 禁止：跨模块访问对方 Mapper 或数据表；跨模块传可变实体而非 DTO；模块间循环依赖；前端跨模块引用内部组件。
- 检查：ArchUnit 用例 + Maven 依赖分析进 CI；新增跨模块依赖须在 PR"影响面"字段声明。

## 契约优先与数据变更

- 顺序：领域模型 → `db/migration/V<版本>__<描述>.sql` → `packages/contracts/openapi/<域>.yaml`（OpenAPI 3.1），三者先评审后编码。
- 字段只增不删；删除或改类型升 major、双写过渡并写 ADR；Sprint 第 3 个工作日后契约冻结，阻断缺陷除外。
- 迁移遵循 expand-contract；不可逆脚本必须附回滚演练记录并在 PR 声明。
- `apps/web/src/api/generated/` 由契约生成，禁止手改；扩展类型先改契约再生成。
- 跨模块提案走 `docs/rfc/`，架构决策落 `docs/adr/NNNN-*.md`；契约破坏性变更须附兼容说明并双人评审。

## 编码与测试要求

- 覆盖率（JaCoCo / Vitest v8）：整体行 ≥70%、分支 ≥55%；核心域（`modules/m2`、`modules/m0` 权限与审计、`services/device-gateway`）与新增代码 ≥80%/70%；契约端点 100% 有用例。
- 分层：单元（JUnit 5 / Vitest）约 70%、集成（Testcontainers）约 20%、契约（Pact + spectral + oasdiff）、E2E（Playwright）。
- E2E 必测：E2E-01 排程冲突、E2E-02 采集断网补传、E2E-03 记录锁单修订、E2E-04 权限越权、E2E-05 麻精药品双人核对。
- 临床记录专项（时间轴、断网采集、并发修改、签名锁定）为发布准入项，任一不通过不得发布。
- 禁止反模式：恒真断言、mock 被测对象、只测快乐路径、测试复制实现、改测试让实现通过。
- 不稳定用例 24h 内修复，否则隔离并建 Issue；跳过或删除测试须在 PR 说明并经架构师批准。
- 缺陷分级：P0（记录丢失、签名失效、越权、泄露）2h 响应、24h 修复并停线；P1 4h 响应、3 个工作日内修复。
- 日志与异常不得出现姓名、身份证、住院号、手机号、真实 SQL 实参。

## 提交、PR 与评审

- 分支：`main` 唯一长期分支；`feat/<issue>-<slug>` 存活 ≤3 天，`fix/<issue>-<slug>`、`chore/<issue>-<slug>` ≤2 天；只 squash merge，禁 merge commit、禁 force push。
- 提交：Conventional Commits 1.0.0，`<type>(<scope>): <subject>`，scope ∈ {m0…m8, web, api, contracts, gateway, db, deploy, docs}。
- PR 必填八字段：关联 Issue（`Closes #<issue>`）、变更类型、变更说明、影响面、测试证据、回滚方案、检查清单、AI 参与声明；有效改动 ≤400 行，>800 行必须拆分。
- 评审：普通 PR 1 人批准；`modules/m2/`、`modules/m0/` 权限与审计、`db/migration/`、安全相关、契约破坏性变更须 2 人批准，其中至少 1 名 CODEOWNERS 自然人；首轮 24h、核心域 12h。
- 阻断：任一必需检查失败、存在未解决 `blocking` 评论、缺 AI 声明，均不得合并；评审意见分 `blocking`、`suggestion`、`question`，只有 `blocking` 阻断。
- Issue：所有工作先建 Issue，用 `feature.yml`、`bug.yml`、`task.yml`、`ai-task.yml` 四类模板；标签只用 `type/`、`area/`、`priority/`、`size/`、`status/`、`risk/` 前缀，XL 任务不进 Sprint。
- 发布：窗口固定周二、周四 20:00–22:00；生产发布须有 `release.yml` 变更单审批；应用回滚目标 10 分钟，数据库变更先加字段后改逻辑、分两次发布。

## AI 使用与署名

- 任务前按序读，最多 6 项：任务卡 `docs/ai/tasks/TASK-<issue>.md` → 本文件 → `docs/modules/<域>/AGENTS.md` → 契约 → 相关 ADR → `docs/ai/examples/` 示例。
- 只改任务卡写域内路径；越界改契约、改测试断言使其通过，均属违规。
- AI 参与必须在 PR 声明工具与版本、任务号、范围、复核人；单次生成 >50 行的高风险代码，提交加五个 trailer：`AI-Tool`、`AI-Task`、`AI-Scope`、`Reviewed-by`、`Co-authored-by`；`AI-Scope` 必须落在 PR 变更集内。
- AI 不产生 `APPROVE`、不写审批记录、不进入 CODEOWNERS；同一代理不得既写又审。
- 七类高风险代码必须人工逐行并在 PR 注明"已逐行"：事务与并发、权限与数据范围、审计与留痕、计费与对账、临床规则、数据迁移、外部接口映射。
- 硬红线：H-01 患者数据不出院；H-02 不直推 main；H-03 不跳过测试；H-04 不臆造接口与字段；H-05 不引入未审批依赖；H-06 不替代临床决策。违反即停止、回滚、30 分钟内上报。

## 患者数据与安全红线

- 可进外部模型：代码、报错（删参数值/token）、架构与契约。禁入：姓名、身份证、住院号、手机、人脸；年龄+精确日期+科室+罕见术式组合；临床自由文本原文；未断开标识的检验与体征。
- 脱敏三问任一不过即改用合成数据或只给字段名：是否含直接标识；能否组合重识别；是否必要。
- 生产网段禁止访问外部模型 API；模型调用经院内网关，记录账号、时间、任务 ID、prompt 摘要，留存 ≥6 个月。
- 导出必须脱敏；日志、截图、测试夹具不得含真实患者数据；dev/staging/uat 禁用真实患者数据。
- 疑似数据外发：立即停止、保留证据、30 分钟内上报信息安全负责人与项目经理。

## 常用命令

```bash
# 后端（apps/api 目录）
mvn -B verify                                  # 编译、单测、覆盖率门禁
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
```

## 文档与决策索引

- 规则：`AGENTS.md` 为唯一主文件；`CLAUDE.md`、`.cursor/rules/`、`.github/copilot-instructions.md`、`.github/instructions/` 只是适配层，不得新增规则。
- 决策：`docs/adr/NNNN-*.md`；22 项待决 D-01–D-22 见 `corpus/structured/decisions.yaml`，P0 须 2026-11-27 前闭环。
- 契约与字典：`packages/contracts/`、`docs/data-dictionary/`；术语见 `docs/ai/glossary.md` 与 `corpus/facts.yaml`。
- 任务与交接：`docs/ai/tasks/`、`docs/ai/handoffs/`、`docs/ai/usage-log/YYYY-MM.md`。
- 项目上下文：`corpus/CONTEXT_CORE.md`、`corpus/facts.yaml`；原文检索 `corpus/INDEX.md`、`corpus/chunks.jsonl`。

## 提交前自检清单

- [ ] 编译与类型检查通过，改动只落在任务卡写域内
- [ ] 新增行为有测试，断言验证可观察行为，覆盖率未下降
- [ ] 契约未私改，迁移可回滚，权限/审计/脱敏三项横切检查已过
- [ ] 日志、截图、测试夹具无患者标识
- [ ] 无白名单外依赖，lockfile 变更已说明
- [ ] 提交信息符合 Conventional Commits，AI trailer 齐全
- [ ] PR 八字段与 AI 参与声明齐全，回滚方案已写
- [ ] 高风险代码已人工逐行，PR 注明"已逐行"
