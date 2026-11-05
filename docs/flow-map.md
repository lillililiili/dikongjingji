# 巡检服务交易闭环流程图

> 适用范围：M03 服务详情至 INS-M-008 报告交付与验收
> 事实来源：`docs/12-inspection-service-flow-baseline.md`、`contracts/openapi-inspection-service-flow.yaml`、当前数据库迁移与后端状态机
> 目的：业务流程 Bug 修改前必须先核对本文件；页面行为必须能够对应真实状态变化、持久化数据、下一页面和后续动作。

## 1. 黄金路径

| 阶段 | 页面与路由 | 用户/角色动作 | API | 状态变化 | 持久化数据变化 | 下一页面 | 后续动作 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 服务详情 | M03 服务详情 | 客户点击“预约服务” | 无写请求 | 无 | 无 | INS-M-004 预约填写 | 填写联系人、时间、地点和附件 |
| 预约填写 | `/pages/service/inspection/booking` | 客户提交预约 | `POST /api/v1/inspection/reservations` | 预约：无 → `SUBMITTED` | 新增预约、预约附件关联、幂等记录 | INS-M-005 预约提交结果 | 等待服务商发布方案 |
| 提交结果 | `/pages/service/inspection/booking-submitted` | 客户查看已发布方案 | `GET /api/v1/inspection/reservations/{id}` | 无 | 无 | INS-M-006 方案确认 | 核对并确认方案 |
| 方案发布 | 受控服务商 API | 被分配的服务商操作员发布方案 | `POST /api/v1/provider/inspection/reservations/{id}/plans` | 方案：`DRAFT` → `PUBLISHED`；预约：`SUBMITTED` → `PLAN_PUBLISHED` | 新增/更新方案、记录状态迁移 | 客户仍在提交结果页或重新进入该页 | 客户进入方案确认 |
| 方案确认 | `/pages/service/inspection/plan-confirmation` | 客户确认自己的有效方案 | `POST /api/v1/inspection/plans/{id}/confirm` | 方案：`PUBLISHED` → `CONFIRMED`；服务单：无 → `ARRANGING`；巡检次：无 → `PENDING_SCHEDULE` | 更新方案并新增服务单、记录状态迁移和幂等结果 | INS-M-007 服务安排与履约 | 服务商安排资源 |
| 服务安排 | `/pages/service/inspection/fulfillment` | 被分配的服务商操作员提交人员和设备 | `PUT /api/v1/provider/inspection/service-orders/{id}/arrangement` | 服务单保持 `ARRANGING` | 写入人员、设备和进度事件，手机号只存密文和脱敏值 | INS-M-007 | 服务商给每次巡检排期 |
| 巡检次排期 | `/pages/service/inspection/fulfillment` | 被分配的服务商操作员排定或改动某次时间 | `PUT /api/v1/provider/inspection/service-orders/{id}/visits/{visitId}/schedule` | 巡检次：`PENDING_SCHEDULE` → `SCHEDULED` | 写入该次开始时间和状态迁移 | INS-M-007 | 到时间后开始这一次 |
| 现场履约 | `/pages/service/inspection/fulfillment` | 被分配的服务商操作员开始/更新/完成某次现场服务 | `POST /api/v1/provider/inspection/service-orders/{id}/visits/{visitId}/progress-events` | 开始：巡检次 `SCHEDULED` → `IN_PROGRESS`，首次开始时服务单 `ARRANGING` → `IN_PROGRESS`；更新和完成不改状态 | 新增该次进度事件 | INS-M-007 | 现场完成后可发布这一次的交付，也可开始下一次 |
| 报告交付 | 受控服务商 API | 被分配的服务商操作员发布某次交付 | `POST /api/v1/provider/inspection/service-orders/{id}/visits/{visitId}/delivery` | 巡检次：`IN_PROGRESS` → `DELIVERED`；交付：无 → `PUBLISHED`；全部已交付时服务单 `IN_PROGRESS` → `DELIVERED` | 新增交付、交付文件和状态迁移 | INS-M-008 | 客户查看并验收这一次 |
| 验收 | `/pages/service/inspection/delivery` | 客户确认某次交付结果 | `POST /api/v1/inspection/service-orders/{id}/visits/{visitId}/acceptance` | 巡检次：`DELIVERED` → `ACCEPTED`；交付：`PUBLISHED` → `ACCEPTED`；全部已验收时服务单 `DELIVERED` → `ACCEPTED` | 新增验收记录和状态迁移 | INS-M-008 已验收只读态 | 还有未完成的次时继续履约，否则服务完成 |

### 1.1 INS-M-004 巡检地点选择

