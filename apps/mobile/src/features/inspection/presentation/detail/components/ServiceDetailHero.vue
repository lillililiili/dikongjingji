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
        <UniIcons type="auth-filled" size="15" color="#fff" />
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
          <UniIcons type="star-filled" size="17" color="#ff8a00" />
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
          <UniIcons type="checkbox-filled" size="16" color="#12af59" />
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
        <UniIcons type="right" size="17" color="#303744" />
      </view>
    </view>
  </view>
</template>

<style scoped>
.service-detail-hero__image-wrap {
  position: relative;
  overflow: hidden;
  height: clamp(208px, 56vw, 240px);
  background: #eaf4fb;
}

.service-detail-hero__image {
  width: 100%;
  height: 100%;
}

.service-detail-hero__category {
  position: absolute;
  top: 18px;
  left: 18px;
  display: flex;
  height: 32px;
  padding: 0 12px;
  align-items: center;
  gap: 5px;
  border-radius: 8px;
  color: #fff;
  background: #08ac58;
  box-shadow: 0 5px 14px rgba(4, 132, 66, 0.2);
  box-sizing: border-box;
  font-size: 14px;
  font-weight: 650;
}

.service-detail-hero__card {
  position: relative;
  z-index: 2;
  display: flex;
  margin: -28px 12px 0;
  padding: 12px 16px 10px;
  flex-direction: column;
  border: 1px solid #f0f1f3;
  border-radius: 20px;
  background: #fff;
  box-sizing: border-box;
  box-shadow: 0 9px 26px rgba(32, 39, 48, 0.09);
}

.service-detail-hero__title {
  display: block;
  color: #101216;
  font-size: clamp(19px, 5vw, 22px);
  font-weight: 760;
  letter-spacing: 0.01em;
  line-height: 1.25;
  overflow-wrap: anywhere;
  white-space: normal;
}

.service-detail-hero__subtitle {
  display: block;
  margin-top: 4px;
  color: #536176;
  font-size: 12px;
  line-height: 1.3;
}

.service-detail-hero__price-row {
  display: flex;
  min-height: 40px;
  margin-top: 8px;
  align-items: flex-end;
}

.service-detail-hero__price {
  color: #ff5900;
  font-size: 33px;
  font-weight: 760;
  letter-spacing: -0.035em;
  line-height: 1;
}

.service-detail-hero__price--quote {
  font-size: 23px;
  letter-spacing: 0;
}

.service-detail-hero__unit {
  margin: 0 0 3px 5px;
  color: #ff5900;
  font-size: 14px;
}

.service-detail-hero__metrics {
  display: flex;
  min-width: 0;
  margin-top: 7px;
  align-items: center;
  flex-wrap: wrap;
  color: #4f5d70;
  font-size: 12px;
  row-gap: 3px;
}

.service-detail-hero__rating {
  display: flex;
  align-items: center;
  gap: 5px;
}

.service-detail-hero__capabilities {
  display: grid;
  margin-top: 10px;
  padding-bottom: 10px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  border-bottom: 1px solid #eceff2;
}

.service-detail-hero__capability {
  display: flex;
  min-width: 0;
  min-height: 32px;
  padding: 0 8px;
  align-items: center;
  justify-content: center;
  gap: 5px;
  border: 1px solid #e6eaee;
  border-radius: 9px;
  color: #354054;
  background: #fff;
  box-sizing: border-box;
  font-size: 11px;
  white-space: nowrap;
}

.service-detail-hero__provider {
  display: grid;
  min-height: 54px;
  padding-top: 8px;
  grid-template-columns: 40px minmax(0, 1fr) auto 18px;
  align-items: center;
  gap: 9px;
}

.service-detail-hero__provider-logo {
  width: 38px;
  height: 38px;
  border: 1px solid #eceff1;
  border-radius: 50%;
  background: #fff;
}

.service-detail-hero__provider-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.service-detail-hero__provider-name {
  overflow: hidden;
  color: #20252c;
  font-size: 14px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-detail-hero__provider-meta {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.service-detail-hero__certification {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  color: #11804a;
  background: #e8f7ef;
  font-size: 11px;
  font-weight: 500;
  line-height: 16px;
}

.service-detail-hero__cities {
  overflow: hidden;
  color: #8a8f97;
  font-size: 11px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-detail-hero__provider-link {
  color: #2f3948;
  font-size: 12px;
  white-space: nowrap;
}

@media (max-width: 359px) {
  .service-detail-hero__card {
    margin-right: 9px;
    margin-left: 9px;
    padding-right: 14px;
    padding-left: 14px;
  }

  .service-detail-hero__capabilities {
    gap: 5px;
  }

  .service-detail-hero__capability {
    padding: 0 4px;
    font-size: 10px;
  }
}
</style>
