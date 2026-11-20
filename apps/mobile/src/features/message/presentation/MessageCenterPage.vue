<script setup lang="ts">
import { computed, ref } from 'vue'

import IconTile from '@/shared/ui/IconTile.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import type { IconTileTone } from '@/shared/ui/icon-tile-tones'

import type { MessageCenterState } from '../application/message-center'
import type { MessageCategory, MessageItem } from '../domain/message'
import {
  MESSAGE_CATEGORY_LABELS,
  formatMessageTime,
  mergeMessageRows,
  type MessageConversationRow,
} from './message-format'

const props = withDefaults(defineProps<{
  state: MessageCenterState
  // null leaves conversations out (public builds, guests).
  conversations?: MessageConversationRow[] | null
}>(), { conversations: null })

const emit = defineEmits<{
  login: []
  retry: []
  open: [message: MessageItem]
  markAllRead: []
  loadMore: []
  openConversation: [conversationId: string]
  contactPlatform: []
}>()

const badge = (count: number): string => (count > 99 ? '99+' : String(count))

// Read once per render pass is enough: the labels only need the day to be right.
const now = ref(new Date())

// Icons from the board Messages (`icon` in renderVals). Each category keeps the colour the board
// gives it: 服务进度 blue, 联系客服 / 客服 orange; 需求与报价 has no square on the board and takes violet.
const ICONS = {
  progress: 'M9 4h6v3H9zM7 5H5v16h14V5h-2M9 14l2 2 4-4',
  service: 'M4 14v-2a8 8 0 1 1 16 0v2M4 14h3v5H5a1 1 0 0 1-1-1zM20 14h-3v5h2a1 1 0 0 0 1-1zM17 19c0 1.5-2 2-5 2',
  demand: 'M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h5',
  check: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM8 12l3 3 5-6',
  chat: 'M4 5h16v11H9l-5 4zM8.5 10.5h.01M12 10.5h.01M15.5 10.5h.01',
}
const CATEGORY_LOOK: Record<MessageCategory, { d: string; tone: IconTileTone }> = {
  SERVICE_PROGRESS: { d: ICONS.progress, tone: 'blue' },
  DEMAND_QUOTE: { d: ICONS.demand, tone: 'violet' },
  CUSTOMER_SERVICE: { d: ICONS.service, tone: 'orange' },
}

// The 服务进度 square filters the list to that category; tapping it again shows everything.
// The other squares of the board (交易物流, 活动提醒, 系统通知) need notifications that do not
// exist yet (R6).
const progressOnly = ref(false)
const ready = computed(() => (props.state.kind === 'ready' ? props.state : null))
// The square's badge counts unread 服务进度 notifications; it is only known once every page is
// loaded, so while more pages wait the square shows no number rather than a partial one.
const progressUnread = computed(() => {
  const current = ready.value
  if (!current || current.hasMore) return 0
  return current.items.filter((item) => item.category === 'SERVICE_PROGRESS' && !item.read).length
})
const rows = computed(() => {
  const current = ready.value
  if (!current) return []
  const notices = progressOnly.value
    ? current.items.filter((item) => item.category === 'SERVICE_PROGRESS')
    : current.items
  return mergeMessageRows(notices, progressOnly.value ? [] : props.conversations ?? [], current.hasMore)
})
const signedOut = computed(() => props.state.kind === 'guest' || props.state.kind === 'expired')
</script>

