#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 全模块演示数据灌入（SYNTHETIC，仅本地/演示环境）
#
# 通过真实 REST API 逐环节写入合成数据，覆盖 M1 申请/排程、M2 术前/记录/复苏/
# 随访、M4 药品麻精/人员/耗材/报表/质控、M5 看板与消息中心。所有请求都走业务
# 校验、审计与状态机，不使用 SQL 直插。
#
# 用法（仓库根）：
#   bash scripts/seed-demo-data.sh --dry-run          # 只打印计划
#   bash scripts/seed-demo-data.sh                    # 灌入（默认 http://127.0.0.1:8080）
#   bash scripts/seed-demo-data.sh --base-url http://127.0.0.1:8080
#
# 账号：demo_admin（首次登录自动改密）/ demo_self（仅用于消息中心未读校验）。
# 口令通过环境变量覆盖，默认值只用于本地演示：
#   DEMO_ADMIN_PASSWORD / DEMO_ADMIN_NEW_PASSWORD
#   DEMO_SELF_PASSWORD  / DEMO_SELF_NEW_PASSWORD
#
# 可重复执行：申请/排程/记录/随访/质控等业务数据每次追加（业务单号带批次后缀），
# 药品/人员/耗材等主数据按编码去重（已存在则复用），重复运行不会 5xx。
# 全部为 SYNTHETIC 合成数据；禁止在生产/预发/真实库执行。
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

BASE_URL="${ANESOP_API_BASE_URL:-http://127.0.0.1:8080}"
ADMIN_USER="${DEMO_ADMIN_USERNAME:-demo_admin}"
ADMIN_OLD_PASS="${DEMO_ADMIN_PASSWORD:-Anesop@2026}"
ADMIN_NEW_PASS="${DEMO_ADMIN_NEW_PASSWORD:-Anesop@2026#Demo}"
SELF_USER="${DEMO_SELF_USERNAME:-demo_self}"
SELF_OLD_PASS="${DEMO_SELF_PASSWORD:-Anesop@2026}"
SELF_NEW_PASS="${DEMO_SELF_NEW_PASSWORD:-Anesop@2026#Demo}"

HIS_IMPORT_COUNT="${SEED_HIS_IMPORT_COUNT:-3}"
APP_COUNT="${SEED_APP_COUNT:-15}"
PREOP_COUNT="${SEED_PREOP_COUNT:-6}"
DRUG_COUNT="${SEED_DRUG_COUNT:-5}"
MATERIAL_COUNT="${SEED_MATERIAL_COUNT:-5}"
STAFF_COUNT="${SEED_STAFF_COUNT:-4}"

DRY_RUN=0
TOKEN=""
ADMIN_TOKEN=""
HTTP=""
RESP_FILE=""
OK=0
SKIP=0
FAIL=0
SEC_OK=0
SEC_SKIP=0
SEC_FAIL=0
SEC_NO=0
SECTION=""
LAST_SCHED_ID=""
LAST_SCHED_DAY=""
LAST_SCHED_START=""
LAST_SCHED_END=""
TMP_DIR=""
BATCH=""

# 演示组织/术间/账号固定标识（见 docs/db/seed.md）
DEPT_ANES="f1000000-0000-4000-8000-000000000002"
DEPT_OR="f1000000-0000-4000-8000-000000000003"
SITE_01="f1000000-0000-4000-8000-000000000001"
ADMIN_ID="e0000000-0000-4000-8000-000000000001"
SELF_ID="e0000000-0000-4000-8000-000000000003"

usage() {
  cat <<'USAGE'
用法：bash scripts/seed-demo-data.sh [--base-url URL] [--dry-run] [--help]

  --base-url URL   API 基地址，默认 http://127.0.0.1:8080（或 ANESOP_API_BASE_URL）
  --dry-run        只打印灌入计划，不发起写请求
  --help           显示本帮助

环境变量（可选）：
  DEMO_ADMIN_PASSWORD / DEMO_ADMIN_NEW_PASSWORD   演示管理员旧/新口令
  DEMO_SELF_PASSWORD  / DEMO_SELF_NEW_PASSWORD    演示麻醉医师旧/新口令
  SEED_HIS_IMPORT_COUNT（默认 3）、SEED_APP_COUNT（默认 15）
  SEED_PREOP_COUNT（默认 6）、SEED_DRUG_COUNT（默认 5）
  SEED_MATERIAL_COUNT（默认 5）、SEED_STAFF_COUNT（默认 4）
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --base-url)
      [ $# -ge 2 ] || { echo "--base-url 缺少参数" >&2; exit 2; }
      BASE_URL="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
  esac
done
BASE_URL="${BASE_URL%/}"

# 数量下限：后续流程固定使用 15 条申请 / 5 条记录，低于下限会告警并抬到下限。
clamp_count() {
  local name="$1" value="$2" min="$3" max="$4"
  case "$value" in
    ''|*[!0-9]*) echo "$min" ;;
    *) if [ "$value" -lt "$min" ]; then echo "$min"; elif [ "$value" -gt "$max" ]; then echo "$max"; else echo "$value"; fi ;;
  esac
}
APP_COUNT="$(clamp_count APP "$APP_COUNT" 15 20)"
PREOP_COUNT="$(clamp_count PREOP "$PREOP_COUNT" 6 14)"
DRUG_COUNT="$(clamp_count DRUG "$DRUG_COUNT" 5 6)"
MATERIAL_COUNT="$(clamp_count MATERIAL "$MATERIAL_COUNT" 5 6)"
STAFF_COUNT="$(clamp_count STAFF "$STAFF_COUNT" 4 6)"
HIS_IMPORT_COUNT="$(clamp_count HIS "$HIS_IMPORT_COUNT" 1 10)"

info() { printf '[seed-demo-data] %s\n' "$*"; }
warn() { printf '[seed-demo-data] 警告：%s\n' "$*" >&2; }

begin_section() {
  SEC_NO=$((SEC_NO + 1))
  SECTION="$1"
  SEC_OK=$OK; SEC_SKIP=$SKIP; SEC_FAIL=$FAIL
  printf '\n[%d] %s\n' "$SEC_NO" "$SECTION"
}

end_section() {
  printf '    → %s：成功 %d，跳过 %d，失败 %d\n' "$SECTION" \
    "$((OK - SEC_OK))" "$((SKIP - SEC_SKIP))" "$((FAIL - SEC_FAIL))"
}

ok()   { OK=$((OK + 1)); printf '    + %s\n' "$*"; }
skip() { SKIP=$((SKIP + 1)); printf '    - %s（跳过）\n' "$*"; }

sanitize_body() {
  python3 - "$RESP_FILE" <<'PY' 2>/dev/null || true
import json, sys
try:
    obj = json.load(open(sys.argv[1], encoding='utf-8'))
except Exception:
    try:
        print(open(sys.argv[1], encoding='utf-8', errors='replace').read()[:300])
    except Exception:
        print('')
    raise SystemExit
SECRET = ('token', 'password', 'secret', 'credential')
def scrub(o):
    if isinstance(o, dict):
        return {k: ('***' if any(s in k.lower() for s in SECRET) else scrub(v))
                for k, v in o.items()}
    if isinstance(o, list):
        return [scrub(x) for x in o]
    return o
print(json.dumps(scrub(obj), ensure_ascii=False)[:400])
PY
}

bad() {
  FAIL=$((FAIL + 1))
  printf '    ! %s（HTTP %s）\n' "$1" "${HTTP:-000}"
  printf '      响应：%s\n' "$(sanitize_body)"
  FAILURES+=("$SECTION / $1 / HTTP ${HTTP:-000}")
}

jget() {
  python3 - "$RESP_FILE" "$1" <<'PY' 2>/dev/null || true
import json, sys
parts = sys.argv[2].split('.')
try:
    cur = json.load(open(sys.argv[1], encoding='utf-8'))
except Exception:
    print(''); raise SystemExit
for p in parts:
    if isinstance(cur, list):
        try:
            cur = cur[int(p)]
        except Exception:
            print(''); raise SystemExit
    elif isinstance(cur, dict):
        if p not in cur:
            print(''); raise SystemExit
        cur = cur[p]
    else:
        print(''); raise SystemExit
if cur is None:
    print('')
elif isinstance(cur, (dict, list)):
    print(json.dumps(cur, ensure_ascii=False))
else:
    print(cur)
PY
}

api() { # api METHOD PATH [BODY]
  local method="$1" path="$2" body="${3-}"
  local args=(-sS --max-time 40 -o "$RESP_FILE" -w '%{http_code}' -X "$method" "$BASE_URL$path")
  if [ -n "$TOKEN" ]; then
    args+=(-H "Authorization: Bearer $TOKEN")
  fi
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' --data-binary "$body")
  fi
  HTTP=$(curl "${args[@]}" 2>/dev/null) || HTTP=000
}

api_noauth() { # api_noauth METHOD PATH [BODY]
  local method="$1" path="$2" body="${3-}"
  local args=(-sS --max-time 40 -o "$RESP_FILE" -w '%{http_code}' -X "$method" "$BASE_URL$path")
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' --data-binary "$body")
  fi
  HTTP=$(curl "${args[@]}" 2>/dev/null) || HTTP=000
}

is_2xx() { case "$HTTP" in 2??) return 0 ;; *) return 1 ;; esac; }

expect_ok() { # expect_ok LABEL
  if is_2xx; then ok "$1"; return 0; fi
  bad "$1"; return 1
}

iso() { # iso SECONDS_OFFSET → 2026-11-16T01:23:45+08:00
  python3 - "$1" <<'PY'
import datetime, sys
from zoneinfo import ZoneInfo
tz = ZoneInfo('Asia/Shanghai')
print((datetime.datetime.now(tz) + datetime.timedelta(seconds=int(sys.argv[1]))).isoformat(timespec='seconds'))
PY
}

iso_day() { iso "$1" | cut -c1-10; }

add_minutes() { # add_minutes HH:MM MINUTES
  python3 - "$1" "$2" <<'PY'
import sys
h, m = map(int, sys.argv[1].split(':'))
total = h * 60 + m + int(sys.argv[2])
total = min(total, 23 * 60 + 59)
print(f"{total // 60:02d}:{total % 60:02d}")
PY
}

today() { iso 0 | cut -c1-10; }

login_request() { # login_request USER PASS → 0/1，成功时设置 LOGIN_TOKEN / LOGIN_MUST
  api_noauth POST /v1/auth/login "{\"username\":\"$1\",\"password\":\"$2\"}"
  if [ "$HTTP" != "200" ]; then LOGIN_TOKEN=""; return 1; fi
  LOGIN_TOKEN="$(jget accessToken)"
  LOGIN_MUST="$(jget user.mustChangePassword)"
  [ -n "$LOGIN_TOKEN" ]
}

change_password() { # change_password OLD NEW（使用当前 TOKEN）
  api POST /v1/auth/password "{\"oldPassword\":\"$1\",\"newPassword\":\"$2\"}"
  [ "$HTTP" = "204" ] || [ "$HTTP" = "200" ]
}

