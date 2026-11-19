<script setup lang="ts">
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { refreshMessageUnread } from '@/app-shell/state/root-tab-badges'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { MESSAGE_TAB_PATH, buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildProviderPageUrl } from '@/features/proservice/navigation/proservice-routes'
import { createChatSession, type ChatState } from '@/features/support/application/chat-session'
import { createSupportKey } from '@/features/support/application/chat-view'
import { supportCardTargetUrl } from '@/features/support/application/support-card'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { supportApi } from '@/features/support/data/support-api-client'
import type { SupportCard } from '@/features/support/domain/support'
import {
  buildSupportChatUrl,
  buildSupportHelpUrl,
  buildSupportOpenUrl,
  parseSupportChatQuery,
  type SupportChatQuery,
} from '@/features/support/navigation/support-routes'
import SupportChatPage from '@/features/support/presentation/chat/SupportChatPage.vue'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

// Replies come in through polling while the page is in front; there is no push channel.
const POLL_MS = 10_000

const state = ref<ChatState>({ kind: 'loading' })
const query = ref<SupportChatQuery | null>(null)
const cityStore = useCurrentCityStore()
let timer: ReturnType<typeof setInterval> | null = null
let leaving = false

const session = createChatSession({
  api: supportApi,
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
  getState: () => state.value,
  setState: (next) => {
    state.value = next
    if (next.kind === 'login') openLogin()
  },
  createKey: createSupportKey,
  onRead: () => { void refreshMessageUnread() },
})

function cityCode(): string | null {
  return cityStore.currentCity?.code ?? (import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim() || null)
}

function currentUrl(): string {
  const value = query.value
  if (!value) return MESSAGE_TAB_PATH
  return value.target
    ? buildSupportOpenUrl(value.target, value.card)
    : buildSupportChatUrl(value.conversationId, value.card)
}

function openLogin(): void {
  if (leaving) return
  leaving = true
  stopPolling()
  uni.redirectTo({ url: buildInspectionLoginUrl(currentUrl()) })
}

function stopPolling(): void {
  if (timer !== null) clearInterval(timer)
  timer = null
}

function startPolling(): void {
  stopPolling()
  timer = setInterval(() => { void session.refresh() }, POLL_MS)
}

async function start(): Promise<void> {
  const value = query.value
  if (!value) {
    state.value = { kind: 'missing' }
    return
  }
  if (!value.target) {
    await session.load(value.conversationId)
    if (!leaving) startPolling()
    return
  }
  // Opened by counterpart: open (or make) the conversation, then stand on its id so back, reload
  // and the login return all come to the same conversation.
  const conversationId = await session.open(value.target)
  if (conversationId && !leaving) {
    leaving = true
    uni.redirectTo({ url: buildSupportChatUrl(conversationId, value.card) })
  }
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.reLaunch({ url: MESSAGE_TAB_PATH })
}

function openCard(card: SupportCard): void {
  const url = supportCardTargetUrl(card, cityCode())
  if (url) uni.navigateTo({ url })
  else uni.showToast({ title: '请先选择所在城市', icon: 'none' })
}

function peerAction(kind: 'HELP' | 'PROVIDER'): void {
  if (kind === 'HELP') {
    uni.navigateTo({ url: buildSupportHelpUrl() })
    return
  }
  const current = state.value.kind === 'ready' ? state.value.conversation.counterpart : null
  const city = cityCode()
  if (current?.organizationId && city) uni.navigateTo({ url: buildProviderPageUrl(current.organizationId, city) })
}

// The merchant or provider cannot be reached: the platform support takes the same card.
function openPlatform(): void {
  leaving = true
  stopPolling()
  uni.redirectTo({ url: buildSupportOpenUrl({ kind: 'PLATFORM' }, query.value?.card ?? null) })
}

onLoad((options) => {
  query.value = parseSupportChatQuery(options as Record<string, unknown> | undefined)
})

onShow(() => {
  if (!SUPPORT_PREVIEW_ENABLED) return
  leaving = false
  void start()
})

onHide(stopPolling)
onUnload(() => {
  leaving = true
  stopPolling()
})
</script>

<template>
  <SupportChatPage
    v-if="SUPPORT_PREVIEW_ENABLED"
    :state="state"
    :card="query?.card ?? null"
    @back="goBack"
    @retry-load="start"
    @send-text="session.sendText"
    @send-card="session.sendCard"
    @retry-send="session.retry"
    @load-older="session.loadOlder"
    @open-card="openCard"
    @peer-action="peerAction"
    @open-platform="openPlatform"
  />
  <SupportUnavailable v-else text="客服会话尚未开通" />
</template>