<template>
  <view class="message-center" data-testid="message-page">
    <view class="message-center__head" data-testid="message-head">
      <view class="message-center__header">
        <text class="message-center__title">消息</text>
        <button
          v-if="state.kind === 'ready' && state.unreadCount > 0"
          class="message-center__read-all"
          data-testid="message-read-all"
          @click="emit('markAllRead')"
        >
          <LineIcon :d="ICONS.check" :size="18" color="#536178" />
          <text>全部已读</text>
        </button>
      </view>
      <view v-if="!signedOut" class="message-center__cats">
        <button
          class="message-center__cat"
          data-testid="message-cat-progress"
          :aria-pressed="progressOnly"
          @click="progressOnly = !progressOnly"
        >
          <view :class="['message-center__cat-tile', { 'message-center__cat-tile--on': progressOnly }]">
            <IconTile tone="blue" :d="ICONS.progress" :size="44" />
            <text v-if="progressUnread > 0" class="message-center__cat-badge">{{ badge(progressUnread) }}</text>
          </view>
          <text :class="['message-center__cat-label', { 'message-center__cat-label--on': progressOnly }]">服务进度</text>
        </button>
        <button
          v-if="conversations"
          class="message-center__cat"
          data-testid="message-contact-platform"
          @click="emit('contactPlatform')"
        >
          <view class="message-center__cat-tile">
            <IconTile tone="orange" :d="ICONS.service" :size="44" />
          </view>
          <text class="message-center__cat-label">联系客服</text>
        </button>
      </view>
    </view>

    <!-- Signed out (board MineGuest, frame 消息): one centred prompt. -->
    <view v-if="state.kind === 'guest'" class="message-center__blank" data-testid="message-guest">
      <view class="message-center__blank-icon">
        <LineIcon :d="ICONS.chat" :size="46" color="#ff6a00" :stroke-width="1.5" />
      </view>
      <text class="message-center__blank-title">登录后查看消息</text>
      <text class="message-center__blank-copy">订单进度、服务通知和客服回复都在这里</text>
      <button class="message-center__blank-action" data-testid="message-login" @click="emit('login')">
        登录 / 注册
      </button>
    </view>

    <view v-else-if="state.kind === 'expired'" class="message-center__blank" data-testid="message-expired">
      <view class="message-center__blank-icon">
        <LineIcon :d="ICONS.chat" :size="46" color="#ff6a00" :stroke-width="1.5" />
      </view>
      <text class="message-center__blank-title">登录已过期</text>
      <text class="message-center__blank-copy">重新登录后可以继续查看消息</text>
      <button class="message-center__blank-action" data-testid="message-login" @click="emit('login')">
        重新登录
      </button>
    </view>

    <view v-else-if="state.kind === 'loading'" class="message-center__list" data-testid="message-loading" aria-label="正在加载">
      <view v-for="index in 4" :key="index" class="message-center__skeleton" />
    </view>

    <view v-else-if="state.kind === 'error'" class="message-center__state" data-testid="message-error">
      <text class="message-center__state-title">消息加载失败，请稍后重试</text>
      <button class="message-center__state-action" data-testid="message-retry" @click="emit('retry')">
        重新加载
      </button>
    </view>

    <view v-else-if="rows.length === 0" class="message-center__state" data-testid="message-empty">
      <text class="message-center__state-title">{{ progressOnly ? '没有服务进度消息' : '还没有消息' }}</text>
      <text class="message-center__state-copy">巡检方案、排期、报告交付等进度会通知到这里</text>
    </view>

    <template v-else>
      <view class="message-center__list" data-testid="message-list">
        <template v-for="entry in rows" :key="entry.key">
          <button
            v-if="entry.kind === 'notice'"
            class="message-center__item"
            data-kind="notice"
            :data-testid="`message-${entry.message.id}`"
            :data-read="entry.message.read"
            @click="emit('open', entry.message)"
          >
            <IconTile
              :tone="CATEGORY_LOOK[entry.message.category].tone"
              :d="CATEGORY_LOOK[entry.message.category].d"
              :size="40"
              :icon-size="22"
            />
            <view class="message-center__copy">
              <view class="message-center__line">
                <text :class="['message-center__item-title', { 'message-center__item-title--unread': !entry.message.read }]">
                  <text class="message-center__category">{{ MESSAGE_CATEGORY_LABELS[entry.message.category] }} · </text>{{ entry.message.title }}
                </text>
                <text class="message-center__time">{{ formatMessageTime(entry.message.occurredAt, now) }}</text>
              </view>
              <view class="message-center__line message-center__line--body">
                <text :class="['message-center__body', { 'message-center__body--read': entry.message.read }]">{{ entry.message.body }}</text>
                <view v-if="!entry.message.read" class="message-center__dot" aria-label="未读" />
              </view>
            </view>
          </button>
          <!-- 客服 conversations sit in the same list by the time of their last message. -->
          <button
            v-else
            class="message-center__item"
            data-kind="conversation"
            :data-testid="`conversation-${entry.row.conversationId}`"
            :data-unread="entry.row.unreadCount"
            @click="emit('openConversation', entry.row.conversationId)"
          >
            <IconTile tone="orange" :d="ICONS.service" :size="40" :icon-size="22" />
            <view class="message-center__copy">
              <view class="message-center__line">
                <text :class="['message-center__item-title', { 'message-center__item-title--unread': entry.row.unreadCount > 0 }]">
                  <text class="message-center__category">客服 · </text>{{ entry.row.title }}
                </text>
                <text class="message-center__time">{{ entry.row.time }}</text>
              </view>
              <view class="message-center__line message-center__line--body">
                <text :class="['message-center__body', { 'message-center__body--read': entry.row.unreadCount === 0 }]">{{ entry.row.preview }}</text>
                <text v-if="entry.row.unreadCount > 0" class="message-center__count" data-testid="conversation-unread">{{ badge(entry.row.unreadCount) }}</text>
              </view>
            </view>
          </button>
        </template>
      </view>

      <view class="message-center__more" data-testid="message-more">
        <text v-if="state.loadingMore">正在加载…</text>
        <button
          v-else-if="state.loadMoreFailed"
          class="message-center__more-action"
          data-testid="message-more-retry"
          @click="emit('loadMore')"
        >
          加载失败，点此重试
        </button>
        <button
          v-else-if="state.hasMore"
          class="message-center__more-action"
          data-testid="message-more-action"
          @click="emit('loadMore')"
        >
          上拉或点此加载更多
        </button>
        <text v-else>没有更多消息了</text>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Messages (390 wide). */
