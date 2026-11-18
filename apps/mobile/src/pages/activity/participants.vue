<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACTIVITY_PARTICIPANTS_PATH } from '@/features/activity/navigation/activity-routes'
import ActivityParticipantsPage from '@/features/activity/presentation/participants/ActivityParticipantsPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  MINE_TAB_PATH,
  buildInspectionLoginUrl,
} from '@/features/inspection/navigation/inspection-routes'

const fromEnroll = ref(false)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: MINE_TAB_PATH })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(ACTIVITY_PARTICIPANTS_PATH) })
}

onLoad((query) => {
  fromEnroll.value = query?.from === 'enroll'
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <ActivityParticipantsPage
    v-if="shown"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :from-enroll="fromEnroll"
    @back="goBack"
    @login="openLogin"
  />
</template>
