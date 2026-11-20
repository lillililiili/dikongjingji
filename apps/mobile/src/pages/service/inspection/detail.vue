<script setup lang="ts">
import type {
  PublishInspectionDemandIntent,
  ReserveServiceIntent,
} from '@/features/inspection/application/detail/inspection-detail-actions'
import { buildInspectionBookingUrl } from '@/features/inspection/navigation/inspection-routes'
import InspectionServiceDetailPage from '@/features/inspection/presentation/detail/pages/InspectionServiceDetailPage.vue'
import { DEMAND_PREVIEW_ENABLED } from '@/features/demand/application/demand-preview'
import { buildDemandPublishUrl } from '@/features/demand/navigation/demand-routes'
import { createPlatformFeatureGateway } from '@/features/platform/application/platform-feature-gateway'
import { PROSERVICE_PREVIEW_ENABLED } from '@/features/proservice/application/proservice-preview'
import { buildProviderPageUrl } from '@/features/proservice/navigation/proservice-routes'

// 发布需求 (M15) is open in preview builds only; public builds keep 立即预约 on every service.
const featureGateway = createPlatformFeatureGateway({
  PUBLISH_DEMAND: DEMAND_PREVIEW_ENABLED ? 'ENABLED' : 'HIDDEN',
})
const demandPublishEnabled =
  featureGateway.resolve({ type: 'PUBLISH_DEMAND', channel: 'INSPECTION' }).action === 'EXECUTE'

function handleReserve(intent: ReserveServiceIntent): void {
  uni.navigateTo({
    url: buildInspectionBookingUrl(intent.serviceId, intent.cityCode),
  })
}

// The demand form with 巡检 picked; it invites this provider only when the provider can be invited.
function handlePublishDemand(intent: PublishInspectionDemandIntent): void {
  if (!demandPublishEnabled) return
  uni.navigateTo({
    url: buildDemandPublishUrl({
      type: 'INSPECTION',
      ...(intent.providerId ? { providerId: intent.providerId } : {}),
    }),
  })
}

// 查看服务商: the provider page (F2) is open in preview builds only; public builds show no link.
function openProvider(providerId: string, cityCode: string): void {
  uni.navigateTo({ url: buildProviderPageUrl(providerId, cityCode) })
}
</script>

<template>
  <InspectionServiceDetailPage
    :provider-page-enabled="PROSERVICE_PREVIEW_ENABLED"
    :demand-publish-enabled="demandPublishEnabled"
    @reserve="handleReserve"
    @publish-demand="handlePublishDemand"
    @open-provider="openProvider"
  />
</template>
