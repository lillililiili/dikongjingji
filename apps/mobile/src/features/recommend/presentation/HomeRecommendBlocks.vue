<script setup lang="ts">
import { ref, watch } from 'vue'

import type { HomeEntryTarget } from '@/app-shell/navigation/home-channels'

import { resolveActivityPreviewCover } from '../../activity/data/preview/activity-preview-covers'
import type { ActivityCard } from '../../activity/domain/activity'
import { buildActivityDetailUrl } from '../../activity/navigation/activity-routes'
import {
  ACTIVITY_TYPE_LABELS,
  formatActivityCardSchedule,
  formatActivityCardStatus,
  formatActivityPrice,
  isPerPersonPrice,
} from '../../activity/presentation/shared/activity-format'
import { CONTENT_PREVIEW_ENABLED } from '../../content/application/content-preview'
import { shortCityName } from '../../content/application/content-view'
import { contentApi } from '../../content/data/content-api-client'
import { resolveArticlePreviewCover } from '../../content/data/preview/content-preview-covers'
import type { ContentArticleCard } from '../../content/domain/content'
import { buildContentArticleUrl } from '../../content/navigation/content-routes'
import { resolveExperiencePreviewCover } from '../../experience/data/preview/experience-preview-covers'
import type { VenueCard } from '../../experience/domain/experience'
import { buildVenueDetailUrl } from '../../experience/navigation/experience-routes'
import { resolveMallPreviewCover } from '../../mall/data/preview/mall-preview-covers'
import type { MallProductCard } from '../../mall/domain/mall'
import { buildMallProductUrl } from '../../mall/navigation/mall-routes'
import {
  MALL_BADGE_LABELS,
  formatMallCardPrice,
  formatMallSellers,
} from '../../mall/presentation/shared/mall-format'
import {
  EMPTY_HOME_RECOMMEND,
  createHomeRecommendLoader,
  formatTodaySeats,
  formatTodaySlotTime,
  type HomeRecommendContent,
} from '../application/home-recommend'
import {
  createCityNewsLoader,
  formatCityNewsSource,
} from '../application/home-city-news'
import { homeRecommendApi } from '../data/home-recommend-api-client'

const props = defineProps<{
  cityCode: string
  cityName?: string
}>()

const emit = defineEmits<{
  selectEntry: [target: HomeEntryTarget]
}>()

const content = ref<HomeRecommendContent>(EMPTY_HOME_RECOMMEND)

const loader = createHomeRecommendLoader(homeRecommendApi, {
  setContent: (next) => { content.value = next },
})

// The "城市资讯" block reads the discover feed, which only preview builds have.
const cityNews = ref<ContentArticleCard | null>(null)
const cityNewsLoader = createCityNewsLoader(contentApi, {
  setArticle: (article) => { cityNews.value = article },
})

watch(() => props.cityCode, (cityCode) => {
  if (!cityCode) return
  void loader.load(cityCode)
  if (CONTENT_PREVIEW_ENABLED) void cityNewsLoader.load(cityCode)
}, { immediate: true })

// The article's own picture, else the same sample picture the discover page shows for it.
function cityNewsPicture(article: ContentArticleCard): { url: string; cutout: boolean } | null {
  if (article.coverImage) return { url: article.coverImage.url, cutout: false }
  const preview = resolveArticlePreviewCover(article.articleId)
  return preview ? { url: preview.url, cutout: preview.cutout } : null
}

function openCityNews(article: ContentArticleCard): void {
  uni.navigateTo({ url: buildContentArticleUrl(article.articleId, props.cityCode) })
}

function activityCover(card: ActivityCard): string | null {
  return card.coverImage?.url ?? resolveActivityPreviewCover(card.activityId)
}

function productCover(product: MallProductCard): { url: string; cutout: boolean } | null {
  return product.coverImage
    ? { url: product.coverImage.url, cutout: false }
    : resolveMallPreviewCover(product.productId)
}

function venueCover(venue: VenueCard): string | null {
  return venue.coverImage?.url ?? resolveExperiencePreviewCover(venue.venueId)
}

function openActivity(card: ActivityCard): void {
  uni.navigateTo({ url: buildActivityDetailUrl(card.activityId, props.cityCode) })
}

function openProduct(product: MallProductCard): void {
  uni.navigateTo({ url: buildMallProductUrl(product.productId, props.cityCode) })
}

function openVenue(venueId: string): void {
  uni.navigateTo({ url: buildVenueDetailUrl(venueId, props.cityCode) })
}
</script>

