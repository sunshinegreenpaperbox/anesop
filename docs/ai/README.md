# docs/ai — AI 协作资产使用说明

> 本目录是 AI 代理的**工作记忆**：任务卡、交接单、会话日志、方案书语料。
> 仓库是唯一共享记忆，会话之间不继承上下文；所有结论必须落文件。
> 规则正文见 [`AGENTS.md`](../../AGENTS.md)，跨会话状态见 [`PROJECT_STATE.md`](../../PROJECT_STATE.md)。

## 1. 目录地图

```text
docs/ai/
├── SESSION_STARTER.md        新会话启动词（总控/模块/站会/收尾；复制即用）
├── tasks/                    任务卡与待办
│   ├── BACKLOG.md            里程碑级待办
│   └── S0-backlog.md         S0 任务卡（目标/验收/写域/依赖）
├── handoffs/                 会话交接单（会话结束时必写）
│   └── TEMPLATE.md
├── sessions/                 会话日志（过程记录，与交接单互补）
│   └── TEMPLATE.md
├── corpus/                   方案书语料（**生成物，不手改**）
│   ├── CONTEXT_CORE.md       常驻上下文（项目一页纸）
│   ├── AGENTS.fragment.md    可并入 AGENTS.md 的编码规则片段
│   ├── facts.yaml            假设/团队/约束/红线/术语
│   ├── chapters/             按章拆分的 Markdown
│   ├── structured/           11 个结构化 YAML（模块/角色/集成/NFR/栈/里程碑…）
│   ├── chunks.jsonl          检索块（158 块，含章/节/路径/正文）
│   ├── INDEX.md              语料地图与 token 账单
│   └── manifest.json         源文件 sha256、生成时间、统计
└── README.md                 本文件
```

## 2. 新会话怎么开始

1. 复制 [`SESSION_STARTER.md`](SESSION_STARTER.md) 中对应角色的启动词（A 总控 / B 模块 /
   C 站会 / D 收尾），整段作为第一条消息；
2. 按启动词的顺序读文件（通常 5–8 项，不要一次把 `corpus/full.md` 塞进上下文）；
3. 先认领任务（任务卡或 GitHub Issue）再动手；一次只推进 1–3 个任务；
4. 结束时更新 `PROJECT_STATE.md`、写交接单，必要时补会话日志。

| 我要做什么 | 先读 |
|---|---|
| 接手/继续施工 | `PROJECT_STATE.md` → `AGENTS.md` → [`tasks/`](tasks/) 当前任务卡 |
| 了解项目范围与术语 | [`corpus/CONTEXT_CORE.md`](corpus/CONTEXT_CORE.md) → [`corpus/facts.yaml`](corpus/facts.yaml) |
| 写后端接口 | 任务卡 → `chapters/03_总体技术方案.md`（3.4/3.5/3.9）→ `structured/modules.yaml` |
| 写前端页面 | 任务卡 → `chapters/02_业务蓝图与功能规划.md`（2.4）→ 契约 → `packages/ui` |
| 改契约/迁移 | `AGENTS.md`「契约优先与数据变更」→ `docs/api/`、`docs/db/` → 相关 ADR |
| 查某条规则出处 | [`corpus/INDEX.md`](corpus/INDEX.md) → `chunks.jsonl` 按 `heading_path` 定位 |
| 交接给下一位 | [`handoffs/TEMPLATE.md`](handoffs/TEMPLATE.md) + `PROJECT_STATE.md` |

## 3. 任务卡

- 位置：`docs/ai/tasks/TASK-<issue>.md`（后续）或 `docs/ai/tasks/S0-backlog.md`（S0）。
- 必填字段：目标、背景（章节/契约）、验收标准、写域、非目标、测试要求、依赖、AI 声明。
- 状态：`Ready → In Progress → Review → QA → Done`；阻塞时标 `blocked`，写原因与需要谁拍板。
- **写域是硬约束**：只改任务卡列出的路径；越界改契约、改测试断言使其通过均属违规。
  同一路径同一时间只允许一个写入者。

## 4. 交接单与会话日志

| 类型 | 何时写 | 位置 | 内容 |
|---|---|---|---|
| 交接单 | 每次会话结束/换会话前**必须** | `handoffs/YYYY-MM-DD-<主题>.md` | 已完成、未完成、关键文件、决策、风险、下一会话第一条指令 |
| 会话日志 | 过程较长或需要复盘时 | `sessions/YYYY-MM-DD-<主题>.md` | 目标、参与代理、产出、验证命令与结果、用时、遗留 |
| 状态文件 | 每轮任务完成 | [`PROJECT_STATE.md`](../../PROJECT_STATE.md) | 阶段、任务勾选、阻塞、下一步 |

