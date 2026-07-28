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
