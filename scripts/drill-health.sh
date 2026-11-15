#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 服务健康检查演练（M6-M20-05）
#
# 用途：上线前/演练时按同一口径检查"入口、应用、数据库、TLS 证书、时钟"，
# 输出逐项 PASS/FAIL/SKIP，可写入演练记录。**不启动/不重启任何服务**。
#
# 三种用法：
#   bash scripts/drill-health.sh --dry-run
#       只打印检查计划，不发起任何网络请求。
#   bash scripts/drill-health.sh
#       无地址时做离线检查（compose 静态校验、时钟、证书文件），实时项标 SKIP；
#       本机 Docker 不可用（EXT-08）时这是预期基线。
#   bash scripts/drill-health.sh --api-url http://127.0.0.1:8080 \
#        --web-url https://anesop.example.org --db-url jdbc:postgresql://127.0.0.1:5432/anesop
#       对真实部署做实时检查；任一项 FAIL 退出 1。
#
# 参数：
#   --api-url URL       API 基址（自动追加 /actuator/health）
#   --web-url URL       Web 基址（自动追加 /healthz；https 时顺带检查证书）
#   --db-url JDBC       jdbc:postgresql://host[:port]/db（只做 TCP 可达性）
#   --timeout SEC       单次请求/连接超时（默认 5）
#   --min-cert-days N   证书剩余天数下限（默认 30）
#   --record FILE       把结果写成 Markdown（可配合 docs/deploy/DRILL-RECORD-TEMPLATE.md）
#   --skip-static       跳过 compose 静态校验（CI 已单独跑过时可省时间）
#   --dry-run           只打印计划
#   --help              显示本帮助
#
# 退出码：0 全部 PASS 或仅有 SKIP；1 存在 FAIL；2 参数错误；3 缺少依赖命令。
# 安全：只读检查；不打印口令/令牌；不写入业务库。
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

API_URL="${ANESOP_API_URL:-}"
WEB_URL="${ANESOP_WEB_URL:-}"
DB_URL="${ANESOP_DB_URL:-}"
TIMEOUT=5
MIN_CERT_DAYS=30
RECORD=""
SKIP_STATIC=0
DRY_RUN=0

PASS=0
FAIL=0
SKIP=0
declare -a LINES=()

usage() {
  awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"
}

die() {
  local code="$1"
  shift
  printf 'drill-health: %s\n' "$*" >&2
  exit "$code"
}

pass() { PASS=$((PASS + 1)); printf '[PASS] %s\n' "$1"; LINES+=("| $2 | PASS | $1 |"); }
fail() { FAIL=$((FAIL + 1)); printf '[FAIL] %s\n' "$1"; LINES+=("| $2 | FAIL | $1 |"); }
skip() { SKIP=$((SKIP + 1)); printf '[SKIP] %s\n' "$1"; LINES+=("| $2 | SKIP | $1 |"); }

while [ $# -gt 0 ]; do
  case "$1" in
    --api-url) [ $# -ge 2 ] || die 2 "缺少 --api-url 的值"; API_URL="$2"; shift 2 ;;
    --api-url=*) API_URL="${1#*=}"; shift ;;
    --web-url) [ $# -ge 2 ] || die 2 "缺少 --web-url 的值"; WEB_URL="$2"; shift 2 ;;
    --web-url=*) WEB_URL="${1#*=}"; shift ;;
    --db-url) [ $# -ge 2 ] || die 2 "缺少 --db-url 的值"; DB_URL="$2"; shift 2 ;;
    --db-url=*) DB_URL="${1#*=}"; shift ;;
    --timeout) [ $# -ge 2 ] || die 2 "缺少 --timeout 的值"; TIMEOUT="$2"; shift 2 ;;
    --timeout=*) TIMEOUT="${1#*=}"; shift ;;
    --min-cert-days) [ $# -ge 2 ] || die 2 "缺少 --min-cert-days 的值"; MIN_CERT_DAYS="$2"; shift 2 ;;
    --min-cert-days=*) MIN_CERT_DAYS="${1#*=}"; shift ;;
    --record) [ $# -ge 2 ] || die 2 "缺少 --record 的值"; RECORD="$2"; shift 2 ;;
    --record=*) RECORD="${1#*=}"; shift ;;
    --skip-static) SKIP_STATIC=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die 2 "未知参数：$1（用 --help 查看用法）" ;;
  esac
done

if [ "$DRY_RUN" -eq 1 ]; then
  cat <<EOF
drill-health（dry-run，不发请求）
  compose 静态校验 : $([ "$SKIP_STATIC" -eq 1 ] && echo 跳过 || echo "scripts/compose-check.sh")
  API 健康         : ${API_URL:-<未提供，实时检查将 SKIP>} → /actuator/health
  Web 健康         : ${WEB_URL:-<未提供，实时检查将 SKIP>} → /healthz
  TLS 证书         : https 时检查剩余 ≥ $MIN_CERT_DAYS 天（openssl s_client）
  数据库           : ${DB_URL:-<未提供，实时检查将 SKIP>}（仅 TCP）
  时钟             : timedatectl 可用时检查 NTP 同步
  记录             : ${RECORD:-<仅标准输出>}
EOF
  exit 0
fi

# ---------------------------------------------------------------------------
# 静态检查：compose 清单 + 时钟 + 证书文件
# ---------------------------------------------------------------------------
if [ "$SKIP_STATIC" -eq 1 ]; then
  skip "compose 静态校验（--skip-static）" "静态"
