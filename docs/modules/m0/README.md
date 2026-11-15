# M0 公共支撑 — 认证、权限与用户管理（S0-07 / M2-M0-02）

> 状态：S0-07 完成（安全评审 B-1/B-2/B-3 与 S-1…S-11 已修）+ M2-M0-01 数据范围绑定（V5）
> + M2-M0-02 手机号加解密与用户管理（V6，契约 0.5.0）
> + **M8-M0-04 字典 API / 消息中心 / 文件管理（V27，契约 platform.yaml 0.2.0）**。
> 依据：`packages/contracts/openapi/auth.yaml` 0.5.0、方案书 §2.1.2（角色 R1–R11）、
> §2.4.1（M0 公共支撑）、§3.9.1（等保身份鉴别/审计）、§3.9.2（RBAC 与数据权限）、
> §3.9.3（字段加密与脱敏）、§3.9.5（OAuth2/JWT）。
> 迁移：`db/migration/V1`（结构）、`V2`（平台预置）、`V3__m0_auth_token.sql`（刷新令牌 + `m0:auth:read`/`m0:auth:write` + `phone_hash`）、
> `V5__m0_data_scope.sql`（`sys_org`/`sys_user_org`/`sys_user_room` + `m0:org:read`/`m0:org:write`，M2-M0-01）、
> `V6__m0_user_phone_crypto.sql`（`phone_key_id`/`phone_updated_at` + 四列一致性约束 + `m0:user:read`/`m0:user:write`，M2-M0-02）、
> `V27__m0_dict_message_file.sql`（`sys_message`/`sys_file` + 字典读索引 +
> `m0:dict:read|write`/`m0:message:read`/`m0:file:read|write`，M8-M0-04）。

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
| GET | `/v1/auth/users` | `PERM_m0:user:read` | 用户分页（page/size/keyword）；关键字支持账号/显示名模糊与 11 位手机号哈希精确匹配；只返回 `phoneMasked` |
| GET | `/v1/auth/users/{userId}` | `PERM_m0:user:read` | 用户详情（脱敏）；不存在/已删除 404 |
| POST | `/v1/auth/users` | `PERM_m0:user:write` | 创建用户：账号大小写不敏感唯一（409）、手机号可选、BCrypt 口令、强制首次改密 |
| PUT | `/v1/auth/users/{userId}` | `PERM_m0:user:write` | 更新显示名/工号/手机号/状态/角色；手机号空串=清除；不允许停用当前登录账号、不允许改自身角色、不得授予超出操作人权限点的角色（403）；改工号撞唯一键 409 |
| POST | `/v1/auth/users/{userId}/password-reset` | `PERM_m0:user:write` | 管理员重置口令：一次性初始口令仅本次响应返回，强制改密，旧口令立即失效，同事务撤销该用户全部刷新令牌（旧刷新令牌 401） |

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

## 5. 手机号加密与脱敏（M2-M0-02，方案书 §3.9.3）

- **存储格式**：`sys_user.phone_enc` = `Base64(iv || ciphertext || tag)`，AES-256-GCM、
  随机 12 字节 IV、128 位认证标签；每条记录独立 IV（同一明文两次加密结果不同），
  改 IV/改密文都会导致解密失败（GCM 认证标签）。
- **检索哈希**：`sys_user.phone_hash` = HMAC-SHA-256(pepper, 规范化手机号) 的小写十六进制
  （64 字符），只用于等值查询/去重；手机号取值空间小（约 10^10），禁止用无密钥 SHA-256
  （可被彩虹表反推）。
- **密钥版本**：`sys_user.phone_key_id`（V6，≤32 字符，默认 `v1`）记录该行所用字段密钥版本，
  `phone_updated_at` 记录手机号最后写入时间。四列由库层约束 `ck_sys_user_phone_crypto`
  强制同生同灭（全空或全非空），杜绝“只有密文没有哈希”“换了明文但 key_id 未更新”等脏数据。
- **写路径**（`UserAdminServiceImpl`）：规范化（去空格/短横线/括号、去 `+86`/`0086`，
  校验 `1[3-9]` + 9 位）→ `PhoneCrypto.encrypt` → `PhoneHasher.hash` → 同事务写四列；
  修改手机号重写四列，显式清除时四列同时置空。
