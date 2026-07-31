# 12｜巡检预约、方案、履约与报告交付基线

## 1. 本增量的授权范围

本文件记录用户在当前任务中明确批准的范围扩展。它只扩展巡检服务详情之后的交易履约链，不修改入口、目录、其他一级业务或全局导航。

唯一允许接入现有页面的改动是：

```text
巡检服务详情页“预约服务”
→ 预约填写
→ 预约申请已提交
→ 方案确认
→ 服务安排
→ 报告交付与验收
```

不包含支付、退款、评价、售后、飞控、直播、空域申请、AI 缺陷识别和 Web 管理页面。

## 2. 页面编号与路由

| 页面编号 | 页面 | 路由 | 入口条件 |
| --- | --- | --- | --- |
| INS-M-004 | 预约服务/填写需求 | `/pages/service/inspection/booking` | 登录用户从详情页点击“预约服务” |
| INS-M-005 | 预约申请已提交 | `/pages/service/inspection/booking-submitted` | 预约创建成功 |
| INS-M-006 | 方案确认 | `/pages/service/inspection/plan-confirmation` | 服务商已发布有效方案 |
| INS-M-007 | 服务安排 | `/pages/service/inspection/fulfillment` | 用户已确认方案 |
| INS-M-008 | 报告交付 | `/pages/service/inspection/delivery` | 服务商已发布交付物 |

INS-M-004 的期望时间使用内嵌底部弹层选择，不调用系统日期选择器。每天固定
09:00–12:00、14:00–17:00、18:00–21:00 三个时段，每个
“服务 + 城市 + 日期 + 时段”容量为 9。余量由后端按非 CANCELLED
预约实时计算，创建预约时再次校验。

预约提交结果页在方案尚未发布时显示“返回首页”；真实方案发布后，同一底部按钮切换为“查看服务方案”，继续进入 INS-M-006。PDF 中带“查看预约进度”的旧版结果页明确废弃。

页面只读取路由中的稳定 ID，不把联系人、报价、方案或整个服务对象序列化进 URL。

## 3. 模块边界

移动端新增代码只能落在：

```text
apps/mobile/src/features/inspection/
├── domain/service-flow/
├── application/service-flow/
├── data/service-flow/
├── presentation/booking/
├── presentation/plan/
├── presentation/fulfillment/
├── presentation/delivery/
├── state/service-flow/
└── tests/service-flow/
```

路由壳只允许新增到 `apps/mobile/src/pages/service/inspection/`。现有入口、目录和详情模块保持冻结；详情页只允许把既有预约动作接到类型化路由。

后端采用模块化单体：

```text
backend/
├── modules/identity/                 # 登录主体、角色与权限
├── modules/inspection/
│   ├── domain/serviceflow/
│   ├── application/serviceflow/
│   ├── infrastructure/
│   │   ├── persistence/
│   │   ├── objectstorage/
│   │   └── security/
│   └── interfaces/appapi/
└── bootstrap/
```

依赖方向保持：

```text
presentation → application → domain
infrastructure → domain ports
identity → 只暴露 Principal 与 Permission，不依赖 inspection
```

## 4. 聚合与状态机

### 4.1 预约

```text
SUBMITTED → PLAN_PUBLISHED
SUBMITTED → CANCELLED
```

- 草稿只保存在客户端，不写入服务端。
- `POST /reservations` 必须带 `Idempotency-Key`。
- 同一用户、同一幂等键和同一请求摘要只能生成一个预约号。

### 4.2 方案

```text
DRAFT → PUBLISHED → CONFIRMED
                  → EXPIRED
                  → WITHDRAWN
```

- 一个预约只能有一个当前有效方案。
- 已发布方案不可原地改价；变更必须产生新版本并使旧版本失效。
- 只有预约所有者可以确认方案。
- 确认方案只创建服务单并进入资源安排，不扣款、不标记完成。

### 4.3 服务单

```text
ARRANGING → IN_PROGRESS → DELIVERED → ACCEPTED
```

- 状态只能前进，禁止客户端直接指定目标状态。
- 方案确认、安排发布、交付发布和验收均需幂等。
- PDF 验收数据中，同一服务单号为 `FW202607270001`；实现不得把该示例号硬编码到生产。
- `ACCEPTED` 后只预留类型化“评价”扩展，不在本期生成评价页。

## 5. 页面数据连续性

下列字段必须来自同一预约/方案/服务单链路，刷新后仍从服务端恢复：

- 城市、服务 ID、服务标题和服务封面。
- 巡检地点、线路长度、期望日期和巡检项目。
- 联系人快照。
- 预约号、方案号、服务单号。
- 报价明细、交付物清单和方案有效期。
- 团队、设备、进度事件、问题统计和交付文件。

用户在 PDF 中看到的 `24` 基杆塔、`18.6km`、`3` 个问题、`2+1` 分类、`24` 个 KML 点位和四类文件均为验收夹具，生产代码不得硬编码。

## 6. 权限基线

权限判断必须在服务端完成；移动端隐藏按钮只能改善体验，不能代替授权。

