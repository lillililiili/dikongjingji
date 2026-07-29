# 本地基础设施

1. 复制根目录 `.env.example` 为 `.env`，并替换所有 `REPLACE_WITH` 占位符。
2. 为 `INSPECTION_JWT_HMAC_BASE64_SECRET` 配置由至少 32 个随机字节进行 Base64 编码得到的本地开发密钥；示例文件不会提供可用密钥。
3. 从仓库根目录运行：

   ```powershell
   docker compose --env-file .env -f infra/compose.yaml config
   docker compose --env-file .env -f infra/compose.yaml up -d --build
   docker compose --env-file .env -f infra/compose.yaml ps
   Invoke-WebRequest http://127.0.0.1:8080/actuator/health
   ```

所有映射端口仅绑定 `127.0.0.1`，可通过 `.env` 中的 `INSPECTION_*_PORT` 调整。PostgreSQL、Redis 和 MinIO 数据分别保存在命名卷中；它们只加入 `inspection-internal` 私有网络。MinIO 初始化容器可重复运行，只创建 `dev-inspection-quarantine` 和 `dev-inspection-private`（名称中的 `dev` 来自 `INSPECTION_ENVIRONMENT`）并对两者设置匿名访问为 `none`。
## 局域网 Android 验收

保持手机与电脑连接同一 Wi-Fi，在仓库根目录运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\scripts\start-lan-acceptance.ps1
```

脚本会从当前默认路由自动选择 RFC1918 私有 IPv4 地址，并临时把后端和 MinIO 绑定到局域网；PostgreSQL 与 Redis 始终覆盖为 `127.0.0.1`。脚本不会改写 `.env`。自动选择失败时可使用 `-LanAddress 192.168.x.x` 手动兜底。

Android 验收构建还需在被 Git 忽略的 `apps/mobile/.env.production.local` 中同时开启 `VITE_INSPECTION_ALLOW_INSECURE_LAN_API=true` 与 `VITE_INSPECTION_ENABLE_LAN_DISCOVERY=true`。正式 HTTPS 构建必须关闭自动发现。
