<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import type { DetailCapability } from '../../../domain/inspection-service-detail'
import { guaranteeIcon } from '../detail-line-icons'

defineProps<{
  items: readonly DetailCapability[]
}>()
</script>

<template>
  <!-- Board InspDetail 服务保障: three pale-green pills, each with a shield icon. -->
  <section class="service-guarantees" data-testid="detail-guarantees">
    <text class="service-guarantees__title">服务保障</text>
    <view class="service-guarantees__row">
      <view
        v-for="item in items"
        :key="item.code"
        class="service-guarantees__item"
      >
        <LineIcon :d="guaranteeIcon(item.label)" :size="18" color="#159a70" :stroke-width="2" />
        <text class="service-guarantees__label">{{ item.label }}</text>
      </view>
    </view>
  </section>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspDetail (390 wide). */
.service-guarantees {
  display: flex;
  margin: 0 16px;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.service-guarantees__title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.service-guarantees__row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.service-guarantees__item {
  display: flex;
  min-width: 0;
  height: 36px;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 10px;
  background: #eefaf3;
}

.service-guarantees__label {
  overflow: hidden;
  color: #11804a;
  font-size: 13px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
