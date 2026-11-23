<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { TRADE_CHANNEL_UNAVAILABLE_TEXT } from '../../application/order-commands'
import {
  TRADE_PAYMENT_METHOD_LABEL,
  describeClosedOrder,
  formatTradeAmount,
  formatTradeTime,
} from '../../application/order-view'
import {
  failedNote,
  orderDestination,
  orderLinkLabel,
  paidFrame,
  type DescribeSourceOrder,
  type SourceOrderView,
  type TradeOrderDestination,
} from '../../application/order-source'
import { checkPaymentUntilSettled, paymentCheckResult } from '../../application/payment-check'
import { isUnauthorizedFailure, readTradeFailure } from '../../application/start-payment'
import { tradeApi } from '../../data/trade-api-client'
import type { TradeOrder } from '../../domain/trade-order'
import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import IconTile from '@/shared/ui/IconTile.vue'

import TradePageHeader from '../shared/TradePageHeader.vue'
import { copyText, wait } from '../shared/trade-presentation'

const props = defineProps<{
  orderId: string
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
  // S4-1: what the business module behind the order adds (the page host asks it); optional.
  describeSource?: DescribeSourceOrder
}>()

const emit = defineEmits<{
  // The order's own page when the order is known: back from a sign-up's result is its ticket.
  back: [destination: TradeOrderDestination | null]
  login: []
  home: []
  // The goods order, or the ticket of the enrolment this order pays for (查看凭证).
  viewOrder: [destination: TradeOrderDestination]
  repay: []
}>()

type ResultState =
  | { kind: 'loading' }
  | { kind: 'ready'; order: TradeOrder }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<ResultState>({ kind: 'loading' })
// True while the page is still asking the server; an unpaid order shows 确认中 until it stops.
const checking = ref(false)
const copied = ref(false)
let runVersion = 0
let alive = true

const order = computed(() => (state.value.kind === 'ready' ? state.value.order : null))
const result = computed(() => (order.value ? paymentCheckResult(order.value) : null))
const destination = computed(() => (order.value ? orderDestination(order.value) : null))
const linkLabel = computed(() => (order.value ? orderLinkLabel(order.value) : '查看订单'))
const sourceView = ref<SourceOrderView | null>(null)
const paid = computed(() => {
  const fallback = order.value ? paidFrame(order.value) : { title: '已支付', note: null }
  return {
    title: sourceView.value?.paidTitle ?? fallback.title,
    note: sourceView.value?.paidNote ?? fallback.note,
  }
})
const failedLine = computed(() => (order.value ? sourceView.value?.failedNote ?? failedNote(order.value) : ''))
const paidNext = computed(() => sourceView.value?.next ?? null)
const paidBack = computed(() => sourceView.value?.back ?? null)
let describedOrderId = ''

function describe(current: TradeOrder): void {
  // Once per order, and again once it is paid (the remaining fees change).
  const marker = `${current.orderId}:${current.status}`
  if (!props.describeSource || describedOrderId === marker) return
  describedOrderId = marker
  props.describeSource(current).then((view) => { sourceView.value = view }).catch(() => undefined)
}

// 支付遇到问题 (board PayResult): a conversation with platform support about this order.
function askForHelp(): void {
  const current = order.value
  if (!current) return
  const first = current.lines[0]
  openSupportChat(
    { kind: 'PLATFORM' },
    current.source === 'MALL'
      ? { type: 'MALL_ORDER', refId: current.orderId, title: first?.productName ?? '商品订单', subtitle: `订单 ${current.orderNo}` }
      : null,
  )
}

function viewOrder(): void {
  if (destination.value) emit('viewOrder', destination.value)
}

const view = computed<'PENDING' | 'PAID' | 'FAILED' | 'CLOSED' | null>(() => {
  if (!result.value) return null
  if (result.value === 'PAID') return 'PAID'
  if (result.value === 'CLOSED') return 'CLOSED'
  return checking.value ? 'PENDING' : 'FAILED'
})

