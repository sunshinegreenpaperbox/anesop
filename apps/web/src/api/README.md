# src/api — 接口层与契约生成类型

- `generated/`：由 `packages/contracts/openapi/*.yaml` 生成的 TypeScript 类型与客户端，**禁止手改**；
- 手写请求封装（token 刷新、错误处理、重试）放在本目录，S0-07/S0-08 落地；
- 生成流程：契约评审 → `pnpm` 生成脚本 → 提交生成物，CI 校验生成物与契约一致。

## 状态

S0-02 仅建目录说明；`generated/` 与生成脚本由 S0-05（契约基线）建立。
