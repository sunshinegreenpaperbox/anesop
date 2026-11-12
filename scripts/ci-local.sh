#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 本地 CI 等价执行（S0-04）
#
# 在无 GitHub 远端 / 无 act 的环境下，按 .github/workflows/ci.yml 的 job 口径
# 串行跑本地可执行的门禁，输出每一步的标题、耗时与失败汇总：
#
#   lint          前端 ESLint+Prettier、Checkstyle(validate)、ShellCheck(可选)、规则检查
#   typecheck     vue-tsc --noEmit + Maven compile
#   unit-test     ./scripts/mvn.sh -B verify（JaCoCo 门禁）+ pnpm test:unit（覆盖率）
#   contract      spectral lint + contract-check.sh（oasdiff + 生成物漂移）
#   deps-license  pnpm audit（prod 硬门禁 + 全量例外表）+ pnpm licenses + Maven dependency:analyze
#   sast          Semgrep（不可用则 SKIP）
#   secret-scan   gitleaks（不可用则 SKIP）
#   image-scan    Docker/Trivy（EXT-08，本地按设计不执行任何 docker 命令，SKIP）
#   build         Maven package + pnpm -C apps/web build
#   e2e-smoke     Playwright（浏览器未安装则 SKIP）
#
# 用法：
#   bash scripts/ci-local.sh                 # 全量（6 个主 job + 4 个外部工具 job）
#   bash scripts/ci-local.sh --fast          # 跳过 build 与 e2e-smoke
#   bash scripts/ci-local.sh --job ci/lint   # 只跑一个 job（也接受 lint 简写）
#   bash scripts/ci-local.sh --help
#
# 退出码：0 全部通过（含 SKIP）；1 至少一个 job 失败；2 参数错误。
# 说明：SKIP 只用于"本机工具不可用"（semgrep/gitleaks/trivy/docker/Playwright 浏览器），
#       绝不用于掩盖真实失败；CI 中这些工具由 workflow 的对应 step 执行。
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 统一工具链与仓库内缓存（home 只读）
# shellcheck source=scripts/env.sh
source "$ROOT/scripts/env.sh" >/dev/null

CACHE_DIR="$ROOT/.cache/ci-local"
mkdir -p "$CACHE_DIR"

JOBS=(lint typecheck unit-test contract deps-license sast secret-scan image-scan build e2e-smoke)
FAST=false
ONLY_JOB=""
START_TS="$(date +%s)"
declare -a RESULTS=()
FAILED=0

usage() {
  sed -n '2,34p' "$0"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --fast) FAST=true ;;
    --job)
      [ $# -ge 2 ] || {
        echo "ci-local.sh: --job 需要参数" >&2
        exit 2
      }
      ONLY_JOB="$2"
      shift
      ;;
    --job=*)
      ONLY_JOB="${1#--job=}"
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "ci-local.sh: 未知参数 $1（见 --help）" >&2
      exit 2
      ;;
  esac
  shift
done

if [ -n "$ONLY_JOB" ]; then
  ONLY_JOB="${ONLY_JOB#ci/}"
  known=false
  for job in "${JOBS[@]}"; do
    [ "$job" = "$ONLY_JOB" ] && known=true
  done
  if [ "$known" != true ]; then
    echo "ci-local.sh: 未知 job '$ONLY_JOB'，可选：${JOBS[*]}" >&2
    exit 2
  fi
fi

# 42 = SKIP（本机工具不可用），由 run_job 统一解释；其余非 0 均为失败。
skip() {
  echo "SKIP: $*"
  return 42
}

hr() { printf '%s\n' "============================================================"; }

