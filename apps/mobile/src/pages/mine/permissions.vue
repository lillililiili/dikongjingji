<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { leaveAccountPage } from '@/features/account/application/account-host'
import { readPermissionStates, type PermissionCode, type PermissionState } from '@/features/account/application/app-permissions'
import PermissionsPage from '@/features/account/presentation/security/PermissionsPage.vue'
import { SETTINGS_PATH, buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// 权限管理 (S5-8): static explanations plus the system's state, read again whenever the page shows
// (the user may come back from the system settings). Needs no account.
const states = ref<Record<PermissionCode, PermissionState>>(readPermissionStates())

onShow(() => {
  states.value = readPermissionStates()
})

function open(url: string): void {
  uni.navigateTo({ url })
}

function redirect(url: string): void {
  uni.redirectTo({ url })
}
</script>

<template>
  <PermissionsPage
    v-if="ACCOUNT_PREVIEW_ENABLED"
    :states="states"
    @back="leaveAccountPage()"
    @notifications="open(SETTINGS_PATH)"
    @privacy="open(buildAgreementUrl('PRIVACY_POLICY'))"
  />
  <SupportUnavailable v-else text="权限管理尚未开通" />
</template>
