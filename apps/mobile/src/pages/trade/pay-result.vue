<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { HOME_ROOT_PATH } from '@/app-shell/navigation/root-tabs'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import { leaveToGoodsOrders, showGoodsOrder } from '@/features/trade/navigation/trade-navigation'
import { buildCashierUrl, buildPayResultUrl } from '@/features/trade/navigation/trade-routes'
import TradePayResultPage from '@/features/trade/presentation/pay-result/TradePayResultPage.vue'
import TradeUnavailable from '@/features/trade/presentation/shared/TradeUnavailable.vue'

const orderId = ref('')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function decode(value: unknown): string {
  if (typeof value !== 'string') return ''
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildPayResultUrl(orderId.value)) })
}

function goHome(): void {
  uni.switchTab({ url: HOME_ROOT_PATH })
}

function repay(): void {
  uni.redirectTo({ url: buildCashierUrl(orderId.value) })
}

onLoad((query) => {
  orderId.value = decode(query?.orderId)
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <TradePayResultPage
    v-if="TRADE_PREVIEW_ENABLED && shown"
    :order-id="orderId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="leaveToGoodsOrders"
    @login="openLogin"
    @home="goHome"
    @view-order="showGoodsOrder(orderId)"
    @repay="repay"
  />
  <TradeUnavailable v-else-if="!TRADE_PREVIEW_ENABLED" />
</template>
