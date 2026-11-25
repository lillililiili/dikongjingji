#!/usr/bin/env bash
# 在构建机运行。只使用临时证书和隔离容器，不启动生产 Compose。
set -euo pipefail
prod_dir="$(cd "$(dirname "$0")/.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -f -- "$fixture"/{privkey.pem,fullchain.pem,openssl.log,prod.env}; rmdir -- "$fixture"' EXIT
# Windows 的 Docker CLI 需要宿主机路径。容器路径保持 Linux 格式。
host_path() { if command -v cygpath >/dev/null; then cygpath -am "$1"; else printf '%s\n' "$1"; fi; }
templates="$(host_path "$prod_dir/nginx/templates")"
tls="$(host_path "$fixture")"
image="${NGINX_TEST_IMAGE:-nginx:1.27-alpine}"
MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=admin.test' \
  -keyout "$tls/privkey.pem" -out "$tls/fullchain.pem" 2>"$fixture/openssl.log" || { cat "$fixture/openssl.log" >&2; exit 1; }

cat > "$fixture/prod.env" <<EOF
LA_ENV_FILE=$tls/prod.env
LA_ENVIRONMENT=staging
LA_BACKEND_TAG=fixture
LA_DATA_DIR=$tls/data
LA_DB_NAME=fixture
LA_DB_USERNAME=fixture
LA_DB_PASSWORD=fixture
LA_REDIS_PASSWORD=fixture
LA_MINIO_ACCESS_KEY=fixture-key
LA_MINIO_SECRET_KEY=fixture-secret
LA_API_DOMAIN=api.test
LA_H5_DOMAIN=m.test
LA_FILES_DOMAIN=files.test
EOF

for domain in '' admin.test; do
  LA_ADMIN_DOMAIN="$domain" docker compose --env-file "$fixture/prod.env" -f "$prod_dir/docker-compose.yml" config -q
  MSYS_NO_PATHCONV=1 docker run --rm -i --network none \
    --add-host backend:127.0.0.1 --add-host minio:127.0.0.1 \
    -e LA_API_DOMAIN=api.test -e LA_H5_DOMAIN=m.test -e LA_FILES_DOMAIN=files.test -e LA_ADMIN_DOMAIN="$domain" \
    --mount "type=bind,source=$templates,target=/etc/nginx/templates,readonly" \
    --mount "type=bind,source=$tls,target=/etc/nginx/tls,readonly" \
    "$image" sh -eu <<'CONTAINER'
mkdir -p /srv/web/current /srv/admin/releases/one/assets /srv/admin/releases/two
echo mobile > /srv/web/current/index.html
echo workbench-one > /srv/admin/releases/one/index.html
echo workbench-two > /srv/admin/releases/two/index.html
echo hashed-script > /srv/admin/releases/one/assets/app-123.js
ln -s releases/one /srv/admin/current
cat > /etc/nginx/conf.d/upstream.conf <<'NGINX'
server {
    listen 127.0.0.1:8080;
    location = /api/v1/auth/me { return 401; }
    location = /api/v1/trade/aftersale-images { client_max_body_size 6m; return 401; }
    location = /api/v1/organization-signups { return 200 "$request_method:$request_uri:$http_host:$http_x_forwarded_proto"; }
    location / { return 404; }
}
NGINX
/docker-entrypoint.d/20-envsubst-on-templates.sh
nginx -t
nginx
request() {
    host="$1"; path="$2"; shift 2
    curl -ksS --noproxy '*' --resolve "$host:443:127.0.0.1" "https://$host$path" "$@"
}
test "$(request m.test /)" = mobile
test "$(request api.test /api/v1/auth/me -o /dev/null -w '%{http_code}')" = 401
# A valid-size proof of purchase must reach the backend's authentication check.
dd if=/dev/zero of=/tmp/proof.jpg bs=1M count=3 2>/dev/null
test "$(request api.test /api/v1/trade/aftersale-images -F file=@/tmp/proof.jpg -o /dev/null -w '%{http_code}')" = 401
if [ -z "$LA_ADMIN_DOMAIN" ]; then
    if request admin.test /signup; then echo 'unexpected workbench with no configured domain'; exit 1; fi
    echo 'Existing deployment with no workbench domain passed'
    exit 0
fi
for path in / /signup /workbench/products; do
    test "$(request admin.test "$path")" = workbench-one
done
request admin.test /signup -I | grep -iq 'cache-control: no-cache'
request admin.test /assets/app-123.js -I | grep -iq 'cache-control: .*immutable'
test "$(request admin.test /assets/absent.js -o /dev/null -w '%{http_code}')" = 404
test "$(request admin.test /api/v1/auth/me -o /dev/null -w '%{http_code}')" = 401
test "$(request admin.test '/api/v1/organization-signups?check=1' -X POST)" = 'POST:/api/v1/organization-signups?check=1:admin.test:https'
cd /srv/admin
ln -s releases/two current.new && mv -Tf current.new current
test "$(request admin.test /workbench/products)" = workbench-two
ln -s releases/one current.new && mv -Tf current.new current
test "$(request admin.test /workbench/products)" = workbench-one
test "$(request m.test /)" = mobile
echo 'Workbench routing, same-origin API, cache, deployment and rollback passed'
CONTAINER
done
