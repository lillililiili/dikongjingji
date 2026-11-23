# 运营端（apps/admin）

Web 运营端与商家 / 服务商 / 直播主办的机构工作台（`docs/redesign/08-后续开发计划.md` S3-1、S3-2，默认决定 D11、“工作台”）。
技术栈按 AGENTS.md §4 锁定：Vue 3 + TypeScript 严格模式 + Vite + Element Plus + Pinia + vue-router，主色 `#ff6a00`。
原型里没有 Web 端画板，版面按 Element Plus 常规后台做，侧栏和颜色参考原型 `Ops.html`（城市运营看板）。

## 启动

```bash
cd apps/admin
npm ci            # 依赖装在 apps/admin/node_modules，用 package-lock.json 锁版本；不进根目录的 pnpm 工作区
npm run dev       # http://127.0.0.1:17430 ，/api 代理到 http://127.0.0.1:18420（和 App 开发版同一个后端）
```

- 后端换地址：`ADMIN_API_TARGET=http://127.0.0.1:18421 npm run dev`。
- 后端要是带 S3-1 的版本（`/api/v1/ops/**`、身份迁移 V007、巡检 V012、客服 V002；商品与供给、收款账户、批量导入还要商城
  V004、交易 V009、opsimport V001）。
- 生产：`npm run build` 出 `dist/` 静态文件，由 Nginx 托管，并把 `/api` 同源转发到后端（不用开 CORS）。

## 登录哪个开发账号

| 账号 | 角色 | 能看到 |
| --- | --- | --- |
| `dev-operator` | 平台运营 PLATFORM_OPERATOR | 城市开通、机构与资质、商品与供给、收款账户、批量导入 |
| `dev-support` | 平台客服 PLATFORM_SUPPORT | 举报与反馈 |
| `dev-merchant` | 机构操作员 PROVIDER_OPERATOR + 直播主办 LIVE_HOST（示例机器人商家） | 机构工作台全部菜单，含直播间；商城订单、询价、租赁、客服、收款账户都有示例数据 |
| `dev-merchant-2` | 机构操作员（示例行业装备代理） | 机构工作台（没有直播间）；商城询价的第二家商家 |
| `dev-provider` | 机构操作员（云翼科技，巡检服务商） | 机构工作台（没有直播间）；巡检预约、需求报价、客服会话有数据 |
| `dev-organizer` | 机构操作员（东营低空与机器人体验馆） | 机构工作台（没有直播间）；它的活动、场馆核销仍走 App 的扫码 / 输码（D8） |

- `dev-support`、`dev-provider` 的口令和其他示例账号一样，见 `backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql`。
- `dev-operator` 的口令**不在仓库里**：平台运营权限大（跨租户读巡检、管机构和资质），所以只在启动后端时设了环境变量
  `DEV_OPERATOR_PASSWORD`（至少 12 位，只在 `dev` 配置下生效）才建这个账号，口令就是你设的值；不设就没有这个账号。
  改了这个值，下次启动后端时口令跟着改。种子：`backend/bootstrap/src/main/resources/db/development/identity-operator/`。
- 客户账号（`dev-customer`）没有运营端角色，登录后会被拒绝。
- 商家和服务商在身份模块里是同一个机构角色 PROVIDER_OPERATOR（没有单独的“商家”角色），所以菜单相同；每个页面的数据
  都由后端按令牌里的机构过滤，没有卖货的服务商看到的商城订单是空的。直播间只给有 LIVE_HOST 的机构（平台在“机构与资质”里授）。
- 登录令牌 15 分钟过期（后端没有刷新令牌），过期后任何操作都会回到登录页；令牌只放内存和 sessionStorage。

## 功能清单（S3-1 第一、二、三部分）

