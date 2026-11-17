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
        <UniIcons :type="item.icon" size="16" color="#11804a" />
        <text>{{ item.label }}</text>
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
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.service-guarantees__title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.service-guarantees__row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
}

.service-guarantees__item {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}
</style>
