<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import {
  ACTIVITY_PARTICIPANTS_PATH,
  buildActivityEnrollmentsUrl,
} from '@/features/activity/navigation/activity-routes'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  createLatestRequestCoordinator,
  requestLatestInspectionOrders,
  resolveInspectionOrderDestination,
} from '@/features/inspection/application/service-flow/inspection-order-navigation'
import {
  inspectionAccountApi,
  inspectionOrderApi,
} from '@/features/inspection/data/service-flow/uni-booking-http-client'
import type { InspectionOrderSummary } from '@/features/inspection/domain/service-flow/order-center'
import {
  MINE_TAB_PATH,
  buildInspectionLoginUrl,
  buildInspectionOrderCenterUrl,
} from '@/features/inspection/navigation/inspection-routes'
import PlatformMinePage from '@/features/inspection/presentation/platform/mine/PlatformMinePage.vue'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import { buildGoodsOrdersUrl } from '@/features/trade/navigation/trade-routes'
import {
  resolveAuthenticatedMineUser,
  resolvePlatformMineState,
  type InspectionMineStatus,
  type PlatformMineAccount,
  type PlatformMineFeatureEntry,
  type PlatformMineLinkEntry,
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
// Activity sign-up lives behind the same switch as the 赛事研学 channel; both pages ask for a
// login themselves and come back here if the user gives up.
const joinEntries: PlatformMineLinkEntry[] = ACTIVITY_PREVIEW_ENABLED
  ? [{ code: 'ACTIVITY_ENROLLMENTS', title: '活动报名', icon: 'flag' }]
  : []
const linkRows: PlatformMineLinkEntry[] = ACTIVITY_PREVIEW_ENABLED
  ? [{ code: 'ACTIVITY_PARTICIPANTS', title: '参与人管理', icon: 'staff' }]
  : []
// The signed-in account's own profile; null until read, and again after signing out.
const account = ref<PlatformMineAccount | null>(null)
let accountToken: string | null = null

function resolveMineUser() {
  const token = getBookingAuthContext().getAccessToken()
  return resolveAuthenticatedMineUser(
    Boolean(token),
    currentCityStore.currentCity?.name,
    token && token === accountToken ? account.value : null,
  )
}

const state = ref<PlatformMineViewState>(resolvePlatformMineState({
  status: 'ready',
  content: {
    // Known before the first request, so a signed-in user never sees the guest card flash by.
    user: resolveMineUser(),
    recentOrders: [],
    featureEntries,
    joinEntries,
    linkRows,
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
      user: resolveMineUser(),
      statusCounts,
      recentOrders,
      featureEntries,
      joinEntries,
      linkRows,
    },
  })
}

// Read once per access token. A failure leaves the neutral "已登录" card: the profile is not
// worth an error message, and the order request below reports a rejected login.
async function loadAccount(): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token) {
    account.value = null
    accountToken = null
    return
  }
  if (token === accountToken) return
  try {
    const loaded = await inspectionAccountApi.loadCurrent(token)
    if (getBookingAuthContext().getAccessToken() !== token) return
    account.value = loaded
    accountToken = token
    if (state.value.kind === 'ready') {
      state.value = resolvePlatformMineState({
        status: 'ready',
        content: { ...state.value.content, user: resolveMineUser() },
      })
    }
  } catch {
    // Keep the neutral card.
  }
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

// After signing in the user comes back to this page, now with their own profile and orders.
function openLogin(): void {
  uni.navigateTo({ url: buildInspectionLoginUrl(MINE_TAB_PATH) })
}

function openLink(code: string): void {
  if (code === 'ACTIVITY_ENROLLMENTS') uni.navigateTo({ url: buildActivityEnrollmentsUrl() })
  if (code === 'ACTIVITY_PARTICIPANTS') uni.navigateTo({ url: ACTIVITY_PARTICIPANTS_PATH })
}

function openAllOrders(): void {
  uni.navigateTo({ url: buildInspectionOrderCenterUrl('ALL') })
}

// Goods orders ask for a login themselves and come back to their own list.
function openGoodsOrders(): void {
  uni.navigateTo({ url: buildGoodsOrdersUrl() })
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
  void loadAccount()
  void loadRecentOrders()
})
</script>

<template>
  <view data-testid="mine-page">
    <PlatformMinePage
      :state="state"
      :goods-orders-entry="TRADE_PREVIEW_ENABLED"
      @retry="loadRecentOrders"
      @login="openLogin"
      @customer-service="showUnavailable"
      @settings="showUnavailable"
      @select-status="openStatus"
      @select-order="openRecentOrder"
      @select-feature="showUnavailable"
      @select-link="openLink"
      @view-all-orders="openAllOrders"
      @view-goods-orders="openGoodsOrders"
    />
    <RootAppTabBar active="MINE" />
  </view>
</template>
