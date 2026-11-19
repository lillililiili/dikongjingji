<script setup lang="ts">
import { onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import { ROOT_TABS } from '@/app-shell/navigation/root-tabs'
import { useRootTabBadgeStore } from '@/app-shell/state/root-tab-badges'
import { buildDemandQuotesUrl } from '@/features/demand/navigation/demand-routes'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  buildInspectionDeliveryUrl,
  buildInspectionFulfillmentUrl,
  buildInspectionLoginUrl,
  buildInspectionPlanConfirmationUrl,
} from '@/features/inspection/navigation/inspection-routes'
import {
  createMessageCenter,
  type MessageCenterState,
} from '@/features/message/application/message-center'
import { messageApi } from '@/features/message/data/message-api-client'
import type { MessageItem } from '@/features/message/domain/message'
import MessageCenterPage from '@/features/message/presentation/MessageCenterPage.vue'
import type { MessageConversationRow } from '@/features/message/presentation/message-format'
import { toConversationRow } from '@/features/support/application/chat-view'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { supportApi } from '@/features/support/data/support-api-client'
import { buildSupportChatUrl, buildSupportOpenUrl } from '@/features/support/navigation/support-routes'

const MESSAGE_PATH = ROOT_TABS.find((tab) => tab.code === 'MESSAGE')?.path ?? '/pages/message/index'

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

// A notification only carries stable ids; the page it opens reads the facts from the server.
function targetUrl(message: MessageItem): string {
  const { type, id, subId } = message.target
  // Someone replied in a conversation: the chat reads its messages and marks them read.
  if (type === 'CONVERSATION') return buildSupportChatUrl(id)
  // A quote came in, or the demand closed without any: its quotes page says which.
  if (type === 'DEMAND') return buildDemandQuotesUrl(id)
  if (type === 'INSPECTION_PLAN') return buildInspectionPlanConfirmationUrl(id)
  if (type === 'INSPECTION_DELIVERY' && subId) return buildInspectionDeliveryUrl(id, subId)
  return buildInspectionFulfillmentUrl(id)
}

function openMessage(message: MessageItem): void {
  void center.markRead(message.id)
  uni.navigateTo({ url: targetUrl(message) })
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