- **读路径**：只在 M0 服务内部解密并立即脱敏为 `138****5678`（保留前 3/后 4，≤7 位整体 `****`）；
  DTO、日志、审计、异常信息只出现 `phoneMasked`，**永不返回**明文、`phone_enc`、`phone_hash`
  或口令哈希。密文损坏或 `key_id` 不匹配时降级为不展示并记 WARN（日志不含密文/明文）。
- **密钥配置**（`anesop.security.*` / 环境变量）：`field-key`（`ANESOP_FIELD_KEY`，Base64，
  解码后必须恰好 32 字节；生成 `openssl rand -base64 32`）、`phone-pepper`（`ANESOP_PHONE_PEPPER`，
  Base64，≥32 字节；生成 `openssl rand -base64 48`）、`field-key-id`（默认 `v1`）。
  校验口径与 JWT 密钥一致（拒绝占位值、非 Base64、长度不符、低熵）；**两者解码后相同即拒绝启动**
  （否则一把密钥同时承担加密与检索哈希，破坏“泄露 pepper 不能解密密文”的隔离假设）。
  **prod 缺失/非法即拒绝启动**；仅 dev/test 的 `crypto.allow-dev-generated=true` 生成进程内随机密钥
  并 WARN（重启后旧密文不可解密，仅本地演示可接受）。两个值必须分开生成、不得复用。
- **密钥轮换（如实说明，当前未实现在线轮换）**：本应用运行期**只持一把**字段密钥
  （`field-key` + 当前 `field-key-id`）。解密时行内 `phone_key_id` 与当前 `field-key-id`
  不等即抛 `PhoneCryptoException`，读路径降级为不展示并记 WARN；应用内**没有** `key_id → 旧密钥`
  的映射，也没有“用旧密钥解密后再用新密钥重加密”的能力。因此直接更换 `field-key` 并递增
  `field-key-id` 会让旧密文不可解密（列表 `phoneMasked` 变空），**不是**可用的在线轮换。
  真正的轮换需 **M1 交付的离线双密钥重加密工具（尚未实现）**：在受控环境用旧密钥+新密钥
  离线重加密并重算 `phone_hash`，分批、可重入、记录进度，全量校验后再统一切换 `field-key-id`
  并下线旧密钥；上线前须演练与备份。密钥丢失 = 密文永久不可解密；密钥不得入库、入日志、
  入工单与截图。
- **权限点**：`m0:user:read`（列表/详情）、`m0:user:write`（创建/更新/重置口令），V6 仅授予
  `SYS_ADMIN`；V2 历史 `user:read`/`user:write` 保留不动（兼容期），M1 完成权限码三段式收敛后
  再以新 V 版本删除授权。
- **接口**：`GET/POST /v1/auth/users`、`GET/PUT /v1/auth/users/{userId}`、
  `POST /v1/auth/users/{userId}/password-reset`（契约 auth.yaml 0.5.0）：创建与重置都强制
  `must_change_password=true`，重置的一次性初始口令只在本次响应出现一次（不入库/日志/审计）。
- **M1 待办**：字段密钥轮换（`key_id → key` 映射**或**离线双密钥重加密工具，二者至少其一；
  当前应用只持单把密钥，改 `field-key-id` 即旧密文不可解）、手机号唯一性策略（同院去重口径
  【待确认】）、手机号变更二次确认与通知（短信/CA 为外部依赖 EXT-05）、用户管理乐观锁版本
  （当前服务端最后写入 + 版本自增）、密钥托管对接院方 KMS、
  `docs/data-dictionary/core-entities.md` 的 phone 字段口径同步。

## 6. 审计

- 分层（S-10）：`@Audited` 注解与 `AuditWriter` 接口在 **m0-api**，`AuditAspect` 与
  `JdbcAuditWriter`（append-only，DB 触发器拒绝改写）在 m0-impl；api-bootstrap 只依赖 m0-api。
