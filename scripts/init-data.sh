#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 数据初始化（M6-M20-02）
#
# 做什么：对一个可连接的 PostgreSQL 16 执行 Flyway 迁移，并按模式选择 locations：
#   --mode prod（默认）：只加载 <repo>/db/migration（V1…V26，生产结构 + 平台基线：
#                        角色/权限/字典；组织、术间、人员由实施按变更单导入）。
#   --mode demo        ：委托 scripts/seed-demo.sh 加载 db/migration + db/seed/demo
#                        （V100/V101，全部 SYNTHETIC 演示账号与药品）。
#
# 生产基线来自迁移 V1/V2/V3/V5/V6…：6 角色、权限点、字典类型/字典项；**不创建任何用户**。
# 演示数据只存在于 db/seed/demo，生产 locations 永远不包含该目录（数据闸门）。
#
# 安全闸门（任一不满足即非零退出，绝不"尽力继续"）：
#   1) prod 模式必须显式 --confirm-prod；未提供时只允许 --dry-run；
#   2) prod 模式拒绝库名含 demo/dev/test/local 的目标；拒绝 SPRING_PROFILES_ACTIVE=dev/test；
#   3) demo 模式委托 seed-demo，后者拒绝 prod/uat/staging 库名与 prod profile；
#   4) 迁移后核对平台基线计数（角色/权限/字典）且 demo_* 账号必须为 0（prod）；
#   5) 脚本不打印口令，不写患者数据，不执行任何 DROP/TRUNCATE。
#
# 用法（仓库根）：
#   bash scripts/init-data.sh --dry-run                       # 只看计划，不连库
#   bash scripts/init-data.sh --mode prod \
#        --url jdbc:postgresql://127.0.0.1:5432/anesop \
#        --user anesop --confirm-prod                        # 生产/预发初始化（人工签字后）
#   bash scripts/init-data.sh --mode demo \
#        --url jdbc:postgresql://127.0.0.1:5432/anesop_demo  # 演示/开发初始化（SYNTHETIC）
#   ANESOP_DB_URL=... ANESOP_DB_PASSWORD=... bash scripts/init-data.sh --mode demo
#   bash scripts/init-data.sh --help
#
# 连接参数优先级：命令行 > ANESOP_DB_URL / ANESOP_DB_USER / ANESOP_DB_PASSWORD > DATABASE_URL。
# 依赖：JDK 21 + Maven（均由 scripts/env.sh 固定到仓库内）；PostgreSQL 16 可达。
#
# 退出码：
#   0  成功（含 dry-run / 已是最新）
#   2  参数或安全闸门拒绝
#   3  缺少 JDK/Maven 等工具链
#   4  无法连接 PostgreSQL
#   5  Maven 解析 classpath 失败
#   6  Flyway 迁移/校验失败
#   7  迁移后基线核对失败（计数不足或发现 demo_* 账号）
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODE="prod"
DRY_RUN=0
CONFIRM_PROD=0
SKIP_BASELINE_CHECK=0
DB_URL="${ANESOP_DB_URL:-${DATABASE_URL:-}}"
DB_USER="${ANESOP_DB_USER:-anesop}"
DB_PASSWORD="${ANESOP_DB_PASSWORD:-}"

usage() {
  awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"
}

die() {
  local code="$1"
  shift
  printf 'init-data: %s\n' "$*" >&2
  exit "$code"
}

warn() { printf 'init-data: [WARN] %s\n' "$*" >&2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --mode)
      [ $# -ge 2 ] || die 2 "缺少 --mode 的值（prod|demo）"
      MODE="$2"
      shift 2
      ;;
    --mode=*)
      MODE="${1#*=}"
      shift
      ;;
    --url | --db-url)
      [ $# -ge 2 ] || die 2 "缺少 --url 的值"
      DB_URL="$2"
      shift 2
      ;;
    --url=* | --db-url=*)
      DB_URL="${1#*=}"
      shift
      ;;
    --user | --username)
      [ $# -ge 2 ] || die 2 "缺少 --user 的值"
      DB_USER="$2"
      shift 2
      ;;
    --user=* | --username=*)
      DB_USER="${1#*=}"
      shift
      ;;
    --password)
      [ $# -ge 2 ] || die 2 "缺少 --password 的值（更推荐用 ANESOP_DB_PASSWORD 环境变量）"
      DB_PASSWORD="$2"
      shift 2
      ;;
    --password=*)
      DB_PASSWORD="${1#*=}"
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --confirm-prod)
      CONFIRM_PROD=1
      shift
      ;;
    --skip-baseline-check)
      SKIP_BASELINE_CHECK=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      die 2 "未知参数：$1（用 --help 查看用法）"
      ;;
  esac
