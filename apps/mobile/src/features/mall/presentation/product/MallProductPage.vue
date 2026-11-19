<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { ApiRequestError } from '@/shared/http/public-api-request'

import {
  resolveMallIntent,
  resolveMallPrimaryAction,
  type MallIntent,
} from '../../application/mall-intent'
import { MALL_PREVIEW_ENABLED } from '../../application/mall-preview'
import { mallApi } from '../../data/mall-api-client'
import { resolveMallPreviewCover } from '../../data/preview/mall-preview-covers'
import type { MallOffer, MallProductDetail } from '../../domain/mall'
import MallBuySheet from './MallBuySheet.vue'
import {
  MALL_BADGE_LABELS,
  MALL_CATEGORY_LABELS,
  MALL_MERCHANT_KIND_LABELS,
  formatMallOfferPrice,
} from '../shared/mall-format'

// The page host owns the account: it adds to the cart, opens the confirm page and the rental
// inquiry form.
const props = withDefaults(defineProps<{ busy?: boolean }>(), { busy: false })

const emit = defineEmits<{
  addToCart: [offerId: string, quantity: number]
  buyNow: [offerId: string, quantity: number]
  // 联系询价 on a rental product (F3): the rental inquiry form for this offer.
  rent: [productId: string, offerId: string]
}>()

type DetailState =
  | { kind: 'loading' }
  | { kind: 'ready'; product: MallProductDetail }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<DetailState>({ kind: 'loading' })
const selectedOfferId = ref<string | null>(null)
let productId = ''
let cityCode = ''
let requestVersion = 0

const product = computed(() => (state.value.kind === 'ready' ? state.value.product : null))
const selectedOffer = computed<MallOffer | null>(() =>
  product.value?.offers.find((offer) => offer.offerId === selectedOfferId.value) ?? null,
)
const primaryAction = computed(() =>
  product.value && selectedOffer.value
    ? resolveMallPrimaryAction(product.value.saleMode, selectedOffer.value.amountMinor)
    : null,
)
const packTotal = computed(() =>
  product.value?.packItems.reduce((total, item) => total + item.quantity, 0) ?? 0,
)
// The server's picture when it has one, else the sample picture of a preview build.
const cover = computed(() => {
  if (!product.value) return null
  return product.value.coverImage
    ? { url: product.value.coverImage.url, cutout: false }
    : resolveMallPreviewCover(product.value.productId)
})
// Parameters open, packing list closed, as on the prototype board.
const specsOpen = ref(true)
const packOpen = ref(false)

