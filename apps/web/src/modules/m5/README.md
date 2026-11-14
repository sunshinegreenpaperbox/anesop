# m5 — 实时态势与 KPI 看板（前端分包）

**一句话职责**：驾驶舱页面（术间状态/台次进度/告警/设备/KPI 卡片、10s 轮询、暂停刷新、
大屏友好布局）。

本目录只放 M5 的页面、模块内组件与组合式函数，不对外导出内部实现。

## 约定

- 跨模块复用只经 `packages/ui`（展示组件）与 `src/api/generated/`（契约类型）；
- 禁止引用其他 `src/modules/mX` 的内部文件；
- 契约类型从 `@anesop/contracts/dashboard` 导入，禁止手写重复模型；
- **不引入 ECharts / WebSocket**：看板用 Element Plus 卡片/进度条/表格即可满足大屏需求，
  刷新走 10s 轮询（无新依赖，ADR-0023）。

## 目录

```text
m5/
├── api/dashboard.ts     # 契约类型别名、轮询间隔、标签与格式化函数
├── views/DashboardView.vue
└── __tests__/DashboardView.spec.ts
```

## 如何运行

```bash
source scripts/env.sh
pnpm install --frozen-lockfile
pnpm -C apps/web dev        # http://localhost:5173/m5/dashboard；/api 代理到 8080
```

页面需要 `m5:dashboard:read`；无权限时路由守卫跳 `/403`，页面内也会展示权限态（不发请求）。

## 如何测试

```bash
pnpm -C apps/web exec vue-tsc --noEmit
pnpm test:unit                 # 含 m5 的 6 个 Vitest 用例
pnpm e2e                       # 含看板加载 → 手动刷新 → 10s 自动刷新 → 暂停
```

## 关键契约

- 契约：`packages/contracts/openapi/dashboard.yaml` 0.1.0（口径 dashboard-1.0.0）；
- 权限点/菜单 meta：`src/navigation.ts`（`/m5/dashboard`，`m5:dashboard:read`）；
- 页面数据只含术间/科室/状态/计数与 KPI，无 PHI。

## 负责人（占位）

- Owner：`@anesop/m5-dashboard` + `@anesop/web-core`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