// Asks the server to query WeChat; while still unpaid, again every 2 seconds, up to 5 more times.
// `retries` 0 is the single check of the 刷新结果 button.
async function runCheck(retries?: number): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.orderId) {
    state.value = { kind: 'missing' }
    return
  }
  const run = ++runVersion
  checking.value = true
  // The query to WeChat can take a while; the plain read fills the order rows under 确认中 meanwhile.
  if (state.value.kind === 'loading') {
    tradeApi.read(token, props.orderId).then((read) => {
      if (run === runVersion && state.value.kind === 'loading') state.value = { kind: 'ready', order: read }
    }).catch(() => undefined)
  }
  try {
    await checkPaymentUntilSettled({
      check: async () => {
        const checked = await tradeApi.checkPayment(token, props.orderId)
        if (run === runVersion) {
          state.value = { kind: 'ready', order: checked }
          describe(checked)
        }
        return checked
      },
      wait,
      isCurrent: () => alive && run === runVersion,
      retries,
    })
  } catch (error) {
    if (run !== runVersion) return
    const failure = readTradeFailure(error)
    if (isUnauthorizedFailure(failure)) {
      emit('login')
      return
    }
    if (failure?.status === 404 || failure?.status === 400) {
      state.value = { kind: 'missing' }
    } else if (failure?.status === 503) {
      uni.showToast({ title: TRADE_CHANNEL_UNAVAILABLE_TEXT, icon: 'none' })
      if (state.value.kind !== 'ready') state.value = { kind: 'error' }
    } else if (state.value.kind !== 'ready') {
      state.value = { kind: 'error' }
    }
  } finally {
    if (run === runVersion) checking.value = false
  }
}

function copyOrderNo(): void {
  if (order.value) copyText(order.value.orderNo, () => { copied.value = true })
}

watch(() => [props.refreshKey, props.accessToken, props.orderId], () => { void runCheck() }, { immediate: true })

onBeforeUnmount(() => {
  alive = false
})
</script>

