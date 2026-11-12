# m8 — 驾驶舱

**一句话职责**：实时手术态势、临床风险、资源态势、运营 KPI、质量与安全、大屏与移动摘要。

## 数据归属

- schema：`m8_dash`（本模块独占；其他模块只能经 `m8-api` 访问，禁止直连本 schema 的表）

## 目录与结构

```text
m8/
├── api/    # artifactId=m8-api：对外接口、DTO、领域事件（跨模块唯一可依赖层）
└── impl/   # artifactId=m8-impl：应用服务、领域实现、Mapper；只依赖本模块 api + spring-context
```

## 依赖边界

- 允许：`m8-impl` → `m8-api`；其他模块 → `m8-api`；
- 禁止：任何模块 → `m8-impl`；`m8-impl` → 其他模块 `impl`；模块间循环依赖。

## 如何运行

本模块不单独启动，由 `api-bootstrap` 统一装配：

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
```

当前为骨架，启动后没有 M8 端点；运行需 PostgreSQL 16（Docker 不可用，EXT-08）。

## 如何测试

```bash
# 本模块及其依赖（单元 + 集成；集成测试用 Zonky 嵌入式 PG16）
./scripts/mvn.sh -B -pl apps/api/modules/m8/impl -am test
```

- 驾驶舱为只读投影：需有“数据延迟/空数据/权限过滤”用例，禁止写业务表；
- 覆盖率：整体行 ≥70%/分支 ≥55%，新增代码 ≥80%/70%；
- 大屏性能与刷新频率在 M8 验收时补基准（报表 <10s）。

## 关键契约

- 本域暂无 OpenAPI 契约：M8 启动时先定实时态势/风险/资源/KPI 的只读投影模型，再新增契约
  （顺序见 [`docs/api/README.md`](../../../../docs/api/README.md)）。
- 只读消费 M1/M2/M6/M7 的 `api` 或事件；不直连任何业务 schema；
- 前端图表在 M8 引入 ECharts 5.5.x，主题取 `packages/ui` 令牌，不在页面另建色板。

## 负责人（占位）

- Owner：`@anesop/m8-dashboard`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
- 状态：S0 空模块；随 M8 里程碑启动。

## 状态

S0-02 空模块占位（仅 `package-info.java`），不含业务代码、表结构与迁移脚本。
