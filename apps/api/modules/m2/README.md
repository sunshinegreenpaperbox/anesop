# m2 — 麻醉信息管理

**一句话职责**：术前访视与风险评分、麻醉计划与知情同意、三方核查、术中记录与设备采集、用药输液、预警、抢救与不良事件、复苏与随访、总结与质控提取。

## 数据归属

- schema：`m2_anes`（本模块独占；其他模块只能经 `m2-api` 访问，禁止直连本 schema 的表）

## 目录与结构

```text
m2/
├── api/    # artifactId=m2-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m2-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m2-impl` → `m2-api`；其他模块 → `m2-api`；
- 禁止：任何模块 → `m2-impl`；`m2-impl` → 其他模块 `impl`；模块间循环依赖。

## 如何运行

本模块不单独启动，由 `api-bootstrap` 统一装配：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
```

已实现术前访视/知情同意/三方核查（V10/V11）、麻醉记录单/时间轴/用药/出入量（V13，契约
anesthesia 0.5.0）与设备采集接入/台账（V14，契约 device 0.1.0）；运行需 PostgreSQL 16
（Docker 不可用，EXT-08）。

## 如何测试

```bash
# 本模块及其依赖（单元 + 集成；集成测试用 Zonky 嵌入式 PG16）
./scripts/mvn.sh -B -pl apps/api/modules/m2/impl -am test
# 采集链路的端到端用例在网关模块
./scripts/mvn.sh -B -f services/device-gateway/pom.xml verify
```

- 核心域要求：行 ≥80%/分支 ≥70%（新增代码同口径）；契约端点 100% 有用例；
- 临床专项（时间轴、断网采集、并发修改、签名锁定）为发布准入项，落地时必须有专项用例；
- 模块边界由 `ModuleBoundaryTest`（ArchUnit）校验。

## 关键契约

- 本域契约：`packages/contracts/openapi/anesthesia.yaml`（术前访视、知情同意、三方核查、
  记录单/时间轴/用药/出入量 0.5.0）与 `packages/contracts/openapi/device.yaml`（设备登记、
  网关上报、体征查询、健康摘要 0.1.0）；患者信息涉及 `patient.yaml`。
- 采集数据表：`db/migration/V4__m2_anes_acquisition_init.sql`（`m2_anes.anesthesia_vital`，
  S0-09 POC 已落库）；记录单/时间轴/用药/出入量表见 V13（`anesthesia_record`、
  `record_event`、`drug_administration`、`fluid_record`）；设备登记/上报幂等日志见 V14
  （`device_registry`、`device_ingest_log`）。
- 跨模块只依赖 `m2-api`；禁止其他模块直连 `m2_anes` 表。

## 负责人（占位）

- Owner：`@anesop/m2-anesthesia`、`@anesop/clinical-safety`（双人评审；均为 CODEOWNERS 占位团队）
- 备份：`@anesop/maintainers`
- 状态：S0 仅采集表与骨架；M2 业务随 2027-01-29 MVP 里程碑实现。

## 状态

S0-02 空模块占位；S0-09 新增 V4 `m2_anes.anesthesia_vital` 采集表；M2-M4-01/M2-M5-01 落地
术前访视与知情同意/三方核查（V10/V11）；M3-M2-01 落地麻醉记录单、append-only 时间轴、
用药（麻精双人核对）与出入量（V13，契约 anesthesia 0.5.0）；M3-M8-01 落地设备采集接入
（V14，契约 device 0.1.0；数据范围、健康窗口与 `last_seen` 语义见
`docs/modules/m2/README.md` §12）。预警/抢救（M3-M2-02）、锁单/修订/CA（M3-M2-03）、
复苏与随访待后续任务。
