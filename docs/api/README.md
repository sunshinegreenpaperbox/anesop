# API 契约（OpenAPI 3.1）

> 状态：S0-05 契约基线 + M2-M0-01 数据范围 + M2-M0-02 用户管理（`info.version=0.5.0`）。契约是接口的唯一事实源，前端 `apps/web/src/api/generated/` 与后端 DTO 都必须由契约派生，禁止手改生成物。
>
> 事实来源：方案书 §3.4（领域模型）、§3.5（接口与集成）、§3.9.2（RBAC 与数据权限）、§2.4.1（M0）、§2.4.2（M1）、§2.4.3（M2）。

## 1. 目录与契约清单

```text
packages/contracts/
├── openapi/
│   ├── auth.yaml          # 认证、令牌、当前用户、会话
│   ├── patient.yaml       # 患者主索引、手术申请
│   ├── scheduling.yaml    # 术间、排程、冲突、急诊加台、停台
│   ├── anesthesia.yaml    # 术前访视、记录单、锁单、三方核查、随访
│   └── baseline/          # 当前已发布兼容基线（4 份快照）
├── rules/error-responses.js
├── generated/typescript/  # openapi-typescript 生成物（禁止手改）
├── .spectral.yaml
└── package.json
```

| 契约 | 路径数 | operationId 数 | 覆盖能力 | 方案书对应 |
|---|---:|---:|---|---|
| `auth.yaml` | 10 | 12 | 登录、刷新、登出、当前用户、改密、会话列表、数据范围示例、用户管理（列表/详情/创建/更新/重置口令） | §2.4.1、§3.9.2、§3.9.3、§3.9.5 |
| `patient.yaml` | 6 | 6 | 患者查询/详情、患者手术申请、申请接收/详情/取消 | §2.4.1、§2.4.2、§3.5.3 I-01a/I-01b |
| `scheduling.yaml` | 6 | 7 | 术间列表、排程查询/创建/详情、冲突检测、急诊加台、停台 | §2.4.2、§2.3.2、§3.4.2 |
| `anesthesia.yaml` | 6 | 10 | 术前访视读写、记录单读写、锁单、三方核查读写、随访创建/查询/列表 | §2.4.3、§3.4.2、§3.9.4 |
| **合计** | **28** | **35** | 4 个限界上下文骨架 | — |

每份契约均定义共享组件：`Error`（`code/message/traceId/details`）、`PageMeta`、`AuditFields`（`createdAt/createdBy/updatedAt/updatedBy/version`）、`IdResponse`（按域按需定义，auth 已移除未使用组件），以及 `401 Unauthorized`、`403 Forbidden`、`400 BadRequest`、`404 NotFound`、`409 Conflict`、`422 UnprocessableEntity` 响应组件。各域只保留实际使用到的组件，`oas3-unused-component` 保持 0 warning。

## 2. 基础约定

- **Base URL**：`https://anesop.local/api`；路径带主版本 `/v1/...`，完整示例 `https://anesop.local/api/v1/auth/login`（§3.5.1）。
- **协议**：HTTPS + JSON（`application/json; charset=utf-8`）；生产网无互联网出口，患者数据不出院。
- **时间**：RFC 3339，服务端统一 `Asia/Shanghai` 展示；库内 `timestamptz`（§3.4.3）。
- **标识**：雪花 ID 在接口层统一序列化为字符串，避免前端 19 位整数精度丢失（§3.4.3）。
- **审计与并发**：业务实体返回 `AuditFields`；更新请求必须回传 `version`，冲突返回 409，不静默覆盖。
- **逻辑删除**：临床数据不物理删除；契约暂不暴露 `deletedAt`，后续按迁移与契约评审扩展。
- **脱敏返回**：列表只返回 `PatientSummary`（无出生日期、无住院号明文）；详情返回 `Patient`，住院号标 `x-restricted: true`。规则见第 6 节。

## 3. 认证与授权

- 全局 `security: [{ bearerAuth: [] }]`；`bearerAuth` 为 HTTP Bearer + JWT（§3.9.5）。
- `POST /v1/auth/login` 与 `POST /v1/auth/refresh` 使用 `security: []`，其余接口均需访问令牌；`logout` 成功返回 `204`（无响应体），仅声明 204/401。
- 访问令牌短时效（示例 1800 秒），刷新令牌轮换；默认会话 30 分钟无操作失效，连续 5 次失败锁定 30 分钟（§3.9.1）。
- 权限粒度到“菜单 + 操作”（如 `anesthesia.record.lock`）；数据权限四类：`ALL`、`DEPARTMENT`、`ROOM`、`OWN_PATIENTS`（§3.9.2）。
- 令牌与日志不得包含姓名、身份证、住院号、手机号；审计日志保留 ≥6 个月，业务角色不可删除（§3.9.1）。
- **用户管理（M2-M0-02，0.5.0 additive）**：`/v1/auth/users` 系列端点需要 `m0:user:read` /
  `m0:user:write`（V6 仅授予 `SYS_ADMIN`）。手机号只以 `phoneMasked`（如 `138****5678`）返回，
  契约中**没有**手机号明文、`phone_enc` 密文、`phone_hash` 哈希字段；创建与管理员重置口令都强制
  `mustChangePassword=true`，重置的一次性初始口令仅本次响应返回，并在同一事务撤销该用户全部
  刷新令牌；更新工号撞院内唯一键返回 409。示例全部为 SYNTHETIC。

