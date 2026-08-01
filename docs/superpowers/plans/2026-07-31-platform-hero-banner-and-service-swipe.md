# Platform Hero Banner and Service Swipe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一首页、四个服务内容频道、发现和商城的 Banner 与 CTA 按钮，并让服务内容区支持可靠的左右滑动切换频道。

**Architecture:** 使用一个位于共享展示层的 `PlatformHeroBanner` 组件承载统一结构和样式，各页面只适配现有类型化内容并继续负责自己的业务事件。服务频道手势由纯函数解析，页面壳只收集触点并复用现有 `selectChannel` 事件，避免把请求和滚动恢复逻辑复制到手势代码。

**Tech Stack:** uni-app、Vue 3、TypeScript strict、Vitest、Vite、CSS Design Tokens、内置 imagegen。

## Global Constraints

- Banner 覆盖首页、我要航拍、我要巡检、飞行服务、机器人服务、发现和商城。
- “更多服务”保持点击入口，不配置 Banner、不参与左右滑动。
- Banner 结构固定为标题、橙色重点文案、说明、三个绿色卖点、橙色胶囊 CTA 和频道独立图片。
- 手势只监听服务内容区；50px 阈值；横向位移必须大于纵向位移的 1.2 倍；一次只切一项；首尾不循环。
- 不修改 Header、城市、天气、搜索、消息、底部导航、订单、预约、详情、支付、退款、接口和路由业务。
- 不新增依赖，不启动云打包，不生成 APK，不提交或推送 Git。
- 保留工作树中用户和其他任务的未提交改动。

---

## File Structure

### Create

- `apps/mobile/src/shared/presentation/PlatformHeroBanner.vue`：跨一级页面复用的 Banner 和 CTA 视图。
- `apps/mobile/src/features/platform/domain/platform-channel-swipe.ts`：无 UI 依赖的频道滑动解析器。
- `apps/mobile/src/features/platform/tests/platform-channel-swipe.test.ts`：手势阈值、方向和边界单元测试。
- `apps/mobile/src/features/platform/tests/platform-hero-banner.test.ts`：公共 Banner 结构与设计令牌契约测试。
- `apps/mobile/src/static/images/inspection/platform/aerial-service-hero-v3.png`：航拍频道横幅。
- `apps/mobile/src/static/images/inspection/platform/flight-service-hero-v3.png`：飞行服务频道横幅。
- `apps/mobile/src/static/images/inspection/platform/robot-service-hero-v3.png`：机器人服务频道横幅。

### Modify

- `apps/mobile/src/App.vue`：补充统一 Banner 和 CTA 设计令牌。
- `apps/mobile/src/features/platform/domain/platform-service-content.ts`：给服务 Hero 增加三个卖点。
- `apps/mobile/src/features/platform/domain/platform-discover-content.ts`：给发现 Hero 增加卖点和 CTA 文案。
- `apps/mobile/src/features/platform/domain/platform-mall-content.ts`：给商城 Hero 增加卖点和 CTA 文案。
- `apps/mobile/src/features/platform/data/development/platform-service.fixture.ts`：独立图片和频道卖点。
- `apps/mobile/src/features/platform/data/development/platform-discover.fixture.ts`：发现页卖点和明确 CTA。
- `apps/mobile/src/features/platform/data/development/platform-mall.fixture.ts`：商城页卖点和明确 CTA。
- `apps/mobile/src/features/platform/presentation/service/PlatformServicePage.vue`：内容区手势监听。
- `apps/mobile/src/features/platform/presentation/service/PlatformServiceChannelContent.vue`：改用公共 Banner。
- `apps/mobile/src/features/platform/presentation/discover/PlatformDiscoverPage.vue`：改用公共 Banner。
- `apps/mobile/src/features/platform/presentation/mall/PlatformMallPage.vue`：改用公共 Banner。
- `apps/mobile/src/features/inspection/presentation/portal/components/InspectionHero.vue`：用公共 Banner 适配巡检 Hero。
- `apps/mobile/src/features/inspection/presentation/platform/home/platform-home.ts`：Home Hero 增加 CTA 文案。
- `apps/mobile/src/features/inspection/presentation/platform/home/PlatformHomePage.vue`：改用公共 Banner。
- `apps/mobile/src/features/inspection/data/preview/platform-home-preview.ts`：为首页预览内容提供 CTA 文案。
- `apps/mobile/src/features/platform/tests/platform-service-page.test.ts`：服务 Banner 与手势集成契约。
- `apps/mobile/src/features/platform/tests/platform-discover-page.test.ts`：发现 Banner 复用契约。
- `apps/mobile/src/features/platform/tests/platform-mall-page.test.ts`：商城 Banner 复用契约。
- `apps/mobile/src/features/inspection/tests/platform-home.test.ts`：首页 Banner 复用契约。
- `apps/mobile/src/features/inspection/tests/inspection-portal.test.ts`：巡检 Banner 复用契约。

