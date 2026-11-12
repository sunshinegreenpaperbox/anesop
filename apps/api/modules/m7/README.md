# m7 — 质量控制

**一句话职责**：指标体系、自动计算与钻取、不良事件与 RCA/PDCA、质控看板与报告、病历质量抽查、规则版本管理。

## 数据归属

- schema：`m7_qc`（本模块独占；其他模块只能经 `m7-api` 访问，禁止直连本 schema 的表）

## 结构

```text
m7/
├── api/    # artifactId=m7-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m7-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m7-impl` → `m7-api`；其他模块 → `m7-api`；
- 禁止：任何模块 → `m7-impl`；`m7-impl` → 其他模块 `impl`；模块间循环依赖。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
