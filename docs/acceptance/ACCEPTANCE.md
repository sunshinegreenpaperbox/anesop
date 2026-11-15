# 验收清单（V1.0）

> 全自动施工结束时，按本清单逐项给出结论：通过 / 不通过 / 环境受限 / 外部依赖。不通过项必须附缺陷单与影响。
> 验收人：项目组 + 客户方（真实联调项由院方环境决定）。

## A. 功能验收（按模块）

| 模块 | 验收点 | 结论 | 证据（测试/页面/脚本） |
|---|---|---|---|
| M0 公共支撑 | 登录/SSO 占位、RBAC+数据权限、审计留痕、字典、消息、文件 | **部分通过**：登录/刷新/登出/RBAC/数据范围/审计/用户管理/手机号加密通过（Auth/DataScope/UserAdmin/审计集成测试、E2E-04）；字典 API/消息中心/文件管理未实现（遗留，见 FINAL-REPORT §6.2） | |
| M1 排程 | 申请接收、规则排程、冲突检测、停改台、急诊加台、排程分析 | **通过**：申请接收/规则排程/冲突/停改台/急诊/分析（契约 scheduling 0.6.0、V7–V9/V12；Scheduling*/Analytics 集成测试、E2E-01） | |
| M2 麻醉 | 术前访视/评分/计划/知情同意/三方核查；术中记录/采集/预警/抢救；复苏/随访/锁单修订/签名占位 | **通过（CA 占位 EXT-05）**：术前评分/同意/三方核查/记录/采集/预警/抢救/复苏/随访/锁单修订（anesthesia 0.5.0–0.10.0、V13/V15–V19；对应集成测试与 E2E-02/03） | |
| M3 人员 | 档案、分级授权、排班、培训、绩效基础数据 | **通过（R1–R5 映射待医务确认）**：档案/分级授权/排班/培训（staff 0.1.0、V21/V22；M4StaffApiIntegrationTest、E2E m4-staff） | |
| M4 耗材 | 主数据、出入库、UDI、高值追溯、库存预警、SPD 适配器 | **通过（库存预警未实现；SPD 模拟 EXT-03）**：主数据/出入库/UDI/高值追溯（material 0.1.0、V23；M4MaterialApiIntegrationTest、M4AcceptanceTest） | |
| M5 药品 | 麻精五专、基数、日结班结、消耗统计、药房适配器 | **通过（药房模拟 EXT-02）**：目录/麻精五专/基数/日结班结（drug 0.1.0、V20；M4Drug*/Narcotic 集成测试；T-4 已收口） | |
| M6 工作量 | 口径配置、多维统计、导出、数据锁定 | **通过（报表数据锁定未实现，遗留）**：口径/多维统计/导出（report 0.1.0、V24；M4ReportApiIntegrationTest） | |
| M7 质控 | 指标计算、不良事件 RCA、病历抽查、上报导出 | **通过（指标清单待医务确认）**：指标/RCA/病历抽查/上报导出（qc 0.1.0、V25；M4QcApiIntegrationTest、M4AcceptanceTest） | |
| M8 驾驶舱 | 实时态势、风险、资源、KPI、大屏 | **通过**：实时态势/风险/资源/KPI/大屏（dashboard 0.1.0/0.2.0、V26；M5Dashboard*/DashboardRisk 集成测试、E2E m5） | |

## B. 技术验收

