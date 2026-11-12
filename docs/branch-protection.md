# main 分支保护规则

> 状态：**远端 GitHub 组织与仓库尚未建立**，本文件是保护规则的唯一书面约定，待远端建立后按第 5、7 节落地为 GitHub Ruleset / Branch protection。
> 规则来源：`AGENTS.md`「提交、PR 与评审」、`docs/ai/corpus/CONTEXT_CORE.md` 第 5 节。
> 维护：S0-01 起草；S0-04 已把必需检查名与 `.github/workflows/ci.yml` 的 job `name` 对齐（2026-11-12）。

## 1. 基本约束

`main` 是唯一长期分支，所有变更必须经 PR 合入：

| 规则 | 设置 |
|---|---|
| 直推 `main` | 禁止（含管理员；bypass 列表为空） |
| 强制推送 | 禁止（`force push`、`--force-with-lease` 一律拒绝） |
| 删除 `main` | 禁止 |
| 合并方式 | **仅允许 Squash merge**；禁用 merge commit 与 rebase merge |
| 合并后分支 | 自动删除 head 分支 |
| 线性历史 | 要求 PR 分支基于最新 `main`（strict / "Require branches to be up to date"） |
| 讨论解决 | 所有 review comment 必须 resolved 才能合并 |
| 合并前同步 | 至少 1 次 approve 且无 `blocking` 评论 |

## 2. 必需状态检查（Required status checks）

以下检查全部为必需，任一失败、跳过或超时即阻断合并。检查名称已与 S0-04 工作流
（`.github/workflows/ci.yml`）的 job `name` 对齐；GitHub 分支保护按该名称匹配：

| # | 检查名（Workflow job name） | 内容 | 阻断条件 |
|---|---|---|---|
| 1 | `ci/lint` | ESLint + Prettier + Checkstyle + ShellCheck | 任一 error |
| 2 | `ci/typecheck` | `vue-tsc --noEmit` + Java 编译 | 类型/编译错误 |
| 3 | `ci/unit-test` | JUnit 5 + Vitest，JaCoCo/Vitest 覆盖率门禁 | 用例失败或覆盖率低于门槛（行 ≥70%/分支 ≥55%；核心域与新增代码 ≥80%/70%） |
| 4 | `ci/contract` | `spectral lint` + `oasdiff breaking` | 规则 error、破坏性变更未附兼容说明 |
| 5 | `ci/build` | Maven `package` + `pnpm build` | 构建失败 |
| 6 | `ci/sast` | Semgrep 规则集 | 命中 high/critical |
| 7 | `ci/deps-license` | 依赖漏洞与许可证扫描（对照 `docs/deps/allowlist.md`） | CVSS ≥7 未处置、GPL/AGPL、白名单外依赖 |
| 8 | `ci/secret-scan` | gitleaks / GitHub secret scanning | 命中密钥或凭据 |
| 9 | `ci/image-scan` | 镜像 Trivy 扫描 | HIGH/CRITICAL 可修复漏洞（Docker 不可用期间标记 `EXT-08`，不阻断合入但记录豁免） |
| 10 | `ci/e2e-smoke` | Playwright 冒烟（至少 E2E-01/02/03/04/05 子集） | 冒烟用例失败 |

工作流内部 job id → 检查名映射（仅实现细节；分支保护只勾选上表 10 个 name）：
`lint`→`ci/lint`、`typecheck`→`ci/typecheck`、`unit-test`→`ci/unit-test`、
`contract`→`ci/contract`、`build`→`ci/build`、`sast`→`ci/sast`、
`deps-license`→`ci/deps-license`、`secret-scan`→`ci/secret-scan`、
`image-scan`→`ci/image-scan`、`e2e-smoke`→`ci/e2e-smoke`。

补充要求：

- 检查必须在**最新提交**上通过（stale check 不算通过）。
- 契约端点 100% 有用例；临床记录专项（时间轴、断网采集、并发修改、签名锁定）为发布准入项，任一不通过不得发布。
- 检查被跳过（skip）等同失败，除非在 PR 中说明并经架构师批准。

## 3. 审批规则

