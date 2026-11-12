# M0 公共支撑 — 认证与权限骨架（S0-07）

> 状态：S0-07 完成（安全评审 B-1/B-2/B-3 与 S-1…S-11 已修）+ M2-M0-01 数据范围绑定（V5）。
> 依据：`packages/contracts/openapi/auth.yaml` 0.4.0、方案书 §2.1.2（角色 R1–R11）、
> §2.4.1（M0 公共支撑）、§3.9.1（等保身份鉴别/审计）、§3.9.2（RBAC 与数据权限）、§3.9.5（OAuth2/JWT）。
> 迁移：`db/migration/V1`（结构）、`V2`（平台预置）、`V3__m0_auth_token.sql`（刷新令牌 + `m0:auth:read`/`m0:auth:write` + `phone_hash`）、
> `V5__m0_data_scope.sql`（`sys_org`/`sys_user_org`/`sys_user_room` + `m0:org:read`/`m0:org:write`，M2-M0-01）。

## 0. 使用方式（本文档怎么用）

| 你要做什么 | 从这里开始 |
|---|---|
| 本地跑起登录/改密/会话 | [`docs/guides/quickstart.md`](../../guides/quickstart.md) → [`docs/guides/demo.md`](../../guides/demo.md) |
| 看/改实现 | [`apps/api/modules/m0/README.md`](../../../apps/api/modules/m0/README.md)（模块边界、测试命令、状态） |
| 改接口字段 | 先改 [`packages/contracts/openapi/auth.yaml`](../../../packages/contracts/openapi/auth.yaml)，再 `bash scripts/gen-contracts.sh --all`；禁止手改生成物 |
| 改权限码/角色映射 | 本文件第 3 节 + `db/migration`（新增版本，不改已执行脚本），权限/审计属核心域需双人评审 |
| 查审计 | `m0_platform.sys_audit_log`（只追加）；查询示例见 [`docs/guides/demo.md`](../../guides/demo.md) 第 4 节 |
| 跑测试 | `./scripts/mvn.sh -B -pl apps/api/modules/m0/impl -am test` |

## 1. 接口（与契约 operationId 一致）

| 方法 | 路径 | 授权 | 说明 |
|---|---|---|---|
| POST | `/v1/auth/login` | permitAll | 本地口令登录；口令错误统一 401；停用/锁定/非本地认证统一 422 模糊文案；IP 限流 429 |
| POST | `/v1/auth/refresh` | permitAll | 刷新令牌轮换；旧令牌立即失效；无效/过期 401；IP 限流 429 |
| POST | `/v1/auth/logout` | authenticated | 撤销当前会话刷新令牌 + 访问令牌 jti；204，幂等 |
| POST | `/v1/auth/password` | authenticated | 修改口令（校验旧口令 + 新口令强度）；204；强制改密账号可用 |
| GET | `/v1/auth/me` | authenticated | 角色、权限点、数据范围、`mustChangePassword` 快照（实时查库） |
| GET | `/v1/auth/sessions` | `PERM_m0:auth:read` | 当前用户会话分页（page/size），越权 403 |
| GET | `/v1/auth/data-scope/users` | authenticated + `PERM_m0:org:read` + 数据范围 | 数据范围示例接口：按 `orgId`/`roomId` 过滤可见用户标识；缺功能权限、范围为空或越权 403 + `ACCESS_DENIED` 审计 |

错误响应统一 `{code,message,traceId,details}`：`AUTH_UNAUTHORIZED`(401)、`AUTH_FORBIDDEN`(403)、
`PARAM_INVALID`(400)、`BUSINESS_RULE_VIOLATION`(422)、`RATE_LIMITED`(429)。
停用/锁定/非本地认证统一「账号状态或认证方式不允许登录」，不通过文案暴露账号状态（S-2）。

## 2. JWT / 刷新 / 撤销设计

- **访问令牌**：HS256 JWT，默认 **15 分钟**（`anesop.security.jwt.access-ttl`）。
  claim：`sub`=用户 UUID、`username`、`displayName`、`roles[]`、`permissions[]`、`sid`=会话 ID、`jti`。
- **刷新令牌**：256 位随机值（Base64URL），默认 **7 天**；DB 只存 **SHA-256** 十六进制哈希
  （`m0_platform.sys_refresh_token.token_hash`），不依赖 Redis。
