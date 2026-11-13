# PROJECT_STATE — ANESOP 施工状态

> 跨会话唯一状态文件。**每个会话开始先读，结束必须更新。** 最后更新：2026-11-13（施工进行中：M1 达成；M2-M0-01/02/03、M2-M1-01 完成；下一步 M2-M2-01）
> 事实源：`docs/plan/手术麻醉信息系统_施工前方案书_V0.9.docx`（V0.9 讨论稿，未冻结）

## 1. 当前阶段

| 项 | 状态 |
|---|---|
| 方案书 | **V1.0 基线已冻结（2026-11-12）**，见 `docs/plan/BASELINE.md` |
| 施工 | **进行中（2026-11-13）**；M1 达成、S0 全完成；M2 已完成 M0-01/02/03、M1-01、M2-01/02、M4-01；下一步 M2-M5-01 |
| 目标里程碑 | ~~M1 工程骨架就绪：2026-12-18~~ **已于 2026-11-12 达成**；M2 MVP：2027-01-29；验收：2027-07-02 |
| 当前焦点 | M2：M2-M5-01 知情同意/三方核查 → M2-M6-01 排程分析 → M2 收口（tag m2） |
| 自动化规则 | `AUTONOMY.md`：不提问、默认决策、阻塞挂号、最终一次性验收 |
| 环境 | JDK 21（`.toolchain/jdk21`）、Maven 本地仓库 `.m2repo`、Node 26/pnpm 11；**Docker 不可用**（EXT-08） |

## 2. 已闭环决策（详见 `docs/decisions.md`）

- **方案基线 V1.0 冻结（2026-11-12）**：范围、架构、技术栈、集成、验收口径全部按方案书第 7 章建议执行，见 `docs/plan/BASELINE.md`。
- **D-02 已决**：Java 21 LTS + Spring Boot 3.4 + MyBatis-Plus + Flyway + PostgreSQL 16；前端 Vue 3.5 + TS + Element Plus；模块化单体 + 契约优先 + 独立采集网关。
- 其余 D-01/03/04/05/06/15 等统一"冻结执行"，不再逐项等待确认；变更走 `docs/decisions.md` 推翻流程。

## 3. 冻结执行的 P0（按 BASELINE 默认值，不再提问）

| 编号 | 主题 | 最晚 | 当前处理 |
|---|---|---|---|
| D-01 | 试点医院与建设边界 | 2026-11-20 | 按 A-01 假设推进，冻结前需书面确认 |
| D-03 | 架构形态与拆分时机 | 2026-11-27 | 方案建议模块化单体；按建议执行，记录 ADR |
| D-04 | 数据库与信创要求 | 2026-11-20 | 按 PostgreSQL 16 开发；SQL 保持可移植 |
| D-05 | 电子签名与无纸化 | 2026-11-27 | 一期 CA 签名 + 纸电并行，待医务处确认 |
| D-06 | 设备采集范围与责任 | 2026-11-27 | 先覆盖 6 类设备；S0 做 1 台 POC |
| D-15 | AI 工具与数据合规 | 2026-11-27 | 默认：患者数据不出院，仅企业版/私有化 |

## 4. S0 待办（详细任务卡见 `docs/ai/tasks/S0-backlog.md`）

- [x] S0-01 仓库初始化与协作资产（2026-11-12，首个提交）
- [x] S0-02 monorepo 骨架（2026-11-12，29 模块 reactor + 前端壳 + ADR-0001）
- [x] S0-03 静态检查与提交规范（2026-11-12，ESLint/Prettier/Checkstyle/SpotBugs/commitlint + check.sh + ADR-0002）
- [x] S0-04 CI 流水线（2026-11-12；10 job + ci-local 本地等价全绿；无远端未实跑）
- [x] S0-05 契约基线（OpenAPI 3.1）（2026-11-12；Java 类型生成延至 S0-07 补齐，见 ADR-0003）
- [x] S0-06 数据库迁移基线（2026-11-12；8 表 + 审计 append-only + 演示种子隔离；评审 B-1 已修）
- [x] S0-07 认证与权限骨架（2026-11-12；78 测试；JWT/RBAC/审计/生产守卫；安全评审 3 blocking 已修）
- [x] S0-08 前端壳与设计令牌（2026-11-12；登录/布局/权限菜单/401 刷新；真实联调通过）
- [x] S0-09 采集网关 POC（2026-11-12；39 测试，10 分钟不丢数/断网补传不重复/重启恢复）
- [x] S0-10 部署环境（Compose）（2026-11-12，静态校验 PASS 32/0；Docker 不可用 EXT-08，digest 待补）
- [x] S0-11 测试基座（2026-11-12；JaCoCo 门禁 + Vitest + Playwright + Testcontainers 基座；m0/网关 ratchet 到期 2026-12-18）
- [x] S0-12 开发文档与一键起（2026-11-12；README/guides/seed-demo/dev-check/链接 129/0 失效）

