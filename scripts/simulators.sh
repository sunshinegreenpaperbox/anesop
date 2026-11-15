#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 联调模拟器启停脚本（M5-M19-01）
#
# 启动 HIS / 药房 / SPD 三个 HTTP 模拟服务（services/simulators，SYNTHETIC 数据），
# 设备路径复用 services/device-gateway 的 SimulatedDevice（TCP 协议 v1 客户端）。
#
# 用法：
#   bash scripts/simulators.sh start          # 后台启动，输出 JSON 状态；已运行则复用
#   bash scripts/simulators.sh status         # 输出 JSON 状态（含进程存活）
#   bash scripts/simulators.sh stop           # 停止并清理 pid/状态文件
#   bash scripts/simulators.sh restart        # stop + start
#   bash scripts/simulators.sh selftest       # 前台自检（端口 0，启动后立即停止）
#   bash scripts/simulators.sh logs           # 查看后台日志尾部
#   bash scripts/simulators.sh device [host] [port] [deviceId] [frames] [intervalMs]
#                                             # 运行设备模拟器（推帧到网关，前台）
#
# 配置：
#   SIMULATORS_CONFIG   JSON 配置（默认 services/simulators/config/simulators.json）
#   SIM_HIS_PORT / SIM_PHARMACY_PORT / SIM_SPD_PORT  端口覆盖（默认 18081/18082/18083）
#
# 约束：只绑定 127.0.0.1；只产生 SYNTHETIC 数据；不连接任何真实外部系统（EXT-01/02/03/04）。
# 退出码：0 成功；1 启动失败/未运行；2 参数错误。
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SIM="$ROOT/services/simulators"
GATEWAY="$ROOT/services/device-gateway"
RUN_DIR="$ROOT/tmp/simulators"
PID_FILE="$RUN_DIR/simulators.pid"
STATUS_FILE="$RUN_DIR/status.json"
LOG_FILE="$RUN_DIR/simulators.log"
CONFIG="${SIMULATORS_CONFIG:-$SIM/config/simulators.json}"
HIS_PORT="${SIM_HIS_PORT:-18081}"
PHARMACY_PORT="${SIM_PHARMACY_PORT:-18082}"
SPD_PORT="${SIM_SPD_PORT:-18083}"

mkdir -p "$RUN_DIR"

# 统一工具链与仓库内缓存（home 只读）。env.sh 会打印工具链信息，重定向避免污染 JSON 输出。
# shellcheck source=scripts/env.sh
source "$ROOT/scripts/env.sh" >/dev/null

usage() {
  sed -n '2,31p' "$0"
}

build_simulators() {
  local need=0
  [ -f "$SIM/target/classes/com/anesop/simulators/SimulatorsMain.class" ] || need=1
  [ -s "$SIM/target/classpath.txt" ] || need=1
  if [ "$need" -eq 1 ]; then
    echo "[simulators] 构建模拟器模块（无新增依赖）..." >&2
    "$ROOT/scripts/mvn.sh" -B -q -f "$SIM/pom.xml" -DskipTests package
    "$ROOT/scripts/mvn.sh" -B -q -f "$SIM/pom.xml" dependency:build-classpath \
      -Dmdep.outputFile="$SIM/target/classpath.txt" -Dmdep.includeScope=runtime
  fi
  if [ ! -s "$SIM/target/classpath.txt" ]; then
    echo "[simulators] 缺少 $SIM/target/classpath.txt" >&2
    exit 1
  fi
}

simulator_classpath() {
  printf '%s:%s' "$SIM/target/classes" "$(cat "$SIM/target/classpath.txt")"
}

