#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 集成联调冒烟（M5-M19-01）
#
# 端到端流程（可重复运行）：
#   1) 启动 HIS/药房/SPD 模拟器（services/simulators，SYNTHETIC）；
#   2) 启动独立 PostgreSQL 16（Zonky 嵌入式，Docker 不可用 → EXT-08）；
#   3) 以 test profile 启动 API（api-bootstrap.jar，真实 Flyway 迁移 + 演示种子）；
#   4) 调用关键流程：健康检查 → 登录 → 药房/SPD 适配器状态 → 模拟器目录映射 →
#      创建药品/耗材 → HIS 申请导入 → 申请列表；
#   5) 停止 API、PG、模拟器（trap，异常也会清理）。
#
# 前置条件：
#   - 已 `source scripts/env.sh` 或本脚本自动 source（JDK 21、仓库内 Maven 仓库）；
#   - 首次运行会构建 simulator 模块与 apps/api（约 2–5 分钟），之后复用 target 产物；
#   - 端口 18080/18081/18082/18083 空闲（可用 SMOKE_API_PORT / SIM_*_PORT 覆盖）；
#   - curl + python3 可用；不需要 Docker、不需要真实 HIS/药房/SPD（EXT-01/02/03/04）。
#
# 用法：bash scripts/integration-smoke.sh [--rebuild]
# 退出码：0 全部断言通过；1 任一失败。
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SMOKE_DIR="$ROOT/tmp/integration-smoke"
API_PORT="${SMOKE_API_PORT:-18080}"
API_BASE="http://127.0.0.1:$API_PORT"
API_JAR="$ROOT/apps/api/api-bootstrap/target/api-bootstrap-1.0.0-SNAPSHOT.jar"
REBUILD=0
if [ "${1:-}" = "--rebuild" ]; then
  REBUILD=1
fi

mkdir -p "$SMOKE_DIR"
API_LOG="$SMOKE_DIR/api.log"
PG_LOG="$SMOKE_DIR/postgres.log"
API_PID=""
PG_PID=""
STARTED_SIMULATORS=0

# shellcheck source=scripts/env.sh
source "$ROOT/scripts/env.sh" >/dev/null

fail() {
  echo "[integration-smoke] FAIL: $*" >&2
  echo "--- API 日志尾部 ---" >&2
  tail -n 30 "$API_LOG" 2>/dev/null >&2 || true
  echo "--- PG 日志尾部 ---" >&2
  tail -n 15 "$PG_LOG" 2>/dev/null >&2 || true
  exit 1
}

ok() { echo "[integration-smoke] OK $*"; }

cleanup() {
  local rc=$?
  if [ -n "$API_PID" ] && kill -0 "$API_PID" 2>/dev/null; then
    kill "$API_PID" 2>/dev/null || true
    wait "$API_PID" 2>/dev/null || true
  fi
  if [ -n "$PG_PID" ] && kill -0 "$PG_PID" 2>/dev/null; then
    kill "$PG_PID" 2>/dev/null || true
    wait "$PG_PID" 2>/dev/null || true
  fi
  if [ "$STARTED_SIMULATORS" -eq 1 ]; then
    bash "$ROOT/scripts/simulators.sh" stop >/dev/null 2>&1 || true
  fi
  exit "$rc"
}
trap cleanup EXIT INT TERM

wait_http_json_contains() {
  local url="$1" needle="$2" timeout_s="$3" waited=0
  while [ "$waited" -lt "$timeout_s" ]; do
    if curl -fsS --max-time 2 "$url" 2>/dev/null | grep -q "$needle"; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

json_field() {
  python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1"
}

echo "[integration-smoke] 1/5 启动模拟器（SYNTHETIC）"
SIM_JSON="$(bash "$ROOT/scripts/simulators.sh" start)"
STARTED_SIMULATORS=1
HIS_BASE="$(printf '%s' "$SIM_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); print([s["baseUrl"] for s in d["servers"] if s["name"]=="his"][0])')"
PHARMACY_BASE="$(printf '%s' "$SIM_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); print([s["baseUrl"] for s in d["servers"] if s["name"]=="pharmacy"][0])')"
SPD_BASE="$(printf '%s' "$SIM_JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); print([s["baseUrl"] for s in d["servers"] if s["name"]=="spd"][0])')"
ok "模拟器已启动 his=$HIS_BASE pharmacy=$PHARMACY_BASE spd=$SPD_BASE"

