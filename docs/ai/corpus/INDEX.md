# ANESOP 方案书 AI 语料索引

- 生成时间：2026-11-12 10:38
- 源文件：手术麻醉信息系统_施工前方案书_V0.9.docx（sha256:593f037d97ddfda4）
- 规模：9 个一级章节（含附录）、158 个检索块、118598 字符、约 101282 tokens（cl100k）
- 全量单文件 `full.md`：约 97138 tokens（仅大上下文模型全量投喂时使用）

## 1. 常驻层（每次对话都加载）

| 文件 | 用途 | tokens | 建议加载 |
|---|---|---|---|
| `CONTEXT_CORE.md` | 项目一页纸：范围、模块、栈、铁律、红线 | 2994 | 始终 |
| `AGENTS.fragment.md` | 编码代理规则片段（可并入仓库 AGENTS.md） | 3387 | 始终 |
| `facts.yaml` | 假设、团队、约束、术语 | 3750 | 始终或按需 |

## 2. 结构化事实层（按任务取，不整篇读）

| 文件 | 内容 | 条目 | tokens |
|---|---|---|---|
| `structured/roles.yaml` | 角色 | 12 | 728 |
| `structured/modules.yaml` | 模块清单 | 9 | 1015 |
| `structured/integrations.yaml` | 外部集成 | 14 | 694 |
| `structured/nfr.yaml` | 非功能指标 | 9 | 479 |
| `structured/stack.yaml` | 技术栈 | 20 | 838 |
| `structured/milestones.yaml` | 里程碑 | 8 | 707 |
| `structured/sprints.yaml` | 迭代 | 14 | 1586 |
| `structured/decisions.yaml` | 待决策 D-xx | 22 | 1780 |
| `structured/questions.yaml` | 待确认 Q-xx | 15 | 710 |
| `structured/deliverables.yaml` | 交付物 DL-xx | 21 | 1302 |
| `structured/figures.yaml` | figures | 9 | 682 |

## 3. 章节全文（按需加载）

| 文件 | 章节 | 字符 | tokens |
|---|---|---|---|
| `chapters/01_项目概述.md` | 第 1 章 项目概述 | 7066 | 7041 |
| `chapters/02_业务蓝图与功能规划.md` | 第 2 章 业务蓝图与功能规划 | 12250 | 12209 |
| `chapters/03_总体技术方案.md` | 第 3 章 总体技术方案 | 18569 | 16339 |
| `chapters/04_工程管理与协作机制.md` | 第 4 章 工程管理与协作机制 | 21775 | 15497 |
| `chapters/05_AIAgents施工约定.md` | 第 5 章 AI Agents 施工约定 | 19835 | 15371 |
| `chapters/06_施工计划与资源安排.md` | 第 6 章 施工计划与资源安排 | 15518 | 14403 |
| `chapters/07_待讨论与待决策清单.md` | 第 7 章 待讨论与待决策清单 | 8589 | 7720 |
| `chapters/08_交付物清单与验收标准.md` | 第 8 章 交付物清单与验收标准 | 6121 | 5587 |
| `chapters/09_附录.md` | 附录 | 4903 | 2936 |

## 4. 检索块（RAG 入库）

`chunks.jsonl` 共 158 条，字段：id / chapter / section / heading_path / kind / chars / cjk / tokens / text。

按章分布：

| 章节 | 块数 | 字符 | tokens |
|---|---|---|---|
| 第 1 章 项目概述 | 7 | 7093 | 7094 |
| 第 2 章 业务蓝图与功能规划 | 19 | 12740 | 12758 |
| 第 3 章 总体技术方案 | 25 | 19152 | 17026 |
| 第 4 章 工程管理与协作机制 | 33 | 22828 | 16546 |
| 第 5 章 AI Agents 施工约定 | 28 | 20719 | 16090 |
| 第 6 章 施工计划与资源安排 | 20 | 16007 | 14953 |
| 第 7 章 待讨论与待决策清单 | 12 | 8848 | 8055 |
| 第 8 章 交付物清单与验收标准 | 9 | 6321 | 5819 |
| 附录 | 5 | 4890 | 2941 |

> 每块 ≤ 1800 字符，超长节按三级标题或段落切分；块首包含 heading_path，可直接用于引用。
