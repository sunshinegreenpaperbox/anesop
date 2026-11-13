# 数据库迁移与 schema 规范（db）

> 状态：S0-06 基线 + V3（认证令牌）+ V4（M2 采集骨架）+ V5（数据范围绑定，M2-M0-01）
> + V6（手机号加解密与用户管理，M2-M0-02）+ V7（M1 手术申请接收，M2-M1-01）
> + V8（M1 术间与排程台次，M2-M2-01）；
> 演示种子为独立 location。权威依据：方案书 §3.4（数据设计）、§3.9（安全）、
> `AGENTS.md`「契约优先与数据变更」，决策 D-04（数据库与信创）。
> 迁移脚本目录：`db/migration/`；生产挂载点：`/opt/anesop/db/migration`。

## 1. 目录与命名

```text
db/
├── migration/                           # 生产 locations：只允许本目录
│   ├── V1__m0_platform_init.sql         # M0 结构基线（表/约束/索引/触发器）
│   ├── V2__m0_platform_seed_synthetic.sql  # 平台预置基线（角色/权限/字典，无账号）
│   ├── V3__m0_auth_token.sql               # 刷新令牌表 + m0:auth:* 权限点 + sys_user.phone_hash（S0-07）
│   ├── V4__m2_anes_acquisition_init.sql    # M2 采集骨架（S0-09）
│   ├── V5__m0_data_scope.sql               # 数据范围绑定表 sys_org/sys_user_org/sys_user_room + m0:org:*（M2-M0-01）
│   ├── V6__m0_user_phone_crypto.sql        # sys_user.phone_key_id/phone_updated_at + 一致性约束 + m0:user:*（M2-M0-02）
│   ├── V7__m1_schedule_application.sql     # m1_sched 患者快照/手术申请/状态历史（含 append-only 触发器）+ m1:application:* / m1:patient:read（M2-M1-01）
│   ├── V8__m1_schedule_room_plan.sql       # m1_sched 术间/排程台次 + surgery_application.required_room_type + m1:schedule:*（M2-M2-01）
│   └── README.md
└── seed/demo/                           # 仅 dev/test 的独立 Flyway location
    └── V100__demo_admin_synthetic.sql   # demo_admin/demo_dept/demo_self + SITE/DEPT 组织（SYNTHETIC，禁止进入生产）
```

- 命名固定为 `V<版本>__<描述>.sql`（双下划线，描述小写下划线，如
  `V3__m1_sched_add_room_index.sql`）；`db/migration` 内版本号从 1 连续递增，不跳号、不复用；
  `db/seed/demo` 独立编号（V100 起），不参与生产版本序列。
- **forward-only**：仓库不存放 `U<版本>__*.sql`（Flyway undo）。回滚策略见 §4。
- 已合入并执行过的脚本**禁止修改**（Flyway checksum 会失败）；任何修正一律新增版本。
- 一个脚本只写一个模块的 schema；跨 schema 变更必须拆脚本并说明依赖顺序。
- `db/seed/demo/` **不属于** `db/migration/`：它是演示专用 location，生产 locations
  只含 `db/migration`。演示账号清单与启用方式见 `docs/db/seed.md`。
- 脚本提交前必须通过 `FlywayMigrationTest`（空库 + 幂等 + 升级路径 + 种子 + 审计只追加）。

## 2. schema 归属

| schema | 模块 | 内容 |
|---|---|---|
| `m0_platform` | M0 公共支撑 | 用户、角色、权限、审计、字典（本基线） |
| `m1_sched` | M1 手术排程 | 手术申请、排程/台次、术间 |
| `m2_anes` | M2 麻醉记录 | 记录单、事件、用药、体征 |
| `m3_staff` | M3 人员资质 | 人员档案、授权 |
| `m4_supply` | M4 耗材追溯 | 批次、消耗 |
| `m5_drug` | M5 药品管理 | 麻精台账、处方 |
| `m6_stats` | M6 工作量统计 | 统计周期与结果 |
| `m7_qc` | M7 质量控制 | 指标、不良事件 |
| `m8_dash` | M8 驾驶舱 | 只读投影 |

