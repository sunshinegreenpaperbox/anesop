#!/usr/bin/env bash
# 在仓库根 source 本文件，统一工具链与本地缓存（home 只读，所有缓存必须落在仓库内）
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANESOP_ROOT="$ROOT"
export JAVA_HOME="$ROOT/.toolchain/jdk21"
export PATH="$JAVA_HOME/bin:$ROOT/.toolchain/maven/bin:$PATH"
export MAVEN_OPTS="-Dmaven.repo.local=$ROOT/.m2repo -Dfile.encoding=UTF-8"
export NPM_CONFIG_CACHE="$ROOT/.npm-cache"
export PNPM_STORE_DIR="$ROOT/.pnpm-store"
export PNPM_HOME="$ROOT/.pnpm-home"
export PLAYWRIGHT_BROWSERS_PATH="$ROOT/.playwright"
mkdir -p "$ROOT/.m2repo" "$ROOT/.npm-cache" "$ROOT/.pnpm-store" "$ROOT/.pnpm-home" "$ROOT/.pnpm-cache" "$ROOT/.playwright"
echo "ANESOP env: JAVA_HOME=$JAVA_HOME  MAVEN_REPO=$ROOT/.m2repo"
java -version 2>&1 | head -1
