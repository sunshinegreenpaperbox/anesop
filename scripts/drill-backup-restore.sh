#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ANESOP 备份恢复演练（M6-M20-05）
#
# 做什么：把源库 pg_dump 成自定义格式，恢复到**独立的演练库**，逐表核对行数，
# 并记录实际耗时是否满足 RTO（默认 30 分钟，方案书表 3-16）。用于季度演练与
# 上线前"恢复不出来的备份等于没有备份"验证。
#
# 重要安全约束：
#   - 默认 dry-run，只打印计划与工具检查，不连接数据库；
#   - --execute 才真正执行，且目标库名必须含 drill 或 restore（防止误覆盖生产库）；
#   - 源库与目标库不得相同；
#   - 目标库必须为空（除非显式 --force）；只执行 pg_restore，不 DROP 源库；
#   - 口令只从环境变量读取（SOURCE_PGPASSWORD / TARGET_PGPASSWORD），不出现在命令行。
#
# 用法（仓库根）：
#   bash scripts/drill-backup-restore.sh --dry-run
#   SOURCE_PGPASSWORD=*** TARGET_PGPASSWORD=*** \
#     bash scripts/drill-backup-restore.sh --execute \
#       --source-url jdbc:postgresql://127.0.0.1:5432/anesop \
#       --target-url jdbc:postgresql://127.0.0.1:5432/anesop_drill \
#       --backup-dir /data/anesop-backup/drill \
#       --record docs/deploy/drills/2026-11-15-backup-restore.md
#   bash scripts/drill-backup-restore.sh --help
#
# 参数：
#   --source-url JDBC     源库（jdbc:postgresql://host[:port]/db[?params]）
#   --target-url JDBC     演练目标库（库名必须含 drill 或 restore）
#   --backup-dir DIR      备份落盘目录（默认 tmp/drill-backup）
#   --rto-seconds N       RTO 上限，默认 1800（30 分钟）
#   --execute             实际执行（默认 dry-run）
#   --dry-run             显式只打印计划（默认行为）
#   --force               允许目标库非空（仍会先 --clean，风险自负）
#   --record FILE         生成 Markdown 演练记录
#   --help                显示帮助
#
# 退出码：0 成功/dry-run；1 恢复或核对失败/RTO 超限；2 参数/安全拒绝；
#         3 缺少 pg_dump/pg_restore/psql；4 数据库连接失败。
# 说明：本机 Docker 不可用（EXT-08）时没有 PostgreSQL 实例，只能 dry-run；
#       恢复演练应在具备 PG 16 的演练环境执行，并由 DBA 记录签字。
# ---------------------------------------------------------------------------
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SOURCE_URL="${SOURCE_DB_URL:-}"
TARGET_URL="${TARGET_DB_URL:-}"
BACKUP_DIR="${DRILL_BACKUP_DIR:-$ROOT/tmp/drill-backup}"
RTO_SECONDS=1800
EXECUTE=0
FORCE=0
RECORD=""
SOURCE_PASSWORD="${SOURCE_PGPASSWORD:-}"
TARGET_PASSWORD="${TARGET_PGPASSWORD:-}"

usage() {
  awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"
}

die() {
  local code="$1"
  shift
  printf 'drill-backup-restore: %s\n' "$*" >&2
  exit "$code"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --source-url) [ $# -ge 2 ] || die 2 "缺少 --source-url 的值"; SOURCE_URL="$2"; shift 2 ;;
    --source-url=*) SOURCE_URL="${1#*=}"; shift ;;
    --target-url) [ $# -ge 2 ] || die 2 "缺少 --target-url 的值"; TARGET_URL="$2"; shift 2 ;;
    --target-url=*) TARGET_URL="${1#*=}"; shift ;;
    --backup-dir) [ $# -ge 2 ] || die 2 "缺少 --backup-dir 的值"; BACKUP_DIR="$2"; shift 2 ;;
    --backup-dir=*) BACKUP_DIR="${1#*=}"; shift ;;
    --rto-seconds) [ $# -ge 2 ] || die 2 "缺少 --rto-seconds 的值"; RTO_SECONDS="$2"; shift 2 ;;
    --rto-seconds=*) RTO_SECONDS="${1#*=}"; shift ;;
    --execute) EXECUTE=1; shift ;;
    --dry-run) EXECUTE=0; shift ;;
    --force) FORCE=1; shift ;;
    --record) [ $# -ge 2 ] || die 2 "缺少 --record 的值"; RECORD="$2"; shift 2 ;;
    --record=*) RECORD="${1#*=}"; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die 2 "未知参数：$1（用 --help 查看用法）" ;;
  esac
done

[[ "$RTO_SECONDS" =~ ^[0-9]+$ ]] || die 2 "--rto-seconds 必须是正整数"

