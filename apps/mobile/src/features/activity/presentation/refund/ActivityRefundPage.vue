<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { formatEnrollmentSession, refundAmountText } from '../../application/enrollment-order-view'
import {
  CHANGE_REASONS,
  availableModes,
  cancellableTickets,
  changeReasonLabel,
  describeModes,
  describePeopleRows,
  describeRefundNote,
  describeRefundSide,
  describeRescheduleOptions,
  initialCancelSelection,
  movableTickets,
  type RefundMode,
} from '../../application/reschedule-view'
import { activityApi } from '../../data/activity-api-client'
import { enrollmentApi } from '../../data/enrollment-api-client'
import { resolveActivityPreviewCover } from '../../data/preview/activity-preview-covers'
import type { ActivitySession } from '../../domain/activity'
import type { ChangeReason, Enrollment } from '../../domain/enrollment'
import { buildActivityCalendarUrl } from '../../navigation/activity-routes'
import { formatActivityAmount } from '../shared/activity-format'

// 申请退改 (board EnrollRefund; R5 #5 of 2026-11-20, reschedule and reasons in S5-3). Both ways take effect
// at once, without the organiser: 取消报名 cancels the ticked people (a paid one is refunded at the unit
// price, 提交即退); 改到其他场次 moves the whole sign-up to another open session of the same activity with
// enough seats, voids the old tickets and issues new ones. The reason is kept on the change record.
const props = defineProps<{
  enrollmentId: string
  // The person the ticket page was showing; ticked to start with.
  ticketId: string | null
  // 改到其他场次 when opened from the ticket's 改期 link; otherwise 取消 comes first when it is allowed.
  initialMode?: RefundMode | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  // The cancel or reschedule went through: the host opens its 退改详情.
  changed: [enrollmentId: string, changeId: string]
  // It went through but the server sent no change record (an older server): the host goes back.
  done: [enrollmentId: string]
}>()

type RefundState =
  | { kind: 'loading' }
  | { kind: 'ready'; enrollment: Enrollment }
  | { kind: 'missing' }
  | { kind: 'error' }

// Board EnrollRefund shows four session tiles; the rest are behind 更多日期 (the month calendar).
const SHOWN_SESSIONS = 4

const state = ref<RefundState>({ kind: 'loading' })
const mode = ref<RefundMode>('REFUND')
const picked = ref<string[]>([])
const sessions = ref<ActivitySession[]>([])
const targetSessionId = ref<string | null>(null)
const reason = ref<ChangeReason>('TIME_CONFLICT')
const pickerOpen = ref(false)
const submitting = ref(false)
let requestVersion = 0

const enrollment = computed(() => (state.value.kind === 'ready' ? state.value.enrollment : null))
const modes = computed(() => (enrollment.value ? describeModes(enrollment.value) : []))
const isMove = computed(() => mode.value === 'MOVE')
const paid = computed(() => enrollment.value?.payment.status === 'PAID')
const rows = computed(() => (enrollment.value ? describePeopleRows(enrollment.value, mode.value) : []))
const open = computed(() => (enrollment.value ? availableModes(enrollment.value).length > 0 : false))
const moveCount = computed(() => (enrollment.value ? movableTickets(enrollment.value).length : 0))
const options = computed(() =>
  enrollment.value
    ? describeRescheduleOptions(sessions.value, enrollment.value.session.sessionId, moveCount.value)
    : [])
// The first four, plus the one picked in the calendar when it is further out.
const shownOptions = computed(() => {
  const first = options.value.slice(0, SHOWN_SESSIONS)
  const target = options.value.find((option) => option.sessionId === targetSessionId.value)
  return target && !first.includes(target) ? [...first.slice(0, SHOWN_SESSIONS - 1), target] : first
})
const coverUrl = computed(() =>
  enrollment.value
    ? enrollment.value.activity.coverImage?.url ?? resolveActivityPreviewCover(enrollment.value.activity.activityId)
    : null)
