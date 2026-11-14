# m4 — 药品目录与麻精五专（E-M15）· 人员档案与分级授权（E-M13）

> 状态：M4-M15-01 完成（2026-11-14，契约 `drug.yaml 0.1.0`、V20、m4-api/impl、前端两页）；
> M4-M13-01 完成（2026-11-14，契约 `staff.yaml 0.1.1`、V21 + V22、m4_staff/授权校验/
> 排班冲突/培训、前端四页）。本文件是 M4 域的模块口径说明；实现细节以契约与迁移为准，冲突时按
> “契约 → 迁移 → 本文件”的顺序裁决。§1–§13 为药品/五专（E-M15），§14–§22 为人员域
> （E-M13，模块目录仍为 `modules/m4`，schema `m4_staff`）。

## 1. 一句话职责

维护**药品主数据**（编码/名称/通用名/规格/单位/剂型/分类/麻精/高危/别名）与**麻精五专**
（专人/专柜/专册/专账/专处方的最小落地：专册登记、专柜交接、基数、日结/班结），并提供
药房系统适配器（EXT-02，模拟）状态；同时**收口 M3 T-4 风险**：麻醉记录用药的麻精属性
由本域药品字典判定，客户端声明只做一致性校验。

## 2. 数据归属与边界

- schema：`m4_drug`（本模块独占；其他模块只能经 `m4-api` 访问，禁止直连本 schema 表）。
- 表：`drug`、`drug_alias`、`narcotic_register`、`cabinet_handover`、`drug_baseline`、
  `daily_close`、`pharmacy_adapter_log`（V20，详见 [`docs/db/README.md`](../../db/README.md) §4.15）。
- 依赖方向：`m4-impl → m4-api → m0-api/api-contracts`；`m2-impl → m4-api`（T-4 只读字典，
  不访问 m4 表）。禁止任何模块依赖 `m4-impl`（bootstrap 装配点除外）。
- 主数据权威源：药品目录以**本院药房/药典**为源（方案书 §2.5）；本系统只读/维护复制，
  不反向写药房系统（EXT-02 为模拟）。

## 3. 权限与数据范围

| 权限点 | 授予角色（V20 最小集） | 端点 |
|---|---|---|
| `m4:drug:read` | SYS_ADMIN、SYS_PHARMACIST、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST | `GET /v1/drugs*`、`GET /v1/drugs/pharmacy-adapter/status` |
| `m4:drug:write` | SYS_ADMIN、SYS_PHARMACIST | `POST/PUT /v1/drugs*` |
| `m4:narcotic:manage` | SYS_ADMIN、SYS_PHARMACIST、SYS_ANESTHESIOLOGIST | 全部 `/v1/narcotic/*` |

- **药品目录全院可读**（不按数据范围过滤）：用药录入需要读目录；维护仅药房/管理员。
- **五专按科室/角色限制（最小实现）**：`narcotic_register`、`cabinet_handover`、
  `drug_baseline`、`daily_close` 均带 `department_id`，服务层用 m0 `DataScopeResolver`：
  - `ALL`：全院可见/可写；
  - `SITE`：授予院区 `sys_org.path` 子树内的启用科室；`DEPT`：仅授权科室；写入越界 403；
  - `SELF`：按本人所属科室推导（取主科室，其次首个绑定科室，来自 m0 `sys_user_org`）；
    跨科室 403；未绑定科室 403；
  - `ROOM`/无范围：**不适用五专**，fail-closed 403（五专没有“术间”语义，不新增数据范围模型）；
  - 单对象（日结详情）越界与不存在统一 404（不泄露存在性）。
- **科室即请求必填项**：`GET /v1/narcotic/departments` 返回调用者可见的启用科室；前端必须
  显式选择，禁止用当前用户 ID 冒充科室（B-3）。服务端对写入/过滤的 `departmentId` 统一校验
  存在、启用且 `org_type=DEPT`，否则 422；不在数据范围 403。
- 麻醉护士（SYS_NURSE_ANESTHETIST）**只授予目录只读**（用药页字典联想/麻精判定所需），
  **不授予**五专维护：麻精账由药房与麻醉医师双线维护，护士只参与记录单的双人核对
  （`m2:record:write`）。

## 4. 药品目录口径

- **编码唯一**（`lower(btrim(code))` 唯一索引 + 服务层 409）：创建后不可改；格式
  `^[A-Za-z0-9._-]{1,64}$`。
