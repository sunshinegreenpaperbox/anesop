# packages/contracts — 契约优先的唯一事实源

OpenAPI 3.1 契约与由契约生成的 TS/Java 类型都从这里出发。
契约先评审、再由生成脚本产出类型；前端 `apps/web/src/api/generated/` 禁止手改。

## 目录约定

```text
packages/contracts/
├── openapi/          # <域>.yaml：auth / patient / scheduling / anesthesia ...
└── README.md
```

## 规则（AGENTS.md「契约优先与数据变更」）

- 顺序：领域模型 → `db/migration/V<版本>__<描述>.sql` → `openapi/<域>.yaml` → 编码；
- 字段只增不删；删除或改类型升 major，双写过渡并写 ADR；
- 破坏性变更须 oasdiff 拦截 + 兼容说明 + 双人评审；
- `spectral lint` 进 CI。

## 状态

S0-02 仅建目录占位（`openapi/.gitkeep`）。auth / patient / scheduling / anesthesia
四域骨架与 spectral 规则由 S0-05 落地，本任务不写契约文件。
