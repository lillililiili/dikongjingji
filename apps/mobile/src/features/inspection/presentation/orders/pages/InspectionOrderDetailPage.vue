<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { providerCredentialLabel } from '@/shared/ui/qualification'
import { openNodePayment } from '../../../application/service-flow/node-payment'
import { inspectionServiceImage } from '../../../data/inspection-service-images'
import type {
  InspectionDelivery,
  InspectionPlan,
  InspectionServiceOrder,
} from '../../../data/service-flow/inspection-plan-api'
import { inspectionPlanApi } from '../../../data/service-flow/uni-booking-http-client'
import {
  buildInspectionDeliveryUrl,
  buildInspectionFulfillmentUrl,
  buildInspectionOrderCenterUrl,
  buildInspectionPlanConfirmationUrl,
} from '../../../navigation/inspection-routes'
import PlanSummaryCard from '../../plan/components/PlanSummaryCard.vue'
import { describeCustomerParty } from '../../plan/plan-confirmation-view'
import {
  describeDeliveryLine,
  describeFulfillmentLine,
  describeOrderChain,
  describeOrderHeadline,
  describeOrderPaymentCard,
  describePlanLine,
  describePrimaryAction,
} from '../order-detail-view'

// Board OrderService (S4-1): one inspection service order. Read-only: it leads to the plan,
// fulfillment and delivery pages, and to the cashier for a node that is due (preview builds only).

const serviceOrderId = ref('')
const order = ref<InspectionServiceOrder | null>(null)
const plan = ref<InspectionPlan | null>(null)
const delivery = ref<{ visitNo: number; value: InspectionDelivery } | null>(null)
const isLoading = ref(true)
const loadError = ref('')
const paying = ref(false)
let loadVersion = 0

const chain = computed(() => (order.value ? describeOrderChain(order.value) : []))
const headline = computed(() => (order.value ? describeOrderHeadline(order.value) : null))
const paymentCard = computed(() => (order.value && plan.value
  ? describeOrderPaymentCard(order.value, plan.value.totalAmountMinor)
  : null))
const planLine = computed(() => (plan.value ? describePlanLine(plan.value) : null))
const fulfillmentLine = computed(() => (order.value ? describeFulfillmentLine(order.value) : null))
const deliveryLine = computed(() => (delivery.value
  ? describeDeliveryLine(delivery.value.visitNo, delivery.value.value)
  : null))
const action = computed(() => (order.value ? describePrimaryAction(order.value, TRADE_PREVIEW_ENABLED) : null))

onLoad((query) => {
  serviceOrderId.value = typeof query?.serviceOrderId === 'string' ? query.serviceOrderId.trim() : ''
  if (!serviceOrderId.value) {
    isLoading.value = false
    loadError.value = '未找到服务订单'
  }
})

// Shown again after the cashier or the delivery page: read the server's state again.
onShow(() => {
  if (serviceOrderId.value) void load()
})

async function load(): Promise<void> {
  const version = ++loadVersion
  if (!order.value) isLoading.value = true
  try {
    const current = await inspectionPlanApi.getServiceOrder(serviceOrderId.value)
    const currentPlan = await inspectionPlanApi.getPlan(current.planId)
    const delivered = [...current.visits]
      .filter((visit) => visit.status === 'DELIVERED' || visit.status === 'ACCEPTED')
      .sort((a, b) => b.visitNo - a.visitNo)[0]
    const currentDelivery = delivered
      ? await inspectionPlanApi.getDelivery(current.id, delivered.id).catch(() => null)
      : null
    if (version !== loadVersion) return
    order.value = current
    plan.value = currentPlan
    delivery.value = delivered && currentDelivery ? { visitNo: delivered.visitNo, value: currentDelivery } : null
    loadError.value = ''
  } catch (error) {
    if (version !== loadVersion) return
    loadError.value = error instanceof Error && error.message ? error.message : '订单加载失败'
  } finally {
    if (version === loadVersion) isLoading.value = false
  }
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildInspectionOrderCenterUrl('ALL') })
}

function openPlan(): void {
  if (order.value) uni.navigateTo({ url: buildInspectionPlanConfirmationUrl(order.value.planId) })
}

