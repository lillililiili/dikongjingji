<script setup lang="ts">
import { computed } from 'vue'

import { formatLiveCount, liveBarLine } from '../../application/live-view'
import type { LiveRoomCard } from '../../domain/live'

// 推荐 · 直播条 (board Discover): the first room that is live in the city, with 进直播间.
const props = defineProps<{ card: LiveRoomCard }>()
const emit = defineEmits<{ open: [] }>()

const picture = computed(() => props.card.coverUrl ?? null)
</script>

<template>
  <button class="live-bar" data-testid="live-bar" :data-room="card.roomId" @click="emit('open')">
    <image v-if="picture" class="live-bar__image" :src="picture" mode="aspectFill" :aria-label="`${card.title}直播画面`" />
    <view class="live-bar__shade" />
    <view class="live-bar__top">
      <view class="live-bar__tag"><view class="live-bar__dot" />直播中</view>
      <text class="live-bar__viewers">{{ formatLiveCount(card.viewerCount) }} 观看</text>
    </view>
    <view class="live-bar__bottom">
      <view class="live-bar__copy">
        <text class="live-bar__title">{{ card.title }}</text>
        <text class="live-bar__line">{{ liveBarLine(card) }}</text>
      </view>
      <text class="live-bar__enter">进直播间</text>
    </view>
  </button>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Discover (推荐, 直播条). */
.live-bar {
  position: relative;
  display: block;
  flex-shrink: 0;
  height: 150px;
  margin: 0;
  padding: 0;
  overflow: hidden;
  border: 0;
  border-radius: 14px;
  color: #ffffff;
  background: #202c42;
  text-align: left;
  line-height: normal;
}

.live-bar::after {
  border: 0;
}

.live-bar__image {
  display: block;
  width: 100%;
  height: 100%;
  opacity: 0.85;
}

.live-bar__shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.1) 30%, rgba(0, 0, 0, 0.7) 100%);
}

.live-bar__top {
  position: absolute;
  top: 10px;
  left: 10px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.live-bar__tag {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  background: #d64f67;
  font-size: 11px;
  font-weight: 700;
}

.live-bar__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ffffff;
}

.live-bar__viewers {
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.45);
  font-size: 11px;
  line-height: 22px;
}

.live-bar__bottom {
  position: absolute;
  right: 12px;
  bottom: 10px;
  left: 12px;
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.live-bar__copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.live-bar__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.live-bar__line {
  overflow: hidden;
  color: rgba(255, 255, 255, 0.8);
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.live-bar__enter {
  flex-shrink: 0;
  height: 32px;
  padding: 0 14px;
  border-radius: 999px;
  background: #ff6a00;
  font-size: 13px;
  font-weight: 500;
  line-height: 32px;
  white-space: nowrap;
}
</style>