- 覆盖动作：`LOGIN`（成功/失败）、`TOKEN_REFRESH`（成功/失败）、`LOGOUT`、`PASSWORD_CHANGE`、
  `USER_CREATE`、`USER_UPDATE`、`PASSWORD_RESET`、`AUTH_UNAUTHORIZED`（401）、`ACCESS_DENIED`
  （403，URL 级由 `RestAccessDeniedHandler`、方法级由异常处理器写入）。登录/刷新/登出/改密/用户管理的
  切面标注在服务实现上，能取得 `AuthPrincipal`；`resource_id` 规则为 **UUID 优先、session 兜底**：
  先取方法参数中的目标对象 UUID（USER_UPDATE / PASSWORD_RESET 的 `userId`），再取方法返回值中的
  目标对象 UUID（USER_CREATE 的新用户 id），最后才回退到会话 ID（登出/刷新等会话动作）；
  `actor_id/actor_name` 为操作人。
- 用户管理审计（M2-M0-02）：`USER_CREATE`/`USER_UPDATE`/`PASSWORD_RESET` 的 `detail` 只含
  操作名与固定文案，**不含手机号明文、密文、哈希与口令**；越权由 `ACCESS_DENIED` 单独留痕。
- 记录字段：`action/resource_type/resource_id/result(1|0)/detail(jsonb)/ip(inet)/user_agent/trace_id/occurred_at`。
- `detail` 只含白名单字段（操作名、尝试登录账号、**异常类名 + 固定短文案**）；
  异常 message 一律不入审计（防 SQL 实参/内部路径泄漏，S-5）；
  **不记录口令、令牌明文、请求体原文与患者标识**。
- **成功路径 fail-open（S-4）**：审计落库失败时记 ERROR 并累加指标 `auth.audit.failures`，
  响应仍按成功返回（不让审计故障放大为登录 500）；失败路径仍抛原始认证异常。
- **401 采样（S-6）**：同一 IP+路径 1 分钟内最多 1 条 `AUTH_UNAUTHORIZED`，避免扫描刷量；
  401 响应本身不受影响。
- `traceId` 由 `TraceIdFilter` 生成/透传（`X-Trace-Id`），日志 MDC 与审计、错误响应共用。

## 7. 运行时装配

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

## 8. 测试与验证

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
- `ProductionStartupFailureTest`（4）：prod 缺 JWT 密钥 / 缺字段密钥 / 缺 pepper 启动失败、含 demo_* 账号启动失败。
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
- `FieldCryptoKeyProviderTest`（12）：字段密钥/pepper 长度与各自生成命令提示、占位值、低熵、非 Base64、
  key_id 校验、prod 缺失拒绝启动与 dev 自动生成、**field-key 与 pepper 解码后相同拒绝启动（S-8）**。
- `PhoneCryptoTest`（9）：AES-256-GCM 往返、随机 IV、篡改密文拒绝、错误 key_id 拒绝、
  **同 key_id 不同密钥解密失败（S-9）**、脱敏规则。
- `PhoneHasherTest`（5）：HMAC-SHA-256 确定性、不同 pepper 不同哈希、规范化同号同哈希。
- `UserAdminApiIntegrationTest`（11，真实 PG）：创建带手机号后库内无明文且密文可解密、hash 精确查询命中、
  列表/详情仅 `phoneMasked`、改号重加密与清除四列、非法手机号 400、账号大小写不敏感唯一 409、
  **改工号撞唯一键 409（S-9）**、**带格式手机号（`+86`/空格/短横线）检索命中（S-11）**、
  缺 read/write 权限 403 + ACCESS_DENIED 审计、重置口令后旧口令失效且强制改密、
  **重置同事务撤销全部刷新令牌、旧 refresh 401（S-4）**、
  **USER_CREATE/USER_UPDATE/PASSWORD_RESET 审计 resource_id 指向目标用户（S-5）**、
  **修改自身角色与越权授予角色 403 + 审计（S-10）**、审计 detail 不含手机号。
- 全量 162 个测试（api-contracts 4、m0-impl 86、api-bootstrap 72，Docker 用例按 `@Tag("docker")` 排除），
  Checkstyle 0 / SpotBugs 0。
