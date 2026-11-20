<script setup lang="ts">
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

// A label that starts with an amount is a starting price: the board writes "¥699 起/公里".
function priceUnit(label: string): string {
  const main = priceMain(label)
  if (main === label) return ''
  const rest = label.slice(main.length).trim()
  return rest.startsWith('起') ? rest : `起${rest}`
}

function isQuote(label: string): boolean {
  return priceMain(label) === label
}
</script>

<template>
  <view v-if="services.length" class="hot-services">
    <view class="hot-services__heading">
      <view class="hot-services__bar" />
      <text class="hot-services__title">热门巡检服务</text>
      <button
        class="hot-services__all"
        data-testid="portal-view-all"
        @click="$emit('viewAll')"
      >
        查看全部 ›
      </button>
    </view>
    <scroll-view class="hot-services__scroll" scroll-x :show-scrollbar="false">
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
              <text
                :class="[
                  'hot-service-card__price-main',
                  { 'hot-service-card__price-main--quote': isQuote(service.priceLabel) },
                ]"
              >
                {{ priceMain(service.priceLabel) }}
              </text>
              <text class="hot-service-card__price-unit">
                {{ priceUnit(service.priceLabel) }}
              </text>
            </view>
          </view>
        </button>
      </view>
    </scroll-view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board HomeSvc (390 wide). */
.hot-services {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.hot-services__heading {
  display: flex;
  height: 24px;
  align-items: center;
  gap: 6px;
}

.hot-services__bar {
  width: 4px;
  height: 16px;
  flex: 0 0 auto;
  border-radius: 2px;
  background: #ff6a00;
}

.hot-services__title {
  color: #202c42;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.hot-services__all {
  display: flex;
  height: 44px;
  margin: -10px 0 -10px auto;
  padding: 0;
  align-items: center;
  gap: 2px;
  border: 0;
  color: #627187;
  background: transparent;
  font-size: 13px;
  line-height: 18px;
}

.hot-services__all::after {
  border: 0;
}

/* The cards run to the right edge of the screen, past the channel's gutter. */
.hot-services__scroll {
  width: calc(100% + var(--app-page-horizontal-gutter));
  white-space: nowrap;
}

.hot-services__track {
  display: inline-flex;
  padding: 0 var(--app-page-horizontal-gutter) 4px 0;
  gap: 10px;
}

.hot-service-card {
  display: flex;
  width: 156px;
  margin: 0;
  padding: 0;
  flex: 0 0 auto;
  flex-direction: column;
  overflow: hidden;
  border: 0;
  border-radius: 12px;
  color: #202c42;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  line-height: 1.2;
  text-align: left;
  white-space: normal;
}

.hot-service-card::after {
  border: 0;
}

.hot-service-card__visual {
  width: 156px;
  height: 88px;
  flex: 0 0 auto;
}

.hot-service-card__body {
  display: flex;
  padding: 8px 10px 10px;
  flex-direction: column;
  gap: 2px;
}

.hot-service-card__title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-service-card__tag {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-service-card__price {
  display: flex;
  height: 22px;
  margin-top: 4px;
  align-items: baseline;
  gap: 2px;
  white-space: nowrap;
}

.hot-service-card__price-main {
  color: #e85400;
  font-size: 17px;
  font-weight: 700;
  line-height: 22px;
}

.hot-service-card__price-main--quote {
  font-size: 15px;
}

.hot-service-card__price-unit {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}
</style>
