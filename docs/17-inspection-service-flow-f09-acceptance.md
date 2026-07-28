# 17｜F09 Android 验收与最终交付记录

更新日期：2026-07-29

## 1. 当前结论

固定软件基线 `28dc0ce` 已先后在 Android 模拟器和真实 Android 手机完成以下真实交易路径：

```text
公共“服务”入口
→ M01 巡检入口
→ M02 巡检目录
→ M03 服务详情
→ INS-M-004 预约填写
→ INS-M-005 预约提交结果
→ INS-M-006 方案确认
→ INS-M-007 服务安排与履约
→ INS-M-008 报告交付与验收
```

Android 真机已验证：

- 从公共“服务”入口连续进入 M01、M02、M03 和五页面闭环。
- 真机填写预约，后端只生成一条预约记录；附件失败后的重复提交没有生成重复业务记录。
- 客户在真机确认方案，服务商通过正式受控 API 安排团队、设备和履约进度。
- 四份交付文件真实写入私有 MinIO，真机通过短时效受控下载保存 ZIP，并显示“文件已保存”。
- 客户在真机确认验收，服务单和交付状态均为 `ACCEPTED`。
- 强制停止 App 后，使用同一服务单重开 INS-M-008，验收状态和四份文件完整恢复。
- App 当前进程日志未发现 `FATAL EXCEPTION`、`TypeError`、`ReferenceError`、`SyntaxError`、`UniError`、`JSERROR` 或 `CommonException`。
- 后端和 MinIO 健康检查均为 200。

严格 Android 真机验收仍有一个已知 P1 阻塞：

- 真机系统相册选择器和 Android 16 文件访问权限弹窗可以正常打开并授权。
- 选中测试图片后，预约主记录可以创建，但标准 HBuilderX 5.15 Android 基座不提供 `uni.getFileSystemManager()`，前端无法计算上传前必需的 SHA-256，页面明确显示“当前平台未配置 SHA-256 文件哈希能力”。
- 因此本轮没有把预约附件写入 MinIO；该问题属于 App 真机运行时适配缺陷，不是设备、ADB、后端或 MinIO 外部阻塞。
- 按本轮“只做 Android 设备验收、不修改业务代码”的范围，本记录仅保存证据，没有修改实现，也不能给出“无已知 P0/P1”的最终结论。

本轮没有重新运行已通过的后端 `105` 项、移动端 `64` 项、F09 视觉校准或 H5 黄金路径。

## 2. Android 模拟器基线

模拟器验收提交：`6db9311 test: record Android emulator acceptance`

- AVD：`LowAltitude_API30_x86`
- ADB 序列号：`emulator-5554`
- 系统：Android 11 / API 30
- ABI：`x86`
- 产品：`sdk_gphone_x86`
- `wm size`：`1080 × 2280`
- 密度：`440 dpi`
- 调试基座：HBuilderX 5.15 标准基座 `io.dcloud.HBuilder`
- 后端：`http://127.0.0.1:8080` 健康检查 200
- MinIO：`http://127.0.0.1:19000` 健康检查 200

模拟器固定测试记录：

- 预约编号：`IR1785253141824d8e57cde`
- 服务单号：`FW202607281553079B27`
- 服务单最终状态：`ACCEPTED`
- 交付最终状态：`ACCEPTED`
- 交付文件关联数：`4`

证据目录：`docs/evidence/inspection-service-flow/android-emulator/`

- `01-m01-portal.png`
- `02-m02-catalog.png`
- `03-m03-service-detail.png`
- `04-m004-booking-filled.png`
- `05-m005-booking-submitted.png`
- `06-m006-plan-confirmation.png`
- `07-m007-arranging.png`
- `08-m007-delivered.png`
- `09-m008-file-saved.png`
- `10-m008-accepted.png`
- `11-m008-restart-restored.png`

## 3. Android 真机环境

本轮真实设备：

- Windows 设备名称：`Xiaomi 17 Pro Max`
- ADB 型号：`2509FPN0BC`
- ADB 产品/设备：`popsicle`
- ADB 序列号：`d6ede713`
- 厂商：Xiaomi
- 系统：Android 16 / API 36
- ABI：`arm64-v8a`
- 主屏显示 ID：`4630946457447247251`
- `wm size`：`1200 × 2608`
- 密度：`480 dpi`
- 调试基座：HBuilderX 5.15 标准基座 `io.dcloud.HBuilder`
- ADB reverse：`8080`、`19000` 以及 HBuilder 调试端口

首次连接时 Windows 只识别通用 WinUSB 设备，ADB 无法枚举手机。本轮只在现有 Android SDK 中补装 Google USB Driver，并在用户确认 UAC 后把设备绑定为 `Android ADB Interface`：

- 驱动提供商：Google, Inc.
- 驱动 INF：`oem54.inf`（原始 `android_winusb.inf`）
- 结果：手机从 `unauthorized` 经用户确认 RSA 后进入 `device`