HIS_APPS="$(curl -fsS "$HIS_BASE/his/v1/surgery-applications?count=2&seed=7")"
printf '%s' "$HIS_APPS" | grep -q '"simulated":true' || fail "HIS 模拟器未标记 simulated=true"
printf '%s' "$HIS_APPS" | grep -q '"applicationNo"' || fail "HIS 模拟器返回缺少 applicationNo"
ok "HIS 申请查询 count=2 simulated=true"

PHARMACY_DRUGS="$(curl -fsS "$PHARMACY_BASE/pharmacy/v1/drugs")"
printf '%s' "$PHARMACY_DRUGS" | grep -q '"narcoticFlag"' || fail "药房目录缺少 narcoticFlag"
SPD_MATERIALS="$(curl -fsS "$SPD_BASE/spd/v1/materials")"
printf '%s' "$SPD_MATERIALS" | grep -q '"udiDi"' || fail "SPD 目录缺少 udiDi"
ok "药房目录/SPD 目录字段可用"

SPD_SYNC_1="$(curl -fsS -X POST "$SPD_BASE/spd/v1/sync" -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"SYNTH-SMOKE-KEY-1","since":"2026-11-01T00:00:00+08:00"}')"
SPD_SYNC_2="$(curl -fsS -X POST "$SPD_BASE/spd/v1/sync" -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"SYNTH-SMOKE-KEY-1","since":"2026-11-01T00:00:00+08:00"}')"
[ "$SPD_SYNC_1" = "$SPD_SYNC_2" ] || fail "SPD 同 idempotencyKey 重放回执不一致"
ok "SPD 同步幂等（同 key 同回执）"

echo "[integration-smoke] 2/5 准备构建产物"
if [ "$REBUILD" -eq 1 ] || [ ! -f "$ROOT/apps/api/api-bootstrap/target/test-classes/com/anesop/api/integration/SmokePostgresMain.class" ]; then
  echo "[integration-smoke] 安装 simulator 构件并构建 apps/api（首次较慢）..."
  "$ROOT/scripts/mvn.sh" -B -q -f "$ROOT/services/simulators/pom.xml" install -DskipTests
  "$ROOT/scripts/mvn.sh" -B -q -f "$ROOT/apps/api/pom.xml" install -DskipTests \
    -Dcheckstyle.skip=true -Dspotbugs.skip=true -Djacoco.skip=true
fi
[ -f "$API_JAR" ] || fail "缺少 $API_JAR（请运行 --rebuild）"

API_TEST_CP_FILE="$SMOKE_DIR/api-test-classpath.txt"
if [ "$REBUILD" -eq 1 ] || [ ! -s "$API_TEST_CP_FILE" ]; then
  "$ROOT/scripts/mvn.sh" -B -q -f "$ROOT/apps/api/api-bootstrap/pom.xml" dependency:build-classpath \
    -Dmdep.outputFile="$API_TEST_CP_FILE" -Dmdep.includeScope=test
fi

echo "[integration-smoke] 3/5 启动独立 PostgreSQL 16（Zonky 嵌入式，EXT-08）"
PG_CP="$ROOT/apps/api/api-bootstrap/target/test-classes:$(cat "$API_TEST_CP_FILE")"
"$JAVA_HOME/bin/java" -cp "$PG_CP" com.anesop.api.integration.SmokePostgresMain \
  >"$PG_LOG" 2>&1 &
