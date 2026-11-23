#!/usr/bin/env bash
# 每日数据库备份（S2-4，docs/ops/部署与回滚.md §5）。在服务器上由 cron 每天跑一次：
#
#   15 3 * * * root /opt/low-altitude/infra/prod/backup/pg-backup.sh >> /var/log/low-altitude/backup.log 2>&1
#
# 用 postgres 容器里的 pg_dump 导出自定义格式（-Fc，可以按表恢复），写到宿主机的 ${LA_BACKUP_DIR}/db/，
# 同时写 .sha256；保留 LA_BACKUP_KEEP_DAYS 天（默认 14）。只读数据库，不停服务。
# 可选：LA_BACKUP_OFFSITE_CMD 设成把文件拷到异地的命令（例如对象存储的命令行），会以文件路径为参数调用。
#
#   LA_ENV_FILE   默认 /etc/low-altitude/prod.env（读 LA_DB_NAME、LA_DB_USERNAME、LA_BACKUP_DIR 等）
#   LA_COMPOSE    默认本脚本旁边的 ../docker-compose.yml
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${LA_ENV_FILE:-/etc/low-altitude/prod.env}"
COMPOSE_FILE="${LA_COMPOSE:-$HERE/../docker-compose.yml}"

log() { printf '%s %s\n' "$(date '+%F %T')" "$*"; }

[ -r "$ENV_FILE" ] || { log "读不到 $ENV_FILE"; exit 1; }
# 只取需要的几个变量，不把整个文件 source 进来（里面有密钥）。
read_var() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"; }
DB_NAME="$(read_var LA_DB_NAME)"
DB_USER="$(read_var LA_DB_USERNAME)"
BACKUP_DIR="$(read_var LA_BACKUP_DIR)"
KEEP_DAYS="$(read_var LA_BACKUP_KEEP_DAYS)"
KEEP_DAYS="${KEEP_DAYS:-14}"
OFFSITE_CMD="$(read_var LA_BACKUP_OFFSITE_CMD)"
[ -n "$DB_NAME" ] && [ -n "$DB_USER" ] && [ -n "$BACKUP_DIR" ] || { log "LA_DB_NAME、LA_DB_USERNAME、LA_BACKUP_DIR 要在 $ENV_FILE 里"; exit 1; }

compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }

STAMP="$(date '+%Y%m%d-%H%M%S')"
NAME="${DB_NAME}-${STAMP}.dump"
umask 077
mkdir -p "$BACKUP_DIR/db"

log "开始备份 $DB_NAME"
# 从容器的标准输出写到宿主机（文件属主是跑脚本的用户，权限按上面的 umask），先写临时名，成功后改名，
# 免得留下半截文件被当成好的。
compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc -Z 6 > "$BACKUP_DIR/db/.${NAME}.partial"
mv "$BACKUP_DIR/db/.${NAME}.partial" "$BACKUP_DIR/db/$NAME"
# 校验能读：列一遍目录，不恢复。
compose exec -T postgres pg_restore -l < "$BACKUP_DIR/db/$NAME" > /dev/null
( cd "$BACKUP_DIR/db" && sha256sum "$NAME" > "$NAME.sha256" )
log "完成 $NAME（$(du -h "$BACKUP_DIR/db/$NAME" | cut -f1)）"

if [ -n "$OFFSITE_CMD" ]; then
  log "拷到异地"
  $OFFSITE_CMD "$BACKUP_DIR/db/$NAME"
  $OFFSITE_CMD "$BACKUP_DIR/db/$NAME.sha256"
fi

# 清理过期的（只删本脚本写的文件名格式）。
find "$BACKUP_DIR/db" -maxdepth 1 -type f \( -name "${DB_NAME}-*.dump" -o -name "${DB_NAME}-*.dump.sha256" \) -mtime +"$KEEP_DAYS" -print -delete \
  | sed 's/^/删除过期备份 /'
log "结束"