- **名称/通用名归一化唯一**（`lower(btrim(name))` 唯一；`lower(btrim(generic_name))` 部分
  唯一，NULL 除外）：冲突 409；通用名可空、参与名称解析。
- **别名全局唯一**（`lower(btrim(alias))` 唯一索引 + 服务层 409）：同一药品内也不得重复；
  名称/通用名不得撞他人别名，别名不得撞他人名称/通用名——任何交叉冲突都会让名称解析
  歧义，必须 409（这是 T-4 的语义前提，不是可选项）。
- **麻精（narcotic）/高危（highRisk）**：主数据布尔字段。麻精是**麻醉记录判定的唯一权威**；
  高危只做提示，不替代医师判断（AGENTS H-06）。变更记审计（before/after 摘要）。
- **状态（status）**：`ACTIVE`（可录入）/`INACTIVE`（停用，只读历史）。本期**不提供删除**：
  临床留痕与字典解析都要能回看历史名称。
- **乐观锁**：更新必须回传 `version`，过期 409；数据库守卫（V20）保证编码/创建留痕不可改、
  `version` 只增。

## 5. 麻精五专口径

### 5.1 专册登记（`narcotic_register`，append-only）

- 必填：药品（必须已收录于字典且 `ACTIVE`，否则 422）、批号、数量（> 0，两位小数）、单位、
  处方号、使用人姓名摘要（≤64）、科室、使用时间（不得晚于服务端时间 + 5 分钟）、去向、
  **两名签字人**（id + 姓名摘要）。
- **双人签字必须不同人**：同一人 400（`details[0].field=secondSignerId`），数据库
  `ck_narcotic_register_two_signers` 兜底。
- **append-only**：只 INSERT；UPDATE/DELETE/TRUNCATE 一律 SQLSTATE 55000。更正只能新增记录
  （两次记录都保留，审计可追溯差异）。
- 脱敏：只保存使用人姓名摘要与处方号文本；审计 detail **不含**处方号原文与人员姓名；
  不含患者姓名/院内患者号/身份证号/手机号。

### 5.2 专柜交接（`cabinet_handover`，append-only）

- 必填：柜号、科室、班次（`DAY`/`NIGHT`，两班制为演示口径【待确认】）、交接时间、
  交班人/接班人（id + 姓名摘要）、品种数量明细（jsonb，≥1 条，药品必须已收录、数量 ≥ 0）、
  **两名签字人**。
- **交班人 ≠ 接班人**、**两名签字人不同人**（均为 400，数据库 CHECK 兜底）。
- 明细按“药品 + 数量 + 单位”保存，不保存价格与金额（财务口径【待确认】，见 ADR-0018）。
- append-only 同专册。

### 5.3 基数（`drug_baseline`）

- 唯一键：`drug_id + cabinet_code`；数量 ≥ 0；科室随柜；更新必须回传 `version`（过期 409）。
- 品种/柜/科室不可改（数据库守卫 55000）：改品种/柜/科室请新增记录并停用旧记录。
- 跨科校验（B-2）：upsert 时 `existing.departmentId` 必须等于请求值且在调用者可见范围；
  不可见科室 403、可见范围内跨科改写 409。
- 基数与日结的关系：本期日结公式使用**申报的期初/入库/使用/退回/期末**，不自动从基数或
  专册汇总（口径【待确认】，见 ADR-0018 Q-2）。

### 5.4 日结/班结（`daily_close`，append-only）

- 唯一键：`cabinet_code + close_date + shift`（同一柜同一天同班次只能日结一次；重复 409）。
- **平账公式**：`期初 + 入库 − 使用 − 退回 = 期末`。不平时 409 `STATE_CONFLICT`，
  `details[0].field=closingQuantity`，`reason` 含期望值与实盘值（前端直接展示）。
  数据库 `ck_daily_close_balanced` 为第二道防线（先服务层 409，避免把 SQL 错误暴露给前端）。
- 业务日不得晚于今天（Asia/Shanghai）；append-only，不平不落库；差异须人工查明后重新日结
  （两次日结记录都保留）。
- **不设差异容忍度/数量阈值【待确认】**：本期只做恒等校验，不猜临床/财务口径。

## 6. 状态机（最小）

```text
药品：ACTIVE ──(停用)──> INACTIVE        # 不提供删除；停用后不可新登记五专/不可联想
麻精五专记录：新建 ──> 已登记（终态）      # append-only，无修改/删除/撤销
日结：提交 ──(平账)──> 已日结（终态）
      └────(不平)──> 409，不落库（可重新申报）
基数：不存在 ──(PUT)──> v1 ──(PUT+version)──> v(n+1)
```

