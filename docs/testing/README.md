# 测试基座（S0-11）

- 状态：已落地（2026-11-12）
- 关联：S0-11 / `AGENTS.md`「编码与测试要求」/ `docs/acceptance/ACCEPTANCE.md` B 项
- 范围：后端 JaCoCo 覆盖率门禁、Testcontainers/嵌入式 PG 基座、前端 Vitest、Playwright E2E、测试数据工厂
- 非目标：本阶段不实现 E2E-01–05 的业务流程（依赖 M1/M2/M5 功能），只落地基座与冒烟

## 1. 测试分层与比例

| 层 | 技术 | 目标占比 | 当前落点 |
|---|---|---|---|
| 单元 | JUnit 5 + AssertJ / Vitest + Vue Test Utils | ≈70% | 后端 **apps/api 1147** 个用例（含契约 16，2026-11-15 实测）+ 模拟器 **36**、前端 **179** 个用例（35 文件，2026-11-15 实测） |
| 集成 | Spring Boot Test + 嵌入式 PostgreSQL 16（Zonky）/ Testcontainers | ≈20% | 迁移、MyBatis、认证越权、审计、网关幂等与断网补传 |
| 契约 | spectral + oasdiff（Java DTO 反序列化）+ 适配器↔模拟器字段映射 | — | `apps/api/api-contracts/src/test`；`apps/api/api-bootstrap/src/test/.../integration`（§13） |
| E2E | Playwright（Chromium） | 关键路径 | **42 条**（首页 3 + 登录/越权 6 + M1 11 + M2 14 + M4 8）；E2E-01–05 见 §6，临床专项矩阵见 §9，管理域专项见 §11 |

比例是目标而非硬门禁；骨架期没有业务域，集成/E2E 占比会随后续里程碑上升。

## 2. 后端覆盖率门禁（JaCoCo 0.8.12）

根 `pom.xml` 配置 `jacoco-maven-plugin`：`prepare-agent`（initialize）→ `report`（verify）→
`check`（verify）。阈值通过属性 `jacoco.line.min` / `jacoco.branch.min` 表达；核心域由
`<profiles>` 按“模块 basedir 下是否存在指定文件”激活并覆盖阈值，不需要改各模块 pom。

| 范围 | LINE 目标 | BRANCH 目标 | 2026-11-12 实测 | 结论 |
|---|---|---|---|---|
| 整体（逐模块默认） | 0.70 | 0.55 | api-bootstrap 85.11% / 60.42% | 达标 |
| 核心域 m0（权限/审计） | 0.80 | 0.70 | m0-impl 单模块 **96.34% / 87.52%**（2026-11-13，140 用例） | 达标；ratchet 已解除 |
| 核心域 m2 | 0.80 | 0.70 | 暂无业务类（仅 `package-info`，0 class） | 阈值已配置，待 M2 生效 |
| 核心域 device-gateway | 0.80 | 0.70 | **91.68% / 77.39%**（2026-11-13，61 用例） | 达标；ratchet 已解除 |
| api-contracts（生成 DTO） | — | — | 排除后 0 class | 不设门禁，生成物禁止手改 |
| m1–m8 api/impl（空壳） | 0.70 | 0.55 | 0 class / 无执行数据 | JaCoCo 自动跳过 |

报告位置：

- 单模块：`<module>/target/site/jacoco/index.html`
- m0 聚合（bootstrap 的依赖模块 m0-api + m0-impl，跨模块集成测试口径；bootstrap 自身不计入）：
  `apps/api/api-bootstrap/target/site/jacoco-aggregate/index.html`
- 网关：`services/device-gateway/target/site/jacoco/index.html`

**口径说明**：`check` 按模块统计本模块单测执行数据。M2-M0-03 起 m0-impl 已有完整的
AuthServiceImpl / UserAdminServiceImpl / AuditAspect / JdbcAuditWriter 单测，单模块
96.34%/87.52%，不再依赖 api-bootstrap 的聚合覆盖率。`report-aggregate` 默认
`includeCurrentProject=false`，聚合 m0-api + m0-impl 的类与执行数据（bootstrap 的测试执行数据
会计入，但 bootstrap 自身类不计入），继续作为跨模块诊断口径；逐模块 `check` 才是构建门禁。

### 排查覆盖率排除项

排除项必须有据，写在根 `pom.xml` 的 `jacoco.excludes`，**不排除 service 或领域逻辑**：

| 排除模式 | 依据 |
|---|---|
| `**/persistence/entity/**` | MyBatis-Plus 实体，只有字段与 getter/setter |
| `**/persistence/mapper/**` | Mapper 接口无逻辑，SQL 行为由集成测试验证 |
| `**/*Application.class` | Spring Boot 启动类，无业务分支 |
| `**/config/**` | Bean 装配与属性绑定，行为在 service/集成测试中验证 |
| `**/generated/**`、`**/contracts/**/model/**` | 构建期生成物，禁止手改（ADR-0003） |
| `**/package-info.class` | 仅包声明 |

## 3. Ratchet 记录

| 模块 | 当前值（LINE/BRANCH） | 临时阈值 | 目标 | 状态 | 解除记录 |
|---|---|---|---|---|---|
| m0-impl | 96.34% / 87.52%（842/874、456/521） | 无 | 0.80 / 0.70 | **已解除** | M2-M0-03 补 AuthServiceImpl/UserAdminServiceImpl/AuditAspect/JdbcAuditWriter 等异常与边界单测（2026-11-13） |
| device-gateway | 91.68% / 77.39%（793/865、308/398） | 无 | 0.80 / 0.70 | **已解除** | M2-M0-03 补 LocalBuffer/TcpDeviceServer/SimulatedDevice/Metric/VitalSample 分支用例（2026-11-13） |
| m2-impl | 无类 | 无 | 0.80 / 0.70 | 待 M2 生效 | M2 引入业务类后自然生效 |

