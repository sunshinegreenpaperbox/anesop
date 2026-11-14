# m4 — 药品目录与麻精五专（E-M15）· 人员档案与分级授权（E-M13）· 工作量与多维报表（E-M16）

> 状态：M4-M15-01 完成（2026-11-14，契约 `drug.yaml 0.1.0`、V20、m4-api/impl、前端两页）；
> M4-M13-01 完成（2026-11-14，契约 `staff.yaml 0.1.1`、V21 + V22、m4_staff/授权校验/
> 排班冲突/培训、前端四页）；M4-M14-01 完成（`material.yaml 0.1.0`、V23、耗材/UDI/SPD）；
> M4-M16-01 完成（2026-11-14，契约 `report.yaml 0.1.0`、V24 data-only、工作量/多维报表/CSV
> 导出、前端报表页）。本文件是 M4 域的模块口径说明；实现细节以契约与迁移为准，冲突时按
> “契约 → 迁移 → 本文件”的顺序裁决。§1–§13 为药品/五专（E-M15），§14–§22 为人员域
> （E-M13，模块目录仍为 `modules/m4`，schema `m4_staff`），§23–§32 为耗材/UDI/SPD（E-M14），
> §33–§41 为工作量与报表（E-M16，口径 report-1.0.0，无独立 schema）。

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

---

# 耗材主数据、出入库、UDI 追溯与 SPD 适配器（M4-M14-01 / E-M14）

## 23. 数据归属与边界

- 新增 schema `m4_material`（V23）：`material`（主数据）、`material_stock`（批次库存）、
  `stock_movement`（append-only 流水）、`udi_instance`（一物一码）、`spd_sync_log`（EXT-03 留痕）。
- 契约 `packages/contracts/openapi/material.yaml` 0.1.0（12 路径 / 15 operation / 22 schema）；
  Java/TS 由契约生成（api-contracts 第 8 个 execution、`gen-contracts.sh`/`contract-check.sh`
  DOMAINS 第 8 域）。
- 与其他模块：**不建跨模块外键**；UDI 使用通过 m4-api 端口 `RecordVisibilityPort`
  （装配层 `M2RecordVisibilityAdapter` 用 `m2-api` `AnesthesiaRecordService.get` 实现）
  校验记录存在/可见，只保存 `record_id/schedule_id` 逻辑关联；不访问 m2 表，避免 m4→m2
  反向依赖形成模块循环（ArchUnit 边界用例覆盖）。
- 耗材域不做计费（计费/账务归 M5/财务域，Q-5）；SPD 属 EXT-03 模拟，不直连生产库。

## 24. 权限与数据范围

| 权限点 | 能力 | 最小授予（V23） |
|---|---|---|
| `m4:material:read` | 目录、批次库存、流水、UDI 追溯、SPD 状态 | SYS_ADMIN、SYS_OR_MANAGER、SYS_NURSE_ANESTHETIST |
| `m4:material:write` | 目录维护、普通出入库、触发 SPD 同步 | SYS_ADMIN、SYS_OR_MANAGER |
| `m4:udi:manage` | UDI 登记（逐件入库）、UDI 使用绑定 | SYS_ADMIN、SYS_OR_MANAGER、SYS_NURSE_ANESTHETIST |

- 数据范围：目录全院可读；**批次库存/流水/UDI 实例按科室** ALL/SITE/DEPT 隔离，
  SELF/ROOM/无范围 fail-closed 403；科室必须是启用 `org_type=DEPT` 组织（422）且在范围内（403）。
- 单对象越界与不存在统一 404（不泄露其他科室数据存在性）。

## 25. 主数据口径

- 编码 `^[A-Za-z0-9._-]{1,64}$` 且创建后不可改；编码/名称 trim + 忽略大小写院内唯一；
  UDI-DI 非空时也唯一（同一 DI 只能对应一个院内耗材，扫码反查主数据的语义前提）——冲突 409。
- `highValue=false→true` 时若仍有数量 > 0 的批次库存 → 409（先清零或改走逐件登记，
  避免出现无法追溯的高值库存）。
- `expiryManaged=true` 时出入库必须带批次与效期；标志由主数据维护，不按分类推断。
- 更新为乐观锁（必须回传 `version`，过期 409），数据库守卫 version 只增、编码/创建留痕不可改。
- 启停：`PATCH /v1/materials/{materialId}/status`（ACTIVE/INACTIVE，`m4:material:write`）；
  状态未变化幂等返回（version 不变），真正变更 version +1；停用后拒绝新的出入库与 UDI 登记，
  历史库存/流水/追溯只读保留（审计 `MATERIAL_STATUS`）。
- 存在 UDI 实例或数量 > 0 的批次库存时，修改或清空 `udi_di` → 409（避免已登记/在库耗材
  失去扫码反查能力；先将库存清零并经退库/报废流程处理后再改）。

## 26. 出入库与批次库存口径

- 类型：`IN`（加）、`OUT`（减）、`RETURN`（退回，减，可带 UDI 把实例标记 RETURNED）、
  `ADJUST`（带符号调整量，必须填写原因，不得为 0）。
- 库存键：**材料 + 批次 + 科室**；`quantity >= 0`（CHECK 兜底）。
- **RETURN 语义 = 退回供应商/中心库**：库存减少、UDI 实例标记 RETURNED（单向终态），不是
  “院内退库复库/再用”；院内退库（退回二级库后复库或再用）的登记口径待院方确认（§32 Q-7）。
