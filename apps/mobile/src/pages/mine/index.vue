<script setup lang="ts">
import { onHide, onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { ROOT_TABS, type RootTabCode } from '@/app-shell/navigation/root-tabs'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  createLatestRequestCoordinator,
  requestLatestInspectionOrders,
  resolveInspectionOrderDestination,
} from '@/features/inspection/application/service-flow/inspection-order-navigation'
import { inspectionOrderApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import type { InspectionOrderSummary } from '@/features/inspection/domain/service-flow/order-center'
import { buildInspectionOrderCenterUrl } from '@/features/inspection/navigation/inspection-routes'
import PlatformMinePage from '@/features/inspection/presentation/platform/mine/PlatformMinePage.vue'
import {
  resolveAuthenticatedMineUser,
  resolvePlatformMineState,
  type InspectionMineStatus,
  type PlatformMineFeatureEntry,
  type PlatformMineOrder,
  type PlatformMineViewState,
} from '@/features/inspection/presentation/platform/mine/platform-mine'
import { toPlatformMineOrder } from '@/features/inspection/presentation/orders/order-center-view'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import {
  createCurrentCityWeatherLoader,
  createPlatformWeatherApi,
  type PlatformWeatherPresentation,
} from '@/shared/weather/platform-weather'
import { createUniPlatformWeatherHttpClient } from '@/shared/weather/uni-platform-weather-http-client'

const currentCityStore = useCurrentCityStore()
const featureEntries: PlatformMineFeatureEntry[] = [
  {
    code: 'PROJECTS',
    title: '我的项目',
    imageUrl: '/static/images/inspection/platform/mine-feature-projects-v2.png',
    availability: 'UNAVAILABLE',
  },
  {
    code: 'EQUIPMENT',
    title: '我的设备',
    imageUrl: '/static/images/inspection/platform/mine-feature-equipment-v2.png',
    availability: 'UNAVAILABLE',
  },
  {
    code: 'QUALIFICATIONS',
    title: '我的资质',
    imageUrl: '/static/images/inspection/platform/mine-feature-qualifications-v2.png',
    availability: 'UNAVAILABLE',
  },
  {
    code: 'SUPPORT',
    title: '服务支持',
    imageUrl: '/static/images/inspection/platform/mine-feature-support-v2.png',
    availability: 'UNAVAILABLE',
  },
]
const state = ref<PlatformMineViewState>(resolvePlatformMineState({
  status: 'ready',
  content: {
    recentOrders: [],
    featureEntries,
  },
}))
const recentOrderById = ref(new Map<string, InspectionOrderSummary>())
const recentOrderRequest = createLatestRequestCoordinator()
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

function restoreDefaultCity(): void {
  if (currentCityStore.currentCity) return
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  if (code && name) currentCityStore.setCurrentCity({ code, name })
}

async function loadWeather(): Promise<void> {
  weather.value = await weatherLoader.load(currentCityStore.currentCity)
}

function emptyStageCounts(): Record<InspectionMineStatus, number> {
  return {
    AWAITING_PLAN: 0,
    AWAITING_CONFIRMATION: 0,
    IN_PROGRESS: 0,
    AWAITING_ACCEPTANCE: 0,
    COMPLETED: 0,
  }
}

function setMineContent(
  recentOrders: PlatformMineOrder[],
  statusCounts: Record<InspectionMineStatus, number>,
): void {
  state.value = resolvePlatformMineState({
    status: 'ready',
    content: {
      user: resolveAuthenticatedMineUser(
        Boolean(getBookingAuthContext().getAccessToken()),
        currentCityStore.currentCity?.name,
      ),
      statusCounts,
      recentOrders,
      featureEntries,
    },
  })
}

async function loadRecentOrders(): Promise<void> {
  const result = await requestLatestInspectionOrders(
    recentOrderRequest,
    () => getBookingAuthContext().getAccessToken(),
    (input) => inspectionOrderApi.list(input),
  )
  if (result.status === 'unauthenticated') {
    setMineContent([], emptyStageCounts())
    return
  }
  if (result.status === 'success') {
    const response = result.value
    recentOrderById.value = new Map(
      response.items.map((order) => [order.reservationId, order]),
    )
    setMineContent(
      response.items.map(toPlatformMineOrder),
      response.stageCounts,
    )
    return
  }
  if (result.status === 'error') {
    uni.showToast({
      title: result.error instanceof Error ? result.error.message : '订单加载失败',
      icon: 'none',
    })
  }
}

function selectTab(code: RootTabCode): void {
  const tab = ROOT_TABS.find((candidate) => candidate.code === code)
  if (tab && code !== 'MINE') uni.switchTab({ url: tab.path })
}

function showUnavailable(): void {
  uni.showToast({ title: '该能力将在下一阶段接入', icon: 'none' })
}

function openAllOrders(): void {
  uni.navigateTo({ url: buildInspectionOrderCenterUrl('ALL') })
}

function openStatus(status: InspectionMineStatus): void {
  uni.navigateTo({ url: buildInspectionOrderCenterUrl(status) })
}

function openRecentOrder(order: PlatformMineOrder): void {
  const source = recentOrderById.value.get(order.id)
  const url = source ? resolveInspectionOrderDestination(source) : null
  if (url) uni.navigateTo({ url })
  else void loadRecentOrders()
}

onShow(() => {
  uni.hideTabBar({ animation: false })
  restoreDefaultCity()
  void loadWeather()
  void loadRecentOrders()
})
onHide(() => {
  uni.showTabBar({
    animation: false,
    fail: () => undefined,
  })
})
</script>

<template>
  <PlatformMinePage
    :state="state"
    :city-name="cityName"
    :weather="weather"
    search-placeholder="搜索订单或服务"
    show-message
    active-tab="MINE"
    @location="showUnavailable"
    @search="showUnavailable"
    @message="showUnavailable"
    @retry="loadRecentOrders"
    @customer-service="showUnavailable"
    @settings="showUnavailable"
    @select-status="openStatus"
    @select-order="openRecentOrder"
    @select-feature="showUnavailable"
    @view-all-orders="openAllOrders"
    @select-tab="selectTab"
  />
</template>
