<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import { buildActivityDetailUrl } from '@/features/activity/navigation/activity-routes'
import { CONTENT_PREVIEW_ENABLED } from '@/features/content/application/content-preview'
import { buildContentArticleUrl, buildContentVideoUrl } from '@/features/content/navigation/content-routes'
import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { ApiRequestError } from '@/shared/http/public-api-request'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { loadMerchantCityLookup } from '../../application/merchant-cities'
import {
  MERCHANT_TABS,
  cityLookupFrom,
  merchantActivityPrice,
  merchantActivityWhen,
  merchantContentTags,
  merchantGlyph,
  merchantInfoRows,
  merchantKindLabel,
  merchantQualRows,
  merchantTabLabel,
  type CityLookup,
  type MerchantTab,
} from '../../application/merchant-view'
import { mallApi } from '../../data/mall-api-client'
import { mallMerchantApi } from '../../data/merchant-api-client'
import type { MallProductCard } from '../../domain/mall'
import type { MallMerchantActivity, MallMerchantContent, MallMerchantPage } from '../../domain/merchant'
import { buildMallProductUrl } from '../../navigation/mall-routes'
import { formatMallCardPrice } from '../shared/mall-format'

/**
 * 商家主页（S5-2，画板 Merchant 的商家帧）：字形头像、名称、资质已审核、商家类型、咨询；经营主体、
 * 可售城市、授权有效期；页签 在售 / 活动 / 内容 / 资质。在售的商品用商品列表加 merchantId 读，
 * 活动和内容取商家主页接口给的前 10 条。没有资料的行不显示。
 */
const props = defineProps<{
  merchantId: string
  cityCode: string
  reachBottomKey: number
}>()

const emit = defineEmits<{
  back: []
  open: [url: string]
}>()

type PageState =
  | { kind: 'loading' }
  | { kind: 'ready'; page: MallMerchantPage }
  | { kind: 'missing' }
  | { kind: 'error' }

const GOODS_PAGE_SIZE = 10

const state = ref<PageState>({ kind: 'loading' })
const tab = ref<MerchantTab>('GOODS')
const lookup = ref<CityLookup>(cityLookupFrom(null))
const goods = ref<MallProductCard[]>([])
const goodsPage = ref(0)
const goodsMore = ref(false)
const goodsLoading = ref(false)
const goodsFailed = ref(false)
let requestVersion = 0

const page = computed(() => (state.value.kind === 'ready' ? state.value.page : null))
const infoRows = computed(() => (page.value ? merchantInfoRows(page.value, lookup.value) : []))
const quals = computed(() => (page.value ? merchantQualRows(page.value) : []))
const kindLabel = computed(() => (page.value ? merchantKindLabel(page.value) : null))

