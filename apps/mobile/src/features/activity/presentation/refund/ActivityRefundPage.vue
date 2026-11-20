<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  describeRefundBreakdown,
  describeRefundRows,
  formatEnrollmentSession,
  initialRefundSelection,
  refundAmountText,
  refundableTickets,
} from '../../application/enrollment-order-view'
import { enrollmentApi } from '../../data/enrollment-api-client'
import { resolveActivityPreviewCover } from '../../data/preview/activity-preview-covers'
import type { Enrollment } from '../../domain/enrollment'
import { formatActivityAmount, formatActivityShortDateTime } from '../shared/activity-format'

// 申请退款 of a paid sign-up (board EnrollRefund, R5 #5 of 2026-11-20). Only refunds: the people
// ticked are cancelled at once through the existing cancel endpoint and each is paid back at the
// unit price (提交即退). Moving to another session and organiser review are not built; the board's
// reason picker is left out because the cancel endpoint has nowhere to keep a reason.
const props = defineProps<{
  enrollmentId: string
  // The person the ticket page was showing; ticked to start with.
  ticketId: string | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  // The cancel went through; the host goes back to where the customer came from.
  done: [enrollmentId: string]
}>()

type RefundState =
  | { kind: 'loading' }
  | { kind: 'ready'; enrollment: Enrollment }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<RefundState>({ kind: 'loading' })
const picked = ref<string[]>([])
const submitting = ref(false)
let requestVersion = 0

const enrollment = computed(() => (state.value.kind === 'ready' ? state.value.enrollment : null))
const rows = computed(() => (enrollment.value ? describeRefundRows(enrollment.value) : []))
const open = computed(() => (enrollment.value ? refundableTickets(enrollment.value).length > 0 : false))
const coverUrl = computed(() =>
  enrollment.value
    ? enrollment.value.activity.coverImage?.url ?? resolveActivityPreviewCover(enrollment.value.activity.activityId)
    : null)
const summary = computed(() => {
  const current = enrollment.value
  if (!current) return ''
  const amount = current.payment.amountMinor
  return amount ? `${formatEnrollmentSession(current)} · 实付 ${formatActivityAmount(amount)}` : formatEnrollmentSession(current)
})
const amountText = computed(() => (enrollment.value ? refundAmountText(enrollment.value, picked.value.length) : ''))
const breakdown = computed(() =>
  enrollment.value ? describeRefundBreakdown(enrollment.value, picked.value.length) : '')

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
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
    const first = state.value.kind !== 'ready'
    state.value = { kind: 'ready', enrollment: loaded }
    const still = new Set(refundableTickets(loaded).map((ticket) => ticket.ticketId))
    picked.value = first
      ? initialRefundSelection(loaded, props.ticketId)
      : picked.value.filter((id) => still.has(id))
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

function toggle(ticketId: string, selectable: boolean): void {
  if (!selectable) return
  picked.value = picked.value.includes(ticketId)
    ? picked.value.filter((id) => id !== ticketId)
    : [...picked.value, ticketId]
}

// The button stays orange like the board (R5 #1); with nobody ticked it says so.
function askSubmit(): void {
  const current = enrollment.value
  if (!current || submitting.value) return
  if (!open.value) {
    uni.showToast({ title: '这笔报名现在不能退款', icon: 'none' })
    return
  }
  if (picked.value.length === 0) {
    uni.showToast({ title: '请选择要退款的参与人', icon: 'none' })
    return
  }
  uni.showModal({
    title: '确认退款？',
    content: `取消 ${picked.value.length} 人的报名，名额会让给别人，原路退回 ${amountText.value}。`,
    confirmText: '确认退款',
    cancelText: '再想想',
    success: (result) => {
      if (result.confirm) void submit()
    },
  })
}

