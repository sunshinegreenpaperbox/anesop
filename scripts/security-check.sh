#!/usr/bin/env bash
# =============================================================================
# ANESOP 安全静态检查（M5-M19-02）
#
# 在无网络、无 Docker 的环境下做确定性安全门禁（约 1–2 秒）：
#   1. 仓库内无私钥/云凭证/令牌类硬编码；配置密钥必须是环境变量占位；
#   2. 默认与 prod profile fail-closed（demo 账号、自动生成密钥、springdoc、
#      actuator 暴露面）；
#   3. Nginx 安全头完整、CSP 无 inline script/eval、connect-src 同源、
#      HSTS 只在 443；/api/ 日志不含 query（PHI）；
#   4. API 层安全头、限流（429 + Retry-After + IP+用户名）、审计动作接线；
#   5. 应用日志不得输出口令/令牌/患者标识；前端入口无 inline script。
#
# 用法：bash scripts/security-check.sh
# 退出码：0 全部通过；1 存在 FAIL；2 输入/解析错误。
# 动态扫描（npm 漏洞/镜像清单）见 scripts/security-scan.sh。
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

PASS=0
FAIL=0
START_TS=$(date +%s)

pass() {
  PASS=$((PASS + 1))
  printf '[PASS] %s\n' "$1"
}

fail() {
  FAIL=$((FAIL + 1))
  printf '[FAIL] %s\n' "$1"
}

# 断言 git grep 无匹配（无输出且 rc=1 视为通过）。
expect_no_match() {
  local desc="$1"
  local pattern="$2"
  shift 2
  local out rc
  out=$(git grep -nE -- "$pattern" "$@" 2>/dev/null)
  rc=$?
  if [ "$rc" -eq 1 ] && [ -z "$out" ]; then
    pass "$desc"
  elif [ "$rc" -eq 0 ]; then
    fail "$desc"
    printf '%s\n' "$out" | head -10 | sed 's/^/       /'
  else
    fail "$desc（git grep 执行失败，rc=${rc}）"
  fi
}

# 断言 git grep 至少匹配一次。
expect_match() {
  local desc="$1"
  local pattern="$2"
  shift 2
  if git grep -qE -- "$pattern" "$@" 2>/dev/null; then
    pass "$desc"
  else
    fail "$desc"
  fi
}

printf 'ANESOP 安全静态检查（M5-M19-02）\n'
printf '  仓库根 : %s\n' "$ROOT"
printf '%s\n' '------------------------------------------------------------------------'

# --- 1. 硬编码凭据/私钥/令牌 ------------------------------------------------
expect_no_match '无私钥/云厂商凭证/聊天令牌硬编码' \
  'BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}|xox[baprs]-[A-Za-z0-9-]{10,}' \
  -- . ':!docs/ai/corpus' ':!*.md'

# 默认与 prod 的密钥类配置必须是 ${ENV} 占位，不得写死字面值。
expect_no_match '默认/prod 配置的密钥必须是环境变量占位' \
  '^[[:space:]]*(secret|phone-pepper|field-key|gateway-token):[[:space:]]*[^$#[:space:]]' \
  -- apps/api/api-bootstrap/src/main/resources/application.yml \
  apps/api/api-bootstrap/src/main/resources/application-prod.yml

# --- 2. 默认与 prod fail-closed --------------------------------------------
PY_CHECKS=$(python3 - "$ROOT" <<'PY'
import sys
from pathlib import Path

import yaml

root = Path(sys.argv[1])
resources = root / "apps/api/api-bootstrap/src/main/resources"


def emit(ok, msg):
    print(("PASS" if ok else "FAIL") + "\t" + msg)


def load(name):
    return yaml.safe_load((resources / name).read_text(encoding="utf-8")) or {}


def get(doc, path, default=None):
    node = doc
    for key in path.split("."):
        if not isinstance(node, dict) or key not in node:
            return default
        node = node[key]
    return node


def rate_limit_enabled(doc):
    value = get(doc, "anesop.security.rate-limit.enabled")
    if value is None:
        return True  # prod 继承 base 默认
    if value is True:
        return True
    return isinstance(value, str) and (value.lower() == "true" or value.endswith(":true}"))


def check_profile(name, label):
    try:
        doc = load(name)
    except Exception as exc:  # noqa: BLE001 - 解析失败必须显式失败
        emit(False, f"{label} 可解析：{exc}")
        return
    checks = [
        ("demo 账号禁用", get(doc, "anesop.security.demo-accounts-allowed") is False),
        ("JWT 禁止自动生成", get(doc, "anesop.security.jwt.allow-dev-generated") is False),
        ("字段加密禁止自动生成", get(doc, "anesop.security.crypto.allow-dev-generated") is False),
        ("springdoc 关闭", get(doc, "springdoc.api-docs.enabled") is False
         and get(doc, "springdoc.swagger-ui.enabled") is False),
        ("actuator 仅暴露 health,info",
         str(get(doc, "management.endpoints.web.exposure.include", "")).replace(" ", "") == "health,info"),
        ("健康详情不暴露", get(doc, "management.endpoint.health.show-details") == "never"),
        ("限流默认开启", rate_limit_enabled(doc)),
    ]
    bad = [name for name, ok in checks if not ok]
    emit(not bad, f"{label} fail-closed 基线（{len(checks)} 项）"
         + (f"；不满足：{', '.join(bad)}" if bad else ""))


check_profile("application.yml", "默认 profile")
check_profile("application-prod.yml", "prod profile")
PY
)
PY_RC=$?
if [ "$PY_RC" -ne 0 ] && [ -z "$PY_CHECKS" ]; then
  fail "默认/prod 配置解析失败（python rc=${PY_RC}）"
