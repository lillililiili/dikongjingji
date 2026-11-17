<script setup lang="ts">
import { computed } from 'vue'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { InspectionServiceDetail } from '../../../domain/inspection-service-detail'

const props = defineProps<{
  detail: InspectionServiceDetail
}>()

const serviceCitiesLabel = computed(() =>
  props.detail.serviceCityNames.length
    ? `可服务城市 ${props.detail.serviceCityNames.join('、')}`
    : '',
)

const priceLabel = computed(() => {
  if (props.detail.priceAmountMinor === null) {
    return '按项目报价'
  }

  return `¥${Math.round(props.detail.priceAmountMinor / 100)}`
})
</script>

<template>
  <view class="service-detail-hero">
    <view class="service-detail-hero__image-wrap">
      <image
        class="service-detail-hero__image"
        :src="detail.heroImageUrl"
        mode="aspectFill"
        lazy-load
        :aria-label="`${detail.title}服务现场`"
      />
      <view class="service-detail-hero__category">
        <text>{{ detail.categoryLabel }}</text>
      </view>
    </view>

    <view class="service-detail-hero__card">
      <text class="service-detail-hero__title">{{ detail.title }}</text>
      <text class="service-detail-hero__subtitle">{{ detail.subtitle }}</text>

      <view class="service-detail-hero__price-row">
        <text
          class="service-detail-hero__price"
          :class="{ 'service-detail-hero__price--quote': detail.priceAmountMinor === null }"
        >
          {{ priceLabel }}
        </text>
        <text
          v-if="detail.priceAmountMinor !== null"
          class="service-detail-hero__unit"
        >
          {{ detail.priceUnitLabel }}
        </text>
      </view>

      <view class="service-detail-hero__metrics">
        <view
          v-if="detail.ratingAverage !== null"
          class="service-detail-hero__rating"
        >
          <UniIcons type="star-filled" size="12" color="#ff8a00" />
          <text>{{ detail.ratingAverage.toFixed(1) }}分</text>
        </view>
        <text v-if="detail.reviewCount !== null">
          ·　{{ detail.reviewCount }}条评价
        </text>
        <text v-if="detail.completedCountLabel">
          ·　{{ detail.completedCountLabel }}
        </text>
      </view>

      <view
        v-if="detail.capabilities.length"
        class="service-detail-hero__capabilities"
      >
        <view
          v-for="capability in detail.capabilities"
          :key="capability.code"
          class="service-detail-hero__capability"
        >
          <UniIcons type="checkmarkempty" size="14" color="#11804a" />
          <text>{{ capability.label }}</text>
        </view>
      </view>

      <view v-if="detail.provider" class="service-detail-hero__provider">
        <image
          class="service-detail-hero__provider-logo"
          :src="detail.provider.logoUrl"
          mode="aspectFill"
          :aria-label="`${detail.provider.name}服务商标识`"
        />
        <view class="service-detail-hero__provider-copy">
          <text class="service-detail-hero__provider-name">
            {{ detail.provider.name }}
          </text>
          <view class="service-detail-hero__provider-meta">
            <text
              v-if="detail.provider.certificationLabel"
              class="service-detail-hero__certification"
            >
              {{ detail.provider.certificationLabel }}
            </text>
            <text
              v-if="serviceCitiesLabel"
              class="service-detail-hero__cities"
            >
              {{ serviceCitiesLabel }}
            </text>
            <text
              v-else-if="detail.provider.teamDescription"
              class="service-detail-hero__cities"
            >
              {{ detail.provider.teamDescription }}
            </text>
          </view>
        </view>
        <text class="service-detail-hero__provider-link">查看服务商</text>
        <UniIcons type="right" size="12" color="#627187" />
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspDetail (390 wide). */
.service-detail-hero {
  display: flex;
  flex-direction: column;
}

.service-detail-hero__image-wrap {
  position: relative;
  height: 200px;
  overflow: hidden;
  background: #eef2f8;
}

.service-detail-hero__image {
  width: 100%;
  height: 100%;
}

.service-detail-hero__category {
  position: absolute;
  top: 12px;
  left: 16px;
  display: flex;
  padding: 3px 10px;
  align-items: center;
  border-radius: 6px;
  color: #ffffff;
  background: #11804a;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
}

/* The card starts over the bottom edge of the picture. */
.service-detail-hero__card {
  position: relative;
  display: flex;
  margin: -24px 16px 0;
  padding: 14px 14px 4px;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.service-detail-hero__title {
  color: #202c42;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.service-detail-hero__subtitle {
  margin-top: 2px;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.service-detail-hero__price-row {
  display: flex;
  margin-top: 8px;
  align-items: baseline;
  gap: 2px;
}

.service-detail-hero__price {
  color: #e85400;
  font-size: 28px;
  font-weight: 700;
  line-height: 36px;
}

.service-detail-hero__price--quote {
  font-size: 22px;
}

.service-detail-hero__unit {
  color: #e85400;
  font-size: 14px;
  line-height: 20px;
}

.service-detail-hero__metrics {
  display: flex;
  margin-top: 2px;
  flex-wrap: wrap;
  align-items: center;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.service-detail-hero__metrics:empty {
  display: none;
}

.service-detail-hero__rating {
  display: flex;
  align-items: center;
  gap: 2px;
}

.service-detail-hero__capabilities {
  display: flex;
  margin-top: 10px;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 16px;
}

.service-detail-hero__capability {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.service-detail-hero__provider {
  display: flex;
  min-height: 60px;
  margin-top: 12px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.service-detail-hero__provider-logo {
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #fff0e3;
}

.service-detail-hero__provider-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.service-detail-hero__provider-name {
  max-width: 100%;
  overflow: hidden;
  color: #202c42;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-detail-hero__provider-meta {
  display: flex;
  max-width: 100%;
  align-items: center;
  gap: 6px;
}

.service-detail-hero__certification {
  padding: 1px 6px;
  flex: 0 0 auto;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 12px;
  line-height: 16px;
}

.service-detail-hero__cities {
  min-width: 0;
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-detail-hero__provider-link {
  flex: 0 0 auto;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}
</style>
