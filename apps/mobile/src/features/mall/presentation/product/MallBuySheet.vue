<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import { CART_MAX_QUANTITY, type DeliveryMode } from '../../domain/cart'
import type { MallOffer } from '../../domain/mall'
import { MALL_MERCHANT_KIND_LABELS, formatMallAmount } from '../shared/mall-format'
import type { MallBuyDelivery, MallBuyDeliveryLoader } from './buy-delivery'

// 规格与数量 (prototype board PdSku). Every product has one spec for now, so the spec row only shows
// it. The delivery cards and the 运费 line come from the confirm page's preview of this offer
// (the host reads it for a signed-in customer); a guest sees the merchant's shipping note instead,
// and the confirm page asks again either way.

const props = withDefaults(
  defineProps<{
    offer: MallOffer
    coverUrl: string | null
    busy: boolean
    loadDelivery?: MallBuyDeliveryLoader | null
  }>(),
  { loadDelivery: null },
)

const emit = defineEmits<{
  close: []
  addToCart: [quantity: number]
  buyNow: [quantity: number, delivery: DeliveryMode | null]
  // 10 件以上: ask the merchant about a bulk purchase.
  bulkInquiry: [quantity: number]
}>()

// The board suggests an enterprise inquiry from 10 pieces on.
const BULK_QUANTITY = 10
const DELIVERY_LABELS: Record<DeliveryMode, string> = { EXPRESS: '快递配送', PICKUP: '到店自提' }

const quantity = ref(1)
const delivery = ref<MallBuyDelivery | null>(null)
const chosenMode = ref<DeliveryMode | null>(null)
let deliveryVersion = 0
let deliveryTimer: ReturnType<typeof setTimeout> | null = null

const goodsMinor = computed(() => (props.offer.amountMinor ?? 0) * quantity.value)
const amountText = computed(() => formatMallAmount(goodsMinor.value + (delivery.value?.shippingMinor ?? 0)))

async function refreshDelivery(): Promise<void> {
  if (!props.loadDelivery) return
  const requested = ++deliveryVersion
  const loaded = await props.loadDelivery(props.offer.offerId, quantity.value, chosenMode.value).catch(() => null)
  if (requested !== deliveryVersion) return
  delivery.value = loaded
  if (loaded) chosenMode.value = loaded.mode
}

function scheduleDelivery(): void {
  if (deliveryTimer) clearTimeout(deliveryTimer)
  deliveryTimer = setTimeout(() => void refreshDelivery(), 300)
}

function step(delta: number): void {
  quantity.value = Math.min(CART_MAX_QUANTITY, Math.max(1, quantity.value + delta))
}

function chooseMode(mode: DeliveryMode): void {
  if (mode === chosenMode.value) return
  chosenMode.value = mode
  void refreshDelivery()
}

watch(quantity, scheduleDelivery)
onMounted(() => void refreshDelivery())
</script>