- 并发：入库 `ON CONFLICT DO UPDATE` 原子累加，出库/负向调整 `WHERE quantity >= ?` 原子扣减；
  不足返回 409，`details[]` 给 `available/requested`。
- 扣减前置校验：OUT/RETURN/负向 ADJUST 先按 材料 + 批次 + 科室 定位库存行，**请求效期与
  库存行效期不一致 → 409**（不按请求效期“洗白”过期批次）；**过期批次 OUT → 422**；
  RETURN 携带 `udi` 时以 UDI 实例的批次/效期为唯一扣减依据，请求批次/效期与实例不一致 → 422
  （避免扣错库存行）。
- 效期：效期管理耗材缺效期/已过效期 422（入库、出库、退回与使用均拒绝）。
- 数量：最多 2 位小数（`multipleOf: 0.01`），越界 400。
- 高值耗材：禁止裸数量 IN/OUT/ADJUST（422）；入库走 UDI 登记、使用走 UDI 使用、退回带 UDI。
- 流水 append-only：UPDATE/DELETE/TRUNCATE 一律 55000；更正走反向 ADJUST + 原因。

## 27. UDI 一物一码与追溯口径

- 内部规范编码 `udi = udiDi + "^" + udiPi`；数据库 `(lower(trim(udi_di)), lower(trim(udi_pi)))`
  唯一 + `udi_code` 唯一；路径参数需 URL 编码（`^` → `%5E`）。
- 组合长度 `udiDi + '^' + udiPi ≤ 160`（越界 400）；`udiDi` ≤64、`udiPi` ≤96。
- 登记 = 逐件入库：`udi_instance`（IN_STOCK）+ 批次库存 +1 + IN 流水，三者同事务；
  UDI-DI 必须与耗材主数据一致（422）；重复登记 409。
- 使用：仅 IN_STOCK → USED；绑定 `recordId`（必填）与 `scheduleId`（可选，必须与记录所属台次
  一致）；记录不存在/不可见 422；重复使用 409；过期实例 422；使用同时写 OUT 流水并扣库存；
  实例效期与批次库存行效期不一致 → 409（数据不自洽时拒绝扣减）。
- 退回：RETURN 携带 `udi` 时实例置 RETURNED（终态），并按实例批次/效期扣减；院内复库/再用
  不在本期状态机内（§32 Q-7）。
- 状态机：IN_STOCK → USED / RETURNED，终态不可再变（触发器 55000）；身份列不可改。
- 追溯链 `GET /v1/materials/udi-instances/{udi}`：instance + material 摘要 + movements
  （按发生时间倒序）+ record（recordId/scheduleId）；只回传标识，不含患者信息。

## 28. SPD 适配器（EXT-03，SIMULATED）

- `GET /v1/materials/spd-adapter/status`：只读最近一次同步结果（不产生日志）；无日志时默认
  `available=true`；失败后 `degraded=true` 且 `pendingSyncCount` 为最近一次成功之后的失败条数。
- `POST /v1/materials/spd-adapter/sync`：无外部 I/O 的模拟同步；成功/失败均返回 200，
  失败 `success=false, degraded=true`（本地库存继续可用）；写 `spd_sync_log` 与审计 `SPD_SYNC`。
- 数据范围：状态查询与库存一致，SELF/ROOM/无科室维度 fail-closed 403（契约承诺，非 200）。
- 演练/测试降级：`anesop.m4.spd.simulated-available=false`（集成测试独立上下文验证）。
- 不返回外部凭据、供应商明细与患者信息。

## 29. 审计

| action | 触发 | detail 白名单 |
|---|---|---|
| `MATERIAL_CREATE` | 新建耗材 | materialId/code/highValue/expiryManaged |
| `MATERIAL_UPDATE` | 更新耗材 | materialId/code/highValue/highValueChanged/expiryManaged/version |
| `MATERIAL_STATUS` | 启用/停用耗材 | materialId/code/fromStatus/status/version |
| `STOCK_MOVE` | 出入库（含 UDI 登记/使用产生的流水） | movementId/movementType/materialId/batchNo/quantity/departmentId/highValue/udiLinked |
| `UDI_REGISTER` | UDI 逐件登记 | udiDi/materialId/batchNo/departmentId/status |
| `UDI_USE` | UDI 使用绑定 | udiDi/materialId/recordId/scheduleId/status |
| `SPD_SYNC` | SPD 模拟同步 | adapterCode/mode/success/degraded |

审计**不含 UDI 原文全串、患者信息、供应商凭据**；失败路径（400/409/422）写 `result=0`，
`resourceId` 用 materialId 或 UDI-DI 摘要，detail 附 materialId/batchNo/movementType/udiDi，
可定位到具体耗材、批次与器械但不会落 UDI-PI/序列号；403 由 `ApiExceptionHandler` 写
`ACCESS_DENIED`。

## 30. 前端

- `MaterialCatalogView.vue`（`/m4/materials`）：耗材目录列表/检索/新建/编辑（高值/效期标志）、
  启停（`PATCH .../status`，停用后历史只读）。
- `MaterialStockView.vue`（`/m4/materials/stock`）：科室选择、批次库存、出入库登记、流水、
  SPD 状态卡（触发模拟同步）。
- `MaterialTraceView.vue`（`/m4/materials/trace`）：扫码式 UDI 输入占位、追溯链展示、
  UDI 登记（逐件入库）、UDI 使用（绑定记录/台次）。
