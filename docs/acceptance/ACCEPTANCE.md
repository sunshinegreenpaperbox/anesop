# 验收清单（V1.0）

> 全自动施工结束时，按本清单逐项给出结论：通过 / 不通过 / 环境受限 / 外部依赖。不通过项必须附缺陷单与影响。
> 验收人：项目组 + 客户方（真实联调项由院方环境决定）。

## A. 功能验收（按模块）

| 模块 | 验收点 | 结论 | 证据（测试/页面/脚本） |
|---|---|---|---|
| M0 公共支撑 | 登录/SSO 占位、RBAC+数据权限、审计留痕、字典、消息、文件 | 待验 | |
| M1 排程 | 申请接收、规则排程、冲突检测、停改台、急诊加台、排程分析 | 待验 | |
| M2 麻醉 | 术前访视/评分/计划/知情同意/三方核查；术中记录/采集/预警/抢救；复苏/随访/锁单修订/签名占位 | 待验 | |
| M3 人员 | 档案、分级授权、排班、培训、绩效基础数据 | 待验 | |
| M4 耗材 | 主数据、出入库、UDI、高值追溯、库存预警、SPD 适配器 | 待验 | |
| M5 药品 | 麻精五专、基数、日结班结、消耗统计、药房适配器 | 待验 | |
| M6 工作量 | 口径配置、多维统计、导出、数据锁定 | 待验 | |
| M7 质控 | 指标计算、不良事件 RCA、病历抽查、上报导出 | 待验 | |
| M8 驾驶舱 | 实时态势、风险、资源、KPI、大屏 | 待验 | |

## B. 技术验收

| 项 | 标准 | 结论 |
|---|---|---|
| 构建 | `./scripts/mvn.sh -B verify`、`pnpm build` 全绿 | |
| 测试 | 覆盖率 整体 ≥70%/55%，核心域 ≥80%/70% | |
| 契约 | spectral 通过，oasdiff 无破坏性变更 | |
| 迁移 | 空库迁移、升级迁移双路径通过 | |
| E2E | 登录、排程冲突、采集断网补传、锁单修订、权限越权、麻精双人核对 | |
| 性能 | 排程页 1000 条 <2s、报表 <10s（本地基准） | |
| 安全 | 依赖漏洞无 HIGH/CRITICAL；密钥不入库；审计可用 | **通过（本地可执行项）**：`pnpm audit --prod` 0 HIGH/CRITICAL；`security-check.sh` 21 项（密钥占位/默认与 prod fail-closed/日志无 PHI）；安全头与限流专项 12 例；扫描摘要见 `docs/security/README.md`。镜像 Trivy 与 Maven jar CVE 为 EXT-08 |
| 部署 | `docker compose config` 校验通过；一键起文档完整（Docker 不可用时标环境受限） | |
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
