# 跨平台协作指南（Linux + macOS）

> 结论：**Linux 与 macOS 可以共同开发本仓库**；应用与依赖本身跨平台，主要差异在仓库内的
> 工具链脚本与本地演示环境。Windows 原生不推荐，请在 WSL2 内按 Linux 方式使用。
> 本指南基于仓库脚本静态检查 + Linux 实跑；macOS 路径为等价做法，尚未在 CI 中验证。

## 1. 支持级别

| 平台 | 级别 | 说明 |
|---|---|---|
| Linux x86_64 | **官方支持** | 仓库自带 `.toolchain/jdk21`、CI 全在 ubuntu-latest 实跑，`./scripts/check.sh` 为权威门禁 |
| macOS（Intel / Apple Silicon） | **开发可用** | 使用系统 JDK/Maven/Node + 本机 PostgreSQL；集成测试用嵌入式 PG（见 §5） |
| Windows | **建议 WSL2** | 原生 Git Bash 下路径/权限/换行问题多；WSL2 等同 Linux |

## 2. 版本要求（两边一致）

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | 根 pom 与 CI 均按 21；mac 用 `brew install openjdk@21` |
| Maven | 3.9+ | `./scripts/mvn.sh` 优先仓库内 Maven，缺失则用系统 `mvn` |
| Node | **≥20**（实测 26） | 见根 `package.json` engines |
| pnpm | **11.x** | 与 `pnpm-lock.yaml` 配套；`packageManager`/CI 已锁定 |
| PostgreSQL | **16** | 本地演示/手工联调用；测试默认用嵌入式 PG，不要求本机 PG |
| Playwright | 1.65.0 | 浏览器按 OS 各自安装，不提交缓存 |

## 3. macOS 环境准备

```bash
brew install openjdk@21 maven node pnpm postgresql@16
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # 或 brew --prefix openjdk@21 下的路径
java -version        # 必须是 21
mvn -v               # 3.9+
node -v && pnpm -v

cd anesop
source scripts/env.sh          # 自动探测：仓库内 Linux JDK 不可执行时回退系统 JAVA_HOME
pnpm install --frozen-lockfile
pnpm exec playwright install chromium   # 首次，装 macOS 版浏览器
```

Linux 照旧：`source scripts/env.sh` 会优先使用仓库内 `.toolchain/jdk21`。

> `scripts/env.sh` / `scripts/mvn.sh` 已做跨平台探测：仓库内 JDK 可执行（Linux）则用仓库内；
> 否则回退 `JAVA_HOME`/PATH（macOS）。若 `source` 后 java 版本不是 21，先修正 `JAVA_HOME`。

## 4. 日常协作命令

两边都建议至少跑：

```bash
source scripts/env.sh
pnpm install --frozen-lockfile
pnpm lint && pnpm typecheck && pnpm test:unit
# 后端（改了 Java/契约/迁移时）
./scripts/mvn.sh -B -f apps/api/pom.xml -pl <module> -am test
# 提交前完整门禁（约 10–20 分钟）
./scripts/check.sh
```

**权威裁决**：CI（ubuntu-latest）与 Linux 上的 `check.sh` 为准；mac 本地门禁通过不代表 CI 通过
（时区/大小写/文件系统差异），PR 仍需 CI 绿。

## 5. 测试与嵌入式 PostgreSQL

- 集成测试走 Zonky 嵌入式 PG16，`m2repo` 已缓存 Linux/Windows/Intel-macOS 二进制；
  Apple Silicon 需要联网拉 `darwin-arm64v8`，若不可用：
  - 用 Docker 起 PG16 + Testcontainers（`-Pdocker`，需 Docker Desktop），或
  - 只跑单测/前端，集成测试交给 Linux 同事/CI。
- Playwright 浏览器不共享：各自 `pnpm exec playwright install chromium`；`.playwright/` 已忽略。
- `demo-local.sh` 使用仓库内 **Linux 版** PG16 二进制，**仅 Linux 可用**；mac 演示改用：
  ```bash
  brew services start postgresql@16
  createdb anesop
  bash scripts/seed-demo.sh --url jdbc:postgresql://127.0.0.1:5432/anesop --user "$USER" --password ''
  # 然后按 docs/guides/quickstart.md 用 java -jar 启动 API
  ```

## 6. 具体差异与规避

| 差异 | 影响 | 规避 |
|---|---|---|
| 换行符 | PR 噪声 | `.gitattributes` 已统一 `eol=lf`；不要改；Windows 脚本才用 CRLF |
| 大小写敏感 | mac 默认大小写不敏感，Linux 敏感 | 文件名/导入名避免仅大小写差异；mac 上可启用大小写敏感 APFS 卷 |
| 路径含中文/空格 | 个别工具解析异常 | 命令里路径加引号；CI 路径为英文 |
| 文件权限 | 脚本可执行位 | git 会保留 `+x`；mac 新拉取后若不能执行：`chmod +x scripts/*.sh` |
| Maven 本地仓库 | `.m2repo` 在仓库内、已忽略 | 不要提交；各机器独立构建 |
| 时区/数据库函数 | 集成测试偶发 | 统一 `Asia/Shanghai`（应用配置已固定），测试用固定时钟/日期夹具 |
| Docker | Linux CI 无 Docker，mac 常有 Docker Desktop | 不依赖 Docker 的测试必须仍可跑；Docker 用例打 `docker` tag，默认跳过（EXT-08） |
| 端到端演示 | 本机 PG/端口不同 | 用 `env` 覆盖 `ANESOP_DB_URL`/`--server.port`，不要改配置文件提交 |

## 7. 提交与评审约定（两人协作）

1. **同一 PR 两人各自本地跑**：至少前端 lint/typecheck/unit + 受影响后端模块测试；
   合并前以 CI 全绿为准。
2. **不允许提交机器相关文件**：`.toolchain/`、`.m2repo/`、`.playwright/`、`.cache/`、
   `playwright-report/`、本机 local profile 配置。
3. **契约/迁移**：改动必须两边都能构建；迁移只加不改（expand-contract），禁止补历史迁移。
4. **行长/换行**：Prettier/Checkstyle 两边一致；mac 保存时确认已用 LF。
5. **演示数据**：各自本地库，不要提交 dump；演示脚本只写 SYNTHETIC 数据。

## 8. 常见问题

- `source scripts/env.sh` 后 `java -version` 不是 21：设置 `JAVA_HOME` 指向 JDK21；mac 可用
  `/usr/libexec/java_home -v 21`。
- `mvn: command not found`：`brew install maven`；或恢复 `.toolchain/maven`（Linux 包）。
- 集成测试卡在下载 PG 二进制：Apple Silicon 联网拉取，或改用 Docker/交给 CI。
- `pnpm e2e` 报找不到浏览器：`pnpm exec playwright install chromium`（按 OS 安装）。
- 启动 API 报数据库连接失败：确认本机 PG16 已启动、库/用户与 `ANESOP_DB_*` 一致；
  演示库可用 `scripts/seed-demo.sh` 初始化。
