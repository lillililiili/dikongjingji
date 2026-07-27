<script setup lang="ts">
import type { DetailExplanation } from '../../../domain/inspection-service-detail'

defineProps<{
  items: readonly DetailExplanation[]
}>()
</script>

<template>
  <section class="detail-section service-explanation">
    <text class="detail-section__title">服务说明</text>
    <view class="service-explanation__list">
      <view
        v-for="item in items"
        :key="item.label"
        class="service-explanation__row"
      >
        <text class="service-explanation__label">{{ item.label }}</text>
        <text class="service-explanation__value">{{ item.value }}</text>
      </view>
    </view>
  </section>
</template>

<style scoped>
.detail-section {
  margin: 0 7px;
  padding: 9px;
  border: 1px solid #edf0f2;
  border-radius: 11px;
  background: #ffffff;
  box-sizing: border-box;
  box-shadow: 0 3px 14px rgba(28, 36, 46, 0.055);
}

.detail-section__title {
  display: block;
  color: #1d2024;
  font-size: 14px;
  font-weight: 700;
}

.service-explanation__list {
  margin-top: 4px;
}

.service-explanation__row {
  display: grid;
  min-height: 32px;
  padding: 6px 0;
  grid-template-columns: 64px minmax(0, 1fr);
  align-items: start;
  border-bottom: 1px solid #eef0f2;
  box-sizing: border-box;
}

.service-explanation__row:last-child {
  padding-bottom: 2px;
  border-bottom: 0;
}

.service-explanation__label {
  color: #5c626a;
  font-size: 11px;
}

.service-explanation__value {
  min-width: 0;
  color: #3f444c;
  font-size: 11px;
  line-height: 1.35;
}

@media (max-width: 359px) {
  .detail-section {
    margin-right: 6px;
    margin-left: 6px;
    padding-right: 7px;
    padding-left: 7px;
  }

  .service-explanation__row {
    grid-template-columns: 58px minmax(0, 1fr);
  }

  .service-explanation__label,
  .service-explanation__value {
    font-size: 10px;
  }
}
</style>
