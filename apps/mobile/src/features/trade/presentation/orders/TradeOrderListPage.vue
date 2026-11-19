<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import {
  TRADE_COMMAND_CONFIRMS,
  classifyOrderCommandFailure,
} from '../../application/order-commands'
import {
  TRADE_TAB_LABELS,
  describeOrderStatus,
  formatTradeAmount,
  listCardActions,
  listCardGoods,
  listCardHint,
  type TradeOrderAction,
} from '../../application/order-view'
import { isUnauthorizedFailure, readTradeFailure } from '../../application/start-payment'
import { createTradeKey } from '../../application/trade-keys'
import { tradeApi } from '../../data/trade-api-client'
import { TRADE_ORDER_TABS, type TradeOrder, type TradeOrderTab } from '../../domain/trade-order'
import TradePageHeader from '../shared/TradePageHeader.vue'
import { TRADE_TONE_COLORS, confirmDialog, coverOfLine } from '../shared/trade-presentation'

const PAGE_SIZE = 10

const props = defineProps<{
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
  // Counts up when the page is scrolled to its end.
  reachBottomKey: number
  initialTab: TradeOrderTab
}>()

const emit = defineEmits<{
  back: []
  login: []
  openOrder: [orderId: string]
  pay: [orderId: string]
  tabChange: [tab: TradeOrderTab]
  // A reload finished (the host stops the pull-down spinner).
  loaded: []
}>()

type ListState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error' }

const ACTION_LABELS: Record<TradeOrderAction, string> = {
  CANCEL: '取消订单',
  CANCEL_AND_REFUND: '取消订单',
  PAY: '继续支付',
  CONFIRM_RECEIPT: '确认收货',
  VIEW: '查看订单',
}

const tab = ref<TradeOrderTab>(props.initialTab)
const state = ref<ListState>({ kind: 'loading' })
const items = ref<TradeOrder[]>([])
const page = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)
const busyOrderId = ref<string | null>(null)
const now = ref(Date.now())
let requestVersion = 0
let timer: ReturnType<typeof setInterval> | null = null

const cards = computed(() => items.value.map((order) => {
  const first = order.lines[0]
  return {
    order,
    status: describeOrderStatus(order, now.value),
    goods: listCardGoods(order),
    hint: listCardHint(order, now.value),
    actions: listCardActions(order, now.value),
    cover: first ? coverOfLine(first) : null,
    coverAlt: first?.productName ?? '',
  }
}))

function handleFailure(error: unknown): boolean {
  if (isUnauthorizedFailure(readTradeFailure(error))) {
    emit('login')
    return true
  }
  return false
}

async function reload(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready' || items.value.length === 0) state.value = { kind: 'loading' }
  try {
    const loaded = await tradeApi.list(token, tab.value, 1, PAGE_SIZE)
    if (requested !== requestVersion) return
    items.value = loaded.items
    page.value = 1
    hasMore.value = loaded.hasMore
    now.value = Date.now()
    state.value = { kind: 'ready' }
  } catch (error) {
    if (requested !== requestVersion) return
    if (handleFailure(error)) return
    state.value = { kind: 'error' }
  } finally {
    if (requested === requestVersion) emit('loaded')
  }
}

async function loadMore(): Promise<void> {
  const token = props.accessToken
  if (!token || state.value.kind !== 'ready' || !hasMore.value || loadingMore.value) return
  const requested = requestVersion
  loadingMore.value = true
  try {
    const loaded = await tradeApi.list(token, tab.value, page.value + 1, PAGE_SIZE)
    if (requested !== requestVersion) return
    // An order can move between pages while the list is open; keep the first copy of each.
    const known = new Set(items.value.map((order) => order.orderId))
    items.value = [...items.value, ...loaded.items.filter((order) => !known.has(order.orderId))]
    page.value += 1
    hasMore.value = loaded.hasMore
  } catch (error) {
    if (requested !== requestVersion) return
    if (!handleFailure(error)) uni.showToast({ title: '加载失败，请重试', icon: 'none' })
  } finally {
    if (requested === requestVersion) loadingMore.value = false
  }
}

function chooseTab(next: TradeOrderTab): void {
  if (next === tab.value) return
  tab.value = next
  items.value = []
  hasMore.value = false
  state.value = { kind: 'loading' }
  emit('tabChange', next)
  void reload()
}