其他模块只能经 `m0-api` 访问 M0 数据，禁止直连 `m0_platform` 表（AGENTS 模块边界）。

## 3. expand-contract 与破坏性变更

字段只增不删。任何删除列、改类型、改语义都按 expand-contract 分多次发布：

1. **expand**：新增可空列/新表/新索引（`CREATE INDEX CONCURRENTLY` 用于大表），
   应用双写新旧字段；旧代码继续可读。
2. **migrate**：分批回填历史数据（每批提交、可重入、记录进度与校验和），
   期间新写入继续双写。
3. **switch**：读路径切到新字段，观察至少一个发布窗口。
4. **contract**：确认无回滚需求后，才在后续版本删除旧列/旧索引；删除前须备份并演练恢复。

硬性规则：

- 不写 `DROP COLUMN`、`ALTER COLUMN TYPE`、`TRUNCATE`、无条件 `DELETE` 作为“顺手清理”。
- 不重命名已上线列；用“新增 + 双写 + 切换 + 删旧”替代。
- 不在迁移脚本里调用外部服务、不写业务逻辑、不依赖应用代码。
- 大表加索引用 `CONCURRENTLY`（需 `flyway.mixed=true`/非事务执行时单独评审）。
- 不可逆脚本必须附回滚演练记录，并在 PR「回滚方案」字段声明；生产执行须人签字。

## 4. 回滚策略（forward-only）

- Flyway undo 不进入交付物：仓库无 `U` 脚本，测试 `MigrationUpgradePathTest` 会断言这一点。
- 迁移失败：停止版本发布，**前向修复**（新增 V(N+1) 脚本）或按备份恢复。
- 数据损坏/不可前向修复：用最近的全量备份 + WAL/PITR 恢复到新库，校验行数与校验和，
  切换连接串后再回滚应用版本；恢复演练每季度一次（§3.9.1 备份恢复）。
- 禁止在生产对 `m0_platform.sys_audit_log` 执行 `DELETE`/`UPDATE`/`TRUNCATE`；
  归档删除只能走受控维护流程并先写归档台账（§3.4.4）。

### 4.1 V6（手机号加解密）回滚说明

V6 属 expand 段：只新增可空列 `phone_key_id`/`phone_updated_at`、四列一致性约束
`ck_sys_user_phone_crypto`、部分索引 `idx_sys_user_phone_hash` 与两个权限点。

- **应用回滚**：旧版本应用不识别新列，列可空、约束对“四列全空”放行，因此旧应用可直接运行；
  但旧应用不会写 `phone_key_id`，与本版混跑期间**禁止**用它写手机号（会触发约束失败，属预期保护）。
- **库层回滚**（前向修复脚本，需 DBA 审批 + 备份）：
  `DROP CONSTRAINT ck_sys_user_phone_crypto` → `DROP INDEX idx_sys_user_phone_hash` →
  `ALTER TABLE ... DROP COLUMN phone_key_id, DROP COLUMN phone_updated_at` →
  `DELETE FROM sys_role_permission WHERE permission_id IN ('...22','...23')` →
  `DELETE FROM sys_permission WHERE code IN ('m0:user:read','m0:user:write')`。
  顺序不可颠倒（先拆约束/索引再删列），否则删列会被约束依赖阻塞。
- **数据不可回退为明文**：已加密手机号按单向设计处理，回滚后如需保留数据，只能由受控的
  离线工具按 `phone_key_id` 选择历史密钥解密到受控介质（应用内只持单把当前密钥，没有
  `key_id → key` 映射）；不得把明文写回 `sys_user`，也不得导出到无审批的下游。
- **密钥丢失**：`ANESOP_FIELD_KEY` 丢失等于密文永久不可解密（无后门、无兜底明文）；
  轮换需 M1 离线双密钥重加密工具，备份/轮换说明见 `docs/modules/m0/README.md` 第 5 节。

### 4.2 V7（M1 手术申请）回滚说明