login_account() { # login_account USER OLD NEW → 设置 ACCOUNT_TOKEN
  ACCOUNT_TOKEN=""
  if login_request "$1" "$2"; then
    if [ "$LOGIN_MUST" = "True" ]; then
      TOKEN="$LOGIN_TOKEN"
      if change_password "$2" "$3"; then
        login_request "$1" "$3" || return 1
        ACCOUNT_TOKEN="$LOGIN_TOKEN"
        return 0
      fi
      return 1
    fi
    ACCOUNT_TOKEN="$LOGIN_TOKEN"
    return 0
  fi
  if login_request "$1" "$3"; then
    ACCOUNT_TOKEN="$LOGIN_TOKEN"
    return 0
  fi
  return 1
}

# ---------------------------------------------------------------------------
# dry-run 计划
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" = "1" ]; then
  cat <<PLAN
[seed-demo-data] 计划（dry-run，不发起写请求）
  API：$BASE_URL
  账号：$ADMIN_USER（首次登录自动改密）；$SELF_USER（仅消息中心未读校验）
  演示组织：麻醉科 $DEPT_ANES / 手术室 $DEPT_OR；术间从 GET /v1/scheduling/rooms 动态读取

  M1 申请与排程
    - HIS 模拟导入 $HIS_IMPORT_COUNT 条（POST /v1/scheduling/applications/import）
    - 手工申请 $APP_COUNT 条：择期/限期/急诊，多术式/年龄；2 条取消（CANCELLED）
    - 台次 ≥8：今天/明天/后天、多个术间与序号
    - 路径：改台、停台、急诊抢占加台、冲突检测+改期解决、台次取消
  M2 术前
    - 访视评估 $PREOP_COUNT 份（ASA/Mallampati/评分组合），1 份 finalize
    - 知情同意 ≥6（签署/拒绝/撤销各 1）、三方核查 ≥5（含多版本）
  M2 术中记录
    - 记录单 5 张：锁定+修订 1、进行中 1、草稿 1、另 2 张完成锁定
    - 事件（麻醉开始/诱导/插管/切皮/拔管/结束）、用药（麻精+第二核对人）、输液/出入量
    - 抢救 1、告警评估、UDI 使用 1
  M2 复苏与随访
    - 复苏 3（不同 Aldrete，1 转出）、随访 3（H24/H48、NRS/PONV、PCA 参数）
  M4 药品与麻精
    - 新增药品 $DRUG_COUNT（麻精/高危/别名）、麻精专册/交接/基数/日结（平账）
  M4 人员
    - 档案 $STAFF_COUNT、授权 R1–R5（1 双人审批）、排班 4、培训 4（含到期）
    - 为双人审批创建 2 个 SYNTHETIC 审批账号（SYS_ADMIN）
  M4 耗材
    - 主数据 $MATERIAL_COUNT、出入库 8、UDI 5（1 已使用）、SPD 模拟同步 1
  M4 报表与质控
    - 工作量/多维查询、锁定快照；不良事件+RCA、病历抽查+整改闭环
  M5 看板与消息中心
    - realtime/kpi/risk/resources；停台/改台/急诊触发后查 $SELF_USER 未读 ≥1

  输出：分模块进度 + 成功/跳过/失败汇总；失败非零退出。
PLAN
  exit 0
fi

# ---------------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------------
for tool in curl python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "缺少依赖：$tool" >&2; exit 2; }
done

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/anesop-seed.XXXXXX")"
RESP_FILE="$TMP_DIR/resp.json"
FAILURES=()
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

BATCH="$(date +%Y%m%d%H%M%S)"
TODAY="$(today)"
TOMORROW="$(iso_day 86400)"
DAY_AFTER="$(iso_day 172800)"
MONTH_FROM="${TODAY:0:8}01"

info "目标 API：$BASE_URL（SYNTHETIC 演示数据，批次 $BATCH）"

HTTP=$(curl -sS --max-time 10 -o "$RESP_FILE" -w '%{http_code}' "$BASE_URL/actuator/health" 2>/dev/null) || HTTP=000
if [ "$HTTP" != "200" ]; then
  printf '[seed-demo-data] 前置检查失败：/actuator/health HTTP %s（API 未启动？）\n' "$HTTP" >&2
  exit 2
fi
info "健康检查：UP"

if ! login_account "$ADMIN_USER" "$ADMIN_OLD_PASS" "$ADMIN_NEW_PASS"; then
  printf '[seed-demo-data] 无法登录 %s：请确认演示库已 seed，或通过 DEMO_ADMIN_PASSWORD / DEMO_ADMIN_NEW_PASSWORD 提供当前口令\n' "$ADMIN_USER" >&2
  exit 2
fi
ADMIN_TOKEN="$ACCOUNT_TOKEN"
TOKEN="$ADMIN_TOKEN"
info "登录成功：$ADMIN_USER"

ROOM_IDS=()
DRUG_IDS=()
MATERIAL_IDS=()
APP_IDS=()
IMPORTED_APP_IDS=()
SCHED_IDS=()
STAFF_IDS=()
APPROVER_IDS=()
PATIENT_IDS=()

# ---------------------------------------------------------------------------
# 1. M1 手术申请（HIS 导入 + 手工申请 + 取消）
# ---------------------------------------------------------------------------
begin_section "M1 申请（HIS 导入 + 手工申请 + 取消）"

SURGERIES=(
  "演示术式（腹腔镜胆囊切除术）"
  "演示术式（全膝关节置换术）"
  "演示术式（腰椎间盘髓核摘除术）"
  "演示术式（甲状腺次全切除术）"
  "演示术式（剖宫产术）"
  "演示术式（经尿道前列腺电切术）"
  "演示术式（冠状动脉造影术）"
  "演示术式（乳腺癌改良根治术）"
  "演示术式（阑尾切除术）"
  "演示术式（肱骨骨折内固定术）"
  "演示术式（腹腔镜疝修补术）"
  "演示术式（鼻内镜鼻窦手术）"
  "演示术式（白内障超声乳化术）"
  "演示术式（食管癌根治术）"
  "演示术式（髋关节置换术）"
)
URGENCIES=(ELECTIVE ELECTIVE EMERGENCY URGENT ELECTIVE URGENT ELECTIVE EMERGENCY ELECTIVE ELECTIVE URGENT ELECTIVE EMERGENCY ELECTIVE URGENT)
DURATIONS=(90 120 75 100 60 80 45 150 50 70 65 55 40 180 110)

# 1.1 HIS 模拟导入（同时生成合成患者）
api POST /v1/scheduling/applications/import "{\"count\":$HIS_IMPORT_COUNT,\"seed\":$(date +%s)}"
if is_2xx; then
  ok "HIS 模拟导入 $HIS_IMPORT_COUNT 条（MOCK_HIS，simulated=true）"
  i=0
  while [ "$i" -lt "$HIS_IMPORT_COUNT" ]; do
    pid="$(jget "applications.$i.patient.patientId")"
    iaid="$(jget "applications.$i.applicationId")"
    [ -n "$pid" ] && PATIENT_IDS+=("$pid")
    [ -n "$iaid" ] && IMPORTED_APP_IDS+=("$iaid")
    i=$((i + 1))
  done
else
  bad "HIS 模拟导入"
fi

# 1.2 患者兜底：导入失败时复用库内已有合成患者
if [ "${#PATIENT_IDS[@]}" -eq 0 ]; then
  api GET "/v1/patients?page=1&size=30"
  if is_2xx; then
    i=0
    while [ "$i" -lt 15 ]; do
      pid="$(jget "items.$i.patientId")"
      [ -z "$pid" ] && break
      PATIENT_IDS+=("$pid")
      i=$((i + 1))
    done
  fi
fi
if [ "${#PATIENT_IDS[@]}" -eq 0 ]; then
  bad "没有可用合成患者，后续申请/排程无法继续"
else
  ok "可用合成患者 ${#PATIENT_IDS[@]} 名"
fi

# 1.3 手工申请（不同术式/年龄/紧急度）
for i in $(seq 0 $((APP_COUNT - 1))); do
  [ "${#PATIENT_IDS[@]}" -eq 0 ] && break
  pid="${PATIENT_IDS[$((i % ${#PATIENT_IDS[@]}))]}"
  surgery="${SURGERIES[$((i % ${#SURGERIES[@]}))]}"
  urgency="${URGENCIES[$((i % ${#URGENCIES[@]}))]}"
  dur="${DURATIONS[$((i % ${#DURATIONS[@]}))]}"
  body=$(cat <<JSON
{"hisApplicationNo":"SYNTH-SEED-${BATCH}-A$(printf '%02d' $((i + 1)))","patientId":"$pid","surgeryName":"$surgery","surgeryCode":"SYNTH-OP-DEMO-$(printf '%02d' $((i + 1)))","urgency":"$urgency","plannedDurationMin":$dur,"surgeonName":"演示术者$(( (i % 4) + 1 ))（合成）","preopDiagnosis":"演示诊断（$surgery，SYNTHETIC）","remark":"SYNTHETIC 演示数据，批次 $BATCH"}
JSON
)
  api POST /v1/scheduling/applications "$body"
  if is_2xx; then
    APP_IDS+=("$(jget applicationId)")
    ok "申请 A$(printf '%02d' $((i + 1)))：$urgency / ${dur}min / $surgery"
  else
    bad "创建申请 A$(printf '%02d' $((i + 1)))"
  fi
done

# 1.4 取消 2 条（状态 CANCELLED）
CANCEL_TARGETS=()
if [ "${#APP_IDS[@]}" -ge 15 ]; then
  CANCEL_TARGETS=("${APP_IDS[13]}" "${APP_IDS[14]}")
elif [ "${#APP_IDS[@]}" -ge 2 ]; then
  CANCEL_TARGETS=("${APP_IDS[$((${#APP_IDS[@]} - 2))]}" "${APP_IDS[$((${#APP_IDS[@]} - 1))]}")
fi
for idx in "${!CANCEL_TARGETS[@]}"; do
  aid="${CANCEL_TARGETS[$idx]}"
  [ -z "$aid" ] && continue
  api POST "/v1/scheduling/applications/$aid/cancel" \
    "{\"reasonCode\":\"PATIENT_CONDITION\",\"reason\":\"演示取消：患者原因（合成）\",\"version\":1}"
  if is_2xx; then
    ok "申请取消 $((idx + 1))/2（CANCELLED）"
  elif [ "$HTTP" = "409" ]; then
    skip "申请已取消或状态不允许重复取消"
  else
    bad "取消申请"
  fi
done
info "说明：当前 API 未提供 ACCEPTED/SCHEDULED 申请状态流转端点，排程后申请仍为 SUBMITTED；本脚本覆盖 SUBMITTED 与 CANCELLED，排程域状态由台次状态体现。"

# ---------------------------------------------------------------------------
# 2. M1 排程（8 台式 + 改台/停台/急诊/冲突/取消）
# ---------------------------------------------------------------------------
begin_section "M1 排程（8+ 台次，改台/停台/急诊/冲突）"

