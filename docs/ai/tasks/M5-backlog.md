# M5 任务卡包（驾驶舱与联调，目标 2027-05-21）

> M4 已完成（2026-11-15，6/6）。M5 范围：E-M18 实时态势/风险/资源/KPI 看板与大屏；
> E-M19 集成联调（模拟器 + 契约测试）、性能优化、安全加固。
> 使用方式同前：总控按依赖派子代理；核心域（权限/审计/契约/迁移/安全）独立 review、
> blocking 清零；`./scripts/check.sh` 全绿才提交；一次最多 2 个且写域不重叠。

## 任务总览

| ID | 任务 | 写域 | 依赖 | 状态 |
|---|---|---|---|---|
| M5-M18-01 | 实时态势与 KPI 看板（术间/台次/告警/资源、SSE 或轮询） | dashboard.yaml（新域）、m5 模块、V26、前端 | M4 | Review |
| M5-M18-02 | 风险与资源大屏（风险分层、利用率、告警聚合、大屏布局） | m5 模块、前端 | M5-M18-01 | Review |
| M5-M19-01 | 集成联调模拟器与契约测试（HIS/药房/SPD/设备 + Pact/契约测试） | `services/simulators/**`（新建）、apps/api adapter、契约测试 | M4 | Review |
| M5-M19-02 | 安全加固（CSP/速率限制/依赖扫描/权限复核/审计覆盖） | apps/api 安全配置、deploy/nginx、CI 脚本 | M4 | Ready |
| M5-QA-01 | 性能与安全专项（压测基准、OWASP 清单、并发/幂等回归） | 测试/文档 | M5-M18-01…M5-M19-02 | Blocked |

## 任务卡要点

### M5-M18-01 实时态势与 KPI 看板
- 目标：实时态势（术间状态/台次进度/告警/设备在线）、KPI（今日台次/准点/取消/告警数/
  利用率），数据来源 M2/M3/M4 现有服务（跨模块只读端口）；刷新机制：SSE（推荐，Spring
  SseEmitter + 前端 EventSource）或 10s 轮询（无新依赖优先轮询，SSE 作为可选）；大屏
  分辨率/Web 页面；权限 `m5:dashboard:read`；契约 dashboard.yaml 0.1.0；V26 data-only 权限；
  DataScope（DEPT/ALL）；不泄露患者 PHI（只看台次状态/计数）。
- 验收：聚合正确（手算）、空数据、权限 403、刷新/断线重连、无 PHI、性能（看板 ≤2s）。

### M5-M18-02 风险与资源大屏
- 目标：风险分层（术前评分/告警/ASA 聚合）、资源（术间利用率/设备在线/人员排班）、
  告警趋势、KPI 趋势；复用 M18-01 数据层；大屏布局（暗色/轮播）；权限与审计。
- 验收：聚合正确、权限、断线、性能。

### M5-M19-01 集成联调模拟器与契约测试
- 目标：HIS/药房/SPD/设备模拟器（可编排响应/异常/超时，`services/simulators` 独立进程或
  测试内嵌）；适配器与模拟器字段映射契约测试（Pact 或 schema 快照，避免新依赖可先用
  JSON schema 断言 + 集成测试）；失败/重试/降级路径；E2E 联调脚本。
- 验收：模拟器启动、字段映射契约测试、异常/超时降级、重试幂等、文档。

### M5-M19-02 安全加固
- 目标：CSP/HSTS/安全头（nginx）、API 速率限制（内存桶，无新依赖）、依赖漏洞扫描
  （已有 audit/镜像扫描脚本）、权限矩阵复核（越权用例）、审计覆盖复核、敏感配置检查
  （prod fail-closed 已有）；OWASP Top 10 清单与结论。
- 验收：安全头断言、速率限制 429、越权回归、审计覆盖、扫描结果登记。

### M5-QA-01 性能与安全专项
- 目标：关键端点基准（报表/看板/时间轴/锁单）、并发回归、SSE/轮询稳定性、OWASP 清单、
  发布准入矩阵更新；结论写入 docs/testing。

