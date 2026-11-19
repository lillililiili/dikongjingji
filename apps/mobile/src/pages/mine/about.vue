<script setup lang="ts">
import { MINE_TAB_PATH } from '@/features/inspection/navigation/inspection-routes'
import { SETTINGS_PREVIEW_ENABLED, readAppVersion } from '@/features/settings/application/settings-preview'
import type { AgreementDoc } from '@/features/settings/domain/agreement'
import { buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import AboutPage from '@/features/settings/presentation/AboutPage.vue'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// Open to guests (board MineGuest): nothing here needs an account.
const version = readAppVersion()

function openAgreement(doc: AgreementDoc): void {
  uni.navigateTo({ url: buildAgreementUrl(doc) })
}

function goBack(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: MINE_TAB_PATH })
}
</script>

<template>
  <AboutPage
    v-if="SETTINGS_PREVIEW_ENABLED"
    :version="version"
    @back="goBack"
    @agreement="openAgreement"
  />
  <SupportUnavailable v-else text="关于页面尚未开通" />
</template>
