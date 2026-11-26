<script setup lang="ts">
import { onBackPress, onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import {
  buildSimulationResultUrl,
  type SimulationResultTarget,
} from '@/features/experience/navigation/experience-routes'
import OnlineExperienceUnavailable from '@/features/experience/presentation/shared/OnlineExperienceUnavailable.vue'
import ExperienceLinkMissing from '@/features/experience/presentation/shared/ExperienceLinkMissing.vue'
import SimulationPage from '@/features/experience/presentation/simulation/SimulationPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'

const experienceId = ref('')
const loaded = ref(false)
const accessToken = ref<string | null>(null)
const page = ref<InstanceType<typeof SimulationPage> | null>(null)
let leaving = false

function leave(): void {
  leaving = true
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('EXPERIENCE')
}

// The task is finished: the result replaces it, so 返回 from the result does not reopen the task.
function openResult(target: SimulationResultTarget): void {
  leaving = true
  uni.redirectTo({ url: buildSimulationResultUrl(target) })
}

onLoad((query) => {
  experienceId.value = typeof query?.experienceId === 'string' ? query.experienceId : ''
  loaded.value = true
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
})

// The system back gesture asks the same as the ✕ once the user has run a program.
onBackPress((options) => {
  if (leaving || options.from === 'navigateBack' || !page.value) return false
  page.value.exit()
  return true
})
</script>

<template>
  <SimulationPage
    v-if="EXPERIENCE_PREVIEW_ENABLED && experienceId"
    ref="page"
    :experience-id="experienceId"
    :access-token="accessToken"
    @exit="leave"
    @finished="openResult"
  />
  <OnlineExperienceUnavailable v-else-if="!EXPERIENCE_PREVIEW_ENABLED" />
  <ExperienceLinkMissing v-else-if="loaded" />
</template>
