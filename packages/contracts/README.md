# packages/contracts — 契约优先的唯一事实源

OpenAPI 3.1 契约、Spectral 规则与由契约生成的 TypeScript 类型都从这里出发。
契约先评审、再由生成脚本产出类型；前端 `apps/web/src/api/generated/` 与后端 DTO 禁止手改。

## 目录

```text
packages/contracts/
├── openapi/
│   ├── auth.yaml           # 认证与令牌
│   ├── patient.yaml        # 患者与手术申请
│   ├── scheduling.yaml     # 手术排程
│   ├── anesthesia.yaml     # 麻醉记录
│   └── baseline/           # 当前兼容基线（4 份快照）
├── rules/
│   └── error-responses.js  # 自定义 Spectral 函数：401/403/404/409
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
- `spectral lint` 进 CI；baseline 仅在评审通过且确认兼容后人工更新。

## 命令

```bash
source scripts/env.sh

pnpm -C packages/contracts lint             # spectral：0 error / 0 warning
pnpm -C packages/contracts gen:ts           # 生成 4 份 .d.ts（带 DO NOT EDIT 头）
pnpm -C packages/contracts check:breaking   # baseline → 当前：破坏性变更
bash scripts/contract-check.sh              # lint + breaking
```

工具版本：`@stoplight/spectral-cli 6.16.0`（Apache-2.0）、`openapi-typescript 7.13.0`（MIT）、
`@oasdiff-js/oasdiff-js 1.0.0`（Apache-2.0，内含 oasdiff 1.15.0）。依赖登记见 `docs/deps/allowlist.md`。

## 状态

S0-05 已落地四域契约、Spectral 规则、TS 生成、baseline 与破坏性变更门禁。后续新增域（如 drug、quality）
按同一结构扩展，并同步更新 `docs/api/README.md` 与 `docs/data-dictionary/`。
