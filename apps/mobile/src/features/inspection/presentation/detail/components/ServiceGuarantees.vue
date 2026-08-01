<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { DetailCapability } from '../../../domain/inspection-service-detail'

defineProps<{
  items: readonly DetailCapability[]
}>()
</script>

<template>
  <section class="service-guarantees">
    <text class="service-guarantees__title">服务保障</text>
    <view class="service-guarantees__row">
      <view
        v-for="item in items"
        :key="item.code"
        class="service-guarantees__item"
      >
        <UniIcons :type="item.icon" size="25" color="#ff6200" />
        <text>{{ item.label }}</text>
      </view>
    </view>
  </section>
</template>

<style scoped>
.service-guarantees {
  margin: 0 12px;
  padding: 12px 14px;
  border: 1px solid #f0f1f3;
  border-radius: 18px;
  background: #fff;
  box-sizing: border-box;
  box-shadow: 0 6px 20px rgba(30, 38, 48, 0.055);
}

.service-guarantees__title {
  display: block;
  color: #16191e;
  font-size: 17px;
  font-weight: 740;
}

.service-guarantees__row {
  display: grid;
  margin-top: 7px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.service-guarantees__item {
  display: flex;
  min-width: 0;
  min-height: 34px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-right: 1px solid #eceff2;
  color: #354052;
  font-size: 11px;
  white-space: nowrap;
}

.service-guarantees__item:last-child {
  border-right: 0;
}

@media (max-width: 359px) {
  .service-guarantees {
    margin-right: 9px;
    margin-left: 9px;
    padding-right: 10px;
    padding-left: 10px;
  }

  .service-guarantees__item {
    gap: 5px;
    font-size: 10px;
  }
}
</style>