- 覆盖率口径：`UserAdminServiceImpl`（及 `AuthServiceImpl`/`DataScopeQueryServiceImpl`）位于 m0-impl，
  但由 api-bootstrap 的 MockMvc + 真实 PG 集成测试覆盖，因此按 api-bootstrap 的
  `jacoco-aggregate` 报告评估；m0-impl 自身的加密单测覆盖 `PhoneCrypto`/`PhoneHasher`/
  `FieldCryptoKeyProvider` 的主要分支（含篡改、密钥版本不匹配、缺失拒绝启动等失败路径）。
- 集成测试共用嵌入式 PostgreSQL 16（Docker 不可用 EXT-08），不使用 Docker、不使用真实患者数据。

## 9. 遗留与风险

| 项 | 说明 | 归属 |
|---|---|---|
| Redis 会话/吊销表 | 当前进程内吊销表，多实例部署失效；接口已留，替换实现即可 | M1/部署前 |
| SSO / AD / LDAP / CA 签名 | 占位，非 LOCAL 认证返回 422 模糊文案 | BLOCKED-EXTERNAL EXT-05 |
| 数据权限绑定表 | **已完成**（M2-M0-01 / V5）：`sys_org`/`sys_user_org`/`sys_user_room` + scopeIds 解析 | — |
| 组织 CRUD 与术间主数据 | `m0:org:read` 已授权 SYS_ADMIN/SYS_OR_MANAGER、`m0:org:write` 仅 SYS_ADMIN，但组织维护接口未建；术间主数据由 M2-M2-01 在 `m1_sched` 建立 | M2 |
| 数据权限自动注入 | 当前由服务显式校验（示例接口）；MyBatis 拦截器自动注入 WHERE 条件待 M1 排程/记录查询落地 | M1 |
| 并发会话上限 / 强制下线 | 仅按会话撤销刷新令牌，未限制同一账号并发会话数 | M1 |
| phone 写路径 | **已完成**（M2-M0-02 / V6）：AES-256-GCM + HMAC-SHA-256 + key_id/updated_at + 脱敏返回 | — |
| 字段密钥轮换 | 应用只持单把字段密钥，`key_id` 不等于当前值即拒绝解密；**未实现**在线轮换，需 M1 交付 `key_id→key` 映射或离线双密钥重加密工具（二者至少其一） | M1 |
| 手机号唯一性 / 变更通知 | 未做同院手机号去重与变更通知（短信为 EXT-05）；口径【待确认】 | M1 |
| 用户管理乐观锁 | 更新按服务端最后写入 + `version` 自增，未把 `version` 暴露到契约（列表/详情字段按任务卡收敛） | M1 |
| 跨服务 trace 透传 | `X-Trace-Id` 由本服务生成，网关跨服务透传未做 | M1 |
| 契约 drift（springdoc → baseline） | 当前只断言 auth 路径存在，未做 springdoc 全量导出与 baseline diff | M1 前补 |
| 限流集群化 | 内存令牌桶为单实例语义，多实例需 Redis 令牌桶 | M1/部署前 |
| openapi-generator 插件版本 | 7.10.0 暂写在 `api-contracts/pom.xml` | 主会话收敛根 pluginManagement |

> `phone_enc`（密文，供展示前解密）与 `phone_hash`（HMAC-SHA-256，供精确检索/去重）
> 的字段名与口径已在本文件、`docs/db/README.md`、`docs/data-dictionary/core-entities.md` 统一；
> V3 只做 expand 加列，写路径由 M2-M0-02（V6）落地；密钥轮换仍待 M1 离线工具，见第 5 节。

## 10. 字典、消息与文件（M8-M0-04，V27，契约 platform.yaml 0.2.0）

> 本文件第 1–9 节描述认证/权限/用户管理；本节描述 M0 公共支撑补全的三类能力。
> 口径与取舍见 [ADR-0024](../../adr/0024-字典消息文件口径.md)；表结构见
> [docs/db/README.md](../../db/README.md) §4.22；人工核对入口 `apps/api/modules/m0/README.md`。

