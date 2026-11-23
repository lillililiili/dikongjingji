<script setup lang="ts">
import { computed, ref } from 'vue'

import { openSupportChat } from '@/features/support/application/support-entry'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { formatServiceAmount } from '../../application/service-order-format'
import {
  describeServiceDeliverySummary,
  describeServiceFulfilment,
  describeServiceOrderHead,
  describeServicePlanCard,
  serviceOrderCancelCopy,
  serviceOrderChain,
  serviceOrderPrimaryAction,
  servicePaymentRows,
} from '../../application/service-order-view'
import { serviceOrderApi } from '../../data/service-order-api-client'
import ServiceOrderHeadCard from '../shared/ServiceOrderHeadCard.vue'
import ServiceOrderNotice from '../shared/ServiceOrderNotice.vue'
import ServiceOrderSheet from '../shared/ServiceOrderSheet.vue'
import { useServiceOrder } from '../shared/use-service-order'

const props = defineProps<{
  orderId: string | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  plan: [orderId: string]
  delivery: [orderId: string]
  pay: [tradeOrderId: string]
  provider: [providerId: string, cityCode: string]
}>()

const { state, order, busy, notice, load, run } = useServiceOrder({
  getOrderId: () => props.orderId,
  getAccessToken: () => props.accessToken,
  getRefreshKey: () => props.refreshKey,
  onLogin: () => emit('login'),
})

const cancelling = ref(false)

const head = computed(() => (order.value ? describeServiceOrderHead(order.value) : null))
const chain = computed(() => (order.value ? serviceOrderChain(order.value.status) : null))
const planCard = computed(() => (order.value ? describeServicePlanCard(order.value) : null))
const payments = computed(() => (order.value ? servicePaymentRows(order.value) : []))
const fulfilment = computed(() => (order.value ? describeServiceFulfilment(order.value) : null))
const delivery = computed(() => (order.value ? describeServiceDeliverySummary(order.value) : null))
const action = computed(() => (order.value ? serviceOrderPrimaryAction(order.value) : null))
const cancelCopy = computed(() => (order.value ? serviceOrderCancelCopy(order.value) : null))

// 联系服务商 (step G1): the provider's conversation with a card about the demand behind the order
// (the support contract has no service-order card yet).
function contactProvider(): void {
  const current = order.value
  if (!current) return
  openSupportChat(
    { kind: 'PROVIDER', organizationId: current.provider.providerId },
    current.demandId
      ? { type: 'DEMAND', refId: current.demandId, title: current.title, subtitle: `服务订单 ${current.orderNo}` }
      : null,
  )
}

// 去付款: one trade order per attempt (an open one is reused), then the cashier.
async function pay(): Promise<void> {
  let tradeOrderId = ''
  const started = await run(async (token, current) => {
    tradeOrderId = (await serviceOrderApi.startPayment(token, current.orderId)).tradeOrderId
  })
  if (started && tradeOrderId) emit('pay', tradeOrderId)
}

function runAction(): void {
  const current = order.value
  const next = action.value
  if (!current || !next) return
  if (next.kind === 'CONFIRM_PLAN') emit('plan', current.orderId)
  else if (next.kind === 'ACCEPT') emit('delivery', current.orderId)
  else void pay()
}

async function confirmCancel(): Promise<void> {
  const paid = Boolean(order.value?.payment.paidAt)
  await run(
    (token, current) => serviceOrderApi.cancel(token, current.orderId, current.version, null),
    paid ? '订单已取消，款项原路退回' : '订单已取消',
  )
  cancelling.value = false
}

function openProvider(providerId: string): void {
  const current = order.value
  if (current?.cityCode) emit('provider', providerId, current.cityCode)
}
</script>

