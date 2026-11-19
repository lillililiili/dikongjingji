<script setup lang="ts">
import type { ContentImage } from '../../domain/content'
import type { Tone } from '../../application/content-view'

// The picture of an article or activity: the licensed picture when there is one, otherwise a plain
// block in the kind's tint (no stand-in photos, §27). With `title` the block carries the title, as
// the waterfall cards and the topic's related articles do.
defineProps<{
  image: ContentImage | null
  tone: Tone
  tag: string
  // The tag's own colours; by default the dark translucent tag of the board.
  tagTone?: Tone | null
  title?: string | null
  sample?: boolean
}>()
</script>

<template>
  <view class="content-thumb" :style="{ background: image ? '#eef0f3' : tone.background }">
    <image v-if="image" class="content-thumb__image" :src="image.url" mode="aspectFill" lazy-load :aria-label="image.alt" />
    <text
      v-else-if="title"
      class="content-thumb__title"
      :style="{ color: tone.color }"
    >{{ title }}</text>
    <text
      class="content-thumb__tag"
      :style="tagTone ? { background: tagTone.background, color: tagTone.color } : undefined"
    >{{ tag }}</text>
    <text v-if="sample" class="content-thumb__sample" data-testid="content-sample">示例</text>
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

.content-thumb__sample {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 0 5px;
  border-radius: 6px;
  color: #ffffff;
  background: rgba(32, 44, 66, 0.55);
  font-size: 11px;
  line-height: 17px;
  white-space: nowrap;
}
</style>
