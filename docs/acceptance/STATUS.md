# 施工状态看板（每 Round 更新）

- 当前里程碑：M1（S0）工程骨架
- 当前 Round：4（2026-11-12）
- 最近提交：S0-06 数据库迁移基线（评审修复）
- 已完成任务：6 / 12（S0-01、S0-02、S0-03、S0-05、S0-06、S0-10）
- 进行中：—（下一批 S0-07/09/11 即将派发）
- 阻塞：—（Docker 不可用登记 EXT-08，不阻塞；远端 GitHub 组织未建，CODEOWNERS/分支保护为占位）
- 下一步：S0-07 认证权限（含 Java codegen）、S0-09 采集 POC、S0-11 测试基座 并行 → S0-04/08/12
- 环境：JDK 21 就绪（.toolchain/jdk21）、Maven 3.10（本地仓库 .m2repo，脚本封装）、Node 26/pnpm 11（仓库内 store）、网络可达 npm/Maven Central；Docker 不可用（EXT-08）
- 门禁：`./scripts/check.sh` → OK（后端 25 测试 + Checkstyle/SpotBugs；契约 lint/breaking/drift；前端 lint/typecheck）

## 近期提交

| 提交 | 任务 | 说明 |
|---|---|---|
| 9740afd | S0-01 | git init、.gitignore、README、CODEOWNERS、Issue/PR 模板、分支保护说明 |
| e505418 | S0-02 | 根/聚合 POM、29 模块 reactor、api-bootstrap、前端壳、占位目录、ADR-0001 |
| 18e8e16 | S0-03 | ESLint/Prettier/Checkstyle/SpotBugs/commitlint/EditorConfig、check.sh 七步门禁、allowlist、ADR-0002 |
| 2121fd7 | S0-10 | Compose 9 服务 + 健康检查 + 资源限制、api/web Dockerfile、nginx TLS、监控 profile、compose-check、ADR-0004 |
| d545f9a | S0-05 | 4 域 OpenAPI 3.1（22 path/26 op）、spectral + oasdiff 基线、TS 生成、数据字典初稿、ADR-0003 |
| ff0512b | S0-05 修 | 400/422、三方核查接口、脱敏、生成物漂移校验（0.2.0，23 path/28 op） |
| （本提交） | S0-06 | 8 表/21 约束/16 索引、审计 append-only、SYNTHETIC 种子、演示账号隔离、嵌入式 PG16 测试 25 个、ADR-0005 |
