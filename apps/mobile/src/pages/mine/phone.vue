<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { accountToken, leaveAccountPage } from '@/features/account/application/account-host'
import { DEVICES_PATH, PHONE_PATH } from '@/features/account/navigation/account-routes'
import PhoneChangePage from '@/features/account/presentation/security/PhoneChangePage.vue'
import { inspectionAccountApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import { buildSupportOpenUrl } from '@/features/support/navigation/support-routes'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// 更换手机号 / 绑定手机号 (S5-8): which one depends on whether the account has a number.
const token = ref<string | null>(null)
const maskedPhone = ref<string | null | undefined>(undefined)

onLoad(async () => {
  if (!ACCOUNT_PREVIEW_ENABLED) return
  token.value = accountToken(PHONE_PATH)
  if (!token.value) return
  try {
    maskedPhone.value = (await inspectionAccountApi.loadCurrent(token.value)).maskedPhone ?? null
  } catch {
    maskedPhone.value = null
  }
})

function open(url: string): void {
  uni.navigateTo({ url })
}

function redirect(url: string): void {
  uni.redirectTo({ url })
}
</script>

<template>
  <PhoneChangePage
    v-if="ACCOUNT_PREVIEW_ENABLED && token && maskedPhone !== undefined"
    :access-token="token"
    :masked-phone="maskedPhone"
    @back="leaveAccountPage()"
    @devices="redirect(DEVICES_PATH)"
    @support="open(buildSupportOpenUrl({ kind: 'PLATFORM' }))"
  />
  <SupportUnavailable v-else-if="!ACCOUNT_PREVIEW_ENABLED" text="更换手机号尚未开通" />
</template>