async function loadProduct(): Promise<void> {
  if (!productId || !cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  try {
    const loaded = await mallApi.loadProduct(productId, cityCode)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', product: loaded }
    // Offers arrive with the lowest listed price first; that one starts selected.
    const stillThere = loaded.offers.some((offer) => offer.offerId === selectedOfferId.value)
    if (!stillThere) selectedOfferId.value = loaded.offers[0]?.offerId ?? null
  } catch (error) {
    if (requested !== requestVersion) return
    const missing = error instanceof ApiRequestError
      && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function selectOffer(offer: MallOffer): void {
  selectedOfferId.value = offer.offerId
}

// Goods with a listed price open 规格与数量; everything else still goes through the gate.
const buySheetOpen = ref(false)

function addToCart(quantity: number): void {
  if (!selectedOffer.value) return
  emit('addToCart', selectedOffer.value.offerId, quantity)
  buySheetOpen.value = false
}

function buyNow(quantity: number): void {
  if (!selectedOffer.value) return
  emit('buyNow', selectedOffer.value.offerId, quantity)
  buySheetOpen.value = false
}

function runIntent(type: 'ADD_TO_CART' | 'BUY_NOW' | 'INQUIRE'): void {
  const offer = selectedOffer.value
  if (!product.value || !offer) return
  if ((type === 'ADD_TO_CART' || type === 'BUY_NOW') && primaryAction.value?.cartAvailable) {
    buySheetOpen.value = true
    return
  }
  // A rental product is asked about on the rental inquiry form (preview builds, like this button).
  if (type === 'INQUIRE' && product.value.saleMode === 'RENT' && MALL_PREVIEW_ENABLED) {
    emit('rent', product.value.productId, offer.offerId)
    return
  }
  const resolution = resolveMallIntent(
    { type, productId: product.value.productId, offerId: offer.offerId },
    MALL_PREVIEW_ENABLED,
  )
  if (resolution.action === 'SHOW_NOTICE') {
    uni.showToast({ title: resolution.message, icon: 'none' })
  }
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('MALL')
}

onLoad((query) => {
  productId = typeof query?.productId === 'string' ? query.productId : ''
  cityCode = typeof query?.cityCode === 'string' ? query.cityCode : ''
  void loadProduct()
})
</script>

<template>
  <view class="mall-product" data-testid="mall-product">
    <!-- With a product on screen the back button floats on its picture instead. -->
    <header v-if="!product" class="mall-product__header">
      <button class="mall-product__back" data-testid="mall-product-back" aria-label="返回" @click="goBack">
        <UniIcons type="left" size="24" color="#435369" />
      </button>
      <text class="mall-product__header-title">商品详情</text>
    </header>

    <view
      v-if="state.kind === 'loading'"
      class="mall-product__content"
      data-testid="mall-product-loading"
      aria-label="正在加载"
    >
      <view class="mall-product__skeleton mall-product__skeleton--cover" />
      <view class="mall-product__skeleton mall-product__skeleton--block" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="mall-product__state" data-testid="mall-product-missing">
      <text class="mall-product__state-title">商品不存在或本城暂不销售</text>
      <text class="mall-product__state-copy">可以回到商城看看其他商品</text>
      <button class="mall-product__state-action" data-testid="mall-product-to-channel" @click="openHomeChannel('MALL')">
        去商城
      </button>
    </view>

    <view v-else-if="state.kind === 'error'" class="mall-product__state" data-testid="mall-product-error">
      <text class="mall-product__state-title">商品加载失败，请稍后重试</text>
      <button class="mall-product__state-action" data-testid="mall-product-retry" @click="loadProduct">
        重新加载
      </button>
    </view>

    <template v-else-if="product">
      <view class="mall-product__content">
        <view :class="['mall-product__cover', { 'mall-product__cover--cutout': cover?.cutout }]">
          <image
            v-if="cover"
            class="mall-product__cover-image"
            :src="cover.url"
            :alt="product.name"
            mode="aspectFill"
          />
          <text v-else class="mall-product__cover-label">{{ MALL_CATEGORY_LABELS[product.category] }}</text>
          <button class="mall-product__float" data-testid="mall-product-back" aria-label="返回" @click="goBack">
            <view class="mall-product__float-circle">
              <UniIcons type="left" size="20" color="#ffffff" />
            </view>
          </button>
        </view>

        <view class="mall-product__card mall-product__card--lead">
          <view class="mall-product__price-row">
            <text
              v-if="selectedOffer"
              class="mall-product__price"
              data-testid="mall-product-price"
            >
              {{ formatMallOfferPrice(selectedOffer.amountMinor, selectedOffer.unit) }}
            </text>
            <text class="mall-product__price-note">
              {{ product.offers.length }} 家{{ product.saleMode === 'RENT' ? '可租' : '在售' }}
            </text>
          </view>
          <text class="mall-product__name" data-testid="mall-product-name">{{ product.name }}</text>
          <text v-if="product.summary" class="mall-product__summary">{{ product.summary }}</text>
          <view v-if="product.experienceBadge || product.saleMode === 'RENT'" class="mall-product__tags">
            <text v-if="product.saleMode === 'RENT'" class="mall-product__tag">租赁</text>
            <text v-if="product.experienceBadge" class="mall-product__tag mall-product__tag--experience">
              {{ MALL_BADGE_LABELS[product.experienceBadge] }}
            </text>
          </view>
          <view v-if="selectedOffer?.shippingNote" class="mall-product__delivery">
            <text class="mall-product__delivery-label">{{ product.saleMode === 'RENT' ? '交付' : '配送' }}</text>
            <text class="mall-product__delivery-value" data-testid="mall-product-shipping">
              {{ selectedOffer.shippingNote }}
            </text>
          </view>
        </view>

        <view class="mall-product__card">
          <view class="mall-product__section-heading">
            <text class="mall-product__section-title">
              {{ product.saleMode === 'RENT' ? '选择可租商家' : '选择在售商家' }}
            </text>
            <text class="mall-product__section-note">同型号 · 不同供给</text>
          </view>
          <button
            v-for="offer in product.offers"
            :key="offer.offerId"
            :class="[
              'mall-product__offer',
              { 'mall-product__offer--selected': offer.offerId === selectedOfferId },
            ]"
            :data-testid="`mall-offer-${offer.offerId}`"
            :aria-pressed="offer.offerId === selectedOfferId"
            @click="selectOffer(offer)"
          >
            <view class="mall-product__radio">
              <text v-if="offer.offerId === selectedOfferId">✓</text>
            </view>
            <view class="mall-product__offer-copy">
              <view class="mall-product__offer-heading">
                <text class="mall-product__offer-name">{{ offer.merchantName }}</text>
                <text class="mall-product__offer-kind">{{ MALL_MERCHANT_KIND_LABELS[offer.merchantKind] }}</text>
              </view>
              <text v-if="offer.deliveryNote" class="mall-product__offer-note">{{ offer.deliveryNote }}</text>
            </view>
            <text class="mall-product__offer-price">{{ formatMallOfferPrice(offer.amountMinor, offer.unit) }}</text>
          </button>
        </view>

        <view v-if="selectedOffer" class="mall-product__card">
          <view class="mall-product__section-heading">
            <text class="mall-product__section-title">交易主体</text>
            <text class="mall-product__section-note">随所选商家变化</text>
          </view>
          <view class="mall-product__parties">
            <text class="mall-product__party-label">销售方</text>
            <text class="mall-product__party-value" data-testid="mall-product-seller">
              {{ selectedOffer.merchantName }}（{{ MALL_MERCHANT_KIND_LABELS[selectedOffer.merchantKind] }}）
            </text>
            <template v-if="selectedOffer.fulfilledFrom">
              <text class="mall-product__party-label">{{ product.saleMode === 'RENT' ? '交付' : '发货履约' }}</text>
              <text class="mall-product__party-value">{{ selectedOffer.fulfilledFrom }}</text>
            </template>
          </view>
        </view>

        <view
          v-if="product.specs.length || product.packItems.length"
          class="mall-product__card mall-product__card--folds"
        >
          <view v-if="product.specs.length" data-testid="mall-product-specs">
            <button
              class="mall-product__fold"
              data-testid="mall-product-specs-toggle"
              :aria-expanded="specsOpen ? 'true' : 'false'"
              @click="specsOpen = !specsOpen"
            >
              <text class="mall-product__fold-title">参数</text>
              <text class="mall-product__fold-count">{{ product.specs.length }} 项</text>
              <UniIcons class="mall-product__fold-arrow" :type="specsOpen ? 'up' : 'down'" size="14" color="#738096" />
            </button>
            <view v-if="specsOpen" class="mall-product__spec-table">
              <template v-for="spec in product.specs" :key="spec.label">
                <text class="mall-product__spec-label">{{ spec.label }}</text>
                <text class="mall-product__spec-value">{{ spec.value }}</text>
              </template>
            </view>
          </view>
          <view
            v-if="product.packItems.length"
            :class="{ 'mall-product__fold-divider': product.specs.length > 0 }"
            data-testid="mall-product-pack"
          >
            <button
              class="mall-product__fold"
              data-testid="mall-product-pack-toggle"
              :aria-expanded="packOpen ? 'true' : 'false'"
              @click="packOpen = !packOpen"
            >
              <text class="mall-product__fold-title">包装清单</text>
              <text class="mall-product__fold-count">{{ packTotal }} 件</text>
              <UniIcons class="mall-product__fold-arrow" :type="packOpen ? 'up' : 'down'" size="14" color="#738096" />
            </button>
            <view v-if="packOpen" class="mall-product__pack-list">
              <view v-for="item in product.packItems" :key="item.name" class="mall-product__pack-row">
                <text class="mall-product__pack-name">{{ item.name }}</text>
                <text class="mall-product__pack-quantity">×{{ item.quantity }}</text>
              </view>
            </view>
          </view>
        </view>
      </view>

      <view v-if="MALL_PREVIEW_ENABLED && primaryAction" class="mall-product__actions">
        <view class="mall-product__action-group">
        <button
          v-if="primaryAction.cartAvailable"
          class="mall-product__secondary"
          data-testid="mall-product-cart"
          @click="runIntent('ADD_TO_CART')"
        >
          加入购物车
        </button>
        <button
          class="mall-product__primary"
          data-testid="mall-product-primary"
          :data-intent="primaryAction.intent"
          @click="runIntent(primaryAction.intent)"
        >
          {{ primaryAction.label }}
        </button>
        </view>
      </view>

      <MallBuySheet
        v-if="buySheetOpen && selectedOffer"
        :offer="selectedOffer"
        :cover-url="cover?.url ?? null"
        :busy="props.busy"
        @close="buySheetOpen = false"
        @add-to-cart="addToCart"
        @buy-now="buyNow"
      />
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board PdFull (390 wide). */
.mall-product {
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
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
}

button::after {
  border: 0;
}

.mall-product__header {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  min-height: calc(50px + var(--status-bar-height));
  padding: var(--status-bar-height) 12px 0;
  align-items: center;
  gap: 8px;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
  box-sizing: border-box;
}

.mall-product__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
}

.mall-product__header-title {
  font-size: 18px;
  font-weight: 700;
}

.mall-product__content {
  display: flex;
  padding-bottom: 16px;
  flex: 1;
  flex-direction: column;
  gap: 12px;
}

.mall-product__skeleton {
  margin: 0 16px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
  background-size: 400% 100%;
  animation: mall-product-skeleton 1.25s ease infinite;
}

.mall-product__skeleton--cover {
  height: 300px;
  margin: 0;
  border-radius: 0;
}

.mall-product__skeleton--block {
  height: 160px;
}

@keyframes mall-product-skeleton {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}

.mall-product__state {
  display: flex;
  margin: 12px 16px;
  min-height: 300px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 14px;
  background: #ffffff;
}

.mall-product__state-title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
}

