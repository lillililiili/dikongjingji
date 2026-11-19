<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { buildMyDemandsUrl } from '@/features/demand/navigation/demand-routes'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { RENTAL_PREVIEW_ENABLED } from '@/features/rental/application/rental-preview'
import {
  RENTAL_ORDER_PATH,
  buildRentalInquiryUrl,
  buildRentalOrderUrl,
  buildRentalQuoteUrl,
} from '@/features/rental/navigation/rental-routes'
import RentalQuotePage from '@/features/rental/presentation/quote/RentalQuotePage.vue'
import RentalUnavailable from '@/features/rental/presentation/shared/RentalUnavailable.vue'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

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
  uni.redirectTo({ url: buildInspectionLoginUrl(inquiryId.value ? buildRentalQuoteUrl(inquiryId.value) : buildMyDemandsUrl()) })
}

// The cashier opens on top: back from it is this quote, now confirmed, with the way to the order.
function openCashier(tradeOrderId: string): void {
  uni.navigateTo({ url: buildCashierUrl(tradeOrderId) })
}

function openOrder(rentalOrderId: string): void {
  if (previousRoute() === RENTAL_ORDER_PATH) {
    uni.navigateBack()
    return
  }
  uni.navigateTo({ url: buildRentalOrderUrl(rentalOrderId) })
}

onLoad((query) => {
  const value = typeof query?.inquiryId === 'string' ? query.inquiryId.trim() : ''
  inquiryId.value = value || null
})

// Every return reads the inquiry again: the merchant may have quoted or revised meanwhile.
onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})

function open(url: string): void {
  uni.navigateTo({ url })
}
</script>

<template>
  <RentalQuotePage
    v-if="RENTAL_PREVIEW_ENABLED && shown"
    :inquiry-id="inquiryId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @pay="openCashier"
    @view-order="openOrder"
    @reinquire="(target) => open(buildRentalInquiryUrl(target))"
  />
  <RentalUnavailable v-else-if="!RENTAL_PREVIEW_ENABLED" />
</template>