else
  while IFS=$'\t' read -r status msg; do
    [ -n "$msg" ] || continue
    if [ "$status" = "PASS" ]; then pass "$msg"; else fail "$msg"; fi
  done <<<"$PY_CHECKS"
fi

# --- 3. Nginx 安全头 / CSP / HSTS / 日志 ------------------------------------
NGINX_CHECKS=$(python3 - "$ROOT" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
path = root / "deploy/nginx/anesop.conf"
text = path.read_text(encoding="utf-8")


def emit(ok, msg):
    print(("PASS" if ok else "FAIL") + "\t" + msg)


https_at = text.find("listen 443 ssl;")
http_at = text.find("listen 80;")
if https_at < 0 or http_at < 0:
    emit(False, "nginx 配置包含 80/443 server")
    sys.exit(0)
https = text[https_at:]
http_block = text[http_at:https_at]

required = {
    "HSTS": "Strict-Transport-Security",
    "nosniff": 'X-Content-Type-Options "nosniff"',
    "X-Frame-Options DENY": 'X-Frame-Options "DENY"',
    "Referrer-Policy": "Referrer-Policy",
    "Permissions-Policy": "Permissions-Policy",
}
missing = [name for name, needle in required.items() if needle not in https]
emit(not missing, "443 安全响应头齐全（HSTS/nosniff/XFO/Referrer/Permissions）"
     + (f"；缺失：{', '.join(missing)}" if missing else ""))

emit("Strict-Transport-Security" not in http_block,
     "HSTS 仅 443 下发（80 明文响应不声明）")


def csp_value(block):
    match = re.search(r'add_header\s+Content-Security-Policy\s+"([^"]+)"\s+always;', block, re.I)
    return match.group(1) if match else None


policy = csp_value(https)
if not policy:
    emit(False, "CSP 已启用")
else:
    directives = {}
    for part in policy.split(";"):
        tokens = part.strip().split()
        if tokens:
            directives[tokens[0].lower()] = " ".join(tokens[1:])
    missing = [d for d in ("default-src", "script-src", "connect-src",
                           "object-src", "base-uri", "form-action", "frame-ancestors")
               if d not in directives]
    emit(not missing, "CSP 指令齐全（default/script/connect/object/base/form/frame-ancestors）"
         + (f"；缺失：{', '.join(missing)}" if missing else ""))
    script = directives.get("script-src", "")
    emit("unsafe-inline" not in script and "unsafe-eval" not in script and "'self'" in script,
         "CSP script-src 无 inline/eval，仅同源")
    connect = directives.get("connect-src", "")
    emit("'self'" in connect and "*" not in connect,
         "CSP connect-src 仅同源（经 /api 反代）")
    emit("'none'" in directives.get("frame-ancestors", ""),
         "CSP frame-ancestors 'none'（与 X-Frame-Options DENY 双保险）")

api_marker = "location /api/"
api_at = text.find(api_marker)
if api_at < 0:
    emit(False, "存在 /api/ 反代 location")
else:
    block = text[api_at:]
    end = block.find("\n    }")
    block = block[:end if end > 0 else len(block)]
    emit("anesop_no_query" in block and "access_log" in block,
         "/api/ 访问日志使用无 query 的自定义格式")
    emit(re.search(r"\$(args|query_string)\b", block) is None
         and re.search(r"\$request(?![A-Za-z0-9_])", block) is None,
         "/api/ location 不引用 query 变量")

log_format = re.search(r"log_format\s+anesop_no_query(.*?);", text, re.S)
emit(bool(log_format) and "$args" not in log_format.group(1)
     and "$query_string" not in log_format.group(1)
     and re.search(r"\$request(?![A-Za-z0-9_])", log_format.group(1)) is None,
     "log_format anesop_no_query 本身不含 query 变量")
PY
)
if [ -z "$NGINX_CHECKS" ]; then
  fail "nginx 配置解析失败"
