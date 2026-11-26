<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import ActivityUnavailable from '@/features/activity/presentation/shared/ActivityUnavailable.vue'
import {
  buildActivityChangeUrl,
  buildActivityDetailUrl,
  buildActivityEnrollmentsUrl,
  buildActivityOrderUrl,
  buildActivityRefundUrl,
  buildActivityResultUrl,
  buildActivityTeamOrderUrl,
  buildActivityTicketUrl,
} from '@/features/activity/navigation/activity-routes'
import ActivityTicketPage from '@/features/activity/presentation/ticket/ActivityTicketPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const enrollmentId = ref('')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Opened as the only page (a link, or after a reload): the list of sign-ups is the way out.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildActivityEnrollmentsUrl() })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildActivityTicketUrl(enrollmentId.value)) })
}

function openActivity(activityId: string, cityCode: string): void {
  uni.navigateTo({ url: buildActivityDetailUrl(activityId, cityCode) })
}

// The sign-up's order is paid at the trade cashier; back from it returns here.
function openCashier(tradeOrderId: string): void {
  uni.navigateTo({ url: buildCashierUrl(tradeOrderId) })
}

function openOrder(id: string): void {
  uni.navigateTo({ url: buildActivityOrderUrl(id) })
}

// `move` opens 申请退改 on 改到其他场次 (a free sign-up cancels on this page's sheet).
function openRefund(id: string, ticketId: string, move: boolean): void {
  uni.navigateTo({ url: buildActivityRefundUrl(id, ticketId) + (move ? '&mode=move' : '') })
}

// A team sign-up's tickets are shown on its team order (board TeamOrder).
function openTeam(id: string): void {
  uni.redirectTo({ url: buildActivityTeamOrderUrl(id) })
}

function openResult(id: string): void {
  uni.navigateTo({ url: buildActivityResultUrl(id) })
}

function openChange(id: string, changeId: string): void {
  uni.navigateTo({ url: buildActivityChangeUrl(id, changeId) })
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
  <ActivityTicketPage
    v-if="ACTIVITY_PREVIEW_ENABLED && shown"
    :enrollment-id="enrollmentId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @open-activity="openActivity"
    @pay="openCashier"
    @open-order="openOrder"
    @refund="openRefund"
    @team="openTeam"
    @open-result="openResult"
    @open-change="openChange"
  />
  <ActivityUnavailable v-else-if="!ACTIVITY_PREVIEW_ENABLED" />
</template>
