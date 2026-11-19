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
import DemandComparePage from '@/features/demand/presentation/compare/DemandComparePage.vue'
import DemandUnavailable from '@/features/demand/presentation/shared/DemandUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'

const demandId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Back is the quotes of the same demand.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: demandId.value ? buildDemandQuotesUrl(demandId.value) : buildMyDemandsUrl() })
}

function openLogin(): void {
  uni.redirectTo({
    url: buildInspectionLoginUrl(demandId.value ? buildDemandCompareUrl(demandId.value) : buildMyDemandsUrl()),
  })
}

function republish(id: string): void {
  uni.navigateTo({ url: buildDemandPublishUrl({ from: id }) })
}

onLoad((query) => {
  const value = typeof query?.demandId === 'string' ? query.demandId.trim() : ''
  demandId.value = value || null
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <DemandComparePage
    v-if="DEMAND_PREVIEW_ENABLED && shown"
    :demand-id="demandId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @republish="republish"
  />
  <DemandUnavailable v-else-if="!DEMAND_PREVIEW_ENABLED" />
</template>
