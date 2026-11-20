<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  CART_INVALID_REASON_TEXT,
  EMPTY_CART_SELECTION,
  allCartItemIds,
  isWholeMerchantChecked,
  reconcileCartSelection,
  selectCartMerchant,
  summarizeCartSelection,
  toggleCartItem,
  toggleWholeMerchant,
  type CartSelection,
} from '../../application/cart-selection'
import { cartApi } from '../../data/cart-api-client'
import { resolveMallPreviewCover } from '../../data/preview/mall-preview-covers'
import { CART_MAX_QUANTITY, type Cart, type CartItem } from '../../domain/cart'
import { MALL_MERCHANT_KIND_LABELS, formatMallAmount } from '../shared/mall-format'

const props = defineProps<{
  // Null for a guest. The page host reads it again every time the tab is shown.
  accessToken: string | null
  cityCode: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  login: []
  openProduct: [productId: string]
  checkout: [cartItemIds: string[]]
  browse: []
  countChange: [count: number]
}>()

type CartState =
  | { kind: 'guest' }
  | { kind: 'loading' }
  | { kind: 'ready'; cart: Cart }
  | { kind: 'error' }

const state = ref<CartState>(props.accessToken ? { kind: 'loading' } : { kind: 'guest' })
const selection = ref<CartSelection>(EMPTY_CART_SELECTION)
const managing = ref(false)
const pickedIds = ref<string[]>([])
const deleteAsked = ref(false)
const busy = ref(false)
let requestVersion = 0

const cart = computed(() => (state.value.kind === 'ready' ? state.value.cart : null))
const isEmpty = computed(() =>
  Boolean(cart.value && cart.value.groups.length === 0 && cart.value.invalidItems.length === 0))
const totals = computed(() =>
  cart.value
    ? summarizeCartSelection(cart.value, selection.value)
    : { lineCount: 0, amountMinor: 0, cartItemIds: [] })
const wholeMerchant = computed(() => Boolean(cart.value && isWholeMerchantChecked(cart.value, selection.value)))
const allIds = computed(() => (cart.value ? allCartItemIds(cart.value) : []))
const everythingPicked = computed(() =>
  allIds.value.length > 0 && allIds.value.every((id) => pickedIds.value.includes(id)))

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

function coverOf(item: CartItem): string | null {
  return item.coverImage?.url ?? resolveMallPreviewCover(item.productId)?.url ?? null
}

// The green tag after a merchant (prototype board Cart): only facts the offers carry.
function pickupTag(items: CartItem[]): string | null {
  if (items.every((item) => item.deliveryModes.length === 1 && item.deliveryModes[0] === 'PICKUP')) return '到店自提'
  return items.some((item) => item.deliveryModes.includes('PICKUP')) ? '可自提' : null
}

function isActiveMerchant(merchantId: string): boolean {
  return managing.value || selection.value.merchantId === merchantId
}

function isTicked(cartItemId: string): boolean {
  return managing.value ? pickedIds.value.includes(cartItemId) : selection.value.checkedIds.includes(cartItemId)
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    state.value = { kind: 'guest' }
    emit('countChange', 0)
    return
  }
  if (!props.cityCode) {
    state.value = { kind: 'error' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await cartApi.load(token, props.cityCode)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', cart: loaded }
    selection.value = reconcileCartSelection(loaded, selection.value)
    const ids = new Set(allCartItemIds(loaded))
    pickedIds.value = pickedIds.value.filter((id) => ids.has(id))
    if (ids.size === 0) managing.value = false
    emit('countChange', loaded.validCount)
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      state.value = { kind: 'guest' }
      emit('countChange', 0)
      return
    }
    state.value = { kind: 'error' }
  }
}

function pickMerchant(merchantId: string): void {
  if (!cart.value) return
  if (managing.value) {
    const group = cart.value.groups.find((item) => item.merchant.organizationId === merchantId)
    const ids = group?.items.map((item) => item.cartItemId) ?? []
    const all = ids.every((id) => pickedIds.value.includes(id))
    pickedIds.value = all
      ? pickedIds.value.filter((id) => !ids.includes(id))
      : [...new Set([...pickedIds.value, ...ids])]
    return
  }
  // Like a radio button (prototype board Cart): picking a merchant always ticks all of its goods.
  selection.value = selectCartMerchant(cart.value, merchantId)
}

