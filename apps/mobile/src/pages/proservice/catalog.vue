<script setup lang="ts">
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { buildDemandPublishUrl } from '@/features/demand/navigation/demand-routes'
import { PROSERVICE_PREVIEW_ENABLED } from '@/features/proservice/application/proservice-preview'
import type { ProServiceType } from '@/features/proservice/domain/proservice'
import {
  buildProServiceDetailUrl,
  parseProServiceCatalogTarget,
  type ProServiceCatalogTarget,
} from '@/features/proservice/navigation/proservice-routes'
import ProServiceCatalogPage from '@/features/proservice/presentation/catalog/ProServiceCatalogPage.vue'
import ProServiceUnavailable from '@/features/proservice/presentation/shared/ProServiceUnavailable.vue'

const target = ref<ProServiceCatalogTarget | null>(null)
const catalog = ref<{ loadMore(): void } | null>(null)

// Back goes where the user came from; opened on its own, back is the 专业服务 channel.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('SERVICE')
}

function openService(serviceId: string, cityCode: string): void {
  uni.navigateTo({ url: buildProServiceDetailUrl(serviceId, cityCode) })
}

function publish(type: ProServiceType): void {
  uni.navigateTo({ url: buildDemandPublishUrl({ type }) })
}

onLoad((query) => {
  target.value = parseProServiceCatalogTarget(query as Record<string, unknown> | undefined)
})

onReachBottom(() => {
  catalog.value?.loadMore()
})
</script>

<template>
  <ProServiceCatalogPage
    v-if="PROSERVICE_PREVIEW_ENABLED && target"
    ref="catalog"
    :target="target"
    @back="goBack"
    @open-service="openService"
    @publish="publish"
  />
  <ProServiceUnavailable v-else-if="!PROSERVICE_PREVIEW_ENABLED" />
</template>
