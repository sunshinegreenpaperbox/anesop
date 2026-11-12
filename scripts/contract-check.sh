#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 契约门禁（S0-05）
#   1) spectral lint：OpenAPI 3.1 结构、operationId/tags、安全、示例、项目规则
#   2) 破坏性变更：baseline 快照 → 当前契约（oasdiff breaking），失败即非 0
#
# 用法：
#   bash scripts/contract-check.sh                 # lint + breaking
#   bash scripts/contract-check.sh --breaking-only # 仅 breaking（供 pnpm check:breaking）
#   bash scripts/contract-check.sh --lint-only     # 仅 lint
#
# 基线更新流程：契约评审通过且确认兼容后，人工将当前 yaml 复制到
# packages/contracts/openapi/baseline/ 并随 PR 说明；本脚本不会自动改基线。
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# shellcheck source=scripts/env.sh
source "$ROOT/scripts/env.sh" >/dev/null

CONTRACTS_DIR="$ROOT/packages/contracts"
DOMAINS=(auth patient scheduling anesthesia)
MODE="all"

for arg in "$@"; do
  case "$arg" in
    --breaking-only) MODE="breaking" ;;
    --lint-only) MODE="lint" ;;
    -h | --help)
      sed -n '2,18p' "$0"
      exit 0
      ;;
    *)
      echo "contract-check.sh: 未知参数 $arg" >&2
      exit 2
      ;;
  esac
done

fail=0
lint_result="跳过"
breaking_result="跳过"

hr() { printf '%s\n' "------------------------------------------------------------"; }

if [ "$MODE" != "breaking" ]; then
  hr
  echo "[contract-check] 1/2 spectral lint（OpenAPI 3.1）"
  if pnpm -C "$CONTRACTS_DIR" lint; then
    lint_result="PASS（0 error）"
  else
    lint_result="FAIL"
    fail=1
  fi
fi

if [ "$MODE" != "lint" ]; then
  hr
  echo "[contract-check] 2/2 破坏性变更检测（baseline → 当前）"
  for domain in "${DOMAINS[@]}"; do
    current="$CONTRACTS_DIR/openapi/${domain}.yaml"
    baseline="$CONTRACTS_DIR/openapi/baseline/${domain}.yaml"

    if [ ! -f "$current" ]; then
      echo "  FAIL ${domain}：缺少当前契约 packages/contracts/openapi/${domain}.yaml"
      breaking_result="FAIL"
      fail=1
      continue
    fi
    if [ ! -f "$baseline" ]; then
      echo "  FAIL ${domain}：缺少基线 packages/contracts/openapi/baseline/${domain}.yaml"
      echo "        首次落地或确认兼容后，将当前契约复制到 baseline/ 并随 PR 评审。"
      breaking_result="FAIL"
      fail=1
      continue
    fi

    if out="$(cd "$CONTRACTS_DIR" && pnpm exec oasdiff breaking "openapi/baseline/${domain}.yaml" "openapi/${domain}.yaml" --fail-on WARN 2>&1)"; then
      echo "  PASS ${domain}：无破坏性变更"
    else
      echo "  FAIL ${domain}：检测到破坏性变更"
      printf '%s\n' "$out" | sed 's/^/    /'
      breaking_result="FAIL"
      fail=1
    fi
  done
  if [ "$breaking_result" != "FAIL" ]; then
    breaking_result="PASS（4 域均无破坏性变更）"
  fi
fi

hr
echo "[contract-check] 摘要：lint=${lint_result}；breaking=${breaking_result}"
if [ "$fail" -ne 0 ]; then
  echo "[contract-check] FAILED" >&2
  exit 1
fi
echo "[contract-check] OK"
