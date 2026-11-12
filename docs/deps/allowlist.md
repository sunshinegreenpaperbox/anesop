# 依赖白名单（allowlist）

> 规则来源：`AGENTS.md`「技术栈锁定」——新增或升级依赖先写 ADR，再更新本表（名称、版本、许可证、理由、责任人）。
> 准入：直接依赖限于 MIT、Apache-2.0、BSD、ISC、0BSD；GPL/AGPL 禁止；LGPL 需法务结论。
> 传递依赖由 Maven Enforcer / pnpm audit 在 CI 检查（S0-04 落地），本表只登记**直接依赖**。
> 更新方式：新增依赖的任务在提交中同步更新本表；版本升级需改版本号并注明 ADR 编号。

## 一、后端（Maven，根 `pom.xml` 统一锁定）

| 依赖（groupId:artifactId） | 版本 | 许可证 | 用途 / 引入任务 | 责任人 |
|---|---|---|---|---|
| org.springframework.boot:spring-boot-starter-parent | 3.4.5 | Apache-2.0 | Boot 基线（父 POM） | 架构师 |
| org.springframework.boot:spring-boot-starter-web | 3.4.5 | Apache-2.0 | REST 接口与内嵌容器 | 后端负责人 |
| org.springframework.boot:spring-boot-starter-validation | 3.4.5 | Apache-2.0 | Bean Validation | 后端负责人 |
| org.springframework.boot:spring-boot-starter-actuator | 3.4.5 | Apache-2.0 | 健康检查/指标 | 后端负责人 |
| io.micrometer:micrometer-core | Boot BOM 管理 | Apache-2.0 | 审计失败指标 `auth.audit.failures`（S0-07 S-4；actuator 传递依赖，显式声明） | 安全负责人 |
| org.springframework.boot:spring-boot-starter-test | 3.4.5 | Apache-2.0 | JUnit 5/MockMvc/AssertJ | 测试负责人 |
| org.springframework.security:spring-security-crypto | Boot BOM 管理 | Apache-2.0 | BCrypt 口令哈希（S0-06 测试，S0-07 认证实现） | 安全负责人 |
| org.springframework.boot:spring-boot-starter-security | 3.4.5 | Apache-2.0 | 安全过滤链（S0-07） | 安全负责人 |
| org.springframework.boot:spring-boot-starter-oauth2-resource-server | 3.4.5 | Apache-2.0 | JWT 资源服务器（Nimbus，免直依赖） | 安全负责人 |
| org.springframework.boot:spring-boot-starter-aop | 3.4.5 | Apache-2.0 | 审计切面 | 后端负责人 |
| org.springframework.boot:spring-boot-starter-jdbc | 3.4.5 | Apache-2.0 | Flyway/JDBC 运行时 | 后端负责人 |
| org.springframework.security:spring-security-core | Boot BOM | Apache-2.0 | 安全核心 API | 安全负责人 |
| com.fasterxml.jackson.core:jackson-databind / datatype-jsr310 | Boot BOM | Apache-2.0 | 契约 DTO 序列化 | 后端负责人 |
| jakarta.validation:jakarta.validation-api | Boot BOM | Apache-2.0 | Bean Validation（生成物注解） | 后端负责人 |
| jakarta.annotation:jakarta.annotation-api | Boot BOM | EPL-2.0 / GPL-2.0 with CPE（Jakarta 标准 API，仅注解） | @Generated 注解 | 后端负责人 |
| org.openapitools:openapi-generator-maven-plugin | 7.10.0 | Apache-2.0 | 契约 → Java DTO（构建期，S0-07） | 架构师 |
| org.springframework:spring-context | Boot BOM | Apache-2.0 | 模块上下文（S0-02 起直接依赖，S0-12 补登记） | 后端负责人 |
| org.springframework:spring-aop | Boot BOM | Apache-2.0 | AOP 切面（S0-07 直接依赖，S0-12 补登记） | 后端负责人 |
| org.aspectj:aspectjweaver | Boot BOM | EPL-1.0（构建/运行期织入库，AGENTS LGPL 条款不适用；登记为待法务确认） | 审计切面织入（S0-07） | 安全负责人 |
| com.fasterxml.jackson.datatype:jackson-datatype-jsr310 | Boot BOM | Apache-2.0 | Java 8 时间序列化（S0-07，S0-11 allowlist 缺口补齐） | 后端负责人 |
| org.postgresql:postgresql | Boot BOM 管理 | BSD-2-Clause | PG JDBC 驱动 + PGobject（jsonb/inet TypeHandler） | 后端负责人 |
| org.springdoc:springdoc-openapi-starter-webmvc-ui | 2.7.0 | Apache-2.0 | OpenAPI 3.1 文档 | 后端负责人 |
| com.tngtech.archunit:archunit-junit5 | 1.3.0 | Apache-2.0 | 模块边界用例 | 架构师 |
| com.baomidou:mybatis-plus-spring-boot3-starter | 3.5.9 | Apache-2.0 | 持久层（S0-06 起启用） | 后端负责人 |
| org.flywaydb:flyway-core | 10.20.1 | Apache-2.0 | 数据库迁移 | DBA/后端 |
| org.flywaydb:flyway-database-postgresql | 10.20.1 | Apache-2.0 | Flyway PG 方言 | DBA/后端 |
| org.testcontainers:testcontainers-bom | 1.20.4 | Apache-2.0 | 容器化集成测试（仅锁版本；Docker 不可用 EXT-08） | 测试负责人 |
| org.testcontainers:junit-jupiter | 1.20.4（BOM 管理） | Apache-2.0 | Testcontainers JUnit 5 集成；`@Tag("docker")` 默认不执行（S0-11，EXT-08） | 测试负责人 |
| org.testcontainers:postgresql | 1.20.4（BOM 管理） | Apache-2.0 | `PostgreSQLContainer` 16 模板（S0-11，EXT-08 期间只编译不实跑） | 测试负责人 |
| org.jacoco:jacoco-maven-plugin | 0.8.12 | EPL-2.0（构建期工具，不进入交付物） | 覆盖率 prepare-agent/report/check 门禁（S0-11） | 测试负责人 |
| io.zonky.test:embedded-postgres | 2.2.2 | Apache-2.0 | 无 Docker 的嵌入式 PG（测试） | 测试负责人 |
| io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64 | 16.15.0 | PostgreSQL License（宽松，见方案表 3-8） | 嵌入式 PG 16 二进制（测试，Linux amd64） | 测试负责人 |
| com.h2database:h2 | 2.3.232（仅属性，未引入） | MPL-2.0 / EPL-1.0 | AGENTS 允许的兜底测试库；**当前不引入**，MPL 在白名单外，启用前须 ADR/法务结论 | — |
| org.apache.camel:*（BOM） | 4.14.0（仅属性，未引入） | Apache-2.0 | 外部系统适配（S0-09 起按需引入） | 集成负责人 |
| ca.uhn.hapi:hapi-*（HL7v2） | 2.5.1（仅属性，未引入） | MPL-2.0/GPL 双许可（需选 MPL 并法务确认） | HL7v2 解析（S0-09 起评估；**引入前须 ADR/法务**） | 集成负责人 |
| org.apache.maven.plugins:maven-checkstyle-plugin | 3.6.0 | Apache-2.0 | 代码风格门禁（构建插件） | 平台组 |
| com.puppycrawl.tools:checkstyle | 10.26.1 | LGPL-2.1（构建期工具，不进入交付物，动态调用） | Checkstyle 引擎（S0-03） | 平台组 |
| com.github.spotbugs:spotbugs-maven-plugin | 4.9.8.5 | LGPL-2.1（构建期工具，不进入交付物） | 静态缺陷扫描（S0-03） | 平台组 |

