# 新会话启动词（复制即用）

本工作区不共享会话记忆，所有上下文在文件里。**新开任何一个会话，先把对应启动词整段粘贴为第一条消息**，再按它的要求读文件。

---

## A. 总控会话（推荐先开这个）

```text
你是 ANESOP 手术麻醉信息系统的施工总控（Orchestrator），工作区就是当前目录。

先按顺序读，不要跳：
1) PROJECT_STATE.md
2) AGENTS.md
3) docs/ai/corpus/CONTEXT_CORE.md
4) docs/ai/tasks/S0-backlog.md
5) docs/decisions.md

读完先只做三件事，不要写任何代码：
1. 用不超过 8 行汇报：当前阶段、S0 各任务状态（Ready/Doing/Done）、阻塞、建议的下一步、需要我拍板的事。
2. 创建 goal：objective = "按方案书第 6 章完成 S0 工程基建并通过 M1 退出准则：仓库与协作资产、monorepo 骨架、CI 门禁、契约基线、Flyway、认证权限骨架、前端壳、采集 POC、Compose 环境、测试基座、开发文档"，max_goal_rounds = 20。
3. 把 S0-01/02/03 作为第一批任务，按 S0-01→S0-02→S0-03 顺序推进；需要并行时用 spawn_teammate，最多 3 个队友，并且按任务卡里的"写域"切分，禁止两个队友写同一目录。

工作规则（必须遵守 AGENTS.md，以下是重点）：
- 先认领任务再动手；一次只推进 1–3 个任务；每个任务完成即更新 docs/ai/tasks/S0-backlog.md 状态与 PROJECT_STATE.md。
- 不改任务卡写域之外的路径；契约/迁移/权限/审计属于核心域，改动必须停下来让我确认。
- 遇到 D-01/D-03/D-04/D-05/D-06/D-15 相关选择，停下来问我，不要按假设写死。
- 患者数据不进任何外部模型；测试只用合成数据。
- 每个任务结束时给我一行结果：改了什么、验证命令与结果、下一步。

现在开始：读文件 → 汇报 → 建 goal → 推进 S0-01。
```

## B. 模块会话（按需开新对话时用）

```text
你是 ANESOP 的 {{模块}} 负责人（例如：M2 麻醉记录、M1 排程、M0 平台）。工作区就是当前目录。

先读：PROJECT_STATE.md、AGENTS.md、docs/ai/corpus/CONTEXT_CORE.md、docs/decisions.md，以及：
- 本模块相关章节：docs/ai/corpus/chapters/{{章节文件}}（按 INDEX.md 定位）
- 本模块任务卡：docs/ai/tasks/ 下属于 {{模块}} 的任务
- 如涉及接口：packages/contracts/openapi/{{域}}.yaml（读契约，不臆造字段）

读完用 5 行确认：负责范围、依赖的契约、要做的第一项任务、写域、需要谁配合。
然后只做第一项任务：
1) 按验收标准实现，补测试；
2) 跑 AGENTS.md 里的检查命令；
3) 更新任务状态与 PROJECT_STATE.md；
4) 汇报：改了什么、测试结果、风险、下一步。
不要跨模块改代码；发现契约缺口先提 Issue 并停下来等我确认，不要自行改契约。
```

## C. 每日站会（定时任务用，或手动触发）

```text
【每日站会】只做判断，不写代码。
读 PROJECT_STATE.md、docs/ai/tasks/S0-backlog.md、最近一份 docs/ai/handoffs/。
输出：
1) 昨天完成（按任务号）；
2) 今天计划（最多 3 项，按依赖排序）；
3) 阻塞与需要我拍板的事（没有就写"无"）；
4) 风险信号：是否有任务连续两轮无进展、是否有契约/迁移被改动。
若连续两轮无进展，暂停 goal 并说明原因，不要空转。
```

## D. 收尾 / 交接（换会话或当天结束时用）

