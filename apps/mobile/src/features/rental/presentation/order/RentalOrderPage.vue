<script setup lang="ts">
import TradePartiesCard from '@/features/trade/presentation/shared/TradePartiesCard.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'

import { formatRentalAmount, formatRentalInstant } from '../../application/rental-format'
import { openSupportChat } from '@/features/support/application/support-entry'
import {
  describeRentalOrderHead,
  refundText,
  rentalChain,
  rentalConfirmSheet,
  rentalContractSummary,
  rentalFeeRows,
  rentalHandoverView,
  rentalOrderAction,
  rentalParties,
  rentalReturnView,
} from '../../application/rental-order-view'
import { rentalApi } from '../../data/rental-api-client'
import type { RentalInquiry, RentalOrder } from '../../domain/rental'
import RentalConfirmSheet from '../shared/RentalConfirmSheet.vue'
import RentalItemCard from '../shared/RentalItemCard.vue'
import RentalPageHeader from '../shared/RentalPageHeader.vue'

const props = defineProps<{
  rentalOrderId: string | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  contract: [rentalOrderId: string]
  pay: [tradeOrderId: string]
  // 再次租赁: the product page of the device in the city it was rented in.
  rentAgain: [productId: string, cityCode: string]
}>()

type OrderState =
  | { kind: 'loading' }
  | { kind: 'ready'; order: RentalOrder }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<OrderState>({ kind: 'loading' })
// The inquiry behind the order gives the device's picture and product (for 再次租赁); the order
// page still works without it.
const inquiry = ref<RentalInquiry | null>(null)
const asking = ref<'CONFIRM_HANDOVER' | 'CONFIRM_RETURN' | null>(null)
const acting = ref(false)
let requestVersion = 0

const order = computed(() => (state.value.kind === 'ready' ? state.value.order : null))

// 联系商家 (step G1): the lessor's conversation with a card about this rental order.
function contactMerchant(): void {
  const current = order.value
  if (!current) return
  openSupportChat(
    { kind: 'MERCHANT', organizationId: current.contract.lessor.merchantId },
    { type: 'RENTAL_ORDER', refId: current.rentalOrderId, title: current.contract.productName, subtitle: `合同 ${current.contract.contractNo}` },
  )
}
const head = computed(() => (order.value ? describeRentalOrderHead(order.value, new Date()) : null))
const chain = computed(() => (order.value ? rentalChain(order.value.status) : null))
const fees = computed(() => (order.value ? rentalFeeRows(order.value) : []))
const handover = computed(() => (order.value ? rentalHandoverView(order.value) : null))
const giveBack = computed(() => (order.value ? rentalReturnView(order.value) : null))
const summary = computed(() => (order.value ? rentalContractSummary(order.value) : []))
const parties = computed(() => (order.value ? rentalParties(order.value) : []))
const action = computed(() => (order.value ? rentalOrderAction(order.value) : null))
const sheet = computed(() => (order.value && asking.value ? rentalConfirmSheet(order.value, asking.value) : null))
const refund = computed(() => (order.value ? refundText(order.value.refund) : null))

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.rentalOrderId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await rentalApi.readOrder(token, props.rentalOrderId)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', order: loaded }
    if (!inquiry.value && loaded.inquiryId) {
      rentalApi.readInquiry(token, loaded.inquiryId)
        .then((found) => { if (requested === requestVersion) inquiry.value = found })
        .catch(() => { /* the picture and 再次租赁 wait for the next show */ })
    }
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    state.value = {
      kind: error instanceof ApiRequestError && (error.status === 404 || error.status === 400) ? 'missing' : 'error',
    }
  }
}

function runAction(): void {
  const current = order.value
  const next = action.value
  if (!current || !next) return
  if (next.kind === 'PAY') emit('pay', current.payment.tradeOrderId)
  else if (next.kind === 'RENT_AGAIN') {
    if (!inquiry.value) {
      uni.showToast({ title: '正在读取设备信息，请稍后再试', icon: 'none' })
      return
    }
    emit('rentAgain', inquiry.value.item.productId, inquiry.value.place.cityCode)
  } else asking.value = next.kind
}

