<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { CONTENT_PREVIEW_ENABLED } from '@/features/content/application/content-preview'
import { contentVideoApi } from '@/features/content/data/content-video-api-client'
import type { ContentVideoFeed } from '@/features/content/domain/content'
import {
  buildContentVideoFeedUrl,
  parseContentVideoFeedTarget,
} from '@/features/content/navigation/content-routes'
import ContentUnavailable from '@/features/content/presentation/shared/ContentUnavailable.vue'
import ContentVideoFeedPage from '@/features/content/presentation/video/ContentVideoFeedPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { createSupportKey } from '@/features/support/application/chat-view'

// The vertical video stream (board VideoFeed, 1.2.0), preview builds only. A card on 发现 opens it
// at that video; 点赞 / 关注 / 评论 sign in first and come back to the same video.
const DISCOVER_PATH = '/pages/discover/index'

const target = ref<{ cityCode: string; startVideoId: string | null; feed: ContentVideoFeed } | null>(null)

function getAccessToken(): string | null {
  return getBookingAuthContext().getAccessToken()
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: DISCOVER_PATH })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

function login(videoId: string | null, feed: ContentVideoFeed): void {
  if (!target.value) return
  uni.redirectTo({ url: buildInspectionLoginUrl(buildContentVideoFeedUrl(target.value.cityCode, videoId, feed)) })
}

onLoad((query) => {
  const parsed = parseContentVideoFeedTarget(query as Record<string, unknown> | undefined)
  target.value = parsed.cityCode
    ? parsed
    : { ...parsed, cityCode: import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim() ?? '' }
})
</script>

<template>
  <ContentVideoFeedPage
    v-if="CONTENT_PREVIEW_ENABLED && target && target.cityCode"
    :city-code="target.cityCode"
    :start-video-id="target.startVideoId"
    :feed="target.feed"
    :api="contentVideoApi"
    :get-access-token="getAccessToken"
    :create-key="createSupportKey"
    @back="goBack"
    @open="open"
    @login="login"
  />
  <ContentUnavailable v-else-if="!CONTENT_PREVIEW_ENABLED" />
</template>
