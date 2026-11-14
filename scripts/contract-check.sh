#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 契约门禁（S0-05）
#   1) spectral lint：OpenAPI 3.1 结构、operationId/tags、安全、示例、项目规则
#   2) 破坏性变更：baseline 快照 → 当前契约（oasdiff breaking），失败即非 0
#   3) 生成物漂移：重跑 gen:ts，git diff 检查 generated/typescript 是否同步
#
# 用法：
#   bash scripts/contract-check.sh                 # lint + breaking（含 HEAD baseline）+ 漂移
#   bash scripts/contract-check.sh --breaking-only # 仅 breaking（含 HEAD baseline；供 pnpm check:breaking）
#   bash scripts/contract-check.sh --lint-only     # 仅 lint
#
# 基线更新流程：契约经人工评审通过且确认兼容后，将当前 yaml 复制到
# packages/contracts/openapi/baseline/，并在同一 PR 说明中写明版本与评审结论；
# 本脚本不会自动改基线。
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# shellcheck source=scripts/env.sh
source "$ROOT/scripts/env.sh" >/dev/null

CONTRACTS_DIR="$ROOT/packages/contracts"
DOMAINS=(auth patient scheduling anesthesia device drug)
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
drift_result="跳过"

hr() { printf '%s\n' "------------------------------------------------------------"; }

if [ "$MODE" != "breaking" ]; then
  hr
  echo "[contract-check] 1/3 spectral lint（OpenAPI 3.1）"
  if pnpm -C "$CONTRACTS_DIR" lint; then
    lint_result="PASS（0 error / 0 warning）"
  else
    lint_result="FAIL"
    fail=1
  fi
fi

if [ "$MODE" != "lint" ]; then
  hr
  echo "[contract-check] 2/4 破坏性变更检测（baseline → 当前）"
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
    breaking_result="PASS（6 域均无破坏性变更）"
  fi
fi

# ADR-0011：评审纪律要求用“上个已发布 baseline（HEAD 版本）”复跑，防止
# 同一未评审变更里覆盖 baseline 自证兼容。此处自动执行，git 不可用/首次提交时跳过。
if [ "$MODE" != "lint" ] && git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  hr
  echo "[contract-check] 3/4 HEAD baseline 兼容检查（ADR-0011）"
  head_ok=0
  head_skip=0
  for domain in "${DOMAINS[@]}"; do
    current="$CONTRACTS_DIR/openapi/${domain}.yaml"
    [ -f "$current" ] || continue
    if ! git -C "$ROOT" cat-file -e "HEAD:packages/contracts/openapi/baseline/${domain}.yaml" 2>/dev/null; then
      head_skip=1
      continue
    fi
    tmp="$(mktemp)"
    git -C "$ROOT" show "HEAD:packages/contracts/openapi/baseline/${domain}.yaml" > "$tmp"
    if out="$(cd "$CONTRACTS_DIR" && pnpm exec oasdiff breaking "$tmp" "openapi/${domain}.yaml" --fail-on WARN 2>&1)"; then
      head_ok=$((head_ok + 1))
    else
      echo "  FAIL ${domain}：相对 HEAD baseline 存在破坏性变更（ADR-0011）"
      printf '%s\n' "$out" | sed 's/^/    /'
      fail=1
    fi
    rm -f "$tmp"
  done
  if [ "$fail" -eq 0 ]; then
    if [ "$head_skip" -eq 1 ] && [ "$head_ok" -eq 0 ]; then
      echo "  SKIP：HEAD 中无 baseline 快照（首次落地）"
    else
      echo "  PASS：${head_ok} 域相对 HEAD baseline 无破坏性变更"
    fi
  fi
fi

if [ "$MODE" = "all" ]; then
  hr
  echo "[contract-check] 4/4 生成物漂移校验（重新生成 → git diff）"
  if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    # 对比“重生成前/后”的 git diff：干净树上等价于 git diff --exit-code；
    # 工作区已有未提交契约改动时，可区分“生成物已同步”与“生成物漂移”。
    diff_before="$(git -C "$ROOT" diff -- packages/contracts/generated/typescript)"
    if pnpm -C "$CONTRACTS_DIR" gen:ts >/dev/null; then
      drift=0
      if [ -z "$diff_before" ]; then
        # 干净树：直接使用 git diff --exit-code 判定生成物是否漂移。
        git -C "$ROOT" diff --exit-code -- packages/contracts/generated/typescript >/dev/null 2>&1 || drift=1
      else
        # 工作区已有未提交契约改动：对比重生成前后的 diff，仅当生成物发生变化才算漂移。
        diff_after="$(git -C "$ROOT" diff -- packages/contracts/generated/typescript)"
        [ "$diff_before" = "$diff_after" ] || drift=1
      fi
      if [ "$drift" -eq 0 ]; then
        echo "  PASS：generated/typescript 与 6 份契约一致"
        drift_result="PASS"
      else
        echo "  FAIL：generated/typescript 与契约不一致（重生成产生了文件变化）"
        git -C "$ROOT" diff --stat -- packages/contracts/generated/typescript | sed 's/^/    /'
        echo "        请运行 bash scripts/gen-contracts.sh 并提交生成物后再提交 PR。"
        drift_result="FAIL"
        fail=1
      fi
    else
      echo "  FAIL：类型重新生成失败"
      drift_result="FAIL"
      fail=1
    fi
  else
    echo "  [skip] git 不可用或当前目录不在仓库内，跳过生成物漂移校验"
    drift_result="跳过（git 不可用或无仓库）"
  fi
fi

hr
echo "[contract-check] 摘要：lint=${lint_result}；breaking=${breaking_result}；drift=${drift_result}"
if [ "$fail" -ne 0 ]; then
  echo "[contract-check] FAILED" >&2
  exit 1
fi
echo "[contract-check] OK"
