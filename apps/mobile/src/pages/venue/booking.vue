<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import {
  VENUE_BOOKINGS_PATH,
  buildVenueBookingUrl,
  buildVenueBookingsUrl,
  buildVenueDetailUrl,
} from '@/features/experience/navigation/experience-routes'
import type { VenueOrderInfo } from '@/features/experience/domain/venue-booking'
import VenueBookingPage from '@/features/experience/presentation/booking/VenueBookingPage.vue'
import VenueBookingUnavailable from '@/features/experience/presentation/shared/VenueBookingUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { tradeApi } from '@/features/trade/data/trade-api-client'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const bookingId = ref('')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function previousRoute(): string | null {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2]
  return previous ? `/${previous.route}` : null
}

// Opened as the only page (a link, a reload, or after the payment result): the list is the way out.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildVenueBookingsUrl() })
}

function openList(): void {
  if (previousRoute() === VENUE_BOOKINGS_PATH) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildVenueBookingsUrl() })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildVenueBookingUrl(bookingId.value)) })
}

// The booking's order is paid at the trade cashier; back from it returns here.
function openCashier(tradeOrderId: string): void {
  uni.navigateTo({ url: buildCashierUrl(tradeOrderId) })
}

function openVenue(venueId: string, cityCode: string): void {
  uni.navigateTo({ url: buildVenueDetailUrl(venueId, cityCode) })
}

// The order number and payee come from the trade module; the page only knows the order id.
async function loadOrderInfo(token: string, tradeOrderId: string): Promise<VenueOrderInfo | null> {
  const order = await tradeApi.read(token, tradeOrderId)
  return { orderNo: order.orderNo, payeeName: order.merchant.name }
}

onLoad((query) => {
  bookingId.value = typeof query?.bookingId === 'string' ? query.bookingId : ''
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <VenueBookingPage
    v-if="EXPERIENCE_PREVIEW_ENABLED && shown"
    :booking-id="bookingId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :load-order-info="loadOrderInfo"
    @back="goBack"
    @login="openLogin"
    @pay="openCashier"
    @open-venue="openVenue"
    @open-list="openList"
  />
  <VenueBookingUnavailable v-else-if="!EXPERIENCE_PREVIEW_ENABLED" />
</template>
