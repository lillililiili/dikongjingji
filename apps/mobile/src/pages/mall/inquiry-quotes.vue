<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { buildMyDemandsUrl } from '@/features/demand/navigation/demand-routes'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { MALL_PREVIEW_ENABLED } from '@/features/mall/application/mall-preview'
import {
  buildMallInquiryQuotesUrl,
  buildMallInquiryUrl,
  buildQuoteCheckoutUrl,
  type MallInquiryTarget,
} from '@/features/mall/navigation/mall-routes'
import MallInquiryQuotesPage from '@/features/mall/presentation/inquiry/MallInquiryQuotesPage.vue'
import MallInquiryUnavailable from '@/features/mall/presentation/inquiry/MallInquiryUnavailable.vue'
import { GOODS_ORDER_PATH, buildGoodsOrderUrl } from '@/features/trade/navigation/trade-routes'

const inquiryId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function previousRoute(): string | null {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2]
  return previous ? `/${previous.route}` : null
}

// Opened from a link (or after sending the form), back is 需求与报价.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildMyDemandsUrl() })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(inquiryId.value ? buildMallInquiryQuotesUrl(inquiryId.value) : buildMyDemandsUrl()) })
}

// The confirm page opens on top: back from it is this page, read again.
function openCheckout(inquiryQuoteId: string, quoteInquiryId: string, cityCode: string): void {
  uni.navigateTo({ url: buildQuoteCheckoutUrl(inquiryQuoteId, quoteInquiryId, cityCode) })
}

function openOrder(orderId: string): void {
  if (previousRoute() === GOODS_ORDER_PATH) {
    uni.navigateBack()
    return
  }
  uni.navigateTo({ url: buildGoodsOrderUrl(orderId) })
}

function reinquire(target: MallInquiryTarget): void {
  uni.navigateTo({ url: buildMallInquiryUrl(target) })
}

onLoad((query) => {
  const value = typeof query?.inquiryId === 'string' ? query.inquiryId.trim() : ''
  inquiryId.value = value || null
})

// Every return reads the inquiry again: merchants may have quoted or revised meanwhile, an order
// may have been made or closed.
onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <MallInquiryQuotesPage
    v-if="MALL_PREVIEW_ENABLED && shown"
    :inquiry-id="inquiryId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @checkout="openCheckout"
    @view-order="openOrder"
    @reinquire="reinquire"
  />
  <MallInquiryUnavailable v-else-if="!MALL_PREVIEW_ENABLED" />
</template>
