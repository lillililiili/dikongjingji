# 15｜巡检交易闭环运行与验收说明

## 1. 范围和前提

本说明只覆盖现有 `codex/inspection-service-flow` 分支中的 M03 至 INS-M-008 交易闭环，不安装或替换 Java、Docker、Node、Android 工具，也不另建第二套后端。

运行前提：

- Java 21、Docker Compose、Node.js 和 npm 已可用。
- 仓库根目录已有从 `.env.example` 复制并填写的本地 `.env`，不得提交真实密钥。
- 移动端本地开发使用 `apps/mobile/.env.local`，生产构建使用 HTTPS 网关。
- 本地默认端口为后端 `8080`、PostgreSQL `15432`、Redis `16379`、MinIO `19000/19001`；实际值以本地 `.env` 为准。

移动端本地 `.env.local` 最小内容：

```dotenv
VITE_INSPECTION_API_BASE_URL=http://127.0.0.1:8080
VITE_INSPECTION_ALLOW_INSECURE_LOCAL_API=true
VITE_INSPECTION_DEFAULT_CITY_CODE=370500
VITE_INSPECTION_DEFAULT_CITY_NAME=东营市
```

## 2. 启动

在仓库根目录执行：

```powershell
docker compose --env-file .env -f infra/compose.yaml up -d --build
docker compose --env-file .env -f infra/compose.yaml ps
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

启动 H5 联调页面：

```powershell
Set-Location apps/mobile
npm run build:h5
npm exec -- vite preview --host 127.0.0.1 --port 4173 --outDir dist/build/h5
```

访问 `http://127.0.0.1:4173`。开发账号只用于本地验收，生产环境必须接入正式身份源。

生成 App 本地资源（不等于已生成 APK 或已完成真机验收）：

```powershell
Set-Location apps/mobile
.\node_modules\.bin\uni.cmd build -p app
```

构建成功后资源位于 `apps/mobile/dist/build/app`，再由已批准的 HBuilderX/原生打包流程安装到真实 Android 设备。

## 3. 最小回归

后端和移动端：

```powershell
.\mvnw.cmd test
npm --prefix apps/mobile test
npm --prefix apps/mobile run type-check
npm --prefix apps/mobile run build:h5
```

全新空库迁移验证使用独立 Compose 项目、端口和临时卷，结束时自动清理，不触碰当前开发数据：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\scripts\verify-f08-empty-database.ps1
```

权限、越权、幂等、MinIO 私有文件、ZIP 和重启恢复：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\scripts\verify-f07-delivery-smoke.ps1
```

预约可用时段、幂等重放和容量并发锁：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\scripts\verify-f04-reservation-smoke.ps1
```

脚本会先调用 `GET /api/v1/inspection/services/{serviceId}/available-time-slots`，严格验证 `MORNING 09:00–12:00`、`AFTERNOON 14:00–17:00`、`EVENING 18:00–21:00` 及初始容量 9（比较前兼容规范化 API 的 `HH:mm:ss`）；随后保留原预约幂等重放断言，并将 `EVENING` 填充到只剩 1 个名额，再并发提交 2 个请求。正确结果必须恰好为一个 `201` 和一个 `409 INS_TIME_SLOT_FULL`。

每次运行只记录本轮真实创建成功的预约 UUID（包括并发任务在异常路径中已经完成的 `201` 响应），并在最外层 `finally` 中按 UUID、客户和服务三重条件精确标记为 `CANCELLED`，同时校验数量。脚本不扫描、删除或取消其他历史预约；因此可在同一干净验收环境连续运行，第二次仍应得到相同的初始容量与 `201/409` 结果。

## 4. 黄金路径 E2E

先保持后端和 4173 端口页面运行。脚本使用已有且获准的 Playwright 安装，不会自动安装依赖：

```powershell
$env:INSPECTION_PLAYWRIGHT_MODULE='<已安装的 Playwright 模块目录>'
$env:INSPECTION_BROWSER_EXECUTABLE='<Edge 或 Chromium 可执行文件>'
node .\infra\scripts\verify-f08-golden-e2e.cjs
```

脚本从真实底部“服务”入口点击进入，执行：

```text
服务 → 我要巡检 → 巡检目录 → 服务详情 → 预约填写 → 提交结果
→ 发布并确认方案 → 服务安排与履约 → 发布报告 → 报告交付与验收
```

服务商操作通过受控 API 完成；客户操作通过真实页面完成。截图保存到忽略目录 `tmp/e2e/f08/`，不会把令牌写入仓库。

预约时间选择使用稳定 H5 路径：文本“期望时间” → `.inspection-time-sheet__panel` → 等待第一项非“更多日期”的 `.inspection-time-sheet__date` → 执行真实浏览器 `.click()` → 文本为 `09:00–12:00` 的 `.inspection-time-sheet__slot` → `.inspection-time-sheet__confirm`，并等待原“期望时间”行显示 `月\d+日 09:00–12:00`。H5 的 `chooseLocation` 依赖宿主地图能力，黄金脚本只在被加载的 booking chunk 内注入受控测试地点适配，不改生产代码；预约、时段查询和后续交易接口仍为真实调用。

视觉验收证据保存在忽略目录：

```text
artifacts/inspection-time-selection/round-1/
artifacts/inspection-time-selection/round-2/
```

每轮包含 375、390、430 CSS 像素宽度的全页、面板聚焦、基准、并排、50% 叠加、真实容量已满状态、测量 JSON 和叠加像素证明。两轮必须使用相同状态：同一真实日期上选中 MORNING，同时由真实预约容量产生 1 个 FULL 时段；生成结束后只精确取消本轮证据预约。默认预览端口仍为 `4173`；端口被已知验收任务占用时，另启隔离端口并通过 `INSPECTION_APP_URL` 显式传入脚本，不得停止不属于本任务的进程。

## 5. 数据恢复和文件安全

- PostgreSQL 为主数据源；刷新页面或重启后端后按稳定 ID 重新读取。
- MinIO 只使用 quarantine/private 私有桶，不返回永久公开 URL。
- 单文件和 ZIP 下载均先校验角色、客户/服务商归属和文件状态，再签发短时地址。
- 写操作使用 `Idempotency-Key`；状态更新使用 `If-Match`/版本号。
- 跨客户读取返回不可见；平台运营默认只读，不得代客户验收。

## 6. 真机边界

- Android 必须使用已连接的真实设备完成关键路径、安全区、键盘、权限、上传下载和弱网检查。
- 本地联调可在设备连接后执行 `adb reverse tcp:8080 tcp:8080` 和 `adb reverse tcp:19000 tcp:19000`，使 App 访问本机 API 与短时 MinIO 地址；验收后应移除对应 reverse 规则。
- iOS 必须依赖 macOS/云构建、签名和真实 iPhone；缺少外部资源时只能记录阻塞，不能以浏览器或 Android 冒充。
- 本轮时间面板只有 H5 自动截图；没有连接 Android 真机，也没有可用的 iOS 构建/签名环境，因此 Android 与 iOS 均保持“未验证”。浏览器截图不能替代任一真机结论。
