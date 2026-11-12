# apps/api — ANESOP 业务应用（模块化单体）

**一句话职责**：以模块化单体承载 M0–M8 业务，`api-bootstrap` 是唯一进程入口，
模块间只经 `api` 子模块协作。

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

## 目录

```text
apps/api/
├── pom.xml                       # 聚合 POM（继承根 pom）
├── api-bootstrap/                # 唯一可执行装配点：启动类、Security/JWT/MyBatis 装配、集成测试
├── api-contracts/                # 契约生成的 Java DTO（target/generated-sources，生成物不入库）
└── modules/m0…m8/
    ├── api/                      # artifactId=mN-api：对外接口、DTO、领域事件
    └── impl/                     # artifactId=mN-impl：应用服务、领域实现、Mapper
```

## 如何运行

本工程不是每个模块都能单独启动，唯一入口是 `api-bootstrap`：

```bash
source scripts/env.sh

# 1) 打包（-am 会构建依赖的模块）
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package

# 2) 启动（需 PostgreSQL 16；dev profile 加载演示种子并允许进程内 JWT 密钥）
export ANESOP_DB_URL=jdbc:postgresql://127.0.0.1:5432/anesop
export ANESOP_DB_USER=anesop ANESOP_DB_PASSWORD=anesop
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev

# 3) 健康检查
curl -s http://localhost:8080/actuator/health     # {"status":"UP",...}
```

没有 PG 时只能编译/测试，启动会在 Flyway/数据源阶段失败；初始化见
[`../../docs/guides/demo.md`](../../docs/guides/demo.md)。生产 profile 需 `ANESOP_JWT_SECRET`
且只加载 `db/migration`（不含演示种子）。

## 如何测试

```bash
# 全量后端：编译、单测、JaCoCo 门禁、Checkstyle、SpotBugs
./scripts/mvn.sh -B verify

# 只跑 API 链路（含 m0 集成测试）
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am test

# 单个模块
./scripts/mvn.sh -B -pl apps/api/modules/m0/impl -am test

# 覆盖率报告
open apps/api/api-bootstrap/target/site/jacoco-aggregate/index.html
```

- 测试分层：单元（JUnit 5 + AssertJ）约 70%、集成（Spring Boot Test + Zonky 嵌入式 PG16）
  约 20%、契约（DTO 反序列化）与 ArchUnit 边界用例；
- 门禁：整体行 ≥70%/分支 ≥55%，核心域（m0 权限与审计、m2、网关）≥80%/70%；
- Docker 不可用（EXT-08），不要依赖 Testcontainers；集成测试用嵌入式 PG。

## 关键契约

| 模块 | 契约（`packages/contracts/openapi/`） | 状态 |
|---|---|---|
| m0 | `auth.yaml` | 已实现（S0-07），服务端与契约一致 |
| m1 | `scheduling.yaml` + `patient.yaml`（申请） | 契约基线，服务端待 M1 |
| m2 | `anesthesia.yaml` + `patient.yaml`（患者） | 契约基线，服务端待 M2 |
| m3–m8 | 暂无 | 各里程碑启动时先评审领域模型与契约 |

- 契约是接口唯一事实源；Java DTO 由 `scripts/gen-contracts.sh --java` 生成到
  `api-contracts/target/`，**禁止手改**；改字段先改契约。
- 生成/校验：`bash scripts/gen-contracts.sh --all`、`bash scripts/contract-check.sh`；
  规范见 [`../../docs/api/README.md`](../../docs/api/README.md)。
- 跨模块只依赖对方 `api` 子模块；禁止依赖 `impl`、禁止直连对方 schema。

## 负责人（占位）

| 范围 | CODEOWNERS（占位） |
|---|---|
| 架构 / api-bootstrap | `@anesop/architects` |
| m0 权限与审计 | `@anesop/m0-platform-security` + `@anesop/security-review`（双人） |
| m1…m8 | 见 [`.github/CODEOWNERS`](../../.github/CODEOWNERS) 对应团队 |
| 契约 | `@anesop/contracts` + `@anesop/architects` |

远端 GitHub 组织未建立，以上均为占位团队，审批暂不生效。

## 状态

S0-02 仅落地骨架：目录、POM、启动类、健康检查与模块边界用例；不含业务实现。
S0-07 已在 `api-bootstrap` 装配 m0-impl（Security/JWT/MyBatis/Flyway）与 m0 认证接口；
m1–m8 仍为 `package-info` 骨架。业务实现按 M1–M8 里程碑推进。