const summary = computed(() => {
  const current = enrollment.value
  if (!current) return ''
  const amount = current.payment.status === 'PAID' ? current.payment.amountMinor : null
  return amount ? `${formatEnrollmentSession(current)} · 实付 ${formatActivityAmount(amount)}` : `${formatEnrollmentSession(current)} · 免费报名`
})
const peopleHint = computed(() => {
  if (isMove.value) return '改期按整单'
  return picked.value.length === 0 ? '至少选择 1 位参与人' : '可只退改其中一人'
})
const amountText = computed(() => (enrollment.value ? refundAmountText(enrollment.value, picked.value.length) : ''))
const breakdown = computed(() => (enrollment.value ? describeRefundSide(enrollment.value, picked.value.length) : ''))
const note = computed(() => (enrollment.value ? describeRefundNote(mode.value, enrollment.value) : ''))
const canSubmit = computed(() => {
  if (!open.value || submitting.value) return false
  return isMove.value ? Boolean(targetSessionId.value) : picked.value.length > 0
})

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

async function loadSessions(loaded: Enrollment): Promise<void> {
  if (!loaded.reschedulable) {
    sessions.value = []
    return
  }
  const detail = await activityApi.loadDetail(loaded.activity.activityId, loaded.activity.cityCode).catch(() => null)
  sessions.value = detail?.sessions ?? []
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
    const still = new Set(cancellableTickets(loaded).map((ticket) => ticket.ticketId))
    picked.value = first
      ? initialCancelSelection(loaded, props.ticketId)
      : picked.value.filter((id) => still.has(id))
    const allowed = availableModes(loaded)
    if (first || !allowed.includes(mode.value)) {
      // Opened from the 改期 link: moving; otherwise cancelling comes first when it is allowed.
      const wanted = first && props.initialMode && allowed.includes(props.initialMode) ? props.initialMode : null
      mode.value = wanted ?? (allowed.includes('REFUND') ? 'REFUND' : allowed[0] ?? 'REFUND')
    }
    await loadSessions(loaded)
    if (requested !== requestVersion) return
    const target = describeRescheduleOptions(sessions.value, loaded.session.sessionId, movableTickets(loaded).length)
      .find((option) => option.sessionId === targetSessionId.value)
    if (!target?.selectable) {
      targetSessionId.value = describeRescheduleOptions(sessions.value, loaded.session.sessionId, movableTickets(loaded).length)
        .find((option) => option.selectable)?.sessionId ?? null
    }
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

function pickTarget(sessionId: string, selectable: boolean): void {
  if (selectable) targetSessionId.value = sessionId
}

function pickReason(code: ChangeReason): void {
  reason.value = code
  pickerOpen.value = false
}

// 更多日期: the whole month; the chosen session comes back over the event channel.
function openCalendar(): void {
  const current = enrollment.value
  if (!current) return
  uni.navigateTo({
    url: buildActivityCalendarUrl(current.activity.activityId, current.activity.cityCode, {
      sessionId: targetSessionId.value ?? undefined,
      exceptSessionId: current.session.sessionId,
      people: moveCount.value,
    }),
    events: {
      picked: (data: { sessionId?: string }) => {
        if (data?.sessionId) targetSessionId.value = data.sessionId
      },
    },
  })
}

// The button stays orange like the board (R5 #1); with nothing chosen it says what is missing.
function askSubmit(): void {
  const current = enrollment.value
  if (!current || submitting.value) return
  if (!open.value) {
    uni.showToast({ title: '这笔报名现在不能退改', icon: 'none' })
    return
  }
  if (isMove.value) {
    const target = options.value.find((option) => option.sessionId === targetSessionId.value)
    if (!target) {
      uni.showToast({ title: '请选择新场次', icon: 'none' })
      return
    }
    uni.showModal({
      title: '确认改期？',
      content: `${moveCount.value} 人改到 ${target.day} ${target.sub.split(' · ')[0]}，原凭证作废，新凭证立即发放。`,
      confirmText: '确认改期',
      cancelText: '再想想',
      success: (result) => {
        if (result.confirm) void submit()
      },
    })
    return
  }
  if (picked.value.length === 0) {
    uni.showToast({ title: '请选择要退改的参与人', icon: 'none' })
    return
  }
  uni.showModal({
    title: paid.value ? '确认退款？' : '确认取消？',
    content: paid.value
      ? `取消 ${picked.value.length} 人的报名，名额会让给别人，原路退回 ${amountText.value}。`
      : `取消 ${picked.value.length} 人的报名，名额会让给别人。`,
    confirmText: paid.value ? '确认退款' : '确认取消',
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
  const moving = isMove.value
  try {
    const updated = moving
      ? await enrollmentApi.reschedule(token, current.enrollmentId, targetSessionId.value as string, reason.value)
      // Every ticked ticket at once; leaving none out means the server would cancel them all.
      : await enrollmentApi.cancel(token, current.enrollmentId, [...picked.value], reason.value)
    state.value = { kind: 'ready', enrollment: updated }
    uni.showToast({
      title: moving ? '已改期，新凭证已发放' : updated.payment.status === 'PAID' ? '已取消，报名费原路退回' : '已取消',
      icon: 'none',
    })
    const latest = updated.changes[0]
    if (latest) emit('changed', updated.enrollmentId, latest.changeId)
    else emit('done', updated.enrollmentId)
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({
      title: error instanceof ApiRequestError ? error.message : '没有提交成功，请稍后重试',
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
    <AppPageHeader title="申请退改" centered test-id="activity-refund" @back="emit('back')" />

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
          <text
            :class="['enroll-refund__heading-note', { 'enroll-refund__heading-note--ask': !isMove && picked.length === 0 }]"
          >
            {{ peopleHint }}
          </text>
        </view>
        <button
          v-for="row in rows"
          :key="row.ticketId"
          :class="['enroll-refund__person', { 'enroll-refund__person--off': !row.selectable && !row.locked }]"
          :data-testid="`activity-refund-person-${row.ticketId}`"
          :data-name="row.name"
          role="checkbox"
          :aria-checked="row.locked || picked.includes(row.ticketId)"
          :aria-disabled="!row.selectable"
          @click="toggle(row.ticketId, row.selectable)"
        >
          <view
            :class="[
              'enroll-refund__box',
              {
                'enroll-refund__box--on': row.locked || (row.selectable && picked.includes(row.ticketId)),
                'enroll-refund__box--locked': row.locked,
                'enroll-refund__box--off': !row.selectable && !row.locked,
              },
            ]"
          >
            <LineIcon
              v-if="row.locked || (row.selectable && picked.includes(row.ticketId))"
              name="check"
              :size="14"
              color="#ffffff"
              :stroke-width="2.6"
            />
          </view>
          <text class="enroll-refund__person-name">{{ row.name }}</text>
          <text v-if="row.ageText" class="enroll-refund__person-age">{{ row.ageText }}</text>
          <text class="enroll-refund__person-side">{{ row.sideText }}</text>
        </button>
        <text v-if="isMove" class="enroll-refund__lock-note" data-testid="activity-refund-whole">
          改期按整单，所有未使用的参与人一起改
        </text>
      </view>

      <view v-if="modes.length > 0" class="enroll-refund__card enroll-refund__card--modes" data-testid="activity-refund-modes">
        <text class="enroll-refund__heading-title">选择处理方式</text>
        <view class="enroll-refund__modes" role="radiogroup" aria-label="处理方式">
          <button
            v-for="item in modes"
            :key="item.mode"
            :class="['enroll-refund__mode', { 'enroll-refund__mode--on': item.mode === mode }]"
            role="radio"
            :aria-checked="item.mode === mode"
            :data-testid="`activity-refund-mode-${item.mode.toLowerCase()}`"
            @click="mode = item.mode"
          >
            <view v-if="item.mode === mode" class="enroll-refund__radio enroll-refund__radio--on">
              <LineIcon name="check" :size="13" color="#ffffff" :stroke-width="3" />
            </view>
            <view v-else class="enroll-refund__radio" />
            <view class="enroll-refund__mode-text">
              <text class="enroll-refund__mode-title">{{ item.title }}</text>
              <text class="enroll-refund__mode-sub">{{ item.subtitle }}</text>
            </view>
          </button>
        </view>
      </view>

      <view v-if="isMove" class="enroll-refund__card enroll-refund__card--sessions" data-testid="activity-refund-sessions">
        <view class="enroll-refund__heading">
          <text class="enroll-refund__heading-title">选择新场次</text>
          <button class="enroll-refund__more" data-testid="activity-refund-more-dates" @click="openCalendar">
            更多日期
            <LineIcon name="forward" :size="14" color="#ba470b" :stroke-width="2" />
          </button>
        </view>
        <view v-if="shownOptions.length > 0" class="enroll-refund__sessions">
          <button
            v-for="option in shownOptions"
            :key="option.sessionId"
            :class="[
              'enroll-refund__session',
              { 'enroll-refund__session--on': option.sessionId === targetSessionId, 'enroll-refund__session--off': !option.selectable },
            ]"
            :data-testid="`activity-refund-session-${option.sessionId}`"
            :aria-pressed="option.sessionId === targetSessionId"
            :aria-disabled="!option.selectable"
            @click="pickTarget(option.sessionId, option.selectable)"
          >
            <text class="enroll-refund__session-day">{{ option.day }}</text>
            <text class="enroll-refund__session-sub">{{ option.sub }}</text>
          </button>
        </view>
        <text v-else class="enroll-refund__empty">这个活动暂时没有别的场次</text>
      </view>

      <view class="enroll-refund__card enroll-refund__card--rows">
        <button
          class="enroll-refund__row enroll-refund__row--button"
          aria-haspopup="dialog"
          data-testid="activity-refund-reason"
          @click="pickerOpen = true"
        >
          <text class="enroll-refund__row-label">申请原因</text>
          <text class="enroll-refund__row-value enroll-refund__row-grow" data-testid="activity-refund-reason-value">{{ changeReasonLabel(reason) }}</text>
          <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
        <view v-if="isMove" class="enroll-refund__row">
          <text class="enroll-refund__row-label">费用变化</text>
          <text class="enroll-refund__row-value">无需补差价</text>
        </view>
        <template v-else-if="paid">
          <view class="enroll-refund__row">
            <text class="enroll-refund__row-label">退款金额</text>
            <text class="enroll-refund__amount" data-testid="activity-refund-amount">{{ amountText || '—' }}</text>
            <text class="enroll-refund__row-side">{{ breakdown }}</text>
          </view>
          <view class="enroll-refund__row">
            <text class="enroll-refund__row-label">退款去向</text>
            <text class="enroll-refund__row-value">微信支付 原路退回</text>
          </view>
        </template>
        <view v-else class="enroll-refund__row">
          <text class="enroll-refund__row-label">费用</text>
          <text class="enroll-refund__row-value">免费报名，不涉及退款</text>
        </view>
      </view>

      <view class="enroll-refund__tip" data-testid="activity-refund-tip">
        <LineIcon name="info" :size="16" color="#ff6a00" :stroke-width="2" />
        <text>{{ open ? note : '活动已开始或没有可退改的参与人，不能再申请退改。' }}</text>
      </view>
    </view>

    <view v-if="enrollment" class="enroll-refund__actions">
      <button
        :class="['enroll-refund__submit', { 'enroll-refund__submit--off': !canSubmit }]"
        data-testid="activity-refund-submit"
        :disabled="submitting"
        @click="askSubmit"
      >
        {{ submitting ? '提交中…' : '提交申请' }}
      </button>
    </view>

    <view v-if="pickerOpen" class="enroll-refund__mask" data-testid="activity-refund-reason-sheet">
      <button class="enroll-refund__mask-close" aria-label="关闭" @click="pickerOpen = false" />
      <view class="enroll-refund__sheet" role="dialog" aria-label="选择申请原因">
        <view class="enroll-refund__sheet-head">
          <text class="enroll-refund__sheet-title">选择申请原因</text>
          <button class="enroll-refund__sheet-x" aria-label="关闭" @click="pickerOpen = false">
            <LineIcon name="close" :size="20" color="#536178" :stroke-width="2" />
          </button>
        </view>
        <view class="enroll-refund__reasons" role="radiogroup" aria-label="申请原因">
          <button
            v-for="item in CHANGE_REASONS"
            :key="item.code"
            :class="['enroll-refund__reason', { 'enroll-refund__reason--on': item.code === reason }]"
            role="radio"
            :aria-checked="item.code === reason"
            :data-testid="`activity-refund-reason-${item.code}`"
            @click="pickReason(item.code)"
          >
            <text class="enroll-refund__reason-label">{{ item.label }}</text>
            <LineIcon v-if="item.code === reason" name="check" :size="18" color="#ff6a00" :stroke-width="2.6" />
          </button>
        </view>
      </view>
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

/* S5-3: 处理方式, 新场次, 申请原因 (board EnrollRefund). */
.enroll-refund__heading-note--ask {
  color: #ba470b;
}

.enroll-refund__box--locked {
  opacity: 0.6;
}

.enroll-refund__lock-note {
  padding: 2px 0 8px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.enroll-refund__card--modes,
.enroll-refund__card--sessions {
  gap: 10px;
  padding: 14px;
}

.enroll-refund__modes {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.enroll-refund__mode {
  display: flex;
  width: 100%;
  padding: 12px;
  align-items: center;
  gap: 12px;
  border: 1.5px solid #e5ebf3;
  border-radius: 12px;
  color: #202c42;
  background: #ffffff;
  text-align: left;
  box-sizing: border-box;
}

.enroll-refund__mode--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.enroll-refund__radio {
  display: flex;
  width: 22px;
  height: 22px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #c9d3e1;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.enroll-refund__radio--on {
  border: 0;
  background: #ff6a00;
}

.enroll-refund__mode-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.enroll-refund__mode-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.enroll-refund__mode-sub {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.enroll-refund__more {
  display: flex;
  margin-left: auto;
  align-items: center;
  gap: 2px;
  color: #ba470b;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.enroll-refund__sessions {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.enroll-refund__session {
  display: flex;
  min-height: 60px;
  padding: 8px 10px;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  border: 1.5px solid #e5ebf3;
  border-radius: 10px;
  background: #ffffff;
  text-align: left;
  box-sizing: border-box;
}

.enroll-refund__session--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.enroll-refund__session--off {
  background: #f5f7fc;
}

.enroll-refund__session-day {
  color: #202c42;
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
  white-space: nowrap;
}

.enroll-refund__session--on .enroll-refund__session-day {
  color: #ba470b;
}

.enroll-refund__session--off .enroll-refund__session-day {
  color: #738096;
}

.enroll-refund__session-sub {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.enroll-refund__session--off .enroll-refund__session-sub {
  color: #91a0b4;
}

.enroll-refund__empty {
  color: #738096;
  font-size: 13px;
  line-height: 19px;
}

.enroll-refund__row--button {
  width: 100%;
  color: #202c42;
  text-align: left;
}

.enroll-refund__row-grow {
  min-width: 0;
  flex: 1;
}

.enroll-refund__mask {
  position: fixed;
  z-index: 50;
  inset: 0;
}

.enroll-refund__mask-close {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.45);
}

.enroll-refund__sheet {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  padding: 8px 16px calc(24px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 8px;
  border-radius: 20px 20px 0 0;
  background: #ffffff;
}

.enroll-refund__sheet-head {
  display: flex;
  align-items: center;
}

.enroll-refund__sheet-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.enroll-refund__sheet-x {
  display: flex;
  width: 44px;
  height: 44px;
  margin-left: auto;
  align-items: center;
  justify-content: flex-end;
}

.enroll-refund__reasons {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.enroll-refund__reason {
  display: flex;
  width: 100%;
  min-height: 48px;
  padding: 0 12px;
  align-items: center;
  gap: 10px;
  border: 1.5px solid #e5ebf3;
  border-radius: 10px;
  color: #202c42;
  background: #ffffff;
  text-align: left;
  box-sizing: border-box;
}

.enroll-refund__reason--on {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fffaf5;
  font-weight: 500;
}

.enroll-refund__reason-label {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  line-height: 21px;
}
</style>