V7 属 expand 段：只新增 `m1_sched` schema 与三张表（患者快照/手术申请/状态历史）、
状态历史 append-only 触发器及三个权限点（m1:application:read/write、m1:patient:read），
不修改 V1–V6 任何表或列。回滚不影响 M0 认证、权限、审计与其他模块。

- **应用回滚**：下线 `/v1/scheduling/applications*` 与 `/v1/patients*`（M1 路由/前端入口），
  停止写入 `m1_sched`；旧版本应用（不识别 V7）可继续运行，因为 M0 表未变更。
- **库层回滚**（前向修复脚本，需 DBA 审批 + 备份；顺序不可颠倒）：
  `DROP TABLE m1_sched.application_status_history` →
  `DROP TABLE m1_sched.surgery_application` →
  `DROP TABLE m1_sched.patient_snapshot` → `DROP SCHEMA m1_sched`（若为空）→
  `DELETE FROM m0_platform.sys_role_permission WHERE permission_id IN ('...24','...25')` →
  `DELETE FROM m0_platform.sys_permission WHERE code IN ('m1:application:read','m1:application:write')`。
- **数据保全**：回滚前必须导出 `m1_sched` 三表（含状态历史）到受控介质并校验行数/校验和；
  历史表 append-only，不接受“只删申请不删历史”的部分回滚。
- **不可逆项**：`inpatient_no_hash` 为单向哈希，回滚后无法还原住院号明文；如业务需要，
  只能由 HIS 重新推送（患者快照按 `his_patient_no` 重建），不得从哈希反推。
- **重放**：修复后重新执行 V7 会重建空表；已导出数据不得直接回灌（会与 Flyway 版本/唯一键冲突），
  须走受控数据修复流程并记录操作人与时间。

### 4.3 V8（M1 术间与排程台次）回滚说明

V8 属 expand 段：新增 `m1_sched.operating_room` / `m1_sched.schedule` 两表、索引与两个权限点
（`m1:schedule:read|write`；read 授 SYS_ADMIN/SYS_OR_MANAGER/SYS_SURGEON，write 仅授
SYS_ADMIN/SYS_OR_MANAGER），`schedule.priority smallint NOT NULL DEFAULT 0`（0=ELECTIVE/
1=URGENT/2=EMERGENCY，`ck_schedule_priority`），并给 `surgery_application` 增加**可空**列
`required_room_type`；不修改 V1–V7 已执行脚本，也不改写既有列与数据。跨术间术者/患者并发由
应用层事务级 advisory lock（`pg_advisory_xact_lock`，确定性排序）串行化。

- **应用回滚**：下线排程工作台路由（`/m1/schedule` 与 `/v1/scheduling/{rooms,schedules*}`），
  停止读写 `m1_sched.schedule`/`operating_room`；申请/患者/麻醉记录能力不受影响。
- **库层回滚**（前向修复脚本，需 DBA 审批 + 备份；顺序不可颠倒）：
  `DROP TABLE m1_sched.schedule` → `DROP TABLE m1_sched.operating_room` →
  `ALTER TABLE m1_sched.surgery_application DROP COLUMN required_room_type` →
  `DELETE FROM m0_platform.sys_role_permission WHERE permission_id IN ('...27','...28')` →
  `DELETE FROM m0_platform.sys_permission WHERE code IN ('m1:schedule:read','m1:schedule:write')`。
- **数据保全**：回滚前导出 `schedule`/`operating_room` 并校验行数；台次取消保留原因，不物理删除。
- **种子**：V8 内置 5 个 SYNTHETIC 术间（含 1 HYBRID、1 INTERVENTION），回滚后需重建；
  真实术间主数据不得由种子代替，须走院方主数据维护流程。
- **重放**：修复后重新执行 V8 会重建空表并重新灌入 5 个合成术间；已导出排程数据不得直接回灌。

## 5. 运行方式

### 5.1 本地 / CI（无 Docker，EXT-08）

