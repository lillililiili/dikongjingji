<script setup lang="ts">
import type { ReserveServiceIntent } from '@/features/inspection/application/detail/inspection-detail-actions'
import InspectionServiceDetailPage from '@/features/inspection/presentation/detail/pages/InspectionServiceDetailPage.vue'

function handleReserve(intent: ReserveServiceIntent): void {
  uni.showToast({
    title: `已选择服务 ${intent.serviceId}，需求流程待接入`,
    icon: 'none',
  })
}
</script>

<template>
  <InspectionServiceDetailPage @reserve="handleReserve" />
</template>