- 根 `pom.xml` 已删除 `TEST: expires=2026-12-18 ratchet` 与 `TODO(TEMP): expires=2026-12-18`
  标记，`grep -n "TEST: expires" pom.xml` 为空；`scripts/check-rules.sh` 无到期告警。
- 确实不可达的分支（如 JVM 级 `JsonProcessingException`、`new Socket` 连接异常）不降低阈值，
  由 80/70 的余量吸收，不加入排除项。
- **禁止**为达标而把 service/领域类加入排除项；确需调整阈值必须更新本表与 ADR-0008。

## 4. 运行命令

```bash
source scripts/env.sh

# 后端：根 reactor（编译、单测、JaCoCo、Checkstyle、SpotBugs）
./scripts/mvn.sh -B verify

# 后端单模块（含依赖）
./scripts/mvn.sh -B -pl apps/api/api-bootstrap -am verify

# Docker 可用时反向激活 @Tag("docker") 用例（EXT-08 期间会 skip）
./scripts/mvn.sh -B -Pdocker -pl apps/api/api-bootstrap test

# 前端单测 + v8 覆盖率（阈值在 apps/web/vitest.config.ts）
pnpm test:unit

# 前端类型检查
pnpm -C apps/web exec vue-tsc --noEmit

# E2E（首次需安装 Chromium；浏览器落在仓库内 .playwright）
pnpm exec playwright install chromium
pnpm e2e

# 本地全量门禁（后端 + 前端 + 契约 + 规则）
./scripts/check.sh
```

## 5. 前端单测（Vitest）

- 配置：`apps/web/vitest.config.ts`（`environment: jsdom`、`coverage.provider: v8`）
- 阈值：行 ≥70%、分支 ≥55%；排除 `src/api/generated/**`、`src/env.d.ts`、`src/main.ts`、
  `src/**/__tests__/**`
- 用例：`apps/web/src/__tests__/**` 与各模块 `__tests__/**`（含 M4 药品/五专/人员/耗材/报表/质控页）
- 实测：**179/179 通过**（35 个测试文件，2026-11-15），语句 76.83%、分支 73.81%、函数 79.12%、行 79.24%（仍高于 70/55 门禁）
- 断言可失败：标题、文案、路由 path/name 均为精确值；不使用恒真断言

## 6. E2E 清单与落地计划

| ID | 场景 | 依赖 | 落地里程碑 | 现状 | 计划 |
|---|---|---|---|---|---|
| E2E-01 | 排程冲突 | M1 排程 API、登录、合成台次 | M1 | **已实现**（`m1-schedule-conflict.spec.ts`，API 拦截） | 新建台次 → 冲突 409 提示 → 改术间成功 |
| E2E-02 | 采集断网补传 | S0-09 网关、平台 ingest、M2 记录页 | M2 | **已实现**（`m2-device-ingest.spec.ts`，UI+契约拦截；真实 HTTP+PG 见 §9） | 同一 batchKey 重放不重复 → 新 batchKey 补齐可见 → 无权限 403 |
| E2E-03 | 记录锁单修订 | M2 术中记录、锁单/修订 | M2 | **已实现**（`m2-record-lock.spec.ts`，API 拦截） | 锁单 → 编辑被拒 → 修订版本递增、原值不变 |
| E2E-04 | 权限越权 | M0 登录/RBAC、受保护页面 | M1 | **已实现（UI 级）**（`auth.spec.ts`） | 低权限路由 → 403；真实 API 403 由集成测试覆盖 |
| E2E-05 | 麻精药品双人核对 | M5 麻精五专、双人核对 | M5 | **待实现**（依赖 M5；记录单内麻精核对已由 `AnesthesiaRecordApiIntegrationTest` 与 `m2-record.spec.ts` 覆盖数据入口） | 双人登录 → 核对/签字 → 未双人不可发药、留痕完整 |

当前已落地：

- `apps/web/e2e/home.spec.ts` 3 条冒烟（首页标题/文案、`/`→`/home`、未知路径兜底）。
- `apps/web/e2e/auth.spec.ts` 6 条（M2-M0-03）：
  1. 空表单触发前端校验且不发出 `/v1/auth/login` 请求；
  2. 登录成功 → `/home`，展示角色标签与权限概览；
  3. 登录失败 → 停留 `/login`，展示契约 `code` / `message` / `traceId`；
  4. **E2E-04 UI 级**：低权限用户访问 `/m0/sessions` → `/403`，且侧边菜单不显示「会话管理」；
  5. `/me` 首次 401 → 单次 `/v1/auth/refresh` → 用新 Bearer 重放并成功（route 计数 + Authorization 断言）；
  6. 登出 → 回 `/login`，`sessionStorage` 中令牌与用户快照清空。
- `apps/web/e2e/m1-applications.spec.ts` 4 条（S-9，M2-M1-01）：
  1. 列表加载：展示 HIS 申请号、脱敏患者与状态，写权限用户可见导入/取消按钮；
  2. 状态筛选：选择“已受理”后查询串携带 `status=ACCEPTED` 并渲染筛选结果；
  3. 详情：点击详情进入 `/m1/applications/:id`，展示申请与 create→SUBMITTED 状态历史；
  4. 只读权限：`m1:application:read` 用户不显示导入/取消按钮（写权限按钮隐藏）。
