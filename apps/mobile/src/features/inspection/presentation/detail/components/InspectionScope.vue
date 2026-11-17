<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { DetailCapability } from '../../../domain/inspection-service-detail'

defineProps<{
  items: readonly DetailCapability[]
}>()
</script>

<template>
  <section class="detail-section inspection-scope">
    <text class="detail-section__title">服务内容</text>
    <view class="inspection-scope__grid">
      <view
        v-for="item in items"
        :key="item.code"
        class="inspection-scope__item"
      >
        <UniIcons :type="item.icon" size="20" color="#e85400" />
        <view class="inspection-scope__copy">
          <text class="inspection-scope__name">{{ item.label }}</text>
          <text class="inspection-scope__description">
            {{ item.description }}
          </text>
        </view>
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

.inspection-scope__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.inspection-scope__item {
  display: flex;
  min-height: 48px;
  padding: 6px 10px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  background: #f5f7fc;
  box-sizing: border-box;
}

.inspection-scope__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.inspection-scope__name {
  color: #202c42;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
}

.inspection-scope__description {
  color: #738096;
  font-size: 11px;
  line-height: 15px;
}

.inspection-scope__description:empty {
  display: none;
}
</style>
