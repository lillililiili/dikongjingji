<script setup lang="ts">
import type { ReserveServiceIntent } from '@/features/inspection/application/detail/inspection-detail-actions'
import { buildInspectionBookingUrl } from '@/features/inspection/navigation/inspection-routes'
import InspectionServiceDetailPage from '@/features/inspection/presentation/detail/pages/InspectionServiceDetailPage.vue'

function handleReserve(intent: ReserveServiceIntent): void {
  uni.navigateTo({
    url: buildInspectionBookingUrl(intent.serviceId, intent.cityCode),
  })
}
</script>

<template>
  <InspectionServiceDetailPage @reserve="handleReserve" />
</template>
