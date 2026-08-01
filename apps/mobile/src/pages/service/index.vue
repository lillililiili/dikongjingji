<script setup lang="ts">
import { onHide, onPageScroll, onShow } from '@dcloudio/uni-app'
import { computed, nextTick, ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildPortalSearchUrl } from '@/features/inspection/application/portal/build-portal-navigation'
import { InspectionPortalPage } from '@/features/inspection/presentation/public'
import { loadPlatformServiceContent } from '@/features/platform/application/load-platform-service-content'
import {
  createPlatformFeatureGateway,
  type PlatformFeatureAvailability,
} from '@/features/platform/application/platform-feature-gateway'
import { PLATFORM_PREVIEW_ENABLED } from '@/features/platform/application/platform-preview'
import { developmentPlatformContentRepository } from '@/features/platform/data/development/development-platform-content-repository'
import {
  DEFAULT_PLATFORM_CHANNEL,
  type PlatformChannelCode,
} from '@/features/platform/domain/platform-channel'
import type { PlatformContentRepository } from '@/features/platform/domain/platform-content-repository'
import type { PlatformFeatureIntent } from '@/features/platform/domain/platform-feature-intent'
import type { PlatformServiceChannelContent as PlatformServiceChannelViewContent } from '@/features/platform/domain/platform-service-content'
import PlatformServiceChannelContent from '@/features/platform/presentation/service/PlatformServiceChannelContent.vue'
import PlatformServicePage from '@/features/platform/presentation/service/PlatformServicePage.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import {
  createCurrentCityWeatherLoader,
  createPlatformWeatherApi,
  type PlatformWeatherPresentation,
} from '@/shared/weather/platform-weather'
import { createUniPlatformWeatherHttpClient } from '@/shared/weather/uni-platform-weather-http-client'

const currentCityStore = useCurrentCityStore()
const activeChannel = ref<PlatformChannelCode>(DEFAULT_PLATFORM_CHANNEL)
const channelContent = ref<PlatformServiceChannelViewContent | null>(null)
const currentScrollTop = ref(0)
const scrollPositions = new Map<PlatformChannelCode, number>()
let channelRequestVersion = 0
const weather = ref<PlatformWeatherPresentation>({
  available: false,
  text: '',
  iconUrl: '/static/images/inspection/platform/weather-unknown.png',
  conditionCode: 'UNKNOWN',
  observedAt: null,
})
const weatherLoader = createCurrentCityWeatherLoader(
  createPlatformWeatherApi(createUniPlatformWeatherHttpClient({
    getAccessToken: () => getBookingAuthContext().getAccessToken(),
  })),
)

const visibleChannels = computed<readonly PlatformChannelCode[]>(() =>
  PLATFORM_PREVIEW_ENABLED
    ? ['AERIAL', 'INSPECTION', 'FLIGHT', 'ROBOT', 'MORE']
    : ['INSPECTION'],
)
const cityName = computed(
  () => currentCityStore.currentCity?.name ?? '选择城市',
)
const searchPlaceholder = computed(() => {
  if (activeChannel.value === 'INSPECTION') {
    return '搜索巡检服务、场景或关键词'
  }
  return channelContent.value?.searchPlaceholder
    ?? '搜索无人机服务、景点、活动等'
})
const featureAvailability: PlatformFeatureAvailability = PLATFORM_PREVIEW_ENABLED
  ? {
      SEARCH: 'NEXT_PHASE',
      OPEN_MESSAGE_CENTER: 'NEXT_PHASE',
      OPEN_ITEM: 'NEXT_PHASE',
      OPEN_MORE_SERVICES: 'NEXT_PHASE',
      OPEN_LOCATION: 'NEXT_PHASE',
    }
  : {
      SEARCH: 'HIDDEN',
      OPEN_MESSAGE_CENTER: 'HIDDEN',
      OPEN_ITEM: 'HIDDEN',
      OPEN_MORE_SERVICES: 'HIDDEN',
      OPEN_LOCATION: 'HIDDEN',
    }
