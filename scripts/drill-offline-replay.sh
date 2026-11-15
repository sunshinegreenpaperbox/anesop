#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 采集断网补传演练（M6-M20-05）
#
# 做什么：模拟采集网关在断网期间缓存了一批体征（带 ts:BACKFILL 标记的补传帧），
# 恢复网络后把**同一批次**上报两次，验证平台按 (gatewayId, batchKey) 幂等：
#   第一次  → HTTP 202，duplicateBatch=false，acceptedRows ≥ 1；
#   第二次  → HTTP 202，duplicateBatch=true，acceptedRows=0（不重复入库）。
# 只使用 SYNTHETIC 数值，不含任何患者标识；不写真实数据。
#
# 前置：
#   1) API 已启动且配置了 ANESOP_M2_ACQUISITION_GATEWAY_TOKEN；
#   2) 目标设备已登记（POST /v1/devices），否则 ingest 返回 404；
#   3) 已安装 curl 与 python3。
#
# 用法（仓库根）：
#   bash scripts/drill-offline-replay.sh --dry-run
#   ANESOP_M2_ACQUISITION_GATEWAY_TOKEN=*** \
#     bash scripts/drill-offline-replay.sh \
#       --base-url http://127.0.0.1:8080 \
#       --gateway-id GW-DRILL-01 --device-id SIM-DRILL-01 \
#       --frames 10 \
#       --record docs/deploy/drills/2026-11-15-offline-replay.md
#   bash scripts/drill-offline-replay.sh --help
#
# 参数：
#   --base-url URL         API 基址（未提供时自动 dry-run）
#   --gateway-token TOKEN  内部网关令牌；优先读 ANESOP_M2_ACQUISITION_GATEWAY_TOKEN
#   --gateway-id ID        默认 GW-DRILL-01
#   --device-id ID         默认 SIM-DRILL-01
#   --frames N             本批帧数（1–500，默认 10）
#   --batch-key KEY        幂等键（默认按时间生成；重放必须复用同一个）
#   --record FILE          生成 Markdown 演练记录
#   --dry-run              只打印计划
#   --help                 显示帮助
#
# 退出码：0 幂等验证通过/dry-run；1 断言失败；2 参数错误；3 缺少 curl/python3。
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BASE_URL="${ANESOP_API_URL:-}"
TOKEN="${ANESOP_M2_ACQUISITION_GATEWAY_TOKEN:-}"
GATEWAY_ID="GW-DRILL-01"
DEVICE_ID="SIM-DRILL-01"
FRAMES=10
BATCH_KEY=""
RECORD=""
DRY_RUN=0

usage() {
  awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"
}

die() {
  local code="$1"
  shift
  printf 'drill-offline-replay: %s\n' "$*" >&2
  exit "$code"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --base-url) [ $# -ge 2 ] || die 2 "缺少 --base-url 的值"; BASE_URL="$2"; shift 2 ;;
    --base-url=*) BASE_URL="${1#*=}"; shift ;;
    --gateway-token) [ $# -ge 2 ] || die 2 "缺少 --gateway-token 的值"; TOKEN="$2"; shift 2 ;;
    --gateway-token=*) TOKEN="${1#*=}"; shift ;;
    --gateway-id) [ $# -ge 2 ] || die 2 "缺少 --gateway-id 的值"; GATEWAY_ID="$2"; shift 2 ;;
    --gateway-id=*) GATEWAY_ID="${1#*=}"; shift ;;
    --device-id) [ $# -ge 2 ] || die 2 "缺少 --device-id 的值"; DEVICE_ID="$2"; shift 2 ;;
    --device-id=*) DEVICE_ID="${1#*=}"; shift ;;
    --frames) [ $# -ge 2 ] || die 2 "缺少 --frames 的值"; FRAMES="$2"; shift 2 ;;
    --frames=*) FRAMES="${1#*=}"; shift ;;
    --batch-key) [ $# -ge 2 ] || die 2 "缺少 --batch-key 的值"; BATCH_KEY="$2"; shift 2 ;;
    --batch-key=*) BATCH_KEY="${1#*=}"; shift ;;
    --record) [ $# -ge 2 ] || die 2 "缺少 --record 的值"; RECORD="$2"; shift 2 ;;
    --record=*) RECORD="${1#*=}"; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die 2 "未知参数：$1（用 --help 查看用法）" ;;
  esac