## 4. 错误模型

统一返回 `Error`；所有 4xx/5xx 响应必须 `$ref` 到 `components.responses` 共享组件，禁止内联定义。

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | string | 稳定错误码，前端分支与告警使用，如 `AUTH_UNAUTHORIZED`、`SCHEDULE_CONFLICT` |
| `message` | string | 可安全展示的中文提示 |
| `traceId` | string | 与日志/审计关联的追踪标识 |
| `details` | array | `{field, reason}` 明细；无明细为空数组 |

| 状态码 | 语义 | 声明条件 |
|---|---|---|
| 400 | 入参校验失败（`BadRequest`） | 带 `requestBody` 的 operation 必备；`details` 为字段级错误数组 |
| 401 | 未认证/令牌失效（`Unauthorized`） | 所有 operation 必备 |
| 403 | 无权限/超出数据范围（`Forbidden`） | 需认证的 operation 必备；`security: []` 的登录/刷新与 `logout` 除外 |
| 404 | 资源不存在或不可见（`NotFound`） | 仅路径含 `{资源参数}` 的单资源 operation；集合查询、创建接口不声明 |
| 409 | 状态/版本/排程/锁单/唯一键冲突（`Conflict`） | 仅状态变更操作（带 `requestBody`）声明；`logout`、登录/刷新不声明。用户管理：创建账号重复、更新工号撞 `uk_sys_user_employee_no` |
| 422 | 语义不合法（`UnprocessableEntity`） | 字段格式正确但业务规则拒绝（如患者不在主索引、术间不满足隔离要求） |
| 201 | 创建成功 | 创建类接口；响应体返回创建后的实体（`POST three-way-checks` 首次创建为 201，更新为 200） |
| 204 | 成功无响应体 | `POST /v1/auth/logout` |

越权语义固定为：**集合查询越权 → 403；单对象越界/不存在 → 404**（不泄露存在性，记审计并告警）。契约中 403 的描述统一为“无权限或超出数据范围”，404 的描述统一为“不存在或不可见”。

`scripts/contract-check.sh` 的 spectral 规则会校验：错误响应是否 `$ref` 到共享组件、状态码与组件名是否匹配、401 是否必备、需认证接口是否声明 403、带请求体是否声明 400、含路径参数是否声明 404，以及 404/409 是否越界声明。错误信息不得包含堆栈、SQL 实参、内部路径与患者标识；示例统一使用 `SYNTH-*` 合成值。

## 5. 分页约定

- 查询参数 `page`（从 1 开始，默认 1）与 `size`（默认 20 或 50，最大 200）。
- 响应统一为 `{ items: [...], meta: PageMeta }`；`PageMeta = { page, size, total, totalPages }`。
- 非分页列表（如术间列表）返回 `{ items: [...] }`，不返回 `meta`。
- 排序默认按业务时间倒序；如需显式排序，后续以新增查询参数方式扩展，不改变既有字段。

## 6. 示例与合成数据

- 所有示例均为 **SYNTHETIC** 合成数据：患者编号固定 `SYNTH-P-0001`，姓名固定“演示患者”，住院号 `SYNTH-ZY-*`，HIS 申请号 `SYNTH-HIS-APP-*`。
- 禁止在契约、示例、截图、测试夹具中出现真实患者姓名、身份证、住院号、手机号、人脸或可重识别的组合。
- 需要新增示例时，沿用 `SYNTH-*` 前缀并在 PR 中声明“示例为合成数据”。

### 6.1 脱敏字段

脱敏三问（是否含直接标识、能否组合重识别、是否必要）任一不过即只给字段名或改用合成数据（§3.9.3）。证件号不得保留出生日期段。

| 字段 | 返回范围 | 规则 |
|---|---|---|
| `idCardMasked` | 列表/详情 | 保留前 3 位 + 11 个 `*` + 后 4 位（共 18 位），如 `110***********1234`；不保留出生日期段 |
| `phoneMasked` | 列表/详情 | 前 3 后 4，如 `138****0000` |
| `birthDate` | 仅详情 | 列表 `PatientSummary` 不返回；与年龄/科室组合可能重识别，按最小必要 |
| `inpatientNoMasked` | 仅列表 | 脱敏住院号，列表不返回明文 |
| `inpatientNo` | 仅详情 | `x-restricted: true`；仅授权角色、传输加密、展示脱敏、访问记审计 |

