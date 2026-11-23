<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { useRootTabBadgeStore } from '@/app-shell/state/root-tab-badges'
import { addressApi } from '@/features/account/data/address-api-client'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  inspectionAccountApi,
  signOutBookingSession,
} from '@/features/inspection/data/service-flow/uni-booking-http-client'
import { MINE_TAB_PATH, buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildAddressBookUrl } from '@/features/mall/navigation/mall-routes'
import { SETTINGS_PREVIEW_ENABLED } from '@/features/settings/application/settings-preview'
import type { AgreementDoc } from '@/features/settings/domain/agreement'
import { ABOUT_PATH, SETTINGS_PATH, buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import SettingsPage from '@/features/settings/presentation/SettingsPage.vue'
import { buildSupportHelpUrl } from '@/features/support/navigation/support-routes'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

const account = ref<{ displayName: string; organizationName: string | null } | null>(null)
const addressCount = ref<number | null>(null)
const badges = useRootTabBadgeStore()

// Settings belong to an account: without one the page goes to sign in and comes back.
async function load(): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token) {
    uni.redirectTo({ url: buildInspectionLoginUrl(SETTINGS_PATH) })
    return
  }
  const [loadedAccount, addresses] = await Promise.allSettled([
    inspectionAccountApi.loadCurrent(token),
    addressApi.list(token),
  ])
  if (loadedAccount.status === 'fulfilled') {
    account.value = { displayName: loadedAccount.value.displayName || '已登录', organizationName: loadedAccount.value.organizationName }
  }
  if (addresses.status === 'fulfilled') addressCount.value = addresses.value.length
}

function goBack(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: MINE_TAB_PATH })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

// Signing out forgets the session on this device; the server-side token simply expires.
function logout(): void {
  uni.showModal({
    title: '退出登录',
    content: '退出后需要重新登录才能查看订单、消息和会话',
    confirmText: '退出',
    cancelText: '取消',
    confirmColor: '#ff6a00',
    success: (result) => {
      if (!result.confirm) return
      // Withdraws the login on the server too (its refresh token), not only on this device.
      signOutBookingSession()
      badges.setMessageUnread(0)
      badges.setCartCount(0)
      uni.reLaunch({ url: MINE_TAB_PATH })
    },
  })
}

onShow(() => {
  if (SETTINGS_PREVIEW_ENABLED) void load()
})
</script>

<template>
  <SettingsPage
    v-if="SETTINGS_PREVIEW_ENABLED"
    :account="account"
    :address-count="addressCount"
    @back="goBack"
    @addresses="open(buildAddressBookUrl(false))"
    @agreement="(doc: AgreementDoc) => open(buildAgreementUrl(doc))"
    @help="open(buildSupportHelpUrl())"
    @about="open(ABOUT_PATH)"
    @logout="logout"
  />
  <SupportUnavailable v-else text="设置尚未开通" />
</template>
