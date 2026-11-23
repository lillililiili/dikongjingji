<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { recordPrivacyConsent } from '@/features/account/application/onboarding-consent'
import OnboardingPage from '@/features/account/presentation/security/OnboardingPage.vue'
import type { AgreementDoc } from '@/features/settings/domain/agreement'
import { buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// 首次启动 (S5-8): the privacy consent, then why location helps. Opened by the home page once on a
// built preview, or from 设置 → 新手引导. Either way it ends on the home page.
const HOME = '/pages/home/index'
const fromSettings = ref(false)

onLoad((query?: Record<string, string | undefined>) => {
  fromSettings.value = query?.from === 'settings'
})

function goHome(toast?: string): void {
  uni.reLaunch({ url: HOME })
  if (toast) setTimeout(() => uni.showToast({ title: toast, icon: 'none' }), 300)
}

function locate(): void {
  // The system asks; whatever the answer, the home page picks the city as it always does.
  uni.getLocation({
    type: 'gcj02',
    success: () => goHome(),
    fail: () => goHome('没有拿到位置，可以在首页左上角选择城市'),
  })
}

function openAgreement(doc: AgreementDoc): void {
  uni.navigateTo({ url: buildAgreementUrl(doc) })
}

function quit(): void {
  // #ifdef APP-PLUS
  plus.runtime.quit()
  // #endif
  uni.showToast({ title: '需要同意后才能使用，下次打开时会再次询问', icon: 'none' })
}
</script>

<template>
  <OnboardingPage
    v-if="ACCOUNT_PREVIEW_ENABLED"
    :from-settings="fromSettings"
    @agreed="recordPrivacyConsent()"
    @agreement="openAgreement"
    @locate="locate"
    @manual-city="goHome('可以在首页左上角选择城市')"
    @quit="quit"
  />
  <SupportUnavailable v-else text="新手引导尚未开通" />
</template>