done

[[ "$FRAMES" =~ ^[0-9]+$ ]] && [ "$FRAMES" -ge 1 ] && [ "$FRAMES" -le 500 ] ||
  die 2 "--frames 必须是 1–500 的整数"
[ -n "$BATCH_KEY" ] || BATCH_KEY="DRILL-${GATEWAY_ID}-${DEVICE_ID}-$(date +%s%N)-${FRAMES}"

if [ -z "$BASE_URL" ] || [ "$DRY_RUN" -eq 1 ]; then
  cat <<EOF
drill-offline-replay（dry-run，不发请求）
  目标 API     : ${BASE_URL:-<未提供 --base-url>}
  网关/设备    : $GATEWAY_ID / $DEVICE_ID
  批次键       : $BATCH_KEY
  帧数         : $FRAMES（SYNTHETIC；含 ts:BACKFILL 补传标记）
  端点         : POST /v1/devices/$GATEWAY_ID/$DEVICE_ID/ingest
  认证         : X-Gateway-Token（环境变量 ANESOP_M2_ACQUISITION_GATEWAY_TOKEN）
  验证口径     : 第 1 次 202/duplicateBatch=false/acceptedRows≥1；
                 第 2 次（同批重放）202/duplicateBatch=true/acceptedRows=0
  前置         : 设备已登记；令牌已注入；API 健康
  说明         : 未提供 --base-url 时自动 dry-run；真实执行需可达 API 与已登记设备。
EOF
  exit 0
fi

command -v curl >/dev/null 2>&1 || die 3 "缺少 curl"
command -v python3 >/dev/null 2>&1 || die 3 "缺少 python3"
[ -n "$TOKEN" ] || die 2 "缺少网关令牌：设置 ANESOP_M2_ACQUISITION_GATEWAY_TOKEN（不要写进仓库/日志）"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/anesop-drill-replay.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

# 生成 SYNTHETIC 帧：seq 从 9000 起，带补传标记；不含患者字段。
python3 - "$FRAMES" "$BATCH_KEY" >"$TMP_DIR/payload.json" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone

frames_n = int(sys.argv[1])
batch_key = sys.argv[2]
now = datetime.now(timezone.utc).astimezone(timezone(timedelta(hours=8)))
frames = []
for i in range(frames_n):
    measured = now - timedelta(seconds=(frames_n - i) * 5)
    frames.append({
        "seq": 9000 + i,
        "measuredAt": measured.isoformat(timespec="seconds"),
        "measuredAtCorrected": measured.isoformat(timespec="seconds"),
        "receivedAt": now.isoformat(timespec="seconds"),
        "hr": 60 + (i % 20),
        "spo2": 96 + (i % 4),
        "nibpSys": 110 + (i % 15),
        "nibpDia": 70 + (i % 10),
        "etco2": 34 + (i % 5),
        "temp": 36.5,
        "quality": 0,
        "flags": ["ts:BACKFILL:-600000"],
        "source": "AUTO",
    })
print(json.dumps({"batchKey": batch_key, "frames": frames}))
PY

post_once() {
  local out="$1"
  curl -sS -o "$out" -w '%{http_code}' --max-time 10 \
    -X POST "$BASE_URL/v1/devices/$GATEWAY_ID/$DEVICE_ID/ingest" \
    -H "X-Gateway-Token: $TOKEN" \
    -H 'Content-Type: application/json' \
    --data-binary @"$TMP_DIR/payload.json" 2>/dev/null || true
}