> 构建期插件（Checkstyle/SpotBugs）为 LGPL-2.1，仅以独立工具方式在构建中调用、不链接进交付物；按 AGENTS「LGPL 需法务结论」登记，结论前不得随产品分发其源码/二进制。

## 二、前端（pnpm workspace，精确版本，`pnpm-lock.yaml` 锁定）

| 包 | 版本 | 许可证 | 用途 / 引入任务 | 责任人 |
|---|---|---|---|---|
| vue | 3.5.45 | MIT | 前端框架 | 前端负责人 |
| vue-router | 4.5.1 | MIT | 路由 | 前端负责人 |
| pinia | 3.0.4 | MIT | 状态管理 | 前端负责人 |
| vite | 6.4.6 | MIT | 构建 | 前端负责人 |
| @vitejs/plugin-vue | 5.2.4 | MIT | SFC 编译 | 前端负责人 |
| typescript | 5.6.3 | Apache-2.0 | 类型系统 | 前端负责人 |
| vue-tsc | 2.1.10 | MIT | 类型检查 | 前端负责人 |
| eslint | 9.39.4 | MIT | 静态检查（S0-03） | 前端负责人 |
| @eslint/js | 9.39.4 | MIT | ESLint 推荐规则 | 前端负责人 |
| typescript-eslint | 8.72.2 | MIT | TS 规则 | 前端负责人 |
| @typescript-eslint/parser | 8.72.2 | MIT | TS 解析器 | 前端负责人 |
| eslint-plugin-vue | 10.12.1 | MIT | Vue 规则 | 前端负责人 |
| eslint-config-prettier | 10.1.8 | MIT | 关闭格式冲突规则 | 前端负责人 |
| vue-eslint-parser | 10.4.2 | MIT | .vue 解析 | 前端负责人 |
| globals | 17.14.0 | MIT | 全局变量定义 | 前端负责人 |
| prettier | 3.9.10 | MIT | 格式化（S0-03） | 前端负责人 |
| @commitlint/cli | 19.8.1 | MIT | 提交信息校验（S0-03） | 平台组 |
| @commitlint/config-conventional | 19.8.1 | MIT | Conventional Commits 规则 | 平台组 |
| vitest | 4.1.12 | MIT | 前端单测（S0-11；M2-M0-03 升级清除 tinypool 漏洞链） | 前端负责人 |
| @vitest/coverage-v8 | 4.1.12 | MIT | v8 覆盖率报告与阈值（S0-11；随 vitest 升级） | 前端负责人 |
| @vue/test-utils | 2.4.6 | MIT | Vue 组件测试（S0-11） | 前端负责人 |
| jsdom | 26.1.0 | MIT | Vitest DOM 环境（S0-11） | 前端负责人 |
| @playwright/test | 1.65.0 | Apache-2.0 | E2E 冒烟（S0-11） | 前端负责人 |