api GET /v1/scheduling/rooms
if is_2xx; then
  i=0
  while [ "$i" -lt 8 ]; do
    rid="$(jget "items.$i.roomId")"
    [ -z "$rid" ] && break
    ROOM_IDS+=("$rid")
    i=$((i + 1))
  done
fi
if [ "${#ROOM_IDS[@]}" -lt 4 ] || [ "${#APP_IDS[@]}" -lt 8 ]; then
  bad "术间或申请不足，无法生成排程"
else
  ok "术间 ${#ROOM_IDS[@]} 个"
fi

create_schedule() { # create_schedule APP_ID ROOM_ID DATE START END SEQ [LABEL]
  local aid="$1" rid="$2" day="$3" start="$4" end="$5" seq="$6" label="${7:-台次}"
  local body attempt=0
  # 演示环境可能已有同术间/同时段台次：冲突时向后顺延 30 分钟重试，最多 8 次。
  while [ "$attempt" -lt 8 ]; do
    body=$(cat <<JSON
{"applicationId":"$aid","roomId":"$rid","plannedStart":"${day}T${start}:00+08:00","plannedEnd":"${day}T${end}:00+08:00","sequenceNo":$seq,"priority":"ELECTIVE"}
JSON
)
    api POST /v1/scheduling/schedules "$body"
    if is_2xx; then
      LAST_SCHED_ID="$(jget scheduleId)"
      LAST_SCHED_DAY="$day"
      LAST_SCHED_START="$start"
      LAST_SCHED_END="$end"
      SCHED_IDS+=("$LAST_SCHED_ID")
      ok "$label（$day $start-$end，序号 $seq）"
      return 0
    fi
    if [ "$HTTP" = "409" ] && [ "$attempt" -lt 7 ]; then
      start="$(add_minutes "$start" 30)"
      end="$(add_minutes "$end" 30)"
      attempt=$((attempt + 1))
      continue
    fi
    break
  done
  SCHED_IDS+=("")
  bad "$label"
  return 1
}

find_free_slot() { # find_free_slot APP_ID ROOM_ID DATE START END → stdout "HH:MM HH:MM"
  local aid="$1" rid="$2" day="$3" start="$4" end="$5" attempt=0 count=1
  while [ "$attempt" -lt 30 ]; do
    api POST /v1/scheduling/schedules/conflicts \
      "{\"roomId\":\"$rid\",\"applicationId\":\"$aid\",\"plannedStart\":\"${day}T${start}:00+08:00\",\"plannedEnd\":\"${day}T${end}:00+08:00\",\"staffIds\":[],\"equipmentTags\":[]}"
    if is_2xx; then
      count="$(python3 - "$RESP_FILE" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
    print(len(d.get('conflicts') or []))
except Exception:
    print(1)
PY
)"
      if [ "$count" = "0" ]; then
        printf '%s %s\n' "$start" "$end"
        return 0
      fi
    fi
    start="$(add_minutes "$start" 30)"
    end="$(add_minutes "$end" 30)"
    attempt=$((attempt + 1))
  done
  return 1
}

create_schedule_free() { # create_schedule_free APP_ID ROOM_ID DATE START END SEQ LABEL
  local slot tstart tend d rid
  if slot="$(find_free_slot "$1" "$2" "$3" "$4" "$5")"; then
    read -r tstart tend <<< "$slot"
    create_schedule "$1" "$2" "$3" "$tstart" "$tend" "$6" "$7"
    return $?
  fi
  # 目标日/术间已满：依次尝试 +3~+5 天的全部术间，避免反复演示时因时段耗尽中断流程。
  for d in "$(iso_day 259200)" "$(iso_day 345600)" "$(iso_day 432000)"; do
    for rid in "${ROOM_IDS[@]}"; do
      if slot="$(find_free_slot "$1" "$rid" "$d" "08:00" "09:30")"; then
        read -r tstart tend <<< "$slot"
        create_schedule "$1" "$rid" "$d" "$tstart" "$tend" "$6" "$7（顺延 $d）"
        return $?
      fi
    done
  done
  skip "$7（未来 5 天均无空闲时段，演示时段已用尽）"
  SCHED_IDS+=("")
  return 1
}

# 8 条基础台次：今天/明天/后天，多术间多序号
if [ "${#ROOM_IDS[@]}" -ge 5 ] && [ "${#APP_IDS[@]}" -ge 8 ]; then
  BASE_PLAN=(
    "0|0|$TODAY|08:00|09:30|1"
    "1|1|$TODAY|08:30|10:00|1"
    "2|2|$TODAY|09:00|10:30|1"
    "3|0|$TOMORROW|08:00|09:00|1"
    "4|1|$TOMORROW|10:00|11:30|1"
    "5|3|$DAY_AFTER|13:00|14:00|1"
    "6|0|$DAY_AFTER|08:00|09:30|1"
    "7|4|$DAY_AFTER|10:00|11:00|1"
  )
  for item in "${BASE_PLAN[@]}"; do
    IFS='|' read -r app_i room_i day start end seq <<< "$item"
    create_schedule_free "${APP_IDS[$app_i]}" "${ROOM_IDS[$room_i]}" "$day" "$start" "$end" "$seq" \
      "基础台次：$(basename "$day") $start"
  done
fi

# 2.1 改台（PUT，写变更留痕 + 消息）
if [ "${#APP_IDS[@]}" -ge 9 ] && [ "${#ROOM_IDS[@]}" -ge 4 ]; then
  create_schedule_free "${APP_IDS[8]}" "${ROOM_IDS[1]}" "$TOMORROW" "09:00" "10:00" 2 "改台前"
  if [ "${#SCHED_IDS[@]}" -ge 9 ] && [ -n "${SCHED_IDS[8]:-}" ]; then
    sid="${SCHED_IDS[8]}"
    slot=""; tday=""; troom=""
    for d in "$TOMORROW" "$DAY_AFTER" "$(iso_day 259200)" "$(iso_day 345600)"; do
      for rid in "${ROOM_IDS[@]}"; do
        if slot="$(find_free_slot "${APP_IDS[8]}" "$rid" "$d" "08:00" "09:00")"; then
          tday="$d"; troom="$rid"; break 2
        fi
      done
    done
    if [ -n "$slot" ]; then
      read -r tstart tend <<< "$slot"
      body=$(cat <<JSON
{"roomId":"$troom","plannedStart":"${tday}T${tstart}:00+08:00","plannedEnd":"${tday}T${tend}:00+08:00","sequenceNo":1,"version":1,"reasonCode":"SCHEDULE_ADJUST","reason":"演示改台（合成）：术间调整"}
JSON
)
      api PUT "/v1/scheduling/schedules/$sid" "$body"
      if is_2xx; then ok "改台（PUT，原因码 SCHEDULE_ADJUST，$tday $tstart-$tend）"; else bad "改台"; fi
    else
      skip "改台（未来 5 天无空闲目标时段）"
    fi
  fi
fi

# 2.2 停台（POST /stop）
if [ "${#APP_IDS[@]}" -ge 10 ] && [ "${#ROOM_IDS[@]}" -ge 3 ]; then
  create_schedule_free "${APP_IDS[9]}" "${ROOM_IDS[2]}" "$TOMORROW" "09:00" "10:00" 2 "停台前"
  if [ "${#SCHED_IDS[@]}" -ge 10 ] && [ -n "${SCHED_IDS[9]:-}" ]; then
    sid="${SCHED_IDS[9]}"
    api POST "/v1/scheduling/schedules/$sid/stop" \
      '{"reasonCode":"PATIENT_CONDITION","reason":"演示停台（合成）：患者条件变化","version":1}'
    if is_2xx; then ok "停台（POST /stop）"; else bad "停台"; fi
  fi
fi

# 2.3 急诊加台 + 抢占择期台（急诊路径）
EMERGENCY_SCHED=""
EMERG_APP="${APP_IDS[11]:-}"
if [ "${#APP_IDS[@]}" -ge 12 ] && [ "${#ROOM_IDS[@]}" -ge 1 ]; then
  # 专用急诊申请：唯一术者 + 未排程患者，确保只与目标台次产生 ROOM_OVERLAP（可抢占）。
  if [ "${#PATIENT_IDS[@]}" -gt 0 ]; then
    epid="${PATIENT_IDS[$((${#PATIENT_IDS[@]} - 1))]}"
    api POST /v1/scheduling/applications "{\"hisApplicationNo\":\"SYNTH-SEED-$BATCH-EMERG\",\"patientId\":\"$epid\",\"surgeryName\":\"演示急诊手术（合成）\",\"surgeryCode\":\"SYNTH-OP-EMERG\",\"urgency\":\"EMERGENCY\",\"plannedDurationMin\":45,\"surgeonName\":\"演示急诊术者$BATCH（合成）\",\"preopDiagnosis\":\"演示急诊诊断（合成）\",\"remark\":\"SYNTHETIC 急诊加台\"}"
    if is_2xx; then EMERG_APP="$(jget applicationId)"; ok "急诊专用申请（唯一术者）"; else bad "急诊专用申请"; fi
  fi
  create_schedule_free "${APP_IDS[10]}" "${ROOM_IDS[0]}" "$TODAY" "08:00" "09:00" 2 "急诊抢占目标"
  if [ "${#SCHED_IDS[@]}" -ge 11 ] && [ -n "${SCHED_IDS[10]:-}" ]; then
    preempt="${SCHED_IDS[10]}"
    pre_start="$LAST_SCHED_START"
    pre_end="$LAST_SCHED_END"
    pre_day="$LAST_SCHED_DAY"
    pre_room="$(jget roomId)"
    body=$(cat <<JSON
{"applicationId":"${EMERG_APP}","roomId":"${pre_room}","reasonCode":"EMERGENCY_PREEMPT","reason":"演示急诊加台（合成）：绿色通道","expectedStart":"${pre_day}T${pre_start}:00+08:00","expectedEnd":"${pre_day}T${pre_end}:00+08:00","sequenceNo":3,"preemptScheduleId":"$preempt"}
JSON
)
    api POST /v1/scheduling/emergency-cases "$body"
    if is_2xx; then
      EMERGENCY_SCHED="$(jget schedule.scheduleId)"
      [ -z "$EMERGENCY_SCHED" ] && EMERGENCY_SCHED="$(jget scheduleId)"
      ok "急诊加台（抢占择期台，EMERGENCY_PREEMPT）"
    else
      bad "急诊加台"
    fi
  fi
fi

# 2.4 冲突解决：先制造冲突（预期 409），再改期成功
if [ "${#APP_IDS[@]}" -ge 13 ] && [ "${#ROOM_IDS[@]}" -ge 1 ]; then
  aid="${APP_IDS[12]}"
  api POST /v1/scheduling/schedules/conflicts \
    "{\"roomId\":\"${ROOM_IDS[0]}\",\"applicationId\":\"$aid\",\"plannedStart\":\"${TODAY}T08:15:00+08:00\",\"plannedEnd\":\"${TODAY}T09:00:00+08:00\",\"staffIds\":[],\"equipmentTags\":[]}"
  if is_2xx; then
    ccount="$(python3 - "$RESP_FILE" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1])); print(len(d.get('conflicts') or []))