没有安装新的 Java、Docker、Maven、Android SDK 平台或 HBuilderX，也没有修改业务代码。

所有 ADB、驱动、HBuilder 和设备检测命令均限制为不超过 30 秒。

## 4. Android 真机黄金路径结果

固定真机测试记录：

- 预约 ID：`2c587dd0-fa80-4bf5-8495-d538563d5124`
- 预约编号：`IR17852791515732c587dd0`
- 方案 ID：`37a9bfd2-1615-434a-a031-523ee20f9af8`
- 服务单 ID：`8532c475-9818-4e43-8b66-6bf52da4b7da`
- 服务单号：`FW202607282301568532`
- 服务：输电线路无人机巡检
- 地址：`Dongying Real Device`
- 线路长度：`12.5 km`
- 巡检项目：线路本体巡检
- 方案报价：`¥8388`
- 交付文件：现场影像 ZIP、缺陷清单 XLSX、巡检点位 KML、巡检报告 PDF，共 4 份
- 服务单最终状态：`ACCEPTED`，版本 `6`
- 交付最终状态：`ACCEPTED`，版本 `1`
- 交付文件关联数：`4`
- 同预约编号记录数：`1`
- 真机保存的受控下载 ZIP：`37` 字节测试交付物

过程验证：

1. 从手机公共“服务”Tab真实点击进入 M01、M02、M03。
2. 在 INS-M-004 填写地址、长度、日期、巡检项目、联系人和手机号。
3. 打开真机系统相册、完成 Android 16 文件权限授权并选择专用测试图片；随后命中 SHA-256 基座能力阻塞。
4. 预约主记录已真实写入 PostgreSQL；以同一预约继续后续闭环，没有伪造提交结果。
5. INS-M-005 从后端恢复预约编号和预约内容。
6. 服务商通过正式受控 API 发布方案，INS-M-005 刷新后出现“查看服务方案”。
7. 客户在 INS-M-006 勾选协议并确认方案，后端只创建一个服务单。
8. 服务商通过正式受控 API 安排团队和设备，连续写入开始、进度、完成事件。
9. INS-M-007 在真机分别恢复“现场服务进行中”和“报告已交付”。
10. 四份交付物通过上传意图写入私有 MinIO，并发布交付。
11. INS-M-008 展示四份文件；真机单项下载通过受控下载意图保存成功。
12. 客户确认验收后，页面显示“服务已验收/验收已完成”。
13. 强制停止 App 后，重新进入相同服务单的 INS-M-008，`ACCEPTED` 状态和四份文件仍完整恢复。
14. 数据库核对为订单 `ACCEPTED/v6`、交付 `ACCEPTED/v1`、文件数 `4`、预约编号记录数 `1`。

真机测试专用图片在选择器验证结束后已从 `/sdcard/Download/inspection-device-test.png` 删除；受控下载的交付 ZIP 保留在 App 私有保存目录，供现场复核。

## 5. Android 真机证据

证据目录：`docs/evidence/inspection-service-flow/android-device/`

- `01-public-service-tab.png`
- `02-m01-portal.png`
- `03-m02-catalog.png`
- `04-m03-service-detail.png`
- `05-m004-booking-filled.png`
- `06-m004-attachment-hash-blocked.png`
- `07-m005-booking-submitted.png`
- `08-m006-plan-confirmation.png`
- `09-m007-in-progress.png`
- `10-m007-delivered.png`
- `11-m008-delivery.png`
- `12-m008-file-saved.png`
- `13-m008-accepted.png`
- `14-m008-restart-restored.png`

全部证据均为真实手机主屏截图，尺寸 `1200 × 2608`。没有使用模拟器截图冒充真机，也没有归档手机相册中的个人图片。

- `06` 记录预约附件的真实 SHA-256 基座阻塞。
- `12` 记录受控下载后的“文件已保存”。
- `13` 记录真机验收成功。
- `14` 记录强制停止 App 后的持久化状态恢复。

## 6. 未完成与下一步

### P1：预约附件真机上传

根因定位到：

```text
apps/mobile/src/features/inspection/application/service-flow/booking-crypto.ts
→ uniBookingFileHashPort.sha256()
→ Android App 标准基座没有 uni.getFileSystemManager()
```

建议另开最小修复任务：

1. 为 uni-app App 端增加可验证的本地文件读取/流式 SHA-256 适配器，不改变后端校验规则。
2. 复用当前真机、当前预约表单和同一张专用测试图片复验。
3. 验证文件对象进入 `READY`、预约详情能恢复附件、跨用户/跨服务商下载仍被拒绝。
4. 修复前后只运行附件相关单元/集成测试和一次真机上传，不重跑视觉校准或无关回归。

该修复会修改业务代码，未包含在本轮设备验收授权中。

### iOS

iOS 需要 macOS/云构建、有效签名和真实 iPhone。当前缺少这些外部资源，本轮未执行 iOS，也未用浏览器、Android 模拟器或 Android 真机冒充 iPhone 真机证据。
