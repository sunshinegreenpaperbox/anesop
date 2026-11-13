# 测试基座（S0-11）

- 状态：已落地（2026-11-12）
- 关联：S0-11 / `AGENTS.md`「编码与测试要求」/ `docs/acceptance/ACCEPTANCE.md` B 项
- 范围：后端 JaCoCo 覆盖率门禁、Testcontainers/嵌入式 PG 基座、前端 Vitest、Playwright E2E、测试数据工厂
- 非目标：本阶段不实现 E2E-01–05 的业务流程（依赖 M1/M2/M5 功能），只落地基座与冒烟

## 1. 测试分层与比例

| 层 | 技术 | 目标占比 | 当前落点 |
|---|---|---|---|
| 单元 | JUnit 5 + AssertJ / Vitest + Vue Test Utils | ≈70% | 后端 140（m0-impl）+ 61（gateway）个用例、前端 32 个用例 |
| 集成 | Spring Boot Test + 嵌入式 PostgreSQL 16（Zonky）/ Testcontainers | ≈20% | 迁移、MyBatis、认证越权、审计、网关幂等与断网补传 |
| 契约 | spectral + oasdiff（Java DTO 反序列化用例） | — | `apps/api/api-contracts/src/test` |
| E2E | Playwright（Chromium） | 关键路径 | 13 条（首页冒烟 3 + 登录/越权 6 + M1 申请 4）；E2E-01–05 见 §6 |

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
- 用例：`apps/web/src/__tests__/{App,router,HomeView}.spec.ts`
- 实测：4/4 通过，行/分支/函数/语句均 100%
- 断言可失败：标题、文案、路由 path/name 均为精确值；不使用恒真断言

## 6. E2E 清单与落地计划

| ID | 场景 | 依赖 | 落地里程碑 | 计划 |
|---|---|---|---|---|
| E2E-01 | 排程冲突 | M1 排程 API、登录、合成台次 | M1 | 新建台次 → 制造时间/术间冲突 → 断言 409 与冲突提示 → 调整后成功；复用登录 fixture |
| E2E-02 | 采集断网补传 | S0-09 网关、M2 记录页 | M2 | 启动模拟设备 → 断网 5 分钟 → 恢复 → 断言数据补齐且不重复；网关侧已有集成测试，E2E 补 UI 可见性 |
| E2E-03 | 记录锁单修订 | M2 术中记录、锁单/修订 | M2 | 开始记录 → 锁单 → 发起修订 → 断言版本号递增、原记录不可改、审计留痕 |
| E2E-04 | 权限越权 | M0 登录/RBAC、受保护页面 | M1 | 低权限账号直接访问受限路由/接口 → 断言 403 与审计；页面不显示无权菜单 |
| E2E-05 | 麻精药品双人核对 | M5 麻精五专、双人核对 | M5 | 双人登录 → 核对/签字 → 断言未双人不可发药、留痕完整 |

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
- `pnpm e2e` 3 次重跑稳定（13/13，无 flaky）。

> **E2E-04 口径说明**：当前为 **UI 级越权（路由守卫 + 菜单权限过滤）+ `/api/v1/auth/*` 合成响应拦截**，
> 没有真实后端参与，因此**不得宣称已覆盖真实越权 API**。真实后端全栈越权
> （403 + 审计留痕 + 数据范围）由 M2-M1-01/M2-M2-01 的 `scripts/e2e-full.sh` 承接
> （登录真实账号 → 直连受限 API → 断言 403 与 `sys_audit_log`），脚本落地前本表保持此声明。
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

## 9. CI 对应关系（S0-04 落地）

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

## 10. 变更记录

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
