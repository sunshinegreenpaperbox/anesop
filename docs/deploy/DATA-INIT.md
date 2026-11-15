# ANESOP 数据初始化手册（生产基线 / 演示数据隔离）

> 依据：方案书 §3.4/§3.6（数据与字典）、`AGENTS.md`「数据闸门」「上线闸门」、
> `docs/db/seed.md`（种子分层与演示账号）、`db/migration/README.md`（迁移纪律）。
> 工具：`scripts/init-data.sh`（M6-M20-02）；演示脚本仍可用 `scripts/seed-demo.sh`。
> 关联：[`DEPLOYMENT.md`](DEPLOYMENT.md)（发布步骤）、[`GO-LIVE-CHECKLIST.md`](GO-LIVE-CHECKLIST.md)
> （数据项）、[`../db/README.md`](../db/README.md)（迁移规范）。

## 1. 数据分层（先分清，再初始化）

| 层 | 内容 | 位置 | 生产 | 演示/开发 |
|---|---|---|---|---|
| 结构 | 表/索引/触发器/约束（V1–V26） | `db/migration/` | ✅ 必须 | ✅ 必须 |
| 平台基线 | 角色、权限、角色授权、字典类型/字典项 | `db/migration/V2`、V3/V5/V6… | ✅ 随迁移 | ✅ 随迁移 |
| 组织/术间/人员 | 院区、科室、术间、人员档案、数据范围绑定 | 由实施按变更单导入（见 §4） | ✅ 必做 | 演示种子提供最小样例 |
| 演示数据 | `demo_admin`/`demo_dept`/`demo_self`、演示药品 | `db/seed/demo/`（V100/V101） | ❌ **禁止** | ✅ 仅演示库 |
| 真实患者数据 | 姓名/住院号/手机号/临床文书 | 院方业务库 | 由业务产生，**不进测试/演示** | ❌ 禁止 |

关键红线：

- 生产 Flyway locations **只允许** `filesystem:/opt/anesop/db/migration`；
  加入 `db/seed/demo` 会在生产创建高权限演示账号，属数据闸门违规。
- 生产首次迁移后 `sys_user = 0` 是预期；管理员按 §4.3 单独创建，不得复制演示种子。
- 所有演示/测试数据必须是 `SYNTHETIC`；禁止把真实患者数据导入 dev/staging/uat。

## 2. 生产初始化（`--mode prod`）

### 2.1 前置

1. `release.yml` 变更单已审批，院方信息科/DBA 在场，数据库已备份且**可读**；
2. PostgreSQL 16 可达；连接账号具备 DDL/DML 权限（迁移期），迁移后可收紧；
3. 环境变量：`SPRING_PROFILES_ACTIVE` 不得为 `dev`/`test`；库名不得含 `demo/dev/test/local`；
4. 生产包**不含** `db/seed/demo`（用 §4.2 打包时已排除）。

### 2.2 干跑与执行

```bash
cd /opt/anesop
source scripts/env.sh

# 只看计划：不连库、不跑 Maven
bash scripts/init-data.sh --dry-run

# 实际执行：必须 --confirm-prod（生产发布还须 release.yml + 人工签字）
bash scripts/init-data.sh --mode prod \
  --url "jdbc:postgresql://<pg-host>:5432/<prod-db>" \
  --user anesop --confirm-prod
```

脚本行为：

- 解析 `api-bootstrap` 运行时 classpath，用 Flyway Java API 只加载 `db/migration`；
- 幂等：重复执行只做校验（`本次执行迁移数 0`），不重复插入；
- 迁移后核对：角色 ≥6、权限 ≥17、字典类型 ≥6、字典项 ≥22；`demo_*` 账号必须为 0；
- `sys_org=0` 会 WARN（组织/术间需 §4 导入），但不阻断结构初始化。

退出码：`0` 成功；`2` 参数/安全拒绝；`3` 工具链缺失；`4` 连不上库；
`5` Maven classpath；`6` 迁移失败；`7` 基线核对失败。

### 2.3 迁移后核对（只读）

```bash
PGPASSWORD=*** psql "postgresql://<pg-host>:5432/<prod-db>" -v ON_ERROR_STOP=1 <<'SQL'
\echo '== Flyway =='
select version, description, success from flyway_schema_history order by installed_rank desc limit 5;
\echo '== 平台基线 =='
select (select count(*) from m0_platform.sys_role)       as roles,
       (select count(*) from m0_platform.sys_permission) as permissions,
       (select count(*) from m0_platform.sys_dict_type)  as dict_types,
       (select count(*) from m0_platform.sys_dict_item)  as dict_items,
       (select count(*) from m0_platform.sys_org)        as orgs,
       (select count(*) from m0_platform.sys_user)       as users;
\echo '== 禁止项：必须为 0 =='
select count(*) as demo_users from m0_platform.sys_user where username like 'demo\_%' escape '\';
\echo '== 审计表可用 =='
select count(*) as audit_rows from m0_platform.sys_audit_log;
SQL
```

期望：`demo_users=0`；角色/权限/字典达到基线；`users=0`（首次）；`audit_rows=0`。

## 3. 演示初始化（`--mode demo`，SYNTHETIC）

```bash
# 独立演示库，库名建议含 demo；不要指向生产库
bash scripts/init-data.sh --mode demo \
  --url jdbc:postgresql://127.0.0.1:5432/anesop_demo \
  --user anesop
```

- 实际委托 `scripts/seed-demo.sh`，locations = `db/migration` + `db/seed/demo`；
- 创建 `demo_admin`/`demo_dept`/`demo_self` 与演示药品（全部 SYNTHETIC）；
  初始口令见 [`../db/seed.md`](../db/seed.md)，**首次登录强制改密**；