```text
客户点击“巡检地点”
→ uni.chooseLocation 请求定位权限并打开原生地图
→ 客户搜索或选择地点
→ 移动端校验并保存完整地址、经度、纬度到预约草稿
→ 客户提交预约
→ POST /api/v1/inspection/reservations
→ PostgreSQL 保存地址，PostGIS 保存坐标点
→ 持久化成功后进入 INS-M-005
```

- 选点成功只更新客户端预约草稿，不提前改变预约状态或写入数据库。
- 用户取消、权限拒绝、地图服务失败或返回无效坐标时，不覆盖原草稿，不发起预约写请求。
- 提交时地址与经纬度必须作为同一 `location` 发送；页面不得使用假地址、仅坐标或硬编码成功结果。
- 原生地图调试基座可用于开发验收，但正式安装包必须配置与包名、签名匹配且具备地点搜索能力的合规地图服务 Key。

## 2. 状态机

### 2.1 预约

```text
SUBMITTED ──服务商发布方案──> PLAN_PUBLISHED
SUBMITTED ──取消──────────> CANCELLED
```

### 2.2 方案

```text
DRAFT ──发布──> PUBLISHED ──客户确认──> CONFIRMED
                         ├──到期────> EXPIRED
                         └──撤回────> WITHDRAWN
```

### 2.3 巡检次、服务单与交付

巡检次是履约的最小单位：

```text
PENDING_SCHEDULE ──排期──> SCHEDULED ──开始──> IN_PROGRESS ──发布交付──> DELIVERED ──客户验收──> ACCEPTED
```

服务单状态由全部巡检次派生，不单独推进：

```text
ARRANGING ──任一次开始──> IN_PROGRESS ──全部已交付──> DELIVERED ──全部已验收──> ACCEPTED
```

禁止跳过中间状态。现场完成事件不改变状态；只有交付发布成功后这一次才进入 `DELIVERED`。下一次只要求上一次现场完成，不等交付和验收。存量服务单回填为 1 次巡检。

订单列表的待验收阶段按巡检次判断：任一次 `DELIVERED` 未验收就显示待验收，并带上最早一次待验收的 `visitId` 和序号。

## 3. 页面状态与主操作

| 当前业务状态 | 应展示页面 | 主操作 | 操作结果 |
| --- | --- | --- | --- |
| 预约 `SUBMITTED`，尚无已发布方案 | INS-M-005 | 返回首页 | 不修改业务状态 |
| 预约 `PLAN_PUBLISHED` | INS-M-005 | 查看方案 | 进入 INS-M-006 |
| 方案 `PUBLISHED`，未过有效期 | INS-M-006 | 确认方案 | 创建 `ARRANGING` 服务单并进入 INS-M-007 |
| 方案已过期或已撤回 | INS-M-006 | 申请更新方案 | 提示经平台客服登记；不修改业务状态 |
| 方案确认之前 | INS-M-006 | 取消订单 | 二次确认后取消预约，进入 INS-M-005 已取消态 |
| 方案 `CONFIRMED` | INS-M-006 | 查看服务安排 | 进入 INS-M-007 |
| 没有待验收的巡检次 | INS-M-007 | 无底部操作 | 服务商继续履约；已交付的巡检次可从时间线进 INS-M-008 |
| 有巡检次 `DELIVERED` | INS-M-007 | 去验收 | 进入最早一次待验收的 INS-M-008 |
| 这一次交付 `PUBLISHED` | INS-M-008 | 确认验收 | 确认面板确认后写入这一次的验收记录 |
| 这一次交付 `ACCEPTED` | INS-M-008 | 无底部操作 | 不重复写入，可查看/下载文件或返回 |

订单中心和“我的”按阶段进入：待验收进那一次的 INS-M-008，进行中和已完成进 INS-M-007。

## 4. 返回行为

返回属于导航行为，不得修改预约、方案、服务单、交付或验收状态。

| 当前页面 | 顶部返回 / Android 系统返回 | 无历史页面时的降级目标 |
| --- | --- | --- |
| INS-M-004 预约填写 | 返回 M03 服务详情，保留稳定服务 ID | M03 服务详情或巡检目录 |
| INS-M-005 预约提交结果 | 返回首页 | 首页 |
| INS-M-006 方案确认 | 从订单中心进入时返回订单中心 | 订单中心 |
| INS-M-007 服务履约 | 从订单中心进入时返回订单中心 | 订单中心 |
| INS-M-008 交付与验收 | 返回进入它的 INS-M-007 或订单中心；确认面板打开时先关闭面板 | 订单中心；若身份已失效则进入认证恢复流程 |

