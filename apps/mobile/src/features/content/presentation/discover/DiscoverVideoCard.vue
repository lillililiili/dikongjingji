<script setup lang="ts">
import { computed } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import { AUTHOR_AVATAR_COLORS, CONTENT_KIND_TONES, authorInitial, formatContentDay } from '../../application/content-view'
import { formatVideoDuration } from '../../application/video-view'
import type { ContentArticleCard } from '../../domain/content'

// A video in the discover waterfall (board Discover): the poster with 视频 and the duration on it,
// the title, the author. Tapping it opens the vertical stream at this video.
const props = defineProps<{ card: ContentArticleCard }>()
const emit = defineEmits<{ open: [] }>()

const poster = computed(() => props.card.coverImage ?? null)
// Portrait videos stand taller in the waterfall, as the board's 236 and 200.
const tall = computed(() => props.card.video?.orientation !== 'LANDSCAPE')
const duration = computed(() => (props.card.video ? formatVideoDuration(props.card.video.durationSeconds) : ''))

// The board's play triangle, on the 24 grid of LineIcon.
const PLAY = 'M6 3l15 9-15 9z'
</script>

<template>
  <button
    class="video-card"
    :data-testid="`discover-video-${card.articleId}`"
    :aria-label="`视频：${card.title}${duration ? `，时长 ${duration}` : ''}`"
    @click="emit('open')"
  >
    <view
      :class="['video-card__cover', { 'video-card__cover--tall': tall }]"
      :style="{ background: poster ? '#eef0f3' : CONTENT_KIND_TONES.VIDEO.background }"
    >
      <image v-if="poster" class="video-card__image" :src="poster.url" mode="aspectFill" lazy-load :aria-label="poster.alt" />
      <text v-else class="video-card__plain-title">{{ card.title }}</text>
      <text class="video-card__tag">视频</text>
      <view v-if="duration" class="video-card__corner" data-testid="discover-video-duration">
        <LineIcon :d="PLAY" :size="8" fill="#ffffff" color="#ffffff" />
        <text>{{ duration }}</text>
      </view>
    </view>
    <text class="video-card__title">{{ card.title }}<text v-if="card.sample" class="video-card__sample" data-testid="content-sample">示例</text></text>
    <view class="video-card__meta">
      <text class="video-card__avatar" :style="{ background: AUTHOR_AVATAR_COLORS[card.author.kind] }" aria-hidden="true">
        {{ authorInitial(card.author.name) }}
      </text>
      <text class="video-card__author">{{ card.author.name }}</text>
      <text v-if="card.sponsored" class="video-card__ad" data-testid="content-sponsored">广告</text>
      <text class="video-card__date">{{ formatContentDay(card.publishedAt) }}</text>
    </view>
  </button>
</template>

<style scoped>
/* Sizes and colours follow the board Discover's video card (390 wide). */
.video-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  margin: 0;
  padding: 0;
  overflow: hidden;
  border: 0;
  border-radius: 12px;
  color: #202c42;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
  font-family: inherit;
  line-height: normal;
  text-align: left;
}

.video-card::after {
  border: 0;
}

.video-card__cover {
  position: relative;
  display: flex;
  height: 200px;
  overflow: hidden;
}

.video-card__cover--tall {
  height: 236px;
}

.video-card__image {
  display: block;
  width: 100%;
  height: 100%;
}

.video-card__plain-title {
  display: block;
  padding: 34px 10px 12px;
  color: #ffffff;
  font-size: 15px;
  font-weight: 700;
  line-height: 22px;
  word-break: break-all;
}

.video-card__tag {
  position: absolute;
  top: 8px;
  left: 8px;
  height: 18px;
  padding: 0 6px;
  border-radius: 6px;
  color: #ffffff;
  background: rgba(0, 0, 0, 0.45);
  font-size: 11px;
  line-height: 18px;
  white-space: nowrap;
}

.video-card__corner {
  position: absolute;
  right: 8px;
  bottom: 8px;
  display: flex;
  height: 20px;
  align-items: center;
  gap: 3px;
  padding: 0 7px;
  border-radius: 999px;
  color: #ffffff;
  background: rgba(0, 0, 0, 0.55);
  font-size: 11px;
  white-space: nowrap;
}

.video-card__title {
  padding: 8px 10px 0;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.video-card__sample {
  margin-left: 6px;
  color: #91a0b4;
  font-size: 11px;
  font-weight: 400;
  line-height: 17px;
  white-space: nowrap;
}

.video-card__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px 10px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.video-card__avatar {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
}

.video-card__author {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.video-card__ad {
  flex-shrink: 0;
  padding: 0 4px;
  border: 1px solid #c9d3e1;
  border-radius: 4px;
  color: #627187;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

.video-card__date {
  flex-shrink: 0;
  white-space: nowrap;
}
</style>
