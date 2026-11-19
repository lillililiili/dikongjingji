<script setup lang="ts">
import { onPageScroll } from '@dcloudio/uni-app'
import { computed, nextTick, ref, watch } from 'vue'

import { buildDemandPublishUrl } from '@/features/demand/navigation/demand-routes'
import type { DemandType } from '@/features/demand/domain/demand'
import { InspectionPortalPage } from '@/features/inspection/presentation/public'
import { createPlatformFeatureGateway } from '@/features/platform/application/platform-feature-gateway'
import { PLATFORM_PREVIEW_ENABLED } from '@/features/platform/application/platform-preview'
import {
  DEFAULT_PLATFORM_CHANNEL,
  PLATFORM_SERVICE_CHANNELS,
  resolveVisiblePlatformChannels,
  type PlatformChannelCode,
} from '@/features/platform/domain/platform-channel'
import { resolvePlatformSwipeTarget } from '@/features/platform/domain/platform-channel-swipe'
import type { PlatformFeatureIntent } from '@/features/platform/domain/platform-feature-intent'
import {
  PLATFORM_SERVICE_CHANNEL_CONTENT,
  PLATFORM_SERVICE_PREVIEW_SIZE,
  type PlatformServiceChannelContent as PlatformServiceChannelViewContent,
} from '@/features/platform/domain/platform-service-content'
import PlatformServiceChannelContent, {
  type PlatformServiceListState,
} from '@/features/platform/presentation/service/PlatformServiceChannelContent.vue'
import { proServiceApi } from '@/features/proservice/data/proservice-api-client'
import {
  buildProServiceCatalogUrl,
  buildProServiceDetailUrl,
} from '@/features/proservice/navigation/proservice-routes'

interface PlatformTouchPoint {
  clientX: number
  clientY: number
}

const props = defineProps<{
  active: boolean
  cityCode: string
}>()

const emit = defineEmits<{
  searchPlaceholderChange: [placeholder: string | null]
}>()

const activeChannel = ref<PlatformChannelCode>(DEFAULT_PLATFORM_CHANNEL)
const channelList = ref<PlatformServiceListState>({ kind: 'loading' })
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
// The copy of the open type; null on inspection, which has its own portal.
const channelContent = computed<PlatformServiceChannelViewContent | null>(() =>
  activeChannel.value === 'INSPECTION' ? null : PLATFORM_SERVICE_CHANNEL_CONTENT[activeChannel.value])
// The four types (their lists, the catalog and publishing a demand) are open in preview builds;
// public builds keep them hidden.
const featureGateway = createPlatformFeatureGateway(PLATFORM_PREVIEW_ENABLED
  ? {
      SEARCH: 'ENABLED',
      PUBLISH_DEMAND: 'ENABLED',
    }
  : {
      SEARCH: 'HIDDEN',
      PUBLISH_DEMAND: 'HIDDEN',
    })

// The first few services of the open type in the App's city (查看全部服务 opens the catalog).
async function loadChannelContent(): Promise<boolean> {
  const requestedChannel = activeChannel.value
  const requestVersion = ++channelRequestVersion
  const content = channelContent.value
  if (!content) return true
  channelList.value = { kind: 'loading' }
  try {
    const page = await proServiceApi.listServices({
      type: content.serviceType,
      cityCode: props.cityCode,
      keyword: '',
      facet: null,
      priceMode: null,
      sort: 'DEFAULT',
    }, 1, PLATFORM_SERVICE_PREVIEW_SIZE)
    if (requestVersion !== channelRequestVersion || requestedChannel !== activeChannel.value) return false
    channelList.value = { kind: 'ready', page }
  } catch {
    if (requestVersion !== channelRequestVersion || requestedChannel !== activeChannel.value) return false
    channelList.value = { kind: 'error' }
  }
  return true
}

// Only inspection searches the home catalog, so other types take over the search box.
function reportSearchPlaceholder(): void {
  emit('searchPlaceholderChange', channelContent.value?.searchPlaceholder ?? null)
}

async function selectChannel(channel: PlatformChannelCode): Promise<void> {
  if (!visibleChannels.includes(channel)) return
  scrollPositions.set(activeChannel.value, currentScrollTop.value)
  activeChannel.value = channel
  reportSearchPlaceholder()
  const loaded = await loadChannelContent()
  if (!loaded) return
  await nextTick()
  if (channel !== activeChannel.value) return
  uni.pageScrollTo({
    scrollTop: scrollPositions.get(channel) ?? 0,
    duration: 0,
  })
}

const DEMAND_TYPE_OF_CHANNEL: Record<PlatformChannelCode, DemandType> = {
  INSPECTION: 'INSPECTION',
  AERIAL: 'AERIAL',
  SURVEY: 'SURVEY',
  MAINTENANCE: 'MAINTENANCE',
  SUPPORT: 'TECH_SUPPORT',
}

const inspectionRequestShown =
  featureGateway.resolve({ type: 'PUBLISH_DEMAND', channel: 'INSPECTION' }).action === 'EXECUTE'

function openCatalog(channel: PlatformChannelCode, keyword = ''): void {
  if (channel === 'INSPECTION') return
  uni.navigateTo({
    url: buildProServiceCatalogUrl({
      type: PLATFORM_SERVICE_CHANNEL_CONTENT[channel].serviceType,
      cityCode: props.cityCode,
      keyword,
    }),
  })
}

function openService(serviceId: string): void {
  uni.navigateTo({ url: buildProServiceDetailUrl(serviceId, props.cityCode) })
}

function handleIntent(intent: PlatformFeatureIntent): void {
  const resolution = featureGateway.resolve(intent)
  if (resolution.action === 'SHOW_NEXT_PHASE') {
    uni.showToast({ title: resolution.message, icon: 'none' })
    return
  }
  if (resolution.action !== 'EXECUTE') return
  if (intent.type === 'PUBLISH_DEMAND') {
    uni.navigateTo({ url: buildDemandPublishUrl({ type: DEMAND_TYPE_OF_CHANNEL[intent.channel] }) })
  }
  if (intent.type === 'SEARCH') openCatalog(intent.channel)
}

// The home search box while one of the four types is open: the type's catalog with the keyword.
// False on inspection, whose search the home page handles itself.
function searchServices(keyword: string): boolean {
  if (activeChannel.value === 'INSPECTION') return false
  const resolution = featureGateway.resolve({ type: 'SEARCH', channel: activeChannel.value })
  if (resolution.action !== 'EXECUTE') return false
  openCatalog(activeChannel.value, keyword)
  return true
}

defineExpose({ searchServices })

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
  channelList.value = { kind: 'loading' }
  reportSearchPlaceholder()
})

// A new city reads the open type's services again.
watch(() => props.cityCode, () => {
  if (activeChannel.value !== 'INSPECTION') void loadChannelContent()
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
      <InspectionPortalPage
        v-if="activeChannel === 'INSPECTION'"
        :show-request-action="inspectionRequestShown"
        @publish-demand="handleIntent({ type: 'PUBLISH_DEMAND', channel: 'INSPECTION' })"
      />
      <PlatformServiceChannelContent
        v-else-if="channelContent"
        :content="channelContent"
        :type-label="activeChannelLabel"
        :list="channelList"
        @intent="handleIntent"
        @open-service="openService"
        @retry="loadChannelContent"
      />
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
</style>
