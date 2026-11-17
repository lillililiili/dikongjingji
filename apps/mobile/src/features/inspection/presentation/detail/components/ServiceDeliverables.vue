<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { DetailCapability } from '../../../domain/inspection-service-detail'

defineProps<{
  items: readonly DetailCapability[]
}>()
</script>

<template>
  <section class="detail-section service-deliverables">
    <text class="detail-section__title">交付成果</text>
    <view class="service-deliverables__grid">
      <view
        v-for="item in items"
        :key="item.code"
        class="service-deliverables__item"
      >
        <image
          v-if="item.imageUrl"
          class="service-deliverables__image"
          :src="item.imageUrl"
          mode="aspectFill"
          :aria-label="item.label"
        />
        <view v-else class="service-deliverables__icon">
          <UniIcons :type="item.icon" size="20" color="#1d63c4" />
        </view>
        <text>{{ item.label }}</text>
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

.service-deliverables__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.service-deliverables__item {
  display: flex;
  min-height: 48px;
  padding: 6px 10px 6px 6px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  color: #202c42;
  background: #f5f7fc;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  box-sizing: border-box;
}

.service-deliverables__image,
.service-deliverables__icon {
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  border-radius: 8px;
}

.service-deliverables__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #e6f0ff;
}
</style>