| 项 | 标准 | 结论 |
|---|---|---|
| 构建 | `./scripts/mvn.sh -B verify`、`pnpm build` 全绿 | |
| 测试 | 覆盖率 整体 ≥70%/55%，核心域 ≥80%/70% | |
| 契约 | spectral 通过，oasdiff 无破坏性变更 | |
| 迁移 | 空库迁移、升级迁移双路径通过 | |
| E2E | 登录、排程冲突、采集断网补传、锁单修订、权限越权、麻精双人核对 | **部分通过**：E2E-01/02/03/04 + 登录/权限/管理域共 **45 条**通过（2026-11-15，`pnpm e2e`）；E2E-05 麻精药品双人核对的“双人登录→核对→签字”页面流程仍为遗留（记录单内麻精核对入口已由 `AnesthesiaRecordApiIntegrationTest` 与 `m2-record.spec.ts` 覆盖） | `apps/web/e2e/**`（21 个 spec）、`docs/testing/README.md` §6 |
| 性能 | 排程页 1000 条 <2s、报表 <10s（本地基准） | **通过（本机基线，2026-11-15）**：申请列表 5000 台次 page=1&size=100 中位 **52ms**；报表 workload 5000 台次/366 天 **180ms**；质控指标 **126ms**；时间轴 300 事件 **46ms**；锁单 **38ms**；看板 realtime **9ms**；登录 **123ms**；SQL 聚合 **68ms**（9 项上限 2000–5000ms，全部中位数 ≤ 上限的 4%）。并发 4 组、看板 20 并发轮询 **216ms**（<2s）。见 `docs/testing/README.md` §15 |
| 安全 | 依赖漏洞无 HIGH/CRITICAL；密钥不入库；审计可用 | **通过（本地可执行项）**：`pnpm audit --prod` 0 HIGH/CRITICAL；`security-check.sh` 21 项（密钥占位/默认与 prod fail-closed/日志无 PHI）；安全头与限流专项 12 例；扫描摘要见 `docs/security/README.md`。镜像 Trivy 与 Maven jar CVE 为 EXT-08 |
| 部署 | `docker compose config` 校验通过；一键起文档完整（Docker 不可用时标环境受限） | **静态通过（2026-11-15，EXT-08）**：`bash scripts/compose-check.sh` 35 PASS/0 FAIL/1 SKIP（Docker 运行时）；上线手册/回滚/检查清单/数据初始化/培训/应急预案齐备（见 §I）；`docker compose config` 与实跑待具备 Docker 的宿主机补 |
| 集成联调 | 模拟器启动/停止、HIS/药房/SPD 字段映射契约测试、错误/超时/重试/降级、冒烟脚本可重复运行 | 环境受限（EXT-01/02/03/04）：模拟器可联调，真实接口待接入 |

## C. 演示脚本（合成数据）

1. 初始化合成数据：`scripts/seed-demo.sh`；
2. 登录 → 排程工作台新建/调整台次，制造冲突并解决；
3. 术前访视 → 风险评分 → 知情同意 → 三方核查；
4. 术中：开始记录 → 模拟设备数据推送 → 触发预警 → 用药/事件 → 锁单 → 修订；
5. 复苏 → 转出 → 术后随访；
6. 质控指标与工作量报表 → 驾驶舱大屏；
7. 审计日志查询：任意关键操作可追溯；
8. **安全演示（M5-M19-02）**：连续错误登录触发 429 + `Retry-After`（等待窗口后恢复）；
   低权限账号访问管理员/驾驶舱接口 403 并在审计中可查 `ACCESS_DENIED`；
   浏览器开发者工具确认 CSP/HSTS/X-Content-Type-Options/X-Frame-Options 等响应头。

## D. 文档验收

- README（快速开始/演示脚本）、部署手册、运维手册、用户手册（R1–R12 分册）、数据字典、ADR 集、追溯矩阵、外部依赖清单、已知缺陷清单。

## E. 判定规则

- A/B 全部"通过"且 C 演示跑通 → 建议验收通过；
- 存在"环境受限/外部依赖"项 → 列为遗留项，不影响代码验收，但需在报告中写明解除条件；
- 任一 P0 缺陷（记录丢失、越权、泄露、签名失效）未修复 → 不通过。

## F. 外部依赖（EXT）联调状态（M5-M19-01）

> 依据：`docs/integration/README.md`（模拟器端点/配置/字段映射/失败模式/联调步骤）、
> `docs/testing/README.md` §13。全部为 SYNTHETIC 数据；真实外部系统仍不可用。
> 编号沿用代码适配器常量（EXT-01 HIS / EXT-02 药房 / EXT-03 SPD / EXT-04 设备）；
> `EXTERNAL-DEPS.md` 早期把 EXT-02/EXT-03 记为 EMR/LIS，两套编号待统一（遗留项）。

| 编号 | 外部系统 | 联调状态 | 证据 | 遗留（真实接入前） |
|---|---|---|---|---|
| EXT-01 | HIS 手术申请/患者主索引 | **模拟器可联调，真实接口待接入** | `services/simulators` HIS 模拟器；`HisSimulatorContractTest`（字段集合/19 字段映射/值域转换/201+409 幂等/回调/重试/超时降级）；`scripts/integration-smoke.sh` | 真实接口文档、HL7/MLLP 或 WebService、鉴权、时延实测 |
| EXT-02 | 药房与合理用药 | **模拟器可联调，真实接口待接入** | 药房模拟器（目录/库存/发药/在线状态）；`PharmacySimulatorContractTest`（目录→`POST /v1/drugs` 201、409 库存不足、500/超时降级、契约 required 字段）；冒烟脚本 | 药房厂商接口、处方审核/合理用药规则、库存回写与对账 |
| EXT-03 | SPD/HRP 耗材 | **模拟器可联调，真实接口待接入** | SPD 模拟器（目录/同步回执/状态）；`SpdSimulatorContractTest`（目录→`POST /v1/materials` 201、同 key 幂等回执、503/超时降级）；冒烟脚本 | SPD 接口规范、每日对账、结算口径 |
| EXT-04 | 麻醉机/监护仪 | **模拟器可联调，真实接口待接入** | 复用 `services/device-gateway` 的 TCP 协议 v1 + `SimulatedDevice`（`scripts/simulators.sh device`）；网关既有测试（解析/标准化/断网补传/幂等） | D-06 设备清单与协议、采集盒/mTLS、现场联调 |

