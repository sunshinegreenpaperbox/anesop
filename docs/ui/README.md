# 前端壳与设计令牌（S0-08）

> 状态：S0-08 完成（2026-11-12）。范围：登录页、主布局、路由与权限菜单、请求封装
> （401 单飞刷新重放）、统一错误处理、设计令牌、Vitest/E2E 测试。
> 依据：`docs/ai/tasks/S0-backlog.md` S0-08、`packages/contracts/openapi/auth.yaml` 0.3.0、
> `docs/modules/m0/README.md`（R1–R11 角色映射与权限点）、方案书 §2.4.1–2.4.3。
> 技术栈：Vue 3.5 / TypeScript 5.6 / Element Plus 2.9.11 / Pinia 3 / Vue Router 4 / Vite 6。

## 1. 目录与职责

```text
packages/ui/                       # @anesop/ui：设计令牌（唯一事实源）
├── tokens.css                     # CSS 变量 + Element Plus 主题映射
├── tokens.ts                      # 同一套令牌的 TS 投影（图表/逻辑引用）
└── README.md

apps/web/src/
├── main.ts                        # 安装 Pinia/Router/Element Plus，配置会话过期跳转
├── navigation.ts                  # 菜单、图标、角色码中文标签（路由 meta 与菜单同源）
├── api/
│   ├── http.ts                    # fetch 封装：Bearer / X-Trace-Id / 超时 / Error / 401 刷新重放
│   ├── auth.ts                    # 契约 auth 端点
│   ├── session.ts                 # token 与 CurrentUser 存储
│   └── README.md
├── stores/
│   ├── index.ts                   # 单一 Pinia 实例（守卫/组件共用）
│   ├── auth.ts                    # 会话、角色、权限点、改密标记
│   └── menu.ts                    # 按权限过滤的可见菜单
├── router/
│   ├── index.ts                   # 登录/首页/403/404 + 全局守卫
│   ├── routes.ts                  # 路由表（meta.permissions/menu）
│   └── redirect.ts                # 站内重定向白名单
├── layout/                        # MainLayout / AppHeader / SideMenu / AppBreadcrumb / MenuIcon
├── views/                         # HomeView / ForbiddenView / NotFoundView
└── modules/m0/views/              # LoginView / ChangePasswordView / SessionsView
```

`src/modules/m1..m8` 仍为 README 占位，S0-08 不实现业务页面。

## 2. 设计令牌

包名 `@anesop/ui`（private），入口 `packages/ui/tokens.css` 与 `packages/ui/tokens.ts`。

| 类别 | 令牌 | 规则 |
|---|---|---|
| 品牌色 | `--anesop-color-brand-50…900` | 医疗蓝主色 `#1f74b3`，十档 |
| 语义色 | `success/warning/danger/info` + `*-soft` | 成功/警告/危险/信息，浅底用于标签与提示 |
| 文本/边框/背景 | `--anesop-color-text-*`、`--anesop-color-border-*`、`--anesop-color-bg-*` | 正文对比度 ≥ 4.5:1 |
| 字号 | `xs…4xl` = 12/13/14/16/18/20/24/28/32 px | 行高 tight/base/relaxed，字重 400/500/600/700 |
| 间距 | `--anesop-space-1/2/3/4/6/8` = 4/8/12/16/24/32 px | 只用 4 的倍数 |
| 圆角 | `sm/md/lg/xl/full` = 4/6/8/12/999 px | — |
| 阴影 | `sm/md/lg/focus` | 低饱和蓝黑阴影，焦点环统一 |
| z-index | base 0 / sticky 100 / header 200 / drawer 1000 / dropdown 1500 / modal 2000 / popover 2100 / toast 3000 | 不允许页面自造层级 |
| 字体 | `--anesop-font-family-sans/mono/number` | 中文回退 PingFang SC → Microsoft YaHei → Noto Sans SC → 思源黑体 → system-ui |

