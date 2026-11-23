<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { accountToken, leaveAccountPage, onAccountUnauthorized } from '@/features/account/application/account-host'
import { buildOrgInviteUrl, buildOrgUrl } from '@/features/account/navigation/account-routes'
import OrgInvitePage from '@/features/account/presentation/org/OrgInvitePage.vue'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// 加入组织邀请 (S5-7): opened from 企业组织 (phone invitations) or from an invitation link (with its
// code). Signing in first comes back here with the same link.
const token = ref<string | null>(null)
const invitationId = ref('')
const code = ref<string | null>(null)

onLoad((query?: Record<string, string | undefined>) => {
  if (!ACCOUNT_PREVIEW_ENABLED) return
  invitationId.value = query?.invitationId?.trim() ?? ''
  code.value = query?.code?.trim() || null
  if (!invitationId.value) {
    leaveAccountPage('mine')
    return
  }
  token.value = accountToken(buildOrgInviteUrl(invitationId.value, code.value))
})

function open(url: string): void {
  uni.navigateTo({ url })
}

function redirect(url: string): void {
  uni.redirectTo({ url })
}
</script>

<template>
  <OrgInvitePage
    v-if="ACCOUNT_PREVIEW_ENABLED && token && invitationId"
    :access-token="token"
    :invitation-id="invitationId"
    :code="code"
    @back="leaveAccountPage('mine')"
    @joined="(id: string) => redirect(buildOrgUrl(id))"
    @unauthorized="onAccountUnauthorized"
  />
  <SupportUnavailable v-else-if="!ACCOUNT_PREVIEW_ENABLED" text="企业组织尚未开通" />
</template>
