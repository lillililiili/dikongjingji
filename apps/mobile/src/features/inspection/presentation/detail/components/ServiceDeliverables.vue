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
  padding: 5px;
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

.service-deliverables__grid {
  display: grid;
  margin-top: 5px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 4px;
}

.service-deliverables__item {
  display: flex;
  min-width: 0;
  min-height: 46px;
  padding: 4px 3px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 4px;
  border: 1px solid #e8ebee;
  border-radius: 10px;
  color: #4d535c;
  box-sizing: border-box;
  font-size: 9px;
  line-height: 1.25;
  text-align: center;
}

@media (max-width: 359px) {
  .detail-section {
    margin-right: 6px;
    margin-left: 6px;
    padding-right: 7px;
    padding-left: 7px;
  }

  .service-deliverables__grid {
    gap: 5px;
  }

  .service-deliverables__item {
    padding-right: 2px;
    padding-left: 2px;
    font-size: 8px;
  }
}
</style>