field() {
  python3 - "$1" "$2" <<'PY'
import json
import sys

try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    print("NA")
    raise SystemExit(0)
value = data.get(sys.argv[2], "NA")
print(str(value).lower() if isinstance(value, bool) else value)
PY
}

START_TS="$(date +%s)"
echo "drill-offline-replay: 第 1 次上报（模拟断网恢复后的补传）…"
CODE1="$(post_once "$TMP_DIR/first.json")"
DUP1="$(field "$TMP_DIR/first.json" duplicateBatch)"
ACC1="$(field "$TMP_DIR/first.json" acceptedRows)"
echo "  HTTP $CODE1  duplicateBatch=$DUP1  acceptedRows=$ACC1"

case "$CODE1" in
  202) ;;
  401) die 1 "401：网关令牌不匹配或未配置（fail-closed），检查 ANESOP_M2_ACQUISITION_GATEWAY_TOKEN" ;;
  404) die 1 "404：设备未登记。先 POST /v1/devices 登记 $GATEWAY_ID/$DEVICE_ID 再演练" ;;
  *) die 1 "第 1 次上报未返回 202（HTTP $CODE1），见响应 $TMP_DIR/first.json" ;;
esac
[ "$DUP1" = "false" ] || die 1 "第 1 次 duplicateBatch=$DUP1（期望 false）：批次键可能被复用"
[ "$ACC1" != "NA" ] && [ "$ACC1" -ge 1 ] 2>/dev/null || die 1 "第 1 次 acceptedRows=$ACC1（期望 ≥1）"

echo "drill-offline-replay: 第 2 次重放同一批次（验证幂等，不重复入库）…"
CODE2="$(post_once "$TMP_DIR/second.json")"
DUP2="$(field "$TMP_DIR/second.json" duplicateBatch)"
ACC2="$(field "$TMP_DIR/second.json" acceptedRows)"
echo "  HTTP $CODE2  duplicateBatch=$DUP2  acceptedRows=$ACC2"

[ "$CODE2" = "202" ] || die 1 "第 2 次上报未返回 202（HTTP $CODE2）"
[ "$DUP2" = "true" ] || die 1 "第 2 次 duplicateBatch=$DUP2（期望 true）：幂等失效，存在重复入库风险"
[ "$ACC2" = "0" ] || die 1 "第 2 次 acceptedRows=$ACC2（期望 0）：重复批次写入了新行"

END_TS="$(date +%s)"
ELAPSED=$((END_TS - START_TS))
echo "drill-offline-replay: PASS（同一批次重放不重复入库；耗时 ${ELAPSED}s）"

if [ -n "$RECORD" ]; then
  mkdir -p "$(dirname "$RECORD")"
  {
    echo "# 采集断网补传演练记录（scripts/drill-offline-replay.sh）"
    echo
    echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "- 目标 API：$BASE_URL"
    echo "- 网关/设备：$GATEWAY_ID / $DEVICE_ID"
    echo "- 批次键：$BATCH_KEY（帧数 $FRAMES，含 ts:BACKFILL 标记）"
    echo "- 第 1 次：HTTP $CODE1，duplicateBatch=$DUP1，acceptedRows=$ACC1"
    echo "- 第 2 次：HTTP $CODE2，duplicateBatch=$DUP2，acceptedRows=$ACC2"
    echo "- 结论：PASS（幂等生效，重复批次 acceptedRows=0）"
    echo "- 耗时：${ELAPSED}s"
    echo
    echo "> 请按 docs/deploy/DRILL-RECORD-TEMPLATE.md 补齐参与人、问题与整改、签字；"
    echo "> 本演练只使用 SYNTHETIC 数值，不含患者标识。"
  } >"$RECORD"
  echo "记录已写入：$RECORD"
fi

exit 0
