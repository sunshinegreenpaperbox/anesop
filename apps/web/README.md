# apps/web — ANESOP 前端（Vue 3 + TypeScript + Vite）

按模块分包的前端工程：路由壳 + 各业务模块页面。构建产物为 Nginx 静态资源（三个部署单元之一）。

## 目录

```text
apps/web/
├── index.html
├── vite.config.ts          # 开发端口 5173，/api 代理到 http://localhost:8080
├── tsconfig.json           # 应用侧 TS 配置（strict）
├── tsconfig.node.json      # 构建脚本侧 TS 配置
└── src/
    ├── main.ts             # 入口：Pinia + Router
    ├── App.vue             # 根组件（RouterView）
    ├── env.d.ts            # Vite 客户端类型
    ├── router/index.ts     # 路由壳：/ → /home
    ├── views/HomeView.vue  # 首页占位
    ├── api/                # 契约生成类型目录（S0-05）
    └── modules/m0..m8/     # 模块分包占位（README 说明归属）
```

## 命令（仓库根执行，先 `source scripts/env.sh`）

```bash
pnpm install
pnpm -C apps/web dev
pnpm -C apps/web build        # vue-tsc --noEmit + tsc(tsconfig.node) + vite build → dist/
pnpm -C apps/web exec vue-tsc --noEmit
```

## 约定

- 依赖精确版本，禁止 `^` / `~`（`pnpm-lock.yaml` 一并提交）；
- 跨模块只经 `src/api/generated/` 的契约类型与各模块对外导出，不引用其他模块内部组件；
- Element Plus / ECharts 在 S0-08 引入，本任务不安装；
- 设计令牌集中在 `packages/ui`（S0-08）。

## 状态

S0-02 路由壳与构建链已就绪；登录、布局、权限菜单与请求封装由 S0-07/S0-08 落地。
