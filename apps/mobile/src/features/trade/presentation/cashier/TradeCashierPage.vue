<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import {
  TRADE_CHANNEL_UNAVAILABLE_TEXT,
  classifyOrderCommandFailure,
} from '../../application/order-commands'
import {
  cancelUnpaidConfirm,
  cashierClosedNote,
  cashierCountdownLine,
  cashierDueNote,
  cashierLayout,
  cashierPayeeSuffix,
  cancelledToast,
  isSourceOrder,
  orderDestination,
  orderLinkLabel,
  type TradeOrderDestination,
} from '../../application/order-source'
import {
  TRADE_PAYMENT_METHOD_LABEL,
  canPay,
  describeCashierClosed,
  formatTradeAmount,
} from '../../application/order-view'
import { isUnauthorizedFailure, readTradeFailure, startPayment } from '../../application/start-payment'
import { createTradeKey } from '../../application/trade-keys'
import { tradeApi } from '../../data/trade-api-client'
import { wechatPaymentLauncher } from '../../data/wechat-payment-launcher'
import { hasBeenPaid, type TradeOrder } from '../../domain/trade-order'
import TradePageHeader from '../shared/TradePageHeader.vue'
import { confirmDialog, copyText, wait } from '../shared/trade-presentation'
import LineIcon from '@/shared/ui/LineIcon.vue'

const props = defineProps<{
  orderId: string
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  // The order's own page when the order is known: the way back from a sign-up's cashier is its ticket.
  back: [destination: TradeOrderDestination | null]
  login: []
  // Go on to the result page; the server tells what happened.
  result: []
  // The goods order, or the ticket of the enrolment this order pays for.
  viewOrder: [destination: TradeOrderDestination]
  rebuy: [productId: string | null]
}>()

type CashierState =
  | { kind: 'loading' }
  | { kind: 'ready'; order: TradeOrder }
  | { kind: 'missing' }
  | { kind: 'error' }

// On H5 the WeChat sheet cannot open; the toast stays this long before the result page.
const UNSUPPORTED_NOTICE_MS = 1500

const state = ref<CashierState>({ kind: 'loading' })
const now = ref(Date.now())
const paying = ref(false)
const cancelling = ref(false)
const copied = ref(false)
let requestVersion = 0
let timer: ReturnType<typeof setInterval> | null = null

const order = computed(() => (state.value.kind === 'ready' ? state.value.order : null))
const payable = computed(() => (order.value ? canPay(order.value, now.value) : false))
// Unpaid and out of time, or closed: the board's 支付已超时 frame.
const closed = computed(() => Boolean(order.value && !payable.value))
// Which of the board's closed frames: out of time (still unpaid past the deadline), cancelled by
// the buyer, or closed because time ran out (the order's close reason says which).
const cancelledHere = ref(false)
const closedFrame = computed(() =>
  order.value ? describeCashierClosed(order.value, now.value, cancelledHere.value) : { title: '', text: '' })
const closedTitle = computed(() => closedFrame.value.title)
const closedText = computed(() => closedFrame.value.text)
const countdownLine = computed(() => (order.value ? cashierCountdownLine(order.value, now.value) : ''))
const dueNote = computed(() => (order.value ? cashierDueNote(order.value) : null))
const layout = computed(() => (order.value ? cashierLayout(order.value) : []))
const payeeSuffix = computed(() => (order.value ? cashierPayeeSuffix(order.value) : null))
// A sign-up or a venue booking: one way on, to its own page, instead of 查看订单 / 重新选购.
const sourceOrder = computed(() => Boolean(order.value && isSourceOrder(order.value)))
const destination = computed(() => (order.value ? orderDestination(order.value) : null))
const amountText = computed(() => (order.value ? formatTradeAmount(order.value.amounts.payableMinor) : ''))

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.orderId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await tradeApi.read(token, props.orderId)
    if (requested !== requestVersion) return
    now.value = Date.now()
    // Already paid (a second device, or the notification came first): the result page says so.
    if (hasBeenPaid(loaded)) {
      emit('result')
      return
    }
    state.value = { kind: 'ready', order: loaded }
  } catch (error) {
    if (requested !== requestVersion) return
    const failure = readTradeFailure(error)
    if (isUnauthorizedFailure(failure)) {
      emit('login')
      return
    }
    state.value = { kind: failure?.status === 404 || failure?.status === 400 ? 'missing' : 'error' }
  }
}

