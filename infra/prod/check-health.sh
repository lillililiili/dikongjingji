#!/usr/bin/env bash
# 发布后和日常巡查用的健康检查（S2-4，docs/ops/部署与回滚.md §4）。在服务器上运行：
#
#   infra/prod/check-health.sh
#
# 1. 各容器的健康状态（postgres、redis、minio、backend、nginx 都要 healthy / running）；
# 2. 用 HTTPS 检查 API、H5、对象存储，以及已启用的工作台页面和同源 API；
# 3. 证书还有多少天过期（少于 20 天报警）。
# 任何一项不对退出码为 1。只读，不改任何东西。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${LA_ENV_FILE:-/etc/low-altitude/prod.env}"
COMPOSE_FILE="${LA_COMPOSE:-$HERE/docker-compose.yml}"
read_var() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"; }
API="$(read_var LA_API_DOMAIN)"; H5="$(read_var LA_H5_DOMAIN)"; FILES="$(read_var LA_FILES_DOMAIN)"
ADMIN="$(read_var LA_ADMIN_DOMAIN)"
TLS_DIR="$(read_var LA_TLS_DIR)"; TLS_DIR="${TLS_DIR:-/etc/low-altitude/tls}"

failed=0
ok() { printf '[通过] %s\n' "$*"; }
bad() { printf '[不通过] %s\n' "$*"; failed=1; }

for service in postgres redis minio backend nginx; do
  state="$(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps --format '{{.Health}} {{.State}}' "$service" 2>/dev/null | head -n 1)"
  case "$state" in
    "healthy running") ok "$service 健康" ;;
    *) bad "$service 状态：${state:-没有运行}" ;;
  esac
done

if curl -fsS --max-time 10 "https://$API/healthz" | grep -q '"status":"UP"'; then ok "接口 https://$API/healthz 回 UP"; else bad "接口 https://$API/healthz 不通或不是 UP"; fi
if curl -fsS --max-time 10 -o /dev/null "https://$H5/"; then ok "H5 https://$H5/ 能打开"; else bad "H5 https://$H5/ 打不开"; fi
if [ -n "$ADMIN" ]; then
  if curl -fsS --max-time 10 "https://$ADMIN/signup" | grep -q '<div id="app"'; then ok "工作台入驻页能打开"; else bad "工作台入驻页异常"; fi
  code="$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "https://$ADMIN/api/v1/auth/me" || true)"
  if [ "$code" = "401" ]; then ok "工作台同源 API 已连接"; else bad "工作台同源 API 匿名请求应返回 401，实际为 $code"; fi
fi
# 对象存储根路径匿名访问会被拒（403），能连上、证书对就算通。
code="$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "https://$FILES/" || true)"
if [ "$code" = "403" ] || [ "$code" = "400" ] || [ "$code" = "200" ]; then ok "对象存储 https://$FILES/ 能连上（HTTP $code）"; else bad "对象存储 https://$FILES/ 连不上（HTTP ${code:-无}）"; fi

if [ -r "$TLS_DIR/fullchain.pem" ]; then
  end="$(openssl x509 -in "$TLS_DIR/fullchain.pem" -noout -enddate | cut -d= -f2)"
  if openssl x509 -in "$TLS_DIR/fullchain.pem" -noout -checkend $((20 * 86400)) > /dev/null; then ok "证书到期：$end"; else bad "证书 20 天内到期：$end"; fi
else
  bad "读不到 $TLS_DIR/fullchain.pem"
fi

exit "$failed"
