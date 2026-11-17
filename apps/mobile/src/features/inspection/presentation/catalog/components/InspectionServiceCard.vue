<script setup lang="ts">
import { computed } from 'vue'

import {
  formatCatalogDistance,
  formatCatalogPrice,
  type InspectionCatalogService,
} from '../model/inspection-catalog'

const props = defineProps<{
  service: InspectionCatalogService
  /** True while the list is sorted by distance; the card then gets one more line. */
  showDistance?: boolean
}>()

defineEmits<{
  detail: [serviceId: string]
}>()

const displayPrice = computed(() =>
  formatCatalogPrice(props.service.price),
)
const distanceLabel = computed(() =>
  props.showDistance && props.service.distanceMeters !== undefined
    ? formatCatalogDistance(props.service.distanceMeters)
    : '',
)
</script>

<template>
  <article
    class="inspection-card"
    :data-testid="`catalog-service-${service.id}`"
    :aria-label="`${service.title}，查看详情`"
    @click="$emit('detail', service.id)"
  >
    <view class="inspection-card__media">
      <image
        class="inspection-card__image"
        :src="service.imageUrl"
        mode="aspectFill"
        :style="{ objectPosition: service.imagePosition ?? '50% 50%' }"
        lazy-load
      />
      <text
        :class="[
          'inspection-card__category',
          `inspection-card__category--${service.category.toLowerCase()}`,
        ]"
      >
        {{ service.categoryLabel }}
      </text>
      <text
        v-if="service.badgeLabel"
        class="inspection-card__badge"
      >
        {{ service.badgeLabel }}
      </text>
    </view>

    <view class="inspection-card__body">
      <text class="inspection-card__title">{{ service.title }}</text>
      <text class="inspection-card__description">
        {{ service.description }}
      </text>
      <view class="inspection-card__tags">
        <text
          v-for="tag in service.tags.slice(0, 3)"
          :key="tag"
          class="inspection-card__tag"
        >
          {{ tag }}
        </text>
      </view>

      <view class="inspection-card__footer">
        <view class="inspection-card__pricing">
          <view class="inspection-card__price">
            <text class="inspection-card__price-value">
              {{ displayPrice.amountLabel }}
            </text>
            <text
              v-if="displayPrice.unitLabel"
              class="inspection-card__price-unit"
            >
              /{{ displayPrice.unitLabel }}起
            </text>
          </view>
          <text
            v-if="service.priceNote"
            class="inspection-card__price-note"
          >
            {{ service.priceNote }}
          </text>
          <text
            v-if="distanceLabel"
            class="inspection-card__price-note inspection-card__distance"
          >
            {{ distanceLabel }}
          </text>
        </view>
        <button
          class="inspection-card__action"
          @click.stop="$emit('detail', service.id)"
        >
          <text class="inspection-card__action-label">详情</text>
        </button>
      </view>
    </view>
  </article>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspCatalog (390 wide). */
.inspection-card {
  display: flex;
  overflow: hidden;
  border-radius: 14px;
  color: #202c42;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.inspection-card__media {
  position: relative;
  display: flex;
  width: 118px;
  flex: 0 0 auto;
  align-self: stretch;
  background: #eef0f3;
}

.inspection-card__image {
  width: 100%;
  height: 100%;
  min-height: 118px;
}

.inspection-card__category {
  position: absolute;
  top: 0;
  left: 0;
  padding: 1px 8px;
  border-radius: 0 0 8px 0;
  color: #ffffff;
  background: #11804a;
  font-size: 11px;
  font-weight: 500;
  line-height: 17px;
}

.inspection-card__category--photovoltaic {
  background: #6a4fe0;
}

.inspection-card__category--oil_gas,
.inspection-card__category--periodic {
  background: #1d63c4;
}

.inspection-card__category--thermal {
  background: #ba470b;
}

.inspection-card__badge {
  position: absolute;
  right: 0;
  bottom: 0;
  padding: 1px 8px;
  border-radius: 8px 0 0 0;
  color: #ffffff;
  background: rgba(32, 44, 66, 0.7);
  font-size: 11px;
  line-height: 17px;
}

.inspection-card__body {
  display: flex;
  min-width: 0;
  padding: 10px;
  flex: 1;
  flex-direction: column;
}

.inspection-card__title {
  overflow: hidden;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-card__description {
  margin-top: 2px;
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-card__tags {
  display: flex;
  height: 20px;
  margin-top: 6px;
  gap: 4px;
  overflow: hidden;
}

.inspection-card__tag {
  height: 20px;
  padding: 0 5px;
  flex: 0 0 auto;
  border: 1px solid #159a70;
  border-radius: 6px;
  color: #11804a;
  font-size: 11px;
  line-height: 18px;
  white-space: nowrap;
  box-sizing: border-box;
}

.inspection-card__footer {
  display: flex;
  margin-top: 6px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.inspection-card__pricing {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.inspection-card__price {
  display: flex;
  height: 20px;
  align-items: baseline;
  gap: 2px;
  white-space: nowrap;
}

.inspection-card__price-value {
  color: #e85400;
  font-size: 17px;
  font-weight: 700;
  line-height: 20px;
}

.inspection-card__price-unit {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.inspection-card__price-note {
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-card__distance {
  color: #627187;
}

.inspection-card__action {
  display: flex;
  height: 32px;
  margin: 0;
  padding: 0 14px;
  flex: 0 0 auto;
  align-items: center;
  border: 0;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  line-height: 1.2;
}

.inspection-card__action::after {
  border: 0;
}

.inspection-card__action-label {
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
}
</style>