<template>
  <view class="pay-result" data-testid="pay-result">
    <TradePageHeader title="支付结果" test-id="pay-result" @back="emit('back', destination)" />

    <view v-if="state.kind === 'loading'" class="pay-result__body">
      <view class="pay-result__card pay-result__status" role="status" data-testid="pay-result-pending">
        <IconTile tone="blue" :size="64">
          <image class="pay-result__spinner" src="/static/images/trade/pending.svg" mode="aspectFit" />
        </IconTile>
        <text class="pay-result__title">支付结果确认中</text>
        <text class="pay-result__text">正在向微信支付确认结果，请勿重复支付</text>
      </view>
    </view>

    <view v-else-if="state.kind === 'missing'" class="pay-result__state" data-testid="pay-result-missing">
      <text class="pay-result__state-title">没有找到这笔订单</text>
      <button class="pay-result__state-button" @click="emit('home')">返回首页</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="pay-result__state" data-testid="pay-result-error">
      <text class="pay-result__state-title">暂时查不到支付结果</text>
      <text class="pay-result__state-text">请检查网络后重试，不要重复支付</text>
      <button class="pay-result__state-button" data-testid="pay-result-retry" @click="runCheck(0)">重试</button>
    </view>

    <view v-else-if="order" class="pay-result__body">
      <view v-if="view === 'PENDING'" class="pay-result__card pay-result__status" role="status" data-testid="pay-result-pending">
        <IconTile tone="blue" :size="64">
          <image class="pay-result__spinner" src="/static/images/trade/pending.svg" mode="aspectFit" />
        </IconTile>
        <text class="pay-result__title">支付结果确认中</text>
        <text class="pay-result__text">正在向微信支付确认结果，请勿重复支付</text>
        <view class="pay-result__actions">
          <button
            v-if="SUPPORT_PREVIEW_ENABLED"
            class="pay-result__secondary"
            data-testid="pay-result-help"
            @click="askForHelp"
          >
            支付遇到问题
          </button>
          <button
            class="pay-result__primary"
            data-testid="pay-result-pending-refresh"
            @click="runCheck(0)"
          >
            刷新结果
          </button>
        </view>
      </view>

      <view v-else-if="view === 'PAID'" class="pay-result__card pay-result__status" role="status" data-testid="pay-result-paid">
        <IconTile tone="green" :size="64">
          <image class="pay-result__glyph" src="/static/images/trade/check-green.svg" mode="aspectFit" />
        </IconTile>
        <text class="pay-result__title" data-testid="pay-result-paid-title">{{ paid.title }}</text>
        <text class="pay-result__amount" data-testid="pay-result-amount">{{ formatTradeAmount(order.amounts.paidMinor) }}</text>
        <text v-if="paid.note" class="pay-result__note" data-testid="pay-result-paid-note">{{ paid.note }}</text>
        <view v-if="paidNext" class="pay-result__actions">
          <button
            v-if="paidBack"
            class="pay-result__secondary"
            data-testid="pay-result-other"
            @click="emit('viewOrder', paidBack.destination)"
          >
            {{ paidBack.label }}
          </button>
          <button
            class="pay-result__primary"
            data-testid="pay-result-next"
            @click="emit('viewOrder', paidNext.destination)"
          >
            {{ paidNext.label }}
          </button>
        </view>
        <view v-else class="pay-result__actions">
          <button class="pay-result__secondary" data-testid="pay-result-home" @click="emit('home')">返回首页</button>
          <button class="pay-result__primary" data-testid="pay-result-view-order" @click="viewOrder">{{ linkLabel }}</button>
        </view>
      </view>

      <view v-else-if="view === 'CLOSED'" class="pay-result__card pay-result__status" role="status" data-testid="pay-result-closed">
        <IconTile tone="blue" bg="#eef2f8" :size="64">
          <image class="pay-result__glyph" src="/static/images/trade/clock-grey.svg" mode="aspectFit" />
        </IconTile>
        <text class="pay-result__title" data-testid="pay-result-closed-title">{{ describeClosedOrder(order).title }}</text>
        <text class="pay-result__text">{{ describeClosedOrder(order).line }}</text>
        <view class="pay-result__actions">
          <button class="pay-result__secondary" @click="emit('home')">返回首页</button>
          <button class="pay-result__primary" data-testid="pay-result-view-order" @click="viewOrder">{{ linkLabel }}</button>
        </view>
      </view>

      <view v-else class="pay-result__card pay-result__status" role="status" data-testid="pay-result-failed">
        <IconTile tone="orange" bg="#fdecec" :size="64">
          <image class="pay-result__glyph" src="/static/images/trade/alert-red.svg" mode="aspectFit" />
        </IconTile>
        <text class="pay-result__title">支付失败</text>
        <text class="pay-result__text">支付未成功，如已扣款将原路退回</text>
        <text class="pay-result__note">{{ failedLine }}</text>
        <view class="pay-result__actions">
          <button class="pay-result__secondary" data-testid="pay-result-view-order" @click="viewOrder">{{ linkLabel }}</button>
          <button class="pay-result__primary" data-testid="pay-result-repay" @click="emit('repay')">重新支付</button>
        </view>
        <button class="pay-result__refresh" data-testid="pay-result-refresh" @click="runCheck(0)">刷新结果</button>
      </view>

      <view class="pay-result__card pay-result__rows">
        <view class="pay-result__row">
          <text class="pay-result__row-label">订单号</text>
          <text class="pay-result__row-value" data-testid="pay-result-order-no">{{ order.orderNo }}</text>
          <button class="pay-result__copy" @click="copyOrderNo">
            <text :class="copied ? 'pay-result__copied' : 'pay-result__copy-text'">{{ copied ? '已复制' : '复制' }}</text>
          </button>
        </view>
        <view v-if="view !== 'PAID'" class="pay-result__row pay-result__row--line">
          <text class="pay-result__row-label">应付金额</text>
          <text class="pay-result__row-value">{{ formatTradeAmount(order.amounts.payableMinor) }}</text>
        </view>
        <view class="pay-result__row pay-result__row--line">
          <text class="pay-result__row-label">收款方</text>
          <text class="pay-result__row-value" data-testid="pay-result-payee">{{ order.merchant.name }}</text>
        </view>
        <view
          v-for="row in sourceView?.resultRows ?? []"
          :key="row.key"
          class="pay-result__row pay-result__row--line"
        >
          <text class="pay-result__row-label">{{ row.label }}</text>
          <text class="pay-result__row-value" :data-testid="`pay-result-${row.key}`">{{ row.value }}</text>
        </view>
        <view v-if="view === 'PAID' && order.paidAt" class="pay-result__row pay-result__row--line">
          <text class="pay-result__row-label">支付时间</text>
          <text class="pay-result__row-value" data-testid="pay-result-paid-at">{{ formatTradeTime(order.paidAt) }}</text>
        </view>
        <view class="pay-result__row pay-result__row--line">
          <text class="pay-result__row-label">支付方式</text>
          <text class="pay-result__row-value">{{ TRADE_PAYMENT_METHOD_LABEL }}</text>
        </view>
      </view>

      <view v-if="view === 'PAID'" class="pay-result__footnote">
        <image class="pay-result__footnote-icon" src="/static/images/trade/info.svg" mode="aspectFit" />
        <text>支付结果以微信支付回执为准</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board PayResult (390 wide) with its light theme. */
