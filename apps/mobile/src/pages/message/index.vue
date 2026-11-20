<script setup lang="ts">
import { onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import { ROOT_TABS } from '@/app-shell/navigation/root-tabs'
import { messageTargetUrl } from '@/app-shell/navigation/message-destination'
import { useRootTabBadgeStore } from '@/app-shell/state/root-tab-badges'
import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import {
  createMessageCenter,
  type MessageCenterState,
} from '@/features/message/application/message-center'
import { messageApi } from '@/features/message/data/message-api-client'
import type { MessageItem } from '@/features/message/domain/message'
import MessageCenterPage from '@/features/message/presentation/MessageCenterPage.vue'
import type { MessageConversationRow, MessageSquare } from '@/features/message/presentation/message-format'
import { toConversationRow } from '@/features/support/application/chat-view'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { supportApi } from '@/features/support/data/support-api-client'
import { buildSupportChatUrl, buildSupportOpenUrl } from '@/features/support/navigation/support-routes'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'

const MESSAGE_PATH = ROOT_TABS.find((tab) => tab.code === 'MESSAGE')?.path ?? '/pages/message/index'

// 交易物流 and 活动提醒 (R6-5) come with the trade and activity / venue previews; public builds keep
// the 服务进度 square only.
const squares: MessageSquare[] = [
  ...(TRADE_PREVIEW_ENABLED ? ['TRADE_LOGISTICS' as const] : []),
  'SERVICE_PROGRESS',
  ...(ACTIVITY_PREVIEW_ENABLED || EXPERIENCE_PREVIEW_ENABLED ? ['ACTIVITY_REMINDER' as const] : []),
]

const badgeStore = useRootTabBadgeStore()
const state = ref<MessageCenterState>(
  getBookingAuthContext().getAccessToken() ? { kind: 'loading' } : { kind: 'guest' },
)

const center = createMessageCenter({
  api: messageApi,
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
  getState: () => state.value,
  setState: (next) => { state.value = next },
  onUnreadCount: (count) => badgeStore.setMessageUnread(count),
})

// 客服会话 (step G1, preview builds): the account's conversations, newest message first. The
// notifications below report a rejected login.
const conversations = ref<MessageConversationRow[] | null>(null)
let conversationRevision = 0

async function loadConversations(): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  const requested = ++conversationRevision
  if (!SUPPORT_PREVIEW_ENABLED || !token) {
    conversations.value = null
    return
  }
  try {
    const page = await supportApi.listConversations(token, 1, 20)
    if (requested !== conversationRevision) return
    const now = new Date()
    conversations.value = page.items.map((item) => toConversationRow(item, now))
  } catch {
    // Rows already on screen stay; a list never read stays hidden.
  }
}

function openConversation(conversationId: string): void {
  uni.navigateTo({ url: buildSupportChatUrl(conversationId) })
}

function contactPlatform(): void {
  uni.navigateTo({ url: buildSupportOpenUrl({ kind: 'PLATFORM' }) })
}

// A notification only carries stable ids; the page it opens reads the facts from the server. One
// whose target this version does not know is only marked read.
function openMessage(message: MessageItem): void {
  void center.markRead(message.id)
  const url = messageTargetUrl(message.target)
  if (url) uni.navigateTo({ url })
}

function openLogin(): void {
  uni.navigateTo({ url: buildInspectionLoginUrl(MESSAGE_PATH) })
}

onShow(() => {
  void center.load()
  void loadConversations()
})

onReachBottom(() => {
  void center.loadMore()
})
</script>

<template>
  <view>
    <MessageCenterPage
      :state="state"
      :conversations="conversations"
      :squares="squares"
      @login="openLogin"
      @retry="center.load()"
      @open="openMessage"
      @mark-all-read="center.markAllRead()"
      @load-more="center.loadMore()"
      @open-conversation="openConversation"
      @contact-platform="contactPlatform"
    />
    <RootAppTabBar active="MESSAGE" />
  </view>
</template>
