#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 演示数据初始化（ACCEPTANCE C 第 1 步，S0-12）
#
# 做什么：
#   对一个可连接的 PostgreSQL 16 执行 Flyway 迁移，locations 固定为
#     <repo>/db/migration        生产结构基线（V1…V4，含 M0/M2 采集表）
#     <repo>/db/seed/demo        演示账号 demo_admin（V100，SYNTHETIC）
#   Flyway 自身记录 schema history，因此脚本是幂等的：重复执行只做校验，
#   不会重复插入账号，也不会重复执行已应用的迁移。
#
# 为什么不用 `mvn flyway:migrate`：
#   仓库刻意没有在 pom 里声明 flyway-maven-plugin（改 pom 属 S0-12 写域之外）。
#   本脚本改用「Maven 解析 api-bootstrap 运行时 classpath + Flyway Java API」，
#   执行的仍是同一组 locations 与同一套 SQL；效果等价，且不启动 Web 容器。
#
# 前置条件（缺一不可）：
#   1) 已 `source scripts/env.sh`（JDK 21、仓库内 .m2repo；脚本会自行 source）；
#   2) PostgreSQL 16 已启动且可从本机连接；库可以是空库，也可以已有 Flyway 历史；
#   3) 首次运行需要 Maven 缓存或网络（解析 classpath 与编译 api-bootstrap 依赖）；
#   4) 目标库必须是 dev/demo/test 库：脚本拒绝库名含 prod/production/uat/staging
#      的目标（确需覆盖时用 --allow-non-dev，责任自负）；生产禁止演示种子。
#
# 用法（仓库根）：
#   bash scripts/seed-demo.sh --url jdbc:postgresql://127.0.0.1:5432/anesop \
#        [--user anesop] [--password anesop]
#   ANESOP_DB_URL=... bash scripts/seed-demo.sh          # 读环境变量
#   bash scripts/seed-demo.sh --compose                  # Docker 可用时在 api 容器内执行
#   bash scripts/seed-demo.sh --help
#
# 连接参数来源优先级：命令行 > ANESOP_DB_URL / ANESOP_DB_USER / ANESOP_DB_PASSWORD
#                     > DATABASE_URL（仅 URL）。
#
# 输出与退出码：
#   0  迁移成功（含「已是最新」的幂等场景）；打印演示账号与「必须改密」提示
#   2  参数错误 / 连接串格式非法 / 拒绝非 dev 库
#   3  Docker 不可用（EXT-08）或 api 容器未运行（仅 --compose）
#   4  无法连接 PostgreSQL（端口不通）
#   5  Maven 解析 classpath 失败
#   6  Flyway 迁移或校验失败
#
# 安全：演示数据全部为 SYNTHETIC；不打印口令，不写患者数据；生产环境禁用本脚本。
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE_FILE="$ROOT/deploy/compose/docker-compose.yml"
MODE="local"
DB_URL="${ANESOP_DB_URL:-${DATABASE_URL:-}}"
DB_USER="${ANESOP_DB_USER:-anesop}"
DB_PASSWORD="${ANESOP_DB_PASSWORD:-}"
ALLOW_NON_DEV=0

usage() {
  # 打印文件头部的连续注释块（到第一行非注释为止）
  awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"
}

die() {
  local code="$1"
  shift
  printf 'seed-demo: %s\n' "$*" >&2
  exit "$code"
}

while [ $# -gt 0 ]; do
  case "$1" in
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
      [ $# -ge 2 ] || die 2 "缺少 --password 的值"
      DB_PASSWORD="$2"
      shift 2
      ;;
    --password=*)
      DB_PASSWORD="${1#*=}"
      shift
      ;;
    --compose)
      MODE="compose"
      shift
      ;;
    --allow-non-dev)
      ALLOW_NON_DEV=1
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

if [ "$MODE" = "compose" ]; then
  # -------------------------------------------------------------------------
  # Docker Compose 模式：在运行中的 api 容器内执行一次性迁移
  # 本机施工环境不可用（EXT-08），只做能力探测，不启动任何容器。
  # -------------------------------------------------------------------------
  command -v docker >/dev/null 2>&1 ||
    die 3 "Docker CLI 不可用（EXT-08）：本机无法执行 --compose。请改用 --url 指向一个可连接的 PostgreSQL 16。"

  if ! docker info >/dev/null 2>&1; then
    die 3 "Docker daemon 不可用（EXT-08）：本机无法执行 --compose。请改用 --url 指向一个可连接的 PostgreSQL 16，或在具备 Docker 24+ 的宿主机重试。"
  fi

  [ -f "$COMPOSE_FILE" ] || die 3 "缺少 $COMPOSE_FILE"

  if [ -z "$(docker compose -f "$COMPOSE_FILE" ps -q api 2>/dev/null)" ]; then
    die 3 "Compose 中 api 服务未运行：先 docker compose -f deploy/compose/docker-compose.yml up -d（并等待 healthcheck 通过）"
  fi

  echo "seed-demo: 模式=compose（在 api 容器内执行 Flyway；dev locations = db/migration + db/seed/demo）"
  docker compose -f "$COMPOSE_FILE" exec -T api \
    java -jar /opt/anesop/app.jar \
    --spring.profiles.active=dev \
    --spring.main.web-application-type=none \
    --spring.flyway.locations=classpath:db/migration,classpath:db/seed/demo ||
    die 6 "容器内 Flyway 执行失败：检查 api 容器日志与数据库连通性"

  echo
  echo "seed-demo: OK（幂等：重复执行不会重复插入）"
  echo "  演示账号：demo_admin（SYNTHETIC，角色 SYS_ADMIN）"
  echo "  初始口令：见 docs/db/seed.md（禁止写入工单、日志或截图）"
  echo "  注意：must_change_password=true，首次登录必须先改密；生产禁止加载 db/seed/demo。"
  echo "  校验（可选）：docker compose -f deploy/compose/docker-compose.yml exec -T postgres \\"
  echo "    sh -c 'psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"select username, must_change_password from m0_platform.sys_user\"'"
  exit 0
