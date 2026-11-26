# App 逐页功能验收

2026-11-26。页面清单来自 `apps/mobile/src/pages.json`。首页推荐、专业服务、赛事研学、体验和商城共用同一条首页路由。H5 在隔离接口与数据库上执行，支付、短信和直播使用项目开发适配器。弱网、延迟响应和部分边界状态由测试用例控制。

**本轮结果：110 条注册路由均已核查。按用例文件和标题去重，81 个文件中的 611 个 App 或跨端浏览器用例均有通过记录。** 部分用例在修复后分组复跑。统计范围不含 5 个仅针对后台的测试文件，也不把重复执行、逐页启动检查、单元测试或接口断言叠加进 611。逐用例结果索引为 `main/app-e2e-coverage.json`。

## 直接影响使用的修复

| 流程 | 复现与修复 | 验证方式 |
| --- | --- | --- |
| 公开版登录与退出 | 登录前点击三份协议原先只提示正文待提供，即使后台已经发布。现在各自读取发布接口。关闭完整设置时，已登录用户可从“我的”直接退出，点取消则保留会话。 | 真实账号登录、返回协议、确认退出、刷新后检查游客状态。已发布协议逐段比对接口，未发布的按 404 处理。 |
| 已关闭功能的历史链接 | 首页隐藏入口后，13 条链接仍能打开业务、误跳登录或报缺少商品。趣玩 2 条、活动 5 条、场馆 2 条、商城与购物车 4 条均在路由处应用开关。共享参与人在开放场馆预约时仍可使用。 | 默认公开版逐条直访并检查业务请求，同时测试单开商城和组合开放体验、商城、趣玩。 |
| 商城下单与询价 | 配送重新计价时阻止提交；游客登录保留自提选择；从商家页返回时重查商品上下架；已有交易的询价单读取失败时只能重试。损坏的对比链接可返回商城。 | 真实数据库交易、下架回读及可控慢响应。详见[交易与服务](./App验收-交易与服务.md)。 |
| 买家寄回商品与商家退款 | 买家更新运单后，商家在详情刷新期间仍可提交旧版本，导致收货确认失败。现在重新读取时锁住该单的处理按钮，待取得当前版本再确认。 | 用同一张售后单真实触发旧版本 412 并核对状态，重读后确认收货返回 200，重复操作返回 409，买家收到模拟微信退款回调后查看最终状态。 |
| 活动、场馆与体验 | 日历选定场次带回报名；返回详情重新读余位；看完设备回到原项目及日期；体验链接缺少参数时给出返回体验频道的入口。录播失败可重试，内容下架后旧链接失效。 | H5 逐页操作，活动真实接口 77 项断言，独立 S6 跨端演练 31 步。详见[活动场馆与课程](./App验收-活动场馆与课程.md)。 |
| 消息列表与角标 | 单条或全部标记失败曾清除未读，用户只能离开再返回。现在收到确认后更新角标，失败可当场重试，迟到的列表不会覆盖确认结果。 | 失败后核对行、角标和重试按钮，模拟刷新与已读写入同时发生。 |
| 系统公告跨版本 | 已经投递至同一账户的系统公告，关闭开关后仍出现在列表、分页总数及未读数中。数据库查询和批量已读现在遵循相同可见范围，旧客户端未传批量写入参数时维持原有语义。 | 同账号先看预览版、再去公开版点全部已读、再回预览版；两条普通通知已读，一条公告仍未读。详见[真实双构建回归](./App验收-交易与服务.md)。 |
| 首次启动、地址和图片 | 明确手动选城后首页不再重复请求定位，迟到的定位结果不会覆盖新选点。地图链接缺参可退出。局域网业务媒体支持放大，筛掉无效图后预览仍指向用户点选的图。 | 雷电 Android 14、可控定位延迟和 H5 图片交互。详见[原生与地图](./App验收-原生与地图.md)。 |
| 账号设置与消息收件箱 | 换号后清除前一账号的手机号、注销弹窗、通知偏好和收件箱。连续保存偏好失败会还原服务端最近确认的值。批量已读后重新读取收件箱，保留同时新到的未读消息。 | 两个真实测试账号切换，延迟旧请求，核对页面与两个账号的接口状态。 |
| 登录续期与退出 | 甲账号的请求在乙登录后收到 401，曾可能用乙的身份重新提交。退出确认、续期和请求重试现按发起会话处理。同一账号正常续期仍可完成操作，续期遇到网络错误、限流或服务暂时不可用时可以重试。 | 两个独立注册的账号用真实 API 核对保存后的值；单测覆盖并发续期、旧请求及晚到的退出清理。H5 通过真实刷新接口取得新令牌并继续访问。 |
| 首页切换频道 | 在 H5 上滚动后立即切换频道，滚动事件回传较晚会使原频道回到顶部。现在切换时记住当时的实际位置。 | 手机尺寸下实际点击可见页签，以及滚动 300 像素后立即切换并返回，两种用例各重复三次。 |
| 扫码与相机权限 | 原生端拒绝相机权限后缺少手工输入路径。可在页面粘贴平台码、输错后重新输入，并能回到系统设置。 | 雷电实际拒绝权限，输入外部链接被拦截，随后输入合法商品链接进入真实商品详情。 |

