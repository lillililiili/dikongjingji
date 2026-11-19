<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, nextTick, ref, watch } from 'vue'

import type { ChatState } from '../../application/chat-session'
import { sendableSupportText } from '../../application/chat-session'
import { SUPPORT_CARD_ICONS, SUPPORT_CARD_LABELS } from '../../application/support-card'
import { buildChatLines, describeChatPeer, type ChatLine } from '../../application/chat-view'
import { SUPPORT_TEXT_LIMIT, type SupportCard, type SupportMessage } from '../../domain/support'

// One conversation (board Chat, 390 wide). The page host owns the session: it loads, polls,
// sends and marks read; this page draws the thread and forwards what the customer does.
const props = defineProps<{
  state: ChatState
  // The "关于什么" card the page was opened with; sent only when the customer taps 发送.
  card: SupportCard | null
}>()

const emit = defineEmits<{
  back: []
  retryLoad: []
  sendText: [text: string]
  sendCard: [card: SupportCard]
  retrySend: [localId: string]
  loadOlder: []
  openCard: [card: SupportCard]
  peerAction: [kind: 'HELP' | 'PROVIDER']
  openPlatform: []
}>()

const draft = ref('')
const scrollTarget = ref('')

const ready = computed(() => (props.state.kind === 'ready' ? props.state : null))
const peer = computed(() => (ready.value ? describeChatPeer(ready.value.conversation.counterpart) : null))
const lines = computed<ChatLine[]>(() =>
  // "今天 / 昨天" are read against the moment the lines are drawn.
  ready.value ? buildChatLines(ready.value.messages, ready.value.outgoing, new Date()) : [],
)
// The offered card, until it is sent in this visit or the customer already sent the same one.
const pendingCard = computed(() => {
  const current = ready.value
  const card = props.card
  if (!current || !card || current.cardSent) return null
  const alreadySent = current.messages.some((message) =>
    message.mine && message.card?.type === card.type && message.card.refId === card.refId)
  return alreadySent ? null : card
})
const canSend = computed(() => sendableSupportText(draft.value) !== null)

function onInput(event: Event): void {
  const value = (event as Event & { detail?: { value?: string } }).detail?.value ?? ''
  draft.value = String(value).slice(0, SUPPORT_TEXT_LIMIT)
}

function send(value?: string): void {
  const text = sendableSupportText(value ?? draft.value)
  if (!text) return
  emit('sendText', text)
  draft.value = ''
}

// The input's confirm event carries the value: v-model can lag behind on H5 (input is throttled).
function onConfirm(event: Event): void {
  send((event as Event & { detail?: { value?: string } }).detail?.value ?? draft.value)
}

function cardOf(message: SupportMessage): SupportCard | null {
  return message.kind === 'CARD' ? message.card : null
}

// Stay at the newest line when lines are added at the end; when older ones come in on top, keep
// the line that was first in view.
let lastKey = ''
let firstKey = ''
watch(lines, async (next) => {
  const newLast = next.length ? next[next.length - 1].key : ''
  const newFirst = next.length ? next[0].key : ''
  const target = firstKey && newFirst !== firstKey && newLast === lastKey ? firstKey : newLast
  lastKey = newLast
  firstKey = newFirst
  scrollTarget.value = ''
  await nextTick()
  scrollTarget.value = target
})
</script>

