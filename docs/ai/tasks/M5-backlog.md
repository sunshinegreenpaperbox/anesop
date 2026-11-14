# M5 任务卡包（驾驶舱与联调，目标 2027-05-21）

> M4 已完成（2026-11-15，6/6）。M5 范围：E-M18 实时态势/风险/资源/KPI 看板与大屏；
> E-M19 集成联调（模拟器 + 契约测试）、性能优化、安全加固。
> 使用方式同前：总控按依赖派子代理；核心域（权限/审计/契约/迁移/安全）独立 review、
> blocking 清零；`./scripts/check.sh` 全绿才提交；一次最多 2 个且写域不重叠。

## 任务总览

| ID | 任务 | 写域 | 依赖 | 状态 |
|---|---|---|---|---|
| M5-M18-01 | 实时态势与 KPI 看板（术间/台次/告警/资源、SSE 或轮询） | dashboard.yaml（新域）、m5 模块、V26、前端 | M4 | Review |
| M5-M18-02 | 风险与资源大屏（风险分层、利用率、告警聚合、大屏布局） | m5 模块、前端 | M5-M18-01 | Blocked |
| M5-M19-01 | 集成联调模拟器与契约测试（HIS/药房/SPD/设备 + Pact/契约测试） | `services/simulators/**`（新建）、apps/api adapter、契约测试 | M4 | Ready |
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
- 执行结果（2026-11-15）：契约 `dashboard.yaml 0.1.0`（口径 dashboard-1.0.0，2 端点）、
  V26 data-only（`m5:dashboard:read` 授 SYS_ADMIN/SYS_OR_MANAGER/SYS_ANESTHESIOLOGIST/
  SYS_QC_MANAGER）、m5-api/impl（4 只读端口 + 装配层适配器）、`/m5/dashboard` 页面（10s 轮询 +
  暂停刷新 + 空/错误/权限态）、ADR-0023；刷新采用 **10s 轮询**（无 SSE：只读聚合 + 无新依赖）。
  待确认 Q-1–Q-4（准点实际开始来源、利用率口径、停用术间语义、SELF 范围/开放范围）见 ADR-0023。
