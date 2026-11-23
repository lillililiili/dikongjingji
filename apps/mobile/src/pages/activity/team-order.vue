<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import {
  buildActivityDetailUrl,
  buildActivityEnrollmentsUrl,
  buildActivityRefundUrl,
  buildActivityTeamOrderUrl,
} from '@/features/activity/navigation/activity-routes'
import ActivityUnavailable from '@/features/activity/presentation/shared/ActivityUnavailable.vue'
import ActivityTeamOrderPage from '@/features/activity/presentation/team/ActivityTeamOrderPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

// Board TeamOrder (S5-3): the leader's team sign-up.
const enrollmentId = ref('')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildActivityEnrollmentsUrl() })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildActivityTeamOrderUrl(enrollmentId.value)) })
}

function openCashier(tradeOrderId: string): void {
  uni.navigateTo({ url: buildCashierUrl(tradeOrderId) })
}

function openRefund(ticketId: string | null): void {
  uni.navigateTo({ url: buildActivityRefundUrl(enrollmentId.value, ticketId ?? undefined) })
}

function openActivity(activityId: string, cityCode: string): void {
  uni.navigateTo({ url: buildActivityDetailUrl(activityId, cityCode) })
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
  <ActivityTeamOrderPage
    v-if="ACTIVITY_PREVIEW_ENABLED && shown"
    :enrollment-id="enrollmentId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @pay="openCashier"
    @refund="openRefund"
    @activity="openActivity"
  />
  <ActivityUnavailable v-else-if="!ACTIVITY_PREVIEW_ENABLED" />
</template>
