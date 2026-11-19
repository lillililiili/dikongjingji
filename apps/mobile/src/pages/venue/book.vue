<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { ACTIVITY_PARTICIPANTS_PATH } from '@/features/activity/navigation/activity-routes'
import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import {
  buildVenueBookUrl,
  buildVenueBookingUrl,
  buildVenueDetailUrl,
  parseVenueBookTarget,
  type VenueBookTarget,
} from '@/features/experience/navigation/experience-routes'
import VenueBookPage from '@/features/experience/presentation/book/VenueBookPage.vue'
import VenueBookingUnavailable from '@/features/experience/presentation/shared/VenueBookingUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const target = ref<VenueBookTarget | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
// The form is created on the first show, when the query and the login state are both known.
const shown = ref(false)

// Back (and 修改场次) is the venue detail, where sessions are picked.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  if (target.value) {
    uni.redirectTo({ url: buildVenueDetailUrl(target.value.venueId, target.value.cityCode) })
    return
  }
  openHomeChannel('EXPERIENCE')
}

// Signing in replaces this page and comes back to the same form.
function openLogin(): void {
  if (!target.value) {
    goBack()
    return
  }
  uni.redirectTo({ url: buildInspectionLoginUrl(buildVenueBookUrl(target.value)) })
}

// The participant list is shared with activity sign-up; coming back shows it again.
function openPeople(): void {
  uni.navigateTo({ url: `${ACTIVITY_PARTICIPANTS_PATH}?from=venue` })
}

// The code page takes the form's place, so going back from it lands on the venue.
function openBooking(bookingId: string): void {
  uni.redirectTo({ url: buildVenueBookingUrl(bookingId) })
}

// A priced booking goes on to the cashier in the form's place; back from the cashier lands on the venue.
function openCashier(tradeOrderId: string): void {
  uni.redirectTo({ url: buildCashierUrl(tradeOrderId) })
}

onLoad((query) => {
  target.value = parseVenueBookTarget(query as Record<string, unknown> | undefined)
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <VenueBookPage
    v-if="EXPERIENCE_PREVIEW_ENABLED && shown"
    :target="target"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @manage-people="openPeople"
    @done="openBooking"
    @pay="openCashier"
  />
  <VenueBookingUnavailable v-else-if="!EXPERIENCE_PREVIEW_ENABLED" />
</template>