## 7. T-4 收口（药品字典判定麻精）

- `m4-api` 暴露只读 `DrugDictionaryService`：`findById(drugId)` / `findByName(name)`；
  名称解析顺序固定为 **药品名称 → 通用名 → 别名**（trim + 忽略大小写），归一化后必须唯一；
  命中多个不同药品时抛 `DrugDictionaryAmbiguousException`（fail-closed），禁止静默取第一条。
- M2 用药登记（`POST /v1/anesthesia/records/{recordId}/drugs`）改为服务端判定：
  - 字典未收录 → 400 `PARAM_INVALID`，`details[0].field=drugName`；
  - 字典解析歧义 → 422 `BUSINESS_RULE_VIOLATION`，`details[0].reason` 含 `DRUG_AMBIGUOUS`；
  - 客户端 `narcotic` 与字典不一致 → 400，`details[0].field=narcotic`（**以字典为准**）；
  - 字典 `narcotic=true` → 强制双人核对（缺失/同人/核对人不合格 400，与原有规则一致）；
  - 字典 `narcotic=false` → 不得填写双人核对字段（原有规则）。
- 残留边界：麻醉记录仍按**药品名称**关联合规字典，契约 `AdministerDrugRequest` 暂无
  `drugId` 字段（anesthesia.yaml 不在本任务写域）。上线后建议新增可选 `drugId` 并优先按
  id 关联（减少改名歧义），登记为后续任务。
- 已知限制：自由文本药品名若与字典名称完全相同仍是合法输入；字典**改名**会使历史记录名
  与新名不再匹配（历史记录按名称快照保存，不受影响；新录入需用新名/别名）。

## 8. 药房适配器（EXT-02，SIMULATED）

- `GET /v1/drugs/pharmacy-adapter/status` 返回 `adapterCode=EXT-02`、`mode=SIMULATED`、
  `available`、`degraded`、`lastSyncedAt`、`pendingSyncCount`、`message`。
- 每次调用执行一次**无外部 I/O** 的模拟探测（不直连药房生产库、不写外部系统），结果写
  `m4_drug.pharmacy_adapter_log` 并记审计 `ADAPTER_SYNC`；探测失败时
  `available=false, degraded=true`，本地字典继续可用（不阻塞临床记录）。
- 配置：`anesop.m4.pharmacy.simulated-available`（默认 `true`；自动化测试可置 `false`
  验证降级）。真实对接（LIVE）需院方提供接口规范与网络策略，并另立 ADR/任务。

## 9. 审计

| action | 触发 | detail 白名单 |
|---|---|---|
| `DRUG_CREATE` | 创建药品 | drugId/code/name/narcotic/highRisk/aliasCount |
| `DRUG_UPDATE` | 更新药品 | drugId/code/narcotic/highRisk/变更标记/version |
| `NARCOTIC_REGISTER` | 专册登记 | registerId/drugId/drugName/batchNo/quantity/unit/departmentId |
| `NARCOTIC_HANDOVER` | 专柜交接 | handoverId/cabinetCode/shift/departmentId/itemCount |
| `NARCOTIC_BASELINE` | 基数维护 | itemCount + changes[]（drugId/cabinetCode/quantity/operation） |
| `NARCOTIC_DAILY_CLOSE` | 日结 | closeId/closeDate/shift/cabinetCode/departmentId + 五项数量 |
| `ADAPTER_SYNC` | 适配器探测 | adapterCode/mode/available/degraded |

审计不写处方号原文、人员姓名、备注原文与患者标识；失败路径记 `result=0`（参数/权限/冲突）。

## 10. 前端

- `apps/web/src/modules/m4/views/DrugCatalogView.vue`：目录列表/检索/新建/编辑（只读账号
  隐藏维护入口）；错误区分 403/409/400 并展示 traceId。
- `apps/web/src/modules/m4/views/NarcoticView.vue`：四个页签（专册/交接/基数/日结）+ 适配器
  状态卡；科室为显式下拉（`/v1/narcotic/departments`，B-3），未选择不发请求；双人签字前端
  先拦一次（服务端 400 为准）；日结实时显示“期初 + 入库 − 使用 − 退回 = 期末”与
  “一致/不一致”，409 时展示 `details` 平账差异。
- 路由/菜单：`/m4/drugs`（`m4:drug:read`）、`/m4/narcotic`（`m4:narcotic:manage`）；
  菜单与路由 meta 同源于 `src/navigation.ts`。
