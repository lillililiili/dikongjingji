<script setup lang="ts">
import { SETTINGS_PREVIEW_ENABLED } from '@/features/settings/application/settings-preview'
import { buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import { formatAddressLine, maskPhone } from '@/features/account/application/address-form'
import {
  classifySubmitFailure,
  isQuoteUnavailableCode,
  quoteUnavailableMessage,
} from '@/features/trade/application/submit-order'
import { createSubmitKeyHolder } from '@/features/trade/application/trade-keys'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import { tradeApi } from '@/features/trade/data/trade-api-client'
import type { SubmitOrderInput } from '@/features/trade/domain/trade-order'
import { ApiRequestError } from '@/shared/http/public-api-request'

import { resolveMallIntent, type MallIntent } from '../../application/mall-intent'
import { MALL_PREVIEW_ENABLED } from '../../application/mall-preview'
import { cartApi } from '../../data/cart-api-client'
import { resolveMallPreviewCover } from '../../data/preview/mall-preview-covers'
import type { CheckoutLine, CheckoutPreview, CheckoutSource, DeliveryMode } from '../../domain/cart'
import { MALL_MERCHANT_KIND_LABELS, formatMallAmount } from '../shared/mall-format'

const props = defineProps<{
  source: CheckoutSource | null
  cityCode: string
  // The address picked on the address page; null means the default one.
  addressId: string | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  chooseAddress: []
  backToCart: []
  // Quote mode: the quote can no longer be ordered, back to its quotes page.
  backToQuotes: []
  // The picked address is gone (deleted on the address page): fall back to the default one.
  addressGone: []
  // The order was made; the host replaces this page with the cashier.
  submitted: [orderId: string]
}>()

type CheckoutState =
  | { kind: 'loading' }
  | { kind: 'ready'; preview: CheckoutPreview }
  // The goods changed since the cart was read: another merchant, gone, or not sold here any more.
  | { kind: 'stale'; message: string }
  | { kind: 'missing' }
  | { kind: 'error' }

const NOTE_MAX_LENGTH = 50

const state = ref<CheckoutState>({ kind: 'loading' })
const deliveryMode = ref<DeliveryMode | null>(null)
const note = ref('')
const agreed = ref(false)
const agreeTip = ref(false)
const submitting = ref(false)
// Learned from the server on submit: this merchant has no payment account, so nothing can be paid.
const paymentUnavailable = ref(false)
const submitKeys = createSubmitKeyHolder()
let requestVersion = 0

const preview = computed(() => (state.value.kind === 'ready' ? state.value.preview : null))
// Ordering a chosen inquiry quote (F4): one line whose quantity and price are the quote's.
const quoteMode = computed(() => props.source?.kind === 'QUOTE')
const addressNeeded = computed(() => preview.value?.blockers.includes('ADDRESS_REQUIRED') ?? false)
const pickupNote = computed(() =>
  preview.value?.deliveryOptions.find((option) => option.mode === 'PICKUP')?.note ?? null)
const expressNote = computed(() =>
  preview.value?.deliveryOptions.find((option) => option.mode === 'EXPRESS')?.note ?? null)

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

function coverOf(line: CheckoutLine): string | null {
  return line.coverImage?.url ?? resolveMallPreviewCover(line.productId)?.url ?? null
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.source || !props.cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await cartApi.preview(token, {
      cityCode: props.cityCode,
      source: props.source,
      deliveryMode: deliveryMode.value,
      addressId: props.addressId,
    })
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', preview: loaded }
    deliveryMode.value = loaded.deliveryMode
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    if (error instanceof ApiRequestError && isQuoteUnavailableCode(error.code)) {
      state.value = { kind: 'stale', message: quoteUnavailableMessage(error.code) }
      return
    }
    if (error instanceof ApiRequestError && error.status === 409) {
      state.value = { kind: 'stale', message: error.message }
      return
    }
    if (error instanceof ApiRequestError && error.status === 404 && props.addressId) {
      emit('addressGone')
      return
    }
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function chooseDelivery(mode: DeliveryMode): void {
  if (mode === deliveryMode.value) return
  deliveryMode.value = mode
  void load()
}

function valueOf(event: Event): string {
  return (event as Event & { detail?: { value?: string } }).detail?.value?.toString() ?? ''
}

function runIntent(intent: MallIntent): void {
  // The agreement page exists since step G1 (preview builds); its text is still a placeholder.
  if (intent.type === 'READ_AGREEMENT' && SETTINGS_PREVIEW_ENABLED) {
    uni.navigateTo({ url: buildAgreementUrl(intent.document) })
    return
  }
  const resolution = resolveMallIntent(intent, MALL_PREVIEW_ENABLED)
  if (resolution.action === 'SHOW_NOTICE') {
    uni.showToast({ title: resolution.message, icon: 'none' })
  }
}

function copySource(source: CheckoutSource): SubmitOrderInput['source'] {
  switch (source.kind) {
    case 'CART': return { kind: 'CART', cartItemIds: [...source.cartItemIds] }
    case 'QUOTE': return { kind: 'QUOTE', inquiryQuoteId: source.inquiryQuoteId }
    case 'OFFER': return { kind: 'OFFER', offerId: source.offerId, quantity: source.quantity }
  }
}

function leave(): void {
  if (quoteMode.value) emit('backToQuotes')
  else emit('backToCart')
}

function submitInput(current: CheckoutPreview, mode: DeliveryMode, source: CheckoutSource): SubmitOrderInput {
  return {
    cityCode: props.cityCode,
    source: copySource(source),
    deliveryMode: mode,
    // The address the page shows: the picked one, else the default the preview chose.
    addressId: mode === 'EXPRESS' ? current.address?.addressId ?? null : null,
    buyerNote: note.value,
    // What the buyer saw. The server works the amount out again and refuses if it differs.
    expectedPayableMinor: current.amounts.payableMinor,
  }
}

// The button checks what the page can check, then the server makes the order (trade module).
// Public builds never show this page; there the gate keeps the old notice-free behaviour.
async function submit(): Promise<void> {
  if (!agreed.value) {
    agreeTip.value = true
    return
  }
  if (addressNeeded.value) {
    uni.showToast({ title: '请先添加收货地址', icon: 'none' })
    return
  }
  if (!TRADE_PREVIEW_ENABLED) {
    runIntent({ type: 'SUBMIT_ORDER' })
    return
  }
  const token = props.accessToken
  const current = preview.value
  const mode = deliveryMode.value
  if (!token || !current || !mode || !props.source || submitting.value || paymentUnavailable.value) return
  const input = submitInput(current, mode, props.source)
  submitting.value = true
  try {
    const order = await tradeApi.submit(token, input, submitKeys.keyFor(input))
    // Stays "submitting" while the host replaces the page, so a second tap cannot slip in.
    emit('submitted', order.orderId)
  } catch (error) {
    submitting.value = false
    const failure = classifySubmitFailure(error)
    switch (failure.kind) {
      case 'UNAUTHORIZED':
        emit('login')
        break
      case 'MERCHANT_PAYMENT_UNAVAILABLE':
        paymentUnavailable.value = true
        break
      case 'GOODS_CHANGED':
      case 'QUOTE_UNAVAILABLE':
        state.value = { kind: 'stale', message: failure.message }
        break
      case 'AMOUNT_CHANGED':
      case 'DELIVERY_CHANGED':
        uni.showToast({ title: failure.message, icon: 'none' })
        void load()
        break
      default:
        uni.showToast({ title: failure.message, icon: 'none' })
    }
  }
}

watch(
  () => [props.refreshKey, props.accessToken, props.addressId],
  () => { void load() },
  { immediate: true },
)
</script>

<template>
  <view class="checkout" data-testid="checkout">
    <header class="checkout__header">
      <button class="checkout__back" data-testid="checkout-back" aria-label="返回" @click="emit('back')">
        <UniIcons type="left" size="24" color="#202c42" />
      </button>
      <text class="checkout__title">确认订单</text>
    </header>

    <view v-if="state.kind === 'loading'" class="checkout__body" data-testid="checkout-loading" aria-label="正在加载">
      <view class="checkout__skeleton" />
      <view class="checkout__skeleton checkout__skeleton--tall" />
    </view>

    <view v-else-if="state.kind === 'stale'" class="checkout__state" data-testid="checkout-stale">
      <text class="checkout__state-title">{{ quoteMode ? '这份报价不能下单' : '商品有变化' }}</text>
      <text class="checkout__state-text">{{ state.message }}</text>
      <button
        class="checkout__state-button"
        :data-testid="quoteMode ? 'checkout-back-to-quotes' : 'checkout-back-to-cart'"
        @click="leave"
      >
        {{ quoteMode ? '返回报价比选' : '返回购物车' }}
      </button>
    </view>

    <view v-else-if="state.kind === 'missing'" class="checkout__state" data-testid="checkout-missing">
      <text class="checkout__state-title">{{ quoteMode ? '没有找到这份报价' : '没有找到要结算的商品' }}</text>
      <button class="checkout__state-button" @click="leave">{{ quoteMode ? '返回报价比选' : '返回购物车' }}</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="checkout__state" data-testid="checkout-error">
      <text class="checkout__state-title">加载失败</text>
      <text class="checkout__state-text">请检查网络后重试</text>
      <button class="checkout__state-button" data-testid="checkout-retry" @click="load">重试</button>
    </view>

    <view v-else-if="preview" class="checkout__body">
      <button
        v-if="deliveryMode === 'EXPRESS' && preview.address"
        class="checkout__address"
        data-testid="checkout-address"
        :aria-label="`更换收货地址：${preview.address.recipientName}`"
        @click="emit('chooseAddress')"
      >
        <view class="checkout__address-icon"><UniIcons type="location" size="20" color="#ff6a00" /></view>
        <view class="checkout__address-text">
          <text class="checkout__address-name">
            {{ preview.address.recipientName }} {{ maskPhone(preview.address.phone) }}
          </text>
          <text class="checkout__address-line">{{ formatAddressLine(preview.address) }}</text>
        </view>
        <UniIcons type="right" size="16" color="#91a0b4" />
      </button>
      <button
        v-else-if="deliveryMode === 'EXPRESS'"
        class="checkout__address checkout__address--empty"
        data-testid="checkout-add-address"
        @click="emit('chooseAddress')"
      >
        <view class="checkout__address-icon"><UniIcons type="plusempty" size="20" color="#ff6a00" /></view>
        <view class="checkout__address-text">
          <text class="checkout__address-name">添加收货地址</text>
          <text class="checkout__address-line">还没有收货地址，添加后才能提交订单</text>
        </view>
        <UniIcons type="right" size="16" color="#91a0b4" />
      </button>

      <view class="checkout__card">
        <view class="checkout__merchant">
          <UniIcons type="shop" size="18" color="#ff6a00" />
          <text class="checkout__merchant-name" data-testid="checkout-merchant">{{ preview.merchant.name }}</text>
          <text class="checkout__merchant-kind">{{ MALL_MERCHANT_KIND_LABELS[preview.merchant.kind] }}</text>
          <text v-if="preview.lines.length > 1" class="checkout__pieces">共 {{ preview.pieceCount }} 件</text>
        </view>
        <view
          v-for="line in preview.lines"
          :key="line.offerId"
          class="checkout__line"
          :data-testid="`checkout-line-${line.offerId}`"
        >
          <image v-if="coverOf(line)" class="checkout__cover" :src="coverOf(line) ?? ''" mode="aspectFill" />
          <view v-else class="checkout__cover" />
          <view class="checkout__line-info">
            <text class="checkout__line-name">{{ line.productName }}</text>
            <text class="checkout__line-spec">{{ line.skuName }}</text>
            <view class="checkout__line-price">
              <text class="checkout__line-amount">{{ formatMallAmount(line.unitAmountMinor) }}</text>
              <text class="checkout__line-qty">× {{ line.quantity }}</text>
            </view>
          </view>
        </view>
        <view v-if="quoteMode" class="checkout__quote-note" data-testid="checkout-quote-note">
          <UniIcons type="info" size="14" color="#ba470b" />
          <text>按所选报价下单，数量和单价不能修改</text>
        </view>

        <view class="checkout__row checkout__row--delivery">
          <view class="checkout__delivery">
            <text class="checkout__label">配送方式</text>
            <view class="checkout__modes" role="radiogroup" aria-label="配送方式">
              <button
                v-for="option in preview.deliveryOptions"
                :key="option.mode"
                :class="['checkout__mode', { 'checkout__mode--on': option.mode === deliveryMode }]"
                :data-testid="`checkout-mode-${option.mode.toLowerCase()}`"
                role="radio"
                :aria-checked="option.mode === deliveryMode"
                @click="chooseDelivery(option.mode)"
              >
                {{ option.mode === 'EXPRESS' ? '快递配送' : '到店自提' }}
              </button>
            </view>
          </view>
          <view v-if="deliveryMode === 'EXPRESS' && expressNote" class="checkout__mode-note checkout__mode-note--express">
            <UniIcons type="paperplane" size="14" color="#159a70" />
            <text>{{ expressNote }}</text>
          </view>
          <view v-if="deliveryMode === 'PICKUP' && pickupNote" class="checkout__mode-note" data-testid="checkout-pickup">
            <UniIcons type="location" size="14" color="#ff6a00" />
            <text>自提点 {{ pickupNote }}</text>
          </view>
          <text v-if="!deliveryMode" class="checkout__mode-note checkout__mode-note--warn" data-testid="checkout-no-delivery">
            这几样商品没有共同的配送方式，请回到购物车分开结算
          </text>
        </view>

        <view class="checkout__row">
          <text class="checkout__label">留言</text>
          <input
            class="checkout__note"
            data-testid="checkout-note"
            type="text"
            placeholder="选填，给商家留言"
            :maxlength="NOTE_MAX_LENGTH"
            :value="note"
            @input="note = valueOf($event)"
          />
        </view>
      </view>

      <view class="checkout__parties">
        <view class="checkout__parties-head">
          <UniIcons type="auth" size="18" color="#159a70" />
          <text class="checkout__parties-title">交易主体</text>
        </view>
        <view class="checkout__parties-grid">
          <text class="checkout__parties-label">销售方</text>
          <text>{{ preview.merchant.name }}（{{ MALL_MERCHANT_KIND_LABELS[preview.merchant.kind] }}）</text>
          <template v-if="preview.fulfilledFrom">
            <text class="checkout__parties-label">发货履约</text>
            <text>{{ preview.fulfilledFrom }}</text>
          </template>
        </view>
      </view>

      <view class="checkout__card">
        <view class="checkout__amount-row">
          <text class="checkout__amount-label">商品金额</text>
          <text data-testid="checkout-goods">{{ formatMallAmount(preview.amounts.goodsMinor) }}</text>
        </view>
        <view class="checkout__amount-row checkout__amount-row--line">
          <text class="checkout__amount-label">运费</text>
          <text data-testid="checkout-shipping">{{ formatMallAmount(preview.amounts.shippingMinor) }}</text>
        </view>
        <view class="checkout__amount-row checkout__amount-row--line checkout__amount-row--total">
          <text>应付</text>
          <text class="checkout__payable">{{ formatMallAmount(preview.amounts.payableMinor) }}</text>
        </view>
      </view>

      <view class="checkout__agree">
        <button
          class="checkout__agree-toggle"
          data-testid="checkout-agree"
          role="checkbox"
          :aria-checked="agreed"
          aria-label="同意交易协议"
          @click="agreed = !agreed; agreeTip = false"
        >
          <view :class="['checkout__box', { 'checkout__box--on': agreed }]">
            <UniIcons v-if="agreed" type="checkmarkempty" size="11" color="#ffffff" />
          </view>
        </button>
        <text class="checkout__agree-text">
          <text @click="agreed = !agreed; agreeTip = false">已阅读并同意</text>
          <text
            class="checkout__link"
            data-testid="checkout-agreement"
            @click="runIntent({ type: 'READ_AGREEMENT', document: 'TRADE_AGREEMENT' })"
          >
            《交易协议》
          </text>
        </text>
      </view>
    </view>

    <view v-if="preview" class="checkout__bar">
      <text v-if="paymentUnavailable" class="checkout__tip" role="status" data-testid="checkout-payment-unavailable">
        该商家暂未开通在线支付，暂时不能下单
      </text>
      <text v-else-if="agreeTip && !agreed" class="checkout__tip" role="status" data-testid="checkout-agree-tip">
        请先阅读并同意《交易协议》
      </text>
      <view class="checkout__bar-row">
        <text class="checkout__bar-sum">
          应付<text class="checkout__bar-value" data-testid="checkout-payable">{{ formatMallAmount(preview.amounts.payableMinor) }}</text>
        </text>
        <button
          :class="['checkout__submit', { 'checkout__submit--off': !deliveryMode || paymentUnavailable }]"
          data-testid="checkout-submit"
          :disabled="!deliveryMode || paymentUnavailable || submitting"
          @click="submit"
        >
          {{ submitting ? '正在提交…' : '提交订单' }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype boards Checkout and CheckoutStates (390 wide). */
.checkout {
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
}

button::after {
  border: 0;
}

.checkout__header {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  min-height: calc(60px + var(--status-bar-height));
  padding: var(--status-bar-height) 8px 0;
  align-items: center;
  gap: 4px;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
  box-sizing: border-box;
}

.checkout__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.checkout__title {
  font-size: 18px;
  font-weight: 700;
}

.checkout__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 16px;
}

.checkout__quote-note {
  display: flex;
  margin: -4px 0 12px;
  padding: 8px 10px;
  align-items: center;
  gap: 6px;
  border-radius: 8px;
  color: #ba470b;
  background: #fff6ee;
  font-size: 12px;
  line-height: 17px;
}

.checkout__skeleton {
  height: 72px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.checkout__skeleton--tall {
  height: 260px;
}

.checkout__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.checkout__state-title {
  font-size: 16px;
  font-weight: 700;
}

.checkout__state-text {
  color: #738096;
  font-size: 13px;
}

.checkout__state-button {
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

.checkout__address {
  display: flex;
  width: 100%;
  padding: 14px;
  align-items: center;
  gap: 12px;
  border-radius: 14px;
  color: #202c42;
  background: #ffffff;
  text-align: left;
  box-sizing: border-box;
}

.checkout__address--empty {
  border: 1.5px dashed #ffd8b8;
}

.checkout__address-icon {
  display: flex;
  width: 40px;
  height: 40px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff0e3;
}

.checkout__address-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.checkout__address-name {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.checkout__address-line {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.checkout__card {
  display: flex;
  flex-direction: column;
  padding: 0 14px;
  border-radius: 14px;
  background: #ffffff;
}

.checkout__merchant {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 6px;
}

.checkout__merchant-name {
  min-width: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.checkout__merchant-kind {
  flex: none;
  padding: 0 5px;
  border: 1px solid #e5ebf3;
  border-radius: 4px;
  color: #536178;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.checkout__pieces {
  margin-left: auto;
  color: #627187;
  font-size: 13px;
  white-space: nowrap;
}

.checkout__line {
  display: flex;
  padding-bottom: 12px;
  gap: 10px;
}

.checkout__cover {
  display: block;
  width: 80px;
  height: 80px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.checkout__line-info {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.checkout__line-name {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.checkout__line-spec {
  padding: 2px 6px;
  border-radius: 6px;
  color: #536178;
  background: #eef2f8;
  font-size: 12px;
  line-height: 17px;
}

.checkout__line-price {
  display: flex;
  margin-top: auto;
  align-items: baseline;
  align-self: stretch;
}

.checkout__line-amount {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.checkout__line-qty {
  margin-left: auto;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.checkout__row {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 12px;
  border-top: 1px solid #f0f1f3;
}

.checkout__row--delivery {
  padding: 10px 0;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}

.checkout__delivery {
  display: flex;
  align-items: center;
  gap: 12px;
}

.checkout__label {
  width: 64px;
  flex: none;
  color: #627187;
  font-size: 14px;
  line-height: 20px;
}

.checkout__modes {
  display: grid;
  flex: 1;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.checkout__mode {
  height: 40px;
  border: 1.5px solid #e5ebf3;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 14px;
  line-height: 37px;
  white-space: nowrap;
  box-sizing: border-box;
}

.checkout__mode--on {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fffaf5;
  font-weight: 500;
}

.checkout__mode-note {
  display: flex;
  padding-left: 76px;
  align-items: center;
  gap: 4px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.checkout__mode-note--express {
  color: #11804a;
}

.checkout__mode-note--warn {
  color: #c0392b;
}

.checkout__note {
  min-width: 0;
  height: 44px;
  flex: 1;
  color: #202c42;
  font-size: 14px;
}

.checkout__parties {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border: 1px dashed #c9d3e1;
  border-radius: 14px;
}

.checkout__parties-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.checkout__parties-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.checkout__parties-grid {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 6px 10px;
  font-size: 13px;
  line-height: 18px;
}

.checkout__parties-label {
  color: #627187;
}

.checkout__amount-row {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  line-height: 20px;
}

.checkout__amount-row--line {
  border-top: 1px solid #f0f1f3;
}

.checkout__amount-row--total {
  min-height: 48px;
  font-size: 15px;
  font-weight: 500;
}

.checkout__amount-label {
  color: #536178;
}

.checkout__payable {
  color: #e85400;
  font-size: 18px;
  font-weight: 700;
}

.checkout__agree {
  display: flex;
  min-height: 44px;
  padding: 0 2px;
  align-items: center;
  gap: 4px;
}

.checkout__agree-toggle {
  display: flex;
  width: 30px;
  height: 30px;
  flex: none;
  margin-left: -6px;
  align-items: center;
  justify-content: center;
}

.checkout__box {
  display: flex;
  width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #91a0b4;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.checkout__box--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.checkout__agree-text {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.checkout__link {
  color: #ba470b;
}

.checkout__bar {
  position: sticky;
  bottom: 0;
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 8px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.checkout__tip {
  color: #d64f67;
  font-size: 12px;
  line-height: 17px;
}

.checkout__bar-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.checkout__bar-sum {
  display: flex;
  align-items: baseline;
  gap: 4px;
  color: #536178;
  font-size: 13px;
  white-space: nowrap;
}

.checkout__bar-value {
  color: #e85400;
  font-size: 22px;
  font-weight: 700;
}

.checkout__submit {
  width: 160px;
  height: 46px;
  flex: none;
  margin-left: auto;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
}

.checkout__submit--off,
.checkout__submit[disabled] {
  color: #ffffff;
  background: #c9d3e1;
}
</style>
