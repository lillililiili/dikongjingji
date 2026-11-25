<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'

import type {
  DetailReview,
  DetailReviewFilter,
} from '../../../domain/inspection-service-detail'

defineProps<{
  average: number | null
  total: number
  positiveRateLabel: string | null
  filters: readonly DetailReviewFilter[]
  reviews: readonly DetailReview[]
  selectedFilter: string
}>()

defineEmits<{
  selectFilter: [code: string]
  viewAll: []
  // A review picture was tapped (preview builds open the picture viewer, plan 08 S5-10).
  viewImage: [review: DetailReview, index: number]
}>()
const STAR = 'M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z'
</script>

<template>
  <section class="detail-section service-reviews">
    <view class="service-reviews__heading">
      <text class="detail-section__title">用户评价</text>
      <button
        v-if="total > 0"
        class="service-reviews__view-all"
        @click="$emit('viewAll')"
      >
        <text>查看全部</text>
        <LineIcon name="chevronRight" :size="15" color="#2f3948" />
      </button>
    </view>

    <template v-if="total > 0 && average !== null">
      <view class="service-reviews__summary">
        <text class="service-reviews__average">{{ average.toFixed(1) }}</text>
        <view class="service-reviews__stars" :aria-label="`平均评分 ${average.toFixed(1)} 分，满分 5 分`">
          <LineIcon v-for="star in 5" :key="star" :d="STAR" :size="17"
            :color="star <= Math.round(average) ? '#ff5c00' : '#d1d5db'"
            :fill="star <= Math.round(average) ? '#ff5c00' : 'none'" />
        </view>
        <text v-if="positiveRateLabel" class="service-reviews__meta">
          {{ positiveRateLabel }}　·　{{ total }}条评价
        </text>
      </view>

      <scroll-view
        v-if="filters.length"
        class="service-reviews__filters"
        scroll-x
        :show-scrollbar="false"
      >
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
              <LineIcon name="person" :size="22" color="#ff7a35" />
            </view>
            <view class="service-review-card__identity">
              <text class="service-review-card__name">
                {{ review.maskedUserName }}
              </text>
              <text class="service-review-card__context">
                {{ review.context }}
              </text>
            </view>
            <text class="service-review-card__date">{{ review.date }}</text>
          </view>
          <view class="service-review-card__stars">
            <LineIcon v-for="star in review.rating" :key="star" :d="STAR" :size="13" color="#ff5c00" fill="#ff5c00" />
          </view>
          <text class="service-review-card__content">{{ review.content }}</text>
          <view
            v-if="review.imageUrls.length"
            class="service-review-card__images"
          >
            <image
              v-for="(imageUrl, index) in review.imageUrls.slice(0, 3)"
              :key="`${review.id}-${index}`"
              class="service-review-card__image"
              :src="imageUrl"
              mode="aspectFill"
              lazy-load
              :aria-label="`${review.maskedUserName}的现场评价图片${index + 1}`"
              :data-testid="`review-image-${review.id}-${index}`"
              @click="$emit('viewImage', review, index)"
            />
          </view>
        </article>
      </view>
    </template>

    <view v-else class="service-reviews__empty">
      <LineIcon name="chatDots" :size="32" color="#c9d3e1" :stroke-width="1.6" />
      <text>暂无评价</text>
      <text>完成服务后可在这里查看真实用户评价</text>
    </view>
  </section>
</template>

<style scoped>
.detail-section {
  margin: 0 16px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
  box-sizing: border-box;
}

.detail-section__title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.service-reviews__heading {
  display: flex;
  min-height: 30px;
  align-items: center;
  justify-content: space-between;
}

.service-reviews__view-all {
  display: flex;
  min-height: 44px;
  margin: -7px -8px -7px 0;
  padding: 0 8px;
  align-items: center;
  gap: 2px;
  border: 0;
  color: #334054;
  background: transparent;
  font-size: 12px;
}

.service-reviews__view-all::after,
.service-reviews__filter::after {
  border: 0;
}

.service-reviews__summary {
  display: flex;
  min-height: 50px;
  margin-top: 5px;
  align-items: center;
  gap: 7px;
}

.service-reviews__average {
  color: #ff5700;
  font-size: 38px;
  font-weight: 760;
  letter-spacing: -0.04em;
  line-height: 1;
}

.service-reviews__stars {
  display: flex;
  align-items: center;
  gap: 1px;
}

.service-reviews__meta {
  min-width: 0;
  color: #4e5a6b;
  font-size: 10px;
  white-space: nowrap;
}

.service-reviews__filters {
  width: 100%;
  margin-top: 7px;
  white-space: nowrap;
}

.service-reviews__filter-row {
  display: inline-flex;
  padding-right: 10px;
  gap: 8px;
}

.service-reviews__filter {
  min-height: 32px;
  margin: 0;
  padding: 0 13px;
  border: 0;
  border-radius: 14px;
  color: #465164;
  background: #f5f6f8;
  box-sizing: border-box;
  font-size: 11px;
  line-height: 32px;
}

.service-reviews__filter--selected {
  color: #ff5c00;
  background: #fff0e8;
}

.service-reviews__list {
  display: flex;
  margin-top: 7px;
  flex-direction: column;
}

.service-review-card {
  display: flex;
  padding: 12px 0;
  flex-direction: column;
  border-bottom: 1px solid #eceff2;
}

.service-review-card:last-child {
  border-bottom: 0;
}

.service-review-card__header {
  display: grid;
  min-width: 0;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 9px;
}

.service-review-card__avatar {
  display: flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff1e7;
}

.service-review-card__identity {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.service-review-card__name {
  color: #262c34;
  font-size: 14px;
  font-weight: 650;
}

.service-review-card__context {
  overflow: hidden;
  color: #7b8696;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-review-card__date {
  color: #778193;
  font-size: 11px;
}

.service-review-card__stars {
  display: flex;
  margin: 9px 0 0 51px;
  gap: 1px;
}

.service-review-card__content {
  margin-top: 10px;
  color: #313943;
  font-size: 13px;
  line-height: 1.65;
}

.service-review-card__images {
  display: grid;
  margin-top: 10px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 7px;
}

.service-review-card__image {
  width: 100%;
  height: clamp(76px, 22vw, 100px);
  border-radius: 7px;
  background: #eef1f4;
}

.service-reviews__empty {
  display: flex;
  padding: 8px 0 6px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 4px;
  color: #536178;
  font-size: 14px;
  line-height: 20px;
}

.service-reviews__empty text:last-child {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

@media (max-width: 359px) {

  .service-reviews__summary {
    gap: 6px;
  }

  .service-reviews__average {
    font-size: 40px;
  }

  .service-reviews__meta {
    font-size: 10px;
  }
}
</style>
