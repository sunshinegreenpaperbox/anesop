#!/usr/bin/env bash
# 本地门禁：后端编译/测试 + 前端 lint/typecheck/单测 + 契约
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [ -f apps/api/pom.xml ]; then
  ./scripts/mvn.sh -B -f apps/api/pom.xml verify
fi
if [ -f package.json ]; then
  pnpm install --frozen-lockfile
  pnpm lint
  pnpm -C apps/web exec vue-tsc --noEmit
  pnpm test:unit --run
fi
if ls packages/contracts/openapi/*.yaml >/dev/null 2>&1; then
  npx --yes @stoplight/spectral-cli lint packages/contracts/openapi/*.yaml
fi
echo "check.sh: OK"