- 菜单按 `m4:material:read` 显示；写操作按 `m4:material:write` / `m4:udi:manage` 隐藏。

## 31. 测试与验收

- m4-impl 单测：耗材目录 13、出入库 18、UDI 追溯 14、SPD 5、数据范围守卫 6（合计 56）；
  覆盖率（JaCoCo，`clean verify` 报告实测）：m4-impl 合计行 92.66%、分支 72.53%；
  新增四个实现类行覆盖 94.17%–97.40%、分支 66.67%–87.50%。
- 集成（`M4MaterialApiIntegrationTest` 9 例 + `M4MaterialSpdDegradedApiIntegrationTest` 1 例，
  真实嵌入式 PG16）：主数据唯一/搜索/乐观锁/权限 403、启停端点与幂等；数量不足 409 + details、
  同批次效期不一致 409、过期批次 OUT 422、数量 scale 400、ADJUST 原因 400、高值裸数量 422、
  append-only 55000；UDI 重复登记/重复使用 409、UDI-DI 不一致 422、组合长度 160/161 边界、
  记录不可见 422、使用绑定与追溯链、RETURN 批次错位 422、存在 UDI 实例时改/清 UDI-DI 409；
  DEPT/ROOM 数据范围与 404、SPD 状态 ROOM 403；失败审计可定位（udiDi 摘要/批次，不含 UDI 全串）；
  SPD 成功/降级 + 审计 + 日志；V23 表/权限/最小授权/索引/触发器（material version、UDI 身份列/
  终态/非法迁移）/CHECK。
- 前端：Vitest 13 例（目录 5 / 出入库 4 / UDI 追溯 4）；E2E `apps/web/e2e/m4-material.spec.ts`
  1 条（UDI 登记入库 → 绑定记录使用 → 追溯链 → SPD 状态卡）。
- 契约：`bash scripts/gen-contracts.sh`、`--java`、`bash scripts/contract-check.sh`
  （spectral/breaking/HEAD baseline/drift，8 域）全 PASS。

## 32. 待确认（耗材域 Q）

1. **Q-1 UDI 解析规则**：GS1/HIBCC 分隔符、校验位、PI 内嵌批号/效期/序列号解析、扫码枪
   前后缀；本期只做字符串唯一，不解析（ADR-0020）。
2. **Q-2 批次策略**：同批号跨供应商合并、跨科室调拨/合并记账、近效期阈值与预警渠道。
3. **Q-3 高值口径**：金额阈值与高值目录范围（本表不内置金额字段）。
4. **Q-4 SPD 报文/频率**：EXT-03 真实接口协议、同步频率、失败重试与对账。
5. **Q-5 计费衔接**：UDI 使用是否触发计费/退费红冲（M5/财务域）。
6. **Q-6 角色分工**：高值到货验收由护士还是库管执行（当前 udi:manage 同时授予 OR 管理员
   与麻醉护士，职责分离口径待院方确认）。
7. **Q-7 院内退库/复库**：本期 RETURN 只表示“退回供应商/中心库”（UDI 终态 RETURNED，单向）；
   退回二级库后复库再用、退库后重新入库是否复用原 UDI/批次/效期、是否需退库单与审批，
   待院方确认（ADR-0020 Q-6）；确认前不得用 RETURN + 重新登记绕过一物一码终态（Q-7）。

## 33. 报表域数据归属与边界（E-M16）

- **无独立 schema/表**：V24 是 **data-only** 迁移（只加权限点，无 DDL）；工作量与多维报表
  都是对既有数据的**只读聚合**，不落库缓存、不建物化视图（ADR-0021）。
- **统计单元**：M1 排程台次（`m1_sched.schedule` + `surgery_application`）、M2 麻醉记录单
  （`m2_anes.anesthesia_record` 的 `started_at/ended_at/created_by`）、M4 人员档案
  （`m4_staff.staff_profile` 的 `user_id/staff_no/name`，用于 STAFF 维度）。
- **跨模块只读**：m4-api 端口 `ReportCaseSource`，由装配层 `ReportCaseSourceAdapter`
  用**单条只读 SELECT** 投影（不依赖 m1/m2 的 Mapper/实体，不写任何表，不做时区换算）；
  m4-impl 只依赖端口，不依赖 m1/m2，避免模块环（ArchUnit 覆盖）。这是 M4-M14-01
  `RecordVisibilityPort` 之后的第二个装配层端口。
- 契约：`packages/contracts/openapi/report.yaml` 0.1.0（3 路径 / 3 operation / 8 schema，
  第 9 域）；Java/TS 由契约生成（api-contracts 第 9 个 execution、gen-contracts /
  contract-check DOMAINS 第 9 域）。

## 34. 权限与数据范围

| 权限点 | 能力 | 最小授予（V24） |
|---|---|---|
| `m4:report:read` | 工作量统计、多维报表 | SYS_ADMIN、SYS_OR_MANAGER、SYS_QC_MANAGER |
| `m4:report:export` | CSV 导出（另写审计 `REPORT_EXPORT`） | SYS_ADMIN、SYS_OR_MANAGER、SYS_QC_MANAGER |

- 数据范围（fail-closed）：`ALL` 全院；`SITE`/`DEPT` 按台次申请科室（或院区）；
  `ROOM` 按术间；`SELF` 只含**本人创建麻醉记录单**的台次。
