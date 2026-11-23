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

## 交易链路的本机联调（模拟微信支付）

没有真实微信商户号之前，用本机模拟的微信支付把下单、付款、退款、对账跑通。它只是测试替身：按官方文档的格式签名、加密、出账单，和真实微信的差别只有联调能发现（见 `docs/redesign/05-微信支付联调记录.md`）。

1. 启动模拟微信（只听 127.0.0.1:17460）。第一次会现生成一套测试密钥，写到被 Git 忽略的 `.runtime/fake-wechat/`：

   ```sh
   FAKE_WECHAT_BACKEND_URL=http://127.0.0.1:8080 node infra/dev/fake-wechat/server.mjs
   ```

2. 后端启动前加载它写出的配置（`trade.wechat.*` 指向模拟微信）：

   ```sh
   set -a; . .runtime/fake-wechat/backend.env; set +a
   ```

3. 冒烟脚本（真实开发库，跑完清掉 `dev-customer` 的订单；`--keep` 保留）：

   ```sh
   PG_CONTAINER=<postgres 容器名> BASE_URL=http://127.0.0.1:8080 node infra/scripts/trade-smoke/order.cjs
   PG_CONTAINER=<postgres 容器名> BASE_URL=http://127.0.0.1:8080 node infra/scripts/trade-smoke/payment.cjs
   PG_CONTAINER=<postgres 容器名> BASE_URL=http://127.0.0.1:8080 node infra/scripts/trade-smoke/fulfilment.cjs
   PG_CONTAINER=<postgres 容器名> BASE_URL=http://127.0.0.1:8080 node infra/scripts/trade-smoke/reconciliation.cjs
   PG_CONTAINER=<postgres 容器名> BASE_URL=http://127.0.0.1:8080 node infra/scripts/trade-smoke/activity.cjs
   PG_CONTAINER=<postgres 容器名> BASE_URL=http://127.0.0.1:8080 node infra/scripts/trade-smoke/venue.cjs
   PG_CONTAINER=<postgres 容器名> BASE_URL=http://127.0.0.1:8080 node infra/scripts/trade-smoke/service-order.cjs
   ```

   `order.cjs` 不需要模拟微信；其余需要。`payment.cjs`、`fulfilment.cjs`、`activity.cjs`、`venue.cjs` 要等定时任务（最长约 2 分半）。
   `activity.cjs`（收费活动报名）和 `venue.cjs`（到馆预约）只删自己建的报名、预约和测试子女，并把名额还回场次；
   `venue.cjs` 为了验核销会把一个场次临时挪到“马上开始”，跑完挪回去。
   `service-order.cjs`（S4-3 通用服务订单、S4-4 报价明细和服务商资料）用 `dev-customer` 发航拍需求、`dev-provider` 报价和履约，
   只删自己建的需求和服务订单；后端启动时设了 `DEV_OPERATOR_PASSWORD` 并在跑脚本时同样带上，才会多验“运营审核服务商资料”。

4. 商家一侧（发货、核销、退款）用 `node infra/scripts/dev-merchant-order.cjs`（开发账号 `dev-merchant`）。到馆预约的核销码用 `node infra/scripts/dev-checkin-venue.cjs <核销码>`（开发账号 `dev-organizer`，开场前 30 分钟到场次结束之间）。模拟微信的控制入口（替买家付款、给出退款结果、改账单）写在 `server.mjs` 开头的注释里。

5. 直播（M20）不需要另外起服务：后端用 dev 配置启动时，`live.stream.provider=fake` 让后端自带一个本机模拟直播服务
   （签名密钥每次启动随机生成，播放地址是 App 内的示例视频 `/static/videos/preview/live-*.mp4`）。开播、断流、结束、
   建直播间、设讲解清单、主播弹幕、隐藏弹幕、强制结束都用 `node infra/scripts/dev-live-host.cjs`（开发账号 `dev-merchant`
   和 `dev-support`，用法写在脚本开头）。模拟回调走开发控制接口 `POST /api/v1/dev/live/rooms/<直播间 ID>/stream-events`，
   它只在 dev profile 下存在，签名后走和真实回调同一条验签路径。

所有这些只对本机地址运行；开发账号的密码公开写在开发种子里，dev 配置只能用在本机或内网。