页面处于加载、空数据、网络错误、未登录或无权限状态时，返回操作仍必须可用。错误视图不得覆盖、吞掉或禁用顶部返回与 Android 系统返回。

## 5. 认证、权限与恢复

### 5.1 页面边界

- M01、M02、M03 为公开读取页面。
- INS-M-004 至 INS-M-008 为受保护页面。
- 客户只能读取和操作自己的预约、方案、服务单、交付、附件和验收。
- 服务商操作员只能处理分配给自己的项目。
- 平台运营在本闭环内默认只读审计。

### 5.2 未登录或令牌失效

```text
进入受保护页面
→ 校验登录态
→ 无令牌或 API 返回 COMMON_UNAUTHORIZED
→ 保存待恢复目标（路由 + reservationId/planId/serviceOrderId 等稳定 ID）
→ 进入公共登录能力
→ 登录成功
→ 恢复原目标并重新读取真实后端数据
```

- `401 / COMMON_UNAUTHORIZED` 必须进入认证恢复流程，不得显示成可无限点击的通用“重新加载”。
- 用户取消登录或从登录页返回时，进入安全的公开页面；不得停留在无法操作的受保护空白页。
- 登录恢复只保留稳定业务 ID，禁止缓存完整业务对象或伪造成功状态。
- `403 / COMMON_FORBIDDEN` 显示无权限说明并提供安全退出路径，禁止通过重试绕过权限。
- “重新加载”只用于可恢复的网络错误、超时或服务端错误；重试必须重新调用真实 API。

## 6. 文件交付

```text
客户上传预约附件
→ 服务端校验类型、大小、预约归属
→ MinIO 私有对象
→ 数据库保存对象键与业务归属

服务商上传报告/交付文件
→ 校验服务商分配关系和这一次巡检的状态
→ MinIO 私有对象
→ 发布这一次的交付并关联文件

客户查看或下载
→ 校验客户与服务单/交付归属
→ 生成短时效受控下载地址
→ 到期失效
```

跨客户、跨服务商、无归属或状态不允许时必须拒绝；客户端不得直接拼接 MinIO 公共地址。

## 7. 幂等、并发与恢复不变量

- 创建预约、确认方案、发布交付、确认验收等写操作必须携带幂等键。
- 同一业务动作重复提交不得生成重复预约、服务单、交付或验收记录。
- 状态更新必须校验当前状态和版本；并发冲突返回明确错误并重新读取最新数据。
- 页面刷新、App 冷启动、后端重启后均以 PostgreSQL 状态为准，不依赖内存或 Fixture。
- 页面跳转必须发生在对应写请求持久化成功之后；写请求失败时不得先跳页或显示假成功。

## 8. 本轮修复状态（2026-07-29）

1. 移动端已增加真实用户名/密码登录页，调用 `POST /api/v1/auth/login`，成功后按服务端有效期持久化访问令牌；不内置账号或密码。
2. 无令牌、令牌过期或 API 返回 `COMMON_UNAUTHORIZED` 时，统一保存当前受保护路由的稳定业务 ID 并进入登录页；登录成功后恢复原页面。未批准的路径和查询参数不会进入恢复目标。
3. INS-M-008 的顶部返回和 Android 系统返回已改为区分 `backbutton` 与 `navigateBack`，避免程序返回再次被自身拦截。Android 真机已覆盖无历史栈顶部返回、有历史栈系统返回、真实 401 重新登录和冷启动会话恢复；均返回同一服务单并保持 `ACCEPTED`，后续动作是“查看报告”。
4. INS-M-007 在服务单已 `ACCEPTED` 时的主操作已改为只读“查看报告”；`DELIVERED` 仍显示“查看报告与验收”。
5. 巡检路由清单已补充 INS-M-007 和最小登录路由。登录取消或恢复失败时返回公开首页，不停留在受保护错误页。
6. 观察项：产品基线允许游客浏览公开目录与详情，但当前后端安全配置仍要求除登录外的请求全部鉴权。该既有差异不属于本次返回/登录恢复补丁，未修改 M01/M02 或后端公共权限。

## 9. Bug 修复验收清单

每个业务流程 Bug 必须同时验证：

1. 用户动作是否真实触发。
2. 应有的状态变化是否发生，非法状态是否被拒绝。
3. PostgreSQL/MinIO 数据是否真实变化且归属正确。
4. 下一页面是否携带正确稳定 ID 并能读取真实数据。
5. 后续动作是否与新状态一致。
6. 顶部返回、Android 系统返回、冷启动、刷新和登录失效是否具有安全路径。
7. 修复没有使用 Fixture、硬编码成功结果、空实现或屏蔽错误。
