#!/usr/bin/env bash
# 从 pg-backup.sh 的备份恢复数据库（S2-4，docs/ops/部署与回滚.md §6）。
#
#   pg-restore.sh <备份文件> --check                 只校验：sha256、能列目录，恢复到临时库再删掉（不动正式库）
#   pg-restore.sh <备份文件> --into <库名>           恢复到一个新库（给核对数据、捞单条记录用），不动正式库
#   pg-restore.sh <备份文件> --replace-production    覆盖正式库：停后端 → 把现库改名留底 → 新建库并恢复 → 起后端
#
# 覆盖正式库前会再要你输入库名确认；原来的库改名为 <库名>_before_<时间>，确认没问题后手动删。
# 备份文件要在 ${LA_BACKUP_DIR}/db/ 下；脚本从宿主机把文件经标准输入交给容器里的 pg_restore。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${LA_ENV_FILE:-/etc/low-altitude/prod.env}"
COMPOSE_FILE="${LA_COMPOSE:-$HERE/../docker-compose.yml}"

usage() { sed -n '2,10p' "$0"; exit 2; }
log() { printf '%s %s\n' "$(date '+%F %T')" "$*"; }

[ $# -ge 2 ] || usage
FILE="$1"; MODE="$2"; TARGET="${3:-}"
[ -r "$ENV_FILE" ] || { log "读不到 $ENV_FILE"; exit 1; }
read_var() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"; }
DB_NAME="$(read_var LA_DB_NAME)"
DB_USER="$(read_var LA_DB_USERNAME)"
BACKUP_DIR="$(read_var LA_BACKUP_DIR)"
compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }
psql_admin() { compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d postgres "$@"; }

BASE="$(basename "$FILE")"
HOST_PATH="$BACKUP_DIR/db/$BASE"
[ -f "$HOST_PATH" ] || { log "备份文件要放在 $BACKUP_DIR/db/ 下：没有 $BASE"; exit 1; }
[[ "$BASE" =~ ^[A-Za-z0-9_.-]+$ ]] || { log "文件名只能有字母、数字和 _.-"; exit 1; }

if [ -f "$HOST_PATH.sha256" ]; then
  ( cd "$BACKUP_DIR/db" && sha256sum -c "$BASE.sha256" ) || { log "sha256 对不上，停止"; exit 1; }
else
  log "注意：没有 $BASE.sha256，跳过校验"
fi
compose exec -T postgres pg_restore -l < "$HOST_PATH" > /dev/null
log "备份能读"

restore_into() {
  local db="$1"
  [[ "$db" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] || { log "库名只能是小写字母、数字和下划线"; exit 1; }
  psql_admin -c "CREATE DATABASE \"$db\" OWNER \"$DB_USER\""
  # postgis 扩展在备份里，由 pg_restore 建；--no-owner 让对象归当前用户。
  compose exec -T postgres pg_restore -U "$DB_USER" -d "$db" --no-owner --exit-on-error < "$HOST_PATH"
  log "已恢复到库 $db"
}

case "$MODE" in
  --check)
    TMP="restore_check_$(date '+%Y%m%d%H%M%S')"
    restore_into "$TMP"
    compose exec -T postgres psql -U "$DB_USER" -d "$TMP" -Atc \
      "SELECT count(*) || ' 张表' FROM information_schema.tables WHERE table_schema = 'public'"
    psql_admin -c "DROP DATABASE \"$TMP\""
    log "检查通过，临时库已删"
    ;;
  --into)
    [ -n "$TARGET" ] || usage
    [ "$TARGET" != "$DB_NAME" ] || { log "--into 不能是正式库，用 --replace-production"; exit 1; }
    restore_into "$TARGET"
    ;;
  --replace-production)
    printf '要覆盖正式库 %s。输入库名确认：' "$DB_NAME"
    read -r answer
    [ "$answer" = "$DB_NAME" ] || { log "没有确认，停止"; exit 1; }
    KEEP="${DB_NAME}_before_$(date '+%Y%m%d%H%M%S')"
    log "停后端"
    compose stop backend
    log "把现库改名为 $KEEP"
    psql_admin -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid()" > /dev/null
    psql_admin -c "ALTER DATABASE \"$DB_NAME\" RENAME TO \"$KEEP\""
    restore_into "$DB_NAME"
    log "起后端（启动时按迁移补齐到当前版本）"
    compose up -d backend
    log "完成。核对无误后删掉旧库：DROP DATABASE \"$KEEP\";"
    ;;
  *)
    usage
    ;;
esac
