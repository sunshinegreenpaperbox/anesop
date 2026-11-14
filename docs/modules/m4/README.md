# m4 — 药品目录与麻精五专（E-M15）

> 状态：M4-M15-01 完成（2026-11-14），契约 `drug.yaml 0.1.0`、迁移 V20、m4-api/impl、
> 前端 `apps/web/src/modules/m4`。本文件是 M4 药品域的模块口径说明；实现细节以契约与
> 迁移为准，冲突时按“契约 → 迁移 → 本文件”的顺序裁决。

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