<template>
  <view class="so-detail" data-testid="service-order-detail" :data-status="order?.status ?? ''">
    <view class="so-detail__top">
      <AppPageHeader title="订单详情" test-id="service-order-detail" @back="emit('back')" />
    </view>

    <view v-if="state.kind === 'loading'" class="so-detail__body" aria-label="正在加载">
      <view class="so-detail__skeleton" />
      <view class="so-detail__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="so-detail__state" data-testid="service-order-missing">
      <text class="so-detail__state-title">没有找到这张服务订单</text>
      <button class="so-detail__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="so-detail__state" data-testid="service-order-error">
      <text class="so-detail__state-title">加载失败</text>
      <text class="so-detail__state-text">请检查网络后重试</text>
      <button class="so-detail__state-button" data-testid="service-order-retry" @click="load">重试</button>
    </view>

    <template v-else-if="order && head">
      <view class="so-detail__body">
        <ServiceOrderHeadCard :order="order" @provider="openProvider" />

        <view class="so-detail__card so-detail__head" data-testid="service-order-status">
          <text :class="['so-detail__head-title', `so-detail__head-title--${head.tone.toLowerCase()}`]" data-testid="service-order-status-title">
            {{ head.title }}
          </text>
          <text class="so-detail__head-text" data-testid="service-order-status-text">{{ head.text }}</text>
          <view v-if="chain" class="so-detail__chain" role="list" aria-label="订单状态" data-testid="service-order-chain">
            <view
              v-for="(step, position) in chain"
              :key="step.label"
              class="so-detail__step"
              role="listitem"
              :aria-current="step.current ? 'step' : undefined"
            >
              <view :class="['so-detail__line', 'so-detail__line--left', { 'so-detail__line--on': step.reached, 'so-detail__line--none': position === 0 }]" />
              <view :class="['so-detail__line', 'so-detail__line--right', { 'so-detail__line--on': chain[position + 1]?.reached, 'so-detail__line--none': position === chain.length - 1 }]" />
              <view :class="['so-detail__dot', { 'so-detail__dot--reached': step.reached, 'so-detail__dot--done': step.done }]">
                <LineIcon v-if="step.done" name="check" :size="10" color="#ffffff" :stroke-width="4" />
                <view v-else-if="step.current" class="so-detail__dot-core" />
              </view>
              <text :class="['so-detail__step-label', { 'so-detail__step-label--current': step.current, 'so-detail__step-label--reached': step.reached }]">
                {{ step.label }}
              </text>
            </view>
          </view>
        </view>

        <view v-if="planCard" class="so-detail__card so-detail__plan" data-testid="service-order-plan-card">
          <button class="so-detail__link" data-testid="service-order-open-plan" @click="emit('plan', order.orderId)">
            <text class="so-detail__card-title">{{ planCard.title }}</text>
            <text :class="['so-detail__tag', `so-detail__tag--${planCard.tag.tone.toLowerCase()}`]" data-testid="service-order-plan-tag">
              {{ planCard.tag.label }}
            </text>
            <view class="so-detail__spacer" />
            <text v-if="planCard.time" class="so-detail__link-time">{{ planCard.time }}</text>
            <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
          <view class="so-detail__summary">
            <text
              v-for="row in planCard.rows"
              :key="row.key"
              :class="['so-detail__summary-item', { 'so-detail__summary-item--wide': row.key !== 'method' }]"
            >
              {{ row.label }}：{{ row.value }}
            </text>
          </view>
          <text v-if="planCard.note" class="so-detail__plan-note">{{ planCard.note }}</text>
        </view>

        <view class="so-detail__card so-detail__pay" data-testid="service-order-payment">
          <view class="so-detail__pay-head">
            <text class="so-detail__card-title">付款</text>
            <text class="so-detail__pay-total">
              合同金额 <text class="so-detail__pay-amount" data-testid="service-order-amount">{{ formatServiceAmount(order.amountMinor) }}</text>
            </text>
          </view>
          <view v-for="row in payments" :key="row.key" class="so-detail__pay-row" :data-testid="`service-order-pay-${row.key}`">
            <view class="so-detail__pay-copy">
              <view class="so-detail__pay-name-line">
                <text class="so-detail__pay-name">{{ row.name }}</text>
                <text v-if="row.tag" class="so-detail__pay-tag">{{ row.tag }}</text>
              </view>
              <text :class="['so-detail__pay-state', `so-detail__pay-state--${row.stateTone.toLowerCase()}`]">{{ row.state }}</text>
            </view>
            <text :class="['so-detail__pay-value', `so-detail__pay-value--${row.amountTone.toLowerCase()}`]">{{ row.amount }}</text>
          </view>
        </view>

        <view v-if="fulfilment" class="so-detail__card so-detail__work" data-testid="service-order-fulfilment">
          <view class="so-detail__work-head">
            <text class="so-detail__card-title">服务履约</text>
          </view>
          <view class="so-detail__work-row">
            <view :class="['so-detail__work-icon', { 'so-detail__work-icon--done': fulfilment.done }]">
              <LineIcon :name="fulfilment.done ? 'check' : 'clock'" :size="18" :color="fulfilment.done ? '#159a70' : '#ba470b'" :stroke-width="2.4" />
            </view>
            <view class="so-detail__work-copy">
              <text class="so-detail__work-title">本次服务</text>
              <text class="so-detail__work-state">{{ fulfilment.state }}</text>
            </view>
            <text :class="['so-detail__work-time', { 'so-detail__work-time--done': fulfilment.done }]">{{ fulfilment.time }}</text>
          </view>
        </view>

        <view v-if="delivery" class="so-detail__card so-detail__delivery" data-testid="service-order-delivery-card">
          <view class="so-detail__delivery-head">
            <text class="so-detail__card-title">交付物</text>
            <text class="so-detail__delivery-time">{{ delivery.time }}</text>
          </view>
          <view class="so-detail__delivery-row">
            <view class="so-detail__file-icon">
              <LineIcon d="M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6" :size="20" color="#2f80ed" :stroke-width="1.8" />
            </view>
            <view class="so-detail__delivery-copy">
              <text class="so-detail__delivery-note">{{ delivery.note }}</text>
              <text class="so-detail__delivery-files">{{ delivery.files }}</text>
            </view>
          </view>
          <button class="so-detail__delivery-link" data-testid="service-order-open-delivery" @click="emit('delivery', order.orderId)">
            <text class="so-detail__delivery-link-text">查看交付与验收</text>
            <text class="so-detail__delivery-link-note">文件在该页受控下载</text>
            <LineIcon name="forward" :size="14" color="#ba470b" :stroke-width="2" />
          </button>
        </view>

        <button
          v-if="order.actions.cancel"
          class="so-detail__cancel"
          data-testid="service-order-cancel"
          @click="cancelling = true"
        >
          取消订单
        </button>
      </view>

      <view class="so-detail__bar">
        <button
          :class="['so-detail__contact', { 'so-detail__contact--wide': !action }]"
          data-testid="service-order-contact"
          @click="contactProvider"
        >
          联系服务商
        </button>
        <button
          v-if="action"
          class="so-detail__primary"
          data-testid="service-order-action"
          :disabled="busy"
          @click="runAction"
        >
          {{ busy && action.kind === 'PAY' ? '处理中…' : action.label }}
        </button>
      </view>
    </template>

    <ServiceOrderNotice v-if="notice" :text="notice" @close="notice = null" />

    <ServiceOrderSheet
      v-if="cancelling && cancelCopy"
      test-id="service-order-cancel-sheet"
      :title="cancelCopy.title"
      :text="cancelCopy.text"
      :keep-label="cancelCopy.keep"
      :go-label="cancelCopy.go"
      :busy="busy"
      @keep="cancelling = false"
      @go="confirmCancel"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board OrderService (390 wide), made one visit
   and one payment. */
