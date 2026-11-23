<script setup lang="ts">
import { computed, ref } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import { photoPageText, type PhotoViewerRequest } from './photo-viewer'

// Board PhotoViewer: × to close, the title and a grey line, "1 / 3"; the picture on black with its
// tag and ‹ ›; the rows under it; the thumbnails; at the bottom where the original comes from and,
// when the page that opened it can save it, 下载 (a short-lived controlled link, no storage address).
const props = defineProps<{ request: PhotoViewerRequest }>()
const emit = defineEmits<{ close: [] }>()

const CLOSE_ICON = 'M6 6l12 12M18 6L6 18'
const LOCK_ICON = 'M6 11h12v9H6zM9 11V8a3 3 0 0 1 6 0v3'

const index = ref(props.request.startIndex)
// The pictures whose link was made, with until when it works.
const saved = ref<Record<number, string | null>>({})
const saving = ref(false)
let touchStartX: number | null = null

const count = computed(() => props.request.photos.length)
const current = computed(() => props.request.photos[index.value])
const page = computed(() => photoPageText(index.value, count.value))
const rows = computed(() => [
  { key: 'time', label: '拍摄时间', value: current.value?.time },
  { key: 'by', label: '录入人', value: current.value?.by },
  { key: 'note', label: '说明', value: current.value?.note },
].filter((row): row is { key: string; label: string; value: string } => Boolean(row.value)))
const isSaved = computed(() => index.value in saved.value)
const sourceText = computed(() =>
  [`第 ${index.value + 1} 张`, current.value?.tag, '原图'].filter(Boolean).join(' · '))

function go(step: number): void {
  index.value = Math.min(count.value - 1, Math.max(0, index.value + step))
}

function onTouchStart(event: Event): void {
  touchStartX = (event as TouchEvent).touches?.[0]?.clientX ?? null
}

function onTouchEnd(event: Event): void {
  const endX = (event as TouchEvent).changedTouches?.[0]?.clientX
  if (touchStartX === null || endX === undefined) return
  const dx = endX - touchStartX
  touchStartX = null
  if (Math.abs(dx) > 40) go(dx < 0 ? 1 : -1)
}