is_running() {
  [ -s "$PID_FILE" ] || return 1
  local pid
  pid="$(cat "$PID_FILE")"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

start() {
  if is_running; then
    echo "[simulators] 已在运行（pid $(cat "$PID_FILE")），复用现有实例" >&2
    cat "$STATUS_FILE" 2>/dev/null || status
    return 0
  fi
  build_simulators
  rm -f "$STATUS_FILE" "$PID_FILE"
  nohup "$JAVA_HOME/bin/java" -cp "$(simulator_classpath)" \
    com.anesop.simulators.SimulatorsMain \
    "--config=$CONFIG" \
    "--status-file=$STATUS_FILE" \
    "--his-port=$HIS_PORT" \
    "--pharmacy-port=$PHARMACY_PORT" \
    "--spd-port=$SPD_PORT" \
    >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"

  local waited=0
  while [ "$waited" -lt 300 ]; do
    if [ -s "$STATUS_FILE" ]; then
      cat "$STATUS_FILE"
      return 0
    fi
    if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
      echo "[simulators] 启动失败，日志：" >&2
      tail -n 30 "$LOG_FILE" >&2
      rm -f "$PID_FILE"
      exit 1
    fi
    sleep 0.1
    waited=$((waited + 1))
  done
  echo "[simulators] 启动超时（30s），日志：" >&2
  tail -n 30 "$LOG_FILE" >&2
  exit 1
}

status() {
  python3 - "$PID_FILE" "$STATUS_FILE" <<'PY'
import json
import os
import pathlib
import sys

pid_file = pathlib.Path(sys.argv[1])
status_file = pathlib.Path(sys.argv[2])
pid = None
alive = False
if pid_file.exists():
    try:
        pid = int(pid_file.read_text().strip())
    except ValueError:
        pid = None
if pid is not None:
    try:
        os.kill(pid, 0)
        alive = True
    except OSError:
        alive = False
data = {}
if status_file.exists():
    try:
        data = json.loads(status_file.read_text())
    except json.JSONDecodeError:
        data = {}
data["pid"] = pid
data["processAlive"] = alive
if not alive:
    data["status"] = "DOWN"
print(json.dumps(data, ensure_ascii=False, indent=2))
PY
}

stop() {
  local stopped=false
  if is_running; then
    local pid
    pid="$(cat "$PID_FILE")"
    kill "$pid" 2>/dev/null || true
    local waited=0
    while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 50 ]; do
      sleep 0.1
      waited=$((waited + 1))
    done
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || true
    fi
    stopped=true
  fi
  rm -f "$PID_FILE" "$STATUS_FILE"
  printf '{"command":"stop","stopped":%s,"synthetic":true}\n' "$stopped"
}

selftest() {
  build_simulators
  "$JAVA_HOME/bin/java" -cp "$(simulator_classpath)" \
    com.anesop.simulators.SimulatorsMain --self-test \
    --his-port=0 --pharmacy-port=0 --spd-port=0
}

device() {
  local host="${1:-127.0.0.1}"
  local port="${2:-19090}"
  local device_id="${3:-SIM-MON-01}"
  local frames="${4:-30}"
  local interval_ms="${5:-200}"
  if [ ! -f "$GATEWAY/target/classes/com/anesop/gateway/simulator/SimulatedDevice.class" ]; then
    echo "[simulators] 构建设备网关（复用 SimulatedDevice，不重复实现协议）..." >&2
    "$ROOT/scripts/mvn.sh" -B -q -f "$GATEWAY/pom.xml" -DskipTests compile
  fi
  exec "$JAVA_HOME/bin/java" -cp "$GATEWAY/target/classes" \
    com.anesop.gateway.simulator.SimulatedDevice "$host" "$port" "$device_id" "$frames" "$interval_ms"
}

COMMAND="${1:-}"
if [ $# -gt 0 ]; then
  shift
fi
case "$COMMAND" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  restart)
    stop
    start
    ;;
  selftest) selftest ;;
  logs)
    [ -f "$LOG_FILE" ] || {
      echo "[simulators] 无日志（尚未启动）" >&2
      exit 1
    }
    tail -n "${1:-50}" "$LOG_FILE"
    ;;
  device) device "$@" ;;
  help | --help | -h | "") usage ;;
  *)
    echo "simulators.sh: 未知命令 $COMMAND（见 --help）" >&2
    exit 2
    ;;
esac