| 变更范围 | 最少批准人数 | 额外要求 |
|---|---|---|
| 普通 PR | 1 | 批准的评审人须为自然人 |
| `apps/api/modules/m2/`、`apps/web/src/modules/m2/` | 2 | 至少 1 名 CODEOWNERS；临床安全专项测试证据 |
| `apps/api/modules/m0/` 的权限与审计相关改动 | 2 | 至少 1 名 CODEOWNERS；越权与审计用例证据 |
| `db/migration/` | 2 | 至少 1 名 CODEOWNERS；expand-contract 与回滚演练记录 |
| 安全相关（认证、加密、脱敏、导出、依赖） | 2 | 至少 1 名 CODEOWNERS |
| 契约破坏性变更（`packages/contracts/`） | 2 | 至少 1 名 CODEOWNERS；兼容说明 + 双写过渡方案 |
| 发布相关（`deploy/`、发布变更单） | 2 | 发布窗口周二/周四 20:00–22:00，须 `release.yml` 审批 |

- 评审意见分级：`blocking`（阻断）、`suggestion`、`question`。只有 `blocking` 阻断合并，但必须全部 resolved。
- 首轮评审时限：普通 PR 24h，核心域 12h。
- **AI 不得产生 `APPROVE`、不得写审批记录、不得进入 CODEOWNERS**；同一代理不得既写又审。
- 缺失 PR 八字段或 AI 参与声明，直接阻断。

## 4. 分支存活期与命名

| 分支 | 命名 | 最长存活 |
|---|---|---|
| 功能 | `feat/<issue>-<slug>` | ≤3 天 |
| 修复 | `fix/<issue>-<slug>` | ≤2 天 |
| 杂项 | `chore/<issue>-<slug>` | ≤2 天 |
| 发布/热修 | `release/<version>`、`hotfix/<issue>-<slug>` | 按变更单，合并后立即删除 |

- 超期分支必须当日合并、关闭或拆小重开，并在 Issue 说明原因。
- 每个 PR 有效改动 ≤400 行；>800 行必须拆分。
- 分支落后 `main` 超过 1 天须 rebase（`git pull --rebase`），避免长期分叉。

## 5. CODEOWNERS 占位替换步骤（远端组织建立后执行）

当前 `.github/CODEOWNERS` 使用 `@anesop/...` 占位团队名，**不产生实际审批效力**。远端建立后按序执行：

1. 在 GitHub 组织下创建团队：`maintainers`、`architects`、`security-review`、`clinical-safety`、`contracts`、`dba`、`device-gateway`、`platform`、`qa`、`web-core`、`m0-platform-security` … `m8-dashboard`。
2. 将团队成员加入对应团队，确认每个团队至少 2 名自然人（避免单点无法满足双人评审）。
3. 逐个替换 `.github/CODEOWNERS` 中的 `@anesop/<team>` 为真实团队或 `@<user>`；替换后用 `git ls-files` 对照第 5 节清单人工核对路径覆盖，确认 m0–m8 的 api/impl、前端模块、契约、迁移、网关、部署、`.github/`、`scripts/`、验收与 ADR 均有归属。
4. 在仓库 Settings → Code owners 确认无 "Unknown owner" 报错；GitHub 会在 PR 中自动请求对应 owner 评审。
5. 启用 "Require review from Code Owners"，并把第 2 节 10 项检查设为 required status checks。
6. 提交一个仅改文档的 PR 验证：CODEOWNERS 自动请求、必需检查全部出现、双人规则生效；验证记录写入 `docs/acceptance/`（由验收任务维护，本任务不写）。
7. 在 `PROJECT_STATE.md` 与本文件顶部更新"远端已建立"状态。

> 替换前请勿删除占位注释，便于审计追溯。

## 6. 远端未建时的本地等价检查

在 GitHub 组织、Actions 与分支保护可用之前，用以下本地流程做等价把关（结果贴入 PR 或交接单）。
S0-04 起用 `bash scripts/ci-local.sh [--fast]` 按 CI job 逐项等价执行，也可用
`--job ci/<name>` 只跑单项；`SKIP` 仅代表本机工具不可用（见 `docs/ci/README.md`）：

