# m4 — 药品目录与麻精五专（前端分包）

**一句话职责**：药品目录维护、麻精五专（专册登记/专柜交接/基数/日结班结）与药房适配器状态
的浏览器页面、模块内组件与组合式函数。

本目录只放 M4 药品域的页面、模块内组件与组合式函数，不对外导出内部实现。

## 目录

```text
m4/
├── api/m4.ts                 # 契约 drug 0.1.0 生成类型 + HTTP 客户端（禁止手写重复模型）
├── views/DrugCatalogView.vue # 药品目录：列表/检索/新建/编辑（只读账号隐藏维护入口）
├── views/NarcoticView.vue    # 麻精五专：科室必选 + 专册/交接/基数/日结 + 适配器状态卡
└── __tests__/                # Vitest 用例（目录 4 例 + 五专 7 例）
```

## 约定

- 类型只从 `@anesop/contracts/drug` 导入（`packages/contracts/generated/typescript/drug.d.ts`
  由 `bash scripts/gen-contracts.sh` 生成，禁止手改）；
- 跨模块复用只经 `packages/ui`（展示组件）与 `src/api/`（HTTP/会话）；禁止引用其他
  `src/modules/mX` 的内部文件；
- 路由/菜单走 `src/router/routes.ts` + `src/navigation.ts`（`/m4/drugs` 需
  `m4:drug:read`，`/m4/narcotic` 需 `m4:narcotic:manage`）。

## 业务口径（详见 `docs/modules/m4/README.md` 与 ADR-0018）

- 科室必须显式选择（`GET /v1/narcotic/departments`）；禁止用当前用户 ID 冒充科室（B-3），
  未选择时前端拦截、不发请求；
- 专册/交接/日结 **append-only**：页面只新增，不提供修改/删除；
- 双人签字必须不同人（前端先拦一次，服务端 400 为准）；交接另要求交班 ≠ 接班；
- 日结实时提示 `期初 + 入库 − 使用 − 退回 = 期末`，不平由服务端 409 返回 `details`，
  页面直接展示差异；不替代临床/财务判断（AGENTS H-06）；
- 药房适配器 EXT-02 为 **SIMULATED**：失败显示“已降级（本地字典可用）”，不阻塞页面；
- 班次两班制（白班/夜班）为演示口径【待确认】。

## 如何运行与测试

```bash
source scripts/env.sh
pnpm -C apps/web dev          # http://localhost:5173；/api 代理到 8080
pnpm test:unit                # Vitest（m4：DrugCatalogView/NarcoticView，含科室必选用例）
pnpm -C apps/web exec vue-tsc --noEmit
pnpm e2e                      # Playwright（m4-drug：目录 → 专册登记 → 日结平账）
```

## 关键契约

- `packages/contracts/openapi/drug.yaml` 0.1.0（`Drug`/`NarcoticRegister`/`CabinetHandover`/
  `DrugBaseline`/`DailyClose`/`PharmacyAdapterStatus` 与共享 `Error`）。
- 生成物：`packages/contracts/generated/typescript/drug.d.ts`、
  `apps/api/api-contracts/target/generated-sources/openapi/drug`。
- 权限点：`m4:drug:read`、`m4:drug:write`、`m4:narcotic:manage`（V20 最小授权）。

## 负责人（占位）

- Owner：`@anesop/m4-drug` + `@anesop/web-core`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`

---

## 耗材/UDI/SPD 分包（M4-M14-01）

```text
m4/
├── api/material.ts                  # 契约 material 0.1.0 生成类型 + HTTP 客户端
├── views/MaterialCatalogView.vue    # 耗材目录：列表/检索/新建/编辑/启停（高值/效期标志）
├── views/MaterialStockView.vue      # 批次库存 + 出入库登记 + 流水 + SPD 状态卡
├── views/MaterialTraceView.vue      # 扫码式 UDI 输入占位 + 追溯链 + UDI 登记/使用
└── __tests__/Material*.spec.ts      # Vitest：目录 5 / 出入库 4 / UDI 追溯 4
```

- 类型只从 `@anesop/contracts/material` 导入（`bash scripts/gen-contracts.sh` 生成）；
- 路由：`/m4/materials`、`/m4/materials/stock`、`/m4/materials/trace`，菜单权限
  `m4:material:read`；写操作按 `m4:material:write` / `m4:udi:manage` 隐藏；
- 数据范围：库存/流水/UDI/SPD 状态按科室（SELF/ROOM 403），页面显式选择科室
  （`GET /v1/materials/departments`）；
- 高值耗材逐件登记/使用；页面不提供高值裸数量出入库入口；
- SPD 适配器（EXT-03）为模拟：失败显示“已降级（本地库存可用）”，不阻塞页面；
- UDI 解析规则/扫码枪口径【待确认】（ADR-0020），本期手动输入/粘贴 `UDI-DI^UDI-PI`。
- E2E：`apps/web/e2e/m4-material.spec.ts`（UDI 登记入库 → 绑定记录使用 → 追溯链 → SPD 卡）。