done

case "$MODE" in
  prod | demo) ;;
  *) die 2 "非法 --mode：$MODE（只支持 prod 或 demo）" ;;
esac

case "$DB_URL" in
  "") ;;
  jdbc:postgresql://*) ;;
  *) die 2 "连接串必须以 jdbc:postgresql:// 开头，当前为：$DB_URL" ;;
esac

if [ -n "$DB_URL" ]; then
  if [[ "$DB_URL" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^?]+) ]]; then
    DB_HOST="${BASH_REMATCH[1]}"
    DB_PORT="${BASH_REMATCH[3]:-5432}"
    DB_NAME="${BASH_REMATCH[4]}"
  else
    die 2 "无法解析连接串（支持 jdbc:postgresql://host[:port]/db[?params]）：$DB_URL"
  fi
fi

DB_NAME_LC="$(printf '%s' "${DB_NAME:-}" | tr '[:upper:]' '[:lower:]')"
PROFILE_LC="$(printf '%s' "${SPRING_PROFILES_ACTIVE:-}" | tr '[:upper:]' '[:lower:]')"

# ---------------------------------------------------------------------------
# 安全闸门：生产模式
# ---------------------------------------------------------------------------
if [ "$MODE" = "prod" ]; then
  case "$DB_NAME_LC" in
    "" ) ;;
    *demo* | *dev* | *test* | *local*)
      die 2 "prod 模式拒绝疑似非生产库名（$DB_NAME）：改用 --mode demo，或确认库名后再走变更单"
      ;;
  esac
  case "$PROFILE_LC" in
    dev* | test*)
      die 2 "prod 模式禁止 SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE（dev/test 仅用于演示与测试）"
      ;;
  esac
fi

# ---------------------------------------------------------------------------
# dry-run：只打印计划，不连接数据库、不跑 Maven
# ---------------------------------------------------------------------------
LOCATIONS_DESC="filesystem:$ROOT/db/migration"
[ "$MODE" = "demo" ] && LOCATIONS_DESC="filesystem:$ROOT/db/migration + filesystem:$ROOT/db/seed/demo"

if [ "$DRY_RUN" -eq 1 ]; then
  cat <<EOF
init-data（dry-run，不会连接数据库）
  模式            : $MODE
  目标库          : ${DB_URL:-<未提供，实际执行时必须 --url 或 ANESOP_DB_URL>}
  数据库账号      : $DB_USER
  Flyway locations: $LOCATIONS_DESC
  生产基线        : V2 角色/权限/字典 + V3/V5/V6…平台权限；不创建用户
  安全闸门        : prod 需 --confirm-prod；库名不得含 demo/dev/test/local；demo 不得用于 prod/uat/staging
  迁移后核对      : 角色/权限/字典类型/字典项计数；prod 另要求 demo_* 账号 = 0
  预计步骤        : 备份确认 → 解析 api-bootstrap classpath → Flyway migrate → 基线核对 → 输出 schema 版本
EOF
  if [ "$MODE" = "prod" ] && [ "$CONFIRM_PROD" -ne 1 ]; then
    printf '  阻断            : 实际执行需追加 --confirm-prod（生产发布须 release.yml 审批 + 人工签字）\n'
  fi
  if [ "$MODE" = "demo" ]; then
    printf '  演示数据        : V100/V101（SYNTHETIC，仅 dev/demo；账号首次登录强制改密）\n'
  fi
  exit 0
fi

[ -n "$DB_URL" ] ||
  die 2 "缺少数据库连接串：用 --url jdbc:postgresql://host:port/db，或设置 ANESOP_DB_URL / DATABASE_URL"

if [ "$MODE" = "prod" ] && [ "$CONFIRM_PROD" -ne 1 ]; then
  die 2 "prod 模式必须显式 --confirm-prod（生产发布须 release.yml 审批 + 院方人工签字；先 --dry-run 复核）"
fi

# 预检端口：把"连接拒绝"变成可操作提示，而不是 Java 堆栈
if ! timeout 3 bash -c "exec 3<>/dev/tcp/$DB_HOST/$DB_PORT" 2>/dev/null; then
  cat >&2 <<EOF
init-data: 无法连接 PostgreSQL（$DB_HOST:$DB_PORT）
  - 确认实例已启动、端口/主机正确；
  - 确认 pg_hba/防火墙/SSL 允许本机；
  - 本机 Docker 不可用（EXT-08）时，请先准备本地或远程 PostgreSQL 16。
EOF
  exit 4
fi

