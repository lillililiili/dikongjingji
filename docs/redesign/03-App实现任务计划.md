# App 实现任务计划（按设计画布 v60）

> 文档版本：V0.2 草案（2026-11-16 加 §6：11-14 修订版 UI 的影响，待用户确认；V0.1 为 2026-11-05）
> 分支：`codex/app-prototype-impl`，工作树 `.worktrees/app-impl`，从产品线 `codex/inspection-visits` 的 `0dcb1fa` 拉出
> 依据：
> 1. `AGENTS.md` V2.2：§0 任务计划、§2 已纳入模块、§3 不开发清单、§5 模块边界、§15 验证等级、§17 停止节点
> 2. `docs/redesign/01-概要设计.md` V0.2：§5.2 页面地图、§13 路线、§14 决策
> 3. `docs/redesign/02-巡检服务流程修订方案.md` V0.2：§2.3 阶段 C 前要定的、§12 活动任务计划
> 4. 设计画布 v60（`canvas.json`，152 块画板；规格板 `Batch4Spec` 第 9、11 节，`Batch5Spec` 第 3、5、6、7 节）。规格板不在仓库内，T01 时把 `Batch4Spec` 第 9、11 节摘录进 `docs/redesign/04-原型确认摘录.md`
> 5. 《服务与商城页面参照》（2026-11-04）第 6 节：6.1 可直接改、6.2 动已验收页面要用户点头、6.3 M01–M04。报告不在仓库内，相关段落同样摘录进 `docs/redesign/04-原型确认摘录.md`
> 6. `docs/18-inspection-multi-visit-report.md`：阶段 A、B 验收记录
>
> 本文件只写计划，不改代码。用户确认后，§4 即 AGENTS.md §0 所说的“当前活动任务计划”。

---

## 1. 原型确认记录

### 1.1 确认了什么

| 项 | 内容 | 出处 |
| --- | --- | --- |
| 画布版本 | v60，152 块画板：12 张规格板（Main、Flows、NavSpec、HomeMallSpec、PdExpSpec、Ops、TradeSpec、Batch2–5Spec、StatusBoard）、4 张早期稿（Home、Mall、Product、Experience）、136 张页面板（其中 8 张 Ref 板是定稿图落点） | `canvas.json` `boards`、`notes` |
| 确认时间 | 2026-11-05，用户回复“确认通过” | 本次对话 |
| 确认范围 1 | 全部 App 页面，包括第五批补的 56 张和最后补缺口的 12 张 | `Batch5Spec` 第 1、7 节 |
| 确认范围 2 | 按《参照》第 6 节打磨后的版式：27 张画板（服务详情与预约 10、需求与报价 5、商城 8、活动与到馆 4） | `Batch5Spec` 第 6 节 |
| 确认范围 3 | 25 条设计假设视为可实现的默认规则：照着写代码；业务以后改口，按新任务改，不算 Bug | `Batch5Spec` 第 3 节 |
| 对 M01–M04 的意义 | 修订方案 §1 和《参照》§6.3 把 M01–M04 列为“不变”。本次确认只替换其中画布已改的几处，清单见 §5 | 修订方案 §1；《参照》§6.3 |
| 不算确认的 | 画板上的价格、名额、日期、人名、主体都是示意数据；评分、服务次数、天气、角标无真实数据时隐藏 | `canvas.json` 便签 n1；AGENTS.md §8 |

25 条设计假设按组：活动与报名 6 条、到馆与体验 3 条、询价需求与合同 2 条、其他专业服务 4 条、商品变体租赁售后 3 条、账户与公共 5 条、内容企业工作模式 2 条。

### 1.2 仍未决定、实现时必须绕开或留开关的

| 编号 | 事项 | 出处 | 实现时怎么处理 |
| --- | --- | --- | --- |
| C1 | 主用支付渠道 | 修订方案 §2.3 | 阻塞 ②。渠道未定时交易模块只做到契约与迁移方案确认，不得用 Mock 渠道冒充闭环（AGENTS.md §11） |
| C2 | 收款主体：服务商自己的商户号还是平台代收 | 修订方案 §2.3；概要 §10 | 阻塞 ②。方案确认页的服务商行先只显示签约和履约主体，不显示收款、开票主体（《参照》§6.2 第 25 条） |
| C3 | 确认方案后的取消、改期、中途终止 | 修订方案 §2.3 | 阻塞阶段 C 打开付款节点。取消入口只在方案确认前显示；服务订单不加售后入口（《参照》§6.2 第 28 条） |
| C4 | 验收超时、自动验收 | 修订方案 §2.2、§2.3 | 一期不做。M07 只有确认验收和“对报告有疑问”接客服 |
| D10 | 收藏与对比一期还是二期 | 概要 §14 | Favorites、MallCompare 不开发；“我的”“体验结果”“商品详情”的收藏、对比入口由功能开关隐藏 |
| D11 | Web 运营端是否进一期 | 概要 §14 | 不在 App 计划内；Ops 画板不实现 |
| D12 | 金刚位“培训课程”去向 | 概要 §14 | 代码现指活动频道（`app-shell/navigation/home-channels.ts` 的 `course` 项），保持不动，确认后再改 |
| D13 | “今天去体验”“城市资讯”放哪 | 概要 §14 | 已随 2026-11-05 的 25 条设计假设确认作为默认规则，开工不再问：推荐频道按画板 HomeFull 的位置实现，无数据整体隐藏（概要 §7.1） |
| D14 | M01 是否保留天气 | 概要 §14 | 不显示，沿用 nav-plan-c 现状；天气接口保留 |
| D15 | 一期课程卖什么、怎么交付 | 概要 §14 | MyCourses、CourseDetail 不开发；“课程”频道只用商品列表模板，不上架数字课程 |
| B1 | 客服形式 | `Batch4Spec` 第 11 节 | 已随 2026-11-05 的 25 条设计假设确认作为默认规则，开工不再问：按设计假设第 18 条（站内会话，一个模板承接平台客服、商家、服务商）在 ⑦ 实现；此前各页“客服”“联系服务商”“联系负责人”走 `PlatformFeatureGateway` 提示 |
| B2 | 验收不通过和服务单退款 | `Batch4Spec` 第 11 节；`Batch5Spec` 第 5 节 | 不做。等 C3 一起定 |
| B3 | 按项目报价的服务走预约还是需求发布 | `Batch4Spec` 第 11 节 | 设计假设第 12 条（走需求发布，四类服务详情和园区安防巡检的主按钮都是「发布需求」）作为默认规则，在 ⑤、⑥ 实现 |
| B4 | 评价提交 | `Batch4Spec` 第 11 节；`Batch5Spec` 第 5 节 | 不做（AGENTS.md §3）。详情只读真实评价聚合 |
| B5 | M04 的名称和提交结果以代码还是画板为准 | `Batch4Spec` 第 11 节 | 已由 2026-11-05 确认解决：以画板为准，范围见 §5 |
| B6 | 状态名“待报价”和“待方案”定一个 | `Batch4Spec` 第 11 节 | 未定。代码和 OrderList 画板写“待方案”，保持；做 OrderService 状态链前必须定 |
| N1 | Web 管理端 | `Batch5Spec` 第 5 节 | 同 D11 |
| N2 | 标准服务包先下单 | `Batch5Spec` 第 5 节 | 两种口径未定（建设方案写直接购买服务包；AGENTS.md §9 写预约不创建支付记录）。按画板实现：服务只走预约或需求发布，不进购物车、不直接下单 |
| N3 | 组织创建和认证在 App 还是 Web | `Batch5Spec` 第 5 节 | ⑧ 只做已加入组织后的页面和被邀请人确认加入（OrgInvite）；创建、认证不做 |
| A1 | 商品售后流程（AftersaleApply、AftersaleDetail、AftersaleFlow、AftersaleList） | AGENTS.md §3“售后工单”不开发 | 与 AGENTS.md §3 冲突，需用户确认并修订 §3 后才进 ③；未确认前 ③ 只做 ② 的标准退款，不做售后申请、售后详情、寄回退款和售后列表 |
| A2 | 核销工作台（StaffVerify、StaffException） | AGENTS.md §3“工作台页面”不开发；概要 §14 D8 | 受 AGENTS.md §3 禁令和 D8 未确认双重阻塞。④ 的验收路径不走工作台页面，改为“受权 API 建立前置状态并保存证据”（AGENTS.md §15 E2E）；工作台页面等 D8 和 §3 修订 |

`Batch5Spec` 第 5 节的六项“没画的”对应上表 N1、B4、B2、C3、N2、N3。

另一处不算冲突：评分和服务次数，画板只画了“暂无评价”这一帧，代码有真实聚合时显示、没有时隐藏（`Batch4Spec` 第 9 节）。代码行为符合 AGENTS.md §8，不改。

---

## 2. 画布到代码的映射

### 2.1 代码现状（2026-11-05 只读核对）

| 项 | 现状 | 位置 |
| --- | --- | --- |
| 移动端 feature | 只有两个：`inspection`（M01–M08、登录页、首页/我的/专业服务频道的内容）和 `platform`（发现、商城、专业服务频道四类 Tab 的开发夹具内容，`PlatformFeatureGateway`） | `apps/mobile/src/features/` |
| 页面 | 17 页：首页、`service/index`（旧路由，重定向）、发现、消息（占位“尚未开通”）、购物车（占位）、商城（已移出底栏）、我的、我的订单（M08）、巡检 9 页（index、catalog、detail、login、booking、booking-submitted、plan-confirmation、fulfillment、delivery） | `apps/mobile/src/pages.json` |
| 壳层 | 底栏首页 / 发现 / 消息 / 购物车 / 我的；首页 9 个频道，公开构建只开推荐和专业服务；两张大卡、8 个金刚位 | `apps/mobile/src/app-shell/` |
| 公共能力 | 当前城市 store 与选城面板、城市目录 API、天气适配器、Banner | `apps/mobile/src/shared/{city,weather,presentation}` |
| 登录 | 用户名密码，JWT 带 `organizationId`；没有手机号登录 | `features/inspection/presentation/auth/pages`；`backend/bootstrap/.../security/AuthenticationController.java` |
| 服务端模块 | 只有 `identity`（用户、组织、成员、角色、权限、审计，迁移 V001–V003）和 `inspection`（迁移 V001–V009 加 `R__platform_city_reference`） | `backend/modules/`；`backend/pom.xml` |
| 控制器 | 城市、天气、登录、巡检目录（只有 `/services` 和 `/services/{id}`，没有 `/portal`、`/filters`）、时段、预约、文件、方案、服务单、交付 | `backend/bootstrap/src/main/java/com/lowaltitude/inspection/` |
| 契约 | 4 份：巡检目录、巡检服务流程 2.0.0、城市、天气；加 2 份示例 | `contracts/` |
| 付款节点 | 模型、表、`PaymentNodePlanner` 已有；开关 `INSPECTION_PAYMENT_NODES_ENABLED` 默认 false；App 读 `paymentMode: NONE / NODES`；订单阶段没有 `AWAITING_PAYMENT` | `backend/bootstrap/src/main/resources/application.yml`；`features/inspection/data/service-flow/inspection-plan-api.ts` |
| 自动化 | App 单测在各 feature 的 `tests/`；E2E 14 个 spec；黄金路径和冒烟脚本在 `infra/scripts/` | `apps/mobile/e2e/`；`infra/scripts/verify-f08-golden-e2e.cjs`、`verify-visit-flow-smoke.cjs` |
| 阶段 B 结果 | L3 通过；Android 真机未做 | `docs/18` §3、§4 |
| 工作树未提交改动 | 已含未提交改动：`CatalogHeader.vue` 占位词已改为“搜索巡检服务、场景或关键词”；M04 预约页标题“预约服务”、按钮“提交预约”，结果页“需求已提交 / 等待服务商确认，方案确认前不收费 / 进度可在“我的订单”查看 / 联系服务商”已落地，单测 47 条、全量 621 条、类型检查通过；另加了页头回看时写“预约详情”的规则（`getSubmittedBookingHeaderTitle`），这一条超出 §5 清单，放进 T01 的待用户点头清单（默认保留，用户不同意就回退） | `git status`；`presentation/catalog/components/CatalogHeader.vue`、`presentation/booking/pages/`、`tests/booking-flow.test.ts` |

### 2.2 分组映射

列的含义：画板（canvas 标题里的文件名）；现有代码（feature 目录、页面、契约）；要新建（页面、feature、契约、表）；依赖哪些未决定项（§1.2 编号）。

#### 2.2.1 巡检闭环

| 画板 | 现有代码 | 要新建 | 依赖 |
| --- | --- | --- | --- |
| HomeSvc 巡检 Tab | M01：`presentation/portal`，挂在首页专业服务频道（`features/inspection/presentation/platform/service`） | 无 | — |
| InspCatalog | M02：`presentation/catalog`。标题“电力巡检”；占位词已在工作树未提交改动中改为“搜索巡检服务、场景或关键词”（`components/CatalogHeader.vue`）；排序透传给服务端但只有 SALES_DESC 有客户端处理（`model/inspection-catalog.ts`）；默认查询无分类、带排序时固定 POWER，`page=1&size=50` 不分页（`data/catalog/inspection-catalog-api.ts`） | ①：标题、卡片价格说明（契约 `InspectionServiceCard` 加 `priceNote`）、核对已落地的占位词。①b：真实 API、服务端搜索筛选分页、距离排序、`/portal` `/filters` 后端。⑥：全品类、城市未开通态 | — |
| InspDetail | M03：`presentation/detail`。契约已有 `priceNote`、`provider.certificationLabel`；没有取消说明、影响价格、可服务城市 | ①：“价格与取消”卡和服务商行（契约 `InspectionServiceDetail` 加字段，迁移 V010） | — |
| InspBooking、InspBooked | M04：`presentation/booking`。工作树未提交改动已把标题改为“预约服务”、按钮“提交预约”（`InspectionBookingPage.vue`），结果卡改为“需求已提交”“等待服务商确认，方案确认前不收费”加灰字“进度可在“我的订单”查看”、底栏“联系服务商”（`InspectionBookingSubmittedPage.vue`）；另加了 `getSubmittedBookingHeaderTitle`（回看时页头“预约详情”），待 T01 点头 | ①：核对已落地的文案并补测 | — |
| PlanOne、PlanConfirm、PlanExpired、PlanConfirmed | M05：`presentation/plan`，阶段 B 已按画板重做 | 无；PlanConfirmed「查看合同」等 ⑤ 的 Contract | C2 |
| FulfillOne、Fulfillment、FulfillmentLater、FulfillOneDone | M06：`presentation/fulfillment` | 无 | — |
| DeliveryOne、Delivery | M07：`presentation/delivery` | 无；验收后的支付入口属 ② | C3、C4 |
| OrderList 巡检卡 | M08：`presentation/orders`，`/pages/mine/inspection-orders` | ③ 并入统一台账 | B6 |
| CashierOneFirst、CashierOneFinal、CashierFirst、CashierFinal | 无收银台 | ②：收银台、支付结果；巡检阶段 C 打开开关、`AWAITING_PAYMENT`、`INS_DOWN_PAYMENT_REQUIRED`（修订方案 §4） | C1、C2、C3、C4 |
| OrderService | 无 | ③ 订单台账后再做（修订方案 §7）；此前由 M06 承担 | B6 |

#### 2.2.2 活动与报名

| 画板 | 现有代码 | 要新建 | 依赖 |
| --- | --- | --- | --- |
| HomeAct | 频道定义在 `home-channels.ts`（公开构建隐藏）；没有页面 | feature `activity`：活动列表（频道根页） | — |
| ActDetail、Ref06ActDetail、Signup、EnrollTicket、Ref08Ticket、EnrollList、OrderEnroll | 无 | 活动详情、报名填写、报名凭证、我的报名、报名订单详情；契约 `openapi-activity.yaml`；表：活动、场次、报名、参与人、凭证、核销记录 | 设计假设 1、3、6；D12 |
| EnrollRefund、Ref22Refund、EnrollRefundDetail | 无 | 报名退改申请与进度；退款走 ② 的标准退款 | 设计假设 4；C1 |
| EnrollWaitlist、TeamSignup、TeamOrder、EnrollResult | 无 | 候补、团队报名与团队订单、签到和结果记录 | 设计假设 2、5 |
| CashierEnroll | 无 | ② 的收银台按“报名”订单类型复用 | C1 |
| Participants | identity 没有参与人表 | feature `account/participants`；契约 `openapi-account.yaml`；表：参与人、监护关系 | 监护人确认文案待法务（《参照》§6.1 第 16 条） |
| StaffVerify、StaffException | 无 | feature `staff`：核销工作台、异常处理；核销 API 在服务端校验场馆与场次归属（概要 §7.8） | D8；A2（页面等 D8 和 AGENTS.md §3 修订，④ 先只做核销 API） |

#### 2.2.3 到馆与体验

| 画板 | 现有代码 | 要新建 | 依赖 |
| --- | --- | --- | --- |
| ExpCenter | 频道定义在 `home-channels.ts`；没有页面 | feature `experience`：体验中心（频道根页） | D7（已随 2026-11-05 的 25 条设计假设确认为默认规则，按画板实现，开工不再问） |
| VenueDetail、Ref10Venue、VenueBook、VenueCode、VenueDevices、CashierVenue | 无 | 场馆详情、到馆预约确认、核销码、馆内设备；契约 `openapi-venue.yaml`；表：场馆、项目、场次、到馆预约、核销 | 设计假设 7、8、9 |
| ExpPick、ExpSim、ExpResult、ExpRecords、SoftwareTrial | 无 | 选择体验方式、仿真容器（H5 消息桥，概要 §8.3）、体验结果、我的体验、软件试用 | D7（先做两个仿真模板）；D10（结果页的收藏对比入口隐藏） |
| CalendarPick | 无 | 公共日历选择器（到馆场次、预约巡检共用） | — |

#### 2.2.4 询价、需求与合同

| 画板 | 现有代码 | 要新建 | 依赖 |
| --- | --- | --- | --- |
| Demand、Ref20Demand、DemandList | M01 的“发布巡检需求”只走 `PlatformFeatureGateway`（AGENTS.md §9） | feature `demand`：需求发布、需求与报价列表；契约 `openapi-service-demand.yaml`；表：需求、分派、报价版本 | B3；N2 |
| Quote、PlanCompare、QuoteGoods | 巡检一个预约只有一个有效方案（`uk_inspection_plan_current`，概要 §2.2） | 报价比选、方案对比、询价商品报价比选与转订单；比选用新模型，不改巡检方案表 | B3；设计假设 10 |
| PdInquiry、PdInquiryFull | 无 | 询价单、询价商品详情（和 ③ 的商品详情同模板，询价版本） | 设计假设 10 |
| Contract | 无 | 合同查看与受控下载（复用 M07 的短时效下载意图写法） | 设计假设 11（线下签署、扫描件归档） |
| ProviderPage | 服务商资料表 `inspection_provider_profile`（V004）只服务于 M03 | 服务商主页 | — |
| MapPick | M04 的巡检地点选择（`docs/flow-map.md` §1.1） | 公共地图选点（需求地点、巡检地点） | — |

#### 2.2.5 其他专业服务

| 画板 | 现有代码 | 要新建 | 依赖 |
| --- | --- | --- | --- |
| HomeSvc 航拍、测绘、设备维护、技术支持 Tab | `features/platform` 的开发夹具（`data/development/platform-service.fixture.ts`），公开构建不显示 | 占位词、保障条、服务流程按类型配置；搜索去向改到四类目录 | B3 |
| SvcCatalog | 无 | 四类服务目录，复用 M02 的目录模板 | ①b 的 M02 真实 API |
| SvcAerial、SvcSurvey、SvcMaint、SvcTech、InspDetailPark | 无；概要 §7.3 写 M03 原样复用 | 四类详情和按项目报价详情：照 M03 还是改 M03，在 ⑥ 的节点 2 定；主按钮「发布需求」接 ⑤ | B3；设计假设 12–15 |

#### 2.2.6 商品与购物车（主线）

| 画板 | 现有代码 | 要新建 | 依赖 |
| --- | --- | --- | --- |
| Mall2、MallFilter、MallSearch、SearchPre | `features/platform/presentation/mall/PlatformMallPage.vue` 是开发夹具；`pages/mall/index` 已移出底栏；全局搜索没有 | feature `mall`：类目频道（商品列表模板）、筛选面板、搜索前置页与结果页；契约 `openapi-mall-catalog.yaml`；表：商家、商品、SKU、商家供给（商家 × SKU × 城市）、城市授权 | D5（首批商品和主体） |
| PdFull、PdSku、Merchant、MoreSheet | 无 | 商品详情（在售商家列表、体验标签）、规格与配送弹层、商家主页、更多面板 | D10（对比入口隐藏） |
| Cart、CartStates | `pages/cart/index.vue` 占位 | 购物车（按商家分组，一期一次结算一个商家）、管理与空态与未登录态 | — |
| Checkout、Ref04Checkout、CheckoutStates、AddressList、RegionPick | 无 | 确认订单、地址管理、省市区选择；feature `account/address`；契约 `openapi-trade-order.yaml`、`openapi-account.yaml`；表：订单、订单行、地址 | — |
| Cashier、PayResult | 无 | ②：收银台、支付结果 | C1 |
| OrderList、OrderGoods、OrderSearch | M08 只有巡检记录 | feature `order`：统一台账（全部 / 商品 / 服务 / 报名）、商品订单详情、订单搜索；巡检记录并入“服务”分类（概要 §12 第 4 项）；`features/inspection/presentation/platform/{home,mine}` 迁到对应 feature，M08 只保留巡检记录读取接口 | B6 |
| AftersaleApply、AftersaleDetail、AftersaleFlow、AftersaleList | 无 | 商品售后申请、详情、寄回到退款、售后列表；退款走 ② | 设计假设 20；C1；A1（未确认前不做） |

#### 2.2.7 商品变体、租赁、售后与购物状态

| 画板 | 现有代码 | 要新建 | 依赖 |
| --- | --- | --- | --- |
| PdStates | 无 | 商品详情的无货、本城不可售、已下架帧，和 ③ 商品详情一起做 | — |
| PdRental、RentalInquiry、DevicePick、RentalQuote、CashierRental、OrderRental、PhotoViewer | 无 | feature `rental`：租赁商品详情、租赁询价单、添加设备、报价单与确认合同、租金收银台（② 复用）、租赁合同订单、图片查看器；契约 `openapi-rental.yaml`；表：租赁询价、报价、合同订单、交接记录 | 设计假设 16、17；押金不在线收取（AGENTS.md §3 保证金暂缓）；C1 |

#### 2.2.8 账户与公共

| 画板 | 现有代码 | 要新建 | 依赖 |
| --- | --- | --- | --- |
| Login | 巡检模块内的用户名密码登录页，登录后回到原目标 | 手机号登录（APP-COM-03）：identity 加验证码能力；页面提到公共 `auth`，不再放在巡检模块内 | 短信能力（概要 §8.3） |
| Onboarding、MineGuest | 无 | 首次启动授权、游客态的“我的”“消息”“购物车” | 设计假设 19、20 |
| Mine | `features/inspection/presentation/platform/mine`：登录后读巡检订单和阶段计数 | 按画板 Mine 重排：账户卡、订单五态、我的参与、参与人、企业组织、售后、设置、工作模式入口 | D10、D15 |
| Settings、SetPhone、SetDevices、SetData、SetPerm、About、Help、Agreement、NoticeDetail | 无 | 设置系列、帮助与反馈、关于、协议正文、通知详情；identity 加登录设备、注销申请 | 设计假设 23（协议正文占位） |
| Messages | `pages/message/index.vue` 占位 | 消息 Tab：站内信列表与未读数；表：站内信 | — |
| Chat | 无 | 客服与商家咨询会话（一个模板） | B1；设计假设 18 |
| ShareSheet、Scan | 无 | 分享面板（只复制链接）、扫一扫落地 | 设计假设 21 |
| CitySheet、CityClosed | 选城面板已有（`shared/city/presentation/CitySelectionSheet.vue`）；城市未开通态在首页由 `publiclyAvailable` 控制 | 城市未开通页按画板补说明 | — |
| OrgInvite | identity 有组织、成员、角色表 | 被邀请人确认加入；identity 加邀请 API | N3；设计假设 25 |

#### 2.2.9 内容、企业与工作模式

| 画板 | 现有代码 | 要新建 | 依赖 |
| --- | --- | --- | --- |
| Discover、ContentVideo、VideoFeed、Ref19Content、ContentArticle、LiveNotice、LiveRoom、Topic | `features/platform/presentation/discover/PlatformDiscoverPage.vue` 是开发夹具 | feature `content`：发现频道、视频与图文详情、直播预告、直播间观看端（2026-11-13 用户确认平台内直播进一期，概要设计 §14 D19；直播间不收款、不打赏；推流与播放的服务商未定，D20 定之前不开工）、专题页；契约 `openapi-content.yaml`；表：内容、关联商品活动、推广标识 | 设计假设 24 |
| OrgDetail、Ref24Org、OrgMembers | identity 有组织、成员、角色，令牌带 `organizationId`；App 没有组织页面和身份切换 | feature `account/organization`：企业组织、成员与角色；契约 `openapi-identity-organization.yaml` | N3；设计假设 25 |
| StaffRecords | 无 | 核销全部记录，接 ④ 的核销工作台 | D8 |

#### 2.2.10 公共选择器与公共状态

| 画板 | 现有代码 | 要新建 | 依赖 |
| --- | --- | --- | --- |
| MapPick、RegionPick、CalendarPick、PhotoViewer、MoreSheet、OrderSearch | 无 | 放全局 `shared`，各期首次用到时建（MapPick 在 ⑤，RegionPick 在 ③，CalendarPick 在 ④，PhotoViewer 在 ⑤，MoreSheet 在 ③，OrderSearch 在 ③） | — |
| StatusBoard | M02、M03 已有加载、空、错误、城市未选态 | 8 种公共状态做成全局 `shared` 组件，新页面统一用 | — |

#### 2.2.11 首页、底栏与增强

| 画板 | 现有代码 | 要新建 | 依赖 |
| --- | --- | --- | --- |
| HomeFull | 首页框架、推荐频道大卡和金刚位已有；Banner、近期活动、本地精选、附近场馆、今天去体验、城市资讯只有开发预览 | 各期把自己的真实区块接进来：③ 本地精选，④ 近期活动、附近场馆、今天去体验，⑧ Banner、城市资讯 | D13 |
| MyCourses、CourseDetail、Favorites、MallCompare | 无 | 不开发 | D15、D10 |
| 12 张规格板、4 张早期稿、Ops | — | 不实现 | D11 |