- `apps/web/e2e/m1-schedule-conflict.spec.ts` **E2E-01** 2 条（M2-M2-01）：冲突 409 → 提示 → 改术间成功；只读权限不显示操作按钮。
- `apps/web/e2e/m2-record-lock.spec.ts` **E2E-03** 1 条（M3-M2-03）：锁单前置 → 锁单 → 编辑被拒 → 带原因修订 → 新版本可见且原版本不变。
- `apps/web/e2e/m2-device-ingest.spec.ts` **E2E-02** 2 条（M3-QA-01）：
  1. 登记设备 → 无网关令牌 401 → 断网缓冲 2 帧上传 → 同 batchKey 重放 `duplicateBatch=true` 且
     `acceptedRows=0` → 记录页体征 2 条（非 4 条）→ 新 batchKey 补 1 帧 → 3 条（非 6 条）；
  2. 无 `m2:device:read` 用户设备查询 403、记录页被路由守卫拦到 `/403`，不渲染患者上下文。
- `apps/web/e2e/m4-*.spec.ts` 8 条（M4 五域 + QA）：
  1. `m4-drug.spec.ts` 1 条：药品新建 → 专册双签登记 → 日结平账；
  2. `m4-staff.spec.ts` 1 条：授予授权 → 授权校验通过 → 排班冲突提示；
  3. `m4-material.spec.ts` 1 条：UDI 登记入库 → 绑定记录使用 → 追溯链；
  4. `m4-report.spec.ts` 2 条：筛选/查询/导出工作量 CSV；无读权限守卫 403；
  5. `m4-qc.spec.ts` 1 条：不良事件 RCA 闭环状态逐级前进；
  6. `m4-narcotic-reconcile.spec.ts` 1 条（M4-QA-01）：日结不平 409 → 差异明细 → 修正期末后平账；
  7. `m4-qc-export.spec.ts` 1 条（M4-QA-01）：质控指标按当前区间导出 CSV（`type=indicator`、`Accept: text/csv`、脱敏提示）。
- `pnpm e2e` 实测 **42/42 通过**（2026-11-15，Chromium，无 flaky；含 M4 8 条）。

> **E2E-02/E2E-04 口径说明**：当前为 **UI 级（路由守卫 + 菜单权限过滤）+ 平台 API 契约拦截**，
> Playwright 不启动 Spring Boot/PG，因此**不得宣称 E2E 已覆盖真实 ingest 幂等或真实越权 API**。
> 真实 HTTP + PostgreSQL 的补传幂等、质量标记、409/55000、403 与审计由 §9 的集成测试承接：
> `DeviceAcquisitionApiIntegrationTest`、`ClinicalRecordAcceptanceTest`。真实全栈 E2E
> （启动 API + 嵌入式 PG）仍由后续 `scripts/e2e-full.sh` 承接，脚本落地前本表保持此声明。
> 记录页体征条数依赖 M3-M8-01 的 `vitalSigns` 映射（T-4 登记，M4 权限细化时收口）；当前 E2E
> 只验证“平台数据不重复 → 记录页条数正确”的 UI 契约，不宣称真实映射已上线。
E2E-01–05 每条落地时必须在 PR 附带失败截图/trace，并接入 CI 的 e2e job。

## 7. Testcontainers 基座（Docker 不可用 EXT-08）

- `com.anesop.testing.TestcontainersPostgresSupport`：PostgreSQL 16 模板，标
  `@Tag("docker")` 与 `@Testcontainers(disabledWithoutDocker = true)`。
- 根 `pom.xml` 的 surefire 默认 `<excludedGroups>docker</excludedGroups>`；`-Pdocker`
  反向配置 `groups=docker`。
- `com.anesop.testing.EmbeddedPostgresSupport`：无 Docker 时的 Zonky 嵌入式 PG 16 统一入口
  （复用既有单实例，避免重复拉起）。
- `com.anesop.testing.TestDataFactory`：SYNTHETIC 用户/患者/病例工厂，固定前缀 `SYNTH-`，
  ID 由固定种子生成；禁止真实数据。
- `TestcontainersPostgresSmokeTest` 默认不执行；Docker 不可用时 `-Pdocker` 下为 skipped，
  不伪造通过。
- **镜像 digest 待补**：当前 `postgres:16-alpine`，EXT-08 解除、镜像仓库可达后改为
  `postgres:16-alpine@sha256:<digest>` 并记录验证日期。
- **浏览器安装记录（2026-11-12）**：`pnpm exec playwright install chromium` 在 Google
  CDN 上速度低于 0.3 MB/s（两次 10% 后停滞），改用 npmmirror 的 Chrome for Testing
  镜像下载 `chrome-linux64.zip` 与 `chrome-headless-shell-linux64.zip`，解压到
  `.playwright/chromium-1254` 与 `.playwright/chromium_headless_shell-1254` 并写入
  `INSTALLATION_COMPLETE`；`pnpm e2e` 3/3 通过。浏览器目录已被 `.gitignore` 排除，
  CI 仍应使用 `pnpm exec playwright install --with-deps chromium` 并配置缓存。

## 8. 不稳定用例政策

- 发现不稳定用例：24 小时内修复；优先修根因（时序、共享状态、外部依赖），不得用
  `Thread.sleep`/放宽断言掩盖。
- 24 小时未修复：隔离并建 Issue，PR 说明原因与恢复计划，经架构师批准后方可
  `@Disabled("issue #<n>")`；禁止直接删除测试。