# ---------------------------------------------------------------------------
# demo 模式：委托既有 seed-demo.sh（同一套 Flyway；口令走环境变量，不出现在 ps）
# ---------------------------------------------------------------------------
if [ "$MODE" = "demo" ]; then
  echo "init-data: mode=demo → 委托 scripts/seed-demo.sh（locations 含 db/seed/demo，全部 SYNTHETIC）"
  ANESOP_DB_PASSWORD="$DB_PASSWORD" \
    bash "$ROOT/scripts/seed-demo.sh" --url "$DB_URL" --user "$DB_USER" ||
    die 6 "seed-demo 失败（见上方日志；已应用迁移不会回滚，修复后重跑即可）"
  echo "init-data: OK（mode=demo）"
  exit 0
fi

# ---------------------------------------------------------------------------
# prod 模式：解析 api-bootstrap classpath，用 Flyway Java API 只加载 db/migration
# ---------------------------------------------------------------------------
# shellcheck source=scripts/env.sh
source "$ROOT/scripts/env.sh" >/dev/null
command -v java >/dev/null 2>&1 || die 3 "缺少 java（检查 .toolchain/jdk21）"
command -v mvn >/dev/null 2>&1 || die 3 "缺少 maven（检查 .toolchain/maven）"

WORK_DIR="$ROOT/.cache/init-data"
mkdir -p "$WORK_DIR"
CP_FILE="$WORK_DIR/classpath.txt"
JAVA_SRC="$WORK_DIR/InitDataRunner.java"
LOGBACK_FILE="$WORK_DIR/logback.xml"
RUN_LOG="$WORK_DIR/init-data.log"
rm -f "$CP_FILE" "$RUN_LOG"

cat >"$LOGBACK_FILE" <<'XML'
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%-5level %logger{24} - %msg%n</pattern></encoder>
  </appender>
  <logger name="org.flywaydb" level="INFO"/>
  <root level="WARN"><appender-ref ref="STDOUT"/></root>
</configuration>
XML

echo "init-data: 解析 api-bootstrap 运行时 classpath（首次可能需要下载依赖）…"
if ! ./scripts/mvn.sh -q -B -f apps/api/pom.xml \
  -pl api-bootstrap -am -DskipTests \
  compile dependency:build-classpath \
  -Dmdep.includeScope=runtime -Dmdep.outputFile="$CP_FILE" >"$WORK_DIR/classpath.log" 2>&1; then
  tail -n 20 "$WORK_DIR/classpath.log" >&2
  die 5 "Maven 解析 classpath 失败：见上方日志（常见原因：网络不可达、.m2repo 无缓存、JDK 版本不对）"
fi
[ -s "$CP_FILE" ] || die 5 "classpath 文件为空：$CP_FILE"
CLASSPATH_CONTENT="$(tr -d '\n' <"$CP_FILE")"

cat >"$JAVA_SRC" <<'JAVA'
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * 一次性 Flyway 迁移器（由 scripts/init-data.sh 生成到 .cache/init-data/，不入库）。
 * 参数：url user password locations
 */
public final class InitDataRunner {

    private InitDataRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("用法：InitDataRunner <url> <user> <password> <locations>");
            System.exit(2);
        }
        String url = args[0];
        String user = args[1];
        String password = args[2];
        String locations = args[3];

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations.split(","))
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .load();

        MigrateResult result = flyway.migrate();
        MigrationInfo current = flyway.info().current();
        String version = current == null ? "(none)" : current.getVersion().getVersion();
        System.out.println("INIT_MIGRATIONS=" + result.migrationsExecuted);
        System.out.println("INIT_SCHEMA_VERSION=" + version);

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            count(connection, "ROLE", "select count(*) from m0_platform.sys_role");
            count(connection, "PERMISSION", "select count(*) from m0_platform.sys_permission");
            count(connection, "DICT_TYPE", "select count(*) from m0_platform.sys_dict_type");
            count(connection, "DICT_ITEM", "select count(*) from m0_platform.sys_dict_item");
            count(connection, "ORG", "select count(*) from m0_platform.sys_org");
            count(connection, "USER", "select count(*) from m0_platform.sys_user");
            count(connection, "DEMO_USERS",
                    "select count(*) from m0_platform.sys_user where username like 'demo\\_%' escape '\\'");
        }
    }

    private static void count(Connection connection, String label, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            System.out.println("INIT_" + label + "_COUNT=" + rs.getLong(1));
        }
    }
}
JAVA