function openFulfillment(): void {
  if (order.value) uni.navigateTo({ url: buildInspectionFulfillmentUrl(order.value.id) })
}

function openDelivery(visitId: string): void {
  if (order.value) uni.navigateTo({ url: buildInspectionDeliveryUrl(order.value.id, visitId) })
}

function openLatestDelivery(): void {
  const visit = order.value?.visits.find((item) => item.visitNo === delivery.value?.visitNo)
  if (visit) openDelivery(visit.id)
}

async function runAction(): Promise<void> {
  const current = action.value
  if (!current || !order.value) return
  if (current.kind === 'ACCEPT') {
    openDelivery(current.visitId)
  } else if (current.kind === 'FULFILLMENT') {
    openFulfillment()
  } else if (!paying.value) {
    paying.value = true
    try {
      await openNodePayment(order.value.id, current.nodeNo)
    } finally {
      paying.value = false
    }
  }
}
</script>

<template>
  <view class="order-detail" data-testid="inspection-order-detail">
    <AppPageHeader title="订单详情" test-id="inspection-order-detail" @back="goBack" />

    <view v-if="isLoading" class="order-detail__state">正在读取服务订单…</view>
    <view v-else-if="loadError" class="order-detail__state">
      <LineIcon name="info" :size="40" color="#b5bac1" />
      <text>{{ loadError }}</text>
      <button v-if="serviceOrderId" class="order-detail__retry" @click="load">重新加载</button>
    </view>

    <main v-else-if="order && plan && headline" class="order-detail__content">
      <PlanSummaryCard
        :cover-url="inspectionServiceImage({ coverUrl: order.serviceSnapshot.serviceCoverUrl, title: order.serviceSnapshot.serviceTitle })"
        :title="order.serviceSnapshot.serviceTitle"
        :provider-name="order.provider?.name ?? ''"
        :certification-label="providerCredentialLabel(order.provider?.qualificationReviewed, order.provider?.certificationLabel) ?? ''"
        :customer="describeCustomerParty(order.customerParty)"
      />

      <view class="order-detail__card order-detail__status">
        <text
          class="order-detail__headline"
          :class="{ 'order-detail__headline--muted': headline.isCancelled }"
          data-testid="order-detail-headline"
        >{{ headline.title }}</text>
        <text class="order-detail__headline-text">{{ headline.text }}</text>
        <view v-if="!headline.isCancelled" class="order-detail__chain" role="list" aria-label="订单状态">
          <view
            v-for="(step, index) in chain"
            :key="step.label"
            class="order-detail__step"
            :class="{ 'order-detail__step--done': step.done, 'order-detail__step--current': step.current }"
            role="listitem"
          >
            <view v-if="index > 0" class="order-detail__step-line order-detail__step-line--left" :class="{ 'order-detail__step-line--on': step.done || step.current }" />
            <view v-if="index < chain.length - 1" class="order-detail__step-line order-detail__step-line--right" :class="{ 'order-detail__step-line--on': step.done && (chain[index + 1].done || chain[index + 1].current) }" />
            <view class="order-detail__step-dot">
              <LineIcon v-if="step.done" name="check" :size="10" color="#ffffff" :stroke-width="3.2" />
              <view v-else-if="step.current" class="order-detail__step-core" />
            </view>
            <text class="order-detail__step-label">{{ step.label }}</text>
          </view>
        </view>
      </view>

      <view v-if="planLine" class="order-detail__card">
        <button class="order-detail__head-link" @click="openPlan">
          <text class="order-detail__card-title">{{ planLine.title }}</text>
          <text class="order-detail__chip">已确认</text>
          <text class="order-detail__head-side">{{ planLine.whenText }}</text>
          <LineIcon name="chevronRight" :size="16" color="#91a0b4" :stroke-width="2" />
        </button>
        <view class="order-detail__plan-rows">
          <text v-for="row in planLine.rows" :key="row" class="order-detail__plan-row">{{ row }}</text>
        </view>
      </view>

      <view v-if="paymentCard" class="order-detail__card order-detail__payment" data-testid="order-detail-payment">
        <view class="order-detail__payment-head">
          <text class="order-detail__card-title">付款节点</text>
          <text class="order-detail__payment-total">合同金额 <text class="order-detail__payment-total-value">{{ paymentCard.totalText }}</text></text>
        </view>
        <view v-for="row in paymentCard.rows" :key="row.key" class="order-detail__node">
          <view class="order-detail__node-copy">
            <view class="order-detail__node-name-line">
              <text class="order-detail__node-name">{{ row.name }}</text>
              <text v-if="row.tag" class="order-detail__node-tag">{{ row.tag }}</text>
            </view>
            <text
              class="order-detail__node-state"
              :class="{ 'order-detail__node-state--paid': row.isPaid, 'order-detail__node-state--due': row.isDue }"
            >{{ row.stateText }}</text>
          </view>
          <text
            class="order-detail__node-amount"
            :class="{ 'order-detail__node-amount--due': row.isDue || Boolean(row.tag), 'order-detail__node-amount--muted': row.isMuted }"
          >{{ row.amountText }}</text>
        </view>
        <view v-if="paymentCard.records.length" class="order-detail__records" data-testid="order-detail-records">
          <text class="order-detail__records-title">付款记录</text>
          <view v-for="record in paymentCard.records" :key="record.key" class="order-detail__record">
            <text class="order-detail__record-text">{{ record.text }}</text>
            <text class="order-detail__record-time">{{ record.timeText }}</text>
          </view>
        </view>
      </view>

      <view v-if="fulfillmentLine" class="order-detail__card">
        <button class="order-detail__head-link" @click="openFulfillment">
          <text class="order-detail__card-title">履约记录</text>
          <text class="order-detail__head-side">{{ fulfillmentLine.summaryText }}</text>
          <LineIcon name="chevronRight" :size="16" color="#91a0b4" :stroke-width="2" />
        </button>
        <view v-if="fulfillmentLine.latest" class="order-detail__visit">
          <view class="order-detail__visit-icon">
            <LineIcon name="check" :size="16" color="#11804a" :stroke-width="2.4" />
          </view>
          <view class="order-detail__visit-copy">
            <text class="order-detail__visit-title">{{ fulfillmentLine.latest.title }}</text>
            <text class="order-detail__visit-text">{{ fulfillmentLine.latest.text }}</text>
          </view>
          <text class="order-detail__visit-done">{{ fulfillmentLine.latest.doneText }}</text>
        </view>
      </view>

      <view v-if="deliveryLine" class="order-detail__card order-detail__delivery">
        <view class="order-detail__delivery-head">
          <text class="order-detail__card-title">交付物</text>
          <text class="order-detail__head-side">{{ deliveryLine.whenText }}</text>
        </view>
        <view class="order-detail__delivery-body">
          <view class="order-detail__delivery-icon">
            <LineIcon name="doc" :size="18" color="#1d63c4" :stroke-width="1.8" />
          </view>
          <view class="order-detail__visit-copy">
            <text class="order-detail__delivery-summary">{{ deliveryLine.summary }}</text>
            <text class="order-detail__visit-text">{{ deliveryLine.files }}</text>
          </view>
        </view>
        <button class="order-detail__delivery-link" @click="openLatestDelivery">
          <text class="order-detail__delivery-link-text">查看交付与验收</text>
          <text class="order-detail__head-side">文件在该页受控下载</text>
          <LineIcon name="chevronRight" :size="16" color="#ba470b" :stroke-width="2" />
        </button>
      </view>
    </main>

    <view v-if="action && !isLoading && !loadError" class="order-detail__bar">
      <button
        class="order-detail__primary"
        :disabled="paying"
        data-testid="order-detail-action"
        @click="runAction"
      >
        {{ action.label }}
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board OrderService (390 wide). */
.order-detail {
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
  text-align: left;
}

