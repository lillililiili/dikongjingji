<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { useRootTabBadgeStore } from '@/app-shell/state/root-tab-badges'
import { CONTENT_PREVIEW_ENABLED } from '@/features/content/application/content-preview'
import { loadRelatedContentRows, type RelatedContentRow } from '@/features/content/application/related-content'
import { contentApi } from '@/features/content/data/content-api-client'
import ContentRelatedBlock from '@/features/content/presentation/related/ContentRelatedBlock.vue'
import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import { FAVORITE_PREVIEW_ENABLED } from '@/features/favorite/application/favorite-preview'
import FavoriteButton from '@/features/favorite/presentation/FavoriteButton.vue'
import {
  loadProductExperienceView,
  type ProductExperienceView,
} from '@/features/experience/application/product-experience'
import { experienceApi } from '@/features/experience/data/experience-api-client'
import ProductTryFirst from '@/features/experience/presentation/product/ProductTryFirst.vue'
import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { buildSupportHelpUrl } from '@/features/support/navigation/support-routes'
import TradePartiesCard from '@/features/trade/presentation/shared/TradePartiesCard.vue'
import {
  goodsParties,
  inquiryParties,
  rentalParties,
} from '@/features/trade/presentation/shared/trade-parties'
import { ApiRequestError } from '@/shared/http/public-api-request'
import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { loadInquiryCityName } from '../../application/inquiry-city'
import {
  resolveMallIntent,
  resolveMallPrimaryAction,
} from '../../application/mall-intent'
import { MALL_PREVIEW_ENABLED } from '../../application/mall-preview'
import { mallApi } from '../../data/mall-api-client'
import { CART_TAB_PATH, buildMallProductUrl } from '../../navigation/mall-routes'
import { resolveMallPreviewCover } from '../../data/preview/mall-preview-covers'
import type { DeliveryMode } from '../../domain/cart'
import type { MallOffer, MallProductDetail } from '../../domain/mall'
import type { MallBuyDeliveryLoader } from './buy-delivery'
import MallBuySheet from './MallBuySheet.vue'
import MallProductMoreSheet from './MallProductMoreSheet.vue'
import {
  MALL_BADGE_LABELS,
  MALL_CATEGORY_LABELS,
  MALL_MERCHANT_KIND_LABELS,
  formatMallDetailPrice,
  formatMallOfferPrice,
} from '../shared/mall-format'

// The page host owns the account: it adds to the cart, opens the confirm page and the rental
// inquiry form, and reads the delivery options of the 规格与数量 sheet.
const props = withDefaults(
  defineProps<{
    busy?: boolean
    loadDelivery?: MallBuyDeliveryLoader | null
  }>(),
  { busy: false, loadDelivery: null },
)

const emit = defineEmits<{
  addToCart: [offerId: string, quantity: number]
  buyNow: [offerId: string, quantity: number, delivery: DeliveryMode | null]
  // 联系询价 on a rental product (F3): the rental inquiry form for this offer.
  rent: [productId: string, offerId: string]
  // 联系询价 on an inquiry product (F4): the inquiry form, which picks the merchants itself.
  inquire: [productId: string]
}>()

// Board icons that line-icons.ts does not list in this exact form.
const SHARE_PATH = 'M12 15V4M8 8l4-4 4 4M5 13v6h14v-6'
const STORE_PATH = 'M4 9l2-5h12l2 5M4 9v11h16V9M4 9h16M9 20v-6h6v6'

// 询价怎么走 (PdInquiryFull board): how an inquiry product gets its price. The quote window is 72
// hours (§25.1 rule 3), not the board's two working days.
const INQUIRY_STEPS = [
  { title: '填询价单', note: '不收费' },
  { title: '商家报价', note: '72 小时内' },
  { title: '比选选定', note: '看价格和交期' },
  { title: '确认下单', note: '选定后付款' },
] as const

// 租赁须知 and 租赁怎么走 (PdRental board), in the words of the rental rules settled in §24: one day
// at least, priced by the day; the deposit is not taken online; handover and return are recorded by
// the merchant and confirmed by the customer; damage is settled by the contract.
const RENTAL_TERMS = [
  { title: '租期', tag: '', text: '1 天起租，按天计费；长期租用的价格以报价为准', d: 'M5 5h14v15H5zM5 10h14M9 3v4M15 3v4' },
  { title: '押金', tag: '待定', text: '押金规则待确认，当前不在线收取', d: 'M12 3l7 3v5c0 5-3 8-7 10c-4-2-7-5-7-10V6z' },
  { title: '交接', tag: '', text: '商家送达或到店自提，双方按清单核对设备和配件，留下交接记录', d: 'M4 9h14l-3-3M20 15H6l3 3' },
  { title: '损坏处理', tag: '', text: '归还时核对交接记录，人为损坏按合同赔付', d: 'M12 4l9 16H3zM12 10v5M12 18h.01' },
] as const
const RENTAL_STEPS = ['提交询价', '商家报价', '确认合同', '交接使用', '归还结束'] as const

