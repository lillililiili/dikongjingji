# 巡检订单中心与提交结果、方案确认入口设计

> 日期：2026-07-31
> 状态：用户已确认
> 方案：A（真实“我的订单”中心）

## 1. 目标

为客户提供一条可恢复、可追踪的真实订单入口：

```text
确认订单
→ 提交成功
→ 订单已提交／等待商家确认需求
→ 商家发布方案
→ 客户确认服务方案
```

用户离开页面、重启 App 或更换设备后，仍可从“我的 → 我的订单”重新进入当前业务阶段。

本设计不新增支付、退款、评价或取消业务；不使用本地缓存、Fixture 或硬编码订单冒充真实数据。

## 2. 已确认事实

- `/pages/service/inspection/booking-submitted` 已存在，通过真实 `reservationId` 查询预约，展示“订单已提交”状态。
- `/pages/service/inspection/plan-confirmation` 已存在，通过真实 `planId` 查询方案，并读取其关联预约。
- 预约状态已有 `SUBMITTED`、`PLAN_PUBLISHED`、`CANCELLED`。
- 方案发布后，预约响应包含 `currentPlanId`；提交结果页可以据此显示“查看服务方案”。
- “我的”页已有近期订单和状态入口的视觉结构，但当前数据为空，点击动作均为“暂未接入”。
- 后端目前只支持创建预约和按 ID 查询单条预约，没有“查询我的预约/订单列表”接口。

## 3. 页面与路由

### 3.1 新增：我的订单

- 页面名称：我的订单
- 建议路由：`/pages/mine/inspection-orders`
- 主要角色：已登录客户
- 核心对象：以 `reservationId` 为稳定生命周期主键的巡检交易摘要
- 数据来源：真实后端分页接口

页面包含：

- 状态筛选：全部、待方案、待确认、进行中、待验收、已完成。
- 订单列表：服务图片、服务名称、预约编号、地点摘要、时间、状态和当前报价。
- 加载、空数据、网络错误、登录失效、分页加载和没有更多状态。
- 订单卡片整卡可点击，按真实状态进入对应页面。

`CANCELLED` 订单仅在“全部”中展示“已取消”状态，不增加独立状态页签。

### 3.2 复用：订单已提交

- 路由：`/pages/service/inspection/booking-submitted?reservationId={id}`
- 状态：预约为 `SUBMITTED`，尚无 `currentPlanId`
- 作用：展示提交结果和等待商家确认需求的进度
- 主按钮：
  - 无 `currentPlanId`：`返回订单`
  - 有 `currentPlanId`：`查看服务方案`

“返回订单”进入真实“我的订单”，不再进入首页。

### 3.3 复用：服务方案确认

- 路由：`/pages/service/inspection/plan-confirmation?planId={id}`
- 进入条件：预约状态为 `PLAN_PUBLISHED`，且存在真实 `currentPlanId`
- 作用：核对服务日期、巡检范围、交付内容和最终报价
- 确认成功后：创建或读取真实服务单，并进入服务安排页面

## 4. 入口设计

### 4.1 提交后的即时入口

```text
POST /api/v1/inspection/reservations 成功
→ 读取响应中的 reservationId
→ 自动进入 booking-submitted
```

请求失败时停留在确认订单页，不提前跳转，也不显示假成功页面。

### 4.2 日常重新进入

```text
底部“我的”
→ 近期巡检订单 / 全部订单
→ 我的订单
→ 点击订单卡片
→ 根据真实状态进入目标页面
```

### 4.3 商家发布方案后的入口

预约从 `SUBMITTED` 进入 `PLAN_PUBLISHED` 后：

- 提交结果页重新加载预约，按钮变为“查看服务方案”。
- 我的订单中，该订单从“待方案”进入“待确认”。
- 点击订单卡片或“查看服务方案”，进入同一个 `planId` 对应的方案确认页。

消息通知可作为后续补充入口，但不是本阶段完成订单闭环的必要条件。

## 5. 状态与落点映射

| 真实状态 | 订单中心展示 | 卡片点击目标 | 必需稳定 ID |
| --- | --- | --- | --- |
| 预约 `SUBMITTED` | 待方案 | 订单已提交 | `reservationId` |
| 预约 `PLAN_PUBLISHED`、方案 `PUBLISHED` | 待确认 | 服务方案确认 | `planId` |
| 方案 `CONFIRMED`、服务单 `ARRANGING` | 进行中 | 服务安排与履约 | `serviceOrderId` |
| 服务单 `IN_PROGRESS` | 进行中 | 服务安排与履约 | `serviceOrderId` |
| 服务单 `DELIVERED` | 待验收 | 报告交付与验收 | `serviceOrderId` |
| 服务单 `ACCEPTED` | 已完成 | 已验收报告 | `serviceOrderId` |
| 预约 `CANCELLED` | 已取消 | 订单已提交的只读状态 | `reservationId` |

前端不得根据中文文案猜测落点；后端必须返回稳定状态编码和对应的可选 ID。

