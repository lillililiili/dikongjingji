<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { useRootTabBadgeStore } from '@/app-shell/state/root-tab-badges'
import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { addressApi } from '@/features/account/data/address-api-client'
import { identityAccountApi } from '@/features/account/data/identity-account-api-client'
import type { AccountClosure } from '@/features/account/domain/identity-account'
import {
  DATA_EXPORT_PATH,
  DEVICES_PATH,
  PERMISSIONS_PATH,
  PHONE_PATH,
  buildOnboardingUrl,
} from '@/features/account/navigation/account-routes'
import AccountClosureSheet from '@/features/account/presentation/security/AccountClosureSheet.vue'
import {
  clearBookingAuthSession,
  getBookingAuthContext,
} from '@/features/inspection/application/service-flow/booking-auth-context'
import { inspectionAccountApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import { MINE_TAB_PATH, buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildAddressBookUrl } from '@/features/mall/navigation/mall-routes'
import { SETTINGS_PREVIEW_ENABLED } from '@/features/settings/application/settings-preview'
import type { AgreementDoc } from '@/features/settings/domain/agreement'
import { ABOUT_PATH, SETTINGS_PATH, buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import SettingsPage from '@/features/settings/presentation/SettingsPage.vue'
import { buildSupportHelpUrl } from '@/features/support/navigation/support-routes'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

const account = ref<{ displayName: string; organizationName: string | null; maskedPhone?: string | null } | null>(null)
const addressCount = ref<number | null>(null)
// 注销账户 (S5-8): the conditions and a running request, read with the page; the sheet shows them.
const closure = ref<AccountClosure | null>(null)
const closureOpen = ref(false)
const closureBusy = ref(false)
const badges = useRootTabBadgeStore()

// Settings belong to an account: without one the page goes to sign in and comes back.
async function load(): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token) {
    uni.redirectTo({ url: buildInspectionLoginUrl(SETTINGS_PATH) })
    return
  }
  const [loadedAccount, addresses, loadedClosure] = await Promise.allSettled([
    inspectionAccountApi.loadCurrent(token),
    addressApi.list(token),
    ACCOUNT_PREVIEW_ENABLED ? identityAccountApi.readClosure(token) : Promise.resolve(null),
  ])
  if (loadedAccount.status === 'fulfilled') {
    account.value = {
      displayName: loadedAccount.value.displayName || '已登录',
      organizationName: loadedAccount.value.organizationName,
      maskedPhone: loadedAccount.value.maskedPhone ?? null,
    }
  }
  if (addresses.status === 'fulfilled') addressCount.value = addresses.value.length
  if (loadedClosure.status === 'fulfilled') closure.value = loadedClosure.value
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
      // This device's session is signed out on the server too (S5-8), so the token stops working at once.
      const token = getBookingAuthContext().getAccessToken()
      if (ACCOUNT_PREVIEW_ENABLED && token) void identityAccountApi.logout(token).catch(() => undefined)
      clearBookingAuthSession()
      badges.setMessageUnread(0)
      badges.setCartCount(0)
      uni.reLaunch({ url: MINE_TAB_PATH })
    },
  })
}

function openClosure(): void {
  if (!closure.value) {
    uni.showToast({ title: '注销条件暂时读取不到，请稍后再试', icon: 'none' })
    return
  }
  closureOpen.value = true
}

async function changeClosure(action: 'apply' | 'withdraw'): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token || closureBusy.value) return
  closureBusy.value = true
  try {
    closure.value = action === 'apply'
      ? await identityAccountApi.requestClosure(token)
      : await identityAccountApi.withdrawClosure(token)
    uni.showToast({ title: action === 'apply' ? '已提交注销申请' : '已撤回注销申请', icon: 'none' })
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '操作失败，请重试', icon: 'none' })
    void load()
  } finally {
    closureBusy.value = false
  }
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
    :account-pages="ACCOUNT_PREVIEW_ENABLED"
    :closure-tag="closure?.status === 'PENDING' ? '处理中' : null"
    @phone="open(PHONE_PATH)"
    @devices="open(DEVICES_PATH)"
    @data="open(DATA_EXPORT_PATH)"
    @permissions="open(PERMISSIONS_PATH)"
    @onboarding="open(buildOnboardingUrl('settings'))"
    @closure="openClosure"
  />
  <AccountClosureSheet
    v-if="SETTINGS_PREVIEW_ENABLED && ACCOUNT_PREVIEW_ENABLED && closureOpen && closure"
    :closure="closure"
    :busy="closureBusy"
    @close="closureOpen = false"
    @apply="changeClosure('apply')"
    @withdraw="changeClosure('withdraw')"
  />
  <SupportUnavailable v-else text="设置尚未开通" />
</template>
