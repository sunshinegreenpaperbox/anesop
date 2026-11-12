# m6 — 工作量统计

**一句话职责**：指标口径、多维度统计、绩效口径配置、报表导出与数据锁定、DRG/DIP 联动（可选）、数据质量校验。

## 数据归属

- schema：`m6_stats`（本模块独占；其他模块只能经 `m6-api` 访问，禁止直连本 schema 的表）

## 结构

```text
m6/
├── api/    # artifactId=m6-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m6-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m6-impl` → `m6-api`；其他模块 → `m6-api`；
- 禁止：任何模块 → `m6-impl`；`m6-impl` → 其他模块 `impl`；模块间循环依赖。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