**Element Plus 映射**：`tokens.css` 覆盖 `--el-color-primary`（含 light-3/5/7/8/9、dark-2）、
success/warning/danger/error/info 同构变量，以及 `--el-font-family`、`--el-font-size-*`、
`--el-text-color-*`、`--el-border-color-*`、`--el-bg-color*`、`--el-border-radius-*`、
`--el-box-shadow*`、`--el-mask-color`。加载顺序必须是 Element Plus CSS 在前、`tokens.css` 在后。

**约束**（评审检查项，详见 `packages/ui/README.md`）：

- 页面禁止硬编码颜色/字号/间距；一律 `var(--anesop-*)`；
- 业务页面优先语义色（`--anesop-color-primary/success/...`），品牌展示区才用 `brand-*`；
- 不在页面覆盖 `--el-*`；令牌只增不删，改名按破坏性变更处理；
- 当前字号/间距为固定 px，不随窗口缩放（1366/1920 均验证可用）。

## 3. 页面结构

### 3.1 登录页 `/login`

左右分栏：左侧品牌介绍（窄屏 <1024 隐藏），右侧登录卡片。Element Plus `el-form`：
账号（≤64）与口令（≤128）必填校验、加载态、回车提交。失败时展示契约 `Error` 的
`message + code + traceId + details`；成功后写入会话并跳转 `redirect` 或 `/home`。
`mustChangePassword=true` 时跳转 `/m0/password?forced=1`。

### 3.2 主布局

`MainLayout`：侧边栏（品牌 + 按权限生成的菜单 + 版本）、顶栏（系统名、待改密标签、主角色、
用户下拉：修改口令/退出登录）、面包屑、内容区（最大宽 1600，居中）。
`window.innerWidth < 1200` 自动收起为 64px 图标栏，可手动切换；1366 与 1920 默认展开。
侧栏深色使用 `--anesop-color-bg-inverse`，选中项使用品牌主色。

### 3.3 首页 `/home`

登录后欢迎区（姓名、权限点数量）+ 三张卡片：当前角色与数据范围、权限概览（按模块分组）、
系统状态（前端壳版本、API 基址、会话状态、令牌到期时间）。**不调用契约之外的健康检查接口**；
ECharts 指标卡推迟到 M8。

### 3.4 其他页面

- `/m0/password`：旧口令 + 新口令 + 确认；强度 12–128 且大写/小写/数字/符号四类取三，
  不含账号、不得与旧口令相同；成功后清会话并重新登录（契约要求）。
- `/m0/sessions`：会话分页表格，需 `m0:auth:read`；只读，强制下线接口未登记，留 M1。
- `/403`：展示目标页面、当前账号/角色与返回首页/切换账号。
- `/404` 与未匹配路径：保留原 URL 渲染 404。

## 4. 路由与权限

| 路径 | 名称 | 权限 | 布局 |
|---|---|---|---|
| `/login` | `login` | 公开 | 无 |
| `/` | — | — | 重定向 `/home` |
| `/home` | `home` | 登录 | MainLayout |
| `/m0/sessions` | `m0-sessions` | `m0:auth:read` | MainLayout |
| `/m0/password` | `m0-password` | 登录 | MainLayout |
| `/403` | `forbidden` | 登录 | 无 |
| `/404`、`/:pathMatch(.*)*` | `not-found`/`catch-all` | 登录 | 无 |

**守卫流程**（`router/index.ts`）：

1. 公开页：已登录访问 `/login` → 拉 `/v1/auth/me` 后回 `redirect` 或首页；未登录放行。
2. 无 token → `/login?redirect=<fullPath>`。
3. 有 token → `auth.fetchCurrentUser()`（缓存到 Pinia；**每次前端启动的首屏导航即使有
   sessionStorage 快照也请求一次 `/v1/auth/me` 校验**，之后复用缓存；`force` 可强制刷新）；
   失败清会话回登录。
