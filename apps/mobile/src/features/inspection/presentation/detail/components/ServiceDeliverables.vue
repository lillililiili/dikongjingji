<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import type { DetailCapability } from '../../../domain/inspection-service-detail'
import { deliverableIcon } from '../detail-line-icons'

defineProps<{
  items: readonly DetailCapability[]
}>()
</script>

<template>
  <!-- Board InspDetail 交付成果: a 36px pale-blue rounded square with a file icon, then the name. -->
  <section class="detail-section service-deliverables" data-testid="detail-deliverables">
    <text class="detail-section__title">交付成果</text>
    <!-- Three items sit side by side, icon above the name (board InspDetailPark). -->
    <view :class="['service-deliverables__grid', { 'service-deliverables__grid--three': items.length === 3 }]">
      <view
        v-for="item in items"
        :key="item.code"
        class="service-deliverables__item"
      >
        <view class="service-deliverables__icon">
          <LineIcon :d="deliverableIcon(item.label)" :size="20" color="#2f80ed" />
        </view>
        <text class="service-deliverables__name">{{ item.label }}</text>
      </view>
    </view>
  </section>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspDetail (390 wide). */
.detail-section {
  display: flex;
  margin: 0 16px;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
  box-sizing: border-box;
}

.detail-section__title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.service-deliverables__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.service-deliverables__item {
  display: flex;
  min-width: 0;
  min-height: 48px;
  padding: 0 10px 0 6px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  background: #f5f7fc;
  box-sizing: border-box;
}

.service-deliverables__grid--three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.service-deliverables__grid--three .service-deliverables__item {
  padding: 10px 4px;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
}

.service-deliverables__icon {
  display: flex;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #e6f0ff;
}

.service-deliverables__name {
  min-width: 0;
  color: #202c42;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  /* One line at 390; on a 320 phone a long name wraps rather than losing its end. */
}
</style>