| 菜单 | 角色 | 能做的事 | 接口契约 |
| --- | --- | --- | --- |
| 城市开通 | 平台运营 | 按名称 / 状态查城市；改开通状态（已开通 / 筹备中 / 未开通，写说明、带版本号）；看每个城市的改动记录 | `contracts/openapi-ops-city.yaml` |
| 机构与资质 | 平台运营 | 机构列表（待审核在前）；新建机构（类型、名称、城市，带幂等键）；录入 / 修改资质文字；上传、下载、移除资质附件（PDF/JPEG/PNG ≤10 MB）；审核通过（写 `qualification_reviewed_at`，App 显示“资质已审核”）或驳回（写原因）；给机构建账号（初始口令只显示一次）、改机构角色（服务商操作员、直播主办；企业客户是客户角色）；操作记录 | `contracts/openapi-ops-organization.yaml` |
| 商品与供给 | 平台运营 | 商品列表（关键词、类目、交易方式、状态）；新建商品（类目、名称、交易方式，带幂等键，草稿）；详情四页：基本资料（一句话介绍、体验标识、排序、卖点标签 ≤5×12 字、参数、包装清单、封面、图集 ≤9、图文介绍 ≤30 段，整份保存带版本号）、规格（≤20，新建、改名、启用停用）、在售供给（商家 × 规格 × 城市 × 价格 × 库存 × 配送，新建是停用的；价格按元填、按分提交；询价不标价，租赁按天或半天；不能快递要有自提点；没登记收款账户的商家标红）、操作记录；上架前列出缺什么并禁用按钮，后端回 `OPS_PRODUCT_INCOMPLETE` 时显示 `details.blockers`；图片先上传（JPEG/PNG/WebP ≤5 MB）拿受控地址 | `contracts/openapi-ops-catalog.yaml` |
| 商家资料审核 | 平台运营 | 商家在工作台提交的商家资料：按状态（待审核 / 已通过 / 已驳回 / 已被替换）、关键词（机构名称或经营主体）查，待审核在前；详情对照这一版和现在对外的资料（改动的项标出），看机构资质审核过没有、这家最近 20 次提交；审核通过（整份写成 App 商家主页的资料）或驳回（原因 ≤200），都带版本号，已处理过（`OPS_MERCHANT_PROFILE_REVIEW_CLOSED`）或版本不对时提示刷新；审核人和时间记在提交单上 | `contracts/openapi-ops-merchant-profile.yaml` |
| 商品与供给 · 交易条款 | 平台运营 | 商品详情的“交易条款”页签：七天无理由开关（默认支持，标不支持后买家不能选七天无理由，质量问题不受影响）；每个供给一行（规格、商家、城市、状态）填开票方、售后责任方（留空按商家资料，再没有就是商家名称），旁边显示实际生效的值；改动记在“操作记录”页签，已下的订单用下单时的快照 | `contracts/openapi-ops-merchant-profile.yaml`（trade-terms） |
| 收款账户 | 平台运营 | 经营机构和它的微信二级商户号（是否登记筛选）；登记或修改商户号（8–32 位数字）和收款开关（带版本号）；看每家的登记记录。只登记，不做进件；占位号段 1900000xxx 只有开发环境能登记 | `contracts/openapi-ops-catalog.yaml`（merchant-accounts） |
| 批量导入 | 平台运营 | 选一类（经营主体与操作员、收款账户、商品与供给能导入；活动、场馆、服务、内容这一版只能预检），上传 `infra/import-templates/` 的 CSV 预检：逐行问题表、不进库的核对列、和以前相同时提示不重复写；预检通过后导入（一个事务），显示新建 / 更新的对象，新操作员账号的一次性初始口令只弹出这一次；导入记录和批次详情 | `contracts/openapi-ops-import.yaml` |
| 活动与场次 | 平台运营 | 活动列表（名称 / 城市 / 类型 / 状态）；新建草稿、改活动（类型、主办 / 承办机构、城市、地点、年龄、收费方式与单价、封面与图集、参与条件、名额说明、退改说明、报名须知，带版本号）；上线前校验必填（缺了列出还差什么）、下线、删除从没上线过的草稿；场次新增 / 修改（北京时间）、停售 / 恢复售卖、删除（有过报名单的不能删）；改动记录 | `contracts/openapi-ops-activity.yaml` |
| 课程 | 平台运营 | 课程列表（关键词、上课方式、子类目、状态）；新建草稿（先选线上课或线下班，建好后不能改；开课机构按机构列表搜，线下班按活动列表选挂靠的活动）；线上课的基本资料（副标题、封面与图集 ≤9、价格按元填按分提交、留空为不单独出售，有效期、适合年龄、讲师、课程包含 ≤6、参数补充 ≤6、图文介绍 ≤20 段、体验中心试听入口 ID、附带于商城商品 ≤10 只存 ID），线下班只有封面、挂靠的活动、卡片文字、排序，整份保存带版本号；目录（章 ≤20、每章课时 ≤30、全课 ≤200，时长按“分:秒”或秒数，试听，视频上传 MP4 或粘贴 https，增删、上下移动，整份保存带章节和课时 ID；有人学过的课时不能删）；上架前列出还差什么并禁用按钮，后端回 `OPS_PUBLISH_INCOMPLETE` 时翻译 `details.missing`；上架 / 下架；版本冲突时提示刷新；改动记录 | `contracts/openapi-ops-course.yaml` |
| 场馆与设备 | 平台运营 | 场馆草稿（运营机构、城市、封面、地址、电话、营业时间、检票与迟到规则、信息条目、排序）、上线前校验必填（封面、营业时间、启用的项目、未来在售场次）、上线 / 下线；项目（时长、价格、年龄、每场人数）启用 / 停用；按周模板批量生成场次（≤200 场，已有和已过的跳过）、停售 / 恢复 / 取消场次（有预约只能停售）；馆内设备及关联商品；改动记录 | `contracts/openapi-ops-venue.yaml` |
| 举报与反馈 | 平台客服 | 待处理 / 已处理 / 全部，按来源筛（用户反馈、举报视频评论、举报直播弹幕）；隐藏被举报的评论或弹幕（调内容、直播已有的受权接口）；写处理说明标为已处理（只处理一次） | `contracts/openapi-ops-feedback.yaml` |
| 发现内容 | 平台运营 | 图文（案例、知识）、视频、专题三个标签页，按关键词 / 类型 / 城市 / 状态筛；新建草稿、编辑（带版本号）；正文块编辑器（段落、小标题、要点表、步骤、条目、提示、文末说明、**正文配图**：上传或粘贴 https 图片、替代文字、图注）；“内容中提到”（类型 + 稳定 ID + 名称）；视频的封面、播放地址（上传 MP4 或粘贴 https）、时长、横竖屏、话题、运营排序；专题的起止日期、挂的活动 / 场馆 / 图文 ID 和**商家内容**（商品 ID + 发布商家）；上线（立即或定时，缺必填时列出还差什么）、下线；改动记录 | `contracts/openapi-ops-content.yaml`（素材上传 `openapi-ops-media.yaml`） |
| 直播间 | 平台运营 | 全部机构的直播间（标题 / 主办机构 / 状态筛选）；强制结束（写原因、带版本号）；给已结束的设或去掉回看链接（受控 https + 平台名称）；改动记录；“主办机构”标签页列开通了直播主办的机构和直播间数，“开通直播主办”给机构的账号加 LIVE_HOST 角色（调机构与资质的改角色接口，记在机构操作记录里），收回去机构详情改 | `contracts/openapi-ops-live.yaml` |
| 巡检服务目录 | 平台运营 | 服务：按关键词 / 类目 / 状态查；新建（先是未上架）、修改（服务商、标题、简介、类目、封面、计价方式与起步价、单位、价格说明、取消说明、可预约状态、主按钮、巡检方式、含报告、覆盖城市、服务项目、价格因素、能力、服务流程、交付物、保障）；上架前校验必填（缺的项翻成“还差：…”）；上架 / 下架；改动记录。类目：新建（编码定了不改）、改名称 / 副标题 / 排序、启用 / 停用（下面有上架服务时不能停用） | `contracts/openapi-ops-inspection-catalog.yaml` |
| 专业服务目录 | 平台运营 | 航拍、测绘、设备维护、技术支持：按关键词 / 类型 / 状态查；新建、修改（服务商、类型、场景分类、标题、简介、封面、排序、起步价或按项目报价、上架城市、筛选项、卡片标签、能力、价格与取消、服务内容、成果格式、服务方式、说明行、服务流程、服务保障）；上架前要有城市、筛选项和服务流程；上架 / 下架；改动记录 | `contracts/openapi-ops-proservice.yaml` |
| 系统通知 | 平台运营 | 发给全体或某个城市的公告（S5-9）：列表（发给谁、关于哪份协议、有效期、状态）；发布（标题、列表摘要、按空行分段的正文、≤4 个要点、≤6 条“主要变化”带去处、文末说明、主按钮、关于哪份协议、有效期，带幂等键，发布后不能改）；撤回（从所有人的“消息”里拿掉）。用户打开 App“消息”时收到，发布时按范围推送（推送服务商未定，开发环境是本机模拟） | `contracts/openapi-notification.yaml` 1.5.0（运营部分） |

