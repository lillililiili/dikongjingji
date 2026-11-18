<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import {
  ACTIVITY_PARTICIPANTS_PATH,
  buildActivityDetailUrl,
  buildActivityEnrollUrl,
  buildActivityTicketUrl,
} from '@/features/activity/navigation/activity-routes'
import ActivityEnrollPage from '@/features/activity/presentation/enroll/ActivityEnrollPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'

const activityId = ref('')
const sessionId = ref('')
const cityCode = ref('')
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
      buildActivityEnrollUrl(activityId.value, sessionId.value, cityCode.value),
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

onLoad((query) => {
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
    @back="goBack"
    @login="openLogin"
    @manage-people="openPeople"
    @done="openTicket"
  />
</template>