集成测试用 Zonky 嵌入式 PostgreSQL 16 真实执行迁移，不使用 H2：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -pl apps/api/modules/m0/impl -am test
# 全量后端验收
./scripts/mvn.sh -B -f apps/api/pom.xml verify
```

测试断言分两条路径：

- **生产路径**（locations 只含 `db/migration`）：空库 `migrate()` 成功、Flyway history 8 条（V1–V8）、
  二次 `migrate()` 执行 0 条、V1→V2 升级成功、约束/索引/注释存在、平台预置种子正确、
  `sys_user=0` 且无 `demo_admin`、审计表 UPDATE/DELETE/TRUNCATE 被数据库拒绝；
- **演示路径**（追加 `db/seed/demo`）：V100 后 `demo_admin`（ALL）、`demo_dept`（DEPT，绑定麻醉科）、
  `demo_self`（SELF）存在，`demo_admin` 绑定 `SYS_ADMIN`、`must_change_password=true`、BCrypt 校验演示口令通过；
- **数据范围**（`DataScopePersistenceIntegrationTest`，12 用例）：真实 PG 验证 ALL/DEPT/SITE/ROOM/SELF、
  未绑定 fail-closed、SITE 按 `path` 覆盖子树且不越界兄弟院区、撤销绑定（status=0）与停用组织不再生效、
  停用用户不出现在 DEPT/ROOM 成员结果、并发解析一致；`DataScopeApiIntegrationTest`（9 用例）
  验证 `/me` 返回真实 scopeIds、跨科室 403 + ACCESS_DENIED 审计、缺 `m0:org:read` 403；
- **Mapper 集成**（`MybatisPersistenceTest`）：jsonb/inet TypeHandler 往返、软删除过滤、
  旧 version 更新影响行数为 0、审计触发器在 Mapper 路径仍拒绝改写。

### 5.2 本地真实 PostgreSQL

```bash
# 1) 空库（示例；生产禁止用 postgres 超级用户跑应用）
createdb -h localhost -U postgres anesop

