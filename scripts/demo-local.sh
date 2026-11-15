#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 本地演示环境一键启动（无 Docker 场景）
#
# 用途：本机没有 PostgreSQL 服务、也没有 Docker 时，用仓库内已解压的
#       PostgreSQL 16（.cache/s0-12/pg16/pg）启动一个演示库，再启动 API。
#       前端仍按 docs/guides/quickstart.md 用 `pnpm -C apps/web dev`（5173，
#       /api 代理到 8080）。
#
# 前置：仓库内存在 .cache/s0-12/pg16/pg/bin/{initdb,postgres}（S0-12 实测包）
#       与已构建的 api-bootstrap jar；否则脚本会提示先执行 prepare。
#
# 用法（仓库根）：
#   bash scripts/demo-local.sh start     # 初始化（首次）→ 启动 PG(55432) → 启动 API(8080)
#   bash scripts/demo-local.sh stop      # 停止 API 与 PG
#   bash scripts/demo-local.sh status    # 查看状态、健康检查与网关令牌探针
#   bash scripts/demo-local.sh seed      # 仅初始化演示库（迁移 + demo 账号）
#   bash scripts/demo-local.sh --help    # 显示帮助
#
# 网关令牌（仅 dev/demo）：start 默认注入
#   ANESOP_M2_ACQUISITION_GATEWAY_TOKEN=demo-gateway-token
# 可用同名环境变量覆盖；生产必须由密钥托管注入随机值，禁止使用本默认值。
#
# 演示账号：demo_admin / demo_dept / demo_self，初始口令 Anesop@2026，
#           首次登录必须改密（详见 docs/db/seed.md、docs/guides/demo.md）。
# 数据全部为 SYNTHETIC；禁止在生产/预发库执行。
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2
source scripts/env.sh >/dev/null 2>&1 || true

PGBIN="$ROOT/.cache/s0-12/pg16/pg/bin"
PGLIB="$ROOT/.cache/s0-12/pg16/pg/lib"
PGROOTDIR="$ROOT/.cache/demo-pg"
PGDATA="$PGROOTDIR/data"
PGPORT="${ANESOP_DEMO_PG_PORT:-55432}"
API_PORT="${ANESOP_DEMO_API_PORT:-8080}"
DB_NAME=anesop
DB_USER=anesop
# 仅 dev/demo：默认演示网关令牌，便于本地 ingest / 告警演示；生产必须用密钥托管注入。
GATEWAY_TOKEN="${ANESOP_M2_ACQUISITION_GATEWAY_TOKEN:-demo-gateway-token}"
GATEWAY_TOKEN_IS_DEFAULT=0
if [ -z "${ANESOP_M2_ACQUISITION_GATEWAY_TOKEN:-}" ]; then
  GATEWAY_TOKEN_IS_DEFAULT=1
fi
JAR=$(ls apps/api/api-bootstrap/target/api-bootstrap-*.jar 2>/dev/null | head -n1)
API_LOG="$PGROOTDIR/api.log"
PG_LOG="$PGROOTDIR/pg.log"
PID_DIR="$PGROOTDIR/pids"
API_PID="$PID_DIR/api.pid"

info() { printf '[demo-local] %s\n' "$*"; }
fail() { printf '[demo-local] ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'USAGE'
用法：bash scripts/demo-local.sh {start|stop|status|seed|--help}

  start    初始化（首次）→ 启动 PostgreSQL(55432) → 启动 API(8080)
  stop     停止 API 与 PostgreSQL（不删除数据）
  status   查看状态、健康检查与网关令牌探针（404=令牌通过且设备未登记；401=未注入/不匹配）
  seed     仅初始化演示库（迁移 + demo 账号）
  --help   显示本帮助

环境变量：
  ANESOP_DEMO_PG_PORT          演示库端口，默认 55432
  ANESOP_DEMO_API_PORT         API 端口，默认 8080
  ANESOP_M2_ACQUISITION_GATEWAY_TOKEN
                               采集网关令牌；start 默认 demo-gateway-token（仅 dev/demo），
                               生产必须由密钥托管注入随机值，禁止使用默认值。

说明：数据全部为 SYNTHETIC；start 默认注入演示网关令牌，仅用于本地采集/告警演示。
USAGE
}

