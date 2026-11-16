# App 重做 ① 验收记录（M02–M04 文案与 M03 价格卡）

> 分支：`codex/app-prototype-impl`（自 `codex/inspection-visits` 的 `0dcb1fa` 拉出）
> 依据：`docs/redesign/03-App实现任务计划.md` §4 的 T01–T08；画板 `InspCatalog`、`InspDetail`、`InspBooking`、`InspBooked`（2026-11-05 用户“确认通过”）
> 本记录对应 AGENTS.md §17 第 3、4 条合并的一次验收，未收到“验收通过”前不进入 ①b。

## 1. 范围与结果

| 任务 | 内容 | 结果 |
| --- | --- | --- |
| T01 | 盘点、冲突清单、偏差接受 | 2026-11-05 用户“接受，继续 T02” |
| T02 | 目录契约 1.1.0：卡片 `priceNote`；详情 `priceNote` 语义、`priceFactors`、`cancellationNote`、`serviceCities` | 2026-11-05 用户“确认，进 T03” |
| T03 | 迁移 V010（`cancellation_note`、`PRICE_FACTOR` 区块类型）、读服务、Mapper、开发种子 | 完成，空库和带种子库各跑一次迁移 |
| T04 | M02 标题“巡检服务”、占位词核对、卡片价格说明行、`pages.json` 标题 | 完成 |
| T05 | M03“价格与取消”卡；服务商行“资质已审核”加“可服务城市” | 完成 |
| T06 | M04 文案核对与补测；booking 路由 H5 标题“预约服务” | 完成 |
| T07 | 运行数据与夹具的“AI 缺陷识别”改“缺陷标注” | 完成，`apps/`、`backend/`、`contracts/` 内搜不到旧文案 |
| T08 | 回归、视觉比对、本记录 | 完成，见下 |

本期明确没碰：M01；M02 的查询、排序、分页；M03 区块顺序、最早可约；M04 的时段、分步、初值、附件时机、已取消帧、取消弹层；M05–M08；登录与权限；`features/platform`。

## 2. 回归结果

| 项目 | 结果 |
| --- | --- |
| 后端 `mvn -o test` | 272 通过，0 失败，含 T02 新增的 OpenAPI 契约示例测试和 T03 的迁移测试 |
| App 单测 `vitest run` | 65 个文件 636 条通过（T04 卡片 5 条、T05 价格卡 4 条和映射 2 条、T06 文案 4 条为新增） |
| 类型检查 `vue-tsc --noEmit` | 通过 |
| H5 生产构建 `uni build` | 通过 |
| 公开构建防泄漏 `verify:public-production-preview` | 通过 |
| 公开构建 E2E `public-build.spec.ts` | 5 条通过（生产构建静态服务） |
| App 编译 `uni build -p app` | 通过 |
| 黄金路径 `service-flow-visits.spec.ts` | 18 条通过，含 1 次和 6 次巡检（H5 开发服务 17420，后端 18420） |
| 目录 `catalog-route-city.spec.ts` | 7 条通过 |
| 详情 `service-detail-recovery.spec.ts` | 9 条通过 |
| 预约 `booking-back.spec.ts`、`booking-recovery.spec.ts` | 25 条通过 |
| `git diff --check` | 通过 |
| 文件白名单 | 34 个修改、7 个新增，全部落在 T02–T07 列出的目录和 `docs/redesign/`；无格式化、改名、删除 |
| Android 真机 | 未执行（无设备，T01 已接受为偏差），是本期缺口 |
| iOS 真机 | 未执行 |

## 3. 视觉比对

截图在 `.planning/t04`、`.planning/t05`、`.planning/t06`（git 忽略），H5 开发服务，Chrome，设备像素比 2。

| 页面 | 画板 | 视口 | 结果 |
| --- | --- | --- | --- |
| M02 巡检服务列表 | `InspCatalog` | 320 / 375 / 430 | 标题、占位词、价格说明行一致；无横向溢出；没有说明的卡片高度不变 |
| M03 服务详情 | `InspDetail` | 320 / 375 / 430 | “价格与取消”三行、服务商行标签与城市一致；“绝缘子及金具巡检”未超出 |
| M04 预约服务 | `InspBooking` | 320 / 375 / 430 | 标题、按钮一致；无横向溢出 |
| M04 提交结果 | `InspBooked` | 320 / 375 / 430 | 结果卡标题、说明、灰字、底栏“联系服务商”一致；无横向溢出 |

M02 列表和 M03 详情的说明行、价格卡用 Playwright 在请求层注入契约示例数据截图，因为 18420 上运行的是 T03 之前的后端包，新字段都是空；空字段时页面按“无数据隐藏”表现，也已截图核对。

有意保留、和画板不一样的地方（AGENTS.md §16 允许项或 T01 默认不改项）：

- M03 价格卡沿用页面现有区块的圆角和阴影，画板是平角白卡。
- M03 服务商标签文案来自服务端 `certificationLabel`，旧数据显示“企业认证”；城市名来自 `serviceCities`。
- M04 提交结果页的状态胶囊、服务商名、时段写法、巡检项目行、校验错误位置按 T01 结论不改；页头回看写“预约详情”保留。
- booking-submitted 的 H5 文档标题仍为“预约结果”，页头本身按状态显示。

## 4. 观察项（只记录，未修改）

| 问题 | 位置 | 建议 |
| --- | --- | --- |
| 320 视口下列表卡价格单位省略成“/…”、“已服务 x 次”折两行 | `InspectionServiceCard.vue` | 改动前已存在，另开任务 |
| 提交结果页服务卡下写死“云翼科技” | `InspectionBookingSubmittedPage.vue` | 属 T01 默认不改项，应改读服务端服务商名，另开任务 |
| 要在页面上真实看到价格说明、价格卡，需用本分支构建后端包替换 18420 上的旧包 | 预览环境 | 验收时按 `docs/15` 运行手册重新构建 |
| 开发用 `apps/mobile/.env.local` 从 inspection-visits 复制而来，git 忽略 | 预览环境 | 不进仓库 |

## 5. 验收入口

- 列表：`http://127.0.0.1:17420/#/pages/service/inspection/catalog?cityCode=370500&categoryCode=POWER`
- 详情：`http://127.0.0.1:17420/#/pages/service/inspection/detail?serviceId=33333333-3333-3333-3333-333333333333&cityCode=370500`
- 预约：`http://127.0.0.1:17420/#/pages/service/inspection/booking?serviceId=33333333-3333-3333-3333-333333333333&cityCode=370500`
- 提交结果：登录后真实提交一次预约进入。
