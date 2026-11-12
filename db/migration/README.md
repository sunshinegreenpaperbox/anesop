# db/migration — Flyway 数据库迁移

唯一允许存放 DDL/DML 迁移脚本的位置，按 `V<版本>__<描述>.sql` 命名。

## 规则

- 遵循 expand-contract：先加字段/表，再改逻辑，最后清理，禁止一步破坏性变更；
- 每个模块只写自己 schema（m0_platform / m1_sched / m2_anes / m3_staff /
  m4_supply / m5_drug / m6_stats / m7_qc / m8_dash）；
- 不可逆脚本必须附回滚演练记录并在 PR 声明；
- 脚本先评审后执行；生产迁移须人签字（上线闸门）。

## 状态

S0-02 仅建目录占位（`.gitkeep`）。V1（用户/角色/权限、审计日志、字典表）由 S0-06 落地。
