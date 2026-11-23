<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'

import type {
  MyOrderCardAction,
  MyOrderCardView,
  MyOrderLineTone,
  MyOrderTagTone,
  MyOrderTone,
} from '../application/my-order-cards'

const props = defineProps<{
  card: MyOrderCardView
  testId: string
  busy?: boolean
}>()

const emit = defineEmits<{
  open: []
  action: [action: MyOrderCardAction]
}>()

// Colours of the board OrderList: tag backgrounds and text, status words, the lines under the title.
const TAG_COLORS: Record<MyOrderTagTone, { background: string; color: string }> = {
  ORANGE: { background: '#fff0e3', color: '#ba470b' },
  PURPLE: { background: '#eee7ff', color: '#6a4fe0' },
  GREEN: { background: '#e2f6ed', color: '#11804a' },
  // 仅退款 on the board AftersaleList.
  BLUE: { background: '#e6f0ff', color: '#1d63c4' },
}

const TONE_COLORS: Record<MyOrderTone, string> = {
  ORANGE: '#ba470b',
  GREEN: '#11804a',
  GREY: '#738096',
  BLUE: '#1d63c4',
  PURPLE: '#6a4fe0',
  RED: '#d64f67',
  SLATE: '#536178',
}

const LINE_COLORS: Record<MyOrderLineTone, string> = {
  MUTED: '#738096',
  PLAIN: '#536178',
  COUNTDOWN: '#e85400',
  SHIPPED: '#11804a',
  ORANGE: '#ba470b',
  GREEN: '#11804a',
  RED: '#d64f67',
}

// Board OrderList: the clock of the payment countdown and the truck of a shipped order.
const CLOCK = 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2'
const TRUCK = 'M3 7h11v9H3zM14 10h4l3 3v3h-7M7 19a1.5 1.5 0 1 0 0-.1M17 19a1.5 1.5 0 1 0 0-.1'
</script>

<template>
  <view class="my-order-card" :data-testid="props.testId">
    <view class="my-order-card__open" :data-testid="`${props.testId}-open`" @click="emit('open')">
      <view class="my-order-card__head">
        <text
          class="my-order-card__tag"
          :style="{ background: TAG_COLORS[props.card.tag.tone].background, color: TAG_COLORS[props.card.tag.tone].color }"
        >
          {{ props.card.tag.label }}
        </text>
        <text :class="['my-order-card__head-name', { 'my-order-card__head-name--muted': props.card.headMuted }]">{{ props.card.head }}</text>
        <text
          class="my-order-card__status"
          data-testid="my-order-status"
          :style="{ color: TONE_COLORS[props.card.status.tone] }"
        >
          {{ props.card.status.text }}
        </text>
      </view>
      <view class="my-order-card__main">
        <image
          v-if="props.card.cover"
          class="my-order-card__cover"
          :src="props.card.cover"
          mode="aspectFill"
          :aria-label="props.card.coverAlt"
        />
        <view v-else class="my-order-card__cover my-order-card__cover--empty">
          <LineIcon name="calendar" :size="26" color="#91a0b4" />
        </view>
        <view class="my-order-card__body">
          <view class="my-order-card__title-line">
            <text class="my-order-card__title">{{ props.card.title }}<text v-if="props.card.titleTail" class="my-order-card__title-tail">{{ props.card.titleTail }}</text></text>
            <text class="my-order-card__amount" data-testid="my-order-amount">{{ props.card.amount }}</text>
          </view>
          <view
            v-for="line in props.card.lines"
            :key="line.key"
            class="my-order-card__line"
            :data-testid="`my-order-line-${line.key}`"
            :style="{ color: LINE_COLORS[line.tone] }"
          >
            <LineIcon
              v-if="line.tone === 'COUNTDOWN' || line.tone === 'SHIPPED'"
              class="my-order-card__line-icon"
              :d="line.tone === 'COUNTDOWN' ? CLOCK : TRUCK"
              :size="14"
              :color="line.tone === 'COUNTDOWN' ? '#e85400' : '#159a70'"
              :stroke-width="2"
            />
            <text>{{ line.text }}</text>
          </view>
        </view>
      </view>
    </view>
    <view v-if="props.card.actions.length > 0" class="my-order-card__actions">
      <button
        v-for="action in props.card.actions"
        :key="action.key"
        :class="['my-order-card__action', `my-order-card__action--${action.style.toLowerCase()}`]"
        :data-testid="`my-order-action-${action.key}`"
        :disabled="props.busy"
        @click="emit('action', action)"
      >
        {{ action.label }}
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours copied from the cards of the 2026-11-14 board OrderList (390 wide). */
.my-order-card {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 12px;
  border-radius: 14px;
  color: #202c42;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.my-order-card__open {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.my-order-card__head {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.my-order-card__tag {
  flex: none;
  padding: 0 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;
}

.my-order-card__head-name {
  min-width: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.my-order-card__head-name--muted {
  color: #536178;
  font-size: 14px;
  font-weight: 400;
}

.my-order-card__status {
  flex: none;
  margin-left: auto;
  font-size: 14px;
  font-weight: 500;
  line-height: 21px;
  white-space: nowrap;
}

.my-order-card__main {
  display: flex;
  gap: 12px;
}

.my-order-card__cover {
  display: block;
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.my-order-card__cover--empty {
  display: flex;
  align-items: center;
  justify-content: center;
}

.my-order-card__body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.my-order-card__title-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.my-order-card__title {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.my-order-card__title-tail {
  display: inline-block;
  white-space: nowrap;
}

.my-order-card__amount {
  flex: none;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  white-space: nowrap;
}

.my-order-card__line {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  line-height: 18px;
}

.my-order-card__line-icon {
  flex: none;
}

.my-order-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.my-order-card__action {
  display: flex;
  height: 36px;
  margin: 0;
  padding: 0 16px;
  align-items: center;
  justify-content: center;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-family: inherit;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
  box-sizing: border-box;
}

.my-order-card__action::after {
  border: 0;
}

.my-order-card__action--primary {
  padding: 0 18px;
  border: 0;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-weight: 500;
}

.my-order-card__action--accent {
  border: 1.5px solid #ff6a00;
  color: #ba470b;
  font-weight: 500;
}

/* uni's own [disabled] colours would turn the buttons grey; keep each look and only fade it. */
.my-order-card__action[disabled] {
  color: #202c42;
  background: #ffffff;
  opacity: 0.55;
}

.my-order-card__action--primary[disabled] {
  color: #ffffff;
  background: #ff6a00;
}

.my-order-card__action--accent[disabled] {
  color: #ba470b;
}
</style>
