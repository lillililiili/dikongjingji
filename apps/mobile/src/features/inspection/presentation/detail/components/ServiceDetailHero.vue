<script setup lang="ts">
import { computed } from 'vue'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { InspectionServiceDetail } from '../../../domain/inspection-service-detail'

const props = defineProps<{
  detail: InspectionServiceDetail
}>()

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
      <text class="service-detail-hero__category">
        {{ detail.categoryLabel }}
      </text>
    </view>

    <view class="service-detail-hero__card">
      <text class="service-detail-hero__title">{{ detail.title }}</text>
      <text class="service-detail-hero__subtitle">{{ detail.subtitle }}</text>

      <view class="service-detail-hero__metrics">
        <view class="service-detail-hero__price-row">
          <text class="service-detail-hero__price">{{ priceLabel }}</text>
          <text
            v-if="detail.priceAmountMinor !== null"
            class="service-detail-hero__unit"
          >
            {{ detail.priceUnitLabel }}
          </text>
        </view>
        <view
          v-if="detail.ratingAverage !== null"
          class="service-detail-hero__rating"
        >
          <UniIcons type="star-filled" size="14" color="#ffad00" />
          <text>{{ detail.ratingAverage.toFixed(1) }}</text>
        </view>
        <text
          v-if="detail.completedCountLabel"
          class="service-detail-hero__served"
        >
          {{ detail.completedCountLabel }}
        </text>
      </view>

      <view class="service-detail-hero__capabilities">
        <view
          v-for="capability in detail.capabilities"
          :key="capability.code"
          class="service-detail-hero__capability"
        >
          <UniIcons
            :type="capability.icon"
            size="14"
            color="#18b85b"
          />
          <text>{{ capability.label }}</text>
        </view>
      </view>
      <text class="service-detail-hero__note">{{ detail.priceNote }}</text>
    </view>
  </view>
</template>

<style scoped>
.service-detail-hero__image-wrap {
  position: relative;
  overflow: hidden;
  height: clamp(168px, 45vw, 210px);
  background: #eaf4fb;
}

.service-detail-hero__image {
  width: 100%;
  height: 100%;
}

.service-detail-hero__category {
  position: absolute;
  top: 11px;
  left: 11px;
  padding: 6px 11px;
  border-radius: 6px;
  color: #ffffff;
  background: #18be5b;
  font-size: 12px;
  font-weight: 650;
  line-height: 1;
}

.service-detail-hero__card {
  position: relative;
  z-index: 2;
  display: flex;
  margin: -36px 7px 0;
  padding: 8px 12px 7px;
  flex-direction: column;
  border: 1px solid #edf0f2;
  border-radius: 12px;
  background: #ffffff;
  box-sizing: border-box;
  box-shadow: 0 5px 18px rgba(28, 36, 46, 0.08);
}

.service-detail-hero__title {
  color: #17191d;
  font-size: clamp(17px, 4.55vw, 20px);
  font-weight: 750;
  letter-spacing: 0.01em;
  line-height: 1.25;
}

.service-detail-hero__subtitle {
  margin-top: 4px;
  color: #656b74;
  font-size: clamp(10px, 2.8vw, 12px);
  line-height: 1.25;
}

.service-detail-hero__metrics {
  display: flex;
  min-width: 0;
  margin-top: 6px;
  align-items: center;
  gap: clamp(10px, 3vw, 16px);
  color: #5f6670;
  font-size: 11px;
}

.service-detail-hero__price-row,
.service-detail-hero__rating {
  display: flex;
  min-width: 0;
  align-items: center;
}

.service-detail-hero__price {
  color: #ff5a00;
  font-size: clamp(22px, 5.8vw, 26px);
  font-weight: 750;
  line-height: 1;
}

.service-detail-hero__unit {
  margin-left: 4px;
  color: #686e77;
  font-size: 10px;
  white-space: nowrap;
}

.service-detail-hero__rating {
  gap: 3px;
  white-space: nowrap;
}

.service-detail-hero__served {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-detail-hero__capabilities {
  display: grid;
  margin-top: 7px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
}

.service-detail-hero__capability {
  display: flex;
  min-width: 0;
  min-height: 25px;
  padding: 5px 5px;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 7px;
  color: #535962;
  background: #f8f9fa;
  box-sizing: border-box;
  font-size: 10px;
  white-space: nowrap;
}

.service-detail-hero__note {
  margin-top: 5px;
  color: #9a9fa6;
  font-size: 10px;
}

@media (max-width: 359px) {
  .service-detail-hero__card {
    margin-right: 7px;
    margin-left: 7px;
    padding-right: 10px;
    padding-left: 10px;
  }

  .service-detail-hero__metrics {
    gap: 7px;
    font-size: 10px;
  }

  .service-detail-hero__capability {
    padding-right: 4px;
    padding-left: 4px;
    font-size: 9px;
  }
}
</style>
