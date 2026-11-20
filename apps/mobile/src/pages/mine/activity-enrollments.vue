<script setup lang="ts">
import { onLoad, onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import type { EnrollmentStatus } from '@/features/activity/domain/enrollment'
import {
  buildActivityDetailUrl,
  buildActivityEnrollmentsUrl,
  buildActivityOrderUrl,
  buildActivityRefundUrl,
  buildActivityTicketUrl,
} from '@/features/activity/navigation/activity-routes'
import ActivityEnrollmentsPage from '@/features/activity/presentation/enrollments/ActivityEnrollmentsPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  MINE_TAB_PATH,
  buildInspectionLoginUrl,
} from '@/features/inspection/navigation/inspection-routes'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const STATUSES: readonly EnrollmentStatus[] = ['UPCOMING', 'FINISHED', 'CANCELLED']

const initialStatus = ref<EnrollmentStatus>('UPCOMING')
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
  uni.redirectTo({
    url: buildInspectionLoginUrl(buildActivityEnrollmentsUrl(initialStatus.value)),
  })
}

function openTicket(enrollmentId: string): void {
  uni.navigateTo({ url: buildActivityTicketUrl(enrollmentId) })
}

function openActivity(activityId: string, cityCode: string): void {
  uni.navigateTo({ url: buildActivityDetailUrl(activityId, cityCode) })
}

// The sign-up's order is paid at the trade cashier; back from it returns here.
function openCashier(tradeOrderId: string): void {
  uni.navigateTo({ url: buildCashierUrl(tradeOrderId) })
}

function openOrder(enrollmentId: string): void {
  uni.navigateTo({ url: buildActivityOrderUrl(enrollmentId) })
}

function openRefund(enrollmentId: string): void {
  uni.navigateTo({ url: buildActivityRefundUrl(enrollmentId) })
}

onLoad((query) => {
  const status = query?.status as EnrollmentStatus | undefined
  if (status && STATUSES.includes(status)) initialStatus.value = status
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
  <ActivityEnrollmentsPage
    v-if="shown"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :reach-bottom-key="reachBottomKey"
    :initial-status="initialStatus"
    @back="goBack"
    @login="openLogin"
    @open-ticket="openTicket"
    @open-activity="openActivity"
    @browse="openHomeChannel('ACTIVITY')"
    @pay="openCashier"
    @open-order="openOrder"
    @refund="openRefund"
  />
</template>
