<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { noticeLinkDestination } from '@/app-shell/navigation/notice-link-destination'
import { ROOT_TABS } from '@/app-shell/navigation/root-tabs'
import { SYSTEM_NOTICE_PREVIEW_ENABLED } from '@/features/message/application/notice-preview'
import { notificationExtrasApi } from '@/features/message/data/notification-extras-api-client'
import type { SystemNoticeLink } from '@/features/message/domain/system-notice'
import { parseNoticeId } from '@/features/message/navigation/message-routes'
import NoticeDetailPage from '@/features/message/presentation/NoticeDetailPage.vue'
import type { NoticeDetailState } from '@/features/message/presentation/notice-format'
import { buildSupportOpenUrl } from '@/features/support/navigation/support-routes'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

const MESSAGE_PATH = ROOT_TABS.find((tab) => tab.code === 'MESSAGE')?.path ?? '/pages/message/index'

// Readable without signing in: an announcement carries no personal data, and 协议与规则 opens it before
// an account exists. Only the id comes in the link; the notice is read from the server.
const state = ref<NoticeDetailState>({ kind: 'loading' })
let noticeId: string | null = null
let revision = 0

async function load(): Promise<void> {
  const requested = ++revision
  if (!noticeId) {
    state.value = { kind: 'missing' }
    return
  }
  state.value = { kind: 'loading' }
  try {
    const notice = await notificationExtrasApi.readSystemNotice(noticeId)
    if (requested === revision) state.value = { kind: 'ready', notice }
  } catch (error) {
    if (requested !== revision) return
    state.value = { kind: (error as { status?: number }).status === 404 ? 'missing' : 'error' }
  }
}

onLoad((query) => {
  noticeId = parseNoticeId(query?.id)
  if (SYSTEM_NOTICE_PREVIEW_ENABLED) void load()
})

function goBack(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.switchTab({ url: MESSAGE_PATH })
}

function open(link: SystemNoticeLink): void {
  const destination = noticeLinkDestination(link)
  if (destination.kind === 'page') uni.navigateTo({ url: destination.url })
  else uni.showToast({ title: destination.message, icon: 'none' })
}

// The chat asks for a login itself when there is none.
function contactPlatform(): void {
  uni.navigateTo({ url: buildSupportOpenUrl({ kind: 'PLATFORM' }) })
}
</script>

<template>
  <NoticeDetailPage
    v-if="SYSTEM_NOTICE_PREVIEW_ENABLED"
    :state="state"
    @back="goBack"
    @retry="load"
    @open="open"
    @contact-platform="contactPlatform"
  />
  <SupportUnavailable v-else text="系统通知尚未开通" />
</template>
