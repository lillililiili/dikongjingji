<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import { buildAftersaleApplyUrl, buildAftersaleDetailUrl } from '@/features/trade/navigation/aftersale-routes'
import { goBackOr } from '@/features/trade/navigation/trade-navigation'
import { buildGoodsOrderUrl } from '@/features/trade/navigation/trade-routes'
import AftersaleApplyPage from '@/features/trade/presentation/aftersale/AftersaleApplyPage.vue'
import TradeUnavailable from '@/features/trade/presentation/shared/TradeUnavailable.vue'

// 申请售后（S4-2，画板 AftersaleApply）。只收订单号；提交成功或已有没结束的售后单时换成售后详情。
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
  goBackOr(() => uni.redirectTo({ url: buildGoodsOrderUrl(orderId.value) }))
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildAftersaleApplyUrl(orderId.value)) })
}

function opened(aftersaleId: string): void {
  uni.redirectTo({ url: buildAftersaleDetailUrl(aftersaleId) })
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
  <AftersaleApplyPage
    v-if="TRADE_PREVIEW_ENABLED && shown"
    :order-id="orderId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @opened="opened"
  />
  <TradeUnavailable v-else-if="!TRADE_PREVIEW_ENABLED" />
</template>
