<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { DEMAND_PREVIEW_ENABLED } from '@/features/demand/application/demand-preview'
import {
  buildDemandCompareUrl,
  buildDemandPublishUrl,
  buildDemandQuotesUrl,
  buildMyDemandsUrl,
} from '@/features/demand/navigation/demand-routes'
import DemandQuotesPage from '@/features/demand/presentation/quotes/DemandQuotesPage.vue'
import DemandUnavailable from '@/features/demand/presentation/shared/DemandUnavailable.vue'
import { buildServiceOrderDetailUrl } from '@/features/serviceorder/navigation/service-order-routes'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildDemandInspectionBookingUrl, buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'

const demandId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Opened from a message or a link, back is the list of demands.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildMyDemandsUrl() })
}

function openLogin(): void {
  uni.redirectTo({
    url: buildInspectionLoginUrl(demandId.value ? buildDemandQuotesUrl(demandId.value) : buildMyDemandsUrl()),
  })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

onLoad((query) => {
  const value = typeof query?.demandId === 'string' ? query.demandId.trim() : ''
  demandId.value = value || null
})

// Every return reads the quotes again: a choice made on the compare page shows up here.
onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <DemandQuotesPage
    v-if="DEMAND_PREVIEW_ENABLED && shown"
    :demand-id="demandId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @compare="(id, quoteId) => open(buildDemandCompareUrl(id, quoteId))"
    @view-demand="(id) => open(buildDemandPublishUrl({ demandId: id }))"
    @republish="(id) => open(buildDemandPublishUrl({ from: id }))"
    @service-order="(orderId) => open(buildServiceOrderDetailUrl(orderId))"
    @inspection-booking="(id, reservationId) => open(buildDemandInspectionBookingUrl(id, reservationId))"
  />
  <DemandUnavailable v-else-if="!DEMAND_PREVIEW_ENABLED" />
</template>
