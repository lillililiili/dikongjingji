<script setup lang="ts">
import { onLoad, onPullDownRefresh, onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import AftersaleListPage from '@/features/orders/presentation/AftersaleListPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  MINE_TAB_PATH,
  buildInspectionLoginUrl,
} from '@/features/inspection/navigation/inspection-routes'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import type { AftersaleTab } from '@/features/trade/domain/aftersale'
import { buildAftersalesUrl, parseAftersaleTab } from '@/features/trade/navigation/aftersale-routes'
import TradeUnavailable from '@/features/trade/presentation/shared/TradeUnavailable.vue'

// 退款/售后（S4-2，画板 AftersaleList）：我的页“退款/售后”和我的订单“全部售后单 ›”进来。
const initialTab = ref<AftersaleTab>('DOING')
const currentTab = ref<AftersaleTab>('DOING')
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
  uni.redirectTo({ url: buildInspectionLoginUrl(buildAftersalesUrl(currentTab.value)) })
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
  initialTab.value = parseAftersaleTab(query?.tab)
  currentTab.value = initialTab.value
})

// 每次回来都重读：售后单可能已被商家处理。
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
  <AftersaleListPage
    v-if="TRADE_PREVIEW_ENABLED && shown"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :reach-bottom-key="reachBottomKey"
    :initial-tab="initialTab"
    @back="goBack"
    @login="openLogin"
    @open="open"
    @tab-change="(tab: AftersaleTab) => (currentTab = tab)"
    @loaded="stopPulling"
  />
  <TradeUnavailable v-else-if="!TRADE_PREVIEW_ENABLED" />
</template>