---

### Task 1: 频道滑动解析器

**Files:**

- Create: `apps/mobile/src/features/platform/domain/platform-channel-swipe.ts`
- Create: `apps/mobile/src/features/platform/tests/platform-channel-swipe.test.ts`

**Interfaces:**

- Consumes: `PlatformChannelCode` from `platform-channel.ts`.
- Produces: `resolvePlatformSwipeTarget(current, deltaX, deltaY): PlatformChannelCode | null`.

- [ ] **Step 1: 写失败测试**

```ts
import { describe, expect, it } from 'vitest'

import { resolvePlatformSwipeTarget } from '../domain/platform-channel-swipe'

describe('resolvePlatformSwipeTarget', () => {
  it('rejects short and vertical-dominant gestures', () => {
    expect(resolvePlatformSwipeTarget('AERIAL', -49, 0)).toBeNull()
    expect(resolvePlatformSwipeTarget('AERIAL', -80, 70)).toBeNull()
  })

  it('moves only across the four content channels', () => {
    expect(resolvePlatformSwipeTarget('AERIAL', -70, 10)).toBe('INSPECTION')
    expect(resolvePlatformSwipeTarget('INSPECTION', -70, 10)).toBe('FLIGHT')
    expect(resolvePlatformSwipeTarget('FLIGHT', -70, 10)).toBe('ROBOT')
    expect(resolvePlatformSwipeTarget('ROBOT', 70, 10)).toBe('FLIGHT')
  })

  it('does not wrap or enter more services', () => {
    expect(resolvePlatformSwipeTarget('AERIAL', 70, 10)).toBeNull()
    expect(resolvePlatformSwipeTarget('ROBOT', -70, 10)).toBeNull()
    expect(resolvePlatformSwipeTarget('MORE', -70, 10)).toBeNull()
  })
})
```

- [ ] **Step 2: 运行测试确认因缺少模块而失败**

Run from `apps/mobile`:

```powershell
pnpm.cmd exec vitest run src/features/platform/tests/platform-channel-swipe.test.ts
```

Expected: FAIL，提示无法解析 `platform-channel-swipe`。

- [ ] **Step 3: 实现最小纯函数**

```ts
import type { PlatformChannelCode } from './platform-channel'

export const PLATFORM_SWIPE_CHANNELS = [
  'AERIAL',
  'INSPECTION',
  'FLIGHT',
  'ROBOT',
] as const satisfies readonly PlatformChannelCode[]

const MIN_HORIZONTAL_DISTANCE = 50
const HORIZONTAL_DOMINANCE_RATIO = 1.2

export function resolvePlatformSwipeTarget(
  current: PlatformChannelCode,
  deltaX: number,
  deltaY: number,
): PlatformChannelCode | null {
  const horizontalDistance = Math.abs(deltaX)
  const verticalDistance = Math.abs(deltaY)
  if (
    horizontalDistance < MIN_HORIZONTAL_DISTANCE
    || horizontalDistance <= verticalDistance * HORIZONTAL_DOMINANCE_RATIO
  ) {
    return null
  }

  const currentIndex = PLATFORM_SWIPE_CHANNELS.indexOf(
    current as (typeof PLATFORM_SWIPE_CHANNELS)[number],
  )
  if (currentIndex < 0) return null

  const targetIndex = currentIndex + (deltaX < 0 ? 1 : -1)
  return PLATFORM_SWIPE_CHANNELS[targetIndex] ?? null
}
```

- [ ] **Step 4: 运行测试确认通过**

```powershell
pnpm.cmd exec vitest run src/features/platform/tests/platform-channel-swipe.test.ts
```

Expected: 3 tests PASS。

- [ ] **Step 5: 查看本任务差异，不提交**

```powershell
git diff -- apps/mobile/src/features/platform/domain/platform-channel-swipe.ts apps/mobile/src/features/platform/tests/platform-channel-swipe.test.ts
```

Expected: 只出现解析器和对应测试；保留未暂存状态。

---

### Task 2: 公共 Banner 与统一 CTA

**Files:**

- Create: `apps/mobile/src/shared/presentation/PlatformHeroBanner.vue`
- Create: `apps/mobile/src/features/platform/tests/platform-hero-banner.test.ts`
- Modify: `apps/mobile/src/App.vue`

**Interfaces:**

