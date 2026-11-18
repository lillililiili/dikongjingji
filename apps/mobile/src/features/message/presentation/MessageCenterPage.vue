<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { ref } from 'vue'

import type { MessageCenterState } from '../application/message-center'
import type { MessageItem } from '../domain/message'
import { MESSAGE_CATEGORY_LABELS, formatMessageTime } from './message-format'

defineProps<{
  state: MessageCenterState
}>()

const emit = defineEmits<{
  login: []
  retry: []
  open: [message: MessageItem]
  markAllRead: []
  loadMore: []
}>()

// Read once per render pass is enough: the labels only need the day to be right.
const now = ref(new Date())
</script>

<template>
  <view class="message-center" data-testid="message-page">
    <view class="message-center__header">
      <text class="message-center__title">消息</text>
      <button
        v-if="state.kind === 'ready' && state.unreadCount > 0"
        class="message-center__read-all"
        data-testid="message-read-all"
        @click="emit('markAllRead')"
      >
        <UniIcons type="checkbox" size="18" color="#536178" />
        <text>全部已读</text>
      </button>
    </view>

    <view v-if="state.kind === 'guest'" class="message-center__state" data-testid="message-guest">
      <text class="message-center__state-title">登录后查看消息</text>
      <text class="message-center__state-copy">巡检方案、排期、报告交付等进度会通知到这里</text>
      <button class="message-center__state-action" data-testid="message-login" @click="emit('login')">
        登录 / 注册
      </button>
    </view>

    <view v-else-if="state.kind === 'expired'" class="message-center__state" data-testid="message-expired">
      <text class="message-center__state-title">登录已过期</text>
      <text class="message-center__state-copy">重新登录后可以继续查看消息</text>
      <button class="message-center__state-action" data-testid="message-login" @click="emit('login')">
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

    <view v-else-if="state.items.length === 0" class="message-center__state" data-testid="message-empty">
      <text class="message-center__state-title">还没有消息</text>
      <text class="message-center__state-copy">巡检方案、排期、报告交付等进度会通知到这里</text>
    </view>

    <template v-else>
      <view class="message-center__list">
        <button
          v-for="message in state.items"
          :key="message.id"
          class="message-center__item"
          :data-testid="`message-${message.id}`"
          :data-read="message.read"
          @click="emit('open', message)"
        >
          <view class="message-center__icon">
            <UniIcons type="paperplane" size="22" color="#2f80ed" />
          </view>
          <view class="message-center__copy">
            <view class="message-center__line">
              <text
                :class="[
                  'message-center__item-title',
                  { 'message-center__item-title--unread': !message.read },
                ]"
              >
                <text class="message-center__category">{{ MESSAGE_CATEGORY_LABELS[message.category] }} · </text>{{ message.title }}
              </text>
              <text class="message-center__time">{{ formatMessageTime(message.occurredAt, now) }}</text>
            </view>
            <view class="message-center__line message-center__line--body">
              <text
                :class="[
                  'message-center__body',
                  { 'message-center__body--read': message.read },
                ]"
              >
                {{ message.body }}
              </text>
              <view v-if="!message.read" class="message-center__dot" aria-label="未读" />
            </view>
          </view>
        </button>
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

.message-center__header {
  display: flex;
  align-items: center;
  padding: calc(var(--status-bar-height) + 6px) 6px 6px 16px;
  background: #ffffff;
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
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
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

.message-center__icon {
  display: flex;
  width: 40px;
  height: 40px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #e6f0ff;
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