echo "init-data: 执行 Flyway migrate（locations=$LOCATIONS_DESC）…"
set +e
java -Dfile.encoding=UTF-8 -Dlogback.configurationFile="$LOGBACK_FILE" \
  -cp "$CLASSPATH_CONTENT" "$JAVA_SRC" \
  "$DB_URL" "$DB_USER" "$DB_PASSWORD" "filesystem:$ROOT/db/migration" 2>&1 | tee "$RUN_LOG"
JAVA_RC=${PIPESTATUS[0]}
set -e

if [ "$JAVA_RC" -ne 0 ]; then
  if grep -Eqi "Connection to .* refused|The connection attempt failed|Unable to obtain connection|UnknownHostException|timeout" "$RUN_LOG"; then
    die 4 "Flyway 无法连接 PostgreSQL（$DB_HOST:$DB_PORT）：检查实例、账号口令、pg_hba/SSL 配置"
  fi
  die 6 "Flyway 迁移失败（exit=$JAVA_RC）：检查上方日志；已应用的迁移不会回滚，修复后重跑即可"
fi

read_init() { sed -n "s/^INIT_$1=//p" "$RUN_LOG" | tail -n1; }

MIGRATIONS="$(read_init MIGRATIONS)"
SCHEMA_VERSION="$(read_init SCHEMA_VERSION)"
ROLE_COUNT="$(read_init ROLE_COUNT)"
PERMISSION_COUNT="$(read_init PERMISSION_COUNT)"
DICT_TYPE_COUNT="$(read_init DICT_TYPE_COUNT)"
DICT_ITEM_COUNT="$(read_init DICT_ITEM_COUNT)"
ORG_COUNT="$(read_init ORG_COUNT)"
USER_COUNT="$(read_init USER_COUNT)"
DEMO_USERS="$(read_init DEMO_USERS)"

echo
echo "------------------------------------------------------------"
echo "init-data: 迁移完成"
echo "  本次执行迁移数：${MIGRATIONS:-unknown}（0 = 已是最新，幂等）"
echo "  schema 版本    ：${SCHEMA_VERSION:-unknown}"

# ---------------------------------------------------------------------------
# 基线核对：demo_* 永远为 0；生产基线计数不得低于 V2 的下限
# ---------------------------------------------------------------------------
if [ -z "$DEMO_USERS" ]; then
  die 7 "未取得 demo_* 账号计数（迁移输出不完整），按失败处理"
fi
if [ "$DEMO_USERS" -ne 0 ]; then
  die 7 "生产库发现 $DEMO_USERS 个 demo_* 账号：演示库不得当生产库使用，立即停用并重新初始化/清理"
fi

if [ "$SKIP_BASELINE_CHECK" -ne 1 ]; then
  [ -n "$ROLE_COUNT" ] && [ "$ROLE_COUNT" -ge 6 ] ||
    die 7 "平台角色基线不足（sys_role=$ROLE_COUNT，期望 ≥6）：检查 V2 是否完整应用"
  [ -n "$PERMISSION_COUNT" ] && [ "$PERMISSION_COUNT" -ge 17 ] ||
    die 7 "平台权限基线不足（sys_permission=$PERMISSION_COUNT，期望 ≥17）"
  [ -n "$DICT_TYPE_COUNT" ] && [ "$DICT_TYPE_COUNT" -ge 6 ] ||
    die 7 "字典类型基线不足（sys_dict_type=$DICT_TYPE_COUNT，期望 ≥6）"
  [ -n "$DICT_ITEM_COUNT" ] && [ "$DICT_ITEM_COUNT" -ge 22 ] ||
    die 7 "字典项基线不足（sys_dict_item=$DICT_ITEM_COUNT，期望 ≥22）"
fi

echo "  角色/权限/字典：${ROLE_COUNT:-?} / ${PERMISSION_COUNT:-?} / ${DICT_TYPE_COUNT:-?} 类 ${DICT_ITEM_COUNT:-?} 项"
echo "  组织机构/用户：${ORG_COUNT:-?} / ${USER_COUNT:-?}（生产首次初始化应为 0 用户）"

if [ "${ORG_COUNT:-0}" -eq 0 ] 2>/dev/null; then
  warn "sys_org=0：组织（SITE/DEPT）、术间、人员与首个管理员需按 docs/deploy/DATA-INIT.md §4 以变更单导入"
fi
if [ "${USER_COUNT:-0}" -gt 0 ] 2>/dev/null; then
  warn "sys_user=$USER_COUNT：非首次初始化；确认账号来源合法且不含 demo_*"
fi

echo "  下一步：按 docs/deploy/GO-LIVE-CHECKLIST.md 逐项勾选；演示数据请用 --mode demo 的独立库。"
echo "------------------------------------------------------------"
exit 0
