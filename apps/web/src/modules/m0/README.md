# m0 — 公共支撑（前端分包）

本目录只放 M0 的页面、模块内组件与组合式函数，不对外导出内部实现。

## 约定

- 跨模块复用只经 `packages/ui`（展示组件）与 `src/api/generated/`（契约类型）；
- 禁止引用其他 `src/modules/mX` 的内部文件；
- 路由按模块注册，菜单与权限由 S0-08 统一装配。

## 接口索引（S0-07 后端已实现，契约 auth.yaml 0.2.0）

| 方法 | 路径 | 前端用途 | 授权 |
|---|---|---|---|
| POST | `/v1/auth/login` | 登录页提交账号口令，保存 access/refresh token | 公开 |
| POST | `/v1/auth/refresh` | 令牌过期前静默续期（轮换后替换本地 refresh token） | 公开 |
| POST | `/v1/auth/logout` | 退出登录，清理本地令牌 | 已登录 |
| GET | `/v1/auth/me` | 拉取当前用户角色、权限点、数据范围，驱动菜单/按钮 | 已登录 |
| GET | `/v1/auth/sessions` | 在线设备/会话查看（分页 `page/size`） | 需 `m0:read` |

- 统一错误体：`{ code, message, traceId, details }`；`AUTH_UNAUTHORIZED`(401)、
  `AUTH_FORBIDDEN`(403)、`PARAM_INVALID`(400)、`BUSINESS_RULE_VIOLATION`(422)。
- 请求头 `Authorization: Bearer <accessToken>`；响应头 `X-Trace-Id` 可用于排障反馈。
- 访问令牌 15 分钟、刷新令牌 7 天；刷新即轮换，旧刷新令牌立即失效。

## 权限点（S0-07 定稿）

| 权限码 | 授权表达式 | 说明 |
|---|---|---|
| `m0:read` | `PERM_m0:read` | M0 公共支撑只读（会话/审计等） |
| `m0:write` | `PERM_m0:write` | M0 公共支撑维护（写接口预留） |

角色码 `SYS_ADMIN`、`SYS_ANESTHESIOLOGIST`、`SYS_NURSE_ANESTHETIST`、`SYS_SURGEON`、
`SYS_OR_MANAGER`、`SYS_QC_MANAGER` 与 §2.1.2 R1–R11 的完整映射见
`docs/modules/m0/README.md`。前端只消费 `/v1/auth/me` 返回的 `roles`/`permissions`，
不在前端硬编码角色判断。

## 状态

- S0-02：分包占位，无页面代码。
- S0-07：后端认证/权限骨架完成，本目录仅补接口与权限点索引；**不做 UI（S0-08）**。
- S0-08 待办：登录页、路由守卫、令牌刷新拦截器、菜单/按钮级鉴权；
  届时通过 `@anesop/contracts` 引用 `packages/contracts/generated/typescript/auth.d.ts`
  （S0-08 会在 `apps/web/package.json` 增加 workspace 依赖，本任务不改前端依赖）。
