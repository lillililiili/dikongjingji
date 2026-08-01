# 平台频道内测预览整合实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `platform-channel-replacement` 工作树中未提交的服务、发现、商城预览页面安全整合到“飞享”集成分支，并保证巡检真实链路、应用品牌和 Android 状态栏修复不回退。

**Architecture:** 保留独立 `features/platform` 限界上下文和类型化 development fixture。增加统一编译期开关 `PLATFORM_PREVIEW_ENABLED`，开发环境自动开启，内测生产包通过 `VITE_PLATFORM_PREVIEW_ENABLED=true` 显式开启，公开生产构建默认关闭。巡检频道继续调用既有真实 API，其他频道只提供有明确“下一阶段接入”反馈的内测预览。

**Tech Stack:** uni-app、Vue 3、TypeScript strict、Vite、Pinia、Vitest、HBuilderX、Android ADB。

## Global Constraints

- 保留应用名称“飞享”、现有图标、包名和版本信息。
- 保留 `PlatformHomePage.vue` 已批准的 `var(--status-bar-height)` Android 修复。
- 不新增支付、退款、下单、商城交易、飞行控制或其他正式业务后端。
- 巡检频道必须继续调用真实巡检 API；不得替换为 fixture。
- 航拍、飞行、机器人、发现、商城预览数据只允许在开发环境或显式内测开关开启时加载。
- 公开生产构建不得包含 development fixture 数据。
- 所有自定义导航页面使用 `var(--status-bar-height)` 处理顶部状态栏。
- 不覆盖 `platform-channel-replacement` 工作树中的原始未提交文件。
- 本轮不提交、不推送。

---

### Task 1: 迁移平台预览模块和页面外壳

**Files:**
- Create: `apps/mobile/src/features/platform/**`
- Create: `apps/mobile/src/features/inspection/presentation/public.ts`
- Modify: `apps/mobile/src/features/inspection/presentation/portal/pages/InspectionPortalPage.vue`
- Modify: `apps/mobile/src/pages/service/index.vue`
- Modify: `apps/mobile/src/pages/discover/index.vue`
- Modify: `apps/mobile/src/pages/mall/index.vue`
- Modify: `apps/mobile/src/pages.json`
- Test: `apps/mobile/src/features/platform/tests/**`
- Test: `apps/mobile/src/features/inspection/tests/platform-integration.test.ts`

**Interfaces:**
- Consumes: `InspectionPortalPage`、`inspectionCatalogApi`、共享城市 store、平台天气适配器。
- Produces: 服务同页频道切换、内嵌巡检入口、发现和商城预览页面。

- [ ] 将 `platform-channel-replacement` 的 29 个移动端文件原样迁入集成工作树，不复制 `.playwright-cli`。
- [ ] 运行平台目标测试，确认迁移基线通过。
- [ ] 检查“飞享”品牌文件和首页状态栏补丁仍在。

### Task 2: 增加显式内测开关并修复真实巡检入口

**Files:**
- Create: `apps/mobile/src/features/platform/application/platform-preview.ts`
- Modify: `apps/mobile/src/pages/service/index.vue`
- Modify: `apps/mobile/src/pages/discover/index.vue`
- Modify: `apps/mobile/src/pages/mall/index.vue`
- Modify: `apps/mobile/src/features/platform/presentation/service/PlatformServicePage.vue`
- Modify: `apps/mobile/.env.example`
- Test: `apps/mobile/src/features/platform/tests/platform-service-page.test.ts`
- Test: `apps/mobile/src/features/platform/tests/platform-discover-page.test.ts`
- Test: `apps/mobile/src/features/platform/tests/platform-mall-page.test.ts`
- Test: `apps/mobile/src/features/inspection/tests/platform-integration.test.ts`

**Interfaces:**
- Produces: `PLATFORM_PREVIEW_ENABLED: boolean`。
- `PLATFORM_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'`。

- [ ] 先增加失败测试：三页使用统一内测开关，巡检搜索调用真实目录导航，城市按钮有反馈，公开生产环境隐藏未接入消息入口，快速切换频道时只有最后一次加载可以更新内容与恢复滚动。
- [ ] 运行目标测试并保存失败证据。
- [ ] 实现统一内测开关、最小事件接线和频道加载请求版本保护。
- [ ] 运行目标测试并确认通过。

### Task 3: 修复新页面 Android 顶部安全区

**Files:**
- Modify: `apps/mobile/src/features/platform/presentation/service/PlatformServicePage.vue`
- Modify: `apps/mobile/src/features/platform/presentation/discover/PlatformDiscoverPage.vue`
- Modify: `apps/mobile/src/features/platform/presentation/mall/PlatformMallPage.vue`
- Test: `apps/mobile/src/features/platform/tests/platform-service-page.test.ts`
- Test: `apps/mobile/src/features/platform/tests/platform-discover-page.test.ts`
- Test: `apps/mobile/src/features/platform/tests/platform-mall-page.test.ts`

- [ ] 将顶部 `env(safe-area-inset-top)` 断言改为 `var(--status-bar-height)` 并确认旧实现测试失败。
- [ ] 只修改三个页面的顶部安全区，保留底部 `env(safe-area-inset-bottom)`。
- [ ] 运行目标测试并确认通过。

### Task 4: 全量验证与真机内测

**Files:**
- Create: `apps/mobile/.env.production`
- Modify: `.gitignore`
- Temporary local build config only: `apps/mobile/.env.production.local`
- Evidence: ignored temporary directory only.

- [ ] 固化非敏感生产默认值 `VITE_PLATFORM_PREVIEW_ENABLED=false`，并验证未提供本地覆盖时 Rollup 不保留 fixture chunk。
- [ ] 移除 App-Plus IIFE 不支持的动态 import，改用可被生产常量裁剪的无副作用静态 fixture import，并同时验证内测 App 编译与公开生产产物隔离。
- [ ] 运行平台目标测试和移动端全量测试。
- [ ] 运行 `pnpm.cmd run type-check`。
- [ ] 运行启用内测开关的 H5 生产构建，并确认预览页面存在。
- [ ] 运行关闭内测开关的 H5 生产构建，并确认 fixture 文案不存在。
- [ ] 运行后端 reactor 测试，确认巡检和天气后端未回退。
- [ ] 使用 HBuilderX 调试基座在当前 Android 设备上验证服务、巡检、发现、商城及顶部状态栏。
- [ ] 查看完整 `git diff`、`git diff --stat` 和 `git diff --check`，确认未覆盖品牌、包名或无关工作树。
- [ ] 删除临时 `.env.production.local`，不提交、不推送。
