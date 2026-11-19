<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { MINE_TAB_PATH } from '@/features/inspection/navigation/inspection-routes'
import { SETTINGS_PREVIEW_ENABLED } from '@/features/settings/application/settings-preview'
import type { AgreementDoc } from '@/features/settings/domain/agreement'
import { parseAgreementDoc } from '@/features/settings/navigation/settings-routes'
import AgreementPage from '@/features/settings/presentation/AgreementPage.vue'
import { buildSupportOpenUrl } from '@/features/support/navigation/support-routes'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// Readable without signing in: the agreements are shown before an account exists.
const doc = ref<AgreementDoc>('PRIVACY_POLICY')

onLoad((query) => {
  doc.value = parseAgreementDoc(query?.doc)
})

function goBack(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: MINE_TAB_PATH })
}

// The chat asks for a login itself when there is none.
function contactPlatform(): void {
  uni.navigateTo({ url: buildSupportOpenUrl({ kind: 'PLATFORM' }) })
}
</script>

<template>
  <AgreementPage v-if="SETTINGS_PREVIEW_ENABLED" :selected="doc" @back="goBack" @contact-platform="contactPlatform" />
  <SupportUnavailable v-else text="协议正文待提供" />
</template>