- 无可用范围、SELF 缺用户标识、`deptId` 超出组织范围 → 403（集合查询越权语义）；
  空数据不是 403，返回 0 与空数组。
- `deptId` 只是收窄条件：ROOM/SELF 范围可自由按科室筛选（仍受自身范围限制），绝不因
  `deptId` 扩大可见范围；科室下拉从 DEPT 维度桶派生（不需 `m1:schedule:read`）。
- 临床个人工作量视图未开放（绩效口径未确认，D-21/Q-1）。

## 35. 工作量口径（report-1.0.0）

- **时间**：`Asia/Shanghai` 自然日；`from`/`to` 必填闭区间，跨度（含首尾）**≤ 366 天**
  （超过 400）；`from`/`to` 非法日期 400；`to < from` 400。
- **台次**：M1 台次 `deleted=false` 且 `status ≠ CANCELLED`，按计划开始时间 `start_at`
  的本地日期归集；跨日台次只计首日、不拆分。已取消台次不计台次/时长。
- **麻醉时长**：麻醉记录单 `started_at`..`ended_at` 之差**向下取整到分钟**之和；
  无记录单/未结束/时间缺失计 0，并单列 `unrecordedCaseCount`（有台次无时长）。
- **加权台次**：`weightedCaseCount = Σ 台次权重`；默认 **1.0**，可按术式编码配置
  `anesop.m4.report.surgery-weights.<code>`（未配置取默认；权重必须 > 0，启动校验）。
  **不内置绩效公式/难度系数/金额**（D-21，权重口径【待确认】Q-1）。
- **术式分布**：桶内按术式编码聚合台次与时长，按台次倒序、编码升序，最多 50 项；
  编码缺失归 `UNSPECIFIED`（桶 key 仍为术式维度兜底桶）。

## 36. 多维报表口径

- 维度白名单：`DEPT`/`STAFF`/`SURGERY`/`ROOM`，白名单全集 4 个、去重后 1–4 个；指标白名单：
  `caseCount`/`anesthesiaMinutes`/`weightedCaseCount`，白名单全集 3 个、去重后 1–3 个；非法/空 400。
- 分组与排序：按请求维度组合分组（未匹配进入 `UNASSIGNED`/`UNSPECIFIED`），按维度声明顺序
  + 维度值升序稳定排序，兜底桶固定最后；各维度台次之和与总计守恒。
- 分页：`page` 从 1 开始、`size` 1–200（默认 20）；`total`/`totalPages` 与 `totals` 覆盖
  **全部分组**（不是当前页）；空数据 `total=0`、`totalPages=0`、`rows=[]`。
- 字段归属：DEPT→`deptId/deptName`；STAFF→`staffId/staffNo/staffName`（院内在册人员，
  非患者标识）；SURGERY→`surgeryCode/surgeryName`；ROOM→`roomId/roomNo`；未请求维度为 null。

## 37. 导出（CSV）

- 列头：`report,level,dimension,key,label,caseCount,anesthesiaMinutes,weightedCaseCount,
  unrecordedCaseCount,surgeryCode,surgeryName,from,to,timezone`；`level` ∈
  `TOTAL`（仅 workload）/`BUCKET`/`SURGERY`/`ROW`。
- `report=workload` 需 `dimension`；`report=multi` 需 `dimensions` 与 `metrics`，导出**全部分组**，
  行数上限 50000（超过 400）。`dimension=SURGERY` 时桶即术式，不再重复输出 `SURGERY` 行。
- UTF-8 **BOM** + 首行列头 + `text/csv` 流式；参数校验与数据范围解析在写字节前完成，
  非法参数仍返回 JSON 400/403（不会在已提交响应中抛错）。
- **防公式注入**：以 `=`/`+`/`-`/`@`/制表符/回车开头的单元格以**前导单引号中和**
  （如 `'=SUM(1,2)`），必要时按 **RFC 4180** 加双引号并转义内部引号。
- **脱敏**：只含科室/术间/术式/院内在册人员工号与姓名与聚合值；**不含**患者姓名、住院号、
  院内患者号、身份证号、手机号、术前诊断、HIS 申请号。
- 审计：导出 `REPORT_EXPORT`（detail：report/format/日期/维度/指标/行数），查询 `REPORT_QUERY`；
  detail 不含 PHI。

## 38. 审计

| 动作 | 触发 | detail（白名单） |
|---|---|---|
| `REPORT_QUERY` | 工作量/多维查询 | report、from、to、timezone、deptId?、dimension(s)、metrics、group/bucketCount、caseCount |
| `REPORT_EXPORT` | CSV 导出（成功） | report、format、from、to、timezone、deptId?、dimension(s)、metrics、dataRows/groupCount |

审计只记聚合与筛选条件，不记患者标识、不记 SQL、不记人员姓名。

## 39. 前端

- `/m4/reports`（权限 `m4:report:read`）：日期范围（默认近 30 天）、工作量维度选择、多维维度/
  指标多选、科室筛选（DEPT 桶派生）、查询、工作量汇总卡 + 维度表（含术式分布）、多维表
  （含动态指标列、合计与分页）；导出按钮仅 `m4:report:export` 可见。
- 前端预校验跨度 ≤ 366 天与维度/指标非空；服务端仍为权威校验（400 文案直接展示）。
- 空数据展示空态；403/错误展示统一 `ApiError`（code + traceId）；无权限展示权限态不发请求。
- Vitest 7 例（加载/权限态/只读/错误/空态/两种导出）；E2E 1 条（筛选切换 → 查询 → 导出工作量 CSV）
  + 1 条无权限 403 守卫。