公共能力：登录与退出、按角色出菜单、路由守卫（没登录去登录页并记住原页面，角色不对去“没有权限”）、接口客户端（统一错误体
`{code, message, traceId}`、错误码翻成中文、401 自动回登录页、请求号、幂等键、附件下载）、分页表格、表单校验、操作确认、
操作留痕提示（每个写操作成功后提示“操作人和时间已记录”，并告诉去哪里看）。

## 功能清单（S3-2 机构工作台，分组 WORKBENCH）

都只看、只改本机构的数据（机构只从令牌里取，别的机构的单一律当作不存在）。写操作先确认，成功后提示结果；这些业务接口
不在运营端另记操作记录（各模块自己记操作人，比如交易的状态流转、租赁的交接记录），所以不用“已记录”的提示。

| 菜单 | 角色 | 能做的事 | 接口契约 |
| --- | --- | --- | --- |
| 商城订单 | 机构操作员 | 待发货 / 待自提 / 已发货 / 退款中 / 全部；详情（商品、金额和还可退、收货信息、退款记录、时间线）；发货（物流公司、运单号，带 If-Match）；自提核销（6 位取货码，输错提示、10 次锁定）；退款（可部分，按元填，不超过实付 − 已退 − 正在退） | `openapi-trade.yaml` 0.5.0 |
| 售后 | 机构操作员 | 本机构卖出商品的售后单：待处理 / 待寄回 / 待收货 / 退款中 / 全部，列表有售后编号、订单号、类型、原因、申请金额、状态、提交时间和处理时限；详情（商品、实付、申请和退款金额、原因和补充描述、凭证图片（带登录取回预览）、寄回地址、退货运单、拒绝原因和商家说明、退款状态 / 到账时间 / 微信交易号、进度）；按 `actions` 同意（寄回地址默认带出默认寄回地址，可存为默认）、不同意（原因 ≤100）、确认收货并退款（按元填，不超过申请金额，少退要写说明，经平台支付渠道原路退回）、验货不通过（原因、说明必填），都带 If-Match，412 / 409 提示刷新；列表页“默认寄回地址”设置 | `openapi-trade-aftersale.yaml` 1.0.0 |
| 商城询价 | 机构操作员 | 按状态看询价单；报价 / 改价（单价、型号、交期、包含内容、有效期 ≤7 天）；拒绝（写原因） | `openapi-mall-inquiry.yaml` |
| 租赁询价、租赁订单 | 机构操作员 | 报价 / 改价（日租金 × 天数 × 台数 + 其他费用，合计按分算）、拒绝；录入交接、录入归还（等客户在 App 确认）；交接前取消并整单退款 | `openapi-rental.yaml` |
| 需求报价 | 机构操作员 | 按状态看能报价的需求（没有发布人联系方式）；报价 / 改价、撤回 | `openapi-demand.yaml` 1.2.0 |
| 巡检预约 | 机构操作员 | 分给本机构的预约（待出方案 / 待客户确认 / 履约中 / 待验收 / 已完成）；出方案（日期、明细、交付物，合计自动算）；安排人员设备；每次巡检排期、开始、记进度、现场完成；上传交付文件（直传对象存储）并发布；看验收结果和付款节点。列表留了“付款状态”一列，巡检付款节点接入后从服务单 `paymentNodes` 读 | `openapi-inspection-service-flow.yaml` 2.3.0 |
| 客服会话 | 机构操作员 | 本机构的会话列表、未读数；回复（≤500 字，不留手机号），进会话标已读，往前翻更早的消息 | `openapi-support.yaml` |
| 直播间 | 直播主办 | 按状态看本机构直播间；建直播间、改预告；讲解清单和当前讲解项；回看链接；看弹幕、隐藏弹幕；推流地址（默认打码，点“显示”才出现） | `openapi-live.yaml` 1.1.0 |
| 商家资料 | 机构操作员 | 看现在对外的资料（没有时写“还没有审核通过的资料”）和最近一次提交（状态、提交人、时间、驳回原因）；提交新的一版（经营主体 2–160 字必填，简介 ≤300、可售说明 ≤40、授权有效期、品类 ≤40、默认开票方 ≤60、默认售后责任方 ≤60）。提交后由平台审核，通过后显示在 App 的商家主页上；待审核时再提交会替换上一版 | `openapi-mall-merchant.yaml` 1.0.0 |
| 收款账户 | 机构操作员 | 只读：本机构登记的微信子商户号（只露后 4 位）和是否可用；没登记时说明找平台运营 | `openapi-trade.yaml` 0.5.0 |

