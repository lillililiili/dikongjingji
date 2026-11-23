<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { tradeApi } from '@/features/trade/data/trade-api-client'
import type { TradeOrder } from '@/features/trade/domain/trade-order'
import { ApiRequestError } from '@/shared/http/public-api-request'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  canRequestRefund,
  describeCancelDeadline,
  describeEnrollmentPayState,
  describeEnrollmentRefunds,
  formatEnrollmentCheckIn,
  formatEnrollmentFee,
  formatEnrollmentSession,
} from '../../application/enrollment-order-view'
import { describeTicket, pickInitialTicket } from '../../application/enrollment-view'
import { describeChangeLine, formatSessionRef } from '../../application/change-view'
import { hasResultPage } from '../../application/result-view'
import { activityApi } from '../../data/activity-api-client'
import { enrollmentApi } from '../../data/enrollment-api-client'
import type { ActivityDetail } from '../../domain/activity'
import type { Enrollment } from '../../domain/enrollment'
import { formatActivityAmount, formatActivityShortDateTime } from '../shared/activity-format'

// 订单详情 of a sign-up (board OrderEnroll, R5 #4 of 2026-11-20). Everything comes from the server:
// the sign-up, its trade order (number and payee) and the activity (co-organiser and refund note).
const props = defineProps<{
  enrollmentId: string
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  openTicket: [enrollmentId: string]
  refund: [enrollmentId: string]
  pay: [tradeOrderId: string]
  // S5-3: 退改详情 of one change, and the result page after the day.
  openChange: [enrollmentId: string, changeId: string]
  openResult: [enrollmentId: string]
}>()

type OrderState =
  | { kind: 'loading' }
  | { kind: 'ready'; enrollment: Enrollment }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<OrderState>({ kind: 'loading' })
// Both are extras: without them the page still shows the sign-up, only a few rows are left out.
const order = ref<TradeOrder | null>(null)
const activity = ref<ActivityDetail | null>(null)
let requestVersion = 0

const enrollment = computed(() => (state.value.kind === 'ready' ? state.value.enrollment : null))
const statusView = computed(() => {
  const current = enrollment.value
  const ticket = current ? pickInitialTicket(current) : null
  return current && ticket ? describeTicket(ticket, current) : null
})
const statusIcon = computed(() => {
  switch (statusView.value?.tone) {
    case 'CANCELLED': return 'close' as const
    case 'EXPIRED': return 'info' as const
    case 'PENDING': return 'wallet' as const
    default: return 'check' as const
  }
})
const session = computed(() => (enrollment.value ? formatEnrollmentSession(enrollment.value) : ''))
const checkIn = computed(() => (enrollment.value ? formatEnrollmentCheckIn(enrollment.value) : null))
const fee = computed(() => (enrollment.value ? formatEnrollmentFee(enrollment.value) : null))
const paidText = computed(() => {
  const amount = enrollment.value?.payment.amountMinor
  return amount ? formatActivityAmount(amount) : '免费'
})
const payState = computed(() => (enrollment.value ? describeEnrollmentPayState(enrollment.value) : null))
const refunds = computed(() => (enrollment.value ? describeEnrollmentRefunds(enrollment.value) : null))
const deadline = computed(() => (enrollment.value ? describeCancelDeadline(enrollment.value) : ''))
// S5-3: a sign-up that can move to another session also opens 申请退改.
const refundable = computed(() =>
  enrollment.value ? canRequestRefund(enrollment.value) || enrollment.value.reschedulable : false)
const previousTime = computed(() =>
  enrollment.value?.reschedule ? formatSessionRef(enrollment.value.reschedule.previousSession) : null)
const changeLines = computed(() =>
  enrollment.value?.changes.map((change) => ({ change, line: describeChangeLine(change) })) ?? [])
const resultReady = computed(() => (enrollment.value ? hasResultPage(enrollment.value, Date.now()) : false))
const unpaid = computed(() =>
  enrollment.value?.payment.status === 'PENDING_PAYMENT' && Boolean(enrollment.value.payment.tradeOrderId))
const parties = computed(() => {
  const current = enrollment.value
  if (!current) return []
  const rows = [{ label: '主办', value: current.activity.organizerName }]
  if (activity.value?.coOrganizer) rows.push({ label: '承办/交付', value: activity.value.coOrganizer.name })
  // C2 (2026-11-18): a paid sign-up's money goes straight to the organiser through the platform channel.
  if (order.value) rows.push({ label: '收款方', value: `${order.value.merchant.name} · 经平台支付渠道` })
  return rows
})
const rules = computed(() => {
  const current = enrollment.value
  if (!current) return []
  const lines: string[] = []
  if (activity.value?.refundNote) lines.push(activity.value.refundNote)
  if (current.payment.status !== 'FREE') {
    lines.push('开始前可以取消其中几位，每位按报名时的单价原路退回，提交即退')
  }
  lines.push('已签到的凭证不能取消')
  return lines
})

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