- 禁止反模式：恒真断言、mock 被测对象、只测快乐路径、测试复制实现、改测试让实现通过。
- Docker/浏览器/网络类环境失败标 `EXT-08`，不算测试通过，也不阻断主线。

### 8.1 约定：新测试禁止依赖真实时间窗

- **禁止**用固定等待去赌"退避窗口/TTL/超时是否已到期"，例如失败后 `Thread.sleep(600)`
  再断言重试——并发负载下调用方的实际耗时不可控，必然偶发。
- 可确定化的逻辑一律注入时间源：生产代码用 `LongSupplier`（如
  `VitalUploader(..., LongSupplier nowEpochMillis)`）或 `java.time.Clock`，公开构造器默认
  `System::currentTimeMillis`，包可见构造器供同包测试传入可控时钟；测试显式推进时钟，
  断言窗口边界前不动作、到边界才动作。
- 无法注入时钟的外部异步边界（真实 socket、`@Scheduled` 线程、嵌入式 PG）只能用**显式条件
  等待**（`GatewayTestSupport.awaitTrue`）：断言依赖条件本身，超时按实测耗时留数倍余量并在
  注释说明；`GatewayTestSupport.sleep` 已收为 `private`，只作轮询间隔，不得当同步手段。
- 禁止为了"变绿"放宽或删除语义断言；修复后须保留原有用例的全部不变量（缓冲保留、指标、
  连接状态、下次重试时间）。

### 8.2 修复记录：`VitalUploaderRetryTest` 退避窗口（2026-11-13）

- **现象**：`./scripts/check.sh` 并发负载下
  `upload/VitalUploaderRetryTest#keepsRecordsAndBacksOffWhenDatabaseUnavailable` 在
  "退避窗口内不得重试"处失败（expected 1 but 2）。
- **根因**：用例用真实墙钟 + 500ms 基础退避，`drain()` 之后依赖"立即调用 `scheduledUpload()`
  必然仍在 500ms 窗口内"，再 `Thread.sleep(600)` 等窗口过期。CPU 争用下 `drain()` 返回到下一次
  `scheduledUpload()` 的真实耗时可能已超过窗口，于是"窗口内"那次调用实际已越界并重试，
  连续失败数变成 2。实现本身正确，是测试假设了真实时序。
- **修复**：`VitalUploader` 的时间读取（退避判定、`lastSuccessEpochMillis`、
  `nextAttemptEpochMillis`）全部改走可注入的 `LongSupplier`，公开构造器仍传
  `System::currentTimeMillis`，生产行为不变；用例改为可控 `AtomicLong` 时钟——失败后断言
  `nextAttempt = T0 + 500`，窗口边界前 1ms 调用不重试，推进到边界重试（failures=2、failed
  指标=2、数据仍在缓冲），再验证下一窗口前不重试。零休眠、零竞态，且断言比原来更强
  （精确窗口值 + 二次退避 1000ms）。
- **顺带核查**：`LocalBufferTest` / `LocalBufferExtraTest` 不含真实时间等待（段 TTL 只用固定
  时间戳与 600s 间隔，容量/轮转判定与墙钟无关），未改；`DeviceGatewayEndToEndTest`、
  `TcpDeviceServerTest`、`SimulatedDeviceTest` 的真实 socket/调度边界保留显式条件等待，
  超时统一加大并注释（E2E 180s/120s/60s，回环 socket 10s，模拟设备 join 10s）。

## 9. 临床记录专项（发布准入矩阵，M3-QA-01）

依据 `AGENTS.md`「临床记录专项（时间轴、断网采集、并发修改、签名锁定）为发布准入项，任一不通过
不得发布」。专项不只跑 E2E：**真实 HTTP + 嵌入式 PostgreSQL 16** 的聚合用例为
`apps/api/api-bootstrap/src/test/java/com/anesop/api/ClinicalRecordAcceptanceTest.java`，
它把四类准入不变量收敛为两条跨域流程，失败即阻断发布。

| 准入项 | 关键断言（可失败） | 真实 API+PG 用例 | E2E / 说明 |
|---|---|---|---|
| 时间轴一致性 | `seq` 连续 1..N、`occurredAt` 非降序、事件/记录单 UPDATE/DELETE 55000 | `ClinicalRecordAcceptanceTest#timelineIngestReplayAndConcurrentMutationsStayConsistent`；边界枚举见 `AnesthesiaRecordApiIntegrationTest#recordTimelineDrugsFluidsAndAuditFollowClinicalRules` | `m2-record.spec.ts`（UI 追加事件/排序） |
| 断网采集补传幂等 | 同 `batchKey` 重放 `acceptedRows=0`/`duplicateBatch=true`、行数不变、`quality_flags` 保留；新 batchKey 补齐 | `ClinicalRecordAcceptanceTest#timelineIngestReplayAndConcurrentMutationsStayConsistent`；行级/批次双层判重见 `DeviceAcquisitionApiIntegrationTest#ingestIsIdempotentAcrossBatchAndRowKeys` | `m2-device-ingest.spec.ts` **E2E-02**（UI 可见性 + 403） |
| 并发修改 | 并发事件 `seq` 不重不丢；同 version 并发 PUT 恰一个 200、一个 409；并发修订 `revision_no` 连续 | `ClinicalRecordAcceptanceTest`（并发事件/并发 PUT/并发修订）；`AnesthesiaRecordApiIntegrationTest#concurrentEventAppendsSerializeSequenceUnderRowLock`；`RecordLockRevisionApiIntegrationTest#concurrentRevisionsSerializeRevisionNumbersUnderRowLock` | — |
| 签名锁定 | 锁后事件/用药/出入量/PUT 409，数据库直写 55000；锁单摘要 64 位 hex 且稳定；修订链 `previousSignatureHash` 逐版衔接 | `ClinicalRecordAcceptanceTest#lockFreezesWritesAndConcurrentRevisionsKeepSignatureChain`；快照/差异/伪造链见 `RecordLockRevisionApiIntegrationTest` | `m2-record-lock.spec.ts` **E2E-03**（UI 锁单/修订） |