浏览器直传交付文件：巡检交付的上传地址是对象存储的预签名地址，对象存储要允许运营端来源的跨域 PUT（开发环境的 MinIO
默认允许）；读不到 ETag 时用占位值，后端按大小和 SHA-256 核对内容。

## 目录

```text
src/
├── main.ts                 # 挂 Pinia、路由、Element Plus；401 时回登录页
├── modules.ts              # 全部功能块的登记表（加菜单只改这里 + 新功能目录）
├── core/
│   ├── auth/               # 角色、令牌（内存 + sessionStorage）、登录状态、/auth 接口
│   ├── http/               # 接口客户端、错误码与中文说明
│   ├── navigation/         # AdminModule 类型与按角色出菜单、路由守卫、路由表
│   ├── ops/                # S3-1 第三部分共用：素材上传与预览（MediaField / MediaListField / MediaPreview）、改动记录表、
│   │                       #   上线前缺项提示（describeMissing）、元与分换算
│   └── ui/                 # 分页表格、usePagedList、页面区块、确认 / 留痕提示、表单规则、时间格式
├── layouts/AdminLayout.vue # 侧栏菜单 + 顶栏
└── features/
    ├── auth/               # 登录、首页、没有权限 / 页面不存在
    ├── city/               # 城市开通
    ├── organization/       # 机构入驻与资质审核
    ├── catalog/            # 商品与供给（含交易条款页签）
    ├── merchant-account/   # 工作台·本机构收款账户（只读）
    ├── aftersale-workbench/ # 工作台·售后（同意 / 拒绝 / 确认收货退款 / 验货不通过、默认寄回地址）
    ├── merchant-profile/   # 工作台·商家资料（提交审核）
    ├── merchant-profile-review/ # 运营端·商家资料审核
    ├── ops-merchant-account/ # 运营端·商家收款账户登记
    ├── import/             # 批量导入
    ├── feedback/           # 举报与反馈
    ├── activity/           # 活动与场次
    ├── venue/              # 场馆、项目、场次、馆内设备
    ├── content/            # 发现内容：图文、视频、专题
    ├── live/               # 直播间与直播主办机构
    ├── inspection-catalog/ # 巡检服务目录与类目
    ├── proservice/         # 四类专业服务目录
    └── system-notice/      # 系统通知（发布、撤回）
```

