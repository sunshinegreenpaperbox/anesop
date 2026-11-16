# 开发工作流（分支 / 提交 / 门禁 / 契约 / 迁移 / 交接）

> 适用：所有人类开发者与 AI 代理。规则正文在 [`AGENTS.md`](../../AGENTS.md)，本文件只把它们
> 落成可执行的日常步骤与命令；两者冲突时以 `AGENTS.md` 为准。
> 相关：[`quickstart.md`](quickstart.md)（首次环境）、[`troubleshooting.md`](troubleshooting.md)、
> [`../ci/README.md`](../ci/README.md)、[`../branch-protection.md`](../branch-protection.md)。

## 1. 每天开始（2 分钟）

```bash
source scripts/env.sh          # JDK 21 + 仓库内 .m2repo / pnpm 缓存（home 只读）
bash scripts/dev-check.sh      # 版本、缓存目录、必需文件、端口占用；末行 PASS/FAIL
bash scripts/install-hooks.sh  # 首次：启用 commit-msg（commitlint）；提交前检查仍需手动跑 check.sh
git status --short             # 确认没有把 .env、target、node_modules 带进来
```

阅读顺序（会话协议，最多 6 项）：`PROJECT_STATE.md` → `AGENTS.md` →
`docs/ai/corpus/CONTEXT_CORE.md` → 当前任务卡 → 相关契约/ADR → `docs/ai/examples/`（如有）。

## 2. 分支与提交

| 项 | 规则 |
|---|---|
| 长期分支 | 只有 `main`；禁止直接 push main（H-02） |
| 功能分支 | `feat/<issue>-<slug>`，存活 ≤3 天 |
| 修复/杂务 | `fix/<issue>-<slug>`、`chore/<issue>-<slug>`，存活 ≤2 天 |
| 合并方式 | 只 squash merge；禁 merge commit、禁 force push |
| 提交格式 | Conventional Commits 1.0.0：`<type>(<scope>): <subject>` |
| scope | `m0`…`m8`、`web`、`api`、`contracts`、`gateway`、`db`、`deploy`、`docs`、`repo` |
| 提交前 | `bash scripts/install-hooks.sh` 后由 `.githooks/commit-msg` 跑 commitlint |

示例：

```text
feat(m1): 增加排程冲突检测接口与用例
fix(m0): 修正强制改密下的 403 响应码
docs(repo): 补齐 S0-12 快速开始与故障排查
chore(repo): 升级 pnpm 缓存目录说明
```

AI 参与的高风险改动（单次生成 >50 行，或属于事务/权限/审计/计费/临床规则/迁移/外部映射）
必须加五个 trailer：`AI-Tool`、`AI-Task`、`AI-Scope`、`Reviewed-by`、`Co-authored-by`；
`AI-Scope` 必须落在本 PR 变更集内。AI 不产生 `APPROVE`，同一代理不得既写又审。

## 3. 本地门禁与 CI 对应

```bash
# 全量本地门禁（后端 verify → pnpm frozen install → lint → vue-tsc → Vitest → 契约 → 规则）
./scripts/check.sh

# 按需拆分（改哪块跑哪块，提交前至少跑一次全量）
./scripts/mvn.sh -B verify                     # 后端编译/单测/覆盖率/Checkstyle/SpotBugs
pnpm lint                                      # ESLint + Prettier
pnpm -C apps/web exec vue-tsc --noEmit         # 类型
pnpm test:unit                                 # Vitest + v8 覆盖率门禁
bash scripts/contract-check.sh                 # spectral + oasdiff + 生成物漂移
bash scripts/check-rules.sh                    # 适配层漂移、临时条款过期
git diff --check                               # 行尾空白/冲突标记
bash scripts/ci-local.sh --fast                # 本地 CI 等价（无远端时用）
```

| 本地命令 | 对应 CI 检查（`.github/workflows/ci.yml`） |
|---|---|
| `pnpm lint`、`mvn -DskipTests validate`、`check-rules.sh` | `ci/lint` |
| `vue-tsc --noEmit`、`mvn compile` | `ci/typecheck` |
| `mvn verify`、`pnpm test:unit` | `ci/unit-test` |
| `contract-check.sh` | `ci/contract` |
| `mvn -DskipTests package`、`pnpm -C apps/web build` | `ci/build` |
| `ci-local.sh` 的 sast/secret-scan/deps-license/image-scan | 外部工具 job（不可用时 SKIP，不掩盖失败） |
| `pnpm e2e` | `ci/e2e-smoke` |