| 远端门禁 | 本地等价操作 |
|---|---|
| lint / typecheck | `bash scripts/ci-local.sh --job ci/lint`；`--job ci/typecheck`（或 `./scripts/check.sh`） |
| 单测与覆盖率 | `bash scripts/ci-local.sh --job ci/unit-test`（`./scripts/mvn.sh -B verify` + `pnpm test:unit`） |
| 契约 | `bash scripts/ci-local.sh --job ci/contract`（spectral + oasdiff + 生成物漂移） |
| 构建 | `bash scripts/ci-local.sh --job ci/build`（`mvn package` + `pnpm build`） |
| 密钥扫描 | `bash scripts/ci-local.sh --job ci/secret-scan`（gitleaks 不可用时 SKIP）；同时人工检查 `git diff` 是否含密钥、`.env`、`*.pem` |
| 依赖与许可证 | `bash scripts/ci-local.sh --job ci/deps-license`（白名单 + `pnpm audit` + `pnpm licenses`）；并核对 `docs/deps/allowlist.md` 与 lockfile diff |
| 镜像扫描 | `bash scripts/ci-local.sh --job ci/image-scan`：Docker 不可用（EXT-08）时按设计 SKIP，CI 恢复后补扫 |
| E2E 冒烟 | `bash scripts/ci-local.sh --job ci/e2e-smoke`；浏览器未安装时 SKIP，环境就绪前以集成测试 + 手工冒烟清单替代并记录 |
| 直推保护 | 合入前 `git log --oneline main..HEAD` 自检；只允许 squash，`git merge --squash` |
| 改动规模 | `git diff --stat main...HEAD` 人工核对 ≤400 行 |
| 分支存活 | `git for-each-ref --sort=committerdate --format='%(refname:short) %(committerdate:short)' refs/heads` 检查超期分支 |

本地检查通过**不能替代**评审；远端就绪后必须补跑 CI 并满足第 2、3 节。

## 7. git 本地配置建议（仓库级，避免写 home）

home 在本工作区只读，配置一律加 `--local`，不要用 `--global`：

```bash
# 身份（按真实姓名/邮箱填写，用于署名与审计）
git config --local user.name  "<姓名>"
git config --local user.email "<邮箱>"

# 拉取与合并习惯
git config --local pull.rebase true
git config --local rebase.autoStash true
git config --local merge.ff only
git config --local fetch.prune true

# 提交前检查
git config --local core.hooksPath .githooks   # S0-03 落地 hooks 后启用
git config --local commit.gpgsign true        # 有签名密钥时启用

# 换行与文件名
git config --local core.autocrlf false
git config --local core.quotepath false
```

约定：

- 不配置 `remote.origin`，直到远端仓库建立并由项目经理确认地址。
- 不使用 `git push --force`；需要修正历史时在分支上 `git rebase -i` 后 `--force-with-lease` 仅推个人分支（`main` 永不适用）。
- 提交信息遵循 Conventional Commits 1.0.0：`<type>(<scope>): <subject>`，scope ∈ {m0…m8, web, api, contracts, gateway, db, deploy, docs, repo}（repo = 仓库级配置/脚本/根文件，S0-03 增补）。
- AI 生成 >50 行高风险代码时，提交附 trailer：`AI-Tool`、`AI-Task`、`AI-Scope`、`Reviewed-by`、`Co-authored-by`。

## 8. 落地检查清单（远端建立后逐项勾选）

- [ ] 组织与仓库建立，`main` 为默认分支
- [ ] Ruleset：禁直推、禁 force push、禁删除、仅 squash、要求线性历史
- [ ] 10 项 required status checks 全部启用
- [ ] CODEOWNERS 占位替换完成，无 Unknown owner
- [ ] 启用 Require review from Code Owners
- [ ] 核心域 2 人审批规则验证通过（用一个测试 PR 演练）
- [ ] 分支自动删除、Issue/PR 模板渲染正常
- [ ] 在 `PROJECT_STATE.md` 记录落地日期与验证 PR 编号
