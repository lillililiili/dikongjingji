<script setup lang="ts">
import { onPageScroll } from '@dcloudio/uni-app'
import { computed, nextTick, ref, watch } from 'vue'

import { InspectionPortalPage } from '@/features/inspection/presentation/public'
import { loadPlatformServiceContent } from '@/features/platform/application/load-platform-service-content'
import { createPlatformFeatureGateway } from '@/features/platform/application/platform-feature-gateway'
import { PLATFORM_PREVIEW_ENABLED } from '@/features/platform/application/platform-preview'
import { developmentPlatformContentRepository } from '@/features/platform/data/development/development-platform-content-repository'
import {
  DEFAULT_PLATFORM_CHANNEL,
  PLATFORM_SERVICE_CHANNELS,
  resolveVisiblePlatformChannels,
  type PlatformChannelCode,
} from '@/features/platform/domain/platform-channel'
import { resolvePlatformSwipeTarget } from '@/features/platform/domain/platform-channel-swipe'
import type { PlatformContentRepository } from '@/features/platform/domain/platform-content-repository'
import type { PlatformFeatureIntent } from '@/features/platform/domain/platform-feature-intent'
import type { PlatformServiceChannelContent as PlatformServiceChannelViewContent } from '@/features/platform/domain/platform-service-content'
import PlatformServiceChannelContent from '@/features/platform/presentation/service/PlatformServiceChannelContent.vue'

interface PlatformTouchPoint {
  clientX: number
  clientY: number
}

const props = defineProps<{
  active: boolean
}>()

const emit = defineEmits<{
  searchPlaceholderChange: [placeholder: string | null]
}>()

const activeChannel = ref<PlatformChannelCode>(DEFAULT_PLATFORM_CHANNEL)
const channelContent = ref<PlatformServiceChannelViewContent | null>(null)
const currentScrollTop = ref(0)
const contentTouchStart = ref<PlatformTouchPoint | null>(null)
const scrollPositions = new Map<PlatformChannelCode, number>()
let channelRequestVersion = 0

const visibleChannels = resolveVisiblePlatformChannels(PLATFORM_PREVIEW_ENABLED)
const channels = computed(() =>
  PLATFORM_SERVICE_CHANNELS.filter((channel) =>
    visibleChannels.includes(channel.code)),
)
const activeChannelLabel = computed(() =>
  PLATFORM_SERVICE_CHANNELS.find((channel) =>
    channel.code === activeChannel.value)?.label ?? '',
)
const featureGateway = createPlatformFeatureGateway(PLATFORM_PREVIEW_ENABLED
  ? {
      SEARCH: 'NEXT_PHASE',
      OPEN_ITEM: 'NEXT_PHASE',
      PUBLISH_DEMAND: 'NEXT_PHASE',
    }
  : {
      SEARCH: 'HIDDEN',
      OPEN_ITEM: 'HIDDEN',
      PUBLISH_DEMAND: 'HIDDEN',
    })

function createContentRepository(): PlatformContentRepository | null {
  return PLATFORM_PREVIEW_ENABLED ? developmentPlatformContentRepository : null
}

async function loadChannelContent(): Promise<boolean> {
  const requestedChannel = activeChannel.value
  const requestVersion = ++channelRequestVersion
  if (requestedChannel === 'INSPECTION') {
    channelContent.value = null
    return true
  }
  const result = await loadPlatformServiceContent(
    requestedChannel,
    createContentRepository(),
  )
  if (requestVersion !== channelRequestVersion || requestedChannel !== activeChannel.value) {
    return false
  }
  channelContent.value = result.kind === 'ready' ? result.content : null
  return true
}

// Only inspection searches the home catalog, so other types take over the search box.
function reportSearchPlaceholder(): void {
  emit('searchPlaceholderChange', activeChannel.value === 'INSPECTION'
    ? null
    : channelContent.value?.searchPlaceholder ?? `搜索${activeChannelLabel.value}服务`)
}

async function selectChannel(channel: PlatformChannelCode): Promise<void> {
  if (!visibleChannels.includes(channel)) return
  scrollPositions.set(activeChannel.value, currentScrollTop.value)
  activeChannel.value = channel
  const loaded = await loadChannelContent()
  if (!loaded) return
  reportSearchPlaceholder()
  await nextTick()
  if (channel !== activeChannel.value) return
  uni.pageScrollTo({
    scrollTop: scrollPositions.get(channel) ?? 0,
    duration: 0,
  })
}

