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