<template>
  <view class="support-chat" data-testid="support-chat" :data-state="state.kind">
    <view class="support-chat__header">
      <button class="support-chat__back" data-testid="support-chat-back" aria-label="返回" @click="emit('back')">
        <UniIcons type="left" size="22" color="#202c42" />
      </button>
      <view class="support-chat__peer">
        <text class="support-chat__title" data-testid="support-chat-title">{{ peer?.title ?? '客服' }}</text>
        <text v-if="peer" class="support-chat__sub">{{ peer.sub }}</text>
      </view>
      <button
        v-if="peer?.action"
        class="support-chat__peer-action"
        data-testid="support-chat-peer-action"
        @click="emit('peerAction', peer.action.kind)"
      >
        <text>{{ peer.action.label }}</text>
      </button>
    </view>

    <view v-if="state.kind === 'loading' || state.kind === 'login'" class="support-chat__state" aria-label="正在加载">
      <view class="support-chat__skeleton" />
      <view class="support-chat__skeleton support-chat__skeleton--right" />
    </view>

    <view v-else-if="state.kind === 'error'" class="support-chat__state" data-testid="support-chat-error">
      <text class="support-chat__state-title">会话没有加载出来，请稍后重试</text>
      <button class="support-chat__state-action" data-testid="support-chat-retry" @click="emit('retryLoad')">重新加载</button>
    </view>

    <view v-else-if="state.kind === 'missing'" class="support-chat__state" data-testid="support-chat-missing">
      <text class="support-chat__state-title">没有这个会话</text>
      <button class="support-chat__state-action" data-testid="support-chat-platform" @click="emit('openPlatform')">联系平台客服</button>
    </view>

    <view v-else-if="state.kind === 'unavailable'" class="support-chat__state" data-testid="support-chat-unavailable">
      <text class="support-chat__state-title">对方暂时不能在线沟通</text>
      <text class="support-chat__state-copy">可以先联系平台客服，我们协助你沟通</text>
      <button class="support-chat__state-action" data-testid="support-chat-platform" @click="emit('openPlatform')">联系平台客服</button>
    </view>

    <template v-else-if="ready && peer">
      <scroll-view
        class="support-chat__log"
        scroll-y
        role="log"
        aria-label="会话记录"
        :scroll-into-view="scrollTarget"
        data-testid="support-chat-log"
        @scrolltoupper="emit('loadOlder')"
      >
        <view class="support-chat__thread">
          <button
            v-if="ready.hasOlder"
            class="support-chat__older"
            data-testid="support-chat-older"
            :disabled="ready.loadingOlder"
            @click="emit('loadOlder')"
          >
            {{ ready.loadingOlder ? '正在加载…' : ready.olderFailed ? '加载失败，点此重试' : '查看更早的消息' }}
          </button>
          <view class="support-chat__note">
            <UniIcons type="info" size="13" color="#738096" />
            <text>{{ peer.note }}</text>
          </view>
          <template v-for="line in lines" :key="line.key">
            <text v-if="line.kind === 'TIME'" :id="line.key" class="support-chat__time">{{ line.text }}</text>
            <view
              v-else-if="line.kind === 'MESSAGE'"
              :id="line.key"
              :class="['support-chat__row', { 'support-chat__row--mine': line.message.mine }]"
              :data-testid="`support-message-${line.message.seq}`"
              :data-mine="line.message.mine"
            >
              <view v-if="!line.message.mine" class="support-chat__avatar">
                <UniIcons v-if="peer.initial === null" type="headphones" size="20" color="#ff6a00" />
                <text v-else>{{ peer.initial }}</text>
              </view>
              <button
                v-if="cardOf(line.message)"
                class="support-chat__card"
                :data-testid="`support-card-${line.message.seq}`"
                @click="emit('openCard', cardOf(line.message) as SupportCard)"
              >
                <view class="support-chat__card-icon">
                  <UniIcons :type="SUPPORT_CARD_ICONS[(cardOf(line.message) as SupportCard).type]" size="24" color="#ff6a00" />
                </view>
                <view class="support-chat__card-copy">
                  <text class="support-chat__card-title">{{ (cardOf(line.message) as SupportCard).title }}</text>
                  <text class="support-chat__card-sub">
                    {{ SUPPORT_CARD_LABELS[(cardOf(line.message) as SupportCard).type] }}{{ (cardOf(line.message) as SupportCard).subtitle ? ` · ${(cardOf(line.message) as SupportCard).subtitle}` : '' }}
                  </text>
                </view>
              </button>
              <text
                v-else
                :class="['support-chat__bubble', line.message.mine ? 'support-chat__bubble--mine' : 'support-chat__bubble--theirs']"
              >{{ line.message.text }}</text>
            </view>
            <view
              v-else
              :id="line.key"
              class="support-chat__row support-chat__row--mine support-chat__row--outgoing"
              data-testid="support-outgoing"
              :data-status="line.outgoing.status"
            >
              <button
                v-if="line.outgoing.status === 'FAILED'"
                class="support-chat__failed"
                data-testid="support-outgoing-retry"
                aria-label="发送失败，点击重试"
                @click="emit('retrySend', line.outgoing.localId)"
              >
                <UniIcons type="info-filled" size="18" color="#d64f67" />
              </button>
              <text v-else class="support-chat__sending">发送中</text>
              <view v-if="'card' in line.outgoing.body" class="support-chat__card support-chat__card--pending">
                <view class="support-chat__card-icon">
                  <UniIcons :type="SUPPORT_CARD_ICONS[line.outgoing.body.card.type]" size="24" color="#ff6a00" />
                </view>
                <view class="support-chat__card-copy">
                  <text class="support-chat__card-title">{{ line.outgoing.body.card.title }}</text>
                  <text class="support-chat__card-sub">{{ SUPPORT_CARD_LABELS[line.outgoing.body.card.type] }}</text>
                </view>
              </view>
              <text v-else class="support-chat__bubble support-chat__bubble--mine">{{ line.outgoing.body.text }}</text>
            </view>
          </template>
        </view>
      </scroll-view>

      <view v-if="pendingCard" class="support-chat__pin" data-testid="support-chat-pending-card">
        <button class="support-chat__pin-copy" @click="emit('openCard', pendingCard)">
          <view class="support-chat__pin-icon">
            <UniIcons :type="SUPPORT_CARD_ICONS[pendingCard.type]" size="22" color="#ff6a00" />
          </view>
          <view class="support-chat__card-copy">
            <text class="support-chat__card-title">{{ pendingCard.title }}</text>
            <text class="support-chat__card-sub">
              {{ SUPPORT_CARD_LABELS[pendingCard.type] }}{{ pendingCard.subtitle ? ` · ${pendingCard.subtitle}` : '' }}
            </text>
          </view>
        </button>
        <button class="support-chat__pin-send" data-testid="support-chat-send-card" @click="emit('sendCard', pendingCard)">发送</button>
      </view>

      <view class="support-chat__composer">
        <input
          class="support-chat__input"
          data-testid="support-chat-input"
          type="text"
          confirm-type="send"
          aria-label="输入消息"
          :value="draft"
          :maxlength="SUPPORT_TEXT_LIMIT"
          :placeholder="peer.placeholder"
          placeholder-class="support-chat__placeholder"
          @input="onInput"
          @confirm="onConfirm"
        />
        <button
          :class="['support-chat__send', { 'support-chat__send--off': !canSend }]"
          data-testid="support-chat-send"
          :disabled="!canSend"
          @click="send()"
        >
          发送
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Chat (390 wide). */
.support-chat {
  display: flex;
  height: 100vh;
  flex-direction: column;
  overflow: hidden;
  color: #202c42;
  background: #f5f7fc;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: normal;
  text-align: left;
}