- Consumes: 标题、重点文案、说明、三个卖点、图片、CTA 文案和禁用状态。
- Produces: `action` 事件；统一 `.platform-hero-banner` DOM 和设计令牌。

- [ ] **Step 1: 写失败的公共组件契约测试**

```ts
import { describe, expect, it } from 'vitest'

import appSource from '../../../App.vue?raw'
import bannerSource from '../../../shared/presentation/PlatformHeroBanner.vue?raw'

describe('PlatformHeroBanner', () => {
  it('renders one shared structure with three trust points', () => {
    expect(bannerSource).toContain('platform-hero-banner__title-row')
    expect(bannerSource).toContain('props.highlights')
    expect(bannerSource).toContain('type="checkbox-filled"')
    expect(bannerSource).toContain("emit('action')")
  })

  it('uses shared banner and pill CTA tokens', () => {
    expect(appSource).toContain('--app-primary-banner-height: 248rpx')
    expect(appSource).toContain('--app-hero-action-height: 46rpx')
    expect(appSource).toContain('--app-hero-action-radius: 999px')
    expect(bannerSource).toContain('var(--app-hero-action-height)')
    expect(bannerSource).toContain('var(--app-hero-action-radius)')
  })
})
```

- [ ] **Step 2: 运行测试确认公共组件不存在**

```powershell
pnpm.cmd exec vitest run src/features/platform/tests/platform-hero-banner.test.ts
```

Expected: FAIL，提示无法加载 `PlatformHeroBanner.vue`。

- [ ] **Step 3: 在 App.vue 增加精确设计令牌**

```css
--app-hero-radius: 22rpx;
--app-hero-content-top: 26rpx;
--app-hero-content-left: 32rpx;
--app-hero-action-height: 46rpx;
--app-hero-action-padding-inline: 20rpx;
--app-hero-action-radius: 999px;
--app-hero-action-font-size: 21rpx;
--app-hero-action-gap: 10rpx;
```

- [ ] **Step 4: 创建公共 Banner 组件**

```vue
<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

const props = withDefaults(defineProps<{
  imageUrl: string
  imageAlt: string
  title: string
  highlight: string
  description: string
  highlights: readonly string[]
  actionLabel?: string
  actionDisabled?: boolean
}>(), {
  actionLabel: '',
  actionDisabled: false,
})

const emit = defineEmits<{ action: [] }>()
</script>

<template>
  <view class="platform-hero-banner">
    <image
      class="platform-hero-banner__image"
      :src="props.imageUrl"
      mode="aspectFill"
      lazy-load
      :aria-label="props.imageAlt"
    />
    <view class="platform-hero-banner__veil" />
    <view class="platform-hero-banner__bottom-fade" />
    <view class="platform-hero-banner__copy">
      <view class="platform-hero-banner__title-row">
        <text class="platform-hero-banner__title">{{ props.title }}</text>
        <text class="platform-hero-banner__highlight">{{ props.highlight }}</text>
      </view>
      <text class="platform-hero-banner__description">{{ props.description }}</text>
      <view class="platform-hero-banner__points">
        <view
          v-for="point in props.highlights"
          :key="point"
          class="platform-hero-banner__point"
        >
          <UniIcons type="checkbox-filled" size="14" color="#1f9853" />
          <text>{{ point }}</text>
        </view>
      </view>
      <button
        v-if="props.actionLabel"
        class="platform-hero-banner__action"
        :disabled="props.actionDisabled"
        @click="emit('action')"
      >
        <text>{{ props.actionLabel }}</text>
        <UniIcons type="right" size="14" color="#ffffff" />
      </button>
    </view>
  </view>
</template>
```

组件样式：