4. `mustChangePassword=true` → 除改密/403/404 外一律收敛到 `/m0/password?forced=1`。
5. `meta.permissions` 非空且用户无任一权限点 → `/403?from=<fullPath>`。

**菜单**：`navigation.ts` 的 `appMenu` 是路由 meta 与侧边菜单的同一事实源；`meta.menu=true`
才进入菜单；`meta.permissions` 为 OR 语义；过滤只看 `/v1/auth/me` 返回的权限点，**不硬编码角色判断**。
角色码仅用于顶栏展示中文标签（R1–R11 映射见 `docs/modules/m0/README.md`）。

新增页面步骤：`navigation.ts` 加菜单项 → `router/routes.ts` 加路由并复用 `menuMeta(path)` →
`menu.spec.ts` 的“菜单/路由漂移”用例自动校验。

## 5. 请求与错误处理

`src/api/http.ts` 基于原生 `fetch`（未引入 axios）：

- **认证**：自动附加 `Authorization: Bearer <accessToken>`；登录/刷新显式 `auth:false`。
- **追踪**：每个请求带 `X-Trace-Id`（调用方指定或 `crypto.randomUUID()` 生成）；错误优先取
  响应体 `traceId`，否则取响应头。
- **超时**：默认 15s，`AbortController` 中断，抛 `NETWORK_TIMEOUT`；网络失败抛 `NETWORK_ERROR`。
- **错误**：统一 `ApiError { kind, status, code, message, traceId, details }`，解析契约 `Error`；
  缺省 code 为 `HTTP_<status>`，页面不解析自由文本。
- **401 刷新重放**：非登录/刷新请求收到 401 → 读取 refresh token 调 `/v1/auth/refresh`；
  成功则替换令牌并**重放原请求一次**；失败清会话并回调 `onSessionExpired`（main.ts 跳登录并保留来源）。
  并发 401 用**单飞 Promise** 合并，只发一次刷新；刷新后仍 401 不再次刷新。
- **登出**：调用 `/v1/auth/logout`，无论成功与否清本地会话。

认证接口封装在 `src/api/auth.ts`，类型全部来自 `@anesop/contracts/auth`（契约生成物，禁止手改）。

## 6. 会话存储与 M1 安全待办

| 数据 | 存储位置 | 生命周期 |
|---|---|---|
| accessToken | 内存 + `sessionStorage` | 15 分钟（服务端 TTL），关闭标签页即失效 |
| refreshToken | `sessionStorage` | 7 天（服务端 TTL），轮换后旧值立即失效 |
| CurrentUser 快照 | `sessionStorage` | 首屏缓存；守卫仍以 `/v1/auth/me` 为准 |

**XSS 风险（已知并接受，S0 权衡）**：`sessionStorage` 对同源脚本可读，一旦 XSS 可窃取
refresh token 并在有效期内续期。缓解：不使用 `localStorage`（降低持久化面）、不在 URL/日志
输出令牌、Vue 默认转义且禁用 `v-html`、依赖 CSP（部署阶段配置）。

**M1 安全待办**（必须在 M1 退出前完成并评审）：

1. refresh token 改存 `HttpOnly + Secure + SameSite=Strict` Cookie，前端不再读取；access token
   仅内存（或同样 Cookie 化），删除 `sessionStorage` 令牌写入；
2. 配套 CSRF 防护（双提交 Token 或 SameSite+Origin 校验），刷新接口校验来源；
3. 配置严格 CSP、`X-Content-Type-Options`、`Referrer-Policy`，清理第三方脚本；
4. 多标签页登出广播（`BroadcastChannel`/storage 事件）与并发会话上限；
5. 令牌/错误日志脱敏审计，禁止把 Authorization、refresh token 写入前端日志或埋点；
6. 会话空闲超时与强制下线接口（契约补充后接入 UI）。

## 7. Element Plus 与 ECharts 引入策略

