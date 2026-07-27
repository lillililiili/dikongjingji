# 04｜API 契约说明

机器可读契约见：

- `contracts/openapi-inspection-catalog.yaml`
- `contracts/inspection-catalog.examples.json`

如果项目已有统一响应信封、分页格式或错误码，Codex 必须把本逻辑契约映射到现有格式，不得引入平行协议。

## 1. API 列表

```text
GET /api/v1/inspection/portal
GET /api/v1/inspection/filters
GET /api/v1/inspection/services
```

## 2. 入口配置

```http
GET /api/v1/inspection/portal?cityCode=370500
```

返回：

- 当前城市信息。
- Hero/Banner。
- 场景分类。
- 服务保障。
- 热门服务摘要。
- 服务流程。
- 配置版本。

动作不返回任意内部路由字符串，只返回白名单动作：

```text
OPEN_CATALOG
OPEN_FILTER
HOST_ACTION
NONE
```

`OPEN_CATALOG` 可以携带：

- `categoryCode`
- `keyword`
- `serviceId` 预选

## 3. 筛选项

```http
GET /api/v1/inspection/filters?cityCode=370500
```

返回：

- 当前城市已开通分类。
- 服务方式。
- 可用状态。
- 价格模式。
- 报告能力。
- 当前支持的排序方式。

无真实服务覆盖数据或距离能力未开放时，不返回 `DISTANCE_ASC`，前端不得仍显示可用距离排序。

## 4. 服务列表

```http
GET /api/v1/inspection/services
```

### 4.1 参数

| 参数 | 必填 | 规则 |
| --- | --- | --- |
| cityCode | 是 | 有效城市编码 |
| keyword | 否 | trim 后最多50字符 |
| categoryCode | 否 | 服务端白名单 |
| serviceMethods | 否 | 多值、服务端白名单 |
| availability | 否 | 多值、服务端白名单 |
| priceModes | 否 | 多值、服务端白名单 |
| includesReport | 否 | boolean |
| sort | 否 | 默认 COMPREHENSIVE |
| latitude | 距离排序时 | -90..90 |
| longitude | 距离排序时 | -180..180 |
| page | 否 | 从1开始 |
| size | 否 | 1..50，默认20 |

### 4.2 排序枚举

```text
COMPREHENSIVE
DISTANCE_ASC
PRICE_ASC
PRICE_DESC
```

服务端不得接收数据库列名作为排序字段。

### 4.3 卡片响应

```json
{
  "serviceId": "uuid",
  "serviceNo": "INS-SVC-000001",
  "category": {
    "code": "POWER",
    "name": "电力"
  },
  "name": "电力输电线路无人机巡检",
  "summary": "高精度巡检 · 缺陷识别 · 报告生成",
  "coverImage": {
    "url": "https://cdn.example.com/inspection/power.jpg",
    "width": 1200,
    "height": 900,
    "alt": "输电线路无人机巡检"
  },
  "features": [
    { "code": "HD_CAPTURE", "name": "高精度拍摄" },
    { "code": "AI_DEFECT", "name": "AI缺陷识别" },
    { "code": "DATA_REPORT", "name": "数据报告" }
  ],
  "price": {
    "mode": "PER_DISTANCE",
    "startingAmountMinor": 69900,
    "currency": "CNY",
    "unitCode": "KILOMETER",
    "unitLabel": "公里"
  },
  "rating": {
    "average": 4.9,
    "count": 128
  },
  "completedCount": 856,
  "distanceMeters": 12500,
  "availability": "AVAILABLE",
  "primaryAction": "CONSULT"
}
```

## 5. 价格规则

```text
STARTING_PRICE
PER_DISTANCE
PER_VISIT
PROJECT_QUOTE
```

- 金额使用人民币分 `long`。
- `PROJECT_QUOTE` 的 `startingAmountMinor` 必须为空。
- `PER_DISTANCE` 需要单位 `KILOMETER` 或正式定义的其他单位。
- 前端统一通过价格值对象渲染。
- 参考图中的价格不自动成为生产价格。

## 6. 统计规则

- `rating.average` 和 `completedCount` 必须来自真实聚合。
- 没有真实数据时返回 `null`，前端隐藏。
- 不得使用参考图“4.9”“已服务8560+”作为生产默认值。
- 统计更新和精度由后续评价/订单模块负责；本目录模块只读取。

## 7. 城市与距离

- 城市使用 `cityCode`。
- 坐标持久化统一 WGS84/SRID 4326。
- 地图供应商输入 GCJ-02 时，经位置适配层转换后再持久化。
- 距离排序优先计算用户点到服务覆盖区域的最短距离；若模型只支持服务中心点，必须在接口说明中明确。
- 未提供位置却请求距离排序时，返回明确业务错误。

## 8. 错误码

建议：

| 错误码 | 含义 | 客户端处理 |
| --- | --- | --- |
| INS_CATALOG_CITY_NOT_ENABLED | 城市未开通 | 展示城市空状态 |
| INS_CATALOG_LOCATION_REQUIRED | 距离排序缺少位置 | 请求权限/回退 |
| INS_CATALOG_INVALID_FILTER | 筛选无效 | 提示并重置非法项 |
| INS_CATALOG_CONFIG_UNAVAILABLE | 配置暂不可用 | 错误状态和重试 |
| INS_CATALOG_SERVICE_UNAVAILABLE | 服务已下架 | 刷新列表 |
| COMMON_UNAUTHORIZED | 登录失效 | 目录可匿名时继续浏览 |
| COMMON_RATE_LIMITED | 请求频繁 | 延迟重试 |

所有错误响应包含可供日志关联的 `traceId`，但不得把堆栈、SQL 或内部路径返回客户端。

## 9. 搜索与安全

- 使用参数化查询。
- `%`、`_` 等通配符按搜索策略处理。
- 中文搜索建议使用 `pg_trgm` 或项目既有全文检索策略。
- 本期数据量未证明需要 Elasticsearch，不得引入。
- 对搜索接口进行合理限流。
- 列表只返回公开目录字段，不返回服务商联系人、精确内部地址或资质原件。

## 10. 缓存与版本

入口/筛选响应包含 `configVersion` 与 `updatedAt`。

- 客户端可使用 ETag 或项目既有缓存机制。
- Redis 键包含城市和配置版本。
- 数据更新后使旧版本失效。
- 缓存异常时回源 PostgreSQL。
- 离线缓存仅保留公开目录数据，并显示“缓存内容”状态。

## 11. OpenAPI 与客户端类型

- OpenAPI 是接口事实源。
- 服务端 CI 校验 OpenAPI 与实现。
- TypeScript DTO 从 OpenAPI 生成或手工严格映射；二选一，不能并存两套漂移类型。
- 领域对象不得直接由生成 DTO 替代。

