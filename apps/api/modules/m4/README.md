# m4 — 耗材仓库管理

**一句话职责**：主数据与 UDI、出入库与效期、术间领用与消耗、高值一物一码、库存预警与盘点、供应商证照、SPD 对账。

## 数据归属

- schema：`m4_supply`（本模块独占；其他模块只能经 `m4-api` 访问，禁止直连本 schema 的表）

## 结构

```text
m4/
├── api/    # artifactId=m4-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m4-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m4-impl` → `m4-api`；其他模块 → `m4-api`；
- 禁止：任何模块 → `m4-impl`；`m4-impl` → 其他模块 `impl`；模块间循环依赖。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
