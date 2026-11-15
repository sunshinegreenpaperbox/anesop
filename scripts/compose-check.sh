#!/usr/bin/env bash
# =============================================================================
# ANESOP Compose 静态校验（S0-10）
#
# 目的：Docker 不可用（EXT-08）时，仍能在 CI/本地对部署清单做确定性校验：
#   - YAML 可解析；必需服务、健康检查、依赖顺序、网络、卷、资源限制齐全；
#   - 镜像 tag 存在且非 latest；无明文口令；仅 web 暴露宿主端口，监控绑 127.0.0.1；
#   - .env.example 覆盖 compose 中全部 ${VAR}；Dockerfile 基础镜像非 latest；
#   - nginx 配置含 TLS/安全头/SPA fallback/反代关键指令。
# 若检测到 Docker daemon（socket 或 DOCKER_HOST），额外执行
#   docker compose ... config -q
# 做运行时配置校验；否则打印 EXT-08 跳过（不计失败）。
#
# 用法：bash scripts/compose-check.sh
# 退出码：0 全部通过（含 SKIP）；1 存在 FAIL。
# =============================================================================

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

COMPOSE_FILE="deploy/compose/docker-compose.yml"
ENV_FILE="deploy/compose/.env.example"

PASS=0
FAIL=0
SKIP=0
START_TS=$(date +%s%N 2>/dev/null || date +%s)

