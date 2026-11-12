# m7 — 质量控制（前端分包）

**一句话职责**：质量控制的浏览器页面、模块内组件与组合式函数；当前为占位，随 M7 交付。

本目录只放 M7 的页面、模块内组件与组合式函数，不对外导出内部实现。

## 约定

- 跨模块复用只经 `packages/ui`（展示组件）与 `src/api/generated/`（契约类型）；
- 禁止引用其他 `src/modules/mX` 的内部文件；
- 路由按模块注册，菜单与权限由 S0-08 统一装配。

## 目录

本目录当前只有 `README.md`；页面按 `views/`、模块内组件按 `components/` 组织（与 m0 一致）。
禁止其他模块引用本目录内部文件。

## 如何运行

```bash
source scripts/env.sh
pnpm install --frozen-lockfile
pnpm -C apps/web dev        # http://localhost:5173；/api 代理到 8080
```

路由按模块注册，菜单/权限由 `src/router`、`src/navigation.ts` 与 Pinia `menu` 统一装配；
当前无 M7 页面，访问对应路由为占位/404 属预期。后端启动见
[`docs/guides/demo.md`](../../../../../docs/guides/demo.md)。

## 如何测试

```bash
pnpm -C apps/web exec vue-tsc --noEmit
pnpm test:unit
pnpm e2e
```

页面落地时必须补 Vitest 用例；关键路径补 Playwright E2E（E2E-01–05 按模块归属）。

## 关键契约

- 契约：暂无（M7 启动时先评审契约）；类型经 `@anesop/contracts` 导入，禁止手改生成物。
- 跨模块只经 `packages/ui` 与 `src/api/generated/`；禁止引用其他 `src/modules/mX` 内部文件。
- 权限点/菜单 meta 见 `src/navigation.ts` 与 [`docs/modules/m0/README.md`](../../../../../docs/modules/m0/README.md)。

## 负责人（占位）

- Owner：`@anesop/m7-quality` + `@anesop/web-core`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`

## 状态

S0-02 分包占位，无页面代码；首个页面随对应业务任务落地。
