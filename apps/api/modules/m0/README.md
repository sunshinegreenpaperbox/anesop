# m0 — 公共支撑

**一句话职责**：统一认证与 SSO、RBAC 与数据权限、组织与字典、工作流与消息、审计日志、报表引擎与打印模板、CA 签名、接口平台与监控、文件存储、时间同步。

## 数据归属

- schema：`m0_platform`（本模块独占；其他模块只能经 `m0-api` 访问，禁止直连本 schema 的表）
- 迁移：`db/migration/V1__m0_platform_init.sql`（结构）、`V2__m0_platform_seed_synthetic.sql`（平台预置基线，无账号）、
  `V3__m0_auth_token.sql`（刷新令牌表 + `m0:auth:read`/`m0:auth:write` 权限点 + `sys_user.phone_hash`，S0-07）、
  `V27__m0_dict_message_file.sql`（`sys_message`/`sys_file` + 字典读索引 + 字典/消息/文件权限点，M8-M0-04）
- 演示种子：`db/seed/demo/V100__demo_admin_synthetic.sql`（仅 dev/test 独立 location）
- 规范与运行方式：`docs/db/README.md`；演示账号：`docs/db/seed.md`

## 表（V1）

| 表 | 说明 |
|---|---|
| `sys_user` | 用户、口令哈希、`phone_enc`/`phone_hash`、强制改密、失败锁定、软删除、乐观锁 |
| `sys_role` | 角色 + 数据范围（ALL/SITE/DEPT/ROOM/SELF） |
| `sys_permission` | 菜单/API/按钮权限点，树形 `parent_id` |
| `sys_user_role` / `sys_role_permission` | 多对多关联，复合主键 + FK + 反向索引 |
| `sys_audit_log` | 只追加审计；触发器拒绝 UPDATE/DELETE/TRUNCATE；留存 ≥ 6 个月 |
| `sys_dict_type` / `sys_dict_item` | 字典；同类型内 code 唯一 |
| `sys_refresh_token` | 刷新令牌（SHA-256 哈希、有效期、撤销时间、客户端信息）；V3 |
| `sys_message` | 站内消息（收件人隔离、模板编码 + jsonb 参数白名单、read_at）；V27 |
| `sys_file` | 文件元数据（内容寻址 storage_key、大小/类型白名单、SHA-256、业务关联、逻辑删除）；V27 |

## 目录与结构

```text
m0/
├── api/    # artifactId=m0-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m0-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
    └── src/main/java/com/anesop/m0/impl/persistence/{entity,mapper,handler}
    └── src/test/java/com/anesop/m0/impl/migration/   # 嵌入式 PG16 + Flyway/MyBatis 集成测试
```

## 依赖边界

- 允许：`m0-impl` → `m0-api`；其他模块 → `m0-api`；
- 禁止：任何模块 → `m0-impl`；`m0-impl` → 其他模块 `impl`；模块间循环依赖。

## 如何运行

