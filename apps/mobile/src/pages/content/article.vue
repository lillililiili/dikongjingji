<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { CONTENT_PREVIEW_ENABLED } from '@/features/content/application/content-preview'
import { parseContentArticleTarget } from '@/features/content/navigation/content-routes'
import ContentArticlePage from '@/features/content/presentation/article/ContentArticlePage.vue'
import ContentUnavailable from '@/features/content/presentation/shared/ContentUnavailable.vue'
import { openSupportChat } from '@/features/support/application/support-entry'
import type { SupportTarget } from '@/features/support/navigation/support-routes'

const DISCOVER_PATH = '/pages/discover/index'

const target = ref<{ articleId: string; cityCode: string } | null>(null)

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

// 咨询服务商 / 咨询商家 (step G1): the conversation with the author's organization.
function consult(supportTarget: SupportTarget): void {
  openSupportChat(supportTarget)
}

onLoad((query) => {
  target.value = parseContentArticleTarget(query as Record<string, unknown> | undefined)
})
</script>

<template>
  <ContentArticlePage
    v-if="CONTENT_PREVIEW_ENABLED && target"
    :article-id="target.articleId"
    :city-code="target.cityCode"
    @back="goBack"
    @browse="browse"
    @open="open"
    @consult="consult"
  />
  <ContentUnavailable v-else-if="!CONTENT_PREVIEW_ENABLED" />
</template>
