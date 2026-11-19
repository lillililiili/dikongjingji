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

// Opened from a form (activity sign-up or venue booking): adding someone offers the way back to it.
const returnLabel = ref<string | null>(null)
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
  if (query?.from === 'enroll') returnLabel.value = '返回报名填写'
  if (query?.from === 'venue') returnLabel.value = '返回预约确认'
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
    :return-label="returnLabel"
    @back="goBack"
    @login="openLogin"
  />
</template>