async function loadExtras(token: string, loaded: Enrollment, requested: number): Promise<void> {
  const [tradeOrder, detail] = await Promise.all([
    loaded.payment.tradeOrderId
      ? tradeApi.read(token, loaded.payment.tradeOrderId).catch(() => null)
      : Promise.resolve(null),
    activityApi.loadDetail(loaded.activity.activityId, loaded.activity.cityCode).catch(() => null),
  ])
  if (requested !== requestVersion) return
  order.value = tradeOrder
  activity.value = detail
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.enrollmentId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await enrollmentApi.loadEnrollment(token, props.enrollmentId)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', enrollment: loaded }
    await loadExtras(token, loaded, requested)
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    const missing = error instanceof ApiRequestError
      && (error.status === 404 || error.status === 400 || error.status === 403)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function copyOrderNo(orderNo: string): void {
  uni.setClipboardData({
    data: orderNo,
    success: () => uni.showToast({ title: '已复制订单编号', icon: 'none' }),
  })
}

watch(() => [props.refreshKey, props.accessToken, props.enrollmentId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="enroll-order" data-testid="activity-order">
    <view :class="['enroll-order__head', { 'enroll-order__head--plain': !enrollment }]">
      <view class="enroll-order__bar">
        <button class="enroll-order__back" data-testid="activity-order-back" aria-label="返回" @click="emit('back')">
          <LineIcon name="back" :size="22" :color="enrollment ? '#ffffff' : '#202c42'" :stroke-width="2" />
        </button>
        <text class="enroll-order__title">订单详情</text>
        <view class="enroll-order__back" />
      </view>
      <view v-if="enrollment && statusView" class="enroll-order__status">
        <view class="enroll-order__status-line">
          <LineIcon :name="statusIcon" :size="22" color="#ffffff" :stroke-width="2.6" />
          <text data-testid="activity-order-status">{{ statusView.title }}</text>
        </view>
        <text class="enroll-order__status-sub">{{ statusView.subtitle }}</text>
      </view>
    </view>

    <view v-if="state.kind === 'loading'" class="enroll-order__body" data-testid="activity-order-loading" aria-label="正在加载">
      <view class="enroll-order__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="enroll-order__state" data-testid="activity-order-missing">
      <text class="enroll-order__state-title">没有找到这笔报名订单</text>
      <button class="enroll-order__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="enroll-order__state" data-testid="activity-order-error">
      <text class="enroll-order__state-title">加载失败</text>
      <text class="enroll-order__state-text">请检查网络后重试</text>
      <button class="enroll-order__state-button" data-testid="activity-order-retry" @click="load">重试</button>
    </view>

    <view v-else-if="enrollment" class="enroll-order__body enroll-order__body--lifted">
      <view class="enroll-order__card enroll-order__card--activity">
        <text class="enroll-order__activity" data-testid="activity-order-activity">{{ enrollment.activity.title }}</text>
        <view v-if="previousTime" class="enroll-order__move" data-testid="activity-order-move">
          <view class="enroll-order__move-side">
            <text class="enroll-order__move-label">原时间</text>
            <text class="enroll-order__move-old">{{ previousTime }}</text>
          </view>
          <LineIcon d="M4 12h15M13 6l6 6-6 6" :size="20" color="#ff6a00" :stroke-width="2" />
          <view class="enroll-order__move-side">
            <text class="enroll-order__move-label enroll-order__move-label--new">新时间</text>
            <text class="enroll-order__move-new">{{ session }}</text>
          </view>
        </view>
        <view class="enroll-order__grid">
          <text class="enroll-order__label">场次</text>
          <text class="enroll-order__value enroll-order__value--strong" data-testid="activity-order-session">{{ session }}</text>
          <text class="enroll-order__label">场馆</text>
          <text class="enroll-order__value">{{ enrollment.location.name }}</text>
          <template v-if="checkIn">
            <text class="enroll-order__label">签到时间</text>
            <text class="enroll-order__value">{{ checkIn }}</text>
          </template>
        </view>
        <view class="enroll-order__dash" />
        <button class="enroll-order__ticket" data-testid="activity-order-ticket" @click="emit('openTicket', enrollment.enrollmentId)">
          <view class="enroll-order__ticket-icon">
            <LineIcon name="qr" :size="22" color="#ff6a00" />
          </view>
          <view class="enroll-order__ticket-text">
            <text class="enroll-order__ticket-title">查看凭证</text>
            <text class="enroll-order__ticket-sub">到场出示凭证签到 · 一人一码</text>
          </view>
          <LineIcon name="forward" :size="16" color="#91a0b4" :stroke-width="2" />
        </button>
        <template v-if="resultReady">
          <view class="enroll-order__dash enroll-order__dash--tight" />
          <button class="enroll-order__ticket" data-testid="activity-order-result" @click="emit('openResult', enrollment.enrollmentId)">
            <view class="enroll-order__ticket-icon">
              <LineIcon name="doc" :size="22" color="#ff6a00" />
            </view>
            <view class="enroll-order__ticket-text">
              <text class="enroll-order__ticket-title">签到与结果记录</text>
              <text class="enroll-order__ticket-sub">主办方发布的完成情况、成绩和证书说明</text>
            </view>
            <LineIcon name="forward" :size="16" color="#91a0b4" :stroke-width="2" />
          </button>
        </template>
      </view>

      <view v-if="changeLines.length > 0" class="enroll-order__card" data-testid="activity-order-changes">
        <text class="enroll-order__heading-title">退改记录</text>
        <button
          v-for="item in changeLines"
          :key="item.change.changeId"
          class="enroll-order__change"
          :data-testid="`activity-order-change-${item.change.changeId}`"
          @click="emit('openChange', enrollment.enrollmentId, item.change.changeId)"
        >
          <view class="enroll-order__change-text">
            <text class="enroll-order__change-title">{{ item.line.title }}</text>
            <text class="enroll-order__change-sub">{{ item.line.sub }}</text>
          </view>
          <text class="enroll-order__change-more">查看进度</text>
          <LineIcon name="forward" :size="16" color="#91a0b4" :stroke-width="2" />
        </button>
      </view>

      <view class="enroll-order__card" data-testid="activity-order-people">
        <view class="enroll-order__heading">
          <text class="enroll-order__heading-title">参与人</text>
          <text class="enroll-order__heading-note">{{ enrollment.tickets.length }} 人</text>
        </view>
        <view v-for="item in enrollment.tickets" :key="item.ticketId" class="enroll-order__person">
          <view class="enroll-order__avatar">
            <LineIcon name="person" :size="18" color="#738096" />
          </view>
          <text class="enroll-order__person-name">{{ item.participantName }}</text>
          <text v-if="item.relation === 'CHILD' && item.age !== null" class="enroll-order__person-age">{{ item.age }} 岁</text>
          <text v-else-if="item.relation === 'SELF'" class="enroll-order__person-age">本人</text>
        </view>
      </view>

      <view class="enroll-order__card" data-testid="activity-order-parties">
        <text class="enroll-order__heading-title">交易主体</text>
        <view class="enroll-order__grid enroll-order__grid--parties">
          <template v-for="row in parties" :key="row.label">
            <text class="enroll-order__label enroll-order__label--muted">{{ row.label }}</text>
            <text class="enroll-order__value">{{ row.value }}</text>
          </template>
        </view>
      </view>

      <view class="enroll-order__card enroll-order__card--rules" data-testid="activity-order-rules">
        <text class="enroll-order__heading-title">退改规则</text>
        <view :class="['enroll-order__deadline', { 'enroll-order__deadline--off': !enrollment.cancellable }]">
          <LineIcon name="clock" :size="16" :color="enrollment.cancellable ? '#159a70' : '#738096'" :stroke-width="2" />
          <text>{{ deadline }}</text>
        </view>
        <view v-for="line in rules" :key="line" class="enroll-order__rule">
          <view class="enroll-order__rule-dot" />
          <text>{{ line }}</text>
        </view>
      </view>

      <view class="enroll-order__card enroll-order__card--money" data-testid="activity-order-money">
        <text class="enroll-order__heading-title enroll-order__heading-title--money">金额与支付</text>
        <view v-if="fee" class="enroll-order__money-row">
          <text class="enroll-order__label--muted">报名费</text>
          <text class="enroll-order__money-right">{{ fee }}</text>
        </view>
        <view class="enroll-order__money-row enroll-order__money-row--baseline">
          <text class="enroll-order__label--muted">{{ enrollment.payment.status === 'PAID' ? '实付' : '应付' }}</text>
          <text class="enroll-order__paid" data-testid="activity-order-amount">{{ paidText }}</text>
        </view>
        <view class="enroll-order__line" />
        <view v-if="enrollment.payment.status !== 'FREE'" class="enroll-order__pay-row">
          <text class="enroll-order__pay-label">支付方式</text>
          <text class="enroll-order__pay-value">微信支付</text>
        </view>
        <view v-if="order" class="enroll-order__pay-row">
          <text class="enroll-order__pay-label">订单编号</text>
          <text class="enroll-order__pay-value" data-testid="activity-order-no">{{ order.orderNo }}</text>
          <button class="enroll-order__copy" data-testid="activity-order-copy" @click="copyOrderNo(order.orderNo)">复制</button>
        </view>
        <view v-if="order?.paidAt" class="enroll-order__pay-row">
          <text class="enroll-order__pay-label">支付时间</text>
          <text class="enroll-order__pay-value">{{ formatActivityShortDateTime(order.paidAt) }}</text>
        </view>
        <view v-if="payState" class="enroll-order__pay-row">
          <text class="enroll-order__pay-label">支付状态</text>
          <text :class="['enroll-order__pay-value', `enroll-order__tone--${payState.tone.toLowerCase()}`]" data-testid="activity-order-pay-state">
            {{ payState.text }}
          </text>
        </view>
        <view v-if="refunds" class="enroll-order__pay-row">
          <text class="enroll-order__pay-label">退款</text>
          <text :class="['enroll-order__pay-value', `enroll-order__tone--${refunds.tone.toLowerCase()}`]" data-testid="activity-order-refunds">
            {{ refunds.text }}
          </text>
        </view>
      </view>
    </view>

    <view v-if="enrollment && (refundable || unpaid)" class="enroll-order__actions">
      <text :class="['enroll-order__actions-note', { 'enroll-order__actions-note--pending': unpaid }]">
        {{ unpaid ? '付款后报名生效，超时名额自动释放' : deadline }}
      </text>
      <button
        v-if="unpaid"
        class="enroll-order__pay"
        data-testid="activity-order-pay"
        @click="emit('pay', enrollment.payment.tradeOrderId as string)"
      >
        去付款
      </button>
      <button
        v-else
        class="enroll-order__refund"
        data-testid="activity-order-refund"
        @click="emit('refund', enrollment.enrollmentId)"
      >
        申请退改
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board OrderEnroll (390 wide). */
.enroll-order {
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

.enroll-order__head {
  flex: none;
  padding: calc(8px + var(--status-bar-height)) 8px 52px;
  color: #ffffff;
  background: linear-gradient(180deg, #ff6a00 0%, #ff8a3d 100%);
}

.enroll-order__head--plain {
  padding-bottom: 8px;
  color: #202c42;
  background: var(--app-head-gradient);
}

.enroll-order__bar {
  display: flex;
  align-items: center;
}

.enroll-order__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.enroll-order__title {
  flex: 1;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-align: center;
}

.enroll-order__status {
  display: flex;
  padding: 10px 8px 0;
  flex-direction: column;
  gap: 6px;
}

.enroll-order__status-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 22px;
  font-weight: 700;
  line-height: 30px;
}

.enroll-order__status-sub {
  font-size: 13px;
  font-weight: 500;
  line-height: 19px;
}

.enroll-order__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 12px 16px 16px;
}

.enroll-order__body--lifted {
  position: relative;
  margin-top: -36px;
  padding-top: 0;
}

.enroll-order__skeleton {
  height: 220px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.enroll-order__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.enroll-order__state-title {
  font-size: 16px;
  font-weight: 700;
}

.enroll-order__state-text {
  color: #738096;
  font-size: 13px;
}

.enroll-order__state-button {
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

.enroll-order__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.enroll-order__card--activity {
  gap: 0;
  padding: 16px 16px 6px;
}

.enroll-order__card--rules,
.enroll-order__card--money {
  gap: 8px;
}

.enroll-order__activity {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.enroll-order__grid {
  display: grid;
  margin-top: 12px;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
  line-height: 19px;
}

.enroll-order__grid--parties {
  margin-top: 0;
  grid-template-columns: 64px minmax(0, 1fr);
}

.enroll-order__label {
  color: #738096;
  white-space: nowrap;
}

.enroll-order__label--muted {
  color: #627187;
  white-space: nowrap;
}

.enroll-order__value {
  min-width: 0;
}

.enroll-order__value--strong {
  font-weight: 500;
}

.enroll-order__dash {
  margin-top: 14px;
  border-top: 1px dashed #e5ebf3;
}

.enroll-order__ticket {
  display: flex;
  width: 100%;
  min-height: 56px;
  align-items: center;
  gap: 10px;
  color: #202c42;
  text-align: left;
}

.enroll-order__ticket-icon {
  display: flex;
  width: 36px;
  height: 36px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: #fff0e3;
}

.enroll-order__ticket-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.enroll-order__ticket-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.enroll-order__ticket-sub {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.enroll-order__heading {
  display: flex;
  align-items: baseline;
}

.enroll-order__heading-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.enroll-order__heading-title--money {
  margin-bottom: 2px;
}

.enroll-order__heading-note {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.enroll-order__person {
  display: flex;
  align-items: center;
  gap: 10px;
}

.enroll-order__avatar {
  display: flex;
  width: 32px;
  height: 32px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef2f8;
}

.enroll-order__person-name {
  font-size: 15px;
  line-height: 21px;
}

.enroll-order__person-age {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.enroll-order__deadline {
  display: flex;
  padding: 9px 12px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.enroll-order__deadline--off {
  color: #536178;
  background: #eef2f8;
}

.enroll-order__rule {
  display: flex;
  gap: 8px;
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.enroll-order__rule-dot {
  width: 4px;
  height: 4px;
  flex: none;
  margin: 8px 2px 0;
  border-radius: 50%;
  background: #91a0b4;
}

.enroll-order__card--money {
  font-size: 13px;
  line-height: 19px;
}

.enroll-order__money-row {
  display: flex;
}

.enroll-order__money-row--baseline {
  align-items: baseline;
}

.enroll-order__money-right {
  margin-left: auto;
}

.enroll-order__paid {
  margin-left: auto;
  color: #e85400;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.enroll-order__line {
  height: 1px;
  margin: 4px 0;
  background: #f0f1f3;
}

.enroll-order__pay-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.enroll-order__pay-label {
  width: 72px;
  flex: none;
  color: #627187;
}

.enroll-order__pay-value {
  min-width: 0;
  color: #536178;
}

.enroll-order__copy {
  height: 36px;
  flex: none;
  margin: -9px -8px -9px auto;
  padding: 0 8px;
  color: #ba470b;
  font-size: 13px;
  line-height: 36px;
  white-space: nowrap;
}

.enroll-order__tone--done {
  color: #11804a;
}

.enroll-order__tone--pending {
  color: #ba470b;
}

.enroll-order__tone--alert {
  color: #d64f67;
}

.enroll-order__tone--muted {
  color: #738096;
}

.enroll-order__actions {
  position: sticky;
  bottom: 0;
  display: flex;
  flex: none;
  padding: 8px 16px calc(20px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 12px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

.enroll-order__actions-note {
  min-width: 0;
  flex: 1;
  color: #11804a;
  font-size: 12px;
  line-height: 17px;
}

.enroll-order__actions-note--pending {
  color: #ba470b;
}

.enroll-order__refund {
  display: flex;
  height: 44px;
  flex: none;
  padding: 0 22px;
  align-items: center;
  justify-content: center;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 15px;
  white-space: nowrap;
  box-sizing: border-box;
}

.enroll-order__pay {
  display: flex;
  height: 44px;
  flex: none;
  padding: 0 28px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
  white-space: nowrap;
}

/* S5-3: 原时间 / 新时间 (board EnrollResult), 结果入口 and 退改记录. */
.enroll-order__move {
  display: grid;
  margin-top: 12px;
  padding: 10px 12px;
  align-items: center;
  gap: 4px;
  grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1fr);
  border-radius: 10px;
  background: #fff0e3;
}

.enroll-order__move-side {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.enroll-order__move-label {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.enroll-order__move-label--new {
  color: #ba470b;
}

.enroll-order__move-old {
  color: #738096;
  font-size: 14px;
  line-height: 20px;
  text-decoration: line-through;
  white-space: nowrap;
}

.enroll-order__move-new {
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
  white-space: nowrap;
}

.enroll-order__dash--tight {
  margin-top: 0;
}

.enroll-order__change {
  display: flex;
  width: 100%;
  min-height: 52px;
  align-items: center;
  gap: 10px;
  color: #202c42;
  text-align: left;
}

.enroll-order__change + .enroll-order__change {
  border-top: 1px solid #f0f1f3;
}

.enroll-order__change-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.enroll-order__change-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.enroll-order__change-sub {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.enroll-order__change-more {
  flex: none;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}
</style>
