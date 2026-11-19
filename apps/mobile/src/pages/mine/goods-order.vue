<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import { goBackOr } from '@/features/trade/navigation/trade-navigation'
import { buildCashierUrl, buildGoodsOrderUrl, buildGoodsOrdersUrl } from '@/features/trade/navigation/trade-routes'
import TradeOrderDetailPage from '@/features/trade/presentation/order/TradeOrderDetailPage.vue'
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

function goBack(): void {
  goBackOr(() => uni.redirectTo({ url: buildGoodsOrdersUrl() }))
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildGoodsOrderUrl(orderId.value)) })
}

function pay(): void {
  uni.navigateTo({ url: buildCashierUrl(orderId.value) })
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
  <TradeOrderDetailPage
    v-if="TRADE_PREVIEW_ENABLED && shown"
    :order-id="orderId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @pay="pay"
  />
  <TradeUnavailable v-else-if="!TRADE_PREVIEW_ENABLED" />
</template>
