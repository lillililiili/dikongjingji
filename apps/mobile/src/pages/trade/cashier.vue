<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { leaveTradePage, showOrderDestination } from '@/app-shell/navigation/trade-destination'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { describeInspectionTradeOrder } from '@/features/inspection/application/service-flow/inspection-trade-source'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildMallProductUrl } from '@/features/mall/navigation/mall-routes'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import { buildCashierUrl, buildPayResultUrl } from '@/features/trade/navigation/trade-routes'
import TradeCashierPage from '@/features/trade/presentation/cashier/TradeCashierPage.vue'
import TradeUnavailable from '@/features/trade/presentation/shared/TradeUnavailable.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

const currentCityStore = useCurrentCityStore()
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
  uni.redirectTo({ url: buildInspectionLoginUrl(buildCashierUrl(orderId.value)) })
}

// Whatever the WeChat sheet said, the result page asks the server; back from it is the order list.
function openResult(): void {
  uni.redirectTo({ url: buildPayResultUrl(orderId.value) })
}

function rebuy(productId: string | null): void {
  const cityCode = currentCityStore.currentCity?.code
  if (productId && cityCode) {
    uni.redirectTo({ url: buildMallProductUrl(productId, cityCode) })
    return
  }
  openHomeChannel('MALL')
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
  <TradeCashierPage
    v-if="TRADE_PREVIEW_ENABLED && shown"
    :order-id="orderId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :describe-source="describeInspectionTradeOrder"
    @back="(destination) => leaveTradePage(destination, true)"
    @login="openLogin"
    @result="openResult"
    @view-order="showOrderDestination"
    @rebuy="rebuy"
  />
  <TradeUnavailable v-else-if="!TRADE_PREVIEW_ENABLED" />
</template>