- **轮换**：刷新时先按 `id + revoked_at IS NULL` 条件更新撤销旧行，再插入新行；并发刷新只有一个成功。
- **登出**：按 `sid` 撤销当前刷新令牌，并把 `jti` 写入吊销表；吊销表接口
  `AccessTokenRevocationStore` 现为进程内实现，多实例部署替换 Redis（占位，不改调用方）。
- **密钥（B-1）**：`anesop.security.jwt.secret` / `ANESOP_JWT_SECRET`，必须是 **Base64**、
  解码后 **≥32 字节**；拒绝占位/示例值（`CHANGE_ME`/`CHANGEME`/`EXAMPLE`/`PLACEHOLDER`/
  `REPLACE_ME`/`TODO`/整词 `secret`）、全同字符或唯一字符数 <8 的低熵值、非 Base64 值。
  生成方式：`openssl rand -base64 48`。`anesop.security.jwt.allow-dev-generated` 默认 **false**
  （未配置即启动失败），仅 dev/test profile 显式置 true 时生成进程内随机密钥。
  错误信息不回显密钥；密钥不得入库/入日志。
- **失败处理（B-3/S-3）**：口令错误 401 且不区分账号是否存在；连续 5 次失败锁定 30 分钟（§3.9.1）。
  失败计数用**单条原子 UPDATE**（`failed_attempts = failed_attempts + 1` + CASE 置锁），
  10 并发错误口令可累计 ≥5 并锁定；锁定过期后原子清零；账号不存在时执行固定假哈希的
  BCrypt 比对，与存在账号同成本（防时序侧信道）；成功登录清零。
- **强制改密（S-3）**：`must_change_password=true` 时服务端只允许 `/v1/auth/me`、
  `/v1/auth/logout`、`/v1/auth/password`，其余接口 403「首次登录必须先修改口令」；
  改密成功后需重新登录取得 `mustChangePassword=false` 的新令牌。
- **限流（S-6）**：登录/刷新按来源 IP 令牌桶（`anesop.security.rate-limit.*`，内存实现，
  默认容量 20/分钟），超限 429 `RATE_LIMITED`；单实例语义，集群化登记遗留。
- **SSO / CA**：仅占位（非 LOCAL 认证返回 422），外部依赖 **BLOCKED-EXTERNAL / EXT-05**，S0 不实现。

## 3. 角色与权限码（定稿）

- 编码约定：权限点统一冒号三段式 `<模块>:<资源>:<动作>`（如 `m0:auth:read`、
  `anesthesia:record:read`）；V2 历史权限点为 `<模块>:<动作>`（如 `auth:login`），
  M1 起新增权限点统一三段式。服务端 Spring Security 授权表达式加前缀 `PERM_<code>`；
  角色授权加前缀 `ROLE_<code>`。`Authorization` 用 Bearer。
- 平台预置角色码（V2，演示基线）：`SYS_ADMIN`、`SYS_ANESTHESIOLOGIST`、`SYS_NURSE_ANESTHETIST`、
  `SYS_SURGEON`、`SYS_OR_MANAGER`、`SYS_QC_MANAGER`。
- S0-07 新增权限点（V3，仅授予 `SYS_ADMIN`）：`m0:auth:read`（`/v1/auth/sessions`）、`m0:auth:write`（M0 写接口预留）。

角色 R1–R11（§2.1.2）→ 角色码映射（定稿）：

| 编号 | 角色（§2.1.2） | 角色码 | 数据范围（§3.9.2） | 落库状态 |
|---|---|---|---|---|
| R1 | 麻醉科主任/质控负责人 | `SYS_ANESTHESIA_DIRECTOR` | DEPT | M1 按院方岗位矩阵落库【待确认】 |
| R2 | 麻醉医师 | `SYS_ANESTHESIOLOGIST` | SELF | V2 已落库 |
| R3 | 麻醉护士 | `SYS_NURSE_ANESTHETIST` | SELF | V2 已落库 |
| R4 | 手术室护士 | `SYS_OR_NURSE` / 管理岗 `SYS_OR_MANAGER` | DEPT | `SYS_OR_MANAGER` V2 已落库；`SYS_OR_NURSE` M1 |
| R5 | PACU 护士 | `SYS_PACU_NURSE` | DEPT | M1 |
| R6 | 耗材/库房管理员 | `SYS_MATERIAL_MANAGER` | ALL | M1 |
| R7 | 药剂科/麻精药品管理员 | `SYS_NARCOTIC_MANAGER` | ALL | M1 |
| R8 | 质控专员 | `SYS_QC_MANAGER` | ALL | V2 已落库 |
| R9 | 信息科管理员 | `SYS_ADMIN` | ALL | V2 已落库 |
| R10 | 手术科室医师 | `SYS_SURGEON` | SELF | V2 已落库 |
| R11 | 院领导/医务处 | `SYS_HOSPITAL_LEADER` | ALL | M1 |
| R12 | 患者/家属（可选） | 不建角色（受限视图） | — | 一期可选【待确认】 |

