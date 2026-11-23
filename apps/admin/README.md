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
- 后端要是带 S3-1 的版本（`/api/v1/ops/**`、身份迁移 V007、巡检 V012、客服 V002）。
- 生产：`npm run build` 出 `dist/` 静态文件，由 Nginx 托管，并把 `/api` 同源转发到后端（不用开 CORS）。

## 登录哪个开发账号

| 账号 | 角色 | 能看到 |
| --- | --- | --- |
| `dev-operator` | 平台运营 PLATFORM_OPERATOR | 城市开通、机构与资质 |
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

## 功能清单（S3-1 第一部分）

| 菜单 | 角色 | 能做的事 | 接口契约 |
| --- | --- | --- | --- |
| 城市开通 | 平台运营 | 按名称 / 状态查城市；改开通状态（已开通 / 筹备中 / 未开通，写说明、带版本号）；看每个城市的改动记录 | `contracts/openapi-ops-city.yaml` |
| 机构与资质 | 平台运营 | 机构列表（待审核在前）；新建机构（类型、名称、城市，带幂等键）；录入 / 修改资质文字；上传、下载、移除资质附件（PDF/JPEG/PNG ≤10 MB）；审核通过（写 `qualification_reviewed_at`，App 显示“资质已审核”）或驳回（写原因）；给机构建账号（初始口令只显示一次）、改机构角色（服务商操作员、直播主办；企业客户是客户角色）；操作记录 | `contracts/openapi-ops-organization.yaml` |
| 举报与反馈 | 平台客服 | 待处理 / 已处理 / 全部，按来源筛（用户反馈、举报视频评论、举报直播弹幕）；隐藏被举报的评论或弹幕（调内容、直播已有的受权接口）；写处理说明标为已处理（只处理一次） | `contracts/openapi-ops-feedback.yaml` |

公共能力：登录与退出、按角色出菜单、路由守卫（没登录去登录页并记住原页面，角色不对去“没有权限”）、接口客户端（统一错误体
`{code, message, traceId}`、错误码翻成中文、401 自动回登录页、请求号、幂等键、附件下载）、分页表格、表单校验、操作确认、
操作留痕提示（每个写操作成功后提示“操作人和时间已记录”，并告诉去哪里看）。

## 功能清单（S3-2 机构工作台，分组 WORKBENCH）

都只看、只改本机构的数据（机构只从令牌里取，别的机构的单一律当作不存在）。写操作先确认，成功后提示结果；这些业务接口
不在运营端另记操作记录（各模块自己记操作人，比如交易的状态流转、租赁的交接记录），所以不用“已记录”的提示。

| 菜单 | 角色 | 能做的事 | 接口契约 |
| --- | --- | --- | --- |
| 商城订单 | 机构操作员 | 待发货 / 待自提 / 已发货 / 退款中 / 全部；详情（商品、金额和还可退、收货信息、退款记录、时间线）；发货（物流公司、运单号，带 If-Match）；自提核销（6 位取货码，输错提示、10 次锁定）；退款（可部分，按元填，不超过实付 − 已退 − 正在退） | `openapi-trade.yaml` 0.5.0 |
| 商城询价 | 机构操作员 | 按状态看询价单；报价 / 改价（单价、型号、交期、包含内容、有效期 ≤7 天）；拒绝（写原因） | `openapi-mall-inquiry.yaml` |
| 租赁询价、租赁订单 | 机构操作员 | 报价 / 改价（日租金 × 天数 × 台数 + 其他费用，合计按分算）、拒绝；录入交接、录入归还（等客户在 App 确认）；交接前取消并整单退款 | `openapi-rental.yaml` |
| 需求报价 | 机构操作员 | 按状态看能报价的需求（没有发布人联系方式）；报价 / 改价、撤回 | `openapi-demand.yaml` 1.2.0 |
| 巡检预约 | 机构操作员 | 分给本机构的预约（待出方案 / 待客户确认 / 履约中 / 待验收 / 已完成）；出方案（日期、明细、交付物，合计自动算）；安排人员设备；每次巡检排期、开始、记进度、现场完成；上传交付文件（直传对象存储）并发布；看验收结果和付款节点。列表留了“付款状态”一列，巡检付款节点接入后从服务单 `paymentNodes` 读 | `openapi-inspection-service-flow.yaml` 2.3.0 |
| 客服会话 | 机构操作员 | 本机构的会话列表、未读数；回复（≤500 字，不留手机号），进会话标已读，往前翻更早的消息 | `openapi-support.yaml` |
| 直播间 | 直播主办 | 按状态看本机构直播间；建直播间、改预告；讲解清单和当前讲解项；回看链接；看弹幕、隐藏弹幕；推流地址（默认打码，点“显示”才出现） | `openapi-live.yaml` 1.1.0 |
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
│   └── ui/                 # 分页表格、usePagedList、页面区块、确认 / 留痕提示、表单规则、时间格式
├── layouts/AdminLayout.vue # 侧栏菜单 + 顶栏
└── features/
    ├── auth/               # 登录、首页、没有权限 / 页面不存在
    ├── city/               # 城市开通
    ├── organization/       # 机构入驻与资质审核
    └── feedback/           # 举报与反馈
```

## 新增一个运营菜单要改哪几处

以“商品与供给”为例（后端已经有 `/api/v1/ops/products…` 和契约 `contracts/openapi-ops-product.yaml`）：

1. **新建功能目录** `src/features/product/`：
   - `product-api.ts`：照 `city/city-api.ts` 写类型（和契约字段一一对应）和调用（`createProductApi(http)`）；列表接口返回
     `Page<T>`，直接给 `usePagedList` 用。
   - 页面 `ProductListPage.vue`：外层 `PageSection`（写操作的页面加 `traced`），列表用 `usePagedList` + `PagedTable`；
     写操作先 `confirmAction(...)`，成功后 `notifyRecorded(动作, 去哪看记录)`，失败 `showError(error)`；表单规则用
     `core/ui/form-rules.ts` 的 `required` / `maxChars` / `matches`，限制和后端一致。
   - `product-module.ts`：导出一个 `AdminModule`：菜单项（`path`、`title`、Element Plus 图标、分组 `PLATFORM` / `SUPPORT` /
     `WORKBENCH`、`roles`、`order`）和路由（绝对路径、`name`、懒加载页面、`meta: { title, roles }`）。菜单和路由的 `roles`
     要一样。
2. **登记**：在 `src/modules.ts` 的 `ADMIN_MODULES` 里加上 `productModule`。布局、菜单、守卫不用改。
3. **错误码**：契约里新的 `OPS_…` 错误码，在 `src/core/http/api-error.ts` 的 `MESSAGES` 里加一行中文说明。
4. **角色**：要是新角色（比如以后的商家管理员），先在身份模块加迁移，再加到 `src/core/auth/roles.ts` 的 `ADMIN_ROLES` 和
   `ROLE_LABELS`。
5. **测试**：`src/core/navigation/module.test.ts` 会自动检查新路由写了标题和角色、菜单指向的路由存在且角色一致；
   业务规则（校验、接口拼法）在功能目录里写 `*.test.ts`。

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
npm test             # vitest：接口客户端、路由守卫、菜单与功能块登记、登录状态、表单校验、各功能块的接口与规则、页面组件，
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