except Exception: print(0)
PY
)"
    ok "冲突检测：返回 $ccount 条冲突（POST /schedules/conflicts）"
  else
    bad "冲突检测"
  fi
  body=$(cat <<JSON
{"applicationId":"$aid","roomId":"${ROOM_IDS[0]}","plannedStart":"${TODAY}T08:15:00+08:00","plannedEnd":"${TODAY}T09:00:00+08:00","sequenceNo":2,"priority":"ELECTIVE"}
JSON
)
  api POST /v1/scheduling/schedules "$body"
  if [ "$HTTP" = "409" ]; then
    ok "冲突路径：冲突台次被拒（409），进入改期"
  elif is_2xx; then
    skip "冲突未触发（时段已空闲），保留新台次"
  else
    bad "冲突路径（预期 409）"
  fi
  create_schedule_free "$aid" "${ROOM_IDS[0]}" "$TOMORROW" "19:00" "20:00" 3 "冲突解决改期" \
    && ok "冲突解决：改期到空闲时段成功"
fi

# 2.5 台次取消（/cancel 兼容端点；使用 HIS 导入申请，避免占用已取消申请）
if [ "${#IMPORTED_APP_IDS[@]}" -ge 1 ] && [ "${#ROOM_IDS[@]}" -ge 3 ]; then
  create_schedule "${IMPORTED_APP_IDS[0]}" "${ROOM_IDS[2]}" "$TOMORROW" "13:00" "14:00" 1 "取消前"
  if [ "${#SCHED_IDS[@]}" -ge 1 ] && [ -n "${SCHED_IDS[$((${#SCHED_IDS[@]} - 1))]:-}" ]; then
    sid="${SCHED_IDS[$((${#SCHED_IDS[@]} - 1))]}"
    api POST "/v1/scheduling/schedules/$sid/cancel" \
      '{"reasonCode":"SCHEDULE_CHANGE","reason":"演示取消台次（合成）","version":1}'
    if is_2xx; then ok "台次取消（POST /cancel）"; else bad "台次取消"; fi
  fi
fi

# ---------------------------------------------------------------------------
# 3. M2 术前（访视评估 / 知情同意 / 三方核查）
# ---------------------------------------------------------------------------
begin_section "M2 术前（访视评估 / 知情同意 / 三方核查）"

api GET /v1/anesthesia/consent-templates?effectiveOnly=true
CONSENT_TEMPLATE_ID=""
CONSENT_TEMPLATE_VERSION=""
if is_2xx; then
  CONSENT_TEMPLATE_ID="$(jget items.0.templateId)"
  CONSENT_TEMPLATE_VERSION="$(jget items.0.version)"
fi
if [ -n "$CONSENT_TEMPLATE_ID" ]; then
  ok "知情同意模板 v$CONSENT_TEMPLATE_VERSION"
else
  bad "读取知情同意模板"
fi

# 3.1 访视评估（不同 ASA/Mallampati/评分组合）
ASA_LIST=(I II III II IV I)
MALL_LIST=(I II III I IV II)
for i in $(seq 0 $((PREOP_COUNT - 1))); do
  [ "${#APP_IDS[@]}" -le "$i" ] && break
  aid="${APP_IDS[$i]}"
  api GET "/v1/scheduling/applications/$aid"
  [ "$HTTP" = "200" ] || { bad "读取申请详情 A$i"; continue; }
  pid="$(jget patient.patientId)"; hisno="$(jget patient.hisPatientNo)"
  pname="$(jget patient.name)"; gender="$(jget patient.gender)"
  age="$(jget patient.age)"; dept="$(jget patient.departmentName)"
  asa="${ASA_LIST[$((i % ${#ASA_LIST[@]}))]}"
  mall="${MALL_LIST[$((i % ${#MALL_LIST[@]}))]}"
  rcri_ihd=$([ $((i % 2)) -eq 0 ] && echo true || echo false)
  apfel_female=$([ "$gender" = "FEMALE" ] && echo true || echo false)
  stops=$([ "$i" -ge 3 ] && echo true || echo false)
  score=0
  snapshot=$(cat <<JSON
{"patientId":"$pid","hisPatientNo":"$hisno","patientName":"$pname","gender":"$gender","age":$age,"departmentName":"$dept","inpatientNoMasked":"SYNT****$i"}
JSON
)
  body=$(cat <<JSON
{"applicationId":"$aid","patientSnapshot":$snapshot,"historySummary":"演示病史（合成）：高血压/糖尿病等慢病组合，规律随访；无真实患者信息。","asaClass":"$asa","airwayAssessment":{"mallampati":"$mall","mouthOpeningCm":4.5,"cervicalSpineMobility":"NORMAL","notes":"演示气道评估"},"scoreInput":{"rcri":{"highRiskSurgery":$stops,"ischemicHeartDisease":$rcri_ihd,"congestiveHeartFailure":false,"cerebrovascularDisease":false,"insulinDependentDiabetes":false,"renalImpairment":false},"apfel":{"female":$apfel_female,"ponvOrMotionSicknessHistory":$stops,"nonSmoker":true,"postoperativeOpioid":true},"stopBang":{"snoring":$stops,"tiredness":$stops,"observedApnea":false,"hypertension":$stops,"bmiOver35":false,"ageOver50":$([ "$age" -ge 50 ] && echo true || echo false),"neckCircumferenceOver40":false,"male":$([ "$gender" = "MALE" ] && echo true || echo false)},"dasiActivities":["SELF_CARE","WALK_INDOORS","CLIMB_STAIRS"]},"plan":{"planType":"GENERAL","airwayPlan":"气管插管全麻","notes":"演示麻醉计划（合成）"}}
JSON
)
  api POST /v1/anesthesia/preop-assessments "$body"
  if is_2xx; then
    ok "访视评估 $((i + 1))：ASA $asa / Mallampati $mall"
    if [ "$i" -eq 0 ]; then
      assess_id="$(jget assessmentId)"; ver="$(jget version)"
      api POST "/v1/anesthesia/preop-assessments/$assess_id/finalize" \
        "{\"version\":$ver,\"note\":\"演示定稿（合成）\"}"
      if is_2xx; then ok "访视评估定稿（finalize）"; else bad "访视评估定稿"; fi
    fi
  else
    bad "访视评估 $((i + 1))"
  fi
done

# 3.2 知情同意（签署/拒绝/撤销 + 为锁单准备有效同意）
consent_create_sign() { # consent_create_sign APP_ID SIGNER RELATION LABEL
  local aid="$1" signer="$2" relation="$3" label="$4"
  [ -n "$CONSENT_TEMPLATE_ID" ] || { skip "$label（无模板）"; return 1; }
  body=$(cat <<JSON
{"applicationId":"$aid","templateId":"$CONSENT_TEMPLATE_ID","templateVersion":$CONSENT_TEMPLATE_VERSION,"signerName":"$signer","signerRelation":"$relation","signMethod":"ELECTRONIC"}
JSON
)
  api POST /v1/anesthesia/consents "$body"
  if ! is_2xx; then bad "$label 创建"; return 1; fi
  local cid ver
  cid="$(jget consentId)"; ver="$(jget version)"
  api POST "/v1/anesthesia/consents/$cid/sign" "{\"version\":$ver}"
  if is_2xx; then ok "$label 签署"; CONSENT_LAST_ID="$cid"; CONSENT_LAST_VER="$(jget version)"; return 0; fi
  bad "$label 签署"; return 1
}

if [ "${#APP_IDS[@]}" -ge 8 ]; then
  # 为前 8 个基础申请全部签署同意（锁单前置条件），另做拒绝/撤销各 1 份。
  RELATIONS=(SELF SPOUSE CHILD PARENT LEGAL_GUARDIAN OTHER SELF SPOUSE)
  SIGNABLES=("演示患者甲" "演示患者乙" "演示患者丙" "演示患者丁" "演示患者戊" "演示患者己" "演示患者庚" "演示患者辛")
  for i in 0 1 2 3 4 5 6 7; do
    consent_create_sign "${APP_IDS[$i]}" "${SIGNABLES[$i]}" "${RELATIONS[$i]}" "同意书 $((i + 1))（签署）"
  done
  # 撤销：先签后撤
  if consent_create_sign "${APP_IDS[11]}" "演示患者壬" "SELF" "同意书 9（签署后撤销）"; then
    api POST "/v1/anesthesia/consents/$CONSENT_LAST_ID/revoke" \
      "{\"version\":$CONSENT_LAST_VER,\"reason\":\"演示撤销（合成）：患者改变决定\"}"
    if is_2xx; then ok "同意书 9 撤销"; else bad "同意书 9 撤销"; fi
  fi
  # 拒绝
  body=$(cat <<JSON
{"applicationId":"${APP_IDS[12]}","templateId":"$CONSENT_TEMPLATE_ID","templateVersion":$CONSENT_TEMPLATE_VERSION,"signerName":"演示患者癸","signerRelation":"LEGAL_GUARDIAN","signMethod":"PAPER"}
JSON
)
  api POST /v1/anesthesia/consents "$body"
  if is_2xx; then
    cid="$(jget consentId)"; ver="$(jget version)"
    api POST "/v1/anesthesia/consents/$cid/decline" \
      "{\"version\":$ver,\"reason\":\"演示拒绝（合成）：选择保守治疗\"}"
    if is_2xx; then ok "同意书 10（拒绝）"; else bad "同意书 10 拒绝"; fi
  else
    bad "同意书 10 创建"
  fi
fi

# 3.3 三方核查（申请维度；5 个版本，含 time-out 补录多版本）
three_way_complete() { # three_way_complete APP_ID LABEL
  local aid="$1" label="$2" body
  body=$(cat <<JSON
{"timeOutAt":"$(iso -180)","confirmTimeOutChange":true,"timeOutBy":"$ADMIN_ID","signOutAt":"$(iso -60)","signOutBy":"$ADMIN_ID","specimenChecked":true,"implantChecked":false,"issues":[]}
JSON
)
  api POST "/v1/anesthesia/applications/$aid/three-way-checks" "$body"
  if is_2xx; then ok "$label（Time-out + Sign-out）"; else bad "$label"; fi
}

