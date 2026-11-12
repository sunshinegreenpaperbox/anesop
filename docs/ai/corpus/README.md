# ANESOP 方案书 · AI 语料包（v1）

把 109 页、约 3.8 万字的《手术麻醉信息系统 · 施工前方案书》转成 AI 代理可直接消费的四层语料。
**docx/PDF 仍是人读的权威源，本目录是机器读的派生层**：文档一改，重跑脚本即可刷新，不要手改生成文件。

- 生成脚本：[scripts/build_corpus.py](../../scripts/build_corpus.py)
- 生成时间与源文件指纹：见 [manifest.json](manifest.json)
- 语料地图与 token 账单：[INDEX.md](INDEX.md)

## 一、包里有什么

```text
corpus/
├── CONTEXT_CORE.md        3.0k tokens  常驻：项目一页纸（范围/模块/栈/铁律/红线/时间）
├── AGENTS.fragment.md     3.4k tokens  常驻：可并入仓库 AGENTS.md 的编码规则
├── facts.yaml             3.7k tokens  常驻：假设、团队、约束、红线、术语
├── full.md                 97.1k tokens  全量单文件（仅大上下文模型一次性审阅用）
├── chunks.jsonl           158 块       检索块：id/章节/路径/类型/字符/token/正文
├── chapters/              9 个文件      按章拆分的干净 Markdown（2.9k–16.3k tokens）
├── structured/            11 个 YAML    模块/角色/集成/NFR/技术栈/里程碑/迭代/决策/待确认/交付物/附图
├── prompts/               4 个模板      任务卡、接口实现、PR 评审、方案问答
├── INDEX.md                            语料地图 + 各文件 token 数 + 按章分块统计
├── manifest.json                       生成信息（源文件 sha256、块数、token 数）
└── reports/token_report.md             块大小分布与统计口径
```

唯一事实源 `docs/plan/00_事实卡与写作规范.md` 不在包内（体积小、变动频繁），单独分发语料包时请一并附上。

原始文档：正文 + 表格约 **55,300 汉字、118,600 字符**；cl100k 分词约 **101.3k tokens**。
其中表格与 Markdown 结构（`|`、`---`、标题路径、数字/编号）占了不少 token，所以"字数"与"token 数"不是 1:1；若只喂结构化 YAML，同内容的 token 约再少 40%。

## 二、为什么不能整篇丢给 AI

1. **装不下或太贵**：101k tokens，64k 上下文模型直接溢出；128k 模型能装下，但每次对话成本与延迟都高，且大部分内容与当前任务无关。
2. **注意力稀释**：一次塞 10 万字，模型对关键约束（如"锁单后不可改""患者数据不出院"）的遵循度反而下降。
3. **难以更新**：文档每周都在改，整篇粘贴无法版本化；本目录用 `manifest.json` 的 sha256 锁定来源。
4. **无法引用**：出问题时无法回答"这条规则来自哪一节"，而 `chunks.jsonl` 与 `heading_path` 可以。

## 三、四层投喂法（推荐）

| 层 | 内容 | 体积 | 何时加载 |
|---|---|---|---|
| L0 常驻 | `CONTEXT_CORE.md` + `AGENTS.fragment.md`（可加 `facts.yaml`） | 6.3k–10.1k | 每个会话/每个编码任务开始时 |
| L1 结构化 | `structured/*.yaml` 中与本任务相关的 1–3 个 | 0.5k–1.8k/个 | 明确要查清单、编号、日期、决策时 |
| L2 章节 | `chapters/0X_*.md` 的 1–2 章 | 2.9k–16.3k | 要写代码/写文档，需要上下文细节时 |
| L3 检索 | `chunks.jsonl` 按需召回 top-k | 0.6k/块 | RAG 或"只问某一段"时 |

**加载顺序固定**：L0 → 任务卡 → L1（相关清单）→ L2（相关章节）→ L3（检索补充）。
L2 只加载与任务直接相关的章：写接口不要加载第 6 章，写排期不要加载第 3 章。

## 四、按任务场景的配方