门禁失败禁止用 `|| true`、跳过测试或改断言绕过（H-03）。不稳定用例 24h 内修复，
否则隔离并建 Issue；跳过/删除测试须 PR 说明并经架构师批准。

## 4. 契约变更（先契约、后代码）

顺序：领域模型 → `db/migration/V<版本>__<描述>.sql` → `packages/contracts/openapi/<域>.yaml`
→ 生成类型 → 编码。字段只增不删；删除/改类型升 major、双写过渡并写 ADR。

```bash
bash scripts/contract-check.sh            # spectral（0 error/0 warning）+ breaking + 生成物漂移
bash scripts/gen-contracts.sh             # 只生成 TS（packages/contracts/generated/typescript）
bash scripts/gen-contracts.sh --java      # 生成 Java DTO 到 api-contracts/target（不入库）
bash scripts/gen-contracts.sh --all       # TS + Java
```

- `packages/contracts/generated/**`、`apps/web/src/api/generated/**`、
  `apps/api/api-contracts/target/**` 一律**禁止手改**；改字段先改契约再重新生成。
- **baseline 更新**：契约经人工评审、确认兼容后，才把当前 yaml 复制到
  `packages/contracts/openapi/baseline/`，并在同一 PR 说明版本与评审结论；
  `contract-check.sh` 不会自动改基线。
- 破坏性变更：`oasdiff` 会在 `ci/contract` 阻断；需附兼容说明、双人评审（至少 1 名 CODEOWNERS）。
- Sprint 第 3 个工作日后契约冻结，阻断缺陷除外。

## 5. 数据库迁移规范

完整规范见 [`../db/README.md`](../db/README.md)；日常只需记住：

- 命名 `V<版本>__<描述>.sql`（双下划线），版本连续、不跳号、不复用；`db/seed/demo` 独立从 V100 起。
- **forward-only**：不写 `U<版本>__*.sql`；回滚 = 备份恢复或前向修复。
- 已合入并执行过的脚本**禁止修改**（Flyway checksum 会失败），修正一律新增版本。
- expand-contract：新增可空列/表 → 双写回填 → 切读 → 后续版本删旧；禁止顺手 `DROP COLUMN`/改类型。
- 演示账号只能放 `db/seed/demo`，生产 `locations` 只含 `db/migration`；`prod` 启动守卫会拒绝 `demo_*`。
- 迁移必须附测试：空库、幂等、升级路径、演示种子隔离、审计 append-only。

```bash
# 迁移/持久层集成测试（Zonky 嵌入式 PG16；Docker 不可用 EXT-08 时的默认路径）
./scripts/mvn.sh -B -pl apps/api/modules/m0/impl -am test
```

## 6. PR 与评审

PR 必填八字段：关联 Issue（`Closes #<issue>`）、变更类型、变更说明、影响面、
测试证据、回滚方案、检查清单、AI 参与声明。有效改动 ≤400 行，>800 行必须拆分。

| 改动范围 | 批准要求 |
|---|---|
| 普通 PR | 1 人 |
| `modules/m2/`、`modules/m0/` 权限与审计、`db/migration/`、安全、契约破坏性变更 | 2 人，至少 1 名 CODEOWNERS 自然人 |
| 首轮评审 | 24h 内；核心域 12h 内 |

评论分 `blocking` / `suggestion` / `question`，只有 `blocking` 阻断；存在未解决
`blocking` 或必需检查失败时不得合并。

## 7. 任务卡、交接单与会话日志

- 开工前先认领任务：`docs/ai/tasks/`（S0 用 `S0-backlog.md`，后续按 `TASK-<issue>.md`），
  状态 `Ready → In Progress → Review → QA → Done`；阻塞标 `blocked` 并写原因与需要谁拍板。
- 只改任务卡「写域」内路径；跨模块改动先拆任务或先改契约任务。
- **会话结束必须落文件**（口头结论无效）：
  1. 更新 `PROJECT_STATE.md` 第 1/4/5/6 节；
  2. 写 `docs/ai/handoffs/YYYY-MM-DD-<主题>.md`（模板 `docs/ai/handoffs/TEMPLATE.md`）；
  3. 需要时补 `docs/ai/sessions/YYYY-MM-DD-<主题>.md`（模板同目录 `TEMPLATE.md`）。