elif [ -f "$ROOT/scripts/compose-check.sh" ]; then
  if bash "$ROOT/scripts/compose-check.sh" >/tmp/anesop-drill-compose.log 2>&1; then
    pass "compose 静态校验通过（scripts/compose-check.sh）" "静态"
  else
    fail "compose 静态校验失败：见 /tmp/anesop-drill-compose.log" "静态"
  fi
else
  fail "缺少 scripts/compose-check.sh" "静态"
fi

if command -v timedatectl >/dev/null 2>&1; then
  if timedatectl show -p NTPSynchronized --value 2>/dev/null | grep -qi '^yes$'; then
    pass "系统时钟已 NTP 同步（timedatectl）" "时钟"
  else
    fail "系统时钟未确认 NTP 同步：审计与事件时间依赖时钟一致" "时钟"
  fi
else
  skip "timedatectl 不可用，无法检查 NTP（记录 date -u：$(date -u '+%Y-%m-%dT%H:%M:%SZ')）" "时钟"
fi

# ---------------------------------------------------------------------------
# 实时检查
# ---------------------------------------------------------------------------
if [ -n "$API_URL" ] || [ -n "$WEB_URL" ]; then
  command -v curl >/dev/null 2>&1 || die 3 "缺少 curl，无法做实时 HTTP 检查"
fi

if [ -z "$API_URL" ]; then
  skip "API 健康检查（未提供 --api-url）" "应用"
else
  url="${API_URL%/}"
  case "$url" in
    */actuator/health) ;;
    *) url="$url/actuator/health" ;;
  esac
  body="$(mktemp)"
  code="$(curl -sS -o "$body" -w '%{http_code}' --max-time "$TIMEOUT" "$url" 2>/dev/null || true)"
  if [ "$code" = "200" ] && grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$body"; then
    pass "API health UP（$url）" "应用"
  else
    fail "API health 非 UP（HTTP ${code:-000}，$url）" "应用"
  fi
  rm -f "$body"
fi

if [ -z "$WEB_URL" ]; then
  skip "Web 健康检查（未提供 --web-url）" "入口"
else
  url="${WEB_URL%/}"
  case "$url" in
    */healthz) ;;
    *) url="$url/healthz" ;;
  esac
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time "$TIMEOUT" "$url" 2>/dev/null || true)"
  if [ "$code" = "200" ]; then
    pass "Web /healthz 200（$url）" "入口"
  else
    fail "Web /healthz 非 200（HTTP ${code:-000}，$url）" "入口"
  fi

  case "$WEB_URL" in
    https://*)
      if ! command -v openssl >/dev/null 2>&1; then
        skip "TLS 证书检查（缺少 openssl）" "证书"
      else
        hostport="${WEB_URL#https://}"
        hostport="${hostport%%/*}"
        host="${hostport%%:*}"
        port="${hostport##*:}"
        [ "$port" = "$hostport" ] && port=443
        if openssl s_client -connect "$host:$port" -servername "$host" </dev/null 2>/dev/null |
          openssl x509 -noout -checkend $((MIN_CERT_DAYS * 86400)) >/dev/null 2>&1; then
          pass "TLS 证书剩余 ≥ ${MIN_CERT_DAYS} 天（$host:$port）" "证书"
        else
          fail "TLS 证书缺失/校验失败/剩余 < ${MIN_CERT_DAYS} 天（$host:$port）" "证书"
        fi
      fi
      ;;
    http://*)
      skip "TLS 证书检查（--web-url 使用 http，生产必须 https）" "证书"
      ;;
  esac
fi

if [ -z "$DB_URL" ]; then
  skip "数据库可达性（未提供 --db-url）" "数据"
else
  case "$DB_URL" in
    jdbc:postgresql://*) ;;
    *) die 2 "--db-url 必须以 jdbc:postgresql:// 开头" ;;
  esac
  if [[ "$DB_URL" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^?]+) ]]; then
    db_host="${BASH_REMATCH[1]}"
    db_port="${BASH_REMATCH[3]:-5432}"
    if timeout "$TIMEOUT" bash -c "exec 3<>/dev/tcp/$db_host/$db_port" 2>/dev/null; then
      pass "PostgreSQL TCP 可达（$db_host:$db_port）" "数据"
    else
      fail "PostgreSQL TCP 不可达（$db_host:$db_port）" "数据"
    fi
  else
    fail "无法解析 --db-url：$DB_URL" "数据"
  fi
fi

# ---------------------------------------------------------------------------
# 汇总与可选记录
# ---------------------------------------------------------------------------
printf '%s\n' '------------------------------------------------------------------------'
printf 'drill-health: PASS=%d FAIL=%d SKIP=%d\n' "$PASS" "$FAIL" "$SKIP"

if [ -n "$RECORD" ]; then
  mkdir -p "$(dirname "$RECORD")"
  {
    echo "# 健康检查演练记录（scripts/drill-health.sh）"
    echo
    echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "- 主机：$(hostname 2>/dev/null || echo unknown)"
    echo "- API：${API_URL:-（未提供）}；Web：${WEB_URL:-（未提供）}；DB：${DB_URL:-（未提供）}"
    echo
    echo "| 类别 | 结果 | 说明 |"
    echo "|---|---|---|"
    printf '%s\n' "${LINES[@]}"
    echo
    echo "> 本记录由脚本生成；请按 docs/deploy/DRILL-RECORD-TEMPLATE.md 补齐参与人、"
    echo "> 发现的问题与整改、签字。EXT-08（Docker 不可用）导致的 SKIP 须注明解除条件。"
  } >"$RECORD"
  echo "记录已写入：$RECORD"
fi

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