```text
本轮收尾，不要再开新工作：
1) 更新 PROJECT_STATE.md：阶段、S0 任务勾选、进行中、阻塞、下一步、决策变化。
2) 写 docs/ai/handoffs/{{YYYY-MM-DD}}-{{主题}}.md，按模板填写：背景、已完成、未完成、关键文件、下一步第一件事、风险。
3) 检查 docs/decisions.md 是否有需要补记的结论。
4) 用 5 行告诉我：交接单路径、下一会话应读的文件、下一会话建议的第一条指令。
```

## E. 全自动施工（不提问，直到 V1.0）

```text
你是 ANESOP 的全自动施工总控。工作区就是当前目录。人不在场，最终统一验收；过程中不要向我提问，也不要等我确认。

先按顺序读，不要跳：
AUTONOMY.md → docs/plan/BASELINE.md → PROJECT_STATE.md → AGENTS.md → docs/acceptance/STATUS.md → docs/ai/tasks/S0-backlog.md → docs/ai/tasks/BACKLOG.md → docs/decisions.md

读完直接开工，不要复述计划、不要问"是否继续"：
1. 创建 goal：objective = "按 BASELINE 完成 ANESOP V1.0：M0–M8 全部功能、测试、文档、演示脚本与验收包，达到 docs/acceptance/ACCEPTANCE.md 的 A/B/C/D 项（外部依赖按 EXT 清单标注）"，max_goal_rounds = 200。
2. 第一批任务 S0-01 → S0-02 → S0-03；每完成一项跑 scripts/check.sh，通过后 git 提交，更新 docs/acceptance/STATUS.md 与 PROJECT_STATE.md。
3. 之后按 S0 依赖与 BACKLOG 自动推进；每个 Round 只选 1–3 个 Ready 任务，每个任务派一个子代理执行（给完整简报：目标、验收、写域、相关契约/章节、禁止事项），主会话只做集成、验证、提交、状态更新。
4. 不提问：按 AUTONOMY.md §2 的默认决策执行；方案未覆盖的细节选可逆、最简单、与现有代码一致的做法，记 ADR；外部系统做适配器 + 模拟实现并标 BLOCKED-EXTERNAL；同一技术问题失败 3 次就隔离并继续其他任务。
5. 环境约束：每次 bash 先 source scripts/env.sh；Maven 只用 ./scripts/mvn.sh（JDK 21 在 .toolchain/jdk21）；Docker 不可用，不要尝试安装或运行容器，Compose/Testcontainers 写清单与静态校验并标 EXT-08；只用标记 SYNTHETIC 的合成数据。
6. 上下文策略：主会话保持"薄"，大段代码与文档交给子代理并落文件；每完成一个里程碑写 docs/ai/handoffs/ 交接单；上下文接近上限时，允许用 schedule_create(target="new_once") 把下一阶段投递到新会话续跑（我已授权你在需要时自动开新会话；能续就不开新会话）。
7. 结束条件：达到验收口径，或触发 AUTONOMY.md §3 硬阻塞 → 写 docs/acceptance/FINAL-REPORT.md（完成度、验收逐项结论、测试统计、已知缺陷、外部依赖、运行与演示方法），然后停止，不要继续空转。

现在开始：source scripts/env.sh → 创建 goal → 执行 S0-01。
```

---

## 使用说明

- **先开总控**：让总控建 goal、拆任务、跑 S0-01/02/03；不要在第一天就开 5 个会话。全自动模式直接粘 E 段，不要粘 A。
- **按需开模块会话**：当某模块任务进入 `In Progress` 且与总控写域不冲突时开；开之前先让总控写交接单，模块会话粘 B 启动词并把 `{{模块}}` 换成实际名称。
- **不要让两个会话同时写同一目录**：会话之间不共享锁，冲突只能靠写域约定避免。
- **定时任务**：如果要在 DSH 里加每日站会，需要你明确说"允许创建定时任务"；否则我不会主动建。
- **会话结束必须收尾**：粘 D 启动词，否则下一个会话只能从 PROJECT_STATE 猜进度。