> 说明：R12 一期是否启用未定，不建账号角色；M1 只新增上表标注“M1”的角色，不改 V2 已有角色码。
> 数据范围值域：`ALL / SITE / DEPT / ROOM / SELF`（`sys_role.data_scope`）。

## 4. 数据权限（M2-M0-01：角色范围 + 绑定表，默认拒绝）

- `DataScope`（m0-api）：`ALL/SITE/DEPT/ROOM/SELF`，另含内部拒绝标记 `NONE`（不输出到契约）。
  契约输出映射 `ALL→ALL`、`SITE→SITE`、`DEPT→DEPARTMENT`、`ROOM→ROOM`、`SELF→OWN_PATIENTS`；
  `NONE` 映射为空 `dataScopes` 数组。
- **绑定表（V5）**：`sys_org`（`org_type`=SITE 院区 / DEPT 科室，树形 `parent_id`+`path`）、
  `sys_user_org`（用户—组织，复合主键 + 每用户最多一个主键 `is_primary`）、
  `sys_user_room`（用户—术间，唯一 `(user_id, room_id)`，`status=1` 才生效；`room_id` 为逻辑外键，
  术间主数据由 M2-M2-01 在 `m1_sched` 建立）。
- **解析**：`DefaultDataScopeResolver` 先读 `sys_user_role → sys_role.data_scope`，再按类型取绑定：
  `SITE`/`DEPT` ← `sys_user_org`（组织需 status=1、未删除），`ROOM` ← `sys_user_room`（status=1），
  `SELF` 无对象标识。返回 `DataScopeAssignments`（`assignments()` / `isDenied()` /
  `isUnrestricted()` / `hasObjectScope()` / `isSelfOnly()` / `requiresObjectFilter()` /
  `allowedOrgIds()` / `allowedRoomIds()`）。
- **空列表不等于不过滤（S-1）**：ALL 与 SELF 的 `allowedOrgIds()` 都为空，但 ALL 是
  `isUnrestricted()`、SELF 是 `isSelfOnly()` 且 `requiresObjectFilter()=true`；调用方必须按
  `isDenied → isUnrestricted → requiresObjectFilter` 判断，不得用“对象列表为空”推断放行。
- **SITE 子树（Q-1）**：`data_scope=SITE` 时范围覆盖 `sys_org.path` 前缀匹配的整棵子树
  （院区自身 + 下属全部科室）；`orgId` 过滤允许院区自身与任意下属组织，兄弟院区仍 403；
  `DEPT` 只精确匹配本科室。
- **fail-closed**：无角色、范围为空/未知、**声明 SITE/DEPT/ROOM 但无生效绑定**、查询异常 → `NONE`，
  调用方必须拒绝（403），绝不回退为 ALL/SELF；任一角色 `ALL` → 全院可见（不查绑定表）。
- `/v1/auth/me` 的 `dataScopes[].scopeIds` 返回真实绑定（DEPT→科室 UUID、ROOM→术间 UUID、SELF/ALL→空），
  `siteIds` 取 SITE 绑定（ALL 或无 SITE 绑定时为空数组）。
- **示例接口** `GET /v1/auth/data-scope/users`：首先要求功能权限点 `m0:org:read`
  （Q-2，数据范围不能替代功能权限）；范围为空或 `orgId`/`roomId` 超出范围 → 403 并写
  `ACCESS_DENIED` 审计；`orgId`+`roomId` 同时给出取交集；只返回用户 UUID，不含姓名/工号/手机号。
- 成员口径统一（S-5）：DEPT/ROOM 成员查询与 ALL 路径一致，只返回 `sys_user.status=1`
  且未删除用户；撤销授权（`sys_user_room.status=0`）与停用组织（`sys_org.status=0`）不参与解析。
