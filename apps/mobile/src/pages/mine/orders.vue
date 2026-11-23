<script setup lang="ts">
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  MINE_TAB_PATH,
  buildInspectionLoginUrl,
} from '@/features/inspection/navigation/inspection-routes'
import {
  buildMyOrdersUrl,
  parseMyOrderChip,
  parseMyOrderTab,
  type MyOrderChip,
  type MyOrderTab,
} from '@/features/orders/application/my-orders-route'
import MyOrdersPage from '@/features/orders/presentation/MyOrdersPage.vue'
import MyOrdersUnavailable from '@/features/orders/presentation/MyOrdersUnavailable.vue'
import { MY_ORDER_SEARCH_PATH } from '@/features/orders/application/my-order-search-route'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'

const initialTab = ref<MyOrderTab>('all')
const initialChip = ref<MyOrderChip | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const reachBottomKey = ref(0)
const shown = ref(false)
let pulling = false

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: MINE_TAB_PATH })
}

// Signing in comes back to this list with the same tab and status.
function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildMyOrdersUrl({ tab: initialTab.value, chip: initialChip.value })) })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

function stopPulling(): void {
  if (!pulling) return
  pulling = false
  uni.stopPullDownRefresh()
}

onLoad((query) => {
  initialTab.value = parseMyOrderTab(query?.tab)
  initialChip.value = parseMyOrderChip(query?.status)
})

// Every return reads the lists again: an order may have been paid, shipped, accepted or refunded.
onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})

onPullDownRefresh(() => {
  if (!TRADE_PREVIEW_ENABLED) {
    uni.stopPullDownRefresh()
    return
  }
  pulling = true
  refreshKey.value += 1
})

onReachBottom(() => {
  reachBottomKey.value += 1
})
</script>

<template>
  <MyOrdersPage
    v-if="TRADE_PREVIEW_ENABLED && shown"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :reach-bottom-key="reachBottomKey"
    :initial-tab="initialTab"
    :initial-chip="initialChip"
    @back="goBack"
    @login="openLogin"
    @open="open"
    @loaded="stopPulling"
    @search="open(MY_ORDER_SEARCH_PATH)"
  />
  <MyOrdersUnavailable v-else-if="!TRADE_PREVIEW_ENABLED" />
</template>