type DetailState =
  | { kind: 'loading' }
  | { kind: 'ready'; product: MallProductDetail }
  | { kind: 'missing' }
  | { kind: 'error' }

const badgeStore = useRootTabBadgeStore()
const state = ref<DetailState>({ kind: 'loading' })
const selectedOfferId = ref<string | null>(null)
const cityName = ref<string | null>(null)
// 先体验，再决定 (R6-2) and 相关内容 (R6-3): read from the experience and content modules by this
// product's id; null / empty hides the block (and the bar's 先体验).
const tryFirst = ref<ProductExperienceView | null>(null)
const relatedRows = ref<RelatedContentRow[]>([])
const pageCity = ref('')
let productId = ''
let cityCode = ''
let requestVersion = 0

const product = computed(() => (state.value.kind === 'ready' ? state.value.product : null))
const saleMode = computed(() => product.value?.saleMode ?? 'BUY')
const selectedOffer = computed<MallOffer | null>(() =>
  product.value?.offers.find((offer) => offer.offerId === selectedOfferId.value) ?? null,
)
// What the delivery row and parties show: the chosen offer, or the first one while none of them
// can be chosen (no merchant takes payment online).
const shownOffer = computed<MallOffer | null>(() => selectedOffer.value ?? product.value?.offers[0] ?? null)
// An inquiry product is asked about on its own form, which picks the merchants; buying and renting
// need a chosen merchant that takes payment online.
const primaryAction = computed(() => {
  if (!product.value || !shownOffer.value) return null
  if (product.value.saleMode !== 'INQUIRY' && !selectedOffer.value) return null
  return resolveMallPrimaryAction(product.value.saleMode, shownOffer.value.amountMinor)
})
const noPayableOffer = computed(() =>
  Boolean(product.value && product.value.saleMode !== 'INQUIRY' && product.value.offers.length > 0 && !selectedOffer.value),
)
// The price card shows the lowest listed price of all the merchants (PdRentalAerial "¥260/天 起");
// the chosen merchant's own price stays on its card below.
const price = computed(() => {
  if (!product.value || !shownOffer.value) return null
  const listedOffers = product.value.offers.filter((item) => item.amountMinor !== null)
  const lowest = listedOffers.reduce<MallOffer | null>(
    (best, item) => (best === null || (item.amountMinor as number) < (best.amountMinor as number) ? item : best),
    null,
  )
  const offer = lowest ?? shownOffer.value
  const lowestOfSeveral = new Set(listedOffers.map((item) => item.amountMinor)).size > 1
  return formatMallDetailPrice(offer.amountMinor, offer.unit, { saleMode: product.value.saleMode, lowestOfSeveral })
})
// "3 家在售 · 东营可售" on the right of the price.
const sellersText = computed(() => {
  if (!product.value) return ''
  const count = product.value.offers.length
  const city = cityName.value?.replace(/市$/, '') ?? null
  if (saleMode.value === 'RENT') return `租赁 · ${count} 家${city ? ` · ${city}可租` : ''}`
  if (saleMode.value === 'INQUIRY') return `${count} 家可报价${city ? ` · ${city}可售` : ''}`
  return `${count} 家在售${city ? ` · ${city}可售` : ''}`
})
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
const parties = computed(() => {
  const offer = shownOffer.value
  if (!offer) return []
  if (saleMode.value === 'INQUIRY') return inquiryParties()
  const input = {
    merchantName: offer.merchantName,
    merchantKindLabel: MALL_MERCHANT_KIND_LABELS[offer.merchantKind],
    fulfilledFrom: offer.fulfilledFrom,
    sellerTestId: 'mall-product-seller',
  }
  const rows = saleMode.value === 'RENT' ? rentalParties(input) : goodsParties(input)
  // Public builds take no payment, so they do not name a payee.
  return MALL_PREVIEW_ENABLED ? rows : rows.filter((row) => row.label !== '收款方')
})
const partiesNote = computed(() => {
  if (saleMode.value === 'INQUIRY') return '选定报价后确定'
  const count = product.value?.offers.length ?? 0
  if (saleMode.value === 'RENT' && count === 1) return '本商品 1 家出租'
  return '随所选商家变化'
})
// 售后与保障: only the two lines that promise nothing the platform has not settled (R5 #8).
const afterSales = computed(() => [
  { title: '平台协助', text: '与商家协商不成时，可联系平台客服协助沟通' },
  { title: '发票', text: `由${saleMode.value === 'RENT' ? '出租方' : '销售方'}开具，平台不代开` },
])
const specNote = computed(() =>
  saleMode.value === 'INQUIRY' ? '参数由厂家填报，平台审核后展示；实际配置以报价单为准' : '参数由厂家填报，平台审核后展示')
