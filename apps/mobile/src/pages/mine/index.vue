<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  createLatestRequestCoordinator,
  requestLatestInspectionOrders,
  resolveInspectionOrderDestination,
} from '@/features/inspection/application/service-flow/inspection-order-navigation'
import { inspectionOrderApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import type { InspectionOrderSummary } from '@/features/inspection/domain/service-flow/order-center'
import {
  buildInspectionLoginUrl,
  buildInspectionOrderCenterUrl,
} from '@/features/inspection/navigation/inspection-routes'
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
    // Known before the first request, so a signed-in user never sees the guest card flash by.
    user: resolveAuthenticatedMineUser(
      Boolean(getBookingAuthContext().getAccessToken()),
      currentCityStore.currentCity?.name,
    ),
    recentOrders: [],
    featureEntries,
  },
}))
const recentOrderById = ref(new Map<string, InspectionOrderSummary>())
const recentOrderRequest = createLatestRequestCoordinator()
function restoreDefaultCity(): void {
  if (currentCityStore.currentCity) return
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  if (code && name) currentCityStore.setCurrentCity({ code, name })
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

function showUnavailable(): void {
  uni.showToast({ title: '该能力将在下一阶段接入', icon: 'none' })
}

// Signing in lands on the order centre, the one page here that needs an account; its back leads to this page.
function openLogin(): void {
  uni.navigateTo({ url: buildInspectionLoginUrl(buildInspectionOrderCenterUrl('ALL')) })
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
  restoreDefaultCity()
  void loadRecentOrders()
})
</script>

<template>
  <view data-testid="mine-page">
    <PlatformMinePage
      :state="state"
      @retry="loadRecentOrders"
      @login="openLogin"
      @customer-service="showUnavailable"
      @settings="showUnavailable"
      @select-status="openStatus"
      @select-order="openRecentOrder"
      @select-feature="showUnavailable"
      @view-all-orders="openAllOrders"
    />
    <RootAppTabBar active="MINE" />
  </view>
</template>