pass() { PASS=$((PASS + 1)); printf '[PASS] %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '[FAIL] %s\n' "$1"; }
skip() { SKIP=$((SKIP + 1)); printf '[SKIP] %s\n' "$1"; }

printf 'ANESOP compose 静态校验\n'
printf '  仓库根 : %s\n' "$ROOT"
printf '  清单   : %s\n' "$COMPOSE_FILE"
printf '  环境   : %s\n' "$ENV_FILE"
printf '%s\n' '------------------------------------------------------------------------'

# -----------------------------------------------------------------------------
# 静态规则检查（Python + PyYAML）
# -----------------------------------------------------------------------------
RESULT_FILE="$(mktemp "${TMPDIR:-/tmp}/anesop-compose-check.XXXXXX")"
trap 'rm -f "$RESULT_FILE"' EXIT

python3 - "$ROOT" >"$RESULT_FILE" <<'PY'
import re
import subprocess
import sys
from pathlib import Path

import yaml

root = Path(sys.argv[1])
compose_path = root / "deploy/compose/docker-compose.yml"
env_path = root / "deploy/compose/.env.example"
nginx_path = root / "deploy/nginx/anesop.conf"
dockerfiles = {
    "api": root / "deploy/compose/api.Dockerfile",
    "web": root / "deploy/compose/web.Dockerfile",
}

REQUIRED_SERVICES = [
    "postgres", "redis", "rabbitmq", "minio", "api", "web",
    "prometheus", "grafana", "loki",
]
MONITORING_SERVICES = ["prometheus", "grafana", "loki"]
REQUIRED_VOLUMES = [
    "pgdata", "redisdata", "rabbitmqdata", "miniodata",
    "promdata", "grafanadata", "lokidata",
]


def emit(ok, msg):
    print(("PASS" if ok else "FAIL") + "\t" + msg)


# --- 1. YAML 可解析 ---------------------------------------------------------
try:
    text = compose_path.read_text(encoding="utf-8")
    doc = yaml.safe_load(text)
except FileNotFoundError:
    emit(False, f"compose 文件存在：{compose_path}")
    sys.exit(2)
except yaml.YAMLError as exc:
    emit(False, f"YAML 可解析：{exc}")
    sys.exit(2)
else:
    emit(True, "YAML 可解析（python3 yaml.safe_load）")

if not isinstance(doc, dict):
    emit(False, "compose 顶层为映射（mapping）")
    sys.exit(2)

emit(doc.get("name") == "anesop", f"项目名 name=anesop（实际：{doc.get('name')}）")

services = doc.get("services") or {}
missing = [s for s in REQUIRED_SERVICES if s not in services]
emit(not missing, "必需服务齐全（postgres/redis/rabbitmq/minio/api/web/prometheus/grafana/loki）"
     + (f"；缺失：{', '.join(missing)}" if missing else ""))

# --- 2. 每服务：image/build、restart、healthcheck、networks ------------------
no_image = [s for s, c in services.items() if not (c.get("image") or c.get("build"))]
emit(not no_image, "每个服务均有 image 或 build" + (f"；缺失：{', '.join(no_image)}" if no_image else ""))

bad_restart = [s for s, c in services.items() if c.get("restart") != "unless-stopped"]
emit(not bad_restart, "每个服务 restart: unless-stopped"
     + (f"；不合规：{', '.join(bad_restart)}" if bad_restart else ""))

def env_files(cfg):
    ef = cfg.get("env_file")
    if not ef:
        return []
    return [ef] if isinstance(ef, str) else list(ef)


bad_env_file = [s for s, c in services.items()
                if ".env" not in [str(e).split(":", 1)[0] for e in env_files(c)]]
emit(not bad_env_file, "每个服务 env_file 指向 .env"
     + (f"；缺失：{', '.join(bad_env_file)}" if bad_env_file else ""))

bad_health = [s for s, c in services.items()
              if not (isinstance(c.get("healthcheck"), dict) and c["healthcheck"].get("test"))]
emit(not bad_health, "每个服务均有 healthcheck.test"
     + (f"；缺失：{', '.join(bad_health)}" if bad_health else ""))

bad_networks = [s for s, c in services.items() if not c.get("networks")]
emit(not bad_networks, "每个服务均声明 networks"
     + (f"；缺失：{', '.join(bad_networks)}" if bad_networks else ""))

# --- 3. depends_on 必须 service_healthy -------------------------------------
bad_dep = []
for svc, cfg in services.items():
    deps = cfg.get("depends_on") or {}
    if not isinstance(deps, dict):
        bad_dep.append(f"{svc}(非映射)")
        continue
    for target, spec in deps.items():
        if not (isinstance(spec, dict) and spec.get("condition") == "service_healthy"):
            bad_dep.append(f"{svc}->{target}")
        if target not in services:
            bad_dep.append(f"{svc}->{target}(不存在)")
emit(not bad_dep, "depends_on 全部使用 condition: service_healthy"
     + (f"；不合规：{', '.join(bad_dep)}" if bad_dep else ""))

# --- 4. 镜像 tag：存在、非 latest -------------------------------------------
bad_tags = []
for svc, cfg in services.items():
    image = cfg.get("image")
    if not image:
        continue
    if "@sha256:" in image:
        continue
    ref = image.split("@", 1)[0]
    last = ref.rsplit("/", 1)[-1]
    if ":" not in last:
        bad_tags.append(f"{svc}={image}(无 tag)")
        continue
    tag = last[last.find(":") + 1:]
    if tag.startswith("${"):
        # ${VAR:-default}：静态校验默认 tag，避免以变量掩盖 latest
        m = re.match(r"\$\{[A-Za-z_][A-Za-z0-9_]*:-([^}]+)\}", tag)
        tag = m.group(1) if m else ""
    if not tag or tag == "latest":
        bad_tags.append(f"{svc}={image}")
emit(not bad_tags, "镜像均有精确 tag 且无 :latest"
     + (f"；不合规：{', '.join(bad_tags)}" if bad_tags else ""))

# --- 5. 卷：使用即声明 ------------------------------------------------------
declared = set((doc.get("volumes") or {}).keys())
missing_declared = [v for v in REQUIRED_VOLUMES if v not in declared]
used_named = []
for svc, cfg in services.items():
    for vol in cfg.get("volumes") or []:
        if isinstance(vol, dict):
            src = str(vol.get("source", ""))
        else:
            src = str(vol).split(":", 1)[0]
        if not src or src.startswith(".") or src.startswith("/"):
            continue
        used_named.append((svc, src))
undeclared = sorted({src for _, src in used_named if src not in declared})
emit(not missing_declared and not undeclared,
     "命名卷均已声明（pgdata/redisdata/rabbitmqdata/miniodata/promdata/grafanadata/lokidata）"
     + (f"；未声明：{', '.join(missing_declared + undeclared)}" if (missing_declared or undeclared) else ""))

# --- 5b. 构建上下文/绑定挂载源存在 ------------------------------------------
compose_dir = compose_path.parent
build_issues = []
for svc, cfg in services.items():
    build = cfg.get("build")
    if not isinstance(build, dict):
        continue
    ctx = (compose_dir / str(build.get("context", "."))).resolve()
    dockerfile = (ctx / str(build.get("dockerfile", "Dockerfile"))).resolve()
    if not ctx.is_dir():
        build_issues.append(f"{svc}: context 不存在({ctx})")
    if not dockerfile.is_file():
        build_issues.append(f"{svc}: dockerfile 不存在({dockerfile})")
emit(not build_issues, "build.context 与 dockerfile 均存在"
     + (f"；问题：{', '.join(build_issues)}" if build_issues else ""))

bind_issues = []
for svc, cfg in services.items():
    for vol in cfg.get("volumes") or []:
        src = str(vol.get("source", "")) if isinstance(vol, dict) else str(vol).split(":", 1)[0]
        if not src or src.startswith("$"):
            continue
        if not (src.startswith(".") or src.startswith("/")):
            continue
        resolved = (compose_dir / src).resolve() if src.startswith(".") else Path(src)
        if not resolved.exists():
            bind_issues.append(f"{svc}:{src}")
emit(not bind_issues, "绑定挂载源均存在（证书/监控配置）"
     + (f"；缺失：{', '.join(bind_issues)}" if bind_issues else ""))

# --- 6. env_file 变量覆盖 ---------------------------------------------------
if not env_path.exists():
    emit(False, ".env.example 存在")
    env_names = set()
else:
    emit(True, ".env.example 存在")
    env_names = set()
    for line in env_path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=", line)
        if m:
            env_names.add(m.group(1))

used_vars = sorted(set(re.findall(r"\$\{([A-Za-z_][A-Za-z0-9_]*)", text)))
missing_vars = [v for v in used_vars if v not in env_names]
emit(not missing_vars,
     f".env.example 覆盖 compose 全部 ${{VAR}}（共 {len(used_vars)} 个变量）"
     + (f"；缺失：{', '.join(missing_vars)}" if missing_vars else ""))

# --- 7. compose 中无明文口令 -------------------------------------------------
plaintext = []
for svc, cfg in services.items():
    env = cfg.get("environment") or {}
    if isinstance(env, dict):
        items = list(env.items())
    else:
        items = []
        for entry in env or []:
            text_entry = str(entry)
            if "=" in text_entry:
                key, value = text_entry.split("=", 1)
            else:
                key, value = text_entry, ""
            items.append((key, value))
    for key, value in items:
        if not re.search(r"(?i)(password|passwd|secret|token)", str(key)):
            continue
        val = "" if value is None else str(value).strip()
        if val and not val.startswith("$"):
            plaintext.append(f"{svc}.{key}")
    cmd = cfg.get("command")
    if cmd:
        cmd_str = " ".join(cmd) if isinstance(cmd, list) else str(cmd)
        for m in re.finditer(r"--(?:requirepass|password)[=\s]+(\S+)", cmd_str):
            if not m.group(1).startswith("$"):
                plaintext.append(f"{svc}.command")
emit(not plaintext, "compose 中无明文口令/密钥（仅 ${VAR} 引用）"
     + (f"；疑似明文：{', '.join(plaintext)}" if plaintext else ""))

# --- 8. 端口暴露：仅 web 发布；监控绑 127.0.0.1 -----------------------------
def split_port_spec(spec):
    """按 ':' 切分端口串，但忽略 ${VAR:-default} 内部的冒号。"""
    parts, cur, depth = [], "", 0
    i = 0
    while i < len(spec):
        if spec.startswith("${", i):
            depth += 1
            cur += "${"
            i += 2
            continue
        ch = spec[i]
        if ch == "}" and depth > 0:
            depth -= 1
            cur += ch
        elif ch == ":" and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
        i += 1
    parts.append(cur)
    # 去掉 /tcp、/udp 协议后缀
    if parts:
        parts[-1] = parts[-1].split("/", 1)[0]
    return parts


port_issues = []
web_ports = set()
for svc, cfg in services.items():
    if cfg.get("network_mode") == "host":
        port_issues.append(f"{svc}(network_mode: host)")
    for port in cfg.get("ports") or []:
        spec = str(port)
        parts = split_port_spec(spec)
        if svc == "web":
            if len(parts) == 2:
                host_ip, host_port = "", parts[0]
            elif len(parts) == 3:
                host_ip, host_port = parts[0], parts[1]
            else:
                port_issues.append(f"web={spec}(格式无法解析)")
                continue
            if host_ip not in ("", "0.0.0.0"):
                port_issues.append(f"web={spec}(绑定地址受限)")
            if host_port not in ("80", "443"):
                port_issues.append(f"web={spec}(仅允许 80/443)")
            web_ports.add(host_port)
        else:
            if len(parts) != 3 or parts[0] != "127.0.0.1":
                port_issues.append(f"{svc}={spec}(必须绑 127.0.0.1)")
if web_ports != {"80", "443"}:
    port_issues.append(f"web 宿主端口={sorted(web_ports) or '无'}(应含 80/443)")
emit(not port_issues, "仅 web 暴露宿主 80/443；其余（含监控）不发布或绑 127.0.0.1"
     + (f"；不合规：{', '.join(port_issues)}" if port_issues else ""))

# --- 9. 网络拓扑 ------------------------------------------------------------
declared_nets = set((doc.get("networks") or {}).keys())
bad_net_refs = []
for svc, cfg in services.items():
    nets = cfg.get("networks") or []
    if isinstance(nets, dict):
        nets = list(nets.keys())
    for net in nets:
        if net not in declared_nets:
            bad_net_refs.append(f"{svc}->{net}")
mon_without = [s for s in MONITORING_SERVICES
               if s in services and "monitoring-net" not in (services[s].get("networks") or [])]
emit(not bad_net_refs and not mon_without,
     "网络引用均已声明，监控服务均接入 monitoring-net"
     + (f"；问题：{', '.join(bad_net_refs + mon_without)}" if (bad_net_refs or mon_without) else ""))

# --- 10. 资源限制 / 日志 / no-new-privileges --------------------------------
bad_limits = [s for s, c in services.items()
              if not ((c.get("deploy") or {}).get("resources") or {}).get("limits")]
emit(not bad_limits, "每个服务均声明 deploy.resources.limits（cpus/memory）"
     + (f"；缺失：{', '.join(bad_limits)}" if bad_limits else ""))

bad_logging = [s for s, c in services.items()
               if not (isinstance(c.get("logging"), dict)
                       and c["logging"].get("driver") == "json-file"
                       and (c["logging"].get("options") or {}).get("max-size")
                       and (c["logging"].get("options") or {}).get("max-file"))]
emit(not bad_logging, "每个服务日志 json-file 且配置 max-size/max-file"
     + (f"；不合规：{', '.join(bad_logging)}" if bad_logging else ""))

bad_sec = [s for s, c in services.items()
           if "no-new-privileges:true" not in (c.get("security_opt") or [])]
emit(not bad_sec, "每个服务 security_opt 含 no-new-privileges:true"
     + (f"；缺失：{', '.join(bad_sec)}" if bad_sec else ""))

# --- 11. Dockerfile：多阶段、FROM 非 latest、非 root ------------------------
for name, path in dockerfiles.items():
    if not path.exists():
        emit(False, f"{path.name} 存在")
        continue
    content = path.read_text(encoding="utf-8")
    froms = re.findall(r"(?im)^\s*FROM\s+([^\s#]+)", content)
    issues = []
    if len(froms) < 2:
        issues.append("非多阶段")
    for ref in froms:
        if ref.startswith("$"):
            issues.append(f"{ref}(变量无法静态校验)")
            continue
        if "@sha256:" in ref:
            continue
        last = ref.rsplit("/", 1)[-1]
        if ":" not in last or last[last.find(":") + 1:] == "latest":
            issues.append(f"{ref}(未 pin 或 latest)")
    if name == "web" and "nginx:1.27-alpine" not in content:
        issues.append("运行阶段不是 nginx:1.27-alpine")
    if name == "api":
        if "USER anesop" not in content:
            issues.append("运行阶段未切非 root 用户")
        if "HEALTHCHECK" not in content:
            issues.append("缺少 HEALTHCHECK")
        if "/opt/anesop/db/migration" not in content:
            issues.append("未复制 Flyway 迁移脚本")
    emit(not issues, f"{path.name}: 多阶段且 FROM 已 pin（{len(froms)} 个 FROM）"
         + (f"；问题：{', '.join(issues)}" if issues else ""))

# --- 12. nginx 关键指令 -----------------------------------------------------
if not nginx_path.exists():
    emit(False, "deploy/nginx/anesop.conf 存在")
else:
    nginx = nginx_path.read_text(encoding="utf-8")
    groups = {
        "TLS 1.2/1.3": ["ssl_protocols", "TLSv1.2", "TLSv1.3"],
        "安全响应头": ["Strict-Transport-Security", "X-Content-Type-Options", "X-Frame-Options"],
        "SPA fallback": ["try_files", "/index.html"],
        "API 反代（保留 Host/X-Forwarded-*）": [
            "proxy_pass http://anesop_api", "proxy_set_header Host", "X-Forwarded-For",
            "X-Forwarded-Proto",
        ],
        "健康检查与请求限制": ["/healthz", "client_max_body_size", "limit_req"],
        "压缩": ["gzip on"],
    }
    for label, needles in groups.items():
        lacking = [n for n in needles if n not in nginx]
        emit(not lacking, f"nginx.conf 含 {label}"
             + (f"；缺少：{', '.join(lacking)}" if lacking else ""))

    # B-3：/api/ location 的访问日志不得包含 query/参数（$request 含查询串，$args 即查询串）。
    api_match = re.search(r"location\s+/api/\s*\{(.*?)\n    \}", nginx, re.S)
    if not api_match:
        emit(False, "nginx.conf 存在 location /api/ 块")
    else:
        api_block = api_match.group(1)
        query_markers = [m for m in ("$request", "$args", "$query_string")
                         if re.search(re.escape(m) + r"(?![A-Za-z0-9_])", api_block)]
        emit(not query_markers, "nginx /api/ 访问日志不含 query/请求参数"
             + (f"；发现：{', '.join(query_markers)}" if query_markers else ""))
        # 上线阻断回归：/api/ 反代必须带末尾斜杠（剥离 /api 前缀），否则后端 /v1/** 全 404。
        proxy_lines = [ln.strip() for ln in api_block.splitlines() if ln.strip().startswith("proxy_pass")]
        emit(bool(proxy_lines) and proxy_lines[0].rstrip(";").rstrip().endswith("/"),
             "nginx /api/ proxy_pass 带末尾斜杠（剥离 /api 前缀，避免后端 404）"
             + (f"；实际：{proxy_lines[0] if proxy_lines else '缺失'}" if not (bool(proxy_lines) and proxy_lines[0].rstrip(";").rstrip().endswith("/")) else ""))
        emit("anesop_no_query" in api_block and "access_log" in api_block,
             "nginx /api/ 使用自定义 anesop_no_query 访问日志格式"
             if "anesop_no_query" in api_block
             else "nginx /api/ 必须使用 anesop_no_query 或 access_log off")

# --- 13. 监控配置可解析 -----------------------------------------------------
monitoring_yamls = [
    "deploy/prometheus/prometheus.yml",
    "deploy/grafana/provisioning/datasources/datasources.yml",
    "deploy/grafana/provisioning/dashboards/dashboards.yml",
    "deploy/loki/loki-config.yml",
]
yaml_issues = []
for rel in monitoring_yamls:
    path = root / rel
    try:
        yaml.safe_load(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        yaml_issues.append(f"{rel}(缺失)")
    except yaml.YAMLError as exc:
        yaml_issues.append(f"{rel}({str(exc).splitlines()[0]})")
emit(not yaml_issues, "监控配置（prometheus/grafana/loki）YAML 均可解析"
     + (f"；问题：{', '.join(yaml_issues)}" if yaml_issues else ""))

# --- 14. .env.example 无真实密钥 --------------------------------------------
if env_path.exists():
    leaked = []
    for line in env_path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$", line)
        if not m:
            continue
        key, value = m.group(1), m.group(2).strip().strip('"').strip("'")
        if not re.search(r"(?i)(password|passwd|secret|token)", key):
            continue
        if value and "CHANGE_ME" not in value and not value.startswith("$"):
            leaked.append(key)
    emit(not leaked, ".env.example 密钥项均为 CHANGE_ME 占位"
         + (f"；疑似真实值：{', '.join(leaked)}" if leaked else ""))

# --- 15. .env 未被 Git 跟踪 -------------------------------------------------
try:
    tracked = subprocess.run(
        ["git", "ls-files", "--", "deploy/compose/.env"],
        cwd=root, capture_output=True, text=True, check=False,
    ).stdout.strip()
    emit(not tracked, "deploy/compose/.env 未被 Git 跟踪（含未跟踪状态检查）")
except OSError:
    emit(True, "deploy/compose/.env 未被 Git 跟踪（git 不可用，跳过）")
PY

PY_RC=$?

while IFS=$'\t' read -r status message; do
  [ -z "${status:-}" ] && continue
  case "$status" in
    PASS) pass "$message" ;;
    FAIL) fail "$message" ;;
    *) printf '%s\t%s\n' "$status" "$message" ;;
  esac
