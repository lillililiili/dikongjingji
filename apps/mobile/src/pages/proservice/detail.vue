<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { buildDemandPublishUrl } from '@/features/demand/navigation/demand-routes'
import { PROSERVICE_PREVIEW_ENABLED } from '@/features/proservice/application/proservice-preview'
import type { ProServiceType } from '@/features/proservice/domain/proservice'
import {
  buildProviderPageUrl,
  parseProServiceDetailTarget,
} from '@/features/proservice/navigation/proservice-routes'
import ProServiceDetailPage from '@/features/proservice/presentation/detail/ProServiceDetailPage.vue'
import ProServiceUnavailable from '@/features/proservice/presentation/shared/ProServiceUnavailable.vue'

const target = ref<{ serviceId: string; cityCode: string } | null>(null)

// Back goes where the user came from; opened on its own, back is the 专业服务 channel.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('SERVICE')
}

function openProvider(providerId: string, cityCode: string): void {
  uni.navigateTo({ url: buildProviderPageUrl(providerId, cityCode) })
}

// The form opens with the type picked and 仅邀请这家 when the provider can be invited there.
function publish(type: ProServiceType, providerId: string | null): void {
  uni.navigateTo({ url: buildDemandPublishUrl({ type, ...(providerId ? { providerId } : {}) }) })
}

onLoad((query) => {
  target.value = parseProServiceDetailTarget(query as Record<string, unknown> | undefined)
})
</script>

<template>
  <ProServiceDetailPage
    v-if="PROSERVICE_PREVIEW_ENABLED && target"
    :service-id="target.serviceId"
    :city-code="target.cityCode"
    @back="goBack"
    @browse="goBack"
    @open-provider="openProvider"
    @publish="publish"
  />
  <ProServiceUnavailable v-else-if="!PROSERVICE_PREVIEW_ENABLED" />
</template>
