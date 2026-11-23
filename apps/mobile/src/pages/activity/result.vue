<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import {
  buildActivityEnrollmentsUrl,
  buildActivityRefundUrl,
  buildActivityTicketUrl,
} from '@/features/activity/navigation/activity-routes'
import ActivityResultPage from '@/features/activity/presentation/result/ActivityResultPage.vue'
import ActivityUnavailable from '@/features/activity/presentation/shared/ActivityUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'

const enrollmentId = ref('')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Opened as the only page (a message, a link): the list of sign-ups is the way out.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildActivityEnrollmentsUrl('FINISHED') })
}

// The login page only returns to pages it knows; the sign-up's ticket is one of them.
function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildActivityTicketUrl(enrollmentId.value)) })
}

function openTicket(id: string): void {
  uni.navigateTo({ url: buildActivityTicketUrl(id) })
}

function openRefund(id: string): void {
  uni.navigateTo({ url: buildActivityRefundUrl(id) })
}

onLoad((query) => {
  enrollmentId.value = typeof query?.enrollmentId === 'string' ? query.enrollmentId : ''
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <ActivityResultPage
    v-if="ACTIVITY_PREVIEW_ENABLED && shown"
    :enrollment-id="enrollmentId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @open-ticket="openTicket"
    @refund="openRefund"
  />
  <ActivityUnavailable v-else-if="!ACTIVITY_PREVIEW_ENABLED" />
</template>
