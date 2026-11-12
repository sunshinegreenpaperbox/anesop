# API 契约（OpenAPI 3.1）

> 状态：S0-05 契约基线（`info.version=0.1.0`）。契约是接口的唯一事实源，前端 `apps/web/src/api/generated/` 与后端 DTO 都必须由契约派生，禁止手改生成物。
>
> 事实来源：方案书 §3.4（领域模型）、§3.5（接口与集成）、§3.9.2（RBAC 与数据权限）、§2.4.1（M0）、§2.4.2（M1）、§2.4.3（M2）。

## 1. 目录与契约清单

```text
packages/contracts/
├── openapi/
│   ├── auth.yaml          # 认证、令牌、当前用户、会话
│   ├── patient.yaml       # 患者主索引、手术申请
│   ├── scheduling.yaml    # 术间、排程、冲突、急诊加台、停台
│   ├── anesthesia.yaml    # 术前访视、记录单、锁单、随访
│   └── baseline/          # 当前已发布兼容基线（4 份快照）
├── rules/error-responses.js
├── generated/typescript/  # openapi-typescript 生成物（禁止手改）
├── .spectral.yaml
└── package.json
```

| 契约 | 路径数 | operationId 数 | 覆盖能力 | 方案书对应 |
|---|---:|---:|---|---|
| `auth.yaml` | 5 | 5 | 登录、刷新、登出、当前用户、会话列表 | §2.4.1、§3.9.2、§3.9.5 |
| `patient.yaml` | 6 | 6 | 患者查询/详情、患者手术申请、申请接收/详情/取消 | §2.4.1、§2.4.2、§3.5.3 I-01a/I-01b |
| `scheduling.yaml` | 6 | 7 | 术间列表、排程查询/创建/详情、冲突检测、急诊加台、停台 | §2.4.2、§2.3.2、§3.4.2 |
| `anesthesia.yaml` | 5 | 8 | 术前访视读写、记录单读写、锁单、随访创建/查询/列表 | §2.4.3、§3.4.2、§3.9.4 |
| **合计** | **22** | **26** | 4 个限界上下文骨架 | — |

每份契约均定义共享组件：`Error`（`code/message/traceId/details`）、`PageMeta`、`AuditFields`（`createdAt/createdBy/updatedAt/updatedBy/version`）、`IdResponse`，以及统一的 401/403/404/409 响应。

## 2. 基础约定

- **Base URL**：`https://anesop.local/api`；路径带主版本 `/v1/...`，完整示例 `https://anesop.local/api/v1/auth/login`（§3.5.1）。
- **协议**：HTTPS + JSON（`application/json; charset=utf-8`）；生产网无互联网出口，患者数据不出院。
- **时间**：RFC 3339，服务端统一 `Asia/Shanghai` 展示；库内 `timestamptz`（§3.4.3）。
- **标识**：雪花 ID 在接口层统一序列化为字符串，避免前端 19 位整数精度丢失（§3.4.3）。
- **审计与并发**：业务实体返回 `AuditFields`；更新请求必须回传 `version`，冲突返回 409，不静默覆盖。
- **逻辑删除**：临床数据不物理删除；契约暂不暴露 `deletedAt`，后续按迁移与契约评审扩展。

## 3. 认证与授权

- 全局 `security: [{ bearerAuth: [] }]`；`bearerAuth` 为 HTTP Bearer + JWT（§3.9.5）。
- `POST /v1/auth/login` 与 `POST /v1/auth/refresh` 使用 `security: []`，其余接口均需访问令牌。
- 访问令牌短时效（示例 1800 秒），刷新令牌轮换；默认会话 30 分钟无操作失效，连续 5 次失败锁定 30 分钟（§3.9.1）。
- 权限粒度到“菜单 + 操作”（如 `anesthesia.record.lock`）；数据权限四类：`ALL`、`DEPARTMENT`、`ROOM`、`OWN_PATIENTS`（§3.9.2）。
- 令牌与日志不得包含姓名、身份证、住院号、手机号；审计日志保留 ≥6 个月，业务角色不可删除（§3.9.1）。

## 4. 错误模型

统一返回 `Error`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | string | 稳定错误码，前端分支与告警使用，如 `AUTH_UNAUTHORIZED`、`SCHEDULE_CONFLICT` |
| `message` | string | 可安全展示的中文提示 |
| `traceId` | string | 与日志/审计关联的追踪标识 |
| `details` | array | `{field, reason}` 明细；无明细为空数组 |

| 状态码 | 语义 | 约定 |
|---|---|---|
| 401 | 未认证/令牌失效 | 不区分账号不存在与口令错误，避免账号枚举 |
| 403 | 无权限/超出数据范围 | 越权访问记审计并告警 |
| 404 | 资源不存在或不可见 | 对无数据权限的资源同样返回 404，避免泄露存在性 |
| 409 | 状态/版本/排程冲突 | 乐观锁、锁单后修改、排程重叠、重复提交 |
| 201 | 创建成功 | 仅用于创建类接口；响应体返回创建后的实体 |

