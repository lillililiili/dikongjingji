<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { CONTENT_PREVIEW_ENABLED } from '@/features/content/application/content-preview'
import { contentVideoApi } from '@/features/content/data/content-video-api-client'
import {
  buildContentVideoFeedUrl,
  buildContentVideoUrl,
  parseContentVideoTarget,
} from '@/features/content/navigation/content-routes'
import ContentUnavailable from '@/features/content/presentation/shared/ContentUnavailable.vue'
import ContentVideoPage from '@/features/content/presentation/video/ContentVideoPage.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'

// A video's detail (board ContentVideo, 1.2.0), preview builds only. Reached from the stream's
// caption and from shared links.
const DISCOVER_PATH = '/pages/discover/index'

const target = ref<{ videoId: string; cityCode: string } | null>(null)
const accessToken = ref<string | null>(null)

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

// 点赞 needs a login; the login page comes back here.
function login(): void {
  if (!target.value) return
  uni.redirectTo({ url: buildInspectionLoginUrl(buildContentVideoUrl(target.value.videoId, target.value.cityCode)) })
}

function stream(videoId: string): void {
  if (target.value) uni.navigateTo({ url: buildContentVideoFeedUrl(target.value.cityCode, videoId) })
}

onLoad((query) => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  target.value = parseContentVideoTarget(query as Record<string, unknown> | undefined)
})
</script>

<template>
  <ContentVideoPage
    v-if="CONTENT_PREVIEW_ENABLED && target"
    :video-id="target.videoId"
    :city-code="target.cityCode"
    :api="contentVideoApi"
    :access-token="accessToken"
    @back="goBack"
    @browse="browse"
    @open="open"
    @login="login"
    @stream="stream"
  />
  <ContentUnavailable v-else-if="!CONTENT_PREVIEW_ENABLED" />
</template>