<template>
  <view class="buy-sheet" data-testid="mall-buy-sheet" @click.self="emit('close')">
    <view class="buy-sheet__panel" role="dialog" aria-label="规格与数量">
      <view class="buy-sheet__head">
        <image v-if="coverUrl" class="buy-sheet__cover" :src="coverUrl" mode="aspectFill" />
        <view v-else class="buy-sheet__cover" />
        <view class="buy-sheet__price-block">
          <text class="buy-sheet__price">{{ formatMallAmount(offer.amountMinor ?? 0) }}</text>
          <text class="buy-sheet__merchant">
            {{ offer.merchantName }}（{{ MALL_MERCHANT_KIND_LABELS[offer.merchantKind] }}）
          </text>
        </view>
        <button class="buy-sheet__close" aria-label="关闭" data-testid="mall-buy-close" @click="emit('close')">
          <LineIcon name="close" :size="20" color="#536178" :stroke-width="2" />
        </button>
      </view>

      <view class="buy-sheet__body">
        <view class="buy-sheet__group">
          <text class="buy-sheet__label">规格</text>
          <view class="buy-sheet__spec" aria-pressed="true">
            <text class="buy-sheet__spec-name">{{ offer.skuName }}</text>
            <text>{{ formatMallAmount(offer.amountMinor ?? 0) }}</text>
          </view>
        </view>

        <view class="buy-sheet__quantity-block">
          <view class="buy-sheet__quantity">
            <text class="buy-sheet__label">数量</text>
            <text class="buy-sheet__bulk-hint">{{ BULK_QUANTITY }} 件以上建议走企业采购询价</text>
            <view class="buy-sheet__stepper">
              <button
                :class="['buy-sheet__step', { 'buy-sheet__step--off': quantity <= 1 }]"
                data-testid="mall-buy-minus"
                aria-label="减少数量"
                :aria-disabled="quantity <= 1"
                @click="step(-1)"
              >
                −
              </button>
              <text class="buy-sheet__qty" data-testid="mall-buy-qty">{{ quantity }}</text>
              <button
                :class="['buy-sheet__step', { 'buy-sheet__step--off': quantity >= CART_MAX_QUANTITY }]"
                data-testid="mall-buy-plus"
                aria-label="增加数量"
                :aria-disabled="quantity >= CART_MAX_QUANTITY"
                @click="step(1)"
              >
                +
              </button>
            </view>
          </view>
          <button
            v-if="quantity >= BULK_QUANTITY"
            class="buy-sheet__bulk"
            data-testid="mall-buy-bulk"
            @click="emit('bulkInquiry', quantity)"
          >
            按 {{ quantity }} 件询价 ›
          </button>
        </view>

        <view v-if="delivery && delivery.options.length" class="buy-sheet__group" data-testid="mall-buy-delivery">
          <text class="buy-sheet__label">配送方式</text>
          <view class="buy-sheet__modes" role="radiogroup" aria-label="配送方式">
            <button
              v-for="option in delivery.options"
              :key="option.mode"
              :class="['buy-sheet__mode', { 'buy-sheet__mode--on': option.mode === chosenMode }]"
              role="radio"
              :aria-checked="option.mode === chosenMode"
              :data-testid="`mall-buy-mode-${option.mode.toLowerCase()}`"
              @click="chooseMode(option.mode)"
            >
              <text class="buy-sheet__mode-title">{{ DELIVERY_LABELS[option.mode] }}</text>
              <text v-if="option.note" class="buy-sheet__mode-note">{{ option.note }}</text>
            </button>
          </view>
        </view>
        <view v-else-if="offer.shippingNote" class="buy-sheet__ship">
          <LineIcon name="truck" :size="14" color="#159a70" :stroke-width="2" />
          <text>{{ offer.shippingNote }}</text>
        </view>

        <view class="buy-sheet__amount">
          <view class="buy-sheet__amount-row">
            <text class="buy-sheet__amount-label">商品金额</text>
            <text data-testid="mall-buy-amount">{{ formatMallAmount(goodsMinor) }}</text>
          </view>
          <view v-if="delivery" class="buy-sheet__amount-row">
            <text class="buy-sheet__amount-label">运费</text>
            <text data-testid="mall-buy-shipping">{{ formatMallAmount(delivery.shippingMinor) }}</text>
          </view>
        </view>
        <text class="buy-sheet__note">换商家会重新计算价格、库存和配送方式；已选规格不存在时提示重新选择。</text>
      </view>

      <view class="buy-sheet__actions">
        <button
          class="buy-sheet__cart"
          data-testid="mall-buy-add"
          :disabled="busy"
          @click="emit('addToCart', quantity)"
        >
          加入购物车
        </button>
        <button
          class="buy-sheet__buy"
          data-testid="mall-buy-now"
          :disabled="busy"
          @click="emit('buyNow', quantity, chosenMode)"
        >
          去确认订单 {{ amountText }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.buy-sheet {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.45);
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

.buy-sheet__panel {
  display: flex;
  width: 100%;
  flex-direction: column;
  padding-bottom: calc(16px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #ffffff;
}

.buy-sheet__head {
  display: flex;
  padding: 16px 16px 12px;
  gap: 12px;
}

.buy-sheet__cover {
  width: 88px;
  height: 88px;
  flex: none;
  margin-top: -36px;
  border: 3px solid #ffffff;
  border-radius: 10px;
  background: #eef2f8;
}

.buy-sheet__price-block {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.buy-sheet__price {
  color: #e85400;
  font-size: 22px;
  font-weight: 700;
}

.buy-sheet__merchant {
  color: #627187;
  font-size: 12px;
}

.buy-sheet__close {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  margin: -8px -8px 0 0;
  align-items: center;
  justify-content: center;
}

.buy-sheet__body {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 0 16px;
}

.buy-sheet__group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.buy-sheet__label {
  font-size: 15px;
  font-weight: 500;
}

.buy-sheet__spec {
  display: flex;
  min-height: 44px;
  padding: 0 12px;
  align-items: center;
  gap: 8px;
  border: 1.5px solid #ff6a00;
  border-radius: 8px;
  color: #ba470b;
  background: #fffaf5;
  font-size: 13px;
  box-sizing: border-box;
}

.buy-sheet__spec-name {
  flex: 1;
  font-size: 14px;
}

.buy-sheet__quantity-block {
  display: flex;
  flex-direction: column;
}

.buy-sheet__quantity {
  display: flex;
  align-items: center;
}

.buy-sheet__bulk-hint {
  min-width: 0;
  margin-left: 8px;
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.buy-sheet__bulk {
  align-self: flex-start;
  margin: -2px 0 -10px;
  padding: 10px 0;
  color: #1d63c4;
  font-size: 13px;
  line-height: 16px;
}

.buy-sheet__modes {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.buy-sheet__mode {
  display: flex;
  min-width: 0;
  padding: 10px 12px;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  border: 1.5px solid #e5ebf3;
  border-radius: 8px;
  text-align: left;
  box-sizing: border-box;
}

.buy-sheet__mode--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.buy-sheet__mode-title {
  color: #202c42;
  font-size: 14px;
}

.buy-sheet__mode--on .buy-sheet__mode-title {
  color: #ba470b;
  font-weight: 500;
}

.buy-sheet__mode-note {
  max-width: 100%;
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.buy-sheet__stepper {
  display: flex;
  margin-left: auto;
  align-items: center;
  border: 1px solid #e5ebf3;
  border-radius: 8px;
}

.buy-sheet__step {
  width: 44px;
  height: 34px;
  color: #202c42;
  font-size: 18px;
  line-height: 34px;
  text-align: center;
}

.buy-sheet__step--off {
  color: #c9d3e1;
}

.buy-sheet__qty {
  width: 40px;
  border-right: 1px solid #e5ebf3;
  border-left: 1px solid #e5ebf3;
  font-size: 15px;
  line-height: 34px;
  text-align: center;
}

.buy-sheet__ship {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #11804a;
  font-size: 13px;
}

.buy-sheet__amount {
  display: flex;
  padding: 12px;
  flex-direction: column;
  gap: 6px;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 13px;
}

.buy-sheet__amount-row {
  display: flex;
  justify-content: space-between;
}

.buy-sheet__amount-label {
  color: #627187;
}

.buy-sheet__note {
  color: #627187;
  font-size: 12px;
  line-height: 1.6;
}

.buy-sheet__actions {
  display: flex;
  margin-top: 18px;
  padding: 10px 16px 0;
  gap: 10px;
  border-top: 1px solid #e5ebf3;
}

.buy-sheet__cart,
.buy-sheet__buy {
  height: 46px;
  border-radius: 999px;
  font-size: 15px;
  font-weight: 500;
  line-height: 46px;
  white-space: nowrap;
  box-sizing: border-box;
}

.buy-sheet__cart {
  width: 120px;
  flex: none;
  border: 1px solid #ffb27a;
  color: #ba470b;
  background: #fff0e3;
  font-weight: 400;
  line-height: 44px;
}

.buy-sheet__buy {
  flex: 1;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
}
</style>