if [ "${#APP_IDS[@]}" -ge 8 ]; then
  # 前 8 个基础申请全部完成三方核查（锁单前置条件）
  for i in 0 1 2 3 4 5 6 7; do
    three_way_complete "${APP_IDS[$i]}" "三方核查 $((i + 1))"
  done
  # 多版本：先仅 Time-out，再补 Sign-out（第二版本继承 Time-out）
  api POST "/v1/anesthesia/applications/${APP_IDS[1]}/three-way-checks" \
    "{\"timeOutAt\":\"$(iso -240)\",\"timeOutBy\":\"$ADMIN_ID\",\"specimenChecked\":true,\"implantChecked\":true,\"issues\":[\"演示：标本标识复核（合成）\"]}"
  if is_2xx; then ok "三方核查 v1（仅 Time-out，多版本准备）"; elif [ "$HTTP" = "409" ]; then skip "三方核查 v1（已完成，不降级为仅 Time-out）"; else bad "三方核查 v1"; fi
  api POST "/v1/anesthesia/applications/${APP_IDS[1]}/three-way-checks" \
    "{\"confirmTimeOutChange\":true,\"timeOutBy\":\"$ADMIN_ID\",\"signOutAt\":\"$(iso -120)\",\"signOutBy\":\"$ADMIN_ID\",\"specimenChecked\":true,\"implantChecked\":true,\"issues\":[]}"
  if is_2xx; then ok "三方核查 v2（补 Sign-out，多版本）"; else bad "三方核查 v2"; fi
fi

# ---------------------------------------------------------------------------
# 4. M2 术中记录（5 张：锁定+修订 / 进行中 / 草稿 / 完成）
# ---------------------------------------------------------------------------
begin_section "M2 术中记录（事件/用药/输液/抢救/锁单/修订）"

RECORD_IDS=()
RECORD_VERS=()
RECORD_BASE=()
LOCKED_RECORDS=()
INPROGRESS_RECORD=""
DRAFT_RECORD=""

create_record() { # create_record SCHED_INDEX TYPE
  local sid="${SCHED_IDS[$1]:-}"
  [ -n "$sid" ] || { skip "记录单：台次缺失，跳过"; return 1; }
  api POST /v1/anesthesia/records "{\"scheduleId\":\"$sid\",\"anesthesiaType\":\"$2\"}"
  if is_2xx; then
    RECORD_IDS+=("$(jget recordId)")
    RECORD_VERS+=("$(jget version)")
    RECORD_BASE+=("$1")
    ok "记录单 $(( ${#RECORD_IDS[@]} ))（$2，台次基序 $1）"
    return 0
  fi
  bad "创建记录单"
  return 1
}

append_event() { # append_event REC_INDEX EVENT_TYPE NOTE
  local idx="$1" type="$2" note="${3:-}"
  local rid="${RECORD_IDS[$idx]:-}"
  [ -n "$rid" ] || return 1
  local ts body
  ts="$(iso $((3 + idx)))"
  body="{\"eventType\":\"$type\",\"occurredAt\":\"$ts\",\"source\":\"MANUAL\",\"note\":\"$note\"}"
  api POST "/v1/anesthesia/records/$rid/events" "$body"
  if is_2xx; then
    RECORD_VERS[$idx]=$(( ${RECORD_VERS[$idx]} + 1 ))
    return 0
  fi
  bad "记录 $((idx + 1)) 事件 $type"
  return 1
}

finish_events() { # finish_events REC_INDEX
  local idx="$1" rid="${RECORD_IDS[$1]:-}"
  [ -n "$rid" ] || return 1
  append_event "$idx" ANESTHESIA_START "演示：麻醉开始（合成）" && \
  append_event "$idx" INDUCTION "演示：诱导（合成）" && \
  append_event "$idx" INTUBATION "演示：气管插管（合成）" && \
  append_event "$idx" INCISION "演示：手术切皮（合成）" && \
  append_event "$idx" EXTUBATION "演示：拔管（合成）" && \
  append_event "$idx" ANESTHESIA_END "演示：麻醉结束（合成）"
}

# 从可用的基础台次中取前 5 个建记录单，避免某天时段已满导致索引错位。
PICK_BASES=()
for b in 0 1 2 3 4 5 6 7; do
  [ -n "${SCHED_IDS[$b]:-}" ] && PICK_BASES+=("$b")
  [ "${#PICK_BASES[@]}" -eq 5 ] && break
done
if [ "${#PICK_BASES[@]}" -ge 5 ]; then
  create_record "${PICK_BASES[0]}" GENERAL && finish_events 0
  create_record "${PICK_BASES[1]}" REGIONAL && finish_events 1
  create_record "${PICK_BASES[2]}" GENERAL
  if [ -n "${RECORD_IDS[2]:-}" ]; then
    INPROGRESS_RECORD="${RECORD_IDS[2]}"
    append_event 2 ANESTHESIA_START "演示：麻醉开始（合成）" && \
    append_event 2 INDUCTION "演示：诱导（合成）" && \
    append_event 2 INTUBATION "演示：气管插管（合成）" && \
    append_event 2 INCISION "演示：手术切皮（合成）"
  fi
  create_record "${PICK_BASES[3]}" COMBINED
  if [ -n "${RECORD_IDS[3]:-}" ]; then
    DRAFT_RECORD="${RECORD_IDS[3]}"
    ok "草稿记录单（无事件，DRAFT）"
  fi
  create_record "${PICK_BASES[4]}" GENERAL && finish_events 4
else
  skip "可用台次不足 5 个，记录单数量不足（演示数据量已接近时段容量）"
fi

# 4.1 麻精/高危用药（字典校验 + 第二核对人 demo_self）
NARCOTIC_DRUG_ID=""
NARCOTIC_DRUG_NAME=""
if [ "${#RECORD_IDS[@]}" -ge 3 ] && [ -n "$INPROGRESS_RECORD" ]; then
  # 药品主数据在 M4 段创建；这里先占位，实际用药在 M4 后补（见 4.4）
  :
fi

# 4.2 输液/出入量（进行中记录）
if [ -n "$INPROGRESS_RECORD" ]; then
  api POST "/v1/anesthesia/records/$INPROGRESS_RECORD/fluids" \
    "{\"direction\":\"INTAKE\",\"fluidType\":\"乳酸林格液\",\"amountMl\":500,\"recordedAt\":\"$(iso 9)\",\"note\":\"演示输液（合成）\"}"
  is_2xx && ok "输液：晶体液 500ml" || bad "输液记录"
  api POST "/v1/anesthesia/records/$INPROGRESS_RECORD/fluids" \
    "{\"direction\":\"OUTPUT\",\"fluidType\":\"尿量\",\"amountMl\":120,\"recordedAt\":\"$(iso 9)\",\"note\":\"演示出量（合成）\"}"
  is_2xx && ok "出入量：尿量 120ml" || bad "出入量记录"
fi

# 4.3 抢救记录在 M4 药品字典创建后写入（见第 5 节末尾），确保药名可被字典解析。

# 4.4 锁单（需有效同意 + 三方核查完成）+ 修订
lock_record() { # lock_record IDX LABEL
  local idx="$1" label="$2" rid="${RECORD_IDS[$1]:-}"
  [ -n "$rid" ] || { bad "$label：无记录单"; return 1; }
  api POST "/v1/anesthesia/records/$rid/lock" \
    "{\"version\":${RECORD_VERS[$idx]},\"signatureRequested\":true,\"confirmChecklist\":true}"
  if is_2xx; then
    ok "$label：锁单（签名 SIM-CA）"
    LOCKED_RECORDS+=("$rid")
    RECORD_VERS[$idx]=$(( ${RECORD_VERS[$idx]} + 1 ))
    return 0
  fi
  bad "$label：锁单"
  return 1
}

if [ "${#RECORD_IDS[@]}" -ge 1 ]; then
  lock_record 0 "锁定记录单 1"
  if [ "${#LOCKED_RECORDS[@]}" -ge 1 ]; then
    api POST "/v1/anesthesia/records/${RECORD_IDS[0]}/revisions" \
      "{\"version\":${RECORD_VERS[0]},\"reasonCode\":\"OTHER\",\"reason\":\"演示修订（合成）：术后补记麻醉方式说明\",\"changes\":\"更正麻醉方式为静吸复合\",\"corrections\":{\"anesthesiaType\":\"COMBINED\"}}"
    if is_2xx; then
      ok "修订版本 v$(jget revisionNo)（锁定后修订）"
      RECORD_VERS[0]=$(( ${RECORD_VERS[0]} + 1 ))
    else
      bad "记录单修订"
    fi
  fi
fi
if [ "${#RECORD_IDS[@]}" -ge 2 ]; then lock_record 1 "锁定记录单 2"; fi
if [ "${#RECORD_IDS[@]}" -ge 5 ]; then lock_record 4 "锁定记录单 5"; fi

# 4.5 告警评估（进行中 + 锁定记录）
for idx in 0 2; do
  rid="${RECORD_IDS[$idx]:-}"
  [ -z "$rid" ] && continue
  api POST "/v1/anesthesia/records/$rid/alerts/evaluate"
  if is_2xx; then
    ok "告警评估 $((idx + 1))：规则 $(jget evaluatedRules) 条，采样 $(jget sampledFrames) 帧，新增告警 $(jget createdAlerts)"
  else
    bad "告警评估 $((idx + 1))"
  fi
done

# ---------------------------------------------------------------------------
# 5. M4 药品字典 + 麻精五专（供用药/专册/交接/基数/日结使用）
# ---------------------------------------------------------------------------
begin_section "M4 药品字典与麻精五专"

DRUG_CODES=("SYNTH-SEED-NARC-01" "SYNTH-SEED-NARC-02" "SYNTH-SEED-HIGH-01" "SYNTH-SEED-SED-01" "SYNTH-SEED-VASO-01" "SYNTH-SEED-OTHER-01")
DRUG_NAMES=("演示麻精药A（合成）" "演示麻精药B（合成）" "演示高危药C（合成）" "演示镇静药D（合成）" "演示升压药E（合成）" "演示其他药F（合成）")
DRUG_NARC=("true" "true" "false" "false" "false" "false")
DRUG_HIGH=("true" "true" "true" "false" "true" "false")
for i in $(seq 0 $((DRUG_COUNT - 1))); do
  code="${DRUG_CODES[$i]}"; name="${DRUG_NAMES[$i]}"
  api GET "/v1/drugs?keyword=$code&page=1&size=5"
  existing=""
  if is_2xx; then
    existing="$(python3 - "$RESP_FILE" "$code" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    for it in d.get('items') or []:
        if it.get('code') == sys.argv[2]:
            print(it.get('drugId','')); break
except Exception: pass
PY
)"
  fi
  if [ -n "$existing" ]; then
    DRUG_IDS+=("$existing"); skip "药品已存在：$code"; continue
  fi
  body=$(cat <<JSON
{"code":"$code","name":"$name","genericName":"$name 通用名（合成）","specification":"1mg/1ml","unit":"支","dosageForm":"INJECTION","category":"$([ "${DRUG_NARC[$i]}" = "true" ] && echo OPIOID || echo OTHER)","narcotic":${DRUG_NARC[$i]},"highRisk":${DRUG_HIGH[$i]},"aliases":["$name 别名（合成）"],"remark":"SYNTHETIC 演示药品"}
JSON
)
  api POST /v1/drugs "$body"
  if is_2xx; then
    DRUG_IDS+=("$(jget drugId)"); ok "新增药品：$code / $name"
  else
    bad "新增药品 $code"
  fi
