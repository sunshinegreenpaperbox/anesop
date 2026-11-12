# api-bootstrap — 唯一可执行装配点

职责：启动 Spring Boot 应用，提供 Web / Validation / Actuator / springdoc 基础设施，
并把各模块 `api` 装配进同一个部署单元（模块化单体）。

## 目录

- `src/main/java/com/anesop/api/ApiApplication.java` — 唯一启动类（`@SpringBootApplication`）
- `src/main/resources/application.yml` — 端口 8080、actuator、springdoc、dev/test profile
- `src/test/java/com/anesop/api/` — 上下文加载、`GET /actuator/health` 用例
- `src/test/java/com/anesop/architecture/ModuleBoundaryTest.java` — ArchUnit 模块边界用例

## 边界

- 只允许依赖各模块 `api`（`m0-api` … `m8-api`），不得依赖任何 `impl`；
- 业务代码不进 bootstrap，bootstrap 只做装配与横切基础设施；
- `impl` 的运行时装配随 S0-07（M0 认证权限骨架）引入，并同步启用 ArchUnit 对真实 impl 的检查。

## 端点

| 路径 | 说明 |
|---|---|
| `GET /actuator/health` | 健康检查，期望 `200` + `{"status":"UP"}` |
| `GET /v3/api-docs` | OpenAPI 文档（契约由 S0-05 落地） |
| `GET /swagger-ui.html` | 接口调试页（仅 dev） |
