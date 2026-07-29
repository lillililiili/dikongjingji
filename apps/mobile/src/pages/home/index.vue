<script setup lang="ts">
import { onHide, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ROOT_TABS, type RootTabCode } from '@/app-shell/navigation/root-tabs'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { inspectionCatalogApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import PlatformHomePage from '@/features/inspection/presentation/platform/home/PlatformHomePage.vue'
import {
  createPlatformHomeContentFromPortal,
  resolvePlatformHomeState,
  type PlatformHomeFeaturedService,
  type PlatformHomeServiceEntry,
  type PlatformHomeViewState,
} from '@/features/inspection/presentation/platform/home/platform-home'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import {
  createCurrentCityWeatherLoader,
  createPlatformWeatherApi,
} from '@/shared/weather/platform-weather'
import { createUniPlatformWeatherHttpClient } from '@/shared/weather/uni-platform-weather-http-client'

const currentCityStore = useCurrentCityStore()
const state = ref<PlatformHomeViewState>({ kind: 'loading' })
const weatherLoader = createCurrentCityWeatherLoader(
  createPlatformWeatherApi(createUniPlatformWeatherHttpClient({
    getAccessToken: () => getBookingAuthContext().getAccessToken(),
  })),
)

function restoreDefaultCity(): void {
  if (currentCityStore.currentCity) return
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  if (code && name) currentCityStore.setCurrentCity({ code, name })
}

async function loadPage(): Promise<void> {
  restoreDefaultCity()
  const city = currentCityStore.currentCity
  if (!city) {
    state.value = { kind: 'error', message: '请先选择当前城市' }
    return
  }

  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const [portal, weather] = await Promise.all([
      inspectionCatalogApi.loadPortal(city.code),
      weatherLoader.load(city),
    ])
    state.value = resolvePlatformHomeState({
      status: 'ready',
      content: createPlatformHomeContentFromPortal(city.name, weather, portal),
    })
  } catch {
    state.value = { kind: 'error', message: '首页加载失败，请稍后重试' }
  }
}

function selectTab(code: RootTabCode): void {
  const tab = ROOT_TABS.find((candidate) => candidate.code === code)
  if (tab && code !== 'HOME') uni.switchTab({ url: tab.path })
}

function openCatalog(categoryCode?: string): void {
  const cityCode = currentCityStore.currentCity?.code
  const query = [
    cityCode ? `cityCode=${encodeURIComponent(cityCode)}` : '',
    categoryCode ? `categoryCode=${encodeURIComponent(categoryCode)}` : '',
  ].filter(Boolean).join('&')
  uni.navigateTo({
    url: `/pages/service/inspection/catalog${query ? `?${query}` : ''}`,
  })
}

function selectEntry(entry: PlatformHomeServiceEntry): void {
  openCatalog(entry.code)
}

function selectFeatured(service: PlatformHomeFeaturedService): void {
  uni.navigateTo({
    url: `/pages/service/inspection/detail?serviceId=${encodeURIComponent(service.id)}`,
  })
}

function showUnavailable(): void {
  uni.showToast({ title: '该能力将在下一阶段接入', icon: 'none' })
}

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
  <PlatformHomePage
    :state="state"
    active-tab="HOME"
    @retry="loadPage"
    @search="openCatalog()"
    @message="showUnavailable"
    @select-service-entry="selectEntry"
    @select-featured-service="selectFeatured"
    @select-activity="showUnavailable"
    @select-enterprise-solution="showUnavailable"
    @select-tab="selectTab"
  />
</template>