判据：契约测试通过 = 字段映射与异常语义已锁定；**不等于**真实接口已验证。真实接口联调仍需院方
测试环境，按上表“遗留”逐项补齐；编号统一属文档遗留（不在 M5-M19-01 写域）。

## G. 安全加固与 OWASP 结论（M5-M19-02）

| 项 | 结论 | 证据 |
|---|---|---|
| HTTP 安全头 | 通过：nginx 443 下发 CSP/HSTS/XFO DENY/nosniff/Referrer/Permissions；80 不声明 HSTS；API 层同语义兜底（HSTS 仅 HTTPS） | `SecurityHeadersIntegrationTest`（3）、`NginxSecurityHeadersConfigTest`（2）、`security-check.sh` |
| CSP | 通过：`script-src 'self'`（无 inline/eval）、`connect-src 'self'`、`frame-ancestors 'none'`；`style-src` 保留 `unsafe-inline`（Element Plus 行内样式，见已知限制） | 同上 + `docs/security/README.md` §2 |
| 速率限制 | 通过：登录按 IP+用户名、刷新按 IP；429 + `Retry-After`；容量/窗口/桶上限可配置；窗口恢复 | `RateLimitIntegrationTest`（4）、`RateLimitRecoveryTest`（1） |
| 依赖/镜像扫描 | 通过（可执行项）：npm 生产依赖 0 HIGH/CRITICAL、许可证/白名单校验（1 条历史 WARN）；镜像清单 9 项待 CI Trivy（EXT-08） | `scripts/security-scan.sh`、`.cache/security/` |
| 权限矩阵 | 通过：M0–M5 端点×角色与迁移授权一致；补测 QC 驾驶舱正例、外科医师 403+审计、M0 用户管理仅管理员 | `docs/security/README.md` §5、`SecurityAuthorizationRegressionTest`（2） |
| 审计覆盖 | 通过：LOGIN/REFRESH/LOGOUT/PASSWORD_CHANGE/ACCESS_DENIED/AUTH_UNAUTHORIZED/业务动作成功与失败均留痕；无口令/令牌/PHI | `docs/security/README.md` §6 |
| OWASP Top 10 | 通过，遗留项已登记（多实例限流 Redis、CSP style-src、HSTS preload、Maven jar CVE 镜像扫描、`spring-tx` 白名单缺口） | `docs/security/README.md` §7–§8 |

> 结论：安全项“通过（本地可执行项）”；EXT-08（Docker/Trivy 镜像扫描与镜像内 jar CVE）
> 解除条件与责任见 `docs/security/README.md` §8，不影响代码验收。

## H. 性能与安全专项（M5-QA-01）

> 依据：`docs/testing/README.md` §15（含**发布准入矩阵**）、`docs/security/README.md` §7
> （OWASP Top 10 2021 逐项现状/证据/剩余风险/EXT）。全部 SYNTHETIC；Docker 不可用按 EXT-08。

| 项 | 结论 | 证据 |
|---|---|---|
| 关键端点性能基准 | 通过（本机基线）：9 项端点/SQL 聚合中位耗时 **9–180ms**，上限 2000–5000ms；报表 5000 台次/366 天 **180ms**、质控 **126ms**、SQL 聚合 **68ms** | `PerformanceBenchmarkTest`（9），证据行 `[M5-QA-01][perf]` 见 surefire `*-output.txt` |
| 并发回归 | 通过：8 并发登录全 200 且令牌互异；同一刷新令牌 8 并发**恰 1×200、7×401、0×500**；并发锁单**恰 1×200+1×409**；并发事件 seq {3,4} 不重不丢；4 并发报表手算一致（3 台次/210 分钟）且各留审计 | `ConcurrencyRegressionTest`（4） |
| 稳定性（10s 轮询 / 无 SSE） | 通过：20 并发看板请求全部 200、总耗时 **216–669ms（<2s，多次运行）**、聚合逐字段一致；看板 4 端点均 `application/json`，无 `text/event-stream`；前端固定 `REFRESH_INTERVAL_MS=10_000` | `DashboardPollingStabilityTest`（2）、`apps/web/src/modules/m5/api/dashboard.ts` |
| OWASP Top 10 2021 | 通过：A01–A10 逐项“现状/证据/剩余风险/EXT”，每项至少 1 个可失败用例/脚本；未发现未处置 HIGH/CRITICAL | `docs/security/README.md` §7；§14 安全专项 12 例；`security-check.sh` 21/21 |
| 发布准入矩阵 | 通过（本地可执行项）：功能/覆盖/性能/安全/专项五域逐项结论与阻断规则已落地；阻断项任一失败即不得发布 | `docs/testing/README.md` §15.4；`scripts/performance-smoke.sh` |
| 遗留 | EXT-05（密钥托管）、EXT-08（Docker/Trivy、镜像内 jar CVE）、EXT-01…04（真实外部接口）、E2E-05（依赖 M5 双人核对页面）；本机基线口径需 CI/压测环境复核 | `docs/security/README.md` §8、`docs/testing/README.md` §15.4 |

