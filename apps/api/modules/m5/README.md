# m5 — 药品统计

**一句话职责**：药品目录与麻精分类、五专管理、基数与请领、日结班结、消耗统计、配伍与异常提醒、上报报表。

## 数据归属

- schema：`m5_drug`（本模块独占；其他模块只能经 `m5-api` 访问，禁止直连本 schema 的表）

## 结构

```text
m5/
├── api/    # artifactId=m5-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m5-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m5-impl` → `m5-api`；其他模块 → `m5-api`；
- 禁止：任何模块 → `m5-impl`；`m5-impl` → 其他模块 `impl`；模块间循环依赖。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
