<script setup lang="ts">
import { computed } from 'vue'

import type { InspectionServiceDetail } from '../../../domain/inspection-service-detail'
import { resolveServicePricingRows } from '../service-pricing-rows'

const props = defineProps<{
  detail: InspectionServiceDetail
}>()

const rows = computed(() => resolveServicePricingRows(props.detail))
</script>

<template>
  <section
    v-if="rows.length"
    class="detail-section service-pricing"
    data-testid="service-pricing-card"
  >
    <text class="detail-section__title">价格与取消</text>
    <view class="service-pricing__rows">
      <view
        v-for="row in rows"
        :key="row.code"
        class="service-pricing__row"
        :data-testid="`service-pricing-${row.code.toLowerCase()}`"
      >
        <text class="service-pricing__label">{{ row.label }}</text>
        <text class="service-pricing__value">{{ row.value }}</text>
      </view>
    </view>
  </section>
</template>

<style scoped>
.detail-section {
  margin: 0 12px;
  padding: 12px 14px;
  border: 1px solid #f0f1f3;
  border-radius: 18px;
  background: #fff;
  box-sizing: border-box;
  box-shadow: 0 6px 20px rgba(30, 38, 48, 0.055);
}

.detail-section__title {
  display: block;
  color: #16191e;
  font-size: 17px;
  font-weight: 740;
}

.service-pricing__rows {
  display: flex;
  margin-top: 4px;
  flex-direction: column;
}

.service-pricing__row {
  display: flex;
  padding: 10px 0;
  align-items: flex-start;
  gap: 12px;
  border-top: 1px solid #f0f1f3;
}

.service-pricing__row:first-child {
  border-top: 0;
}

.service-pricing__label {
  flex-shrink: 0;
  width: 56px;
  color: #666b73;
  font-size: 13px;
  line-height: 19px;
  white-space: nowrap;
}

.service-pricing__value {
  min-width: 0;
  flex: 1;
  color: #191919;
  font-size: 13px;
  line-height: 19px;
  overflow-wrap: anywhere;
}

@media (max-width: 359px) {
  .detail-section {
    margin-right: 9px;
    margin-left: 9px;
    padding: 15px 12px;
  }
}
</style>