function tick(item: CartItem): void {
  if (!cart.value) return
  if (managing.value) {
    pickedIds.value = pickedIds.value.includes(item.cartItemId)
      ? pickedIds.value.filter((id) => id !== item.cartItemId)
      : [...pickedIds.value, item.cartItemId]
    return
  }
  selection.value = toggleCartItem(cart.value, selection.value, item.cartItemId)
}

function tickAll(): void {
  if (!cart.value) return
  if (managing.value) {
    pickedIds.value = everythingPicked.value ? [] : [...allIds.value]
    return
  }
  selection.value = toggleWholeMerchant(cart.value, selection.value)
}

async function changeQuantity(item: CartItem, step: number): Promise<void> {
  const token = props.accessToken
  const next = item.quantity + step
  if (!token || busy.value || next < 1 || next > CART_MAX_QUANTITY) return
  busy.value = true
  try {
    await cartApi.setQuantity(token, item.cartItemId, next)
    await load()
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({ title: '数量没有改成功，请重试', icon: 'none' })
  } finally {
    busy.value = false
  }
}

async function removePicked(): Promise<void> {
  const token = props.accessToken
  if (!token || pickedIds.value.length === 0 || busy.value) return
  busy.value = true
  try {
    await cartApi.remove(token, pickedIds.value)
    pickedIds.value = []
    deleteAsked.value = false
    await load()
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({ title: '没有删除成功，请重试', icon: 'none' })
  } finally {
    busy.value = false
  }
}

async function clearInvalid(): Promise<void> {
  const token = props.accessToken
  if (!token || !props.cityCode || busy.value) return
  busy.value = true
  try {
    await cartApi.removeInvalid(token, props.cityCode)
    await load()
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({ title: '没有清空成功，请重试', icon: 'none' })
  } finally {
    busy.value = false
  }
}

function toggleManaging(): void {
  managing.value = !managing.value
  pickedIds.value = []
  deleteAsked.value = false
}

function checkout(): void {
  if (totals.value.lineCount > 0) emit('checkout', totals.value.cartItemIds)
}