| 场景 | 加载内容 | 约 tokens |
|---|---|---|
| 写一条任务卡/Issue | CORE + AGENTS + `structured/milestones.yaml` + `sprints.yaml` + 对应章节节选 | 8k |
| 后端接口/领域服务 | CORE + AGENTS + ch03 + `structured/stack.yaml` + `modules.yaml` + `decisions.yaml` 中 D-02/D-03 | 24k |
| 前端页面/记录单 | CORE + AGENTS + ch02（2.2–2.4 节）+ ch03（3.4/3.5） | 20k |
| 设备采集网关 | CORE + AGENTS + ch03 3.6 + ch02 2.5 + `integrations.yaml` | 15k |
| 数据库/契约变更 | CORE + AGENTS + ch03 3.4/3.5 + ch04 4.7 + `modules.yaml` | 16k |
| 代码评审/CI 配置 | AGENTS + ch04（4.5–4.7）+ ch05（5.6–5.7） | 16k |
| 方案答疑/决策讨论 | CORE + `structured/decisions.yaml` + `questions.yaml` + ch07 | 11k |
| 汇报/摘要生成 | CORE + ch06 + `structured/milestones.yaml`+`sprints.yaml` | 9k |

> 表格给的是"上限估计"；实际可把 L2 的章节先按节裁剪，再喂给模型。

## 五、三种接入方式

**1）编码代理（Claude Code / Codex / Cursor 等）**
把 `AGENTS.fragment.md` 并入仓库根 `AGENTS.md`，`CONTEXT_CORE.md` 放到 `docs/ai/context.md`，在任务开始时让它先读这两个文件，再按任务读章节与 YAML。不要一次把 `full.md` 塞进系统提示。

**2）RAG / 知识库**
`chunks.jsonl` 每行一条，字段：`id, chapter, chapter_no, section, heading_path, kind, chars, cjk, tokens, text`。入库时把 `chapter`、`section`、`kind` 作为元数据过滤字段；召回应保留 `heading_path`，回答时引用"第 3 章 3.6 设备数据采集"。

**3）一次性大上下文**
只有"请通读全文，找出前后矛盾/生成评审意见"这类任务才用 `full.md`（97k tokens）。用 200k 上下文模型，并把 `INDEX.md` 一起给它，便于定位。

## 六、提示词模板

`prompts/` 下四份可直接复制：

- `task-card.md`：把决策清单与里程碑转成可执行任务卡（含验收标准、涉及文件、非目标）
- `implement-endpoint.md`：契约优先地实现一个接口（先读契约与模块边界，再写代码与测试）
- `review-pr.md`：按质量红线与医疗安全清单做评审，输出 blocking/suggestion 分级
- `explain-decision.md`：围绕 D-xx 做方案对比与建议，强制引用章节与假设

模板里的 `{{...}}` 占位按场景替换；每个模板都要求"引用来源章节/文件"。

## 七、更新流程

```bash
# 1. 改 docx（docs/plan/）
# 2. 重新生成（约 3 秒）
PYTHONPATH=../.pylibs python3 scripts/build_corpus.py
# 3. 看统计
cat corpus/reports/token_report.md
```

- `chapters/`、`chunks.jsonl`、`structured/*.yaml`、`INDEX.md`、`manifest.json` 全自动，**不要手改**；
- `CONTEXT_CORE.md`、`AGENTS.fragment.md`、`facts.yaml` 是人工策展层：范围/技术栈/红线变化时必须人工复核；
- 每次重跑都会更新 `manifest.json` 里的源文件 sha256，便于判断语料是否过期。

## 八、使用注意

1. **语料不是事实源**：与 docx 冲突时以 docx 为准；`CONTEXT_CORE.md` 开头也写明了这一点。
2. **图片不进语料**：9 张图在 `assets/figs/`，`structured/figures.yaml` 给出图题与文件路径；用视觉模型时单独上传图片，不要指望文本模型"看懂"图。
3. **token 数只是参考**：本目录按 cl100k 统计，属保守上限；DeepSeek/Qwen 对中文更省，实际可能低 30%–50%，投喂前用目标模型 tokenizer 复核。
4. **脱敏**：语料本身不含患者数据；把真实日志、真实患者信息贴进对话前，按 `AGENTS.fragment.md` 的脱敏三问自查。
5. **决策会变**：D-01–D-22 闭环后，先改 docx/结构化 YAML，再重跑，不要只改语料。
