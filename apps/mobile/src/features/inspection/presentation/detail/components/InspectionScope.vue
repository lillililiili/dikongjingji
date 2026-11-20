<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import type { DetailCapability } from '../../../domain/inspection-service-detail'
import { scopeIcon } from '../detail-line-icons'

defineProps<{
  items: readonly DetailCapability[]
  categoryCode?: string
}>()
</script>

<template>
  <!-- Board InspDetail 服务内容: two columns of one-line tiles with an orange line icon. -->
  <section class="detail-section inspection-scope" data-testid="detail-scope">
    <text class="detail-section__title">服务内容</text>
    <view class="inspection-scope__grid">
      <view
        v-for="(item, index) in items"
        :key="item.code"
        class="inspection-scope__item"
      >
        <LineIcon :d="scopeIcon(categoryCode ?? '', index)" :size="24" color="#ff6a00" />
        <text class="inspection-scope__name">{{ item.label }}</text>
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

.inspection-scope__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.inspection-scope__item {
  display: flex;
  min-width: 0;
  min-height: 48px;
  padding: 0 10px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  background: #f5f7fc;
  box-sizing: border-box;
}

.inspection-scope__name {
  min-width: 0;
  color: #202c42;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  /* One line at 390; on a 320 phone a long name wraps rather than losing its end. */
}
</style>
