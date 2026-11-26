<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { experienceCityCode } from '@/features/experience/application/experience-city'
import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import {
  buildCourseTrialUrl,
  buildExperienceRecordsUrl,
} from '@/features/experience/navigation/experience-routes'
import OnlineExperienceUnavailable from '@/features/experience/presentation/shared/OnlineExperienceUnavailable.vue'
import ExperienceLinkMissing from '@/features/experience/presentation/shared/ExperienceLinkMissing.vue'
import TrialPage from '@/features/experience/presentation/trial/TrialPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildMallProductUrl } from '@/features/mall/navigation/mall-routes'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

const cityStore = useCurrentCityStore()
const experienceId = ref('')
const recordId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('EXPERIENCE')
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildCourseTrialUrl(experienceId.value, recordId.value)) })
}

function openRecords(): void {
  uni.navigateTo({ url: buildExperienceRecordsUrl('ONLINE') })
}

function openProduct(productId: string): void {
  uni.navigateTo({ url: buildMallProductUrl(productId, experienceCityCode(cityStore.currentCity)) })
}

onLoad((query) => {
  experienceId.value = typeof query?.experienceId === 'string' ? query.experienceId : ''
  recordId.value = typeof query?.recordId === 'string' && query.recordId ? query.recordId : null
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <TrialPage
    v-if="EXPERIENCE_PREVIEW_ENABLED && shown && experienceId"
    trial-type="COURSE_TRIAL"
    :experience-id="experienceId"
    :record-id="recordId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @open-records="openRecords"
    @open-product="openProduct"
    @open-channel="openHomeChannel('EXPERIENCE')"
  />
  <OnlineExperienceUnavailable v-else-if="!EXPERIENCE_PREVIEW_ENABLED" />
  <ExperienceLinkMissing v-else-if="shown" />
</template>
