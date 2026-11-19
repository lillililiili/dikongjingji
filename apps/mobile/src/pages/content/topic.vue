<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { CONTENT_PREVIEW_ENABLED } from '@/features/content/application/content-preview'
import { buildContentArticleUrl, parseContentTopicTarget } from '@/features/content/navigation/content-routes'
import ContentUnavailable from '@/features/content/presentation/shared/ContentUnavailable.vue'
import ContentTopicPage from '@/features/content/presentation/topic/ContentTopicPage.vue'

const DISCOVER_PATH = '/pages/discover/index'

const target = ref<{ topicId: string; cityCode: string } | null>(null)

// Back goes where the user came from; opened on its own (a shared link), back is 发现.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: DISCOVER_PATH })
}

function browse(): void {
  uni.switchTab({ url: DISCOVER_PATH })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

function openArticle(articleId: string, cityCode: string): void {
  uni.navigateTo({ url: buildContentArticleUrl(articleId, cityCode) })
}

// 查看全部活动: the home page's 赛事研学 channel, where every activity of the city is listed.
function openAllActivities(): void {
  openHomeChannel('ACTIVITY')
}

onLoad((query) => {
  target.value = parseContentTopicTarget(query as Record<string, unknown> | undefined)
})
</script>

<template>
  <ContentTopicPage
    v-if="CONTENT_PREVIEW_ENABLED && target"
    :topic-id="target.topicId"
    :city-code="target.cityCode"
    @back="goBack"
    @browse="browse"
    @open="open"
    @open-article="openArticle"
    @all-activities="openAllActivities"
  />
  <ContentUnavailable v-else-if="!CONTENT_PREVIEW_ENABLED" />
</template>