else
  while IFS=$'\t' read -r status msg; do
    [ -n "$msg" ] || continue
    if [ "$status" = "PASS" ]; then pass "$msg"; else fail "$msg"; fi
  done <<<"$NGINX_CHECKS"
fi

# --- 4. API 层安全头 / 限流 / 审计接线 --------------------------------------
expect_match 'API 安全头配置齐全（XFO deny/CSP/Permissions/HSTS）' \
  'frameOptions\(frame -> frame\.deny\(\)\)|contentSecurityPolicy\(|permissionsPolicy\(|httpStrictTransportSecurity\(' \
  -- apps/api/api-bootstrap/src/main/java/com/anesop/api/config/SecurityConfiguration.java

expect_match '限流覆盖 login/refresh 且返回 Retry-After' \
  'LOGIN_PATH = "/v1/auth/login"|REFRESH_PATH = "/v1/auth/refresh"|RETRY_AFTER' \
  -- apps/api/api-bootstrap/src/main/java/com/anesop/api/security/RateLimitFilter.java

expect_match '限流按 IP+用户名并限制桶表容量' \
  'usernameKey\(|maxBuckets|BucketMap' \
  -- apps/api/api-bootstrap/src/main/java/com/anesop/api/security/RateLimitFilter.java \
  apps/api/api-bootstrap/src/main/java/com/anesop/api/config/RateLimitProperties.java

expect_match '限流阈值/窗口可由环境变量配置' \
  'ANESOP_RATE_LIMIT_(CAPACITY|REFILL_TOKENS|REFILL_PERIOD|MAX_BUCKETS)' \
  -- apps/api/api-bootstrap/src/main/resources/application.yml

expect_match '审计动作接线（LOGIN/TOKEN_REFRESH/LOGOUT/ACCESS_DENIED/AUTH_UNAUTHORIZED）' \
  'action = "LOGIN"|action = "TOKEN_REFRESH"|action = "LOGOUT"|record\("ACCESS_DENIED"|record\("AUTH_UNAUTHORIZED"' \
  -- apps/api/modules/m0/impl/src/main/java/com/anesop/m0/impl/auth/AuthServiceImpl.java \
  apps/api/api-bootstrap/src/main/java/com/anesop/api/security/RestAccessDeniedHandler.java \
  apps/api/api-bootstrap/src/main/java/com/anesop/api/security/RestAuthenticationEntryPoint.java

# --- 5. 日志与前端入口 ------------------------------------------------------
expect_no_match '应用日志不输出口令/令牌/患者标识' \
  '(LOG|LOGGER)\.(trace|debug|info|warn|error)\([^;]*(password|passwd|refreshToken|accessToken|inpatientNo|patientName|idCard|phone)' \
  -- 'apps/**/src/main/**/*.java' 'services/**/src/main/**/*.java'

if python3 - "$ROOT" <<'PY'
import re
import sys
from pathlib import Path

text = Path(sys.argv[1], "apps/web/index.html").read_text(encoding="utf-8")
inline = [m.group(0) for m in re.finditer(r"<script\b[^>]*>", text, re.I)
          if not re.search(r"\bsrc\s*=", m.group(0), re.I)]
sys.exit(0 if not inline else 1)
PY
then
  pass '前端入口 index.html 无 inline script'
else
  fail '前端入口 index.html 存在 inline script（CSP script-src 将阻断）'
fi

if [ -f scripts/security-scan.sh ]; then
  pass '安全扫描脚本存在（scripts/security-scan.sh）'
else
  fail '缺少 scripts/security-scan.sh'
fi

END_TS=$(date +%s)
ELAPSED=$((END_TS - START_TS))

printf '%s\n' '------------------------------------------------------------------------'
if [ "$FAIL" -ne 0 ]; then
  printf '[security-check] FAILED：%d 项通过，%d 项失败（%ds）\n' "$PASS" "$FAIL" "$ELAPSED" >&2
  exit 1
fi
printf '[security-check] OK：%d 项通过，0 项失败（%ds）\n' "$PASS" "$ELAPSED"