button::after {
  border: 0;
}

.support-chat__header {
  display: flex;
  flex-shrink: 0;
  padding: calc(var(--status-bar-height) + 8px) 16px 8px 8px;
  align-items: center;
  gap: 4px;
  border-bottom: 1px solid #f0f1f3;
  /* The page head of the light theme (theme-page-head). */
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.support-chat__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
}

.support-chat__peer {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 1px;
}

.support-chat__title {
  overflow: hidden;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.support-chat__sub {
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.support-chat__peer-action {
  display: flex;
  height: 44px;
  flex-shrink: 0;
  align-items: center;
}

.support-chat__peer-action text {
  height: 32px;
  padding: 0 14px;
  box-sizing: border-box;
  border: 1px solid #e5ebf3;
  border-radius: 999px;
  color: #536178;
  background: #ffffff;
  font-size: 13px;
  line-height: 30px;
  white-space: nowrap;
}

.support-chat__log {
  min-height: 0;
  flex: 1;
}

.support-chat__thread {
  display: flex;
  padding: 14px 16px;
  flex-direction: column;
  gap: 14px;
}

.support-chat__older {
  align-self: center;
  min-height: 32px;
  padding: 0 12px;
  color: #536178;
  font-size: 12px;
}

.support-chat__note {
  display: flex;
  max-width: 100%;
  padding: 5px 10px;
  align-self: center;
  align-items: center;
  gap: 4px;
  box-sizing: border-box;
  border-radius: 8px;
  color: #627187;
  background: #eef0f3;
  font-size: 12px;
  line-height: 17px;
}

.support-chat__time {
  align-self: center;
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.support-chat__row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.support-chat__row--mine {
  justify-content: flex-end;
}

.support-chat__row--outgoing {
  align-items: center;
}

.support-chat__avatar {
  display: flex;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #ff6a00;
  background: #fff0e3;
  font-size: 15px;
  font-weight: 700;
}

.support-chat__bubble {
  max-width: 258px;
  padding: 9px 12px;
  box-sizing: border-box;
  font-size: 14px;
  line-height: 21px;
  white-space: pre-wrap;
  word-break: break-word;
}

.support-chat__bubble--theirs {
  border-radius: 4px 14px 14px 14px;
  background: #ffffff;
}

.support-chat__bubble--mine {
  border: 1px solid #ffd8b8;
  border-radius: 14px 4px 14px 14px;
  background: #fff0e3;
}

.support-chat__card {
  display: flex;
  width: 258px;
  max-width: calc(100% - 44px);
  padding: 10px;
  align-items: center;
  gap: 10px;
  box-sizing: border-box;
  border-radius: 12px;
  color: #202c42;
  background: #ffffff;
}

.support-chat__card--pending {
  opacity: 0.7;
}

.support-chat__card-icon,
.support-chat__pin-icon {
  display: flex;
  width: 52px;
  height: 52px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #fff0e3;
}

.support-chat__pin-icon {
  width: 44px;
  height: 44px;
}

.support-chat__card-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.support-chat__card-title {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.support-chat__card-sub {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.support-chat__sending {
  flex-shrink: 0;
  color: #91a0b4;
  font-size: 12px;
}

.support-chat__failed {
  display: flex;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
}

.support-chat__pin {
  display: flex;
  flex-shrink: 0;
  margin: 0 16px 8px;
  padding: 8px 10px;
  align-items: center;
  gap: 10px;
  border-radius: 12px;
  background: #ffffff;
}

.support-chat__pin-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 10px;
  color: #202c42;
}

.support-chat__pin-send {
  height: 36px;
  flex-shrink: 0;
  padding: 0 16px;
  box-sizing: border-box;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
}

.support-chat__composer {
  display: flex;
  flex-shrink: 0;
  padding: 8px 16px calc(24px + env(safe-area-inset-bottom)) 16px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.support-chat__input {
  min-width: 0;
  height: 40px;
  flex: 1;
  padding: 0 14px;
  box-sizing: border-box;
  border-radius: 999px;
  color: #202c42;
  background: #eef2f8;
  font-size: 14px;
}

.support-chat__send {
  height: 36px;
  flex-shrink: 0;
  padding: 0 16px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
  line-height: 36px;
  white-space: nowrap;
}

.support-chat__send--off,
.support-chat__send[disabled] {
  color: #ffffff;
  background: #c9d3e1;
}

.support-chat__state {
  display: flex;
  margin: 16px;
  padding: 40px 16px;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  border-radius: 14px;
  background: #ffffff;
  text-align: center;
}

.support-chat__state-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.support-chat__state-copy {
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.support-chat__state-action {
  height: 40px;
  margin-top: 8px;
  padding: 0 24px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
  line-height: 40px;
  text-align: center;
}

.support-chat__skeleton {
  width: 70%;
  height: 40px;
  border-radius: 14px;
  background: #eef2f8;
}

.support-chat__skeleton--right {
  align-self: flex-end;
}
</style>
