#!/usr/bin/env bash
# 统一 Maven 调用：JDK 21 + 仓库内本地仓库（避免写 ~/.m2）
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$ROOT/.toolchain/jdk21"
export PATH="$JAVA_HOME/bin:$PATH"
# -Danesop.root 供根 pom 定位 config/checkstyle 与 config/spotbugs 规则文件
exec mvn -Dmaven.repo.local="$ROOT/.m2repo" -Dfile.encoding=UTF-8 -Danesop.root="$ROOT" "$@"
