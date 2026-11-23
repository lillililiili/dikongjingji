<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import {
  ACTIVITY_PARTICIPANTS_PATH,
  buildActivityDetailUrl,
  buildActivityEnrollUrl,
  buildActivityTicketUrl,
  buildActivityWaitlistUrl,
} from '@/features/activity/navigation/activity-routes'
import ActivityEnrollPage from '@/features/activity/presentation/enroll/ActivityEnrollPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const activityId = ref('')
const sessionId = ref('')
const cityCode = ref('')
// 'waitlist' (S5-3): join the waitlist of a full session instead of signing up.
const mode = ref<'enroll' | 'waitlist'>('enroll')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
// The form is created on the first show, when the query and the login state are both known.
const shown = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildActivityDetailUrl(activityId.value, cityCode.value) })
}

// Signing in replaces this page and comes back to the same form.
function openLogin(): void {
  uni.redirectTo({
    url: buildInspectionLoginUrl(
      buildActivityEnrollUrl(
        activityId.value,
        sessionId.value,
        cityCode.value,
        mode.value === 'waitlist' ? 'waitlist' : undefined,
      ),
    ),
  })
}

function openPeople(): void {
  uni.navigateTo({ url: `${ACTIVITY_PARTICIPANTS_PATH}?from=enroll` })
}

// The ticket takes the form's place, so going back from it lands on the activity.
function openTicket(enrollmentId: string): void {
  uni.redirectTo({ url: buildActivityTicketUrl(enrollmentId) })
}

// A paid sign-up goes on to the cashier in the form's place, like the mall's confirm page; back
// from the cashier lands on the activity.
function openCashier(tradeOrderId: string): void {
  uni.redirectTo({ url: buildCashierUrl(tradeOrderId) })
}

// The waitlist page takes the form's place; back from it lands on the activity.
function openWaitlist(waitlistId: string): void {
  uni.redirectTo({ url: buildActivityWaitlistUrl(waitlistId) })
}

onLoad((query) => {
  mode.value = query?.mode === 'waitlist' ? 'waitlist' : 'enroll'
  activityId.value = typeof query?.activityId === 'string' ? query.activityId : ''
  sessionId.value = typeof query?.sessionId === 'string' ? query.sessionId : ''
  cityCode.value = typeof query?.cityCode === 'string' ? query.cityCode : ''
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <ActivityEnrollPage
    v-if="shown"
    :activity-id="activityId"
    :session-id="sessionId"
    :city-code="cityCode"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :mode="mode"
    @back="goBack"
    @login="openLogin"
    @manage-people="openPeople"
    @done="openTicket"
    @pay="openCashier"
    @waitlisted="openWaitlist"
  />
</template>