首页轮播测试曾把数量固定为四条。隔离库实际返回六条已发布记录，现逐项核对接口顺序、单次滑动、循环、键盘与各自去向。巡检详情的零评价检查也按当前服务次数格式和评价展示规则断言。两项检查都保留运营已维护的数据。

## 测试环境与验证边界

移动端使用仓库的 uni-app 工程。浏览器测试分别使用预览 H5、默认公开生产构建和部分打开功能的生产构建。预览 H5 在 Chrome 中按手机尺寸访问。公开构建经过静态产物检查，再用同源 HTTPS 请求连接隔离后端，测试时没有向用户的日常数据库创建订单、修改账号或发布内容。

逐条冷启动检查采用游客身份，分别打开预览和公开 H5 的全部 110 条注册路由。两遍均有 110 页显示有效内容、登录提示、关闭说明或缺参恢复入口，无空白页或页面脚本异常。正常业务路径另用带真实业务参数的用例操作。开发账号、短信、微信支付和直播的验收条件，以及受控延迟、网络错误和接口桩的使用位置，见下表与各专题记录。

| 验收范围 | 实际结果 | 本机证据 |
| --- | --- | --- |
| App 全部单元测试、类型检查 | 198 个测试文件，共 1620 项通过；`vue-tsc --noEmit` 成功 | `main/unit-final.log`、`main/mobile-types-final.log` |
| 预览主流程 | 318 项执行中 316 项首轮通过。登录续期的旧测试将真实身份替换成无主体字符串，已改为保留原账号并模拟访问过期，7 项登录及请求用例通过。发现页视觉批量截图曾超过单条测试时限，已按 4 个屏宽独立执行，原布局检查和画板对照全数通过 | `main/accepted-all.json`、`main/auth-final.json`、`main/content-widths.json` |
| H5 路由与默认公开生产包 | 预览版和公开版各 110 条冷启动；公开版 26 项业务、登录和发布开关用例通过 | `main/routes-accepted-preview.json`、`main/routes-accepted-public.json`、`main/release-final.json` |
| 独立发布开关 | 只开商城 1 项、只开设置 1 项，同时开商城、体验、趣玩 3 项通过；同账号跨预览和关闭公告的构建及既有公告回归 8 项通过 | `commerce/mall-only.json`、`main/settings-release-final.json`、`main/selective-accepted.json`、`commerce/notice-final.json` |
| 活动、场馆、体验、课程、核销、设置和消息 | 更新鉴权后 17 个文件、119 项全数通过。6 项商家工作台联动通过；另以商家和买家两个独立浏览器实测，后台发货后买家 H5 从待发货变为已发货，运单号一致 | `events/final-auth.json`、`events/cross-admin-final.json`、`events/shipping-h5.json` |
| 交易、租赁、报价、服务与商品售后 | 111 项交易回归首次有 106 项通过，售后组首项失败后同组 4 项未运行。修复后整组 5 项全过，包括商家确认、重复处理、模拟退款到账和买家读回。后台售后单测 12 项通过，后台类型检查及生产构建成功 | `commerce/commerce-post-auth-full.json`、`commerce/post-auth-aftersale-final.json`、`main/admin-final-build.log` |
| 原生与地图 | 雷电 Android 14 实际走通登录退出、五个底栏、巡检、选城、相机拒绝后手工识别、地图和断网恢复；相关 H5 31 项通过，APP-PLUS 构建成功 | [原生验收与复跑命令](./App验收-原生与地图.md)，`main/native-build-current.log` |
| 后端与数据库 | Maven 全量 3193 项通过，另 14 项真实 PostgreSQL 和 Redis 集成检查通过 | [后端回归与证据位置](./App验收-交易与服务.md) |

