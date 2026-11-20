<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import { buildActivityTicketUrl } from '@/features/activity/navigation/activity-routes'
import ActivityRefundPage from '@/features/activity/presentation/refund/ActivityRefundPage.vue'
import ActivityUnavailable from '@/features/activity/presentation/shared/ActivityUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'

const enrollmentId = ref('')
const ticketId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Back (and after a refund) is the page it was opened from; alone, the sign-up's ticket.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildActivityTicketUrl(enrollmentId.value) })
}

// The login page only returns to pages it knows; the sign-up's ticket is one of them.
function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildActivityTicketUrl(enrollmentId.value)) })
}

onLoad((query) => {
  enrollmentId.value = typeof query?.enrollmentId === 'string' ? query.enrollmentId : ''
  ticketId.value = typeof query?.ticketId === 'string' && query.ticketId ? query.ticketId : null
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <ActivityRefundPage
    v-if="ACTIVITY_PREVIEW_ENABLED && shown"
    :enrollment-id="enrollmentId"
    :ticket-id="ticketId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @done="goBack"
  />
  <ActivityUnavailable v-else-if="!ACTIVITY_PREVIEW_ENABLED" />
</template>
