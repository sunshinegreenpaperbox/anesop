#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 本地一键门禁（S0-03）
# 顺序：后端 verify（含 Checkstyle/SpotBugs）→ 前端依赖 → lint → 类型检查
#       → 单测 → 契约 lint（无契约时跳过）→ 规则检查；最后打印耗时。
# 任一环节失败即以非 0 退出，并标明失败环节（不允许 || true 掩盖）。
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 统一工具链与仓库内缓存（home 只读）
# shellcheck source=scripts/env.sh
source "$ROOT/scripts/env.sh" >/dev/null

CURRENT="初始化"
START_TS="$(date +%s)"

on_error() {
  local rc=$?
  echo
  echo "============================================================"
  echo "check.sh: FAILED —— 失败环节：${CURRENT}（exit=${rc}）"
  echo "============================================================"
  exit "$rc"
}
trap on_error ERR

step() {
  CURRENT="$1"
  echo
  echo "------------------------------------------------------------"
  echo "[check.sh] ${CURRENT}"
  echo "------------------------------------------------------------"
}

# 1. 后端：编译、单测、覆盖率、Checkstyle(validate)、SpotBugs(verify)
step "1/7 后端 mvn verify（含 Checkstyle + SpotBugs）"
./scripts/mvn.sh -B -f apps/api/pom.xml verify

# 2. 前端依赖：lockfile 必须与 package.json 一致
step "2/7 前端依赖安装（pnpm install --frozen-lockfile）"
pnpm install --frozen-lockfile

# 3. 静态检查：ESLint（flat config）+ Prettier
step "3/7 前端静态检查（pnpm lint = ESLint + Prettier）"
pnpm lint

# 4. 类型检查：vue-tsc
step "4/7 前端类型检查（vue-tsc --noEmit）"
pnpm -C apps/web exec vue-tsc --noEmit

# 5. 单元测试：S0-11 落地 Vitest 后自动生效（当前无测试时 pnpm 返回 0）
step "5/7 前端单元测试（pnpm test:unit --run）"
pnpm test:unit --run

# 6. 契约 lint：S0-05 落地 OpenAPI 后自动生效
step "6/7 契约 lint（spectral）"
if compgen -G "packages/contracts/openapi/*.yaml" >/dev/null; then
  npx --yes @stoplight/spectral-cli lint packages/contracts/openapi/*.yaml
else
  echo "跳过：packages/contracts/openapi/ 下暂无 *.yaml（S0-05 落地后自动生效）"
fi

# 7. 规则检查：适配层漂移 + 临时条款过期
step "7/7 规则检查（scripts/check-rules.sh）"
./scripts/check-rules.sh

CURRENT="完成"
END_TS="$(date +%s)"
ELAPSED=$((END_TS - START_TS))

echo
echo "============================================================"
echo "check.sh: OK"
echo "耗时：${ELAPSED}s（$((ELAPSED / 60))m$((ELAPSED % 60))s）"
echo "============================================================"
