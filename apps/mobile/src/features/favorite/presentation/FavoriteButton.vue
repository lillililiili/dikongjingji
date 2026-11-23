<script setup lang="ts">
import { onMounted, watch } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { createFavoriteToggle, type FavoriteTarget } from '../application/favorite-toggle'
import { favoriteApi } from '../data/favorite-api-client'
import type { FavoriteTargetType } from '../domain/favorite'

// 收藏 / 已收藏 on the bar of a detail page (boards ActDetail, ContentVideo, ContentArticle; the
// product page's bar as on board Product). Icon above the word, 44 or 46 wide like the bar's 咨询.
// The page passes its own address, so a login started here comes back to the same page.
const props = withDefaults(defineProps<{
  type: FavoriteTargetType
  targetId: string
  cityCode: string
  returnUrl: string
  width?: number
  // A star where the page already has a heart for 点赞 (the video page, as board VideoFeed draws them).
  icon?: 'heart' | 'star'
}>(), { width: 46, icon: 'heart' })

const STAR = 'M12 3l2.8 5.8 6.4.9-4.6 4.5 1.1 6.3L12 17.5 6.3 20.5l1.1-6.3L2.8 9.7l6.4-.9z'


const toggle = createFavoriteToggle({
  api: favoriteApi,
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
  openLogin: () => uni.navigateTo({ url: buildInspectionLoginUrl(props.returnUrl) }),
  toast: (title) => uni.showToast({ title, icon: 'none' }),
})
// No `disabled`: a disabled uni button turns its words grey; a tap while busy is ignored instead.
const { favorited, busy } = toggle

function target(): FavoriteTarget {
  return { type: props.type, targetId: props.targetId, cityCode: props.cityCode }
}

onMounted(() => { void toggle.refresh(target()) })
watch(() => [props.type, props.targetId], () => { void toggle.refresh(target()) })
</script>

<template>
  <button
    class="favorite-button"
    :style="{ width: `${width}px`, height: `${width}px`, color: favorited ? '#ff6a00' : '#536178' }"
    data-testid="favorite-button"
    :data-favorited="favorited ? 'true' : 'false'"
    :aria-pressed="favorited"
    :aria-label="favorited ? '取消收藏' : '收藏'"
    :aria-busy="busy"
    @click="toggle.toggle(target())"
  >
    <LineIcon
      :name="icon === 'heart' ? 'heart' : undefined"
      :d="icon === 'star' ? STAR : undefined"
      :size="22"
      :color="favorited ? '#ff6a00' : '#536178'"
      :fill="favorited ? '#ff6a00' : undefined"
    />
    <text>{{ favorited ? '已收藏' : '收藏' }}</text>
  </button>
</template>

<style scoped>
.favorite-button {
  display: flex;
  margin: 0;
  padding: 0;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border: 0;
  background: transparent;
  font-family: inherit;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

.favorite-button::after {
  border: 0;
}

</style>