async function submit(): Promise<void> {
  const token = props.accessToken
  const current = enrollment.value
  if (!token || !current || submitting.value) return
  submitting.value = true
  try {
    // Every ticked ticket at once; leaving none out means the server would cancel them all.
    const updated = await enrollmentApi.cancel(token, current.enrollmentId, [...picked.value])
    state.value = { kind: 'ready', enrollment: updated }
    uni.showToast({ title: '已取消，报名费原路退回', icon: 'none' })
    emit('done', updated.enrollmentId)
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({
      title: error instanceof ApiRequestError ? error.message : '没有退款成功，请稍后重试',
      icon: 'none',
    })
    void load()
  } finally {
    submitting.value = false
  }
}

watch(() => [props.refreshKey, props.accessToken, props.enrollmentId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="enroll-refund" data-testid="activity-refund">
    <AppPageHeader title="申请退款" centered test-id="activity-refund" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="enroll-refund__body" data-testid="activity-refund-loading" aria-label="正在加载">
      <view class="enroll-refund__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="enroll-refund__state" data-testid="activity-refund-missing">
      <text class="enroll-refund__state-title">没有找到这笔报名</text>
      <button class="enroll-refund__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="enroll-refund__state" data-testid="activity-refund-error">
      <text class="enroll-refund__state-title">加载失败</text>
      <text class="enroll-refund__state-text">请检查网络后重试</text>
      <button class="enroll-refund__state-button" data-testid="activity-refund-retry" @click="load">重试</button>
    </view>

    <view v-else-if="enrollment" class="enroll-refund__body">
      <view class="enroll-refund__card enroll-refund__card--summary">
        <image
          v-if="coverUrl"
          class="enroll-refund__cover"
          :src="coverUrl"
          :alt="enrollment.activity.title"
          mode="aspectFill"
        />
        <view class="enroll-refund__summary">
          <text class="enroll-refund__activity" data-testid="activity-refund-activity">{{ enrollment.activity.title }}</text>
          <text class="enroll-refund__summary-sub">{{ summary }}</text>
        </view>
      </view>

      <view class="enroll-refund__card enroll-refund__card--people" data-testid="activity-refund-people">
        <view class="enroll-refund__heading">
          <text class="enroll-refund__heading-title">选择参与人</text>
          <text class="enroll-refund__heading-note">可只退其中一人</text>
        </view>
        <button
          v-for="row in rows"
          :key="row.ticketId"
          :class="['enroll-refund__person', { 'enroll-refund__person--off': !row.selectable }]"
          :data-testid="`activity-refund-person-${row.ticketId}`"
          :data-name="row.name"
          role="checkbox"
          :aria-checked="picked.includes(row.ticketId)"
          :aria-disabled="!row.selectable"
          @click="toggle(row.ticketId, row.selectable)"
        >
          <view
            :class="[
              'enroll-refund__box',
              { 'enroll-refund__box--on': picked.includes(row.ticketId), 'enroll-refund__box--off': !row.selectable },
            ]"
          >
            <LineIcon v-if="picked.includes(row.ticketId)" name="check" :size="14" color="#ffffff" :stroke-width="2.6" />
          </view>
          <text class="enroll-refund__person-name">{{ row.name }}</text>
          <text v-if="row.ageText" class="enroll-refund__person-age">{{ row.ageText }}</text>
          <text class="enroll-refund__person-side">{{ row.sideText }}</text>
        </button>
      </view>

      <view class="enroll-refund__card enroll-refund__card--rows">
        <view class="enroll-refund__row">
          <text class="enroll-refund__row-label">退款金额</text>
          <text class="enroll-refund__amount" data-testid="activity-refund-amount">{{ amountText || '—' }}</text>
          <text class="enroll-refund__row-side">{{ breakdown }}</text>
        </view>
        <view class="enroll-refund__row">
          <text class="enroll-refund__row-label">退款去向</text>
          <text class="enroll-refund__row-value">微信支付 原路退回</text>
        </view>
      </view>

      <view class="enroll-refund__tip" data-testid="activity-refund-tip">
        <LineIcon name="info" :size="16" color="#ff6a00" :stroke-width="2" />
        <text>
          提交后立即取消所选参与人的报名并释放名额，报名费原路退回，到账时间以微信支付为准。{{
            enrollment.cancellable ? `${formatActivityShortDateTime(enrollment.cancellableUntil)} 前可以申请。` : '活动已开始，不能再申请退款。'
          }}
        </text>
      </view>
    </view>

    <view v-if="enrollment" class="enroll-refund__actions">
      <button
        :class="['enroll-refund__submit', { 'enroll-refund__submit--off': !open || submitting }]"
        data-testid="activity-refund-submit"
        :disabled="submitting"
        @click="askSubmit"
      >
        {{ submitting ? '提交中…' : '提交申请' }}
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board EnrollRefund (390 wide). */
.enroll-refund {
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

.enroll-refund__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.enroll-refund__skeleton {
  height: 180px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.enroll-refund__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.enroll-refund__state-title {
  font-size: 16px;
  font-weight: 700;
}

.enroll-refund__state-text {
  color: #738096;
  font-size: 13px;
}

.enroll-refund__state-button {
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

.enroll-refund__card {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.enroll-refund__card--summary {
  flex-direction: row;
  padding: 12px;
  align-items: center;
  gap: 12px;
}

.enroll-refund__cover {
  display: block;
  width: 56px;
  height: 56px;
  flex: none;
  border-radius: 8px;
  background: #eef2f8;
}

.enroll-refund__summary {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.enroll-refund__activity {
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
}

.enroll-refund__summary-sub {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.enroll-refund__card--people {
  gap: 4px;
  padding: 14px 14px 6px;
}

.enroll-refund__heading {
  display: flex;
  align-items: baseline;
}

.enroll-refund__heading-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.enroll-refund__heading-note {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.enroll-refund__person {
  display: flex;
  width: 100%;
  min-height: 44px;
  align-items: center;
  gap: 10px;
  color: #202c42;
  text-align: left;
}

.enroll-refund__box {
  display: flex;
  width: 20px;
  height: 20px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #9aa8ba;
  border-radius: 4px;
  background: #ffffff;
  box-sizing: border-box;
}

.enroll-refund__box--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.enroll-refund__box--off {
  border-color: #d9e0ea;
  background: #f1f3f6;
}

.enroll-refund__person-name {
  font-size: 15px;
  line-height: 21px;
  white-space: nowrap;
}

.enroll-refund__person--off .enroll-refund__person-name {
  color: #738096;
}

.enroll-refund__person-age {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.enroll-refund__person-side {
  margin-left: auto;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.enroll-refund__card--rows {
  padding: 0 14px;
}

.enroll-refund__row {
  display: flex;
  min-height: 50px;
  align-items: center;
  gap: 12px;
}

.enroll-refund__row + .enroll-refund__row {
  border-top: 1px solid #f0f1f3;
}

.enroll-refund__row-label {
  width: 60px;
  flex: none;
  color: #627187;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.enroll-refund__amount {
  color: #e85400;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.enroll-refund__row-side {
  min-width: 0;
  margin-left: auto;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.enroll-refund__row-value {
  font-size: 14px;
  line-height: 20px;
}

.enroll-refund__tip {
  display: flex;
  padding: 10px 12px;
  align-items: flex-start;
  gap: 8px;
  border-radius: 10px;
  color: #536178;
  background: #fff0e3;
  font-size: 13px;
  line-height: 19px;
}

.enroll-refund__tip > :first-child {
  margin-top: 2px;
}

.enroll-refund__actions {
  position: sticky;
  bottom: 0;
  display: flex;
  flex: none;
  flex-direction: column;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

.enroll-refund__submit {
  display: flex;
  height: 46px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
}

.enroll-refund__submit--off {
  color: #91a0b4;
  background: #eef0f3;
  box-shadow: none;
}
</style>
