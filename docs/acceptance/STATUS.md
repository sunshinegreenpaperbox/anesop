# 施工状态看板（每 Round 更新）

- 当前里程碑：**M1–M6 达成**；M7 验收完成（代码/文档验收通过，待人工签字）
- 当前 Round：38（2026-11-15，M7 验收）
- 最近提交：`docs(acceptance)` M7 验收与 FINAL-REPORT
- 已完成任务：**S0 12/12；M2 9/9；M3 7/7；M4 6/6；M5 5/5；M6 6/6；M7 验收完成**
- 进行中：**自动施工结束**；生产上线/真实联调/人工验收（上线/数据/决策闸门）
- 阻塞：上线前阻断——nginx `/api` 路径、实时演练、EXT-08 镜像扫描；GitHub 远端未建，CI/CODEOWNERS 未实跑
- 下一步：按 `docs/acceptance/FINAL-REPORT.md` §7 执行人工验收与 EXT 解除；遗留见 §6.2
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
- 覆盖率（2026-11-13）：m0-impl LINE 96.34%/BRANCH 87.52%、gateway 91.68%/77.39%，ratchet 已清零，核心域 80/70 门禁真实生效；devDeps 漏洞例外清零（vitest 4.1.12 + glob/minimatch override）。

## M3-QA-01 临床记录专项（2026-11-14）

| 准入项 | 证据 |
|---|---|
| 时间轴一致性 | `ClinicalRecordAcceptanceTest#timelineIngestReplayAndConcurrentMutationsStayConsistent`（seq 1..3、occurredAt 非降序、UPDATE/DELETE 55000） |
| 断网补传幂等 | 同 batchKey 重放 `acceptedRows=0/duplicateBatch=true`、行数 2 不变、`quality_flags=hr:RANGE` 保留；新 batchKey 补至 3；`DeviceAcquisitionApiIntegrationTest` 双层判重 |
| 并发修改 | 并发事件 seq {2,3} 均落库；同 version 并发 PUT 恰 200/409，version +1；并发修订 revisionNo {1,2} |
| 签名锁定 | 锁后事件/用药/出入量/PUT 409、数据库直写 55000；lock hash 64hex 稳定；修订 2 `previousSignatureHash` = 修订 1 hash |
| E2E-02 | `m2-device-ingest.spec.ts` 2 条：重放不重复 + 记录页 2→3 条、无权限 403；`pnpm e2e` 34/34 |
| 文档 | `docs/testing/README.md` §9 发布准入矩阵；`TRACEABILITY.md` 4 行 |
| 门禁实跑 | `mvn -f apps/api/pom.xml clean verify` BUILD SUCCESS（803 用例，8m04s）；前端 111 单测 + build + lint + `pnpm e2e` 34/34；`./scripts/check.sh` OK（11m09s，含契约/规则） |

## M4-QA-01 管理域专项（2026-11-15）