fi

# ---------------------------------------------------------------------------
# 本地模式
# ---------------------------------------------------------------------------
[ -n "$DB_URL" ] ||
  die 2 "缺少数据库连接串：用 --url jdbc:postgresql://host:port/db，或设置 ANESOP_DB_URL / DATABASE_URL。本机 Docker 不可用（EXT-08）时需自备 PostgreSQL 16。"

case "$DB_URL" in
  jdbc:postgresql://*) ;;
  *) die 2 "连接串必须以 jdbc:postgresql:// 开头，当前为：$DB_URL" ;;
esac

if [[ "$DB_URL" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^?]+) ]]; then
  DB_HOST="${BASH_REMATCH[1]}"
  DB_PORT="${BASH_REMATCH[3]:-5432}"
  DB_NAME="${BASH_REMATCH[4]}"
else
  die 2 "无法解析连接串（支持 jdbc:postgresql://host[:port]/db[?params]）：$DB_URL"
fi

# 生产红线：拒绝明显是生产/预发库的库名（可用 --allow-non-dev 显式覆盖）
DB_NAME_LC="$(printf '%s' "$DB_NAME" | tr '[:upper:]' '[:lower:]')"
PROFILE_LC="$(printf '%s' "${SPRING_PROFILES_ACTIVE:-}" | tr '[:upper:]' '[:lower:]')"
if [ "$ALLOW_NON_DEV" -ne 1 ]; then
  case "$DB_NAME_LC" in
    *prod* | *uat* | *staging*)
      die 2 "拒绝在疑似生产/预发库（$DB_NAME）上执行演示种子；如确认是隔离环境，加 --allow-non-dev 并自行承担风险"
      ;;
  esac
  case "$PROFILE_LC" in
    prod*)
      die 2 "SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE：生产 profile 禁止执行演示种子"
      ;;
  esac
fi

[ -d "$ROOT/db/migration" ] || die 5 "缺少 db/migration 目录"
[ -d "$ROOT/db/seed/demo" ] || die 5 "缺少 db/seed/demo 目录"

echo "seed-demo: 目标库 jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME（用户 $DB_USER）"
echo "seed-demo: Flyway locations = db/migration + db/seed/demo（仅 dev/demo；生产不得包含后者）"

# 预检端口：把“连接拒绝”变成可操作的提示，而不是 Java 堆栈
if ! timeout 3 bash -c "exec 3<>/dev/tcp/$DB_HOST/$DB_PORT" 2>/dev/null; then
  cat >&2 <<EOF
seed-demo: 无法连接 PostgreSQL（$DB_HOST:$DB_PORT）
  可能原因：
    - PG 未启动，或端口/主机写错；
    - 本机 Docker 不可用（EXT-08），尚未启动本地 PG；
    - 远程库需要 VPN/防火墙放行。
  处理：
    - 启动本地 PostgreSQL 16 后重试，或
    - 在具备 Docker 的机器上用 --compose，或
    - 参考 docs/guides/troubleshooting.md「没有 PostgreSQL / Docker 不可用」。
EOF
  exit 4
fi

# ---------------------------------------------------------------------------
# 解析 api-bootstrap 运行时 classpath（包含 flyway-core / flyway-database-postgresql
# / PostgreSQL JDBC 驱动），随后用 Flyway Java API 直接迁移，不启动 Spring 容器。
# ---------------------------------------------------------------------------
source "$ROOT/scripts/env.sh" >/dev/null

WORK_DIR="$ROOT/.cache/seed-demo"
mkdir -p "$WORK_DIR"
CP_FILE="$WORK_DIR/classpath.txt"
JAVA_SRC="$WORK_DIR/SeedDemoRunner.java"
LOGBACK_FILE="$WORK_DIR/logback.xml"
RUN_LOG="$WORK_DIR/seed-demo.log"
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