PG_PID=$!
JDBC_URL=""
for _ in $(seq 1 60); do
  if grep -q '^JDBC_URL=' "$PG_LOG" 2>/dev/null; then
    JDBC_URL="$(grep '^JDBC_URL=' "$PG_LOG" | head -1 | cut -d= -f2-)"
    break
  fi
  kill -0 "$PG_PID" 2>/dev/null || fail "PostgreSQL 辅助进程已退出"
  sleep 1
done
[ -n "$JDBC_URL" ] || fail "未取得嵌入式 PostgreSQL JDBC URL"
ok "PostgreSQL 16 已启动（$JDBC_URL）"

echo "[integration-smoke] 4/5 启动 API（test profile，真实 Flyway）"
"$JAVA_HOME/bin/java" -jar "$API_JAR" \
  --spring.profiles.active=test \
  --server.port="$API_PORT" \
  --spring.datasource.url="$JDBC_URL" \
  --spring.datasource.username=postgres \
  --spring.datasource.password= \
  --anesop.security.rate-limit.capacity=100000 \
  --anesop.security.rate-limit.refill-tokens=100000 \
  --anesop.m1.notification.scheduler-enabled=false \
  >"$API_LOG" 2>&1 &
API_PID=$!
wait_http_json_contains "$API_BASE/actuator/health" '"status":"UP"' 120 \
  || fail "API 未在 120s 内就绪（health UP）"
ok "API health UP（$API_BASE，test profile）"

TOKEN="$(curl -fsS -X POST "$API_BASE/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo_admin","password":"Anesop@2026","authType":"LOCAL"}' | json_field accessToken)"
[ -n "$TOKEN" ] || fail "登录未返回 accessToken"
ok "演示账号首次登录成功（demo_admin，SYNTHETIC）"

# 演示种子 must_change_password=true：先改密（否则其它接口 403），再用新口令重新登录。
PASSWORD_CODE="$(curl -sS -o "$SMOKE_DIR/password-change.json" -w '%{http_code}' \
  -X POST "$API_BASE/v1/auth/password" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"oldPassword":"Anesop@2026","newPassword":"Smoke@2026-Synthetic"}')"
[ "$PASSWORD_CODE" = "204" ] || fail "首次登录改密失败（HTTP $PASSWORD_CODE）"
TOKEN="$(curl -fsS -X POST "$API_BASE/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo_admin","password":"Smoke@2026-Synthetic","authType":"LOCAL"}' | json_field accessToken)"
[ -n "$TOKEN" ] || fail "改密后重新登录未返回 accessToken"
ok "首次登录改密并重新登录成功（demo_admin，SYNTHETIC）"

PHARMACY_STATUS="$(curl -sS -w $'\n%{http_code}' "$API_BASE/v1/drugs/pharmacy-adapter/status" \
  -H "Authorization: Bearer $TOKEN")"
PHARMACY_STATUS_CODE="${PHARMACY_STATUS##*$'\n'}"
PHARMACY_STATUS_BODY="${PHARMACY_STATUS%$'\n'*}"
[ "$PHARMACY_STATUS_CODE" = "200" ] || fail "药房适配器状态 HTTP $PHARMACY_STATUS_CODE：$PHARMACY_STATUS_BODY"
printf '%s' "$PHARMACY_STATUS_BODY" | grep -q '"adapterCode":"EXT-02"' \
  || fail "药房适配器状态未知（期望 EXT-02/SIMULATED）：$PHARMACY_STATUS_BODY"
ok "GET /v1/drugs/pharmacy-adapter/status → EXT-02"

SPD_STATUS="$(curl -sS -w $'\n%{http_code}' "$API_BASE/v1/materials/spd-adapter/status" \
  -H "Authorization: Bearer $TOKEN")"
SPD_STATUS_CODE="${SPD_STATUS##*$'\n'}"
SPD_STATUS_BODY="${SPD_STATUS%$'\n'*}"
[ "$SPD_STATUS_CODE" = "200" ] || fail "SPD 适配器状态 HTTP $SPD_STATUS_CODE：$SPD_STATUS_BODY"
printf '%s' "$SPD_STATUS_BODY" | grep -q '"adapterCode":"EXT-03"' \
  || fail "SPD 适配器状态未知（期望 EXT-03/SIMULATED）：$SPD_STATUS_BODY"