```css
.platform-hero-banner {
  position: relative;
  height: var(--app-primary-banner-height);
  overflow: hidden;
  border-radius: var(--app-hero-radius);
  background: #dcefff;
}

.platform-hero-banner__image,
.platform-hero-banner__veil,
.platform-hero-banner__bottom-fade {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.platform-hero-banner__veil {
  background: linear-gradient(
    90deg,
    rgba(240, 249, 255, 0.98) 0%,
    rgba(240, 249, 255, 0.9) 36%,
    rgba(240, 249, 255, 0.18) 72%,
    rgba(240, 249, 255, 0) 100%
  );
}

.platform-hero-banner__bottom-fade {
  top: auto;
  height: 30%;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0), rgba(255, 255, 255, 0.72));
}

.platform-hero-banner__copy {
  position: relative;
  z-index: 1;
  display: flex;
  width: 72%;
  padding: var(--app-hero-content-top) 0 0 var(--app-hero-content-left);
  flex-direction: column;
  align-items: flex-start;
  box-sizing: border-box;
}

.platform-hero-banner__title-row,
.platform-hero-banner__points,
.platform-hero-banner__point,
.platform-hero-banner__action {
  display: flex;
  align-items: center;
}

.platform-hero-banner__title-row {
  gap: 10rpx;
  align-items: baseline;
}

.platform-hero-banner__title,
.platform-hero-banner__highlight {
  font-size: 34rpx;
  font-weight: 900;
  line-height: 1.08;
}

.platform-hero-banner__highlight {
  color: #ff5a00;
}

.platform-hero-banner__description {
  display: -webkit-box;
  overflow: hidden;
  margin-top: 8rpx;
  color: #39434e;
  font-size: 18rpx;
  line-height: 1.35;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.platform-hero-banner__points {
  margin-top: 9rpx;
  gap: 13rpx;
  white-space: nowrap;
}

.platform-hero-banner__point {
  gap: 4rpx;
  color: #39434e;
  font-size: 15rpx;
}

.platform-hero-banner__action {
  min-height: var(--app-hero-action-height);
  margin-top: 10rpx;
  justify-content: center;
  gap: var(--app-hero-action-gap);
  padding: 0 var(--app-hero-action-padding-inline);
  border: 0;
  border-radius: var(--app-hero-action-radius);
  color: #ffffff;
  background: linear-gradient(
    90deg,
    var(--app-action-orange-start),
    var(--app-action-orange-end)
  );
  font-size: var(--app-hero-action-font-size);
  font-weight: 800;
  line-height: var(--app-hero-action-height);
}

.platform-hero-banner__action:active {
  transform: scale(0.98);
}

.platform-hero-banner__action:disabled {
  opacity: 0.48;
}

.platform-hero-banner__action:focus-visible {
  outline: 2px solid #7a2800;
  outline-offset: 2px;
}

.platform-hero-banner__action::after {
  border: 0;
}
```

- [ ] **Step 5: 运行组件契约测试**

```powershell
pnpm.cmd exec vitest run src/features/platform/tests/platform-hero-banner.test.ts
```

Expected: 2 tests PASS。

- [ ] **Step 6: 查看差异，不提交**

```powershell
git diff -- apps/mobile/src/App.vue apps/mobile/src/shared/presentation/PlatformHeroBanner.vue apps/mobile/src/features/platform/tests/platform-hero-banner.test.ts
```

Expected: 只出现公共设计令牌、组件和测试。

---

### Task 3: 生成三个独立服务频道横幅

**Files:**

- Create: `apps/mobile/src/static/images/inspection/platform/aerial-service-hero-v3.png`
- Create: `apps/mobile/src/static/images/inspection/platform/flight-service-hero-v3.png`
- Create: `apps/mobile/src/static/images/inspection/platform/robot-service-hero-v3.png`

**Interfaces:**

- Consumes: 公共 Banner 的右侧主体、左侧文字留白构图。
- Produces: 三张 3:1 横向 PNG；无文字、无 Logo、无水印。

- [ ] **Step 1: 使用内置 imagegen 分别生成三张图片**

航拍提示词：

```text
Use case: ads-marketing
Asset type: Chinese mobile app wide hero banner background, 3:1 landscape
Primary request: professional aerial photography service over a modern Chinese city waterfront
Subject: a realistic camera drone flying on the right side, city and water behind it
Style/medium: polished photorealistic commercial photography, trustworthy service marketplace
Composition/framing: main drone and detail on the right 45%, generous bright sky and soft negative space on the left for UI copy
Lighting/mood: clear daylight, fresh and professional
Color palette: sky blue, clean white, restrained warm sunlight
Constraints: no text, no logo, no watermark, no UI, no people, no cropped main drone
Avoid: 3D clay style, cartoon style, neon sci-fi, dark cockpit
```

飞行服务提示词：

```text
Use case: ads-marketing
Asset type: Chinese mobile app wide hero banner background, 3:1 landscape
Primary request: safe compliant drone flight preparation service at a professional low-altitude operations site
Subject: a ready-to-fly industrial drone and a subtle marked takeoff pad on the right, open safe airspace behind it
Style/medium: polished photorealistic commercial photography, credible and calm
Composition/framing: equipment concentrated on the right 45%, clean light negative space on the left for UI copy
Lighting/mood: clear daylight, orderly, secure
Color palette: pale blue, white, restrained green accents
Constraints: no text, no logo, no watermark, no UI, no people
Avoid: aerial city waterfront reused from other channels, 3D clay, cartoon, neon sci-fi
```

机器人服务提示词：

