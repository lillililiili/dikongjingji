<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildMallProductUrl } from '@/features/mall/navigation/mall-routes'
import { RENTAL_PREVIEW_ENABLED } from '@/features/rental/application/rental-preview'
import {
  buildMyRentalOrdersUrl,
  buildRentalContractUrl,
  buildRentalOrderUrl,
} from '@/features/rental/navigation/rental-routes'
import RentalOrderPage from '@/features/rental/presentation/order/RentalOrderPage.vue'
import RentalUnavailable from '@/features/rental/presentation/shared/RentalUnavailable.vue'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const rentalOrderId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Opened from the pay result or a link, back is 我的 → 租赁订单.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildMyRentalOrdersUrl() })
}

function openLogin(): void {
  uni.redirectTo({
    url: buildInspectionLoginUrl(rentalOrderId.value ? buildRentalOrderUrl(rentalOrderId.value) : buildMyRentalOrdersUrl()),
  })
}

onLoad((query) => {
  const value = typeof query?.rentalOrderId === 'string' ? query.rentalOrderId.trim() : ''
  rentalOrderId.value = value || null
})

// Every return reads the order again: the merchant records handover and return meanwhile.
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
  <RentalOrderPage
    v-if="RENTAL_PREVIEW_ENABLED && shown"
    :rental-order-id="rentalOrderId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @contract="(id) => open(buildRentalContractUrl(id))"
    @pay="(tradeOrderId) => open(buildCashierUrl(tradeOrderId))"
    @rent-again="(productId, cityCode) => open(buildMallProductUrl(productId, cityCode))"
  />
  <RentalUnavailable v-else-if="!RENTAL_PREVIEW_ENABLED" />
</template>
