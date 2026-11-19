<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { RENTAL_PREVIEW_ENABLED } from '@/features/rental/application/rental-preview'
import {
  RENTAL_ORDER_PATH,
  buildMyRentalOrdersUrl,
  buildRentalContractUrl,
  buildRentalOrderUrl,
  buildRentalQuoteUrl,
} from '@/features/rental/navigation/rental-routes'
import RentalContractPage from '@/features/rental/presentation/contract/RentalContractPage.vue'
import RentalUnavailable from '@/features/rental/presentation/shared/RentalUnavailable.vue'

const rentalOrderId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function previousRoute(): string | null {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2]
  return previous ? `/${previous.route}` : null
}

// Opened from a link, back is the order the contract belongs to.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: rentalOrderId.value ? buildRentalOrderUrl(rentalOrderId.value) : buildMyRentalOrdersUrl() })
}

// 查看订单: back to the order when the contract was opened from it.
function openOrder(id: string): void {
  if (previousRoute() === RENTAL_ORDER_PATH) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildRentalOrderUrl(id) })
}

function openLogin(): void {
  uni.redirectTo({
    url: buildInspectionLoginUrl(rentalOrderId.value ? buildRentalContractUrl(rentalOrderId.value) : buildMyRentalOrdersUrl()),
  })
}

onLoad((query) => {
  const value = typeof query?.rentalOrderId === 'string' ? query.rentalOrderId.trim() : ''
  rentalOrderId.value = value || null
})

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
  <RentalContractPage
    v-if="RENTAL_PREVIEW_ENABLED && shown"
    :rental-order-id="rentalOrderId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @order="openOrder"
    @quote="(inquiryId) => open(buildRentalQuoteUrl(inquiryId))"
  />
  <RentalUnavailable v-else-if="!RENTAL_PREVIEW_ENABLED" />
</template>