```text
Use case: ads-marketing
Asset type: Chinese mobile app wide hero banner background, 3:1 landscape
Primary request: intelligent service robot working in a clean modern urban service environment
Subject: a friendly but realistic delivery and inspection robot on the right, modern public space behind it
Style/medium: high-end photorealistic product and environment photography
Composition/framing: robot on the right 45%, bright uncluttered negative space on the left for UI copy
Lighting/mood: clean daylight, capable, approachable, trustworthy
Color palette: white, light blue, subtle green, neutral gray
Constraints: no text, no logo, no watermark, no UI, no exaggerated face
Avoid: 3D clay toy look, cartoon, orange mascot, neon sci-fi
```

- [ ] **Step 2: 检查生成结果并复制到项目**

逐张确认主体位置、风格、无文字和 3:1 构图；将选中结果非覆盖式保存为上面的三个 `-v3.png` 文件。

- [ ] **Step 3: 验证尺寸和文件有效**

```powershell
Add-Type -AssemblyName System.Drawing
Get-ChildItem 'src/static/images/inspection/platform/*-service-hero-v3.png' |
  ForEach-Object {
    $image = [System.Drawing.Image]::FromFile($_.FullName)
    [PSCustomObject]@{ Name = $_.Name; Width = $image.Width; Height = $image.Height }
    $image.Dispose()
  }
```

Run from `apps/mobile`. Expected: 3 张图均可读取，宽高比接近 3:1。

---

### Task 4: 服务频道与巡检接入公共 Banner

**Files:**

- Modify: `apps/mobile/src/features/platform/domain/platform-service-content.ts`
- Modify: `apps/mobile/src/features/platform/data/development/platform-service.fixture.ts`
- Modify: `apps/mobile/src/features/platform/presentation/service/PlatformServiceChannelContent.vue`
- Modify: `apps/mobile/src/features/inspection/presentation/portal/components/InspectionHero.vue`
- Modify: `apps/mobile/src/features/platform/tests/platform-service-page.test.ts`
- Modify: `apps/mobile/src/features/inspection/tests/inspection-portal.test.ts`

**Interfaces:**

- Consumes: `PlatformHeroBanner` and现有服务/巡检业务事件。
- Produces: 四个服务内容频道一致的 Banner 结构；不同图片、文案和卖点。

- [ ] **Step 1: 增加失败契约**

在服务测试中增加：

```ts
expect(channelContentSource).toContain('<PlatformHeroBanner')
expect(channelContentSource).toContain(':highlights="content.hero.highlights"')
expect(channelContentSource).not.toContain('service-channel__hero-action')
expect(serviceFixtureSource).toContain("image('aerial-service-hero-v3.png')")
expect(serviceFixtureSource).toContain("image('flight-service-hero-v3.png')")
expect(serviceFixtureSource).toContain("image('robot-service-hero-v3.png')")
```

在巡检测试中增加：

```ts
expect(heroSource).toContain('<PlatformHeroBanner')
expect(heroSource).toContain(':highlights="hero.highlights"')
expect(heroSource).toContain('@action="$emit(\'request\')"')
```

- [ ] **Step 2: 运行定向测试确认失败**

```powershell
pnpm.cmd exec vitest run src/features/platform/tests/platform-service-page.test.ts src/features/inspection/tests/inspection-portal.test.ts
```

Expected: FAIL，缺少公共组件引用和独立图片。

- [ ] **Step 3: 扩展类型与数据**

```ts
export interface PlatformServiceHero {
  imageUrl: string
  title: string
  highlight: string
  description: string
  highlights: readonly string[]
  actionLabel: string
}
```

三个频道分别使用：

```ts
// AERIAL
highlights: ['专业团队', '高清交付', '合规飞行']

// FLIGHT
highlights: ['飞前检查', '安全合规', '全程保障']

// ROBOT
highlights: ['专业设备', '快速响应', '服务透明']
```

并将图片分别替换为 Task 3 的三个 `-v3.png`。

- [ ] **Step 4: 两个原组件接入公共 Banner**

服务组件映射：

```vue
<PlatformHeroBanner
  :image-url="content.hero.imageUrl"
  :image-alt="`${content.hero.title}${content.hero.highlight}`"
  :title="content.hero.title"
  :highlight="content.hero.highlight"
  :description="content.hero.description"
  :highlights="content.hero.highlights"
  :action-label="content.hero.actionLabel"
  @action="emit('intent', { type: 'SEARCH', channel: content.code })"
/>
```

巡检组件映射：

```vue
<PlatformHeroBanner
  :image-url="hero.imageUrl"
  image-alt="无人机巡检工业设施"
  :title="hero.title"
  :highlight="hero.accent"
  :description="hero.description"
  :highlights="hero.highlights"
  :action-label="showRequestAction ? '发布巡检需求' : ''"
  @action="$emit('request')"
/>
```