## 40. 测试与验收

- 单测（m4-impl `ReportServiceImplTest`，25 例）：手算台次/时长/加权/无时长、维度桶与术式
  分布排序、兜底桶最后、多维分页与全量合计、白名单/366 天/日期/分页参数 400、
  DataScope（denied/DEPT 空/DEPT 过滤/SELF/越界 403）、CSV BOM/列头/行数/防注入/无 PHI、
  空数据 0、权重启动校验、`minutes`/`hasDuration`/`isFormulaRisk` 边界；装配层
  `ReportCaseSourceAdapterTest` 2 例（空范围 fail-closed、null 过滤器不查库）。
- 集成（`M4ReportApiIntegrationTest`，真跑嵌入式 PG16 + Flyway V1–V24，6 例）：固定 SYNTHETIC
  台次/记录单手算对照（3 台次/215 分钟/3.5 加权/1 无时长；契约示例为另一组合成数据
  3 台次/180 分钟）、DEPT/SELF 数据范围与越界 403、
  STAFF 工号+姓名与兜底桶、多维分页与合计、CSV BOM/列头/6 数据行/`'=SUM(1,2)` 防注入/
  无 PHI/审计 detail、空数据 0、366 天边界、V24 权限点与 6 条授权。
- 性能（本机基线 2026-11-15）：5000 台次 / 366 天区间应用内聚合 **约 60ms（实测 59–63ms）**
  （单测 benchmark，断言 < 5000ms）；接口端到端为单条索引扫描 SQL + 同一聚合路径，
  距“月度报表 < 10s”留有余量。
- 覆盖率（本机 JaCoCo，2026-11-15）：报表包行 **95.4%**、分支 **80.4%**（目标 ≥80%/≥70%），
  m4-impl 模块整体行 93.0%、分支 73.7%；契约 3 个端点 100% 有用例。

## 41. 待确认（报表域 Q）

1. **报表域 Q-1 绩效权重与难度系数**：术式权重来源（绩效方案/手术分级）、ASA/年龄/急诊加成、
   二次分配规则；本期默认 1.0 + 可配置映射，不内置公式（ADR-0021 Q-1）。
2. **报表域 Q-2 时长口径**：是否扣除麻醉准备/复苏时间、跨零点展示、是否以设备/事件时间为准
   （当前为记录单 `started_at/ended_at`）。
3. **报表域 Q-3 数据来源**：`STAFF` 用记录单创建人还是麻醉排班/实际执行人；`DEPT` 用申请科室
   还是手术科室/患者科室；`SURGERY` 用 HIS 编码还是院内手术分级编码。
4. **报表域 Q-4 报表锁定/修订**：月度报表锁定、修订审批与新版本（方案书 §2.4.7）；本期不落库，
   口径变更后历史报表不自动重算。
5. **报表域 Q-5 导出格式与个人视图**：是否需要 Excel/PDF、定时生成与推送；是否开放医师本人
   工作量视图（涉及绩效口径与角色授权）。
6. **报表域 Q-6 例数 vs 台次**（评审 Q-1）：方案书 §2.7.2 同时出现“例数/台次”，本期统一按
   **台次**（M1 排程台次，非取消）统计，不按患者/住院次去重；是否另出“例数”指标、分母口径
   （在院/手术例数）与患者去重规则待院方确认。
7. **报表域 Q-7 SELF 范围角色授予**（评审 Q-2）：V24 未给 SYS_ANESTHESIOLOGIST 授予
   `m4:report:read`（工作量属管理口径，D-21）；SELF 范围目前只对合成测试角色开通。本期是否
   给麻醉医师 `m4:report:read` + SELF 数据范围（本人工作量视图）待院方确认。
8. **报表域 Q-8 workload 导出上限**（评审 Q-3）：当前仅多维导出有 50000 行硬上限；工作量导出
   为“TOTAL + 维度桶 + 术式分布”，行数受维度基数与每桶 50 项术式分布约束，未设统一行数上限。
   是否统一设置上限（如 50000 行）或按桶数/台次预检，待压测与院方确认。

## 42. 质控域数据归属与边界（E-M17）

- **归属**：指标定义/结果、不良事件与 RCA、病历抽查与整改全部落在 `m4_qc` schema
  （`qc_indicator`/`qc_indicator_result`/`adverse_event`/`adverse_rca`/`chart_review`/
  `rectification`，V25），只经 `m4-api` 对外暴露；其他模块不得直连本域表。
- **数据来源**：指标计数来自 M2 排程台次、M3 麻醉记录单/术前访视/三方核查、M4 麻精专册与
  高值耗材流水，以及本域不良事件/病历抽查。m4-impl 不访问其他模块表/Mapper，跨 schema
  只读投影统一放在装配层：`QcIndicatorSourceAdapter`（5 条计数 `SELECT`）与
  `QcCaseReferenceAdapter`（2 条台次/记录单所属科室 `SELECT`，ADR-0022）。
- **不写入其他模块**：质控只做计数与留痕，不回写排程、记录单、药品或耗材表。
- **表只增不删**：迁移 V25 只新增 schema/表/索引/触发器/权限点与 7 条合成参考指标，
  不改写 V1–V24；结构变更走 V26+。

## 43. 权限与数据范围

