<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { MINE_TAB_PATH } from '@/features/inspection/navigation/inspection-routes'
import { SYSTEM_NOTICE_PREVIEW_ENABLED } from '@/features/message/application/notice-preview'
import { notificationExtrasApi } from '@/features/message/data/notification-extras-api-client'
import { buildNoticeDetailUrl } from '@/features/message/navigation/message-routes'
import { SETTINGS_PREVIEW_ENABLED } from '@/features/settings/application/settings-preview'
import type { AgreementDoc } from '@/features/settings/domain/agreement'
import { parseAgreementDoc } from '@/features/settings/navigation/settings-routes'
import AgreementPage from '@/features/settings/presentation/AgreementPage.vue'
import { buildSupportOpenUrl } from '@/features/support/navigation/support-routes'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// Readable without signing in: the agreements are shown before an account exists.
const doc = ref<AgreementDoc>('PRIVACY_POLICY')

// 有更新 · 查看本次更新摘要 (S5-9, preview builds): the latest 系统通知 about the document on screen,
// read once per document. A failed read shows no entry.
const updates = ref<Partial<Record<AgreementDoc, { noticeId: string } | null>>>({})
const asked = new Set<AgreementDoc>()

async function readUpdate(shown: AgreementDoc): Promise<void> {
  if (!SYSTEM_NOTICE_PREVIEW_ENABLED || asked.has(shown)) return
  asked.add(shown)
  try {
    const latest = await notificationExtrasApi.latestAgreementUpdate(shown)
    updates.value = { ...updates.value, [shown]: latest ? { noticeId: latest.noticeId } : null }
  } catch {
    asked.delete(shown)
  }
}

function openUpdate(noticeId: string): void {
  uni.navigateTo({ url: buildNoticeDetailUrl(noticeId) })
}

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
  <AgreementPage
    v-if="SETTINGS_PREVIEW_ENABLED"
    :selected="doc"
    :updates="updates"
    @back="goBack"
    @contact-platform="contactPlatform"
    @shown="readUpdate"
    @open-update="openUpdate"
  />
  <SupportUnavailable v-else text="协议正文待提供" />
</template>
