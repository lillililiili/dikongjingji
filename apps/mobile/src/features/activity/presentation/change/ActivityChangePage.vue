<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import { ticketQrImage } from '@/shared/qr/ticket-qr'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { describeChange, formatSessionRef } from '../../application/change-view'
import { enrollmentApi } from '../../data/enrollment-api-client'
import { resolveActivityPreviewCover } from '../../data/preview/activity-preview-covers'
import type { ChangeDetail, Enrollment } from '../../domain/enrollment'
import { formatActivityAmount, formatActivityDay, formatActivityTime } from '../shared/activity-format'
import { contactOrganizer } from './contact-organizer'

// 退改详情 (board EnrollRefundDetail, S5-3): one cancellation or reschedule of a sign-up. The change says
// what happened and the refund's state in the trade module; the sign-up adds the paid amount, the venue
// and the check-in time of the new session.
const props = defineProps<{
  enrollmentId: string
  changeId: string
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  // 查看订单: the order page of a paid sign-up, the ticket page of a free one.
  openOrder: [enrollmentId: string, paid: boolean]
}>()

type ChangeState =
  | { kind: 'loading' }
  | { kind: 'ready'; detail: ChangeDetail; enrollment: Enrollment | null }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<ChangeState>({ kind: 'loading' })
const refreshing = ref(false)
const pickedTicketId = ref<string | null>(null)
let requestVersion = 0

const detail = computed(() => (state.value.kind === 'ready' ? state.value.detail : null))
const enrollment = computed(() => (state.value.kind === 'ready' ? state.value.enrollment : null))
const view = computed(() => (detail.value ? describeChange(detail.value) : null))
// New tickets of a reschedule that can still be used.
const newTickets = computed(() =>
  detail.value?.tickets.filter((ticket) => ticket.issued && ticket.ticketCode) ?? [])
const currentTicket = computed(() =>
  newTickets.value.find((ticket) => ticket.ticketId === pickedTicketId.value) ?? newTickets.value[0] ?? null)
const qrImage = computed(() => (currentTicket.value?.ticketCode ? ticketQrImage(currentTicket.value.ticketCode) : ''))
const newSessionDay = computed(() => {
  const to = detail.value?.change.toSession
  return to ? `${formatActivityDay(to.startsAt)} ${formatActivityTime(to.startsAt)}` : ''
})
// The sign-up is in the new session now, so its check-in time is the new one's.
const newSessionCheckIn = computed(() => {
  const current = enrollment.value
  const to = detail.value?.change.toSession
  if (!current || !to || current.session.sessionId !== to.sessionId) return '待签到'
  return current.session.checkInOpensAt
    ? `${formatActivityTime(current.session.checkInOpensAt)} 起签到 · 待签到`
    : '待签到'
})
const coverUrl = computed(() => {
  const current = detail.value
  if (!current) return null
  return current.coverImage?.url ?? (enrollment.value ? resolveActivityPreviewCover(enrollment.value.activity.activityId) : null)
})
const applySummary = computed(() => {
  const current = detail.value
  if (!current) return ''
  return `${formatSessionRef(current.change.fromSession)} · ${current.change.people} 人`
})
const paidLine = computed(() => {
  const amount = enrollment.value?.payment.amountMinor
  return enrollment.value?.payment.status === 'PAID' && amount ? `实付 ${formatActivityAmount(amount)}` : '免费报名'
})

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.enrollmentId || !props.changeId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const [loaded, sign] = await Promise.all([
      enrollmentApi.loadChange(token, props.enrollmentId, props.changeId),
      // An extra: without it the page still shows the change.
      enrollmentApi.loadEnrollment(token, props.enrollmentId).catch((error: unknown) => {
        if (isUnauthorized(error)) throw error
        return null
      }),
    ])
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', detail: loaded, enrollment: sign }
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400 || error.status === 403)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

async function refresh(): Promise<void> {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await load()
    uni.showToast({ title: '已刷新', icon: 'none' })
  } finally {
    refreshing.value = false
  }
}

function contact(): void {
  const current = enrollment.value
  if (!current) {
    uni.showToast({ title: '请稍后再试', icon: 'none' })
    return
  }
  void contactOrganizer(current.activity)
}

