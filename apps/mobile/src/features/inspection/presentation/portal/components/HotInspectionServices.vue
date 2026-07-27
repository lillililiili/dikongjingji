<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { InspectionPortalHotService } from '../../../domain/inspection-portal-content'

defineProps<{
  services: readonly InspectionPortalHotService[]
}>()

defineEmits<{
  select: [service: InspectionPortalHotService]
  viewAll: []
}>()

function priceMain(label: string): string {
  return label.match(/^¥\d+/)?.[0] ?? label
}

function priceUnit(label: string): string {
  return label.slice(priceMain(label).length)
}
</script>

<template>
  <view v-if="services.length" class="hot-services">
    <view class="hot-services__heading">
      <view class="hot-services__title-row">
        <text class="hot-services__title">热门巡检服务</text>
        <UniIcons type="fire-filled" size="14" color="#ff5a00" />
        <text class="hot-services__meta">专业服务 · 安全可靠</text>
      </view>
      <button class="hot-services__all" @click="$emit('viewAll')">
        查看全部
        <UniIcons type="right" size="12" color="#666b73" />
      </button>
    </view>

    <view class="hot-services__track">
      <button
        v-for="service in services"
        v-show="service.imageUrl"
        :key="service.id"
        class="hot-service-card"
        @click="$emit('select', service)"
      >
        <image
          class="hot-service-card__visual"
          :src="service.imageUrl"
          mode="aspectFill"
          lazy-load
          :aria-label="service.title"
        />
        <view class="hot-service-card__body">
          <text class="hot-service-card__title">{{ service.title }}</text>
          <text class="hot-service-card__tag">{{ service.tag }}</text>
          <view v-if="service.priceLabel" class="hot-service-card__price">
            <text class="hot-service-card__price-main">
              {{ priceMain(service.priceLabel) }}
            </text>
            <text class="hot-service-card__price-unit">
              {{ priceUnit(service.priceLabel) }}
            </text>
          </view>
          <view
            v-if="service.rating || service.servedCountLabel"
            class="hot-service-card__stats"
          >
            <view v-if="service.rating" class="hot-service-card__rating">
              <UniIcons type="star-filled" size="8" color="#ffad00" />
              <text>{{ service.rating }}</text>
            </view>
            <text v-if="service.servedCountLabel">
              {{ service.servedCountLabel }}
            </text>
          </view>
        </view>
      </button>
    </view>
  </view>
</template>

<style scoped>
.hot-services {
  padding:
    clamp(7px, 1.4vw, 8px)
    clamp(7px, 1.9vw, 11px)
    clamp(8px, 1.7vw, 10px);
  border: 1px solid #ebedf0;
  border-radius: clamp(11px, 2.8vw, 17px);
  background: #ffffff;
}

.hot-services__heading {
  display: flex;
  min-height: clamp(24px, 5.4vw, 32px);
  padding:
    0
    clamp(2px, 0.5vw, 3px)
    clamp(7px, 1.2vw, 7px);
  align-items: center;
  justify-content: space-between;
  gap: clamp(8px, 2.1vw, 13px);
}

.hot-services__title-row {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: clamp(6px, 1.6vw, 10px);
}

.hot-services__title {
  color: #191919;
  font-size: clamp(15px, 3.4vw, 20px);
  font-weight: 750;
  white-space: nowrap;
}

.hot-services__meta {
  overflow: hidden;
  color: #969ba3;
  font-size: clamp(9px, 2.4vw, 14px);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-services__all {
  display: flex;
  min-width: clamp(58px, 15.5vw, 93px);
  min-height: clamp(24px, 5.4vw, 32px);
  padding: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  border: 0;
  color: #666b73;
  background: transparent;
  font-size: clamp(10px, 2.6vw, 15px);
  line-height: 1;
}

.hot-services__all::after,
.hot-service-card::after {
  border: 0;
}

.hot-services__track {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: clamp(6px, 1.6vw, 10px);
}

.hot-service-card {
  min-width: 0;
  overflow: hidden;
  padding: 0;
  border: 1px solid #edf0f3;
  border-radius: clamp(7px, 1.9vw, 11px);
  background: #ffffff;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.04);
  line-height: 1.2;
  text-align: left;
}

.hot-service-card__visual {
  display: block;
  width: 100%;
  height: clamp(48px, 12.8vw, 77px);
}

.hot-service-card__body {
  display: flex;
  min-height: clamp(57px, 15.2vw, 91px);
  padding:
    clamp(5px, 1.3vw, 8px)
    clamp(5px, 1.3vw, 8px)
    clamp(6px, 1.6vw, 10px);
  flex-direction: column;
  box-sizing: border-box;
}

.hot-service-card__title {
  display: block;
  overflow: hidden;
  color: #20242a;
  font-size: clamp(11px, 2.9vw, 17px);
  font-weight: 700;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-service-card__tag {
  display: inline-block;
  max-width: 100%;
  align-self: flex-start;
  margin-top: clamp(3px, 0.8vw, 5px);
  overflow: hidden;
  padding:
    clamp(1px, 0.3vw, 2px)
    clamp(4px, 1.1vw, 7px);
  border: 0.5px solid #7eb2ff;
  border-radius: clamp(3px, 0.8vw, 5px);
  color: #347dd9;
  font-size: clamp(7px, 1.8vw, 11px);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-service-card__price {
  display: block;
  margin-top: clamp(4px, 1.1vw, 7px);
  overflow: hidden;
  color: #ff5a00;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-service-card__price-main {
  font-size: clamp(10px, 2.6vw, 15px);
}

.hot-service-card__price-unit {
  margin-left: 2px;
  color: #7f858d;
  font-size: clamp(6.5px, 1.7vw, 10px);
  font-weight: 500;
}

.hot-service-card__stats {
  display: flex;
  margin-top: auto;
  justify-content: space-between;
  gap: 2px;
  color: #969ba3;
  font-size: clamp(6.5px, 1.7vw, 10px);
  white-space: nowrap;
}

.hot-service-card__rating {
  display: flex;
  align-items: center;
  gap: 1px;
  color: #7d838c;
}

@media (max-width: 359px) {
  .hot-services {
    padding-right: 5px;
    padding-left: 5px;
  }

  .hot-services__track {
    gap: 4px;
  }

  .hot-service-card__body {
    padding-right: 3px;
    padding-left: 3px;
  }

  .hot-service-card__stats {
    font-size: 5.8px;
  }
}
</style>
