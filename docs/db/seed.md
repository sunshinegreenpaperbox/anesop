# 种子数据与演示账号（SYNTHETIC，dev / demo only）

> **全部为 SYNTHETIC（合成/演示）数据，禁止用于生产真实账号。**
>
> 种子分两层：
>
> 1. **平台预置基线**：`db/migration/V2__m0_platform_seed_synthetic.sql`，随生产迁移执行，
>    只含 6 角色、17 权限、42 条授权、6 类 22 项字典，**不创建任何用户账号**；
> 2. **演示账号**：`db/seed/demo/V100__demo_admin_synthetic.sql`，位于**独立 Flyway
>    location，仅 dev/test 显式启用**，生产 locations 不得包含该目录。
>
> 生产账号由院方 AD/LDAP/CAS 或实施管理员创建（S0-07），不得来自演示种子。

## 1. Flyway locations 与生产红线

| 环境 | `spring.flyway.locations` | 内容 |
|---|---|---|
| 生产 / staging / uat | `filesystem:/opt/anesop/db/migration` | 仅 V1、V2；`sys_user` 为空 |
| 本地 / 演示 / 集成测试 | `filesystem:db/migration,filesystem:db/seed/demo` | V1、V2、V100（demo_admin） |

- **禁止**把 `db/seed/demo` 加入生产、staging、uat 的 locations；生产挂载目录只读，
  不含 `db/seed/`（见 `docs/db/README.md` §5.3）。
- V100 依赖 V2 已创建的 `SYS_ADMIN` 角色；独立 location 内版本号只保证排在 V1/V2 之后。
- 演示库若日后切回生产 locations，Flyway 会报告“已应用但本地未解析的 V100”，
  这是有意的安全信号：演示库不得直接当生产库使用，须重新初始化并核对数据。

## 2. 演示账号

| 用户名 | 初始口令 | 姓名 | 工号 | 角色 | 状态 | 首次登录改密 |
|---|---|---|---|---|---|---|
| `demo_admin` | `Anesop@2026` | 演示管理员（合成） | `SYN-DEMO-0001` | `SYS_ADMIN` | 启用 | **必须**（`must_change_password=true`） |

- 固定 UUID：`e0000000-0000-4000-8000-000000000001`；角色 UUID：`a0000000-...-000000000001`。
- 口令哈希：BCrypt，cost=10，`$2y$` 前缀，长度 60；由以下命令生成：
  ```bash
  htpasswd -bnBC 10 "" 'Anesop@2026' | tr -d ':\n'
  ```
- 哈希只用于本地/演示登录，禁止在工单、日志、截图中粘贴口令或哈希。
- 首次登录后必须修改口令；演示环境也不得长期保留默认口令。
- `phone_enc` 为空：演示账号不写入手机号，避免伪造密文被误认为真实数据。

## 3. 平台预置角色（6 个，V2）

| 编码 | 名称 | 数据范围 | 说明 |
|---|---|---|---|
| `SYS_ADMIN` | 系统管理员 | `ALL` | 平台全部权限（演示） |
| `SYS_ANESTHESIOLOGIST` | 麻醉医师 | `SELF` | 排程、麻醉记录、麻精用药、质控查看 |
| `SYS_NURSE_ANESTHETIST` | 麻醉护士 | `SELF` | 麻醉记录、耗材与麻精核对 |
| `SYS_SURGEON` | 外科医师 | `SELF` | 手术申请/排程查看、记录只读 |
| `SYS_OR_MANAGER` | 手术室管理员 | `DEPT` | 术间排程、工作量、驾驶舱 |
| `SYS_QC_MANAGER` | 质控管理员 | `ALL` | 质控指标、不良事件、驾驶舱 |

数据范围取值见 `sys_role.data_scope`：`ALL`/`SITE`/`DEPT`/`ROOM`/`SELF`（§3.9.2）。

## 4. 权限点（17 个，V2）

- 基础：`auth:login`、`user:read`、`user:write`、`role:read`、`role:write`、
  `audit:read`、`dict:read`、`dict:write`
- 模块占位：`m0:access` … `m8:access`（S0 阶段仅占位，后续按模块细化到菜单/按钮级）

授权关系 42 条：`SYS_ADMIN` 拥有全部 17 个；其余角色按岗位最小权限授予登录 +
职责模块访问。详细映射见 V2 脚本第 3 节。

## 5. 字典（6 类 / 22 项，V2）

| 字典类型 | 名称 | 项数 | 示例 |
|---|---|---|---|
| `SEX` | 性别 | 3 | 男 / 女 / 未知 |
| `ASA` | ASA 分级 | 6 | ASA I – ASA VI |
| `SURGERY_URGENCY` | 手术紧急度 | 3 | 择期 / 限期 / 急诊 |
| `OR_ROOM_STATUS` | 术间状态 | 4 | 可用 / 使用中 / 清洁消毒 / 维护 |
| `CASE_STATUS` | 病例状态 | 4 | 已排程 / 进行中 / 已完成 / 已取消 |
| `ROLE_TYPE` | 角色类型 | 2 | 系统预置 / 业务角色 |

字典项停用不删除，保证历史记录按当时版本解释（§2.4.1）。

## 6. 合成声明与检查

- 所有姓名、工号、账号均带“演示/合成/SYN-”标识；
- 不含任何真实患者、员工、手机号、身份证、住院号；
- 演示账号只有一个，且强制改密；
- 若需扩充演示数据，必须继续使用固定 UUID + `SYNTHETIC` 标记，并更新本文件；
- CI 断言两条路径（`FlywayMigrationTest` / `DemoSeedMigrationTest`）：
  - 生产路径：`sys_user=0`、`sys_user_role=0`，无 `demo_admin`；`sys_role=6`、
    `sys_permission=17`、`sys_role_permission=42`、`sys_dict_item=22`；
  - 演示路径：在基线之上 `sys_user=1`、`sys_user_role=1`，`demo_admin` 绑定
    `SYS_ADMIN`，`must_change_password=true`，BCrypt 校验 `Anesop@2026` 通过。