---

## 3. 分期与顺序

### 3.1 总表

| 期 | 范围 | 前置 | 验证等级 | 停止节点（AGENTS.md §17） |
| --- | --- | --- | --- | --- |
| ① | M02–M04 已确认文案；M03 价格与取消卡和服务商行；目录契约加字段 | 本文件确认 | 契约与迁移 L3，页面 L2 | 第 1 条（T01）、第 2 条（契约）、第 4 条（M03/M04 阶段完成） |
| ①b | M02 真实 API 与 `/portal`、`/filters` 后端：搜索、分类、排序、距离、筛选、分页由服务端完成，PostGIS 距离排序 | ① | L3 | 第 2 条（契约）、第 3 条（M01/M02 目录阶段，停止） |
| ② | 统一交易模块：收银台、支付回执、标准退款、对账导出；巡检阶段 C 打开付款节点 | C1、C2；阶段 C 还要 C3、C4 | L4 | 第 2 条（契约、状态机、迁移）、第 7 条（资金链路发布候选） |
| ③ | 商品与购物车闭环：类目频道、搜索、商品详情、购物车、地址、确认订单、统一订单台账、商品订单、站内信记录表；商品售后等 A1 确认 | ② 的收银台（目录、详情、购物车可先做） | L3；接收银台和退款的部分 L4 | 第 2 条、阶段 L3 检查点 |
| ④ | 活动报名与到馆：活动频道、详情、参与人、报名、凭证、退改、候补、团队、体验频道、场馆、到馆码、核销工作台、两个仿真模板 | ②、③ 的订单台账 | L3；支付部分 L4 | 第 2 条、阶段 L3 检查点 |
| ⑤ | 需求发布、报价比选、询价、合同、租赁、地图选点、身份切换 | ②、③ | L3；租金支付 L4 | 第 2 条、阶段 L3 检查点 |
| ⑥ | 专业服务目录与详情通用化：四类目录、详情、服务商主页、专业服务频道四类 Tab | ①b、⑤ 的需求发布（四类主按钮） | L3 | 第 2 条、阶段 L3 检查点 |
| ⑦ | 账户与公共：手机号登录、首次启动、游客态、设置系列、帮助、关于、协议、通知、消息 Tab、客服会话、分享、扫一扫 | ③ 起陆续可做；客服会话要 B1 | 登录与注销 L3，其余 L2 | 第 2 条、阶段 L3 检查点 |
| ⑧ | 内容、企业与工作模式：发现真实内容、内容详情、竖屏视频流、直播预告与直播间（依赖概要设计 §14 D20）、专题、企业组织页面、核销记录、首页推荐频道收尾 | ④、⑤ | L3 | 第 2 条、阶段 L3 检查点；全部完成后第 7 条（真机、完整回归、发布候选） |

与建议顺序的差别和理由：

| 调整 | 理由 |
| --- | --- |
| 核销 API 从 ⑧ 提到 ④，工作台页面（StaffVerify、StaffException）等 D8 | 活动和到馆闭环的最后一步是核销（概要 §6.3、§6.5）；④ 的验收路径用受权 API 建立前置状态并保存证据（A2）。核销记录页 StaffRecords 留在 ⑧ |
| 地址管理提到 ③，参与人提到 ④，个人/企业身份切换提到 ⑤ | 分别是确认订单、报名、需求发布的必填项，拆开就没法走通入口路径 |
| M02 真实 API 单列为 ①b，排在 ① 之后、③ 之前 | 要补 `/portal`、`/filters` 后端、服务端搜索筛选分页和 PostGIS 距离排序（概要 §2.2），是 L3，按 §17 第 3 条停止；① 只改文案和一张卡，保持小改动小验证；⑥ 只剩四类目录、详情的通用化 |
| ② 被 C1、C2 阻塞时，先做 ③ 的类目、详情、购物车 | 这三部分不碰钱；确认订单停在“提交订单”之前。交易模块只做到契约与迁移确认，不得用 Mock 渠道冒充闭环 |
| 消息 Tab 放 ⑦，但站内信记录表在 ③ 建 | 支付回执、服务进度、活动提醒都要记站内信（概要 §8.3）；页面可以后做，记录不能补；③ 是第一个产生订单记录的阶段，② 被 C1、C2 阻塞时不应把表挂在 ② |

### 3.2 各期要点

#### ① M02–M04 文案与 M03 价格与取消卡

- 范围：§4 的 T01–T08。
- 入口验收路径：首页 → 顶部频道“专业服务” → 巡检 → 场景格 / 查看全部 → 巡检服务列表（标题、占位词、卡片说明） → 服务详情（价格与取消卡、服务商行） → 预约服务（标题、按钮） → 提交结果（结果卡、底栏）。黄金路径重跑，和阶段 B 的 1 次、6 次巡检路径一起通过。
- 验证等级：契约和迁移 L3；页面改动 L2；纯文案 L1。改 `pages.json` 标题时按 §15 强制升级到 L2 并跑 Android 路径；现在没有 Android 设备，这是与 AGENTS.md §15 的偏差，需用户在 T01 回复中明确接受。catalog 和 booking 都是 `navigationStyle: custom`，该标题只影响 H5 的 document title，不影响 App 原生导航栏。
- 停止节点：T01 后停；T02 契约方案汇报后停；T08 后停，§17 第 3、4 条合并一次验收，等“验收通过”。
- 要改的契约和迁移：`contracts/openapi-inspection-catalog.yaml` 与 `inspection-catalog.examples.json`；迁移 `V010`（`inspection_service` 加取消说明；影响价格因素存详情项）；开发种子 `R__dev_inspection_detail.sql`。
- 需要用户先定的规则：无。`Batch4Spec` 第 9 节里 M04 提交结果的其余差异（页头“预约详情”、状态胶囊和进度随方案发布变化、不写服务商名、期望时间写时段、加巡检项目行、校验错误移到按钮上方）由 T01 列成清单，用户逐项点头的才进 ①，默认不改；其中页头“预约详情”已在工作树落地（`getSubmittedBookingHeaderTitle`），默认保留，用户不同意就回退。

#### ①b M02 真实 API 与 `/portal`、`/filters` 后端

- 范围：M02 接真实 API（画板 InspCatalog 的分类、排序、距离权限、切城、筛选、分页、空态）；`/portal`、`/filters` 后端；搜索、分类、排序、距离、筛选、分页由服务端完成，PostGIS 距离排序；卡片距离排序时多一行距离。
- 入口验收路径：首页 → 频道“专业服务” → 巡检 → 场景格（带 `categoryCode`） → 列表（切分类、排序、距离、切城、筛选、翻页） → 详情。
- 验证等级：L3（OpenAPI、PostGIS 距离排序）。
- 停止节点：契约确认后停；M01/M02 目录阶段完成后停（§17 第 3 条）。
- 要改的契约和迁移：`contracts/openapi-inspection-catalog.yaml` 的 `/portal`、`/filters` 按现有定义实现；排序白名单和距离参数服务端校验。
- 需要用户先定的规则：无。

#### ② 统一交易模块（D2）

- 范围：新服务端模块 `trade`：付款单、支付回执、标准退款、对账导出；App 收银台（画板 Cashier、CashierFirst、CashierFinal、CashierOneFirst、CashierOneFinal、CashierEnroll、CashierVenue、CashierRental 合成一个收银台页，按订单类型显示）、支付结果（PayResult）。巡检阶段 C：打开 `INSPECTION_PAYMENT_NODES_ENABLED`，`AWAITING_PAYMENT` 阶段和筛选，`INS_DOWN_PAYMENT_REQUIRED`，交易模块同进程回写 `paid_at`（修订方案 §4、§9）。
- 入口验收路径：我的订单 → 方案确认 → 确认方案 → 首付收银台 → 支付结果 → 服务履约 → 第 1 次交付与验收 → 本次服务费收银台 → 支付结果 → 我的订单“待付款”“已完成”筛选。重复回执只记一次，金额不符不记已付。
- 验证等级：L4（AGENTS.md §15 资金链路）。
- 停止节点：契约、状态机、迁移方案确认后停；发布候选后停。
- 要改的契约和迁移：新 `contracts/openapi-trade-payment.yaml`；服务流程契约加阶段 C 的枚举和错误码（修订方案 §4 已预留）；`backend/modules/trade/migrations/V001`；巡检回填不需要（开关打开前的服务单仍是 `NONE`）。
- 需要用户先定的规则：C1、C2 开工前；C3、C4 打开开关前；退款规则（商品、报名、到馆按设计假设 1、4、8、20，服务单按 C3）。

#### ③ 商品与购物车闭环

- 范围：§2.2.6 全部（商品售后四页等 A1 确认，未确认前只接 ② 的标准退款）、§2.2.7 的 PdStates、§2.2.10 的 RegionPick、MoreSheet、OrderSearch，首页推荐频道“本地精选”；站内信记录表；把 `features/inspection/presentation/platform/{home,mine}` 迁到对应 feature，M08 只保留巡检记录读取接口。
- 入口验收路径（② 未就绪时）：首页 → 频道“机器人” → 商品 → 规格与配送 → 加入购物车 → 底栏“购物车” → 结算 → 确认订单（选地址） → 提交订单为止。另一条：首页搜索框 → 搜索前置页 → 结果页 → 商品。
- 入口验收路径（② 就绪后）：上述路径接着 → 收银台 → 支付结果 → 我的 → 我的订单“商品” → 商品订单详情 → 标准退款；售后申请、售后进度只在 A1 确认后加入。
- 验证等级：L3；接收银台和退款的部分 L4。
- 停止节点：契约与迁移确认后停；阶段完成后停。
- 要改的契约和迁移：新 `openapi-mall-catalog.yaml`、`openapi-trade-order.yaml`、`openapi-account.yaml`（地址）；新模块 `catalog`（商家、商品、SKU、供给、城市授权）和 `trade` 的订单表；M08 的巡检记录读取接到统一台账。
- 需要用户先定的规则：D5 首批商品与经营主体（没有就用标记环境的开发种子）；B6 状态名；A1 商品售后是否修订 AGENTS.md §3；售后由平台客服协助、不裁决（设计假设 20）。

#### ④ 活动报名与到馆

- 范围：§2.2.2、§2.2.3 全部，首页推荐频道“近期活动”“附近场馆”“今天去体验”。
- 入口验收路径：首页 → 频道“活动” → 活动详情 → 选场次 → 选参与人（含添加子女和监护人确认） → 报名收银台 → 凭证 → 用受权核销 API 建立“已签到”前置状态并保存证据（A2，工作台页面等 D8） → 凭证显示已签到 → 我的报名 → 结果记录。到馆：首页 → 频道“体验” → 场馆详情 → 场次 → 到馆预约确认 → 收银台 → 核销码 → 受权核销 API 建立前置状态并保存证据 → 我的体验。线上：体验中心 → 仿真任务 → 体验结果 → 预约线下。
- 验证等级：L3；支付部分 L4；核销 API 的归属校验按 L3 的鉴权要求。
- 停止节点：契约与迁移确认后停；阶段完成后停。
- 要改的契约和迁移：新 `openapi-activity.yaml`、`openapi-venue.yaml`、`openapi-account.yaml`（参与人）、`openapi-staff.yaml`；新模块 `activity`、`venue`；identity 加核销员角色与权限码。
- 需要用户先定的规则：D8（App 内只做扫码核销，工作台页面等此项和 AGENTS.md §3 修订）、D12；D7 已作为默认规则按画板实现，不再问；退改时限“[协议约定时间]”的具体值；监护人确认文案（法务）。

#### ⑤ 需求发布、报价比选、合同、租赁

- 范围：§2.2.4、§2.2.7 的租赁部分、身份切换（“我的”账户卡读 identity 现有组织成员关系）。
- 入口验收路径：首页 → 频道“专业服务” → 发布需求（选类型、交付物随类型变、仅邀请指定服务商） → 需求与报价列表 → 报价比选 → 对比明细 → 确认选择 → 方案确认（M05）。租赁：首页 → 频道“租赁” → 租赁商品详情 → 租赁询价单（添加设备） → 报价单 → 确认合同并支付 → 租赁合同订单 → 交接照片。
- 验证等级：L3；租金支付 L4。
- 停止节点：契约与迁移确认后停；阶段完成后停。
- 要改的契约和迁移：新 `openapi-service-demand.yaml`、`openapi-rental.yaml`；新模块 `demand`、`rental`；巡检方案表不改（比选是新模型，确认后生成的方案仍走修订方案的 `ServicePlan`）。
- 需要用户先定的规则：B3（默认走需求发布）；设计假设 10、11、16、17；押金不在线收取；N3。

#### ⑥ 专业服务目录与详情通用化

- 范围：§2.2.5 全部（四类目录、详情、服务商主页、专业服务频道四类 Tab）；M02 全品类分类和城市未开通态。真实 API、`/portal`、`/filters` 后端已在 ①b 完成。
- 入口验收路径：专业服务频道 → 航拍 Tab → 搜索 → 四类目录 → 航拍详情 → 发布需求（⑤）。
- 验证等级：L3（OpenAPI、`pages.json`）。
- 停止节点：契约确认后停；阶段完成后停。
- 要改的契约和迁移：目录契约按全品类核对；新服务类型的目录对象（是否扩展 `inspection_category`、还是建通用服务目录）在节点 2 定；详情页照 M03 还是改 M03 同时定。
- 需要用户先定的规则：B3；四类详情的模板选择。

#### ⑦ 账户与公共

- 范围：§2.2.8 除已提前的地址、参与人、身份切换和 OrgInvite。
- 入口验收路径：首次启动 → 授权 → 游客“我的” → 登录（手机号） → 回到原目标；我的 → 设置 → 更换手机号 / 登录设备 / 个人信息导出 / 权限 / 注销申请；底栏“消息” → 通知详情；商品详情“客服” → 会话；分享 → 复制链接；扫一扫 → 商品码落地。
- 验证等级：登录、注销、个人信息导出 L3；其余 L2。
- 停止节点：identity 契约确认后停；阶段完成后停。
- 要改的契约和迁移：`openapi-identity-auth.yaml`（手机号登录、验证码、设备、注销）；identity 迁移；站内信读取契约。
- 需要用户先定的规则：B1；短信渠道；协议正文；设计假设 18–23。

#### ⑧ 内容、企业与工作模式

- 范围：§2.2.9 全部，首页推荐频道 Banner 与城市资讯，OrgInvite。
- 入口验收路径：底栏“发现” → 视频 / 图文 → 关联商品或活动（带来源标识） → 下单或报名；发现专题卡 → 专题页；我的 → 企业组织 → 邀请成员 → 对方“消息” → 确认加入；核销工作台 → 全部记录。
- 验证等级：L3。
- 停止节点：契约确认后停；阶段完成后停；随后进入 §17 第 7 条，补 Android、iOS 真机和完整回归。
- 要改的契约和迁移：新 `openapi-content.yaml`、`openapi-identity-organization.yaml`；新模块 `content`；identity 加邀请表。
- 需要用户先定的规则：N3；设计假设 24、25；内容素材授权（概要 §8.3）。D13 已作为默认规则按画板 HomeFull 实现，不再问。

---

## 4. 第一个里程碑的任务清单（①）

共 8 个任务。每个任务完成后按 AGENTS.md §18 汇报；T01、T02、T08 是停止点，T08 把 §17 第 3、4 条合并一次验收。

| 任务 | 做什么 | 文件范围 | 验收标准 | 验证等级 |
| --- | --- | --- | --- | --- |
| T01 项目盘点与冲突检查 | 按 AGENTS.md §0 读完规范、契约、本文件；核对 §2.1 的现状（含工作树未提交改动）；跑一次后端测试、App 单测、类型检查、黄金路径取基线；把 `Batch4Spec` 第 9 节里 M04 提交结果的其余差异列成逐项清单交用户点头（含已落地的 `getSubmittedBookingHeaderTitle` 页头“预约详情”，默认保留，不同意就回退）；请用户明确接受“无 Android 设备”与 §15 的偏差；把 `Batch4Spec` 第 9、11 节和《参照》§6 的相关段落摘录进 `docs/redesign/04-原型确认摘录.md`；列出本期不碰的区域 | 只读，不改代码；产出汇报和 `docs/redesign/04-原型确认摘录.md` | 汇报含：基线结果（通过数）、冲突清单、逐项清单、本期不改清单；摘录文件已建；用户回复后才进 T02 | L1（§17 第 1 条，停止） |
| T02 目录契约加字段与迁移方案 | 契约：`InspectionServiceCard` 加 `priceNote`（起步价含什么；按项目报价时服务端返回“方案确认后定价”）；`InspectionServiceDetail` 的 `priceNote` 改作计价方式文案，加 `priceFactors`（string[]）、`cancellationNote`（string 或 null）、`serviceCities`（`CitySummary[]`，来自 `inspection_service_city`）；示例同步。迁移方案：`V010` 给 `inspection_service` 加 `cancellation_note`，影响价格因素存 `inspection_service_detail_item` 新类型；存量为空 | `contracts/openapi-inspection-catalog.yaml`、`contracts/inspection-catalog.examples.json`；迁移草案写在汇报里 | 契约测试（`InspectionServiceFlowOpenApiContractTest` 同类写法）通过；字段都可空，旧数据不报错；汇报列出字段、表、索引和回填 | L3（§17 第 2 条，停止等确认） |
| T03 服务端接线 | 迁移 `V010`；读服务和 mapper 返回新字段；开发种子补电力线路巡检的计价、因素、取消说明、可服务城市，并标记环境 | `backend/modules/inspection/migrations/V010__*.sql`；`PostgresInspectionServiceCatalogReadStore`、`InspectionServiceCatalogReadService`、`InspectionServiceCatalogMapper`；`R__dev_inspection_detail.sql` | 空库和有存量两种迁移都通过；`InspectionServiceCatalogControllerTest` 加新字段断言先失败后通过；没有数据时字段为 null，不编造文案 | L3 |
| T04 M02 标题、占位词、卡片说明 | 标题“电力巡检”改“巡检服务”；核对工作树已落地的占位词“搜索巡检服务、场景或关键词”（AGENTS.md §8）并补测；卡片价格下一行 12px 灰字显示 `priceNote`，没有就不显示；距离行随 ①b 的距离排序一起做，本任务不加；`pages.json` 的 catalog 标题跟着改（catalog 为 `navigationStyle: custom`，只影响 H5 title；与 AGENTS.md §15 的偏差（无 Android 设备）需用户在 T01 回复中明确接受） | `presentation/catalog/components/CatalogHeader.vue`、`InspectionServiceCard.vue`、`model/inspection-catalog.ts`（DTO 映射）、`apps/mobile/src/pages.json` | 组件测试：有说明显示、无说明不占高、按项目报价显示服务端文案；占位词断言已改为新文案且通过；375 视口和画板 InspCatalog 比对，320 或 430 任一边界不溢出；`catalog-route-city.spec.ts` 通过 | L2（改 `pages.json`，Android 路径按 T01 接受的偏差处理） |
| T05 M03 价格与取消卡与服务商行 | 头图信息卡下加“价格与取消”卡：计价方式、影响价格、取消说明三行；原价格下那句并进卡里；服务商行改成绿色“资质已审核”标签加灰字“可服务城市 东营”；三行全空时整卡隐藏；不加最早可约 | `presentation/detail/components/`（新建 `ServicePricingCard.vue`，改 `ServiceDetailHero.vue`）、`domain/inspection-service-detail.ts`、`data/detail` 的 mapper、`detail/README.md` | 单测：DTO 映射、空值隐藏、按项目报价文案；`inspection-detail.test.ts`、`service-detail-recovery.spec.ts` 通过；375 视口和画板 InspDetail 比对，“绝缘子及金具巡检”不换行溢出（`Batch4Spec` 第 10 节） | L2 |
| T06 M04 文案 | 核对工作树已落地的改动并补测：预约页标题“预约服务”、按钮“提交预约”；结果页提交成功帧标题“需求已提交”，说明“等待服务商确认，方案确认前不收费”，灰字“进度可在“我的订单”查看”；底栏“联系服务商”；已取消帧、取消弹层和“返回订单”不改（`Batch5Spec` 6.1）；T01 用户点头的项一并做，`getSubmittedBookingHeaderTitle` 按 T01 结论保留或回退 | `presentation/booking/pages/InspectionBookingPage.vue`、`InspectionBookingSubmittedPage.vue`、`tests/booking-flow.test.ts`；若 `pages.json` 标题也改，列入范围（booking 为 `navigationStyle: custom`，只影响 H5 title） | `booking-flow.test.ts` 的文案断言已改为新文案且通过；`booking-back.spec.ts`、`booking-recovery.spec.ts` 通过；375 视口和画板 InspBooking、InspBooked 比对 | L2（改 `pages.json` 时按 T01 接受的偏差处理） |
| T07 开发数据与夹具的“AI 缺陷识别” | 改成“缺陷标注”（AGENTS.md §3 不做 AI 缺陷识别） | `R__dev_inspection_detail.sql`、`data/fixtures/inspection-portal.fixture.ts`、`inspection-service-detail.fixture.ts`、`presentation/catalog/fixtures/power-inspection-catalog.fixture.ts`、`model/inspection-catalog.test.ts`、`contracts/inspection-catalog.examples.json` 及其契约示例测试 | `apps/`、`backend/`、`contracts/` 内的运行数据与夹具搜不到“AI 缺陷识别”“AI缺陷识别”；相关单测和契约示例测试通过 | L1 |
| T08 回归、视觉比对与验收记录 | 后端全量测试、App 单测、类型检查、H5 生产构建和公开构建防泄漏、`uni build -p app`；黄金路径 1 次和 6 次巡检重跑；受影响页面 320/375/430 截图和差异说明；`git diff --check` 和白名单检查；写 `docs/19-app-m02-m04-copy-acceptance.md`；按 §18 汇报 | `docs/19-*.md`；`.planning/` 下的截图（git 忽略） | 全部通过；差异只有 §16 允许项；Android 真机列为缺口 | L3（§17 第 3、4 条合并一次验收，停止等“验收通过”） |

本期明确不改：M01；M02 的查询范围、排序项和分页方式；M03 的区块顺序；M04 的表单初值、时段默认、附件上传时机、校验规则；M05–M08；登录与权限；`features/platform`。

---

## 5. 活动任务计划

1. 本文件 §4 是当前活动任务计划（AGENTS.md §0）。修订方案 §12 的第 1–5 条已完成；第 6 条（阶段 C）并入本文件 ②。
2. M01–M04 的“不变”约束（修订方案 §1；《参照》§6.3）已被用户 2026-11-05 的确认替代，但只限画布上确认的这几处：

| 页面 | 可以改的 | 出处 |
| --- | --- | --- |
| M02 | 标题“巡检服务”；占位词“搜索巡检服务、场景或关键词”；卡片价格下一行起步价说明，按项目报价写“方案确认后定价” | `Batch5Spec` 第 6 节 InspCatalog；`Batch4Spec` 第 9 节占位词行 |
| M03 | “价格与取消”卡（计价方式、影响价格、取消说明）；服务商行“资质已审核”加“可服务城市”；能力标签“AI 缺陷识别”改“缺陷标注” | `Batch5Spec` 第 6 节 InspDetail；`Batch4Spec` 第 9 节 |
| M04 | 标题“预约服务”、按钮“提交预约”；提交成功帧结果卡标题、说明和灰字；底栏“联系服务商” | `Batch5Spec` 第 6 节 InspBooking、InspBooked；`Batch4Spec` 第 9 节提交结果页行 |

   其余（M01 全部；M02 的查询、排序、分页；M03 的最早可约、套餐卡、资质分区；M04 的时段、分步、初值、附件时机；`Batch4Spec` 第 9 节里写“以代码为准”的行）仍按“不变”处理。要改必须先经 T01 清单逐项点头，或另开任务。
3. 工作方式：按 T01 → T08 逐项做，T01、T02、T08 停下来等回复；Bug 按 AGENTS.md §14 只有诊断权限；验证按 §15 选等级；每次汇报按 §18。未经用户要求不提交、不推送。
4. 分支：实现在 `codex/app-prototype-impl`；产品线是 `codex/inspection-visits`（`0dcb1fa`）。每期验收通过后怎么合并，由用户定。
5. 观察项（不在本期处理）：AGENTS.md V2.2 的 §2 只列巡检 M01–M08，新模块没有正式页面编号（概要 §14 D4）；代码按稳定路由和契约识别模块（AGENTS.md §2 末段）。②–⑧ 开工前要同步 AGENTS.md 的模块清单、E2E 路径和完成定义。

---

## 6. 2026-11-14 修订版 UI 对本计划的影响（V0.2 草案，待用户确认）

依据：概要设计 §14 D21。用户 2026-11-16 指定按修订版原型开发；视觉与交互依据改为 `.planning/prototype/v2-20261114/`（224 个 HTML，2026-11-16 逐页打开检查无脚本错误、无坏图、无断链）。画板源文件没有跟着改。

### 6.1 进度（2026-11-16 核对）

| 期 | 状态 |
| --- | --- |
| 巡检闭环 M01–M08、方案 C 壳层、多次巡检阶段 A、B | 已在产品线 `codex/inspection-visits`（`0dcb1fa`），真实 API；Android 真机未做 |
| ① | T01–T08 已做完，2026-11-16 单独提交到本分支；仍未收到“验收通过” |
| 首页改版（修订版 UI 第一步） | **用户 2026-11-16 验收通过**，同日单独提交到本分支。范围：频道行改为 推荐 / 专业服务 / 赛事研学 / 体验 / 商城；首页四张轮播；金刚位“专业服务”换成“趣玩挑战”；趣玩入口页和两关游戏（`features/play`，路由 `/pages/play/index`、`/pages/play/game`）；轮播、大卡、金刚位和趣玩页用浅色多彩样式。证据：类型检查通过；App 单测 653 条；本地后端启动后 E2E 140 条（含 `home-revamp.spec.ts` 12 条、`navigation.spec.ts` 27 条；2026-11-16 曾误记为 134 条）和公开版 6 条全过；公开版构建防泄漏检查通过；截图在 `.planning/home-revamp/visual/`（320/375/430）。没做：Android、iOS 真机。验收时的状态：轮播和趣玩入口只在预览构建显示；浅色样式没有用到 M01–M08 和底栏 |
| ①b–⑧ | 未开工。商城、赛事研学、体验、发现、消息、购物车在代码里只有占位页或开发夹具 |

### 6.2 修订内容落到哪一期

