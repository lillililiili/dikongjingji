<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import type { DemandType } from '@/features/demand/domain/demand'
import { buildDemandPublishUrl } from '@/features/demand/navigation/demand-routes'
import { buildInspectionDetailUrl } from '@/features/inspection/navigation/inspection-routes'
import { PROSERVICE_PREVIEW_ENABLED } from '@/features/proservice/application/proservice-preview'
import {
  buildProServiceDetailUrl,
  parseProviderPageTarget,
} from '@/features/proservice/navigation/proservice-routes'
import ProviderPage from '@/features/proservice/presentation/provider/ProviderPage.vue'
import ProServiceUnavailable from '@/features/proservice/presentation/shared/ProServiceUnavailable.vue'

const target = ref<{ providerId: string; cityCode: string } | null>(null)

// Back goes where the user came from; opened on its own, back is the 专业服务 channel.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('SERVICE')
}

function openInspectionService(serviceId: string, cityCode: string): void {
  uni.navigateTo({ url: buildInspectionDetailUrl(serviceId, cityCode) })
}

function openProService(serviceId: string, cityCode: string): void {
  uni.navigateTo({ url: buildProServiceDetailUrl(serviceId, cityCode) })
}

// The form opens with 仅邀请这家 when the provider can be invited for the city and type.
function publish(type: DemandType | undefined, providerId: string): void {
  uni.navigateTo({ url: buildDemandPublishUrl({ ...(type ? { type } : {}), providerId }) })
}

onLoad((query) => {
  target.value = parseProviderPageTarget(query as Record<string, unknown> | undefined)
})
</script>

<template>
  <ProviderPage
    v-if="PROSERVICE_PREVIEW_ENABLED && target"
    :provider-id="target.providerId"
    :city-code="target.cityCode"
    @back="goBack"
    @browse="goBack"
    @open-inspection-service="openInspectionService"
    @open-pro-service="openProService"
    @publish="publish"
  />
  <ProServiceUnavailable v-else-if="!PROSERVICE_PREVIEW_ENABLED" />
</template>