// The inquiry 配置 and 交付 rows: the spec the merchants quote for and how it is delivered here.
const inquiryDelivery = computed(() =>
  product.value?.offers.find((offer) => offer.shippingNote)?.shippingNote ?? null)
// One fold open at a time, the parameters first, as on the board.
const openFold = ref<'spec' | 'pack' | ''>('spec')

function toggleFold(fold: 'spec' | 'pack'): void {
  openFold.value = openFold.value === fold ? '' : fold
}

async function loadProduct(): Promise<void> {
  if (!productId || !cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  if (!cityName.value) {
    void loadInquiryCityName(cityCode).then((name) => {
      if (requested === requestVersion) cityName.value = name
    }).catch(() => undefined)
  }
  try {
    const loaded = await mallApi.loadProduct(productId, cityCode)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', product: loaded }
    // Offers arrive with the lowest listed price first; the first one whose merchant takes payment
    // online starts selected (catalog 1.2.0). A merchant without it is shown, greyed, never chosen.
    const stillThere = loaded.offers.some((offer) => offer.offerId === selectedOfferId.value && offer.onlinePayment)
    if (!stillThere) selectedOfferId.value = loaded.offers.find((offer) => offer.onlinePayment)?.offerId ?? null
    loadExtras(requested, loaded.productId)
  } catch (error) {
    if (requested !== requestVersion) return
    const missing = error instanceof ApiRequestError
      && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

// The two blocks other modules supply, each only on preview builds of its own module. A failed read
// leaves its block out; a later product load drops what an earlier one brought.
function loadExtras(requested: number, loadedProductId: string): void {
  tryFirst.value = null
  relatedRows.value = []
  if (EXPERIENCE_PREVIEW_ENABLED) {
    void loadProductExperienceView(experienceApi, loadedProductId, cityCode).then((view) => {
      if (requested === requestVersion) tryFirst.value = view
    })
  }
  if (CONTENT_PREVIEW_ENABLED) {
    void loadRelatedContentRows(contentApi, 'PRODUCT', loadedProductId, cityCode).then((rows) => {
      if (requested === requestVersion) relatedRows.value = rows
    })
  }
}

// 先体验 in the bar brings the block into view (boards PdFull, PdRental).
function showTryFirst(): void {
  uni.pageScrollTo({ selector: '#mall-product-try-first', duration: 300 })
}

function selectOffer(offer: MallOffer): void {
  if (!offer.onlinePayment) {
    uni.showToast({ title: '这家商家暂未开通在线支付，请选其他商家', icon: 'none' })
    return
  }
  selectedOfferId.value = offer.offerId
}

// An inquiry product's merchant row opens the inquiry form, where the merchants are picked.
function askOffer(offer: MallOffer): void {
  if (!offer.onlinePayment) {
    uni.showToast({ title: '这家商家暂未开通在线支付，请选其他商家', icon: 'none' })
    return
  }
  runIntent('INQUIRE')
}

// 咨询 (step G1): the merchant of the offer on screen, with a card about this product.
function consult(): void {
  const offer = shownOffer.value
  if (!product.value || !offer) return
  openSupportChat(
    { kind: 'MERCHANT', organizationId: offer.merchantId },
    {
      type: 'PRODUCT',
      refId: product.value.productId,
      title: product.value.name,
      subtitle: formatMallOfferPrice(offer.amountMinor, offer.unit),
    },
  )
}

// 分享: only 复制链接, the address of this product in this city.
const shareTarget = ref<ShareTarget | null>(null)
function openShare(): void {
  if (!product.value) return
  const offer = shownOffer.value
  shareTarget.value = {
    subject: '商品',
    title: product.value.name,
    subtitle: offer ? formatMallOfferPrice(offer.amountMinor, offer.unit) : null,
    owner: offer?.merchantName ?? null,
    imageUrl: cover.value?.url ?? null,
    path: buildMallProductUrl(product.value.productId, cityCode),
  }
}

function openCart(): void {
  uni.switchTab({ url: CART_TAB_PATH })
}

// 更多 (board MoreSheet): 回首页, 反馈, 举报.
const moreOpen = ref(false)
const moreSubtitle = computed(() => {
  const offer = shownOffer.value
  const kind = saleMode.value === 'RENT' ? '租赁' : saleMode.value === 'INQUIRY' ? '询价商品' : '商品'
  return offer ? `${offer.merchantName} · ${kind}` : kind
})

function moreHome(): void {
  moreOpen.value = false
  openHomeChannel('RECOMMEND')
}

function moreFeedback(): void {
  moreOpen.value = false
  uni.navigateTo({ url: buildSupportHelpUrl() })
}

function moreReport(): void {
  moreOpen.value = false
  if (!product.value) return
  const offer = shownOffer.value
  openSupportChat(
    { kind: 'PLATFORM' },
    {
      type: 'PRODUCT',
      refId: product.value.productId,
      title: product.value.name,
      subtitle: offer ? formatMallOfferPrice(offer.amountMinor, offer.unit) : '',
    },
  )
}

// Goods with a listed price open 规格与数量; everything else still goes through the gate.
const buySheetOpen = ref(false)

function addToCart(quantity: number): void {
  if (!selectedOffer.value) return
  emit('addToCart', selectedOffer.value.offerId, quantity)
  buySheetOpen.value = false
}

function buyNow(quantity: number, delivery: DeliveryMode | null): void {
  if (!selectedOffer.value) return
  emit('buyNow', selectedOffer.value.offerId, quantity, delivery)
  buySheetOpen.value = false
}

// 10 件以上: a bulk purchase of a listed product is asked about in a conversation with its merchant
// (the inquiry form only takes inquiry products, §25.1).
function bulkInquiry(): void {
  buySheetOpen.value = false
  consult()
}

// 已选 and 配送 open the sheet of a product that can be bought.
function openBuySheet(): void {
  if (MALL_PREVIEW_ENABLED && primaryAction.value?.cartAvailable) buySheetOpen.value = true
}

// No merchant takes payment online: the button stays orange and says so (R5 #1).
function explainNoPayable(): void {
  uni.showToast({ title: '暂无可在线付款的商家，请稍后再来', icon: 'none' })
}

function runIntent(type: 'ADD_TO_CART' | 'BUY_NOW' | 'INQUIRE'): void {
  // An inquiry product needs no chosen merchant: its form picks the ones to ask.
  const offer = product.value?.saleMode === 'INQUIRY' ? shownOffer.value : selectedOffer.value
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
  // An inquiry product is asked about on the inquiry form (preview builds, like this button).
  if (type === 'INQUIRE' && product.value.saleMode === 'INQUIRY' && MALL_PREVIEW_ENABLED) {
    emit('inquire', product.value.productId)
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
  pageCity.value = cityCode
  void loadProduct()
})
</script>

<template>
  <view class="mall-product" data-testid="mall-product">
    <!-- With a product on screen the back button floats on its picture instead. -->
    <AppPageHeader v-if="!product" title="商品详情" test-id="mall-product" @back="goBack" />

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
          <view class="mall-product__floats">
            <button class="mall-product__float" data-testid="mall-product-back" aria-label="返回" @click="goBack">
              <view class="mall-product__float-circle">
                <LineIcon name="back" :size="20" color="#ffffff" :stroke-width="2" />
              </view>
            </button>
            <view class="mall-product__floats-gap" />
            <button
              v-if="SUPPORT_PREVIEW_ENABLED"
              class="mall-product__float"
              data-testid="mall-product-share"
              aria-label="分享"
              @click="openShare"
            >
              <view class="mall-product__float-circle">
                <LineIcon :d="SHARE_PATH" :size="20" color="#ffffff" :stroke-width="2" />
              </view>
            </button>
            <button
              v-if="MALL_PREVIEW_ENABLED"
              class="mall-product__float"
              data-testid="mall-product-to-cart"
              aria-label="购物车"
              @click="openCart"
            >
              <view class="mall-product__float-circle">
                <LineIcon name="cart" :size="20" color="#ffffff" :stroke-width="2" />
              </view>
              <text v-if="badgeStore.cartCount > 0" class="mall-product__float-badge" data-testid="mall-product-cart-count">
                {{ badgeStore.cartCount > 99 ? '99+' : badgeStore.cartCount }}
              </text>
            </button>
            <button
              v-if="SUPPORT_PREVIEW_ENABLED"
              class="mall-product__float"
              data-testid="mall-product-more"
              aria-label="更多"
              @click="moreOpen = true"
            >
              <view class="mall-product__float-circle">
                <LineIcon name="more" :size="20" color="#ffffff" :stroke-width="3.6" />
              </view>
            </button>
          </view>
        </view>

        <view class="mall-product__card mall-product__card--lead">
          <view class="mall-product__price-row">
            <template v-if="price">
              <text class="mall-product__price" data-testid="mall-product-price">{{ price.amount }}</text>
              <text v-if="price.suffix" class="mall-product__price-unit" data-testid="mall-product-price-unit">{{ price.suffix }}</text>
            </template>
            <text class="mall-product__price-note" data-testid="mall-product-sellers">{{ sellersText }}</text>
          </view>
          <text class="mall-product__name" data-testid="mall-product-name">{{ product.name }}</text>
          <text v-if="product.summary" class="mall-product__summary">{{ product.summary }}</text>
          <view v-if="product.experienceBadge || product.saleMode === 'RENT'" class="mall-product__tags">
            <text v-if="product.saleMode === 'RENT'" class="mall-product__tag">租赁</text>
            <text v-if="product.experienceBadge" class="mall-product__tag mall-product__tag--experience">
              {{ MALL_BADGE_LABELS[product.experienceBadge] }}
            </text>
          </view>
          <button
            v-if="shownOffer?.shippingNote && product.saleMode === 'BUY'"
            class="mall-product__delivery"
            data-testid="mall-product-delivery"
            @click="openBuySheet"
          >
            <text class="mall-product__delivery-label">配送</text>
            <text class="mall-product__delivery-value" data-testid="mall-product-shipping">
              {{ shownOffer.shippingNote }}
            </text>
            <LineIcon
              v-if="MALL_PREVIEW_ENABLED && primaryAction?.cartAvailable"
              name="forward"
              :size="14"
              color="#91a0b4"
              :stroke-width="2"
            />
          </button>
        </view>

        <!-- 先体验，再决定: right under the price card, before the merchants (PdFull) or the rental terms (PdRental). -->
        <view v-if="tryFirst" id="mall-product-try-first" class="mall-product__try-first">
          <ProductTryFirst :view="tryFirst" :city-code="pageCity" />
        </view>

        <view
          v-if="MALL_PREVIEW_ENABLED && product.saleMode === 'RENT'"
          class="mall-product__card mall-product__card--terms"
          data-testid="mall-product-rental-terms"
        >
          <text class="mall-product__section-title mall-product__terms-title">租赁须知</text>
          <view v-for="term in RENTAL_TERMS" :key="term.title" class="mall-product__term">
            <view class="mall-product__term-icon">
              <LineIcon :d="term.d" :size="18" color="#ff6a00" />
            </view>
            <view class="mall-product__term-copy">
              <view class="mall-product__term-heading">
                <text class="mall-product__term-title">{{ term.title }}</text>
                <text v-if="term.tag" class="mall-product__term-tag">{{ term.tag }}</text>
              </view>
              <text class="mall-product__term-text">{{ term.text }}</text>
            </view>
          </view>
        </view>

        <view
          v-if="MALL_PREVIEW_ENABLED && product.saleMode !== 'BUY'"
          class="mall-product__card mall-product__card--steps"
          :data-testid="product.saleMode === 'RENT' ? 'mall-product-rental-steps' : 'mall-product-inquiry-steps'"
        >
          <view class="mall-product__section-heading">
            <text class="mall-product__section-title">{{ product.saleMode === 'RENT' ? '租赁怎么走' : '询价怎么走' }}</text>
            <text class="mall-product__section-note mall-product__section-note--free">提交询价不产生费用</text>
          </view>
          <view
            v-if="product.saleMode === 'RENT'"
            class="mall-product__steps mall-product__steps--five"
            role="list"
            aria-label="租赁流程"
          >
            <view v-for="(step, index) in RENTAL_STEPS" :key="step" class="mall-product__step" role="listitem">
              <view
                :class="[
                  'mall-product__step-line',
                  { 'mall-product__step-line--first': index === 0, 'mall-product__step-line--last': index === RENTAL_STEPS.length - 1 },
                ]"
              />
              <text class="mall-product__step-no mall-product__step-no--wide">{{ index + 1 }}</text>
              <text class="mall-product__step-title mall-product__step-title--small">{{ step }}</text>
            </view>
          </view>
          <view v-else class="mall-product__steps" role="list" aria-label="询价流程">
            <view
              v-for="(step, index) in INQUIRY_STEPS"
              :key="step.title"
              class="mall-product__step"
              role="listitem"
            >
              <view
                :class="[
                  'mall-product__step-line',
                  { 'mall-product__step-line--first': index === 0, 'mall-product__step-line--last': index === INQUIRY_STEPS.length - 1 },
                ]"
              />
              <text class="mall-product__step-no">{{ index + 1 }}</text>
              <text class="mall-product__step-title">{{ step.title }}</text>
              <text class="mall-product__step-note">{{ step.note }}</text>
            </view>
          </view>
          <text v-if="product.saleMode === 'RENT'" class="mall-product__steps-hint">商家在 7 天内报价，档期在报价时确认。</text>
        </view>

        <view class="mall-product__card">
          <view class="mall-product__section-heading">
            <text class="mall-product__section-title">
              {{ product.saleMode === 'RENT' ? '选择可租商家' : product.saleMode === 'INQUIRY' ? '可报价商家' : '选择在售商家' }}
            </text>
            <text class="mall-product__section-note">同型号 · 不同供给</text>
          </view>
          <template v-if="product.saleMode === 'INQUIRY'">
            <button
              v-for="offer in product.offers"
              :key="offer.offerId"
              :class="['mall-product__offer', { 'mall-product__offer--unpaid': !offer.onlinePayment }]"
              :data-testid="`mall-offer-${offer.offerId}`"
              :data-online-payment="offer.onlinePayment"
              :aria-disabled="!offer.onlinePayment"
              @click="askOffer(offer)"
            >
              <view class="mall-product__offer-copy">
                <view class="mall-product__offer-heading">
                  <text class="mall-product__offer-name">{{ offer.merchantName }}</text>
                  <text class="mall-product__offer-kind">{{ MALL_MERCHANT_KIND_LABELS[offer.merchantKind] }}</text>
                </view>
                <text v-if="offer.deliveryNote" class="mall-product__offer-note">{{ offer.deliveryNote }}</text>
                <text
                  v-if="!offer.onlinePayment"
                  class="mall-product__offer-unpaid"
                  :data-testid="`mall-offer-unpaid-${offer.offerId}`"
                >暂未开通在线支付</text>
              </view>
              <text class="mall-product__offer-price mall-product__offer-price--ask">询价</text>
              <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
            </button>
          </template>
          <template v-else>
            <button
              v-for="offer in product.offers"
              :key="offer.offerId"
              :class="[
                'mall-product__offer',
                {
                  'mall-product__offer--selected': offer.offerId === selectedOfferId,
                  'mall-product__offer--unpaid': !offer.onlinePayment,
                },
              ]"
              :data-testid="`mall-offer-${offer.offerId}`"
              :data-online-payment="offer.onlinePayment"
              :aria-pressed="offer.offerId === selectedOfferId"
              :aria-disabled="!offer.onlinePayment"
              @click="selectOffer(offer)"
            >
              <view class="mall-product__radio">
                <LineIcon v-if="offer.offerId === selectedOfferId" name="check" :size="12" color="#ffffff" :stroke-width="3" />
              </view>
              <view class="mall-product__offer-copy">
                <view class="mall-product__offer-heading">
                  <text class="mall-product__offer-name">{{ offer.merchantName }}</text>
                  <text class="mall-product__offer-kind">{{ MALL_MERCHANT_KIND_LABELS[offer.merchantKind] }}</text>
                </view>
                <text v-if="offer.deliveryNote" class="mall-product__offer-note">{{ offer.deliveryNote }}</text>
                <text
                  v-if="!offer.onlinePayment"
                  class="mall-product__offer-unpaid"
                  :data-testid="`mall-offer-unpaid-${offer.offerId}`"
                >暂未开通在线支付</text>
              </view>
              <text class="mall-product__offer-price">{{ formatMallOfferPrice(offer.amountMinor, offer.unit) }}</text>
            </button>
          </template>
          <text v-if="product.saleMode === 'INQUIRY'" class="mall-product__offers-hint">在询价单里选发给哪几家，报价后再比选。</text>
          <text v-else-if="product.offers.length > 1" class="mall-product__offers-hint">排序：按价格从低到高。价格为商家当前报价。</text>
        </view>

        <button
          v-if="MALL_PREVIEW_ENABLED && product.saleMode === 'BUY' && selectedOffer && primaryAction?.cartAvailable"
          class="mall-product__card mall-product__chosen"
          data-testid="mall-product-chosen"
          @click="openBuySheet"
        >
          <view class="mall-product__chosen-row">
            <text class="mall-product__chosen-label">已选</text>
            <text class="mall-product__chosen-value">{{ selectedOffer.skuName }} · 1 件</text>
            <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
          </view>
        </button>

        <view
          v-if="MALL_PREVIEW_ENABLED && product.saleMode === 'INQUIRY' && shownOffer"
          class="mall-product__card mall-product__chosen"
          data-testid="mall-product-inquiry-rows"
        >
          <button class="mall-product__chosen-row mall-product__chosen-row--line" @click="runIntent('INQUIRE')">
            <text class="mall-product__chosen-label">配置</text>
            <text class="mall-product__chosen-value">{{ shownOffer.skuName }}</text>
            <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
          <button v-if="inquiryDelivery" class="mall-product__chosen-row" @click="runIntent('INQUIRE')">
            <text class="mall-product__chosen-label">交付</text>
            <text class="mall-product__chosen-value">{{ inquiryDelivery }}</text>
            <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
        </view>

        <TradePartiesCard
          v-if="shownOffer"
          class="mall-product__parties"
          :rows="parties"
          :note="partiesNote"
          test-id="mall-product-parties"
        />

        <view v-if="MALL_PREVIEW_ENABLED" class="mall-product__card" data-testid="mall-product-after-sales">
          <text class="mall-product__section-title">售后与保障</text>
          <view v-for="item in afterSales" :key="item.title" class="mall-product__after">
            <LineIcon name="check" :size="16" color="#159a70" :stroke-width="2.2" />
            <text class="mall-product__after-text">
              <text class="mall-product__after-title">{{ item.title }}</text>
              <text class="mall-product__after-copy">{{ item.text }}</text>
            </text>
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
              :aria-expanded="openFold === 'spec' ? 'true' : 'false'"
              @click="toggleFold('spec')"
            >
              <text class="mall-product__fold-title">参数</text>
              <text class="mall-product__fold-count">{{ product.specs.length }} 项</text>
              <LineIcon
                class="mall-product__fold-arrow"
                :name="openFold === 'spec' ? 'chevronUp' : 'chevronDown'"
                :size="16"
                color="#91a0b4"
                :stroke-width="2"
              />
            </button>
            <view v-if="openFold === 'spec'" class="mall-product__fold-body">
              <view class="mall-product__spec-table">
                <template v-for="spec in product.specs" :key="spec.label">
                  <text class="mall-product__spec-label">{{ spec.label }}</text>
                  <text class="mall-product__spec-value">{{ spec.value }}</text>
                </template>
              </view>
              <text class="mall-product__spec-note">{{ specNote }}</text>
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
              :aria-expanded="openFold === 'pack' ? 'true' : 'false'"
              @click="toggleFold('pack')"
            >
              <text class="mall-product__fold-title">{{ product.saleMode === 'RENT' ? '随箱清单' : '包装清单' }}</text>
              <text class="mall-product__fold-count">{{ packTotal }} 件</text>
              <LineIcon
                class="mall-product__fold-arrow"
                :name="openFold === 'pack' ? 'chevronUp' : 'chevronDown'"
                :size="16"
                color="#91a0b4"
                :stroke-width="2"
              />
            </button>
            <view v-if="openFold === 'pack'" class="mall-product__pack-list">
              <view v-for="item in product.packItems" :key="item.name" class="mall-product__pack-row">
                <text class="mall-product__pack-name">{{ item.name }}</text>
                <text class="mall-product__pack-quantity">×{{ item.quantity }}</text>
              </view>
            </view>
          </view>
        </view>

        <!-- 相关内容: after the parameters and the pack list, before the bar (PdFull, PdRental). -->
        <ContentRelatedBlock v-if="relatedRows.length" :rows="relatedRows" :city-code="pageCity" />
      </view>

      <view v-if="MALL_PREVIEW_ENABLED && (primaryAction || noPayableOffer)" class="mall-product__actions">
        <button
          v-if="SUPPORT_PREVIEW_ENABLED"
          class="mall-product__consult"
          data-testid="mall-product-consult"
          @click="consult"
        >
          <LineIcon name="chat" :size="22" color="#536178" />
          <text>咨询</text>
        </button>
        <!-- 收藏 next to 咨询, where board Product draws it (PdFull's 商家 slot; the merchant page is not built). -->
        <FavoriteButton
          v-if="FAVORITE_PREVIEW_ENABLED"
          type="PRODUCT"
          :target-id="product.productId"
          :city-code="pageCity"
          :return-url="buildMallProductUrl(product.productId, pageCity)"
          :width="44"
        />
        <button
          v-if="tryFirst"
          :class="['mall-product__try', { 'mall-product__try--wide': !(primaryAction?.cartAvailable && !noPayableOffer) }]"
          data-testid="mall-product-try-first"
          @click="showTryFirst"
        >
          先体验
        </button>
        <button
          v-if="noPayableOffer"
          class="mall-product__solo"
          data-testid="mall-product-no-payable"
          @click="explainNoPayable"
        >
          {{ product.saleMode === 'BUY' ? '立即购买' : '联系询价' }}
        </button>
        <view v-else-if="primaryAction && primaryAction.cartAvailable" class="mall-product__action-group">
          <button
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
        <button
          v-else-if="primaryAction"
          class="mall-product__solo"
          data-testid="mall-product-primary"
          :data-intent="primaryAction.intent"
          @click="runIntent(primaryAction.intent)"
        >
          {{ primaryAction.label }}
        </button>
      </view>

      <ShareSheet :target="shareTarget" @close="shareTarget = null" />

      <MallProductMoreSheet
        v-if="moreOpen"
        :title="product.name"
        :subtitle="moreSubtitle"
        :cover-url="cover?.url ?? null"
        @close="moreOpen = false"
        @home="moreHome"
        @feedback="moreFeedback"
        @report="moreReport"
      />

      <MallBuySheet
        v-if="buySheetOpen && selectedOffer"
        :offer="selectedOffer"
        :cover-url="cover?.url ?? null"
        :busy="props.busy"
        :load-delivery="props.loadDelivery"
        @close="buySheetOpen = false"
        @add-to-cart="addToCart"
        @buy-now="buyNow"
        @bulk-inquiry="bulkInquiry"
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
  box-shadow: var(--app-card-shadow);
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

.mall-product__floats {
  position: absolute;
  top: calc(var(--status-bar-height) + 4px);
  right: 8px;
  left: 8px;
  display: flex;
  align-items: center;
}

.mall-product__floats-gap {
  flex: 1;
}

.mall-product__float {
  position: relative;
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.mall-product__float-badge {
  position: absolute;
  top: 1px;
  left: 50%;
  min-width: 16px;
  height: 16px;
  margin-left: 5px;
  padding: 0 4px;
  border-radius: 8px;
  color: #ffffff;
  background: #ff5200;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  box-sizing: border-box;
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
  box-shadow: var(--app-card-shadow);
}

.mall-product__parties {
  margin: 0 16px;
}

/* The first card overlaps the picture. */
.mall-product__card--lead {
  position: relative;
  margin-top: -24px;
  gap: 6px;
}

.mall-product__price-unit {
  color: #e85400;
  font-size: 13px;
}

/* 已选 (and the 配置 / 交付 rows of an inquiry product): rows of 48px, label 40px wide. */
.mall-product__chosen {
  padding: 0 14px;
  gap: 0;
}

.mall-product__chosen-row {
  display: flex;
  width: 100%;
  min-height: 48px;
  align-items: center;
  gap: 12px;
}

.mall-product__chosen-row--line {
  border-bottom: 1px solid #f0f1f3;
}

.mall-product__chosen-label {
  width: 40px;
  flex: none;
  color: #627187;
  font-size: 13px;
}

.mall-product__chosen-value {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 租赁须知 (PdRental). */
.mall-product__card--terms {
  padding: 14px 14px 4px;
  gap: 0;
}

.mall-product__terms-title {
  margin-bottom: 8px;
}

.mall-product__term {
  display: flex;
  padding: 10px 0;
  align-items: flex-start;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.mall-product__term-icon {
  display: flex;
  width: 32px;
  height: 32px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff0e3;
}

.mall-product__term-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.mall-product__term-heading {
  display: flex;
  align-items: center;
  gap: 6px;
}

.mall-product__term-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  white-space: nowrap;
}

.mall-product__term-tag {
  padding: 0 6px;
  border-radius: 6px;
  color: #627187;
  background: #eef2f8;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.mall-product__term-text {
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.mall-product__card--steps {
  gap: 14px;
}

.mall-product__steps-hint {
  color: #627187;
  font-size: 12px;
  line-height: 18px;
}

/* 售后与保障: a green tick, the title in 500 and the words in grey. */
.mall-product__after {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 1.5;
}

.mall-product__after :deep(.line-icon) {
  margin-top: 2px;
}

.mall-product__after-title {
  font-weight: 500;
}

.mall-product__after-copy {
  margin-left: 4px;
  color: #536178;
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
  width: 100%;
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
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.mall-product__section-note--free {
  color: #11804a;
  white-space: nowrap;
}

/* 询价怎么走: four numbered steps joined by a line (PdInquiryFull board). */
.mall-product__steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.mall-product__steps--five {
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.mall-product__step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.mall-product__step-line {
  position: absolute;
  top: 11px;
  right: 0;
  left: 0;
  height: 2px;
  background: #ffd8b8;
}

.mall-product__step-line--first {
  left: 50%;
}

.mall-product__step-line--last {
  right: 50%;
}

.mall-product__step-no {
  position: relative;
  width: 24px;
  height: 24px;
  margin-bottom: 4px;
  border-radius: 50%;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  font-weight: 700;
  line-height: 24px;
  text-align: center;
}

.mall-product__step-title {
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.mall-product__step-no--wide {
  margin-bottom: 6px;
}

.mall-product__step-title--small {
  font-size: 12px;
  line-height: 17px;
}

.mall-product__step-note {
  color: #738096;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

.mall-product__offers-hint {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
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
  background: #fffaf5;
}

/* A merchant that cannot take payment online: shown, greyed, not chosen. */
.mall-product__offer--unpaid {
  opacity: 0.6;
}

.mall-product__offer-unpaid {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
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

.mall-product__offer-price--ask {
  color: #e85400;
  font-size: 15px;
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

.mall-product__fold-body {
  display: flex;
  padding-bottom: 14px;
  flex-direction: column;
  gap: 10px;
}

.mall-product__spec-note {
  color: #738096;
  font-size: 12px;
}

.mall-product__spec-table {
  display: grid;
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
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px calc(8px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

/* 咨询 on the left of the bar, as on the board PdFull. */
.mall-product__consult {
  display: flex;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #536178;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

/* 先体验: an orange outline pill before the buttons (PdFull: padding 14px next to the two-part pill;
   PdRental: 18px next to 联系询价). */
.mall-product__try {
  display: flex;
  height: 44px;
  padding: 0 14px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
  box-sizing: border-box;
}

.mall-product__try--wide {
  padding: 0 18px;
}

.mall-product__try-first {
  display: flex;
  flex-direction: column;
}

/* One orange pill: 联系询价 of a rental or inquiry product, or a product nobody can be paid for. */
.mall-product__solo {
  display: flex;
  height: 44px;
  min-width: 0;
  flex: 1;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  box-shadow: var(--app-primary-shadow);
}

/* One pill: the two buttons share its rounded ends. */
.mall-product__action-group {
  min-width: 0;
  flex: 1;
  display: flex;
  height: 44px;
  overflow: hidden;
  border-radius: 999px;
  box-shadow: var(--app-primary-shadow);
}

/* Sized by their words like board PdFull (flex 1 1 auto); 8px sides instead of 10 so 加入购物车 still
   fits at 375 wide next to 咨询, 收藏 and 先体验. */
.mall-product__secondary,
.mall-product__primary {
  display: flex;
  min-width: 0;
  padding: 0 8px;
  flex: 1 1 auto;
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