删除两处已被公共组件替代的 Hero CSS，不改下游卡片、目录和业务事件。

- [ ] **Step 5: 运行定向测试**

```powershell
pnpm.cmd exec vitest run src/features/platform/tests/platform-service-page.test.ts src/features/inspection/tests/inspection-portal.test.ts
```

Expected: 全部 PASS。

- [ ] **Step 6: 查看差异，不提交**

```powershell
git diff -- apps/mobile/src/features/platform/domain/platform-service-content.ts apps/mobile/src/features/platform/data/development/platform-service.fixture.ts apps/mobile/src/features/platform/presentation/service/PlatformServiceChannelContent.vue apps/mobile/src/features/inspection/presentation/portal/components/InspectionHero.vue
```

Expected: 只包含 Hero 适配、类型和内容配置。

---

### Task 5: 服务内容区接入左右滑动

**Files:**

- Modify: `apps/mobile/src/features/platform/presentation/service/PlatformServicePage.vue`
- Modify: `apps/mobile/src/features/platform/tests/platform-service-page.test.ts`

**Interfaces:**

- Consumes: `resolvePlatformSwipeTarget`。
- Produces: 仍通过 `selectChannel: [channel: PlatformChannelCode]` 发出频道变化。

- [ ] **Step 1: 写失败集成契约**

```ts
expect(shellSource).toContain('resolvePlatformSwipeTarget')
expect(shellSource).toContain('@touchstart="beginContentSwipe"')
expect(shellSource).toContain('@touchend="finishContentSwipe"')
expect(shellSource).toContain('@touchcancel="cancelContentSwipe"')
expect(shellSource).toContain("emit('selectChannel', targetChannel)")
```

- [ ] **Step 2: 运行服务页测试确认失败**

```powershell
pnpm.cmd exec vitest run src/features/platform/tests/platform-service-page.test.ts
```

Expected: FAIL，缺少手势监听。

- [ ] **Step 3: 在页面壳收集触点并调用纯函数**

```ts
interface PlatformTouchPoint {
  clientX: number
  clientY: number
}

interface PlatformTouchEvent {
  touches?: readonly PlatformTouchPoint[]
  changedTouches?: readonly PlatformTouchPoint[]
}

const contentTouchStart = ref<PlatformTouchPoint | null>(null)

function firstTouch(event: PlatformTouchEvent): PlatformTouchPoint | null {
  return event.changedTouches?.[0] ?? event.touches?.[0] ?? null
}

function beginContentSwipe(event: PlatformTouchEvent): void {
  contentTouchStart.value = firstTouch(event)
}

function cancelContentSwipe(): void {
  contentTouchStart.value = null
}

function finishContentSwipe(event: PlatformTouchEvent): void {
  const start = contentTouchStart.value
  const end = firstTouch(event)
  contentTouchStart.value = null
  if (!start || !end) return

  const targetChannel = resolvePlatformSwipeTarget(
    props.activeChannel,
    end.clientX - start.clientX,
    end.clientY - start.clientY,
  )
  if (targetChannel) emit('selectChannel', targetChannel)
}
```

模板只在内容节点绑定：

```vue
<main
  class="platform-service-page__content"
  @touchstart="beginContentSwipe"
  @touchend="finishContentSwipe"
  @touchcancel="cancelContentSwipe"
>
  <slot />
</main>
```

不得使用 `.prevent` 或 `.stop`，从而保留上下滚动和内部点击。

- [ ] **Step 4: 运行手势单测和集成测试**

```powershell
pnpm.cmd exec vitest run src/features/platform/tests/platform-channel-swipe.test.ts src/features/platform/tests/platform-service-page.test.ts
```

Expected: 全部 PASS。

- [ ] **Step 5: 查看差异，不提交**

```powershell
git diff -- apps/mobile/src/features/platform/presentation/service/PlatformServicePage.vue apps/mobile/src/features/platform/tests/platform-service-page.test.ts
```

Expected: Header、频道按钮栏和 RootAppTabBar 没有手势监听。

---

### Task 6: 首页、发现和商城接入公共 Banner

**Files:**