错误信息不得包含堆栈、SQL 实参、内部路径与患者标识；示例统一使用 `SYNTH-*` 合成值。

## 5. 分页约定

- 查询参数 `page`（从 1 开始，默认 1）与 `size`（默认 20 或 50，最大 200）。
- 响应统一为 `{ items: [...], meta: PageMeta }`；`PageMeta = { page, size, total, totalPages }`。
- 非分页列表（如术间列表）返回 `{ items: [...] }`，不返回 `meta`。
- 排序默认按业务时间倒序；如需显式排序，后续以新增查询参数方式扩展，不改变既有字段。

## 6. 示例与合成数据

- 所有示例均为 **SYNTHETIC** 合成数据：患者编号固定 `SYNTH-P-0001`，姓名固定“演示患者”，住院号 `SYNTH-ZY-*`，HIS 申请号 `SYNTH-HIS-APP-*`。
- 禁止在契约、示例、截图、测试夹具中出现真实患者姓名、身份证、住院号、手机号、人脸或可重识别的组合。
- 需要新增示例时，沿用 `SYNTH-*` 前缀并在 PR 中声明“示例为合成数据”。

## 7. 版本与破坏性变更流程

- `info.version` 采用语义化版本；当前 `0.1.0`。路径主版本为 `/v1`。
- **兼容变更**（只增不删）：新增可选字段、新增接口、新增枚举值（调用方需容忍未知值）；更新 `minor`/`patch`，同步更新 baseline。
- **破坏性变更**（删除字段、改类型、收紧必填、删除接口/枚举值、改语义）：升 `major`，新增 `/v2` 路径，旧版至少保留两个迭代，写 ADR 并双人评审（§3.5.1、AGENTS「契约优先与数据变更」）。
- **基线**：`openapi/baseline/<域>.yaml` 是上一次评审通过的兼容快照。只有契约评审通过且确认兼容后，才可人工复制当前契约覆盖 baseline，并在 PR 说明；禁止为了过检而修改 baseline。
- **拦截**：`scripts/contract-check.sh` 以 `oasdiff breaking --fail-on WARN` 对比 baseline 与当前契约，任一破坏性变更即非 0 退出，并打印 `[response-required-property-removed]` 等规则码。
- 破坏性变更 PR 必须附兼容说明、影响面、回滚方案与双人评审；不满足即阻断合并。

## 8. 校验与生成命令

```bash
source scripts/env.sh

# 1) 契约 lint（0 error；warning 目标 0）
pnpm -C packages/contracts lint

# 2) 契约 → TS 类型（生成物带 DO NOT EDIT 头）
bash scripts/gen-contracts.sh
pnpm -C packages/contracts gen:ts          # 等价入口

# 3) 生成物可编译
pnpm exec tsc --noEmit --strict packages/contracts/generated/typescript/*.d.ts

# 4) baseline → 当前：spectral + 破坏性变更
bash scripts/contract-check.sh
pnpm -C packages/contracts check:breaking   # 仅 breaking
```

`scripts/check.sh` 第 6 步调用 `contract-check.sh`；主会话全量门禁无需额外命令。

## 9. 工具与依赖

| 工具 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| `@stoplight/spectral-cli` | 6.16.0 | Apache-2.0 | OpenAPI 3.1 lint |
| `openapi-typescript` | 7.13.0 | MIT | 契约 → TS 类型 |
| `@oasdiff-js/oasdiff-js` | 1.0.0 | Apache-2.0 | Node 包装的 oasdiff 1.15.0，破坏性变更检测 |
| Node.js | ≥20.17（本机 26.7.0） | — | 工具运行环境 |

依赖登记状态：`docs/deps/allowlist.md` 第三节已将 spectral / openapi-typescript / openapi-diff(oasdiff 等价) 列为“待引入”。实际采用 `@oasdiff-js/oasdiff-js`（Apache-2.0），需由主会话在 allowlist 中把 oasdiff 行登记为已引入、openapi-diff 行标注为未采用。

## 10. 【待确认】

- CA 签名与时间戳的厂商、接口与验签方式（§3.9.4，标【待确认】）；当前契约只保留 `signatureStatus`，不写外部 CA 接口。
- 会话与刷新令牌的最终时效、是否强制双因素，需安全评审确认（当前示例 1800 秒 / 30 分钟）。
- 排程冲突的“人员资质/设备依赖”校验字段与字典编码，待 M1 规则引擎评审后补充。
- 生命体征高频序列与波形查询接口不在本基线，按 §3.4.4 的分区/归档方案在 M2 细化。
