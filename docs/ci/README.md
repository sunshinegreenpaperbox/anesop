# CI 流水线（S0-04）

- 状态：已落地（2026-11-12）；远端 GitHub 组织/仓库未建立，工作流尚未在真实 runner 运行
- 关联：S0-04 / `AGENTS.md`「提交、PR 与评审」「编码与测试要求」/ `docs/branch-protection.md` 第 2 节 / EXT-08
- 工作流：`.github/workflows/ci.yml`
- 本地等价：`scripts/ci-local.sh`
- 门禁辅助：`docs/ci/check-deps-license.py`、`docs/ci/audit-exceptions.md`、`docs/ci/deps-allowlist-baseline.txt`、`docs/ci/gitleaks.toml`

## 1. 触发、权限与并发

| 项 | 设置 |
|---|---|
| 触发 | `pull_request`、`push`（仅 `main`）、`workflow_dispatch` |
| 权限 | `permissions: contents: read`（全部 job 只读；不上传 code scanning、不写 PR、不推镜像） |
| 并发 | `concurrency: ci-${{ github.ref }}`，`cancel-in-progress: true`（同一 PR/分支新提交取消旧运行） |
| Runner | `ubuntu-latest` |
| 工具链 | 每个 job 统一 JDK 21（temurin）+ Node.js 22 + pnpm 11；Maven 一律 `./scripts/mvn.sh` |
| 状态检查名 | job `name` 与分支保护必需检查名完全一致（`ci/<job>`） |

## 2. 10 个 job 与门禁矩阵

| # | 必需检查（job name） | workflow job id | 超时 | 主要命令 | 失败即阻断 |
|---|---|---|---|---|---|
| 1 | `ci/lint` | `lint` | 10 min | `pnpm lint`（ESLint + Prettier）、`./scripts/mvn.sh -B -DskipTests validate`（Checkstyle）、ShellCheck（runner 预装，缺失记录跳过）、`scripts/check-rules.sh` | 是 |
| 2 | `ci/typecheck` | `typecheck` | 10 min | `pnpm -C apps/web exec vue-tsc --noEmit`、`./scripts/mvn.sh -B -DskipTests compile` | 是 |
| 3 | `ci/unit-test` | `unit-test` | 20 min | `./scripts/mvn.sh -B verify`（Surefire + JaCoCo + SpotBugs）、`pnpm test:unit`（Vitest v8 覆盖率） | 是 |
| 4 | `ci/contract` | `contract` | 10 min | `pnpm -C packages/contracts lint`、`bash scripts/contract-check.sh`（spectral + oasdiff breaking + TS 生成物漂移） | 是 |
| 5 | `ci/build` | `build` | 20 min | `./scripts/mvn.sh -B -DskipTests package`、`pnpm -C apps/web build`；上传 jar/dist | 是 |
| 6 | `ci/sast` | `sast` | 15 min | Semgrep 1.185.0，`p/default`，`--severity=ERROR --error`（ERROR ≈ high/critical） | 是 |
| 7 | `ci/deps-license` | `deps-license` | 15 min | `pnpm audit --prod --audit-level high`（硬门禁）、全量 `pnpm audit --json` + 审计例外表、`pnpm licenses list`、白名单脚本、`mvn compile dependency:analyze` | 是 |
| 8 | `ci/secret-scan` | `secret-scan` | 10 min | gitleaks 8.30.1 全历史扫描（`fetch-depth: 0`），命中即失败 | 是 |
| 9 | `ci/image-scan` | `image-scan` | 30 min | `docker build` api/web → Trivy `HIGH,CRITICAL`（`ignore-unfixed`）；**镜像构建步骤 EXT-08 记录性豁免** | 构建成功时扫描阻断 |
| 10 | `ci/e2e-smoke` | `e2e-smoke` | 20 min | `pnpm exec playwright install --with-deps chromium` + `pnpm e2e`；上传 report/trace | 是 |

说明：

- 并行目标：代码类 job（除 image-scan 外）在缓存命中时合计 wall-clock ≤10 分钟；
  `ci/image-scan` 的冷构建受镜像层下载影响可能单独超过 10 分钟（EXT-08 期间构建失败不阻断）。
- `ci/unit-test` 的 `mvn verify` 已包含 Checkstyle/SpotBugs/测试/覆盖率；`ci/lint`、`ci/typecheck`、
  `ci/build` 为并行拆分后的快速反馈，重复的编译成本由 Maven 缓存吸收。