.mall-product__state-copy {
  color: #627187;
  font-size: 13px;
}

.mall-product__state-action {
  display: flex;
  min-width: 108px;
  min-height: 44px;
  margin-top: 8px;
  padding: 0 18px;
  align-items: center;
  justify-content: center;
  border-radius: 22px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
}

.mall-product__cover {
  position: relative;
  display: flex;
  height: 300px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #eef0f3;
}

.mall-product__cover--cutout {
  background: #f0edff;
}

.mall-product__cover-image {
  width: 100%;
  height: 100%;
}

.mall-product__cover-label {
  color: #738096;
  font-size: 14px;
}

.mall-product__float {
  position: absolute;
  top: calc(var(--status-bar-height) + 4px);
  left: 8px;
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.mall-product__float-circle {
  display: flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
}

.mall-product__card {
  display: flex;
  margin: 0 16px;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

/* The first card overlaps the picture. */
.mall-product__card--lead {
  position: relative;
  margin-top: -36px;
  gap: 6px;
}

.mall-product__price-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.mall-product__price {
  color: #e85400;
  font-size: 26px;
  font-weight: 700;
}

.mall-product__price-note {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
}

.mall-product__name {
  font-size: 18px;
  font-weight: 700;
}

.mall-product__summary {
  color: #627187;
  font-size: 13px;
}

.mall-product__tags {
  display: flex;
  gap: 6px;
}

.mall-product__tag {
  padding: 2px 6px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 11px;
}

.mall-product__tag--experience {
  color: #11804a;
  background: #e2f6ed;
}

.mall-product__delivery {
  display: flex;
  min-height: 44px;
  margin: 4px 0 -8px;
  align-items: center;
  gap: 12px;
  border-top: 1px solid #f0f1f3;
}

.mall-product__delivery-label {
  width: 40px;
  flex: 0 0 auto;
  color: #627187;
  font-size: 13px;
}

.mall-product__delivery-value {
  min-width: 0;
  flex: 1;
  font-size: 14px;
}

.mall-product__section-heading {
  display: flex;
  align-items: baseline;
}

.mall-product__section-title {
  font-size: 16px;
  font-weight: 700;
}

.mall-product__section-note {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
}

.mall-product__offer {
  display: flex;
  width: 100%;
  padding: 12px;
  align-items: center;
  gap: 10px;
  border: 1.5px solid #e5ebf3;
  border-radius: 10px;
  background: #ffffff;
  box-sizing: border-box;
}

.mall-product__offer--selected {
  border-color: #ff6a00;
  background: #fff8f2;
}

.mall-product__radio {
  display: flex;
  width: 20px;
  height: 20px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #c9d3e1;
  border-radius: 50%;
  color: #ffffff;
  font-size: 12px;
  box-sizing: border-box;
}

.mall-product__offer--selected .mall-product__radio {
  border-color: #ff6a00;
  background: #ff6a00;
}

.mall-product__offer-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.mall-product__offer-heading {
  display: flex;
  align-items: center;
  gap: 6px;
}

.mall-product__offer-name {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mall-product__offer-kind {
  padding: 0 5px;
  flex: 0 0 auto;
  border: 1px solid #e5ebf3;
  border-radius: 4px;
  color: #536178;
  font-size: 11px;
  line-height: 16px;
}

.mall-product__offer-note {
  color: #627187;
  font-size: 12px;
}

.mall-product__offer-price {
  flex: 0 0 auto;
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
}

.mall-product__offer--selected .mall-product__offer-price {
  color: #e85400;
}

.mall-product__parties {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 8px 10px;
  font-size: 13px;
}

.mall-product__party-label {
  color: #627187;
}

.mall-product__card--folds {
  padding: 0 14px;
  gap: 0;
}

.mall-product__fold {
  display: flex;
  width: 100%;
  min-height: 52px;
  align-items: center;
  gap: 8px;
}

.mall-product__fold-divider {
  border-top: 1px solid #f0f1f3;
}

.mall-product__fold-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.mall-product__fold-count {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.mall-product__fold-arrow {
  margin-left: auto;
}

.mall-product__spec-table {
  display: grid;
  margin-bottom: 14px;
  grid-template-columns: 96px 1fr;
  overflow: hidden;
  border: 1px solid #f0f1f3;
  border-radius: 8px;
  font-size: 13px;
}

.mall-product__spec-label,
.mall-product__spec-value {
  padding: 8px 10px;
  border-bottom: 1px solid #f0f1f3;
}

.mall-product__spec-label {
  color: #627187;
  background: #fafafb;
}

.mall-product__pack-list {
  display: flex;
  margin-bottom: 14px;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #f0f1f3;
  border-radius: 8px;
  font-size: 13px;
}

.mall-product__pack-row {
  display: flex;
  padding: 8px 10px;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid #f0f1f3;
}

.mall-product__pack-name {
  min-width: 0;
  flex: 1;
}

.mall-product__pack-quantity {
  flex: 0 0 auto;
  color: #536178;
}

.mall-product__actions {
  position: sticky;
  bottom: 0;
  z-index: 5;
  padding: 8px 12px calc(8px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: 0 -4px 18px rgba(38, 59, 98, 0.06);
}

/* One pill: the two buttons share its rounded ends. */
.mall-product__action-group {
  display: flex;
  height: 44px;
  overflow: hidden;
  border-radius: 999px;
}

.mall-product__secondary,
.mall-product__primary {
  display: flex;
  min-width: 0;
  padding: 0 10px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border-radius: 0;
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
}

.mall-product__secondary {
  color: #ba470b;
  background: #fff0e3;
}

.mall-product__primary {
  color: #ffffff;
  background: #ff6a00;
}
</style>
