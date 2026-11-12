# m6 — 工作量统计

**一句话职责**：指标口径、多维度统计、绩效口径配置、报表导出与数据锁定、DRG/DIP 联动（可选）、数据质量校验。

## 数据归属

- schema：`m6_stats`（本模块独占；其他模块只能经 `m6-api` 访问，禁止直连本 schema 的表）

## 目录与结构

```text
m6/
├── api/    # artifactId=m6-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m6-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m6-impl` → `m6-api`；其他模块 → `m6-api`；
- 禁止：任何模块 → `m6-impl`；`m6-impl` → 其他模块 `impl`；模块间循环依赖。

## 如何运行

本模块不单独启动，由 `api-bootstrap` 统一装配：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
```

当前为骨架，启动后没有 M6 端点；运行需 PostgreSQL 16（Docker 不可用，EXT-08）。

## 如何测试

```bash
# 本模块及其依赖（单元 + 集成；集成测试用 Zonky 嵌入式 PG16）
./scripts/mvn.sh -B -pl apps/api/modules/m6/impl -am test
```

- 统计口径是核心域：每个指标需有固定输入/期望输出的口径用例，禁止只测快乐路径；
- 覆盖率：整体行 ≥70%/分支 ≥55%，新增代码 ≥80%/70%；
- 报表性能基准（排程页 1000 条 <2s、报表 <10s）在 M6 验收时补本地基准。

## 关键契约

- 本域暂无 OpenAPI 契约：M6 启动时先定指标口径与读模型，再新增契约
  （顺序见 [`docs/api/README.md`](../../../../docs/api/README.md)）。
- 只读消费其他模块数据：经对方 `api` 接口或领域事件，不直连 `m1_sched`/`m2_anes` 等表；
- 数据锁定与导出（DRG/DIP 联动）涉及 EXT-07，需在接口层标注外部依赖状态。

## 负责人（占位）

- Owner：`@anesop/m6-workload`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
- 状态：S0 空模块；随 M6 里程碑启动。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
