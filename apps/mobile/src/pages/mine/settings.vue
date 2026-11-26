<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { confirmSignOut } from '@/features/account/application/confirm-sign-out'
import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { addressApi } from '@/features/account/data/address-api-client'
import { identityAccountApi } from '@/features/account/data/identity-account-api-client'
import type { AccountClosure, PersonalPasswordStatus } from '@/features/account/domain/identity-account'
import {
  DATA_EXPORT_PATH,
  DEVICES_PATH,
  PERMISSIONS_PATH,
  PASSWORD_PATH,
  PHONE_PATH,
  buildOnboardingUrl,
} from '@/features/account/navigation/account-routes'
import AccountClosureSheet from '@/features/account/presentation/security/AccountClosureSheet.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { inspectionAccountApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import { MINE_TAB_PATH, buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildAddressBookUrl } from '@/features/mall/navigation/mall-routes'
import { CART_PREVIEW_ENABLED } from '@/features/mall/application/cart-preview'
import { SYSTEM_NOTICE_PREVIEW_ENABLED } from '@/features/message/application/notice-preview'
import { createNotificationSwitches } from '@/features/message/application/notification-switches'
import { notificationExtrasApi } from '@/features/message/data/notification-extras-api-client'
import type { NotificationPreferences, NotificationSwitch } from '@/features/message/domain/notification-preferences'
import { SETTINGS_PREVIEW_ENABLED } from '@/features/settings/application/settings-preview'
import type { AgreementDoc } from '@/features/settings/domain/agreement'
import { ABOUT_PATH, SETTINGS_PATH, buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import SettingsPage from '@/features/settings/presentation/SettingsPage.vue'
import { buildSupportHelpUrl } from '@/features/support/navigation/support-routes'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

const account = ref<{ displayName: string; organizationName: string | null; maskedPhone?: string | null } | null>(null)
const addressCount = ref<number | null>(null)
const passwordStatus = ref<PersonalPasswordStatus | null>(null)
const passwordLoadFailed = ref(false)
// 注销账户 (S5-8): the conditions and a running request, read with the page; the sheet shows them.
const closure = ref<AccountClosure | null>(null)
const closureOpen = ref(false)
const closureBusy = ref(false)
interface SettingsSession {
  owner: string
  closureRead?: { version: number; result: Promise<AccountClosure> }
  closureVersion: number
}
let session: SettingsSession | null = null
let loadVersion = 0

// 通知 switches (S5-9, preview builds): read with the page, each tap saved on its own.
const notifications = ref<NotificationPreferences | null>(null)
function currentAccount(): string | null {
  const auth = getBookingAuthContext()
  const token = auth.getAccessToken()
  return token ? auth.getPrincipalId() ?? token : null
}
const switches = createNotificationSwitches({
  api: notificationExtrasApi,
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
  getAccountKey: currentAccount,
  get: () => notifications.value,
  set: (next) => { notifications.value = next },
  onSaveFailed: () => uni.showToast({ title: '设置没有保存，请稍后重试', icon: 'none' }),
})

function toggleNotification(key: NotificationSwitch): void {
  void switches.toggle(key)
}

function isCurrent(s: SettingsSession): boolean {
  return session === s && currentAccount() === s.owner
}

function readClosure(s: SettingsSession, token: string): Promise<AccountClosure> {
  const version = s.closureVersion
  if (s.closureRead?.version === version) return s.closureRead.result
  const result = identityAccountApi.readClosure(token).finally(() => {
    if (s.closureRead?.version === version) s.closureRead = undefined
  })
  s.closureRead = { version, result }
  return result
}

// Settings belong to an account: without one the page goes to sign in and comes back.
async function load(): Promise<void> {
  const requested = ++loadVersion
  const token = getBookingAuthContext().getAccessToken()
  const owner = currentAccount()
  if (owner !== (session?.owner ?? null)) {
    session = owner ? { owner, closureVersion: 0 } : null
    account.value = null
    addressCount.value = null
    passwordStatus.value = null
    passwordLoadFailed.value = false
    closure.value = null
    closureOpen.value = false
    closureBusy.value = false
  }
  if (SYSTEM_NOTICE_PREVIEW_ENABLED) void switches.load()
  const s = session
  if (!token || !s) {
    uni.redirectTo({ url: buildInspectionLoginUrl(SETTINGS_PATH) })
    return
  }
  const closureVersion = s.closureVersion
  const [loadedAccount, addresses, loadedClosure, loadedPassword] = await Promise.allSettled([
    inspectionAccountApi.loadCurrent(token),
    CART_PREVIEW_ENABLED ? addressApi.list(token) : Promise.resolve(null),
    ACCOUNT_PREVIEW_ENABLED ? readClosure(s, token) : Promise.resolve(null),
    ACCOUNT_PREVIEW_ENABLED ? identityAccountApi.passwordStatus(token) : Promise.resolve(null),
  ])
  if (requested !== loadVersion || !isCurrent(s)) return
  passwordStatus.value = loadedPassword.status === 'fulfilled' ? loadedPassword.value : null
  passwordLoadFailed.value = loadedPassword.status === 'rejected'
  if (loadedAccount.status === 'fulfilled') {
    account.value = {
      displayName: loadedAccount.value.displayName || '已登录',
      organizationName: loadedAccount.value.organizationName,
      maskedPhone: loadedAccount.value.maskedPhone ?? null,
    }
  } else account.value = null
  addressCount.value = addresses.status === 'fulfilled' && addresses.value ? addresses.value.length : null
  if (s.closureVersion === closureVersion && !closureBusy.value) {
    closure.value = loadedClosure.status === 'fulfilled' ? loadedClosure.value : null
  }
}

function goBack(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: MINE_TAB_PATH })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

