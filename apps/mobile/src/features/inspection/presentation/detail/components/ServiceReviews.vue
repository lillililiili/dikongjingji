<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type {
  DetailReview,
  DetailReviewFilter,
} from '../../../domain/inspection-service-detail'

defineProps<{
  average: number
  total: number
  positiveRateLabel: string
  filters: readonly DetailReviewFilter[]
  reviews: readonly DetailReview[]
  selectedFilter: string
}>()

defineEmits<{
  selectFilter: [code: string]
  viewAll: []
}>()
</script>

<template>
  <section class="detail-section service-reviews">
    <view class="service-reviews__heading">
      <text class="detail-section__title">服务评价（{{ total }}）</text>
      <button class="service-reviews__view-all" @click="$emit('viewAll')">
        <text>查看全部</text>
        <UniIcons type="right" size="14" color="#ff6a00" />
      </button>
    </view>

    <view class="service-reviews__summary">
      <text class="service-reviews__average">{{ average.toFixed(1) }}</text>
      <view class="service-reviews__summary-copy">
        <view class="service-reviews__stars" aria-label="五星评价">
          <UniIcons
            v-for="star in 5"
            :key="star"
            type="star-filled"
            size="19"
            color="#ffad00"
          />
        </view>
        <view class="service-reviews__summary-meta">
          <text>{{ positiveRateLabel }}</text>
          <text class="service-reviews__divider">|</text>
          <text>{{ total }}条评价</text>
        </view>
      </view>
    </view>

    <scroll-view class="service-reviews__filters" scroll-x>
      <view class="service-reviews__filter-row">
        <button
          v-for="filter in filters"
          :key="filter.code"
          class="service-reviews__filter"
          :class="{
            'service-reviews__filter--selected':
              filter.code === selectedFilter,
          }"
          @click="$emit('selectFilter', filter.code)"
        >
          {{ filter.label }} {{ filter.count }}
        </button>
      </view>
    </scroll-view>

    <view class="service-reviews__list">
      <article
        v-for="review in reviews"
        :key="review.id"
        class="service-review-card"
      >
        <view class="service-review-card__header">
          <view class="service-review-card__avatar" aria-hidden="true">
            <UniIcons type="person-filled" size="23" color="#cdd1d6" />
          </view>
          <view class="service-review-card__identity">
            <text class="service-review-card__name">
              {{ review.maskedUserName }}
            </text>
            <view class="service-review-card__stars">
              <UniIcons
                v-for="star in review.rating"
                :key="star"
                type="star-filled"
                size="13"
                color="#ffad00"
              />
            </view>
          </view>
          <view class="service-review-card__context">
            <text>{{ review.date }}</text>
            <text>{{ review.context }}</text>
          </view>
        </view>
        <text class="service-review-card__content">{{ review.content }}</text>
        <view
          v-if="review.imageUrls.length > 0"
          class="service-review-card__images"
        >
          <image
            v-for="(imageUrl, index) in review.imageUrls"
            :key="`${review.id}-${index}`"
            class="service-review-card__image"
            :src="imageUrl"
            mode="aspectFill"
            lazy-load
            :aria-label="`${review.maskedUserName}的现场评价图片${index + 1}`"
          />
        </view>
      </article>
    </view>
  </section>
</template>

<style scoped>
.detail-section {
  margin: 0 7px;
  padding: 9px;
  border: 1px solid #edf0f2;
  border-radius: 11px;
  background: #ffffff;
  box-sizing: border-box;
  box-shadow: 0 3px 14px rgba(28, 36, 46, 0.055);
}

.detail-section__title {
  color: #1d2024;
  font-size: 14px;
  font-weight: 700;
}

.service-reviews__heading {
  display: flex;
  min-height: 28px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.service-reviews__view-all {
  display: flex;
  min-width: 82px;
  min-height: 44px;
  margin: -7px -8px -7px 0;
  padding: 0 8px;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  border: 0;
  color: #ff6a00;
  background: transparent;
  font-size: 11px;
}

.service-reviews__view-all::after,
.service-reviews__filter::after {
  border: 0;
}

.service-reviews__summary {
  display: flex;
  margin-top: 8px;
  align-items: center;
  gap: 15px;
}

.service-reviews__average {
  color: #ff5a00;
  font-size: 44px;
  font-weight: 750;
  letter-spacing: -0.04em;
  line-height: 1;
}

.service-reviews__summary-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.service-reviews__stars {
  display: flex;
  align-items: center;
  gap: 1px;
}

.service-reviews__summary-meta {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 5px;
  color: #555b63;
  font-size: 11px;
  white-space: nowrap;
}

.service-reviews__divider {
  color: #d5d8dc;
}

.service-reviews__filters {
  width: 100%;
  margin-top: 8px;
  white-space: nowrap;
}

.service-reviews__filter-row {
  display: inline-flex;
  padding-right: 10px;
  gap: 6px;
}

.service-reviews__filter {
  min-height: 32px;
  margin: 0;
  padding: 0 10px;
  border: 1px solid #e5e8eb;
  border-radius: 9px;
  color: #4e545c;
  background: #ffffff;
  box-sizing: border-box;
  font-size: 10px;
  line-height: 30px;
}

.service-reviews__filter--selected {
  border-color: #ff8a4d;
  color: #ff6a00;
  background: #fff8f3;
}

.service-reviews__list {
  display: flex;
  margin-top: 8px;
  flex-direction: column;
  gap: 7px;
}

.service-review-card {
  display: flex;
  padding: 9px;
  flex-direction: column;
  border: 1px solid #e9ecef;
  border-radius: 9px;
  background: #ffffff;
  box-shadow: 0 2px 9px rgba(20, 28, 36, 0.04);
}

.service-review-card__header {
  display: grid;
  min-width: 0;
  grid-template-columns: 34px minmax(0, 1fr) auto;
  align-items: center;
  column-gap: 7px;
}

.service-review-card__avatar {
  display: flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #f3f4f6;
}

.service-review-card__identity {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.service-review-card__name {
  color: #3d4249;
  font-size: 11px;
}

.service-review-card__stars {
  display: flex;
  gap: 1px;
}

.service-review-card__context {
  display: flex;
  max-width: 140px;
  align-items: flex-end;
  flex-direction: column;
  gap: 3px;
  color: #9b9fa6;
  font-size: 9px;
  line-height: 1.25;
}

.service-review-card__content {
  margin-top: 8px;
  color: #3f444c;
  font-size: 11px;
  line-height: 1.45;
}

.service-review-card__images {
  display: grid;
  margin-top: 7px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}

.service-review-card__image {
  width: 100%;
  height: clamp(60px, 18vw, 88px);
  border-radius: 8px;
  background: #eef1f4;
}

@media (max-width: 359px) {
  .detail-section {
    margin-right: 10px;
    margin-left: 10px;
    padding-right: 12px;
    padding-left: 12px;
  }

  .service-reviews__summary {
    gap: 15px;
  }

  .service-reviews__average {
    font-size: 48px;
  }

  .service-reviews__summary-meta {
    gap: 5px;
    font-size: 11px;
  }

  .service-review-card {
    padding: 12px 10px;
  }

  .service-review-card__context {
    max-width: 112px;
  }
}
</style>