# 2) 由应用启动迁移（推荐：与应用同一套 Flyway）
SPRING_FLYWAY_LOCATIONS=filesystem:db/migration \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/anesop \
SPRING_DATASOURCE_USERNAME=anesop_app \
SPRING_DATASOURCE_PASSWORD='***' \
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar
```

### 5.3 生产

- 迁移脚本随交付物挂载到 **`/opt/anesop/db/migration`**（只读，属主 root，应用账号只读）。
- 通过环境变量指定位置，不把 SQL 打进 jar：

```bash
SPRING_FLYWAY_LOCATIONS=filesystem:/opt/anesop/db/migration
SPRING_FLYWAY_ENABLED=true
SPRING_FLYWAY_BASELINE_ON_MIGRATE=false   # 空库首次迁移；既有库升级须单独 baseline 评审
SPRING_FLYWAY_CLEAN_DISABLED=true         # 生产禁止 clean（默认即 true，显式写出）
SPRING_FLYWAY_VALIDATE_ON_MIGRATE=true
SPRING_FLYWAY_TABLE=flyway_schema_history
```

- 应用账号与迁移账号分离：迁移用具备 DDL 权限的专用账号；应用运行账号只授予
  `SELECT/INSERT/UPDATE/DELETE` 中业务必需项，**审计表只授予 SELECT/INSERT**。
- **生产 locations 只含 `db/migration`**，禁止出现 `db/seed/demo`；演示种子是独立
  Flyway location，仅本地/演示/集成测试显式追加：
  ```bash
  # 本地/演示（仅此场景允许第二个 location）
  SPRING_FLYWAY_LOCATIONS=filesystem:db/migration,filesystem:db/seed/demo
  # 生产（不得追加任何 seed 目录）
  SPRING_FLYWAY_LOCATIONS=filesystem:/opt/anesop/db/migration
  ```
- **启动安全守卫（S0-07，B-2）**：`anesop.security.demo-accounts-allowed` 默认 **false**
  （不依赖 profile），启动时校验 `m0_platform.sys_user` 中不存在 `demo_*` 账号
  （`username LIKE 'demo\_%'`，精确转义，`demouser` 不命中），命中即拒绝启动并告警。
  dev/test 的 `application-dev.yml` / `application-test.yml` 显式置 true 才允许演示账号。
  生产首次部署迁移后 `sys_user` 应为 0；后续账号只能来自院方身份源或实施管理员创建，
  不得来自演示种子。JWT 密钥同理（`anesop.security.jwt.allow-dev-generated` 默认 false）。
- 发布窗口内先跑迁移再启应用；迁移失败即停止发布，禁止应用“带病启动”。
- 迁移前必须有一次可验证的备份；跨大版本升级先在 staging 用同规模数据演练。

## 6. PostgreSQL 特性与 D-04 信创适配

V1 使用的 PG 特性**集中在 `m0_platform` 一个 schema**，避免扩散到 M1–M8：

| 特性 | 用途 | 信创适配方式（D-04） |
|---|---|---|
| `uuid` + `gen_random_uuid()` | 主键 | 国产库原生 UUID 或 `char(36)`；生成改应用侧 UUIDv7/雪花并由适配层注入 |
| `jsonb` | 审计 `detail` 前后值 | `json`/`text` + 应用侧序列化；查询能力降级需评估 |
| `inet` | 审计来源 IP | `varchar(45)`，应用侧校验 IPv4/IPv6 |
| `timestamptz` | 全部时间列 | 统一 `timestamp with time zone` 等价类型；禁止裸 `timestamp` |
| PL/pgSQL 触发器 | 审计 append-only | 国产库触发器/规则等价实现，或仅靠权限 + 应用审计网关；须重跑 append-only 用例 |
| 部分唯一索引 | `employee_no` 非空唯一 | 国产库支持度不一；退化为普通唯一索引 + 应用校验 |
| `COMMENT ON` | 中文表/列注释 | 多数国产库兼容；不兼容时落 `docs/data-dictionary/` |

约定：

- 不在迁移脚本使用 PG 数组、range、自定义类型、`RETURNING` 之外的扩展语法；
- 不使用 `serial/bigserial`（主键统一 uuid；日志表未来分区另议）；
- 所有时间由服务端写入，应用层统一 `Asia/Shanghai` 展示；客户端时间只作展示（§3.4.5）；
- 应用启动校验 NTP 偏差（>500ms 告警、>5s 拒绝写入）由 S0-07 之后的运行时实现，
  数据库层只保证 `timestamptz` 存储。

## 7. 审计留存与权限

- `m0_platform.sys_audit_log` 无 `updated_at`、无 `deleted`，只追加；
- 触发器 `trg_sys_audit_log_no_update/no_delete/no_truncate` 拒绝改写，
  SQLSTATE `55000`，测试 `FlywayMigrationTest#auditLogRejectsUpdateDeleteAndTruncate` 覆盖；
- 在线留存 **≥ 6 个月**（等保三级 §3.9.1），方案要求按月分区、在线 12 个月后归档
  MinIO（Parquet，按年打包）；分区与归档作业在 M8/运维任务落地，本基线先建索引；
- 运行时账号只授予 `SELECT/INSERT`；表属主/超级用户仍被触发器拦截（第二道防线）；
- 归档删除须先写归档台账（行数、校验和、操作人、时间），保留可审计证据；
- 审计 `detail` 禁止写入患者姓名、证件号、住院号、手机号等直接标识（§3.9.3）。

## 8. 已知边界与后续待办（编号承接）

以下事项为 S0-06 评审登记、**本任务不实现**，按编号在后续任务承接：