# ---------------------------------------------------------------------------
# 各 job 实现（与 .github/workflows/ci.yml 的 step 一一对应）
# ---------------------------------------------------------------------------
job_lint() {
  pnpm lint
  ./scripts/mvn.sh -B -DskipTests validate
  if command -v shellcheck >/dev/null 2>&1; then
    shellcheck --severity=error --external-sources scripts/*.sh .githooks/commit-msg
  else
    echo "SKIP: shellcheck 工具不可用（CI 中执行）"
  fi
  bash scripts/check-rules.sh
}

job_typecheck() {
  pnpm -C apps/web exec vue-tsc --noEmit
  ./scripts/mvn.sh -B -DskipTests compile
}

job_unit_test() {
  ./scripts/mvn.sh -B verify
  pnpm test:unit
}

job_contract() {
  pnpm -C packages/contracts lint
  bash scripts/contract-check.sh
}

job_deps_license() {
  # 生产依赖树硬门禁：high/critical 即失败
  pnpm audit --prod --audit-level high --registry=https://registry.npmjs.org

  # 全量审计（含 devDependencies）：捕获 JSON，交由审计例外表判定，避免直接放行
  set +e
  pnpm audit --json --registry=https://registry.npmjs.org >"$CACHE_DIR/npm-audit-full.json"
  local audit_rc=$?
  set -e
  echo "pnpm audit（全量）exit=${audit_rc}（非 0 表示存在漏洞，是否阻断见例外表判定）"
  [ -s "$CACHE_DIR/npm-audit-full.json" ]

  pnpm licenses list --json >"$CACHE_DIR/npm-licenses.json"
  python3 docs/ci/check-deps-license.py \
    --root . \
    --allowlist docs/deps/allowlist.md \
    --npm-licenses "$CACHE_DIR/npm-licenses.json" \
    --audit-json "$CACHE_DIR/npm-audit-full.json" \
    --audit-exceptions docs/ci/audit-exceptions.md

  ./scripts/mvn.sh -B -DskipTests compile dependency:analyze
}

job_sast() {
  if command -v semgrep >/dev/null 2>&1; then
    mkdir -p "$CACHE_DIR/semgrep"
    export SEMGREP_SETTINGS_FILE="$CACHE_DIR/semgrep/settings.yml"
    export SEMGREP_LOG_FILE="$CACHE_DIR/semgrep/semgrep.log"
    export SEMGREP_SEND_METRICS=off
    semgrep scan --config=p/default --severity=ERROR --error --metrics=off \
      --exclude-rule='generic.secrets.security.detected-bcrypt-hash.detected-bcrypt-hash' \
      --exclude='**/target/**' --exclude='**/dist/**' --exclude='**/coverage/**' \
      apps services packages db/migration
  else
    skip "semgrep 工具不可用（CI 中执行）"
  fi
}

job_secret_scan() {
  if command -v gitleaks >/dev/null 2>&1; then
    gitleaks detect --source . --config docs/ci/gitleaks.toml \
      --no-banner --redact --exit-code 1 --verbose
  else
    skip "gitleaks 工具不可用（CI 中执行）"
  fi
}

job_image_scan() {
  # EXT-08：本机无 Docker daemon；按任务约定本地不执行任何 docker 命令。
  skip "Docker/Trivy 工具不可用（EXT-08，CI 中执行）"
}

job_build() {
  ./scripts/mvn.sh -B -DskipTests package
  pnpm -C apps/web build
}

job_e2e_smoke() {
  if compgen -G "$ROOT/.playwright/chromium*" >/dev/null; then
    pnpm e2e
  else
    skip "Playwright 浏览器未安装（本地受限；CI 中执行 playwright install --with-deps chromium）"
  fi
}

run_job() {
  local job="$1"
  local label="ci/$job"
  local start_ts end_ts elapsed rc=0 status
  start_ts="$(date +%s)"
  hr
  echo "[ci-local] ${label}"
  hr
  # 注意：不能写成 `"job_$job" || rc=$?` —— bash 在 || 上下文中会关闭函数体内的
  # set -e，导致函数中途失败仍继续执行。用带 set -e 的子 shell 隔离并捕获退出码。
  set +e
  ( set -e; "job_${job//-/_}" )
  rc=$?
  set -e
  end_ts="$(date +%s)"
  elapsed=$((end_ts - start_ts))
  if [ "$rc" -eq 0 ]; then
    status="PASS"
  elif [ "$rc" -eq 42 ]; then
    status="SKIP"
  else
    status="FAIL"
    FAILED=1
  fi
  RESULTS+=("${status}|${label}|${elapsed}s")
  echo
  echo "[ci-local] ${label}：${status}（${elapsed}s）"
}

selected_jobs() {
  if [ -n "$ONLY_JOB" ]; then
    echo "$ONLY_JOB"
    return
  fi
  for job in "${JOBS[@]}"; do
    if [ "$FAST" = true ] && { [ "$job" = "build" ] || [ "$job" = "e2e-smoke" ]; }; then
      continue
    fi
    echo "$job"
  done
}

echo "ci-local：仓库根 $ROOT"
echo "模式：$([ -n "$ONLY_JOB" ] && echo "单 job ${ONLY_JOB}" || { [ "$FAST" = true ] && echo "--fast（跳过 build/e2e-smoke）" || echo "全量"; })"

# 先把 job 列表读入数组，避免子进程/工具误读 while 的 stdin 导致后续 job 丢失
mapfile -t SELECTED_JOBS < <(selected_jobs)
for job in "${SELECTED_JOBS[@]}"; do
  [ -n "$job" ] || continue
  run_job "$job"
done

END_TS="$(date +%s)"
TOTAL=$((END_TS - START_TS))

hr
echo "ci-local 汇总（总耗时 $((TOTAL / 60))m$((TOTAL % 60))s）"
hr
for row in "${RESULTS[@]}"; do
  IFS='|' read -r status label elapsed <<<"$row"
  printf '  %-4s  %-16s %s\n' "$status" "$label" "$elapsed"
done
echo

if [ "$FAILED" -ne 0 ]; then
  echo "ci-local: FAILED（存在失败 job，见上方输出）" >&2
  exit 1
fi
echo "ci-local: OK（SKIP 项为本地工具不可用，CI 中执行）"
