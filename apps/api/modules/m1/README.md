# m1 — 手术排程管理

**一句话职责**：申请接收与校验、规则排程、拖拽工作台与冲突检测、急诊加台与停改台、术前准备联动、通知与回执、排程分析。

## 数据归属

- schema：`m1_sched`（本模块独占；其他模块只能经 `m1-api` 访问，禁止直连本 schema 的表）

## 结构

```text
m1/
├── api/    # artifactId=m1-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m1-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m1-impl` → `m1-api`；其他模块 → `m1-api`；
- 禁止：任何模块 → `m1-impl`；`m1-impl` → 其他模块 `impl`；模块间循环依赖。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