## 三、待引入（登记后可用）

| 依赖 | 计划版本 | 许可证（待核） | 用途 | 引入任务 |
|---|---|---|---|---|
| （已采用，见第三节）@stoplight/spectral-cli | 6.16.0 | Apache-2.0 | OpenAPI lint | S0-05 已引入 |
| （已采用，见第三节）openapi-typescript | 7.13.0 | MIT | 契约 → TS 类型 | S0-05 已引入 |
| （已采用，见第三节）@oasdiff-js/oasdiff-js | 1.0.0 | Apache-2.0 | 破坏性变更检测（封装 oasdiff 1.15.0） | S0-05 已引入 |
| element-plus | 2.9.11 | MIT | UI 组件（S0-08 已引入，全量注册） | S0-08 已引入 |
| echarts | 5.5.x | Apache-2.0 | 图表/大屏 | M8 |
| pact-jvm | 4.x | Apache-2.0 | 契约测试 | M5 联调 |

## 三之二、S0-05 实际引入（契约工具链）

| 包 | 版本 | 许可证 | 用途 | 备注 |
|---|---|---|---|---|
| @stoplight/spectral-cli | 6.16.0 | Apache-2.0 | OpenAPI lint | 其遥测依赖 @scarf/scarf 安装脚本已在 pnpm-workspace 显式关闭 |
| openapi-typescript | 7.13.0 | MIT | 契约 → TS 类型生成 | 生成物 `packages/contracts/generated/typescript/` 入库 |
| @oasdiff-js/oasdiff-js | 1.0.0 | Apache-2.0 | OpenAPI 破坏性变更检测 | 封装 oasdiff 1.15.0；openapi-diff 0.24.1 因不支持 3.1 未采用 |

## 四、检查与豁免

- CI（S0-04）将执行：Maven Enforcer（禁用未管理版本/依赖收敛）、`pnpm audit --audit-level=high`、许可证扫描（对照本表 + SPDX 白名单）、SBOM 生成。
- 环境受限豁免：Docker 不可用（EXT-08）期间镜像扫描与 Testcontainers 实跑豁免，恢复后补扫；H2/HAHPS 等"仅属性未引入"项不构成依赖。
- 任何不在本表的直接依赖即为**未审批依赖**（AGENTS H-05），CI 应阻断。

## 五、本地注解/规避说明

- `edu.umd.cs.findbugs.annotations.SuppressFBWarnings`：仓库内**自带最小同 FQN 注解**
  （CLASS retention、无运行时依赖、不随产品分发第三方代码），用于抑制 SpotBugs
  `EI_EXPOSE_REP2` 字段/构造器注入误报；不使用官方 `spotbugs-annotations`（LGPL-2.1，
  需法务结论）。M1 重新评估；如改用官方依赖须先登记本表并由法务确认。

## 六、pnpm overrides（传递依赖安全修复）

| 包 | 版本 | 许可证 | 原因 | 引入任务 |
|---|---|---|---|---|
| glob | 14.0.6 | ISC | 修复 CLI 注入 GHSA-5j98-mcp5-4vw2（仅测试/CLI 工具链，不进入交付物） | M2-M0-03 |
| minimatch | 11.0.8 | ISC | 修复 ReDoS，glob 14 依赖链 | M2-M0-03 |

override 仅影响测试与 CLI 工具链；升级后用 `pnpm audit --dev --audit-level high`（npmjs registry）
验证 0 high/critical，并跑 `pnpm test:unit`/`lint`/`build`/`e2e` 全绿。
