<script setup lang="ts">
import { computed } from 'vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { providerCredentialLabel } from '@/shared/ui/qualification'
import type { InspectionServiceDetail } from '../../../domain/inspection-service-detail'
import { categoryBadgeColour, categoryBadgeLabel } from '../detail-line-icons'

const props = defineProps<{
  detail: InspectionServiceDetail
  // Whether the provider page is open (preview builds, F2); without it the row is not a link.
  providerLinkEnabled?: boolean
}>()

const emit = defineEmits<{
  openProvider: [providerId: string]
}>()

function openProvider(): void {
  if (props.providerLinkEnabled && props.detail.provider) emit('openProvider', props.detail.provider.id)
}

const serviceCitiesLabel = computed(() =>
  props.detail.serviceCityNames.length
    ? `可服务城市 ${props.detail.serviceCityNames.join('、')}`
    : '',
)

const STAR = 'M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z'
const badgeLabel = computed(() => categoryBadgeLabel(props.detail.categoryLabel))
const badgeColour = computed(() => categoryBadgeColour(props.detail.categoryCode ?? ''))
// “0 条评价” says nothing, so the count only shows when there are reviews.
const reviewCount = computed(() => (props.detail.reviewCount ? props.detail.reviewCount : null))
// The boards draw the provider as a round initial; only a real uploaded logo replaces it.
const providerLogo = computed(() => {
  const url = props.detail.provider?.logoUrl ?? ''
  return /^https?:\/\//.test(url) ? url : ''
})
const providerInitial = computed(() => props.detail.provider?.name.trim().charAt(0) ?? '')
// InspDetail board: the green 资质已审核 tag when the platform reviewed the provider (R6-4), else its own label.
const providerCredential = computed(() => {
  const provider = props.detail.provider
  return provider ? providerCredentialLabel(provider.qualificationReviewed, provider.certificationLabel) : null
})

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
      <view
        v-if="badgeLabel"
        class="service-detail-hero__category"
        data-testid="detail-category-badge"
        :style="{ background: badgeColour }"
      >
        <text>{{ badgeLabel }}</text>
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
          <LineIcon :d="STAR" :size="12" color="#ff8a00" fill="#ff8a00" :stroke-width="1" />
          <text>{{ detail.ratingAverage.toFixed(1) }}分</text>
        </view>
        <!-- A separator only goes between two shown items, never at the start of the line. -->
        <text v-if="reviewCount !== null">
          {{ detail.ratingAverage !== null ? '·　' : '' }}{{ reviewCount }}条评价
        </text>
        <text v-if="detail.completedCountLabel">
          {{ detail.ratingAverage !== null || reviewCount !== null ? '·　' : '' }}{{ detail.completedCountLabel }}
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
          <LineIcon name="check" :size="14" color="#159a70" :stroke-width="3" />
          <text>{{ capability.label }}</text>
        </view>
      </view>

      <view v-if="detail.provider" class="service-detail-hero__provider">
        <image
          v-if="providerLogo"
          class="service-detail-hero__provider-logo"
          :src="providerLogo"
          mode="aspectFill"
          :aria-label="`${detail.provider.name}服务商标识`"
        />
        <text
          v-else
          class="service-detail-hero__provider-logo service-detail-hero__provider-initial"
          data-testid="detail-provider-initial"
          aria-hidden="true"
        >{{ providerInitial }}</text>
        <view class="service-detail-hero__provider-copy">
          <text class="service-detail-hero__provider-name">
            {{ detail.provider.name }}
          </text>
          <view class="service-detail-hero__provider-meta">
            <text
              v-if="providerCredential"
              class="service-detail-hero__certification"
              data-testid="service-detail-provider-credential"
            >
              {{ providerCredential }}
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
        <button
          v-if="providerLinkEnabled"
          class="service-detail-hero__provider-link"
          data-testid="detail-open-provider"
          @click="openProvider"
        >
          查看服务商 ›
        </button>
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
  box-shadow: var(--app-card-shadow);
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

.service-detail-hero__provider-initial {
  color: #ba470b;
  font-size: 15px;
  font-weight: 700;
  line-height: 36px;
  text-align: center;
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
  height: 44px;
  flex: 0 0 auto;
  margin: 0;
  padding: 0 0 0 4px;
  border: 0;
  color: #627187;
  background: transparent;
  font-family: inherit;
  font-size: 13px;
  line-height: 44px;
  white-space: nowrap;
}

.service-detail-hero__provider-link::after {
  border: 0;
}
</style>