| 修订内容 | 落在哪期 | 说明 |
| --- | --- | --- |
| 浅色多彩主题 | 单列，先于 ③–⑧ | 改全局设计令牌，按 AGENTS.md §15 至少 L2 并回归所有使用方；是否覆盖 M01–M08 待确认（D21 ①） |
| 频道改名“赛事研学”、金刚位换“趣玩挑战” | 首页壳层，随首页改版 | 金刚位入口和 E2E 用例要一起改（D21 ②） |
| 首页四张轮播 | 首页壳层 | 轮播框架可先做；四张的落地页分别要等 ④ 和趣玩 |
| Games、GamePlay（趣玩挑战两关） | ④ 体验 | 单机，不需要服务端；成绩是否进“我的体验”待确认（D21 ③） |
| EventSignup（选场、年龄限制、按人数计价、候补、团队入口） | ④ | 收费场次要等 ② 的收银台 |
| CourseTrial（课程试听） | ④ | D15 未定前只做图文试听 |
| QuoteConfirm（按 quote + version 确认报价） | ⑤ | 只传稳定 ID，报价事实从服务端读（AGENTS.md §5） |
| 三处串页修复（仿真、试听、报价） | ④、⑤ | 对应路由都只传稳定 ID |

### 6.3 顺序

② 仍等 C1、C2（§1.2）。首页改版已做完并验收（§6.1）。不碰钱、接下来能做的是：①b，③ 的类目、详情、购物车，以及 ④ 里不收费的部分（赛事研学频道与详情、体验频道）。从哪一项开始由用户定。① 仍未验收。①、首页改版、赛事研学只读频道已于 2026-11-16 按用户要求分三次提交到本分支（没有推送、没有合并到产品线）。

---

## 7. ④a 赛事研学频道只读（草案，2026-11-16，待用户确认后开工）

用户 2026-11-16 选定下一步做“赛事研学”。④ 整期太大，而且收费部分被 ② 挡着，所以拆成三步，这里只定第一步：

| 步 | 范围 | 前置 |
| --- | --- | --- |
| ④a（本节） | 赛事研学频道列表、活动详情、选场次。只读，不写报名 | 本节确认 |
| ④b | 参与人、免费活动报名、凭证、我的报名、候补、退改里不涉及钱的部分 | ④a；登录；监护人确认文案 |
| ④c | 收费报名、团队报名付款、退款 | ② 的收银台（C1、C2） |

体验频道（体验中心、场馆详情、仿真任务）另列一步，排在 ④a 之后，方案届时单独确认。

### 7.1 做完能看到什么

依据修订版原型的 HomeAct、ActDetail、ActStudyRobot、ActCamp、ActTraining、ActOpenDay（五个详情是同一个模板）。

- 首页点“赛事研学”频道：类型页签（全部 / 赛事 / 培训 / 研学 / 体验日）、日期条、筛选（报名中、免费）、活动卡片（类型、标题、时间和地点、价格、名额或状态）。下拉刷新，滚到底加载下一页，加载中、空、出错、重试都有。
- 首页轮播“查看赛事”“查看研学”、金刚位“赛事报名”“研学体验”进到这个频道。
- 点卡片进活动详情：封面、类型、标题、价格、适合年龄、各场次（时间、余位或“已满可候补 / 已截止 / 已结束”、签到和报名截止时间）、主办和承办、地点与导航、参与条件、名额说明、退改规则。
- 选场次后主按钮变成“立即报名”或“加入候补”；报名截止、活动结束时按钮停用。这一步点下去只提示“报名功能即将开放”，报名在 ④b、④c 做。

### 7.2 这一步不做的

| 原型上有 | 这一步怎么处理 | 原因 |
| --- | --- | --- |
| 报名、候补、团队报名、凭证、我的报名 | 不做；按钮走类型化网关给提示 | ④b、④c |
| “距你 2.3km” | 不显示距离；地址和导航保留 | 距离要定位权限和服务端空间计算，随 ④b 或单列 |
| 主办方、承办场馆点进去的主页 | 只显示名称，不可点 | 商家主页在 ⑥，场馆页在体验频道那一步 |
| “活动使用器材”商品卡、“在线试试编程任务” | 整块不显示 | 商城在 ③，体验在后一步 |
| 咨询、收藏 | 咨询走网关提示；收藏不显示 | 客服在 ⑦；收藏一期做不做未定（D10） |
| 筛选项“亲子”“8–15 岁”“线上” | 不显示 | 原型里这三项本来也不起作用，数据上没有对应字段 |
| 频道对公开版开放 | 不开放，仍只在预览构建显示 | 只能看不能报，不算闭环（AGENTS.md §3） |

### 7.3 规则（建议值，确认后按此实现）

1. 活动四类：赛事、培训、研学、体验日。价格三种：按人收费、免费、询价。
2. 列表只列还有未开始场次的活动，按最近一场的开始时间从早到晚排；全部场次都开始了的活动不进列表，但详情链接仍能打开。
3. 状态由服务端按容量、报名截止时间和开始时间算，客户端不自己判断：报名中、已满可候补、已满、报名截止、已结束。
4. 名额公开显示“余 N”。
5. 日期条有活动的日子带圆点，点某天只看那天有场次的活动（原型里点日期不筛选，这里改成筛选）。
6. 参与条件、名额说明、退改规则是主办方填的文字，原样显示。原型里的“[协议约定时间]”是占位符，开发数据写成“活动开始 48 小时前可免费取消”作示例；真正的退改时限在 ④b 定。
7. 时间一律按北京时间显示。

### 7.4 接口（契约草案 `contracts/openapi-activity.yaml` 0.1.0）

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/activities?cityCode&type&date&enrolling&free&page&size` | 频道列表，服务端分页（每页默认 20，最多 50） |
| `GET /api/v1/activities/session-dates?cityCode&type&from&to` | 日期条上哪几天有场次 |
| `GET /api/v1/activities/{activityId}?cityCode` | 活动详情，含全部场次 |

三个都是公开只读接口，不用登录。沿用现有约定：直接返回对象不套外壳、分页字段 `page / size / total / hasMore / items`、错误体 `ApiError`、金额用分、图片只给 HTTPS 地址、枚举返回稳定编码。不存在或不属于该城市返回 404 `COMMON_NOT_FOUND`，参数不对返回 400 `COMMON_INVALID_ARGUMENT`。

### 7.5 数据表（新模块 `backend/modules/activity`，迁移 `V001__activity_catalog.sql`）

```sql
CREATE TABLE activity (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organizer_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    co_organizer_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    type varchar(16) NOT NULL CHECK (type IN ('CONTEST', 'TRAINING', 'STUDY_TOUR', 'OPEN_DAY')),
    title varchar(160) NOT NULL,
    subtitle varchar(200),
    cover_url varchar(500),
    city_code varchar(16) NOT NULL,
    venue_name varchar(120) NOT NULL,
    venue_address varchar(240) NOT NULL,
    venue_location geography(Point, 4326),
    schedule_note varchar(120),
    price_mode varchar(16) NOT NULL CHECK (price_mode IN ('PER_PERSON', 'FREE', 'INQUIRY')),
    price_amount_minor bigint CHECK (price_amount_minor >= 0),
    currency char(3) NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
    min_age smallint CHECK (min_age >= 0),
    max_age smallint CHECK (max_age >= 0),
    eligibility_note varchar(300),
    seat_note varchar(300),
    refund_note varchar(500),
    waitlist_allowed boolean NOT NULL DEFAULT false,
    team_enrollment_allowed boolean NOT NULL DEFAULT false,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE', 'DELETED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((price_mode = 'PER_PERSON') = (price_amount_minor IS NOT NULL)),
    CHECK (min_age IS NULL OR max_age IS NULL OR min_age <= max_age)
);
CREATE INDEX idx_activity_city_published ON activity (city_code, status, type, id);

CREATE TABLE activity_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    activity_id uuid NOT NULL REFERENCES activity(id) ON DELETE RESTRICT,
    label varchar(60),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz,
    check_in_opens_at timestamptz,
    enrollment_closes_at timestamptz NOT NULL,
    capacity integer NOT NULL CHECK (capacity > 0),
    enrolled_count integer NOT NULL DEFAULT 0 CHECK (enrolled_count >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (enrolled_count <= capacity),
    CHECK (enrollment_closes_at <= starts_at),
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);
CREATE INDEX idx_activity_session_upcoming ON activity_session (activity_id, status, starts_at);
```

- 一个活动只属于一个城市（活动在一个地点办），所以不另建“活动 × 城市”表。
- `enrolled_count` 这一步只读；④b 的报名事务在同一行上加锁增减，并用 `version` 做并发保护。
- 迁移里不放数据。开发数据放 `backend/bootstrap/src/main/resources/db/development/activity/R__dev_activity_catalog.sql`，只在 dev 环境加载：原型里的五个活动、各三个场次，相对当前日期排期，保证总有报名中、已满可候补、已截止几种状态可看。
- 正式数据怎么录入没有着落：Web 管理后台不在一期（AGENTS.md §3、D11），上线前要么先定一个录入办法，要么由技术人员导入。这是观察项，不在本步解决。

### 7.6 任务清单

| 任务 | 做什么 | 验证等级 |
| --- | --- | --- |
| A01 | 契约定稿：`openapi-activity.yaml` 1.0.0、`activity.examples.json`；AGENTS.md §2 登记 `activity` 模块，§3 去掉对它的限制 | L3，停下确认（本节即此节点） |
| A02 | 服务端：新模块 `activity`、迁移 V001、Flyway 注册、读服务（状态计算用可注入的时钟）、mapper（排序和筛选全部绑定参数）、控制器、公开 GET 白名单、404 映射、开发数据；单测、mapper SQL 测试、迁移测试、契约测试、安全测试 | L3 |
| A03 | App 公共请求：把公开请求从 `features/inspection` 提到 `shared/http`，巡检改为从那里引用，行为不变 | L3，回归全套 E2E |
| A04 | App `features/activity`：领域类型、API 与映射、列表加载（只认最后一次请求）、频道内容组件接进首页（替换“频道建设中”）、类型页签、日期条、筛选、分页、各种状态 | L2 |
| A05 | App 活动详情页 `/pages/activity/detail?activityId=…`：场次选择、主按钮状态、导航、未做入口走网关 | L2；改路由表，Android 路径没有设备，沿用已接受的偏差 |
| A06 | 回归与验收记录：后端全量测试、App 单测、类型检查、E2E（新增赛事研学用例，含从首页轮播和金刚位进入的两条路径）、公开版构建与防泄漏、320/375/430 截图 | L3，停下等“验收通过” |

本步明确不改：巡检 M01–M08 的页面与接口；底栏；首页推荐频道已验收的轮播、大卡、金刚位；`features/play`。

### 7.7 与规范的出入，需要一并点头

1. AGENTS.md §3 写“其他一级或二级业务模块”不开发，§2 只登记了巡检。新增 `activity` 模块要在 A01 里同步改这两处（用户 2026-11-16 已说“做赛事研学”，这里是把规范改成与之一致）。
2. AGENTS.md §6 建议控制器放在模块的 `interfaces/app-api`，现有代码的控制器都在 `backend/bootstrap` 里、与读服务同包。新模块沿用现有代码的做法，不另起一套。
3. 原型的报名页 EventSignup 是“报名意向演示”，不产生真实报名。App 不照做演示版，报名留到 ④b 做成真的。

### 7.8 完成情况（**用户 2026-11-16 验收通过**）

用户 2026-11-16 回复“按方案开工”，A01–A06 做完后同日回复“验收通过”，并要求把三批改动分三次提交。验收时用户已看过下面“与 §7 方案的出入”七条。

| 项 | 结果 |
| --- | --- |
| 契约 | `contracts/openapi-activity.yaml` 1.0.0；AGENTS.md V2.7 登记 M09 `activity` |
| 服务端 | 新模块 `backend/modules/activity`，迁移 `V001__activity_catalog.sql`，三个公开只读接口；dev 示例数据 5 个活动 16 个场次 |
| 后端测试 | 全量 304 条通过（新增 32 条：读服务 12、SQL 与迁移 5、控制器 6、契约对照 4、开发数据 3、Flyway 1、安全 1） |
| App | `features/activity`（列表频道接进首页、详情页 `/pages/activity/detail`），`shared/http/public-api-request.ts` |
| App 单测 | 685 条通过（新增 32 条），类型检查通过 |
| E2E | 开发版 155 条通过（新增 `activity.spec.ts` 15 条，含首页轮播和金刚位两条入口、筛选、日期条、分页、出错重试、详情选场次、不存在的活动）；公开版 6 条通过 |
| 公开版构建 | 构建通过，防泄漏检查通过；赛事研学频道在公开版不显示 |
| 截图 | `.planning/activity/visual/` 320 / 375 / 430 的频道页和详情页，无横向溢出 |
| 真机 | Android、iOS 没测。改了路由表，按 AGENTS.md §15 应跑 Android 路径，没有设备 |

与 §7 方案的出入：

1. A03 没有把请求代码搬走。`shared/http/public-api-request.ts` 只是统一出口，实现（地址规则、局域网发现、失败重试一次、错误类型）仍在巡检的 HTTP 客户端里，和 `shared/weather` 现有做法一致。搬动会牵动巡检八个文件和一组测试，这一步不值得冒这个险。
2. 没做下拉刷新。首页整页滚动由各频道共用，打开下拉会改变已验收的巡检频道的行为；改为每次回到频道重新读取列表。
3. 没建 `activity.examples.json`。契约测试改为把每个返回对象的字段、三个枚举的取值和契约逐项对照，改了一边忘了另一边会失败。
4. 卡片显示的不是“最近一场”，而是“还能报名的最近一场”，都报不了时才显示最近一场；否则会出现“显示已满场次却写着报名中”。契约说明已同步。
5. 详情页“导航”把服务端的 WGS84 坐标换算成国内地图用的 GCJ-02 再打开地图；打不开时复制地址。换算有单测，真机上没试过。
6. 示例活动没有封面图（没有有使用权的图片），列表和详情用按类型区分的色块代替。
7. E2E 的 ACT-002（金刚位“赛事报名”）用的是派发点击，因为该入口在金刚区第二页，自动化里滑不过去。

观察项（记录，不在本步处理）：

- 公开版能否去掉首页的示例内容，取决于预览开关和首页是否被打进同一个代码块（压缩器只在同一个代码块里做常量折叠，打包器不跨文件折叠）。本步一度因为详情页引入共享开关而让示例文字“无人机足球赛”进了公开包，被 `verify-public-production-preview.mjs` 拦下。处理办法是活动模块用自己的等价开关 `activity-preview.ts`，并用单测保证表达式与共享开关一致、活动模块不引入共享开关文件。隐患本身还在：以后别的页面引入共享开关，可能再次触发。建议另开任务把示例内容改成按构建模式替换模块，不再依赖压缩器。
- `GET /api/v1/inspection/services` 缺少 `cityCode` 时返回 500 而不是 400（通用异常处理没有覆盖缺参）。活动接口已避开，巡检接口没有改。
- 正式活动数据的录入办法仍没有着落（§7.5）。

---

## 8. ④d 体验频道与场馆只读（2026-11-16，按用户“一直推进”的授权直接开工）

用户 2026-11-16 在验收 ④a 后说“一直推进直到你推进不下去”。按 AGENTS.md §17 的连续推进条款，这一步不等方案确认，做完保存证据后继续；需要用户拍板的事项不替用户定，遇到就停。

### 8.1 范围

依据修订版原型 ExpCenter、VenueDetail、VenueOutdoor、VenueDevices。

- 首页“体验”频道：“线上体验 / 线下体验”切换。线上按形式筛选（全部 / 虚拟仿真 / 录播演示 / 软件试用 / 课程试听），列出体验项目；线下列出本城场馆。频道里保留“趣玩挑战”入口。
- 场馆详情：开放时间、地址与导航、电话、可预约项目（时长、年龄、每场人数、限制说明、价格）、按日期看场次余位、场馆信息、馆内设备入口。
- 馆内设备：按机器人 / 无人机 / 配件筛选，标明“工作人员陪同试用”或“只展示，不试用”。
- 首页大卡“去体验”、轮播第四张、金刚位“线下场馆”进到这个频道。

### 8.2 这一步不做的

| 原型上有 | 处理 | 原因 |
| --- | --- | --- |
| 到馆预约、支付、核销码、改期取消 | “预约到馆”走类型化网关提示 | 每个项目都收费（示例 ¥39），要等 ② 的收银台 |
| 虚拟仿真任务（ExpSim、ExpResult） | 列表里显示，点开提示“即将开放” | 原型只画了积木列表和一份固定的示例结果，没有关卡和判定规则；概要设计 §8.3 又写仿真由 H5 容器加载。做成真的要先定：自己设计关卡，还是接厂家提供的仿真内容。**需要用户决定** |
| 录播演示、软件试用、课程试听 | 同上，提示“即将开放” | 分别依赖发现页内容（⑧）、隔离试用环境、课程规则（D15） |
| 我的体验记录 | 不显示入口 | 记录来自仿真和到馆预约，这两样都还没有 |
| 设备卡上的价格、在售商家、商品链接 | 不显示 | 商城在 ③ |
| 频道对公开版开放 | 不开放 | 只能看不能约，不算闭环 |

### 8.3 规则

1. 场次状态由服务端算：可约（未开始且有余位）、已满、已结束。这一步不设“预约截止时间”，到开始前都算可约；真正的截止规则随到馆预约一起定。
2. 场馆卡片上的“今日 N 场可约”是当天（北京时间）还可约的场次数，为 0 时不显示。
3. 线上体验项目不分城市，全国可见；场馆按城市。
4. 开放时间、限制说明、到馆须知等是场馆填的文字，原样显示。原型里标了“示例”的入口、检票、迟到规则，开发数据照录并保留“示例”二字。

### 8.4 接口与数据表

契约 `contracts/openapi-experience.yaml`，新模块 `backend/modules/experience`，迁移 `V001__experience_catalog.sql`：

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/experiences?type&page&size` | 线上体验项目 |
| `GET /api/v1/venues?cityCode&page&size` | 本城场馆 |
| `GET /api/v1/venues/{venueId}?cityCode` | 场馆详情，含项目和场馆信息 |
| `GET /api/v1/venues/{venueId}/sessions?cityCode&projectId&date` | 某项目某天的场次 |
| `GET /api/v1/venues/{venueId}/devices?cityCode&category&page&size` | 馆内设备 |

表：`experience_item`、`venue`、`venue_info_item`、`venue_project`、`venue_session`、`venue_device`。约定同 ④a（uuid 主键、状态、乐观锁、创建更新时间、金额用分、城市用 `city_code`、坐标用 `geography(Point, 4326)`、迁移里不放数据、开发数据只在 dev 环境加载）。

验证等级 L3：后端全量测试、App 单测、类型检查、全套 E2E、公开版构建与防泄漏、320/375/430 截图。

### 8.5 完成情况（2026-11-16，未提交，等用户看过）

| 项 | 结果 |
| --- | --- |
| 契约与规范 | `contracts/openapi-experience.yaml` 1.0.0；AGENTS.md V2.8 登记 M10 `experience` |
| 服务端 | 新模块 `backend/modules/experience`，迁移 `V001__experience_catalog.sql`（6 张表），5 个公开只读接口；dev 示例数据：5 个线上体验项目、2 个场馆、6 个项目、一周场次、6 件设备 |
| 后端测试 | 全量 332 条通过（本步新增 28 条） |
| App | `features/experience`（体验频道接进首页、场馆详情 `/pages/venue/detail`、馆内设备 `/pages/venue/devices`）；公共工具 `shared/time/beijing-time.ts`、`shared/map/map-coordinate.ts`、`shared/list/paged-list-loader.ts` |
| App 单测 | 703 条通过（本步新增 18 条），类型检查通过 |
| E2E | 开发版 168 条通过（新增 `experience.spec.ts` 13 条）；公开版 6 条通过 |
| 公开版构建 | 构建通过，防泄漏检查通过；体验频道在公开版不显示 |
| 截图 | `.planning/experience/visual/` 320 / 375 / 430 |
| 真机 | Android、iOS 没测。“导航”“电话”两个按钮调用系统能力，只在浏览器里验证过不报错 |

顺带的改动：把北京时间格式化和坐标换算从活动模块提到 `shared/`，活动模块改为引用公共实现，它的单测和 E2E 照旧通过。

与原型的出入：

1. 线上体验五项都能看到，但点开只提示“即将开放”（原因见 §8.2）。
2. 场馆和设备没有图片（没有有使用权的图片），用色块代替。
3. 场馆详情的日期条固定显示从今天起 7 天，没有做“更多日期”的日历。
4. 设备卡不显示价格、在售商家，也不能点进商品。
5. 原型里黄河口营地的卡片点进去是体验馆的详情（原型自身的错），App 里各进各的。

推进到这里的阻塞与待定（需要用户决定，未替用户定）：

- 仿真任务怎么做：自己设计关卡和判定规则，还是接厂家提供的仿真内容（§8.2）。
- 免费活动报名（④b）：未成年人信息、监护人确认文案、免费取消时限。
- 收费的一切（报名、到馆预约、商城下单）：支付渠道 C1、收款主体 C2。
- 正式数据怎么录入：活动、场馆、体验项目现在都只有开发示例数据。

---

## 9. ③a 商城频道与商品详情只读（2026-11-16，连续推进）

依据同 §8：用户授权连续推进。§3.2 ③ 写明“② 被 C1、C2 阻塞时，先做 ③ 的类目、详情、购物车”，首批商品没有就用标记环境的开发种子（D5）。这一步只做类目和详情的只读部分；购物车、确认订单要写数据并依赖登录，另列一步。

### 9.1 范围

依据修订版原型 MallHome、MallDrone、Mall2、MallParts、MallRental、MallCourse（同一个列表模板）和 PdFull。

- 首页“商城”频道：类目页签 全部 / 无人机 / 机器人 / 配件 / 租赁 / 课程；综合、价格升序、价格降序（询价商品始终排最后）；两列商品卡（名称、价格、在售商家数、体验标签、租赁角标）；服务端分页。
- 首页金刚位“无人机”“机器人”“设备租赁”“培训课程”进到商城频道并直接选中对应类目。
- 商品详情：名称、简介、在售商家（商家类型、价格、配送说明）、选中商家后的销售方与发货履约、参数、包装清单；“加入购物车”“立即购买”“联系询价”走类型化网关。

### 9.2 这一步不做的

| 原型上有 | 处理 | 原因 |
| --- | --- | --- |
| 规格与配送弹层、购物车、确认订单、收银台 | 按钮提示“即将开放” | 写数据、要登录，收银台等 ② |
| 子类目、筛选面板、已选条件、搜索 | 不显示 | 这一步只做类目和价格排序；搜索要全局搜索页，筛选要先定筛选项 |
| 对比、收藏 | 不显示 | D10 未定 |
| 交易主体里的收款方、开票方 | 不显示 | 取决于收款主体 C2 |
| 退换、保修、发票等保障条目 | 不显示 | 商品售后规则 A1 未定，不能先写承诺 |
| 课程类目的商品 | 类目页签保留，列表为空态 | D15：不上架数字课程 |
| 商家主页 | 商家名不可点 | ⑥ |
| 评分、销量 | 不显示 | 没有真实聚合（AGENTS.md §8） |
| 频道对公开版开放 | 不开放 | 只能看不能买，不算闭环 |

### 9.3 规则

1. 数据模型按计划 §2.2.6：商品 → 规格（SKU）→ 商家供给（商家 × 规格 × 城市 × 价格）。供给的城市为空表示全国可售。
2. 列表只列在当前城市有有效供给的商品。卡片价格取该城市最低的明码标价；有不止一个供给价或是租赁时带“起”；全部供给都是询价时显示“询价”。
3. “N 家在售”是该城市有供给的商家数；租赁写“租赁 · N 家”。
4. 交易方式由商品决定：购买、租赁、询价，对应主按钮“立即购买”“联系询价”“联系询价”。
5. 价格排序由服务端做，询价商品排最后。

### 9.4 接口与数据表