- **Element Plus 2.9.11（MIT，已登记 allowlist）**：当前在 `main.ts` 全量引入
  `element-plus` 与 `dist/index.css`，并注册 `zh-CN` locale。优点是配置简单、S0 可快速闭环；
  代价是首包偏大（见第 9 节）。M1 若首屏预算紧张，改为按需引入（需评审登记
  `unplugin-vue-components`/`unplugin-auto-import`），或按路由懒加载重页面，不在 S0 拆。
- **图标**：只用了 3 个菜单图标，采用内联 SVG（`layout/MenuIcon.vue`），未新增
  `@element-plus/icons-vue` 依赖。
- **ECharts 5.5.x（Apache-2.0）推迟到 M8**：届时从 `@anesop/ui` 的 `colors`/`spacing` 生成
  图表主题，不在页面另建色板；M8 前不引入、不写假图表。
- **国际化**：当前仅 Element Plus 组件文案使用 `zh-CN`，业务文案为中文硬编码；未引入
  `vue-i18n`。若产品要求多语言，M1 立项后统一抽取，不在页面散落 i18n key。

## 8. 本地开发

```bash
source scripts/env.sh
pnpm install
pnpm -C apps/web dev            # http://localhost:5173
pnpm -C apps/web build          # vue-tsc + tsc(node config) + vite build
pnpm lint                       # ESLint + Prettier
pnpm test:unit                  # Vitest + v8 覆盖率（行 ≥70 / 分支 ≥55）
pnpm e2e                        # Playwright 3 条前端壳冒烟
```

**API 代理（避免 CORS）**：前端统一请求 `/api/v1/...`，`vite.config.ts` 把 `/api` 代理到
`http://localhost:8080` 并 **rewrite 去掉 `/api` 前缀**（后端未设置 servlet context-path，
保留前缀会 404）。生产由 Nginx 将 `/api` 反代到 API，前端代码不变；开发与生产都同源，
因此后端不需要开启 CORS。可用 `VITE_API_BASE_URL` 覆盖基址。

**后端联调**：启动 API 需要 PostgreSQL 16 与 JWT 密钥（`ANESOP_JWT_SECRET`，Base64 ≥32 字节），
dev profile 还需显式允许演示种子/账号。演示账号 `demo_admin / Anesop@2026` 为 SYNTHETIC，
首次登录 `must_change_password=true`，登录后前端跳改密页，改密成功要求重新登录。

### 8.1 真实联调结果（2026-11-12，已完成）

环境：Docker 不可用（EXT-08），使用 Zonky EmbeddedPostgreSQL **16.15**（127.0.0.1:55432）
+ `api-bootstrap` 可执行 jar（`dev` profile，Flyway 自动迁移 V1–V4、V100 演示种子）
+ Vite dev server 5173（`/api` 代理 rewrite 生效），均为临时进程，联调后已停止。

`curl` 证据（`demo_admin`，全部 SYNTHETIC）：

| 步骤 | 请求 | 结果 |
|---|---|---|
| 1 | `POST /v1/auth/login`（`Anesop@2026`） | 200；`mustChangePassword=true`、roles=`SYS_ADMIN`、19 个权限点、`expiresIn=900` |
| 2 | `GET /v1/auth/me` | 200；`mustChangePassword=true`，权限快照与登录一致 |
| 3 | `POST /v1/auth/password`（旧→`Anesop@2026#New`） | 204 |
| 4 | 用新口令 `POST /v1/auth/login` | 200；`mustChangePassword=false` |
| 5 | 用旧口令登录 | 401 `AUTH_UNAUTHORIZED`（统一“账号或口令错误”文案，不暴露账号状态） |

浏览器（Playwright 一次性脚本，**未提交为 E2E 用例**）：

- 登录页空表单提交：0 次 `/v1/auth/login` 请求，`请输入登录账号` 校验可见（Element Plus
  表单校验在真实浏览器生效）；
