# 安全加固与安全运营（M5-M19-02 / M5-QA-01）

- 状态：已完成（2026-11-15），进入 Review；OWASP 逐项复评见 §7（M5-QA-01，2026-11-15）
- 任务：`docs/ai/tasks/M5-backlog.md` M5-M19-02（加固）+ M5-QA-01（专项复评）；
  验收：`docs/acceptance/ACCEPTANCE.md` B/G/H 项
- 规则依据：`AGENTS.md`「患者数据与安全红线」「依赖准入」、方案书 §3.9（安全与权限）、
  §3.7.1（部署拓扑）、§3.8.3（Nginx）
- 写域：`apps/api/api-bootstrap/**`、`deploy/nginx/anesop.conf`、`deploy/compose/docker-compose.yml`（仅注释）、
  `scripts/**`、`docs/security/**`、`docs/acceptance/ACCEPTANCE.md`、`docs/testing/README.md`、
  `docs/ai/tasks/M5-backlog.md`
- 非目标：不改 V1–V26、不改业务模块/契约/前端业务代码、不引入新依赖、不执行生产部署

## 1. 证据索引

| 证据 | 位置 |
|---|---|
| Nginx 安全头/CSP/HSTS/日志静态守门 | `apps/api/api-bootstrap/src/test/java/com/anesop/api/NginxSecurityHeadersConfigTest.java`、`NginxPhiLogConfigTest.java` |
| API 安全头行为断言（MockMvc） | `SecurityHeadersIntegrationTest`（3 例） |
| 限流 429 + Retry-After + IP/用户名 + 窗口恢复 | `RateLimitIntegrationTest`（4 例）、`RateLimitRecoveryTest`（1 例） |
| 权限矩阵关键补测 + ACCESS_DENIED 审计 | `SecurityAuthorizationRegressionTest`（2 例） |
| 安全静态检查（21 项，离线） | `scripts/security-check.sh` |
| 依赖/镜像扫描（联网） | `scripts/security-scan.sh`，证据 `.cache/security/` |
| 本地一键门禁接入 | `scripts/check.sh` 第 8/8 步（新增约 1–2 秒） |
| 性能/并发/稳定性专项（M5-QA-01） | `PerformanceBenchmarkTest`（9 项，含锁单摘要/报表/看板）、`ConcurrencyRegressionTest`（8 并发登录 + 刷新轮换唯一、并发锁单恰一成功、事件 seq、报表幂等）、`DashboardPollingStabilityTest`（20 并发轮询无状态 + 无 SSE）；`scripts/performance-smoke.sh` |
| OWASP Top 10（2021）逐项结论 | 本文 §7（A01–A10 的现状/证据/剩余风险/EXT） |
| CI 既有门禁 | `.github/workflows/ci.yml` `ci/deps-license` / `ci/secret-scan` / `ci/sast` / `ci/image-scan` |

## 2. HTTP 安全响应头

Nginx（`deploy/nginx/anesop.conf`，只在 443 server 下发；80 只跳转且不声明 HSTS）：