watch(() => [props.refreshKey, props.accessToken, props.cityCode], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="cart" data-testid="cart">
    <view class="cart__top">
      <view class="cart__header">
        <text class="cart__title">
          购物车<text v-if="cart" class="cart__title-count" data-testid="cart-count">({{ cart.validCount }})</text>
        </text>
        <button
          v-if="cart && !isEmpty"
          :class="['cart__manage', { 'cart__manage--done': managing }]"
          data-testid="cart-manage"
          @click="toggleManaging"
        >
          {{ managing ? '完成' : '管理' }}
        </button>
      </view>
    </view>

    <view v-if="state.kind === 'guest'" class="cart__blank" data-testid="cart-guest">
      <view class="cart__blank-icon"><LineIcon name="cart" :size="40" color="#c9d3e1" /></view>
      <text class="cart__blank-title">登录后查看购物车</text>
      <text class="cart__blank-text">加入购物车的商品会保存在你的账号里</text>
      <button class="cart__blank-button" data-testid="cart-login" @click="emit('login')">登录 / 注册</button>
    </view>

    <view v-else-if="state.kind === 'loading'" class="cart__body" data-testid="cart-loading" aria-label="正在加载">
      <view class="cart__skeleton" />
      <view class="cart__skeleton" />
    </view>

    <view v-else-if="state.kind === 'error'" class="cart__blank" data-testid="cart-error">
      <text class="cart__blank-title">购物车没有读出来</text>
      <text class="cart__blank-text">请检查网络后重试</text>
      <button class="cart__blank-button" data-testid="cart-retry" @click="load">重试</button>
    </view>

    <view v-else-if="isEmpty" class="cart__blank" data-testid="cart-empty">
      <view class="cart__blank-icon"><LineIcon name="cart" :size="40" color="#c9d3e1" /></view>
      <text class="cart__blank-title">购物车是空的</text>
      <text class="cart__blank-text">在商城里挑好商品，加入购物车后在这里结算</text>
      <button class="cart__blank-button" data-testid="cart-browse" @click="emit('browse')">去商城逛逛</button>
      <text class="cart__footnote">询价商品、服务和活动不进购物车，分别在商品详情、专业服务和活动里办理。</text>
    </view>

    <view v-else-if="cart" class="cart__body">
      <view v-if="!managing" class="cart__tip">
        <LineIcon name="info" :size="14" color="#2f80ed" :stroke-width="2" />
        <text>每次只能结算一个商家；不同商家请分别下单付款。</text>
      </view>

      <view
        v-for="group in cart.groups"
        :key="group.merchant.organizationId"
        class="cart__group"
        :data-testid="`cart-group-${group.merchant.organizationId}`"
      >
        <view class="cart__merchant">
          <button
            class="cart__radio-hit"
            :data-testid="`cart-merchant-${group.merchant.organizationId}`"
            role="radio"
            :aria-checked="!managing && selection.merchantId === group.merchant.organizationId"
            :aria-label="`${managing ? '勾选' : '结算'}${group.merchant.name}的商品`"
            @click="pickMerchant(group.merchant.organizationId)"
          >
            <view
              :class="[
                'cart__tick',
                {
                  'cart__tick--on': group.items.every((item) => isTicked(item.cartItemId)),
                  'cart__tick--na': !isActiveMerchant(group.merchant.organizationId),
                },
              ]"
            >
              <LineIcon v-if="group.items.every((item) => isTicked(item.cartItemId))" name="check" :size="12" color="#ffffff" :stroke-width="3" />
            </view>
          </button>
          <LineIcon name="store" :size="18" color="#202c42" />
          <text class="cart__merchant-name">{{ group.merchant.name }}</text>
          <text class="cart__merchant-kind">{{ MALL_MERCHANT_KIND_LABELS[group.merchant.kind] }}</text>
          <text v-if="pickupTag(group.items)" class="cart__merchant-tag">{{ pickupTag(group.items) }}</text>
        </view>

        <view
          v-for="item in group.items"
          :key="item.cartItemId"
          class="cart__item"
          :data-testid="`cart-item-${item.cartItemId}`"
          :data-name="item.productName"
        >
          <button
            v-if="isActiveMerchant(group.merchant.organizationId)"
            class="cart__radio-hit cart__radio-hit--item"
            :data-testid="`cart-tick-${item.cartItemId}`"
            role="checkbox"
            :aria-checked="isTicked(item.cartItemId)"
            :aria-label="`勾选${item.productName}`"
            @click="tick(item)"
          >
            <view :class="['cart__tick', { 'cart__tick--on': isTicked(item.cartItemId) }]">
              <LineIcon v-if="isTicked(item.cartItemId)" name="check" :size="12" color="#ffffff" :stroke-width="3" />
            </view>
          </button>
          <view v-else class="cart__tick cart__tick--na cart__tick--item" aria-label="当前不可选" />
          <button class="cart__cover-button" @click="emit('openProduct', item.productId)">
            <image v-if="coverOf(item)" class="cart__cover" :src="coverOf(item) ?? ''" mode="aspectFill" />
            <view v-else class="cart__cover cart__cover--blank" />
          </button>
          <view class="cart__info">
            <text class="cart__name" @click="emit('openProduct', item.productId)">{{ item.productName }}</text>
            <text class="cart__spec">{{ item.skuName }}</text>
            <text
              v-if="item.shippingNote && isActiveMerchant(group.merchant.organizationId)"
              class="cart__ship"
            >{{ item.shippingNote }}</text>
            <view v-if="!isActiveMerchant(group.merchant.organizationId)" class="cart__other">
              <LineIcon name="info" :size="12" color="#738096" :stroke-width="2" />
              <text>与已选商家不同，需分开结算</text>
            </view>
            <view class="cart__price-row">
              <text class="cart__price">{{ formatMallAmount(item.unitAmountMinor ?? 0) }}</text>
              <view v-if="!managing" class="cart__stepper">
                <button
                  :class="['cart__step', { 'cart__step--off': item.quantity <= 1 }]"
                  :data-testid="`cart-minus-${item.cartItemId}`"
                  :aria-label="`减少${item.productName}数量`"
                  :aria-disabled="item.quantity <= 1"
                  @click="changeQuantity(item, -1)"
                >
                  −
                </button>
                <text class="cart__qty" :data-testid="`cart-qty-${item.cartItemId}`">{{ item.quantity }}</text>
                <button
                  :class="['cart__step', { 'cart__step--off': item.quantity >= CART_MAX_QUANTITY }]"
                  :data-testid="`cart-plus-${item.cartItemId}`"
                  :aria-label="`增加${item.productName}数量`"
                  :aria-disabled="item.quantity >= CART_MAX_QUANTITY"
                  @click="changeQuantity(item, 1)"
                >
                  +
                </button>
              </view>
              <text v-else class="cart__qty-plain">× {{ item.quantity }}</text>
            </view>
          </view>
        </view>
      </view>

      <view v-if="cart.invalidItems.length > 0" class="cart__group" data-testid="cart-invalid">
        <view class="cart__invalid-head">
          <text class="cart__invalid-title">失效商品 ({{ cart.invalidItems.length }})</text>
          <button
            v-if="!managing"
            class="cart__invalid-clear"
            data-testid="cart-clear-invalid"
            @click="clearInvalid"
          >
            清空失效
          </button>
        </view>
        <view
          v-for="item in cart.invalidItems"
          :key="item.cartItemId"
          class="cart__invalid-item"
          :data-testid="`cart-invalid-${item.cartItemId}`"
        >
          <button
            v-if="managing"
            class="cart__radio-hit cart__radio-hit--invalid"
            role="checkbox"
            :aria-checked="isTicked(item.cartItemId)"
            :aria-label="`勾选${item.productName}`"
            @click="tick(item)"
          >
            <view :class="['cart__tick', { 'cart__tick--on': isTicked(item.cartItemId) }]">
              <LineIcon v-if="isTicked(item.cartItemId)" name="check" :size="12" color="#ffffff" :stroke-width="3" />
            </view>
          </button>
          <text v-else class="cart__invalid-tag">失效</text>
          <image v-if="coverOf(item)" class="cart__invalid-cover" :src="coverOf(item) ?? ''" mode="aspectFill" />
          <view v-else class="cart__invalid-cover" />
          <view class="cart__invalid-info">
            <text class="cart__invalid-name">{{ item.productName }}</text>
            <text class="cart__invalid-why">
              {{ item.invalidReason ? CART_INVALID_REASON_TEXT[item.invalidReason] : '' }}
            </text>
          </view>
        </view>
      </view>

    </view>

    <view v-if="cart && !isEmpty" class="cart__bar">
      <button class="cart__all" data-testid="cart-all" @click="tickAll">
        <view :class="['cart__tick', { 'cart__tick--on': managing ? everythingPicked : wholeMerchant }]">
          <LineIcon v-if="managing ? everythingPicked : wholeMerchant" name="check" :size="12" color="#ffffff" :stroke-width="3" />
        </view>
        <text>{{ managing ? '全选' : '全选本商家' }}</text>
      </button>
      <template v-if="!managing">
        <text class="cart__sum">
          合计<text class="cart__sum-value" data-testid="cart-total">{{ formatMallAmount(totals.amountMinor) }}</text>
        </text>
        <button
          :class="['cart__checkout', { 'cart__checkout--off': totals.lineCount === 0 }]"
          data-testid="cart-checkout"
          :disabled="totals.lineCount === 0"
          @click="checkout"
        >
          {{ totals.lineCount > 0 ? `结算(${totals.lineCount})` : '结算' }}
        </button>
      </template>
      <template v-else>
        <text class="cart__picked">已选 {{ pickedIds.length }} 件</text>
        <button
          :class="['cart__delete', { 'cart__delete--off': pickedIds.length === 0 }]"
          data-testid="cart-delete"
          :disabled="pickedIds.length === 0"
          @click="deleteAsked = true"
        >
          {{ pickedIds.length > 0 ? `删除(${pickedIds.length})` : '删除' }}
        </button>
      </template>
    </view>

    <view v-if="deleteAsked" class="cart__mask" @click.self="deleteAsked = false">
      <view class="cart__sheet" role="dialog" aria-label="删除商品" data-testid="cart-delete-sheet">
        <text class="cart__sheet-title">删除选中的 {{ pickedIds.length }} 件商品？</text>
        <text class="cart__sheet-text">删除后不能恢复，需要时请重新加入购物车。</text>
        <view class="cart__sheet-actions">
          <button class="cart__sheet-keep" @click="deleteAsked = false">暂不删除</button>
          <button class="cart__sheet-confirm" data-testid="cart-delete-confirm" @click="removePicked">确认删除</button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype boards Cart and CartStates (390 wide). */
