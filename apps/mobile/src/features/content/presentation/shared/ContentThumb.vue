<script setup lang="ts">
import type { ContentImage } from '../../domain/content'
import type { Tone } from '../../application/content-view'

// The picture of an article or activity: the server's picture, or on preview builds the board's
// (the page passes either in). Only when there is neither is it a block in the kind's tint, with
// the title in it when one is given. "示例" is written next to the title by the page, not here.
defineProps<{
  image: (ContentImage & { cutout?: boolean }) | null
  tone: Tone
  tag: string
  // The tag's own colours; by default the dark translucent tag of the board.
  tagTone?: Tone | null
  title?: string | null
}>()
</script>

<template>
  <view class="content-thumb" :style="{ background: image ? (image.cutout ? '#f0edff' : '#eef0f3') : tone.background }">
    <image v-if="image" class="content-thumb__image" :src="image.url" :mode="image.cutout ? 'aspectFit' : 'aspectFill'" lazy-load :aria-label="image.alt" />
    <text
      v-else-if="title"
      class="content-thumb__title"
      :style="{ color: tone.color }"
    >{{ title }}</text>
    <text
      class="content-thumb__tag"
      :style="tagTone ? { background: tagTone.background, color: tagTone.color } : undefined"
    >{{ tag }}</text>
  </view>
</template>

<style scoped>
.content-thumb {
  position: relative;
  display: flex;
  width: 100%;
  height: 100%;
  /* The waterfall card's block grows with its title from the cover's minimum height. */
  min-height: inherit;
  overflow: hidden;
  box-sizing: border-box;
}

.content-thumb__image {
  display: block;
  width: 100%;
  height: 100%;
}

.content-thumb__title {
  display: block;
  padding: 34px 10px 12px;
  font-size: 15px;
  font-weight: 700;
  line-height: 22px;
  word-break: break-all;
}

.content-thumb__tag {
  position: absolute;
  top: 6px;
  left: 6px;
  height: 18px;
  padding: 0 6px;
  border-radius: 6px;
  color: #ffffff;
  background: rgba(0, 0, 0, 0.45);
  font-size: 11px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}
</style>
