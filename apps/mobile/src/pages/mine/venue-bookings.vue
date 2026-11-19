<script setup lang="ts">
import { onLoad, onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import { VENUE_BOOKING_BUCKETS, type VenueBookingBucket } from '@/features/experience/domain/venue-booking'
import {
  buildVenueBookingUrl,
  buildVenueBookingsUrl,
} from '@/features/experience/navigation/experience-routes'
import VenueBookingsPage from '@/features/experience/presentation/bookings/VenueBookingsPage.vue'
import VenueBookingUnavailable from '@/features/experience/presentation/shared/VenueBookingUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  MINE_TAB_PATH,
  buildInspectionLoginUrl,
} from '@/features/inspection/navigation/inspection-routes'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const initialBucket = ref<VenueBookingBucket>('UPCOMING')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const reachBottomKey = ref(0)
const shown = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: MINE_TAB_PATH })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildVenueBookingsUrl(initialBucket.value)) })
}

function openBooking(bookingId: string): void {
  uni.navigateTo({ url: buildVenueBookingUrl(bookingId) })
}

// The booking's order is paid at the trade cashier; back from it returns here.
function openCashier(tradeOrderId: string): void {
  uni.navigateTo({ url: buildCashierUrl(tradeOrderId) })
}

onLoad((query) => {
  const bucket = query?.bucket as VenueBookingBucket | undefined
  if (bucket && VENUE_BOOKING_BUCKETS.includes(bucket)) initialBucket.value = bucket
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})

onReachBottom(() => {
  reachBottomKey.value += 1
})
</script>

<template>
  <VenueBookingsPage
    v-if="EXPERIENCE_PREVIEW_ENABLED && shown"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :reach-bottom-key="reachBottomKey"
    :initial-bucket="initialBucket"
    @back="goBack"
    @login="openLogin"
    @open-booking="openBooking"
    @pay="openCashier"
    @browse="openHomeChannel('EXPERIENCE')"
  />
  <VenueBookingUnavailable v-else-if="!EXPERIENCE_PREVIEW_ENABLED" />
</template>
