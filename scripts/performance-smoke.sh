#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# M5-QA-01 性能 / 并发 / 稳定性冒烟（本机基线，可重复）
#
# 依次运行三组用例（真实嵌入式 PostgreSQL 16 + 真实 API）：
#   1) PerformanceBenchmarkTest        关键端点基准（9 项，中位耗时对宽松上限断言）
#   2) ConcurrencyRegressionTest      并发登录/刷新、锁单、事件 seq、报表幂等（4 组）
#   3) DashboardPollingStabilityTest  看板 10s 轮询后端无状态（20 并发 <2s）与“无 SSE”
#
# 输出 `[M5-QA-01]` 证据行并汇总耗时；任一组失败即非 0 退出（不吞错误、不跳过）。
# 本脚本不替代 `./scripts/mvn.sh -B -f apps/api/pom.xml clean verify` 与
# `./scripts/check.sh`：发布前两者仍需单独跑（见 docs/testing/README.md §15）。
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 统一工具链与仓库内缓存（home 只读）
# shellcheck source=scripts/env.sh
source "$ROOT/scripts/env.sh" >/dev/null

LOG_DIR="$ROOT/.cache/performance"
mkdir -p "$LOG_DIR"
START_TS="$(date +%s)"

CURRENT=""
on_error() {
  local rc=$?
  echo
  echo "============================================================"
  echo "[performance-smoke] FAILED —— ${CURRENT}（exit=${rc}）"
  echo "日志：${LOG_DIR}"
  echo "============================================================"
  exit "$rc"
}
trap on_error ERR

run_group() {
  local name="$1"
  local classes="$2"
  CURRENT="$name"
  echo
  echo "------------------------------------------------------------"
  echo "[performance-smoke] ${name}：${classes}"
  echo "------------------------------------------------------------"
  ./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am \
    -Dtest="${classes}" -Dsurefire.failIfNoSpecifiedTests=false test \
    >"${LOG_DIR}/${name}.log" 2>&1 || {
      echo "失败：${name}。最后 40 行："
      tail -40 "${LOG_DIR}/${name}.log"
      return 1
    }
  grep -h "M5-QA-01" "${LOG_DIR}/${name}.log" || true
}

run_group "perf" "PerformanceBenchmarkTest"
run_group "concurrency" "ConcurrencyRegressionTest"
run_group "stability" "DashboardPollingStabilityTest"

CURRENT="汇总"
END_TS="$(date +%s)"
ELAPSED=$((END_TS - START_TS))

# 证据行必须存在：防止“用例被过滤掉但仍 exit 0”的假绿。
EVIDENCE="$(grep -h "M5-QA-01" "$LOG_DIR"/*.log | wc -l | tr -d ' ')"
if [ "${EVIDENCE}" -lt 15 ]; then
  echo "[performance-smoke] 证据行不足（${EVIDENCE} < 15），疑似用例未执行；判失败" >&2
  exit 1
fi

echo
echo "============================================================"
echo "[performance-smoke] OK：9 项基准 + 4 组并发 + 2 条稳定性"
echo "证据行数：${EVIDENCE}；耗时：${ELAPSED}s（$((ELAPSED / 60))m$((ELAPSED % 60))s）"
echo "日志：${LOG_DIR}"
echo "============================================================"
