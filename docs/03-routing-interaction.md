# 03｜路由与交互矩阵

## 1. 路由

如果项目已有命名规范，保留以下语义并映射到既有路径：

| 页面 | 路由名 | 建议路径 | 默认登录要求 |
| --- | --- | --- | --- |
| INS-M-002 | InspectionPortal | `/pages/service/inspection/index` | 浏览不强制登录 |
| INS-M-003 | InspectionCatalog | `/pages/service/inspection/catalog` | 浏览不强制登录 |

## 2. M02 路由参数

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| cityCode | string | 当前城市稳定编码 |
| categoryCode | string? | 巡检分类编码 |
| keyword | string? | 搜索关键词 |
| sort | enum? | COMPREHENSIVE、DISTANCE_ASC、PRICE_ASC、PRICE_DESC |
| openFilter | boolean? | 一次性打开筛选层 |
| source | enum? | SEARCH、CATEGORY、HOT、VIEW_ALL、DEEP_LINK |

禁止在 URL 传递：

- 完整服务对象。
- 图片 URL。
- 价格展示文案。
- 精确经纬度。
- 联系方式。
- 中文分类名称作为业务判断字段。

## 3. 路由恢复

- M02 初始化时由路由恢复城市、关键词、分类和排序。
- 如果路由没有城市，使用共享 `currentCity`。
- 如果路由城市与共享城市不同，以用户最近主动选择为准，并同步另一处状态。
- 筛选面板中的复杂条件存入 Pinia 快照；路由只保留可稳定表达的主要条件。
- `openFilter` 读取一次后从历史状态移除，避免每次返回都自动打开。
- M02 返回 M01 时恢复 M01 滚动位置。
- 深链直接进入 M02 且没有历史栈时，返回 M01，而不是退出到空白页面。

## 4. M01 交互矩阵

| 控件 | 前置 | 本期动作 | 失败/未接入 |
| --- | --- | --- | --- |
| 当前城市 | 公共城市能力 | 打开城市选择 | 无能力则仅显示，不伪造 |
| 搜索框 | 有当前城市 | 进入 M02，传 keyword | 空关键词进入全部列表 |
| 消息图标 | 公共消息能力 | `openMessageCenter` | 生产隐藏角标/入口 |
| 我要巡检 Tab | 无 | 保持当前页面 | 不重复入栈 |
| 其他服务 Tab | 宿主路由 | `openSiblingService` | 明确不可用 |
| 发布巡检需求 | 后续模块 | `startInspectionRequest` | 生产功能开关关闭 |
| 普通场景 | 分类可用 | M02 + categoryCode | 无服务仍进入明确空状态 |
| 更多场景 | 无 | M02 + openFilter | 筛选项加载失败可重试 |
| 查看全部 | 无 | M02，清空分类 | 真实跳转 |
| 热门服务卡 | 有 serviceId | `openInspectionServiceDetail` | 生产功能开关关闭 |
| 服务流程 | 无 | 解释性展示 | 不创建假跳转 |
| 底部导航 | 公共壳 | `openRootTab` | 沿用宿主行为 |

## 5. M02 交互矩阵

| 控件/事件 | 状态变化 | 请求 | 注意 |
| --- | --- | --- | --- |
| 返回 | 回 M01 | 无 | 保持入口滚动位置 |
| 搜索提交 | keyword 更新，page=1 | 列表查询 | 清理空格，最大50字符 |
| 清空搜索 | keyword 清空，page=1 | 列表查询 | 保留城市/分类/筛选 |
| 分类点击 | category 更新，page=1 | 列表查询 | 单选 |
| 综合排序 | sort=COMPREHENSIVE | 列表查询 | 默认 |
| 距离最近 | 请求位置权限 | 定位成功后查询 | 拒绝不改变原排序 |
| 价格点击1 | sort=PRICE_ASC | 列表查询 | 显示向上箭头 |
| 价格点击2 | sort=PRICE_DESC | 列表查询 | 显示向下箭头 |
| 城市切换 | city 更新，page=1 | 先筛选项，再列表 | 清理不适用筛选 |
| 打开筛选 | 创建临时草稿 | 无 | 不立即修改列表 |
| 取消筛选 | 丢弃草稿 | 无 | 已应用条件不变 |
| 重置筛选 | 清空草稿 | 无 | 城市和关键词不变 |
| 应用筛选 | filters 更新，page=1 | 列表查询 | 显示已选数量 |
| 下拉刷新 | refreshing=true,page=1 | 列表查询 | 失败保留旧数据 |
| 上拉加载 | page+1 | 下一页 | 防并发、按ID去重 |
| 卡片点击 | 发出 detail intent | 无 | 不写死未来路由 |
| 咨询/预约 | 发出 service intent | 无 | 后续能力未开则生产隐藏 |
| 客服 | 调公共客服 | 无 | 无公共能力则生产隐藏 |

## 6. 查询状态

```ts
export interface InspectionCatalogQuery {
  cityCode: string
  keyword?: string
  categoryCode?: string
  sort: 'COMPREHENSIVE' | 'DISTANCE_ASC' | 'PRICE_ASC' | 'PRICE_DESC'
  serviceMethods: string[]
  availability: string[]
  priceModes: string[]
  includesReport?: boolean
  page: number
  size: number
}
```

经纬度不长期写入 store 或路由。距离查询时由位置适配器提供给请求层，并遵守用户授权。

## 7. 请求竞态

示例：

```text
用户选择“电力” → 请求 A
100ms 后选择“光伏” → 请求 B
请求 B 先返回 → 展示光伏
请求 A 后返回 → 因 requestKey 过期被丢弃
```

验收要求：

- 任何旧请求不能覆盖当前查询。
- 列表 query 与响应携带的 query snapshot 可比对。
- 路由离开页面后未完成请求不得继续更新已销毁组件。

## 8. 功能开关

建议功能开关：

```text
inspection.catalog.enabled
inspection.request.enabled
inspection.detail.enabled
inspection.customerService.enabled
inspection.distanceSort.enabled
```

规则：

- I1 测试环境开启 `catalog`。
- `request/detail` 未完成前，生产必须关闭。
- 功能开关来自远程配置或项目既有配置系统，不得在页面散落布尔常量。

## 9. 埋点

建议事件：

```text
inspection_portal_view
inspection_search_submit
inspection_category_select
inspection_hot_service_click
inspection_catalog_view
inspection_sort_change
inspection_filter_apply
inspection_city_change
inspection_load_more
inspection_host_action_unavailable
```

事件属性只包含稳定编码、来源和结果，不记录搜索原文之外的敏感信息；如搜索词可能包含敏感内容，应按项目隐私规则脱敏或不采集。