### 10.1 接口与权限

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/v1/dicts` | `m0:dict:read` | 字典类型列表 + 启用项数量（全院可读） |
| GET | `/v1/dicts/{type}` | `m0:dict:read` | 字典项（默认只返回启用项、按 sort/code 排序）；`includeDisabled=true` 需 `m0:dict:write`，否则 403 |
| POST | `/v1/dicts/{type}/items` | `m0:dict:write` + `SYS_ADMIN` | 新增字典项；同类型编码重复 409 |
| PUT | `/v1/dicts/{type}/items/{itemId}` | `m0:dict:write` + `SYS_ADMIN` | 更新名称/排序/启停/备注；**编码不可改** |
| DELETE | `/v1/dicts/{type}/items/{itemId}` | `m0:dict:write` + `SYS_ADMIN` | 逻辑停用（幂等，204），不物理删除 |
| GET | `/v1/messages` | `m0:message:read` | 当前用户消息分页（`page/size/unreadOnly`）+ 未读总数 |
| POST | `/v1/messages/{messageId}/read` | `m0:message:read` | 标记单条已读（幂等）；非本人 404 |
| POST | `/v1/messages/read-all` | `m0:message:read` | 标记本人全部未读，返回更新条数 |
| POST | `/v1/files` | `m0:file:write` | JSON + Base64 上传（类型/大小白名单；不持久化原始文件名） |
| GET | `/v1/files` | `m0:file:read` | 我的文件分页（只返回当前用户上传） |
| GET | `/v1/files/{fileId}` | `m0:file:read` + 可见性 | 鉴权下载（`X-Content-Sha256`/`nosniff`） |
| GET | `/v1/files/{fileId}/meta` | `m0:file:read` + 可见性 | 元数据（不含内容与存储路径） |

错误码：`PARAM_INVALID`(400，schema/字段/BusinessId 模式/Base64)、`AUTH_UNAUTHORIZED`(401)、
`AUTH_FORBIDDEN`(403，缺权限点)、`RESOURCE_NOT_FOUND`(404，不存在/已删除/业务对象超范围)、
`STATE_CONFLICT`(409，字典编码重复/并发更新)、`BUSINESS_RULE_VIOLATION`(422，解码后超
20 MiB/声明大小不一致)。

### 10.2 口径

- **字典**：全院参考数据，读不按数据范围裁剪；维护仅 `SYS_ADMIN`（V27 仅授予该角色，服务层
  再校验角色）。`code` 创建后不可改；停用为 `status=0`（接口 `enabled=false`），历史记录按当时
  编码解释。`remark` 只放口径说明，禁止患者标识与临床自由文本。
- **消息**：`recipient_user_id = 当前用户` 强制隔离；非本人与不存在统一 404。**没有**任意发送
  端点，消息只能由领域事件/系统广播经 `MessagePublisher` 写入；`MessageTemplates` 注册表决定
  `code → 类型/固定标题/允许参数键`，**调用方不提交标题**，未登记编码/类型不匹配/参数越界一律
  拒绝（S-4）。参数值 ≤200 字符且不含控制字符，**禁止 PHI**。已读幂等，未读角标 `unreadCount`
  恒为全部未读数；侧边菜单角标与消息中心共享 `useMessageStore`。
- **文件**：白名单 `application/pdf`、`text/csv`、`text/plain`、`image/png`、`image/jpeg`，
  ≤20 MiB；服务端计算 SHA-256 并按内容寻址落盘（同内容只存一份，元数据每次上传独立）。
  **不持久化客户端原始文件名**：`sys_file` 无文件名列，展示名服务端生成
  `file-<fileId>.<ext>`；审计 detail 不含 fileName/businessId（B-2）。`businessId` 只接受 UUID
  或大写业务号，拒绝自由文本。
  下载可见性 = 功能权限 + 业务对象数据范围：上传者 / `SYS_ADMIN` / 业务类型对应模块只读权限
  （`REPORT`→`m4:report:read`、`SCHEDULE`→`m1:schedule:read`、`RECORD`→`m2:record:read`），
  且 `SCHEDULE`/`RECORD` 还需 m1/m2 的 DataScope 可见；`TEMPLATE` 对持 `m0:file:read` 者公开，
  `ATTACHMENT` 仅上传者与 `SYS_ADMIN`。缺模块权限 403；业务对象不存在/超出数据范围、文件
  不存在 404（防存在性泄露）；感染 403。`REPORT` 当前 fail-closed（Q-24）。

### 10.3 存储适配器与 PHI 禁令（EXT-06 / EXT-07）

- `FileStorage` 接口 + `LocalFileStorage` 本地 FS 实现：目录 `anesop.m0.file.root-dir`
  （默认仓库内 `.cache/files`，已 gitignore；生产挂载盘），大小上限 `anesop.m0.file.max-size-bytes`
  （默认且最大 20 MiB，配置更大时夹紧）。
- **MinIO 适配器仅为接口占位**：`MinioFileStorage` 全部方法抛 `UnsupportedOperationException`，
  **EXT-06**（Docker/MinIO 当前不可用）。compose 中的 `minio` 服务与 `miniodata` 卷是未来切换
  预留，不得据此宣称已接入（Q-23）。接入时用 `@ConditionalOnProperty` 选择实现，`FileService`
  不改；分片/预签名下载需先升契约版本。
- **部署（S-1）**：compose `api` 注入 `ANESOP_M0_FILE_ROOT_DIR=/var/lib/anesop/files` 并挂载
  `m0filedata` 持久卷；`scripts/compose-check.sh` 静态断言两者。
- **病毒扫描占位**：`scan_status` 当前固定 `NOT_SCANNED`（**EXT-07**）；接入前不得上传外部
  来源文件，`INFECTED` 一律拒绝下载。
- **不得存 PHI**：禁止临床影像（DICOM/视频）、身份证明（身份证/护照/驾驶证扫描）、含患者
  标识的表格与截图。上传方为 PHI 合规第一责任人；违规按安全事件流程处置。

### 10.4 审计与前端

- 审计动作：`DICT_ITEM_CREATE`/`DICT_ITEM_UPDATE`/`DICT_ITEM_DISABLE`、
  `MESSAGE_READ`/`MESSAGE_READ_ALL`、`FILE_UPLOAD`/`FILE_DOWNLOAD`；detail 只含字典类型/编码、
  消息类型/模板编码、文件业务类型/大小/摘要前 12 位，**不含 fileName/businessId、文件内容与
  患者标识**。
- 前端：`/m0/dicts`（字典管理，`m0:dict:write`）、`/m0/messages`（消息中心 + 侧边菜单未读角标）、
  `/m0/files`（我的文件列表 + 上传/下载示例页）；上传组件 `FileUploadPanel` 以
  `business-type`/`business-id` 在排程/记录页复用（示例见 `FileCenterView`）。
- 测试：`PlatformDictServiceTest`/`PlatformMessageServiceTest`/`PlatformFileServiceTest`/
  `PlatformStorageTest`（m0-impl，嵌入式 PG + 本地 FS 临时目录）、
  `FileBusinessVisibilityAdapterTest`（m1/m2 可见性 fail-closed）、`M0PlatformApiIntegrationTest`
  （MockMvc 全端点、权限 403、跨范围/不存在附件 404、审计 detail 无 fileName/businessId、
  schema 400、语义 422）、前端 Vitest（字典/消息/上传/客户端/我的文件/导航角标）、
  E2E `apps/web/e2e/m0-messages.spec.ts`。

## 附：`@SuppressFBWarnings` 的来源说明

为避免引入 LGPL-2.1 的 `spotbugs-annotations` 依赖（AGENTS 要求 LGPL 先有法务结论），
仓库在 `apps/api/modules/m0/api/src/main/java/edu/umd/cs/findbugs/annotations/` 下提供了
**同 FQN、CLASS retention、无运行时依赖**的最小注解，仅用于 SpotBugs 识别字段/构造器注入的
`EI_EXPOSE_REP2` 误报；不使用它改变任何运行时语义。M1 评估改为官方依赖（需法务）或改用
`@SuppressWarnings` 不可行时保留并登记 allowlist。该做法已记录于 docs/deps/allowlist.md。
