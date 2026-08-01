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
          <UniIcons :type="item.icon" size="27" color="#ff6200" />
        </view>
        <text>{{ item.label }}</text>
      </view>
    </view>
  </section>
</template>

<style scoped>
.detail-section {
  margin: 0 12px;
  padding: 13px 12px;
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

.service-deliverables__grid {
  display: grid;
  margin-top: 9px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.service-deliverables__item {
  display: flex;
  min-width: 0;
  align-items: center;
  flex-direction: column;
  gap: 5px;
  color: #323a46;
  font-size: 11px;
  line-height: 1.25;
  text-align: center;
}

.service-deliverables__image,
.service-deliverables__icon {
  width: 100%;
  height: clamp(58px, 17vw, 74px);
  border-radius: 7px;
  background: #f4f5f7;
}

.service-deliverables__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #eceff2;
  box-sizing: border-box;
}

@media (max-width: 359px) {
  .detail-section {
    margin-right: 9px;
    margin-left: 9px;
    padding-right: 10px;
    padding-left: 10px;
  }

  .service-deliverables__grid {
    gap: 5px;
  }

  .service-deliverables__item {
    font-size: 9px;
  }
}
</style>
