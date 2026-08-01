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
        <UniIcons :type="item.icon" size="32" color="#ff6200" />
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

.inspection-scope__grid {
  display: grid;
  margin-top: 6px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.inspection-scope__item {
  display: grid;
  min-width: 0;
  min-height: 52px;
  padding: 5px 7px;
  grid-template-columns: 36px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  border-right: 1px solid #edf0f2;
  border-bottom: 1px solid #edf0f2;
  box-sizing: border-box;
}

.inspection-scope__item:nth-child(2n) {
  border-right: 0;
}

.inspection-scope__item:nth-last-child(-n + 2) {
  border-bottom: 0;
}

.inspection-scope__copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.inspection-scope__name {
  color: #1f242b;
  font-size: 13px;
  font-weight: 650;
  line-height: 1.35;
}

.inspection-scope__description {
  color: #8b94a2;
  font-size: 9px;
  line-height: 1.35;
}

@media (max-width: 359px) {
  .detail-section {
    margin-right: 9px;
    margin-left: 9px;
    padding: 15px 12px;
  }

  .inspection-scope__item {
    padding-right: 5px;
    padding-left: 5px;
    grid-template-columns: 34px minmax(0, 1fr);
    gap: 4px;
  }

  .inspection-scope__name {
    font-size: 12px;
  }
}
</style>