done

NARCOTIC_DRUG_ID="${DRUG_IDS[0]:-}"
NARCOTIC_DRUG_NAME="${DRUG_NAMES[0]}"
CABINET="SYN-${BATCH:4:10}"

if [ -n "$NARCOTIC_DRUG_ID" ]; then
  body=$(cat <<JSON
{"drugId":"$NARCOTIC_DRUG_ID","batchNo":"SYN-NB-$BATCH","quantity":2,"unit":"支","prescriptionNo":"SYN-RX-$BATCH","usedBy":"演示麻醉医师（合成）","departmentId":"$DEPT_ANES","usageAt":"$(iso -3600)","destination":"OR-01","firstSignerId":"$ADMIN_ID","firstSignerName":"演示管理员（合成）","secondSignerId":"$SELF_ID","secondSignerName":"演示麻醉医师（合成）"}
JSON
)
  api POST /v1/narcotic/registers "$body"
  is_2xx && ok "麻精专册登记（双签字）" || bad "麻精专册登记"

  body=$(cat <<JSON
{"cabinetCode":"$CABINET","departmentId":"$DEPT_ANES","shift":"DAY","handedOverAt":"$(iso -1800)","handoverFromId":"$ADMIN_ID","handoverFromName":"演示管理员（合成）","handoverToId":"$SELF_ID","handoverToName":"演示麻醉医师（合成）","items":[{"drugId":"$NARCOTIC_DRUG_ID","quantity":2,"unit":"支"}],"firstSignerId":"$ADMIN_ID","firstSignerName":"演示管理员（合成）","secondSignerId":"$SELF_ID","secondSignerName":"演示麻醉医师（合成）"}
JSON
)
  api POST /v1/narcotic/cabinet-handovers "$body"
  is_2xx && ok "麻精柜交接（DAY 班）" || bad "麻精柜交接"

  api PUT /v1/narcotic/baselines "{\"items\":[{\"drugId\":\"$NARCOTIC_DRUG_ID\",\"cabinetCode\":\"$CABINET\",\"departmentId\":\"$DEPT_ANES\",\"quantity\":5}]}"
  is_2xx && ok "麻精基数设置（5 支）" || bad "麻精基数设置"

  api POST /v1/narcotic/daily-closes "{\"closeDate\":\"$TODAY\",\"shift\":\"DAY\",\"cabinetCode\":\"$CABINET\",\"departmentId\":\"$DEPT_ANES\",\"openingQuantity\":5,\"inboundQuantity\":2,\"usedQuantity\":2,\"returnedQuantity\":0,\"closingQuantity\":5}"
  if is_2xx; then
    ok "麻精日结（平账：5+2-2-0=5，balanced=$(jget balanced)）"
  elif [ "$HTTP" = "409" ]; then
    skip "麻精日结已存在（同日同柜同班次）"
  else
    bad "麻精日结"
  fi
fi

# 4.6 用药（麻精需第二核对人）；依赖药品字典
if [ -n "$INPROGRESS_RECORD" ] && [ -n "$NARCOTIC_DRUG_ID" ]; then
  api POST "/v1/anesthesia/records/$INPROGRESS_RECORD/drugs" \
    "{\"drugName\":\"$NARCOTIC_DRUG_NAME\",\"dose\":0.1,\"unit\":\"mg\",\"route\":\"IV\",\"administeredAt\":\"$(iso 10)\",\"narcotic\":true,\"secondCheckerId\":\"$SELF_ID\",\"secondCheckerName\":\"演示麻醉医师（合成）\",\"note\":\"演示麻精用药（合成）\"}"
  is_2xx && ok "麻精用药（第二核对人 $SELF_ID）" || bad "麻精用药"
  api POST "/v1/anesthesia/records/$INPROGRESS_RECORD/drugs" \
    "{\"drugName\":\"丙泊酚（演示）\",\"dose\":120,\"unit\":\"mg\",\"route\":\"IV\",\"administeredAt\":\"$(iso 10)\",\"narcotic\":false,\"note\":\"演示诱导用药（合成）\"}"
  is_2xx && ok "非麻精用药（丙泊酚）" || bad "非麻精用药"
fi
# 锁定记录补记用药会被拒绝（状态机验证，不计失败）
if [ "${#LOCKED_RECORDS[@]}" -ge 1 ]; then
  api POST "/v1/anesthesia/records/${LOCKED_RECORDS[0]}/drugs" \
    "{\"drugName\":\"丙泊酚（演示）\",\"dose\":50,\"unit\":\"mg\",\"route\":\"IV\",\"administeredAt\":\"$(iso 10)\",\"narcotic\":false}"
  if [ "$HTTP" = "409" ]; then ok "锁单后用药被拒（409，状态机生效）"; else skip "锁单后用药未按预期拒绝"; fi
fi

# 4.3 抢救（进行中记录；药名使用已入库的演示升压药）
if [ -n "$INPROGRESS_RECORD" ] && [ "${#DRUG_IDS[@]}" -ge 5 ]; then
  body=$(cat <<JSON
{"resusType":"VASOPRESSOR","leader":{"userId":"$ADMIN_ID","name":"演示管理员（合成）"},"rescuers":[{"name":"演示护士（合成）"}],"startedAt":"$(iso 5)","endedAt":"$(iso 6)","eventSummary":{"summary":"演示低血压处理（合成）：补液并给予升压药后循环稳定。","interventions":["快速补液","升压药"]},"drugs":[{"drugName":"${DRUG_NAMES[4]}","dose":0.1,"unit":"mg","route":"IV","administeredAt":"$(iso 5)"}],"outcome":"SUCCESS"}
JSON
)
  api POST "/v1/anesthesia/records/$INPROGRESS_RECORD/resuscitations" "$body"
  is_2xx && ok "抢救记录（VASOPRESSOR，SUCCESS）" || bad "抢救记录"
fi

# ---------------------------------------------------------------------------
# 6. M2 复苏与随访
# ---------------------------------------------------------------------------
begin_section "M2 复苏与随访"

# 记录事件时间取「记录创建后 +3~8 秒」，先等待其成为过去，避免复苏/随访时间落在未来。
sleep 8
PACU_IDS=()
pacu_create() { # pacu_create REC_INDEX LABEL CONSCIOUSNESS PAIN
  local idx="$1" label="$2" cons="$3" pain="$4" rid="${RECORD_IDS[$1]:-}"
  [ -n "$rid" ] || { bad "$label：无记录单"; return 1; }
  body=$(cat <<JSON
{"recordId":"$rid","admittedAt":"$(iso -1)","vitalSigns":{"systolicBp":126,"diastolicBp":78,"heartRate":84,"spo2":98,"respiratoryRate":14,"temperature":36.6},"consciousness":"$cons","painScore":$pain,"nausea":false,"airway":"NATURAL"}
JSON
)
  api POST /v1/anesthesia/pacu-records "$body"
  if is_2xx; then
    PACU_IDS+=("$(jget pacuId)"); ok "$label 入复苏室"; return 0
  fi
  bad "$label 入复苏室"; return 1
}

pacu_assess() { # pacu_assess IDX ACTIVITY RESPIRATION CIRCULATION CONSCIOUSNESS OXYGENATION LABEL
  local pid="${PACU_IDS[$1]:-}"; [ -n "$pid" ] || { bad "$8：无复苏记录"; return 1; }
  body=$(cat <<JSON
{"assessedAt":"$(iso 0)","activity":$2,"respiration":$3,"circulation":$4,"consciousness":$5,"oxygenation":$6,"notes":"演示 Aldrete 评估（合成）"}
JSON
)
  api POST "/v1/anesthesia/pacu-records/$pid/assessments" "$body"
  if is_2xx; then ok "$7（Aldrete $(jget aldreteTotal)）"; return 0; fi
  bad "$7"; return 1
}

if [ "${#RECORD_IDS[@]}" -ge 5 ]; then
  pacu_create 0 "复苏 1" DROWSY 3 && pacu_assess 0 2 2 2 2 2 "复苏 1 评估（10 分）"
  pacu_create 1 "复苏 2" ALERT 2 && pacu_assess 1 2 2 2 1 2 "复苏 2 评估（9 分）"
  pacu_create 4 "复苏 3" SEDATED 4 && pacu_assess 2 2 2 2 2 1 "复苏 3 评估（9 分）"
  if [ "${#PACU_IDS[@]}" -ge 1 ]; then
    # 与 Aldrete 评估拉开 1 秒，避免同一秒内写评估+转出触发服务端竞态。
    sleep 1
    api POST "/v1/anesthesia/pacu-records/${PACU_IDS[0]}/discharge" \
      '{"destination":"WARD","handover":{"situation":"演示：术后循环稳定（合成）","background":"全麻术后，生命体征平稳","assessment":"Aldrete 10 分，疼痛可耐受","recommendation":"安返病房，继续观察"},"criteriaConfirmed":true}'
    case "$HTTP" in
    5??)
      sleep 1
      api POST "/v1/anesthesia/pacu-records/${PACU_IDS[0]}/discharge" \
        '{"destination":"WARD","handover":{"situation":"演示：术后循环稳定（合成）","background":"全麻术后，生命体征平稳","assessment":"Aldrete 10 分，疼痛可耐受","recommendation":"安返病房，继续观察"},"criteriaConfirmed":true}'
      ;;
    esac
    is_2xx && ok "复苏 1 转出（WARD）" || bad "复苏 1 转出"
  fi
fi

followup_create() { # followup_create REC_INDEX TYPE NRS PONV LABEL
  local idx="$1" type="$2" nrs="$3" ponv="$4" label="$5" rid="${RECORD_IDS[$1]:-}"
  [ -n "$rid" ] || { bad "$label：无记录单"; return 1; }
  body=$(cat <<JSON
{"anesthesiaRecordId":"$rid","followupType":"$type","assessedAt":"$(iso 0)","nrsScore":$nrs,"ponvScore":$ponv,"complications":[],"complicationNotes":"演示随访（合成）","patientFeedback":"演示反馈（合成）","followedUpBy":"演示随访者（合成）"}
JSON
)
  api POST /v1/anesthesia/followups "$body"
  if is_2xx; then
    FOLLOWUP_IDS+=("$(jget followupId)"); ok "$label（$type，NRS $nrs / PONV $ponv）"; return 0
  fi
  bad "$label"; return 1
}