运行与阻断：

```bash
source scripts/env.sh
# 专项聚合（真实嵌入式 PG，约 33s）
./scripts/mvn.sh -B -pl apps/api/api-bootstrap -am \
  -Dtest=ClinicalRecordAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false test
# 全量后端门禁（含专项与既有边界用例）
./scripts/mvn.sh -B -f apps/api/pom.xml clean verify
# E2E（含 E2E-01/02/03/04）
pnpm e2e
```

- 四类准入项任一失败（含 flaky）→ **阻断发布**，按 P0/P1 缺陷分级处理；不得 `@Disabled` 或放宽断言。
- `scripts/ci-local.sh --job e2e-smoke` 已包含 `pnpm e2e`（E2E-02 随之纳入本地门禁），无需改脚本；
  `--fast` 按设计跳过 e2e-smoke，发布前必须单独跑 `pnpm e2e`。
- E2E-02 为 UI+契约层（见 §6 口径说明）；真实 ingest 幂等以集成测试为准，二者同时通过才算准入。
- E2E-05（麻精双人核对）依赖 M5，尚未实现；记录单内麻精核对入口已由集成测试与 `m2-record.spec.ts`
  覆盖，但**不能替代** M5 双人核对准入。

## 10. CI 对应关系（S0-04 落地）

| 本地步骤 | CI job（实际名） | 产物/缓存 |
|---|---|---|
| `./scripts/mvn.sh -B verify` | `ci/unit-test` | JaCoCo HTML/XML、Surefire 报告；缓存 `.m2repo` |
| `pnpm test:unit` | `ci/unit-test` | `apps/web/coverage/`；缓存 pnpm store |
| `pnpm e2e` | `ci/e2e-smoke` | Playwright report/trace；缓存 `.playwright` |
| `./scripts/contract-check.sh` | `ci/contract` | spectral/oasdiff 输出 |
| `pnpm lint` + Prettier | `ci/lint` | — |
| `vue-tsc --noEmit` + `mvn compile` | `ci/typecheck` | — |
| `mvn -DskipTests package` + `pnpm build` | `ci/build` | jar/dist artifact |
| Semgrep | `ci/sast` | SARIF |
| 依赖漏洞/许可证 | `ci/deps-license` | audit 输出 |
| gitleaks | `ci/secret-scan` | SARIF |
| Trivy（镜像） | `ci/image-scan` | SARIF（EXT-08 受限豁免） |

CI 建议把后端、前端、E2E 拆 job 并行（本地 `check.sh` 仍串行全量），总时长目标 ≤10 分钟；
覆盖率报告作为 artifact 上传，JaCoCo 门禁失败即阻断合并。

## 11. 管理域专项（M4-QA-01，发布准入）

依据 `AGENTS.md`「权限与数据范围」「导出必须脱敏」与 M4 任务卡（E-M13…E-M17），管理域专项
不拆成散点，而由聚合用例 `apps/api/api-bootstrap/src/test/java/com/anesop/api/M4AcceptanceTest.java`
在**真实嵌入式 PostgreSQL 16 + 真实 Flyway V20–V25** 上收敛；任一断言失败即阻断 M4 验收。
全部数据为 `SYNTH-` 前缀的 SYNTHETIC 数据，固定窗口（报表 2032-06、质控 2026-10、
UDI 2026-08；五专用当前时点与业务日 T−3）与其他用例不交叉，用例结束清理。