- `ci/sast` 使用 Semgrep ERROR 级作为阻断线；`p/default` 的 WARNING/INFO 不阻断（记录在 SARIF）。
  扫描范围为 `apps/ services/ packages/ db/migration`（应用代码、契约、迁移）；`deploy/` 的
  Dockerfile/IaC 由 `ci/image-scan`（Trivy）与部署评审覆盖，见 §10。
  排除 bcrypt 哈希规则：`AuthServiceImpl` 的固定假哈希（侧信道均衡）与合成演示种子哈希均非
  凭据，真实凭据由 `ci/secret-scan` 负责；排除项写在工作流命令注释中，不使用全局抑制。
- `ci/deps-license` 的漏洞策略：**生产依赖树** high/critical 硬阻断；**devDependencies** 的
  high/critical 必须逐条登记在 `docs/ci/audit-exceptions.md`（含 GHSA、责任人、`expires`），
  未登记或过期同样阻断。2026-11-13（M2-M0-03）升级 vitest 4.1.12 并 override
  glob 14.0.6 / minimatch 11.0.8 后，devDependencies 漏洞为 **0**，阻断级例外清零。
- `ci/secret-scan` 不使用 `gitleaks/gitleaks-action`（组织仓库需商业 license），改为固定版本
  CLI 直接下载，因此**不需要任何 secret**。
- 10 个 job 均未使用 `continue-on-error`，唯一例外是 `ci/image-scan` 的镜像构建步骤
  （见 §6 EXT-08）。

## 3. 缓存键与路径

所有缓存路径都在仓库内（与 `scripts/env.sh` 一致），不使用 `~/.m2`/`~/.npm`：

| 缓存 | path | key | restore-keys | 使用 job |
|---|---|---|---|---|
| Maven 本地仓库 | `.m2repo` | `m2-${runner.os}-${hashFiles('**/pom.xml')}` | `m2-${runner.os}-` | lint、typecheck、unit-test、build、deps-license |
| pnpm store | `.pnpm-store` | `pnpm-${runner.os}-${hashFiles('pnpm-lock.yaml')}` | `pnpm-${runner.os}-` | lint、typecheck、unit-test、contract、build、deps-license、e2e-smoke |
| Playwright 浏览器 | `.playwright` | `playwright-${runner.os}-${hashFiles('pnpm-lock.yaml','apps/web/playwright.config.ts')}` | `playwright-${runner.os}-` | e2e-smoke |

- 不缓存 `node_modules`：pnpm 软链接与平台相关，恢复旧目录可能产生"命中但行为异常"；
  改为缓存 store + 每次 `pnpm install --frozen-lockfile`（命中时通常 <30s）。
- 缓存保存由 `actions/cache@v4` 在 job 结束时自动完成；缓存键不匹配时回退到前缀键。

## 4. 必需 secrets

| 项 | 是否需要 | 说明 |
|---|---|---|
| 代码/测试/SAST/密钥扫描 | 无 | 全部使用公开工具与仓库内配置 |
| 镜像仓库 | 否（当前） | `ci/image-scan` 只本地 build + 扫描，不 push；接入私有 registry 时再加 `IMAGE_REGISTRY`/`IMAGE_PUSH_TOKEN`（仅该 job 使用） |
| gitleaks license | 无 | 不用 gitleaks-action，避免组织仓库 license 依赖 |
| GitHub code scanning | 无 | SARIF 以 artifact 留存，不需要 `security-events: write` |

工作流中不存在任何硬编码密钥；`docs/ci/gitleaks.toml` 只豁免已登记的合成凭据
（`SYNTHETIC-PASSWORD`、演示种子 `Anesop@2026`），不放行真实凭据目录。

## 5. 本地等价命令

```bash
source scripts/env.sh

# 一键等价（默认全量 10 个 job 口径；外部工具不可用时打印 SKIP）
bash scripts/ci-local.sh

# 快速模式：跳过 build 与 e2e-smoke
bash scripts/ci-local.sh --fast

# 单 job（接受 ci/lint 或 lint）
bash scripts/ci-local.sh --job ci/unit-test

# 分项命令
pnpm lint
./scripts/mvn.sh -B -DskipTests validate
pnpm -C apps/web exec vue-tsc --noEmit
./scripts/mvn.sh -B -DskipTests compile
./scripts/mvn.sh -B verify
pnpm test:unit
pnpm -C packages/contracts lint && bash scripts/contract-check.sh
pnpm audit --prod --audit-level high --registry=https://registry.npmjs.org
pnpm licenses list --json > .cache/ci-local/npm-licenses.json
python3 docs/ci/check-deps-license.py \
  --npm-licenses .cache/ci-local/npm-licenses.json \
  --audit-json .cache/ci-local/npm-audit-full.json \
  --audit-exceptions docs/ci/audit-exceptions.md
./scripts/mvn.sh -B -DskipTests package && pnpm -C apps/web build
pnpm e2e
```