async function openClosure(): Promise<void> {
  const s = session
  if (!s || !isCurrent(s)) return
  if (!closure.value) {
    const token = getBookingAuthContext().getAccessToken()
    if (!token) return
    const version = s.closureVersion
    try {
      const value = await readClosure(s, token)
      if (!isCurrent(s) || version !== s.closureVersion) return
      closure.value = value
    } catch (reason) {
      if (!isCurrent(s)) return
      uni.showToast({ title: reason instanceof Error ? reason.message : '注销条件读取失败，请重试', icon: 'none' })
      return
    }
  }
  closureOpen.value = true
}

async function changeClosure(action: 'apply' | 'withdraw'): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  const s = session
  if (!token || !s || !isCurrent(s) || closureBusy.value) return
  s.closureVersion++
  closureBusy.value = true
  let reload = false
  try {
    const value = action === 'apply'
      ? await identityAccountApi.requestClosure(token)
      : await identityAccountApi.withdrawClosure(token)
    if (!isCurrent(s)) return
    closure.value = value
    uni.showToast({ title: action === 'apply' ? '已提交注销申请' : '已撤回注销申请', icon: 'none' })
  } catch (error) {
    if (!isCurrent(s)) return
    uni.showToast({ title: error instanceof Error ? error.message : '操作失败，请重试', icon: 'none' })
    reload = true
  } finally {
    if (isCurrent(s)) {
      s.closureVersion++
      closureBusy.value = false
      if (reload) void load()
    }
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
    :addresses-enabled="CART_PREVIEW_ENABLED"
    :help-enabled="SUPPORT_PREVIEW_ENABLED"
    :notifications="notifications"
    @back="goBack"
    @addresses="open(buildAddressBookUrl(false))"
    @agreement="(doc: AgreementDoc) => open(buildAgreementUrl(doc))"
    @help="open(buildSupportHelpUrl())"
    @about="open(ABOUT_PATH)"
    @logout="confirmSignOut"
    :account-pages="ACCOUNT_PREVIEW_ENABLED"
    :password-status="passwordStatus"
    :password-load-failed="passwordLoadFailed"
    :closure-tag="closure?.status === 'PENDING' ? '处理中' : null"
    @phone="open(PHONE_PATH)"
    @password="open(PASSWORD_PATH)"
    @devices="open(DEVICES_PATH)"
    @data="open(DATA_EXPORT_PATH)"
    @permissions="open(PERMISSIONS_PATH)"
    @onboarding="open(buildOnboardingUrl('settings'))"
    @closure="openClosure"
    @toggle-notification="toggleNotification"
  />
  <SupportUnavailable v-else text="设置尚未开通" />
  <!-- After the v-if / v-else pair, so 设置尚未开通 hangs off the settings page, not this sheet. -->
  <AccountClosureSheet
    v-if="SETTINGS_PREVIEW_ENABLED && ACCOUNT_PREVIEW_ENABLED && closureOpen && closure"
    :closure="closure"
    :busy="closureBusy"
    @close="closureOpen = false"
    @apply="changeClosure('apply')"
    @withdraw="changeClosure('withdraw')"
  />
</template>