async function pay(): Promise<void> {
  const token = props.accessToken
  if (!token || !order.value || paying.value) return
  paying.value = true
  const result = await startPayment({
    launch: (key) => tradeApi.launchPayment(token, props.orderId, key),
    launcher: wechatPaymentLauncher,
  })
  if (result.kind === 'TO_RESULT') {
    if (result.outcome === 'UNSUPPORTED') {
      uni.showToast({ title: '请在 App 中完成微信支付', icon: 'none', duration: UNSUPPORTED_NOTICE_MS })
      await wait(UNSUPPORTED_NOTICE_MS)
    }
    emit('result')
    return
  }
  paying.value = false
  if (result.kind === 'UNAUTHORIZED') {
    emit('login')
  } else if (result.kind === 'CHANNEL_UNAVAILABLE') {
    uni.showToast({ title: TRADE_CHANNEL_UNAVAILABLE_TEXT, icon: 'none' })
  } else if (result.kind === 'ORDER_CHANGED') {
    uni.showToast({ title: '订单状态已变化', icon: 'none' })
    void load()
  } else {
    uni.showToast({ title: result.message, icon: 'none' })
  }
}

async function cancel(): Promise<void> {
  const token = props.accessToken
  const current = order.value
  if (!token || !current || cancelling.value) return
  if (!(await confirmDialog(cancelUnpaidConfirm(current)))) return
  cancelling.value = true
  try {
    const cancelled = await tradeApi.cancel(token, current.orderId, {
      idempotencyKey: createTradeKey(),
      version: current.version,
    })
    cancelledHere.value = true
    state.value = { kind: 'ready', order: cancelled }
    uni.showToast({ title: cancelledToast(cancelled), icon: 'none' })
  } catch (error) {
    const failure = classifyOrderCommandFailure(error)
    if (failure.kind === 'UNAUTHORIZED') {
      emit('login')
    } else {
      uni.showToast({ title: failure.message, icon: 'none' })
      if (failure.kind === 'ORDER_CHANGED') void load()
    }
  } finally {
    cancelling.value = false
  }
}

function copyOrderNo(): void {
  if (order.value) copyText(order.value.orderNo, () => { copied.value = true })
}

watch(() => [props.refreshKey, props.accessToken, props.orderId], () => { void load() }, { immediate: true })