- 测试：Vitest 11 例（目录加载/只读隐藏/新建/409；五专加载/降级/双人拦截/科室必选/
  登记/日结不平/日结平账）；E2E `apps/web/e2e/m4-drug.spec.ts`（目录新建 → 显式选科室 →
  专册登记 → 日结平账）。

## 11. 待确认（Q）

1. **Q-1 班次口径**：两班制（DAY/NIGHT）为演示口径；三班制或“日结 + 班结”双轨需院方确认
   后新增迁移扩展 CHECK 与契约枚举。
2. **Q-2 日结数据来源**：期初/入库/使用/退回由人工申报，还是由基数 + 专册 + 交接自动汇总？
   本期不自动汇总（避免臆造财务口径）；若自动汇总，需定义“入库/退回”的来源单据。
3. **Q-3 差异容忍度**：是否允许 ±N 的盘点差异、差异审批流与责任归属（药房/护理/麻醉）。
4. **Q-4 药品分类与剂型字典**：`category`/`dosageForm` 目前是自由文本编码，院方药典分类
   确认后应改为字典项并做白名单校验。
5. **Q-5 处方关联**：本期只保存处方号文本，不校验处方真伪/开方医师资质（属 HIS/处方审核域）；
   是否对接处方审核接口（EXT-01/EXT-02）待确认。
6. **Q-6 五专数据范围**：是否允许药房跨科室维护（当前 ALL 可见可写）；是否需要“药房仅见
   药房柜、麻醉仅见本科室柜”的更细粒度规则。
7. **Q-7 高危药品提醒**：是否需要在用药录入/专册登记时强制二次提示（当前仅主数据标志）。
8. **Q-8 真实药房对接**：EXT-02 解除条件、接口规范、同步频率、失败重试与对账口径。
9. **Q-9（评审 Q-1）抢救用药麻精判定**：M2 抢救记录用药仍为自由文本、未接药品字典；是否要求同样按
   字典判定麻精/高危、强制双人核对并补记专册，待临床/药房/医务处确认。
10. **Q-10（评审 Q-2）日结重开/调整机制**：append-only 下错误日结只能新增记录；是否允许重开当日 +
    调整、审批流、红冲口径与审计要求，待财务/药房/医务处确认。

## 12. 测试与验收

- 后端：`m4-impl` 单测 29 例（目录归一化唯一/反向冲突/乐观锁/双人不同人/科室范围/SELF 推导/
  日结平账与不平/基数版本/基数跨科/药品未收录/适配器降级）；集成
  `M4DrugApiIntegrationTest` 7 例（CRUD/权限/append-only 55000 + TRUNCATE/日结 409/details/
  失败审计/科室校验/基数跨科/V20 结构）；T-4 集成用例在 `AnesthesiaRecordApiIntegrationTest`
  （别名指向麻精 + 声明 false → 400、大小写/空白解析、字典歧义 → 422 `DRUG_AMBIGUOUS`、
  未收录 → 400、麻精 + 双人核对 → 201）。
- 覆盖率（m4-impl）：行 91.7%、分支 73.9%（JaCoCo，2026-11-14 全量 verify）。
- 契约：`bash scripts/gen-contracts.sh`、`--java`、`bash scripts/contract-check.sh`
  （spectral/breaking/HEAD baseline/drift）全 PASS。
- 前端：`pnpm test:unit`（m4 10 例）、`pnpm e2e`（m4-drug 1 条）。

## 13. 负责人（占位）