button::after {
  border: 0;
}

.order-detail__state {
  display: flex;
  min-height: 360px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  color: #7e858e;
  font-size: 14px;
}

.order-detail__retry {
  height: 44px;
  padding: 0 24px;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 14px;
  line-height: 44px;
}

.order-detail__content {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
  box-sizing: border-box;
}

.order-detail__card {
  display: flex;
  padding: 3px 14px 14px;
  flex-direction: column;
  border-radius: 14px;
  background: #fff;
  box-shadow: var(--app-card-shadow);
}

.order-detail__status {
  padding: 14px;
  gap: 4px;
}

.order-detail__headline {
  color: #ba470b;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.order-detail__headline--muted {
  color: #536178;
}

.order-detail__headline-text {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.order-detail__chain {
  display: grid;
  margin-top: 12px;
  grid-template-columns: repeat(6, minmax(0, 1fr));
}

.order-detail__step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.order-detail__step-line {
  position: absolute;
  top: 8px;
  height: 2px;
  background: #e5ebf3;
}

.order-detail__step-line--left {
  left: 0;
  right: 50%;
}

.order-detail__step-line--right {
  left: 50%;
  right: 0;
}

.order-detail__step-line--on {
  background: #ff6a00;
}

.order-detail__step-dot {
  position: relative;
  display: flex;
  width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  border: 2px solid #c9d3e1;
  border-radius: 50%;
  background: #fff;
}

.order-detail__step--done .order-detail__step-dot {
  border-color: #ff6a00;
  background: #ff6a00;
}

.order-detail__step--current .order-detail__step-dot {
  border-color: #ff6a00;
}

.order-detail__step-core {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ff6a00;
}

.order-detail__step-label {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.order-detail__step--done .order-detail__step-label {
  color: #202c42;
}

.order-detail__step--current .order-detail__step-label {
  color: #ba470b;
  font-weight: 700;
}

.order-detail__head-link {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
}

.order-detail__card-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.order-detail__chip {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 11px;
  line-height: 16px;
}

.order-detail__head-side {
  margin-left: auto;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.order-detail__plan-rows {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
}

.order-detail__plan-row {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}


.order-detail__payment {
  padding: 14px 14px 4px;
}

.order-detail__payment-head {
  display: flex;
  margin-bottom: 4px;
  align-items: center;
}

.order-detail__payment-total {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.order-detail__payment-total-value {
  color: #202c42;
  font-size: 14px;
  font-weight: 700;
}

.order-detail__node {
  display: flex;
  min-height: 52px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.order-detail__node-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.order-detail__node-name-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.order-detail__node-name {
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.order-detail__node-tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.order-detail__node-state {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.order-detail__node-state--paid {
  color: #11804a;
}

.order-detail__node-state--due {
  color: #ba470b;
}

.order-detail__node-amount {
  flex-shrink: 0;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.order-detail__node-amount--due {
  color: #e85400;
}

.order-detail__node-amount--muted {
  color: #738096;
}

.order-detail__records {
  display: flex;
  padding: 8px 0 10px;
  flex-direction: column;
  gap: 4px;
  border-top: 1px solid #f0f1f3;
}

.order-detail__records-title {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.order-detail__record {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  line-height: 17px;
}

.order-detail__record-text {
  color: #202c42;
}

.order-detail__record-time {
  color: #11804a;
  white-space: nowrap;
}

.order-detail__visit,
.order-detail__delivery-body {
  display: flex;
  align-items: center;
  gap: 10px;
}

.order-detail__visit-icon {
  display: flex;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #e2f6ed;
}

.order-detail__visit-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.order-detail__visit-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.order-detail__visit-text {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.order-detail__visit-done {
  flex-shrink: 0;
  color: #11804a;
  font-size: 13px;
  line-height: 18px;
}

.order-detail__delivery {
  padding: 14px 14px 0;
}

.order-detail__delivery-head {
  display: flex;
  margin-bottom: 10px;
  align-items: center;
  gap: 8px;
}

.order-detail__delivery-head .order-detail__head-side {
  font-size: 12px;
  line-height: 17px;
}

.order-detail__delivery-body {
  padding-bottom: 12px;
  align-items: flex-start;
}

.order-detail__delivery-icon {
  display: flex;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #e6f0ff;
}

.order-detail__delivery-summary {
  font-size: 14px;
  line-height: 20px;
}

.order-detail__delivery-link {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
}

.order-detail__delivery-link-text {
  color: #ba470b;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  white-space: nowrap;
}

.order-detail__delivery-link .order-detail__head-side {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.order-detail__bar {
  position: sticky;
  bottom: 0;
  display: flex;
  padding: 10px 16px max(24px, env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #fff;
}

.order-detail__primary {
  height: 46px;
  flex: 1;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
}

.order-detail__primary[disabled] {
  color: #fff;
  background: #ffb27a;
}
</style>
