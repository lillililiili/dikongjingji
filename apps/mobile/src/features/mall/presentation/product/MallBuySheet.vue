<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref } from 'vue'

import { CART_MAX_QUANTITY } from '../../domain/cart'
import type { MallOffer } from '../../domain/mall'
import { MALL_MERCHANT_KIND_LABELS, formatMallAmount } from '../shared/mall-format'

// 规格与数量 (prototype board PdSku). Every product has one spec for now, so the spec row only
// shows it; the delivery mode is chosen on the confirm page, where the address is too.
const props = defineProps<{
  offer: MallOffer
  coverUrl: string | null
  busy: boolean
}>()

const emit = defineEmits<{
  close: []
  addToCart: [quantity: number]
  buyNow: [quantity: number]
}>()

const quantity = ref(1)
const amountText = computed(() => formatMallAmount((props.offer.amountMinor ?? 0) * quantity.value))

function step(delta: number): void {
  quantity.value = Math.min(CART_MAX_QUANTITY, Math.max(1, quantity.value + delta))
}
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
          <UniIcons type="closeempty" size="20" color="#536178" />
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

        <view class="buy-sheet__quantity">
          <text class="buy-sheet__label">数量</text>
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

        <view v-if="offer.shippingNote" class="buy-sheet__ship">
          <UniIcons type="paperplane" size="14" color="#159a70" />
          <text>{{ offer.shippingNote }}</text>
        </view>

        <view class="buy-sheet__amount">
          <text class="buy-sheet__amount-label">商品金额</text>
          <text data-testid="mall-buy-amount">{{ amountText }}</text>
        </view>
        <text class="buy-sheet__note">配送方式和运费在确认订单时选择和计算。</text>
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
          @click="emit('buyNow', quantity)"
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

.buy-sheet__quantity {
  display: flex;
  align-items: center;
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
  justify-content: space-between;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 13px;
}

.buy-sheet__amount-label {
  color: #627187;
}

.buy-sheet__note {
  margin-top: -8px;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.buy-sheet__actions {
  display: flex;
  padding: 12px 16px 0;
  gap: 10px;
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
  border: 1.5px solid #ff6a00;
  color: #ba470b;
  background: #ffffff;
  line-height: 43px;
}

.buy-sheet__buy {
  flex: 1;
  color: #ffffff;
  background: #ff6a00;
}
</style>
