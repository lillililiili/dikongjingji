<script setup lang="ts">
import { onHide, onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import {
  createPlatformFeatureGateway,
  type PlatformFeatureAvailability,
} from '@/features/platform/application/platform-feature-gateway'
import { PLATFORM_PREVIEW_ENABLED } from '@/features/platform/application/platform-preview'
import { DEVELOPMENT_PLATFORM_DISCOVER_CONTENT } from '@/features/platform/data/development/platform-discover.fixture'
import type { PlatformDiscoverContent } from '@/features/platform/domain/platform-discover-content'
import type { PlatformFeatureIntent } from '@/features/platform/domain/platform-feature-intent'
import PlatformDiscoverPage from '@/features/platform/presentation/discover/PlatformDiscoverPage.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import {
  createCurrentCityWeatherLoader,
  createPlatformWeatherApi,
  type PlatformWeatherPresentation,
} from '@/shared/weather/platform-weather'
import { createUniPlatformWeatherHttpClient } from '@/shared/weather/uni-platform-weather-http-client'

const currentCityStore = useCurrentCityStore()
const content = ref<PlatformDiscoverContent | null>(null)
const weather = ref<PlatformWeatherPresentation>({
  available: false,
  text: '',
  iconUrl: '/static/images/inspection/platform/weather-unknown.png',
  conditionCode: 'UNKNOWN',
  observedAt: null,
})
const weatherLoader = createCurrentCityWeatherLoader(
  createPlatformWeatherApi(createUniPlatformWeatherHttpClient({
    getAccessToken: () => null,
  })),
)
const cityName = computed(
  () => currentCityStore.currentCity?.name ?? '选择城市',
)
const featureAvailability: PlatformFeatureAvailability = PLATFORM_PREVIEW_ENABLED
  ? {
      SEARCH: 'NEXT_PHASE',
      OPEN_MESSAGE_CENTER: 'NEXT_PHASE',
      OPEN_ITEM: 'NEXT_PHASE',
      OPEN_LOCATION: 'NEXT_PHASE',
    }
  : {
      SEARCH: 'HIDDEN',
      OPEN_MESSAGE_CENTER: 'HIDDEN',
      OPEN_ITEM: 'HIDDEN',
      OPEN_LOCATION: 'HIDDEN',
    }
const featureGateway = createPlatformFeatureGateway(featureAvailability)

function restoreDefaultCity(): void {
  if (currentCityStore.currentCity) return
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  if (code && name) currentCityStore.setCurrentCity({ code, name })
}

function loadContent(): void {
  if (!PLATFORM_PREVIEW_ENABLED) return
  content.value = DEVELOPMENT_PLATFORM_DISCOVER_CONTENT
}

async function loadWeather(): Promise<void> {
  weather.value = await weatherLoader.load(currentCityStore.currentCity)
}

function handleIntent(intent: PlatformFeatureIntent): void {
  const resolution = featureGateway.resolve(intent)
  if (resolution.action === 'SHOW_NEXT_PHASE') {
    uni.showToast({ title: resolution.message, icon: 'none' })
  }
}

onShow(() => {
  if (PLATFORM_PREVIEW_ENABLED) {
    uni.hideTabBar({ animation: false })
  } else {
    uni.showTabBar({
      animation: false,
      fail: () => undefined,
    })
  }
  restoreDefaultCity()
  void loadContent()
  void loadWeather()
})
onHide(() => {
  uni.showTabBar({
    animation: false,
    fail: () => undefined,
  })
})
</script>

<template>
  <PlatformDiscoverPage
    v-if="content"
    :city-name="cityName"
    :weather="weather"
    :content="content"
    @intent="handleIntent"
  />
  <view v-else class="platform-page-unavailable">
    <text>发现频道尚未开通</text>
  </view>
</template>

<style scoped>
.platform-page-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
