# m1 — 手术排程管理（前端分包）

**一句话职责**：手术申请接收与校验的浏览器页面（列表、详情、状态历史、取消/模拟导入）。

本目录只放 M1 的页面、模块内组件与组合式函数，不对外导出内部实现。

## 状态（2026-11-13，M2-M1-01）

- 已落地：`views/ApplicationListView.vue`（分页/状态/紧急度/科室筛选、模拟 HIS 导入、取消）、
  `views/ApplicationDetailView.vue`（脱敏患者摘要、完整状态历史、取消）、`api/m1.ts`
  （类型来自 `@anesop/contracts` 生成物）。
- 路由：`/m1/applications`、`/m1/applications/:id`，权限 `m1:application:read`；
  写操作按钮按 `m1:application:write` 显示（只读用户隐藏导入/取消）。
- 患者检索：使用 `POST /v1/patients/search`（`searchPatients`），条件放请求体，避免 PHI 进入
  URL/访问日志；旧 `GET /v1/patients` 已 deprecated，前端不再使用。

## 约定

- 跨模块复用只经 `packages/ui`（展示组件）与 `src/api/generated/`（契约类型）；
- 禁止引用其他 `src/modules/mX` 的内部文件；
- 路由按模块注册，菜单与权限由 S0-08 统一装配。

## 目录

```text
apps/web/src/modules/m1/
├── api/m1.ts                 # 契约类型 + API 调用（禁止手写重复模型）
├── views/ApplicationListView.vue
├── views/ApplicationDetailView.vue
└── __tests__/ApplicationViews.spec.ts
```

## 如何运行

```bash
source scripts/env.sh
pnpm install --frozen-lockfile
pnpm -C apps/web dev        # http://localhost:5173；/api 代理到 8080
```

后端启动见 [`docs/guides/demo.md`](../../../../../docs/guides/demo.md)。

## 如何测试

```bash
pnpm -C apps/web exec vue-tsc --noEmit
pnpm test:unit
pnpm e2e                    # 含 e2e/m1-applications.spec.ts（列表/筛选/详情/权限按钮）
```

页面改动必须补 Vitest 用例；关键路径补 Playwright E2E（M1 已覆盖 API 拦截级用例）。

## 关键契约

- `packages/contracts/openapi/scheduling.yaml` 0.3.0（`/v1/scheduling/applications*`）；
- `packages/contracts/openapi/patient.yaml` 0.3.0（患者摘要、`POST /v1/patients/search`）；
- 类型经 `@anesop/contracts` 导入，禁止手改生成物；契约门禁见 `docs/api/README.md`。

## 负责人（占位）

- Owner：`@anesop/m1-scheduling` + `@anesop/web-core`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
