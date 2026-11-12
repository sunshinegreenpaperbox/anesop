#!/usr/bin/env bash
# 统一 Maven 调用：JDK 21 + 仓库内本地仓库（避免写 ~/.m2）
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$ROOT/.toolchain/jdk21"
export PATH="$JAVA_HOME/bin:$PATH"
exec mvn -Dmaven.repo.local="$ROOT/.m2repo" -Dfile.encoding=UTF-8 "$@"
