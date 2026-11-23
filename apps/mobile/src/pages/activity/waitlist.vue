<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import {
  buildActivityDetailUrl,
  buildActivityEnrollmentsUrl,
  buildActivityTicketUrl,
  buildActivityWaitlistUrl,
} from '@/features/activity/navigation/activity-routes'
import ActivityUnavailable from '@/features/activity/presentation/shared/ActivityUnavailable.vue'
import ActivityWaitlistPage from '@/features/activity/presentation/waitlist/ActivityWaitlistPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

// Board EnrollWaitlist (S5-3): one waitlist of the signed-in account.
const waitlistId = ref('')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Back is the page it came from; alone (opened from a message), the account's sign-ups.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildActivityEnrollmentsUrl() })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildActivityWaitlistUrl(waitlistId.value)) })
}

// The cashier keeps this page under it, so back from the cashier shows the waitlist again.
function openCashier(tradeOrderId: string): void {
  uni.navigateTo({ url: buildCashierUrl(tradeOrderId) })
}

function openTicket(enrollmentId: string): void {
  uni.navigateTo({ url: buildActivityTicketUrl(enrollmentId) })
}

function openActivity(activityId: string, cityCode: string): void {
  uni.navigateTo({ url: buildActivityDetailUrl(activityId, cityCode) })
}

onLoad((query) => {
  waitlistId.value = typeof query?.waitlistId === 'string' ? query.waitlistId : ''
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <ActivityWaitlistPage
    v-if="ACTIVITY_PREVIEW_ENABLED && shown"
    :waitlist-id="waitlistId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @pay="openCashier"
    @ticket="openTicket"
    @activity="openActivity"
    @more="openHomeChannel('ACTIVITY')"
  />
  <ActivityUnavailable v-else-if="!ACTIVITY_PREVIEW_ENABLED" />
</template>
