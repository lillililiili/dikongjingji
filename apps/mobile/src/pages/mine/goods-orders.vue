<script setup lang="ts">
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  MINE_TAB_PATH,
  buildInspectionLoginUrl,
} from '@/features/inspection/navigation/inspection-routes'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import type { TradeOrderTab } from '@/features/trade/domain/trade-order'
import {
  buildCashierUrl,
  buildGoodsOrderUrl,
  buildGoodsOrdersUrl,
  parseTradeOrderTab,
} from '@/features/trade/navigation/trade-routes'
import TradeOrderListPage from '@/features/trade/presentation/orders/TradeOrderListPage.vue'
import TradeUnavailable from '@/features/trade/presentation/shared/TradeUnavailable.vue'

const initialTab = ref<TradeOrderTab>('ALL')
const currentTab = ref<TradeOrderTab>('ALL')
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

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildGoodsOrdersUrl(currentTab.value)) })
}

function openOrder(orderId: string): void {
  uni.navigateTo({ url: buildGoodsOrderUrl(orderId) })
}

function pay(orderId: string): void {
  uni.navigateTo({ url: buildCashierUrl(orderId) })
}

function stopPulling(): void {
  if (!pulling) return
  pulling = false
  uni.stopPullDownRefresh()
}

onLoad((query) => {
  initialTab.value = parseTradeOrderTab(query?.tab)
  currentTab.value = initialTab.value
})

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
  <TradeOrderListPage
    v-if="TRADE_PREVIEW_ENABLED && shown"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :reach-bottom-key="reachBottomKey"
    :initial-tab="initialTab"
    @back="goBack"
    @login="openLogin"
    @open-order="openOrder"
    @pay="pay"
    @tab-change="currentTab = $event"
    @loaded="stopPulling"
  />
  <TradeUnavailable v-else-if="!TRADE_PREVIEW_ENABLED" />
</template>