// The same picture is not linked twice (board: 已生成).
async function save(): Promise<void> {
  const saver = props.request.save
  if (!saver || saving.value || isSaved.value) return
  saving.value = true
  try {
    const outcome = await saver(index.value)
    saved.value = { ...saved.value, [index.value]: outcome.until }
  } catch {
    uni.showToast({ title: '下载链接没有生成，请稍后重试', icon: 'none' })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <view class="photo-viewer" data-testid="photo-viewer">
    <view class="photo-viewer__head">
      <button class="photo-viewer__close" data-testid="photo-viewer-close" aria-label="关闭" @click="emit('close')">
        <LineIcon :d="CLOSE_ICON" :size="22" color="#ffffff" :stroke-width="2" />
      </button>
      <view class="photo-viewer__titles">
        <text class="photo-viewer__title" role="heading">{{ request.title }}</text>
        <text v-if="request.subtitle" class="photo-viewer__subtitle">{{ request.subtitle }}</text>
      </view>
      <text class="photo-viewer__page" role="status" :aria-label="page.aria" data-testid="photo-viewer-page">{{ page.text }}</text>
    </view>

    <view class="photo-viewer__stage" @touchstart="onTouchStart" @touchend="onTouchEnd">
      <image v-if="current" class="photo-viewer__image" :src="current.url" mode="aspectFit" :aria-label="current.alt" data-testid="photo-viewer-image" />
      <text v-if="current?.tag" class="photo-viewer__tag">{{ current.tag }}</text>
      <button
        class="photo-viewer__arrow photo-viewer__arrow--prev"
        data-testid="photo-viewer-prev"
        aria-label="上一张"
        :disabled="index === 0"
        @click="go(-1)"
      >
        <LineIcon name="back" :size="22" color="#ffffff" :stroke-width="2" />
      </button>
      <button
        class="photo-viewer__arrow photo-viewer__arrow--next"
        data-testid="photo-viewer-next"
        aria-label="下一张"
        :disabled="index === count - 1"
        @click="go(1)"
      >
        <LineIcon name="forward" :size="22" color="#ffffff" :stroke-width="2" />
      </button>
    </view>

    <view v-if="rows.length" class="photo-viewer__rows">
      <template v-for="row in rows" :key="row.key">
        <text class="photo-viewer__row-label">{{ row.label }}</text>
        <text class="photo-viewer__row-value">{{ row.value }}</text>
      </template>
    </view>

    <scroll-view v-if="count > 1" class="photo-viewer__thumbs" scroll-x role="list" aria-label="全部照片">
      <view class="photo-viewer__thumb-track">
        <button
          v-for="(photo, at) in request.photos"
          :key="`${at}:${photo.url}`"
          :class="['photo-viewer__thumb', { 'photo-viewer__thumb--on': at === index }]"
          role="listitem"
          :aria-label="`第 ${at + 1} 张 ${photo.tag}`"
          :aria-pressed="at === index"
          :data-testid="`photo-viewer-thumb-${at}`"
          @click="index = at"
        >
          <image class="photo-viewer__thumb-image" :src="photo.url" mode="aspectFill" />
        </button>
      </view>
    </scroll-view>

    <view class="photo-viewer__foot">
      <view v-if="isSaved" class="photo-viewer__status photo-viewer__status--ready" role="status" data-testid="photo-viewer-ready">
        <LineIcon name="check" :size="12" color="#4cd68a" :stroke-width="3" />
        <text>{{ saved[index] ? `下载链接已生成，${saved[index]} 前有效` : '下载链接已生成' }}</text>
      </view>
      <view v-else class="photo-viewer__status">
        <LineIcon :d="LOCK_ICON" :size="12" color="#91a0b4" :stroke-width="2" />
        <text>{{ request.save ? '原图经受控链接下载，不显示存储地址' : '图片仅供查看' }}</text>
      </view>
      <view v-if="request.save" class="photo-viewer__source-row">
        <text class="photo-viewer__source">{{ sourceText }}</text>
        <button class="photo-viewer__download" data-testid="photo-viewer-download" :disabled="saving" @click="save">
          {{ isSaved ? '已生成' : saving ? '正在生成…' : '下载' }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 board PhotoViewer (390 wide). */
.photo-viewer {
  display: flex;
  height: 100vh;
  flex-direction: column;
  overflow: hidden;
  color: #fff;
  background: #111214;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.photo-viewer__head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 4px;
  padding: calc(var(--status-bar-height) + 8px) 8px 8px;
}

.photo-viewer__close {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.photo-viewer__titles {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 1px;
}

.photo-viewer__title {
  overflow: hidden;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.photo-viewer__subtitle {
  overflow: hidden;
  color: #c9d3e1;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.photo-viewer__page {
  flex: none;
  margin-right: 8px;
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.photo-viewer__stage {
  position: relative;
  display: flex;
  min-height: 0;
  flex: 1 1 auto;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #000;
}

.photo-viewer__image {
  width: 100%;
  height: 100%;
}

.photo-viewer__tag {
  position: absolute;
  top: 12px;
  left: 12px;
  padding: 3px 8px;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.55);
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.photo-viewer__arrow {
  position: absolute;
  top: 50%;
  display: flex;
  width: 44px;
  height: 44px;
  margin-top: -22px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
}

.photo-viewer__arrow[disabled] {
  background: rgba(0, 0, 0, 0.45);
  opacity: 0.35;
}

.photo-viewer__arrow--prev {
  left: 6px;
}

.photo-viewer__arrow--next {
  right: 6px;
}

.photo-viewer__rows {
  display: grid;
  flex: none;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 6px 10px;
  padding: 12px 16px 10px;
  background: #1c1d20;
  font-size: 13px;
  line-height: 18px;
}

.photo-viewer__row-label {
  color: #91a0b4;
  white-space: nowrap;
}

.photo-viewer__row-value {
  min-width: 0;
}

.photo-viewer__thumbs {
  flex: none;
  padding: 0 16px 10px;
  background: #1c1d20;
  white-space: nowrap;
  box-sizing: border-box;
}

.photo-viewer__thumb-track {
  display: inline-flex;
  gap: 8px;
}

.photo-viewer__thumb {
  width: 64px;
  height: 64px;
  overflow: hidden;
  border: 2px solid transparent;
  border-radius: 8px;
  background: #000;
  box-sizing: border-box;
}

.photo-viewer__thumb--on {
  border-color: #ff6a00;
}

.photo-viewer__thumb-image {
  display: block;
  width: 100%;
  height: 100%;
  opacity: 0.6;
}

.photo-viewer__thumb--on .photo-viewer__thumb-image {
  opacity: 1;
}

.photo-viewer__foot {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 8px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid rgba(255, 255, 255, 0.12);
  background: #1c1d20;
}

.photo-viewer__status {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
}

.photo-viewer__status--ready {
  color: #4cd68a;
}

.photo-viewer__source-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.photo-viewer__source {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  color: #c9d3e1;
  font-size: 13px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.photo-viewer__download {
  height: 44px;
  flex: none;
  padding: 0 22px;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
}
</style>
