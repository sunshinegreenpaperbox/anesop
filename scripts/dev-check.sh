#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 开发环境自检（S0-12）
#
# 用途：新人第一次 clone 后、或 CI/本地门禁失败时，先跑本脚本定位环境问题。
# 只做版本、目录、文件、端口与权限检查：不编译、不下载依赖、不连数据库、
# 不启动 Docker（Docker 可用性登记为 EXT-08，仅检查 CLI 是否存在）。
#
# 用法（仓库根）：
#   bash scripts/dev-check.sh
#
# 退出码：
#   0  全部通过（WARN 不算失败，例如端口已被占用、Docker 不可用）
#   1  存在 FAIL（版本不满足、目录不可写、必需文件缺失、git 仓库异常）
#   2  不在仓库根执行
#
# 输出：每条 [PASS]/[WARN]/[FAIL]，最后一行汇总；FAIL 会给出修复命令。
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || {
  echo "dev-check: 无法进入仓库根 $ROOT" >&2
  exit 2
}

if [ ! -f "$ROOT/pom.xml" ] || [ ! -d "$ROOT/apps/api" ]; then
  echo "dev-check: $ROOT 不像 ANESOP 仓库根（缺 pom.xml 或 apps/api）" >&2
  exit 2
fi

PASS=0
WARN=0
FAIL=0

pass() {
  PASS=$((PASS + 1))
  printf '[PASS] %s\n' "$1"
}

warn() {
  WARN=$((WARN + 1))
  printf '[WARN] %s\n' "$1"
}

fail() {
  FAIL=$((FAIL + 1))
  printf '[FAIL] %s\n' "$1"
  if [ -n "${2:-}" ]; then
    printf '       修复：%s\n' "$2"
  fi
}

# 语义化版本比较：version_ge A B 为真表示 A >= B
version_ge() {
  [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n1)" = "$2" ]
}

# 端口是否已有进程监听（只做 TCP connect，不发业务数据）
port_busy() {
  timeout 2 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" 2>/dev/null
}

echo "============================================================"
echo "ANESOP dev-check（仓库根：$ROOT）"
echo "============================================================"

# ---------------------------------------------------------------------------
# 0. 工具链初始化（必须与 check.sh 一致，缓存落在仓库内）
# ---------------------------------------------------------------------------
if [ -f "$ROOT/scripts/env.sh" ]; then
  # shellcheck source=scripts/env.sh
  if source "$ROOT/scripts/env.sh" >/dev/null 2>&1; then
    pass "已加载 scripts/env.sh（JAVA_HOME/MAVEN_OPTS/PNPM 缓存指向仓库内）"
  else
    fail "scripts/env.sh 加载失败" "bash -x scripts/env.sh 查看具体错误"
  fi
else
  fail "缺少 scripts/env.sh" "从仓库重新检出 scripts/env.sh"
fi

# ---------------------------------------------------------------------------
# 1. JDK 21
# ---------------------------------------------------------------------------
if [ -x "$ROOT/.toolchain/jdk21/bin/java" ]; then
  pass "仓库内 JDK 存在：.toolchain/jdk21"
else
  fail "缺少 .toolchain/jdk21（仓库内 JDK 21）" "按 docs/guides/troubleshooting.md 重新解压 JDK 21 到 .toolchain/jdk21"
fi

if command -v java >/dev/null 2>&1; then
  JAVA_VERSION="$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
  JAVA_MAJOR="${JAVA_VERSION%%.*}"
  JAVA_MAJOR="${JAVA_MAJOR#1.}"
  if [ "$JAVA_MAJOR" = "21" ]; then
    pass "java 在 PATH 中且为 21（$JAVA_VERSION）"
  else
    fail "当前 java 版本为 ${JAVA_VERSION:-unknown}，要求 21" "source scripts/env.sh（不要裸调系统 java/mvn）"
  fi
else
  fail "java 不在 PATH" "source scripts/env.sh"
fi