async function runAction(order: TradeOrder, action: TradeOrderAction): Promise<void> {
  if (action === 'VIEW') {
    emit('openOrder', order.orderId)
    return
  }
  if (action === 'PAY') {
    emit('pay', order.orderId)
    return
  }
  const token = props.accessToken
  if (!token || busyOrderId.value) return
  if (!(await confirmDialog(TRADE_COMMAND_CONFIRMS[action]))) return
  busyOrderId.value = order.orderId
  try {
    const command = { idempotencyKey: createTradeKey(), version: order.version }
    if (action === 'CONFIRM_RECEIPT') {
      await tradeApi.confirmReceipt(token, order.orderId, command)
      uni.showToast({ title: '已确认收货', icon: 'none' })
    } else {
      await tradeApi.cancel(token, order.orderId, command)
      uni.showToast({ title: '订单已取消', icon: 'none' })
    }
  } catch (error) {
    const failure = classifyOrderCommandFailure(error)
    if (failure.kind === 'UNAUTHORIZED') {
      emit('login')
      return
    }
    uni.showToast({ title: failure.message, icon: 'none' })
  } finally {
    busyOrderId.value = null
  }
  // The order may now belong to another tab; the list is read again either way.
  await reload()
}

watch(() => [props.refreshKey, props.accessToken], () => { void reload() }, { immediate: true })
watch(() => props.reachBottomKey, () => { void loadMore() })

