<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { experienceCityCode } from '@/features/experience/application/experience-city'
import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import {
  buildSimulationResultUrl,
  buildSimulationUrl,
  buildVenueDetailUrl,
  parseSimulationResultTarget,
  type SimulationResultTarget,
} from '@/features/experience/navigation/experience-routes'
import SimulationResultPage from '@/features/experience/presentation/result/SimulationResultPage.vue'
import OnlineExperienceUnavailable from '@/features/experience/presentation/shared/OnlineExperienceUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildMallProductUrl } from '@/features/mall/navigation/mall-routes'
import { openSupportChat } from '@/features/support/application/support-entry'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

const cityStore = useCurrentCityStore()
const target = ref<SimulationResultTarget | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)
const cityCode = ref('')

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('EXPERIENCE')
}

function openLogin(): void {
  if (!target.value) return
  uni.redirectTo({ url: buildInspectionLoginUrl(buildSimulationResultUrl(target.value)) })
}

// A guest's result became a record: the address follows, so a reload reads the record.
function continueOnRecord(next: SimulationResultTarget): void {
  uni.redirectTo({ url: buildSimulationResultUrl(next) })
}

function retry(experienceId: string): void {
  uni.redirectTo({ url: buildSimulationUrl(experienceId) })
}

function openProduct(productId: string): void {
  uni.navigateTo({ url: buildMallProductUrl(productId, cityCode.value) })
}

function openVenue(venueId: string): void {
  uni.navigateTo({ url: buildVenueDetailUrl(venueId, cityCode.value) })
}

function consult(merchantId: string, productId: string, title: string, subtitle: string): void {
  openSupportChat({ kind: 'MERCHANT', organizationId: merchantId }, { type: 'PRODUCT', refId: productId, title, subtitle })
}

onLoad((query) => {
  target.value = parseSimulationResultTarget(query)
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  cityCode.value = experienceCityCode(cityStore.currentCity)
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <SimulationResultPage
    v-if="EXPERIENCE_PREVIEW_ENABLED && shown && target"
    :target="target"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :city-code="cityCode"
    @back="goBack"
    @login="openLogin"
    @saved="continueOnRecord"
    @retry="retry"
    @open-product="openProduct"
    @open-venue="openVenue"
    @open-venues="openHomeChannel('EXPERIENCE')"
    @open-activities="openHomeChannel('ACTIVITY')"
    @consult="consult"
  />
  <OnlineExperienceUnavailable v-else-if="!EXPERIENCE_PREVIEW_ENABLED" />
</template>