FOLLOWUP_IDS=()
if [ "${#RECORD_IDS[@]}" -ge 5 ]; then
  followup_create 0 H24 3 1 "随访 1"
  followup_create 1 H48 5 2 "随访 2"
  followup_create 4 H24 2 0 "随访 3"
  if [ "${#FOLLOWUP_IDS[@]}" -ge 2 ]; then
    api POST "/v1/anesthesia/followups/${FOLLOWUP_IDS[0]}/pain-management" \
      "{\"modality\":\"PCIA\",\"drug\":\"舒芬太尼（演示）\",\"bolusDose\":1.0,\"backgroundDose\":0.5,\"lockoutMinutes\":15,\"effect\":\"EFFECTIVE\",\"adjustments\":[{\"adjustedAt\":\"$(iso 15)\",\"description\":\"演示调整（合成）\"}]}"
    is_2xx && ok "镇痛管理 1（PCIA 参数）" || bad "镇痛管理 1"
    api POST "/v1/anesthesia/followups/${FOLLOWUP_IDS[1]}/pain-management" \
      "{\"modality\":\"PCEA\",\"drug\":\"罗哌卡因（演示）\",\"bolusDose\":5.0,\"backgroundDose\":4.0,\"lockoutMinutes\":30,\"effect\":\"PARTIAL\",\"adjustments\":[{\"adjustedAt\":\"$(iso 15)\",\"description\":\"演示调整（合成）\"}]}"
    is_2xx && ok "镇痛管理 2（PCEA 参数）" || bad "镇痛管理 2"
  fi
fi

# ---------------------------------------------------------------------------
# 7. M4 人员（档案 / 分级授权 / 排班 / 培训）
# ---------------------------------------------------------------------------
begin_section "M4 人员（档案/授权/排班/培训）"

# 7.1 双人审批所需审批账号（SYS_ADMIN，SYNTHETIC）
for n in 1 2; do
  username="demo_approver_$n"
  api GET "/v1/auth/users?keyword=$username&page=1&size=10"
  uid=""
  if is_2xx; then
    uid="$(python3 - "$RESP_FILE" "$username" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    for it in d.get('items') or []:
        if it.get('username') == sys.argv[2]:
            print(it.get('userId','')); break
except Exception: pass
PY
)"
  fi
  if [ -n "$uid" ]; then
    APPROVER_IDS+=("$uid"); skip "审批账号已存在：$username"; continue
  fi
  api POST /v1/auth/users "{\"username\":\"$username\",\"displayName\":\"演示审批人$n（合成）\",\"employeeNo\":\"SYN-DEMO-90$n\",\"initialPassword\":\"Anesop@2026#Appr$n\",\"roles\":[\"SYS_ADMIN\"]}"
  if is_2xx; then
    APPROVER_IDS+=("$(jget userId)"); ok "创建审批账号：$username（SYS_ADMIN）"
  else
    bad "创建审批账号 $username"
  fi
done

# 7.2 人员档案
STAFF_NOS=(SYN-DOC-01 SYN-DOC-02 SYN-NURSE-01 SYN-DOC-03 SYN-NURSE-02 SYN-DOC-04)
STAFF_NAMES=("演示医师甲（合成）" "演示医师乙（合成）" "演示护士甲（合成）" "演示医师丙（合成）" "演示护士乙（合成）" "演示医师丁（合成）")
STAFF_TITLES=(主任医师 副主任医师 主管护师 主治医师 护师 住院医师)
for i in $(seq 0 $((STAFF_COUNT - 1))); do
  no="${STAFF_NOS[$i]}"; name="${STAFF_NAMES[$i]}"; title="${STAFF_TITLES[$i]}"
  api GET "/v1/staff?keyword=$no&page=1&size=10"
  sid=""
  if is_2xx; then
    sid="$(python3 - "$RESP_FILE" "$no" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    for it in d.get('items') or []:
        if it.get('staffNo') == sys.argv[2]:
            print(it.get('staffId','')); break
except Exception: pass
PY
)"
  fi
  if [ -n "$sid" ]; then STAFF_IDS+=("$sid"); skip "人员已存在：$no"; continue; fi
  body=$(cat <<JSON
{"staffNo":"$no","name":"$name","departmentId":"$DEPT_ANES","siteId":"$SITE_01","title":"$title","jobGrade":"G3","employmentStatus":"ACTIVE","specialties":["GENERAL","REGIONAL"],"qualifications":[{"qualificationType":"PRACTICE_LICENSE","certificateNo":"SYN-CERT-$no","issuingAuthority":"演示卫健委（合成）","validFrom":"2024-01-01","validTo":"2029-12-31","status":"ACTIVE"}]}
JSON
)
  api POST /v1/staff "$body"
  if is_2xx; then STAFF_IDS+=("$(jget staffId)"); ok "人员档案：$no / $name"; else bad "人员档案 $no"; fi
done

# 7.3 分级授权 R1–R5（R1 双人审批）
grant_level() { # grant_level STAFF_INDEX LEVEL DUAL LABEL [EMPTY_ONLY]
  local idx="$1" level="$2" dual="$3" label="$4" empty_only="${5:-0}"
  local sid="${STAFF_IDS[$idx]:-}"; [ -n "$sid" ] || { bad "$label：无人员"; return 1; }
  local current="0" current_level=""
  api GET "/v1/staff/$sid/authorizations"
  if is_2xx; then
    current="$(jget current.version)"; [ -z "$current" ] && current="0"
    current_level="$(jget current.level)"
  fi
  if [ "$empty_only" = "1" ] && [ -n "$current_level" ]; then
    skip "$label（已有分级授权 $current_level，历史已覆盖）"
    return 0
  fi
  if [ "$current_level" = "$level" ]; then
    skip "$label（当前已为 $level）"
    return 0
  fi
  local body approved="${APPROVER_IDS[0]:-}" second="${APPROVER_IDS[1]:-}"
  if [ -z "$approved" ] || [ -z "$second" ]; then bad "$label：缺少审批账号"; return 1; fi
  if [ "$dual" = "1" ]; then
    body=$(cat <<JSON
{"action":"GRANT","version":$current,"level":"$level","surgeryScopes":["GENERAL"],"ageMin":18,"ageMax":85,"asaMax":"ASA4","validFrom":"2026-01-01","validTo":"2027-12-31","approvedBy":"$approved","secondApproverId":"$second","secondApproverReason":"演示双人审批（合成）","changeReason":"演示分级授权（合成）"}
JSON
)
  else
    body=$(cat <<JSON
{"action":"GRANT","version":$current,"level":"$level","surgeryScopes":["GENERAL"],"ageMin":18,"ageMax":85,"asaMax":"ASA3","validFrom":"2026-01-01","validTo":"2027-12-31","approvedBy":"$approved","changeReason":"演示分级授权（合成）"}
JSON
)
  fi
  api PUT "/v1/staff/$sid/authorizations" "$body"
  if is_2xx; then
    ok "$label（$level，版本 $(jget current.version)，审批 $(jget current.approvedBy)）"
    return 0
  fi
  bad "$label"; return 1
}

if [ "${#STAFF_IDS[@]}" -ge 4 ] && [ "${#APPROVER_IDS[@]}" -ge 2 ]; then
  grant_level 0 R1 1 "分级授权 1（R1 双人）" 1
  grant_level 1 R2 1 "分级授权 2（R2 双人）"
  grant_level 2 R3 0 "分级授权 3（R3）"
  grant_level 3 R4 0 "分级授权 4（R4）"
  grant_level 0 R5 0 "分级授权 5（R5，覆盖 R1）"
else
  skip "分级授权（人员或审批账号不足）"
fi

# 7.4 排班（4 条）
if [ "${#STAFF_IDS[@]}" -ge 4 ]; then
  SHIFTS=(DAY NIGHT ON_CALL DAY)
  for i in 0 1 2 3; do
    sid="${STAFF_IDS[$i]}"
    day="$TODAY"; [ "$i" -ge 2 ] && day="$TOMORROW"
    api POST /v1/staff/schedules "{\"staffId\":\"$sid\",\"scheduleDate\":\"$day\",\"shift\":\"${SHIFTS[$i]}\",\"roomId\":\"${ROOM_IDS[$((i % ${#ROOM_IDS[@]}))]}\",\"departmentId\":\"$DEPT_ANES\",\"note\":\"演示排班（合成）\"}"
    if is_2xx; then ok "人员排班 ${SHIFTS[$i]}（$day）"; elif [ "$HTTP" = "409" ]; then skip "人员排班已存在"; else bad "人员排班 ${SHIFTS[$i]}"; fi
  done
fi

# 7.5 培训（4 条，1 条即将到期）
if [ "${#STAFF_IDS[@]}" -ge 4 ]; then
  for i in 0 1 2 3; do
    sid="${STAFF_IDS[$i]}"
    code="SYN-COURSE-$(printf '%02d' $((i + 1)))"
    name="演示培训课程$((i + 1))（合成）"
    completed="$TODAY"
    expires="$(iso_day $((86400 * 365)))"
    [ "$i" -eq 1 ] && expires="$(iso_day $((86400 * 20)))"
    api GET "/v1/staff/trainings?staffId=$sid&limit=50"
    exists=""
    if is_2xx; then
      exists="$(python3 - "$RESP_FILE" "$code" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    for it in d.get('items') or []:
        if it.get('courseCode') == sys.argv[2]:
            print('1'); break
except Exception: pass
PY
)"
    fi
    if [ "$exists" = "1" ]; then skip "培训已存在：$code"; continue; fi
    api POST /v1/staff/trainings "{\"staffId\":\"$sid\",\"courseCode\":\"$code\",\"courseName\":\"$name\",\"completedOn\":\"$completed\",\"expiresOn\":\"$expires\",\"certificateNo\":\"SYN-TC-$BATCH-$i\"}"
    if is_2xx; then ok "培训：$code（到期 $expires）"; else bad "培训 $code"; fi
  done
fi

# ---------------------------------------------------------------------------
# 8. M4 耗材（主数据 / 出入库 / UDI / SPD）
# ---------------------------------------------------------------------------
begin_section "M4 耗材（主数据/出入库/UDI/SPD）"

MATERIAL_CODES=(SYNTH-SEED-MAT-01 SYNTH-SEED-MAT-02 SYNTH-SEED-MAT-03 SYNTH-SEED-MAT-04 SYNTH-SEED-MAT-05 SYNTH-SEED-MAT-06)
MATERIAL_NAMES=("演示耗材缝合线（合成）" "演示耗材穿刺针（合成）" "演示耗材导管（合成）" "演示耗材电极片（合成）" "演示高值耗材（合成）" "演示耗材敷料（合成）")
MATERIAL_DI=(SYNTH-DI-MAT-01 SYNTH-DI-MAT-02 SYNTH-DI-MAT-03 SYNTH-DI-MAT-04 SYNTH-DI-MAT-05 SYNTH-DI-MAT-06)
for i in $(seq 0 $((MATERIAL_COUNT - 1))); do
  code="${MATERIAL_CODES[$i]}"
  api GET "/v1/materials?keyword=$code&page=1&size=5"
  mid=""
  if is_2xx; then
    mid="$(python3 - "$RESP_FILE" "$code" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    for it in d.get('items') or []:
        if it.get('code') == sys.argv[2]:
            print(it.get('materialId','')); break