> 本机若把 npm registry 指向镜像站，`pnpm audit` 会报 “audit endpoint does not exist”；
> `scripts/ci-local.sh` 与 CI 均显式使用 `--registry=https://registry.npmjs.org`。

`ci-local.sh` 行为约定：

- 每步打印 `[ci-local] ci/<job>` 标题、单步耗时；结束打印 PASS/SKIP/FAIL 汇总与总耗时。
- 任一 job 失败 → 退出码 1；参数错误 → 2；全部通过（含 SKIP）→ 0。
- `SKIP` 仅用于本机工具缺失：semgrep / gitleaks / Trivy / Docker（EXT-08）/ Playwright 浏览器。
  **不用于掩盖真实失败**：工具存在时失败即 FAIL。
- 按任务约定，`image-scan` 本地**不执行任何 docker 命令**，直接 SKIP；CI 中由真实 Docker
  build + Trivy 执行。

## 6. EXT-08 豁免项与解除条件

| 豁免项 | 现状 | 解除条件 |
|---|---|---|
| `ci/image-scan` 镜像构建 | 本机无 Docker daemon，`api.Dockerfile`/`web.Dockerfile` 从未真实构建；workflow 中构建步骤 `continue-on-error: true`，失败时在 Step Summary 记录并跳过 Trivy | 远端 runner 上 `docker build` api/web 成功一次；回填 `docs/acceptance/`；删除该步骤的 `continue-on-error` 与豁免分支 |
| Trivy 镜像扫描 | 依赖上一条；构建成功时已是阻断门禁（HIGH/CRITICAL 可修复漏洞） | 同上；另补基础镜像 digest（`TODO(digest,EXT-08)`） |
| Testcontainers 用例 | `@Tag("docker")` 默认排除，surefire 不执行 | Docker 可用后跑 `-Pdocker`，并把结果纳入 `ci/unit-test` |
| 镜像 digest | `deploy/compose/*.Dockerfile`、compose 服务镜像未 pin `@sha256` | 有 registry 访问后补 digest 并记录验证日期 |

CI 中不因 EXT-08 跳过任何代码/配置类门禁（lint、typecheck、单测、契约、构建、SAST、
依赖、密钥扫描、E2E 均真实执行）。

## 7. 与 `scripts/check.sh` 的关系（本地 vs CI）

| 能力 | `scripts/check.sh`（本地串行全量） | `.github/workflows/ci.yml`（CI 并行） |
|---|---|---|
| 目的 | 提交前一条命令自检，历史行为保持不变 | PR 门禁，10 个必需检查并行 |
| 结构 | 7 步串行：后端 verify → pnpm install → lint → vue-tsc → Vitest → 契约 → 规则 | 10 个独立 job，按关注点拆分 |
| 耗时 | 约 6 分钟（后端 verify 为主） | 目标 ≤10 分钟（并行 + 缓存） |
| 覆盖率 | JaCoCo/Vitest 门禁 | 同左，另上传报告 artifact |
| SAST/密钥/镜像/依赖审计 | 不包含 | 包含（`ci/sast`、`ci/secret-scan`、`ci/image-scan`、`ci/deps-license`） |
| E2E | 不包含 | `ci/e2e-smoke` |
| 规则检查 | 第 7 步 | `ci/lint` 内 |

两者共用同一批底层命令（`mvn.sh`、`pnpm`、`contract-check.sh`、`check-rules.sh`），
`scripts/check.sh` 未在本任务修改。日常建议：提交前 `bash scripts/check.sh`，
需要覆盖 CI 新增门禁时 `bash scripts/ci-local.sh --fast`。

## 8. PR 检查清单映射

| PR 模板检查项（`.github/PULL_REQUEST_TEMPLATE.md`） | 对应 CI 检查 |
|---|---|
| 编译与类型检查通过 | `ci/typecheck`、`ci/build` |
| 新增行为有测试、覆盖率未下降 | `ci/unit-test`（JaCoCo / Vitest） |
| 契约未私改；破坏性变更附兼容说明 | `ci/contract`（spectral + oasdiff + 生成物漂移） |
| 迁移可回滚、expand-contract | 不在 CI 自动判定（评审项）；由 `ci/unit-test` 的迁移测试兜底 |
| 权限/审计/脱敏横切检查 | 评审项；`ci/sast` + 单测用例辅助 |
| 无患者标识、仅合成数据 | 评审项；`ci/secret-scan` 兜底凭据泄露 |
| 无白名单外依赖；lockfile 变更说明 | `ci/deps-license`（白名单 + 许可证 + 漏洞） |
| 有效改动 ≤400 行 / 分支规则 | 评审项（GitHub Ruleset 不自动判定行数） |
| Conventional Commits / AI trailer | 本地 hook + 评审项（CI 未加 commitlint job，避免超出 10 项必需检查） |