> 结论：性能与安全专项“通过（本地可执行项）”；上述遗留不构成代码验收阻断，
> 但真实接口/镜像扫描/容量复核必须在生产发布前按 EXT 逐项解除。

## I. 试点上线准备（M6 / E-M20）

> 依据：`docs/ai/tasks/M6-backlog.md`、`docs/deploy/`、`docs/training/`、`docs/runbook/`。
> 全部为文档/脚本与静态校验；**生产部署与真实数据迁移不在自动验收范围**（上线/数据闸门需人工签字）。

| 项 | 结论 | 证据 |
|---|---|---|
| 部署清单与手册 | 通过：两种形态、环境前置、配置项/secrets 来源、本地构建+tar 打包（无 Docker）、发布步骤、验证 | `docs/deploy/DEPLOYMENT.md`、`docs/deploy/README.md`、`deploy/compose/*` |
| 回滚 | 通过（文档+时间盒）：应用 ≤10 分钟、数据库 expand-contract/PITR、回滚后验证 | `docs/deploy/ROLLBACK.md` |
| 数据初始化 | 通过（干跑）：生产 `db/migration` 与演示 `db/seed/demo` 隔离；prod 闸门/基线核对；demo 委托 seed-demo | `scripts/init-data.sh`、`docs/deploy/DATA-INIT.md`；`--dry-run` PASS |
| 培训材料 | 通过（材料齐备）：管理员/麻醉医师/护士/质控/大屏 + 速查卡，含截图占位与常见错误 | `docs/training/README.md` 及 5 份角色材料、`quick-reference.md` |
| 应急预案 | 通过（材料齐备）：6 类场景六段式；故障速查；联系人/升级路径（占位） | `docs/runbook/EMERGENCY-PLAYBOOK.md`、`FAULT-PLAYBOOK.md`、`CONTACTS.md` |
| 上线检查清单 | 通过（可勾选）：A–M，关联 §A/§B/§I 与 STATUS；阻断项明确 | `docs/deploy/GO-LIVE-CHECKLIST.md` |
| 演练脚本 | 通过（脚本可用/干跑）：健康检查、备份恢复、断网补传；`--help`/`--dry-run` 正常 | `scripts/drill-health.sh`、`drill-backup-restore.sh`、`drill-offline-replay.sh`；`docs/deploy/drills/2026-11-15-local-static.md` |
| 实时演练 | 环境受限：应用回滚/PITR/补传/健康实时项未在本机执行 | 需预发/演练环境；按 `DRILL-RECORD-TEMPLATE.md` 记录签字 |
| Docker/镜像 | 环境受限（EXT-08）：镜像 build/scan、Compose 实跑未执行 | 解除条件见 `docs/deploy/README.md` §9、`docs/security/README.md` §8 |
| nginx `/api` 前缀 | **待修复/实测**：nginx 保留 `/api` 前缀，后端映射为 `/v1`（无 context-path），静态分析有 404 风险 | 上线前二选一：`proxy_pass http://anesop_api/;` 或后端 `context-path=/api`；`nginx -t` + 真实登录验证 |

> 本地门禁：`./scripts/mvn.sh -B -f apps/api/pom.xml verify` **BUILD SUCCESS（1172 用例，0 failure/0 error，2026-11-15）**；
> `bash scripts/compose-check.sh` 35 PASS；`init-data.sh --dry-run` 与三演练脚本 dry-run/`--help` 通过。
> 结论：M6“试点上线准备”文档/脚本**通过（本地可执行项）**；实时演练、镜像扫描、nginx 路径
> 实测为上线前阻断项，未关闭不得发布。上线与数据闸门仍须 `release.yml` + 院方书面签字。