.cart {
  display: flex;
  min-height: 100vh;
  padding-bottom: calc(72px + var(--app-root-tabbar-content-offset, 0px));
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  box-sizing: border-box;
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

.cart__top {
  position: sticky;
  top: 0;
  z-index: 5;
  padding-top: var(--status-bar-height);
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.cart__header {
  display: flex;
  padding: 6px 4px 0 16px;
  align-items: center;
}

.cart__title {
  font-size: 20px;
  font-weight: 700;
  line-height: 44px;
}

.cart__title-count {
  font-size: 15px;
  font-weight: 500;
}

.cart__manage {
  height: 44px;
  margin-left: auto;
  padding: 0 12px;
  color: #202c42;
  font-size: 15px;
  line-height: 44px;
}

.cart__manage--done {
  color: #ba470b;
}

.cart__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 10px 16px 16px;
}

.cart__tip {
  display: flex;
  padding: 8px 12px;
  align-items: flex-start;
  gap: 6px;
  border-radius: 10px;
  color: #1d63c4;
  background: #e6f0ff;
  font-size: 12px;
  line-height: 17px;
}

.cart__group {
  display: flex;
  flex-direction: column;
  padding: 0 12px 12px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.cart__merchant {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 6px;
}

.cart__radio-hit {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  margin: 0 -10px 0 -12px;
  align-items: center;
  justify-content: center;
}

.cart__radio-hit--item {
  margin-top: 18px;
}

.cart__radio-hit--invalid {
  margin-top: 0;
}

.cart__tick {
  display: flex;
  width: 20px;
  height: 20px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #c9d3e1;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.cart__tick--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.cart__tick--na {
  border-color: #e5ebf3;
  background: #eef2f8;
}

.cart__tick--item {
  margin-top: 30px;
}

.cart__merchant-name {
  min-width: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cart__merchant-kind {
  flex: none;
  padding: 0 5px;
  border: 1px solid #e5ebf3;
  border-radius: 4px;
  color: #536178;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.cart__merchant-tag {
  flex: none;
  padding: 1px 5px;
  border-radius: 4px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.cart__item {
  display: flex;
  padding-top: 10px;
  align-items: flex-start;
  gap: 10px;
}

.cart__cover-button {
  flex: none;
}

.cart__cover {
  display: block;
  width: 80px;
  height: 80px;
  border-radius: 10px;
  background: #eef2f8;
}

.cart__info {
  display: flex;
  min-width: 0;
  min-height: 80px;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.cart__name {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.cart__spec {
  padding: 0 6px;
  border-radius: 6px;
  color: #536178;
  background: #eef2f8;
  font-size: 12px;
  line-height: 22px;
}

.cart__ship {
  color: #11804a;
  font-size: 12px;
  line-height: 17px;
}

.cart__other {
  display: flex;
  padding: 2px 6px;
  align-items: center;
  gap: 4px;
  border-radius: 6px;
  color: #627187;
  background: #eef2f8;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.cart__price-row {
  display: flex;
  margin-top: auto;
  align-items: center;
  align-self: stretch;
}

.cart__price {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
}

.cart__stepper {
  display: flex;
  height: 26px;
  margin-left: auto;
  align-items: center;
  border: 1px solid #e5ebf3;
  border-radius: 6px;
}

.cart__step {
  width: 30px;
  height: 26px;
  color: #536178;
  font-size: 14px;
  line-height: 26px;
  text-align: center;
}

.cart__step--off {
  color: #c9d3e1;
}

.cart__qty {
  width: 30px;
  border-right: 1px solid #e5ebf3;
  border-left: 1px solid #e5ebf3;
  font-size: 13px;
  line-height: 26px;
  text-align: center;
}

.cart__qty-plain {
  margin-left: auto;
  color: #627187;
  font-size: 13px;
}

.cart__invalid-head {
  display: flex;
  min-height: 44px;
  align-items: center;
}

.cart__invalid-title {
  color: #536178;
  font-size: 15px;
  font-weight: 500;
}

.cart__invalid-clear {
  min-height: 44px;
  margin-left: auto;
  padding-left: 12px;
  color: #ba470b;
  font-size: 14px;
}

.cart__invalid-item {
  display: flex;
  padding-top: 8px;
  align-items: center;
  gap: 10px;
}

.cart__invalid-tag {
  flex: none;
  padding: 1px 4px;
  border-radius: 6px;
  color: #ffffff;
  background: #c9d3e1;
  font-size: 11px;
  line-height: 16px;
}

.cart__invalid-cover {
  width: 64px;
  height: 64px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
  opacity: 0.5;
}

.cart__invalid-info {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.cart__invalid-name {
  color: #91a0b4;
  font-size: 14px;
  line-height: 20px;
}

.cart__invalid-why {
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
}

.cart__footnote {
  padding: 0 8px;
  color: #738096;
  font-size: 12px;
  line-height: 19px;
  text-align: center;
}

.cart__blank {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.cart__blank-icon {
  display: flex;
  width: 72px;
  height: 72px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef2f8;
}

.cart__blank-title {
  margin-top: 4px;
  font-size: 16px;
  font-weight: 700;
}

.cart__blank-text {
  color: #738096;
  font-size: 13px;
  line-height: 19px;
}

.cart__blank-button {
  min-width: 140px;
  height: 40px;
  margin: 8px 0 12px;
  padding: 0 20px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  line-height: 40px;
}

.cart__skeleton {
  height: 140px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.cart__bar {
  position: fixed;
  right: 0;
  bottom: calc(60px + env(safe-area-inset-bottom));
  left: 0;
  z-index: 6;
  display: flex;
  height: 56px;
  padding: 0 12px 0 16px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-sizing: border-box;
}

.cart__all {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 6px;
  color: #536178;
  font-size: 13px;
  white-space: nowrap;
}

.cart__sum {
  display: flex;
  margin-left: auto;
  align-items: baseline;
  gap: 4px;
  font-size: 13px;
  white-space: nowrap;
}

.cart__sum-value {
  color: #e85400;
  font-size: 18px;
  font-weight: 700;
}

.cart__checkout,
.cart__delete {
  height: 44px;
  flex: none;
  padding: 0 20px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
  line-height: 44px;
  white-space: nowrap;
}

.cart__checkout--off,
.cart__checkout[disabled] {
  color: #ffffff;
  background: #c9d3e1;
}

.cart__picked {
  margin-left: auto;
  color: #536178;
  font-size: 13px;
}

.cart__delete {
  border: 1.5px solid #ff6a00;
  color: #ba470b;
  background: #ffffff;
  line-height: 41px;
  box-sizing: border-box;
}

.cart__delete--off,
.cart__delete[disabled] {
  border: 0;
  color: #ffffff;
  background: #c9d3e1;
  line-height: 44px;
}

.cart__mask {
  position: fixed;
  z-index: 1200;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.45);
}

.cart__sheet {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 10px;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  background: #ffffff;
  box-sizing: border-box;
}

.cart__sheet-title {
  font-size: 17px;
  font-weight: 700;
}

.cart__sheet-text {
  color: #536178;
  font-size: 13px;
  line-height: 1.6;
}

.cart__sheet-actions {
  display: flex;
  margin-top: 4px;
  gap: 10px;
}

.cart__sheet-keep,
.cart__sheet-confirm {
  height: 44px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
  box-sizing: border-box;
}

.cart__sheet-keep {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
  line-height: 42px;
}

.cart__sheet-confirm {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}
</style>