| 主体 | 允许 | 明确禁止 |
| --- | --- | --- |
| 游客 | 浏览已公开的目录与服务详情 | 创建预约、读取服务单、上传或下载私有文件 |
| 客户 `CUSTOMER` | 创建预约；读取本人预约/方案/服务单；确认本人方案；下载本人交付文件；验收本人服务单 | 读取他人数据；发布方案；安排资源；发布交付物 |
| 服务商操作员 `PROVIDER_OPERATOR` | 读取所属服务商获分配的数据；发布方案；维护安排与进度；上传并发布交付物 | 读取其他服务商数据；代客户确认方案或验收 |
| 平台运营 `PLATFORM_OPERATOR` | 带工单理由的跨租户只读、交付下载审计、投诉调查、状态与权限诊断 | 默认不得代用户确认方案、代服务商交付或代用户验收 |
| 系统任务 `SYSTEM` | 方案过期、文件扫描回调、ZIP 汇总、事件投递 | 通过移动端登录或执行人工业务动作 |

最小权限编码：

```text
inspection.reservation.create
inspection.reservation.read.own
inspection.reservation.read.assigned
inspection.reservation.read.all
inspection.plan.publish.assigned
inspection.plan.read.own
inspection.plan.read.all
inspection.plan.confirm.own
inspection.order.read.own
inspection.order.read.assigned
inspection.order.read.all
inspection.order.arrange.assigned
inspection.order.progress.assigned
inspection.delivery.publish.assigned
inspection.delivery.download.own
inspection.delivery.download.all
inspection.acceptance.create.own
inspection.audit.read
```

资源归属、服务商归属和状态条件必须与 RBAC 同时校验。仅拥有权限编码但不拥有资源归属时仍返回 `403`。

## 7. API 约束

- 契约事实源：`contracts/openapi-inspection-service-flow.yaml`。
- 所有写接口使用 Bearer Token，并接受稳定的 `Idempotency-Key`。
- 状态变更同时要求 `If-Match` 或请求体中的 `version`，防止旧页面覆盖新状态。
- 金额使用最小货币单位 `long`。
- 联系人和精确地址不得写入普通日志或埋点。
- 返回直接 DTO 与统一 `ApiError`；不得暴露数据库实体。
- `404` 用于不可见资源，避免通过 ID 探测他人订单。
- 幂等键冲突但请求体摘要不同，返回 `409 INS_IDEMPOTENCY_KEY_REUSED`。

## 8. PostgreSQL 数据基线

迁移事实源：`backend/modules/inspection/migrations/V001__inspection_service_flow.sql`。

业务表：

- `inspection_reservation`
- `inspection_reservation_item`
- `inspection_file_object`
- `inspection_reservation_attachment`
- `inspection_service_plan`
- `inspection_service_plan_item`
- `inspection_plan_deliverable`
- `inspection_service_order`
- `inspection_order_assignment`
- `inspection_order_progress`
- `inspection_delivery`
- `inspection_delivery_file`
- `inspection_acceptance`
- `inspection_state_transition`
- `inspection_idempotency_record`
- `inspection_domain_event_outbox`

身份与授权表独立放在 identity 模块迁移中，避免 inspection 复制登录体系。

所有业务表必须包含主键、创建/更新时间、状态或删除标记、`version`。状态转换与业务写入使用同一事务，事件通过 outbox 投递。

## 9. MinIO 基线

所有对象保持私有，不返回永久公开 URL。

```text
buckets:
  {env}-inspection-quarantine
  {env}-inspection-private
object key:
  tenant/{providerId}/reservation/{reservationId}/{fileId}
  tenant/{providerId}/order/{serviceOrderId}/delivery/{deliveryId}/{fileId}
  tenant/{providerId}/order/{serviceOrderId}/bundle/{deliveryId}.zip
```

规则：

- 对象键不包含原始文件名、手机号、地址或联系人。
- 上传采用短时效预签名 PUT；服务端先校验用途、MIME、扩展名、大小和数量。
- 新上传对象只进入 quarantine 桶。上传完成后状态为 `UPLOADED`，扫描通过并迁入 private 桶后变为 `READY`；扫描失败或超时不可被业务引用。
- 下载前先校验权限和资源归属，再签发最长 10 分钟的预签名 GET。
- 用户端只允许下载 `PUBLISHED` 交付中的 `READY` 文件。
- 全部下载使用交付发布时生成的 ZIP 汇总对象，不由移动端拼接。
- 文件下载、拒绝、扫描结果和 ZIP 生成都写审计日志。

允许的交付文件类型：

| 类型编码 | 扩展名 | MIME |
| --- | --- | --- |
| `IMAGE_ARCHIVE` | `.zip` | `application/zip` |
| `DEFECT_LIST` | `.xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| `POINT_KML` | `.kml` | `application/vnd.google-earth.kml+xml` |
| `INSPECTION_REPORT` | `.pdf` | `application/pdf` |

## 10. UI 资产与验收边界

- PDF 整页和其中切片不得用作交互页面或按钮。
- 服务封面必须是独立可替换且授权清晰的图片。
- 如果由 GPT 生成新图，每一张都必须先交给用户确认，未确认不得进入生产资源目录。
- Android、iOS 真机截图和自动截图按相同状态与同一服务单对比。
- 真机验收不能在当前 Windows 主机上伪造：Android 可在本机环境就绪后执行，iOS 需要 macOS/云构建及真实 iPhone。

## 11. 冻结区

本增量明确保持不变：

- `inspection/portal` 与 `inspection/catalog` 的页面结构和交互；只允许为真实公共入口补齐现有目录 API 数据源，不做视觉优化。
- 底部 Tab 顺序和其他一级页面。
- 服务详情的结构、评价和全局导航；只允许从真实目录 DTO 恢复已有详情字段并连接既有预约按钮。
- 目录 OpenAPI 和数据库表。
- 其他并行任务产生的未提交文件。
