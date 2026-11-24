<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import {
  TRADE_COMMAND_CONFIRMS,
  classifyOrderCommandFailure,
} from '../../application/order-commands'
import {
  TRADE_DELIVERY_LABELS,
  TRADE_MERCHANT_KIND_LABELS,
  TRADE_PAYMENT_METHOD_LABEL,
  TRADE_REFUND_STATUS_LABELS,
  describeOrderHeader,
  describePayment,
  describeRefund,
  detailActions,
  formatTradeAmount,
  formatTradeTime,
  type TradeOrderAction,
} from '../../application/order-view'
import { isSourceOrder, orderDestination, payerRow, type TradeOrderDestination } from '../../application/order-source'
import { isUnauthorizedFailure, readTradeFailure } from '../../application/start-payment'
import { createTradeKey } from '../../application/trade-keys'
import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { tradeApi } from '../../data/trade-api-client'
import { isOpenAftersale } from '../../domain/aftersale'
import type { TradeOrder } from '../../domain/trade-order'
import TradePartiesCard from '../shared/TradePartiesCard.vue'
import { aftersaleTerms, goodsParties } from '../shared/trade-parties'
import { confirmDialog, copyText, coverOfLine } from '../shared/trade-presentation'

const props = defineProps<{
  orderId: string
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  pay: []
  // The order is not a goods order (a paid sign-up): its own page shows it instead.
  elsewhere: [destination: TradeOrderDestination]
  // 申请售后 / 查看售后 (S4-2, board OrderGoods): the application form, or the latest case.
  applyAftersale: []
  openAftersale: [aftersaleId: string]
}>()

type DetailState =
  | { kind: 'loading' }
  | { kind: 'ready'; order: TradeOrder }
  | { kind: 'missing' }
  | { kind: 'error' }

const ACTION_LABELS: Record<TradeOrderAction, string> = {
  CANCEL: '取消订单',
  CANCEL_AND_REFUND: '取消订单',
  PAY: '去支付',
  CONFIRM_RECEIPT: '确认收货',
  VIEW: '查看订单',
}

const state = ref<DetailState>({ kind: 'loading' })
const now = ref(Date.now())
const busy = ref(false)
const copied = ref<'ORDER' | 'WAYBILL' | 'TXN' | null>(null)
let requestVersion = 0
let timer: ReturnType<typeof setInterval> | null = null

const order = computed(() => (state.value.kind === 'ready' ? state.value.order : null))

// 联系商家 (step G1, preview builds): the seller's conversation with a card about this order.
function contactMerchant(): void {
  const current = order.value
  if (!current) return
  const first = current.lines[0]
  const more = current.lines.length > 1 ? ` 等 ${current.lines.length} 件` : ''
  openSupportChat(
    { kind: 'MERCHANT', organizationId: current.merchant.organizationId },
    { type: 'MALL_ORDER', refId: current.orderId, title: `${first?.productName ?? '商品订单'}${more}`, subtitle: `订单 ${current.orderNo}` },
  )
}
const header = computed(() => (order.value ? describeOrderHeader(order.value, now.value) : null))
const actions = computed(() => (order.value ? detailActions(order.value, now.value) : []))
const payment = computed(() => (order.value ? describePayment(order.value) : null))
const refundSummary = computed(() => (order.value ? describeRefund(order.value) : null))
const delivery = computed(() => order.value?.delivery ?? null)
// Only while the goods wait to be picked up; after a refund the server still sends the code.
const pickupCode = computed(() => (order.value?.status === 'PAID' ? order.value.delivery?.pickupCode ?? null : null))
const shipped = computed(() => Boolean(delivery.value?.shippedAt && delivery.value.mode === 'EXPRESS'))
const parties = computed(() => {
  const current = order.value
  if (!current) return []
  const rows = goodsParties({
    merchantName: current.merchant.name,
    merchantKindLabel: current.merchant.kind ? TRADE_MERCHANT_KIND_LABELS[current.merchant.kind] : null,
    fulfilledFrom: current.parties.fulfilledFrom,
    invoiceIssuer: current.parties.invoiceIssuer,
    aftersaleParty: current.parties.aftersaleParty,
  })
  // S5-7: an order placed as an enterprise names it as the payer.
  const payer = payerRow(current)
  return payer ? [...rows, { label: '付款主体', value: payer.value, testId: 'goods-order-payer' }] : rows
})
// 售后条款 (board OrderGoods): the order does not carry the products' 七天无理由 mark, so the first
// term says it follows the seller's rules, as the board does.
const terms = computed(() => (order.value
  ? aftersaleTerms({ noReasonReturn: null, aftersaleParty: order.value.parties.aftersaleParty ?? order.value.merchant.name })
  : []))