本模块不单独启动，由 `api-bootstrap` 统一装配：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
export ANESOP_DB_URL=jdbc:postgresql://127.0.0.1:5432/anesop
export ANESOP_DB_USER=anesop ANESOP_DB_PASSWORD=anesop
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
```

`dev` profile 加载 `db/migration` + `db/seed/demo` 并允许进程内 JWT 密钥；`prod` 只加载
`db/migration`、关闭 springdoc，缺失 `ANESOP_JWT_SECRET` 或存在 `demo_*` 账号时拒绝启动。
运行需 PostgreSQL 16（Docker 不可用，EXT-08）。联调步骤见
[`docs/guides/demo.md`](../../../../docs/guides/demo.md)。

## 如何测试

```bash
source scripts/env.sh
./scripts/mvn.sh -B -pl apps/api/modules/m0/impl -am test
```

- `FlywayMigrationTest`：生产路径（只含 `db/migration`）空库迁移、表/约束/索引/注释、
  timestamptz、幂等、平台预置种子、`sys_user=0`、审计 append-only（SQLState 55000）。
- `DemoSeedMigrationTest`：演示路径追加 `db/seed/demo`，`demo_admin` 存在、绑定
  `SYS_ADMIN`、强制改密、BCrypt 校验通过。
- `ConstraintNegativeTest`：登录名大小写不敏感唯一、状态 CHECK、工号部分唯一（S-8）。
- `MybatisPersistenceTest`：jsonb/inet TypeHandler 往返、审计 Mapper 仍拒绝改写（S-3）、
  软删除与乐观锁（S-4）。
- `MigrationUpgradePathTest`：V1 → V2 升级、history 递增、命名/版本连续且无 undo 脚本。
- Docker 不可用（EXT-08），集成测试使用 Zonky 嵌入式 PostgreSQL 16；Testcontainers 仅保留 BOM。

## 关键契约

- 本域契约：`packages/contracts/openapi/auth.yaml`（登录/刷新/登出/改密/当前用户/会话）；
  服务端实现以契约为准，operationId 与 `docs/modules/m0/README.md` 一致。
- 跨模块能力经 `m0-api`：`AuthPrincipal`、`AuditWriter`、`DataScopeResolver` 等；其他模块
  不得依赖 `m0-impl`、不得直连 `m0_platform` 表。
- 权限码：`<模块>:<资源>:<动作>`（如 `m0:auth:read`），授权表达式加 `PERM_` 前缀；
  角色码加 `ROLE_` 前缀。角色 R1–R11 映射见 [`docs/modules/m0/README.md`](../../../../docs/modules/m0/README.md)。
- 契约变更走 [`docs/guides/dev-workflow.md`](../../../../docs/guides/dev-workflow.md) 的门禁：
  spectral + oasdiff + 生成物漂移 + 双人评审（权限/审计属核心域）。

## 负责人（占位）

- Owner：`@anesop/m0-platform-security`、`@anesop/security-review`（双人评审；CODEOWNERS 占位团队）
- 备份：`@anesop/maintainers`
- 状态：S0-06/07 已交付认证、RBAC、审计与迁移；后续按 M0 任务扩展字典/消息/文件等能力。

## 状态

- S0-02：空模块占位（`package-info.java`）已完成。
- S0-06：V1/V2 迁移、演示种子独立 location、6 个实体、6 个 Mapper、
  uuid/jsonb/inet TypeHandler、迁移与持久层集成测试完成。
- S0-07（已完成）：`AuthService` 实现（登录/刷新轮换/登出/当前用户/会话分页）、
  `AccessTokenIssuer` 适配（JWT 由 api-bootstrap 签发）、刷新令牌 SHA-256 落库与撤销、
  BCrypt 口令校验与原子失败锁定、强制改密、口令修改、`DataScopeResolver`（读 data_scope，
  fail-closed）、`@Audited` 审计切面与 `AuditWriter`（接口在 m0-api、实现在 impl）、
  `api-bootstrap` 装配 Security/JWT/MyBatis（`MapperScan`、`UuidTypeHandler`、乐观锁）、
  Flyway 运行时与 prod 启动断言。接口与权限点详见 `docs/modules/m0/README.md`。
  注意：实体主键为 `UUID` + `IdType.INPUT`，插入前由应用层赋值
  （MyBatis-Plus `ASSIGN_UUID` 只支持 String 主键；DB `gen_random_uuid()` 仅兜底直连 SQL）。

## 字典 / 消息 / 文件（M8-M0-04）

| 层 | 类 | 说明 |
|---|---|---|
| api | `com.anesop.m0.api.platform.DictService` | 全院读、管理员写、编码不可变、逻辑停用 |
| api | `MessageService` / `MessagePublisher` | 消息仅本人；写入只走内部发布器（无任意发送端点） |
| api | `MessageTemplates` | 模板注册表：code → 类型/固定标题/参数白名单，服务端渲染标题 |
| api | `FileService` / `FileStorage` / `FileDownload` | 文件类型/大小白名单、内容寻址、可见性下载、我的文件 |
| api | `FileBusinessVisibilityPort` | 业务对象数据范围端口（m1/m2 适配由 api-bootstrap 装配） |
| impl | `com.anesop.m0.impl.platform.*` | 三个服务 + `LocalFileStorage` + `MinioFileStorage`（EXT-06 占位） |
| impl | `com.anesop.m0.impl.config.FileProperties` | `anesop.m0.file.root-dir`（默认 `.cache/files`）、`max-size-bytes`（≤20 MiB） |
| web | `DictController`/`MessageController`/`FileController` | 位于 `api-bootstrap`，权限点 + 异常映射 |

- 口径：见 [`docs/modules/m0/README.md`](../../../../docs/modules/m0/README.md) §10 与 ADR-0024。
- 审计：`DICT_ITEM_CREATE/UPDATE/DISABLE`、`MESSAGE_READ/READ_ALL`、`FILE_UPLOAD/DOWNLOAD`。
- 测试：`./scripts/mvn.sh -B -f apps/api/pom.xml -pl modules/m0/impl -am test`
  （`Platform*ServiceTest`）、`-pl api-bootstrap -am -Dtest=M0PlatformApiIntegrationTest test`。
- 禁止：在本模块存 PHI；把消息当通用通知通道；绕过 `FileStorage` 直接写盘。
