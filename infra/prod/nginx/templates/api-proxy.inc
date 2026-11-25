proxy_pass http://backend:8080;
proxy_http_version 1.1;
proxy_set_header Host $host;
# 转发 nginx 看到的客户端地址，供后端限流及审计。
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Proto https;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection $connection_upgrade;
proxy_connect_timeout 5s;
proxy_read_timeout 60s;
