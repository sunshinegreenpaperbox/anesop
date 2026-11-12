# 施工状态看板（每 Round 更新）

- 当前里程碑：M1（S0）工程骨架
- 当前 Round：2（2026-11-12）
- 最近提交：S0-02 monorepo 骨架
- 已完成任务：2 / 12（S0-01、S0-02）
- 进行中：S0-03 静态检查与提交规范
- 阻塞：—（Docker 不可用登记 EXT-08，不阻塞；远端 GitHub 组织未建，CODEOWNERS/分支保护为占位）
- 下一步：S0-03 → S0-04/05/06/10 并行 → S0-07/09/11
- 环境：JDK 21 就绪（.toolchain/jdk21）、Maven 3.10（本地仓库 .m2repo，脚本封装）、Node 26/pnpm 11（仓库内 store）、网络可达 npm/Maven Central；Docker 不可用（EXT-08）
- 门禁：`./scripts/check.sh` → OK（后端 7 测试 BUILD SUCCESS；前端 build/typecheck 通过；pnpm 安装锁文件已入库）

## 近期提交

| 提交 | 任务 | 说明 |
|---|---|---|
| 9740afd | S0-01 | git init、.gitignore、README、CODEOWNERS、Issue/PR 模板、分支保护说明 |
| （本提交） | S0-02 | 根/聚合 POM、29 模块 reactor、api-bootstrap、前端壳、占位目录、ADR-0001 |
