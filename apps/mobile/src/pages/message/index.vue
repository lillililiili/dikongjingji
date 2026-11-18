<script setup lang="ts">
import { onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import { ROOT_TABS } from '@/app-shell/navigation/root-tabs'
import { useRootTabBadgeStore } from '@/app-shell/state/root-tab-badges'
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

// A notification only carries stable ids; the page it opens reads the facts from the server.
function targetUrl(message: MessageItem): string {
  const { type, id, subId } = message.target
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
})

onReachBottom(() => {
  void center.loadMore()
})
</script>

<template>
  <view>
    <MessageCenterPage
      :state="state"
      @login="openLogin"
      @retry="center.load()"
      @open="openMessage"
      @mark-all-read="center.markAllRead()"
      @load-more="center.loadMore()"
    />
    <RootAppTabBar active="MESSAGE" />
  </view>
</template>