echo "seed-demo: 解析 api-bootstrap 运行时 classpath（首次可能需要下载依赖）…"
if ! ./scripts/mvn.sh -q -B -f apps/api/pom.xml \
  -pl api-bootstrap -am -DskipTests \
  compile dependency:build-classpath \
  -Dmdep.includeScope=runtime -Dmdep.outputFile="$CP_FILE" >"$WORK_DIR/classpath.log" 2>&1; then
  tail -n 20 "$WORK_DIR/classpath.log" >&2
  die 5 "Maven 解析 classpath 失败：见上方日志（常见原因：网络不可达、.m2repo 无缓存、JDK 版本不对）"
fi
[ -s "$CP_FILE" ] || die 5 "classpath 文件为空：$CP_FILE（Maven 未按预期写出依赖）"
CLASSPATH_CONTENT="$(tr -d '\n' <"$CP_FILE")"

cat >"$JAVA_SRC" <<'JAVA'
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * 一次性 Flyway 迁移器（由 scripts/seed-demo.sh 生成到 .cache/seed-demo/，不入库）。
 * 参数：url user password repoRoot。
 */
public final class SeedDemoRunner {

    private SeedDemoRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("用法：SeedDemoRunner <url> <user> <password> <repoRoot>");
            System.exit(2);
        }
        String url = args[0];
        String user = args[1];
        String password = args[2];
        String repoRoot = args[3];

        String migration = "filesystem:" + repoRoot + "/db/migration";
        String demoSeed = "filesystem:" + repoRoot + "/db/seed/demo";

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations(migration, demoSeed)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .load();

        MigrateResult result = flyway.migrate();
        MigrationInfo current = flyway.info().current();
        String version = current == null ? "(none)" : current.getVersion().getVersion();

        System.out.println("SEED_MIGRATIONS=" + result.migrationsExecuted);
        System.out.println("SEED_SCHEMA_VERSION=" + version);

        try (Connection connection = DriverManager.getConnection(url, user, password);
                PreparedStatement statement = connection.prepareStatement(
                        "select username, display_name, must_change_password, status "
                                + "from m0_platform.sys_user where username = ?")) {
            statement.setString(1, "demo_admin");
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    System.out.println("SEED_DEMO_ACCOUNT=" + rs.getString(1) + "|"
                            + rs.getString(2) + "|mustChange=" + rs.getBoolean(3)
                            + "|status=" + rs.getInt(4));
                } else {
                    System.out.println("SEED_DEMO_ACCOUNT=NOT_FOUND");
                }
            }
        }
    }
}
JAVA

echo "seed-demo: 执行 Flyway migrate…"
set +e
java -Dfile.encoding=UTF-8 -Dlogback.configurationFile="$LOGBACK_FILE" \
  -cp "$CLASSPATH_CONTENT" "$JAVA_SRC" \
  "$DB_URL" "$DB_USER" "$DB_PASSWORD" "$ROOT" 2>&1 | tee "$RUN_LOG"
JAVA_RC=${PIPESTATUS[0]}
set -e

if [ "$JAVA_RC" -ne 0 ]; then
  if grep -Eqi "Connection to .* refused|The connection attempt failed|Unable to obtain connection|UnknownHostException|timeout" "$RUN_LOG"; then
    die 4 "Flyway 无法连接 PostgreSQL（$DB_HOST:$DB_PORT）：检查实例、账号口令、pg_hba/SSL 配置"
  fi
  die 6 "Flyway 迁移失败（exit=$JAVA_RC）：检查上方日志；已应用的迁移不会回滚，修复后重跑即可"
fi

MIGRATIONS="$(sed -n 's/^SEED_MIGRATIONS=//p' "$RUN_LOG" | tail -n1)"
SCHEMA_VERSION="$(sed -n 's/^SEED_SCHEMA_VERSION=//p' "$RUN_LOG" | tail -n1)"
ACCOUNT_LINE="$(sed -n 's/^SEED_DEMO_ACCOUNT=//p' "$RUN_LOG" | tail -n1)"

echo
echo "------------------------------------------------------------"
echo "seed-demo: OK"
echo "  本次执行的迁移数：${MIGRATIONS:-unknown}（0 = 已是最新，幂等生效）"
echo "  当前 schema 版本：${SCHEMA_VERSION:-unknown}"
if [ -n "$ACCOUNT_LINE" ] && [ "$ACCOUNT_LINE" != "NOT_FOUND" ]; then
  IFS='|' read -r ACC_NAME ACC_DISPLAY ACC_MUST ACC_STATUS <<<"$ACCOUNT_LINE"
  echo "  演示账号：${ACC_NAME}（${ACC_DISPLAY}；${ACC_MUST}；${ACC_STATUS}）"
else
  echo "  演示账号：未找到 demo_admin —— 检查 db/seed/demo/V100 是否被 Flyway 加载"
fi
echo "  初始口令：见 docs/db/seed.md（禁止写入工单、日志或截图）"
echo "  必须改密：must_change_password=true；首次登录后先改密，再重新登录。"
echo "  安全：全部为 SYNTHETIC 演示数据；生产/预发禁止加载 db/seed/demo。"
echo "------------------------------------------------------------"