token_probe() {
  # 用未登记探针设备验证网关令牌：404=令牌通过（设备未登记），401=未注入/不匹配。
  # 不写业务数据；只在 API 可达时调用。
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
    -X POST "http://127.0.0.1:$API_PORT/v1/devices/GW-TOKEN-PROBE/SIM-TOKEN-PROBE/ingest" \
    -H "X-Gateway-Token: $GATEWAY_TOKEN" -H 'Content-Type: application/json' \
    -d '{"batchKey":"status-probe","frames":[{"seq":0,"measuredAt":"2026-01-01T00:00:00+08:00","quality":0}]}' \
    2>/dev/null) || code=000
  case "$code" in
    404) info "网关令牌：通过（探针 404=设备未登记）" ;;
    401) info "网关令牌：未注入或不匹配（探针 401）—— 需 stop 后重新 start" ;;
    000) info "网关令牌：无法探针（API 不可达）" ;;
    *)   info "网关令牌：探针 HTTP $code" ;;
  esac
}

pg_ready() {
  "$PGBIN/postgres" --version >/dev/null 2>&1 || return 1
  (exec 3<>"/dev/tcp/127.0.0.1/$PGPORT") >/dev/null 2>&1 || [ -S "$PGROOTDIR/.s.PGSQL.$PGPORT" ]
}

prepare_pg() {
  command -v "$PGBIN/postgres" >/dev/null 2>&1 || [ -x "$PGBIN/postgres" ] || \
    fail "找不到 PostgreSQL 16 二进制（$PGBIN）。请先按 S0-12 说明解压，或使用系统 PostgreSQL 16。"
  mkdir -p "$PGROOTDIR" "$PGDATA" "$PID_DIR"
  if [ ! -f "$PGDATA/PG_VERSION" ]; then
    info "初始化演示库数据目录：$PGDATA"
    "$PGBIN/initdb" -D "$PGDATA" -U "$DB_USER" --auth=trust --encoding=UTF8 --locale=C >"$PGROOTDIR/initdb.log" 2>&1 \
      || fail "initdb 失败，见 $PGROOTDIR/initdb.log"
  fi
}

start_pg() {
  prepare_pg
  if pg_ready; then
    info "PostgreSQL 已在 127.0.0.1:$PGPORT 运行"
    return 0
  fi
  info "启动 PostgreSQL 16（port=$PGPORT）"
  LD_LIBRARY_PATH="$PGLIB" nohup "$PGBIN/postgres" -D "$PGDATA" -p "$PGPORT" \
    -k "$PGROOTDIR" -c listen_addresses=127.0.0.1 -c fsync=off -c full_page_writes=off \
    >"$PG_LOG" 2>&1 &
  echo $! > "$PID_DIR/pg.pid"
  for _ in $(seq 1 30); do pg_ready && break; sleep 1; done
  pg_ready || fail "PostgreSQL 启动失败，见 $PG_LOG"
}

ensure_db_and_seed() {
  # 用 JDBC 创建库（仓库内已有驱动），避免依赖 psql（S0-12 包不含 psql）
  local cp="$ROOT/.m2repo/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar"
  [ -f "$cp" ] || fail "缺少 PostgreSQL JDBC 驱动：$cp"
  mkdir -p "$PGROOTDIR/java"
  cat > "$PGROOTDIR/java/CreateDb.java" <<'JAVA'
import java.sql.*;
public class CreateDb {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + a[0] + "/postgres", a[1], "")) {
      try (ResultSet rs = c.createStatement().executeQuery("SELECT 1 FROM pg_database WHERE datname='anesop'")) {
        if (!rs.next()) {
          c.createStatement().execute("CREATE DATABASE anesop OWNER " + a[1]);
          System.out.println("[demo-local] database anesop created");
        }
      }
    }
  }
}
JAVA
  java -cp "$cp" "$PGROOTDIR/java/CreateDb.java" "$PGPORT" "$DB_USER" || fail "创建演示库失败"
  info "执行演示迁移与种子（db/migration + db/seed/demo）"
  bash scripts/seed-demo.sh --url "jdbc:postgresql://127.0.0.1:$PGPORT/$DB_NAME" \
    --user "$DB_USER" --password "$DB_USER" || fail "seed-demo 失败"
}