## 7. 版本与破坏性变更流程

- `info.version` 采用语义化版本；当前 `0.5.0`（M2-M0-02：新增用户管理与 `phoneMasked`，`PUT /v1/auth/users/{userId}` 增加 409，compatible additive）。路径主版本为 `/v1`。
- **兼容变更**（只增不删）：新增可选字段、新增接口、新增响应字段、新增枚举值（调用方需容忍未知值）；更新 `minor`/`patch`，评审通过后同步 baseline。
- **阻断性变更**（oasdiff `--fail-on WARN` 即失败）：删除路径或 operation；请求体新增必填字段；请求字段类型变更；响应必填字段删除；响应字段类型变更。删除/收紧枚举值、改变字段语义同样按破坏性变更处理。
- **兼容例外（M2 契约冻结前）**：响应新增必填字段按 warning 处理、不阻断，但必须在 PR 说明调用方兼容方案，并在下表登记；M2 契约冻结前清零。

| 已登记例外 | oasdiff 行为 | 处理与截止 |
|---|---|---|
| 响应新增必填字段 | warning | 不阻断；PR 说明调用方兼容方案，M2 契约冻结前清零 |

- **基线**：`openapi/baseline/<域>.yaml` 是上一次人工评审通过的兼容快照。只有契约评审通过且确认兼容后，才可人工复制当前契约覆盖 baseline，并在**同一 PR** 说明版本、评审结论与差异；禁止为了过检而修改 baseline，禁止脚本自动覆盖。
- **拦截**：`scripts/contract-check.sh` 以 spectral（0 error/0 warning）+ oasdiff breaking（`--fail-on WARN`）+ 生成物漂移（重跑 `gen:ts` 并对比前后 `git diff`，干净树上等价于 `git diff --exit-code`）三道门禁；任一失败即非 0 退出。
- 破坏性变更 PR 必须附兼容说明、影响面、回滚方案与双人评审；不满足即阻断合并（§3.5.1、AGENTS「契约优先与数据变更」）。

## 8. 校验与生成命令

```bash
source scripts/env.sh

# 1) 契约 lint（--fail-severity=warn，必须 0 error / 0 warning）
pnpm -C packages/contracts lint

# 2) 契约 → TS 类型（生成物带 DO NOT EDIT 头）
bash scripts/gen-contracts.sh
pnpm -C packages/contracts gen:ts          # 等价入口

# 3) 生成物可编译
pnpm exec tsc --noEmit --strict packages/contracts/generated/typescript/*.d.ts

# 4) 契约门禁：lint + 破坏性变更 + 生成物漂移
bash scripts/contract-check.sh
pnpm -C packages/contracts check:breaking   # 仅 breaking
```

`scripts/check.sh` 第 6 步调用 `contract-check.sh`；漂移检查在 `--breaking-only`/`--lint-only` 模式下跳过，全量模式执行。

## 9. 工具与依赖

| 工具 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| `@stoplight/spectral-cli` | 6.16.0 | Apache-2.0 | OpenAPI 3.1 lint |
| `openapi-typescript` | 7.13.0 | MIT | 契约 → TS 类型 |
| `@oasdiff-js/oasdiff-js` | 1.0.0 | Apache-2.0 | Node 包装的 oasdiff 1.15.0，破坏性变更检测 |
| Node.js | ≥20.17（本机 26.7.0） | — | 工具运行环境 |

上述三项依赖均已引入、版本在 `pnpm-lock.yaml` 锁定，并在 `docs/deps/allowlist.md` 按“已引入”登记；`openapi-diff 0.24.1` 因不支持 OpenAPI 3.1 未采用。allowlist 由 S0-03 维护，本节只记录实际使用状态，不重复维护白名单。

## 10. 待办与【待确认】

- **角色/权限映射待办**：角色 R1–R12 与权限码（如 `anesthesia.record.lock`）的对应关系由 S0-07 定稿并回写契约与权限矩阵；本契约不自造映射表。
- CA 签名与时间戳的厂商、接口与验签方式（§3.9.4，标【待确认】）；当前契约只保留 `signatureStatus`，不写外部 CA 接口。
- DASI 取值范围按院方口径配置（通行 DASI 0–58.2，待临床确认），契约不设上限。
- 会话与刷新令牌的最终时效、是否强制双因素，需安全评审确认（当前示例 1800 秒 / 30 分钟）。
- 排程冲突的“人员资质/设备依赖”校验字段与字典编码，待 M1 规则引擎评审后补充。
- 生命体征高频序列与波形查询接口不在本基线，按 §3.4.4 的分区/归档方案在 M2 细化。