function handleIntent(intent: PlatformFeatureIntent): void {
  const resolution = featureGateway.resolve(intent)
  if (resolution.action === 'SHOW_NEXT_PHASE') {
    uni.showToast({ title: resolution.message, icon: 'none' })
  }
}

function firstTouch(event: TouchEvent): PlatformTouchPoint | null {
  return event.changedTouches[0] ?? event.touches[0] ?? null
}

function beginContentSwipe(event: TouchEvent): void {
  contentTouchStart.value = firstTouch(event)
}

function cancelContentSwipe(): void {
  contentTouchStart.value = null
}

function finishContentSwipe(event: TouchEvent): void {
  const start = contentTouchStart.value
  const end = firstTouch(event)
  contentTouchStart.value = null
  if (!start || !end) return

  const targetChannel = resolvePlatformSwipeTarget(
    activeChannel.value,
    end.clientX - start.clientX,
    end.clientY - start.clientY,
  )
  if (targetChannel) void selectChannel(targetChannel)
}

// Every entry into the channel lands on inspection, whichever type was open before leaving.
watch(() => props.active, (active) => {
  if (active || activeChannel.value === DEFAULT_PLATFORM_CHANNEL) return
  activeChannel.value = DEFAULT_PLATFORM_CHANNEL
  channelContent.value = null
  reportSearchPlaceholder()
})

// The channel stays mounted behind v-show, so scrolling on other home channels must not count here.
onPageScroll(({ scrollTop }) => {
  if (props.active) currentScrollTop.value = scrollTop
})
</script>

<template>
  <view class="service-channel" data-testid="service-channel">
    <scroll-view class="service-channel__types" scroll-x :show-scrollbar="false">
      <view class="service-channel__type-row">
        <button
          v-for="channel in channels"
          :key="channel.code"
          :class="[
            'service-channel__type',
            {
              'service-channel__type--active':
                channel.code === activeChannel,
            },
          ]"
          :data-testid="`service-type-${channel.code.toLowerCase()}`"
          :aria-pressed="channel.code === activeChannel"
          @click="selectChannel(channel.code)"
        >
          <text>{{ channel.label }}</text>
        </button>
      </view>
    </scroll-view>

    <main
      class="service-channel__content"
      @touchstart="beginContentSwipe"
      @touchend="finishContentSwipe"
      @touchcancel="cancelContentSwipe"
    >
      <InspectionPortalPage v-if="activeChannel === 'INSPECTION'" />
      <PlatformServiceChannelContent
        v-else-if="channelContent"
        :content="channelContent"
        :type-label="activeChannelLabel"
        @intent="handleIntent"
      />
      <view
        v-else
        class="service-channel__notice"
        data-testid="service-channel-notice"
      >
        <text class="service-channel__notice-title">
          {{ activeChannelLabel }}服务
        </text>
        <text class="service-channel__notice-copy">
          该类服务按项目报价，发布需求后由已审核的服务商报价
        </text>
        <button
          class="service-channel__notice-action"
          @click="handleIntent({ type: 'PUBLISH_DEMAND' })"
        >
          发布需求
        </button>
      </view>
    </main>
  </view>
</template>

<style scoped>
.service-channel {
  width: 100%;
  color: #17191d;
  box-sizing: border-box;
  font-family:
    system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC",
    "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.service-channel__types {
  width: 100%;
  white-space: nowrap;
}

.service-channel__type-row {
  display: inline-flex;
  gap: 8px;
  padding: 6px var(--app-page-horizontal-gutter);
  box-sizing: border-box;
}

.service-channel__type {
  position: relative;
  display: inline-flex;
  height: 32px;
  flex: 0 0 auto;
  align-items: center;
  padding: 0 12px;
  border-radius: 999px;
  color: #191919;
  background: #ffffff;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

/* Extends the 32px pill to the 44px touch minimum without changing its look. */
.service-channel__type::before {
  position: absolute;
  top: -6px;
  right: -4px;
  bottom: -6px;
  left: -4px;
  content: "";
}

.service-channel__type--active {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.service-channel__content {
  width: 100%;
  max-width: 600px;
  margin: 0 auto;
  padding: 4px var(--app-page-horizontal-gutter) 12px;
  box-sizing: border-box;
}

.service-channel__notice {
  display: flex;
  padding: 14px;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  border-radius: 14px;
  background: #ffffff;
}

.service-channel__notice-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.service-channel__notice-copy {
  color: #666b73;
  font-size: 13px;
  line-height: 19px;
}

.service-channel__notice-action {
  display: inline-flex;
  height: 44px;
  margin-top: 8px;
  padding: 0 24px;
  align-items: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
}
</style>
