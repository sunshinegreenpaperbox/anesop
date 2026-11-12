# m2 — 麻醉信息管理

**一句话职责**：术前访视与风险评分、麻醉计划与知情同意、三方核查、术中记录与设备采集、用药输液、预警、抢救与不良事件、复苏与随访、总结与质控提取。

## 数据归属

- schema：`m2_anes`（本模块独占；其他模块只能经 `m2-api` 访问，禁止直连本 schema 的表）

## 结构

```text
m2/
├── api/    # artifactId=m2-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m2-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m2-impl` → `m2-api`；其他模块 → `m2-api`；
- 禁止：任何模块 → `m2-impl`；`m2-impl` → 其他模块 `impl`；模块间循环依赖。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
