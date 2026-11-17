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
/* Sizes and colours follow the 2026-11-14 prototype board InspDetail (390 wide). */
.detail-section {
  display: flex;
  margin: 0 16px;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
  box-sizing: border-box;
}

.detail-section__title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.service-pricing {
  padding-bottom: 4px;
  gap: 4px;
}

.service-pricing__rows {
  display: flex;
  flex-direction: column;
}

.service-pricing__row {
  display: flex;
  padding: 10px 0;
  align-items: flex-start;
  gap: 12px;
}

.service-pricing__row + .service-pricing__row {
  border-top: 1px solid #f0f1f3;
}

.service-pricing__label {
  width: 56px;
  flex: 0 0 auto;
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.service-pricing__value {
  min-width: 0;
  flex: 1;
  color: #202c42;
  font-size: 13px;
  line-height: 19px;
}
</style>
