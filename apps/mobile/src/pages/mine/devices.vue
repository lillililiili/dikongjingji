<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { accountToken, leaveAccountPage, onAccountUnauthorized } from '@/features/account/application/account-host'
import { DEVICES_PATH, PHONE_PATH } from '@/features/account/navigation/account-routes'
import DevicesPage from '@/features/account/presentation/security/DevicesPage.vue'
import { buildSupportOpenUrl } from '@/features/support/navigation/support-routes'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// 登录设备管理 (S5-8).
const token = ref<string | null>(null)

onLoad(() => {
  if (ACCOUNT_PREVIEW_ENABLED) token.value = accountToken(DEVICES_PATH)
})

function open(url: string): void {
  uni.navigateTo({ url })
}

function redirect(url: string): void {
  uni.redirectTo({ url })
}
</script>

<template>
  <DevicesPage
    v-if="ACCOUNT_PREVIEW_ENABLED && token"
    :access-token="token"
    @back="leaveAccountPage()"
    @phone="open(PHONE_PATH)"
    @support="open(buildSupportOpenUrl({ kind: 'PLATFORM' }))"
    @unauthorized="onAccountUnauthorized"
  />
  <SupportUnavailable v-else-if="!ACCOUNT_PREVIEW_ENABLED" text="登录设备管理尚未开通" />
</template>
