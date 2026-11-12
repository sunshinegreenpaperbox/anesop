#!/usr/bin/env bash
# 启用仓库级 git 钩子（home 只读，所有配置一律 --local）
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ ! -d .git ]; then
  echo "install-hooks.sh: 当前目录不是 git 仓库根（$ROOT）" >&2
  exit 1
fi

git config --local core.hooksPath .githooks
chmod +x .githooks/commit-msg
[ -f .githooks/pre-commit ] && chmod +x .githooks/pre-commit
[ -f .githooks/pre-push ] && chmod +x .githooks/pre-push

echo "install-hooks.sh: OK"
echo "  core.hooksPath = $(git config --local --get core.hooksPath)"
echo "  已启用钩子：$(ls -1 .githooks | tr '\n' ' ')"
echo "  自检：echo 'bad message' | npx --no-install commitlint  应失败"
