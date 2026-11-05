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
      <button
        class="hot-services__all"
        data-testid="portal-view-all"
        @click="$emit('viewAll')"
      >
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
  padding: 12px 10px 10px;
  border: 1px solid #ebedf0;
  border-radius: clamp(11px, 2.8vw, 17px);
  background: #ffffff;
}

.hot-services__heading {
  display: flex;
  min-height: 28px;
  padding: 0 2px 10px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.hot-services__title-row {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 8px;
}

.hot-services__title {
  color: #191919;
  font-size: 17px;
  font-weight: 750;
  white-space: nowrap;
}

.hot-services__meta {
  overflow: hidden;
  color: #969ba3;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-services__all {
  display: flex;
  min-width: 64px;
  min-height: 28px;
  padding: 0;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  border: 0;
  color: #666b73;
  background: transparent;
  font-size: 12px;
  line-height: 1;
}

.hot-services__all::after,
.hot-service-card::after {
  border: 0;
}

.hot-services__track {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
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
  height: 82px;
}

.hot-service-card__body {
  display: flex;
  min-height: 100px;
  padding: 6px;
  flex-direction: column;
  box-sizing: border-box;
}

.hot-service-card__title {
  display: block;
  overflow: hidden;
  color: #20242a;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-service-card__tag {
  display: inline-block;
  max-width: 100%;
  align-self: flex-start;
  margin-top: 4px;
  overflow: hidden;
  padding: 1px 4px;
  border: 0.5px solid #7eb2ff;
  border-radius: clamp(3px, 0.8vw, 5px);
  color: #347dd9;
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-service-card__price {
  display: block;
  margin-top: 5px;
  overflow: hidden;
  color: #ff5a00;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-service-card__price-main {
  font-size: 12px;
}

.hot-service-card__price-unit {
  margin-left: 2px;
  color: #7f858d;
  font-size: 8px;
  font-weight: 500;
}

.hot-service-card__stats {
  display: flex;
  margin-top: auto;
  justify-content: space-between;
  gap: 2px;
  color: #969ba3;
  font-size: 8px;
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
    font-size: 7px;
  }
}

@media (min-width: 420px) {
  .hot-service-card__visual {
    height: 92px;
  }
}
</style>