# ---------------------------------------------------------------------------
# 2. Maven
# ---------------------------------------------------------------------------
if command -v mvn >/dev/null 2>&1; then
  MVN_FULL="$(mvn -v 2>/dev/null | head -n1)"
  MVN_VERSION="$(printf '%s\n' "$MVN_FULL" | sed -E 's/^Apache Maven ([0-9.]+).*/\1/')"
  if version_ge "$MVN_VERSION" "3.9.0"; then
    pass "Maven $MVN_VERSION（>=3.9；统一经 scripts/mvn.sh 调用）"
  else
    fail "Maven 版本为 ${MVN_VERSION:-unknown}，要求 >=3.9" "使用 .toolchain/maven 或升级 Maven"
  fi
  MVN_JAVA="$(mvn -v 2>/dev/null | sed -nE 's/^Java version: ([0-9.]+).*/\1/p')"
  if [ "${MVN_JAVA%%.*}" = "21" ]; then
    pass "Maven 运行在 JDK 21（$MVN_JAVA）"
  else
    fail "Maven 运行在 JDK ${MVN_JAVA:-unknown}，会与编译目标不符" "source scripts/env.sh 后用 ./scripts/mvn.sh，不要裸调 mvn"
  fi
else
  fail "mvn 不在 PATH" "source scripts/env.sh 或检查 .toolchain/maven"
fi

# ---------------------------------------------------------------------------
# 3. Node / pnpm
# ---------------------------------------------------------------------------
if command -v node >/dev/null 2>&1; then
  NODE_VERSION="$(node --version | sed 's/^v//')"
  if version_ge "$NODE_VERSION" "20.0.0"; then
    pass "Node $NODE_VERSION（>=20，package.json engines 要求）"
  else
    fail "Node $NODE_VERSION 低于 20" "升级 Node（CI 使用 Node 22）"
  fi
else
  fail "node 不在 PATH" "安装 Node 20+ 并重新 source scripts/env.sh"
fi

if command -v pnpm >/dev/null 2>&1; then
  PNPM_VERSION="$(pnpm --version 2>/dev/null | head -n1)"
  if version_ge "$PNPM_VERSION" "10.0.0"; then
    pass "pnpm $PNPM_VERSION（>=10；pnpm-lock.yaml 要求 frozen 安装）"
    if ! version_ge "$PNPM_VERSION" "11.0.0"; then
      warn "pnpm 主版本为 $PNPM_VERSION，CI 使用 pnpm 11；锁文件与 allowBuilds 字段已兼容，但建议对齐"
    fi
  else
    fail "pnpm $PNPM_VERSION 低于 10" "corepack enable 或升级 pnpm 到 11"
  fi
else
  fail "pnpm 不在 PATH" "corepack enable pnpm 或安装 pnpm 11"
fi

# ---------------------------------------------------------------------------
# 4. 仓库内缓存目录可写（home 只读，禁止写 ~/.m2、~/.npm）
# ---------------------------------------------------------------------------
CACHE_DIRS=(".m2repo" ".npm-cache" ".pnpm-store" ".pnpm-home" ".pnpm-cache" ".playwright" ".cache")
for d in "${CACHE_DIRS[@]}"; do
  target="$ROOT/$d"
  if ! mkdir -p "$target" 2>/dev/null; then
    fail "缓存目录不可创建：$d" "检查仓库目录权限（不要写到 home）"
    continue
  fi
  probe="$target/.dev-check.$$"
  if ( : >"$probe" ) 2>/dev/null; then
    rm -f "$probe"
    pass "缓存目录可写：$d"
  else
    fail "缓存目录不可写：$d" "chmod u+w '$target' 或修正挂载权限"
  fi
done