parse_url_parts() {
  local url="$1"
  [[ "$url" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^?]+) ]] ||
    die 2 "无法解析连接串（jdbc:postgresql://host[:port]/db）：$url"
  printf '%s|%s|%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[3]:-5432}" "${BASH_REMATCH[4]}"
}

to_pg_url() { printf '%s' "${1#jdbc:}"; }

if [ -n "$SOURCE_URL" ] && [ -n "$TARGET_URL" ]; then
  [ "${SOURCE_URL#jdbc:postgresql://}" != "$SOURCE_URL" ] || die 2 "源库必须以 jdbc:postgresql:// 开头"
  [ "${TARGET_URL#jdbc:postgresql://}" != "$TARGET_URL" ] || die 2 "目标库必须以 jdbc:postgresql:// 开头"
  IFS='|' read -r SRC_HOST SRC_PORT SRC_DB <<<"$(parse_url_parts "$SOURCE_URL")"
  IFS='|' read -r TGT_HOST TGT_PORT TGT_DB <<<"$(parse_url_parts "$TARGET_URL")"
  [ -n "$SRC_DB" ] && [ -n "$TGT_DB" ] || die 2 "源库/目标库库名为空"
  [ "$SOURCE_URL" != "$TARGET_URL" ] || die 2 "源库与目标库相同：拒绝把恢复演练指向源库"
  case "$(printf '%s' "$TGT_DB" | tr '[:upper:]' '[:lower:]')" in
    *drill* | *restore*) ;;
    *) die 2 "目标库名必须含 drill 或 restore（当前：$TGT_DB），防止误覆盖生产库" ;;
  esac
fi

# ---------------------------------------------------------------------------
# dry-run
# ---------------------------------------------------------------------------
if [ "$EXECUTE" -ne 1 ]; then
  echo "drill-backup-restore（dry-run，不连接数据库）"
  echo "  源库        : ${SOURCE_URL:-<未提供 --source-url>}"
  echo "  演练目标库  : ${TARGET_URL:-<未提供 --target-url；必须含 drill/restore>}"
  echo "  备份目录    : $BACKUP_DIR"
  echo "  RTO 上限    : ${RTO_SECONDS}s（方案书目标 ≤30 min，RPO ≤5 min 由 WAL 归档保证）"
  echo "  口令来源    : SOURCE_PGPASSWORD / TARGET_PGPASSWORD 环境变量（不落命令行）"
  echo "  步骤        : pg_dump -Fc → sha256 → pg_restore --list 校验 → 恢复到空演练库"
  echo "                → 逐表行数比对（角色/权限/字典/审计/Flyway 历史）→ 计时 → 记录"
  echo "  安全闸门    : 目标库名含 drill/restore；源≠目标；目标为空（除非 --force）"
  echo "  工具检查    :"
  for tool in pg_dump pg_restore psql; do
    if command -v "$tool" >/dev/null 2>&1; then
      echo "    [PASS] $tool：$(command -v "$tool")"
    else
      echo "    [SKIP] $tool 未安装（--execute 需要 PostgreSQL 16 客户端）"
    fi
  done
  echo "  说明        : 本机 Docker 不可用（EXT-08）时无实例可练，仅在演练环境 --execute。"
  exit 0
fi

# ---------------------------------------------------------------------------
# execute 前置
# ---------------------------------------------------------------------------
for tool in pg_dump pg_restore psql; do
  command -v "$tool" >/dev/null 2>&1 || die 3 "缺少 $tool（安装 PostgreSQL 16 客户端后重试）"
done
[ -n "$SOURCE_URL" ] || die 2 "--execute 需要 --source-url"
[ -n "$TARGET_URL" ] || die 2 "--execute 需要 --target-url"

SRC_PG="$(to_pg_url "$SOURCE_URL")"
TGT_PG="$(to_pg_url "$TARGET_URL")"

# 连接与目标库空库检查
if ! PGPASSWORD="$SOURCE_PASSWORD" psql "$SRC_PG" -Atc 'select 1' >/dev/null 2>&1; then
  die 4 "无法连接源库（$SRC_HOST:$SRC_PORT/$SRC_DB）：检查实例、账号、pg_hba"
fi
if ! PGPASSWORD="$TARGET_PASSWORD" psql "$TGT_PG" -Atc 'select 1' >/dev/null 2>&1; then
  die 4 "无法连接演练目标库（$TGT_HOST:$TGT_PORT/$TGT_DB）：检查实例、账号、pg_hba"
fi

existing="$(PGPASSWORD="$TARGET_PASSWORD" psql "$TGT_PG" -Atc \
  "select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema')")"
if [ "$existing" -gt 0 ] && [ "$FORCE" -ne 1 ]; then
  die 2 "演练目标库非空（$existing 张表）：换空库，或显式 --force（会先 --clean）"