| 准入项 | 证据 |
|---|---|
| 权限矩阵 | `M4AcceptanceTest#roleEndpointMatrixCoversPositiveAndNegativeCases`：SYS_ADMIN/SYS_PHARMACIST/SYS_AUTH_MANAGER/SYS_OR_MANAGER/SYS_QC_MANAGER/SYS_ANESTHESIOLOGIST/匿名 × 药品写、五专、人员写/授权、耗材 UDI、报表读/导出、质控读/导出/复核；匿名 401、缺权限 403、正例 200/201 |
| 授权治理 | `#authorizationRequiresDualApprovalAndForbidsSelfGrant`：缺第二审批人/与第一审批人相同 400、两名不同审批人 200、自授（管理员与授权管理员）403、伪造审批人 400 |
| 数据范围 | `#dataScopeEnforcesDeptBoundaryRoomFailClosedAndSelfReadOnly`：DEPT 越界科室 403；ROOM 在人员/耗材 fail-closed 403、报表按术间收窄；SELF 仅本人档案（他人 404、写 403）、报表仅本人 1 台次/125 分钟 |
| 导出脱敏/防注入 | `#csvExportsAreBomMaskedInjectionSafePhiFreeAndAudited`：报表 workload/multi + 质控 indicator/adverse/review 五类 CSV 的 BOM、列头、无患者标识、无自由文本、审计 dataRows（9/6/7/1/2）；报表覆盖 `= + - @` 四种前缀，质控覆盖 `=`/`@` |
| 五专对账 | `#narcoticLedgerReconcilesAndRejectsUnbalancedClose`：基数 10 + 交接 4 − 专册 3 − 退回 0 = 期末 11，API 台账独立求和后与日结一致；不平 409 给期望/实盘、重复 409、双签同一人 400、失败审计 result=0 |
| UDI 追溯 | `#udiTraceChainCoversInboundUseLookupAndGuards`：入库 IN_STOCK → 绑定记录/台次 USED → 按 UDI 反查耗材/记录/台次；重复使用 409、过期登记 422、退回批次不一致 422、UDI-DI 不一致 422；`UDI_USE` 审计无患者标识 |
| 手算对照 | `#workloadMatchesHandCalculatedTotalsAndWeights`（6 台次/215 分钟/加权 6.5/unrecorded 4；麻醉科 2/125/2.5、手术室 4/90/4.0）；`#qcIndicatorRatesMatchHandCalculation`（50/50/25/50/250/50/66.67） |
| E2E | `m4-narcotic-reconcile.spec.ts`（日结 409 差异 → 修正平账）、`m4-qc-export.spec.ts`（质控指标按当前区间导出 CSV）；`pnpm e2e` **42/42** |
| 文档 | `docs/testing/README.md` §11 管理域专项发布准入矩阵；`TRACEABILITY.md` 2 行；本看板 |
| 门禁实跑 | 后端 `mvn -f apps/api/pom.xml clean verify` **BUILD SUCCESS（1100 用例，13:03；api-bootstrap 248，含专项 8）**；前端 167/167 单测 + build + lint（0 error）+ `pnpm e2e` 42/42（1.2m）。`./scripts/check.sh` 未重复跑（会二次触发全量 verify），上述单项已分别实跑。 |
| 遗留 | `drug.yaml`/`material.yaml` 无 CSV 导出端点（契约未定义，按 H-04 不臆造），药品/耗材导出待补契约后加测 |

## M5-QA-01 性能与安全专项（2026-11-15）

| 准入项 | 证据 |
|---|---|
| 性能基准（9 项） | `PerformanceBenchmarkTest`：登录 123ms、申请列表 5000 台次/100 条 52ms、排程冲突 15ms、时间轴 300 事件 46ms、锁单 38ms、报表 workload 5000 台次/366 天 180ms、质控指标 126ms、看板 realtime 9ms、SQL 聚合 68ms（上限 2000–5000ms，全部中位 ≤ 上限 4%）；报表同时断言 5000 台次/450000 分钟手算值 |
| 并发回归（4 组） | `ConcurrencyRegressionTest`：8 并发登录全 200 且 8 个令牌互异；同一刷新令牌 8 并发 → 恰 1×200、7×401、0×500；并发锁单 → 恰 1×200+1×409、库内 LOCKED/摘要 64hex；并发事件 seq {3,4} 不重不丢、occurredAt 非降序；4 并发报表 3 台次/210 分钟完全一致且各留 `REPORT_QUERY` 审计 |
| 稳定性 | `DashboardPollingStabilityTest`：20 并发 `GET /v1/dashboard/realtime` 全 200、**216–669ms**（<2s，多次运行）、聚合逐字段一致（专用 DEPT：1 术间/2 台次/1 设备）；看板 4 端点均 JSON、无 `text/event-stream`（确认 10s 轮询、未用 SSE） |
| OWASP Top 10 2021 | `docs/security/README.md` §7：A01–A10 逐项现状/证据/剩余风险/EXT，引用既有安全测试（安全头 3、限流 4+1、权限矩阵补测 2、M4 验收 8、临床专项 2 等） |
| 发布准入矩阵 | `docs/testing/README.md` §15.4：功能/覆盖/性能/安全/专项五域 + 阻断规则 + M5-QA-01 汇总 |
| 脚本 | 新增 `scripts/performance-smoke.sh`（三组用例 + 证据行校验，防“用例被过滤仍假绿”） |
| 文档 | `docs/testing/README.md` §15、`docs/security/README.md` §7/§10、ACCEPTANCE B 性能 + §H、本看板、M5-backlog 状态 |
| 门禁实跑 | 后端 `./scripts/mvn.sh -B -f apps/api/pom.xml clean verify` **BUILD SUCCESS（1172 用例，14m24s；api-bootstrap 296 含专项 15）**；前端 `pnpm test:unit` 179/179、`pnpm build`/`pnpm lint`/`vue-tsc`/`pnpm e2e` 45/45；`security-check.sh` 21/21、`contract-check.sh`/`check-rules.sh` 通过（未重复跑全量 `check.sh`，单项已分别实跑） |
| 遗留 | EXT-05 密钥托管、EXT-08 Docker/Trivy 与镜像内 jar CVE、EXT-01…04 真实外部接口、E2E-05（依赖 M5 双人核对页面）、本机基线需 CI/压测环境复核 |