- Owner：`@anesop/m4-drug` + `@anesop/web-core`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`


---

# 人员档案、分级授权、排班与培训（M4-M13-01 / E-M13）

> 对应方案书 §2.4.4（M3 人员管理）：资质可查、排班可排、授权可控、绩效有据；
> 关键规则“无授权不得执行对应麻醉方式或术式，排程与记录两处校验；证书到期前 30 天提醒；
> 排班变更须审批并留痕”。分级授权与排班口径见 [ADR-0019](../../adr/0019-分级授权与排班口径.md)。

## 14. 数据归属与边界

- schema：`m4_staff`（本域独占；其他模块只能经 `m4-api` 访问，禁止直连本 schema 表）。
- 表：`staff_profile`、`staff_qualification`、`staff_authorization`（append-only + 当前视图）、
  `staff_schedule`、`staff_training`（V21，详见 [`docs/db/README.md`](../../db/README.md) §4.16）。
- 视图：`staff_authorization_current`（每人员 `version` 最大的一行；撤销后当前版本为 REVOKED）。
- 依赖方向：`m4-impl → m4-api → m0-api/api-contracts`；M1 排程 / M5 术中只依赖
  `m4-api` 的 `StaffAuthorizationService.evaluate(...)`，不访问 m4 表。
- 人员主数据权威源：方案书 §3.5 定“人员归 AD，本系统只维护映射与冲突提示”；一期无 AD 接口，
  档案由本系统维护并以 `userId` 关联 m0 用户（非空时一人一档案），AD/人事同步见 Q-7。

## 15. 权限与数据范围

| 权限点 | 授予角色（V21 最小集） | 端点 |
|---|---|---|
| `m4:staff:read` | SYS_ADMIN、SYS_OR_MANAGER、SYS_AUTH_MANAGER、SYS_ANESTHESIOLOGIST | `GET /v1/staff*`、`GET /v1/staff/{id}/authorizations`、`GET /v1/staff/schedules`、`GET /v1/staff/trainings*`、`POST /v1/staff/authorization-checks`（只读判定） |
| `m4:staff:write` | SYS_ADMIN、SYS_AUTH_MANAGER（DEPT） | `POST/PUT /v1/staff`、`PUT /v1/staff/{id}/authorizations` |
| `m4:staff:authorize` | SYS_ADMIN、SYS_AUTH_MANAGER（DEPT） | 审批人资格（授予/撤销时的 `approvedBy/secondApproverId` 校验）；**不是独立端点权限** |
| `m4:schedule:manage` | SYS_ADMIN、SYS_OR_MANAGER | `POST /v1/staff/schedules`、`PUT /{id}`、`POST /{id}/cancel` |
| `m4:training:manage` | SYS_ADMIN、SYS_AUTH_MANAGER（DEPT） | `POST /v1/staff/trainings` |

- **V22 角色收敛（B-2）**：新增 `SYS_AUTH_MANAGER`（`data_scope=DEPT`，绑定科室后按科室维护
  档案/培训并审批授权）；`SYS_ANESTHESIOLOGIST` 收回 `m4:staff:write`/`m4:training:manage`，
  **只读**（保留 `m4:staff:read`）；`SYS_ADMIN` 追加 `m4:staff:authorize`（双人规则不因管理员豁免）。
  麻醉护士/术者仍不授予人员域权限（Q-11）。
- **授权治理（B-1）**：操作者不得自授（`staff.userId == caller` 403）；`approvedBy` 必须存在、
  启用且具备 `m4:staff:authorize`，且不是操作者/被授权人（伪造/停用 400，无权限/自审 403）；
  `R1/R2`、`surgeryScopes` 含 `*` 或 `asaMax>=ASA4` 必须两名**不同**审批人（缺第二审批人 400），
  双方与审批时间落库（`second_approver_id/second_approved_at`）。

数据范围（m0，复用不新增模型）：

| 范围 | 语义 | 写操作 |
|---|---|---|
| `ALL` | 全院人员/排班/培训 | 允许 |
| `SITE` | 授予院区子树内的启用科室 | 允许（限范围内科室） |
| `DEPT` | 授权科室 | 允许（限范围内科室） |
| `SELF` | 仅本人档案/排班/培训（按 m0 userId） | **403**（避免本人自改科室/职称/授权） |
| `ROOM`/无范围 | 不适用人员域（含 `/v1/staff/departments`） | fail-closed 403 |

- 单对象读取越界与不存在统一 404（不泄露其他科室数据存在性）；科室必须为启用
  `org_type=DEPT` 组织（422）且在范围内（403）。
- 麻醉护士与术者不授予人员域权限（最小权限）；需要时按岗位另行评审（Q-1/Q-11）。

## 16. 人员档案口径

- **工号（`staffNo`）**：`^[A-Za-z0-9._-]{1,32}$`，`lower(btrim())` 院内唯一，**创建后不可改**
  （数据库守卫 55000）；重复 409。
- **userId**：可空；非空时一人一档案（部分唯一索引），重复 409；创建后不可改。写入时校验
  用户存在、启用（`UserAdminService`）；用户已有组织绑定时档案科室必须在其归属内（422），
  未绑定账号允许建档（AD/人事接入前，Q-7）。
- **siteId**：可空；非空时必须是合法 UUID；SITE 范围用户只能使用授予院区（403）。
  院区存在/启用与“科室同院区”校验依赖 m0 组织查询扩展，当前由组织主数据与 AD/人事同步
  保证（Q-7）。
- **科室/职称/职级/专长**：科室必填且须为启用 DEPT；职称/职级当前为自由文本（字典【待确认】Q-8）；
  专长最多 20 项、每项 ≤32 字（去重，仅参考，不替代授权校验）。
- **执业资质**：类型 `PRACTICE_LICENSE/SPECIALTY_CERT/NURSE_LICENSE/OTHER`；同一人员同一
  证书号唯一；`validFrom` 必填、`validTo` 可空（长期有效）且不得早于 `validFrom`；
  `expired` 由服务端按 `validTo` 与当天计算；**提交列表为最新快照**（替换式维护，Q-6）。
- **在职状态**：`ACTIVE`（在职）/`INACTIVE`（离职/停用，档案只读历史）；离职后授权校验
  返回 `allowed=false` + `STAFF_INACTIVE`，不得排班/登记培训（422）。
- **乐观锁**：更新必须回传 `version`，过期 409；不提供删除（人员与质控留痕需可回看）。

## 17. 分级授权表与校验口径

### 17.1 授权表（R1–R5）

**R1–R5 的业务含义与“级别 → 术式范围/年龄段/ASA”默认映射【待确认】（Q-1）**：系统
**不内置**映射，范围以审批人录入的显式字段为权威。下表只说明字段语义，**不是授权目录**：

| 级别 | 本实现中的语义 | 可实施术式范围 | 年龄段 | ASA 上限 |
|---|---|---|---|---|
| R1 | 级别标签（最高）【待确认】 | 审批人录入 `surgeryScopes` | 审批人录入 `ageMin/ageMax` | 审批人录入 `asaMax` |
| R2 | 级别标签【待确认】 | 同上 | 同上 | 同上 |
| R3 | 级别标签【待确认】 | 同上 | 同上 | 同上 |
| R4 | 级别标签【待确认】 | 同上 | 同上 | 同上 |
| R5 | 级别标签（基础）【待确认】 | 同上 | 同上 | 同上 |

- `surgeryScopes`：`*` 表示全部术式，否则为术式类别/术式编码（大小写不敏感精确匹配）。
- `asaMax`：ASA1–ASA5（ASA VI 不纳入，Q-2）。
- 院方确认授权目录后，可配置 `anesop.m4.staff.authorization.level-policies` 叠加**级别上限**：
  授予/复评超限 422；校验超限追加 `LEVEL_POLICY_EXCEEDED`。默认未配置任何策略。

### 17.2 校验口径（`POST /v1/staff/authorization-checks`）

按顺序判定并**返回全部**未通过原因（多原因一次纠正）：

| 原因码 | 触发条件 | 说明 |
|---|---|---|
| `STAFF_INACTIVE` | 人员 `employmentStatus != ACTIVE` | 离职/停用不得执业 |
| `NO_AUTHORIZATION` | 无任何授权版本 | 未授权不得执行 |
| `AUTHORIZATION_REVOKED` | 当前版本 `status=REVOKED` | 撤销后拒绝 |
| `NOT_YET_VALID` | 判定日 < `validFrom` | 未生效 |
| `EXPIRED` | 判定日 > `validTo` | 过期 |
| `SURGERY_SCOPE_MISSING` | 传入 `surgeryTypeId` 且不在范围内 | 无授权术式 |
| `AGE_OUT_OF_RANGE` | 传入 `age` 且超出 `[ageMin, ageMax]` | 年龄超范围 |
| `ASA_EXCEEDS_LIMIT` | 传入 `asa` 且高于 `asaMax` | ASA 超上限 |
| `INPUT_INCOMPLETE` | 术式/年龄/ASA 缺失且未在 `notApplicable` 声明 | **fail-closed**，不再静默跳过 |
| `LEVEL_POLICY_EXCEEDED` | 配置了级别策略且请求/范围超限 | 院方策略上限 |

- 三个判定维度必须显式提供；确实不适用时在 `notApplicable` 声明 `SURGERY_TYPE/AGE/ASA`，
  缺失且未声明 → `INPUT_INCOMPLETE`（B-3）。
- `at` 可回溯判定（默认当前时间）：按 `approved_at <= at AND valid_from <= at 当日 AND
  (revoked_at IS NULL OR revoked_at > at)` 选择**当时生效版本**，不会把后来扩大的授权范围
  套到过去时点（B-4）；撤销记录 `revoked_at` 且**不追溯既往**（Q-21）。
- 人员不存在或不在数据范围：422（集合型操作，契约不声明 404）。
- **`allowed=true` 只表示授权范围允许，不表示该台麻醉在临床上适宜**（H-06）：最终由麻醉
  医师判断并留痕；M1/M5 集成建议“排程即提示 + 术中复核”，落点见 Q-10。
- 进程内集成：`StaffAuthorizationService.evaluate(UUID staffId, String surgeryTypeId,
  Integer age, String asa, Instant at)` 要求三维齐全；不适用维度用
  `evaluate(..., Set<AuthorizationDimension> notApplicable)` 显式声明，返回
  `AuthorizationDecision`（含命中的 `authorizationId`），不依赖 HTTP 上下文。

### 17.3 append-only 历史

- `PUT /v1/staff/{id}/authorizations`：`action=GRANT` 写入新版本；`action=REVOKE` 保留上一
  版本范围快照并把新版本标记 REVOKED（必须给原因，记录 `revoked_at/revoked_by`）；`version`
  必须等于当前版本（无授权传 0），过期 409；重复撤销 409；人员离职后**不得授予**（422），
  但**允许撤销**（离职清理，S-2）。失败审计按实际 action 记 `AUTH_GRANT`/`AUTH_REVOKE`。
- **双人复核落地**：R1/R2、范围 `*` 或 ASA≥4 的授予/撤销都要求两名不同审批人；前端在表单中
  显示双人提示并校验两人不同，服务端二次校验（不信任前端）。
- 数据库触发器：授权历史 UPDATE/DELETE/TRUNCATE 一律 55000；同人员 `version` 唯一
  （并发写入由唯一索引兜底 409）。

## 18. 排班与冲突检测

- 班次：`DAY`（白班）/`NIGHT`（夜班）/`ON_CALL`（备班），对应方案书“日常班/值班/备班”；
  **三班制/节假日班/日结班结双轨【待确认】（Q-3）**。
- 日期为 Asia/Shanghai 自然日；术间主数据归 M1，本域只保存 `roomId` 并做冲突检测。
- **冲突（409 `STATE_CONFLICT` + `details[]`）**：
  - 同一人员同一日期同一班次（`field=staffId`）；
  - 同一术间同一日期同一班次（`field=roomId`）；
  - `reason` 含日期、班次与冲突排班标识；多冲突一次返回；已取消（CANCELLED）不占用名额。
  - 数据库部分唯一索引 `uk_staff_schedule_staff_slot` / `uk_staff_schedule_room_slot`
    是并发兜底（仅 PLANNED 生效）。
- 更新（PUT）冲突规则同新建（排除自身），乐观锁过期 409；已取消不得更新/复活（409 + 55000）；
  取消（POST cancel）保留记录与原因并释放名额。
- **换班审批流未实现（Q-5）**：本期只有变更/取消留痕与审计，没有审批节点与通知；
  术间是否允许多人（麻醉医师 + 护士）见 Q-4。
- 排班不校验“人员是否具备该术间所需授权”：授权校验是独立集成点（§17.2），排程侧应在
  创建/调整台次时调用 `evaluate`（Q-10）。

## 19. 培训与到期提醒

- 记录：`courseCode`（`^[A-Za-z0-9._-]{1,64}$`）、`courseName`、`completedOn`、`expiresOn`（可空）、
  `certificateNo`（可空）；同一人员同一课程编码唯一（409）。
- 校验：完成日期不得晚于今天（400）、到期日期不得早于完成日期（400）。
- 状态（固定 30 天提醒窗口）：`NO_EXPIRY`（无到期日）/`VALID`（>30 天）/`EXPIRING`
  （0–30 天）/`EXPIRED`（<0 天）；同时返回 `daysUntilExpiry`（负值表示已过期）。
- `GET /v1/staff/trainings/expiring?days=30`：`expiresOn <= 今天 + days` 的记录（**含已过期**，
  便于清零），按到期日期升序；`days` 1–365，默认 30（方案书“证书到期前 30 天提醒”）；
  返回 `days` 与 `asOf` 基准日。
- “到期未更新自动降权”未实现（需要授权自动收窄/审批，Q-1/Q-10）：本期只提醒，不自动改授权。

## 20. 审计

| action | 触发 | detail 白名单 |
|---|---|---|
| `STAFF_CREATE` | 新建档案 | staffId/staffNo/departmentId/employmentStatus/qualificationCount |
| `STAFF_UPDATE` | 更新档案 | staffId/staffNo/departmentId/departmentChanged/employmentStatus/变更标记/qualificationCount/version |
| `AUTH_GRANT` | 授予/复评 | staffId/authorizationId/level/version/surgeryScopeCount/ageMin/ageMax/asaMax/validFrom/validTo/status |
| `AUTH_REVOKE` | 撤销 | 同上（status=REVOKED） |
| `SCHEDULE_CREATE` | 新建排班 | scheduleId/staffId/scheduleDate/shift/departmentId/roomId/status |
| `SCHEDULE_UPDATE` | 调整排班 | 同上 + version |
| `SCHEDULE_CANCEL` | 取消排班 | scheduleId/staffId/scheduleDate/shift/departmentId/status/version |
| `TRAINING_RECORD` | 登记培训 | trainingId/staffId/courseCode/completedOn/expiresOn |

审计**不含姓名、证书号原文、联系方式与患者标识**；失败路径（400/409/422）写 `result=0`；
403 由 `ApiExceptionHandler` 写 `ACCESS_DENIED`。

## 21. 测试与验收

- 后端单测（m4-impl 人员域 81 例；m4-impl 合计 110 例）：档案（工号/资质/乐观锁/SELF
  只读/列表分页）、授权（版本链、撤销、级别策略、校验矩阵、字段校验）、排班（双冲突明细、
  取消、已取消不可复活）、培训（状态计算、窗口校验、课程唯一）、数据范围守卫
  （ALL/SITE/DEPT/SELF/ROOM、越界 404、科室 400/422/403）、jsonb 编解码与失败审计。
- 覆盖率（m4-impl，JaCoCo，全量 verify 输出为准）：行/分支 ≥80/70。
- 集成（`M4StaffApiIntegrationTest` 10 例，真实嵌入式 PG16）：档案 CRUD/权限 403/DEPT 与
  SELF 范围/越界 404；授权授予-复评-撤销 + 校验矩阵 + append-only 55000 + 离职 STAFF_INACTIVE；
  授权治理（自授/伪造审批人/双人缺失/双人成功/普通单人成功）；`notApplicable` 与回溯版本；
  授权管理员按科室写 + 麻醉医师只读；离职可撤销；ROOM 科室选项 403；userId/siteId 校验；
  排班双冲突 409/details/取消释放/权限；培训到期提醒与审计；V21/V22 表/视图/权限/角色/触发器。
- 前端：Vitest 17 例（档案 4/授权 7/排班 3/培训 3）；E2E `apps/web/e2e/m4-staff.spec.ts`
  1 条（授予授权 → 校验通过 → 排班创建 → 冲突提示）。
- 契约：`bash scripts/gen-contracts.sh`、`--java`、`bash scripts/contract-check.sh`
  （spectral/breaking/HEAD baseline/drift）全 PASS。

## 22. 待确认（人员域 Q）

1. **Q-1 R1–R5 映射**：各级别含义、可实施术式、年龄段、ASA 上限默认值；是否要求值班资质/
   独立值班年限。
2. **Q-2 ASA 范围**：ASA1–ASA5 是否满足；ASA VI/急诊 ASA 是否纳入。
3. **Q-3 班次定义**：两班/三班、节假日班、备班是否占用术间。
4. **Q-4 术间多人**：同术间同班次是否允许麻醉医师 + 护士同时排班（当前唯一索引只允许一人）。
5. **Q-5 换班审批**：换班申请/科主任审批/通知/撤销流程。
6. **Q-6 资质历史**：执业资质是否必须保留历史版本（当前替换式维护）。
7. **Q-7 AD/人事同步**：主数据方向、频率、冲突处理、离职联动（方案书 I-12）。
8. **Q-8 字典**：职称/职级/术式字典与白名单校验；术式字典归属 M1/M0。
9. **Q-9 绩效基础数据**：例数/台次/时长/难度系数/夜班加班统计归属（当前归 M4-M16-01）。
10. **Q-10 校验集成落点**：排程即拦还是术中提示、拒绝后的覆盖审批流、到期未更新是否自动降权。
11. **Q-11 权限范围**：麻醉护士/术者/质控专员是否需要人员域只读（当前不授予）。
12. **Q-12（评审 Q-2）撤销是否追溯**：当前按“撤销不追溯既往”实现（`revoked_at` 之后拒绝，
    之前按当时版本判定）；院方是否要求撤销回溯到授权生效日、是否需红冲/补正流程待确认。
13. **Q-13（评审 Q-1）资质吊销/过期是否阻断授权**：当前 `staff_qualification.status/valid_to`
    只用于档案展示与到期提醒，**不参与**授权判定（V22 已纠正 V21 注释）；是否新增
    `QUALIFICATION_INVALID` 原因码与“执业资格必须有效”规则待医务处/人事确认。
