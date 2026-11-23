<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { accountToken, leaveAccountPage, onAccountUnauthorized } from '@/features/account/application/account-host'
import { buildOrgMembersUrl } from '@/features/account/navigation/account-routes'
import OrgMembersPage from '@/features/account/presentation/org/OrgMembersPage.vue'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// 成员与角色 (S5-7).
const token = ref<string | null>(null)
const enterpriseId = ref('')

onLoad((query?: Record<string, string | undefined>) => {
  if (!ACCOUNT_PREVIEW_ENABLED) return
  enterpriseId.value = query?.enterpriseId?.trim() ?? ''
  if (!enterpriseId.value) {
    leaveAccountPage('mine')
    return
  }
  token.value = accountToken(buildOrgMembersUrl(enterpriseId.value))
})
</script>

<template>
  <OrgMembersPage
    v-if="ACCOUNT_PREVIEW_ENABLED && token && enterpriseId"
    :access-token="token"
    :enterprise-id="enterpriseId"
    @back="leaveAccountPage('mine')"
    @unauthorized="onAccountUnauthorized"
  />
  <SupportUnavailable v-else-if="!ACCOUNT_PREVIEW_ENABLED" text="企业组织尚未开通" />
</template>
