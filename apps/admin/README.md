# 运营端（apps/admin）

Web 运营端与以后的商家 / 服务商工作台（`docs/redesign/08-后续开发计划.md` S3-1，默认决定 D11、“工作台”）。
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
| `dev-provider`、`dev-organizer` 等 | 服务商操作员 PROVIDER_OPERATOR | 首页（工作台菜单以后再加） |

- `dev-support`、`dev-provider` 的口令和其他示例账号一样，见 `backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql`。
- `dev-operator` 的口令**不在仓库里**：平台运营权限大（跨租户读巡检、管机构和资质），所以只在启动后端时设了环境变量
  `DEV_OPERATOR_PASSWORD`（至少 12 位，只在 `dev` 配置下生效）才建这个账号，口令就是你设的值；不设就没有这个账号。
  改了这个值，下次启动后端时口令跟着改。种子：`backend/bootstrap/src/main/resources/db/development/identity-operator/`。
- 客户账号（`dev-customer`）没有运营端角色，登录后会被拒绝。
- 登录令牌 15 分钟过期（后端没有刷新令牌），过期后任何操作都会回到登录页；令牌只放内存和 sessionStorage。

## 功能清单（S3-1 第一部分、第二部分）

| 菜单 | 角色 | 能做的事 | 接口契约 |
| --- | --- | --- | --- |
| 城市开通 | 平台运营 | 按名称 / 状态查城市；改开通状态（已开通 / 筹备中 / 未开通，写说明、带版本号）；看每个城市的改动记录 | `contracts/openapi-ops-city.yaml` |
| 机构与资质 | 平台运营 | 机构列表（待审核在前）；新建机构（类型、名称、城市，带幂等键）；录入 / 修改资质文字；上传、下载、移除资质附件（PDF/JPEG/PNG ≤10 MB）；审核通过（写 `qualification_reviewed_at`，App 显示“资质已审核”）或驳回（写原因）；给机构建账号（初始口令只显示一次）、改机构角色（服务商操作员、直播主办；企业客户是客户角色）；操作记录 | `contracts/openapi-ops-organization.yaml` |
| 商品与供给 | 平台运营 | 商品列表（关键词、类目、交易方式、状态）；新建商品（类目、名称、交易方式，带幂等键，草稿）；详情四页：基本资料（一句话介绍、体验标识、排序、卖点标签 ≤5×12 字、参数、包装清单、封面、图集 ≤9、图文介绍 ≤30 段，整份保存带版本号）、规格（≤20，新建、改名、启用停用）、在售供给（商家 × 规格 × 城市 × 价格 × 库存 × 配送，新建是停用的；价格按元填、按分提交；询价不标价，租赁按天或半天；不能快递要有自提点；没登记收款账户的商家标红）、操作记录；上架前列出缺什么并禁用按钮，后端回 `OPS_PRODUCT_INCOMPLETE` 时显示 `details.blockers`；图片先上传（JPEG/PNG/WebP ≤5 MB）拿受控地址 | `contracts/openapi-ops-catalog.yaml` |
| 收款账户 | 平台运营 | 经营机构和它的微信二级商户号（是否登记筛选）；登记或修改商户号（8–32 位数字）和收款开关（带版本号）；看每家的登记记录。只登记，不做进件；占位号段 1900000xxx 只有开发环境能登记 | `contracts/openapi-ops-catalog.yaml`（merchant-accounts） |
| 批量导入 | 平台运营 | 选一类（经营主体与操作员、收款账户、商品与供给能导入；活动、场馆、服务、内容这一版只能预检），上传 `infra/import-templates/` 的 CSV 预检：逐行问题表、不进库的核对列、和以前相同时提示不重复写；预检通过后导入（一个事务），显示新建 / 更新的对象，新操作员账号的一次性初始口令只弹出这一次；导入记录和批次详情 | `contracts/openapi-ops-import.yaml` |
| 举报与反馈 | 平台客服 | 待处理 / 已处理 / 全部，按来源筛（用户反馈、举报视频评论、举报直播弹幕）；隐藏被举报的评论或弹幕（调内容、直播已有的受权接口）；写处理说明标为已处理（只处理一次） | `contracts/openapi-ops-feedback.yaml` |

公共能力：登录与退出、按角色出菜单、路由守卫（没登录去登录页并记住原页面，角色不对去“没有权限”）、接口客户端（统一错误体
`{code, message, traceId}`、错误码翻成中文、401 自动回登录页、请求号、幂等键、附件下载）、分页表格、表单校验、操作确认、
操作留痕提示（每个写操作成功后提示“操作人和时间已记录”，并告诉去哪里看）。

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
    ├── catalog/            # 商品与供给
    ├── merchant-account/   # 商家收款账户
    ├── import/             # 批量导入
    └── feedback/           # 举报与反馈
```

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
                     #         商品 / 收款账户 / 导入的接口拼法与规则、上架按钮、导入结果
npm run build        # 类型检查 + 生产构建
```

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