// The bottom bar's after-sales button: 查看售后 while a case is still open, else 申请售后 when the
// server says one may be applied for now; nothing otherwise.
const aftersaleButton = computed<'VIEW' | 'APPLY' | null>(() => {
  const entry = order.value?.aftersale
  if (!entry) return null
  if (entry.latestAftersaleId && isOpenAftersale(entry.latestStatus)) return 'VIEW'
  return entry.applicable ? 'APPLY' : null
})

function runAftersaleButton(): void {
  const entry = order.value?.aftersale
  if (!entry) return
  if (aftersaleButton.value === 'VIEW' && entry.latestAftersaleId) emit('openAftersale', entry.latestAftersaleId)
  else if (aftersaleButton.value === 'APPLY') emit('applyAftersale')
}

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
    if (isSourceOrder(loaded)) {
      emit('elsewhere', orderDestination(loaded))
      return
    }
    now.value = Date.now()
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

async function runAction(action: TradeOrderAction): Promise<void> {
  if (action === 'PAY') {
    emit('pay')
    return
  }
  const token = props.accessToken
  const current = order.value
  if (!token || !current || busy.value || action === 'VIEW') return
  if (!(await confirmDialog(TRADE_COMMAND_CONFIRMS[action]))) return
  busy.value = true
  try {
    const command = { idempotencyKey: createTradeKey(), version: current.version }
    const updated = action === 'CONFIRM_RECEIPT'
      ? await tradeApi.confirmReceipt(token, current.orderId, command)
      : await tradeApi.cancel(token, current.orderId, command)
    state.value = { kind: 'ready', order: updated }
    const done = action === 'CONFIRM_RECEIPT' ? '已确认收货' : action === 'CANCEL_AND_REFUND' ? '已申请退款' : '订单已取消'
    uni.showToast({ title: done, icon: 'none' })
  } catch (error) {
    const failure = classifyOrderCommandFailure(error)
    if (failure.kind === 'UNAUTHORIZED') {
      emit('login')
      return
    }
    uni.showToast({ title: failure.message, icon: 'none' })
    if (failure.kind === 'ORDER_CHANGED') void load()
  } finally {
    busy.value = false
  }
}

function copy(kind: 'ORDER' | 'WAYBILL' | 'TXN', text: string | null): void {
  if (text) copyText(text, () => { copied.value = kind })
}

watch(() => [props.refreshKey, props.accessToken, props.orderId], () => { void load() }, { immediate: true })

