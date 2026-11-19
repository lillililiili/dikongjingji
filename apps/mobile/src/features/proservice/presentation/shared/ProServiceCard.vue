<script setup lang="ts">
import { computed } from 'vue'

import { formatProServicePrice } from '../../application/proservice-view'
import type { ProServiceCard } from '../../domain/proservice'
import ProServiceCover from './ProServiceCover.vue'

// One service in the catalog and on the 专业服务 channel (SvcCatalog board card, 129px high).
const props = defineProps<{
  service: ProServiceCard
}>()

const emit = defineEmits<{
  open: [serviceId: string]
}>()

const price = computed(() => formatProServicePrice(props.service.price))
</script>

<template>
  <button
    class="pro-card"
    :data-testid="`pro-service-card-${service.serviceId}`"
    :data-price-mode="service.price.mode"
    :aria-label="`${service.title}，${price.amount}${price.unit}`"
    @click="emit('open', service.serviceId)"
  >
    <view class="pro-card__cover">
      <ProServiceCover
        :type="service.type"
        :category="service.category"
        :cover-url="service.coverUrl"
        :title="service.title"
      />
      <text v-if="service.sample" class="pro-card__sample" data-testid="pro-service-sample">示例</text>
    </view>
    <view class="pro-card__body">
      <text class="pro-card__title">{{ service.title }}</text>
      <text class="pro-card__summary">{{ service.summary }}</text>
      <view v-if="service.tags.length" class="pro-card__tags">
        <text v-for="tag in service.tags" :key="tag" class="pro-card__tag">{{ tag }}</text>
      </view>
      <view class="pro-card__foot">
        <view class="pro-card__price">
          <view class="pro-card__amount-line">
            <text :class="['pro-card__amount', { 'pro-card__amount--quote': price.quote }]">{{ price.amount }}</text>
            <text v-if="price.unit" class="pro-card__unit">{{ price.unit }}</text>
          </view>
          <text v-if="price.note" class="pro-card__note">{{ price.note }}</text>
        </view>
        <text class="pro-card__action">详情</text>
      </view>
    </view>
  </button>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board SvcCatalog (390 wide). */
.pro-card {
  display: flex;
  width: 100%;
  min-height: 129px;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 14px;
  overflow: hidden;
  color: #202c42;
  background: #ffffff;
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
}

.pro-card::after {
  border: 0;
}

.pro-card__cover {
  position: relative;
  width: 118px;
  min-height: 129px;
  flex: 0 0 118px;
}

.pro-card__sample {
  position: absolute;
  bottom: 6px;
  left: 6px;
  padding: 0 5px;
  border-radius: 6px;
  color: #ffffff;
  background: rgba(32, 44, 66, 0.55);
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.pro-card__body {
  display: flex;
  min-width: 0;
  padding: 10px;
  flex: 1 1 auto;
  flex-direction: column;
}

.pro-card__title {
  overflow: hidden;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pro-card__summary {
  overflow: hidden;
  margin-top: 2px;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pro-card__tags {
  display: flex;
  margin-top: 6px;
  gap: 4px;
  overflow: hidden;
}

.pro-card__tag {
  height: 20px;
  flex: 0 0 auto;
  padding: 0 5px;
  border: 1px solid #159a70;
  border-radius: 6px;
  color: #11804a;
  font-size: 11px;
  line-height: 18px;
  white-space: nowrap;
  box-sizing: border-box;
}

.pro-card__foot {
  display: flex;
  min-height: 36px;
  margin-top: auto;
  padding-top: 6px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.pro-card__price {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.pro-card__amount-line {
  display: flex;
  height: 20px;
  align-items: baseline;
  gap: 2px;
  white-space: nowrap;
}

.pro-card__amount {
  color: #e85400;
  font-size: 17px;
  font-weight: 700;
  line-height: 20px;
}

.pro-card__amount--quote {
  font-size: 15px;
}

.pro-card__unit {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.pro-card__note {
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pro-card__action {
  display: flex;
  height: 32px;
  flex: 0 0 auto;
  align-items: center;
  padding: 0 14px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}
</style>
