#!/usr/bin/env bash
# 统一 Maven 调用：优先仓库内 JDK21/Maven（Linux），macOS 等平台回退系统工具链。
# 用法：./scripts/mvn.sh -B -f apps/api/pom.xml verify
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

BUNDLED_JAVA="$ROOT/.toolchain/jdk21/bin/java"
if [ -x "$BUNDLED_JAVA" ] && "$BUNDLED_JAVA" -version >/dev/null 2>&1; then
  export JAVA_HOME="$ROOT/.toolchain/jdk21"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  : # 使用系统 JAVA_HOME（macOS 预期路径）
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
  # 解析真实 java 所在目录（处理 /usr/bin/java -> /Library/Java/... 的软链）
  while [ -L "$JAVA_BIN" ]; do JAVA_BIN="$(readlink "$JAVA_BIN")"; done
  export JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
else
  echo "mvn.sh: [ERROR] 未找到 JDK 21；Linux 请恢复 .toolchain/jdk21，macOS 请安装 openjdk@21 并设置 JAVA_HOME" >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

MVN="mvn"
if [ -x "$ROOT/.toolchain/maven/bin/mvn" ]; then
  export PATH="$ROOT/.toolchain/maven/bin:$PATH"
fi
command -v mvn >/dev/null 2>&1 || {
  echo "mvn.sh: [ERROR] 未找到 mvn；请安装 Maven 3.9+（macOS: brew install maven）" >&2
  exit 1
}

# -Danesop.root 供根 pom 定位 config/checkstyle 与 config/spotbugs 规则文件
exec "$MVN" -Dmaven.repo.local="$ROOT/.m2repo" -Dfile.encoding=UTF-8 -Danesop.root="$ROOT" "$@"