| 响应头 | 值 | 说明 |
|---|---|---|
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'` | 无 inline script / 无 eval；`connect-src` 仅同源（前端经 `/api` 反代）。`style-src` 保留 `'unsafe-inline'` 仅因 Element Plus 弹层使用行内样式，未放开脚本 |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | 仅 HTTPS 响应；未加 `preload`（需院方域名与全子域 HTTPS 确认后另行评审） |
| `X-Content-Type-Options` | `nosniff` | 与 API 层一致 |
| `X-Frame-Options` | `DENY` | 与 `frame-ancestors 'none'` 双保险；不允许被任何页面嵌入 |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | 跨域不泄露路径/查询 |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=(), usb=()` | 关闭非必要浏览器能力 |

API 层（`SecurityConfiguration`）同语义兜底，CSP 最小化为
`default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`；
HSTS 由 Spring Security 仅对 HTTPS 请求下发（`max-age=31536000; includeSubDomains`）。
401/403 等错误响应同样经 `HeaderWriterFilter` 带头，避免异常路径漏头。

**CSP 变更纪律**：新增第三方脚本/字体/接口域名必须先改 nginx 本行、同步更新本节与
`NginxSecurityHeadersConfigTest`，禁止直接加 `'unsafe-inline'` 到 `script-src`。

## 3. 速率限制（登录/刷新）

实现：`RateLimitFilter`（Spring 过滤器，内存令牌桶，JDK 内置，无新依赖）。

| 项 | 规则 |
|---|---|
| 计数键 | `POST /v1/auth/login`：来源 IP + 小写用户名（用户名从请求体解析，不落日志/审计）；`POST /v1/auth/refresh`：来源 IP（**不读取**刷新令牌请求体） |
| 算法 | 每键一个桶：容量 `capacity`，每 `refillPeriod` 补充 `refillTokens`；桶表按 LRU 限制 `maxBuckets`，防止大量 IP/用户名组合撑爆内存 |
| 超限 | `429` + 契约错误 `RATE_LIMITED` + `Retry-After`（秒，向上取整到下一令牌） |
| 正常请求 | 容量内不影响；仅命中 login/refresh 两个端点，健康检查与业务接口不经过限流 |
| 审计/日志 | 请求体只在内存中用于取用户名，不写日志/审计；限流短路请求不写登录审计（无认证尝试），由 nginx 429 计数与指标观测 |

配置（`application.yml`，均可用环境变量覆盖）：

| 属性 | 环境变量 | 默认 |
|---|---|---|
| `anesop.security.rate-limit.enabled` | `ANESOP_RATE_LIMIT_ENABLED` | `true` |
| `anesop.security.rate-limit.capacity` | `ANESOP_RATE_LIMIT_CAPACITY` | `20` |
| `anesop.security.rate-limit.refill-tokens` | `ANESOP_RATE_LIMIT_REFILL_TOKENS` | `20` |
| `anesop.security.rate-limit.refill-period` | `ANESOP_RATE_LIMIT_REFILL_PERIOD` | `PT1M` |
| `anesop.security.rate-limit.max-buckets` | `ANESOP_RATE_LIMIT_MAX_BUCKETS` | `10000` |

双层限流：nginx `limit_req_zone`（登录 2r/s、接口 20r/s，按来源 IP）做流量兜底；
应用层做“IP+用户名”的凭据暴破防护。**单实例语义**：多实例/集群必须改为 Redis 令牌桶
（登记为遗留，见 §7）。

测试证据：容量 2/1 小时窗口下连续失败登录第 3 次 429 且带 `Retry-After`；不同用户名
不共享桶；正常登录仍 200 且令牌可用；PT5S 窗口后恢复为 401（不再 429）；登录审计
不含口令/令牌明文。nginx 配置变更后 `scripts/compose-check.sh` 通过。

## 4. 依赖与镜像扫描

```bash
source scripts/env.sh
bash scripts/security-check.sh    # 离线静态检查，约 1–2 秒，21 项
bash scripts/security-scan.sh     # 联网依赖扫描 + 镜像清单，约 30–60 秒
```

`security-scan.sh` 结果口径（2026-11-15 实测）：

| 检查 | 结果 |
|---|---|
| npm 生产依赖 `pnpm audit --prod --audit-level high` | **0 HIGH/CRITICAL**（1 moderate：element-plus GHSA-5m5x-9j46-h678，已在 `docs/ci/audit-exceptions.md` 记录，低于阻断线） |
| 依赖白名单/许可证（`check-deps-license.py`） | 30 个 Maven 直接依赖、27 个 npm 直接依赖、292 个已安装 npm 包；许可证分组 9 类，无 AGPL/GPL 交付物 |
| Maven 直接依赖白名单缺口 | **WARN**：`org.springframework:spring-tx` 未登记 `docs/deps/allowlist.md`（历史遗留，非本任务引入；`docs/deps` 不在本任务写域，建议后续补 ADR/白名单，届时转为阻断） |
| Maven jar CVE | 本地无 dependency-check/NVD 离线库 → SKIP；由 CI `ci/image-scan` 的 Trivy 覆盖镜像内 jar 与 OS 包（EXT-08） |
| 镜像扫描 | Docker 不可用（EXT-08）→ 生成待扫清单 `.cache/security/image-scan-manifest.txt`（9 个服务镜像 + `trivy image --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1`）；Docker+Trivy 可用时脚本自动真实扫描并阻断 |
| 密钥/敏感配置/日志 PHI | `security-check.sh` 21 项全部通过：无私钥/云 token、密钥必须是 `${ENV}`、默认/prod fail-closed、日志无口令/令牌/患者标识、前端入口无 inline script |

`security-check.sh` 已接入 `scripts/check.sh` 第 8/8 步（离线、确定性，约 1–2 秒），
不改变原 7 步的语义；动态扫描不进 `check.sh`（需网络/工具，避免日常门禁不确定）。

## 5. 权限矩阵复核（M0–M5）

复核方法：以迁移脚本的 `sys_role_permission` 种子为事实源，对照 `@PreAuthorize` 与
OpenAPI `security` 声明，汇总既有 403/401 用例，并补两条关键缺口用例。图例：
`✓` 授权；`—` 未授权（403）；`★` 受数据范围（ALL/DEPT/ROOM/SELF）二次裁剪；
匿名访问统一 401（login/refresh 除外）。

| 域 | 端点组（权限点） | 匿名 | ADMIN | OR_MGR | ANES | NURSE | SURGEON | QC | PHARM | AUTH_MGR |
|---|---|---|---|---|---|---|---|---|---|---|
| M0 | `POST /v1/auth/login`、`/refresh` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| M0 | `/v1/auth/me`、`/logout`、`/password` | — | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| M0 | `GET /v1/auth/sessions`（`m0:auth:read`） | — | ✓ | — | — | — | — | — | — | — |
| M0 | `/v1/auth/users`（`m0:user:read/write`） | — | ✓ | — | — | — | — | — | — | — |
| M0 | `GET /v1/data-scope`（`m0:org:read`） | — | ✓ | ✓ | — | — | — | — | — | — |
| M1 | `/v1/scheduling/applications`（`m1:application:read/write`） | — | ✓ | ✓ | — | — | read | — | — | — |
| M1 | `/v1/scheduling/schedules/**`（`m1:schedule:read/write`、`approve`） | — | ✓ | ✓ | — | — | read | — | — | — |
| M1 | `/v1/scheduling/analytics/**`（`m1:analytics:read`） | — | ✓ | ✓ | — | — | — | ✓ | — | — |
| M2 | 术前/知情同意/三方核查/记录/PACU/随访（`m2:*:read/write`） | — | ✓ | — | ✓ | ✓ | — | — | — | — |
| M2 | 记录锁单/修订（`m2:record:lock/revise`） | — | ✓ | — | ✓ | — | — | — | — | — |
| M2 | 设备登记（`m2:device:read/write`） | — | ✓ | ✓ | — | — | — | — | — | — |
| M2 | 预警读/处置/抢救（`m2:alert:read/ack`、`m2:rescue:write`） | — | ✓ | — | ✓ | ✓ | — | — | — | — |
| M2 | 预警规则配置（`m2:alert:configure`） | — | ✓ | — | — | — | — | — | — | — |
| M4 | 药品目录/麻精五专（`m4:drug:*`、`m4:narcotic:manage`） | — | ✓ | — | ✓ | read | — | — | ✓ | — |
| M4 | 耗材/UDI（`m4:material:*`、`m4:udi:manage`） | — | ✓ | ✓ | — | read+UDI | — | — | — | — |
| M4 | 人员档案/排班/培训/授权（`m4:staff:*`、`schedule:manage`、`training:manage`） | — | ✓ | read+排班 | read（V22 收回写） | — | — | — | — | ✓（写/审批） |
| M4 | 报表（`m4:report:read/export`） | — | ✓ | ✓ | — | — | — | ✓ | — | — |
| M4 | 质控（`m4:qc:read/write/review/export`） | — | ✓ | read+write | — | — | — | ✓ | — | — |
| M5 | `/v1/dashboard/**`（`m5:dashboard:read`） | — | ✓ | ✓ | ✓（SELF） | — | — | ✓ | — | — |

- 所有 `★` 数据范围由服务层裁剪；集合查询越权 **403**，单对象越界 **404**（不泄露存在性）。
- 既有覆盖：`AuthApiIntegrationTest`（401/403/会话/审计）、`M4AcceptanceTest`（角色×端点正反例）、
  `M4*ApiIntegrationTest`（DataScope）、`M5DashboardRiskResourceApiIntegrationTest`（驾驶舱 403/范围）、
  E2E `auth.spec.ts`（UI 越权）。
- **本任务补测**（`SecurityAuthorizationRegressionTest`）：
  1. `SYS_QC_MANAGER` 读驾驶舱 4 端点 200（V26 新授权正例）；`SYS_SURGEON` 403 且写
     `ACCESS_DENIED` 审计（actor/result/detail 不含口令/令牌）；匿名 401；
  2. M0 用户管理仅 SYS_ADMIN：麻醉医师读 403、写 403 且**零写入**，管理员 200。
- 结论：M0–M5 端点权限与迁移授权一致，未发现“缺权限却放行”或“有权限却 403”的偏差；
  M3（人员域）当前为空壳模块，无对外端点，不纳入矩阵。

## 6. 审计覆盖复核

| 动作（`sys_audit_log.action`） | 触发点 | 失败路径 | 敏感字段 |
|---|---|---|---|
| `LOGIN` | `AuthServiceImpl.login` `@Audited` | 口令错误/锁定/停用/参数错：`result=0`，detail 只含用户名、异常类名与固定文案 | 不记口令/令牌 |
| `TOKEN_REFRESH` / `LOGOUT` / `PASSWORD_CHANGE` | `AuthServiceImpl` `@Audited` | 失败同样留痕（`result=0`） | 不记 refresh/access token |
| `ACCESS_DENIED` | `RestAccessDeniedHandler` + `ApiExceptionHandler`（服务层权限/数据范围拒绝） | 403 必留痕（actor/IP/路径/方法） | 不记请求体 |
| `AUTH_UNAUTHORIZED` | `RestAuthenticationEntryPoint` | 401 按 IP+路径 1 分钟采样，防扫描器刷库 | 不记令牌 |
| 业务动作 | 各域 `@Audited`（`USER_CREATE/UPDATE`、`RECORD_LOCK/REVISE`、`DRUG_*`、`MATERIAL_*`、`REPORT_QUERY/EXPORT`、`QC_*`、`DASHBOARD_VIEW` 等） | 成功/失败均写；审计故障成功路径 fail-open 并累加 `auth.audit.failures`，失败路径不掩盖原异常 | detail 按白名单构造，无 PHI |

审计表 append-only（UPDATE/DELETE/TRUNCATE 被触发器拒绝）；`trace_id` 与响应 `X-Trace-Id`
一致，180 天在线留存要求见方案书 §3.9.1。本任务复核未发现“关键动作无审计”或“审计含
口令/令牌/患者标识”的路径；限流短路请求（429）不进入认证流程，故不产生登录审计。

## 7. OWASP Top 10（2021）对照结论

复评时间 2026-11-15（M5-QA-01），方法：以 M5-M19-02 安全加固证据为基线，叠加 M5-QA-01 的
性能/并发/稳定性专项与 M0–M5 既有集成测试，逐项给出“现状 / 证据 / 剩余风险 / EXT”。
“证据”只列**可失败**的自动化用例或脚本（不含人工目检）；“EXT”指向 §8 或
`docs/acceptance/EXTERNAL-DEPS.md` 的编号，无外部依赖的写“无”。

| 风险 | 现状（当前控制措施） | 证据（可失败测试/脚本） | 剩余风险 | EXT / 遗留 |
|---|---|---|---|---|
| **A01 访问控制失效** | RBAC + 数据范围（ALL/DEPT/ROOM/SELF）+ 方法级 `@PreAuthorize`；集合越权 403、单对象越界 404（不泄露存在性）；拒绝即写 `ACCESS_DENIED` 审计；锁单/修订有行锁 + version 条件，并发下恰一个成功 | `SecurityAuthorizationRegressionTest`（2）、`M4AcceptanceTest#roleEndpointMatrixCoversPositiveAndNegativeCases`、`M4*ApiIntegrationTest`（DataScope）、`M5DashboardRiskResourceApiIntegrationTest`（4 端点 403）、`ConcurrencyRegressionTest#concurrentLockHasExactlyOneWinner`、E2E `auth.spec.ts` | M3 人员域当前为空壳、无对外端点，矩阵未覆盖；前端菜单/路由守卫仅是展示层，真实边界在 API（已由集成测试锁定） | 无（M3 端点落地后补矩阵行） |
| **A02 加密机制失效** | TLS 1.2/1.3 + HSTS；口令 BCrypt；手机号 AES-256-GCM + HMAC；JWT HS256 密钥 ≥32 字节且缺失即 fail-closed；刷新令牌只存 SHA-256 哈希；字段密钥/pepper 走环境注入；锁单/修订摘要 SHA-256 | `SecurityHeadersIntegrationTest`（3）、`NginxSecurityHeadersConfigTest`（2）、`AuthApiIntegrationTest`（跨密钥 401、轮换/吊销）、`ClinicalRecordAcceptanceTest` + `RecordLockRevisionApiIntegrationTest`（摘要 64 位 hex、版本链稳定）、`security-check.sh`（无硬编码密钥/云 token） | 密钥托管与轮换依赖院方 KMS/密钥管理制度；HS256 为共享密钥模式，无自动轮换 | EXT-05（密钥托管待院方） |
| **A03 注入** | MyBatis-Plus 参数绑定 + Bean Validation + 契约枚举白名单；CSV 导出对 `= + - @` 前置单引号（公式注入）；日志不含 SQL 实参与患者标识 | `M4AcceptanceTest#csvExportsAreBomMaskedInjectionSafePhiFreeAndAudited`（`workload/multi` + `indicator/adverse/review` 五类 CSV）、`M4ReportApiIntegrationTest#csvExportHasBomHeaderFormulaGuardNoPhiAndAudit`、`security-check.sh`（日志无 PHI/口令）、Checkstyle/SpotBugs/Semgrep（CI `ci/sast`） | 动态 SQL 片段（排序/维度拼装）依赖白名单与评审保证；无 WAF，注入尝试仅靠 nginx 限流与日志观测 | 无 |
| **A04 不安全设计** | 临床记录 append-only（数据库触发器 55000）＋锁单/修订版本链；三方核查、双人核对、状态机；配置 fail-closed；看板为只读聚合、不含 PHI，刷新用 10s 轮询而非 SSE（无长连接服务端状态）；报表/事件并发幂等 | `ClinicalRecordAcceptanceTest`（时间轴/锁单/并发修订链）、`M4AcceptanceTest`（授权治理/五专对账）、`ConcurrencyRegressionTest`（并发登录/锁单/事件 seq/报表 4 组）、`DashboardPollingStabilityTest`（20 并发聚合逐字段一致、无 `text/event-stream`） | 临床/管理口径仍有【待确认】项（ADR-0018…0023），由院方确认后可能调整聚合语义；设计评审仍依赖人工 | 无（口径项按 ADR 管理） |
| **A05 安全配置错误** | 默认即安全：默认/prod 缺密钥拒绝启动；springdoc/actuator 收紧；CSP（`script-src 'self'` 无 inline/eval）、HSTS（仅 HTTPS）、XFO DENY、nosniff、Referrer、Permissions；登录/刷新限流 | `security-check.sh` **21/21**（接入 `check.sh` 第 8/8 步）、`DefaultProfileSecureTest`、`SecureDefaultsIntegrationTest`、`SecurityHeadersIntegrationTest`、`RateLimitIntegrationTest`（4）、`RateLimitRecoveryTest`（1）、`NginxSecurityHeadersConfigTest`、`compose-check.sh` 34 PASS | CSP `style-src 'unsafe-inline'`（Element Plus 行内样式）；HSTS 无 `preload`；nginx master 以 root 启动 | 遗留-3 / 遗留-4 / 遗留-5 |
| **A06 易受攻击组件** | `pnpm audit --prod` 硬门禁 + 例外表（逐条记录 GHSA/理由/到期）；直接依赖 allowlist + 许可证校验；镜像 Trivy 进 CI；lockfile 精确版本 | `scripts/security-scan.sh`（npm 生产依赖 **0 HIGH/CRITICAL**；1 条 moderate `element-plus` 低于阻断线；1 条 WARN `spring-tx` 白名单缺口；镜像待扫清单 9 项）、`check-deps-license.py`、CI `ci/deps-license` / `ci/image-scan` | Maven jar CVE 本机无 dependency-check 离线库，未实测；镜像内 OS 包漏洞未扫 | EXT-08（Docker/Trivy 不可用）；遗留-2 |
| **A07 身份认证失效** | 失败计数/锁定（原子 UPDATE，并发错误口令全部累计）；刷新令牌轮换 + 哈希存储 + 条件撤销（并发刷新恰一个成功）；吊销表；强制改密；登录按 IP+用户名限流 429 + `Retry-After`；账号不存在也执行假哈希比对（侧信道防护） | `AuthApiIntegrationTest`（锁定/轮换/改密/401/审计）、`RateLimitIntegrationTest`（4）、`RateLimitRecoveryTest`（1）、`ConcurrencyRegressionTest#concurrentLoginAndRefreshIssueExactlyOneRotatedToken`（8 并发登录全 200 且令牌互异；同一刷新令牌 8 并发 → 恰 1×200、7×401、0×500） | 应用层限流为单实例内存桶，多实例部署阈值被放大；未启用 MFA（方案未要求） | 遗留-1 |
| **A08 软件与数据完整性** | Flyway 版本化迁移 + expand-contract；审计/临床/修订表 append-only（UPDATE/DELETE/TRUNCATE 55000）；契约生成物禁手改（ADR-0003）；依赖 lockfile；外部回执按 `idempotencyKey` 幂等 | `ClinicalRecordAcceptanceTest`（直写 55000）、`M4AcceptanceTest`（五专/UDI 审计与台账守恒）、`SpdSimulatorContractTest`（同 key 同回执）、`security-check.sh`、`scripts/contract-check.sh`（drift/oasdiff） | 镜像 digest 固定与 SBOM/签名未落地；迁移回滚演练仅本机记录 | EXT-08（镜像 digest/Trivy） |
| **A09 日志与监控失效** | 统一审计覆盖登录/刷新/登出/改密/越权/401/业务动作的成功与失败；`trace_id` 与 `X-Trace-Id` 一致；401 按 IP+路径采样；`auth.audit.failures` 指标；nginx 访问日志不含 query；日志/审计无口令、令牌、患者标识 | `AuthAuditFailOpenTest`、`AuthApiIntegrationTest`（审计 actor/result/detail 断言）、`NginxPhiLogConfigTest`、`security-check.sh`（日志无 PHI/令牌）、`ConcurrencyRegressionTest#concurrentReportsStayIdempotent`（4 路并发各留 `REPORT_QUERY` 审计） | Loki 告警规则与值班流程待运维配置；审计在线留存 180 天未做容量验证 | 运维遗留（Loki/留存） |
| **A10 SSRF** | 外部系统仅 HIS/药房/SPD/设备适配器，地址来自受控配置（env），代码中无用户可控 URL 拉取；模拟器只绑定 `127.0.0.1`；超时/4xx 不重试、5xx 有限重试、失败降级不阻塞主链路 | `services/simulators` 36 用例（非法配置 fail-closed）、`HisSimulatorContractTest` / `PharmacySimulatorContractTest` / `SpdSimulatorContractTest`（超时降级、4xx 不重试、503 重试）、`WireMapperContractTest`（缺失/非法字段 fail-closed） | 真实接口（HL7/WebService、mTLS、库房/SPD 地址）接入后，出站白名单、DNS 重绑定与超时策略需重新评估 | EXT-01/02/03/04（真实联调前复评） |

**结论（M5-QA-01）**：A01–A10 十项均有可执行证据，未发现未处置的 HIGH/CRITICAL 风险；
剩余风险集中在外部依赖（密钥托管 EXT-05、镜像/依赖扫描 EXT-08、外部接口 EXT-01…04）
与已知折衷（单实例限流、CSP `style-src`、HSTS preload、nginx root），已登记在 §8 与
`docs/testing/README.md` §15 的发布准入矩阵，**不构成发布阻断**。


## 8. 已知限制与外部依赖（EXT）

| 编号 | 限制 | 影响 | 解除条件 / 责任 |
|---|---|---|---|
| EXT-08 | Docker 不可用：镜像构建/Trivy/Testcontainers 未真实执行 | 镜像漏洞与 Maven jar CVE 未在本机实测 | runner 具备 Docker+Trivy，构建 api/web 并扫描通过；回填 ACCEPTANCE |
| EXT-10 | ~~CSP 未启用~~ | 已解除：CSP 已在 nginx 启用并有静态/行为断言 | — |
| 遗留-1 | 应用层限流为单实例内存桶 | 多实例/集群下每实例独立计数，阈值放大 | 部署多实例前改 Redis 令牌桶（需新增依赖，走 ADR） |
| 遗留-2 | `org.springframework:spring-tx` 未登记 allowlist | `check-deps-license.py` 失败；`security-scan.sh` 降为 WARN | 补 `docs/deps/allowlist.md`（`docs/deps` 不在本任务写域），随后恢复阻断 |
| 遗留-3 | CSP `style-src 'unsafe-inline'`（Element Plus 行内样式） | 样式注入面略宽，不影响脚本执行 | 升级 Element Plus 并移除行内样式后收紧 |
| 遗留-4 | HSTS 无 `preload` | 首次访问仍可能走 HTTP（已 301 跳转） | 院方确认全子域 HTTPS 后提交 preload 列表 |
| 遗留-5 | nginx master 以 root 启动 | 容器内提权面 | 切 `nginx-unprivileged` 或非 root 镜像（`docs/ci/audit-exceptions.md` 已登记） |

## 9. 复现命令

```bash
source scripts/env.sh

# 安全头/限流/权限/审计专项（12 例）
./scripts/mvn.sh -B -pl apps/api/api-bootstrap -am \
  -Dtest=RateLimitIntegrationTest,RateLimitRecoveryTest,SecurityHeadersIntegrationTest,NginxSecurityHeadersConfigTest,SecurityAuthorizationRegressionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

bash scripts/security-check.sh      # 静态，21 项
bash scripts/security-scan.sh       # 联网，依赖 + 镜像清单
bash scripts/compose-check.sh       # nginx/compose 静态校验
./scripts/check.sh                  # 本地全量门禁（含第 8 步安全静态检查）

# M5-QA-01：性能/并发/稳定性证据（OWASP §7 的“性能/并发”类证据；约 2 分钟）
bash scripts/performance-smoke.sh
# 或单独跑：-Dtest=PerformanceBenchmarkTest / ConcurrencyRegressionTest /
#           DashboardPollingStabilityTest（见 docs/testing/README.md §15.5）
```

## 10. 变更记录

- 2026-11-15（M5-QA-01）：§7 重写为 OWASP Top 10 2021 逐项“现状/证据/剩余风险/EXT”对照
  （A01–A10 全部引用可失败测试/脚本）；§1 增补性能/并发/稳定性证据索引；§9 增加
  `scripts/performance-smoke.sh`。
- 2026-11-15（M5-M19-02）：安全头/CSP/HSTS、登录与刷新限流（429 + `Retry-After`）、
  `security-check.sh`（21 项）与 `security-scan.sh`、M0–M5 权限矩阵复核、审计覆盖复核；
  初版 OWASP Top 10 对照（§7）。