**S0 退出准则（M1）评估（2026-11-12）**：6 条中 4 条通过、1 条部分通过（CI 无远端 + 覆盖率 ratchet）、1 条环境受限（Docker EXT-08，已用嵌入式 PG 等价验证）。详见 `docs/acceptance/STATUS.md`。

**M1 关键产出**：OpenAPI 3.1 四域契约（23 path/28 op）+ spectral/oasdiff/TS/Java DTO；
V1–V4 迁移（M0 平台、认证令牌、采集明细）+ 演示种子隔离；JWT+RBAC+审计+生产守卫；
采集网关 POC（断网补传幂等）；前端登录/布局/权限菜单/401 刷新；JaCoCo/Vitest/Playwright
测试基座；10 项 CI + 本地等价脚本；README/guides/一键起。ADR-0001…0010。

## 5. 进行中 / 阻塞

| 项 | 状态 | 需要谁 |
|---|---|---|
| Docker/容器运行时（EXT-08） | 不可用，Compose/镜像/Testcontainers 静态校验 | 宿主机/信息科 |
| 施工恢复 | 2026-11-13 用户指令恢复；`check.sh` 全绿验证 WIP 可编译（530s） | — |
| M2-M0-01 | 已完成（review PASS、122 测试、contract-check 通过） | — |
| M2-M0-02 | 已完成（review PASS、156 测试、contract-check 通过） | — |
| GitHub 远端与组织 | 未建，CI/CODEOWNERS/分支保护未实跑 | 项目经理 |
| ~~覆盖率 ratchet~~ | 已清理（m0 96/87、gateway 91/77，80/70 门禁生效） | — |
| ~~施工暂停（第二次）~~ | 2026-11-13 已恢复；验收/评审全部闭环 | — |
| ~~devDependencies 漏洞例外~~ | 已清理（vitest 4.1.12 + glob/minimatch override，audit 0 high） | — |
| 真实 HIS/EMR/设备/CA 联调（EXT-01/02/04/05） | 适配器 + 模拟实现，待院方环境 | 信息科牵头 |
| 多院区 site_id、审计分区、phone 写路径 | M1/M2 评估或实施 | 架构师 |

## 6. 下一步（暂停恢复点）

0. ~~恢复点：验证 M2-M0-01 WIP~~ **已完成（2026-11-13）**：`check.sh` 全绿、独立 review PASS、
   122 测试、contract-check 通过，WIP 已转正式提交。
0. 下一步：M2-M5-01 知情同意与三方核查 → M2-M6-01 排程分析 → M2 里程碑收口（tag m2、
   交接单、STATUS）。任务卡见 `docs/ai/tasks/M2-backlog.md`。
2. M2 关键路径：E-M1 申请接收（patient/scheduling 契约实现 + HIS 模拟适配器 + 列表/详情页）
   → E-M2 排程工作台与冲突检测（规则引擎 + 冲突算法 + 甘特/看板 + E2E-01）
   → E-M4 术前访视与评估（访视单/ASA/气道/RCRI/Apfel/STOP-BANG/麻醉计划）。
3. 每个里程碑结束更新 `docs/acceptance/STATUS.md` 与交接单；M7 结束生成 `FINAL-REPORT.md`
   等人工验收。
4. 新会话启动：读本文件 + `docs/ai/handoffs/` 最新交接单 + `docs/ai/tasks/BACKLOG.md`。

## 7. 会话记录

| 日期 | 会话主题 | 产出 | 交接单 |
|---|---|---|---|
| 2026-11-12 | 方案书 V0.9 + AI 语料包 + 施工工作区 | docx/PDF、corpus、S0 任务卡、AUTONOMY/BASELINE | —（准备阶段） |
| 2026-11-12 | 全自动施工启动（S0 全量） | S0-01…S0-12 完成，M1 达成；`docs/ai/sessions/2026-11-12-全自动施工启动.md` | `docs/ai/handoffs/2026-11-12-M1.md` |
| 2026-11-12 | 施工暂停（用户断电） | M2-M0-01 WIP 快照 `ee15c8b`；状态与交接已写入 | `docs/ai/handoffs/2026-11-12-暂停.md` |
| 2026-11-13 | 恢复施工（M2-M0-01/02/03 完成）+ 第二次暂停 | M2-M1-01 WIP `f2c125a`；ADR-0011 | `docs/ai/handoffs/2026-11-13-暂停.md` |
| 2026-11-13 | 恢复并完成 M2-M1-01 | 手术申请接收（契约 0.3.0/V7/m1 模块/前端）；review BLOCKING CLEARED；flaky 修 | `docs/ai/handoffs/2026-11-13-M2-M1-01.md`（下一里程碑写） |

## 8. 更新规则

- 每轮任务完成后更新第 1、4、5、6 节；跨会话前写 `docs/ai/handoffs/`。
- 决策变化只改 `docs/decisions.md`，本文件引用结论，不复制理由。
- 阶段推进（S0→S1、里程碑达成）时更新第 1 节并记录日期。