- 演示库不得直接改配置当生产库：Flyway 会报告"已应用但本地未解析的 V100"，
  这是安全信号，应重新初始化生产库并核对数据；
- 演示账号不得进入生产；生产启动守卫会拒绝 `demo_*`。

## 4. 生产组织/术间/人员与首个管理员（人工变更单）

> 这些是**院方特有主数据**，不能由通用种子生成，也不能从演示库复制。
> 由实施管理员在变更单下执行，SQL 先评审、先备份、可回滚（逻辑删除/停用，不物理删除）。

### 4.1 组织（`m0_platform.sys_org`）

按院区（SITE）→ 科室（DEPT）建树，`path` 以 `/` 开头且与编码层级一致：

```sql
BEGIN;
INSERT INTO m0_platform.sys_org (id, code, name, org_type, parent_id, path, sort, status)
VALUES
  ('<site-uuid>', 'SITE-01', '<院区名称>', 'SITE', NULL, '/SITE-01', 0, 1),
  ('<dept-uuid>', 'DEPT-ANES', '<麻醉科>', 'DEPT', '<site-uuid>', '/SITE-01/DEPT-ANES', 0, 1),
  ('<or-dept-uuid>', 'DEPT-OR', '<手术室>', 'DEPT', '<site-uuid>', '/SITE-01/DEPT-OR', 1, 1);
COMMIT;
```

核对：`select code, org_type, path, status from m0_platform.sys_org order by path;`

### 4.2 术间（`m1_sched.operating_room`）

```sql
BEGIN;
INSERT INTO m1_sched.operating_room
  (id, room_no, name, room_type, dept_name, dept_id, site_id)
VALUES
  ('<room1-uuid>', 'OR-01', '<1 号手术间>', 'GENERAL', '<手术室>', '<or-dept-uuid>', '<site-uuid>'),
  ('<room2-uuid>', 'OR-02', '<2 号手术间>', 'HYBRID',  '<手术室>', '<or-dept-uuid>', '<site-uuid>');
COMMIT;
```

`room_type` 仅 `GENERAL/HYBRID/INTERVENTION`；编号院内唯一，软删除不释放。

### 4.3 首个管理员（一次性，必须改密）

优先走院方 AD/LDAP/CAS（EXT-05，待院方）；无统一身份时，由实施管理员在变更单下创建
**一个** `SYS_ADMIN` 账号，口令哈希在部署机本地生成、不落盘/不回显：

```bash
# 交互输入，不进 shell 历史；哈希只在内存中传给 psql
read -r -s -p "初始口令（≥12 位，三类字符）: " BOOTSTRAP_PASSWORD; echo
HASH="$(htpasswd -bnBC 10 "" "$BOOTSTRAP_PASSWORD" | tr -d ':\n')"
unset BOOTSTRAP_PASSWORD

PGPASSWORD=*** psql "postgresql://<pg-host>:5432/<prod-db>" -v ON_ERROR_STOP=1 -v hash="$HASH" <<'SQL'
BEGIN;
INSERT INTO m0_platform.sys_user
  (id, username, password_hash, display_name, employee_no, phone_enc,
   status, must_change_password, failed_attempts, locked_until, last_login_at,
   created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
  (gen_random_uuid(), 'admin_bootstrap', :'hash', '实施管理员（首次）', 'OPS-0001', NULL,
   1, true, 0, NULL, NULL, now(), NULL, now(), NULL, 0, false);
INSERT INTO m0_platform.sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM m0_platform.sys_user u, m0_platform.sys_role r
WHERE u.username = 'admin_bootstrap' AND r.code = 'SYS_ADMIN';
COMMIT;
SQL
```

- `must_change_password=true`：首次登录必须改密后才能使用其他接口；
- 创建正式管理员后，将 `admin_bootstrap` 停用（`status=0`）或按变更单删除登录能力；
- 禁止把口令/哈希写入工单、日志、截图、Git；变更单只记录"已创建、账号名、时间、操作人"。

### 4.4 人员、角色与数据范围

- 人员档案（M4）、分级授权（双人审批）、排班、培训按 [`../training/admin.md`](../training/admin.md)；
- 数据范围按角色 `ALL/SITE/DEPT/ROOM/SELF` 与 `sys_user_org`/`sys_user_room` 绑定；
  ROOM 无绑定 fail-closed 403，不要用"先给 ALL 再收"绕过；
- 质控/报表角色按最小权限授予，导出权限单独授予并留审计。

## 5. 失败处理

| 现象 | 处理 |
|---|---|
| `4` 无法连接 | 检查实例/端口/pg_hba/SSL；Docker 不可用时先准备本地或远程 PG16 |
| `6` Flyway 失败 | 已应用迁移不回滚；读日志修复后重跑（幂等）；破坏性变更走评审 + 备份恢复 |
| `7` 基线不足 | 检查 V2 是否完整应用；不要手工补角色/权限，先修迁移 |
| `7` 发现 demo 账号 | 立即停用该库；演示库不得当生产库，重新初始化/按变更单清理 |
| 生产误加载 `db/seed/demo` | 停止发布、保留证据、按安全事件上报；清理演示账号并复核审计 |

## 6. 初始化签字

| 项 | 结论 | 证据 | 签字/日期 |
|---|---|---|---|
| 迁移版本与基线计数 | | `init-data.sh` 输出 | |
| `demo_* = 0`（生产） | | SQL 结果 | |
| 组织/术间/人员导入 | | 变更单 + SQL 输出 | |
| 首个管理员创建并改密 | | 登录记录（不含口令） | |
| 演示库独立、未混用 | | 库名/连接串 | |