- 新会话从交接单接续，启动词见 [`../ai/SESSION_STARTER.md`](../ai/SESSION_STARTER.md)。

## 8. 相对链接校验（可复用）

文档改动后，从仓库根运行以下脚本；输出 `checked=N broken=0` 即通过。它只校验
**相对链接指向的文件/目录是否存在**，不校验外链与页内锚点。

```bash
python3 - <<'PY'
import pathlib
import re
import sys

root = pathlib.Path('.').resolve()
files = [root / 'README.md']
files += sorted((root / 'docs/guides').glob('*.md'))
files += [root / 'docs/ai/README.md']
files += sorted((root / 'docs/modules').glob('*/README.md'))
files += sorted((root / 'apps').glob('*/README.md'))
files += sorted((root / 'apps/api/modules').glob('*/README.md'))
files += sorted((root / 'apps/web/src/modules').glob('*/README.md'))
files += sorted((root / 'packages').glob('*/README.md'))
files += sorted((root / 'services').glob('*/README.md'))

pattern = re.compile(r'!?\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)')
checked, broken = 0, []
for md in files:
    if not md.is_file():
        continue
    for match in pattern.finditer(md.read_text(encoding='utf-8')):
        link = match.group(1)
        if link.startswith(('http://', 'https://', 'mailto:', '#')):
            continue
        target = link.split('#', 1)[0]
        if not target:
            continue
        checked += 1
        if not (md.parent / target).resolve().exists():
            broken.append(f'{md.relative_to(root)} -> {link}')

print(f'checked={checked} broken={len(broken)}')
for item in broken:
    print('BROKEN', item)
sys.exit(1 if broken else 0)
PY
```

建议在提交前与 `git diff --check` 一起跑；新增/移动文档后尤其要跑。CI 目前未加该步骤
（避免 S0-12 改 CI），后续可登记为 `ci/lint` 的附加 step。

## 9. 常见返工点

| 症状 | 先检查 |
|---|---|
| 本地绿、CI 红 | 是否用了本机 JDK/Node 版本；CI 为 JDK 21 + Node 22 + pnpm 11 |
| `pnpm install` 失败 | 是否漏提交 `pnpm-lock.yaml`；是否改了 `package.json` 版本未更新锁文件 |
| 契约 job 红 | 是否手改生成物；baseline 是否误更新；是否引入破坏性变更 |
| 迁移 job 红 | 是否改了已执行脚本；版本是否跳号；生产 locations 是否混入 seed |
| 覆盖率下降 | 新增分支是否有用例；是否把逻辑塞进被排除的 `config/**` |
| 找不到改动 | 是否只改在写域内；跨模块依赖是否应先改 `api` 契约 |

## 10. 远程仓库与推送（2026-11-16 建立）

- 远程：`origin = git@github.com:sunshinegreenpaperbox/anesop.git`，默认分支 **`main`**
- 当前已推送：`main`（148 个提交）与标签 `m1`–`m9`
- 本机网络：GitHub **HTTPS 被阻断**，使用 SSH；本机系统 `ssh_config` 权限异常，仓库内已设置：
  ```bash
  git config core.sshCommand "ssh -F /dev/null -o StrictHostKeyChecking=accept-new \
    -o UserKnownHostsFile=$PWD/.cache/known_hosts"
  ```
  团队成员各自把公钥加入 GitHub（Settings → SSH keys）即可；不要提交私钥。
- **仓库规则限制**：GitHub ruleset 对单次推送的分支+标签数量上限为 **5**，因此：
  ```bash
  git push -u origin main          # 先推分支（单次 1 个 ref）
  git push origin m1 m2 m3 m4 m5   # 标签分批，每批 ≤5
  git push origin m6 m7 m8 m9
  ```
  日常功能分支 PR 不受影响；不要 force push 已共享的 `main`/标签。
- 首次建立（记录用）：
  ```bash
  git remote add origin git@github.com:sunshinegreenpaperbox/anesop.git
  git push -u origin main
  git push origin --tags   # 若超出 5 个标签会被规则拒绝，需分批
  ```
