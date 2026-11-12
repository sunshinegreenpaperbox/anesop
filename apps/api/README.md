# apps/api — ANESOP 业务应用（模块化单体）

Java 21 + Spring Boot 3.4 多模块工程。唯一可执行装配点是 `api-bootstrap`；
业务按 M0–M8 分模块，每个模块固定拆成 `api`（对外契约）与 `impl`（实现与数据访问）。

## 模块与数据归属（对齐方案书表 3-2）

| 模块 | 职责 | 依赖 | 数据归属 schema |
|---|---|---|---|
| m0 | 公共支撑：认证授权、字典、工作流、审计、报表引擎、文件存储 | m0-api | `m0_platform` |
| m1 | 手术排程管理 | m1-api | `m1_sched` |
| m2 | 麻醉信息管理（记录单、采集、复苏随访） | m2-api | `m2_anes` |
| m3 | 人员管理（档案、授权、排班、培训） | m3-api | `m3_staff` |
| m4 | 耗材仓库管理（UDI、出入库、效期） | m4-api | `m4_supply` |
| m5 | 药品统计（麻精五专、日结班结） | m5-api | `m5_drug` |
| m6 | 工作量统计（读模型） | m6-api | `m6_stats` |
| m7 | 质量控制（指标、不良事件、抽查） | m7-api | `m7_qc` |
| m8 | 驾驶舱（读模型） | m8-api | `m8_dash` |

## 依赖规则（ArchUnit + CI 双重检查）

1. 跨模块只能依赖对方的 `api` 子模块，禁止依赖任何 `impl`；
2. 禁止跨模块访问对方 Mapper / 数据表；跨模块只传 DTO，不传可变实体；
3. 禁止模块间循环依赖；数据库 schema 隔离做兜底。

## 命令（仓库根执行，先 `source scripts/env.sh`）

```bash
./scripts/mvn.sh -B -f apps/api/pom.xml verify   # 编译、测试、模块边界校验
```

## 状态

S0-02 仅落地骨架：目录、POM、启动类、健康检查与模块边界用例；不含业务实现。
`api-bootstrap` 当前只依赖各模块 `api`，`impl` 的装配在 S0-07 引入。