- Modify: `apps/mobile/src/features/inspection/presentation/platform/home/platform-home.ts`
- Modify: `apps/mobile/src/features/inspection/data/preview/platform-home-preview.ts`
- Modify: `apps/mobile/src/features/inspection/presentation/platform/home/PlatformHomePage.vue`
- Modify: `apps/mobile/src/features/platform/domain/platform-discover-content.ts`
- Modify: `apps/mobile/src/features/platform/data/development/platform-discover.fixture.ts`
- Modify: `apps/mobile/src/features/platform/presentation/discover/PlatformDiscoverPage.vue`
- Modify: `apps/mobile/src/features/platform/domain/platform-mall-content.ts`
- Modify: `apps/mobile/src/features/platform/data/development/platform-mall.fixture.ts`
- Modify: `apps/mobile/src/features/platform/presentation/mall/PlatformMallPage.vue`
- Modify: `apps/mobile/src/features/inspection/tests/platform-home.test.ts`
- Modify: `apps/mobile/src/features/platform/tests/platform-discover-page.test.ts`
- Modify: `apps/mobile/src/features/platform/tests/platform-mall-page.test.ts`

**Interfaces:**

- Consumes: `PlatformHeroBanner`。
- Produces: 三个一级页面同构 Banner；各自保留图片、文案和业务事件。

- [ ] **Step 1: 为三个页面添加失败契约**

首页测试增加：

```ts
expect(homePageSource).toContain('<PlatformHeroBanner')
expect(homePageSource).toContain(':highlights="props.state.content.hero.highlights"')
expect(homePageSource).toContain('@action="emit(\'search\')"')
expect(homePageSource).not.toContain('platform-home__hero-action')
```

发现测试增加：

```ts
import { DEVELOPMENT_PLATFORM_DISCOVER_CONTENT } from '../data/development/platform-discover.fixture'

expect(discoverPageSource).toContain('<PlatformHeroBanner')
expect(discoverPageSource).toContain(':highlights="content.hero.highlights"')
expect(discoverPageSource).toContain("type: 'OPEN_LOCATION'")
expect(discoverPageSource).not.toContain('discover-page__hero-action')
expect(DEVELOPMENT_PLATFORM_DISCOVER_CONTENT.hero.highlights).toHaveLength(3)
expect(DEVELOPMENT_PLATFORM_DISCOVER_CONTENT.hero.actionLabel).toBe('发现附近精彩')
```

商城测试增加：

```ts
import { DEVELOPMENT_PLATFORM_MALL_CONTENT } from '../data/development/platform-mall.fixture'

expect(mallPageSource).toContain('<PlatformHeroBanner')
expect(mallPageSource).toContain(':highlights="content.hero.highlights"')
expect(mallPageSource).toContain("itemId: 'mall-hero'")
expect(mallPageSource).not.toContain('mall-page__hero-action')
expect(DEVELOPMENT_PLATFORM_MALL_CONTENT.hero.highlights).toHaveLength(3)
expect(DEVELOPMENT_PLATFORM_MALL_CONTENT.hero.actionLabel).toBe('查看精选商品')
```

首页 Hero 类型和预览数据必须包含 `actionLabel: '浏览精选服务'`。

- [ ] **Step 2: 运行三个页面的测试确认失败**

```powershell
pnpm.cmd exec vitest run src/features/inspection/tests/platform-home.test.ts src/features/platform/tests/platform-discover-page.test.ts src/features/platform/tests/platform-mall-page.test.ts
```

Expected: FAIL，页面仍包含各自复制的 Hero。

- [ ] **Step 3: 扩展页面内容类型与预览数据**

首页：

```ts
export interface PlatformHomeHero {
  imageUrl: string
  title: string
  highlight: string
  description: string
  highlights: readonly string[]
  actionLabel: string
}
```

发现：

```ts
hero: {
  imageUrl: string
  title: string
  highlight: string
  description: string
  highlights: readonly string[]
  actionLabel: string
}
```

商城使用同一字段形状。预览内容分别设置：

```ts
// 首页
highlights: ['多元服务', '专业保障', '便捷响应']
actionLabel: '浏览精选服务'

// 发现
highlights: ['本地活动', '精选攻略', '兴趣社群']
actionLabel: '发现附近精彩'

// 商城
highlights: ['正品保障', '专业选型', '售后无忧']
actionLabel: '查看精选商品'
```

- [ ] **Step 4: 三个页面替换为公共 Banner**

首页映射：

```vue
<PlatformHeroBanner
  :image-url="props.state.content.hero.imageUrl"
  :image-alt="`${props.state.content.hero.title}${props.state.content.hero.highlight}`"
  :title="props.state.content.hero.title"
  :highlight="props.state.content.hero.highlight"
  :description="props.state.content.hero.description"
  :highlights="props.state.content.hero.highlights"
  :action-label="props.state.content.hero.actionLabel"
  @action="emit('search')"
/>
```

发现映射：

