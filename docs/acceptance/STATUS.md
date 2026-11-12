# 施工状态看板（每 Round 更新）

- 当前里程碑：**M2 MVP 进行中**（M1 已于 2026-11-12 达成）
- 当前 Round：10（2026-11-13，恢复施工）
- 最近提交：`feat(m0)` M2-M0-01 数据范围绑定表与解析（评审 PASS 后转正）
- 已完成任务：**S0 12/12；M2：M2-M0-01 完成**
- 进行中：M2-M0-02 手机号加密与 hash 写路径（下一步）
- 阻塞：—（Docker 不可用登记 EXT-08；GitHub 远端未建，CI/CODEOWNERS 未实跑）
- 下一步：M2-M0-02（手机号加密/hash）→ M2-M0-03（E2E/ratchet）→ M2-M1-01 申请接收
- 环境：JDK 21、Maven 3.10（.m2repo）、Node 26/pnpm 11（仓库内 store）、嵌入式 PG16（Zonky）、
  Playwright Chromium（.playwright）；Docker 不可用（EXT-08）

## M1 退出准则评估（S0 六条）

| # | 准则 | 结论 | 证据 |
|---|---|---|---|
| 1 | Compose 起全栈后登录→首页→受保护接口 | 环境受限（EXT-08） | compose-check PASS 32/0；本地嵌入式 PG + API + Vite 实测链路（S0-08/S0-12） |
| 2 | CI 10 检查全绿、覆盖率达标 | 部分通过（无远端 + ratchet） | ci.yml 10 job；ci-local --fast 9m13s 绿；覆盖率见 docs/testing（m0/网关 ratchet 到期 2026-12-18） |
| 3 | 契约 lint + 生成 + 兼容 | 通过 | spectral 0/0；oasdiff 4 域无破坏；drift PASS；TS/Java 同源 |
| 4 | 空库/升级库迁移 | 通过 | V1–V4+V100；空库/幂等/升级/append-only；生产无 demo 账号 |
| 5 | 采集 POC 断网补传 | 通过 | 39 测试；10min 660 帧不丢、断网恢复不重复、重启恢复 |
| 6 | README 30 分钟可运行 | 通过 | 实测约 7 分钟；dev-check PASS；链接 129/0 失效 |

## 门禁基线（2026-11-12）

- `./scripts/check.sh` → OK（约 7 分钟）：根 reactor 121 后端测试、JaCoCo 门禁、
  前端 32 用例覆盖率 88.3/80.21、Playwright 3/3、契约 lint/breaking/drift、规则检查。
- `scripts/ci-local.sh --fast` → PASS（9m13s）。
- 已知 ratchet：m0-impl 单模块 0.13/0.09、gateway 分支 0.60（到期 2026-12-18，ADR-0008）。

## 近期提交

| 提交 | 任务 | 说明 |
|---|---|---|
| 9740afd | S0-01 | 仓库初始化、CODEOWNERS、Issue/PR 模板、分支保护说明 |
| e505418 | S0-02 | monorepo 骨架（29 模块 reactor + 前端壳） |
| 18e8e16 | S0-03 | ESLint/Prettier/Checkstyle/SpotBugs/commitlint、check.sh |
| 2121fd7 | S0-10 | Compose 9 服务、Dockerfile、nginx、监控、compose-check |
| d545f9a/ff0512b | S0-05 | OpenAPI 3.1 四域契约 + spectral/oasdiff/TS 生成（评审修复） |
| d183563 | S0-06 | V1/V2 迁移、审计 append-only、演示种子隔离（评审修复） |
| 890bf18 | S0-09 | 采集网关 POC（39 测试） |
| b21a55a | S0-07 | 认证权限审计 + Java codegen（安全评审 3 blocking 修复） |
| 6e9dce4 | S0-11 | JaCoCo/Vitest/Playwright/Testcontainers 基座 |
| 7f72bf0 | S0-08 | 前端登录/布局/权限菜单/401 刷新 |
| bad4204 | S0-04 | 10 项 CI 流水线 + ci-local 本地等价 |
| （本提交） | S0-12 | README/guides/seed-demo/dev-check/链接校验 |

## 下一里程碑 M2（2027-01-29）任务候选

1. E-M1 手术申请接收与校验：patient/scheduling 契约实现 + HIS 适配器（模拟）+ 列表/详情页。
2. E-M2 排程工作台与冲突检测：术间/台次/优先级/资质规则、冲突算法 + 单测、甘特/看板。
3. E-M4 术前访视与评估：访视单、ASA/气道、RCRI/Apfel/STOP-BANG、麻醉计划。
4. M1 前必须清理：覆盖率 ratchet、devDeps 漏洞例外、data_scope 绑定表、phone 写路径、
   E2E-01/04 登录与越权用例、springdoc 全量 drift。
