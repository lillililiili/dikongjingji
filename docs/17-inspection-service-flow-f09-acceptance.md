# 17｜F09 真机与最终交付记录

日期：2026-07-28

## 1. 当前结论

F09 尚未完成，不能给出“可以进入领导验收”的最终结论。

已完成：

- Java、Docker、Node、Android SDK、ADB 和 HBuilderX 均已存在，未重复安装。
- uni-app App 资源构建通过，输出 `apps/mobile/dist/build/app`。
- 后端健康检查 `http://127.0.0.1:8080/actuator/health` 返回 200。
- H5 验收入口 `http://127.0.0.1:4173` 返回 200。
- 五页参考源、首轮、最终 `390 × 737`、并排图、半透明叠加图和区域差异报告已归档到 `docs/evidence/inspection-service-flow/`。
- M004 联系人卡 DOM 底部为 `661.19px`，固定底栏顶部为 `662px`；M008 最终页面高度为 `737px`，自动截图控制台错误为 0。
- 最终移动端回归通过：Vitest `64/64`、TypeScript 检查、H5 构建和 App 资源构建。
- 视觉修正后重新执行完整黄金路径，真实公共入口可达，四个交付文件完成，最终状态为 `ACCEPTED`，浏览器控制台错误为 0。

当前外部阻塞：

- 当前未连接真实 Android 设备。
- 现有 `LowAltitude_API35` 模拟器可以启动并被 ADB 识别，但当前工作树只有 `apps/mobile/dist/build/app` App 资源，没有可安装 APK；Android 15 又禁止 ADB 直接覆盖 HBuilder 调试基座的应用资源目录，因此未能部署固定基线 `28dc0ce`，不能执行或宣称安卓模拟器黄金路径通过。
- 当前 Windows 主机没有可用于 iOS 真机打包、签名和安装的 macOS/真实 iPhone 资源。

## 2. Android 模拟器检查记录

2026-07-28 使用现有环境完成以下检查，没有安装新环境：

- AVD：`LowAltitude_API35`
- ADB 序列号：`emulator-5554`
- 系统：Android 15
- 分辨率：`1080 × 2400`
- 密度：`420 dpi`
- 启动状态：`sys.boot_completed=1`
- 已安装调试基座：`io.dcloud.HBuilder`
- 基座内 AppID：`__UNI__INSPECTION_I1`
- 工作树 APK：无
- 已有 App 资源：`apps/mobile/dist/build/app`

部署结果：

1. ADB 直接覆盖调试基座资源时返回 `Permission denied`。
2. 使用调试包自身 `run-as io.dcloud.HBuilder` 调整目录写权限时仍返回 `Permission denied`。
3. 按“两次失败停止重复尝试”的约束停止继续处理。
4. 读取基座目录确认其仅包含 M01、M02、M03 页面资源，不是固定基线 `28dc0ce` 的五页面构建。

结论：本轮状态为“安卓模拟器环境可启动，但当前固定基线无可安装 APK且调试基座资源无法受控覆盖”，未执行模拟器黄金路径，不得记录为模拟器验收通过，也不得替代 Android 真机验收。

## 3. Android 真机待执行

设备连接并授权 USB 调试后，必须记录设备型号、Android 版本、逻辑分辨率和安装包/调试基座版本，再执行：

1. 从底部“服务”真实进入 M01、M02、M03。
2. 提交预约并检查日期、地址、联系人键盘与附件选择/上传。
3. 在方案发布后从 INS-M-005 进入 INS-M-006，确认方案且不触发支付。
4. 检查 INS-M-007 安排、进度刷新和返回手势。
5. 检查 INS-M-008 四类文件、单项/全部下载、验收和验收后状态恢复。
6. 杀进程后重开 App，确认稳定 ID 路由和服务端状态恢复。
7. 断网、恢复网络、弱网重试和重复点击，确认无重复业务数据。
8. 检查状态栏、底部安全区、系统返回、键盘遮挡和权限弹窗。
9. 保存同状态真机截图，并与同视口自动截图和 PDF 参考逐页对比。

## 4. Android 联调命令

设备连接后：

```powershell
adb devices -l
adb reverse tcp:8080 tcp:8080
adb reverse tcp:19000 tcp:19000
```

验收结束后：

```powershell
adb reverse --remove tcp:8080
adb reverse --remove tcp:19000
```

如果真实设备不能使用 USB reverse，必须把 API 与 MinIO 短时下载入口改为设备可达的受控局域网地址；不得把数据库、Redis 或 MinIO 控制台直接暴露给设备。

## 5. iOS 如实阻塞

iOS 需要 macOS/云构建、有效签名和真实 iPhone。当前缺少这些外部资源，状态保持“未验收/外部阻塞”，浏览器响应式视口和 Android 设备均不能替代 iPhone 真机证据。
