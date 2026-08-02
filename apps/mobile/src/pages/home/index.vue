<script setup lang="ts">
import { onHide, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ROOT_TABS, type RootTabCode } from '@/app-shell/navigation/root-tabs'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { createPlatformHomePreviewContent } from '@/features/inspection/data/preview/platform-home-preview'
import { inspectionCatalogApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import PlatformHomePage from '@/features/inspection/presentation/platform/home/PlatformHomePage.vue'
import {
  createPlatformHomeContentFromPortal,
  resolvePlatformHomeState,
  type PlatformHomeFeaturedService,
  type PlatformHomeServiceEntry,
  type PlatformHomeViewState,
} from '@/features/inspection/presentation/platform/home/platform-home'
import {
  resolveLocatedCityName,
  useCurrentCityStore,
  type CurrentCity,
} from '@/shared/city/state/current-city'
import {
  createCurrentCityWeatherLoader,
  createPlatformWeatherApi,
  toPlatformWeatherPresentation,
  type PlatformWeatherPresentation,
} from '@/shared/weather/platform-weather'
import { createUniPlatformWeatherHttpClient } from '@/shared/weather/uni-platform-weather-http-client'

const currentCityStore = useCurrentCityStore()
const state = ref<PlatformHomeViewState>({ kind: 'loading' })
const homePreviewEnabled =
  import.meta.env.DEV ||
  import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
const weatherApi = createPlatformWeatherApi(createUniPlatformWeatherHttpClient({
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
}))
const weatherLoader = createCurrentCityWeatherLoader(weatherApi)
const DEFAULT_CITY: CurrentCity = { code: '370500', name: '东营市' }

function restoreDefaultCity(): CurrentCity {
  if (currentCityStore.currentCity) return currentCityStore.currentCity
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  const city = code && name ? { code, name } : DEFAULT_CITY
  currentCityStore.setCurrentCity(city)
  return city
}

function locateCurrentCityName(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.getLocation({
      type: 'gcj02',
      geocode: true,
      isHighAccuracy: true,
      highAccuracyExpireTime: 5000,
      success: (result) => {
        try {
          resolve(resolveLocatedCityName(result.address))
        } catch (error) {
          reject(error)
        }
      },
      fail: reject,
    })
  })
}

async function resolveCurrentCityContext(): Promise<{
  city: CurrentCity
  weather: PlatformWeatherPresentation
}> {
  let locatedCityName: string
  try {
    locatedCityName = await locateCurrentCityName()
  } catch {
    const city = restoreDefaultCity()
    return {
      city,
      weather: await weatherLoader.load(city),
    }
  }

  try {
    const weatherDto = await weatherApi.loadCurrentByCityName(locatedCityName)
    const city = { code: weatherDto.cityCode, name: weatherDto.cityName }
    currentCityStore.setCurrentCity(city)
    return {
      city,
      weather: toPlatformWeatherPresentation(weatherDto),
    }
  } catch {
    const city = restoreDefaultCity()
    return {
      city,
      weather: await weatherLoader.load(null),
    }
  }
}

async function loadPage(): Promise<void> {
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const { city, weather } = await resolveCurrentCityContext()
    const portal = await inspectionCatalogApi.loadPortal(city.code)
    let content = createPlatformHomeContentFromPortal(city.name, weather, portal)
    if (homePreviewEnabled) {
      content = createPlatformHomePreviewContent(city.name, weather, portal)
    }
    state.value = resolvePlatformHomeState({
      status: 'ready',
      content,
    })
  } catch {
    state.value = { kind: 'error', message: '首页内容加载失败，请稍后重试' }
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
    @location="loadPage"
    @search="openCatalog()"
    @message="showUnavailable"
    @select-service-entry="selectEntry"
    @select-featured-service="selectFeatured"
    @select-activity="showUnavailable"
    @select-enterprise-solution="showUnavailable"
    @select-tab="selectTab"
  />
</template>
