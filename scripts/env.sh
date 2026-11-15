#!/usr/bin/env bash
# 在仓库根 source 本文件，统一工具链与本地缓存（home 只读，所有缓存必须落在仓库内）。
#
# 跨平台约定（Linux / macOS）：
#   - 优先使用仓库内 .toolchain/jdk21（仅当当前系统可执行时，例如 Linux x86_64）；
#   - 若仓库内 JDK 不可执行（macOS/其它架构），回退到已设置的 JAVA_HOME，再回退 PATH 上的 java；
#   - Maven 同理：优先 .toolchain/maven/bin，缺失则用 PATH 上的 mvn。
# Windows 原生 bash 不受支持，请在 WSL2 内按 Linux 方式使用本仓库。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANESOP_ROOT="$ROOT"

# ---- JDK ----
BUNDLED_JAVA="$ROOT/.toolchain/jdk21/bin/java"
if [ -x "$BUNDLED_JAVA" ] && "$BUNDLED_JAVA" -version >/dev/null 2>&1; then
  export JAVA_HOME="$ROOT/.toolchain/jdk21"
  echo "ANESOP env: 使用仓库内 JDK21（$JAVA_HOME）"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  echo "ANESOP env: 使用系统 JAVA_HOME=$JAVA_HOME（仓库内 JDK 不可执行，符合 macOS 预期）"
else
  if command -v java >/dev/null 2>&1; then
    echo "ANESOP env: 使用 PATH 上的 java（未发现仓库内 JDK，也未设置 JAVA_HOME）"
    echo "ANESOP env: 提示：本项目要求 JDK 21，请确认 java -version 为 21；macOS 可 brew install openjdk@21"
  else
    echo "ANESOP env: [ERROR] 未找到可用的 JDK 21。Linux 请先恢复 .toolchain/jdk21；macOS 请安装 JDK21 并设置 JAVA_HOME。" >&2
  fi
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

# ---- Maven ----
BUNDLED_MAVEN="$ROOT/.toolchain/maven/bin/mvn"
if [ -x "$BUNDLED_MAVEN" ]; then
  export PATH="$ROOT/.toolchain/maven/bin:$PATH"
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "ANESOP env: [WARN] PATH 中未找到 mvn；请安装 Maven 3.9+（macOS: brew install maven）或使用 scripts/mvn.sh 前先安装。" >&2
fi

export MAVEN_OPTS="-Dmaven.repo.local=$ROOT/.m2repo -Dfile.encoding=UTF-8"
export NPM_CONFIG_CACHE="$ROOT/.npm-cache"
export PNPM_STORE_DIR="$ROOT/.pnpm-store"
export PNPM_HOME="$ROOT/.pnpm-home"
export PLAYWRIGHT_BROWSERS_PATH="$ROOT/.playwright"
# 跨平台本地演示库目录（demo-local.sh 使用；macOS 建议用系统 PG 或 Docker，见 docs/guides/cross-platform.md）
export ANESOP_DEMO_PG_DIR="${ANESOP_DEMO_PG_DIR:-$ROOT/.cache/demo-pg}"
mkdir -p "$ROOT/.m2repo" "$ROOT/.npm-cache" "$ROOT/.pnpm-store" "$ROOT/.pnpm-home" \
  "$ROOT/.pnpm-cache" "$ROOT/.playwright"
echo "ANESOP env: JAVA_HOME=${JAVA_HOME:-<system>}  MAVEN_REPO=$ROOT/.m2repo"
java -version 2>&1 | head -1
