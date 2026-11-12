# apps/web — ANESOP 前端（Vue 3 + TypeScript + Vite）

**一句话职责**：围术期各角色的浏览器工作台（登录/布局/权限菜单/业务页面），
构建产物为 Nginx 静态资源，接口统一经 `/api` 访问后端。

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

## 如何运行

```bash
source scripts/env.sh
pnpm install --frozen-lockfile

# 开发服务器：http://localhost:5173，/api 代理到 http://localhost:8080（去掉 /api 前缀）
pnpm -C apps/web dev

# 生产构建：vue-tsc + tsc(node) + vite build → apps/web/dist/
pnpm -C apps/web build
```

后端未启动时登录会失败，但登录页、404、重定向等壳页面可正常渲染。需要登录时按
[`../../docs/guides/demo.md`](../../docs/guides/demo.md) 启动 API 并使用 `demo_admin`。
`VITE_API_BASE_URL` 默认为空（走 `/api` 代理）；Vite 只读取 `apps/web` 下的 `.env*`
或进程环境变量，根 `.env` 需先 `set -a; source .env; set +a`。

## 如何测试

```bash
pnpm -C apps/web exec vue-tsc --noEmit   # 类型
pnpm test:unit                           # Vitest + v8 覆盖率（10 文件 / 32 用例）
pnpm e2e                                 # Playwright 3 条壳冒烟（不依赖后端/Docker）
pnpm -C apps/web build                   # 构建即一次完整校验
```

- 首次 E2E 需 `pnpm exec playwright install chromium`（浏览器落在仓库内 `.playwright/`）；
- 覆盖率门禁：行 ≥70%/分支 ≥55%（`apps/web/vitest.config.ts`）；
- E2E-01–05（排程冲突、断网补传、锁单修订、越权、麻精双人核对）待 M1/M2 落地。

## 关键契约

- 类型唯一来源：`packages/contracts/openapi/*.yaml` → `packages/contracts/generated/typescript/`
  → 经 `@anesop/contracts` 子路径导入；**禁止手改生成物**。
- `src/api/http.ts` 统一封装 Bearer、`X-Trace-Id`、超时、契约 `Error` 与 401 单飞刷新重放；
  页面不得直接 `fetch`。
- 契约变更：`bash scripts/gen-contracts.sh` → 提交生成物 → `bash scripts/contract-check.sh`
  校验漂移与破坏性变更（见 [`../../docs/guides/dev-workflow.md`](../../docs/guides/dev-workflow.md)）。

## 负责人（占位）

- Owner：`@anesop/web-core`（框架/壳/设计令牌）+ 各模块团队（见
  [`.github/CODEOWNERS`](../../.github/CODEOWNERS)：m0 `@anesop/m0-platform-security`、
  m2 `@anesop/m2-anesthesia` + `@anesop/clinical-safety` 等）
- 备份：`@anesop/maintainers`
- 说明：远端组织未建，占位团队审批暂不生效。

## 状态

S0-02 路由壳与构建链已就绪；登录、布局、权限菜单与请求封装由 S0-07/S0-08 落地。