.message-center {
  min-height: 100vh;
  padding-bottom: var(--app-root-tabbar-content-offset);
  color: #202c42;
  background: #f5f7fc;
  box-sizing: border-box;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  text-align: left;
}

button::after {
  border: 0;
}

/* The board's white head, painted with the theme's page-head gradient. */
.message-center__head {
  padding-top: calc(var(--status-bar-height) + 6px);
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.message-center__header {
  display: flex;
  padding: 0 6px 0 16px;
  align-items: center;
  gap: 2px;
}

.message-center__cats {
  display: grid;
  padding: 10px 6px 16px;
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.message-center__cat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: #202c42;
  font-size: 12px;
  line-height: 17px;
}

.message-center__cat-tile {
  position: relative;
  border-radius: 16px;
}

.message-center__cat-tile--on {
  box-shadow: 0 0 0 2px #fff, 0 0 0 3.5px #ff6a00;
}

.message-center__cat-badge {
  position: absolute;
  top: -6px;
  right: -8px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border: 1.5px solid #fff;
  border-radius: 9px;
  color: #fff;
  background: #ff5200;
  font-size: 11px;
  line-height: 15px;
  text-align: center;
  box-sizing: border-box;
}

.message-center__cat-label {
  white-space: nowrap;
}

.message-center__cat-label--on {
  color: #ba470b;
  font-weight: 500;
}

.message-center__blank {
  display: flex;
  min-height: 560px;
  padding: 0 40px 64px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  box-sizing: border-box;
}

.message-center__blank-icon {
  display: flex;
  width: 96px;
  height: 96px;
  margin-bottom: 10px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff0e3;
}

.message-center__blank-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.message-center__blank-copy {
  color: #627187;
  font-size: 14px;
  line-height: 20px;
  text-align: center;
}

.message-center__blank-action {
  width: 168px;
  height: 46px;
  margin-top: 16px;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
}

.message-center__title {
  font-size: 22px;
  font-weight: 700;
  line-height: 44px;
}

.message-center__read-all {
  display: flex;
  min-height: 44px;
  margin-left: auto;
  padding: 0 8px;
  align-items: center;
  gap: 4px;
  color: #536178;
  font-size: 14px;
}

.message-center__list {
  display: flex;
  margin: 10px 16px 0;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.message-center__item {
  display: flex;
  padding: 14px;
  align-items: flex-start;
  gap: 12px;
  border-bottom: 1px solid #f0f1f3;
  border-radius: 0;
  line-height: normal;
}

.message-center__item:last-child {
  border-bottom-color: transparent;
}

.message-center__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.message-center__line {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.message-center__line--body {
  align-items: flex-start;
}

.message-center__item-title {
  /* A block, so a long title ends in an ellipsis on one line instead of wrapping (320 wide). */
  display: block;
  min-width: 0;
  flex: 1;
  overflow: hidden;
  font-size: 15px;
  font-weight: 400;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-center__item-title--unread {
  font-weight: 700;
}

.message-center__category {
  /* uni's text is pre-line by default, which lets the line break after "·". */
  white-space: nowrap;
  color: #738096;
  font-size: 13px;
  font-weight: 400;
}

.message-center__time {
  flex: none;
  color: #91a0b4;
  font-size: 12px;
}

.message-center__body {
  display: -webkit-box;
  min-width: 0;
  flex: 1;
  overflow: hidden;
  color: #536178;
  font-size: 13px;
  line-height: 19px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.message-center__body--read {
  color: #91a0b4;
}

.message-center__dot {
  width: 8px;
  height: 8px;
  margin: 6px 2px 0 0;
  flex: none;
  border-radius: 50%;
  background: #ff5200;
}

.message-center__count {
  min-width: 18px;
  height: 18px;
  flex: none;
  padding: 0 5px;
  box-sizing: border-box;
  border-radius: 9px;
  color: #ffffff;
  background: #ff5200;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}

.message-center__skeleton {
  height: 68px;
  margin: 10px 14px;
  border-radius: 10px;
  background: #eef2f8;
}

.message-center__state {
  display: flex;
  margin: 10px 16px 0;
  padding: 40px 16px;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  border-radius: 14px;
  background: #ffffff;
  text-align: center;
}

.message-center__state-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.message-center__state-copy {
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.message-center__state-action {
  height: 40px;
  margin-top: 8px;
  padding: 0 24px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
  line-height: 40px;
}

.message-center__more {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  color: #738096;
  font-size: 12px;
}

.message-center__more-action {
  min-height: 44px;
  padding: 0 16px;
  color: #536178;
  font-size: 13px;
  line-height: 44px;
}
</style>