| 准入项 | 关键断言（可失败） | 真实 API+PG 用例 | E2E / 说明 |
|---|---|---|---|
| 权限矩阵（角色×端点） | SYS_ADMIN / SYS_PHARMACIST / SYS_AUTH_MANAGER / SYS_OR_MANAGER / SYS_QC_MANAGER / SYS_ANESTHESIOLOGIST / 匿名 × 药品写、五专科室/专册、人员档案写/授权、耗材 UDI、报表读/导出、质控读/导出/复核；匿名一律 401，缺权限一律 403，正例 200/201 | `M4AcceptanceTest#roleEndpointMatrixCoversPositiveAndNegativeCases` | — |
| 授权治理 | 双人审批：缺第二审批人 400、与第一审批人相同 400、两名不同审批人 200；自授禁止（管理员/授权管理员均 403）；伪造审批人 400；响应记录双审批人与时点 | `M4AcceptanceTest#authorizationRequiresDualApprovalAndForbidsSelfGrant`；`M4StaffApiIntegrationTest#authorizationApprovalGovernanceRejectsSelfFakeAndMissingSecondApprover` | `m4-staff.spec.ts`（授权 → 校验 → 排班） |
| 数据范围 | DEPT 越界科室 403（报表/人员），本科室只返回本科室；ROOM 在无术间语义的域（人员/耗材）fail-closed 403，在报表按术间收窄；SELF 仅本人档案可读、他人 404、写 403；SELF 报表仅本人记录单台次 | `M4AcceptanceTest#dataScopeEnforcesDeptBoundaryRoomFailClosedAndSelfReadOnly`；`M4ReportApiIntegrationTest#dataScopeDeptAndSelfAreRestricted` | — |
| 导出脱敏/防注入 | 报表 `workload`/`multi` + 质控 `indicator`/`adverse`/`review` 五类 CSV：UTF-8 BOM、固定列头、无患者姓名/院内号/住院号/身份证/手机号、质控无自由文本原文；报表覆盖 `= + - @` 四种公式前缀，质控 `adverse`/`review` 覆盖 `=`/`@`（同一 `csvCell` 分支）；审计 `REPORT_EXPORT`/`QC_EXPORT` 的 `dataRows` 与真实行数一致 | `M4AcceptanceTest#csvExportsAreBomMaskedInjectionSafePhiFreeAndAudited`；`M4ReportApiIntegrationTest#csvExportHasBomHeaderFormulaGuardNoPhiAndAudit`；`M4QcApiIntegrationTest#exportIsBomMaskedInjectionSafeAndFreeOfPhiAndFreeText` | `m4-report.spec.ts`（导出请求）、`m4-qc-export.spec.ts`（质控导出） |
| 五专对账 | 基数 10 + 交接入库 4 − 专册使用 3 − 退回 0 = 期末 11；不平 409 `details` 给期望/实盘；同日同班次重复 409；双人签字同一人 400；成功/失败审计分离（失败审计只记 rejection，不记柜号/处方号） | `M4AcceptanceTest#narcoticLedgerReconcilesAndRejectsUnbalancedClose`；`M4DrugApiIntegrationTest#narcoticFiveSpecialtiesFlowAndRules` | `m4-narcotic-reconcile.spec.ts`（409 差异 → 修正平账） |
| UDI 追溯链 | 登记入库 `IN_STOCK` → 绑定记录/台次使用 `USED` → 按 UDI 反查实例/耗材/2 条流水/记录/台次；重复使用 409；过期登记 422；退回批次不一致 422；UDI-DI 与耗材不一致 422；`UDI_USE` 审计无患者标识 | `M4AcceptanceTest#udiTraceChainCoversInboundUseLookupAndGuards`；`M4MaterialApiIntegrationTest#udiLifecycleTraceDuplicateUseAndRecordBinding` | `m4-material.spec.ts`（UI 追溯链） |
| 报表/质控手算对照 | 报表：台次 6、麻醉时长 215、加权 6.5（SYNTH-ART-001 权重 1.5）、unrecorded 4，麻醉科桶 2/125/2.5、手术室桶 4/90/4.0；质控：记录单 50.00%、访视 50.00%、三方核查 25.00%、麻精双签 50.00%、不良事件 250.00‰、抽查合格 50.00%、高值 UDI 66.67% | `M4AcceptanceTest#workloadMatchesHandCalculatedTotalsAndWeights`、`#qcIndicatorRatesMatchHandCalculation`；`M4ReportApiIntegrationTest#workloadMatchesHandCalculatedTotalsAndBuckets`；`M4QcApiIntegrationTest#indicatorResultsMatchHandCalculatedValues` | — |

**导出覆盖口径（重要）**：截至 M4-QA-01，契约只定义了两类导出端点——`GET /v1/reports/export`
（`report.yaml`，workload/multi 两种报表）与 `GET /v1/qc/export`（`qc.yaml`，indicator/adverse/review
三种类型）。`drug.yaml`、`material.yaml` **没有** CSV 导出端点，按「不臆造接口与字段」（AGENTS.md
H-04）本期不新增生产代码与契约，因此导出脱敏证据覆盖上述 5 个 CSV 变体；药品/耗材如后续需要
导出，须先补契约与权限点（建议 `m4:drug:export` / `m4:material:export`）再补测，已登记为
M4 遗留项（不阻断本期验收）。

运行与阻断：

```bash
source scripts/env.sh
# 管理域专项聚合（真实嵌入式 PG，约 55s / 8 用例）
./scripts/mvn.sh -B -pl apps/api/api-bootstrap -am \
  -Dtest=M4AcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false test
# 全量后端门禁（含 M4 五域既有集成用例与专项）
./scripts/mvn.sh -B -f apps/api/pom.xml clean verify
# E2E（含新增五专日结对账 / 质控导出 2 条）
pnpm e2e
```

- `scripts/ci-local.sh` 无需改动：`ci/unit-test` 跑全量 `mvn verify`（自动包含 `M4AcceptanceTest`），
  `ci/e2e-smoke` 跑 `pnpm e2e`（自动包含 `m4-narcotic-reconcile.spec.ts` / `m4-qc-export.spec.ts`）。
- 任一准入项失败（含 flaky）→ 阻断 M4 验收，按 P0/P1 缺陷分级处理；不得 `@Disabled` 或放宽断言。
- 遗留项：药品/耗材 CSV 导出（契约未定义）；M4 模块 README 中已登记的【待确认】口径
  （R1–R5 映射、统计权重、质控指标清单）不因本专项改变，仍按 ADR-0018…0022 待院方确认。

## 13. 集成联调与契约测试（M5-M19-01，发布准入）

> 依据：`docs/integration/README.md`。EXT-01/02/03/04 真实系统不可用，用 `services/simulators`
> 的 JDK `HttpServer` 模拟器锁定**适配器字段映射与异常语义**（SYNTHETIC，只绑定 127.0.0.1）；
> 平台生产适配器仍为进程内模拟（无外部 I/O）。契约测试通过 **不等于**真实接口已验证（见 §F）。

