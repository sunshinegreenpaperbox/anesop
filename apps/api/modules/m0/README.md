# m0 — 公共支撑

**一句话职责**：统一认证与 SSO、RBAC 与数据权限、组织与字典、工作流与消息、审计日志、报表引擎与打印模板、CA 签名、接口平台与监控、文件存储、时间同步。

## 数据归属

- schema：`m0_platform`（本模块独占；其他模块只能经 `m0-api` 访问，禁止直连本 schema 的表）

## 结构

```text
m0/
├── api/    # artifactId=m0-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m0-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m0-impl` → `m0-api`；其他模块 → `m0-api`；
- 禁止：任何模块 → `m0-impl`；`m0-impl` → 其他模块 `impl`；模块间循环依赖。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