<template>
  <view class="home-recommend" data-testid="home-recommend">
    <view v-if="content.today" class="home-recommend__today" data-testid="home-today">
      <view class="home-recommend__today-heading">
        <text class="home-recommend__title">今天去体验</text>
        <text class="home-recommend__today-venue">
          {{ content.today.venue.name }}{{ content.today.venue.openingNote ? ` · ${content.today.venue.openingNote}` : '' }}
        </text>
      </view>
      <view class="home-recommend__slots">
        <button
          v-for="slot in content.today.slots"
          :key="slot.projectId"
          class="home-recommend__slot"
          :data-testid="`home-today-slot-${slot.projectId}`"
          @click="openVenue(content.today.venue.venueId)"
        >
          <text class="home-recommend__slot-name">{{ slot.projectName }}</text>
          <text class="home-recommend__slot-time">{{ formatTodaySlotTime(slot) }}</text>
          <text class="home-recommend__slot-seats">{{ formatTodaySeats(slot) }}</text>
        </button>
      </view>
      <text class="home-recommend__today-note">体验结束不会自动下单。</text>
    </view>

    <view v-if="content.activities.length" class="home-recommend__section" data-testid="home-activities">
      <view class="home-recommend__heading">
        <text class="home-recommend__title">近期活动</text>
        <button
          class="home-recommend__more"
          data-testid="home-activities-more"
          @click="emit('selectEntry', { type: 'CHANNEL', channel: 'ACTIVITY' })"
        >
          全部 ›
        </button>
      </view>
      <scroll-view class="home-recommend__scroll" scroll-x :show-scrollbar="false">
        <view class="home-recommend__activity-track">
          <button
            v-for="card in content.activities"
            :key="card.activityId"
            class="home-recommend__activity"
            :data-testid="`home-activity-${card.activityId}`"
            @click="openActivity(card)"
          >
            <view class="home-recommend__activity-cover">
              <image
                v-if="activityCover(card)"
                class="home-recommend__image"
                :src="activityCover(card) ?? ''"
                :alt="card.title"
                mode="aspectFill"
                lazy-load
              />
              <text class="home-recommend__activity-type">{{ ACTIVITY_TYPE_LABELS[card.type] }}</text>
            </view>
            <view class="home-recommend__activity-body">
              <text class="home-recommend__activity-title">{{ card.title }}</text>
              <text class="home-recommend__meta">{{ formatActivityCardSchedule(card) }}</text>
              <view class="home-recommend__activity-footer">
                <text class="home-recommend__activity-price">
                  {{ formatActivityPrice(card.price) }}{{ isPerPersonPrice(card.price) ? '/人' : '' }}
                </text>
                <text
                  :class="[
                    'home-recommend__activity-status',
                    `home-recommend__activity-status--${card.enrollmentStatus.toLowerCase()}`,
                  ]"
                >
                  {{ formatActivityCardStatus(card) }}
                </text>
              </view>
            </view>
          </button>
        </view>
      </scroll-view>
    </view>

    <view v-if="content.products.length" class="home-recommend__section" data-testid="home-products">
      <view class="home-recommend__heading">
        <text class="home-recommend__title">本地精选</text>
        <button
          class="home-recommend__more"
          data-testid="home-products-more"
          @click="emit('selectEntry', { type: 'CHANNEL', channel: 'MALL' })"
        >
          更多 ›
        </button>
      </view>
      <view class="home-recommend__products">
        <button
          v-for="product in content.products"
          :key="product.productId"
          class="home-recommend__product"
          :data-testid="`home-product-${product.productId}`"
          @click="openProduct(product)"
        >
          <view
            :class="[
              'home-recommend__product-cover',
              { 'home-recommend__product-cover--cutout': productCover(product)?.cutout },
            ]"
          >
            <image
              v-if="productCover(product)"
              class="home-recommend__image"
              :src="productCover(product)?.url"
              :alt="product.name"
              :mode="productCover(product)?.cutout ? 'aspectFit' : 'aspectFill'"
              lazy-load
            />
            <text
              v-if="product.experienceBadge"
              :class="[
                'home-recommend__product-badge',
                `home-recommend__product-badge--${product.experienceBadge.toLowerCase()}`,
              ]"
            >
              {{ MALL_BADGE_LABELS[product.experienceBadge] }}
            </text>
          </view>
          <view class="home-recommend__product-body">
            <text class="home-recommend__product-name">{{ product.name }}</text>
            <view class="home-recommend__product-price">
              <text class="home-recommend__product-amount">{{ formatMallCardPrice(product).amount }}</text>
              <text class="home-recommend__product-unit">
                {{ formatMallCardPrice(product).unit }}{{ formatMallCardPrice(product).from }}
              </text>
              <text class="home-recommend__product-sellers">{{ formatMallSellers(product) }}</text>
            </view>
          </view>
        </button>
      </view>
    </view>

    <view v-if="content.venues.length" class="home-recommend__section" data-testid="home-venues">
      <view class="home-recommend__heading">
        <text class="home-recommend__title">附近场馆</text>
        <button
          class="home-recommend__more"
          data-testid="home-venues-more"
          @click="emit('selectEntry', { type: 'CHANNEL', channel: 'EXPERIENCE' })"
        >
          全部 ›
        </button>
      </view>
      <button
        v-for="venue in content.venues"
        :key="venue.venueId"
        class="home-recommend__venue"
        :data-testid="`home-venue-${venue.venueId}`"
        @click="openVenue(venue.venueId)"
      >
        <view class="home-recommend__venue-cover">
          <image
            v-if="venueCover(venue)"
            class="home-recommend__image"
            :src="venueCover(venue) ?? ''"
            :alt="venue.name"
            mode="aspectFill"
            lazy-load
          />
        </view>
        <view class="home-recommend__venue-body">
          <text class="home-recommend__venue-name">{{ venue.name }}</text>
          <!-- Board HomeFull: the project summary under the name (experience 1.4.0), then hours and seats.
               The board's distance needs a position the home page does not ask for; the address stands in
               for the summary only when the venue has none. -->
          <text
            v-if="venue.projectSummary"
            class="home-recommend__meta"
            :data-testid="`home-venue-summary-${venue.venueId}`"
          >{{ venue.projectSummary }}</text>
          <view
            v-if="venue.openingNote || venue.openSessionsToday > 0"
            class="home-recommend__meta-row"
          >
            <text v-if="venue.openingNote" class="home-recommend__meta">{{ venue.openingNote }}</text>
            <text v-if="venue.openSessionsToday > 0" class="home-recommend__venue-seats">
              {{ venue.openingNote ? '·' : '' }} 今日 {{ venue.openSessionsToday }} 场可约
            </text>
          </view>
          <text v-if="!venue.projectSummary" class="home-recommend__meta">{{ venue.address }}</text>
        </view>
        <text class="home-recommend__venue-action">预约</text>
      </button>
    </view>

    <view v-if="cityNews" class="home-recommend__section" data-testid="home-city-news">
      <view class="home-recommend__heading">
        <text class="home-recommend__title">城市资讯</text>
      </view>
      <button
        class="home-recommend__news"
        :data-testid="`home-city-news-${cityNews.articleId}`"
        @click="openCityNews(cityNews)"
      >
        <view class="home-recommend__news-copy">
          <text class="home-recommend__news-source">
            {{ formatCityNewsSource(cityNews, shortCityName(cityName)) }}{{ cityNews.sample ? ' · 示例' : '' }}
          </text>
          <text class="home-recommend__news-title">{{ cityNews.title }}</text>
        </view>
        <image
          v-if="cityNewsPicture(cityNews)"
          :class="[
            'home-recommend__news-picture',
            { 'home-recommend__news-picture--cutout': cityNewsPicture(cityNews)?.cutout },
          ]"
          :src="cityNewsPicture(cityNews)?.url ?? ''"
          :alt="cityNews.coverImage?.alt ?? '资讯配图'"
          :mode="cityNewsPicture(cityNews)?.cutout ? 'aspectFit' : 'aspectFill'"
          lazy-load
        />
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board HomeFull (390 wide). */
.home-recommend {
  display: flex;
  padding: 12px var(--app-page-horizontal-gutter) 16px;
  flex-direction: column;
  gap: 14px;
  color: #202c42;
}

