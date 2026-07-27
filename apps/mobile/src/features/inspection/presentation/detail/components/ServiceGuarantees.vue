<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { DetailCapability } from '../../../domain/inspection-service-detail'

defineProps<{
  items: readonly DetailCapability[]
}>()
</script>

<template>
  <section class="detail-section service-guarantees">
    <text class="detail-section__title">服务保障</text>
    <view class="service-guarantees__row">
      <view
        v-for="item in items"
        :key="item.code"
        class="service-guarantees__item"
      >
        <UniIcons
          :type="item.icon"
          size="24"
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

.service-guarantees__row {
  display: grid;
  margin-top: 6px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.service-guarantees__item {
  display: flex;
  min-width: 0;
  min-height: 38px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #454a52;
  font-size: 11px;
  white-space: nowrap;
}

@media (max-width: 359px) {
  .detail-section {
    margin-right: 6px;
    margin-left: 6px;
    padding-right: 7px;
    padding-left: 7px;
  }

  .service-guarantees__item {
    gap: 5px;
    font-size: 10px;
  }
}
</style>