watch(() => [props.refreshKey, props.accessToken, props.enrollmentId, props.changeId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="change" data-testid="activity-change">
    <AppPageHeader title="退改详情" test-id="activity-change" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="change__body" data-testid="activity-change-loading" aria-label="正在加载">
      <view class="change__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="change__state" data-testid="activity-change-missing">
      <text class="change__state-title">没有找到这条退改记录</text>
      <button class="change__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="change__state" data-testid="activity-change-error">
      <text class="change__state-title">加载失败</text>
      <text class="change__state-text">请检查网络后重试</text>
      <button class="change__state-button" data-testid="activity-change-retry" @click="load">重试</button>
    </view>

    <view v-else-if="detail && view" class="change__body">
      <view class="change__card change__card--status">
        <text class="change__status-title" data-testid="activity-change-status" :style="{ color: view.statusColor }">{{ view.statusTitle }}</text>
        <text class="change__status-sub" data-testid="activity-change-status-sub">{{ view.statusSub }}</text>
        <view class="change__info" data-testid="activity-change-info">
          <view v-for="row in view.info" :key="row.label" class="change__info-row">
            <text class="change__info-label">{{ row.label }}</text>
            <text :class="['change__info-value', row.strong ? `change__info-value--${row.strong.toLowerCase()}` : '']">{{ row.value }}</text>
          </view>
        </view>
      </view>

      <view v-if="currentTicket" class="change__card change__card--ticket" data-testid="activity-change-ticket">
        <view class="change__ticket-head">
          <text class="change__heading">新凭证</text>
          <text class="change__issued">已发放</text>
          <text class="change__ticket-note">一人一码</text>
        </view>
        <view class="change__chips">
          <button
            v-for="item in newTickets"
            :key="item.ticketId"
            :class="['change__chip', { 'change__chip--on': item.ticketId === currentTicket.ticketId }]"
            :aria-pressed="item.ticketId === currentTicket.ticketId"
            :data-testid="`activity-change-person-${item.ticketId}`"
            @click="pickedTicketId = item.ticketId"
          >
            {{ item.participantName }}
          </button>
        </view>
        <view class="change__ticket-body">
          <image class="change__qr" :src="qrImage" mode="aspectFit" aria-label="签到二维码" />
          <view class="change__ticket-text">
            <text class="change__ticket-day">{{ newSessionDay }}</text>
            <text class="change__ticket-line">{{ newSessionCheckIn }}</text>
            <text v-if="enrollment" class="change__ticket-line">{{ enrollment.location.name }}</text>
            <text class="change__ticket-code">凭证号 <text data-testid="activity-change-code">{{ currentTicket.ticketCode }}</text></text>
          </view>
        </view>
      </view>

      <view class="change__card change__card--steps" data-testid="activity-change-steps">
        <view class="change__steps-head">
          <text class="change__heading">退改进度</text>
          <button
            v-if="view.refreshable"
            class="change__refresh"
            data-testid="activity-change-refresh"
            :disabled="refreshing"
            @click="refresh"
          >
            刷新进度
          </button>
        </view>
        <view class="change__steps" role="list" aria-label="退改进度">
          <view
            v-for="(step, index) in view.steps"
            :key="step.title"
            class="change__step"
            role="listitem"
            :data-kind="step.kind"
          >
            <view class="change__step-mark">
              <view
                v-if="index < view.steps.length - 1"
                :class="['change__step-line', `change__step-line--${view.steps[index + 1].kind.toLowerCase()}`]"
              />
              <view :class="['change__dot', `change__dot--${step.kind.toLowerCase()}`]">
                <LineIcon v-if="step.kind === 'DONE'" name="check" :size="9" color="#ffffff" :stroke-width="4" />
                <view v-else-if="step.kind === 'CURRENT'" class="change__dot-inner" />
                <LineIcon v-else-if="step.kind === 'END'" name="close" :size="8" color="#ffffff" :stroke-width="4" />
              </view>
            </view>
            <view class="change__step-text">
              <text :class="['change__step-title', `change__step-title--${step.kind.toLowerCase()}`]">{{ step.title }}</text>
              <text v-if="step.sub" :class="['change__step-sub', `change__step-sub--${step.kind.toLowerCase()}`]">{{ step.sub }}</text>
            </view>
          </view>
        </view>
      </view>

      <view class="change__card change__card--apply" data-testid="activity-change-apply">
        <text class="change__heading">申请信息</text>
        <view class="change__apply-summary">
          <image v-if="coverUrl" class="change__cover" :src="coverUrl" :alt="detail.activityTitle" mode="aspectFill" />
          <view class="change__apply-text">
            <text class="change__apply-title">{{ detail.activityTitle }}</text>
            <text class="change__apply-sub">{{ applySummary }}</text>
            <text class="change__apply-sub">{{ paidLine }}</text>
          </view>
        </view>
        <view class="change__apply-grid">
          <template v-for="row in view.apply" :key="row.label">
            <text class="change__apply-label">{{ row.label }}</text>
            <text class="change__apply-value">{{ row.value }}</text>
          </template>
        </view>
      </view>
    </view>

    <view v-if="detail" class="change__actions">
      <button class="change__contact" data-testid="activity-change-contact" @click="contact">
        <LineIcon name="chatDots" :size="22" color="#536178" :stroke-width="1.8" />
        <text>联系主办方</text>
      </button>
      <button
        class="change__primary"
        data-testid="activity-change-order"
        @click="emit('openOrder', detail.enrollmentId, enrollment?.payment.status === 'PAID')"
      >
        查看订单
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board EnrollRefundDetail (390 wide). */
.change {
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

.change__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.change__skeleton {
  height: 180px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.change__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.change__state-title {
  font-size: 16px;
  font-weight: 700;
}

.change__state-text {
  color: #738096;
  font-size: 13px;
}

.change__state-button {
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

.change__card {
  display: flex;
  flex-direction: column;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.change__card--status {
  gap: 4px;
}

.change__status-title {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.change__status-sub {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.change__info {
  display: flex;
  margin-top: 8px;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 20px;
}

.change__info-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.change__info-label {
  width: 56px;
  flex: none;
  color: #627187;
  white-space: nowrap;
}

.change__info-value {
  min-width: 0;
  color: #202c42;
  font-size: 13px;
}

.change__info-value--amount {
  color: #e85400;
  font-size: 15px;
  font-weight: 700;
}

.change__info-value--bold {
  font-weight: 700;
}

.change__heading {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.change__card--ticket {
  gap: 10px;
}

.change__ticket-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.change__issued {
  padding: 1px 6px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.change__ticket-note {
  margin-left: auto;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.change__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.change__chip {
  display: flex;
  width: auto;
  flex: none;
  align-items: center;
  height: 36px;
  padding: 0 14px;
  border: 1px solid #e5ebf3;
  border-radius: 999px;
  color: #536178;
  background: #ffffff;
  font-size: 14px;
  white-space: nowrap;
}

.change__chip--on {
  border-color: #ffd8b8;
  color: #ba470b;
  background: #fff0e3;
  font-weight: 500;
}

.change__ticket-body {
  display: flex;
  align-items: center;
  gap: 14px;
}

.change__qr {
  display: block;
  width: 112px;
  height: 112px;
  flex: none;
}

.change__ticket-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.change__ticket-day {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.change__ticket-line {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.change__ticket-code {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.change__card--steps {
  gap: 12px;
  padding-bottom: 0;
}

.change__steps-head {
  display: flex;
  min-height: 22px;
  align-items: center;
}

.change__refresh {
  display: flex;
  width: auto;
  flex: none;
  align-items: center;
  height: 44px;
  margin: -11px 0 -11px auto;
  padding: 0 0 0 12px;
  color: #ba470b;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.change__steps {
  display: flex;
  flex-direction: column;
}

.change__step {
  display: flex;
  gap: 10px;
  padding-bottom: 14px;
}

.change__step-mark {
  position: relative;
  width: 16px;
  flex: none;
}

.change__step-line {
  position: absolute;
  top: 10px;
  bottom: -24px;
  left: 7px;
  width: 2px;
  background: #e5ebf3;
}

.change__step-line--done,
.change__step-line--current {
  background: #ff6a00;
}

.change__step-line--end {
  background: #c9d3e1;
}

.change__dot {
  position: absolute;
  top: 2px;
  left: 0;
  display: flex;
  width: 16px;
  height: 16px;
  align-items: center;
  justify-content: center;
  border: 2px solid #c9d3e1;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.change__dot--done {
  border-color: #ff6a00;
  background: #ff6a00;
}

.change__dot--current {
  border-color: #ff6a00;
}

.change__dot--end {
  border-color: #738096;
  background: #738096;
}

.change__dot-inner {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ff6a00;
}

.change__step-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.change__step-title {
  color: #738096;
  font-size: 14px;
  line-height: 20px;
}

.change__step-title--done {
  color: #202c42;
  font-weight: 500;
}

.change__step-title--current {
  color: #ba470b;
  font-weight: 700;
}

.change__step-title--end {
  color: #536178;
  font-weight: 700;
}

.change__step-sub {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.change__step-sub--current {
  color: #ba470b;
}

.change__step-sub--todo {
  color: #91a0b4;
}

.change__card--apply {
  gap: 10px;
}

.change__apply-summary {
  display: flex;
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f1f3;
}

.change__cover {
  display: block;
  width: 56px;
  height: 56px;
  flex: none;
  border-radius: 8px;
  background: #eef2f8;
}

.change__apply-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.change__apply-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.change__apply-sub {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.change__apply-grid {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 8px 12px;
  font-size: 13px;
  line-height: 18px;
}

.change__apply-label {
  color: #627187;
  white-space: nowrap;
}

.change__actions {
  position: sticky;
  bottom: 0;
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.change__contact {
  display: flex;
  width: 60px;
  height: 46px;
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

.change__primary {
  display: flex;
  height: 46px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
}
</style>