本机运行证据在 `.runtime/acceptance/app-audit-20261126/`，不进入版本管理。可从 `apps/mobile` 执行 `npm run type-check` 与 `node node_modules/vitest/vitest.mjs run --maxWorkers=1 --minWorkers=1`。日常复跑浏览器用例可在独立开发测试栈就绪后，从仓库根目录执行 `node infra/dev/e2e/stack.mjs test <用例文件> --workers=1`；独立生产构建的开关与网址按对应测试文件设置。

本轮未验证 iOS 真机、独立发布签名的 Android 安装包、镜头与相册实图解码、室外 GPS 或已经安装的地图客户端实际导航。短信送达、真实微信扣款退款、直播和推送服务商的真实联调仍需各自的接入环境。雷电清理临时用户时系统进程异常，重启模拟器后已核对原用户、App 资源、登录凭据存在状态和端口映射均已恢复。

## 本地开发服务复验

验收后使用同一份已测试的 JAR 更新了本地开发后端 `18420`。替换前备份原 JAR、导出 PostgreSQL 并用 `pg_restore --list` 校验备份。新旧包（含依赖）中的 155 份 SQL 文件内容完全一致。重启前后，34 条用户、40 条地址、2 条交易订单的全行指纹和东营首页轮播接口结果一致。

重启后健康检查返回 `UP`，H5 和后台入口均返回 HTTP 200。另用独立浏览器打开日常 H5 的首页，从 `18420` 收到 4 条实际发布的轮播，当前显示项属于本次接口响应，未出现页面脚本异常。该次本地检查没有登录或创建测试订单。备份、前后指纹及浏览器结果位于 `.runtime/maintenance/app-audit-20261126/`。

## 页面与功能索引

下表列出每页的核查点和对应的浏览器用例或专项记录。路由均以 `/pages/` 开头，用例在 `apps/mobile/e2e/`，后缀为 `.spec.ts`。不带业务参数的直访另作一轮，用于发现白屏、错误文案和发布开关失效。

### 首页、巡检与专业服务

| 路由 | 功能核查点 | 对应用例 |
| --- | --- | --- |
| `home/index` | 频道切换、滑动与键盘轮播、切换城市、搜索及空列表重试 | home-revamp、home-recommend、city-sheet |
| `service/index` | 兼容入口转入首页专业服务频道 | navigation |
| `service/inspection/index` | 巡检场景、热门服务、从目录返回 | navigation、hot-service |
| `service/inspection/catalog` | 搜索、切城市、筛选、排序、下一页、重试 | catalog-search、catalog-route-city、navigation |
| `service/inspection/detail` | 可预约与下架、无评价、换城市、失败重试 | service-detail-recovery、hot-service |
| `service/inspection/login` | 账号密码、验证码、注册与找回、协议阅读、回到原操作 | account、personal-password、public-build |
| `service/inspection/booking` | 未登录回跳、定位地址、表单、找不到服务、提交及重试 | booking-back、booking-recovery、booking-service-lookup |
| `service/inspection/booking-submitted` | 提交结果、返回订单、刷新和缺失预约 | booking-recovery、order-center-back |
| `service/inspection/plan-confirmation` | 选定版本、报价过期、异地确认、签约与首付 | service-flow-visits、inspection-payment |
| `service/inspection/fulfillment` | 团队安排、巡检进度、改期和待交付状态 | service-flow-visits、inspection-payment |
| `service/inspection/delivery` | 交付下载、验收、退回整改、响应丢失后的状态 | service-flow-visits、inspection-payment |
| `mine/inspection-orders` | 订单分组、选单、各阶段跳转和返回 | order-center-back、mine |
| `mine/inspection-order` | 单笔巡检状态、服务商、下一步操作 | inspection-order-provider、order-center-back |
| `proservice/catalog` | 四类服务、价格排序、筛选、搜索和分页 | proservice |
| `proservice/detail` | 按城市读取与下架、进入需求发布和服务商 | proservice |
| `provider/detail` | 服务商资料、在售服务与仅邀请该机构 | proservice |
| `play/index` | 从首页或直链进游戏列表、已关闭构建直链 | home-revamp、public-build |
| `play/game` | 两款游戏的移动、收集、通关、重开及无效关卡 | home-revamp、public-build |
| `search/index` | 关键词、联想、纠错、搜索范围、最近搜索 | search |
| `search/results` | 分组、查看更多、零结果及搜索商品 | search |
| `mine/order-search` | 商品名和订单号尾号搜索、空状态 | search |

