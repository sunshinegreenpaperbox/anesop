# db/migration — Flyway 数据库迁移

唯一允许存放 DDL/DML 迁移脚本的位置，按 `V<版本>__<描述>.sql` 命名。

## 规则

- 遵循 expand-contract：先加字段/表，再改逻辑，最后清理，禁止一步破坏性变更；
- 每个模块只写自己 schema（m0_platform / m1_sched / m2_anes / m3_staff /
  m4_supply / m5_drug / m6_stats / m7_qc / m8_dash）；
- 不可逆脚本必须附回滚演练记录并在 PR 声明；
- 脚本先评审后执行；生产迁移须人签字（上线闸门）。

## 状态

S0-06 已落地：

- `V1__m0_platform_init.sql`：`m0_platform` schema、用户/角色/权限/关联表、审计日志
  （append-only 触发器）、字典类型/字典项，含中文表/列注释与 FK 索引。
- `V2__m0_platform_seed_synthetic.sql`：平台预置基线（6 角色、17 权限、42 条授权、
  6 类 22 项字典），**不含任何用户账号**。

演示管理员 `demo_admin` 位于 `db/seed/demo/V100__demo_admin_synthetic.sql`，是仅
dev/test 使用的独立 Flyway location，**不得加入生产 locations**（生产只含本目录）。
生产路径测试断言 `sys_user=0`；演示路径测试断言 `demo_admin` 存在。

规范、expand-contract、回滚与生产挂载方式见 `docs/db/README.md`；
种子账号清单见 `docs/db/seed.md`。V3 起为各业务模块（M1–M8）迁移，不在本任务范围。