onMounted(() => {
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view class="goods-order" data-testid="goods-order">
    <view class="goods-order__head">
      <view class="goods-order__nav">
        <button class="goods-order__back" data-testid="goods-order-back" aria-label="返回" @click="emit('back')">
          <LineIcon name="back" :size="22" color="#ffffff" :stroke-width="2" />
        </button>
        <text class="goods-order__nav-title">订单详情</text>
        <view class="goods-order__nav-spacer" />
      </view>
      <view v-if="header" class="goods-order__status">
        <text class="goods-order__status-title" data-testid="goods-order-status-title">{{ header.title }}</text>
        <text class="goods-order__status-line" data-testid="goods-order-status-line">{{ header.line }}</text>
      </view>
    </view>

    <view v-if="state.kind === 'loading'" class="goods-order__body" data-testid="goods-order-loading" aria-label="正在加载">
      <view class="goods-order__skeleton" />
      <view class="goods-order__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="goods-order__state" data-testid="goods-order-missing">
      <text class="goods-order__state-title">没有找到这笔订单</text>
      <button class="goods-order__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="goods-order__state" data-testid="goods-order-error">
      <text class="goods-order__state-title">加载失败</text>
      <text class="goods-order__state-text">请检查网络后重试</text>
      <button class="goods-order__state-button" data-testid="goods-order-retry" @click="load">重试</button>
    </view>

    <template v-else-if="order && delivery">
      <view class="goods-order__body">
        <view class="goods-order__card goods-order__delivery" data-testid="goods-order-delivery">
          <template v-if="delivery.mode === 'EXPRESS'">
            <view v-if="shipped" class="goods-order__block" data-testid="goods-order-shipment">
              <image class="goods-order__block-icon" src="/static/images/trade/truck-green-thin.svg" mode="aspectFit" />
              <view class="goods-order__block-text goods-order__block-text--start">
                <text class="goods-order__block-title">已发货 · {{ delivery.carrierName }}</text>
                <text class="goods-order__block-line">运单号 {{ delivery.trackingNo }}</text>
                <text class="goods-order__block-note">{{ formatTradeTime(delivery.shippedAt as string) }} 发货</text>
                <button
                  class="goods-order__waybill"
                  data-testid="goods-order-copy-waybill"
                  @click="copy('WAYBILL', delivery.trackingNo)"
                >
                  {{ copied === 'WAYBILL' ? '已复制' : '复制单号去承运商查询' }}
                </button>
              </view>
            </view>
            <view v-if="shipped" class="goods-order__divider" />
            <view class="goods-order__block">
              <image class="goods-order__block-icon" src="/static/images/trade/location.svg" mode="aspectFit" />
              <view class="goods-order__block-text goods-order__block-text--tight">
                <text class="goods-order__address-name" data-testid="goods-order-recipient">
                  {{ delivery.recipientName }} {{ delivery.phoneMasked }}
                </text>
                <text class="goods-order__block-line">{{ delivery.addressLine }}</text>
              </view>
            </view>
          </template>
          <template v-else>
            <view class="goods-order__block">
              <image class="goods-order__block-icon" src="/static/images/trade/store-green.svg" mode="aspectFit" />
              <view class="goods-order__block-text goods-order__block-text--tight">
                <text class="goods-order__block-title">{{ TRADE_DELIVERY_LABELS.PICKUP }}</text>
                <text class="goods-order__block-line" data-testid="goods-order-pickup-point">自提点 {{ delivery.pickupPoint }}</text>
              </view>
            </view>
            <view v-if="pickupCode" class="goods-order__pickup" data-testid="goods-order-pickup-code">
              <text class="goods-order__pickup-label">取货码</text>
              <text class="goods-order__pickup-code">{{ pickupCode }}</text>
              <text class="goods-order__pickup-note">到自提点后出示给商家，取货码请勿告诉他人</text>
            </view>
          </template>
        </view>

        <view class="goods-order__card goods-order__goods">
          <view class="goods-order__merchant">
            <image class="goods-order__merchant-icon" src="/static/images/trade/store.svg" mode="aspectFit" />
            <text class="goods-order__merchant-name" data-testid="goods-order-merchant">{{ order.merchant.name }}</text>
            <text v-if="order.merchant.kind" class="goods-order__merchant-kind">{{ TRADE_MERCHANT_KIND_LABELS[order.merchant.kind] }}</text>
          </view>
          <view v-for="line in order.lines" :key="line.lineId" class="goods-order__line">
            <image v-if="coverOfLine(line)" class="goods-order__cover" :src="coverOfLine(line) ?? ''" mode="aspectFit" />
            <view v-else class="goods-order__cover" />
            <view class="goods-order__line-info">
              <view class="goods-order__line-top">
                <text class="goods-order__line-name">{{ line.productName }}</text>
                <text class="goods-order__line-amount">{{ formatTradeAmount(line.unitAmountMinor) }}</text>
              </view>
              <text class="goods-order__line-spec">{{ line.skuName }}</text>
              <text class="goods-order__line-spec">× {{ line.quantity }}</text>
            </view>
          </view>
        </view>

        <TradePartiesCard
          test-id="goods-order-parties"
          :rows="parties"
          footnote="发票由销售方开具，可联系销售方申请"
        />

        <view class="goods-order__card goods-order__section">
          <text class="goods-order__section-title">金额明细</text>
          <view class="goods-order__money"><text class="goods-order__label">商品金额</text><text>{{ formatTradeAmount(order.amounts.goodsMinor) }}</text></view>
          <view class="goods-order__money"><text class="goods-order__label">运费</text><text>{{ formatTradeAmount(order.amounts.shippingMinor) }}</text></view>
          <view class="goods-order__money goods-order__money--total">
            <text class="goods-order__label">{{ order.paidAt ? '实付' : '应付' }}</text>
            <text class="goods-order__payable" data-testid="goods-order-payable">{{ formatTradeAmount(order.amounts.payableMinor) }}</text>
          </view>
          <view v-if="order.amounts.refundedMinor > 0" class="goods-order__money">
            <text class="goods-order__label">已退</text>
            <text class="goods-order__refunded" data-testid="goods-order-refunded">{{ formatTradeAmount(order.amounts.refundedMinor) }}</text>
          </view>
        </view>

        <view v-if="order.refunds.length > 0" class="goods-order__card goods-order__section" data-testid="goods-order-refunds">
          <text class="goods-order__section-title">退款记录</text>
          <view v-for="refund in order.refunds" :key="refund.refundId" class="goods-order__refund">
            <view class="goods-order__refund-top">
              <text class="goods-order__refund-amount">{{ formatTradeAmount(refund.amountMinor) }}</text>
              <text
                :class="['goods-order__refund-status', `goods-order__refund-status--${refund.status.toLowerCase()}`]"
                data-testid="goods-order-refund-status"
              >
                {{ TRADE_REFUND_STATUS_LABELS[refund.status] }}
              </text>
            </view>
            <text class="goods-order__refund-line">{{ refund.reason }}</text>
            <text class="goods-order__refund-line">
              {{ formatTradeTime(refund.requestedAt) }} 申请<template v-if="refund.succeededAt"> · {{ formatTradeTime(refund.succeededAt) }} 原路退回</template>
            </text>
          </view>
        </view>

        <view v-if="order.paidAt" class="goods-order__card goods-order__section" data-testid="goods-order-payment">
          <text class="goods-order__section-title">支付信息</text>
          <view class="goods-order__info"><text class="goods-order__info-label">支付方式</text><text>{{ TRADE_PAYMENT_METHOD_LABEL }}</text></view>
          <view v-if="order.channelTransactionId" class="goods-order__info" data-testid="goods-order-transaction">
            <text class="goods-order__info-label">渠道交易号</text>
            <text class="goods-order__order-no goods-order__transaction" data-testid="goods-order-transaction-id">{{ order.channelTransactionId }}</text>
            <button class="goods-order__copy" data-testid="goods-order-copy-transaction" @click="copy('TXN', order.channelTransactionId)">
              {{ copied === 'TXN' ? '已复制' : '复制' }}
            </button>
          </view>
          <view class="goods-order__info"><text class="goods-order__info-label">支付时间</text><text>{{ formatTradeTime(order.paidAt) }}</text></view>
          <view class="goods-order__info">
            <text class="goods-order__info-label">支付状态</text>
            <text class="goods-order__paid">
              <text data-testid="goods-order-payment-status">{{ payment }}</text>
              <!-- The status only comes from a verified WeChat notification or the server's own query. -->
              <text v-if="payment === '已支付'" data-testid="goods-order-payment-verified"> · 渠道回执已核验</text>
            </text>
          </view>
          <template v-if="refundSummary">
            <view class="goods-order__info">
              <text class="goods-order__info-label">退款状态</text>
              <text data-testid="goods-order-refund-summary">{{ refundSummary.status }}</text>
            </view>
            <view v-if="refundSummary.arrival" class="goods-order__info">
              <text class="goods-order__info-label">到账时间</text>
              <text data-testid="goods-order-refund-arrival">{{ refundSummary.arrival }}</text>
            </view>
          </template>
        </view>

        <view class="goods-order__card goods-order__section">
          <text class="goods-order__section-title">订单信息</text>
          <view class="goods-order__info">
            <text class="goods-order__info-label">订单号</text>
            <text class="goods-order__order-no" data-testid="goods-order-no">{{ order.orderNo }}</text>
            <button class="goods-order__copy" data-testid="goods-order-copy" @click="copy('ORDER', order.orderNo)">
              {{ copied === 'ORDER' ? '已复制' : '复制' }}
            </button>
          </view>
          <view class="goods-order__info"><text class="goods-order__info-label">下单时间</text><text data-testid="goods-order-created-at">{{ formatTradeTime(order.createdAt) }}</text></view>
          <view v-if="delivery.shippedAt" class="goods-order__info">
            <text class="goods-order__info-label">发货时间</text><text>{{ formatTradeTime(delivery.shippedAt) }}</text>
          </view>
          <view v-if="order.buyerNote" class="goods-order__info goods-order__info--top">
            <text class="goods-order__info-label">留言</text><text class="goods-order__note">{{ order.buyerNote }}</text>
          </view>
        </view>

        <view v-if="order.paidAt" class="goods-order__card goods-order__terms" data-testid="goods-order-terms">
          <text class="goods-order__section-title goods-order__terms-title">售后条款</text>
          <view v-for="term in terms" :key="term" class="goods-order__term">
            <LineIcon name="check" :size="16" color="#159a70" :stroke-width="2.2" />
            <text class="goods-order__term-text">{{ term }}</text>
          </view>
          <button
            v-if="order.aftersale.latestAftersaleId"
            class="goods-order__terms-link"
            data-testid="goods-order-view-aftersale"
            @click="emit('openAftersale', order.aftersale.latestAftersaleId)"
          >
            查看售后 ›
          </button>
        </view>
      </view>

      <view v-if="actions.length > 0 || SUPPORT_PREVIEW_ENABLED || aftersaleButton" class="goods-order__bar">
        <button
          v-if="SUPPORT_PREVIEW_ENABLED"
          class="goods-order__contact"
          data-testid="goods-order-contact"
          @click="contactMerchant"
        >
          <LineIcon name="chat" :size="22" color="#536178" />
          <text>联系商家</text>
        </button>
        <button
          v-if="aftersaleButton"
          class="goods-order__bar-button"
          :data-testid="aftersaleButton === 'VIEW' ? 'goods-order-aftersale-view' : 'goods-order-aftersale-apply'"
          :disabled="busy"
          @click="runAftersaleButton"
        >
          {{ aftersaleButton === 'VIEW' ? '查看售后' : '申请售后' }}
        </button>
        <button
          v-for="action in actions"
          :key="action"
          :class="['goods-order__bar-button', { 'goods-order__bar-button--primary': action === 'PAY' || action === 'CONFIRM_RECEIPT' }]"
          :data-testid="`goods-order-action-${action.toLowerCase()}`"
          :disabled="busy"
          @click="runAction(action)"
        >
          {{ ACTION_LABELS[action] }}
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board OrderGoods (390 wide). */
.goods-order {
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

.goods-order__head {
  padding: calc(var(--status-bar-height) + 8px) 8px 52px;
  color: #ffffff;
  background: linear-gradient(180deg, #ff6a00 0%, #ff8a3d 100%);
}

.goods-order__nav {
  display: flex;
  align-items: center;
}

.goods-order__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.goods-order__nav-title {
  flex: 1;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-align: center;
}

.goods-order__nav-spacer {
  width: 44px;
  height: 44px;
  flex: none;
}

.goods-order__status {
  display: flex;
  padding: 10px 8px 0;
  flex-direction: column;
  gap: 6px;
}

.goods-order__status-title {
  font-size: 24px;
  font-weight: 700;
  line-height: 32px;
}

.goods-order__status-line {
  font-size: 13px;
  font-weight: 500;
  line-height: 19px;
}

.goods-order__body {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  margin-top: -36px;
  padding: 0 16px 16px;
}

.goods-order__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.goods-order__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.goods-order__state-title {
  font-size: 16px;
  font-weight: 700;
}

.goods-order__state-text {
  color: #738096;
  font-size: 13px;
}

.goods-order__state-button {
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

.goods-order__card {
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
}

.goods-order__delivery {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 12px;
}

.goods-order__block {
  display: flex;
  gap: 10px;
}

.goods-order__block-icon {
  width: 24px;
  height: 24px;
  flex: none;
}

.goods-order__block-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.goods-order__block-text--start {
  align-items: flex-start;
}

.goods-order__block-text--tight {
  gap: 2px;
}

.goods-order__block-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.goods-order__block-line {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.goods-order__block-note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.goods-order__waybill {
  height: 36px;
  margin-top: 6px;
  padding: 0 14px;
  border: 1px solid #ffd8b8;
  border-radius: 999px;
  color: #ba470b;
  background: #fffaf5;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.goods-order__divider {
  height: 1px;
  background: #f0f1f3;
}

.goods-order__address-name {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.goods-order__pickup {
  display: flex;
  padding: 12px;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  border-radius: 10px;
  background: #fffaf5;
}

.goods-order__pickup-label {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.goods-order__pickup-code {
  color: #e85400;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: 6px;
  line-height: 40px;
}

.goods-order__pickup-note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.goods-order__goods {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 12px;
}

.goods-order__merchant {
  display: flex;
  align-items: center;
  gap: 6px;
}

.goods-order__merchant-icon {
  width: 18px;
  height: 18px;
  flex: none;
}

.goods-order__merchant-name {
  min-width: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.goods-order__merchant-kind {
  flex: none;
  padding: 0 5px;
  border: 1px solid #e5ebf3;
  border-radius: 4px;
  color: #536178;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.goods-order__line {
  display: flex;
  gap: 12px;
}

.goods-order__cover {
  display: block;
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: 10px;
  background: #f0edff;
}

.goods-order__line-info {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.goods-order__line-top {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.goods-order__line-name {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.goods-order__line-amount {
  flex: none;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.goods-order__line-spec {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.goods-order__section {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
  line-height: 19px;
}

.goods-order__section-title {
  margin-bottom: 2px;
  font-size: 17px;
  font-weight: 700;
  line-height: 22px;
}

.goods-order__money {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.goods-order__label {
  color: #627187;
}

.goods-order__payable {
  color: #e85400;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.goods-order__refunded {
  color: #11804a;
}

.goods-order__refund {
  display: flex;
  padding-top: 8px;
  flex-direction: column;
  gap: 2px;
  border-top: 1px solid #f0f1f3;
}

.goods-order__refund-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.goods-order__refund-amount {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.goods-order__refund-status {
  color: #ba470b;
}

.goods-order__refund-status--succeeded {
  color: #11804a;
}

.goods-order__refund-status--abnormal,
.goods-order__refund-status--closed {
  color: #d64f67;
}

.goods-order__refund-line {
  color: #536178;
}

.goods-order__info {
  display: flex;
  align-items: center;
  gap: 10px;
}

.goods-order__info--top {
  align-items: flex-start;
}

.goods-order__info-label {
  width: 72px;
  flex: none;
  color: #627187;
}

.goods-order__paid {
  color: #11804a;
}

.goods-order__order-no {
  white-space: nowrap;
}

.goods-order__note {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
}

.goods-order__copy {
  height: 36px;
  flex: none;
  margin: -9px -8px -9px auto;
  padding: 0 8px;
  color: #ba470b;
  font-size: 13px;
  line-height: 36px;
  white-space: nowrap;
}

.goods-order__transaction {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.goods-order__terms {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 8px;
}

.goods-order__terms-title {
  margin-bottom: 0;
  font-size: 16px;
}

.goods-order__term {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 19px;
}

.goods-order__term :deep(.line-icon) {
  flex: none;
  margin-top: 2px;
}

.goods-order__term-text {
  min-width: 0;
  color: #536178;
}

.goods-order__terms-link {
  display: flex;
  min-height: 44px;
  margin: -4px 0 -12px;
  align-self: flex-start;
  align-items: center;
  color: #ba470b;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  white-space: nowrap;
}

.goods-order__contact {
  display: flex;
  width: 56px;
  height: 44px;
  margin-right: auto;
  flex: none;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #536178;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

.goods-order__bar {
  position: sticky;
  bottom: 0;
  display: flex;
  flex: none;
  padding: 8px 16px max(20px, env(safe-area-inset-bottom));
  justify-content: flex-end;
  gap: 10px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: 0 -4px 18px rgba(38, 59, 98, 0.06);
}

.goods-order__bar-button {
  height: 44px;
  flex: none;
  padding: 0 20px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 15px;
  line-height: 42px;
  white-space: nowrap;
  box-sizing: border-box;
}

.goods-order__bar-button--primary {
  padding: 0 24px;
  border: 0;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  font-weight: 500;
  line-height: 44px;
}

.goods-order__bar-button[disabled] {
  color: #202c42;
  background: #ffffff;
  opacity: 0.55;
}

.goods-order__bar-button--primary[disabled] {
  color: #ffffff;
  background: #ff6a00;
}
</style>
