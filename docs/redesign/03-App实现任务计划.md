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
