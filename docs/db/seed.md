# 种子数据与演示账号（SYNTHETIC，dev / demo only）

> **全部为 SYNTHETIC（合成/演示）数据，禁止用于生产真实账号。**
>
> 种子分两层：
>
> 1. **平台预置基线**：`V2`（6 角色、17 权限、42 条授权、6 类 22 项字典）随生产迁移执行，
>    `V3`/`V5`/`V6` 再追加 6 个 M0 权限点与 7 条授权（当前 23 权限 / 49 条授权），
>    **不创建任何用户账号**；
> 2. **演示账号**：`db/seed/demo/V100__demo_admin_synthetic.sql`，位于**独立 Flyway
>    location，仅 dev/test 显式启用**，生产 locations 不得包含该目录。
>
> 生产账号由院方 AD/LDAP/CAS 或实施管理员创建（S0-07），不得来自演示种子。

## 1. Flyway locations 与生产红线

| 环境 | `spring.flyway.locations` | 内容 |
|---|---|---|
| 生产 / staging / uat | `filesystem:/opt/anesop/db/migration` | 仅 V1–V6；`sys_user` 为空 |
| 本地 / 演示 / 集成测试 | `filesystem:db/migration,filesystem:db/seed/demo` | V1–V6 + V100（demo_admin/demo_dept/demo_self） |

- **禁止**把 `db/seed/demo` 加入生产、staging、uat 的 locations；生产挂载目录只读，
  不含 `db/seed/`（见 `docs/db/README.md` §5.3）。
- V100 依赖 V2 已创建的 `SYS_ADMIN` 角色；独立 location 内版本号只保证排在 V1/V2 之后。
- 演示库若日后切回生产 locations，Flyway 会报告“已应用但本地未解析的 V100”，
  这是有意的安全信号：演示库不得直接当生产库使用，须重新初始化并核对数据。

## 2. 演示账号

| 用户名 | 初始口令 | 姓名 | 工号 | 角色 | 数据范围 | 组织/术间绑定 | 状态 | 首次登录改密 |
|---|---|---|---|---|---|---|---|---|
| `demo_admin` | `Anesop@2026` | 演示管理员（合成） | `SYN-DEMO-0001` | `SYS_ADMIN` | `ALL` | 院区 `SITE-01`（ALL 不消费） | 启用 | **必须** |
| `demo_dept` | `Anesop@2026` | 演示科室管理员（合成） | `SYN-DEMO-0002` | `SYS_OR_MANAGER` | `DEPT` | 麻醉科 `DEPT-ANES`（主科室） | 启用 | **必须** |
| `demo_self` | `Anesop@2026` | 演示麻醉医师（合成） | `SYN-DEMO-0003` | `SYS_ANESTHESIOLOGIST` | `SELF` | 麻醉科（SELF 不消费，不放大范围）+ 术间 `f2000000-...0001` | 启用 | **必须** |

- 固定 UUID：`demo_admin`=`e0000000-...000000000001`、`demo_dept`=`...000000000002`、
  `demo_self`=`...000000000003`；角色 UUID：`SYS_ADMIN`=`a0000000-...000000000001`、
  `SYS_OR_MANAGER`=`a0000000-...000000000005`、`SYS_ANESTHESIOLOGIST`=`a0000000-...000000000002`。
- 组织（`sys_org`）：`SITE-01`=`f1000000-...000000000001`、`DEPT-ANES`=`...000000000002`、
  `DEPT-OR`=`...000000000003`；绑定见 V100 的 `sys_user_org` / `sys_user_room`。
- 三个演示账号的 `must_change_password` 均为 `true`，首次登录强制改密。
- 口令哈希：BCrypt，cost=10，`$2y$` 前缀，长度 60；由以下命令生成：
  ```bash
  htpasswd -bnBC 10 "" 'Anesop@2026' | tr -d ':\n'
  ```
- 哈希只用于本地/演示登录，禁止在工单、日志、截图中粘贴口令或哈希。
- 首次登录后必须修改口令；演示环境也不得长期保留默认口令。
- `phone_enc`/`phone_hash`/`phone_key_id`/`phone_updated_at` 均为空：演示账号不写入手机号，
  避免伪造密文被误认为真实数据。M2-M0-02 的手机号用例只在**运行时**由集成测试创建
  （如 `13800005678` 之类明显合成的号码），并且写入前经 `PhoneCrypto`/`PhoneHasher` 处理，
  库内只有密文与哈希；任何文档、脚本、种子都不得出现真实手机号。

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

## 4. 权限点（23 个：V2 17 + V3 2 + V5 2 + V6 2）

- 基础：`auth:login`、`user:read`、`user:write`、`role:read`、`role:write`、
  `audit:read`、`dict:read`、`dict:write`
- 模块占位：`m0:access` … `m8:access`（S0 阶段仅占位，后续按模块细化到菜单/按钮级）
- M0 认证（V3）：`m0:auth:read`、`m0:auth:write`（仅 `SYS_ADMIN`）
- M0 组织（V5）：`m0:org:read`、`m0:org:write`；`m0:org:read` 授予 `SYS_ADMIN` 与
  `SYS_OR_MANAGER`（数据范围示例接口需要该功能权限），`m0:org:write` 仅 `SYS_ADMIN`
- M0 用户管理（V6，M2-M0-02）：`m0:user:read`（列表/详情）、`m0:user:write`（创建/更新/重置口令），
  仅授予 `SYS_ADMIN`；V2 历史 `user:read`/`user:write` **保留不动**（兼容期，M1 权限码收敛后再删）

授权关系 49 条：`SYS_ADMIN` 拥有全部权限；其余角色按岗位最小权限授予登录 +
职责模块访问。详细映射见 V2/V3/V5/V6 脚本授权段。

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
- 演示账号共三个（admin/dept/self），且均强制改密；
- 若需扩充演示数据，必须继续使用固定 UUID + `SYNTHETIC` 标记，并更新本文件；
- CI 断言两条路径（`FlywayMigrationTest` / `DemoSeedMigrationTest`）：
  - 生产路径：`sys_user=0`、`sys_user_role=0`，无 `demo_admin`；`sys_role=6`、
    `sys_permission=21`、`sys_role_permission=47`、`sys_dict_item=22`、`sys_org=0`、
    `sys_user_org=0`、`sys_user_room=0`；
  - 演示路径：在基线之上 `sys_user=3`、`sys_user_role=3`、`sys_org=3`、`sys_user_org=3`、
    `sys_user_room=1`；`demo_dept` 绑定麻醉科（`SYS_OR_MANAGER`），`demo_self` 为 `SELF`，
    三个账号均 `must_change_password=true`，BCrypt 校验 `Anesop@2026` 通过。