## 第三部分各块的共同约定（活动、场馆、内容、直播、服务目录）

- **改动留痕**：每块在自己模块的表里记改动（只追加：对象、动作、说明、操作人显示名快照、时间），详情页下方用
  `core/ops/ChangeLogTable.vue` 显示；接口返回的形状统一是 `OpsChange`（后端 `ops.common.OpsChangeView`）。
- **上线前校验必填**：上线 / 发布 / 上架时缺项，后端回 422 `OPS_PUBLISH_INCOMPLETE`，`details.missing` 是缺的字段键；
  页面用 `describeMissing(error, 字段中文表)` 提示“上线前还差：封面、场次”。
- **版本号**：写操作带详情里读到的 `version`，不对回 409 `OPS_VERSION_CONFLICT`，提示刷新。
- **素材**：图片、视频先经 `POST /api/v1/ops/media`（契约 `openapi-ops-media.yaml`：JPEG / PNG / WebP ≤5 MB，MP4 ≤50 MB，
  按文件头判断）存进对象存储端口，拿回受控 https 地址再填进表单；业务接口只收 https 地址。开发环境是本机目录模拟
  （`ops.media.local-dir`），地址前缀是打不开的 `https://media.dev.localhost`，所以页面用 `MediaPreview` 带登录取回预览。
- **后端**：业务拒绝在模块里抛 `ops.common.OpsException`（带本块的 `OpsError` 枚举），由 `ops.OpsExceptionHandler`
  统一翻成 ApiError，不用改全局异常处理；输入整理用 `ops.common.OpsText`；契约测试用 `OpsContractChecker.verifyOpsErrors`
  核对错误码。

## 新增一个运营菜单要改哪几处

以已经做好的“商品与供给”为例（后端接口 `/api/v1/ops/catalog/…`，契约 `contracts/openapi-ops-catalog.yaml`）：

1. **新建功能目录** `src/features/catalog/`：
   - `catalog-api.ts`：照 `city/city-api.ts` 写类型（和契约字段一一对应）和调用（`createCatalogApi(http)`）；列表接口返回
     `Page<T>`，直接给 `usePagedList` 用。
   - 页面 `ProductListPage.vue`：外层 `PageSection`（写操作的页面加 `traced`），列表用 `usePagedList` + `PagedTable`；
     写操作先 `confirmAction(...)`，成功后 `notifyRecorded(动作, 去哪看记录)`，失败 `showError(error)`；表单规则用
     `core/ui/form-rules.ts` 的 `required` / `maxChars` / `matches`，限制和后端一致。
   - `catalog-module.ts`：导出一个 `AdminModule`：菜单项（`path`、`title`、Element Plus 图标、分组 `PLATFORM` / `SUPPORT` /
     `WORKBENCH`、`roles`、`order`）和路由（绝对路径、`name`、懒加载页面、`meta: { title, roles }`）。菜单和路由的 `roles`
     要一样。