onMounted(() => {
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view class="goods-orders" data-testid="goods-orders">
    <view class="goods-orders__top">
      <TradePageHeader title="商品订单" centered test-id="goods-orders" @back="emit('back')" />
      <view class="goods-orders__tabs" role="tablist" aria-label="订单状态">
        <button
          v-for="item in TRADE_ORDER_TABS"
          :key="item"
          :class="['goods-orders__tab', { 'goods-orders__tab--on': item === tab }]"
          :data-testid="`goods-orders-tab-${item}`"
          role="tab"
          :aria-selected="item === tab"
          @click="chooseTab(item)"
        >
          {{ TRADE_TAB_LABELS[item] }}
          <view v-if="item === tab" class="goods-orders__tab-bar" />
        </button>
      </view>
    </view>

    <view v-if="state.kind === 'loading'" class="goods-orders__body" data-testid="goods-orders-loading" aria-label="正在加载">
      <view class="goods-orders__skeleton" />
      <view class="goods-orders__skeleton" />
    </view>

    <view v-else-if="state.kind === 'error'" class="goods-orders__state" data-testid="goods-orders-error">
      <text class="goods-orders__state-title">加载失败</text>
      <text class="goods-orders__state-text">请检查网络后重试</text>
      <button class="goods-orders__state-button" data-testid="goods-orders-retry" @click="reload">重试</button>
    </view>

    <view v-else-if="items.length === 0" class="goods-orders__empty" data-testid="goods-orders-empty">
      <view class="goods-orders__empty-icon">
        <image class="goods-orders__empty-svg" src="/static/images/trade/empty-orders.svg" mode="aspectFit" />
      </view>
      <text class="goods-orders__empty-text">暂无相关订单</text>
    </view>

    <view v-else class="goods-orders__body">
      <view
        v-for="card in cards"
        :key="card.order.orderId"
        class="goods-orders__card"
        :data-testid="`goods-order-${card.order.orderNo}`"
      >
        <view class="goods-orders__open" @click="emit('openOrder', card.order.orderId)">
          <view class="goods-orders__card-head">
            <text class="goods-orders__kind">商品</text>
            <text class="goods-orders__merchant">{{ card.order.merchant.name }}</text>
            <text
              class="goods-orders__status"
              data-testid="goods-order-status"
              :style="{ color: TRADE_TONE_COLORS[card.status.tone] }"
            >
              {{ card.status.label }}
            </text>
          </view>
          <view class="goods-orders__goods">
            <image v-if="card.cover" class="goods-orders__cover" :src="card.cover" mode="aspectFit" :aria-label="card.coverAlt" />
            <view v-else class="goods-orders__cover" />
            <view class="goods-orders__info">
              <view class="goods-orders__name-line">
                <text class="goods-orders__name">{{ card.goods.name }}</text>
                <text class="goods-orders__amount" data-testid="goods-order-amount">{{ formatTradeAmount(card.order.amounts.payableMinor) }}</text>
              </view>
              <text class="goods-orders__spec">{{ card.goods.spec }}</text>
              <view
                v-if="card.hint"
                :class="['goods-orders__hint', `goods-orders__hint--${card.hint.kind.toLowerCase()}`]"
                data-testid="goods-order-hint"
              >
                <image
                  v-if="card.hint.kind === 'COUNTDOWN'"
                  class="goods-orders__hint-icon"
                  src="/static/images/trade/clock-orange.svg"
                  mode="aspectFit"
                />
                <image
                  v-else-if="card.hint.kind === 'SHIPPED'"
                  class="goods-orders__hint-icon"
                  src="/static/images/trade/truck-green.svg"
                  mode="aspectFit"
                />
                <text>{{ card.hint.text }}</text>
              </view>
            </view>
          </view>
        </view>
        <view class="goods-orders__actions">
          <button
            v-for="action in card.actions"
            :key="action"
            :class="[
              'goods-orders__action',
              {
                'goods-orders__action--primary': action === 'PAY',
                'goods-orders__action--accent': action === 'CONFIRM_RECEIPT',
              },
            ]"
            :data-testid="`goods-order-action-${action.toLowerCase()}`"
            :disabled="busyOrderId === card.order.orderId"
            @click="runAction(card.order, action)"
          >
            {{ ACTION_LABELS[action] }}
          </button>
        </view>
      </view>
      <text v-if="loadingMore" class="goods-orders__more">正在加载…</text>
      <text v-else-if="!hasMore" class="goods-orders__more" data-testid="goods-orders-end">没有更多了</text>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the goods cards of the 2026-11-14 prototype board OrderList (390 wide). */
.goods-orders {
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

.goods-orders__top {
  position: sticky;
  top: 0;
  z-index: 5;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.goods-orders__tabs {
  display: grid;
  padding: 0 8px 4px;
  grid-template-columns: repeat(6, minmax(0, 1fr));
}

.goods-orders__tab {
  position: relative;
  height: 44px;
  color: #627187;
  font-size: 15px;
  line-height: 44px;
  white-space: nowrap;
}

.goods-orders__tab--on {
  color: #202c42;
  font-weight: 700;
}

.goods-orders__tab-bar {
  position: absolute;
  bottom: 3px;
  left: 50%;
  width: 20px;
  height: 3px;
  margin-left: -10px;
  border-radius: 3px;
  background: #ff6a00;
}

.goods-orders__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 12px 16px;
}

.goods-orders__skeleton {
  height: 170px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.goods-orders__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.goods-orders__state-title {
  font-size: 16px;
  font-weight: 700;
}

.goods-orders__state-text {
  color: #738096;
  font-size: 13px;
}

.goods-orders__state-button {
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

.goods-orders__empty {
  display: flex;
  padding: 72px 0;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.goods-orders__empty-icon {
  display: flex;
  width: 64px;
  height: 64px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef0f3;
}

.goods-orders__empty-svg {
  width: 30px;
  height: 30px;
}

.goods-orders__empty-text {
  color: #738096;
  font-size: 14px;
  line-height: 20px;
}

.goods-orders__card {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 12px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
}

.goods-orders__open {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.goods-orders__card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.goods-orders__kind {
  flex: none;
  padding: 0 6px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  line-height: 20px;
}

.goods-orders__merchant {
  min-width: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.goods-orders__status {
  flex: none;
  margin-left: auto;
  font-size: 14px;
  font-weight: 500;
  line-height: 21px;
}

.goods-orders__goods {
  display: flex;
  gap: 12px;
}

.goods-orders__cover {
  display: block;
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: 10px;
  background: #f0edff;
}

.goods-orders__info {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.goods-orders__name-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.goods-orders__name {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.goods-orders__amount {
  flex: none;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.goods-orders__spec {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.goods-orders__hint {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.goods-orders__hint--countdown {
  color: #e85400;
}

.goods-orders__hint--shipped {
  color: #11804a;
}

.goods-orders__hint-icon {
  width: 14px;
  height: 14px;
  flex: none;
}

.goods-orders__actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.goods-orders__action {
  height: 36px;
  padding: 0 16px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 14px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.goods-orders__action--primary {
  padding: 0 18px;
  border: 0;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  font-weight: 500;
  line-height: 36px;
}

.goods-orders__action--accent {
  border: 1.5px solid #ff6a00;
  color: #ba470b;
  font-weight: 500;
  line-height: 33px;
}

/* uni's own [disabled] colours would turn the buttons grey; keep each look and only fade it. */
.goods-orders__action[disabled] {
  color: #202c42;
  background: #ffffff;
  opacity: 0.55;
}

.goods-orders__action--primary[disabled] {
  color: #ffffff;
  background: #ff6a00;
}

.goods-orders__action--accent[disabled] {
  color: #ba470b;
}

.goods-orders__more {
  padding: 4px 0 12px;
  color: #91a0b4;
  font-size: 12px;
  text-align: center;
}
</style>
