# m8 — 驾驶舱

**一句话职责**：实时手术态势、临床风险、资源态势、运营 KPI、质量与安全、大屏与移动摘要。

## 数据归属

- schema：`m8_dash`（本模块独占；其他模块只能经 `m8-api` 访问，禁止直连本 schema 的表）

## 结构

```text
m8/
├── api/    # artifactId=m8-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m8-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m8-impl` → `m8-api`；其他模块 → `m8-api`；
- 禁止：任何模块 → `m8-impl`；`m8-impl` → 其他模块 `impl`；模块间循环依赖。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