| 准入项 | 断言（可失败） | 用例 |
|---|---|---|
| 模拟器启动/停止/配置 | `start` JSON（UP/端口/请求数）、`status`、`stop`、端口 0、非法配置 fail-closed、端口冲突快速失败 | `services/simulators` **36** 用例（含 `SimulatorsMain --self-test`、延迟/错误码/前 N 次失败/非法 JSON 注入） |
| HIS 字段映射（EXT-01） | 外部字段集合完全一致；19 字段映射；`F/M/U` 与 `择期/限期/急诊` 值域；患者主索引与申请一致；`POST /v1/scheduling/applications` 201、重复 409；创建/变更回调 | `HisSimulatorContractTest`（真实 HttpServer + 嵌入式 PG + 真实 API） |
| 药房字段映射（EXT-02） | 目录字段映射 → `POST /v1/drugs` 201、重复 409；库存/发药（409 不足、404）；适配器状态映射满足 `drug.yaml` required 且与 API 语义一致 | `PharmacySimulatorContractTest` |
| SPD 字段映射（EXT-03） | 目录映射 → `POST /v1/materials` 201、重复 409；同 `idempotencyKey` 同回执；回执/状态映射满足 `material.yaml` required 且与 API 一致 | `SpdSimulatorContractTest` |
| 错误/超时/重试/降级 | 5xx 重试 3 次（attempts=3）；4xx 不重试（attempts=1）；超时 attempts=2 后降级；降级字段 `available=false/degraded=true/pendingSyncCount=1/lastSyncedAt=null`、`success=false/counts=0`；降级不阻塞平台主链路 | 上述 3 类 + `WireMapperContractTest`（值域与缺失字段 fail-closed） |
| 设备（EXT-04） | TCP 协议 v1 解析/标准化/断网补传幂等 | 复用 `services/device-gateway` 既有测试；`scripts/simulators.sh device` 封装 `SimulatedDevice` |
| 端到端联调冒烟 | 模拟器 + 独立 PG16 + `test` profile API：health UP → 登录 → EXT-02/EXT-03 状态 → 模拟器目录创建药品/耗材 → HIS 申请导入 → 申请列表 | `bash scripts/integration-smoke.sh`（退出码 0 + `[integration-smoke] OK`） |

覆盖率（逐模块 JaCoCo 门禁 70%/55%）：`services/simulators` 实测 **97.28% 行 / 83.46% 分支**
（36 用例，2026-11-15）。契约测试为 test 作用域代码，不参与模块覆盖率统计。

运行：

```bash
source scripts/env.sh
# 模拟器单元 + 覆盖率 + Checkstyle/SpotBugs
./scripts/mvn.sh -B -f services/simulators/pom.xml verify
# 契约测试（真实模拟器 + 嵌入式 PG16 + 真实 API）
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am \
  -Dtest='HisSimulatorContractTest,PharmacySimulatorContractTest,SpdSimulatorContractTest,WireMapperContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 端到端冒烟（首次构建 2–5 分钟，之后复用产物）
bash scripts/integration-smoke.sh
```

阻断规则：任一契约/冒烟用例失败（含 flaky）→ 阻断 M5 联调验收；不得 `@Disabled` 或放宽断言。
`api-bootstrap` 以 test 作用域依赖 `anesop-simulators`：根 reactor 自动先构建；只构建 `apps/api`
时先 `./scripts/mvn.sh -B -f services/simulators/pom.xml install`。

## 14. 安全专项（M5-M19-02，发布准入）

> 依据：`docs/security/README.md`。覆盖 HTTP 安全头/CSP、登录/刷新限流、权限矩阵补测、
> 审计覆盖与依赖扫描；全部 SYNTHETIC 数据，不需要真实患者数据。

| 准入项 | 断言（可失败） | 用例/脚本 |
|---|---|---|
| Nginx 安全头/CSP | 443 下发 HSTS/nosniff/XFO DENY/Referrer/Permissions；CSP 含 `script-src 'self'`（无 inline/eval）、`connect-src 'self'`、`frame-ancestors 'none'`；80 明文响应无 HSTS；`/api/` 日志无 query | `NginxSecurityHeadersConfigTest`（2）、`NginxPhiLogConfigTest`、`scripts/security-check.sh` |
| API 安全头 | 健康检查与 401 响应带 XCTO/XFO/Referrer/Permissions/CSP；HSTS 仅 `secure(true)` 请求下发 | `SecurityHeadersIntegrationTest`（3） |
| 登录限流 | 同一 IP+用户名容量 2：第 3 次 429 `RATE_LIMITED` + `Retry-After`；不同用户名不共享桶；正常登录 200 且令牌可用 | `RateLimitIntegrationTest`（4） |
| 限流窗口恢复 | 容量 1、PT5S 窗口：401 → 429 → 等待后恢复 401（不再 429） | `RateLimitRecoveryTest`（1） |
| 审计与敏感字段 | 失败/成功登录审计只含用户名与固定文案，不含口令/access token；429 短路不写登录审计 | 上述限流用例 |
| 权限矩阵补测 | SYS_QC_MANAGER 驾驶舱 4 端点 200；SYS_SURGEON 403 + `ACCESS_DENIED` 审计；匿名 401；M0 用户管理仅 SYS_ADMIN，麻醉医师读/写 403 且零写入 | `SecurityAuthorizationRegressionTest`（2） |
| 依赖/镜像扫描 | npm 生产依赖 0 HIGH/CRITICAL；许可证/白名单（1 条历史 WARN spring-tx）；镜像清单 9 项（EXT-08） | `scripts/security-scan.sh`，证据 `.cache/security/` |
| 静态安全门禁 | 21 项：无私钥/云 token、密钥占位、默认/prod fail-closed、CSP/安全头、日志无 PHI、前端无 inline script | `scripts/security-check.sh`（接入 `check.sh` 第 8/8 步，约 1–2s） |