## 6. 数据与接口

### 6.1 新增分页查询

建议在现有预约集合资源上增加：

```http
GET /api/v1/inspection/reservations?page=1&size=20&stage=ALL
Authorization: Bearer <customer token>
```

仅返回当前登录客户自己的数据。`stage` 使用白名单：

- `ALL`
- `AWAITING_PLAN`
- `AWAITING_CONFIRMATION`
- `IN_PROGRESS`
- `AWAITING_ACCEPTANCE`
- `COMPLETED`

建议响应：

```json
{
  "items": [
    {
      "reservationId": "uuid",
      "reservationNo": "IR...",
      "serviceId": "uuid",
      "serviceTitle": "电力输电线路无人机巡检",
      "serviceCoverUrl": "https://...",
      "cityCode": "370500",
      "locationSummary": "山东省东营市...",
      "preferredServiceDate": "2026-08-02",
      "submittedAt": "2026-07-31T03:00:00Z",
      "stage": "AWAITING_CONFIRMATION",
      "reservationStatus": "PLAN_PUBLISHED",
      "currentPlanId": "uuid",
      "serviceOrderId": null,
      "serviceOrderStatus": null,
      "quoteAmountMinor": 34900,
      "currency": "CNY"
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1,
  "hasMore": false
}
```

规则：

- 尚未发布方案时没有最终报价，页面显示“待报价”，不得使用截图价格。
- 金额继续使用最小货币单位整数。
- 列表按最近更新时间倒序，排序字段由服务端固定。
- 列表投影由预约、当前方案和服务单的真实状态聚合生成。
- 精确联系人、手机号和坐标不进入列表响应。

### 6.2 权限

- 仅 `CUSTOMER` 且拥有本人预约读取权限的主体可访问。
- 查询条件中不接受客户端传入 `customerId`。
- 服务端始终以访问令牌中的 `subjectId` 限定数据范围。
- 登录失效时保存订单中心或具体订单的安全恢复路由，重新登录后恢复。

## 7. 页面行为

### 我的页

- `onShow` 刷新近期订单和状态数量。
- “全部订单”、状态入口和订单卡片不再调用 `showUnavailable`。
- 近期订单最多展示后端返回的前若干条，不复制第二套订单数据。

### 我的订单页

- 初次进入请求第一页。
- 切换状态后重置页码并取消或忽略旧请求。
- 上拉分页防止重复加载。
- 下拉刷新重新读取真实状态。
- 返回时进入“我的”Tab；无历史栈时也不得回到确认订单表单。

### 订单已提交页

- 每次重新显示或用户主动刷新时重新查询当前预约。
- `currentPlanId` 出现后，将主按钮切换为“查看服务方案”。
- “返回订单”清理当前交易页面栈并进入“我的订单”。

### 服务方案确认页

- 只接受真实 `planId`。
- 方案不存在、已撤回或已过期时显示明确状态，并保留返回订单入口。
- 确认方案保持现有幂等键和版本冲突处理。

## 8. 异常与恢复

- 未登录或令牌失效：进入登录恢复流程，不显示可无限重试的空白页。
- 列表网络错误：保留返回“我的”和重新加载。
- 订单已在其他设备推进：重新查询后按最新状态更新卡片及落点。
- 卡片状态与目标 ID 不一致：不跳转，刷新该订单并提示状态已更新。
- 方案被撤回或过期：回到订单已提交页，显示等待新方案。
- 冷启动：仅依赖后端真实数据恢复，不依赖内存或本地最近订单。

## 9. 验收范围

### 移动端

- 提交订单成功后自动进入“订单已提交”。
- 点击“返回订单”进入真实订单中心。
- 从“我的 → 全部订单”进入同一订单中心。
- `SUBMITTED` 订单进入提交结果页。
- `PLAN_PUBLISHED` 订单进入正确 `planId` 的方案确认页。
- 刷新、冷启动和重新登录后仍可恢复。
- Android 顶部返回、系统返回手势和无历史栈返回均进入安全页面。

### 服务端

- 只能查询当前客户自己的订单。
- 分页、状态筛选和倒序稳定。
- 状态与 `reservationId`、`planId`、`serviceOrderId` 映射正确。
- 无方案时不返回虚假报价。
- 其他客户数据返回不可见结果。

### 不在本阶段

- 支付与退款。
- 评价与售后。
- 订单取消。
- 消息推送中心。
- 商家工作台页面；现有受控商家 API 仍负责发布方案。

## 10. 实现边界

本方案会新增真实订单列表能力，属于对原“订单不开发”范围的明确增量。实现时应形成独立变更集，至少覆盖：

- OpenAPI 契约与示例。
- 后端本人订单分页投影和权限测试。
- 移动端订单查询 DTO、映射、状态落点策略。
- 我的订单页面及“我的”页接线。
- 提交结果页“返回订单”和方案入口接线。
- H5 自动化与 Android 真机业务链路验证。

不得借此修改支付、退款、其他一级页面或无关公共组件。
