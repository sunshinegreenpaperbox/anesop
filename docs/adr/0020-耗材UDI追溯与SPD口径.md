# ADR-0020 耗材主数据、UDI 一物一码追溯与 SPD 适配器口径

- 状态：已接受（UDI 解析规则、批次策略、高值金额口径待院方确认）
- 日期：2026-11-14
- 决策人：自动施工代理（按 AUTONOMY §2 默认决策；不确定口径标【待确认】）
- 关联：M4-M14-01 / 方案书 §2.5（药品与耗材管理）、§3.4（领域模型）、§3.9.2（RBAC 与数据权限）、
  §3.9.3（脱敏）、§3.9 集成 EXT-03（SPD 供应链适配器）/ AGENTS 数据红线与高风险代码清单

## 背景与约束

1. 方案书要求“耗材可追溯、高值一物一码、效期/批次可校验、SPD 可对接”，但**没有**给出
   UDI 编码解析规则（GS1/HIBCC 分隔符、PI 内嵌批号/效期/序列号的提取）、批次记账粒度、
   高值耗材金额阈值/目录范围、SPD 接口报文与同步频率等可执行口径；直接猜测会违反 H-04。
2. Docker 不可用（EXT-08）；SPD/HIS/供应链系统不可用（EXT-03），本期只能做模拟适配器 +
   状态/降级留痕，不直连生产库、不写外部系统。
3. 既有模式：M4 药品域（V20）已建立“主数据 + append-only 五专 + 适配器日志 + 最小权限”的
   分层；耗材域复用同一模式，新增 `m4_material` schema、`material.yaml` 第 8 域。
4. 跨模块只调 `api` 且保持无环：UDI 使用需校验麻醉记录存在/可见。若 m4-impl 直接依赖
   m2-api 会与 m2-impl→m4-api（药品字典）形成模块循环（ArchUnit 禁止），因此 m4-api 声明
   `RecordVisibilityPort`，由 api-bootstrap 的 `M2RecordVisibilityAdapter` 用 m2-api
   `AnesthesiaRecordService.get` 实现并注入；不访问 m2 表、不建跨模块外键。

## 决策

1. **UDI 内部规范编码 = `UDI-DI^UDI-PI`**（`^` 为内部拼接符，路径参数 URL 编码）。
   数据库 `udi_code = udi_di || '^' || udi_pi`（CHECK 兜底），`(lower(trim(udi_di)),
   lower(trim(udi_pi)))` 唯一。本期只做字符串唯一与长度校验，**不解析** DI 内部结构、不校验
   校验位/证书/GUDID；GS1/HIBCC 解析规则【待确认】（Q-1）。
2. **高值耗材一物一码强约束**：`material.high_value=true` 时，
   - 入库只能 `POST /v1/materials/udi-instances`（UDI 实例 + 批次库存 +1 + IN 流水同事务）；
   - 使用只能 `POST /v1/materials/udi-instances/{udi}/use`（绑定记录/台次，IN_STOCK→USED，
     库存 −1 + OUT 流水）；
   - 裸数量 IN/OUT/ADJUST 一律 422；退回必须带 UDI（逐件，数量必须 1），实例标记 RETURNED。
   - 主数据启停走 `PATCH /v1/materials/{materialId}/status`（ACTIVE/INACTIVE，写权限），
     停用后服务层拒绝新出入库/UDI 登记；存在 UDI 实例或库存 > 0 时修改/清空 UDI-DI → 409。
   理由：允许裸数量出入库等于允许“账实分离”，追溯链会断；一物一码的语义要求“件”与“码”同源。
3. **批次库存按 材料 + 批次 + 科室 唯一**，数量 CHECK ≥ 0。出入库变更全部走原子 SQL：
   入库 `ON CONFLICT DO UPDATE` 累加（效期不一致返回 0 → 409），出库/负向调整
   `WHERE quantity >= ?` 扣减（返回 0 → 409，details 给 `available/requested`）。
   扣减前先按 材料 + 批次 + 科室 定位库存行：请求效期与库存行不一致 → 409；过期批次 OUT
   → 422；RETURN 携带 UDI 时以实例批次/效期为唯一扣减依据（请求不一致 422）。
   理由：先查后写会在并发扣减时超发；把不变量交给数据库条件更新，服务层只翻译冲突。
   反向选择（只按 batch_no 扣减、忽略效期）会让“未来效期”扣掉过期批次，账实与追溯都会错。
4. **效期口径**：`expiry_managed=true` 的耗材出入库必须带批次与效期，效期早于今天 → 422；
   同一 材料 + 批次 + 科室 的效期前后不一致 → 409（不允许同批次两个效期）。效期管理
   由主数据标志控制，不按耗材分类推断。**RETURN 表示退回供应商/中心库**（库存减少 +
   UDI 终态 RETURNED，单向，不复库）；院内退库复库/再用登记口径【待确认】（Q-6），
   确认前不得用 RETURN + 重新登记绕过一物一码终态。效期临界天数/近效期预警归
   M4-M16/M4-M17，本期不做。
