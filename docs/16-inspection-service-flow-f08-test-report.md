# 16｜F08 集成与权限回归报告

日期：2026-07-28

## 1. 结论

F08 的真实公共入口黄金路径、空库迁移、权限/越权、幂等、MinIO 私有文件、ZIP、重启恢复、后端全量和移动端全量回归均通过。F09 Android 真机和最终视觉归档尚未完成，因此本报告不作“可以进入领导验收”的最终结论。

## 2. 自动化结果

| 检查 | 结果 |
| --- | --- |
| Java 全量 `.\mvnw.cmd test` | 通过：identity 5、inspection 50、bootstrap 50，共 105 |
| 移动端 Vitest | 通过：10 个测试文件，63/63 |
| TypeScript `vue-tsc --noEmit` | 通过 |
| H5 生产构建 | 通过；仅有 Sass legacy API 弃用警告 |
| 全新空库迁移 | 通过：identity 2、inspection 4、核心表 6、PostGIS 1 |
| F07 权限与文件烟测 | 通过：跨客户 404、平台写入 403、私有下载成功、ZIP 4 项 |
| 幂等 | 通过：发布和验收重复请求返回同一业务结果；冲突请求 409 |
| 重启恢复 | 通过：后端重启后交付状态和受控下载仍可恢复 |

## 3. 黄金路径

黄金路径最终状态：

```json
{
  "PublicEntry": true,
  "ReservationId": "29b7f81e-dbd9-4429-bcdc-49f012f9add8",
  "PlanId": "05ede232-9b9b-4d26-8dd8-af7c72e46558",
  "ServiceOrderId": "769f69e2-937d-4943-93c8-3ec1d05dc865",
  "DeliveryId": "fc14dd11-4d85-3cdf-a428-8d480fd4fd1e",
  "DeliveryFileCount": 4,
  "FinalStatus": "ACCEPTED",
  "ConsoleErrors": 0
}
```

路径从底部“服务”真实点击进入，不使用内部路由代替入口可达性。业务数据来自真实 API、PostgreSQL 和 MinIO。

## 4. 本轮发现并修复

- 无坐标预约写入时 PostgreSQL 无法推断参数类型：为经纬度参数增加明确的 `double precision` 转换，并新增 SQL 回归测试。
- 空验收备注触发不可空集合异常：改为允许空备注的参数集合，并新增服务测试。
- INS-M-007 固定底栏遮挡报告入口：在交付后把同一底栏主按钮切换为“查看报告与验收”。
- INS-M-008 验收完成按钮误导进入本期未实现的评价：改为禁用态“验收已完成”。
- M01/M02/M03 生产构建未形成真实公共入口数据链：仅补齐正式目录 DTO 数据源和最小详情映射，未改页面结构。

## 5. 未完成和已知限制

- Android 真机验收、同状态最终截图和证据归档属于 F09。
- iOS 需要外部 macOS/签名/真实设备；当前不得声明完成。
- Sass legacy API 与 Mockito 动态 agent 为未来兼容性警告，不是当前 P0/P1 阻断。