- 后续：组织 CRUD 接口（`m0:org:read` 已由 V5 授予 `SYS_ADMIN` + `SYS_OR_MANAGER`，
  `m0:org:write` 仅 `SYS_ADMIN`）与 MyBatis 数据权限拦截器自动注入条件在 M1/M2 落地；
  多院区 `siteIds` 收窄在 M1 组织主数据完成后启用。

## 5. 审计

- 分层（S-10）：`@Audited` 注解与 `AuditWriter` 接口在 **m0-api**，`AuditAspect` 与
  `JdbcAuditWriter`（append-only，DB 触发器拒绝改写）在 m0-impl；api-bootstrap 只依赖 m0-api。
- 覆盖动作：`LOGIN`（成功/失败）、`TOKEN_REFRESH`（成功/失败）、`LOGOUT`、`PASSWORD_CHANGE`、
  `AUTH_UNAUTHORIZED`（401）、`ACCESS_DENIED`（403，URL 级由 `RestAccessDeniedHandler`、
  方法级由异常处理器写入）。登录/刷新/登出/改密的切面标注在 **AuthService 实现**上，
  能取得 `AuthPrincipal`，登出审计的 `actor_id/actor_name/resource_id`（会话 ID）非空（S-1）。
- 记录字段：`action/resource_type/resource_id/result(1|0)/detail(jsonb)/ip(inet)/user_agent/trace_id/occurred_at`。
- `detail` 只含白名单字段（操作名、尝试登录账号、**异常类名 + 固定短文案**）；
  异常 message 一律不入审计（防 SQL 实参/内部路径泄漏，S-5）；
  **不记录口令、令牌明文、请求体原文与患者标识**。
- **成功路径 fail-open（S-4）**：审计落库失败时记 ERROR 并累加指标 `auth.audit.failures`，
  响应仍按成功返回（不让审计故障放大为登录 500）；失败路径仍抛原始认证异常。
- **401 采样（S-6）**：同一 IP+路径 1 分钟内最多 1 条 `AUTH_UNAUTHORIZED`，避免扫描刷量；
  401 响应本身不受影响。
- `traceId` 由 `TraceIdFilter` 生成/透传（`X-Trace-Id`），日志 MDC 与审计、错误响应共用。

## 6. 运行时装配

- `api-bootstrap`：`@MapperScan("com.anesop.m0.impl.persistence.mapper")`、Spring Security
  （`spring-boot-starter-security` + `oauth2-resource-server` + `aop` + `jdbc`）、
  `maven-resources-plugin` 把 `db/migration`、`db/seed/demo` 复制进 classpath。
- 配置分层（B-2，默认即安全）：`application.yml` 关闭 springdoc、actuator 只暴露 health/info、
  Flyway 只含 `classpath:db/migration`、`demo-accounts-allowed=false`、`allow-dev-generated=false`；
  `application-dev.yml` / `application-test.yml` 显式开启文档/指标/演示种子；
  `application-prod.yml` 再次显式收紧（springdoc 关闭、health/info、日志 WARN）。
  **不再设置 `spring.profiles.default=dev`**：漏设 profile 时按默认安全基线启动。
- 生产启动守卫 `ProductionDemoAccountGuard`（**不依赖 profile**）：`demo-accounts-allowed=false`
  时存在 `demo_*` 账号即拒绝启动；`username LIKE 'demo\_%'` 精确转义，`demouser` 不命中。
- MyBatis-Plus 下划线转驼峰 + 乐观锁；`UuidTypeHandler` 全局注册
  （jsonb/inet 由实体 `@TableField` 精确绑定）。

