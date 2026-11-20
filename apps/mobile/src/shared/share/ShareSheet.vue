<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { useCurrentCityStore } from '@/shared/city/state/current-city'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { buildShareLink, describeShareSubtitle, type ShareTarget } from './share-link'

// The one share panel of the detail pages (board ShareSheet): only 复制链接. Posters and QR codes
// are not made in the App.
const props = defineProps<{ target: ShareTarget | null }>()
const emit = defineEmits<{ close: [] }>()

const copied = ref(false)
watch(() => props.target, () => { copied.value = false })

const link = computed(() => (props.target ? buildShareLink(props.target.path) : ''))

// "¥1,299 起 · 东营可售" (board ShareSheet): a product says where it is sold, when the link's city
// is the App's current one and its name is known.
const cityStore = useCurrentCityStore()
function knownCity(): { code: string; name: string } | null {
  if (cityStore.currentCity) return cityStore.currentCity
  // Before any city is picked the App runs in its default city (as the Mine page restores it).
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  return code && name ? { code, name } : null
}
const subtitle = computed(() => (props.target ? describeShareSubtitle(props.target, knownCity()) : null))

// Icons copied from the board ShareSheet.
const ICONS = {
  info: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01',
  check: 'M5 12l5 5 9-10',
}

function copy(): void {
  if (!link.value) return
  uni.setClipboardData({
    data: link.value,
    showToast: false,
    success: () => { copied.value = true },
    fail: () => { uni.showToast({ title: '没有复制成功，请再试一次', icon: 'none' }) },
  })
}
</script>

<template>
  <template v-if="target">
    <view class="share-sheet__mask" aria-label="关闭" data-testid="share-mask" @click="emit('close')" />
    <view class="share-sheet" role="dialog" :aria-label="`分享${target.subject}`" data-testid="share-sheet" :data-link="link">
      <view class="share-sheet__head">
        <text class="share-sheet__title">分享{{ target.subject }}</text>
        <text v-if="target.owner" class="share-sheet__owner">{{ target.owner }}</text>
      </view>
      <view class="share-sheet__item">
        <image v-if="target.imageUrl" class="share-sheet__image" :src="target.imageUrl" mode="aspectFill" />
        <view v-else class="share-sheet__image share-sheet__image--blank">
          <LineIcon name="image" :size="24" color="#91a0b4" />
        </view>
        <view class="share-sheet__item-copy">
          <text class="share-sheet__item-title">{{ target.title }}</text>
          <text v-if="subtitle" class="share-sheet__item-sub" data-testid="share-subtitle">{{ subtitle }}</text>
        </view>
      </view>
      <view class="share-sheet__note">
        <LineIcon class="share-sheet__note-icon" :d="ICONS.info" :size="16" color="#738096" :stroke-width="2" />
        <view class="share-sheet__note-copy">
          <text class="share-sheet__note-main">链接只带这个{{ target.subject }}的编号和城市，不包含你的个人信息</text>
          <text class="share-sheet__note-sub">App 内只能复制链接，不生成海报和二维码</text>
        </view>
      </view>
      <view class="share-sheet__actions">
        <button class="share-sheet__close" data-testid="share-close" @click="emit('close')">关闭</button>
        <button v-if="!copied" class="share-sheet__copy" data-testid="share-copy" @click="copy">复制链接</button>
        <view v-else class="share-sheet__copied" role="status" data-testid="share-copied">
          <LineIcon :d="ICONS.check" :size="16" color="#159a70" :stroke-width="3" />
          <text>链接已复制</text>
        </view>
      </view>
    </view>
  </template>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board ShareSheet (390 wide). */
button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: normal;
}

button::after {
  border: 0;
}

.share-sheet__mask {
  position: fixed;
  inset: 0;
  z-index: 300;
  background: rgba(0, 0, 0, 0.45);
}

.share-sheet {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 301;
  display: flex;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 14px;
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #ffffff;
}

.share-sheet__head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.share-sheet__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.share-sheet__owner {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.share-sheet__item {
  display: flex;
  padding: 10px;
  align-items: center;
  gap: 12px;
  border: 1px solid #e5ebf3;
  border-radius: 12px;
}

.share-sheet__image {
  width: 64px;
  height: 64px;
  flex-shrink: 0;
  border-radius: 8px;
  background: #eef2f8;
}

.share-sheet__image--blank {
  display: flex;
  align-items: center;
  justify-content: center;
}

.share-sheet__item-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.share-sheet__item-title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.share-sheet__item-sub {
  overflow: hidden;
  color: #e85400;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.share-sheet__note {
  display: flex;
  padding: 10px 12px;
  align-items: flex-start;
  gap: 6px;
  border-radius: 10px;
  background: #f5f7fc;
}

.share-sheet__note-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.share-sheet__note-main {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.share-sheet__note-sub {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.share-sheet__actions {
  display: flex;
  gap: 10px;
}

.share-sheet__close,
.share-sheet__copy,
.share-sheet__copied {
  display: flex;
  height: 44px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  gap: 4px;
  box-sizing: border-box;
  border-radius: 999px;
  font-size: 15px;
  white-space: nowrap;
}

.share-sheet__close {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
}

.share-sheet__copy {
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-weight: 500;
}

.share-sheet__note-icon {
  margin-top: 1px;
}

.share-sheet__copied {
  color: #11804a;
  background: #e2f6ed;
  font-weight: 500;
}
</style>