// The countdown ticks on the page; the server closes the order on its own when time runs out.
onMounted(() => {
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view class="cashier" data-testid="cashier">
    <TradePageHeader :title="closed ? '支付结果' : '收银台'" test-id="cashier" @back="emit('back', destination)" />

    <view v-if="state.kind === 'loading'" class="cashier__body" data-testid="cashier-loading" aria-label="正在加载">
      <view class="cashier__skeleton" />
      <view class="cashier__skeleton cashier__skeleton--short" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="cashier__state" data-testid="cashier-missing">
      <text class="cashier__state-title">没有找到这笔订单</text>
      <button class="cashier__state-button" @click="emit('back', null)">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="cashier__state" data-testid="cashier-error">
      <text class="cashier__state-title">加载失败</text>
      <text class="cashier__state-text">请检查网络后重试</text>
      <button class="cashier__state-button" data-testid="cashier-retry" @click="load">重试</button>
    </view>

    <template v-else-if="order">
      <view class="cashier__body">
        <view v-if="!closed" class="cashier__card cashier__due">
          <text class="cashier__due-label">应付金额</text>
          <text class="cashier__due-amount" data-testid="cashier-amount">{{ amountText }}</text>
          <text v-if="dueNote" class="cashier__due-note" data-testid="cashier-due-note">{{ dueNote }}</text>
          <text class="cashier__due-time" data-testid="cashier-countdown">{{ countdownLine }}</text>
        </view>

        <view v-else class="cashier__card cashier__closed" data-testid="cashier-closed">
          <view class="cashier__closed-icon">
            <image class="cashier__closed-svg" src="/static/images/trade/clock-grey.svg" mode="aspectFit" />
          </view>
          <text class="cashier__closed-title">{{ closedTitle }}</text>
          <text class="cashier__closed-text">{{ closedText }}</text>
          <text class="cashier__closed-note" data-testid="cashier-closed-note">{{ cashierClosedNote(order) }}</text>
        </view>

        <view class="cashier__card cashier__rows">
          <view
            v-for="(line, index) in layout"
            :key="line.key"
            :class="['cashier__row', { 'cashier__row--line': index > 0 }]"
          >
            <template v-if="line.key === 'order-no'">
              <text class="cashier__row-label">订单号</text>
              <text class="cashier__row-value" data-testid="cashier-order-no">{{ order.orderNo }}</text>
              <button class="cashier__copy" data-testid="cashier-copy" @click="copyOrderNo">
                <text :class="copied ? 'cashier__copied' : 'cashier__copy-text'">{{ copied ? '已复制' : '复制' }}</text>
              </button>
            </template>
            <template v-else-if="line.key === 'payee'">
              <text class="cashier__row-label">收款方</text>
              <text class="cashier__row-value">
                <text data-testid="cashier-payee">{{ order.merchant.name }}</text>
                <text v-if="payeeSuffix">{{ payeeSuffix }}</text>
              </text>
            </template>
            <template v-else-if="'label' in line">
              <text class="cashier__row-label">{{ line.label }}</text>
              <text class="cashier__row-value cashier__row-value--goods" :data-testid="`cashier-${line.key}`">{{ line.value }}</text>
            </template>
          </view>
        </view>

        <view v-if="!closed" class="cashier__card cashier__methods">
          <text class="cashier__methods-title">支付方式</text>
          <view class="cashier__method" role="radio" aria-checked="true" data-testid="cashier-method">
            <view class="cashier__method-icon">
              <image class="cashier__method-svg" src="/static/images/trade/wallet.svg" mode="aspectFit" />
            </view>
            <text class="cashier__method-name">{{ TRADE_PAYMENT_METHOD_LABEL }}</text>
            <view class="cashier__method-check"><LineIcon name="check" :size="12" color="#ffffff" :stroke-width="3" /></view>
          </view>
          <text class="cashier__methods-note">目前只支持这一种支付方式</text>
        </view>
      </view>

      <view class="cashier__bar">
        <template v-if="!closed">
          <button
            class="cashier__pay"
            data-testid="cashier-pay"
            :disabled="paying"
            @click="pay"
          >
            {{ paying ? '正在发起支付…' : `确认支付 ${amountText}` }}
          </button>
          <button class="cashier__cancel" data-testid="cashier-cancel" :disabled="cancelling || paying" @click="cancel">
            取消支付
          </button>
        </template>
        <button
          v-else-if="sourceOrder && destination"
          class="cashier__primary cashier__primary--wide"
          data-testid="cashier-view-order"
          @click="emit('viewOrder', destination)"
        >
          {{ orderLinkLabel(order) }}
        </button>
        <view v-else-if="destination" class="cashier__bar-pair">
          <button class="cashier__secondary" data-testid="cashier-view-order" @click="emit('viewOrder', destination)">查看订单</button>
          <button
            class="cashier__primary"
            data-testid="cashier-rebuy"
            @click="emit('rebuy', order.lines[0]?.productId ?? null)"
          >
            重新选购
          </button>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Cashier (390 wide) with its light theme. */
.cashier {
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

.cashier__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.cashier__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.cashier__skeleton--short {
  height: 132px;
}

.cashier__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.cashier__state-title {
  font-size: 16px;
  font-weight: 700;
}

.cashier__state-text {
  color: #738096;
  font-size: 13px;
}

.cashier__state-button {
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

.cashier__card {
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
}

.cashier__due {
  display: flex;
  padding: 24px 16px 20px;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.cashier__due-label {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.cashier__due-amount {
  color: #e85400;
  font-size: 36px;
  font-weight: 700;
  line-height: 44px;
}

.cashier__due-note {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.cashier__due-time {
  margin-top: 4px;
  padding: 4px 10px;
  border-radius: 999px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.cashier__closed {
  display: flex;
  padding: 28px 16px 24px;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
}

.cashier__closed-icon {
  display: flex;
  width: 56px;
  height: 56px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef2f8;
}

.cashier__closed-svg {
  width: 28px;
  height: 28px;
}

.cashier__method-svg {
  width: 20px;
  height: 20px;
}

.cashier__closed-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.cashier__closed-text {
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.cashier__closed-note {
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.cashier__rows {
  display: flex;
  padding: 0 14px;
  flex-direction: column;
}

.cashier__row {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  line-height: 20px;
}

.cashier__row--line {
  border-top: 1px solid #f0f1f3;
}

.cashier__row-label {
  width: 56px;
  flex: none;
  color: #627187;
}

.cashier__row-value {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
}

.cashier__row-value--goods {
  padding: 12px 0;
}

.cashier__copy {
  min-height: 44px;
  flex: none;
  margin-right: -8px;
  padding: 0 8px;
  font-size: 13px;
  line-height: 44px;
}

.cashier__copy-text {
  color: #ba470b;
}

.cashier__copied {
  color: #11804a;
}

.cashier__methods {
  display: flex;
  padding: 0 14px 12px;
  flex-direction: column;
}

.cashier__methods-title {
  display: flex;
  min-height: 44px;
  align-items: center;
  font-size: 17px;
  font-weight: 700;
  line-height: 44px;
}

.cashier__method {
  display: flex;
  min-height: 56px;
  padding: 0 12px;
  align-items: center;
  gap: 10px;
  border: 1.5px solid #ff6a00;
  border-radius: 10px;
  background: #fffaf5;
  box-sizing: border-box;
}

.cashier__method-icon {
  display: flex;
  width: 32px;
  height: 32px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #fff0e3;
}

.cashier__method-name {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.cashier__method-check {
  display: flex;
  width: 20px;
  height: 20px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #ff6a00;
}

.cashier__methods-note {
  margin-top: 8px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.cashier__bar {
  position: sticky;
  bottom: 0;
  display: flex;
  flex: none;
  flex-direction: column;
  padding: 10px 16px max(20px, env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: 0 -4px 18px rgba(38, 59, 98, 0.06);
}

.cashier__pay {
  height: 46px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
}

.cashier__pay[disabled] {
  color: #ffffff;
  background: #ffb27a;
}

.cashier__cancel {
  min-height: 44px;
  margin-top: 4px;
  padding: 0 16px;
  align-self: center;
  color: #627187;
  font-size: 14px;
  line-height: 44px;
}

.cashier__cancel[disabled] {
  color: #91a0b4;
  background: transparent;
}

.cashier__bar-pair {
  display: flex;
  gap: 10px;
}

.cashier__secondary,
.cashier__primary {
  height: 46px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
  box-sizing: border-box;
}

.cashier__secondary {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
}

.cashier__primary {
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  font-weight: 500;
  line-height: 46px;
}

/* A sign-up's closed cashier has one way on: its own ticket. */
.cashier__primary--wide {
  width: 100%;
  flex: none;
}
</style>