# ---------------------------------------------------------------------------
# 5. 必需文件（缺失会让 check.sh / 快速开始直接失败）
# ---------------------------------------------------------------------------
REQUIRED_FILES=(
  "pom.xml"
  "package.json"
  "pnpm-lock.yaml"
  "pnpm-workspace.yaml"
  "apps/api/pom.xml"
  "apps/api/api-bootstrap/pom.xml"
  "apps/web/package.json"
  "packages/contracts/package.json"
  "packages/ui/package.json"
  "services/device-gateway/pom.xml"
  "db/migration/V1__m0_platform_init.sql"
  "db/seed/demo/V100__demo_admin_synthetic.sql"
  "deploy/compose/docker-compose.yml"
  "scripts/env.sh"
  "scripts/mvn.sh"
  "scripts/check.sh"
  "scripts/contract-check.sh"
  "docs/ai/tasks/S0-backlog.md"
  "docs/acceptance/ACCEPTANCE.md"
  "README.md"
)
MISSING=()
for f in "${REQUIRED_FILES[@]}"; do
  [ -e "$ROOT/$f" ] || MISSING+=("$f")
done
if [ ${#MISSING[@]} -eq 0 ]; then
  pass "必需文件齐全（${#REQUIRED_FILES[@]} 个）"
else
  fail "缺少必需文件：${MISSING[*]}" "git status 确认是否误删，或重新检出仓库"
fi

# ---------------------------------------------------------------------------
# 6. 端口占用提示（占用只告警：可能是上次未退出的 dev 进程）
# ---------------------------------------------------------------------------
if port_busy 8080; then
  warn "端口 8080（API）已被占用；启动后端前先停止旧进程（lsof -i:8080 / ss -ltnp）"
else
  pass "端口 8080（API）空闲"
fi
if port_busy 5173; then
  warn "端口 5173（Vite dev）已被占用；E2E 使用 strictPort，占用了会导致 pnpm e2e 失败"
else
  pass "端口 5173（Vite dev）空闲"
fi
if port_busy 8081; then
  warn "端口 8081（采集网关 HTTP）已被占用（可能是旧网关进程）"
else
  pass "端口 8081（采集网关 HTTP）空闲"
fi
if port_busy 19090; then
  warn "端口 19090（采集网关 TCP）已被占用（可能是旧网关进程）"
else
  pass "端口 19090（采集网关 TCP）空闲"
fi
if port_busy 5432; then
  pass "端口 5432（PostgreSQL）已监听；可执行 scripts/seed-demo.sh 与本地启动 API"
else
  warn "端口 5432（PostgreSQL）未监听：check.sh 不受影响（用嵌入式 PG），但 API 启动与 seed-demo 需要 PG 实例；Docker 不可用时见 EXT-08"
fi

# ---------------------------------------------------------------------------
# 7. git 钩子与仓库状态提示
# ---------------------------------------------------------------------------
if [ -d "$ROOT/.git" ]; then
  HOOKS_PATH="$(git config --local --get core.hooksPath 2>/dev/null || true)"
  if [ "$HOOKS_PATH" = ".githooks" ]; then
    pass "git 钩子已启用（core.hooksPath=.githooks）"
  else
    warn "git 钩子未启用（commitlint/提交前检查不会生效）" "bash scripts/install-hooks.sh"
  fi
else
  fail "当前目录不是 git 仓库（缺 .git）" "git init 或重新 clone"
fi

# ---------------------------------------------------------------------------
# 8. Docker（EXT-08：只探测 CLI，不启动 daemon / 不运行容器）
# ---------------------------------------------------------------------------
if command -v docker >/dev/null 2>&1; then
  warn "检测到 docker CLI；本机 daemon 可用性未探测（施工环境登记 EXT-08，Compose/E2E 相关步骤可能不可用）"
else
  warn "未安装 docker CLI：deploy/compose 与 Testcontainers 相关步骤不可用（EXT-08，不阻塞主线）"
fi

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
echo "------------------------------------------------------------"
if [ "$FAIL" -eq 0 ]; then
  echo "dev-check: PASS —— 通过 $PASS，警告 $WARN，失败 0"
  echo "下一步：source scripts/env.sh && pnpm install --frozen-lockfile && ./scripts/check.sh"
  exit 0
fi
echo "dev-check: FAIL —— 通过 $PASS，警告 $WARN，失败 $FAIL"
echo "请按上面 [FAIL] 行的“修复”建议处理后重跑。"
exit 1