## 执行注意
- 迁移从 V26 起（V1–V25 已用）；新域契约需接入 gen-contracts/contract-check/api-contracts。
- 前端可选 ECharts（allowlist 内）但需审批；优先原生/Element Plus 降低风险。
- 临床/运营口径未确认标【待确认】并记 ADR。
- 只用 SYNTHETIC；导出/看板不得含 PHI。
- 执行结果（2026-11-15，M5-M18-02）：契约 `dashboard.yaml 0.2.0`（additive，口径
  dashboard-1.1.0，新增 `/v1/dashboard/risk`、`/v1/dashboard/resources`、
  `/v1/dashboard/trends?hours=24`；0.1.0 的 2 端点未改）、m5-api 新增 2 只读端口
  （`DashboardRiskSource`/`DashboardStaffSource`）+ `countAlertsByHour`、装配层两个新适配器、
  m5-impl 风险/资源/趋势聚合与确定性缺口规则（阈值 `anesop.m5.dashboard.overdue-grace-minutes`
  默认 0）、`/m5/dashboard/risk` 暗色大屏页面（10s 轮询 + 暂停/继续/立即刷新 + 空/错误/权限态）；
  无新迁移（V26 的 `/api/v1/dashboard/*` 已覆盖）、无新依赖；审计 `DASHBOARD_VIEW`
  type=risk/resources/trends（采样）。证据：m5-impl 24 例单测（含手算风险分层/资源/趋势桶、
  超时宽限与并集去重、窗口 30–1440 分钟边界、空数据、403/DataScope、无 PHI）+
  `M5DashboardRiskResourceApiIntegrationTest`（嵌入式 PG16，手算 ASA/风险等级/人数/利用率、
  hours 400、三端点 403、审计无 PHI）+ Vitest 6 例 + E2E 1 条。待确认 Q-5/Q-6
  （超时宽限与是否分列、备班是否计入当班、人员 SELF 按科室、缺口阈值）见 ADR-0023。
- 执行结果（2026-11-15，M5-M18-01）：契约 `dashboard.yaml 0.1.0`（口径 dashboard-1.0.0，2 端点）、
  V26 data-only（`m5:dashboard:read` 授 SYS_ADMIN/SYS_OR_MANAGER/SYS_ANESTHESIOLOGIST/
  SYS_QC_MANAGER）、m5-api/impl（4 只读端口 + 装配层适配器）、`/m5/dashboard` 页面（10s 轮询 +
  暂停刷新 + 空/错误/权限态）、ADR-0023；刷新采用 **10s 轮询**（无 SSE：只读聚合 + 无新依赖）。
  待确认 Q-1–Q-4（准点实际开始来源、利用率口径、停用术间语义、SELF 范围/开放范围）见 ADR-0023。
- 执行结果（2026-11-15，M5-M19-01）：新增独立模块 `services/simulators`（JDK
  `HttpServer`/`HttpClient`，**无新增依赖**，全部 SYNTHETIC，只绑定 127.0.0.1）：HIS
  （患者主索引/申请查询/创建与变更回调）、药房（目录/库存/发药/在线状态）、SPD（目录/同步回执/
  在线状态）；JSON 配置端口、延迟/超时、前 N 次错误、错误码与失败模式（NONE/HTTP_ERROR/
  MALFORMED_JSON），配置写错 fail-closed；`scripts/simulators.sh`（start/stop/status/restart/
  selftest/logs/device，JSON 输出），设备复用 `device-gateway` 的 `SimulatedDevice`
  （`device` 子命令，不重复实现协议）；`scripts/integration-smoke.sh`：模拟器 + 独立 PG16
  （Zonky 嵌入式，EXT-08）+ `test` profile 真实 API → health/登录/EXT-02·EXT-03 状态/模拟器目录
  创建药品与耗材/HIS 申请导入/申请列表，trap 清理，可重复，成功输出 `[integration-smoke] OK`。
  契约测试 4 类（真实启动模拟器 HttpServer + 嵌入式 PG16 + 真实 API）：HIS 字段集合 + 19 字段
  映射 + 值域（F/M/U、择期/限期/急诊）+ 主索引一致 + 创建 201/重复 409 + 创建/变更回调 + 4xx
  不重试 + 前 2 次 503 后成功 + 超时降级；药房目录→`POST /v1/drugs` 201/重复 409、发药 409
  库存不足、500/超时降级、适配器状态映射满足 `drug.yaml` required；SPD 目录→
  `POST /v1/materials` 201/重复 409、同 `idempotencyKey` 幂等回执、503/超时降级、回执/状态映射
  满足 `material.yaml` required；`WireMapperContractTest` 锁定缺失/非法外部字段 fail-closed。
  `apps/api/api-bootstrap/pom.xml` 仅新增 `anesop-simulators` **test** 作用域依赖（不进入运行时
  交付物；单模块构建 `apps/api` 前需先 install 该模块，根 reactor 自动处理）。
  文档：`docs/integration/README.md`（端点/配置/字段映射表/失败模式/冒烟/真实接入差异/EXT 编号
  遗留）、`docs/acceptance/ACCEPTANCE.md` §F（EXT-01/02/03/04 = 模拟器可联调，真实接口待接入）、
  `docs/testing/README.md` §13。证据：simulators 36 用例、覆盖率 97.28%/83.46%；契约 16 用例；
  `apps/api` clean verify BUILD SUCCESS（1147 用例，13:57）、冒烟脚本 OK（命令见 docs/integration §2/§8）。
  遗留：真实接口（HL7/WebService、mTLS、对账）待院方环境；EXT 编号口径与
  `docs/acceptance/EXTERNAL-DEPS.md` 早期登记不一致，待统一（不在本任务写域）。
