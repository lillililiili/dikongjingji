<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import {
  buildActivityDetailUrl,
  buildActivityTeamOrderUrl,
  buildActivityTeamSignupUrl,
} from '@/features/activity/navigation/activity-routes'
import ActivityUnavailable from '@/features/activity/presentation/shared/ActivityUnavailable.vue'
import ActivityTeamSignupPage from '@/features/activity/presentation/team/ActivityTeamSignupPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

// Board TeamSignup (S5-3).
const activityId = ref('')
const sessionId = ref('')
const cityCode = ref('')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildActivityDetailUrl(activityId.value, cityCode.value) })
}

function openLogin(): void {
  uni.redirectTo({
    url: buildInspectionLoginUrl(buildActivityTeamSignupUrl(activityId.value, sessionId.value, cityCode.value)),
  })
}

// The team order (free) or the cashier (paid) takes the form's place; back lands on the activity.
function openOrder(enrollmentId: string): void {
  uni.redirectTo({ url: buildActivityTeamOrderUrl(enrollmentId) })
}

function openCashier(tradeOrderId: string): void {
  uni.redirectTo({ url: buildCashierUrl(tradeOrderId) })
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
  <ActivityTeamSignupPage
    v-if="ACTIVITY_PREVIEW_ENABLED && shown"
    :activity-id="activityId"
    :session-id="sessionId"
    :city-code="cityCode"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @done="openOrder"
    @pay="openCashier"
  />
  <ActivityUnavailable v-else-if="!ACTIVITY_PREVIEW_ENABLED" />
</template>
