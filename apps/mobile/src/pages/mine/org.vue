<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { accountToken, leaveAccountPage, onAccountUnauthorized } from '@/features/account/application/account-host'
import { ORG_PATH, buildOrgInviteUrl, buildOrgMembersUrl } from '@/features/account/navigation/account-routes'
import OrgPage from '@/features/account/presentation/org/OrgPage.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// 企业组织 (S5-7).
const token = ref<string | null>(null)
const enterpriseId = ref<string | null>(null)
const city = useCurrentCityStore()

onLoad((query?: Record<string, string | undefined>) => {
  if (!ACCOUNT_PREVIEW_ENABLED) return
  enterpriseId.value = query?.enterpriseId?.trim() || null
  token.value = accountToken(ORG_PATH)
})

function open(url: string): void {
  uni.navigateTo({ url })
}

function redirect(url: string): void {
  uni.redirectTo({ url })
}
</script>

<template>
  <OrgPage
    v-if="ACCOUNT_PREVIEW_ENABLED && token"
    :access-token="token"
    :enterprise-id="enterpriseId"
    :city-code="city.currentCity?.code ?? null"
    :city-name="city.currentCity?.name ?? null"
    @back="leaveAccountPage('mine')"
    @members="(id: string) => open(buildOrgMembersUrl(id))"
    @invitation="(id: string) => open(buildOrgInviteUrl(id))"
    @unauthorized="onAccountUnauthorized"
  />
  <SupportUnavailable v-else-if="!ACCOUNT_PREVIEW_ENABLED" text="企业组织尚未开通" />
</template>
