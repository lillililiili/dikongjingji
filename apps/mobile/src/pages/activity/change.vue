<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import {
  buildActivityEnrollmentsUrl,
  buildActivityOrderUrl,
  buildActivityTicketUrl,
} from '@/features/activity/navigation/activity-routes'
import ActivityChangePage from '@/features/activity/presentation/change/ActivityChangePage.vue'
import ActivityUnavailable from '@/features/activity/presentation/shared/ActivityUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'

const enrollmentId = ref('')
const changeId = ref('')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Opened after a refund or reschedule (redirected from the refund page) or from a list; alone, the
// sign-ups list is the way out.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildActivityEnrollmentsUrl() })
}

// The login page only returns to pages it knows; the sign-up's ticket is one of them.
function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildActivityTicketUrl(enrollmentId.value)) })
}

function openOrder(id: string, paid: boolean): void {
  uni.navigateTo({ url: paid ? buildActivityOrderUrl(id) : buildActivityTicketUrl(id) })
}

onLoad((query) => {
  enrollmentId.value = typeof query?.enrollmentId === 'string' ? query.enrollmentId : ''
  changeId.value = typeof query?.changeId === 'string' ? query.changeId : ''
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <ActivityChangePage
    v-if="ACTIVITY_PREVIEW_ENABLED && shown"
    :enrollment-id="enrollmentId"
    :change-id="changeId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @open-order="openOrder"
  />
  <ActivityUnavailable v-else-if="!ACTIVITY_PREVIEW_ENABLED" />
</template>