const featureGateway = createPlatformFeatureGateway(featureAvailability)

function restoreDefaultCity(): void {
  if (currentCityStore.currentCity) return
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  if (code && name) currentCityStore.setCurrentCity({ code, name })
}

function createContentRepository(): PlatformContentRepository | null {
  return PLATFORM_PREVIEW_ENABLED ? developmentPlatformContentRepository : null
}

async function loadChannelContent(): Promise<boolean> {
  const requestedChannel = activeChannel.value
  const requestVersion = ++channelRequestVersion
  if (requestedChannel === 'INSPECTION' || requestedChannel === 'MORE') {
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

async function loadPage(): Promise<void> {
  restoreDefaultCity()
  if (!PLATFORM_PREVIEW_ENABLED && activeChannel.value !== 'INSPECTION') {
    activeChannel.value = 'INSPECTION'
  }
  const [loadedWeather] = await Promise.all([
    weatherLoader.load(currentCityStore.currentCity),
    loadChannelContent(),
  ])
  weather.value = loadedWeather
}

async function selectChannel(channel: PlatformChannelCode): Promise<void> {
  if (channel === 'MORE') {
    handleIntent({ type: 'OPEN_MORE_SERVICES' })
    return
  }
  scrollPositions.set(activeChannel.value, currentScrollTop.value)
  activeChannel.value = channel
  const loaded = await loadChannelContent()
  if (!loaded) return
  await nextTick()
  if (channel !== activeChannel.value) return
  uni.pageScrollTo({
    scrollTop: scrollPositions.get(channel) ?? 0,
    duration: 0,
  })
}

function openInspectionSearch(): void {
  uni.navigateTo({
    url: buildPortalSearchUrl(currentCityStore.currentCity?.code, ''),
  })
}

function handleSearch(): void {
  if (activeChannel.value === 'INSPECTION') {
    openInspectionSearch()
    return
  }
  handleIntent({ type: 'SEARCH', channel: activeChannel.value })
}

function handleLocation(): void {
  if (!PLATFORM_PREVIEW_ENABLED) {
    uni.showToast({ title: '城市切换功能暂未开放', icon: 'none' })
    return
  }
  handleIntent({ type: 'OPEN_LOCATION' })
}

function handleIntent(intent: PlatformFeatureIntent): void {
  const resolution = featureGateway.resolve(intent)
  if (resolution.action === 'SHOW_NEXT_PHASE') {
    uni.showToast({ title: resolution.message, icon: 'none' })
  }
}

onPageScroll(({ scrollTop }) => {
  currentScrollTop.value = scrollTop
})

onShow(() => {
  uni.hideTabBar({ animation: false })
  void loadPage()
})
onHide(() => {
  uni.showTabBar({
    animation: false,
    fail: () => undefined,
  })
})
</script>

<template>
  <PlatformServicePage
    :city-name="cityName"
    :weather="weather"
    :active-channel="activeChannel"
    :visible-channels="visibleChannels"
    :search-placeholder="searchPlaceholder"
    :show-message="PLATFORM_PREVIEW_ENABLED"
    @search="handleSearch"
    @message="handleIntent({ type: 'OPEN_MESSAGE_CENTER' })"
    @location="handleLocation"
    @select-channel="selectChannel"
  >
    <InspectionPortalPage
      v-if="activeChannel === 'INSPECTION'"
      embedded
    />
    <PlatformServiceChannelContent
      v-else-if="channelContent"
      :content="channelContent"
      @intent="handleIntent"
    />
    <view v-else class="platform-service-unavailable">
      <text>当前频道尚未开通</text>
    </view>
  </PlatformServicePage>
</template>

<style scoped>
.platform-service-unavailable {
  display: flex;
  min-height: 360px;
  align-items: center;
  justify-content: center;
  border-radius: 16px;
  color: #8a929b;
  background: #ffffff;
  font-size: 14px;
}
</style>