## 7. 测试与验证

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml verify          # 全模块构建 + Checkstyle/SpotBugs + 测试
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-contracts -am generate-sources
bash scripts/gen-contracts.sh                           # TS 生成（漂移门禁）
bash scripts/gen-contracts.sh --java                    # Java 契约模型（M1 前置）
bash scripts/contract-check.sh                          # spectral + oasdiff + 漂移
```

- `JwtSecretProviderTest`（8）：占位值/短密钥/低熵/非 Base64 拒绝、合法 32 字节通过、缺失默认失败。
- `AuthApiIntegrationTest`（18）：登录成功/失败、缺失/过期/伪造令牌、/me、/sessions、越权 403、
  刷新轮换、登出吊销与审计归属、参数校验、422 模糊文案、强制改密、并发锁定、锁定过期清零、
  401 审计采样、springdoc 路径。
- `SecureDefaultsIntegrationTest`（2）：prod 上下文 springdoc 404、actuator 仅 health/info。
- `DefaultProfileSecureTest`（2）：不激活任何 profile 时 springdoc 404、actuator 仅 health/info。
- `ProductionStartupFailureTest`（2）：prod 缺密钥启动失败、含 demo_* 账号启动失败。
- `ProductionDemoAccountGuardTest`（3）：真实 PG 验证 `demo\_%` 转义（demo_admin 命中、demouser 不命中）。
- `RateLimitIntegrationTest`（2）：低阈值验证登录/刷新 429。
- `AuthAuditFailOpenTest`（1）：审计故障时登录仍 200 且 `auth.audit.failures` 递增。
- `AuthAcceptanceFlowTest`：验收流程打印 6 步响应与三类审计记录。
- `DefaultDataScopeResolverTest`（14）：ALL/DEPT/SITE/ROOM/SELF 解析、未绑定/未知/查询异常 fail-closed。
- `DataScopeAssignmentsTest`（11）：拒绝标记、ALL 不受限、SELF/DEPT 过滤语义、空值忽略、
  org/room 并集去重、ALL 与其他范围并存时仍不受限。
- `DataScopePersistenceIntegrationTest`（12，真实 PG）：admin→ALL、DEPT→本科室 scopeIds、
  SITE→院区子树且不越界兄弟院区、未绑定/无角色→NONE、ROOM 仅绑定可见、SELF 仅本人、
  撤销绑定/停用组织 fail-closed、停用用户不出现在 DEPT/ROOM、跨科室 403、8 线程并发解析一致。
- `DataScopeApiIntegrationTest`（9）：`/me` 返回真实 scopeIds、DEPT 只看本科室、未绑定 403 + 审计、
  ROOM 越权 403、缺 `m0:org:read` 403、admin 全院可见、springdoc 暴露新路径。
- 全量 122 个测试（api-contracts 4、m0-impl 59、api-bootstrap 59），Checkstyle 0 / SpotBugs 0。
- 集成测试共用嵌入式 PostgreSQL 16（Docker 不可用 EXT-08），不使用 Docker、不使用真实患者数据。

## 8. 遗留与风险

| 项 | 说明 | 归属 |
|---|---|---|
| Redis 会话/吊销表 | 当前进程内吊销表，多实例部署失效；接口已留，替换实现即可 | M1/部署前 |
| SSO / AD / LDAP / CA 签名 | 占位，非 LOCAL 认证返回 422 模糊文案 | BLOCKED-EXTERNAL EXT-05 |
| 数据权限绑定表 | **已完成**（M2-M0-01 / V5）：`sys_org`/`sys_user_org`/`sys_user_room` + scopeIds 解析 | — |
| 组织 CRUD 与术间主数据 | `m0:org:read` 已授权 SYS_ADMIN/SYS_OR_MANAGER、`m0:org:write` 仅 SYS_ADMIN，但组织维护接口未建；术间主数据由 M2-M2-01 在 `m1_sched` 建立 | M2 |
| 数据权限自动注入 | 当前由服务显式校验（示例接口）；MyBatis 拦截器自动注入 WHERE 条件待 M1 排程/记录查询落地 | M1 |
| 并发会话上限 / 强制下线 | 仅按会话撤销刷新令牌，未限制同一账号并发会话数 | M1 |
| phone 写路径 | V3 已加 `phone_hash varchar(128)`，实体已映射；`phone_enc`/`phone_hash` 的加密与 HMAC 写入（PhoneHashing + 密钥配置）未实现 | M1 |
| 跨服务 trace 透传 | `X-Trace-Id` 由本服务生成，网关跨服务透传未做 | M1 |
| 契约 drift（springdoc → baseline） | 当前只断言 auth 路径存在，未做 springdoc 全量导出与 baseline diff | M1 前补 |
| 限流集群化 | 内存令牌桶为单实例语义，多实例需 Redis 令牌桶 | M1/部署前 |
| openapi-generator 插件版本 | 7.10.0 暂写在 `api-contracts/pom.xml` | 主会话收敛根 pluginManagement |

> `phone_enc`（密文，供展示前解密）与 `phone_hash`（HMAC-SHA-256，供精确检索/去重）
> 的字段名与口径已在本文件、`docs/db/README.md`、`docs/data-dictionary/core-entities.md` 统一；
> V3 只做 expand 加列，写路径 M1。