## M6 试点上线准备（E-M20，2026-11-15）

| 任务 | 结论 | 证据 |
|---|---|---|
| M6-M20-01 部署清单与手册 | 完成：环境前置/配置项/本地构建+tar 打包（Docker 不可用）/发布步骤/验证；回滚 ≤10 分钟手册 | `docs/deploy/DEPLOYMENT.md`、`ROLLBACK.md`、`README.md` §0；compose env 补密钥 |
| M6-M20-02 数据初始化 | 完成：`init-data.sh` prod/demo 模式、`--dry-run`、基线核对、`demo_* = 0` 闸门 | `scripts/init-data.sh`、`docs/deploy/DATA-INIT.md`；`--dry-run` PASS |
| M6-M20-03 培训材料 | 完成：管理员/麻醉医师/护士/质控/大屏 5 份 + 速查卡 + 课程/考核 | `docs/training/`（7 文件） |
| M6-M20-04 应急预案 | 完成：DB/应用/采集/CA/网络分区/疑似泄露 6 类六段式；故障速查/联系人/升级 | `docs/runbook/`（4 文件） |
| M6-M20-05 检查清单与演练 | 完成：A–M Go/No-Go 清单；健康/备份恢复/断网补传脚本（`--help`/`--dry-run`） | `docs/deploy/GO-LIVE-CHECKLIST.md`、`scripts/drill-*.sh`、`docs/deploy/drills/` |
| M6-QA-01 自检 | 完成：compose-check 35 PASS；链接 97/0；后端 `verify` **BUILD SUCCESS（1172 用例，0 failure/0 error）** | `M6-backlog.md`；`./scripts/mvn.sh -B -f apps/api/pom.xml verify` exit 0 |
| 实时演练 | 环境受限：应用回滚/PITR/补传/健康实时项未执行 | 需预发/演练环境，按 `DRILL-RECORD-TEMPLATE.md` 签字 |
| 上线前阻断 | **nginx `/api/` 前缀与后端 `/v1` 不一致（静态分析 404 风险）**；EXT-08 镜像扫描；实时演练 | 修复/验证后关闭；见 `ACCEPTANCE.md` §I |

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
| （历史，未提交） | M4-QA-01 | `M4AcceptanceTest` 8 条 + E2E 2 条 + 管理域专项矩阵与文档 |
| （历史，未提交） | M5-QA-01 | `PerformanceBenchmarkTest` 9 + `ConcurrencyRegressionTest` 4 + `DashboardPollingStabilityTest` 2 + OWASP 逐项 + 发布准入矩阵 + `performance-smoke.sh` |
| （本轮，未提交） | M6-M20 | 部署/回滚/数据初始化/培训/应急预案/演练脚本/Go-Live 清单 |

## 下一里程碑 M7（验收）任务候选

1. 上线前收口：修复并实测 nginx `/api` 路径；预发环境跑应用回滚、PITR、断网补传、健康检查实时演练；
   具备 Docker 的宿主机补镜像 digest/Trivy 与 `docker compose config`。
2. 验收：按 `docs/acceptance/ACCEPTANCE.md` 逐项给出 A/B/I 结论，更新 `TRACEABILITY.md`；
3. 收口：生成 `FINAL-REPORT.md`、移交文档（部署/运维/用户/培训/演练），遗留项登记责任人与期限；
4. 闸门：生产发布/真实数据迁移仍须 `release.yml` + 院方人工签字，自动化不执行。
