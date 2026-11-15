# m5 — 实时态势与 KPI 看板（前端分包）

**一句话职责**：驾驶舱页面（实时态势/KPI 看板 + 风险与资源大屏；10s 轮询、暂停刷新、
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
├── api/dashboard.ts               # 契约类型别名、轮询间隔、标签与格式化函数
├── views/DashboardView.vue        # 实时态势与 KPI 看板（/m5/dashboard）
├── views/RiskDashboardView.vue    # 风险与资源大屏（/m5/dashboard/risk）
└── __tests__/
    ├── DashboardView.spec.ts
    └── RiskDashboardView.spec.ts
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
pnpm test:unit                 # 含 m5 的 12 个 Vitest 用例（实时看板 6 + 风险资源 6）
pnpm e2e                       # 含看板加载 → 手动刷新 → 10s 自动刷新 → 暂停；风险资源大屏主链路
```

## 关键契约

- 契约：`packages/contracts/openapi/dashboard.yaml` 0.2.0（口径 dashboard-1.1.0）；
- 权限点/菜单 meta：`src/navigation.ts`（`/m5/dashboard` 实时看板、`/m5/dashboard/risk`
  风险与资源大屏，均需 `m5:dashboard:read`）；
- 页面数据只含术间/科室/状态/计数/比率/班组人数与 KPI，无患者与人员标识（无 PHI）；
- 风险与资源大屏为暗色大屏网格（风险卡 + ASA/风险等级分布 + 资源卡 + 缺口提示 + 趋势表），
  数据来自 `/v1/dashboard/risk`、`/v1/dashboard/resources`、`/v1/dashboard/trends?hours=24`。

## 负责人（占位）

- Owner：`@anesop/m5-dashboard` + `@anesop/web-core`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