5. **状态机与 append-only**：`stock_movement` 只 INSERT，UPDATE/DELETE/TRUNCATE 一律
   SQLSTATE 55000；更正走反向 ADJUST 并强制原因。`udi_instance` 允许状态更新但由触发器守卫：
   身份列（编码/耗材/批次/科室/创建留痕）不可改，只允许 IN_STOCK→USED/RETURNED，终态不可再变。
6. **数据范围复用 m0**：耗材目录全院可读（主数据共享）；批次库存、流水、UDI 实例与
   SPD 适配器状态按 ALL/SITE/DEPT 隔离；SELF/ROOM/无范围 fail-closed 403（仓库/追溯没有
   “本人/术间”语义）。单对象越界与不存在统一 404（不泄露存在性）。
7. **权限最小集**：`m4:material:read`（SYS_ADMIN/SYS_OR_MANAGER/SYS_NURSE_ANESTHETIST）、
   `m4:material:write`（SYS_ADMIN/SYS_OR_MANAGER）、`m4:udi:manage`
   （SYS_ADMIN/SYS_OR_MANAGER/SYS_NURSE_ANESTHETIST）。术者/药房/人员管理员不授予。
8. **SPD 适配器（EXT-03）为 SIMULATED**：`POST /v1/materials/spd-adapter/sync` 触发无外部
   I/O 的模拟同步，结果写 `spd_sync_log`（success/degraded/detail 摘要）与审计 `SPD_SYNC`；
   失败返回 HTTP 200 + `success=false, degraded=true`（本地库存继续可用），不阻塞临床。
   `simulated-available=false` 用于演练/测试降级链路。状态查询按科室数据范围
   fail-closed（SELF/ROOM 403），不因“适配器状态非患者数据”而放宽。
9. **审计白名单**：`MATERIAL_CREATE/UPDATE/STATUS`、`STOCK_MOVE`、`UDI_REGISTER/UDI_USE`、
   `SPD_SYNC`；detail 只含标识、编码/UDI-DI、类型、数量、批次、科室与状态摘要，
   **不含 UDI 原文全串（PI/序列号）、患者信息、供应商凭据**；失败路径（400/409/422）以
   独立事务写 `result=0`（复用 `M4FailureAuditor`），resourceId 用 materialId 或 UDI-DI
   摘要，detail 附 materialId/batchNo/movementType/udiDi，保证可定位且不泄露。
10. **不建跨模块外键**：`stock_movement.record_id/schedule_id` 与 `udi_instance.record_id/
    schedule_id` 为逻辑关联；记录被修订/锁单不影响耗材追溯，追溯查询只回传 recordId/scheduleId。

## 后果与待确认

- 正面：高值追溯链在库层可验证（一条 UDI 实例 + 成对 IN/OUT 流水 + 记录绑定）；数量不足与
  效期问题在服务端可解释（409/422 + details）；SPD 失败可演练且不阻塞本地作业。
- 代价：高值耗材必须逐件扫码，未接入扫码枪时录入成本高（扫码式输入为占位）；同一批次跨科室
  分别记账，调拨需两次流水（调拨单据/审批【待确认】）。
- **Q-1 UDI 解析规则**【待确认】：GS1（01/21/17/10）与 HIBCC 的分隔符、校验位、PI 内嵌
  批号/效期/序列号解析、扫码枪前缀/后缀；确认后新增解析器与迁移，不改本 ADR 的存储口径。
- **Q-2 批次策略**【待确认】：是否按供应商批号合并、是否允许跨科室调拨/合并记账、近效期阈值
  与预警渠道。
- **Q-3 高值口径**【待确认】：金额阈值（如 ≥500 元）、高值目录范围、是否按医保/物价口径；
  本表不内置金额字段，避免把财务口径写死成临床规则。
- **Q-4 SPD 报文与频率**【待确认】：供应商/中心库房接口协议、同步频率、失败重试与对账口径；
  本期仅模拟。
- **Q-5 计费衔接**【待确认】：UDI 使用是否触发计费/耗材计费单据、退费与红冲流程（属 M5/财务域）。
- **Q-6 院内退库/复库**【待确认】：本期 RETURN = 退回供应商/中心库（UDI 终态 RETURNED，
  单向）；退回二级库后复库再用、退库后重新入库是否复用原 UDI/批次/效期、是否需退库单与
  审批，待院方确认。确认前不得用“RETURN + 重新登记”绕过一物一码终态，否则同一器械会出现
  两条有效追溯链。