| 权限点 | 用途 | SYS_ADMIN | SYS_QC_MANAGER | SYS_OR_MANAGER |
|---|---|:-:|:-:|:-:|
| `m4:qc:read` | 指标/结果/不良事件台账/抽查列表 | ✓ | ✓ | ✓ |
| `m4:qc:write` | 登记不良事件、提交 RCA、创建抽查、提交整改 | ✓ | ✓ | ✓ |
| `m4:qc:review` | RCA 复核/闭环、整改闭环 | ✓ | ✓ | — |
| `m4:qc:export` | 上报导出（另写 `QC_EXPORT`） | ✓ | ✓ | — |

- **数据范围**：`ALL` 全院；`SITE`/`DEPT` 按可见科室（`OrganizationQueryService` 收敛）；
  `ROOM`/`SELF` 或无任何可用范围 **403**（质控为科室/全院聚合口径，不做个人质控视图）。
- `deptId` 只收窄不扩大：越界 403；科室不存在/停用/非 DEPT → 422；单对象越界/不存在 → 404。
- 质控指标目录（`GET /v1/qc/indicators`）是全院配置，不按数据范围裁剪，但需 `m4:qc:read`
  且用户必须有可用数据范围（无范围仍 403，避免“无范围账号看全院配置”）。
- 反例：SYS_OR_MANAGER 有 read+write 但无 `export`/`review`，`GET /v1/qc/export` 与
  `POST /rca/{id}/close`、`POST /rectify/{id}/close` 一律 **403**（权限先于资源存在性）；
  集成用例 `qcReadWriteRoleCannotExportOrReview` 固化。

## 44. 质控指标口径（qc-1.0.0）

- **版本化与版本选择**：`qc_indicator(code, version)`；语义/阈值变更必须新增版本行（守卫
  55000；定义 `DELETE`/`TRUNCATE` 同样拒绝 55000）。计算/导出按有效期选择版本：
  `effective_from <= to AND (effective_to IS NULL OR effective_to >= from)`；同一 `code`
  在查询周期内只允许一个生效版本，区间重叠 → **409**（契约以 `x-allow-query-conflict: true`
  显式声明查询类冲突）。查询区间跨版本时按有效期切分为多段，
  同一 `code` 返回多行（每段一个版本，`periodFrom/periodTo` 为切分后的真实区间）。
  结果唯一键 `(indicator_id, period_from, period_to, dept_id)`，`dept_id` 全 0 哨兵表示全院；
  重算覆盖同一快照的分子/分母/值/达标与 `computed_at`，跨版本历史结果独立保留。
- **计算**：`value = round(numerator / denominator × 100, 2)`（PERCENT）或 `× 1000`
  （PER_1000_CASES）；`denominator = 0 → value = 0 且 targetMet = false`；
  `targetMet` 按 `GTE`/`LTE` 与阈值比较；`inTargetStatistics=false` 的指标即使过阈值也固定
  `targetMet=false`（不进达标统计）。分子/分母由服务端计数，不接受客户端传入。
- **7 条合成参考指标**（`provisional=true`，阈值与清单【待确认】）：

| 编码 | 名称 | 类别 | 分子 / 分母 | 数据来源 | 阈值 |
|---|---|---|---|---|---|
| QC-SYN-STRUCT-001 | 麻醉记录单完成率 | 结构 | 已结束记录单台次 / 非取消台次 | M3 记录单 + M2 排程 | ≥95% |
| QC-SYN-PROC-002 | 术前访视完成率 | 过程 | 有术前访视台次 / 非取消台次 | M3 访视 + M2 排程 | ≥95% |
| QC-SYN-PROC-003 | 三方核查完成率 | 过程 | 完成 Time-out 与 Sign-out 台次 / 非取消台次 | M3 三方核查 + M2 排程 | ≥95% |
| QC-SYN-PROC-004 | 麻精专册双人签字结构合规率 | 结构 | 双人签字字段完整条数 / 登记条数（`inTargetStatistics=false`，Q-1 前不计入达标） | M4 麻精专册 | ≥100%（仅展示） |
| QC-SYN-OUT-005 | 不良事件上报率 | 结果 | 上报事件数 / 非取消台次 | 本域事件 + M2 排程 | ≥3‰ |
| QC-SYN-PROC-006 | 病历质量抽查合格率 | 过程 | 得分率 ≥90% 抽查数 / 抽查数 | 本域病历抽查 | ≥90% |
| QC-SYN-STRUCT-007 | 高值耗材 UDI 使用登记率 | 结构 | 带 UDI 高值出库条数 / 高值出库条数 | M4 高值耗材流水 | ≥100% |

- **手算示例**（集成用例固定数据，2026-01）：台次 4（麻醉科 3 + 手术室 1）、已结束记录单 3、
  访视 3、三方核查 2、麻精登记 2（双人完整 1）、不良事件 1、抽查 2（合格 1）、高值出库 3
  （带 UDI 2）⇒ 75.00% / 75.00% / 50.00% / 50.00% / 250.00‰（达标）/ 50.00% / 66.67%。
- **未登记编码**按 0/0 呈现“无数据”，不静默丢弃；正式指标由后续版本实现计算器。

## 45. 不良事件状态机与 RCA

```
REPORTED --(提交 RCA)--> RCA --(复核)--> RECTIFYING --(整改完成确认)--> CLOSED
```

- 只允许逐级前进：越级/跳级/重复提交/重复闭环一律 **409**（`details.field=status`）。
- `POST /adverse-events/{id}/rca`：仅 `REPORTED`，一条事件一条 RCA；`timeline` 1–20 条按时间
  升序排序；`dueDate` 不早于事件发生日（422）。
