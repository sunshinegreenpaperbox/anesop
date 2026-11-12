# m3 — 人员管理

**一句话职责**：人员档案、分级授权、排班、培训与学分、绩效基础数据、账号生命周期。

## 数据归属

- schema：`m3_staff`（本模块独占；其他模块只能经 `m3-api` 访问，禁止直连本 schema 的表）

## 结构

```text
m3/
├── api/    # artifactId=m3-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m3-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m3-impl` → `m3-api`；其他模块 → `m3-api`；
- 禁止：任何模块 → `m3-impl`；`m3-impl` → 其他模块 `impl`；模块间循环依赖。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
