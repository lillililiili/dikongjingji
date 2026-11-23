<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import {
  buildAftersaleApplyUrl,
  buildAftersaleDetailUrl,
  buildAftersalesUrl,
} from '@/features/trade/navigation/aftersale-routes'
import { goBackOr } from '@/features/trade/navigation/trade-navigation'
import { GOODS_ORDER_PATH, buildGoodsOrderUrl } from '@/features/trade/navigation/trade-routes'
import AftersaleDetailPage from '@/features/trade/presentation/aftersale/AftersaleDetailPage.vue'
import TradeUnavailable from '@/features/trade/presentation/shared/TradeUnavailable.vue'

// 售后详情（S4-2，画板 AftersaleDetail / AftersaleFlow）。只收售后单号；每次回到这页都从服务端重读。
const aftersaleId = ref('')
const openWaybill = ref(false)
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
  goBackOr(() => uni.redirectTo({ url: buildAftersalesUrl() }))
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildAftersaleDetailUrl(aftersaleId.value)) })
}

// 从订单页进来时“查看订单”回到下面那一页，不再叠一层。
function viewOrder(orderId: string): void {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2] as { route?: string; options?: Record<string, string> } | undefined
  if (previous && `/${previous.route}` === GOODS_ORDER_PATH && decode(previous.options?.orderId) === orderId) {
    uni.navigateBack()
    return
  }
  uni.navigateTo({ url: buildGoodsOrderUrl(orderId) })
}

function reapply(orderId: string): void {
  uni.redirectTo({ url: buildAftersaleApplyUrl(orderId) })
}

onLoad((query) => {
  aftersaleId.value = decode(query?.aftersaleId)
  openWaybill.value = query?.waybill === '1'
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <AftersaleDetailPage
    v-if="TRADE_PREVIEW_ENABLED && shown"
    :aftersale-id="aftersaleId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :open-waybill="openWaybill"
    @back="goBack"
    @login="openLogin"
    @view-order="viewOrder"
    @reapply="reapply"
  />
  <TradeUnavailable v-else-if="!TRADE_PREVIEW_ENABLED" />
</template>