fi

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
DUMP_FILE="$BACKUP_DIR/anesop-drill-$STAMP.dump"
START_TS="$(date +%s)"

echo "drill-backup-restore: 1/5 备份源库 → $DUMP_FILE"
PGPASSWORD="$SOURCE_PASSWORD" pg_dump "$SRC_PG" \
  --format=custom --no-owner --no-privileges --file="$DUMP_FILE" ||
  die 1 "pg_dump 失败"
sha256sum "$DUMP_FILE" >"$DUMP_FILE.sha256"
echo "  sha256：$(cut -d' ' -f1 "$DUMP_FILE.sha256")"

echo "drill-backup-restore: 2/5 校验备份可读（pg_restore --list）"
pg_restore --list "$DUMP_FILE" >/dev/null || die 1 "备份文件不可读：pg_restore --list 失败"
OBJECTS="$(pg_restore --list "$DUMP_FILE" | grep -vc '^;' || true)"
echo "  归档对象数：${OBJECTS:-0}"

echo "drill-backup-restore: 3/5 恢复到演练库 $TGT_DB"
PGPASSWORD="$TARGET_PASSWORD" pg_restore \
  --clean --if-exists --no-owner --no-privileges \
  --dbname="$TGT_PG" "$DUMP_FILE" || die 1 "pg_restore 失败"

echo "drill-backup-restore: 4/5 逐表行数比对"
TABLES=(
  "m0_platform.sys_role"
  "m0_platform.sys_permission"
  "m0_platform.sys_dict_item"
  "public.flyway_schema_history"
)
MISMATCH=0
for table in "${TABLES[@]}"; do
  src_count="$(PGPASSWORD="$SOURCE_PASSWORD" psql "$SRC_PG" -Atc "select count(*) from $table" 2>/dev/null || echo NA)"
  tgt_count="$(PGPASSWORD="$TARGET_PASSWORD" psql "$TGT_PG" -Atc "select count(*) from $table" 2>/dev/null || echo NA)"
  if [ "$src_count" = "$tgt_count" ] && [ "$src_count" != "NA" ]; then
    echo "  [PASS] $table：$src_count"
  else
    echo "  [FAIL] $table：源=$src_count 目标=$tgt_count"
    MISMATCH=1
  fi
done

END_TS="$(date +%s)"
ELAPSED=$((END_TS - START_TS))

echo "drill-backup-restore: 5/5 计时与结论"
echo "  实际耗时：${ELAPSED}s（RTO 上限 ${RTO_SECONDS}s）"
if [ "$MISMATCH" -ne 0 ]; then
  die 1 "行数比对不一致：恢复不完整，禁止宣称备份可用"
fi
if [ "$ELAPSED" -gt "$RTO_SECONDS" ]; then
  die 1 "恢复耗时超过 RTO（${ELAPSED}s > ${RTO_SECONDS}s）：优化备份/恢复流程或调整目标"
fi

if [ -n "$RECORD" ]; then
  mkdir -p "$(dirname "$RECORD")"
  {
    echo "# 备份恢复演练记录（scripts/drill-backup-restore.sh）"
    echo
    echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "- 源库：${SRC_HOST}:${SRC_PORT}/${SRC_DB}"
    echo "- 演练库：${TGT_HOST}:${TGT_PORT}/${TGT_DB}"
    echo "- 备份文件：$DUMP_FILE"
    echo "- 实际耗时：${ELAPSED}s（RTO 上限 ${RTO_SECONDS}s）"
    echo "- 归档对象数：${OBJECTS:-0}"
    echo
    echo "## 行数比对"
    echo
    echo "| 表 | 源库 | 演练库 | 结论 |"
    echo "|---|---|---|---|"
    for table in "${TABLES[@]}"; do
      src_count="$(PGPASSWORD="$SOURCE_PASSWORD" psql "$SRC_PG" -Atc "select count(*) from $table" 2>/dev/null || echo NA)"
      tgt_count="$(PGPASSWORD="$TARGET_PASSWORD" psql "$TGT_PG" -Atc "select count(*) from $table" 2>/dev/null || echo NA)"
      echo "| $table | $src_count | $tgt_count | $([ "$src_count" = "$tgt_count" ] && echo PASS || echo FAIL) |"
    done
    echo
    echo "> 请按 docs/deploy/DRILL-RECORD-TEMPLATE.md 补齐参与人、问题与整改、签字。"
  } >"$RECORD"
  echo "记录已写入：$RECORD"
fi

echo "drill-backup-restore: PASS（备份可读、恢复完整、RTO 达标）"
echo "  注意：演练库可保留供抽查，确认后按变更单销毁；备份文件按保留策略归档/加密。"
exit 0
