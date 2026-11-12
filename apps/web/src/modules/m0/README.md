# m0 — 公共支撑（前端分包）

**一句话职责**：登录、强制改密、会话管理等 M0 页面，消费 `auth.yaml` 契约并提供
认证/权限的浏览器入口；不做业务页面。

本目录只放 M0 的页面、模块内组件与组合式函数，不对外导出内部实现。

## 目录

```text
m0/
├── views/LoginView.vue           # /login
├── views/ChangePasswordView.vue  # /m0/password（首次登录强制）
├── views/SessionsView.vue        # /m0/sessions
└── README.md
```

## 如何运行

```bash
source scripts/env.sh
pnpm install --frozen-lockfile
pnpm -C apps/web dev        # http://localhost:5173；/api 代理到 8080
```

后端按 [`docs/guides/demo.md`](../../../../../docs/guides/demo.md) 启动；未启动时登录失败、
壳页面仍可渲染。路由与菜单由 `src/router/index.ts`、`src/navigation.ts` 统一装配。

## 如何测试

```bash
pnpm -C apps/web exec vue-tsc --noEmit
pnpm test:unit     # LoginView/ChangePasswordView 等 32 个前端用例
pnpm e2e           # 3 条壳冒烟（不依赖后端）
```

## 约定

- 跨模块复用只经 `packages/ui`（展示组件）与 `src/api/generated/`（契约类型）；
- 禁止引用其他 `src/modules/mX` 的内部文件；
- 路由按模块注册，菜单与权限由 S0-08 统一装配。

## 接口索引（S0-07 后端已实现，契约 auth.yaml 0.3.0）

| 方法 | 路径 | 前端用途 | 授权 |
|---|---|---|---|
| POST | `/v1/auth/login` | 登录页提交账号口令，保存 access/refresh token | 公开 |
| POST | `/v1/auth/refresh` | 令牌过期前静默续期（轮换后替换本地 refresh token） | 公开 |
| POST | `/v1/auth/logout` | 退出登录，清理本地令牌 | 已登录 |
| GET | `/v1/auth/me` | 拉取当前用户角色、权限点、数据范围，驱动菜单/按钮 | 已登录 |
| GET | `/v1/auth/sessions` | 在线设备/会话查看（分页 `page/size`） | 需 `m0:auth:read` |

- 统一错误体：`{ code, message, traceId, details }`；`AUTH_UNAUTHORIZED`(401)、
  `AUTH_FORBIDDEN`(403)、`PARAM_INVALID`(400)、`BUSINESS_RULE_VIOLATION`(422)。
- 请求头 `Authorization: Bearer <accessToken>`；响应头 `X-Trace-Id` 可用于排障反馈。
- 访问令牌 15 分钟、刷新令牌 7 天；刷新即轮换，旧刷新令牌立即失效。

## 权限点（S0-07 定稿）

| 权限码 | 授权表达式 | 说明 |
|---|---|---|
| `m0:auth:read` | `PERM_m0:auth:read` | M0 认证只读（会话列表等） |
| `m0:auth:write` | `PERM_m0:auth:write` | M0 认证写接口（预留） |
| `m0:access` | `PERM_m0:access` | V2 模块访问占位（历史权限点） |

角色码 `SYS_ADMIN`、`SYS_ANESTHESIOLOGIST`、`SYS_NURSE_ANESTHETIST`、`SYS_SURGEON`、
`SYS_OR_MANAGER`、`SYS_QC_MANAGER` 与 §2.1.2 R1–R11 的完整映射见
`docs/modules/m0/README.md`。前端只消费 `/v1/auth/me` 返回的 `roles`/`permissions`，
不在前端硬编码角色判断。

## 关键契约

- 接口与错误体见下方「接口索引」「权限点」；类型来自 `@anesop/contracts` 的
auth 生成类型，禁止手改生成物。
- 页面不直接 `fetch`，统一经 `src/api/http.ts`（Bearer、`X-Trace-Id`、超时、401 单飞刷新重放）。
- 契约版本以 `packages/contracts/openapi/auth.yaml` 为准。

## 负责人（占位）

- Owner：`@anesop/m0-platform-security` + `@anesop/web-core`（CODEOWNERS 占位团队）
- 备份：`@anesop/maintainers`
- 状态：S0-08 已交付三个 M0 页面；强制下线等留 M1。

## 状态

- S0-02：分包占位，无页面代码。
- S0-07：后端认证/权限骨架完成，本目录仅补接口与权限点索引；**不做 UI（S0-08）**。
- S0-08：已落地登录、强制改密、会话管理三个 M0 页面；认证接口经 `src/api/auth.ts`
  引用 `@anesop/contracts` 生成的类型（`packages/contracts/generated/typescript/auth.d.ts`）。
  其他模块保持占位，不实现业务页面。

## 页面（S0-08）

| 文件 | 路由 | 权限 | 说明 |
|---|---|---|---|
| `views/LoginView.vue` | `/login` | 公开 | 账号/口令登录，回车提交，展示契约 `Error` 的 code/message/traceId；`mustChangePassword=true` 跳改密 |
| `views/ChangePasswordView.vue` | `/m0/password` | 登录 | 旧口令 + 新口令（12–128，四类取三），成功后清会话重新登录 |
| `views/SessionsView.vue` | `/m0/sessions` | `m0:auth:read` | 当前账号会话分页列表（只读，强制下线留 M1） |

页面不直接调用 `fetch`，一律经 `src/api/http.ts`（Bearer、`X-Trace-Id`、超时、401 单飞刷新重放）。
菜单与路由守卫见 `src/navigation.ts`、`src/router/index.ts`，约定见 `docs/ui/README.md`。