.so-detail {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
}

.so-detail__top {
  position: sticky;
  top: 0;
  z-index: 5;
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

.so-detail__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.so-detail__card {
  display: flex;
  flex-direction: column;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.so-detail__card-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.so-detail__head {
  gap: 4px;
}

.so-detail__head-title {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.so-detail__head-title--action { color: #ba470b; }
.so-detail__head-title--done { color: #11804a; }
.so-detail__head-title--muted { color: #536178; }

.so-detail__head-text {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.so-detail__chain {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  margin-top: 12px;
}

.so-detail__step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.so-detail__line {
  position: absolute;
  top: 8px;
  height: 2px;
  background: #e5ebf3;
}

.so-detail__line--left { left: 0; right: 50%; }
.so-detail__line--right { left: 50%; right: 0; }
.so-detail__line--on { background: #ff6a00; }
.so-detail__line--none { background: transparent; }

.so-detail__dot {
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

.so-detail__dot--reached { border-color: #ff6a00; }
.so-detail__dot--done { background: #ff6a00; }

.so-detail__dot-core {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ff6a00;
}

/* Seven steps in 330px: the board's 12px labels are 11px here so 待出方案 fits its column. */
.so-detail__step-label {
  color: #738096;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.so-detail__step-label--reached { color: #202c42; }
.so-detail__step-label--current { color: #ba470b; font-weight: 700; }

.so-detail__plan {
  padding: 3px 14px 14px;
}

.so-detail__link {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
}

.so-detail__tag {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.so-detail__tag--action { color: #ba470b; background: #fff0e3; }
.so-detail__tag--done { color: #11804a; background: #e2f6ed; }
.so-detail__tag--muted { color: #627187; background: #eef2f8; }

.so-detail__spacer {
  flex: 1;
}

.so-detail__link-time {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.so-detail__summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 12px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.so-detail__summary-item {
  min-width: 0;
}

.so-detail__summary-item--wide {
  grid-column: 1 / -1;
}

.so-detail__plan-note {
  margin-top: 6px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.so-detail__pay {
  padding: 14px 14px 4px;
}

.so-detail__pay-head {
  display: flex;
  align-items: center;
  margin-bottom: 4px;
}

.so-detail__pay-total {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.so-detail__pay-amount {
  color: #202c42;
  font-size: 14px;
  font-weight: 700;
}

.so-detail__pay-row {
  display: flex;
  min-height: 52px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.so-detail__pay-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.so-detail__pay-name-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.so-detail__pay-name {
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.so-detail__pay-tag {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.so-detail__pay-state {
  font-size: 12px;
  line-height: 17px;
}

.so-detail__pay-state--muted { color: #627187; }
.so-detail__pay-state--action { color: #ba470b; }
.so-detail__pay-state--done { color: #11804a; }
.so-detail__pay-state--alert { color: #c43c3c; }

.so-detail__pay-value {
  flex: none;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.so-detail__pay-value--plain { color: #202c42; }
.so-detail__pay-value--accent { color: #e85400; }
.so-detail__pay-value--muted { color: #738096; }
.so-detail__pay-value--done { color: #11804a; }

.so-detail__work {
  padding: 3px 14px 14px;
}

.so-detail__work-head {
  display: flex;
  min-height: 44px;
  align-items: center;
}

.so-detail__work-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.so-detail__work-icon {
  display: flex;
  width: 32px;
  height: 32px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff0e3;
}

.so-detail__work-icon--done {
  background: #e2f6ed;
}

.so-detail__work-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.so-detail__work-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.so-detail__work-state {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.so-detail__work-time {
  flex: none;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.so-detail__work-time--done {
  color: #11804a;
}

.so-detail__delivery {
  padding: 14px 14px 0;
}

.so-detail__delivery-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.so-detail__delivery-time {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.so-detail__delivery-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding-bottom: 12px;
}

.so-detail__file-icon {
  display: flex;
  width: 36px;
  height: 36px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #e6f0ff;
}

.so-detail__delivery-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.so-detail__delivery-note {
  font-size: 14px;
  line-height: 20px;
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.so-detail__delivery-files {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.so-detail__delivery-link {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
}

.so-detail__delivery-link-text {
  color: #ba470b;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  white-space: nowrap;
}

.so-detail__delivery-link-note {
  margin-left: auto;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

/* PlanConfirm board: the grey 取消订单 under the cards. */
.so-detail__cancel {
  align-self: center;
  height: 44px;
  padding: 0 20px;
  color: #627187;
  font-size: 14px;
  line-height: 44px;
  white-space: nowrap;
}

.so-detail__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  gap: 10px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.so-detail__contact {
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

.so-detail__contact--wide {
  flex: 1;
}

.so-detail__primary {
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

.so-detail__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.so-detail__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 32px;
}

.so-detail__state-title {
  font-size: 16px;
  font-weight: 700;
}

.so-detail__state-text {
  color: #627187;
  font-size: 14px;
  line-height: 20px;
}

.so-detail__state-button {
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