async function load(): Promise<void> {
  if (!props.merchantId || !props.cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  goods.value = []
  goodsPage.value = 0
  goodsMore.value = false
  void loadMerchantCityLookup().then((loaded) => {
    if (requested === requestVersion) lookup.value = loaded
  })
  try {
    const loaded = await mallMerchantApi.load(props.merchantId, props.cityCode)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', page: loaded }
    void loadGoods(requested)
  } catch (error) {
    if (requested !== requestVersion) return
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

async function loadGoods(requested = requestVersion): Promise<void> {
  if (goodsLoading.value) return
  goodsLoading.value = true
  goodsFailed.value = false
  try {
    const next = goodsPage.value + 1
    const loaded = await mallApi.loadProducts(
      { cityCode: props.cityCode, category: null, sort: 'DEFAULT', merchantId: props.merchantId },
      next,
      GOODS_PAGE_SIZE,
    )
    if (requested !== requestVersion) return
    const known = new Set(goods.value.map((item) => item.productId))
    goods.value = [...goods.value, ...loaded.items.filter((item) => !known.has(item.productId))]
    goodsPage.value = next
    goodsMore.value = loaded.hasMore && loaded.items.length > 0
  } catch {
    if (requested === requestVersion) goodsFailed.value = true
  } finally {
    goodsLoading.value = false
  }
}

function coverOfProduct(product: MallProductCard): string | null {
  return product.coverImage?.url ?? null
}

function coverOfActivity(activity: MallMerchantActivity): string | null {
  return activity.coverImage?.url ?? null
}

function coverOfContent(content: MallMerchantContent): string | null {
  return content.coverImage?.url ?? null
}

// 活动卡进活动详情、内容卡进图文或视频详情；那些页面没开时卡片不能点。
function openActivity(activity: MallMerchantActivity): void {
  if (ACTIVITY_PREVIEW_ENABLED) emit('open', buildActivityDetailUrl(activity.activityId, props.cityCode))
}

function openContent(content: MallMerchantContent): void {
  if (!CONTENT_PREVIEW_ENABLED) return
  emit('open', content.kind === 'VIDEO'
    ? buildContentVideoUrl(content.contentId, props.cityCode)
    : buildContentArticleUrl(content.contentId, props.cityCode))
}

function consult(): void {
  if (page.value) openSupportChat({ kind: 'MERCHANT', organizationId: page.value.merchantId })
}

watch(() => [props.merchantId, props.cityCode], () => { void load() }, { immediate: true })
watch(() => props.reachBottomKey, () => {
  if (tab.value === 'GOODS' && goodsMore.value && state.value.kind === 'ready') void loadGoods()
})
</script>

<template>
  <view class="merchant" data-testid="mall-merchant" :data-state="state.kind">
    <view class="merchant__head">
      <button class="merchant__back" data-testid="mall-merchant-back" aria-label="返回" @click="emit('back')">
        <LineIcon name="back" :size="22" color="#202c42" :stroke-width="2" />
      </button>
      <template v-if="page">
        <view class="merchant__who">
          <view class="merchant__glyph" data-testid="mall-merchant-glyph">{{ merchantGlyph(page.name) }}</view>
          <view class="merchant__names">
            <text class="merchant__name" data-testid="mall-merchant-name">{{ page.name }}</text>
            <view class="merchant__tags">
              <text v-if="page.qualificationReviewed" class="merchant__tag merchant__tag--green" data-testid="mall-merchant-reviewed">资质已审核</text>
              <text v-if="kindLabel" class="merchant__tag merchant__tag--orange" data-testid="mall-merchant-kind">{{ kindLabel }}</text>
            </view>
          </view>
          <button v-if="SUPPORT_PREVIEW_ENABLED" class="merchant__consult" data-testid="mall-merchant-consult" @click="consult">咨询</button>
        </view>
        <view v-if="infoRows.length > 0" class="merchant__info" data-testid="mall-merchant-info">
          <template v-for="row in infoRows" :key="row.key">
            <text class="merchant__info-label">{{ row.label }}</text>
            <text class="merchant__info-value" :data-testid="`mall-merchant-info-${row.key}`">{{ row.value }}</text>
          </template>
        </view>
      </template>
    </view>

    <view v-if="state.kind === 'loading'" class="merchant__body" data-testid="mall-merchant-loading" aria-label="正在加载">
      <view class="merchant__skeleton" />
      <view class="merchant__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="merchant__state" data-testid="mall-merchant-missing">
      <text class="merchant__state-title">没有找到这家商家</text>
      <button class="merchant__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="merchant__state" data-testid="mall-merchant-error">
      <text class="merchant__state-title">加载失败</text>
      <text class="merchant__state-text">请检查网络后重试</text>
      <button class="merchant__state-button" data-testid="mall-merchant-retry" @click="load">重试</button>
    </view>

    <template v-else-if="page">
      <view class="merchant__tabs" role="tablist">
        <button
          v-for="item in MERCHANT_TABS"
          :key="item"
          :class="['merchant__tab', { 'merchant__tab--on': item === tab }]"
          role="tab"
          :aria-selected="item === tab"
          :data-testid="`mall-merchant-tab-${item.toLowerCase()}`"
          @click="tab = item"
        >
          {{ merchantTabLabel(item, page) }}
        </button>
      </view>

      <view v-if="tab === 'GOODS'" class="merchant__body" data-testid="mall-merchant-goods">
        <view v-if="goods.length > 0" class="merchant__grid">
          <view
            v-for="product in goods"
            :key="product.productId"
            class="merchant__product"
            :data-testid="`mall-merchant-product-${product.productId}`"
            @click="emit('open', buildMallProductUrl(product.productId, cityCode))"
          >
            <image v-if="coverOfProduct(product)" class="merchant__product-cover" :src="coverOfProduct(product) ?? ''" mode="aspectFill" />
            <view v-else class="merchant__product-cover" />
            <view class="merchant__product-text">
              <text class="merchant__product-name">{{ product.name }}</text>
              <text class="merchant__product-price">
                {{ formatMallCardPrice(product).amount }}<text class="merchant__product-unit">{{ formatMallCardPrice(product).unit }}{{ formatMallCardPrice(product).from }}</text>
              </text>
            </view>
          </view>
        </view>
        <text v-else-if="!goodsLoading && !goodsFailed" class="merchant__empty" data-testid="mall-merchant-goods-empty">这个城市暂时没有在售商品</text>
        <button v-if="goodsFailed" class="merchant__more" data-testid="mall-merchant-goods-retry" @click="loadGoods()">加载失败，点这里重试</button>
        <button v-else-if="goodsMore" class="merchant__more" :disabled="goodsLoading" @click="loadGoods()">
          {{ goodsLoading ? '正在加载…' : '加载更多' }}
        </button>
      </view>

      <view v-else-if="tab === 'ACTS'" class="merchant__body" data-testid="mall-merchant-acts">
        <view
          v-for="activity in page.activities"
          :key="activity.activityId"
          class="merchant__row-card"
          :data-testid="`mall-merchant-activity-${activity.activityId}`"
          @click="openActivity(activity)"
        >
          <image v-if="coverOfActivity(activity)" class="merchant__row-cover" :src="coverOfActivity(activity) ?? ''" mode="aspectFill" />
          <view v-else class="merchant__row-cover" />
          <view class="merchant__row-text">
            <text class="merchant__row-title">{{ activity.title }}</text>
            <text class="merchant__row-meta">{{ merchantActivityWhen(activity) }}</text>
            <text :class="['merchant__row-price', { 'merchant__row-price--free': merchantActivityPrice(activity).free }]">
              {{ merchantActivityPrice(activity).text }}
            </text>
          </view>
        </view>
        <text v-if="page.activities.length === 0" class="merchant__empty">这个城市暂时没有这家的活动</text>
      </view>

      <view v-else-if="tab === 'POSTS'" class="merchant__body" data-testid="mall-merchant-posts">
        <view
          v-for="content in page.contents"
          :key="content.contentId"
          class="merchant__row-card"
          :data-testid="`mall-merchant-content-${content.contentId}`"
          @click="openContent(content)"
        >
          <image v-if="coverOfContent(content)" class="merchant__row-cover" :src="coverOfContent(content) ?? ''" mode="aspectFill" />
          <view v-else class="merchant__row-cover" />
          <view class="merchant__row-text">
            <text class="merchant__row-title">{{ content.title }}</text>
            <view class="merchant__content-tags">
              <text
                v-for="(tag, index) in merchantContentTags(content)"
                :key="tag"
                :class="['merchant__content-tag', { 'merchant__content-tag--kind': index === 0 }]"
              >
                {{ tag }}
              </text>
            </view>
          </view>
        </view>
        <text v-if="page.contents.length === 0" class="merchant__empty">这家还没有发布内容</text>
      </view>

      <view v-else class="merchant__body" data-testid="mall-merchant-qual">
        <view class="merchant__quals">
          <view
            v-for="(row, index) in quals"
            :key="row.key"
            :class="['merchant__qual', { 'merchant__qual--last': index === quals.length - 1 }]"
            :data-testid="`mall-merchant-qual-${row.key}`"
          >
            <text class="merchant__qual-label">{{ row.label }}</text>
            <text :class="['merchant__qual-value', `merchant__qual-value--${row.tone.toLowerCase()}`]">{{ row.value }}</text>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours copied from the 2026-11-14 board Merchant, merchant frame (390 wide). */
.merchant {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.merchant__head {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: calc(var(--status-bar-height) + 8px) 16px 16px;
  background: linear-gradient(180deg, #fff0e3, #ffffff);
}

.merchant__back {
  display: flex;
  width: 44px;
  height: 44px;
  margin-left: -8px;
  align-items: center;
  justify-content: center;
}

.merchant__who {
  display: flex;
  align-items: center;
  gap: 12px;
}

.merchant__glyph {
  display: flex;
  width: 56px;
  height: 56px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1px solid #e5ebf3;
  border-radius: 14px;
  color: #ff6a00;
  background: #ffffff;
  font-size: 22px;
  font-weight: 700;
  box-sizing: border-box;
}

.merchant__names {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.merchant__name {
  font-size: 18px;
  font-weight: 700;
}

.merchant__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.merchant__tag {
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 11px;
  white-space: nowrap;
}

.merchant__tag--green {
  color: #11804a;
  background: #e2f6ed;
}

.merchant__tag--orange {
  color: #ba470b;
  background: #fff0e3;
}

.merchant__consult {
  display: flex;
  min-height: 34px;
  flex: none;
  padding: 0 14px;
  align-items: center;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #000000;
  background: #ffffff;
  font-size: 13px;
  white-space: nowrap;
  box-sizing: border-box;
}

.merchant__info {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 6px 8px;
  font-size: 12px;
}

.merchant__info-label {
  color: #627187;
}

.merchant__info-value {
  min-width: 0;
  overflow-wrap: anywhere;
}

.merchant__tabs {
  display: flex;
  gap: 22px;
  padding: 0 16px;
  border-bottom: 1px solid #f0f1f3;
  background: #ffffff;
}

.merchant__tab {
  padding: 10px 0;
  border-bottom: 3px solid transparent;
  color: #627187;
  font-size: 15px;
  font-weight: 400;
  white-space: nowrap;
}

.merchant__tab--on {
  border-bottom-color: #ff6a00;
  color: #202c42;
  font-weight: 700;
}

.merchant__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px calc(24px + env(safe-area-inset-bottom));
}

.merchant__skeleton {
  height: 160px;
  border-radius: 12px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.merchant__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.merchant__state-title {
  font-size: 16px;
  font-weight: 700;
}

.merchant__state-text {
  color: #738096;
  font-size: 13px;
}

.merchant__state-button {
  min-width: 120px;
  height: 40px;
  margin-top: 8px;
  padding: 0 20px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  line-height: 40px;
}

.merchant__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  align-content: start;
}

.merchant__product {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 12px;
  background: #ffffff;
}

.merchant__product-cover {
  display: block;
  width: 100%;
  height: 118px;
  background: #eef2f8;
}

.merchant__product-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px 10px;
}

.merchant__product-name {
  font-size: 14px;
  font-weight: 500;
}

.merchant__product-price {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
}

.merchant__product-unit {
  font-size: 12px;
  font-weight: 400;
}

.merchant__row-card {
  display: flex;
  gap: 10px;
  padding: 10px;
  border-radius: 12px;
  background: #ffffff;
}

.merchant__row-cover {
  display: block;
  width: 88px;
  height: 64px;
  flex: none;
  border-radius: 8px;
  background: #eef2f8;
}

.merchant__row-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.merchant__row-title {
  font-size: 14px;
  font-weight: 500;
}

.merchant__row-meta {
  color: #627187;
  font-size: 12px;
}

.merchant__row-price {
  color: #e85400;
  font-size: 13px;
  font-weight: 700;
}

.merchant__row-price--free {
  color: #11804a;
}

.merchant__content-tags {
  display: flex;
  gap: 6px;
}

.merchant__content-tag {
  color: #738096;
  font-size: 12px;
}

.merchant__content-tag--kind {
  color: #1d63c4;
}

.merchant__quals {
  padding: 4px 14px;
  border-radius: 12px;
  background: #ffffff;
}

.merchant__qual {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid #f0f1f3;
  font-size: 14px;
}

.merchant__qual--last {
  border-bottom-color: transparent;
}

.merchant__qual-label {
  width: 72px;
  flex: none;
  color: #627187;
}

.merchant__qual-value {
  min-width: 0;
  flex: 1;
}

.merchant__qual-value--green {
  color: #11804a;
}

.merchant__qual-value--muted {
  color: #738096;
}

.merchant__empty {
  padding: 40px 0;
  color: #738096;
  font-size: 14px;
  text-align: center;
}

.merchant__more {
  height: 40px;
  color: #536178;
  font-size: 14px;
  text-align: center;
}
</style>
