<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { accountToken, leaveAccountPage, onAccountUnauthorized } from '@/features/account/application/account-host'
import type { DataSectionCode } from '@/features/account/domain/identity-account'
import { identityLine } from '@/features/account/domain/identity-account'
import { DATA_EXPORT_PATH, ORG_PATH, PHONE_PATH } from '@/features/account/navigation/account-routes'
import DataExportPage from '@/features/account/presentation/security/DataExportPage.vue'
import { ACTIVITY_PARTICIPANTS_PATH } from '@/features/activity/navigation/activity-routes'
import { inspectionAccountApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import { buildAddressBookUrl } from '@/features/mall/navigation/mall-routes'
import { buildMyOrdersUrl } from '@/features/orders/application/my-orders-route'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// 个人信息查看与导出 (S5-8). Each stored section opens the page that already shows it.
const token = ref<string | null>(null)
const profile = ref<{ displayName: string; maskedPhone: string | null; cityName: string | null; identityText: string } | null>(null)
const city = useCurrentCityStore()

const SECTION_PAGES: Record<DataSectionCode, () => string> = {
  ACCOUNT: () => DATA_EXPORT_PATH,
  PARTICIPANTS: () => ACTIVITY_PARTICIPANTS_PATH,
  ADDRESSES: () => buildAddressBookUrl(false),
  ORDERS: () => buildMyOrdersUrl(),
  ENTERPRISES: () => ORG_PATH,
}

onLoad(async () => {
  if (!ACCOUNT_PREVIEW_ENABLED) return
  token.value = accountToken(DATA_EXPORT_PATH)
  if (!token.value) return
  try {
    const account = await inspectionAccountApi.loadCurrent(token.value)
    profile.value = {
      displayName: account.displayName || '已登录',
      maskedPhone: account.maskedPhone ?? null,
      cityName: city.currentCity?.name?.replace(/市$/, '') ?? null,
      identityText: account.identity ? identityLine(account.identity) ?? '个人' : '个人',
    }
  } catch {
    profile.value = { displayName: '已登录', maskedPhone: null, cityName: null, identityText: '个人' }
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
  <DataExportPage
    v-if="ACCOUNT_PREVIEW_ENABLED && token && profile"
    :access-token="token"
    :profile="profile"
    @back="leaveAccountPage()"
    @open="(code: DataSectionCode) => open(SECTION_PAGES[code]())"
    @phone="open(PHONE_PATH)"
    @unauthorized="onAccountUnauthorized"
  />
  <SupportUnavailable v-else-if="!ACCOUNT_PREVIEW_ENABLED" text="个人信息导出尚未开通" />
</template>