契约 `contracts/openapi-mall-catalog.yaml`，新模块 `backend/modules/mall`，迁移 `V001__mall_catalog.sql`：

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/mall/products?cityCode&category&sort&page&size` | 类目商品列表 |
| `GET /api/v1/mall/products/{productId}?cityCode` | 商品详情，含该城市的在售商家 |

表：`mall_product`、`mall_product_spec`、`mall_product_pack_item`、`mall_sku`、`mall_offer`。约定同 ④a、④d。验证等级 L3。

### 9.5 完成情况（2026-11-16，未提交，等用户看过）

| 项 | 结果 |
| --- | --- |
| 契约与规范 | `contracts/openapi-mall-catalog.yaml` 1.0.0；AGENTS.md V2.9 登记 M11 `mall` |
| 服务端 | 新模块 `backend/modules/mall`，迁移 `V001__mall_catalog.sql`（5 张表），2 个公开只读接口；dev 示例数据：6 个商家、东营可见商品 12 件，另有 1 件只在济南、1 件已下架，用来验证城市过滤和下架不可见 |
| 后端测试 | 全量 355 条通过（本步新增 23 条） |
| App | `features/mall`（商城频道接进首页、商品详情 `/pages/mall/product`）；金刚位“无人机”“机器人”“设备租赁”“培训课程”进商城并选中对应类目 |
| App 单测 | 715 条通过（本步新增 12 条），类型检查通过 |
| E2E | 开发版 179 条通过（新增 `mall.spec.ts` 11 条）；公开版 6 条通过 |
| 公开版构建 | 构建通过，防泄漏检查通过；商城频道在公开版不显示 |
| 截图 | `.planning/mall/visual/` 320 / 375 / 430 |
| 真机 | Android、iOS 没测 |

到这一步，首页五个频道都有真实内容，不再有占位频道。旧用例里把“商城”当占位频道的两处（HOME-001、NAV-012）已改成断言真实的商城频道。

与原型的出入：

1. 商品没有图片（没有有使用权的图片），用带类目名的色块代替。
2. 没有子类目、筛选、搜索、对比、收藏（§9.2）。
3. “加入购物车”“立即购买”“联系询价”都只提示“即将开放”；租赁和询价商品不显示“加入购物车”。
4. 详情的“交易主体”只有销售方和发货履约，没有收款方、开票方；没有退换、保修、发票这类保障条目。
5. 课程类目是空态，给一个“看全部商品”的出口。

观察项（只记录，不修改）：

- uni-app H5 触底事件在上一次触发后的 350ms 内不处理滚动事件。自动化用例如果在这个窗口里离开底部再回来，第二次触底不会触发；真人操作到不了这个速度。`mall.spec.ts` 里用 `leaveBottomThenScrollToBottom` 绕开，写别的分页用例时要注意同一点。

接下来能做而不需要用户决定的只剩一项：①b（M02 目录接真实的 `/portal`、`/filters` 和服务端搜索、排序、筛选、分页）。它会改动已经验收过的 M02。购物车到“提交订单”之前的部分技术上能做，但要先定购物车里放什么（是否允许租赁、询价商品进购物车，跨商家怎么拆单），这属于产品规则。

---

## 10. ①b 巡检目录接真实接口（2026-11-16，连续推进）

依据：§3.2 ①b（“需要用户先定的规则：无”），AGENTS.md §9 M01、M02 的“必须实现”和 §19“搜索、分类、排序、筛选、切城、刷新和分页调用真实 API”，修订版原型画板 InspCatalog。契约 `contracts/openapi-inspection-catalog.yaml` 早已定义 `/portal`、`/filters` 和 `/services` 的搜索、筛选、位置参数，服务端只实现了“城市 + 类别 + 排序 + 分页”。

### 10.1 现状（开工前核对）

| 项 | 现状 |
| --- | --- |
| M02 取数 | 一次取 50 条，类别写死 `POWER`；搜索、分类、排序、分页都在手机上对这 50 条做 |
| M02 分类 | 电力下的子类：输电线路、变电站、配电网、电缆、光伏；从 M01 点别的场景进来不被接受 |
| M01 取数 | 没有 `/portal`；场景格和热门服务从服务列表里凑，主视觉文案写在客户端 |
| 筛选 | 巡检方式、是否含报告只在手机上判断，服务端没有对应数据 |
| 距离 | 没有位置数据，没有距离排序 |

### 10.2 做完能看到什么

- M02 标题下的分类按原型改为 全部 / 电力 / 光伏 / 油气 / 热力 / 园区 / 设备 / 定期，由服务端下发；变电站、配电网、电缆归到“电力”，光伏单列。
- 搜索、分类、排序（综合、距离最近、价格升降序）、筛选（巡检方式、报价方式、含巡检报告）、分页都由服务端完成，每页 5 条。
- 点“距离最近”先问定位；允许后按距离排，卡片多一行“距你 x 公里”；不允许就回到综合排序并说明原因。
- 城市没有开通巡检时显示“××暂未开通巡检服务”和“切换城市”，和“没有找到相关服务”区分开。
- M01 的主视觉、场景格、热门服务、服务流程来自 `/portal`；从任何一个场景格进 M02 都带上对应分类。

### 10.3 规则

1. 分类是基础数据，放正式迁移：一级 7 个（`POWER`、`PHOTOVOLTAIC`、`OIL_GAS`、`THERMAL`、`PARK`、`EQUIPMENT`、`PERIODIC`），`SUBSTATION`、`DISTRIBUTION`、`CABLE` 的上级是 `POWER`。按一级分类查询时包含它的下级。
2. 综合排序沿用现在的口径：分类顺序，再按已完成次数从多到少，最后按 id。
3. 距离口径只有一种：用户位置到服务在该城市的服务中心点（`CENTER_POINT`，docs/05 §8）。没有中心点的服务排最后、不显示距离。距离由 PostGIS 计算。
4. 距离排序必须带经纬度，否则 400。城市没有任何可见服务时，`/services`、`/portal`、`/filters` 返回 404 `INS_CATALOG_CITY_NOT_ENABLED`。
5. 热门服务取该城市已完成次数最多的 4 个，不另设人工开关。
6. 服务保障没有确认过的承诺，正式迁移和开发数据都不写，M01 这一块继续隐藏。主视觉文案是已经上线的那一句，放正式迁移；服务流程只在开发数据里。
7. 契约改动只有一处：`CatalogSort` 增加 `DISTANCE_ASC`，版本 1.2.0。

### 10.4 数据表（迁移 `V011`）

`inspection_category`、`inspection_portal_content`、`inspection_service_coverage`（中心点 `geography(Point,4326)`）、`inspection_service_method`（`UAV` / `ROBOT`），`inspection_service` 加 `includes_report`。已有表和已有列不动。验证等级 L3。

### 10.5 完成情况（2026-11-16，未提交，等用户看过）

| 项 | 结果 |
| --- | --- |
| 契约 | `contracts/openapi-inspection-catalog.yaml` 1.2.0：`CatalogSort` 增加 `DISTANCE_ASC`，其余只补说明 |
| 服务端 | 迁移 `V011__inspection_catalog_search.sql`（4 张新表、`inspection_service` 加 1 列）；`/services` 支持搜索、巡检方式、报价方式、含报告、距离；新增 `/portal`、`/filters`；城市未开通返回 404 `INS_CATALOG_CITY_NOT_ENABLED`，入口没有主视觉配置返回 503 `INS_CATALOG_CONFIG_UNAVAILABLE` |
| 开发数据 | `R__dev_inspection_catalog_search.sql`：8 个示例服务的巡检方式、是否含报告、东营 7 个和济南 2 个服务中心点、场景副标题、服务流程四步 |
| 后端测试 | 全量 371 条通过（本步新增 16 条）。三个接口都对着本地真实数据库验证过，包括 PostGIS 距离 |
| App | M02 改为服务端查询和分页；M01 和首页改读 `/portal`；新增定位适配 `uni-catalog-position.ts`；删除没有被引用的 `power-inspection-catalog.fixture.ts` |
| App 单测 | 730 条通过，类型检查通过 |
| E2E | 开发版 198 条通过（新增 `catalog-search.spec.ts` 19 条）；公开版 6 条通过 |
| 公开版构建 | 构建通过，防泄漏检查通过 |
| 截图 | `.planning/catalog-search/visual/` 320 / 375 / 430（默认、筛选面板、拒绝定位、按距离排序） |
| 真机 | Android、iOS 没测。定位在真机上要走系统权限弹窗，这条路径只在浏览器里验证过 |

**这一步改掉了以前批准过的设计，请过目：**

1. M02 原来只列电力类服务，分类是输电线路、变电站、配电网、电缆、光伏五个子类。现在按修订版原型改成全品类，分类是 全部 / 电力 / 光伏 / 油气 / 热力 / 园区 / 设备 / 定期，变电站、配电网、电缆并进“电力”。
2. 排序栏原来是 综合 / 销量 / 价格，现在是 综合 / 距离最近 / 价格。服务端仍然接受销量排序和子类编码，要恢复只需改 App。
3. 从 M01 点电力以外的场景，原来提示“该分类暂未开放”并退回，现在直接进目录并选中该分类。
4. 目录的搜索、筛选原来在手机上对一次取回的 50 条做，现在每次条件变化都请求服务端。

与原型的出入：

1. 定位询问用系统样式的确认框，不是原型里自绘的底部确认框；文案相同。
2. 列表底部多了一个可以点的“上拉或点此加载更多”。原因：一页 5 条在高屏手机上装不满一屏，页面滚不动就永远触发不了上拉加载。
3. 设备、定期两个分类没有示例服务，点进去是空态。
4. 服务保障在 M01 仍然隐藏（§10.3 第 6 条）；开发版看到的保障条来自原有的预览内容，不是服务端。

这一步里顺带查清并处理的两个问题：

- 搜索偶尔用到旧关键词：uni-app 的 H5 输入框把输入事件限流为 100ms 一次，在这 100ms 内按搜索，页面拿到的是上一拍的内容（打字很快再回车时会丢最后一个字）。改为用输入框“确认”事件自带的内容搜索。改动在 `CatalogHeader.vue` 和目录页的 `searchCatalog`。
- `BKB-103` 偶发失败：详情页“立即预约”在详情加载完之前是禁用的，用例的辅助函数没等就点。只改了 `booking-back.spec.ts` 的 `openBookingFromHome`，让它点可用状态的按钮。

观察项（只记录，不修改）：

- H5 上目录页整页比视口高 47px，整页能再滚一小段把标题行滚出去。布局规则这一步没动过，App 里没有整页滚动；要不要在 H5 上处理另开任务。
- 320 宽时卡片的“已服务 x 次”会折成两行，单位会被截断。卡片样式这一步没动过。
- 其他几个用例文件（`booking-recovery`、`booking-service-lookup`、`service-route-city`、`service-detail-recovery`）里也有不等详情加载就点“立即预约”的写法，这次没有失败，没有改。
- `GET /api/v1/inspection/services/{id}` 不带 `cityCode` 仍然是 500（列表接口这一步已改成 400）。
- 正式环境的分类副标题为空（副标题只在开发数据里），正式数据录入时要补。

**到这里，不需要用户决定就能做的事已经做完。** 下面每一项都卡在一个需要用户定的问题上：

| 想做的 | 卡在哪 |
| --- | --- |
| 收银台、支付、退款（②），以及所有收费的报名、到馆预约、商城下单 | 支付渠道 C1、收款主体 C2 |
| 购物车到“提交订单”（③b） | 租赁和询价商品能不能进购物车、跨商家怎么拆单；商品售后规则 A1 |
| 免费活动报名（④b） | 未成年人怎么报名、监护人确认文案、免费取消时限 |
| 线上体验的仿真任务 | 自己设计关卡，还是接厂家的仿真内容 |
| 需求发布、报价比选、租赁（⑤） | 依赖 ②；另有 B3 和设计假设 10、11、16、17 |
| 专业服务其余三类（⑥） | 依赖 ⑤；四类详情用哪个模板 |
| 手机号登录、客服会话（⑦） | 短信渠道、协议正文、客服方案 B1 |
| 发现页真实内容和直播（⑧） | 直播供应商和审核方式 D20、内容素材授权 |
| 正式数据 | 活动、场馆、体验项目、商品、巡检服务中心点现在都只有开发示例数据，怎么录入没有定 |
| 真机验收 | 没有 Android、iOS 设备测过任何一步 |

---

## 11. 三个新频道按原型对齐样式和图片（2026-11-16）

起因：用户看过后指出“样子和原型有的样式不太一样”。并排对照后确认三类差异（对照图在 `.planning/style-compare/`），用户选定先改商城、赛事研学、体验三个新频道；巡检页（M01–M08）仍是旧主题，这次没有动。

### 11.1 改了什么

| 页面 | 对齐的内容（原型画板） |
| --- | --- |
| 商城频道 | 类目胶囊、排序行、商品卡：图片顶到卡片边缘，价格与在售商家数同一行，左侧大按钮，租赁角标（MallHome） |
| 商品详情 | 通栏头图加浮动返回，价格卡压在头图上，配送行，商家单选样式，参数与包装清单折叠，底栏两个按钮连成一个胶囊（PdFull） |
| 赛事研学频道 | 类型改为带下划线的文字页签，日期格、筛选胶囊、活动卡缩略图和类型角标（HomeAct） |
| 活动详情 | 通栏头图加浮动返回和类型角标，场次改三列格子，主办、地点、参与条件、退改规则合成一张带标签的行卡，团队报名收进场次卡，底栏（ActDetail） |
| 体验频道 | 线上 / 线下切换、趣玩挑战入口上移并带图、深色类型胶囊、体验卡头图和白底标签、场馆卡（ExpCenter） |
| 场馆详情 | 通栏头图，信息卡压图，项目改胶囊加一张项目卡，日期和场次格子，馆内设备缩略图条，场馆信息栅格，底栏（VenueDetail） |
| 馆内设备 | 标题、类别胶囊、提示条、设备卡、底部“预约到馆看真机”（VenueDevices） |

### 11.2 图片

- 原型里的 15 张图转成 JPEG 放在 `apps/mobile/src/static/images/preview/`（共约 1.0 MB），按开发示例数据的 id 对应到商品、活动、体验项目、场馆和设备：`features/{mall,activity,experience}/data/preview/*-preview-covers.ts`。
- 只在预览构建里生效；服务端返回了图片就用服务端的。公开版里这三个频道本来就不显示。
- **这批图是原型里的概念图，使用权没有确认过。** 正式上线前要换成有使用权的图，换的办法是给商品、活动、场馆录入图片地址，客户端不用改。

### 11.3 没有补的（原型上有）

商城的二级筛选（热销 / 新品 / 可体验）、筛选面板、“企业采购”条、“对比”；商品详情的“先体验，再决定”、已选规格行、售后与保障、买家评价、相关内容、底栏的“商家 / 咨询 / 先体验”；赛事研学的“亲子 / 年龄 / 线上”筛选；活动详情的器材和“在线试试”两行、收藏；体验频道的“我的体验记录”和卡片上的“关联商品”；场馆详情的“营业中”标签、距离、“更多日期”、咨询；设备卡上的价格和在售商家。这些都依赖还没定的规则或还没做的功能（§8.2、§9.2），不是样式问题。

### 11.4 验证

类型检查通过；App 单测 733 条通过（新增图片对应关系 3 条：每个示例都有图、图都存在、文件夹里没有用不上的图）；开发版 E2E 198 条通过；公开版构建和 6 条公开版用例通过。截图 `.planning/{mall,activity,experience}/visual/` 已按新样式重拍，对照图 `.planning/style-compare/` 7 张。真机没测。

用例改动两处：场馆详情里项目的时长、限制、价格改为断言项目卡（`venue-project-detail`）；馆内设备新增“预约到馆看真机”回到场馆页的断言。

---

## 12. 巡检各页按原型改新主题（2026-11-16，用户确认“改巡检页”）

接 §11。对照图在 `.planning/style-compare/insp-*-before.png` 和 `insp-*-after.png`，三个宽度的截图在 `.planning/inspection-theme/visual/`。AGENTS.md V2.10 把视觉依据改为修订版原型。

### 12.1 各页改了什么

| 页面 | 原型画板 | 改动 |
| --- | --- | --- |
| M01 巡检入口 | HomeSvc | 结构换了：去掉主视觉 Banner；一行保障条；场景格改为小图标色块，不显示副标题；“发布巡检需求”条；热门服务改 156 宽横滑卡，不显示评分和服务次数；服务流程改小圆图标 |
| M02 巡检目录 | InspCatalog | 页头标题靠左加渐变、客服胶囊；分类胶囊、排序行分隔线；卡片左图通高、分类角标按类别配色、绿色描边标签、“¥699/公里起”；不再显示“已服务 x 次”；筛选生效时按钮带橙点 |
| M03 服务详情 | InspDetail | 页头靠左加渐变；信息卡压在头图上；能力项改成打勾文字；价格带“/公里起”；服务内容、交付成果改浅底小格；流程改圆点；底栏胶囊按钮 |
| M04 预约 | InspBooking | 页头、服务卡（浅绿类目标签、88 图）、行高和字号、巡检项目去掉左侧图标、底部保障条和胶囊按钮 |
| M04 提交结果 | InspBooked | 整屏大对勾改成一张紧凑结果卡（左侧蓝底对勾），信息卡、时间线、底栏按主题 |
| M05 / M06 / M07 | PlanConfirm / Fulfillment / Delivery | 版式本来就和原型一致，只加主题层：页头渐变、背景色、卡片阴影 |
| M08 订单中心 | OrderList | 页头渐变；阶段筛选改成一行可横滑的胶囊；卡片按原型的服务订单卡配色和按钮 |

### 12.2 没有照原型改的

1. **登录页**：原型是“本机号码一键登录”，现在是账号密码登录。这是功能不同（手机号登录要定短信或运营商认证渠道），样式没动。
2. **订单中心的内容**：原型的“我的订单”列出商品、服务、报名三类订单；现在只有巡检订单，所以保留了自己的阶段筛选，卡片上没有服务商名和金额明细。
3. **M01 的“发布巡检需求”条**：文案写成“说明场景和范围，由服务商给出方案”，没有照抄原型的“发布后多家报价、比选后再签约”，因为比选还没做。这条只在开发环境显示，公开版没有。
4. **M04 提交结果页头的“1/5”和标题“预约详情”**：沿用之前确认的写法，原型是“提交成功”。
5. ~~首页各频道左右留白是 9px，原型是 16px。~~ 用户 2026-11-16 确认改成 16px，见 §12.6。
6. ~~专业服务频道里航拍、测绘等其他类型的入口页没有动。~~ 已改，见 §13.3。

### 12.3 这一步改掉的、以前确认过的做法（请过目）

1. M01 的场景格以前特意做成大图标加副标题（单测里记着“宽松的图标和文字比例”），现在按原型改成 44px 小色块、不显示副标题。
2. M01 的主视觉 Banner 没有了；公开版的入口页现在是场景格、热门服务两块。
3. M02 卡片上的“已服务 x 次”不再显示。

### 12.4 顺带处理

- M02 整页比屏幕高 47px（§10.5 的观察项）：页面改成弹性布局后消失，截图用例里加了断言。
- 预约页、详情页、目录卡的价格统一写成“¥699/公里起”。
- 把服务流程的桩数据从 `service-flow-visits.spec.ts` 提到 `e2e/support/service-flow-fixtures.ts`，行为用例和截图用例共用。

### 12.5 验证

类型检查通过；App 单测 734 条通过；开发版 E2E 207 条通过（新增 `inspection-visual.spec.ts` 9 条：九个画面在 320 / 375 / 430 下不横向溢出、文字不竖排并留截图）；公开版构建、防泄漏检查和 6 条公开版用例通过。后端没有改动。真机没测。

改动过程中出现并修掉的问题：去掉预约页项目图标后文字被挤成竖排（现由截图用例里的“不竖排”检查看住）；入口页变短后三条滚动位置用例滚不到 300px，改用矮屏运行；目录首屏超过一屏时滚到底会自动加载，`loadEverything` 改为两种情况都接受。

### 12.6 首页和各频道左右留白改为 16px（2026-11-16，用户确认）

- 全局令牌 `--app-page-horizontal-gutter` 从 9px 改为 16px（`App.vue`），首页推荐、专业服务、赛事研学、体验、商城五个频道和发现页一起变。
- 五处横滑卡片的宽度原来写死按 9px 算（`100vw - 49px`），改为跟着令牌算。
- 入口页的几个区块原来自己又加了一层留白，9px 时看不出来，16px 时内容明显变窄；去掉这一层，只留频道那一层，热门服务卡仍然延伸到屏幕右边。
- 320 宽时商城卡片上的“N 家在售”放不下会折行，改为单行、放不下时省略。
- 验证：单测 734 条、开发版 E2E 207 条、公开版构建和 6 条公开版用例通过；首页、入口页、商城的 320 / 375 截图已看过。

## 13. 其余页面按原型对齐（2026-11-17，用户说“继续”）

把还没和原型比过的根页面逐个并排比了一遍（`.planning/style-compare/rest-*.png`），能改的改掉，改不了的列在 §13.4。

### 13.1 首页“推荐”频道下半部分接真实数据

原型的推荐频道在金刚位下面有四块内容，原来 App 这里放的是巡检的“精选服务”。预览构建现在换成这四块，数据都来自已有的只读接口，没有新增接口：

| 区块 | 数据来源 | 点了去哪 |
| --- | --- | --- |
| 今日可约 | 本城第一个“今天还有场次”的场馆，取它今天还能约的项目（最多 3 个），显示开场时间和余位 | 场馆详情 |
| 近期赛事研学 | 赛事研学列表前 3 条 | 活动详情；“全部”到赛事研学频道 |
| 热门设备 | 商城列表前 4 条 | 商品详情；“全部”到商城频道 |
| 附近场馆 | 本城场馆前 2 个 | 场馆详情；“全部”到体验频道 |

- 代码在 `features/recommend/`：`home-recommend.ts` 负责取数和拼装，`HomeRecommendBlocks.vue` 负责显示。三个列表并行请求，哪个失败哪块不显示，其余照常；切城市时旧城市的结果不会回填。
- “今日可约”只有在本城确实有今天的场次时才出现，没有就整块不显示，不放假数据。“今天”按北京时间算。
- 公开版不变：仍然是巡检的“精选服务”，四块内容不出现（`public-build.spec.ts` PUB-001 加了断言）。
- 观察项：这四块是客户端拼出来的，“今日可约”最多要多发 4 个请求（场馆详情 1 个 + 最多 3 个项目各查一次场次）。量大以后应当由服务端出一个首页聚合接口，本次没有做。

### 13.2 “我的”页

- 头部换成原型的渐变卡片：56px 圆头像、19px 名字；客服和设置两个图标移到卡片上方。
- 订单阶段卡加“我的订单”标题，五个阶段从 3D 图片换成线条图标；角标只在数量大于 0 时出现（原来 0 也显示）。
- 近期订单和功能入口改成主题卡片（圆角、阴影、字号、配色）；订单标题最多两行，窄屏不再被截成几个字。
- **未登录状态**：原来显示“未登录用户 / Lv.0 / 身份待完善”，现在按原型显示“未登录”和“登录 / 注册”按钮，不再显示等级和认证。按钮去登录页，登录后进订单中心（登录页只接受已登记的回跳地址，订单中心是其中之一，这里没有改回跳白名单）。
- 没有照原型做的：原型“我的订单”是商品、服务、报名合在一起的五个阶段（待付款、待发货/履约、待参加、待验收、退款/售后），现在只有巡检订单，保留巡检的五个阶段；“我的参与”、参与人管理、企业组织等入口对应的功能都还没有，没有加。
- 观察项（原来就有，没有动）：已登录时名字固定显示“已登录用户”、等级固定“Lv.0”、认证固定“身份待完善”，不是真实资料，要等身份模块给出用户资料接口；四张阶段 3D 图片（`mine-status-*-v2.png`）现在没有地方用了，没有删。

### 13.3 专业服务频道的航拍、测绘、设备维护、技术支持

- 原来只有航拍有内容，而且是一整页预览用的假数据（Banner、八个场景、带价格 / 评分 / 已售数的服务卡）；其余三类只有一张说明卡。
- 现在四类都按原型：一行保障条、一张“××服务”说明卡（“查看全部服务”和“发布需求”）、服务流程（发布需求 → 比选报价 → 服务履约 → 成果验收）。价格、评分、已售数全部去掉。
- 保障条按类型不同：航拍、测绘是“资质审核 / 持证飞手 / 成果可追溯”；设备维护是“资质审核 / 服务记录 / 报告可追溯”；技术支持是“资质审核 / 服务记录 / 报价留档”（不涉及飞行的不写持证飞手）。
- 搜索框占位词改为“搜索航拍服务、场景或关键词”这一类。
- “查看全部服务”“发布需求”和搜索都还没有后续页面，点了提示“下一阶段接入”；这四类只在预览构建出现，公开版没有。
- 观察项：航拍旧页面用的几张图片（`aerial-service-hero-v3.png` 等）不再被这一页引用，没有删。

### 13.4 比过但没有改的

| 页面 | 情况 |
| --- | --- |
| 发现 | 原型是短视频 / 直播内容流，功能没做（直播供应商、内容版权待定），样式没动 |
| 消息、购物车 | 功能没做，现在是占位页 |
| 切换城市 | 原型是底部面板，现在是整页列表，功能一致，没动 |
| 趣玩挑战 | 已和原型一致 |
| 登录页 | 同 §12.2 第 1 条 |

### 13.5 验证

- 类型检查通过；App 单测 741 条通过（新增首页推荐取数 6 条，改写“我的”和服务类型入口相关断言）。
- 开发版 E2E：全量跑了两遍。第一遍 211 通过、3 失败；修完后第二遍 213 通过、1 失败（CAT-206）、6 跳过；CAT-206 修完后 `catalog-search.spec.ts` 连跑三遍 57 条全过。之后没有再跑第三遍全量。
- 公开版：构建和防泄漏检查通过，6 条公开版用例通过（PUB-001 新增“首页没有推荐四块、仍是精选服务”）。
- 新增用例：`home-recommend.spec.ts` 5 条、`mine.spec.ts` 2 条；NAV-022 补了航拍保障条和“发布需求”提示。
- 后端代码没有改，只把两份开发种子里的对齐日期改成 2026-11-17 并重启（见下），后端测试这一轮没有重跑。真机没测。

四条失败各自的原因：

| 用例 | 原因 | 处理 |
| --- | --- | --- |
| ACT-102 | 开发种子的场次时间是按“执行当天”排的，种子是 11-16 执行的，过了一天“明天上午”那场已结束，已结束场次从 1 个变 2 个 | 按种子文件第 4 行写的办法把日期改成 2026-11-17、重启后端重新对齐。页面没有问题。**以后每隔一天跑 E2E 前都要先做这一步** |
| ACT-006 | 首页推荐新加的“近期赛事研学”也会请求一次活动列表，用例把它算进了频道的翻页请求 | 用例只统计频道自己的请求 |
| NAV-022 | 我新加的断言写错：提示消失后节点还留在页面里，不能用“数量为 0”判断 | 改为判断不可见 |
| CAT-206 | 目录“加载更多”按钮滚进视野时会自己触发加载（已知行为），和用例的点击抢跑，偶发 | 用例两种情况都接受，和 `loadEverything` 同一个写法 |

并排对照图在 `.planning/style-compare/`：`rest-mine-in-after.png`、`rest-mine-after.png`、`rest-aerial-after.png`、`rest-support-after.png`。

## 14. 后续计划（草案，2026-11-17，用户要求“后面的做个计划”，待确认）

分期的范围、验收路径、契约仍以 §3 为准，这一节只回答三件事：现在在哪、接下来按什么顺序做、每一步等你定什么。

### 14.1 现在在哪

| 期（§3.1） | 状态 |
| --- | --- |
| 巡检闭环 M01–M08、多次巡检 | 已有，真实接口；这一轮按原型改了样式 |
| ① | 已提交，**一直没有收到“验收通过”** |
| ①b 巡检目录真实接口 | 做完，已提交，未验收 |
| ③ 商城 | 只做了只读的频道和商品详情（③a）；搜索、筛选、购物车、下单都没有 |
| ④ 赛事研学、体验 | 只读的频道、活动详情、场馆详情和设备已做（赛事研学已验收，体验未验收）；报名、到馆预约、仿真任务没有 |
| 首页、“我的”、专业服务其余四类入口 | 已按原型对齐；“我的”里的名字、等级仍是占位 |
| ②、⑤、⑥、⑦、⑧ | 没有开工 |

代码都在 `codex/app-prototype-impl`（比产品线 `codex/inspection-visits` 多 12 个提交），没有推送，没有合回产品线。Android、iOS 真机从来没有测过。

### 14.2 顺序

按“先把已经做的收住，再做不用等决定的，再按解锁成本从小到大”排。

| 步 | 做什么 | 等你定什么 | 做完你能看到什么 | 验证等级 |
| --- | --- | --- | --- | --- |
| A 收尾 | ① 后端全量测试重跑一遍（这一轮没跑）；② 开发种子改成每次启动自动对齐当天，不用再手改日期；③ 在这台电脑的 iOS 模拟器里把黄金路径和新页面走一遍，留截图；④ 写一份照着点的验收清单；⑤ 清掉 §10.5、§13 里记下的小问题（详情接口不带城市返回 500、几张不再使用的图片、几条不等加载就点的用例） | 不用定。做完请你验收 ①、①b、体验、商城、样式这几步 | 验收清单和模拟器截图 | L3 |
| B 只读补齐 | ① 商城搜索和筛选；② “我的”显示真实账号资料（现在后端只有登录接口，要加一个读取本人资料的接口）；③ 底栏“消息”：巡检进度通知（方案已出、待验收等）的只读列表，同时把站内信记录表建起来；④ 首页推荐四块改由服务端一个接口给出 | 只要你点头范围（商城搜索筛选、消息在 AGENTS.md 里现在写的是“还没确认”） | 商城能搜能筛；“我的”是真名字；消息里有巡检进度 | L3（新接口、新表） |
| C 免费活动报名（④b） | 参与人管理、选场次报名、报名凭证、我的报名、取消；核销先用受权接口，不做工作台页面 | 三条规则：未成年人怎么报（是否必须由监护人账号代报）、监护人确认文案、免费报名的取消时限 | 第一个不经过支付就能走完的新闭环：首页 → 赛事研学 → 报名 → 凭证 → 我的报名 | L3 |
| D 购物车到“提交订单”之前（③b） | 购物车、地址、确认订单页，停在提交订单 | 购物车规则：租赁和询价商品能不能进购物车、跨商家怎么拆单 | 加入购物车、结算到确认订单 | L3 |
| E 统一交易模块（②） | 收银台、支付回执、标准退款、对账导出；之后依次接上收费报名、到馆预约、商城下单、巡检付款节点 | **支付渠道 C1、收款主体 C2**；巡检付款节点另要 C3（确认方案后的取消、改期） | 真的能付钱、能退钱 | L4 |
| F 需求发布、比选、租赁（⑤）→ 四类专业服务（⑥） | §3.2 | B3、设计假设 10、11、16、17；四类详情用哪个模板 | 航拍等四类的“发布需求”不再是提示 | L3，租金 L4 |
| G 账户与公共（⑦） | 手机号登录、设置、协议、客服会话、扫一扫 | 短信渠道、协议正文、客服形式 B1 | 手机号登录 | 登录 L3 |
| H 内容与直播（⑧） | 发现页真实内容、直播 | 直播供应商和审核方式 D20、内容授权 | 发现页不再是占位 | L3 |
| I 发布候选 | 真机（一台 Android、一台 iPhone）、完整回归、打包 | 见 §14.4 | 可以装到手机上的包 | L4 |

A、B 不依赖任何业务决定，可以马上做。C 是解锁成本最低的新闭环（三条规则，不碰钱）。E 解锁的东西最多，但卡在 C1、C2 上。

### 14.3 等你定的事（按能解锁多少排）

| 事项 | 解锁 | 我的看法（供参考，决定在你） |
| --- | --- | --- |
| 支付渠道 C1 | E，以及所有收费流程 | 面向国内用户、以后还要复用到微信小程序，微信支付是最顺的一条；要有国内主体的商户号才能申请，这一点需要你那边确认有没有 |
| 收款主体 C2 | E | 平台统一收款再转给商家，在国内涉及支付牌照问题（俗称“二清”），需要请懂合规的人看；一期又明确暂缓分账。更稳的是每笔钱直接进签约方自己的商户号，或者一期只卖平台自营的 |
| 未成年人报名、监护人文案、免费取消时限 | C | 监护人文案建议请法务出；另外两条可以先按原型的写法（监护人账号添加子女代报；开场前可取消）做，之后再调 |
| 购物车规则 | D | 原型里租赁、询价商品不进购物车，按商家拆单；按原型做最省事 |
| 正式数据怎么录入 | 上线 | 活动、场馆、商品、巡检服务现在全是开发示例。Web 运营端不在一期，那就得定：先由我按你给的表格写成导入脚本，还是提前做一个最小的录入后台 |
| 图片 | 上线 | 原型里的 15 张概念图已经在仓库里，只在预览版用，上线前要换成有使用权的 |
| 短信渠道、协议正文、客服形式 | G | — |
| 仿真任务内容 | ④ 里的线上体验 | 自己设计关卡还是接厂家内容 |
| 直播供应商 D20 | H | — |
| 验收 | — | ① 一直没验收；体验、商城、①b、样式这几步也还没看过 |

### 14.4 真机和打包

- 这台电脑有 Xcode 和 iPhone 模拟器，没有 Android SDK。A 步可以先用 iOS 模拟器走一遍，但它替代不了真机，也说明不了 Android。
- uni-app 打 App 包要用 HBuilderX 或 DCloud 的云打包（需要 DCloud 账号和证书）。这一条到 I 步之前要准备好：谁的账号、Android 签名证书、iOS 开发者账号。
- 所有涉及定位、返回手势、键盘、安全区的改动，目前都只在浏览器里验证过。

### 14.5 分支

- 现在 12 个提交只在本机的 `codex/app-prototype-impl`。建议 A 步做完、你验收后：推到远端，再合回产品线 `codex/inspection-visits`。推送和合并都等你说了再做。
- 主检出目录 `main` 上有我改过的 `AGENTS.md` 和 `01-概要设计.md`，和你自己没提交的改动混在一起，一直没动。这两份文档要不要以本分支的版本为准、`main` 上怎么处理，需要你定。

### 14.6 A 步完成情况（2026-11-17，未提交）

| 项 | 结果 |
| --- | --- |
| 后端全量测试 | 374 条通过（比上次多 3 条：种子日期占位符 2 条、详情接口 1 条） |
| 开发种子自动对齐 | 活动、体验两份种子里写入日期占位符 `${devSeedDate}`，后端启动时填入北京日期；日期一变 Flyway 的校验和就变，当天第一次启动自动重排场次。已对着本地数据库核对：库里记的校验和等于“填入日期后的文件”的校验和，不是原文件的 |
| 详情接口不带城市 | 原来返回 500，现在返回 400 `COMMON_INVALID_ARGUMENT`，和列表、入口、筛选三个接口一致。先写了失败用例再改 |
| 不再使用的图片 | 删了这一轮变成没人用的 7 张（“我的”阶段图 5 张、航拍旧页面 2 张）。另有 5 张更早就没人用的没动，列在验收清单 §9 |
| 抢跑的用例 | 四个用例文件里 6 处不等详情加载就点“立即预约”，改为只点可用状态的按钮 |
| App 单测、E2E、公开版 | 类型检查通过；单测 741 条；开发版 E2E 214 条通过、6 条跳过；公开版构建、防泄漏检查和 6 条用例通过 |
| iOS 模拟器 | iPhone 17（iOS 26.3）里用全屏网页壳打开开发版，真实点按走了 首页 → 专业服务 → 光伏场景 → 目录 → 详情 → 预约页，另外打开了赛事研学、活动详情、体验、场馆详情、馆内设备、商城、商品详情、“我的”、航拍入口共 16 张截图，都没有横向溢出和文字竖排。截图和总览在 `.planning/ios-sim-walk/` |
| 验收清单 | `docs/redesign/04-验收清单.md` |

模拟器这一项的限制：装不上 HBuilderX 基座，用的是自己编的全屏网页壳，加载的是 H5 开发版。它没有 App 的原生运行时，模拟器里也没有中文字体（用的是替代字体，字宽和真机不同）。它能说明页面在 iOS 的网页内核里排版正常，说明不了真机 App，也说明不了 Android。

走模拟器时新发现的问题（没有改，等你定）：见验收清单 §9 第 1–5 条。其中第 1 条（本地没有登录账号）会挡住你手工验收需要登录的几步；第 2、3 条（首页等定位、内容从状态栏下穿过）在真机上很可能也有。

### 14.7 开发环境测试账号（2026-11-17，用户确认“加测试账号”，未提交）

- 新增开发种子 `db/development/identity/R__dev_identity_accounts.sql`，由只在 dev 配置下存在的 `identityDevelopmentDataFlyway` 加载，有自己的迁移历史表。账号结构和密码沿用仓库里已有的冒烟脚本 `infra/scripts/verify-f04-reservation-smoke.ps1`。
- 两个账号：`dev-customer`（客户）、`dev-provider`（“云翼科技”的服务商操作员，开发种子里的巡检服务都属于这家）。没有建平台运营账号。
- 密码公开写在种子注释里，所以 dev 配置只能用在本机或内网；任何外面能访问到的环境都不能开 dev。种子开头写了这句话。
- 测试 3 条：只在 dev 加载且历史表独立；只建客户和服务商操作员、不出现平台运营；生产迁移目录里没有任何建账号或写口令的语句。
- 验证：后端全量 377 条通过；开发版 E2E 全量跑了一遍，213 通过、1 失败（CAT-211，见下），修后 `catalog-search.spec.ts` 连跑三遍 57 条全过，没有再跑全量。重启后对着真实登录接口验过：两个账号都能登录；客户账号能读自己的订单；服务商账号读客户订单被拒（403）；错误密码 401。又在 iOS 模拟器里从“我的 → 登录 / 注册”实际登录，落在“我的订单”。
- 剩下的缺口：有了账号也只能手工走到“提交预约”。之后的发布方案、履约、交付在服务商一侧，只有接口没有页面，见验收清单 §9 第 1 条。
- CAT-211 偶发失败的原因（和账号无关，查清并复现了）：uni-app 的 H5 输入框对“100ms 内的第二次输入”是延后上报的，而这次延后的上报会取消“按绑定值刷新输入框”的动作。用例里两次搜索挨得很近，本地后端又快，偶尔整段落在 100ms 以内，点“清空”之后旧文字又被写回输入框（列表是对的，只有框里的字没清掉）。按这个顺序构造的复现 10 次里 10 次出现，隔 150ms 再清空 10 次里 0 次。手指做不到这么快，页面没有改，只让用例在清空前等 150ms。

### 14.8 服务商一侧的推进脚本（2026-11-17，用户确认“写推进脚本”，未提交）

- 新增 `infra/scripts/dev-advance-reservation.cjs`。服务商没有页面，客户提交预约后卡在“待方案”；脚本用 `dev-provider` 调已有的服务商接口，把一张预约往前推到“下一个该客户做的动作”：待方案 → 发布方案；确认后 → 安排团队、排期、开始、进度、现场完成、上传 4 个交付文件并发布交付；多次巡检的单在每次验收后推进下一次。客户一侧的确认方案和验收不代做。
- 用法见验收清单 §0。`--visits N` 指定几次巡检，`--step` 一次只做一个动作，后面可写订单编号。
- 约束：只允许回环地址；密码从开发种子里读，脚本里没有第二份；客户账号在脚本里只用来读订单列表找到那一张；没有新增任何接口，后端代码没有改。
- 交付文件是脚本现造的小文件，但都是能正常打开的：zip、xlsx（缺陷清单三行）、kml（三个点位）、pdf（一页，英文，因为不嵌字体写不了中文），内容都标明是示例。
- 验证（对着本地真实后端）：一张 2 次巡检的预约从“待方案”走到“已完成”，其中第 1 次用 `--step` 逐个动作走、每步核对服务单状态，第 2 次一次走完；确认方案和两次验收由我用客户账号调接口代做。四个交付文件经客户的下载意图下载后逐个检查能打开。非本机地址、参数写错、订单编号不存在三种情况都按预期拒绝。又在 iOS 模拟器里用 `dev-customer` 登录，看了订单中心、方案确认页、交付验收页显示这些真实数据（`.planning/ios-sim-walk/20–22`、`sheet-d.jpg`）。
- `dev-customer` 名下留了四张示例订单（一张待确认、两张待验收、一张已完成），都是脚本验证时产生的，在本地开发库里。改过示例文案后又用一张单次巡检的预约完整跑了一遍。
- 没有做的：脚本没有自动化测试（和同目录另外两个 Node 冒烟脚本一样）；App 里的“确认方案”“确认验收”按钮这一轮没有在模拟器里实际点（自动用例覆盖这两步，用的是预置数据）。

## 15. B 步：只读补齐（2026-11-17，用户说“一直继续，按照计划”，未提交）

对应 §14.2 的 B 步。四项都不依赖业务决定；AGENTS.md 升到 V2.11 登记了范围。

### 15.1 “我的”显示真实账号资料

- 新接口 `GET /api/v1/auth/me`：登录账号读自己的显示名、所属组织名和类型、角色编码。账号只从令牌里取；不返回登录名、手机号、口令。账号被停用时返回 401。契约 `openapi-inspection-service-flow.yaml` 升到 2.1.0。
- “我的”页：名字用显示名，名字下面的标签用组织名；没有组织就不显示标签。原来固定显示的“Lv.0”“身份待完善”去掉了，因为没有等级和认证这两样数据。
- 资料读不到时（例如登录刚过期）只显示“已登录”，不把人带去登录页；需要登录的请求（订单）照旧负责提示登录。
- 开发账号改了名字和单位（王女士（示例） / 东营示例物流公司），免得名字和单位一样。

### 15.2 商城搜索和筛选

- 接口 `GET /api/v1/mall/products` 增加：`keyword`（匹配商品名和简介，% 和 _ 按字面）、`saleModes`、`experienceBadges`、`merchantKinds`（都可多选）、`minPriceMinor`、`maxPriceMinor`。契约 `openapi-mall-catalog.yaml` 升到 1.1.0。
- 价格区间比的是该城市的最低标价；设了区间后，没有标价的询价商品不出现。商家类型看的是该城市有没有这类商家在售。
- App：在商城频道里，首页搜索框搜的是商城；结果上方有可单独移除的标签（关键词、每个筛选项、价格区间）。“筛选”按钮打开页内面板，确认按钮上显示“查看 N 件”。搜不到时说明并可一键清除。切到别的频道时搜索结束，筛选保留。
- 筛选面板只放了数据里真有的三组（体验方式、交易方式、经营商家）和价格区间。原型里的“配送与自提”“适用场景”和“可预约试飞”没有做：商品数据里没有这几项。
- 原型里的统一搜索（商品、服务、活动、内容一起搜，带搜索前置页和最近搜索）没有做，这里只搜商城。
- 顺带修了首页搜索框的一个问题：打字很快再回车时可能用到上一拍的内容（和巡检目录搜索框同一个原因，同一个修法）。

### 15.3 首页推荐改由服务端一个接口给出

- 新接口 `GET /api/v1/home/recommend?cityCode=`（契约 `openapi-home.yaml`）：今天去体验、近期活动（3 个）、本地精选（4 个）、附近场馆（2 个）一次返回，每一项的结构和各自的列表接口相同。
- “今天还有哪些场次可约”改由服务端判断（北京时间）。哪个模块读取失败，哪一块就是空的，其余照常返回。
- App 首页从原来最多 7 个请求变成 1 个；请求失败时不显示这四块，上面的入口照常可用。公开版不调用这个接口。

### 15.4 消息：站内信和巡检进度通知

- 新模块 `notification`，新表 `notification_message`（迁移 `V001`）：收件人、分类、标题、正文、跳转目标（类型 + 稳定 ID）、幂等键、发生时间、已读时间。
- 通知怎么来的：巡检流程每次状态变化本来就会在同一个事务里写一条“待发布事件”（`inspection_domain_event_outbox`），但一直没有程序消费。这次加了转发器，每 3 秒把到期的事件取出来，补上客户、服务名、第几次这些标识，交给站内信写成通知，再把事件标为已发布。每条事件单独一个事务；失败的记下原因，5 秒起翻倍重试，最多隔 10 分钟。巡检的写入路径没有改。
- 哪些事件发通知：方案已出、服务团队已安排、巡检已排期、巡检已开始、现场巡检完成、巡检报告已交付。客户自己做的动作（确认方案、验收、取消）和中途进度不发。
- 接口（都要登录，契约 `openapi-notification.yaml`）：列表（带未读数）、未读数、标一条已读、全部已读。收件人只从令牌里取；别人的通知一律当作不存在。
- App：底栏“消息”从占位页换成真实页面：未登录提示登录；列表按原型的样式，未读有红点；点一条标为已读并进到方案确认、履约或交付验收页；“全部已读”；底栏角标显示未读数（每个根页面出现时刷新，超过 99 显示 99+）。
- 登录回跳白名单加了“消息”“我的”两个底栏页（不带任何参数）。“我的”页的登录现在回到“我的”，不再落到订单中心。
- 没有做的：客服会话、交易 / 活动 / 系统通知、通知设置、推送、下拉刷新（要改路由表，按规范要跑 Android 真机，先不动；进入页面时会重新读取）。
- 推进脚本里的进度时间原来比当前时间多 1 秒（照抄自冒烟脚本），导致“现场完成”排在“报告已交付”后面，改成用当前时间。

### 15.5 验证

| 项 | 结果 |
| --- | --- |
| 后端 | 全量 424 条通过（A 步结束时 377 条）。新增：资料接口和适配器、商城搜索筛选、首页聚合和“今天”逻辑、站内信服务和 SQL、转发器、通知文案、各接口的登录要求 |
| 真实数据库 | 商城搜索和每种筛选逐个对着本地库查过；转发器启动后把库里已有的 32 条事件全部转发，生成 23 条通知，0 条失败；又用推进脚本新走了一张预约，6 条通知按发生顺序出现；用服务商账号读、标客户的通知，分别得到空列表和 404 |
| App 单测 | 类型检查通过；760 条通过（A 步结束时 741 条） |
| 开发版 E2E | 全量 228 条通过、6 条跳过。新增：`mall.spec.ts` 6 条（搜索、筛选、价格区间、320 宽）、`message.spec.ts` 7 条（含一条用开发账号真实登录）、`mine.spec.ts` 1 条；`home-recommend.spec.ts` 两条改为对新接口 |
| 公开版 | 构建和防泄漏检查通过，6 条公开版用例通过 |
| 预览 | 商城搜索、筛选面板在浏览器预览里看过；消息页有 375 / 320 截图（`.planning/message/visual/`） |
| 没有验的 | Android、iOS 真机；模拟器这一轮没有再走；转发器只在单实例下跑过 |

### 15.6 请过目

1. **公开版现在也有真实的“消息”页**（原来是“消息功能尚未开通”）。它只显示巡检进度，巡检本来就是公开的，所以没有加开关；如果你想先藏着，说一声。
2. 转发器第一次启动会把库里积压的事件都变成通知。现在只有开发数据，没有影响；正式库上线这一版之前如果已经有真实订单，要先定这些历史事件要不要通知。
3. 一条事件如果一直转发失败（比如对应的订单被删了），会每 10 分钟重试一次，不会自己放弃。没有做告警。
4. “服务团队已安排”每次服务商修改安排都会发一条。

### 15.7 接下来

§14.2 的 C 步（免费活动报名）要先定三条规则：未成年人怎么报名、监护人确认文案、免费报名的取消时限。D 步要定购物车规则，E 步要定支付渠道和收款主体。这几项没定之前不往下做。

---

## 16. C 步：免费活动报名（2026-11-17，用户回复“按原型先做”，未提交）

§14.2 的 C 步。三条规则用户选了“按原型先做”：未成年人由监护人账号添加为子女后代报；监护人确认文案先用原型里的，等法务给了再换；免费报名开场前可以取消。

### 16.1 做完能看到什么

`首页 → 赛事研学 → 无人机开放体验日 → 选场次 → 立即报名 → 登录（需要时）→ 填写报名 → 报名凭证 → 我的 → 活动报名 → 取消`，全程真实接口。

| 页面 | 路由 | 依据的画板 |
| --- | --- | --- |
| 填写报名 | `/pages/activity/enroll` | Signup |
| 参与人 | `/pages/activity/participants` | Participants |
| 报名凭证 | `/pages/activity/ticket` | EnrollTicket |
| 我的报名 | `/pages/mine/activity-enrollments` | EnrollList |
| “我的”里的“我的参与 · 活动报名”和“参与人管理”一行 | `/pages/mine/index` | Mine |

### 16.2 规则

1. 只有免费活动能在线报名。收费、询价的活动仍然只给提示。
2. 参与人两种：本人（账号持有人，按成年人处理，第一次打开参与人时按账号显示名自动建好）和子女（未成年，由监护人添加并勾选确认）。只收姓名和出生年月，不收证件号码和手机号。一个账号最多 10 个子女。
3. 年龄按出生年月算周岁，按场次当天算。活动限定了年龄时，不够或超过的孩子不能勾选；活动的年龄上限不到 18 岁时，本人不能报（显示“陪同不用报名”）。
4. 一次报名最多 10 人，每人一张凭证、一个凭证号（`ACT-` 加 12 位随机字符）。同一个人同一场次只能有一张有效凭证；取消后可以重新报。
5. 名额只在服务端锁住场次那一行之后增减，不够时整单拒绝。重复提交（同一个幂等键）返回第一次的报名单，不多占名额。
6. 场次开始前可以取消，可以只取消其中几张；已签到的不能取消。全部取消后报名单进“已取消”。
7. 报名单三种状态（待参加、已完成、已取消）和凭证的“已过期”由服务端按场次时间算，不落库。
8. 核销：主办或承办机构的操作员按凭证号核销，只在签到开放时间（没设置时是开场前 1 小时）到场次结束之间；别的机构的凭证当作不存在；重复核销返回第一次的时间，不改数据。
9. 报名、取消、核销都记一条状态变化记录（谁、什么时候）。

### 16.3 接口和数据表

契约 `contracts/openapi-activity.yaml` 1.1.0，新增十个接口，都要登录：

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/activity-participants?sessionId` | 我的参与人；带场次时附上每个人能不能报和原因 |
| `POST /api/v1/activity-participants` | 添加子女 |
| `PUT /api/v1/activity-participants/{id}` | 改名 |
| `PUT /api/v1/activity-participants/{id}/photo-use` | “活动照片可公开使用”的单独授权和撤回 |
| `DELETE /api/v1/activity-participants/{id}` | 删除子女（本人不能删） |
| `POST /api/v1/activity-enrollments` | 报名，带 `Idempotency-Key` |
| `GET /api/v1/activity-enrollments?status&page&size` | 我的报名 |
| `GET /api/v1/activity-enrollments/{id}` | 一张报名单和它的凭证 |
| `POST /api/v1/activity-enrollments/{id}/cancel` | 取消全部或其中几张 |
| `POST /api/v1/activity-checkins` | 主办方操作员核销 |

参与人和报名要客户角色，核销要服务商操作员角色。账号只从令牌里取，不是自己的参与人、报名单一律当作不存在。

迁移 `backend/modules/activity/migrations/V002__activity_enrollment.sql`：`activity_participant`、`activity_enrollment`、`activity_enrollment_ticket`、`activity_enrollment_event`。

### 16.4 没有照原型做的

| 原型上有 | 这一步怎么处理 | 原因 |
| --- | --- | --- |
| 候补（已满场次“加入候补”、候补转正） | 不做，已满场次在报名表里是灰的 | 候补转正的规则（自动转正还是要确认、超时多久）没有定，原型里又是和付款绑在一起的 |
| 团队报名、收费报名、退款、改期 | 不做 | 团队和收费在 ④c，等统一交易模块 |
| 常用联系人（给别的成年人报名） | 不做 | 要存别人的手机号，和手机号登录、短信渠道一起定（G 步） |
| 证件“已登记 / 未登记” | 不显示，也不收证件 | 没有哪个活动要用，证件号码是敏感资料 |
| 本人一行的手机号和“更换” | 只显示“账户持有人” | 账号现在没有手机号 |
| “加入日历” | 不显示 | H5 做不了，App 上要原生日历权限，留到真机阶段 |
| “活动前一天将发送提醒” | 改成“到场出示二维码签到” | 没有做活动提醒，不能这么承诺 |
| 凭证页“开始后 30 分钟停止入场（示例）” | 不显示 | 原型标了“示例”，没有这条规则 |
| “查看结果记录”“联系主办方”“查看退款进度” | 失效的凭证只给“查看活动详情” | 这三个去处都还没有 |
| 页签“已退改” | 写成“已取消” | 免费报名没有退款和改期 |
| 进度条四步（已支付、报名成功、签到、完成） | 三步，没有“已支付” | 免费 |
| 《儿童个人信息保护规则》、“报名须知与照片使用说明” | 文字照原型，点了提示“协议正文待提供” | 正文没有，不能编一份 |
| “资料使用说明”第三条“活动结束后名单按规定期限保存” | 没有放；前两条按实际情况改写 | 没有做到期清理，不能这么承诺 |
| 原型的二维码是占位图案 | 生成真的二维码，内容是凭证号 | 假的码不能交付。项目里没有二维码库，自己写了一个只支持短 ASCII 文本的标准编码器（版本 2、纠错 M），用 macOS 的识别器实扫过 8 个样本 |

### 16.5 开发环境

- 第三个示例账号 `dev-organizer`：示例场馆“东营低空与机器人体验馆”的操作员，只用来核销。和另外两个账号一样写在 `R__dev_identity_accounts.sql`。
- `infra/scripts/dev-checkin-ticket.cjs <凭证号>`：用 `dev-organizer` 核销一张凭证，只对本机后端运行，不绕过签到时间窗口。
- 活动示例数据每天重排场次时，“已报人数”会加上本机真实报名、还没取消的凭证数，名额不会对不上。

### 16.6 验证

| 项 | 结果 |
| --- | --- |
| 后端 | 全量 566 条通过（上一步 424）；之后又加了 1 条凭证排序的 SQL 测试，活动模块单独重跑通过，合计 567 条。新增：报名服务 60、SQL 与迁移 12、凭证号 4、控制器 54、契约对照改写为 13，以及安全、开发数据几条。后端测试和契约由子代理写，我自己重跑并核对过 |
| 真实数据库 | 用脚本对本地后端逐项查了 40 项：参与人增删改、年龄规则（在 8–15 岁的挑战赛场次上查）、报名、同一个键重放、同一个键换内容、重复报名、名额增减、只取消一张、开场后不能取消、别的机构核销不了、已取消的凭证核销不了、核销和重复核销、结束后进“已完成”、状态变化记录。签到窗口是把一个示例场次临时挪到当时来验的，验完挪回并清掉了数据 |
| App 单测 | 794 条通过（新增 34 条），类型检查通过 |
| 二维码 | 8 个样本（1 到 26 个字符）渲染成图片后用 macOS 的识别器全部读回原文；其中一个的图样固定在单测里 |
| E2E | 见 §16.7 |
| 公开版 | 构建和防泄漏检查通过；“我的”里没有两个新入口（PUB-007） |
| 截图 | `.planning/activity-enroll/visual/` 四个页面 × 320 / 375 / 430，无横向溢出；`.planning/style-compare/enroll/` 四个画板和页面 390 宽各一张 |
| 真机 | 没测。改了路由表（四个新页面），按 AGENTS.md §15 应跑 Android 路径，没有设备。出生年月用的是系统选择器，App 上是原生的，只在浏览器里验过 |

### 16.7 E2E

开发版全量 237 条通过，另有 7 条公开版用例在开发版里跳过；公开版构建上那 7 条单独跑通过。

新增 `e2e/activity-enroll.spec.ts` 9 条，连跑三遍都通过：

| 用例 | 内容 |
| --- | --- |
| ENR-001 | 游客从首页点进开放体验日，选场次，登录后回到报名表，给本人报名，看凭证，在“我的 → 活动报名”里取消，进“已取消” |
| ENR-002 | 参与人：添加子女（不勾监护人确认不能保存）、重复添加被拒、照片授权、改名、刷新后仍在、删除、本人不能删 |
| ENR-003 | 本人和孩子一起报名，凭证上本人在前、一人一码，只取消孩子那一张，再从列表取消整张 |
| ENR-004 | 已经报过这一场的人勾不上，换一场可以 |
| ENR-005 | 收费活动只给提示，直接打开报名表也进不去 |
| ENR-006 | 没登录直接打开四个页面，都先到登录页 |
| ENR-007 | 不存在或别人的报名单打不开 |
| ENR-008 | “已签到”“已过期”两种凭证的样子（接口桩，因为签到要等到场次当天） |
| ENR-009 | 四个页面在 320 / 375 / 430 宽不横向溢出，并留截图 |

用例发现并改掉的两个问题：

1. 参与人面板的层级比 H5 的日期选择器高，选择器弹出来被面板挡住。面板层级降到选择器之下。
2. 一张报名单里的凭证是同一时刻生成的，原来按生成时间和随机 ID 排，页面上两个人的先后不固定。改成本人在前、其余按姓名。

### 16.8 请过目

1. **监护人确认和报名须知现在只有一句话和一个勾**，协议正文没有。频道和报名入口都只在预览版，公开版看不到；正文到位之前不建议放开。
2. **“本人”按成年人处理**，不问出生年月。活动只限成年人以上的年龄（比如 16 岁以上）时本人可以报；限未成年的不能报。
3. **只知道出生年月**，周岁按月份算，生日当月就算满了，比按日算最多早不到一个月。主办方现场还可以再核对。
4. **加了第三个示例账号 `dev-organizer`** 和一个核销脚本。密码和另外两个一样是公开的，dev 配置仍然只能用在本机或内网。
5. **示例数据里只有一个免费活动**（开放体验日，不限年龄），所以页面上看不到“未满 8 岁”这种情况；年龄规则是用接口在挑战赛的场次上验的。要在页面上看，得再加一个限年龄的免费示例活动，会动到已有的列表用例，没有加。
6. 删除子女不检查他有没有还没开始的报名：凭证保留报名时的名字，照常有效。删掉再用同样的名字加回来，会被当成另一个人，可以在同一场再报一次。影响很小（免费名额），先记着。
7. 没有结束时间的场次，按开始时间当作结束：开场后既不能签到，报名单也直接进“已完成”。示例场次都有结束时间；正式录入活动时结束时间应当必填。

### 16.9 接下来

D 步（购物车到“提交订单”之前）要先定购物车规则：租赁和询价商品能不能进购物车、跨商家怎么拆单。E 步要定支付渠道和收款主体。

---

## 17. D 步：购物车到“提交订单”之前（2026-11-17，用户回复“按原型做”，未提交）

§14.2 的 D 步。购物车规则用户选了“按原型做”：租赁和询价商品不进购物车，一次只结算一个商家（不同商家分开下单）。

### 17.1 做完能看到什么

`首页 → 商城 → 商品 → 加入购物车（规格与数量）→ 底栏“购物车” → 选一个商家结算 → 确认订单（添加或选择收货地址、选配送方式）→ 提交订单`为止，全程真实接口。商品页的“立即购买”也走同一个确认订单页。

| 页面 | 路由 | 依据的画板 |
| --- | --- | --- |
| 规格与数量（商品页底部弹层） | `/pages/mall/product` | PdSku |
| 购物车（底栏） | `/pages/cart/index` | Cart、CartStates |
| 确认订单 | `/pages/cart/checkout` | Checkout、CheckoutStates |
| 收货地址 | `/pages/mine/addresses` | AddressList、RegionPick |

### 17.2 规则

1. 只有按件标价的“购买”商品能进购物车。租赁（按天、半天计价）和询价（没有价格）的商品不进，商品页也不显示“加入购物车”。
2. 购物车的一行是“某个商家的某个规格”，同一样再加一次只加数量，最多 99 件；一个账号最多 50 行。
3. 购物车不存价格。每次打开都按当前价格、当前城市重新判断：商品下架、商家不卖了、改成租赁或询价、当前城市不卖，都放进“失效商品”，可以一键清空。
4. 按商家分组，一次只结算一个商家：点一个商家就全选它的商品，其他商家的商品写“与已选商家不同，需分开结算”。“管理”里可以跨商家勾选删除，删除前要确认。
5. 底栏“购物车”的角标是当前城市能买的商品有几种（不按件数）。
6. 确认订单页的内容每次向服务端重新算：商品、数量、单价、金额、可选的配送方式。快递：每样商品都能快递时才有；到店自提：几样商品的自提点相同时才有。运费取各商品快递运费的最大值，自提为 0（示例数据都是 0）。
7. 快递时用收货地址：选过的那条，否则用默认地址；一条都没有时页面提示先添加，“提交订单”不能用。
8. “提交订单”这一步不建订单：勾了《交易协议》、有地址后，点了提示“下单和付款要等支付渠道确定后开放”。
9. 收货地址只给主人读写。第一条自动成为默认；默认只能通过把别的地址设为默认来换；删掉默认后最早添加的一条接上。最多 20 条。手机号只收中国大陆手机号，页面显示时打码。

### 17.3 接口和数据表

契约 `contracts/openapi-mall-cart.yaml`、`contracts/openapi-account.yaml`，都要登录、都要客户角色：

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/mall/cart?cityCode` | 购物车（按商家分组 + 失效商品 + 能买的种数） |
| `GET /api/v1/mall/cart/count?cityCode` | 底栏角标 |
| `POST /api/v1/mall/cart/items` | 加入购物车 |
| `PUT /api/v1/mall/cart/items/{id}` | 改数量 |
| `POST /api/v1/mall/cart/remove` | 删除几行 |
| `POST /api/v1/mall/cart/remove-invalid` | 清空失效 |
| `GET /api/v1/mall/checkout-preview` | 确认订单的内容（来自购物车几行，或商品页的一个供给），只读 |
| `GET/POST /api/v1/account/addresses`、`PUT/DELETE /api/v1/account/addresses/{id}` | 收货地址簿 |

迁移：`backend/modules/mall/migrations/V002__mall_cart.sql`（在售商家加上能否快递、自提点、快递运费三列；新表 `mall_cart_item`）；新模块 `backend/modules/account`，迁移 `V001__account_address.sql`（`account_address`）。

### 17.4 没有照原型做的

| 原型上有 | 这一步怎么处理 | 原因 |
| --- | --- | --- |
| 提交订单后进收银台 | 只给提示 | 要等支付渠道 C1、收款主体 C2 |
| 交易主体里的“收款方”“开票方”“售后责任”，确认订单的“售后保障”“发票由销售方开具” | 只显示销售方和发货履约 | 收款方要等 C2；发票开具对接一期暂缓；商品售后规则 A1 没有定，不能先承诺 |
| 省、市、区县三级选择 | 只到地级市，区县写在详细地址里 | 平台的行政区划目录只有省和地级市；完整的区县数据要另外引入，没有擅自下载 |
| 地址卡片上的“企业”标签、收货点名称 | 不做 | 企业组织地址要和企业身份一起定 |
| 规格弹层里选规格、选配送方式 | 规格只显示（每个商品现在只有一个规格）；配送方式在确认订单里选 | 配送方式要和地址放在一起才说得清 |
| “10 件以上建议走企业采购询价”“按 N 件询价” | 不显示 | 询价单不在这一步 |
| “暂时无货” | 不做 | 商品数据里没有库存 |
| 失效商品的“切换城市”按钮 | 不做，只写“当前城市不可售” | 切城在首页 |
| 商家名可点进商家主页 | 不可点 | 商家主页在 ⑥ |
| 定位一键填地区 | 不做 | 定位换算到地级市以下没有数据 |
| 购物车里改规格 | 不做 | 只有一个规格 |

### 17.5 开发数据

- 商城示例数据：本地门店（示例科创器材店）的商品只能到店自提；示例机器人商家东营仓的“编程机器人探索套装”快递、自提都行，其余商家只能快递。运费都是 0。
- `dev-customer` 的购物车和地址簿开始是空的，验收时自己加。

### 17.6 验证

| 项 | 结果 |
| --- | --- |
| 后端 | 全量 720 条通过（上一步 567）。新增：地址服务 28、地址 SQL 与迁移 7、购物车服务 31、购物车 SQL 与迁移 7、两个控制器 51、两份契约对照 24，以及安全、Flyway、开发数据几条。测试和两份契约由子代理写，我自己重跑过 |
| 真实数据库 | 用脚本对本地后端逐项查了 37 项：地址的默认规则和省市补全、非法手机号和未知城市、服务商账号读不到；租赁、询价、外城商品加不进购物车；同一样再加合并、封顶 99；按城市判断失效；预览的配送方式、自提、金额、两个商家被拒、失效商品被拒、立即购买、没有地址时的提示；商家下架后进失效、清空失效。另外 5 个并发的“新增第一条地址”请求都成功，只有 1 条默认 |
| App 单测 | 815 条通过（上一步 794），类型检查通过 |
| E2E | 开发版全量 248 条通过，另有 8 条公开版用例在开发版里跳过；公开版构建上那 8 条单独跑通过。新增 `e2e/mall-cart.spec.ts` 11 条（见下） |
| 公开版 | 构建和防泄漏检查通过；底栏“购物车”仍是“尚未开通”，没有角标（PUB-008） |
| 截图 | `.planning/mall-cart/visual/` 购物车、确认订单、地址、规格弹层 × 320 / 375 / 430，无横向溢出；`.planning/style-compare/cart/` 四个画板和页面 390 宽各一张 |
| 真机 | 没测。加了两个路由；地址里的输入框和省市选择在 App 上的键盘、滚动只在浏览器里看过 |

新增用例：CART-001 游客点购物车先登录再回来；CART-002 商品加购、按商家结算、改数量、没地址时添加（省市选择）、到“提交订单”为止；CART-003 器材店立即购买只能自提；CART-004 快递和自提切换；CART-005 管理里跨商家删除；CART-006 失效商品和清空（接口桩）；CART-007 两个商家一起结算被拒；CART-008 租赁商品没有加入购物车；CART-009 没登录打开确认订单和地址先登录；CART-010 截图；CART-011 换地址、换上的地址被删后退回默认地址。

做的过程中改掉的：

1. 已选中的商家再点一次圆圈，会把它的商品全部取消；原型里是保持全选。改成和原型一样，取消全选用底部“全选本商家”。
2. 子代理指出两个并发问题：同一账号同时新增地址或同时换默认地址，会撞上“只有一条默认”的约束返回 500；地址 20 条、购物车 50 行的上限在并发下可能被突破。地址和购物车的写操作改成先按账号取事务级锁再检查。
3. 自提点比较前没去首尾空格，改了。
4. 在地址页删掉了确认订单正在用的地址再返回，页面会显示“没有找到要结算的商品”。改成退回默认地址。
5. 购物车页头补上浅色渐变、商家名后补上“到店自提 / 可自提”标签、结算栏贴住底栏；确认订单和地址页的页头同样补上渐变；地址页的“编辑”按原型加图标和分隔线。

### 17.7 请过目

1. **地区只到地级市**。完整的省市区县数据（约 3000 条）要从国家统计局或民政部的行政区划代码引入。需要的话说一声，我把来源和许可情况列给你再定。
2. **确认订单页的“留言”只留在页面上**，没有保存，因为没有订单可存。提交订单接上以后一起保存。
3. **运费规则只有“一单取最大运费”这一条**，示例数据都是 0。“满 99 包邮”这类规则没有做，商家的运费规则怎么定要等正式商家入驻。
4. **购物车按账号存在服务端**，不支持没登录时先加购物车、登录后合并。原型里未登录的购物车页也是提示登录。
5. **公开版的底栏“购物车”仍是“尚未开通”**：购物车做到确认订单为止，付不了钱，不算闭环。
6. **同一个商品，加购和立即购买的拒绝说法不完全一样**：已下架或外城的商品，加入购物车返回“资源不存在”，立即购买返回“有商品已失效”。都拦得住，只是错误码不同，契约按现状写了。

### 17.8 接下来

E 步（统一交易模块：收银台、支付回执、标准退款、对账导出）要先定支付渠道 C1 和收款主体 C2。之后才能把“提交订单”接上。

---

## 18. 四个已知问题的修复（2026-11-18，用户回复“允许修改这 4 个”，未提交）

验收清单 §9 的 1–4 条。每个都先写能复现的用例，确认修复前失败、修复后通过。

| # | 问题 | 根因 | 改动 | 证据 |
| --- | --- | --- | --- | --- |
| 1 | 时段查询接口不带开始日期返回 500 | 缺少必填查询参数时框架抛出的异常没有被全局异常处理归类，落进了兜底的 500 | `GlobalExceptionHandler` 把这个异常归到“请求参数无效”400（1 个文件 2 行）。巡检目录接口缺城市时报 500 也是同一个原因，一起好了 | `InspectionTimeSlotControllerTest.missingStartDateOrCityIsABadRequest`：修复前 500，修复后 400；本地后端实测两个接口都返回 400 |
| 2 | 首页第一次打开要等定位有结果才显示城市和下半部分 | 决定城市前一直等系统定位返回，没有超时；系统权限弹窗没人点或定位服务卡住时会一直等 | 首页的首次定位加 6 秒超时，超时按定位失败处理，用默认城市（1 个文件约 6 行） | E2E HOME-901 把浏览器定位改成永远不回应：修复前停在“选择城市”，修复后显示东营 |
| 3 | 首页往上滑，内容从状态栏下面穿过 | 首页头部跟着页面滚走，状态栏那一条没有底色 | 首页顶部加一条固定的、和状态栏一样高的底色（1 个文件约 12 行）；浏览器里高度是 0，没有变化 | E2E HOME-902 把状态栏高度设成 44px 模拟 App：修复前顶部是页面内容，修复后是底色条；iOS 模拟器截图 `.planning/ios-sim-walk/23-home-scrolled-after-fix.png` |
| 4 | 服务详情没有评分时，评价行开头多一个“·” | 每一项前面都固定加了分隔点 | 只在前面已经有一项时才加（1 个文件 2 行） | E2E SDR-109 用接口桩把评分改成空：修复前以“·”开头，修复后是“0条评价 · 已服务…” |

明确没有改：首页定位成功后的流程、城市面板里的“重新定位”、其他页面的页头、评价区块本身、各接口自己的参数校验。

回归：后端全量 721 条通过；App 类型检查通过、单测 815 条通过；开发版 E2E 251 条通过（另 8 条公开版用例跳过）。真机没测，状态栏那一条尤其要在真机上再看。

修复 2 的副作用：如果用户 6 秒后才点“允许定位”，这一次不会再切到定位到的城市，需要在城市面板里点“重新定位”。

---

## 19. E 步方案：统一交易模块（2026-11-18 用户回复“按方案开工”，§19.7 的建议值一并确认）

用户 2026-11-18 定了：支付渠道 C1 = 微信支付；收款主体 C2 = 钱直接进商家自己的商户号，平台不经手资金。AGENTS.md §17 要求交易这种里程碑先确认范围、接口、状态机和数据表再写代码，这一节就是要确认的方案。验证等级 L4。

### 19.1 微信支付用哪种产品（查了官方文档）

| 项 | 结论 | 出处（pay.weixin.qq.com/doc/v3/partner/…） |
| --- | --- | --- |
| 多商家平台用什么 | “平台收付通”：基于服务商模式，专为电商场景设计。商家作为“二级商户”直接收款，资金进各自的账户 | 4012086891 |
| 收款方 | 二级商户。普通支付时，收银台显示的就是商家自己 | 4012086891 |
| 怎么开通 | 先成为普通服务商，再申请平台收付通（提交平台资料，微信审核）。**注意：已经用普通服务商模式给任何商户进过件的，不能再开收付通**，所以一开始就要按收付通申请 | 4012086921 |
| 商家怎么进来 | 平台调“二级商户进件”接口替商家提交资料，审核 1–3 个工作日，通过后得到商家的子商户号 | 4012713017、4012086921 |
| App 下单 | `POST /v3/pay/partner/transactions/app`，带服务商号、子商户号、商户订单号（≤32 位）、金额（分）、回调地址；返回预支付 ID，2 小时有效 | 4012714669 |
| App 调起 | 服务端把 appId、partnerId、prepayId、Sign=WXPay、随机串、时间戳和签名交给 App，App 用微信 SDK 调起；签名用平台的 API 证书私钥 | 4012090168 |
| 结果以谁为准 | 以支付回调和服务端查单为准，App 端的返回不可靠 | 4012090168、4012088031 |
| 支付回调 | 回调要验签、用 APIv3 密钥（AES-256-GCM）解密、核对金额，5 秒内回 200/204；同一通知可能重复到达，要幂等；没收到回调要主动查单 | 4012082568、4012082320 |
| 关单 | `POST /v3/pay/partner/transactions/out-trade-no/{单号}/close`；先查单，未支付才关 | 4012760574 |
| 订单有效期 | 可设 1 分钟到 15 天 | 4012714669 |
| 退款 | 交易后一年内可退 | 4012086921 |
| 对账 | T 日账单 T+1 日 10 点后可下载（`GET /v3/bill/tradebill`） | 4012088031、4012082568 |
| 以后上小程序 | 同一套，下单换成 `/v3/pay/partner/transactions/jsapi` | 4012088031 |

E01 时补查到的（同样出自官方文档）：

| 项 | 结论 | 出处 |
| --- | --- | --- |
| 查单 | `GET /v3/pay/partner/transactions/out-trade-no/{单号}?sp_mchid&sub_mchid`；状态 SUCCESS / REFUND / NOTPAY / CLOSED | 4012760568 |
| 支付回调 | 事件 `TRANSACTION.SUCCESS`；解密后核对服务商号、子商户号、商户单号、金额；5 秒内回 200/204；失败时按 15s、15s、30s、3m、10m、20m、30m、30m、30m、60m、3h、3h、3h、6h、6h 重发，最多 15 次；回调可能重复 | 4012090195 |
| 退款 | `POST /v3/ecommerce/refunds/apply`：子商户号、退款单号（≤64 位，重试沿用原单号）、退款金额和原订单金额；可部分退款、可多次，一单最多 50 次，合计不超过订单金额，一年内；返回只代表受理，结果以查询或回调为准 | 4012476892 |
| 退款查询 | `GET /v3/ecommerce/refunds/out-refund-no/{退款单号}?sub_mchid`；状态 SUCCESS / CLOSED / PROCESSING / ABNORMAL（异常要人工处理） | 4012476911 |
| 退款回调 | 事件 `REFUND.SUCCESS` / `REFUND.ABNORMAL` / `REFUND.CLOSED`，验签、解密和重试同支付回调 | 4012124635 |
| App 调起签名 | 对“appId\n时间戳\n随机串\nprepayId\n”做 SHA256withRSA（下单用的同一张 API 证书私钥），Base64 | 4012365868 |

还要在联调时确认的：App 签名以 SDK 实测为准（另一页文档列的签名字段不同）；没有分账标记的订单是否完全不冻结（文档只写了带分账标记会冻结 180 天）；回调验签的时间戳容差。

### 19.2 这一步做什么

| 做 | 不做（仍暂缓或另定） |
| --- | --- |
| 统一订单台账：商城订单（D 步的确认订单接上“提交订单”） | 收费报名、到馆预约、巡检付款节点：各自接入时再做（顺序见 19.7） |
| 微信 App 支付：下单、调起、回调、查单兜底、关单 | 合单支付（一次付多个商家）：按原型一次只结算一个商家，用不上 |
| 标准退款：未发货前买家取消全额退款；商家受权接口发起退款 | 售后申请、退货寄回、争议（A1 未定） |
| 对账导出：按日下载微信账单，与平台订单逐笔核对，平台运营受权接口导出差异 | 分账、佣金、钱包、保证金、发票（一期暂缓） |
| 商家收款账户登记：商家 ↔ 子商户号，受权接口或运维脚本录入 | 二级商户进件的页面（没有管理后台；一期先在微信的服务商后台人工进件） |
| 发货：商家受权接口填物流单号（像巡检的服务商接口，没有页面），附开发脚本 | 物流轨迹查询 |

### 19.3 订单和支付的状态

```text
订单  PENDING_PAYMENT ──付款成功──▶ PAID ──商家发货──▶ SHIPPED ──买家确认或到期──▶ COMPLETED
         │                         │ (自提：商家核销取货 ──▶ COMPLETED)
         ├─超时未付/买家取消─▶ CLOSED   └─发货前买家取消─▶ REFUNDING ──退款成功──▶ REFUNDED
                                                       └─退款失败──▶ 人工处理（REFUND_FAILED）
支付单 CREATED ──拿到预支付──▶ PREPAID ──回调或查单成功──▶ SUCCEEDED
                       └─关单/超时──▶ CLOSED     （金额不符 ──▶ MISMATCH，告警，不改订单）
退款单 REQUESTED ──提交微信──▶ PROCESSING ──回调或查询──▶ SUCCEEDED / FAILED
```

- 订单状态只由服务端改：付款只认验签后的回调或主动查单，App 说“付好了”只触发一次查单。
- 一张订单同时只有一个有效支付单；重新付款前先查单、再关旧单。
- 所有命令带幂等键；回调按微信的通知 ID 和交易号去重。

### 19.4 数据表（新模块 `backend/modules/trade`）

| 表 | 内容 |
| --- | --- |
| `trade_order` | 订单号、买家、商家、业务来源（MALL）、状态、商品金额、运费、应付、配送方式、有效期、版本、幂等键 |
| `trade_order_line` | 下单时的商品快照：商品、规格、商家供给、单价、数量、小计 |
| `trade_order_delivery` | 收货地址快照（姓名、手机号、省市、详细地址）或自提点；物流公司和单号 |
| `trade_merchant_account` | 商家 ↔ 微信子商户号、状态（未开通的商家不能下单） |
| `trade_payment` | 商户订单号（out_trade_no）、子商户号、金额、预支付 ID、微信交易号、状态、付款时间 |
| `trade_refund` | 退款单号、金额、原因、状态、微信退款号 |
| `trade_channel_notification` | 收到的每一条回调：通知 ID、类型、验签结果、处理结果（只存摘要，不存明文个人信息） |
| `trade_reconciliation` | 每日账单导入结果和差异（平台有微信没有、金额不一致等） |
| `trade_state_transition` | 状态变化记录，谁、什么时候、从哪到哪 |

金额一律存分（long）。下单时把商品、价格、地址都做快照，之后改价、删地址不影响已下的单。

### 19.5 接口（契约 `openapi-trade.yaml`，确认后写）

| 接口 | 谁用 |
| --- | --- |
| `POST /api/v1/trade/orders`（幂等键；内容同确认订单预览的输入） | 买家：提交订单 |
| `GET /api/v1/trade/orders`、`GET /api/v1/trade/orders/{id}` | 买家：我的订单、订单详情 |
| `POST /api/v1/trade/orders/{id}/payments` | 买家：发起微信支付，返回 App 调起参数 |
| `POST /api/v1/trade/orders/{id}/payment-check` | 买家：App 回来后让服务端查一次单 |
| `POST /api/v1/trade/orders/{id}/cancel` | 买家：未付款取消；已付款未发货取消即退款 |
| `POST /api/v1/trade/orders/{id}/receipt` | 买家：确认收货 |
| `POST /api/v1/trade/channels/wechat/notifications`（公开，验签） | 微信：支付和退款回调 |
| `POST /api/v1/provider/trade/orders/{id}/shipment`、`/refunds`、`/pickup` | 商家操作员：发货、退款、自提核销（受权接口，没有页面） |
| `GET /api/v1/platform/trade/reconciliation?date=` | 平台运营：对账结果导出 CSV |

App 页面：收银台（Cashier）、支付结果（PayResult）、我的订单里的“商品”分类和商品订单详情（OrderGoods），按原型画板做。

### 19.6 要你那边准备的（开工不等这些，联调要等）

1. 用国内主体申请微信支付**服务商号**，再申请**平台收付通**；在微信开放平台创建移动应用并和服务商号绑定（拿到 appid）。
2. 证书和密钥：服务商 API 证书（私钥和序列号）、APIv3 密钥、微信支付公钥和公钥 ID。只能放在服务器的密钥配置里，不进代码和仓库。
3. 至少一个测试用的二级商户（进件通过，拿到子商户号）。微信支付 APIv3 没有通用的沙箱，联调要用真实商户号小额（如 0.01 元）付款再退款。
4. 一个外网能访问的 HTTPS 地址收回调（开发环境也要，可以先用测试服务器）。
5. 这些到位之前，我按文档写好并用单元测试覆盖签名、验签、解密、状态机；渠道调用部分只能在联调时验证，不会用假渠道冒充“已支付”。

### 19.7 要你定的规则（有建议值）

| 规则 | 建议 |
| --- | --- |
| 未付款订单多久关闭 | 30 分钟（微信最少 1 分钟） |
| 已付款未发货，买家能否自己取消 | 能，直接全额原路退款 |
| 发货后多久自动确认收货 | 10 天 |
| 发货后的退款 | 只能由商家发起（受权接口），售后流程等 A1 |
| 商家没有登记子商户号时 | 商品照常展示，确认订单页提示“该商家暂未开通在线支付”，不能提交 |
| 之后接入的顺序 | 商城订单 → 收费活动报名 → 到馆预约 → 巡检付款节点（巡检还要先定 C3） |

### 19.8 任务拆分（确认后按此做，每步结束跑对应回归）

| 任务 | 内容 | 等级 |
| --- | --- | --- |
| E01 | 契约 `openapi-trade.yaml`、迁移、AGENTS.md 登记 `trade` 模块 | L3，停下确认 |
| E02 | 订单：提交订单（来自确认订单）、我的订单、取消、超时关闭 | L3 |
| E03 | 微信渠道适配：请求签名、回调验签和解密、下单、查单、关单、退款；全部有单元测试（用文档里的格式和自造的密钥对） | L4 |
| E04 | 支付：发起支付、回调处理、查单兜底、金额核对、幂等 | L4 |
| E05 | 发货、自提核销、确认收货、退款（受权接口 + 开发脚本） | L4 |
| E06 | 对账：账单下载、逐笔核对、差异导出 | L4 |
| E07 | App：收银台、支付结果、商品订单列表和详情；App 调起微信支付需要 App 原生配置（manifest 里的微信支付模块和 appid），只能真机验证 | L4 |
| E08 | 回归与联调记录：没有商户号之前，联调部分标为“未验证” | L4 |

### 19.9 E01 完成情况（2026-11-18，用户回复“确认，做 E02”）

| 项 | 结果 |
| --- | --- |
| 契约 | `contracts/openapi-trade.yaml` 0.1.0 草案：14 个操作（买家 7、微信回调 1、商家 4、平台 2），32 个结构；解析和引用检查通过 |
| 迁移 | 新模块 `backend/modules/trade`，`V001__trade.sql` 建 10 张表（§19.4 的 9 张，对账拆成汇总和差异两张）；已注册到 pom 和 Flyway，排在身份和巡检迁移之后 |
| 迁移实测 | 在本地 PostgreSQL 里建了一个临时库，先跑身份模块的迁移再跑这份：合法订单能写入；应付和商品金额对不上、自提收运费、一张订单两张未关闭的支付单、非法的微信单号、已付款没有付款时间、退款超过已付、快递缺地址，都被约束拒绝。临时库已删除，开发库没有动 |
| 规范 | AGENTS.md V2.14 登记 M14 `trade`（草案） |
| 测试 | 后端全量 727 条通过（新增迁移文本检查 4 条、Flyway 注册 2 条） |
| 代码 | 只有迁移，没有订单、支付的代码（从 E02 开始） |

请过目：
1. **契约里每个接口的形状**，特别是提交订单要带页面上看到的应付金额（对不上就拒绝、让页面重新预览），以及商家、平台那几个受权接口。
2. **数据表**：订单里存下单时商家的子商户号快照，之后商家换账户也按原账户退款；回调表只存摘要，不存解密后的内容。
3. **下一次重新打包后端时，这份迁移会套到本地开发库上**。如果确认时要改表结构，我会在本地删掉这几张表重新建，不影响别的数据。
4. **确认后从 E02（订单）开始**：E02 不需要商户号就能做完并测试；E03、E04 的渠道部分在没有商户号之前只能用单元测试覆盖签名、验签、解密，不会用假渠道冒充付款成功。

### 19.10 E02 完成情况（2026-11-18，用户回复“做 E03”，未提交）

做了什么（只有服务端，App 的“提交订单”仍走未开通提示，页面在 E07）：

| 项 | 结果 |
| --- | --- |
| 提交订单 | `POST /api/v1/trade/orders`：内容和确认订单预览相同（购物车几行，或立即购买一个供给），服务端借商城的预览重新算一遍。拒绝顺序：商家没有可用收款账户 → 配送方式不可用 → 快递没地址 → 应付和页面看到的不一样。通过后存商品、单价、地址、商家子商户号快照，订单号 `MO`+北京日期+8 位数字，30 分钟内付款，下单的购物车行删掉 |
| 幂等 | 同一买家同一 `Idempotency-Key`、同样内容重放返回原单（200），内容不同 409；同一买家的提交排队（事务锁），不同买家抽到同一个单号时靠号码锁错开 |
| 我的订单 | 列表按页签（全部 / 待付款 / 待发货 / 待收货 / 已完成 / 退款）过滤，新的在前；详情回 ETag；别人的单当作不存在 |
| 取消 | 只限待付款，带 `If-Match` 版本；已经关闭的再取消原样返回。已付款的取消要退款，在 E05 |
| 超时关闭 | 每分钟一次，关掉过了付款截止时间的待付款单（每次最多 100 张，多实例用 SKIP LOCKED 错开），记状态迁移 |
| 商家收款账户 | `PUT /api/v1/platform/trade/merchant-accounts/{organizationId}`，只给平台运营角色；开发种子给三家示例商家登记了假的子商户号，示例无人机厂家、行业代理、租赁商家没登记，用来测“商家收不了款” |
| 契约 | `openapi-trade.yaml` 写明已实现的 5 个操作；按实现补了提交、取消的 ETag 和详情、取消的 400；去掉取消的“原因”（表里没有地方存）；页码上限 10000、应付下限 1 分 |

验证：

| 项 | 结果 |
| --- | --- |
| 后端全量 | 870 条通过（E01 时 727；新增服务 54、映射器 SQL 10、控制器 44 + 9、契约对照 12、商城适配 8、定时任务 4、登录与角色 2 条） |
| 本地库实测 | 脚本跑过 27 项：未登录 401、商家账号 403；自提立即购买、重放、同键改内容、金额变化、快递买只能自提的、商家没登记、没勾协议、缺幂等键；购物车下快递单（缺地址被拒、加地址后成功、购物车清空、子商户号快照、删地址后订单地址不变）；页签、他人订单、不存在的订单；旧版本取消 412、取消、重复取消；把截止时间改到过去后定时任务关单，迁移记录是 SUBMIT、TIMEOUT。另测超大页码、0 元都是 400。测试订单已删除 |
| App 回归 | 购物车与确认订单 E2E 11 条通过（App 代码没改） |
| 没验证 | 平台登记收款账户的成功路径只有控制器测试，开发环境没有平台运营账号 |

写测试时查出、已经在本步改掉的：超大页码算偏移量溢出成 500；0 元提交撞数据库约束成 500；不同买家同时抽到同一个单号会撞唯一约束；取消的“原因”收了但没存；契约漏写 ETag 和 400；测试环境里关单定时任务没关。

留着没改的取舍：关单每分钟最多 100 张，积压时分几轮关完；异常类型里的“无权”目前没有地方用（别人的单一律当作不存在）。

下一步 E03（微信渠道适配：签名、验签、解密、下单、查单、关单、退款的请求和解析），L4。没有商户号之前只能用文档格式和自造密钥对做单元测试。

### 19.11 E03 完成情况（2026-11-18，用户回复“做 E04”，未提交）

做了什么（只有服务端的渠道适配，没有接口和页面变化；E04 才把它接到“发起支付”和回调接口上）：

| 项 | 结果 |
| --- | --- |
| 渠道端口 | `trade/application/PaymentChannel`：App 下单、App 调起参数、查单、关单、申请退款、查退款、解析回调。渠道只负责发请求、验签、解密、翻译结果，不改订单；商户号、单号、金额的核对在 E04 的支付服务里做 |
| 失败分三种 | 可重试（网络、超时、微信 5xx、SYSTEM_ERROR / BANK_ERROR / FREQUENCY_LIMITED）；被拒绝（带微信错误码，如 NOT_ENOUGH、OUT_TRADE_NO_USED）；不可信（验签不过、格式不对、成功却缺交易号或金额）。不可信的内容不会让任何单子变成已付款或已退款 |
| 请求签名 | 5 行签名串（方法、带查询串的路径、时间戳、随机串、请求体），服务商 API 证书私钥 SHA256withRSA；Authorization 里 mchid 填服务商号 |
| 应答和回调验签 | 3 行签名串（时间戳、随机串、原文），只认配置的微信支付公钥 ID（PUB_KEY_ID_…），平台证书序列号一律不认；签名时间和本机差 5 分钟以上不认；签名探测流量照常验证、自然不通过 |
| 回调解密 | APIv3 密钥 AES-256-GCM，密文末尾 16 字节是校验标签；解密前先验签；只处理 TRANSACTION.SUCCESS 和 REFUND.*，别的事件验签通过后照样回成功、不解密 |
| 下单内容 | 按 4012714669：sp_appid、sp_mchid、sub_mchid、描述（按字符截到 127）、支付单号、北京时间的最晚付款时间、回调地址、`settle_info.profit_sharing=false`、金额（分，CNY） |
| App 调起 | appId 用 sp_appid、partnerId 用服务商号、package 固定 Sign=WXPay；签名串 4 行（appId、时间戳、随机串、prepay_id），与下单同一把私钥 |
| 退款 | 收付通 `/v3/ecommerce/refunds/apply`，按商户订单号退；原因按字符截到 80，空的不传；受理不等于成功，以查询或回调为准 |
| 配置与密钥 | `trade.wechat.*`，默认关闭；关闭时没有支付渠道（E04 回“渠道不可用”，不会退回假渠道）。两个 PEM 只给服务器上的路径，APIv3 密钥只走环境变量，仓库里只有空占位；开启但缺项时启动失败，报错只说缺哪一项，不带密钥和路径 |
| 依赖 | 没有新的第三方库：JDK 的 HttpClient 和加解密，Jackson（项目已在用，交易模块里声明一下） |

验证：

| 项 | 结果 |
| --- | --- |
| 单元测试 | 新增 106 条：签名 5、验签 17、解密 7、密钥与配置校验 9、渠道 62（测试里扮一个“假微信”：用商户公钥核对我们发出的签名，用自造的平台私钥给应答和回调签名，用自造的 APIv3 密钥加密回调）、Spring 配置 6 |
| 测试能否抓错 | 故意把请求签名串末尾的换行去掉、把验签的公钥 ID 检查拿掉，各有测试失败（共 9 条），已还原 |
| 后端全量 | 976 条通过（E02 时 870） |
| 本地启动 | 重新打包重启后正常起来（渠道关闭），E02 的订单冒烟脚本再跑一遍全部通过，测试订单已删 |
| 没验证 | 和真实微信的任何一次调用。没有服务商号、证书和子商户号之前做不了；测试用的都是现生成的密钥，只能证明“按文档的格式做对了”，不能证明微信认 |

联调时要确认的（文档没写清，或两页写法不一）：

1. App 调起：4012714669（平台商户的下单页）和 4012090168（App 调起页，标的是普通服务商）适用对象不一致；现在按“appId 用 sp_appid、partnerId 用服务商号”做，用真机调起一次就知道。
2. 不带分账标记（`profit_sharing=false`）的订单，钱是否直接结算给商家、不冻结。
3. 签名时间容差：文档没给数，先定 5 分钟（`TRADE_WECHAT_SIGNATURE_TOLERANCE` 可调）。
4. 错误应答是否总带签名头：带了就必须验过；完全没带（比如网关直接回的 502）就只按状态码判断能否重试，不会据此改状态。
5. 关单遇到已付款的单，文档只给了通用的 403 TRADE_ERROR；E04 本来就是先查单、确认未付才关。
6. 一期只接“微信支付公钥”模式。如果开通的账户还在用平台证书，需要先在商户平台申请公钥。

下一步 E04（支付：发起支付、回调接口、查单兜底、金额核对、幂等），L4。

### 19.12 E04 完成情况（2026-11-18，用户回复“做 E05”，未提交）

做了什么（只有服务端；App 的收银台和支付结果页在 E07）：

| 项 | 结果 |
| --- | --- |
| 发起支付 | `POST /api/v1/trade/orders/{id}/payments`：订单要是自己的、待付款、没过截止时间。一张订单同时只有一张进行中的支付单，重复发起沿用它、每次重新签调起参数；上次没拿到预支付的用同一个商户单号再下单。新单号是 `P`+北京日期+12 位数字 |
| 调微信时不占锁 | 先在短事务里建支付单，事务外调微信，再在另一个短事务里记预支付；微信超时不会把订单行锁住，回调也不会因此超过 5 秒 |
| 入账只有一个入口 | 回调和查单的结果都要核对服务商号、子商户号、商户单号、金额（分）、币种，全对上才把订单改成已付款并记状态迁移（PAYMENT_NOTIFIED / PAYMENT_QUERIED）。对不上的支付单记为 MISMATCH 并告警，订单不动；同一笔交易重复到达只入账一次；一张订单第二笔成功的付款记 MISMATCH 等人工退款；关单后才到的付款把订单重新打开成已付款 |
| App 回来查一次单 | `POST /api/v1/trade/orders/{id}/payment-check`：只看微信的结果，App 说的支付结果一概不信 |
| 回调 | `POST /api/v1/trade/channels/wechat/notifications`，不登录，按原始字节验签（限 64KB）。按微信通知 ID 去重，重复的回 204；验签不过 401，不落库；处理失败 500 让微信重发；金额不符也回 204（重发没用）。退款回调在 E05 处理，现在只记录 |
| 取消 | 发起过支付的单先向微信查单：付了就入账并拒绝取消（409）；没付就先在微信关单，再关订单；微信不通时 503，不盲关 |
| 定时兜底（每分钟） | 发起支付 1 分钟后还没结果的去查单（回调丢了也不漏单）；过了截止时间、还有进行中支付单的订单先查单，付了入账，没付在微信关单再关订单。没配置渠道时不关、只记日志。原来的超时关单任务只关从没发起过支付的单 |
| 契约 | 写明 E04 已实现的三个接口；补上发起支付和查单的 400、查单的 ETag、取消的 503、回调的 413；“每次发起支付用新单号”改成“同时只有一张进行中的支付单，关掉后再发起才用新单号”；App 每次点“去支付”用新的幂等键 |
| 本机联调工具 | 不进仓库：`.planning/stack/fake-wechat/server.mjs` 模拟微信（下单、查单、关单，应答和回调按真实格式签名加密，可以“替买家付款”、重发回调、故意付错金额）；`FAKE_WECHAT=1 sh .planning/stack/start-backend.sh` 让后端接它。为此接口地址和回调地址允许本机回环的 http，其他地址仍只收 https |

验证：

| 项 | 结果 |
| --- | --- |
| 后端全量 | 1102 条通过（E03 时 976；新增支付服务 79、支付 SQL 10、回调接口 11、支付任务 4 等 126 条） |
| 本地库 + 模拟微信 | 脚本跑过 41 项：发起支付（调起参数用商户私钥签名、截止时间、发给微信的金额 / 子商户号 / 不分账 / 北京时间截止）、重复发起沿用、付款回调入账、重复回调只入账一次、伪造回调 401 且不落库、App 回来查单入账、金额不符不改订单、取消没付的（微信那边也关了）、取消其实已付的（拒绝并入账）、过期单拒绝发起、定时任务对过期单查单后关单或入账、回调丢失时定时查单入账。测试数据已删 |
| E02 回归 | 订单冒烟 27 项重跑通过 |
| 没验证 | 真实微信。模拟服务是按文档写的，和真实微信的差别只有联调能发现 |

写测试时查出、已经在本步改掉的：拿预支付期间订单被关，微信那边刚建的单没关（现在顺手关掉）；回调记录在重试或并发时可能被后来的结果覆盖（现在只写第一次的结果）。

留着没改的（记一下）：

1. 预支付比订单先过期时发起支付回 409。现在预支付 1 小时 55 分、付款期 30 分钟，走不到；以后如果把付款期调到 2 小时以上要改成“查单、关旧单、开新单”。
2. 一个幂等键对应的支付单关掉后再用这个键发起回 409，App 每次点“去支付”要用新键（契约已写）。
3. 入账时不会顺手关同一订单的其他进行中支付单；按现在的流程一张订单同时只有一张，没找到能走到的路径。

下一步 E05（发货、自提核销、确认收货、退款），L4。

### 19.13 E05 完成情况（2026-11-18，用户回复“做 E06”，未提交）

做了什么（只有服务端和开发脚本；App 的订单页、确认收货按钮在 E07）：

| 项 | 结果 |
| --- | --- |
| 迁移 V002 | 自提单记取货码输错次数（最多 10 次）；状态迁移原因加 REFUND_REJECTED（微信当场拒绝退款）；退款兜底用的索引 |
| 取货码 | 自提单付款入账时生成 6 位随机数字，只生成一次；只给买家看，商家看不到 |
| 商家接口（受权，没有页面） | `GET /api/v1/provider/trade/orders`：本机构卖出的订单，可按状态筛；配送信息里多一个完整手机号（发货要用），买家接口为空。`POST .../shipment`：已付款的快递单填物流公司和单号 → 已发货，同样的单号再发一次原样返回。`POST .../pickup`：核对取货码 → 已完成，输错记一次，连续错 10 次锁定，买家找客服。`POST .../refunds`：见下 |
| 确认收货 | 买家 `POST /api/v1/trade/orders/{id}/receipt`：已发货 → 已完成；发货满 10 天没确认的，每分钟的任务自动确认 |
| 买家取消已付款的单 | 已付款、未发货（未自提）的取消即退款：把还没退的钱全退，订单进入退款中；同一个幂等键只建一笔退款 |
| 商家退款 | 已付款、已发货、已完成或上次退款异常的订单；可以部分退，最多 50 次、付款后一年内，合计不超过实付减已退减还占着钱的退款；退的是剩下的全部时订单进入退款中，部分退款不改订单状态；同一个幂等键、同样内容重放返回同一笔 |
| 退款结果 | 只认微信的退款回调或主动查询，核对子商户号、商户订单号、退款金额，全对上才算；到账才累加已退金额，退完订单变已退款。微信当场拒绝、退款关闭或异常时，如果这是全额退款，订单变“退款异常”，人工处理；异常的退款以后人工处理成功也能转成到账 |
| 定时兜底 | 微信不通或限频没提交成功的退款，1 分钟后用同一个退款单号重新提交（微信保证同一单号只退一次）；处理中的 1 分钟查一次，异常的 1 小时查一次 |
| 开发账号和脚本 | 开发种子加 `dev-merchant`（示例机器人商家的操作员，这家的编程机器人套装快递、自提都行）；`infra/scripts/dev-merchant-order.cjs` 列订单、发货、核销、退款，只对本机后端运行 |
| 本机模拟微信 | 加了申请退款、查退款、退款回调，和“给出退款结果 / 下一次退款直接拒绝”的控制入口 |
| 契约 | 写明 E05 已实现的 5 个接口和规则；配送信息加 recipientPhone；补 400、ETag；物流公司、单号、退款原因最短 1 个字 |

验证：

| 项 | 结果 |
| --- | --- |
| 后端全量 | 1277 条通过（E04 时 1102；新增退款服务 61、商家接口 22、迁移和种子检查等） |
| 本地库 + 模拟微信 | 脚本跑过 43 项：商家只看得到本机构的单、完整手机号只给商家；发货、重复发货、换单号拒绝、旧版本 412；买家确认收货；部分退款不改状态、再退完变已退款、重放同一笔、超额拒绝；取货码只给买家、输错计数、输错 10 次后连对的也拒绝、核销完成；取消已付款的单即全额退款、重复取消不建第二笔、重复回调只累加一次；微信拒绝退款后订单“退款异常”、商家再退成功；退款异常；退款回调丢失时定时查询到账；发货满 10 天自动确认。测试数据已删 |
| 回归 | E04 支付冒烟 41 项、E02 订单冒烟 27 项重跑通过 |
| 开发脚本 | 列订单、发货、退款、报错路径都试过 |
| 没验证 | 真实微信的退款；App 页面（E07） |

写测试时查出、已经在本步改掉的：退款“异常”的那笔在人工处理后仍可能退成功，原来算可退金额时没扣它，商家可能把同一笔钱再退一次（现在扣掉，并加了“累计超过实付就不入账、告警”的兜底）；退款结果缺子商户号、订单号或金额时原来直接放过，现在和支付一样当作对不上。

留着没改的（记一下）：

1. 定时查询和重提退款没有加跨实例的锁，多实例部署时可能重复调微信；靠退款单号幂等和状态条件，结果不会错。上多实例前再加。
2. 部分退款到账也会让订单版本加 1，买家或商家手里的旧版本会过期，下一次操作回 412，刷新即可。
3. 商家完整手机号的读取没有单独留访问记录；有商家工作台以后再加。

下一步 E06（对账：下载微信账单、逐笔核对、导出差异），L4。

### 19.14 E06 完成情况（2026-11-18，用户回复“做 E07”，未提交）

做了什么（只有服务端；没有页面，平台运营用接口导出 CSV）：

| 项 | 结果 |
| --- | --- |
| 账单（查了官方文档 4012760667、4012124894、4013080599、4013080592、4054257727） | 按子商户申请某一天的交易账单（全部：支付和退款，不压缩）；“账单不存在”当作那天没有交易；“账单生成中”过一会儿再试。下载请求要签名，应答不带微信签名，按申请时给的 SHA1 对原始字节校验；下载地址只认微信自己的域名，签名请求不发给别的主机 |
| 解析 | 按表头列名取值（早期格式少两列也能读），去掉每个字段前的反引号和文件开头的 BOM；金额从元换成分，只认“整数或最多两位小数”；每行列数要和表头一致，明细笔数、订单总金额、退款总金额要和汇总行对上，否则整份不信 |
| 分天 | 都按北京时间的自然日：支付按付款成功时间，退款按微信受理时间（迁移 V003 给退款单加了受理时间，受理时记下） |
| 逐笔核对 | 支付按商户订单号、退款按商户退款单号找平台的单据。四种差异：微信有平台没有；平台有微信没有；金额不一致；状态不一致（平台记的是没付成、没受理、已关闭，或账单说退款失败而平台记为到账）。只记差异并告警，不自动改订单 |
| 结果 | 一天一个子商户一条：导入成功（IMPORTED）或失败（FAILED，记原因，下一轮再试）；导入成功的不会被之后一次失败的重跑盖掉。要对账的子商户是这一天结束前登记了收款账户的，加上那天有付款或退款受理的 |
| 平台接口 | `GET /api/v1/platform/trade/reconciliation?date=&format=JSON|CSV`：这一天全部子商户都导入成功才给，否则 409；CSV 带 BOM，Excel 直接能开，以 = + - @ 开头的格前面加单引号。`POST .../reconciliation/runs?date=&redo=`：立刻对某一天跑一次（导入失败后重试、差异处理后重新核对），只能对今天以前的日期 |
| 定时任务 | 北京时间每小时 5 分和 35 分：10 点前只补前天，10 点后补前天和昨天。没有配置支付渠道时什么也不做 |
| 本机模拟微信 | 加了交易账单（按文档的 27 列格式和反引号、汇总行、SHA1），和“账单生成中 / 删一行 / 改金额 / 多一行 / 文件被改坏 / 记录挪到前一天 / 清空”的控制入口 |

验证：

| 项 | 结果 |
| --- | --- |
| 后端全量 | 1463 条通过（E05 时 1277；新增账单解析 59、对账服务 52、渠道账单 35、平台接口等） |
| 本地库 + 模拟微信 | 脚本跑过 23 项：造一天的单（两张对得上，其中一张带一笔部分退款；一张账单里没有；一张金额被改；一张平台记为金额不符；外加一笔平台没有的），没导入时 409、今天不能对账、账单生成中记失败、第二次全部导入、匹配 3 笔和 4 种差异各一条、CSV 下载（文件名、BOM、元）、再跑跳过、文件被改坏的重跑失败但不盖掉之前的结果。临时建的平台运营账号和测试数据已删 |
| 回归 | E05 冒烟 43 项、E04 41 项、E02 27 项都在这个版本上重跑通过 |
| 没验证 | 真实微信的账单：文件是否带 BOM、汇总行的确切位置、退款行“订单金额”是否为 0，文档没写清，要拿真实账单确认 |

写测试时查出、已经在本步改掉的：新登记一个商家会让以前每一天都变成“未就绪”（现在只算那天结束前登记的）；意外错误不留失败记录（现在记 FAILED，原因只写错误类型）；BOM 和科学计数法、负数金额；平台有微信没有的退款差异缺商户订单号；一个用不上的索引条件。

留着没改的（记一下）：

1. 一个商户都没有、也没有交易的日子，结果接口一直回 409（没有任何结果可给）。
2. 商家换子商户号时，收款账户的登记时间沿用第一次的，换号前的日子可能因为新号变成“未就绪”，要手动对一次。
3. 账单整份读进内存，和其他接口共用 10 秒读超时；商户交易量大了以后再改成流式。
4. 日期参数按 ISO 格式解析，带时区后缀的（如 2026-11-17Z）也会被接受，时区被忽略。

下一步 E07（App：收银台、支付结果、商品订单列表和详情），L4；调起微信支付要 App 原生配置，只能真机验证。

### 19.15 E07 完成情况（2026-11-18，用户回复“做 E08”，未提交）

做了什么（App，只在预览构建；公开构建里新页面只显示“商品订单功能尚未开通”，“我的”里没有入口）：

| 页面 | 结果 |
| --- | --- |
| 确认订单 | “提交订单”真的建单：同一份内容重试沿用同一个幂等键，配送方式、地址、留言或应付变了换新键。价格变了、地址或配送方式不对时提示并重新预览；商家没开通在线支付时底栏写明“该商家暂未开通在线支付”、按钮停用。成功后直接换成收银台（返回不再回到确认订单） |
| 收银台 `/pages/trade/cashier`（画板 Cashier） | 应付、到截止时间的倒计时、订单号（可复制）、收款方、商品、支付方式“微信支付”。点支付：服务端发起支付拿到调起参数，App 用 `uni.requestPayment` 调起微信；不管调起结果如何都进支付结果页，由服务端查单决定。浏览器里调不起来，提示“请在 App 中完成微信支付”。过了截止时间显示“支付已超时”；“取消支付”确认后关闭订单 |
| 支付结果 `/pages/trade/pay-result`（画板 PayResult） | 进页就让服务端查单，还是待付款时每 2 秒再查、最多 5 次；已付款显示金额、订单号、收款方、支付时间；查完仍未付款显示失败态（“重新支付”“刷新结果”）。接口没有渠道交易号，不显示 |
| 商品订单 `/pages/mine/goods-orders`（画板 OrderList 的商品卡片） | 全部 / 待付款 / 待发货 / 待收货 / 已完成 / 退款六个页签、分页、下拉刷新、空态；待付款“取消订单”“继续支付”，已发货“查看订单”“确认收货”。报名、服务、租赁卡片和分类筛选不在这一步 |
| 商品订单详情 `/pages/mine/goods-order`（画板 OrderGoods） | 按状态的标题和说明（待自提显示取货码和自提点；已发货显示物流和“发货 10 天后”的自动确认日期），地址（手机号打码）、商家和商品、金额明细（有退款时显示已退）、退款记录、支付信息、订单信息。底栏：待付款“取消订单”“去支付”；已付款未发货“取消订单”（确认框写明货款原路退回）；已发货“确认收货” |
| “我的” | “我的订单”卡右上加“商品订单 ›”；巡检订单的五个阶段角标没动 |

和画板的差异（截图在 `.planning/style-compare/e07-*`）：没做画板里的交易主体卡、售后条款、联系商家、申请售后、配套课程“去学习”、预计送达和发货仓（不在范围或接口没有）；支付方式写“微信支付”（画板是占位）；订单列表标题叫“商品订单”，只有状态页签。

后端顺手修的：退款中、已退款或已取过货的自提单，服务端原来仍把取货码给买家；现在只在“已付款、待自提”时给。

验证：

| 项 | 结果 |
| --- | --- |
| 前端单元测试 | 853 条通过（新增交易 38 条） |
| 类型检查、公开构建防泄漏检查 | 通过 |
| 开发版 E2E 全量 | 259 条通过、9 条跳过（新增 `trade-orders.spec.ts` 8 条：主流程 从商品详情立即购买 → 提交 → 收银台 → 支付 → 模拟微信付款回调 → 已支付 → 商家发货 → 确认收货 → 已完成；待付款取消；自提取货码和取消即退款到账；没开通在线支付的商家；页签和空态；收银台取消和超时；320/375/430 宽；390 宽比对截图）。改了已有的 CART-002：提交订单现在进收银台 |
| 公开版 E2E | 9 条通过（新增 PUB-009） |
| 后端 | 全量 1463 条通过；改完取货码后重新打包，交易和购物车 E2E 19 条、E05 冒烟 43 项重跑通过 |
| 没验证 | Android / iOS 真机（状态栏、安全区、新图标），以及 App 里真的调起微信支付 |

要你那边准备、或待定的：

1. **App 原生配置**：manifest 里要加微信支付模块和微信开放平台移动应用的 appid（iOS 还要 Universal Links）。appid 还没有，这次没加；加了以后只能真机验证。
2. **“用户取消支付”的判断**：现在按 `uni.requestPayment` 返回信息里含 cancel 判断，文档没写错误码，要真机确认。不管判断对不对，订单状态都以服务端查单为准。
3. **关闭原因**：已关闭的订单分不出是买家取消还是超时关闭，页面只能写“已关闭”。要分开显示的话，契约加一个关闭原因字段（服务端已有状态迁移记录，取得到）。

下一步 E08：回归与联调记录。没有商户号之前，联调部分标为“未验证”。

### 19.16 E08 完成情况（2026-11-18，未提交；用户同时说“做每个阶段前不要我再确认了，你直接都做完”）

| 项 | 结果 |
| --- | --- |
| 联调记录 | 新文档 `docs/redesign/05-微信支付联调记录.md`：联调前要准备的 8 项（服务商号、appid、证书、APIv3 密钥、微信支付公钥、外网回调地址、子商户号、平台运营账号）、17 个联调用例、12 条要在联调时确认的事，全部标“未验证” |
| 回归工具收进仓库 | 模拟微信 `infra/dev/fake-wechat/server.mjs`（测试密钥现生成在被忽略的 `.runtime/fake-wechat/`）；四份冒烟 `infra/scripts/trade-smoke/{order,payment,fulfilment,reconciliation}.cjs` 和公共的 `lib.cjs`；用法写在 `infra/README.md` |
| 独立复查 | 让一个没参与开发的子代理只读复查交易模块后端：没有发现同一笔钱入账两次、多退、越权读写的高危问题；查出 3 个中等、5 个低等问题，本步全部改掉（见下） |
| 关闭原因 | 订单加 `closeReason`（买家取消 / 超时关闭，只在已关闭时给）；App 的列表、详情、收银台、支付结果按原因显示“订单已取消”或“订单已超时关闭”（§19.15 待定第 3 条） |
| iOS 模拟器走查 | iPhone 17 模拟器（iOS 26.3）的 WKWebView 替身里打开收银台、商品订单、待自提详情、已发货详情、支付结果：状态栏和灵动岛、底部安全区没有遮挡，没有横向溢出。截图 `.planning/ios-sim-walk/e08-*.png`。这是替身不是 App |

复查后改掉的：

1. 渠道的签名错误、无权限这类配置或暂时性错误，原来会被当成“明确拒绝”把退款单关掉、订单转退款异常；现在只有确定的业务拒绝码（待联调确认）才关闭，其余留着重试，而且只关闭还没提交成功的那一笔，不会关掉已受理的。
2. 定时兜底原来按截止时间排队，几张一直查不动的单会永远占住队首；现在每查一次（成功、失败或还没付）都记下时间，按时间轮转。退款查询失败时也一样。
3. 商家名称快照原来比机构名短（120 对 160），名字长的商家一下单就报错；迁移 V004 放宽到 160。
4. 用来下结论的错误应答（查单“订单不存在”、查退款“退款单不存在”、账单“账单不存在”）现在必须带微信签名并验过，否则按“稍后重试”。
5. 发起支付拿到预支付后再核对一次订单状态：期间订单被关了就在微信关单，不把调起参数交出去。
6. 0 元商品提交时直接拒绝（400），不再撞数据库约束。
7. App“查一次单”同一张单 2 秒内只真正向微信查一次。
8. 商家接口只在付款之后给完整手机号和地址；待付款、已关闭的单只给打码手机号。

验证（这一轮的最终结果）：

| 项 | 结果 |
| --- | --- |
| 后端全量 | 1503 条通过 |
| 前端单元测试、类型检查 | 856 条通过；类型检查通过 |
| 开发版 E2E 全量 | 259 条通过、9 条跳过（和 E07 一致） |
| 公开构建 | 防泄漏检查通过；公开版 E2E 9 条通过 |
| 交易冒烟（新包 + 模拟微信） | 订单 27 项、支付 41 项、履约退款 43 项、对账 23 项全部通过；测试数据已清 |
| 没验证 | 真实微信（全部，见联调记录）；Android（这台电脑上没有 adb 和设备）；iOS 只在 WKWebView 替身里看过，没有在 App 基座里跑过 |

交易模块一期（E01–E08）到这里做完了，剩下的都要等外部条件：微信支付服务商号、证书、子商户号和移动应用 appid 到位后按联调记录逐项联调；App 的微信支付原生配置要有 appid 才能加，并且只能真机验证。§19.7 里“之后接入的顺序”（收费活动报名、到馆预约、巡检付款节点）在 AGENTS.md §3 里仍是未确认范围，没有做。

---

## 20. E09 收费活动报名接入交易模块（2026-11-19，用户说“按照计划继续后面的作业一直做，别问我”）

§19.7 用户确认的接入顺序是：商城订单 → 收费活动报名 → 到馆预约 → 巡检付款节点。这一步做第二项。用户要求不再停下来问，规则按 2026-11-05 确认为默认规则的设计假设（`Batch5Spec` 第 3 节第 1、3、4 条）和本节的取舍做，等用户看过再调。

### 20.1 规则

1. **能在线付款的活动**：按人收费（`PER_PERSON`）、单价大于 0、主办机构登记了微信子商户号。主办机构没登记的，报名表照常显示，底部写“主办方暂未开通在线支付”，不能提交。询价活动仍只给提示。
2. **提交报名 = 占名额 + 建交易订单**：订单来源 `ACTIVITY`，收款方是活动的主办机构（钱进它自己的子商户号，和商城一样平台不经手），金额 = 单价 × 人数。页面带上它看到的应付金额，和服务端算的不一致时拒绝，让页面重新读活动。
3. **付款时限 15 分钟**（设计假设 3），且不晚于报名截止或场次开始。超时或买家取消 → 订单关闭 → 报名单“已关闭”、凭证作废、名额释放。
4. **待付款**：凭证已经生成但不显示二维码、不能核销；这几个人在这一场仍然算“已报名”，不能重复报。
5. **付款成功只认验签后的回调或服务端查单**（和商城一样）→ 凭证生效、显示二维码。
6. **关闭之后才到账的付款**：场次没开始、名额够、这些人没有在这一场另报 → 恢复报名；否则整笔自动原路退款（报名单保持“已关闭”）。
7. **退改**（设计假设 1、4）：场次开始前可以取消其中几位，每位按报名时的单价原路退款；已签到的不能退。设计假设 4 写的“由谁确认待业务定”，这里先按“提交即退、不用主办方确认”做（和商城未发货取消即退款一致）。凭证和报名单上显示每位的退款进度：退款中、已退款、退款异常。
8. 改期、候补、团队报名、结果记录仍不做。
9. 收费报名的订单不出现在“商品订单”里，也不能用商家的发货、核销、退款接口操作；钱只能由报名单的退改退回。

### 20.2 怎么接（模块边界）

- **交易模块**多一个“业务来源”的入口：业务模块在自己的事务里建订单（`TradeSourceOrders.open`）、为几位参与人建退款（`requestRefund`，事务提交后再交给渠道）；订单付款、关闭时回调业务模块（`TradeSourceListener.onPaid / onClosed`，和改订单状态在同一个事务里）。`onPaid` 返回“收不下”时交易模块自动全额退款（退款发起方 `SOURCE`）。
- **活动模块**只认自己的端口 `ActivityPaymentPort`（建订单、建退款、查退款进度、取消未付款订单、锁订单）；启动模块把两边接起来。两个模块互不依赖。
- **加锁顺序**：凡是同时动交易订单和报名单的路径，一律先锁交易订单、再锁报名单和场次（交易回调本来就是先锁订单）。
- **未付款时取消报名**走交易的买家取消（会先向微信查单、关单），关闭后由回调释放名额。

### 20.3 数据表

- 交易迁移 `V005__trade_sources.sql`：`trade_order.source` 加 `ACTIVITY`；加 `source_ref`（业务单 ID，商城为空，其余必填，同一来源唯一）；`delivery_mode` 加 `NONE`（非商城只能是它，不建配送行）；`merchant_kind` 只在商城必填；退款发起方加 `SOURCE`。
- 活动迁移 `V003__activity_paid_enrollment.sql`：报名单加 `PENDING_PAYMENT`、`CLOSED` 两个状态和关闭时间、单价、金额、交易订单 ID、付款截止时间；凭证加 `PENDING_PAYMENT` 状态、取消原因、退款 ID；状态变化记录加付款、关闭、恢复、退款几类。

### 20.4 接口

- `POST /api/v1/activity-enrollments` 增加 `expectedAmountMinor`（收费活动必填）；返回的报名单带 `payment`（状态、交易订单 ID、金额、单价、付款截止时间）。
- `POST /api/v1/activity-enrollments/{id}/cancel`：待付款的关闭订单；已付款的按凭证退款。
- 报名单的凭证带 `refund`（状态、金额）。
- 交易订单多 `source`、`sourceRef`；买家的商品订单列表只列商城订单。
- 新错误码：`ACTIVITY_AMOUNT_CHANGED`、`ACTIVITY_PAYMENT_UNAVAILABLE`。

### 20.5 App

- 报名表：收费活动显示单价 × 人数，按钮“提交并支付” → 收银台（复用 E07 的收银台）→ 支付结果 → “查看凭证”。
- 报名凭证：待付款（倒计时、去付款、取消报名）、已关闭、已退款几种样子；取消时写明每位退多少钱。
- 我的报名：待付款的标“待付款”并能去付款；已关闭的进“已取消”。

### 20.6 验证（L4）

后端全量、活动和交易的新测试；前端单测和类型检查；开发版 E2E 全量（新增收费报名用例，付款用模拟微信）；公开版构建和防泄漏；新冒烟脚本 `infra/scripts/trade-smoke/activity.cjs` 对着本机后端和模拟微信走：报名 → 付款 → 凭证生效 → 退一位 → 退款到账 → 超时关闭释放名额 → 关闭后到账的恢复和自动退款。真实微信和真机仍未验证。


### 20.7 完成情况（2026-11-19，已提交）

| 项 | 结果 |
| --- | --- |
| 服务端 | 交易迁移 `V005__trade_sources.sql`、活动迁移 `V003__activity_paid_enrollment.sql`；交易模块的业务来源入口（`TradeSourceOrders`、`TradeSourceListener`，退款在事务提交后交给微信）；活动报名的付款端口和回调；活动详情和列表卡片的 `price.onlinePayment`；开发种子给示例主办机构登记了子商户号 |
| 契约 | `openapi-activity.yaml` 1.2.0、`openapi-trade.yaml` 0.2.0 |
| App | 报名表“提交并支付”→ 收银台 → 支付结果 →“查看凭证”；凭证的待付款、已关闭、退款中 / 已退款；我的报名“待付款”；收银台和支付结果按订单来源决定去处，交易模块不引用活动模块 |
| 冒烟 | `infra/scripts/trade-smoke/activity.cjs` 33 项全过（报名、金额核对、重放、付款、退一位、未付款取消、超时关闭、关闭后到账的恢复和自动退款） |
| 截图 | `.planning/activity-paid/visual/` 六个页面 × 320 / 375 / 430 |

和 §20.1 不一样、或者这一步才定下来的：

1. 示例活动的退改说明原来写“开始 48 小时前可免费取消，之后不退”，和服务端实际规则（开场前都能退）矛盾，改成了“活动开始前可在线取消，取消几位按人数原路退回报名费（示例）”。正式录入活动时，这段文字要和规则一致；如果业务要“开场前 48 小时”这类时限，得改成结构化的截止时间，不能只写在说明里。
2. 待付款时取消只能整单取消，不能只取消其中几位。
3. 自动退款（关闭后才到账）的原因写“订单已关闭，付款自动原路退回”，没有操作人。
4. 活动订单号以 `AO` 开头（商城 `MO`）。
5. 收银台“参与人”一行显示成“人数 N 人”：交易订单里不存参与人姓名。
6. 按人收费但单价为 0 的活动仍然不能在线报名（当作配置错误）。

---

## 21. E10 到馆预约接入交易模块（2026-11-19，连续推进）

§19.7 接入顺序的第三项。依据修订版原型 VenueDetail、VenueBook、CashierVenue、VenueCode 和设计假设第 3、7、8 条；没定的按本节取舍做，等用户看过再调。

### 21.1 规则

1. **能预约的**：场馆已发布、项目启用、场次没开始且有余位。项目标价大于 0 的要付款，场馆运营机构必须登记了微信子商户号（没登记的确认页写“场馆暂未开通在线支付”，不能提交）；标价为 0 的提交即预约成功。
2. **参与人**：复用“参与人”（本人和子女，和活动报名同一份名单），按项目的最低年龄在场次当天算周岁；一单最多 10 人，不超过余位。同一个人同一场次只能有一张有效预约。
3. **一单一码**（设计假设 7）：付款成功后生成一个核销码（`VEN-` 加 12 位随机字符），一单几个人共用。
4. **付款时限 15 分钟**（设计假设 3），不晚于场次开始；超时或买家取消 → 预约关闭、名额释放。关闭之后才到账的付款，场次没开始、余位够就恢复，否则整笔自动退款（同 §20.1 第 6 条）。
5. **改期**（设计假设 8）：开场前可以改到同一项目的另一场次（要有余位），立即生效，不用场馆确认；旧码作废、换新码；同项目同价，不补差价。
6. **取消**：开场前可以取消，整单原路全额退款（一单一码，不拆人）。设计假设 8 里“开场前 [协议约定时间]”的时限没定，先按“开场前都可以”做。
7. **没到馆**：场次结束后预约变“已过期”，不自动退款。设计假设 8 写“未到馆怎么收费待业务定”，这一条最需要你定。
8. **核销**：场馆运营机构的操作员按核销码核销（受权接口，没有页面），开场前 30 分钟到场次结束之间；整单一次核销；别的机构的码当作不存在；重复核销返回第一次的时间。
9. 到馆订单不出现在“商品订单”里，也不能用商家接口操作。场馆取消场次的全额退款、到馆提醒不做（没有运营端，也没有推送）。

### 21.2 怎么接

和 §20.2 一样：体验模块定义自己的付款端口和参与人端口，启动模块分别接到交易模块（订单来源 `VENUE`，收款方是场馆运营机构）和活动模块的参与人名单。加锁顺序同样是先交易订单、后预约和场次。

### 21.3 数据表

- 体验迁移 `V002__venue_booking.sql`：`venue_booking`（账号、场馆、项目、场次、状态、核销码、人数、单价、金额、交易订单、付款截止、幂等键、核销时间和操作员、版本）、`venue_booking_participant`（报名时的姓名、关系、年龄快照）、`venue_booking_event`（预约、付款、关闭、恢复、改期、取消、核销）。
- 交易迁移 `V006__trade_venue_source.sql`：订单来源加 `VENUE`。

### 21.4 接口（契约 `openapi-experience.yaml` 升 1.1.0）

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/venue-sessions/{sessionId}/participants` | 我的参与人，附上这一场能不能约和原因 |
| `POST /api/v1/venue-bookings`（幂等键） | 提交预约：场次、参与人、页面看到的应付金额、同意到馆须知 |
| `GET /api/v1/venue-bookings?bucket&page&size` | 我的到馆预约（待付款和待到馆 / 已完成 / 已取消） |
| `GET /api/v1/venue-bookings/{id}` | 一张预约和它的核销码 |
| `POST /api/v1/venue-bookings/{id}/reschedule` | 改期 |
| `POST /api/v1/venue-bookings/{id}/cancel` | 取消（待付款的关单，已付款的退款） |
| `POST /api/v1/venue-checkins` | 场馆操作员核销 |

### 21.5 App

确认到馆预约 `/pages/venue/book`（VenueBook）→ 收银台（复用）→ 支付结果 →“查看核销码”`/pages/venue/booking`（VenueCode：待付款、待到馆、已核销、已过期、已取消几种样子，改期面板、取消确认）→“我的 → 到馆预约”`/pages/mine/venue-bookings`。场馆详情的“预约到馆”从提示改成进确认页。

### 21.6 验证（L4）

同 §20.6；冒烟脚本 `infra/scripts/trade-smoke/venue.cjs`；开发脚本 `infra/scripts/dev-checkin-venue.cjs` 用 `dev-organizer` 核销。


### 21.7 完成情况（2026-11-19，已提交）

| 项 | 结果 |
| --- | --- |
| 服务端 | 体验迁移 `V002__venue_booking.sql`、交易迁移 `V006__trade_venue_source.sql`；预约、付款回调、改期（两个场次按 ID 顺序加锁、名额一出一进）、取消退款、核销；参与人通过端口读活动模块的名单；场馆详情每个项目有 `onlinePayment`；预约里带场馆城市 |
| 契约 | `openapi-experience.yaml` 1.1.0；交易订单来源加 `VENUE` |
| App | 确认到馆预约 `/pages/venue/book`、核销码 `/pages/venue/booking`、我的到馆预约 `/pages/mine/venue-bookings`；“我的”加入口（预览版）；公开版这三个页面只有说明 |
| 开发脚本 | `infra/scripts/dev-checkin-venue.cjs <核销码>`（`dev-organizer` 核销，开场前 30 分钟到结束） |
| 冒烟 | `infra/scripts/trade-smoke/venue.cjs` 36 项全过（年龄判断、预约、金额核对、重放、付款出码、改期换码和名额移动、旧码作废、核销和重复核销、整单退款、未付款取消、超时关闭、关闭后到账的恢复和自动退款） |
| 截图 | `.planning/venue-booking/visual/`（七个画面 × 320 / 375 / 430）；与画板并排 `.planning/style-compare/venue/` |

和 §21.1 不一样、或者这一步才定下来的：

1. “本人”按 18 岁算，项目最低年龄超过 18 岁时本人不能约。
2. 核销码只在“待到馆”“已核销”时给出；改期后旧码直接不存在（核销返回“没有这张码”）。
3. 免费项目（标价 0）也要带 `expectedAmountMinor = 0`，价格从 0 变成收费时能拦住。
4. 确认页去掉了原型的“联系人 / 接收到馆提醒”和“距你 2.3km”：不收联系人，也没有提醒和定位。退改说明只写实际做到的几条，原型里“场馆调整或取消场次时全额退款”没写，因为没做。
5. 收银台的“预约内容”带着场馆名（交易订单的商品名是“场馆名 项目名”）。
6. 示例数据里没有免费项目（加了会改动已有用例里的项目列表），免费路径由单元测试和冒烟时临时改价验过。

### 20、21 两步合计的验证（2026-11-19）

| 项 | 结果 |
| --- | --- |
| 后端全量 | 1644 条通过（E08 结束时 1503） |
| 前端单测、类型检查 | 895 条通过；类型检查通过 |
| 开发版 E2E 全量 | 273 条通过、10 条跳过（跳过的都是只在公开版跑的用例）。新增 `activity-paid.spec.ts` 6 条、`venue-booking.spec.ts` 8 条 |
| 公开版 | 构建和防泄漏检查通过；公开版 E2E 10 条通过（新增 PUB-010） |
| 交易冒烟 | 订单 27、支付 38、履约退款 43、对账 23、收费报名 33、到馆预约 36，全部通过，测试数据已清 |
| 没验证 | 真实微信；Android、iOS 真机 |

顺带修的：活动报名的 E2E 一直没清理自己建的免费报名，`dev-customer` 名下攒了 62 张已取消的报名，把新建的那张挤出了用例翻看的前几页。现在用例会清掉自己建的报名、凭证和子女并把名额还回去。

---

## 22. F1 需求发布与报价比选（2026-11-19，连续推进）

### 22.0 先说为什么不先做巡检付款节点

§19.7 接入顺序的最后一项是巡检付款节点，计划里写明“巡检还要先定 C3（确认方案后的取消、改期）”，§1.2 也把 C3 列为打开付款节点的前提。C3 决定的是“客户付了首付之后取消，钱怎么办”，属于资金和合同规则，没有设计假设可以套用，这一步不替你定，跳过。等你定了 C3（和 B2 验收不通过怎么办），再按 E09、E10 的方式接入。

于是按 §14.2 的顺序往下做 F 步（⑤ 需求发布、比选、合同、租赁 → ⑥ 四类专业服务）。F 步里要定的 B3、设计假设 10、11、16、17 在 2026-11-05 已经确认为默认规则（§1.2）。

### 22.1 这一步（F1）的范围

做 Demand、DemandList、Quote、PlanCompare 四张画板：客户发布需求 → 服务商报价 → 客户比选 → 选定一家。**停在“选定报价”**：选定之后的方案确认、付款和履约，巡检以外的服务还没有通用的服务订单（M05–M08 只服务巡检），这一步不做，选定后的页面如实写“方案确认和付款还没有开通”，入口走类型化网关。这和 D 步停在“提交订单”之前是同一种切法。

| 做 | 不做（原因） |
| --- | --- |
| 发布需求：五种服务类型（巡检、航拍、测绘、设备维护、技术支持），服务地点（城市 + 地址文字）、作业范围、期望完成日期、预算区间、需要的交付物（按类型给选项）、补充说明；报价范围“平台匹配”或“仅邀请指定服务商”（1–3 家）；存草稿 | 附件上传（要单独接文件存储和下载授权，下一步做）；企业 / 个人身份切换（按账号所属组织显示，不切换） |
| 我的 → 需求与报价：全部 / 待报价 / 报价中 / 已选定 / 已关闭；取消需求；删除草稿 | 询价单（商品询价、租赁询价，属于 F2） |
| 服务商报价（受权接口 + 开发脚本，没有页面）：总价、方式、工期、周期（天）、交付物、有效期；截止前可以改价（新版本）或撤回 | 服务商工作台页面（AGENTS.md §3 不做） |
| 报价比选：综合 / 总价 / 交付周期排序，对比明细，选择一家后需求“已选定”，其余报价“未选中” | 选定后的方案确认、合同、付款、履约（要通用服务订单，另定） |
| 收到报价、需求关闭时给发布人发站内信（“消息”页新增“需求与报价”一类） | 推送 |

### 22.2 规则（没写明的按设计假设和原型）

1. 发布人是登录账号；账号属于某个客户组织时，需求记在这个组织名下（页面显示“企业 · 组织名”），否则是个人。只有发布人自己能看、能改。
2. 报价截止：提交后 72 小时（原型写“[协议约定时间]”，先按 72 小时做）。截止前没有任何报价 → 需求自动“已关闭（报价期内没有服务商报价）”；有报价的，截止后不再收新报价，客户在每份报价的有效期内都可以选。
3. 平台匹配 = 发布城市里所有有服务商资料的服务商都能看到并报价；仅邀请 = 只有被邀请的 1–3 家能看到。服务商只看到需求内容，看不到发布人的账号和联系方式。
4. 一家服务商对一份需求只有一份有效报价，可以改（版本号加 1，客户看最新版）或撤回；被选中之后不能再改。
5. 客户选定一家后：需求“已选定”，被选的报价“已选中”，其余“未选中”，不能再选别家；取消需求只在“已选定”之前。
6. 提交需求不产生费用（原型原文）。金额一律存分，只做展示和比较，不建付款单。

### 22.3 接口与数据（新模块 `demand`，契约 `openapi-demand.yaml` 1.0.0）

- 客户：`POST /api/v1/demands`（幂等键）、`PUT /api/v1/demands/{id}`（草稿）、`POST /api/v1/demands/{id}/submit`、`GET /api/v1/demands?status`、`GET /api/v1/demands/{id}`、`POST /api/v1/demands/{id}/cancel`、`DELETE /api/v1/demands/{id}`（草稿）、`GET /api/v1/demands/{id}/quotes?sort`、`POST /api/v1/demands/{id}/choice`（If-Match）、`GET /api/v1/demand-providers?cityCode&type`（可邀请的服务商）。
- 服务商（受权）：`GET /api/v1/provider/demands`、`GET /api/v1/provider/demands/{id}`、`PUT /api/v1/provider/demands/{id}/quote`、`DELETE /api/v1/provider/demands/{id}/quote`。
- 表：`demand`、`demand_deliverable`、`demand_invitation`、`demand_quote`（含版本）、`demand_event`。
- 开发脚本 `infra/scripts/dev-provider-quote.cjs`：用服务商开发账号给一份需求报价、改价、撤回。

### 22.4 App

发布需求 `/pages/demand/publish`（专业服务频道和四类服务的“发布需求”从提示改成进这里，预览版）→ 我的 → 需求与报价 `/pages/mine/demands` → 报价比选 `/pages/demand/quotes` → 对比明细 `/pages/demand/compare`。

### 22.5 验证（L3）

后端全量、App 单测和类型检查、开发版 E2E 全量（新增需求用例，报价用受权接口建立前置状态）、公开版构建和防泄漏、320/375/430 截图和 390 宽对照。