运行：

```bash
source scripts/env.sh
# 安全专项（12 例，约 2 分钟；含独立限流上下文与 PT5S 窗口）
./scripts/mvn.sh -B -pl apps/api/api-bootstrap -am \
  -Dtest=RateLimitIntegrationTest,RateLimitRecoveryTest,SecurityHeadersIntegrationTest,NginxSecurityHeadersConfigTest,SecurityAuthorizationRegressionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
bash scripts/security-check.sh     # 离线 21 项，约 1s
bash scripts/security-scan.sh      # 联网依赖扫描 + 镜像清单，约 30–60s
bash scripts/compose-check.sh      # nginx/compose 静态校验
```

实测（2026-11-15）：安全专项 **12/12** 通过；`apps/api` clean verify BUILD SUCCESS
（1157 用例，14:07）；`security-check.sh` 21/21；`security-scan.sh` OK（0 HIGH/CRITICAL，
1 条白名单 WARN，镜像清单 9 项）；`compose-check.sh` 34 PASS；`pnpm test:unit` 179/179、
`pnpm build`/`pnpm lint` 通过、`pnpm e2e` 45/45。

阻断规则：安全头缺失/CSP 退回 inline script、限流无 429/Retry-After、越权用例失败、
扫描出现 HIGH/CRITICAL 未登记例外、日志/审计出现口令或令牌 → 阻断发布；不得放宽断言
或跳过用例。已知限制（单实例限流、Maven jar CVE 依赖 CI 镜像扫描、CSP style-src
`unsafe-inline`、`spring-tx` 白名单缺口）见 `docs/security/README.md` §8。

## 12. 变更记录

- 2026-11-15（M5-M19-02）：新增 §14 安全专项（12 例：安全头 3、nginx 配置 2、限流 4、
  窗口恢复 1、权限矩阵补测 2）；新增 `scripts/security-check.sh`（21 项，接入 `check.sh`
  第 8/8 步）与 `scripts/security-scan.sh`（npm 0 HIGH/CRITICAL、镜像清单 9 项 EXT-08）；
  nginx 启用 CSP、XFO 改 DENY；API 层同语义安全头；登录限流改 IP+用户名并返回 Retry-After。
- 2026-11-15（M5-M19-01）：新增 §13 集成联调与契约测试矩阵；新增 `services/simulators`
  （36 用例，97.28%/83.46%）与 4 类契约测试；新增 `scripts/simulators.sh`、
  `scripts/integration-smoke.sh`；E2E 不变（42 条）。
- 2026-11-15（M4-QA-01）：新增 `M4AcceptanceTest`（权限矩阵/授权治理/数据范围/5 类 CSV/
  五专对账/UDI 追溯/报表与质控手算，8 条）；新增 E2E `m4-narcotic-reconcile.spec.ts`
  （日结 409 差异 → 修正平账）与 `m4-qc-export.spec.ts`（指标导出请求/脱敏提示）；新增 §11 管理域
  专项发布准入矩阵；E2E 总数 42。药品/耗材无 CSV 导出端点（契约未定义）登记为遗留项。
  实测：`mvn -f apps/api/pom.xml clean verify` BUILD SUCCESS（1100 用例，13:03，api-bootstrap 248）；
  前端 `pnpm test:unit` 167/167、`pnpm build` 通过、`pnpm lint` 0 error、`pnpm e2e` 42/42（1.2m）。
- 2026-11-14（M3-QA-01）：新增 `ClinicalRecordAcceptanceTest`（时间轴/补传幂等/并发 PUT/签名锁定
  聚合，2 条）；新增 **E2E-02** `m2-device-ingest.spec.ts`（2 条）；新增 §9 临床记录专项发布准入矩阵；
  E2E 总数 34；`pnpm e2e` 34/34、专项 2/2 通过。
- 2026-11-13（gateway flaky 修复）：`VitalUploader` 支持注入时钟；重写
  `VitalUploaderRetryTest` 去除真实时间窗依赖；E2E/回环 socket 用例加大条件等待余量；
  新增 §8.1 禁止依赖真实时间窗约定与 §8.2 根因记录。
- 2026-11-13（M2-M1-01）：新增 M1 E2E 4 条（`m1-applications.spec.ts`，API 拦截 + SYNTHETIC），
  E2E 总数 13；后端新增 V7 迁移结构/append-only、并发导入、缺 pepper 启动失败、POST 患者检索用例。
- 2026-11-13（M2-M0-03）：m0-impl 覆盖率 96.34%/87.52%，device-gateway 91.68%/77.39%，
  删除两处 ratchet 及全部到期临时标记；新增登录 + E2E-04 UI 级 E2E 6 条；
  Vitest 升级 4.1.12、新增 glob 14.0.6 / minimatch 11.0.8 override，devDeps 漏洞清零。
- 2026-11-12：S0-11 初版；JaCoCo 0.8.12、Vitest 3.2.7、Playwright 1.65.0、
  Testcontainers 1.20.4；m0-impl 与 gateway 分支为临时 ratchet。
  `./scripts/mvn.sh -B verify` 实测 5:22（BUILD SUCCESS），`./scripts/check.sh`
  实测 359s（5m59s，OK）。
