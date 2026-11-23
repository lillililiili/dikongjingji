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
- 后端要是带 S3-1 的版本（`/api/v1/ops/**`、身份迁移 V007、巡检 V012、客服 V002）。
- 生产：`npm run build` 出 `dist/` 静态文件，由 Nginx 托管，并把 `/api` 同源转发到后端（不用开 CORS）。

## 登录哪个开发账号

| 账号 | 角色 | 能看到 |
| --- | --- | --- |
| `dev-operator` | 平台运营 PLATFORM_OPERATOR | 城市开通、机构与资质 |
| `dev-support` | 平台客服 PLATFORM_SUPPORT | 举报与反馈 |
| `dev-provider`、`dev-organizer` 等 | 服务商操作员 PROVIDER_OPERATOR | 首页（工作台菜单以后再加） |

- `dev-support`、`dev-provider` 的口令和其他示例账号一样，见 `backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql`。
- `dev-operator` 的口令**不在仓库里**：平台运营权限大（跨租户读巡检、管机构和资质），所以只在启动后端时设了环境变量
  `DEV_OPERATOR_PASSWORD`（至少 12 位，只在 `dev` 配置下生效）才建这个账号，口令就是你设的值；不设就没有这个账号。
  改了这个值，下次启动后端时口令跟着改。种子：`backend/bootstrap/src/main/resources/db/development/identity-operator/`。
- 客户账号（`dev-customer`）没有运营端角色，登录后会被拒绝。
- 登录令牌 15 分钟过期（后端没有刷新令牌），过期后任何操作都会回到登录页；令牌只放内存和 sessionStorage。

## 功能清单（S3-1 第一部分）

| 菜单 | 角色 | 能做的事 | 接口契约 |
| --- | --- | --- | --- |
| 城市开通 | 平台运营 | 按名称 / 状态查城市；改开通状态（已开通 / 筹备中 / 未开通，写说明、带版本号）；看每个城市的改动记录 | `contracts/openapi-ops-city.yaml` |
| 机构与资质 | 平台运营 | 机构列表（待审核在前）；新建机构（类型、名称、城市，带幂等键）；录入 / 修改资质文字；上传、下载、移除资质附件（PDF/JPEG/PNG ≤10 MB）；审核通过（写 `qualification_reviewed_at`，App 显示“资质已审核”）或驳回（写原因）；给机构建账号（初始口令只显示一次）、改机构角色（服务商操作员、直播主办；企业客户是客户角色）；操作记录 | `contracts/openapi-ops-organization.yaml` |
| 场馆与设备 | 平台运营 | 场馆草稿（运营机构、城市、封面、地址、电话、营业时间、检票与迟到规则、信息条目、排序）、上线前校验必填（封面、营业时间、启用的项目、未来在售场次）、上线 / 下线；项目（时长、价格、年龄、每场人数）启用 / 停用；按周模板批量生成场次（≤200 场，已有和已过的跳过）、停售 / 恢复 / 取消场次（有预约只能停售）；馆内设备及关联商品；改动记录 | `contracts/openapi-ops-venue.yaml` |
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
npm test             # vitest：接口客户端、路由守卫、菜单与功能块登记、登录状态、表单校验、登录页和改城市状态弹窗
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
