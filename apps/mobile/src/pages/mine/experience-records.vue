<script setup lang="ts">
import { onLoad, onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { experienceCityCode } from '@/features/experience/application/experience-city'
import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import type { ExperienceType } from '@/features/experience/domain/experience'
import {
  EXPERIENCE_RECORDS_TABS,
  buildCourseTrialUrl,
  buildExperienceRecordsUrl,
  buildSimulationResultUrl,
  buildSimulationUrl,
  buildSoftwareTrialUrl,
  buildVenueBookingUrl,
  buildVenueBookingsUrl,
  type ExperienceRecordsTab,
  type SimulationResultTarget,
} from '@/features/experience/navigation/experience-routes'
import ExperienceRecordsPage from '@/features/experience/presentation/records/ExperienceRecordsPage.vue'
import OnlineExperienceUnavailable from '@/features/experience/presentation/shared/OnlineExperienceUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { MINE_TAB_PATH, buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildMallProductUrl } from '@/features/mall/navigation/mall-routes'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

const cityStore = useCurrentCityStore()
const initialTab = ref<ExperienceRecordsTab>('ALL')
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
  uni.redirectTo({ url: buildInspectionLoginUrl(buildExperienceRecordsUrl(initialTab.value)) })
}

function openResult(target: SimulationResultTarget): void {
  uni.navigateTo({ url: buildSimulationResultUrl(target) })
}

function openTrial(type: ExperienceType, experienceId: string, recordId: string | null): void {
  const url = type === 'COURSE_TRIAL' ? buildCourseTrialUrl(experienceId, recordId) : buildSoftwareTrialUrl(experienceId, recordId)
  uni.navigateTo({ url })
}

function retry(experienceId: string): void {
  uni.navigateTo({ url: buildSimulationUrl(experienceId) })
}

function openProduct(productId: string): void {
  uni.navigateTo({ url: buildMallProductUrl(productId, experienceCityCode(cityStore.currentCity)) })
}

function openBooking(bookingId: string): void {
  uni.navigateTo({ url: buildVenueBookingUrl(bookingId) })
}

function openAllBookings(): void {
  uni.navigateTo({ url: buildVenueBookingsUrl() })
}

onLoad((query) => {
  const tab = query?.tab as ExperienceRecordsTab | undefined
  if (tab && EXPERIENCE_RECORDS_TABS.includes(tab)) initialTab.value = tab
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
  <ExperienceRecordsPage
    v-if="EXPERIENCE_PREVIEW_ENABLED && shown"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :reach-bottom-key="reachBottomKey"
    :initial-tab="initialTab"
    @back="goBack"
    @login="openLogin"
    @open-result="openResult"
    @retry="retry"
    @open-trial="openTrial"
    @open-product="openProduct"
    @open-booking="openBooking"
    @open-all-bookings="openAllBookings"
  />
  <OnlineExperienceUnavailable v-else-if="!EXPERIENCE_PREVIEW_ENABLED" />
</template>
