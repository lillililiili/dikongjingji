<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { accountToken, leaveAccountPage, loginAfterPasswordUpdate, onAccountUnauthorized } from '@/features/account/application/account-host'
import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { PASSWORD_PATH } from '@/features/account/navigation/account-routes'
import PersonalPasswordForm from '@/features/account/presentation/security/PersonalPasswordForm.vue'
import { SETTINGS_PATH } from '@/features/settings/navigation/settings-routes'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'

const token = ref<string | null>(null)
onLoad(() => {
  if (ACCOUNT_PREVIEW_ENABLED) token.value = accountToken(PASSWORD_PATH)
})
</script>

<template>
  <view v-if="ACCOUNT_PREVIEW_ENABLED" class="password-page">
    <AppPageHeader title="登录密码" test-id="password" @back="leaveAccountPage()" />
    <PersonalPasswordForm v-if="token" class="password-page__body" :token="token" @saved="leaveAccountPage()" @signed-out="loginAfterPasswordUpdate(SETTINGS_PATH)" @unauthorized="onAccountUnauthorized" />
  </view>
  <SupportUnavailable v-else text="个人密码设置尚未开通" />
</template>

<style scoped>
.password-page { min-height: 100vh; background: #f5f7fc; }
.password-page__body { width: 100%; max-width: 600px; margin: 0 auto; padding: 24px 16px calc(24px + env(safe-area-inset-bottom)); box-sizing: border-box; }
</style>
