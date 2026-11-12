# api-contracts — 契约生成的 Java 类型

**职责**：把 `packages/contracts/openapi/{auth,patient,scheduling,anesthesia}.yaml` 生成 Java 模型类，
供 `apps/api` 各模块按契约实现接口（ADR-0003 第 5 条，M1 前必须可用）。

## 生成方式

```bash
bash scripts/gen-contracts.sh --java
# 等价：./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-contracts -am generate-sources
```

- 插件：`org.openapitools:openapi-generator-maven-plugin:7.10.0`（版本暂写在模块 pom，
  **待主会话收敛到根 `pluginManagement`**）。
- 生成器：`spring`（Jackson + Jakarta Validation，无 Gson/无 swagger 注解依赖）。
- 每份契约一个 execution，输出到 `target/generated-sources/openapi/<域>`，
  模型包 `com.anesop.contracts.<域>.model`（auth/patient/scheduling/anesthesia）。
- `generateModels=true`、`generateApis=false`、`generateSupportingFiles=false`、
  `generateModelTests=false`、`generateModelDocumentation=false`。
- `skipValidateSpec=true`：契约结构与风格由 spectral（`scripts/contract-check.sh`）门禁，
  生成器内置的 Swagger 解析器对 3.1 的 `license.identifier` 等要求与已评审契约冲突。

## 禁止手改

`target/generated-sources/openapi/**` 全部是生成物，**禁止手工修改**；文件头带
`@jakarta.annotation.Generated`。要改字段/枚举必须先改契约，重新生成并同步 baseline。
生成物不入库、不随源码提交（由构建期生成，`scripts/gen-contracts.sh --java` 可复现）。

## 校验

- 编译：`./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-contracts -am test`
- 语义：`ContractModelDeserializationTest` 从契约示例 JSON 反序列化
  `LoginRequest`/`TokenResponse`/`Session`，断言字段名、枚举与 `OffsetDateTime` 映射。
- 生成代码不纳入 Checkstyle/SpotBugs（`spotbugs-exclude.xml`），人工规则只约束手写源码。

## 依赖

`jackson-databind`、`jackson-datatype-jsr310`、`spring-context`（`@DateTimeFormat`）、
`jakarta.validation-api`、`jakarta.annotation-api`，版本均由 Spring Boot BOM 管理。