async function confirmRecord(): Promise<void> {
  const token = props.accessToken
  const current = order.value
  const kind = asking.value
  if (!token || !current || !kind || acting.value) return
  acting.value = true
  try {
    const updated = kind === 'CONFIRM_HANDOVER'
      ? await rentalApi.confirmHandover(token, current.rentalOrderId)
      : await rentalApi.confirmReturn(token, current.rentalOrderId)
    state.value = { kind: 'ready', order: updated }
    uni.showToast({ title: kind === 'CONFIRM_HANDOVER' ? '已确认交接，开始计租' : '已确认归还，订单结束', icon: 'none' })
  } catch (error) {
    if (isUnauthorized(error)) {
      asking.value = null
      emit('login')
      return
    }
    uni.showToast({
      title: error instanceof ApiRequestError && error.code === 'RENTAL_STATE_INVALID'
        ? '订单状态已变化，已刷新'
        : '没有确认成功，请稍后重试',
      icon: 'none',
    })
    await load()
  } finally {
    acting.value = false
    asking.value = null
  }
}

watch(() => [props.refreshKey, props.accessToken, props.rentalOrderId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="rental-order" data-testid="rental-order" :data-status="order?.status ?? ''">
    <RentalPageHeader title="订单详情" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="rental-order__body" aria-label="正在加载">
      <view class="rental-order__skeleton" />
      <view class="rental-order__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="rental-order__state" data-testid="rental-order-missing">
      <text class="rental-order__state-title">没有找到这张租赁订单</text>
      <button class="rental-order__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="rental-order__state" data-testid="rental-order-error">
      <text class="rental-order__state-title">加载失败</text>
      <text class="rental-order__state-text">请检查网络后重试</text>
      <button class="rental-order__state-button" data-testid="rental-order-retry" @click="load">重试</button>
    </view>

    <template v-else-if="order && head">
      <view class="rental-order__body">
        <RentalItemCard
          :product-id="inquiry?.item.productId ?? ''"
          :product-name="order.contract.productName"
          :quantity="order.contract.quantity"
          :cover-url="inquiry?.item.coverUrl ?? null"
          :merchant-name="order.contract.lessor.name"
          :merchant-reviewed="order.contract.lessor.qualificationReviewed"
          :lessee="order.contract.lessee"
        />

        <view class="rental-order__card rental-order__head" data-testid="rental-order-head">
          <text :class="['rental-order__head-title', `rental-order__head-title--${head.tone.toLowerCase()}`]" data-testid="rental-order-title">
            {{ head.title }}
          </text>
          <text class="rental-order__head-text" data-testid="rental-order-text">{{ head.text }}</text>
          <view v-if="chain" class="rental-order__chain" role="list" aria-label="订单状态">
            <view
              v-for="(step, position) in chain"
              :key="step.label"
              class="rental-order__step"
              role="listitem"
              :aria-current="step.current ? 'step' : undefined"
            >
              <view
                :class="['rental-order__step-line', 'rental-order__step-line--left', { 'rental-order__step-line--on': step.reached, 'rental-order__step-line--none': position === 0 }]"
              />
              <view
                :class="['rental-order__step-line', 'rental-order__step-line--right', { 'rental-order__step-line--on': chain[position + 1]?.reached, 'rental-order__step-line--none': position === chain.length - 1 }]"
              />
              <view :class="['rental-order__dot', { 'rental-order__dot--reached': step.reached, 'rental-order__dot--done': step.done }]">
                <LineIcon v-if="step.done" name="check" :size="10" color="#ffffff" :stroke-width="3" />
                <view v-else-if="step.current" class="rental-order__dot-core" />
              </view>
              <text :class="['rental-order__step-label', { 'rental-order__step-label--current': step.current, 'rental-order__step-label--reached': step.reached }]">
                {{ step.label }}
              </text>
            </view>
          </view>
        </view>

        <view v-if="order.status === 'CANCELLED' && refund" class="rental-order__card rental-order__refund" data-testid="rental-order-refund">
          <text class="rental-order__card-title">退款</text>
          <text class="rental-order__refund-text" data-testid="rental-order-refund-text">{{ refund }}</text>
          <text class="rental-order__refund-note">出租方在交接前取消，租金整单原路退回，到账时间以微信支付为准</text>
        </view>

        <view class="rental-order__card rental-order__contract">
          <button class="rental-order__contract-link" data-testid="rental-order-contract" @click="emit('contract', order.rentalOrderId)">
            <text class="rental-order__card-title">租赁合同</text>
            <text class="rental-order__tag rental-order__tag--done">已确认</text>
            <text class="rental-order__contract-time">{{ formatRentalInstant(order.contract.confirmedAt) }}</text>
            <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
          <view class="rental-order__summary">
            <text
              v-for="row in summary"
              :key="row.key"
              :class="['rental-order__summary-item', { 'rental-order__summary-item--wide': row.key === 'place' }]"
              :data-testid="`rental-order-summary-${row.key}`"
            >
              {{ row.label }}：{{ row.value }}
            </text>
          </view>
        </view>

        <view class="rental-order__card rental-order__fees" data-testid="rental-order-fees">
          <view class="rental-order__fees-head">
            <text class="rental-order__card-title">费用约定</text>
            <text class="rental-order__fees-total">
              合同金额 <text class="rental-order__fees-amount" data-testid="rental-order-total">{{ formatRentalAmount(order.contract.totalMinor) }}</text>
            </text>
          </view>
          <view v-for="fee in fees" :key="fee.key" class="rental-order__fee" :data-testid="`rental-order-fee-${fee.key}`">
            <view class="rental-order__fee-copy">
              <text class="rental-order__fee-name">{{ fee.name }}</text>
              <text :class="['rental-order__fee-state', { 'rental-order__fee-state--paid': fee.paid }]">{{ fee.state }}</text>
            </view>
            <text :class="['rental-order__fee-amount', { 'rental-order__fee-amount--quiet': !fee.money }]">{{ fee.amount }}</text>
          </view>
        </view>

        <view v-if="handover" class="rental-order__card rental-order__record" data-testid="rental-order-handover">
          <view class="rental-order__record-head">
            <text class="rental-order__card-title">交接记录</text>
            <text :class="['rental-order__tag', `rental-order__tag--${handover.tone.toLowerCase()}`]" data-testid="rental-order-handover-tag">
              {{ handover.tag }}
            </text>
          </view>
          <view class="rental-order__grid">
            <template v-for="row in handover.rows" :key="row.key">
              <text class="rental-order__grid-label">{{ row.label }}</text>
              <text class="rental-order__grid-value">{{ row.value }}</text>
            </template>
          </view>
          <text v-if="handover.note" class="rental-order__record-note">{{ handover.note }}</text>
        </view>

        <view v-if="giveBack" class="rental-order__card rental-order__record" data-testid="rental-order-return">
          <view class="rental-order__record-head">
            <text class="rental-order__card-title">归还记录</text>
            <text :class="['rental-order__tag', `rental-order__tag--${giveBack.tone.toLowerCase()}`]" data-testid="rental-order-return-tag">
              {{ giveBack.tag }}
            </text>
          </view>
          <view class="rental-order__grid">
            <template v-for="row in giveBack.rows" :key="row.key">
              <text class="rental-order__grid-label">{{ row.label }}</text>
              <text class="rental-order__grid-value">{{ row.value }}</text>
            </template>
          </view>
          <text v-if="giveBack.note" class="rental-order__record-note">{{ giveBack.note }}</text>
        </view>

        <TradePartiesCard test-id="rental-order-parties" :rows="parties" />
      </view>

      <view class="rental-order__bar">
        <button
          :class="['rental-order__contact', { 'rental-order__contact--wide': !action }]"
          data-testid="rental-order-contact"
          @click="contactMerchant"
        >
          联系商家
        </button>
        <button v-if="action" class="rental-order__primary" data-testid="rental-order-action" @click="runAction">
          {{ action.label }}
        </button>
      </view>
    </template>

    <RentalConfirmSheet
      v-if="sheet"
      :title="sheet.title"
      :text="sheet.note"
      :rows="sheet.rows"
      keep-label="再看看"
      :go-label="sheet.go"
      :busy="acting"
      @keep="!acting && (asking = null)"
      @go="confirmRecord"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board OrderRental (390 wide). */
.rental-order {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
}

button::after {
  border: 0;
}

.rental-order__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.rental-order__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.rental-order__card-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.rental-order__head {
  gap: 4px;
}

.rental-order__head-title {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.rental-order__head-title--action { color: #ba470b; }
.rental-order__head-title--done { color: #11804a; }
.rental-order__head-title--muted { color: #536178; }

.rental-order__head-text {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.rental-order__chain {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  margin-top: 12px;
}

.rental-order__step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.rental-order__step-line {
  position: absolute;
  top: 8px;
  height: 2px;
  background: #e5ebf3;
}

.rental-order__step-line--left { left: 0; right: 50%; }
.rental-order__step-line--right { left: 50%; right: 0; }
.rental-order__step-line--on { background: #ff6a00; }
.rental-order__step-line--none { background: transparent; }

.rental-order__dot {
  position: relative;
  display: flex;
  width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  border: 2px solid #c9d3e1;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.rental-order__dot--reached { border-color: #ff6a00; }
.rental-order__dot--done { background: #ff6a00; }

.rental-order__dot-core {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ff6a00;
}

.rental-order__step-label {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.rental-order__step-label--reached { color: #202c42; }
.rental-order__step-label--current { color: #ba470b; font-weight: 700; }

.rental-order__refund-text {
  color: #11804a;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.rental-order__refund-note {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.rental-order__contract {
  gap: 0;
  padding: 3px 14px 14px;
}

.rental-order__contract-link {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
}

.rental-order__tag {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.rental-order__tag--action { color: #ba470b; background: #fff0e3; }
.rental-order__tag--done { color: #11804a; background: #e2f6ed; }
.rental-order__tag--muted { color: #627187; background: #eef2f8; }

.rental-order__contract-time {
  margin-left: auto;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.rental-order__summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 12px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.rental-order__summary-item {
  min-width: 0;
}

.rental-order__summary-item--wide {
  grid-column: 1 / -1;
}

.rental-order__fees {
  gap: 0;
  padding: 14px 14px 4px;
}

.rental-order__fees-head {
  display: flex;
  align-items: center;
  margin-bottom: 4px;
}

.rental-order__fees-total {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.rental-order__fees-amount {
  color: #202c42;
  font-size: 14px;
  font-weight: 700;
}

.rental-order__fee {
  display: flex;
  min-height: 52px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.rental-order__fee-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.rental-order__fee-name {
  font-size: 14px;
  line-height: 20px;
}

.rental-order__fee-state {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.rental-order__fee-state--paid {
  color: #11804a;
}

.rental-order__fee-amount {
  flex: none;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.rental-order__fee-amount--quiet {
  color: #627187;
  font-size: 13px;
  font-weight: 400;
}

.rental-order__record {
  gap: 0;
  padding: 14px 14px 4px;
}

.rental-order__record-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.rental-order__record-head .rental-order__tag {
  margin-left: auto;
}

.rental-order__grid {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 6px 10px;
  padding-bottom: 10px;
  font-size: 13px;
  line-height: 18px;
}

.rental-order__grid--wide {
  grid-template-columns: 56px minmax(0, 1fr);
  padding-bottom: 0;
}

.rental-order__grid-label {
  color: #627187;
  white-space: nowrap;
}

.rental-order__grid-value {
  min-width: 0;
  word-break: break-all;
}

.rental-order__record-note {
  display: flex;
  min-height: 44px;
  align-items: center;
  border-top: 1px solid #f0f1f3;
  padding: 13px 0;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  box-sizing: border-box;
}

.rental-order__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  gap: 10px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.rental-order__contact {
  height: 46px;
  flex: none;
  padding: 0 22px;
  border: 1.5px solid #e5ebf3;
  border-radius: 999px;
  background: #ffffff;
  font-size: 15px;
  line-height: 43px;
  text-align: center;
  white-space: nowrap;
  box-sizing: border-box;
}

.rental-order__contact--wide {
  flex: 1;
}

.rental-order__primary {
  min-width: 0;
  height: 46px;
  flex: 1;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
  white-space: nowrap;
}

.rental-order__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.rental-order__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 32px;
}

.rental-order__state-title {
  font-size: 16px;
  font-weight: 700;
}

.rental-order__state-text {
  color: #627187;
  font-size: 14px;
  line-height: 20px;
}

.rental-order__state-button {
  height: 44px;
  padding: 0 24px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
  line-height: 44px;
  white-space: nowrap;
}
</style>