模板：[`handoffs/TEMPLATE.md`](handoffs/TEMPLATE.md)、[`sessions/TEMPLATE.md`](sessions/TEMPLATE.md)。
交接单要能让**没有上下文的下一会话**直接接续；不要写“见聊天记录”。

## 5. 语料刷新（生成物不手改）

`corpus/` 由脚本从方案书 docx 生成。**禁止手工编辑** `chapters/`、`structured/`、
`chunks.jsonl`、`INDEX.md`、`manifest.json` 等生成物；要改内容先改方案书，再重跑脚本。

```bash
# 方式 A：依赖已安装时（仓库根）
python3 scripts/build_corpus.py

# 方式 B：脚本 docstring 中的本地依赖目录约定
PYTHONPATH=./.pylibs python3 scripts/build_corpus.py

# 方式 C：隔离 venv（推荐；缓存放仓库内，不写 home）
python3 -m venv .cache/venv-corpus
.cache/venv-corpus/bin/pip install python-docx pyyaml tiktoken
.cache/venv-corpus/bin/python scripts/build_corpus.py
```

前置条件与注意：

- Python 3.10+，依赖 `python-docx`、`pyyaml`、`tiktoken`（cl100k_base 需要首次下载编码表，
  离线环境需预置缓存）；
- 源文件：`docs/plan/手术麻醉信息系统_施工前方案书_V0.9.docx`（路径写在脚本顶部）；
- 输出：`docs/ai/corpus/` 下 9 章 Markdown、11 个 YAML、`chunks.jsonl`、`INDEX.md`、
  `manifest.json`、`reports/token_report.md`；
- **刷新后必须 `git diff --stat docs/ai/corpus` 检查**：只应出现预期内容变化；生成物与源文件
  的 sha256 记录在 `manifest.json`，可作为“语料与方案书同版本”的证据；
- 语料是派生层，**方案书 docx/PDF 才是人读的权威源**；两者冲突以方案书为准；
- 当前施工环境未安装上述 Python 依赖（S0-12 实测 `ModuleNotFoundError: docx`），
  因此 S0-12 **没有**重跑语料；需要刷新时按方式 C 建 venv，或在有依赖的环境执行。
- 语料刷新属独立改动，不要与业务代码 PR 混在一起；提交信息用 `docs(ai): 刷新语料至 <日期>`。

## 6. 语料怎么投喂（四层法）

| 层 | 内容 | 体积 | 何时加载 |
|---|---|---|---|
| L0 常驻 | `CONTEXT_CORE.md` + `AGENTS.fragment.md`（可选 `facts.yaml`） | 6–10k tokens | 每个会话/编码任务开始 |
| L1 结构化 | `structured/*.yaml` 中与任务相关的 1–3 个 | 0.5–1.8k/个 | 查清单、编号、日期、决策 |
| L2 章节 | `chapters/0X_*.md` 的 1–2 章 | 2.9–16.3k/章 | 写代码/文档需细节时 |
| L3 检索 | `chunks.jsonl` 按需 top-k | ≈0.6k/块 | 只问某一段/RAG |

顺序固定 L0 → 任务卡 → L1 → L2 → L3；不要一次加载 `full.md`（约 97k tokens）。
各场景配方见 [`corpus/README.md`](corpus/README.md) 第四节。

## 7. 数据与合规红线（语料使用相关）

- 语料只含方案书文本，**不含患者数据**；不得把真实姓名、身份证、住院号、手机号、
  临床自由文本原文贴进任何外部模型或 Issue/PR。
- 报错与日志进模型前先删参数值、token、连接串口令。
- 测试夹具只用标记 `SYNTHETIC` 的合成数据；演示账号见 [`docs/db/seed.md`](../db/seed.md)。
- 生产网段禁止访问外部模型 API（D-15 / EXT-05 边界）。

## 8. 常见问题

- **语料与当前代码不一致**：语料只反映方案书，不反映代码现状；代码事实以仓库源码、
  契约、迁移与 `PROJECT_STATE.md` 为准。
- **找不到某条规定**：先在 `AGENTS.md` 找规则，再在 `corpus/INDEX.md` 找方案书章节；
  两者都没有时走 `docs/rfc/` 或 ADR，不要臆造。
- **任务卡与写域冲突**：停下来先改任务卡（经总控确认），不要先改代码再补卡。