- `POST /rca/{id}/close` **分阶段**：
  1. 事件 `RCA` 且 RCA 未复核：写复核人/结论，事件 → `RECTIFYING`；请求
     `rectificationCompleted` 必须缺省/`false`（否则 409）。
  2. 事件 `RECTIFYING` 且已复核未闭环：必须 `rectificationCompleted=true`（否则 409），
     记录闭环时间，事件/RCA → `CLOSED`；闭环复核人必须与复核阶段一致（复核字段一次性写入）。
- **关联台次**：`scheduleId` 可选；台次不存在/已删除 **422**，属他科 **403**（不校验患者归属）。
- **独立复核**：复核人不得为上报人或责任人（422）。
- **append-only**：事件业务内容（类型/等级/时间/科室/描述/上报人/匿名）、RCA 内容不可改删；
  状态字段只允许前移且 `version +1`；复核/闭环字段只允许从 NULL 写一次；DELETE/TRUNCATE
  与非法状态迁移均由触发器拒绝（55000）。
- **匿名上报**：`anonymous=true` 对外只显示“匿名上报”，内部保留上报人标识用于追溯与独立
  复核校验（匿名范围【待确认】Q-3）。

## 46. 病历质量抽查与整改

- **评分项白名单**：`COMPLETENESS`（完整性）/`TIMELINESS`（时效性）/`SIGNATURE`（签名）/
  `CONSISTENCY`（逻辑一致性），1–12 项且编码不重复；每项 `0 ≤ score ≤ maxScore ≤ 100`。
- **服务端计算**：`totalScore = Σ score`、`maxScore = Σ maxScore`、`defectCount = score < maxScore`
  项数、`grade`：A≥95%、B≥90%、C≥80%、D<80%（合格线 **90%**【待确认】）。
  `score < maxScore` 必须填脱敏缺陷说明（否则 400）；缺陷说明含疑似患者标识 → 400。
- **状态机**：`OPEN --(提交整改)--> RECTIFYING --(复核闭环)--> CLOSED`；重复提交/状态不符 409；
  整改期限不得早于抽查日（422）；复核人不得为整改责任人（422）。
- **关联台次/记录单**：`scheduleId`/`recordId` 可选；目标不存在/已删除 **422**，
  属他科 **403**（只做科室一致性校验，不校验患者归属）。
- **append-only**：抽查评分内容与整改全文不可改删；状态前移 `version +1`；复核/闭环一次性写入。

## 47. 导出（CSV，上报占位）

- `GET /v1/qc/export?type=indicator|adverse|review&from&to&format=csv[&deptId]`，权限
  `m4:qc:export`；UTF-8 **BOM** + 首行列头 + `text/csv` 流式。
- `indicator` 导出先按 qc-1.0.0 计算并留存快照（与 `GET /indicator-results` 同源）；
  列头 `type,code,name,category,indicatorVersion,periodFrom,periodTo,deptId,deptName,numerator,
  denominator,value,unit,thresholdOperator,thresholdValue,targetMet,inTargetStatistics,provisional,timezone`。
- `adverse` **不输出**描述/根因/整改等自由文本，只输出事件号/类型/等级/发生时间/科室/状态/
  RCA 状态/责任人/期限/复核人/闭环时间/匿名标志；`review` 同理只输出评分与整改进度。
- 防公式注入：`=`/`+`/`-`/`@`/制表符/回车开头的单元格以**前导单引号**中和，必要时 RFC 4180
  加引号并转义内部引号；导出写审计 `QC_EXPORT`（type/日期/科室/真实行数/`truncated`）。
- **行数上限 50000**：服务端以 `LIMIT 50000+1` 探测，超限**不截断**并返回 **422**；失败审计
  仍写 `QC_EXPORT`（result=0，detail 含真实 `dataRows`、`truncated=true`、`limit`）。
- 上报 I-10 的字段映射与文件格式未确认（EXT 占位）：本期不直连外部平台、不写外部系统。

## 48. 审计

| 动作 | 触发 | detail（无 PHI） |
|---|---|---|
| `QC_INDICATOR_QUERY` | 读取指标定义 | 指标数、口径版本 |
| `ADVERSE_EVENT_CREATE` | 登记不良事件 | 事件号、类型、等级、科室、是否匿名 |
| `ADVERSE_EVENT_QUERY` | 台账查询 | 状态、科室、分页、条数 |
| `ADVERSE_EVENT_STATUS` | 状态机前移 | 事件号、目标状态 |
| `RCA_CREATE` / `RCA_REVIEW` / `RCA_CLOSE` | RCA 提交/复核/闭环 | 事件号、RCA 标识、时间线条数/阶段 |
| `CHART_REVIEW_CREATE` / `CHART_REVIEW_QUERY` / `CHART_REVIEW_STATUS` | 抽查 | 抽查号、总分/满分/缺陷数、目标状态 |
| `RECTIFICATION_CREATE` / `RECTIFICATION_CLOSE` | 整改 | 抽查号、整改标识、期限/目标状态 |
| `QC_INDICATOR_COMPUTE` | 计算/留存指标结果（查询与导出均写） | 周期、指标数、达标数、是否带科室、来源 `query`/`export` |
| `QC_EXPORT` | 导出 | 类型、日期、科室、真实数据行数、是否超限（`truncated`/`limit`） |

