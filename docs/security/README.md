# 安全加固与安全运营（M5-M19-02）

- 状态：已完成（2026-11-15），进入 Review
- 任务：`docs/ai/tasks/M5-backlog.md` M5-M19-02；验收：`docs/acceptance/ACCEPTANCE.md` B/G 项
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

| 风险 | 本系统措施 | 结论 / 遗留 |
|---|---|---|
| A01 访问控制失效 | RBAC + 数据范围（ALL/DEPT/ROOM/SELF）+ 方法级 `@PreAuthorize` + 403/404 语义 + 越权审计；本任务矩阵复核与补测 | 通过；M3 空壳，待其端点落地后补矩阵 |
| A02 加密机制失效 | TLS 1.2/1.3、HSTS、BCrypt 口令、手机号 AES-256-GCM + HMAC、JWT HS256（≥32 字节、fail-closed）、字段密钥/pepper 环境注入 | 通过；密钥托管（EXT-05）待院方 |
| A03 注入 | MyBatis-Plus 参数绑定 + Bean Validation + 契约枚举；日志不含 SQL 实参 | 通过（Semgrep/SpotBugs/评审兜底） |
| A04 不安全设计 | 记录 append-only、锁单/修订版本、双人核对、状态机、fail-closed 配置 | 通过；临床口径待确认项按 ADR 管理 |
| A05 安全配置错误 | 默认即安全（prod fail-closed）、springdoc/actuator 收紧、CSP/HSTS/安全头、密钥占位、`security-check.sh` 21 项 | 通过；CSP `style-src 'unsafe-inline'` 为 Element Plus 兼容的已知折衷 |
| A06 易受攻击组件 | `pnpm audit`（prod 硬门禁 + 全量例外表）、allowlist/许可证、镜像 Trivy（CI） | 通过（0 HIGH/CRITICAL）；Maven jar CVE 依赖 CI 镜像扫描（EXT-08），`spring-tx` 白名单缺口待补 |
| A07 身份认证失效 | 登录失败计数/锁定、刷新令牌轮换 + 哈希存储、吊销表、强制改密、限流 429 | 通过；多实例限流需 Redis（遗留） |
| A08 软件与数据完整性 | Flyway 版本化迁移 + append-only 审计、契约生成物禁手改、依赖 lockfile | 通过 |
| A09 日志与监控失效 | 统一审计 + traceId + 401 采样 + `auth.audit.failures` 指标 + 访问日志无 query（无 PHI） | 通过；Loki 告警规则待运维配置 |
| A10 SSRF | 外部系统仅 HIS/药房/SPD/设备适配器，地址来自受控配置；无用户可控任意 URL 拉取 | 通过（真实联调 EXT-01/02/03/04 待接入时复评） |

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
```