api_ready() {
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$API_PORT/actuator/health" 2>/dev/null)" = "200" ]
}

start_api() {
  [ -n "$JAR" ] || fail "找不到 api-bootstrap jar；先执行 ./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package"
  if api_ready; then
    info "API 已在 http://127.0.0.1:$API_PORT 运行"
    if [ "$GATEWAY_TOKEN_IS_DEFAULT" = "1" ]; then
      info "注意：API 不是本次启动的；若它启动时未注入网关令牌，设备 ingest 会 401。可用 stop 后重新 start（不删数据，仅重启）。"
    fi
    return 0
  fi
  info "启动 API（profile=dev, port=$API_PORT, db=127.0.0.1:$PGPORT/$DB_NAME）"
  info "注入采集网关令牌：$([ "$GATEWAY_TOKEN_IS_DEFAULT" = "1" ] && echo '默认演示值（仅 dev/demo，生产禁止）' || echo '来自 ANESOP_M2_ACQUISITION_GATEWAY_TOKEN')"
  ANESOP_M2_ACQUISITION_GATEWAY_TOKEN="$GATEWAY_TOKEN" \
  ANESOP_DB_URL="jdbc:postgresql://127.0.0.1:$PGPORT/$DB_NAME" \
  ANESOP_DB_USER="$DB_USER" ANESOP_DB_PASSWORD="$DB_USER" \
    nohup java -jar "$JAR" --spring.profiles.active=dev --server.port="$API_PORT" \
    >"$API_LOG" 2>&1 &
  echo $! > "$API_PID"
  for _ in $(seq 1 60); do api_ready && break; sleep 2; done
  api_ready || fail "API 启动失败，见 $API_LOG"
}

stop_all() {
  if [ -f "$API_PID" ] && kill -0 "$(cat "$API_PID")" 2>/dev/null; then
    info "停止 API（pid=$(cat "$API_PID")）"
    kill "$(cat "$API_PID")" 2>/dev/null || true
    rm -f "$API_PID"
  fi
  if [ -f "$PID_DIR/pg.pid" ] && kill -0 "$(cat "$PID_DIR/pg.pid")" 2>/dev/null; then
    info "停止 PostgreSQL（pid=$(cat "$PID_DIR/pg.pid")）"
    kill "$(cat "$PID_DIR/pg.pid")" 2>/dev/null || true
    rm -f "$PID_DIR/pg.pid"
  fi
}

status_all() {
  if pg_ready; then info "PostgreSQL: UP (127.0.0.1:$PGPORT)"; else info "PostgreSQL: DOWN"; fi
  if api_ready; then
    info "API: UP (http://127.0.0.1:$API_PORT, health=200)"
    token_probe
  else
    info "API: DOWN"
  fi
}

case "${1:-start}" in
  start)
    start_pg
    ensure_db_and_seed
    start_api
    status_all
    cat <<'TIP'

演示账号（SYNTHETIC）：
  demo_admin  Anesop@2026  SYS_ADMIN/ALL
  demo_dept   Anesop@2026  SYS_OR_MANAGER/DEPT
  demo_self   Anesop@2026  SYS_ANESTHESIOLOGIST/SELF
首次登录必须改密；前端：source scripts/env.sh && pnpm -C apps/web dev → http://localhost:5173
设备/告警演示：bash scripts/seed-demo-data.sh --section alerts（网关令牌默认 demo-gateway-token，仅 dev）
停止：bash scripts/demo-local.sh stop
TIP
    ;;
  stop) stop_all ;;
  status) status_all ;;
  seed) start_pg; ensure_db_and_seed ;;
  --help|-h) usage ;;
  *)
    usage >&2
    exit 2
    ;;
esac