except Exception: pass
PY
)"
  fi
  if [ -n "$mid" ]; then MATERIAL_IDS+=("$mid"); skip "耗材已存在：$code"; continue; fi
  hv=false; [ "$i" -eq 4 ] && hv=true
  body=$(cat <<JSON
{"code":"$code","name":"${MATERIAL_NAMES[$i]}","specification":"演示规格","unit":"个","category":"CONSUMABLE","udiDi":"${MATERIAL_DI[$i]}","highValue":$hv,"expiryManaged":true,"remark":"SYNTHETIC 演示耗材"}
JSON
)
  api POST /v1/materials "$body"
  if is_2xx; then MATERIAL_IDS+=("$(jget materialId)"); ok "耗材主数据：$code"; else bad "耗材主数据 $code"; fi
done

if [ "${#MATERIAL_IDS[@]}" -ge 5 ]; then
  # 高值耗材（第 5 个）必须逐件 UDI 出入库，禁止裸数量；其余耗材走批次出入库。
  for i in 0 1 2 3; do
    mid="${MATERIAL_IDS[$i]}"
    api POST /v1/materials/stock-movements "{\"movementType\":\"IN\",\"materialId\":\"$mid\",\"batchNo\":\"SYN-B-$BATCH-$i\",\"expiryDate\":\"2027-12-31\",\"quantity\":20,\"departmentId\":\"$DEPT_ANES\",\"counterparty\":\"演示供应商（合成）\",\"reason\":\"演示入库（合成）\"}"
    is_2xx && ok "入库 20：耗材 $((i + 1))" || bad "入库 耗材 $((i + 1))"
  done
  for i in 0 1; do
    mid="${MATERIAL_IDS[$i]}"
    api POST /v1/materials/stock-movements "{\"movementType\":\"OUT\",\"materialId\":\"$mid\",\"batchNo\":\"SYN-B-$BATCH-$i\",\"expiryDate\":\"2027-12-31\",\"quantity\":5,\"departmentId\":\"$DEPT_ANES\",\"counterparty\":\"演示手术间（合成）\",\"reason\":\"演示出库（合成）\"}"
    is_2xx && ok "出库 5：耗材 $((i + 1))" || bad "出库 耗材 $((i + 1))"
  done
  api POST /v1/materials/stock-movements "{\"movementType\":\"RETURN\",\"materialId\":\"${MATERIAL_IDS[0]}\",\"batchNo\":\"SYN-B-$BATCH-0\",\"expiryDate\":\"2027-12-31\",\"quantity\":1,\"departmentId\":\"$DEPT_ANES\",\"counterparty\":\"演示手术间（合成）\",\"reason\":\"演示退回（合成）\"}"
  is_2xx && ok "退回 1：耗材 1" || bad "退回"
  api POST /v1/materials/stock-movements "{\"movementType\":\"ADJUST\",\"materialId\":\"${MATERIAL_IDS[1]}\",\"batchNo\":\"SYN-B-$BATCH-1\",\"expiryDate\":\"2027-12-31\",\"quantity\":-1,\"departmentId\":\"$DEPT_ANES\",\"counterparty\":\"演示盘点（合成）\",\"reason\":\"演示盘亏（合成）\"}"
  is_2xx && ok "盘点调整 -1：耗材 2" || bad "盘点调整"
fi

# UDI 实例 5 条，其中 1 条使用
UDI_USED=""
if [ "${#MATERIAL_IDS[@]}" -ge 5 ]; then
  for i in 0 1 2 3 4; do
    mid="${MATERIAL_IDS[$i]}"
    di="${MATERIAL_DI[$i]}"
    pi="SYN-PI-$BATCH-$i"
    api POST /v1/materials/udi-instances "{\"materialId\":\"$mid\",\"udiDi\":\"$di\",\"udiPi\":\"$pi\",\"serialNumber\":\"SN-$BATCH-$i\",\"batchNo\":\"SYN-B-$BATCH-$i\",\"expiryDate\":\"2027-12-31\",\"departmentId\":\"$DEPT_ANES\",\"counterparty\":\"演示供应商（合成）\"}"
    if is_2xx; then
      ok "UDI 登记：$di^$pi"
      if [ "$i" -eq 0 ]; then UDI_USED="$di^$pi"; fi
    else
      bad "UDI 登记 $i"
    fi
  done
  if [ -n "$UDI_USED" ] && [ -n "$INPROGRESS_RECORD" ]; then
    udi_enc="${UDI_USED/\^/%5E}"
    api POST "/v1/materials/udi-instances/$udi_enc/use" "{\"recordId\":\"$INPROGRESS_RECORD\"}"
    is_2xx && ok "UDI 使用（绑定进行中记录）" || bad "UDI 使用"
  fi
fi

api POST /v1/materials/spd-adapter/sync
if is_2xx; then ok "SPD 模拟同步（SIMULATED）"; else bad "SPD 同步"; fi

# ---------------------------------------------------------------------------
# 9. M4 报表与质控
# ---------------------------------------------------------------------------
begin_section "M4 报表与质控"

api GET "/v1/reports/workload?from=$MONTH_FROM&to=$TODAY&dimension=DEPT"
is_2xx && ok "工作量报表（DEPT，台次 $(jget caseCount)）" || bad "工作量报表"
api GET "/v1/reports/multi?from=$MONTH_FROM&to=$TODAY&dimensions=DEPT,ROOM&metrics=caseCount,anesthesiaMinutes&page=1&size=10"
is_2xx && ok "多维报表（DEPT+ROOM）" || bad "多维报表"
api POST /v1/reports/locks "{\"report\":\"workload\",\"from\":\"$MONTH_FROM\",\"to\":\"$TODAY\",\"dimension\":\"DEPT\"}"
if is_2xx; then ok "报表锁定快照（workload）"; elif [ "$HTTP" = "409" ]; then skip "报表锁定快照已存在"; else bad "报表锁定快照"; fi

# 质控：不良事件 + RCA + 复核（阶段 1）
api POST /v1/qc/adverse-events "{\"eventType\":\"RESPIRATORY\",\"severity\":\"LEVEL_II\",\"occurredAt\":\"$(iso -7200)\",\"deptId\":\"$DEPT_ANES\",\"description\":\"演示不良事件（合成）：术后低氧，经处理后恢复，未造成损害。\",\"anonymous\":false}"
if is_2xx; then
  ae_id="$(jget id)"; ok "不良事件上报（$(jget eventNo)）"
  api POST "/v1/qc/adverse-events/$ae_id/rca" "{\"timeline\":[{\"occurredAt\":\"$(iso -7200)\",\"description\":\"事件发生\"},{\"occurredAt\":\"$(iso -7000)\",\"description\":\"处理并上报\"}],\"rootCause\":\"演示根因（合成）：气道分泌物增多\",\"correctiveAction\":\"演示整改（合成）：加强气道管理培训\",\"responsibleName\":\"演示负责人（合成）\",\"dueDate\":\"$(iso_day $((86400 * 30)))\"}"
  if is_2xx; then
    rca_id="$(jget id)"; ok "RCA 录入"
    api POST "/v1/qc/rca/$rca_id/close" "{\"reviewerName\":\"演示复核人（合成）\",\"conclusion\":\"RCA 复核通过，整改措施进入跟踪（阶段 1）\",\"rectificationCompleted\":false}"
    is_2xx && ok "RCA 复核闭环（阶段 1）" || bad "RCA 复核"
  else
    bad "RCA 录入"
  fi
else
  bad "不良事件上报"
fi

# 质控：病历抽查 + 整改 + 闭环
api POST /v1/qc/chart-reviews "{\"deptId\":\"$DEPT_ANES\",\"reviewedAt\":\"$(iso -3600)\",\"items\":[{\"itemCode\":\"COMPLETENESS\",\"itemName\":\"完整性\",\"score\":88,\"maxScore\":100,\"defectNote\":\"演示缺陷：术后随访记录缺项\"},{\"itemCode\":\"SIGNATURE\",\"itemName\":\"签名\",\"score\":95,\"maxScore\":100,\"defectNote\":\"演示缺陷：一处签名时间缺失\"}]}"
if is_2xx; then
  cr_id="$(jget id)"; ok "病历抽查（$(jget reviewNo)）"
  api POST "/v1/qc/chart-reviews/$cr_id/rectify" "{\"correctiveAction\":\"演示整改（合成）：补录随访并双人复核\",\"responsibleName\":\"演示负责人（合成）\",\"dueDate\":\"$(iso_day $((86400 * 14)))\"}"
  if is_2xx; then
    rect_id="$(jget id)"; ok "整改任务下发"
    api POST "/v1/qc/rectify/$rect_id/close" "{\"reviewerName\":\"演示复核人（合成）\",\"conclusion\":\"整改完成并复核通过\"}"
    is_2xx && ok "整改闭环（CLOSED）" || bad "整改闭环"
  else
    bad "整改任务"
  fi
else
  bad "病历抽查"
fi

# ---------------------------------------------------------------------------
# 10. M5 看板
# ---------------------------------------------------------------------------
begin_section "M5 看板（realtime/kpi/risk/resources）"

api GET /v1/dashboard/realtime
is_2xx && ok "实时看板（术间 $(jget roomStatusCounts.total) 间）" || bad "实时看板"
api GET "/v1/dashboard/kpi?date=$TODAY"
is_2xx && ok "KPI（台次 $(jget caseCount)）" || bad "KPI"
api GET /v1/dashboard/risk
is_2xx && ok "风险看板" || bad "风险看板"
api GET /v1/dashboard/resources
is_2xx && ok "资源看板" || bad "资源看板"

# ---------------------------------------------------------------------------
# 11. 消息中心（demo_self 未读 ≥1）
# ---------------------------------------------------------------------------
begin_section "消息中心（停台/改台/急诊通知）"

if login_account "$SELF_USER" "$SELF_OLD_PASS" "$SELF_NEW_PASS"; then
  TOKEN="$ACCOUNT_TOKEN"
  unread=0
  for _ in $(seq 1 15); do
    api GET "/v1/messages?unreadOnly=true"
    if is_2xx; then
      unread="$(jget unreadCount)"
      [ -z "$unread" ] && unread=0
      [ "$unread" -ge 1 ] && break
    fi
    sleep 2
  done
  if [ "$unread" -ge 1 ]; then
    ok "未读消息 $unread 条（$SELF_USER）"
    api GET /v1/messages
    if is_2xx; then ok "消息列表可查询（$(jget meta.total) 条）"; else bad "消息列表"; fi
  else
    bad "未读消息不足 1 条（停台/改台/急诊通知未投递）"
  fi
else
  skip "无法登录 $SELF_USER，跳过未读消息校验（口令见 DEMO_SELF_PASSWORD/NEW）"
fi

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
printf '\n================ 灌入汇总 ================\n'
printf '  API        : %s\n' "$BASE_URL"
printf '  批次        : %s\n' "$BATCH"
printf '  成功        : %d\n' "$OK"
printf '  跳过        : %d\n' "$SKIP"
printf '  失败        : %d\n' "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf '  失败项      :\n'
  for f in "${FAILURES[@]}"; do printf '    - %s\n' "$f"; done
  printf '=========================================\n'
  exit 1
fi
printf '  结果        : OK（全部为 SYNTHETIC 演示数据）\n'
printf '=========================================\n'