ok "GET /v1/materials/spd-adapter/status → EXT-03"

printf '%s' "$PHARMACY_DRUGS" | python3 -c '
import json,sys
drug = json.load(sys.stdin)["drugs"][0]
print(json.dumps({
    "code": drug["drugCode"],
    "name": drug["drugName"],
    "genericName": drug["commonName"],
    "specification": drug["spec"],
    "unit": drug["unit"],
    "dosageForm": drug["doseForm"],
    "category": drug["drugClass"],
    "narcotic": drug["narcoticFlag"] == 1,
    "highRisk": drug["highRiskFlag"] == 1,
    "aliases": drug["aliasList"],
    "remark": "SYNTHETIC：联调冒烟（EXT-02 模拟器目录）"
}, ensure_ascii=False))' >"$SMOKE_DIR/drug-create.json"
DRUG_CODE="$(curl -sS -o "$SMOKE_DIR/drug-create-response.json" -w '%{http_code}' \
  -X POST "$API_BASE/v1/drugs" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary "@$SMOKE_DIR/drug-create.json")"
case "$DRUG_CODE" in
  201 | 409) ;;
  *) fail "药房目录映射创建药品失败（HTTP $DRUG_CODE）" ;;
esac
curl -fsS "$API_BASE/v1/drugs?size=100" -H "Authorization: Bearer $TOKEN" \
  | grep -q '"code":"PMS-D-0001"' || fail "药房目录映射的药品未在平台字典中"
ok "药房目录 → POST /v1/drugs（HTTP $DRUG_CODE）→ 字典可查"

printf '%s' "$SPD_MATERIALS" | python3 -c '
import json,sys
material = json.load(sys.stdin)["materials"][0]
print(json.dumps({
    "code": material["materialCode"],
    "name": material["materialName"],
    "specification": material["specification"],
    "unit": material["unit"],
    "category": material["categoryCode"],
    "udiDi": material["udiDi"],
    "highValue": material["highValueFlag"] == 1,
    "expiryManaged": material["expiryManagedFlag"] == 1,
    "remark": "SYNTHETIC：联调冒烟（EXT-03 模拟器目录）"
}, ensure_ascii=False))' >"$SMOKE_DIR/material-create.json"
MATERIAL_CODE="$(curl -sS -o "$SMOKE_DIR/material-create-response.json" -w '%{http_code}' \
  -X POST "$API_BASE/v1/materials" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary "@$SMOKE_DIR/material-create.json")"
case "$MATERIAL_CODE" in
  201 | 409) ;;
  *) fail "SPD 目录映射创建耗材失败（HTTP $MATERIAL_CODE）" ;;
esac
curl -fsS "$API_BASE/v1/materials?size=100" -H "Authorization: Bearer $TOKEN" \
  | grep -q '"code":"SPD-M-0001"' || fail "SPD 目录映射的耗材未在平台目录中"
ok "SPD 目录 → POST /v1/materials（HTTP $MATERIAL_CODE）→ 目录可查"

IMPORT_JSON="$(curl -fsS -X POST "$API_BASE/v1/scheduling/applications/import" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"count":2,"seed":20260101}')"
printf '%s' "$IMPORT_JSON" | grep -q '"simulated":true' || fail "HIS 申请导入未标记 simulated=true"
ok "HIS 申请导入（POST /v1/scheduling/applications/import，simulated=true）"

APPLICATIONS="$(curl -fsS "$API_BASE/v1/scheduling/applications?size=5" -H "Authorization: Bearer $TOKEN")"
printf '%s' "$APPLICATIONS" | grep -q '"items"' || fail "申请列表查询失败"
ok "手术申请列表可查（GET /v1/scheduling/applications）"

echo "[integration-smoke] 5/5 清理（API / PG / 模拟器）"
echo "[integration-smoke] OK（模拟器 + API + 关键流程全部通过，SYNTHETIC）"