done <"$RESULT_FILE"

if [ "$PY_RC" -ne 0 ] && [ "$FAIL" -eq 0 ]; then
  fail "静态检查脚本异常退出（python3 退出码 $PY_RC）"
fi

# -----------------------------------------------------------------------------
# 运行时配置校验：仅当 Docker daemon 实际可达时执行；EXT-08 环境跳过
# -----------------------------------------------------------------------------
printf '%s\n' '------------------------------------------------------------------------'

DOCKER_RUNTIME_CHECK="0"
if [ "${ANESOP_SKIP_DOCKER_RUNTIME_CHECK:-0}" = "1" ]; then
  skip "EXT-08: 已按 ANESOP_SKIP_DOCKER_RUNTIME_CHECK=1 跳过运行时配置校验"
elif ! command -v docker >/dev/null 2>&1; then
  skip "EXT-08: Docker 不可用，跳过运行时配置校验"
elif [ -z "${DOCKER_HOST:-}" ] && [ ! -S /var/run/docker.sock ] \
     && [ ! -S "${HOME}/.docker/run/docker.sock" ]; then
  # CLI 存在但 daemon 不可达：不调用任何 docker 子命令（EXT-08）
  skip "EXT-08: Docker 不可用，跳过运行时配置校验"
else
  DOCKER_RUNTIME_CHECK="1"
