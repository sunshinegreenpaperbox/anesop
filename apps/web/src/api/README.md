# src/api — 接口层与契约生成类型

## 目录

| 文件 | 职责 |
|---|---|
| `http.ts` | 基于原生 `fetch` 的请求封装：Bearer、`X-Trace-Id`、超时、契约 `Error` 解析、401 单飞刷新并重放、网络错误归类 |
| `auth.ts` | 认证接口：login / refresh / logout / me / sessions / changePassword，类型来自 `@anesop/contracts` |
| `session.ts` | 会话存储：access（内存 + sessionStorage）、refresh（sessionStorage）、`CurrentUser` 快照与过期时间 |
| `generated/` | 由 `packages/contracts/openapi/*.yaml` 生成的 TypeScript 类型，**禁止手改** |

## 约定

- 页面不直接调用 `fetch`；统一走 `http.ts`，便于集中处理认证、超时与错误。
- `http.ts` 的 `baseUrl` 默认 `/api`：开发由 Vite proxy 去前缀转发到 8080，生产由 Nginx 反代；
  可用 `VITE_API_BASE_URL` 覆盖。
- 错误统一抛 `ApiError`（`status/code/message/traceId/details/kind`），页面展示 message 与
  traceId，按 code 做分支；禁止解析后端自由文本。
- 生成类型经 `packages/contracts/package.json` 的 `exports` 子路径导入，例如：

```ts
import type { components } from '@anesop/contracts/auth'
type CurrentUser = components['schemas']['CurrentUser']
```

- 生成流程：契约评审 → `bash scripts/gen-contracts.sh` → 提交生成物，CI 校验生成物与契约一致。
- 会话存储的安全权衡与 M1 httpOnly Cookie 迁移待办见 `docs/ui/README.md`。