## 9. 首次远端接入步骤

1. 建立 GitHub 组织与仓库，`main` 为默认分支；推送本仓库（含 `.github/workflows/ci.yml`）。
2. Settings → Actions → General：允许 Actions；确认 workflow 权限为只读（默认）。
3. 首次推送后手动 `workflow_dispatch` 跑一次，逐 job 确认：
   - 10 个 job 名称与 §2 完全一致；
   - 缓存第二次运行命中（`.m2repo`、`.pnpm-store`、`.playwright`）；
   - `ci/image-scan` 的构建结果（成功则 Trivy 阻断，失败则记录 EXT-08）。
4. Settings → Branches/Ruleset：按 `docs/branch-protection.md` 第 2 节把 10 个检查设为
   required status checks（名称使用 `ci/lint` … `ci/e2e-smoke`），启用
   "Require branches to be up to date"、禁直推/force push、仅 squash。
5. 按 `docs/branch-protection.md` 第 5 节替换 `CODEOWNERS` 占位团队，启用 Require review from Code Owners。
6. 用仅改文档的测试 PR 验证：10 个检查出现、红灯阻断、缓存命中有日志；把验证记录写入
   `docs/acceptance/` 并更新 `PROJECT_STATE.md`。
7. 若接入私有镜像仓库：仅为 `ci/image-scan` 增加 `IMAGE_REGISTRY`/`IMAGE_PUSH_TOKEN` secret，
   并同步更新本文件 §4。

## 10. 已知约束与风险

- **Semgrep 规则集为远端 `p/default`**：首次运行需访问 semgrep registry；规则更新可能引入
  新命中。建议首次远端运行后把命中基线（`semgrep.sarif`）纳入评审，必要时在 PR 中说明
  例外；不通过降低 severity 掩盖。
- **已知 SAST 发现（已登记为部署评审例外）**：`deploy/compose/web.Dockerfile` 未声明
  `USER`，nginx 默认以 root 运行（Semgrep `dockerfile.security.missing-user`）。`deploy/`
  不在 S0-04 写域，本任务未修改；该发现已登记在 `docs/deploy/README.md` §13 与
  `docs/ci/audit-exceptions.md` §四，M6 上线准备前切换非 root 镜像关闭。`ci/sast` 当前
  不扫描 `deploy/`（由 `ci/image-scan` + 部署评审覆盖），不以抑制规则或改 severity 掩盖。
- **`scripts/ci-local.sh` 不执行 `pnpm install`**：假设依赖已安装（与 `scripts/check.sh`
  不同），CI 每个 job 会执行 `pnpm install --frozen-lockfile`。本地首次使用前请先运行
  `pnpm install --frozen-lockfile`；lockfile 一致性由 CI 的 install 步骤把关。
- **gitleaks 全历史扫描**首次运行可能命中历史提交中的合成凭据；`docs/ci/gitleaks.toml`
  只精确豁免已登记的合成值/演示种子，不放行目录级真实凭据。
- **devDependencies 漏洞**：2026-11-13 已升级 vitest/@vitest/coverage-v8 到 4.1.12，
  并以 `pnpm-workspace.yaml` 的 `overrides` 固定 glob 14.0.6、minimatch 11.0.8；
  `pnpm audit --dev` 为 0 漏洞，例外表不再有 dev high/critical 条目。升级工具链后须复跑
  `pnpm test:unit` / `pnpm lint` / `pnpm build` / `pnpm e2e`。
- **白名单历史缺口**（`docs/ci/deps-allowlist-baseline.txt`）4 条 direct 依赖（spring-context、
  spring-aop、aspectjweaver、jackson-datatype-jsr310）待补 `docs/deps/allowlist.md`；新增依赖
  不受基线保护，必须直接进白名单。
- **Playwright 浏览器下载**：本机 Google CDN 曾限速，CI 用 `--with-deps` + `.playwright`
  缓存；首次运行仍可能较慢（超时 20 分钟）。
- **未跑 `act`**：本机无 GitHub 远端/act，workflow 仅做 YAML 静态校验与 `ci-local.sh` 等价
  执行；首次真实 runner 运行仍需按 §9 验证。
- `docs/testing/README.md` §9 的 CI job 映射为 S0-04 落地前的建议名
  （`backend-test`/`frontend-test`/`e2e`/`contract`/`rules`），实际以本文件 §2 为准，
  待 S0-11 文档维护者同步。

## 11. 变更记录

- 2026-11-12：S0-04 初版；10 个 job、`scripts/ci-local.sh`、依赖/许可证/审计例外脚本与
  gitleaks 配置落地；EXT-08 镜像扫描记录性豁免。