- 真实账号登录成功 → `/home`，顶栏显示角色“信息科管理员（R9）”，菜单为
  首页/会话管理/修改口令；
- 篡改 sessionStorage 的 access token 后刷新进入 `/m0/sessions`：`GET /v1/auth/me` **401** →
  `POST /v1/auth/refresh` **200** → 重放 `GET /v1/auth/me` **200** → `GET /v1/auth/sessions` **200**
  （4 条会话）；验证首屏 `/me` 校验与真实刷新重放链路；
- 退出登录 → `/login`，本地会话清理；
- 截图（1366×900、1920×1080）仅存本地 `tmp/`，未入库。

> 联调修改了临时库中的 `demo_admin` 口令；嵌入式数据库随进程销毁，**未修改**仓库种子、
> 迁移或真实环境。`登录 → 首页` 的正式 Playwright 用例仍按计划留到 M1（需要稳定后端、
> 独立测试账号与数据清理策略）。

## 9. 测试与构建基线（2026-11-12）

| 项目 | 结果 |
|---|---|
| `pnpm -C apps/web build` | 通过；JS 约 995 kB（gzip 331 kB），CSS 359.7 kB（gzip 49.9 kB） |
| `pnpm test:unit` | 32 个用例通过；行 88.3% / 分支 80.2% / 函数 79.16% |
| `pnpm e2e` | 3 条前端壳冒烟通过；用 SYNTHETIC 会话 + `/v1/auth/me` 路由桩渲染登录后主壳，不提交登录表单、不伪造登录结果（登录 E2E 留 M1） |
| 真实联调 | Embedded PG 16.15 + API jar + Vite 代理：登录/改密/重新登录/旧口令 401、浏览器刷新重放全通过（§8.1） |

覆盖的关键用例：

- 路由守卫：无 token → `/login?redirect=`；拉 `/me` 放行；权限不足 → `/403?from=`；
  权限足够进入 `/m0/sessions`；未知路径 404；`mustChangePassword` 收敛改密页。
- 菜单：按权限点过滤、无 `m0:auth:read` 隐藏会话管理、菜单路径与路由表一致。
- `http.ts`：401 → refresh → 重放（断言三次请求、Authorization 替换、refresh 轮换）；
  刷新失败清会话并回调；并发 401 只刷新一次；网络错误映射。
- `LoginView`：成功保存会话并跳首页；失败展示 code/message/traceId；空值前端拦截；
  强制改密跳转。
- 布局：顶栏/菜单/面包屑/内容区、菜单权限过滤、窄屏自动收起。
- 改密：成功后清会话回登录、强度校验、两次输入不一致拦截。
- 会话管理：列表渲染与 403 错误展示。

## 10. 风险与遗留

| 项 | 说明 | 归属 |
|---|---|---|
| 会话存储 XSS | refresh token 在 sessionStorage，M1 改 httpOnly Cookie（第 6 节） | M1 |
| 首包体积 | Element Plus 全量引入约 844 kB（gzip 273 kB），M1 视预算按需引入/路由懒加载 | M1 |
| 登录 E2E | 后端未就绪，E2E 只做壳冒烟；真实登录→首页 E2E 留 M1 | M1 |
| 数据权限 UI | 仅展示 `/me` 的 dataScopes；科室/术间范围选择器与数据范围联动留 M1 | M1 |
| 国际化 | 未引入 vue-i18n，业务文案中文硬编码 | M1 评估 |
| ECharts | 推迟到 M8，从 `@anesop/ui` 取色 | M8 |
| 强制下线/会话数上限 | 契约未登记接口，页面只读 | M1 |
| Vitest CJS 互操作 | `async-validator` 在 Vitest SSR 下需 inline + ESM 别名，已在 `vitest.config.ts` 注释原因 | 测试基座 |

## 11. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-12 | S0-08 建立：设计令牌、登录/改密/会话页、主布局、路由守卫、请求封装、测试与文档 |