审计不写描述原文、根因/整改/结论原文、患者标识；导出与写操作均在同一请求内落库。

## 49. 前端

- `apps/web/src/modules/m4/views/QcIndicatorView.vue`：指标定义表 + 周期结果表（达标/未达标
  tag、`inTargetStatistics=false` 显示“不计入达标”、分子/分母/值/阈值、provisional 标识）
  + 导出按钮。
- `AdverseEventView.vue`：台账（状态/日期/科室过滤、分页）、登记弹窗、按状态显示
  “提交 RCA / 复核 / 整改闭环”，闭环需勾选“整改完成”。
- `ChartReviewView.vue`：抽查列表（总分/得分率/等级/缺陷数/状态）、新建抽查（四项评分 +
  实时预览总分/等级）、提交整改、整改闭环。
- 路由 `/m4/qc/indicators`、`/m4/qc/adverse-events`、`/m4/qc/chart-reviews`；菜单权限
  `m4:qc:read`；导出按钮需 `m4:qc:export`，复核/闭环需 `m4:qc:review`。
- 质控指标看板只做表格 + 达标状态（ECharts 趋势图留待指标目录确认后另开）。

## 50. 测试与验收

- 单测 `QcServiceImplTest`（35 例）+ `QcAccessGuardTest`（5 例）+ `QcJsonTest`（3 例）：
  7 条指标分子分母映射、换算/四舍五入/分母 0、达标判定、
  数据范围快照、日期与跨度 400、PHI/未来时间 400、匿名上报、RCA 状态机与独立复核、
  阶段 2 完成确认、抽查评分/等级/缺陷、重复评分项/缺缺陷说明 400、整改期限 422、
  导出 BOM/防注入/无自由文本/审计、非法导出参数、**按有效期选择版本/跨版本切分/重叠 409/
  结构合规不计达标/关联台次与记录单 422/403/导出超限 422 与失败审计**。
- 集成 `M4QcApiIntegrationTest`（17 例，真跑嵌入式 PostgreSQL 16 + Flyway V25）：
  指标手算（全院 7 条）、DEPT 数据范围与越界 403、无权限 403、**两版本跨期查询（旧版本/
  新版本/跨版本切分）与重叠 409**、不良事件状态机（重复 RCA/两阶段闭环/重复闭环 409）、
  append-only 55000（内容改删 + 越级迁移 + **指标定义 DELETE/TRUNCATE**）、
  **关联台次/记录单不存在 422、跨科室 403**、**SYS_OR_MANAGER 无 export/review → 403**、
  抽查评分与整改闭环、重复整改 409、非独立复核人 422、导出 BOM/`'=SUM(1,2)`/无 PHI/
  无自由文本、**50001 行导出超限 422 + 失败审计真实行数**、导入非法参数 400、
  V25 权限与 7 条合成指标。
- 前端 Vitest `QcViews.spec.ts`（8 例）：看板/台账/抽查渲染与权限态、错误态、空态、
  区间校验/得分率/等级/导出请求参数；E2E `m4-qc.spec.ts`（1 条）：上报 → RCA → 复核 →
  闭环全链路（两次闭环请求体区分 `rectificationCompleted`）。
- 覆盖率（本机 JaCoCo，2026-11-15 全量 verify）：**m4-impl 模块行 92.8% / 分支 73.3%**
  （门禁 80%/70%）；质控实现包行 91.7% / 分支 70.3%、质控支撑类（`QcAccessGuard`/`QcJson`）
  行 93.8% / 分支 84.6%；契约 11 个端点 100% 有用例（单测 + 集成 + E2E）。

## 51. 待确认（质控域 Q，ADR-0022）

1. **质控域 Q-1 指标清单与阈值**：7 条为合成参考（`QC-SYN-*`），国家/省级正式目录、阈值、
   单位、统计周期与分母定义待院方/质控办确认；确认后升版本替换，不改历史行。
2. **质控域 Q-2 上报格式**：I-10 平台文件/接口格式、字段映射、上报周期；本期 CSV 为 EXT 占位。
3. **质控域 Q-3 匿名化**：匿名上报是否对质控专员匿名；内部追溯标识的可见范围。
4. **质控域 Q-4 抽查规则**：评分项权重与满分分配、合格线（当前 90%）与等级划分。
5. **质控域 Q-5 复核治理**：复核人资质（质控专员/科主任）、是否双人复核、回避范围。
6. **质控域 Q-6 闭环时限**：48h 分级初评与整改期限是否硬校验（当前仅记录与提示）。
7. **质控域 Q-7 指标钻取**：到患者/记录条目的明细钻取权限与脱敏方案（当前只给聚合计数）。
8. **质控域 Q-8 结果锁定**：历史周期是否允许重算、是否需锁定/归档（当前重算覆盖同一快照）。
9. **质控域 Q-9 病历抽查规则**：方案书要求按规则抽样；本期**降级为人工抽检**（抽查人逐份
   录入四项评分），不做随机/分层自动抽样、抽样比例与必查清单硬校验，待质控办确认。
10. **质控域 Q-10 复核人/责任人身份关联**：`reviewerName`/`responsibleName` 为自由文本，
   未与 m0 用户表建立外键/工号关联，无法自动校验资质与在册状态；本期仅做“复核人≠上报人/
   责任人”文本比对，属**已知取舍**，待院方确认是否改为用户选择 + 资质校验（Q-5 相关）。