```vue
<PlatformHeroBanner
  :image-url="content.hero.imageUrl"
  :image-alt="`${content.hero.title}${content.hero.highlight}`"
  :title="content.hero.title"
  :highlight="content.hero.highlight"
  :description="content.hero.description"
  :highlights="content.hero.highlights"
  :action-label="content.hero.actionLabel"
  @action="emit('intent', { type: 'OPEN_LOCATION' })"
/>
```

商城映射：

```vue
<PlatformHeroBanner
  :image-url="content.hero.imageUrl"
  :image-alt="`${content.hero.title}${content.hero.highlight}`"
  :title="content.hero.title"
  :highlight="content.hero.highlight"
  :description="content.hero.description"
  :highlights="content.hero.highlights"
  :action-label="content.hero.actionLabel"
  @action="emit('intent', { type: 'OPEN_ITEM', itemId: 'mall-hero', itemKind: 'PRODUCT' })"
/>
```

删除三个页面被替代的 Hero CSS，保留页面整体间距、现有 `PlatformFeatureIntent` 和下游区块；不得新增路由。

- [ ] **Step 5: 运行三个页面定向测试**

```powershell
pnpm.cmd exec vitest run src/features/inspection/tests/platform-home.test.ts src/features/platform/tests/platform-discover-page.test.ts src/features/platform/tests/platform-mall-page.test.ts
```

Expected: 全部 PASS。

- [ ] **Step 6: 查看差异，不提交**

```powershell
git diff -- apps/mobile/src/features/inspection/presentation/platform/home apps/mobile/src/features/inspection/data/preview/platform-home-preview.ts apps/mobile/src/features/platform/domain/platform-discover-content.ts apps/mobile/src/features/platform/data/development/platform-discover.fixture.ts apps/mobile/src/features/platform/presentation/discover/PlatformDiscoverPage.vue apps/mobile/src/features/platform/domain/platform-mall-content.ts apps/mobile/src/features/platform/data/development/platform-mall.fixture.ts apps/mobile/src/features/platform/presentation/mall/PlatformMallPage.vue
```

Expected: 不包含 Header、底部导航、业务路由或接口变化。

---

### Task 7: 全量回归与同视口视觉验收

**Files:**

- Modify only if a preceding targeted test exposes a direct regression in an already approved file.
- Evidence output: `artifacts/ux-audit-20260731-platform-hero/`

**Interfaces:**

- Consumes: Tasks 1–6 的实现。
- Produces: 自动测试、构建、截图、尺寸表和最终范围审计。

- [ ] **Step 1: 运行移动端全量测试**

Run from `apps/mobile`:

```powershell
pnpm.cmd test
```

Expected: 所有测试 PASS，数量不少于修改前的 229。

- [ ] **Step 2: 运行类型检查**

```powershell
pnpm.cmd run type-check
```

Expected: exit code 0。

- [ ] **Step 3: 运行 H5 生产构建**

```powershell
pnpm.cmd run build:h5
```

Expected: exit code 0；无 Vue、TypeScript 或资源解析错误。

- [ ] **Step 4: 在 393×852 视口遍历页面**

使用当前 H5 预览依次检查：

```text
/#/
/#/pages/service/index（四个内容频道）
/#/pages/discover/index
/#/pages/mall/index
```

保存首页、四个服务频道、发现和商城共 7 张截图到 `artifacts/ux-audit-20260731-platform-hero/`。

- [ ] **Step 5: 测量统一元素**

记录每个页面 `.platform-hero-banner` 和 `.platform-hero-banner__action` 的：

```text
x, y, width, height, padding, gap, border-radius, font-size, line-height
```

Expected:

- Banner 高度和圆角完全一致。
- CTA 高度、圆角、字体、箭头间距完全一致。
- 图片、标题、重点文案和三个卖点均存在。
- 不出现省略号占位、文字裁切、竖排或底部导航遮挡。

- [ ] **Step 6: 验证滑动与误触**

在服务页内容区执行：

```text
航拍左滑 -> 巡检
巡检左滑 -> 飞行
飞行左滑 -> 机器人
机器人左滑 -> 保持机器人
机器人右滑 -> 飞行
小于 50px 横移 -> 不切换
明显纵向滚动 -> 不切换
点击 Banner CTA 和内容卡片 -> 原事件仍触发
```

Expected: 频道高亮和内容同步；不进入“更多服务”；不影响上下滚动。

- [ ] **Step 7: 审计最终差异**

```powershell
git diff --stat
git diff --check
git status --short
```

Expected:

- `git diff --check` exit code 0。
- 没有无关格式化、删除、重命名、订单/预约/详情/支付/退款变化。
- 新图片与新测试可见，但不暂存、不提交。
- 不启动云打包。
