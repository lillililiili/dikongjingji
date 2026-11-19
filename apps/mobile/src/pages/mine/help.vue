<script setup lang="ts">
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { MINE_TAB_PATH, buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { createSupportKey } from '@/features/support/application/chat-view'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { supportApi } from '@/features/support/data/support-api-client'
import type { SupportFeedbackType } from '@/features/support/domain/support'
import { SUPPORT_HELP_PATH, buildSupportOpenUrl } from '@/features/support/navigation/support-routes'
import SupportHelpPage from '@/features/support/presentation/help/SupportHelpPage.vue'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// Open to guests (board MineGuest): the questions need no account; feedback and the platform
// support ask for a login first.
const sent = ref(false)
const submitting = ref(false)
const helpPage = ref<InstanceType<typeof SupportHelpPage> | null>(null)
// One key per distinct feedback: tapping 提交 again after a timeout cannot record it twice.
let lastSignature = ''
let lastKey = ''

function goBack(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: MINE_TAB_PATH })
}

function openLogin(): void {
  uni.navigateTo({ url: buildInspectionLoginUrl(SUPPORT_HELP_PATH) })
}

function openFeedback(): void {
  sent.value = false
  if (!getBookingAuthContext().getAccessToken()) openLogin()
}

async function submitFeedback(type: SupportFeedbackType, text: string): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token) {
    openLogin()
    return
  }
  const signature = `${type}|${text}`
  if (signature !== lastSignature) {
    lastSignature = signature
    lastKey = createSupportKey()
  }
  submitting.value = true
  try {
    await supportApi.submitFeedback(token, lastKey, type, text)
    lastSignature = ''
    sent.value = true
    helpPage.value?.reset()
  } catch (error) {
    const status = (error as { status?: number }).status
    if (status === 401) openLogin()
    else uni.showToast({ title: '没有提交成功，请稍后重试', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

// Complaints go to the platform support too (design assumption 18); the chat asks for a login.
function contactPlatform(): void {
  uni.navigateTo({ url: buildSupportOpenUrl({ kind: 'PLATFORM' }) })
}
</script>

<template>
  <SupportHelpPage
    v-if="SUPPORT_PREVIEW_ENABLED"
    ref="helpPage"
    :sent="sent"
    :submitting="submitting"
    @back="goBack"
    @open-feedback="openFeedback"
    @submit-feedback="submitFeedback"
    @contact-platform="contactPlatform"
  />
  <SupportUnavailable v-else text="帮助与反馈尚未开通" />
</template>
