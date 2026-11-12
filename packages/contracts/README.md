# packages/contracts — 契约优先的唯一事实源

**一句话职责**：维护四域 OpenAPI 3.1 契约、Spectral 规则与生成类型，作为前后端接口的
唯一事实源；契约先评审，再由脚本生成代码。

OpenAPI 3.1 契约、Spectral 规则与由契约生成的 TypeScript 类型都从这里出发。
契约先评审、再由生成脚本产出类型；前端 `apps/web/src/api/generated/` 与后端 DTO 禁止手改。

## 目录

```text
packages/contracts/
├── openapi/
│   ├── auth.yaml           # 认证与令牌
│   ├── patient.yaml        # 患者与手术申请
│   ├── scheduling.yaml     # 手术排程
│   ├── anesthesia.yaml     # 麻醉记录、锁单、三方核查
│   └── baseline/           # 当前兼容基线（4 份快照）
├── rules/
│   └── error-responses.js  # 自定义 Spectral 函数：错误响应存在性、条件化与 $ref 语义
├── generated/typescript/   # openapi-typescript 生成物（禁止手改）
├── .spectral.yaml
├── package.json
└── README.md
```

契约说明、认证/错误/分页约定与版本流程见 `docs/api/README.md`；实体字段见 `docs/data-dictionary/`。

## 规则（AGENTS.md「契约优先与数据变更」）

- 顺序：领域模型 → `db/migration/V<版本>__<描述>.sql` → `openapi/<域>.yaml` → 编码；
- 字段只增不删；删除或改类型升 major，双写过渡并写 ADR；
- 破坏性变更须 oasdiff 拦截 + 兼容说明 + 双人评审；
- `spectral lint` 进 CI（0 error / 0 warning）；baseline 仅在人工评审通过且确认兼容后复制更新，并在同一 PR 说明。

## 命令

```bash
source scripts/env.sh

pnpm -C packages/contracts lint             # spectral：0 error / 0 warning
pnpm -C packages/contracts gen:ts           # 生成 4 份 .d.ts（带 DO NOT EDIT 头）
pnpm -C packages/contracts check:breaking   # baseline → 当前：破坏性变更
bash scripts/contract-check.sh               # lint + breaking + 生成物漂移
```

工具版本：`@stoplight/spectral-cli 6.16.0`（Apache-2.0）、`openapi-typescript 7.13.0`（MIT）、
`@oasdiff-js/oasdiff-js 1.0.0`（Apache-2.0，内含 oasdiff 1.15.0）。依赖登记见 `docs/deps/allowlist.md`。

## 如何运行

本包不单独运行：它是契约与生成类型的源，被 `apps/web`（TS 类型）与 `apps/api/api-contracts`
（Java DTO）消费。

```bash
source scripts/env.sh
pnpm install --frozen-lockfile
pnpm -C packages/contracts lint              # 只跑 spectral
bash scripts/contract-check.sh               # lint + breaking + 生成物漂移（推荐）
```

## 如何测试

```bash
# 1) 契约静态检查：0 error / 0 warning
pnpm -C packages/contracts lint

# 2) 生成物可编译/可复现（重跑生成后 git diff 必须为空）
bash scripts/gen-contracts.sh --all
git diff --exit-code packages/contracts/generated apps/web/src/api/generated

# 3) 破坏性变更：baseline → 当前
pnpm -C packages/contracts check:breaking

# 4) Java DTO 语义用例（生成后编译）
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-contracts -am test
```

CI 对应 `ci/contract`；规则例外与 baseline 更新流程见 `docs/api/README.md`。

## 关键契约

| 契约 | 域 | 关键约定 |
|---|---|---|
| `openapi/auth.yaml` | 认证 | login/refresh/logout/me/password/sessions；错误码 `AUTH_*` |
| `openapi/patient.yaml` | 患者/申请 | 列表只返回 `PatientSummary`，住院号标 `x-restricted` |
| `openapi/scheduling.yaml` | 排程 | 冲突返回 409 `SCHEDULE_CONFLICT`；急诊加台/停台 |
| `openapi/anesthesia.yaml` | 麻醉 | 访视/记录单/锁单/三方核查/随访；更新必须回传 `version` |

- 全局约定：Base URL `/api`、路径 `/v1/...`、HTTPS+JSON、RFC 3339、雪花 ID 序列化为字符串、
  统一 `Error {code,message,traceId,details}`、401/403/400/404/409/422 共享响应组件。
- 字段只增不删；删除/改类型升 major、双写过渡并写 ADR；baseline 仅在人工评审通过后更新。
- 生成物（`generated/typescript/**`、`apps/web/src/api/generated/**`、Java DTO）禁止手改。

## 负责人（占位）

- Owner：`@anesop/contracts` + `@anesop/architects`（契约变更双人评审）
- 备份：`@anesop/maintainers`
- 状态：S0-05 基线；新增域（drug/quality 等）按同一结构扩展并同步数据字典。

## 状态

S0-05 已落地四域契约、Spectral 规则、TS 生成、baseline 与破坏性变更门禁。后续新增域（如 drug、quality）
按同一结构扩展，并同步更新 `docs/api/README.md` 与 `docs/data-dictionary/`。
