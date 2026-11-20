<script setup lang="ts">
import { onReachBottom, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import { buildActivityDetailUrl } from '@/features/activity/navigation/activity-routes'
import { CONTENT_PREVIEW_ENABLED } from '@/features/content/application/content-preview'
import { buildContentArticleUrl, buildContentTopicUrl } from '@/features/content/navigation/content-routes'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import ContentDiscoverPage from '@/features/content/presentation/discover/ContentDiscoverPage.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

// 发现 (step H1): articles, topics and the city's activities in preview builds. Public builds keep
// the closed notice above the root bar until the discover content is released.
//
// The gate is the content feature's own copy of the preview expression, not the shared
// PLATFORM_PREVIEW_ENABLED: this page shares the activity modules with the home page, and importing
// the shared flag here moves it into a common chunk where the minifier cannot fold it, which leaves
// the home page's sample content in the public bundle (see activity-preview.ts).
const currentCityStore = useCurrentCityStore()
const discover = ref<{ loadMore(): void; refresh(): void } | null>(null)

function restoreDefaultCity(): void {
  if (currentCityStore.currentCity) return
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  if (code && name) currentCityStore.setCurrentCity({ code, name })
}

function openArticle(articleId: string, cityCode: string): void {
  uni.navigateTo({ url: buildContentArticleUrl(articleId, cityCode) })
}

function openTopic(topicId: string, cityCode: string): void {
  uni.navigateTo({ url: buildContentTopicUrl(topicId, cityCode) })
}

function openActivity(activityId: string, cityCode: string): void {
  uni.navigateTo({ url: buildActivityDetailUrl(activityId, cityCode) })
}

// 直播 (M20): a live room, a preview page or a replay, by the route the live feature built.
function openUrl(url: string): void {
  uni.navigateTo({ url })
}

// 提醒我 without a login: sign in, then land on that room's preview page.
function openLogin(returnUrl: string): void {
  uni.navigateTo({ url: buildInspectionLoginUrl(returnUrl) })
}

onShow(() => {
  if (!CONTENT_PREVIEW_ENABLED) return
  restoreDefaultCity()
  discover.value?.refresh()
})

onReachBottom(() => {
  discover.value?.loadMore()
})
</script>

<template>
  <view data-testid="discover-page">
    <ContentDiscoverPage
      v-if="CONTENT_PREVIEW_ENABLED"
      ref="discover"
      @open-article="openArticle"
      @open-topic="openTopic"
      @open-activity="openActivity"
      @open-url="openUrl"
      @login="openLogin"
    />
    <view v-else class="platform-page-unavailable">
      <text>发现频道尚未开通</text>
    </view>
    <RootAppTabBar active="DISCOVER" />
  </view>
</template>

<style scoped>
.platform-page-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
