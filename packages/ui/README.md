# packages/ui — 共享 UI 组件与设计令牌

存放跨前端模块复用的展示型组件、布局片段与设计令牌（色板、字号、间距）。
只放无业务语义的组件；业务组件留在 `apps/web/src/modules/<mX>/`。

## 目录约定

```text
packages/ui/
└── src/    # 组件源码与令牌（S0-08 起逐步填充）
```

## 规则

- 依赖方向：`apps/web` → `packages/ui`，反向禁止；
- 禁止在 UI 包内访问接口、路由或 store；
- Element Plus / ECharts 等三方组件在 S0-08 引入，本任务不安装。

## 状态

S0-02 仅建目录占位（`src/.gitkeep`），设计令牌与布局组件由 S0-08 落地。
