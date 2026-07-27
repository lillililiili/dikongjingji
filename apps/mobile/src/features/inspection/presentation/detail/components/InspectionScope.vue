<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { DetailCapability } from '../../../domain/inspection-service-detail'

defineProps<{
  items: readonly DetailCapability[]
}>()
</script>

<template>
  <section class="detail-section inspection-scope">
    <text class="detail-section__title">巡检项目</text>
    <view class="inspection-scope__grid">
      <view
        v-for="item in items"
        :key="item.code"
        class="inspection-scope__item"
      >
        <UniIcons
          :type="item.icon"
          size="25"
          :color="item.tone === 'green' ? '#18b85b' : '#ff6a00'"
        />
        <text>{{ item.label }}</text>
      </view>
    </view>
  </section>
</template>

<style scoped>
.detail-section {
  margin: 0 7px;
  padding: 7px;
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

.inspection-scope__grid {
  display: grid;
  margin-top: 5px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px;
}

.inspection-scope__item {
  display: flex;
  min-width: 0;
  min-height: 35px;
  padding: 4px 7px;
  align-items: center;
  gap: 8px;
  border: 1px solid #e8ebee;
  border-radius: 10px;
  color: #444a53;
  box-sizing: border-box;
  font-size: 11px;
}

@media (max-width: 359px) {
  .detail-section {
    margin-right: 6px;
    margin-left: 6px;
    padding: 8px 7px;
  }

  .inspection-scope__item {
    padding-right: 8px;
    padding-left: 8px;
    gap: 7px;
    font-size: 10px;
  }
}
</style>