2. **登记**：在 `src/modules.ts` 的 `ADMIN_MODULES` 里加上 `catalogModule`。布局、菜单、守卫不用改。
3. **错误码**：契约里新的 `OPS_…` 错误码，在 `src/core/http/api-error.ts` 的 `MESSAGES` 里加一行中文说明。
4. **角色**：要是新角色（比如以后的商家管理员），先在身份模块加迁移，再加到 `src/core/auth/roles.ts` 的 `ADMIN_ROLES` 和
   `ROLE_LABELS`。
5. **测试**：`src/core/navigation/module.test.ts` 会自动检查新路由写了标题和角色、菜单指向的路由存在且角色一致；
   业务规则（校验、接口拼法）在功能目录里写 `*.test.ts`；接口拼法用 `core/http/recording-client.ts` 记下请求再断言。
   金额一律用 `core/ui/money.ts` 换算（接口是分，页面按元）。

商家 / 服务商工作台照同样的方式加，分组用 `WORKBENCH`、角色写机构角色（`PROVIDER_OPERATOR`、`LIVE_HOST`），后端接口按
令牌里的机构过滤。

后端一侧（给写接口的人）：运营接口统一放 `/api/v1/ops/…`，安全配置已经要求平台运营或平台客服之一；每个 ops 控制器用
`com.lowaltitude.inspection.ops.OpsActors` 的 `operator(...)` 或 `support(...)` 收紧到具体角色并拿到操作人显示名（写进模块自己
的操作记录）；契约每块一个 `contracts/openapi-ops-<块>.yaml`，契约测试照 `OpsCityOpenApiContractTest` 用
`OpsContractChecker`；三个 `@SpringBootTest`（`AuthenticationSecurityIntegrationTest`、`BootstrapContextIntegrationTest`、
`InspectionTimeSlotControllerTest`）的 `@MockBean` 里加上新服务。

## 命令

```bash
npm run type-check   # vue-tsc 严格模式
npm test             # vitest：接口客户端、路由守卫、菜单与功能块登记、登录状态、表单校验、登录页、改城市状态弹窗、
                     # 各功能块的接口与规则（商品 / 收款账户 / 导入 / 工作台）、上架按钮、导入结果，
                     # 以及 src/workbench.integration.test.ts（整个运营端 + 假后端：商家登录、待发货、发货、权限）
npm run build        # 类型检查 + 生产构建
```

## 对着真后端的 E2E

`apps/mobile/e2e/admin-workbench.spec.ts`（复用 App E2E 的真实后端、模拟微信和开发库工具）：先起运营端
`cd apps/admin && npm run dev`，再在 apps/mobile 里 `npx playwright test e2e/admin-workbench.spec.ts`。
运营端地址可用 `ADMIN_BASE_URL` 改；后端要带 S3-2 的接口（交易 0.5.0、巡检 2.3.0、需求 1.2.0、直播 1.1.0）。
截图存到仓库外层 `.planning/style-compare/admin-workbench/`。

## 新增依赖

全部锁定版本（`package-lock.json`），只装在 `apps/admin`：

| 依赖 | 版本 | 用途 | 许可证 |
| --- | --- | --- | --- |
| vue | 3.5.45 | 框架（AGENTS §4 锁定） | MIT |
| vue-router | 4.6.4 | 路由 | MIT |
| pinia | 3.0.4 | 状态 | MIT |
| element-plus | 2.14.8 | 组件库（AGENTS §4 锁定） | MIT |
| @element-plus/icons-vue | 2.3.2 | 菜单图标（Element Plus 官方图标） | MIT |
| vite / @vitejs/plugin-vue | 7.3.7 / 6.0.10 | 构建 | MIT |
| typescript / vue-tsc | 5.9.3 / 3.3.14 | 类型检查 | Apache-2.0 / MIT |
| vitest / @vue/test-utils / jsdom | 3.2.7 / 2.5.1 / 27.4.0 | 单元测试 | MIT |
| @vue/tsconfig / @types/node | 0.9.1 / 22.20.5 | 类型配置 | MIT |