### 账号、消息与内容

| 路由 | 功能核查点 | 对应用例 |
| --- | --- | --- |
| `mine/index` | 个人身份、企业身份、订单角标、游客与读取失败 | mine、account |
| `onboarding/index` | 同意、暂不定位、手动选城 | account、原生专项 |
| `mine/phone` | 验证旧号、换绑新号、其他会话失效 | account |
| `mine/password` | 首次设置、已有密码修改、找回后登录 | personal-password |
| `mine/devices` | 本机与其他设备、撤销登录 | account |
| `mine/data` | 导出、一次性下载、注销与撤销 | account |
| `mine/permissions` | 设备权限说明、返回与手动选择 | account |
| `mine/org` | 创建企业、选择个人或企业身份、付款主体 | account |
| `mine/org-members` | 成员列表、身份权限、移除后的身份 | account |
| `mine/org-invite` | 邀请、被邀请人接受、成员更新 | account |
| `mine/settings` | 退出、重新登录、通知偏好持久化、换号及令牌续期 | support、system-notice、settings-session |
| `mine/help` | 反馈提交、平台客服入口 | support、admin-support-operations |
| `mine/agreement` | 协议选择、发布和撤回、公开登录前阅读与返回 | support、ops-agreements、public-build |
| `mine/about` | 从个人中心进入、版本与公开构建入口 | support、public-build |
| `message/index` | 游客、未读角标、分页、全部已读重试和切换账号 | message、system-notice、support |
| `message/notice` | 已读、查看更新、撤回后的状态 | system-notice、ops-editor-integrity |
| `support/chat` | 会话开立、回复、业务卡片和读回 | support、admin-support-operations |
| `discover/index` | 频道、混排、翻页重试、慢响应与频道切换 | content、content-video、live |
| `content/article` | 正文、广告标记、关联对象、机构主页、分享 | content |
| `content/topic` | 专题内活动、场馆和文章互跳 | content |
| `content/video` | 播放接口媒体、点赞、关注、评论 | content-video、database-media |
| `content/video-feed` | 滑动切换、播放、评论及搜索 | content-video、search |
| `live/notice` | 预告、开播提醒、回看入口 | live |
| `live/room` | 直播、断流、结束、弹幕、点赞、讲解清单 | live、ops-live-moderation |

### 商城、交易与服务订单

| 路由 | 功能核查点 | 对应用例 |
| --- | --- | --- |
| `mall/index` | 旧商城入口跳转到首页对应频道 | navigation、mall |
| `mall/product` | 规格、数量、收藏、库存、商家、下架后的刷新 | mall、mall-cart、mall-sku-stock |
| `mall/merchant` | 店铺资料、销售商品、返回商品 | merchant-page |
| `mall/compare` | 选商品、参数对照、商品不足、发布开关 | favorites、public-build |
| `mine/favorites` | 收藏、分组和对比选择 | favorites |
| `mall/inquiry` | 配置、数量、选择报价商家和提交 | mall-inquiry |
| `mall/inquiry-quotes` | 比价、选定、转订单、已下单后的异常恢复 | mall-inquiry |
| `cart/index` | 商家分组、数量、选择、失效商品、删除与角标 | mall-cart |
| `cart/checkout` | 配送、自提、价格重算、确认提交、登录回跳 | mall-cart、trade-back |
| `mine/addresses` | 增删改、默认地址、确认订单选择地址 | mall-cart |
| `trade/cashier` | 订单金额、付款、取消、过期和业务来源回跳 | trade-orders、trade-back |
| `trade/pay-result` | 支付结果、待确认、由结果回订单 | trade-orders、trade-back |
| `mine/goods-orders` | 状态筛选、分页与进入订单 | trade-orders |
| `mine/goods-order` | 取消、物流、自提、退款、确认收货 | trade-orders、aftersale |
| `trade/aftersale-apply` | 售后类型、数量、理由和申请 | aftersale |
| `trade/aftersale` | 处理进度、退货物流、退款结果 | aftersale |
| `mine/aftersales` | 售后列表及详情回跳 | aftersale |
| `mine/orders` | 跨类型合并、状态筛选和金额 | mine-orders、mine-order-amount |
| `demand/publish` | 保存草稿、选择邀请范围、发布 | demand、proservice |
| `mine/demands` | 按状态查自己的需求、进报价 | demand |
| `demand/quotes` | 报价有效期、选择和生成服务订单 | demand、merchant-quote-revision |
| `demand/compare` | 并列查看报价、选定服务商 | demand |
| `rental/inquiry` | 数量、起止日期、租赁询价 | rental |
| `rental/quote` | 报价、合同和接受报价 | rental |
| `rental/contract` | 合同信息、回到租赁操作 | rental |
| `rental/order` | 付款、交接、归还和取消 | rental |
| `mine/rental-orders` | 订单列表、状态与详情 | rental |
| `service-order/detail` | 选中报价生成订单、履约推进 | service-order、merchant-quote-revision |
| `service-order/plan` | 方案、确认金额、付款 | service-order、merchant-quote-revision |
| `service-order/delivery` | 服务商交付、客户验收和结单 | service-order、merchant-quote-revision |

