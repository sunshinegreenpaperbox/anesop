# m0 — 公共支撑

**一句话职责**：统一认证与 SSO、RBAC 与数据权限、组织与字典、工作流与消息、审计日志、报表引擎与打印模板、CA 签名、接口平台与监控、文件存储、时间同步。

## 数据归属

- schema：`m0_platform`（本模块独占；其他模块只能经 `m0-api` 访问，禁止直连本 schema 的表）
- 迁移：`db/migration/V1__m0_platform_init.sql`（结构）、`V2__m0_platform_seed_synthetic.sql`（平台预置基线，无账号）
- 演示种子：`db/seed/demo/V100__demo_admin_synthetic.sql`（仅 dev/test 独立 location）
- 规范与运行方式：`docs/db/README.md`；演示账号：`docs/db/seed.md`

## 表（V1）

| 表 | 说明 |
|---|---|
| `sys_user` | 用户、口令哈希、失败锁定、软删除、乐观锁 |
| `sys_role` | 角色 + 数据范围（ALL/SITE/DEPT/ROOM/SELF） |
| `sys_permission` | 菜单/API/按钮权限点，树形 `parent_id` |
| `sys_user_role` / `sys_role_permission` | 多对多关联，复合主键 + FK + 反向索引 |
| `sys_audit_log` | 只追加审计；触发器拒绝 UPDATE/DELETE/TRUNCATE；留存 ≥ 6 个月 |
| `sys_dict_type` / `sys_dict_item` | 字典；同类型内 code 唯一 |

## 结构

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

## 测试与运行

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

## 状态

- S0-02：空模块占位（`package-info.java`）已完成。
- S0-06：V1/V2 迁移、演示种子独立 location、6 个实体、6 个 Mapper、
  uuid/jsonb/inet TypeHandler、迁移与持久层集成测试完成。
- S0-07 待办：认证服务、RBAC/数据权限拦截器、审计写入服务、`@Version` 与
  审计字段自动填充、`api-bootstrap` 运行时装配（本任务不写控制器/认证逻辑）；
  MyBatis 配置需注册 `UuidTypeHandler`（全局）并按实体 `@TableField` 生效 jsonb/inet。
  注意：实体主键为 `UUID` + `IdType.INPUT`，插入前由应用层赋值
  （MyBatis-Plus `ASSIGN_UUID` 只支持 String 主键；DB `gen_random_uuid()` 仅兜底直连 SQL）。
