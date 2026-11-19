<script setup lang="ts">
import type { ReserveServiceIntent } from '@/features/inspection/application/detail/inspection-detail-actions'
import { buildInspectionBookingUrl } from '@/features/inspection/navigation/inspection-routes'
import InspectionServiceDetailPage from '@/features/inspection/presentation/detail/pages/InspectionServiceDetailPage.vue'
import { PROSERVICE_PREVIEW_ENABLED } from '@/features/proservice/application/proservice-preview'
import { buildProviderPageUrl } from '@/features/proservice/navigation/proservice-routes'

function handleReserve(intent: ReserveServiceIntent): void {
  uni.navigateTo({
    url: buildInspectionBookingUrl(intent.serviceId, intent.cityCode),
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
    @reserve="handleReserve"
    @open-provider="openProvider"
  />
</template>