### 活动、场馆、课程与工作模式

| 路由 | 功能核查点 | 对应用例 |
| --- | --- | --- |
| `activity/detail` | 场次、城市、报名状态和日历选场 | activity、events-audit |
| `activity/calendar` | 独立选场回详情，退改表日历回填 | events-audit、activity-result-change |
| `activity/enroll` | 按人报名、免费与收费、名额及付款 | activity-enroll、activity-paid |
| `activity/participants` | 本人或子女的增改、报名与场馆预约共用 | activity-enroll、venue-booking |
| `activity/ticket` | 一人一码、查看凭证、取消 | activity-enroll、activity-paid |
| `activity/order` | 报名明细、付款、凭证、退款入口 | activity-paid |
| `activity/refund` | 选择退款参与人、退款金额和状态 | activity-paid |
| `activity/waitlist` | 满额候补、转正和付款期限 | activity-waitlist-team |
| `activity/team-signup` | 团队成员与报名校验 | activity-waitlist-team |
| `activity/team-order` | 团队单、成员凭证与状态 | activity-waitlist-team |
| `activity/result` | 按参与人看结果和记录 | activity-result-change |
| `activity/change` | 改期结果、前后时间和新凭证入口 | activity-result-change |
| `mine/activity-enrollments` | 报名与候补、团队和结果入口 | activity-enrollments-s53、activity-enroll |
| `venue/detail` | 选项目与日期、余位、预约和位置 | experience、venue-booking、events-audit |
| `venue/devices` | 设备分类、状态和回场馆保留选择 | experience、ops-editor-integrity、events-audit |
| `venue/book` | 选参与人、提交预约及付款 | venue-booking |
| `venue/booking` | 核销码、改期、取消、退款 | venue-booking |
| `mine/venue-bookings` | 状态筛选、详情回跳 | venue-booking |
| `experience/recorded` | 已发布媒体播放、断网重试和下架后回访 | experience、events-audit |
| `experience/simulation` | 选择仿真任务、交互、提交及缺参恢复 | experience-sim、events-audit |
| `experience/result` | 任务成绩、记录与仿真项目匹配、缺参恢复 | simulation-result-context、events-audit |
| `experience/software-trial` | 软件体验与缺参返回 | experience-sim、events-audit |
| `experience/course-trial` | 历史链接按体验项目读取试听期限、缺参返回 | experience-sim、events-audit |
| `mine/experience-records` | 切换记录分组、查看记录状态 | experience-sim |
| `course/detail` | 课程内容、购买与退款条件 | course |
| `course/learn` | 开始学习、课时进度、退款条件和退款操作 | course |
| `course/trial` | 从体验中心试听、关联仿真项目和失败恢复 | course、course-trial-recovery、admin-course-simulation |
| `mine/courses` | 已购及附赠课程分组、进入学习 | course |
| `staff/verify` | 浏览器手输凭证、取消和错误提示，真实接口核销、重复码和跨机构拦截 | staff-verify、S6 跨端演练 |
| `staff/records` | 核销记录和关联对象 | staff-verify |
| `staff/exception` | 核销异常原因、回到操作入口 | staff-verify |

### 设备与通用页

| 路由 | 功能核查点 | 对应用例 |
| --- | --- | --- |
| `place/destination` | 场馆坐标、底图、断网、复制地址和导航选择 | destination-map、原生专项 |
| `place/map-pick` | 地址选点、确认和取消 | pickers、原生专项 |
| `scan/index` | 扫码入口、权限、取消与手工输入 | pickers、原生专项 |
| `common/photo-viewer` | 查看高清图、切换图片、关闭与失效输入 | pickers、原生专项 |