.home-recommend:empty {
  display: none;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
}

button::after {
  border: 0;
}

.home-recommend__title {
  flex: 0 0 auto;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.home-recommend__today {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.home-recommend__today-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 2px 8px;
}

.home-recommend__today-venue {
  min-width: 0;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.home-recommend__slots {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

/* light-theme.css `.theme-slot`: a tinted tile with a white ring peeking out of the corner. */
.home-recommend__slot {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 82px;
  padding: 12px 9px;
  flex-direction: column;
  gap: 4px;
  overflow: hidden;
  border-radius: 10px;
  background: #e9f1ff;
  box-sizing: border-box;
}

.home-recommend__slot::after {
  position: absolute;
  right: -11px;
  bottom: -10px;
  width: 45px;
  height: 45px;
  border: 8px solid rgba(255, 255, 255, 0.62);
  border-radius: 50%;
  box-sizing: content-box;
  content: '';
  pointer-events: none;
}

.home-recommend__slot:nth-child(3n + 2) {
  background: #eee8ff;
}

.home-recommend__slot:nth-child(3n + 3) {
  background: #e1f5ed;
}

/* Project names run longer than the board's four characters; they take a second line. */
.home-recommend__slot-name {
  display: -webkit-box;
  max-width: 100%;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  word-break: break-all;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.home-recommend__slot-time {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.home-recommend__slot-seats {
  color: #11804a;
  font-size: 12px;
  font-weight: 500;
  line-height: 17px;
}

.home-recommend__today-note {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.home-recommend__section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.home-recommend__heading {
  display: flex;
  min-height: 32px;
  align-items: center;
  justify-content: space-between;
}

.home-recommend__more {
  display: flex;
  min-height: 44px;
  margin: -6px 0;
  align-items: center;
  gap: 2px;
  color: #627187;
  font-size: 13px;
}

.home-recommend__image {
  width: 100%;
  height: 100%;
}

.home-recommend__meta {
  max-width: 100%;
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* The cards run to the right edge of the screen, past the page gutter. */
.home-recommend__scroll {
  width: calc(100% + var(--app-page-horizontal-gutter));
  white-space: nowrap;
}

.home-recommend__activity-track {
  display: inline-flex;
  padding: 0 var(--app-page-horizontal-gutter) 4px 0;
  gap: 10px;
}

.home-recommend__activity {
  display: flex;
  width: 220px;
  flex: 0 0 auto;
  flex-direction: column;
  overflow: hidden;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  white-space: normal;
}

.home-recommend__activity-cover {
  position: relative;
  height: 96px;
  flex: 0 0 auto;
  background: #eef0f3;
}

.home-recommend__activity-type {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 1px 6px;
  border-radius: 6px;
  color: #6a4fe0;
  background: #eee7ff;
  font-size: 11px;
  font-weight: 500;
  line-height: 16px;
}

.home-recommend__activity-body {
  display: flex;
  padding: 10px;
  flex-direction: column;
  gap: 4px;
}

.home-recommend__activity-title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-recommend__activity-footer {
  display: flex;
  align-items: baseline;
  white-space: nowrap;
}

.home-recommend__activity-price {
  color: #e85400;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.home-recommend__activity-status {
  margin-left: auto;
  font-size: 12px;
  line-height: 17px;
}

.home-recommend__activity-status--open {
  color: #11804a;
}

.home-recommend__activity-status--full_waitlist {
  color: #ba470b;
}

.home-recommend__activity-status--full,
.home-recommend__activity-status--closed,
.home-recommend__activity-status--ended {
  color: #738096;
}

.home-recommend__products {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.home-recommend__product {
  display: flex;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
}

.home-recommend__product-cover {
  position: relative;
  height: 132px;
  flex: 0 0 auto;
  background: #eef0f3;
}

.home-recommend__product-cover--cutout {
  background: #f0edff;
}

.home-recommend__product-badge {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 1px 6px;
  border-radius: 6px;
  color: #ffffff;
  background: #159a70;
  font-size: 11px;
  line-height: 16px;
}

.home-recommend__product-badge--venue_display {
  background: #2f80ed;
}

.home-recommend__product-body {
  display: flex;
  padding: 10px;
  flex-direction: column;
  gap: 4px;
}

.home-recommend__product-name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-recommend__product-price {
  display: flex;
  align-items: baseline;
  gap: 3px;
  white-space: nowrap;
}

.home-recommend__product-amount {
  color: #e85400;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.home-recommend__product-unit {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.home-recommend__product-sellers {
  min-width: 0;
  margin-left: auto;
  flex: 0 1 auto;
  overflow: hidden;
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-recommend__venue {
  display: flex;
  width: 100%;
  padding: 12px;
  align-items: center;
  gap: 12px;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  box-sizing: border-box;
}

.home-recommend__venue-cover {
  width: 76px;
  height: 64px;
  flex: 0 0 auto;
  overflow: hidden;
  border-radius: 10px;
  background: #e2f6ed;
}

.home-recommend__venue-body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.home-recommend__venue-name {
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-recommend__meta-row {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 4px;
}

.home-recommend__meta-row .home-recommend__meta {
  min-width: 0;
  flex: 0 1 auto;
}

.home-recommend__venue-seats {
  flex: 0 0 auto;
  color: #11804a;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.home-recommend__news {
  display: flex;
  width: 100%;
  padding: 12px;
  gap: 12px;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
  box-sizing: border-box;
}

.home-recommend__news-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 6px;
}

.home-recommend__news-source {
  color: #627187;
  font-size: 11px;
  line-height: 16px;
}

.home-recommend__news-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.home-recommend__news-picture {
  width: 92px;
  height: 70px;
  flex: 0 0 auto;
  border-radius: 10px;
}

.home-recommend__news-picture--cutout {
  background: #f0edff;
}

.home-recommend__venue-action {
  display: flex;
  height: 32px;
  padding: 0 14px;
  flex: 0 0 auto;
  align-items: center;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 13px;
  font-weight: 500;
}
</style>