| 编号 | 事项 | 承接点 |
|---|---|---|
| S-1 | user → dept/room 绑定表（用户与科室/术间的数据范围绑定） | **已完成**：V5 + `DefaultDataScopeResolver`（M2-M0-01） |
| S-2 | `SYS_ADMIN` 权限拆分（平台管理员与业务管理员分离） | M1 实施基线 |
| S-5 | `phone_hash` 列已由 V3 expand 就绪（varchar(128)，与 `phone_enc` 配套）；加密/HMAC 写路径 | M1 |
| S-9 | 数据库最小权限 GRANT / initdb 脚本（迁移账号与应用账号分离） | S0-07 或 S0-10 |
| Q-2 | 患者/术间表归属哪个 schema（M1 与 M2 边界） | M1 |
| Q-3 | 多院区 `tenant_id`/`site_id` 字段引入时点（expand-contract） | M1 前 |
| Q-4 | 审计按月分区/归档的门槛（行数、留存窗口与运维流程） | 首个真实数据前 |

### 8.1 数据范围绑定表（V5，M2-M0-01）

| 表 | 内容 | 关键约束 |
|---|---|---|
| `sys_org` | 组织机构（数据范围对象）：`org_type` = SITE 院区 / DEPT 科室，树形 `parent_id` + `path` | `uk_sys_org_code` 唯一；`ck_sys_org_org_type`；软删除不释放编码 |
| `sys_user_org` | 用户—组织绑定：SITE/DEPT 范围 `scopeIds` 来源；`updated_at/updated_by` 记录授权变更 | 复合主键 `(user_id, org_id)`；`uk_sys_user_org_primary` 保证每用户最多一个主科室 |
| `sys_user_room` | 用户—术间绑定：ROOM 范围 `scopeIds` 来源 | 复合主键 `(user_id, room_id)`；`status=1` 才生效；`room_id` 为逻辑外键（术间主数据 M2-M2-01 在 `m1_sched` 建立） |

- 解析语义（fail-closed）：角色声明 SITE/DEPT/ROOM 但无生效绑定、角色范围未知或查询异常 →
  一律 `NONE`（接口 403），不得降级为 ALL/SELF；任一角色 `ALL` 即全院可见。
- **SITE 子树（Q-1）**：`data_scope=SITE` 时范围覆盖 `sys_org.path` 前缀匹配的整棵子树
  （院区自身 + 下属全部科室），不是只取院区直属成员；`DEPT` 只精确匹配本科室。
- 成员口径统一（S-5）：DEPT/ROOM 成员查询与 ALL 路径一致，只返回 `sys_user.status=1` 且未删除用户。
- 生产迁移路径只建结构，不写组织/绑定数据（`FlywayMigrationTest#dataScopeBindingTablesAreReady` 断言为 0）；
  演示/测试数据只在 `db/seed/demo` 与测试夹具中出现，均为 SYNTHETIC。
- 回滚：forward-only。回滚时应用侧停止读取绑定表（范围 fail-closed 到 NONE）+ 前向修复；
  不删除表（删除会丢失范围绑定证据，且 Flyway 不执行 undo）。
- 后续：组织 CRUD 接口（`m0:org:read` 已由 V5 授予 SYS_ADMIN 与 SYS_OR_MANAGER，`m0:org:write`
  仅 SYS_ADMIN）在 M2 组织管理任务落地；
  MyBatis 数据权限拦截器自动注入条件在 M1 排程/记录查询落地。

补充说明：

- V1 **尚未包含** `tenant_id`/`site_id`（Q-3）：多院区上线前必须用 expand-contract 增加
  两列并重建唯一索引（如 `username` 改为 `(tenant_id, username)`），否则与 §3.4.5 不一致；
- 审计按月分区、大表归档、`CREATE INDEX CONCURRENTLY` 尚未落地（Q-4），属 M8/运维任务；
- 认证服务、审计写入服务、MyBatis 拦截器与 `@Version` 自动填充的运行时装配由 S0-07 负责；
  `UuidTypeHandler`/`JsonbTypeHandler`/`InetTypeHandler` 需要在 S0-07 的 MyBatis 配置中注册
  （uuid 全局注册；jsonb/inet 已由实体 `@TableField(typeHandler=...)` 绑定）；
- Testcontainers 用例因 Docker 不可用标记 `EXT-08`，恢复后补跑容器化路径（与嵌入式 PG 二选一）。