.pay-result {
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

.pay-result__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.pay-result__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.pay-result__state-title {
  font-size: 16px;
  font-weight: 700;
}

.pay-result__state-text {
  color: #738096;
  font-size: 13px;
}

.pay-result__state-button {
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

.pay-result__card {
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
}

.pay-result__status {
  display: flex;
  padding: 28px 16px 20px;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
}

.pay-result__icon {
  display: flex;
  width: 64px;
  height: 64px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}

.pay-result__icon--pending {
  background: #fff0e3;
}

.pay-result__icon--paid {
  background: #e2f6ed;
}

.pay-result__icon--failed {
  background: #fdecec;
}

.pay-result__icon--closed {
  background: #eef2f8;
}

.pay-result__spinner {
  width: 36px;
  height: 36px;
  animation: pay-result-spin 1s linear infinite;
}

.pay-result__glyph {
  width: 34px;
  height: 34px;
}

@keyframes pay-result-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .pay-result__spinner {
    animation: none;
  }
}

.pay-result__title {
  margin-top: 6px;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.pay-result__text {
  color: #536178;
  font-size: 14px;
  line-height: 20px;
}

.pay-result__note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.pay-result__amount {
  color: #e85400;
  font-size: 32px;
  font-weight: 700;
  line-height: 40px;
}

.pay-result__actions {
  display: flex;
  margin-top: 12px;
  align-self: stretch;
  gap: 10px;
}

.pay-result__secondary,
.pay-result__primary {
  min-width: 0;
  height: 44px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  white-space: nowrap;
  box-sizing: border-box;
}

.pay-result__secondary {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
  line-height: 42px;
}

.pay-result__primary {
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  font-weight: 500;
  line-height: 44px;
}

.pay-result__refresh {
  min-height: 44px;
  padding: 0 16px;
  color: #ba470b;
  font-size: 14px;
  line-height: 44px;
}

.pay-result__rows {
  display: flex;
  padding: 0 14px;
  flex-direction: column;
}

.pay-result__row {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  line-height: 20px;
}

.pay-result__row--line {
  border-top: 1px solid #f0f1f3;
}

.pay-result__row-label {
  width: 76px;
  flex: none;
  color: #627187;
}

.pay-result__row-value {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
}

.pay-result__copy {
  min-height: 44px;
  flex: none;
  margin-right: -8px;
  padding: 0 8px;
  font-size: 13px;
  line-height: 44px;
}

.pay-result__copy-text {
  color: #ba470b;
}

.pay-result__copied {
  color: #11804a;
}

.pay-result__footnote {
  display: flex;
  padding: 0 4px;
  align-items: center;
  gap: 6px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.pay-result__footnote-icon {
  width: 14px;
  height: 14px;
  flex: none;
}
</style>