fi

if [ "$DOCKER_RUNTIME_CHECK" = "1" ]; then
  printf '检测到 Docker daemon，执行 compose config 校验（不启动容器）...\n'
  CONFIG_ERR="$(mktemp "${TMPDIR:-/tmp}/anesop-compose-config.XXXXXX")"
  # compose 的 env_file: .env 在 config 阶段需存在；缺失时用示例临时补齐，结束即删
  TMP_ENV_CREATED=0
  if [ ! -f deploy/compose/.env ]; then
    cp "$ENV_FILE" deploy/compose/.env
    TMP_ENV_CREATED=1
  fi
  if docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" config -q >"$CONFIG_ERR" 2>&1; then
    pass "docker compose config -q 校验通过"
  else
    fail "docker compose config -q 校验失败：$(tr '\n' ' ' <"$CONFIG_ERR" | cut -c1-400)"
  fi
  if [ "$TMP_ENV_CREATED" = "1" ]; then
    rm -f deploy/compose/.env
  fi
  rm -f "$CONFIG_ERR"
fi

# -----------------------------------------------------------------------------
# 汇总
# -----------------------------------------------------------------------------
END_TS=$(date +%s%N 2>/dev/null || date +%s)
if [ "${#START_TS}" -gt 12 ] 2>/dev/null; then
  ELAPSED_MS=$(( (END_TS - START_TS) / 1000000 ))
else
  ELAPSED_MS=$(( (END_TS - START_TS) * 1000 ))
fi

printf '%s\n' '========================================================================'
printf 'PASS: %d  FAIL: %d  SKIP: %d  耗时: %d ms\n' "$PASS" "$FAIL" "$SKIP" "$ELAPSED_MS"
if [ "$FAIL" -gt 0 ]; then
  printf '结果: FAIL（compose 静态校验未通过）\n'
  exit 1
fi
printf '结果: PASS（静态校验通过；运行时验证待 EXT-08 解除后补）\n'
exit 0
