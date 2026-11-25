<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import { chooseNavigation, viewDestination } from '@/shared/map/destination-actions'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { ACTIVITY_PREVIEW_ENABLED } from '../../application/activity-preview'
import { formatSessionRef } from '../../application/change-view'
import { canRequestRefund } from '../../application/enrollment-order-view'
import { hasResultPage } from '../../application/result-view'
import {
  cancelRefundMinor,
  describeCancelConfirm,
  describeTicket,
  describeTicketRefund,
  describeTicketSteps,
  formatPayCountdown,
  paymentRemainingMs,
  pickInitialTicket,
} from '../../application/enrollment-view'
import { ticketQrImage } from '@/shared/qr/ticket-qr'
import { enrollmentApi } from '../../data/enrollment-api-client'
import type { Enrollment, EnrollmentTicket } from '../../domain/enrollment'
import {
  formatActivityAmount,
  formatActivityDateTime,
  formatActivityShortDateTime,
} from '../shared/activity-format'

const props = defineProps<{
  enrollmentId: string
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  openActivity: [activityId: string, cityCode: string]
  // 去付款: the trade cashier of this sign-up's order.
  pay: [tradeOrderId: string]
  // The order of a paid sign-up (board OrderEnroll).
  openOrder: [enrollmentId: string]
  // 申请退改 (board EnrollRefund), starting on the person shown; `move` opens it on 改到其他场次.
  refund: [enrollmentId: string, ticketId: string, move: boolean]
  // S5-3: a team sign-up has its own page (board TeamOrder); the host swaps this page for it.
  team: [enrollmentId: string]
  // S5-3: 查看结果记录 (board EnrollResult) and 查看退款进度 (board EnrollRefundDetail).
  openResult: [enrollmentId: string]
  openChange: [enrollmentId: string, changeId: string]
}>()

// Board EnrollTicket's 申请退改 mark: an arrow turning back.
const UNDO_ICON = 'M9 7l-5 5 5 5M4 12h11a5 5 0 0 1 5 5v1'

// Once the time to pay has run out the server closes the order within a minute; the page asks it
// again this often (and at most this many times) instead of deciding on its own that it closed.
const OVERDUE_RELOAD_MS = 10_000
const OVERDUE_RELOADS = 6

type TicketState =
  | { kind: 'loading' }
  | { kind: 'ready'; enrollment: Enrollment }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<TicketState>({ kind: 'loading' })
const selectedTicketId = ref<string | null>(null)
const cancelSheetOpen = ref(false)
const cancelling = ref(false)
const now = ref(Date.now())
let requestVersion = 0
let timer: ReturnType<typeof setInterval> | null = null
let lastOverdueReload = 0
let overdueReloads = 0

const enrollment = computed(() => state.value.kind === 'ready' ? state.value.enrollment : null)
const ticket = computed<EnrollmentTicket | null>(() =>
  enrollment.value?.tickets.find((item) => item.ticketId === selectedTicketId.value) ?? null)
const view = computed(() =>
  ticket.value && enrollment.value ? describeTicket(ticket.value, enrollment.value) : null)
const live = computed(() => view.value?.tone === 'LIVE')
const pending = computed(() => view.value?.tone === 'PENDING')
// A code that can no longer be used is drawn grey on a grey page.
const grey = computed(() => view.value?.tone === 'EXPIRED' || view.value?.tone === 'CANCELLED')
// Unpaid or closed before payment: there is no code to draw.
const qrImage = computed(() => ticket.value?.ticketCode ? ticketQrImage(ticket.value.ticketCode) : '')
const remainingMs = computed(() => enrollment.value ? paymentRemainingMs(enrollment.value, now.value) : 0)
const countdown = computed(() => formatPayCountdown(remainingMs.value))
const refund = computed(() => ticket.value?.refund ? describeTicketRefund(ticket.value.refund) : null)
// A paid sign-up's money (amount, payee, order number) lives on its order page.
// Both pages open only in preview builds.
const hasOrder = computed(() =>
  ACTIVITY_PREVIEW_ENABLED && Boolean(enrollment.value && enrollment.value.payment.status !== 'FREE'))
// A paid ticket in use is refunded on its own page; free and unpaid ones use the sheet.
const refundsOnPage = computed(() =>
  ACTIVITY_PREVIEW_ENABLED && Boolean(enrollment.value && live.value && canRequestRefund(enrollment.value)))
// S5-3: a free sign-up keeps the cancel sheet; moving it to another session has its own link.
const canMove = computed(() =>
  ACTIVITY_PREVIEW_ENABLED && Boolean(enrollment.value?.reschedulable && live.value && !refundsOnPage.value))
const resultReady = computed(() =>
  ACTIVITY_PREVIEW_ENABLED && Boolean(enrollment.value && hasResultPage(enrollment.value, now.value)))
// The cancellation behind a cancelled ticket: the newest one (a sign-up rarely has more than one).
const latestCancel = computed(() =>
  enrollment.value?.changes.find((change) => change.kind === 'CANCEL') ?? null)
const showChangeLink = computed(() =>
  ACTIVITY_PREVIEW_ENABLED && view.value?.tone === 'CANCELLED' && Boolean(latestCancel.value)
  && enrollment.value?.payment.status !== 'CLOSED')
const previousTime = computed(() =>
  enrollment.value?.reschedule ? formatSessionRef(enrollment.value.reschedule.previousSession) : null)
const statusIcon = computed(() => {
  switch (view.value?.tone) {
    case 'CANCELLED': return 'close' as const
    case 'EXPIRED': return 'info' as const
    case 'PENDING': return 'wallet' as const
    default: return 'check' as const
  }
})
const whoMeta = computed(() => {
  if (!ticket.value) return ''
  return ticket.value.age !== null ? `${ticket.value.age} 岁 · 一人一码` : '一人一码'
})
const activeTickets = computed(() =>
  enrollment.value?.tickets.filter((item) => item.status === 'ACTIVE') ?? [])
const canCancel = computed(() => Boolean(enrollment.value?.cancellable) && (live.value || pending.value))
const canPay = computed(() => pending.value && remainingMs.value > 0 && Boolean(enrollment.value?.payment.tradeOrderId))
const progress = computed(() =>
  ticket.value && enrollment.value ? describeTicketSteps(ticket.value, enrollment.value) : { steps: [], doneWidth: '0' })
// The line runs between the centres of the first and the last step.
const stepInset = computed(() => `${(50 / Math.max(progress.value.steps.length, 1)).toFixed(1)}%`)
// Unpaid sign-ups can only be cancelled whole, so the sheet counts every unpaid ticket.
const cancelCount = computed(() => (pending.value ? enrollment.value?.tickets.length ?? 0 : 1))
const cancelLine = computed(() => enrollment.value ? describeCancelConfirm(enrollment.value, cancelCount.value) : '')
const cancelOneRefund = computed(() => {
  const amount = enrollment.value ? cancelRefundMinor(enrollment.value, 1) : null
  return amount === null ? null : formatActivityAmount(amount)
})
const cancelAllRefund = computed(() => {
  const amount = enrollment.value ? cancelRefundMinor(enrollment.value, activeTickets.value.length) : null
  return amount === null ? '' : `（共退 ${formatActivityAmount(amount)}）`
})
const note = computed(() => {
  if (!view.value || !enrollment.value) return ''
  switch (view.value.tone) {
    case 'PENDING':
      return remainingMs.value > 0
        ? `请在 ${countdown.value} 内付款，超时名额自动释放`
        : '付款时间已到，正在确认订单状态'
    case 'LIVE':
      return enrollment.value.cancellable
        ? `${formatActivityShortDateTime(enrollment.value.cancellableUntil)} 前可取消`
        : '活动已开始，不能再取消'
    case 'USED':
      return '已签到的凭证不能取消'
    case 'EXPIRED':
      return '没有签到记录'
    case 'CANCELLED':
      if (enrollment.value.payment.status === 'CLOSED') return '没有扣款，需要时可以重新报名'
      if (ticket.value?.refund) {
        return ticket.value.refund.status === 'SUCCEEDED'
          ? `${formatActivityAmount(ticket.value.refund.amountMinor)} 已原路退回`
          : `${refund.value?.text} · 原路退回，到账时间以微信支付为准`
      }
      return '免费报名，不涉及退款'
  }
})

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

function show(loaded: Enrollment): void {
  if (loaded.kind === 'TEAM' && ACTIVITY_PREVIEW_ENABLED) {
    emit('team', loaded.enrollmentId)
    return
  }
  if (loaded.payment.status !== 'PENDING_PAYMENT') overdueReloads = 0
  state.value = { kind: 'ready', enrollment: loaded }
  const stillThere = loaded.tickets.some((item) => item.ticketId === selectedTicketId.value)
  if (!stillThere) selectedTicketId.value = pickInitialTicket(loaded)?.ticketId ?? null
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
    show(loaded)
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

async function cancel(ticketIds?: string[]): Promise<void> {
  const token = props.accessToken
  if (!token || !enrollment.value || cancelling.value) return
  const wasUnpaid = enrollment.value.payment.status === 'PENDING_PAYMENT'
  cancelling.value = true
  try {
    const updated = await enrollmentApi.cancel(token, enrollment.value.enrollmentId, ticketIds)
    show(updated)
    cancelSheetOpen.value = false
    // The server found the payment while closing: nothing was cancelled, the sign-up stands.
    const title = wasUnpaid && updated.payment.status === 'PAID'
      ? '已经付款，报名已生效'
      : updated.payment.status === 'PAID' ? '已取消，报名费原路退回' : '已取消'
    uni.showToast({ title, icon: 'none' })
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    cancelSheetOpen.value = false
    uni.showToast({
      title: error instanceof ApiRequestError ? error.message : '没有取消成功，请稍后重试',
      icon: 'none',
    })
    void load()
  } finally {
    cancelling.value = false
  }
}

function openNavigation(): void {
  if (enrollment.value?.location) chooseNavigation(enrollment.value.location)
}

function openMap(): void {
  if (enrollment.value?.location) viewDestination(enrollment.value.location)
}

function askCancel(): void {
  if (!canCancel.value) return
  if (refundsOnPage.value && enrollment.value && ticket.value) {
    emit('refund', enrollment.value.enrollmentId, ticket.value.ticketId, false)
    return
  }
  cancelSheetOpen.value = true
}

function openMove(): void {
  if (canMove.value && enrollment.value && ticket.value) {
    emit('refund', enrollment.value.enrollmentId, ticket.value.ticketId, true)
  }
}

function pay(): void {
  const orderId = enrollment.value?.payment.tradeOrderId
  if (canPay.value && orderId) emit('pay', orderId)
}

// Reaching the deadline asks the server; it may have closed the order, or seen a late payment.
function tick(): void {
  now.value = Date.now()
  if (!pending.value || remainingMs.value > 0 || overdueReloads >= OVERDUE_RELOADS) return
  if (now.value - lastOverdueReload < OVERDUE_RELOAD_MS) return
  lastOverdueReload = now.value
  overdueReloads += 1
  void load()
}

watch(() => [props.refreshKey, props.accessToken, props.enrollmentId], () => { void load() }, { immediate: true })

onMounted(() => {
  timer = setInterval(tick, 1000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view
    :class="['ticket', { 'ticket--grey': grey, 'ticket--plain': state.kind !== 'ready' }]"
    data-testid="activity-ticket"
  >
    <header class="ticket__header">
      <button class="ticket__back" data-testid="ticket-back" aria-label="返回" @click="emit('back')">
        <LineIcon name="back" :size="22" :color="state.kind === 'ready' ? '#ffffff' : '#202c42'" :stroke-width="2" />
      </button>
      <text class="ticket__header-title">报名凭证</text>
      <button
        v-if="hasOrder && enrollment"
        class="ticket__back ticket__order-link"
        data-testid="ticket-order"
        @click="emit('openOrder', enrollment.enrollmentId)"
      >
        订单
      </button>
      <view v-else class="ticket__back" />
    </header>

    <view v-if="state.kind === 'loading'" class="ticket__body" data-testid="ticket-loading" aria-label="正在加载">
      <view class="ticket__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="ticket__state" data-testid="ticket-missing">
      <text class="ticket__state-title">没有找到这张报名凭证</text>
      <button class="ticket__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="ticket__state" data-testid="ticket-error">
      <text class="ticket__state-title">加载失败</text>
      <text class="ticket__state-text">请检查网络后重试</text>
      <button class="ticket__state-button" data-testid="ticket-retry" @click="load">重试</button>
    </view>

    <template v-else-if="enrollment && ticket && view">
      <view class="ticket__status">
        <view class="ticket__status-line">
          <LineIcon :name="statusIcon" :size="22" color="#ffffff" :stroke-width="2.6" />
          <text class="ticket__status-title" data-testid="ticket-status-title">{{ view.title }}</text>
        </view>
        <text class="ticket__status-sub" data-testid="ticket-status-sub">{{ view.subtitle }}</text>
      </view>

      <view class="ticket__card">
        <view class="ticket__info">
          <text class="ticket__activity" data-testid="ticket-activity">{{ enrollment.activity.title }}</text>
          <view class="ticket__people">
            <button
              v-for="item in enrollment.tickets"
              :key="item.ticketId"
              :class="['ticket__person', { 'ticket__person--on': item.ticketId === ticket.ticketId }]"
              :data-testid="`ticket-person-${item.ticketId}`"
              :data-status="item.status"
              :aria-pressed="item.ticketId === ticket.ticketId"
              @click="selectedTicketId = item.ticketId"
            >
              {{ item.participantName }}
            </button>
            <text class="ticket__who-meta">{{ whoMeta }}</text>
          </view>
          <view class="ticket__facts">
            <view class="ticket__fact">
              <text class="ticket__fact-label">场次</text>
              <text class="ticket__fact-value" data-testid="ticket-session">
                {{ formatActivityDateTime(enrollment.session.startsAt) }}
              </text>
            </view>
            <view class="ticket__fact">
              <text class="ticket__fact-label">签到状态</text>
              <text
                :class="['ticket__fact-value', `ticket__fact-value--${view.tone.toLowerCase()}`]"
                data-testid="ticket-sign"
              >
                {{ view.signText }}
              </text>
            </view>
          </view>
          <view v-if="previousTime" class="ticket__place" data-testid="ticket-previous-time">
            <text class="ticket__place-label">原时间</text>
            <text class="ticket__place-text ticket__place-text--old">{{ previousTime }}（已改期）</text>
          </view>
          <view class="ticket__place" role="button" data-testid="ticket-map" @click="openMap">
            <text class="ticket__place-label">地点</text>
            <text class="ticket__place-text">{{ enrollment.location.name }} · {{ enrollment.location.address }}</text>
          </view>
          <view v-if="refund" class="ticket__place">
            <text class="ticket__place-label">退款</text>
            <text
              :class="['ticket__place-text', 'ticket__refund', `ticket__refund--${refund.tone.toLowerCase()}`]"
              data-testid="ticket-refund"
            >
              {{ refund.text }} · 原路退回
            </text>
          </view>
        </view>

        <view class="ticket__tear">
          <view class="ticket__notch ticket__notch--left" />
          <view class="ticket__notch ticket__notch--right" />
        </view>

        <view class="ticket__code-block">
          <view class="ticket__qr">
            <image
              v-if="qrImage"
              :class="['ticket__qr-image', { 'ticket__qr-image--off': !live }]"
              data-testid="ticket-qr"
              :src="qrImage"
              mode="aspectFit"
              :aria-label="live ? '签到二维码' : '已失效的二维码'"
            />
            <view v-else class="ticket__qr-empty" data-testid="ticket-qr-empty">
              <LineIcon :name="pending ? 'wallet' : 'close'" :size="34" color="#91a0b4" />
              <text class="ticket__qr-empty-text">{{ pending ? '付款后生成' : '未生成二维码' }}</text>
            </view>
            <text
              v-if="view.stamp"
              :class="['ticket__stamp', { 'ticket__stamp--used': view.tone === 'USED' }]"
              data-testid="ticket-stamp"
            >
              {{ view.stamp }}
            </text>
          </view>
          <text v-if="ticket.ticketCode" class="ticket__code">凭证号 <text data-testid="ticket-code">{{ ticket.ticketCode }}</text></text>
          <text :class="['ticket__hint', { 'ticket__hint--strong': !live }]">{{ view.hint }}</text>
        </view>
      </view>

      <view v-if="pending" class="ticket__actions">
        <button
          :class="['ticket__action', 'ticket__action--pay', { 'ticket__action--off': !canPay }]"
          data-testid="ticket-pay"
          :disabled="!canPay"
          @click="pay"
        >
          <LineIcon name="wallet" :size="22" :color="canPay ? '#ffffff' : '#91a0b4'" />
          <text>{{ canPay ? `去付款 · ${countdown}` : '付款时间已到' }}</text>
        </button>
        <button
          :class="['ticket__action', { 'ticket__action--off': !canCancel }]"
          data-testid="ticket-cancel"
          :disabled="!canCancel"
          @click="askCancel"
        >
          <LineIcon :d="UNDO_ICON" :size="22" :color="canCancel ? '#ff6a00' : '#91a0b4'" />
          <text>取消报名</text>
        </button>
      </view>
      <view v-else-if="live" class="ticket__actions">
        <button class="ticket__action" data-testid="ticket-navigate" @click="openNavigation">
          <LineIcon name="navigate" :size="22" color="#ff6a00" />
          <text>导航到场馆</text>
        </button>
        <button
          :class="['ticket__action', { 'ticket__action--off': !canCancel }]"
          data-testid="ticket-cancel"
          :data-refund-page="refundsOnPage"
          :disabled="!canCancel"
          @click="askCancel"
        >
          <LineIcon :d="UNDO_ICON" :size="22" :color="canCancel ? '#ff6a00' : '#91a0b4'" />
          <text>{{ refundsOnPage ? '申请退改' : '取消报名' }}</text>
        </button>
      </view>
      <button
        v-else-if="showChangeLink && latestCancel"
        class="ticket__next"
        data-testid="ticket-open-change"
        @click="emit('openChange', enrollment.enrollmentId, latestCancel.changeId)"
      >
        <LineIcon :d="UNDO_ICON" :size="22" color="#ff6a00" />
        <text class="ticket__next-label">查看退款进度</text>
        <LineIcon name="forward" :size="16" color="#91a0b4" :stroke-width="2" />
      </button>
      <button
        v-else-if="resultReady"
        class="ticket__next"
        data-testid="ticket-open-result"
        @click="emit('openResult', enrollment.enrollmentId)"
      >
        <LineIcon name="doc" :size="22" color="#ff6a00" />
        <text class="ticket__next-label">查看结果记录</text>
        <LineIcon name="forward" :size="16" color="#91a0b4" :stroke-width="2" />
      </button>
      <button
        v-else
        class="ticket__next"
        data-testid="ticket-open-activity"
        @click="emit('openActivity', enrollment.activity.activityId, enrollment.activity.cityCode)"
      >
        <LineIcon name="flag" :size="22" color="#ff6a00" />
        <text class="ticket__next-label">查看活动详情</text>
        <LineIcon name="forward" :size="16" color="#91a0b4" :stroke-width="2" />
      </button>
      <button v-if="canMove" class="ticket__move" data-testid="ticket-move" @click="openMove">
        <LineIcon name="calendar" :size="16" color="#ba470b" :stroke-width="2" />
        <text>改到其他场次</text>
      </button>
      <button v-if="live && resultReady" class="ticket__move" data-testid="ticket-open-result" @click="emit('openResult', enrollment.enrollmentId)">
        <LineIcon name="doc" :size="16" color="#ba470b" :stroke-width="2" />
        <text>查看签到与结果记录</text>
      </button>

      <view
        class="ticket__steps"
        data-testid="ticket-steps"
        :style="{ gridTemplateColumns: `repeat(${progress.steps.length}, minmax(0, 1fr))` }"
      >
        <view class="ticket__steps-line" :style="{ left: stepInset, right: stepInset }" />
        <view
          class="ticket__steps-line ticket__steps-line--done"
          :style="{ left: stepInset, width: progress.doneWidth }"
        />
        <view v-for="step in progress.steps" :key="step.label" class="ticket__step">
          <view :class="['ticket__dot', `ticket__dot--${step.state.toLowerCase()}`]">
            <LineIcon
              v-if="step.state !== 'TODO'"
              :name="step.state === 'DONE' ? 'check' : 'close'"
              :size="11"
              color="#ffffff"
              :stroke-width="3"
            />
          </view>
          <text :class="['ticket__step-label', `ticket__step-label--${step.state.toLowerCase()}`]">
            {{ step.label }}
          </text>
        </view>
      </view>

      <view :class="['ticket__note', `ticket__note--${view.tone.toLowerCase()}`]" data-testid="ticket-note">
        <LineIcon
          :name="live || pending ? 'clock' : 'info'"
          :size="16"
          :color="live ? '#159a70' : pending ? '#ff6a00' : '#738096'"
          :stroke-width="2"
        />
        <text>{{ note }}</text>
      </view>
    </template>

    <view v-if="cancelSheetOpen && enrollment && ticket" class="ticket__mask" @click.self="cancelSheetOpen = false">
      <view class="ticket__sheet" role="dialog" aria-label="取消报名" data-testid="ticket-cancel-sheet">
        <text class="ticket__sheet-title">取消报名？</text>
        <text class="ticket__sheet-text" data-testid="ticket-cancel-text">{{ cancelLine }}</text>
        <view class="ticket__sheet-row">
          <text class="ticket__sheet-label">场次</text>
          <text>{{ formatActivityDateTime(enrollment.session.startsAt) }}</text>
        </view>
        <view class="ticket__sheet-row">
          <text class="ticket__sheet-label">参与人</text>
          <text>{{ pending ? enrollment.tickets.map((item) => item.participantName).join('、') : ticket.participantName }}</text>
        </view>
        <view v-if="!pending && cancelOneRefund" class="ticket__sheet-row">
          <text class="ticket__sheet-label">退款</text>
          <text class="ticket__sheet-refund" data-testid="ticket-cancel-refund">原路退回 {{ cancelOneRefund }}</text>
        </view>
        <view class="ticket__sheet-actions">
          <button class="ticket__sheet-keep" @click="cancelSheetOpen = false">再想想</button>
          <button
            v-if="pending"
            class="ticket__sheet-confirm"
            data-testid="ticket-cancel-unpaid"
            :disabled="cancelling"
            @click="cancel()"
          >
            取消报名
          </button>
          <button
            v-else
            class="ticket__sheet-confirm"
            data-testid="ticket-cancel-one"
            :disabled="cancelling"
            @click="cancel([ticket.ticketId])"
          >
            取消这张凭证
          </button>
        </view>
        <button
          v-if="!pending && activeTickets.length > 1"
          class="ticket__sheet-all"
          data-testid="ticket-cancel-all"
          :disabled="cancelling"
          @click="cancel()"
        >
          {{ activeTickets.length }} 人全部取消{{ cancelAllRefund }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board EnrollTicket (390 wide). */
.ticket {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  padding-bottom: calc(24px + env(safe-area-inset-bottom));
  color: #202c42;
  background: linear-gradient(180deg, #ff7a1a 0, #ff8f3d 60px, #ffb27a 200px, #f5f7fc 340px);
  box-sizing: border-box;
  --ticket-notch: #f9e9df;
}

.ticket--grey {
  background: linear-gradient(180deg, #7d828a 0, #738096 60px, #b9bdc4 200px, #f5f7fc 340px);
  --ticket-notch: #e9ebee;
}

.ticket--plain {
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

.ticket__header {
  display: flex;
  flex: none;
  padding: calc(8px + var(--status-bar-height)) 8px 8px;
  align-items: center;
}

.ticket__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.ticket__header-title {
  flex: 1;
  color: #ffffff;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-align: center;
}

.ticket__order-link {
  color: #ffffff;
  font-size: 14px;
  line-height: 44px;
  white-space: nowrap;
}

.ticket--plain .ticket__header-title {
  color: #202c42;
}

.ticket__body {
  padding: 12px 16px;
}

.ticket__skeleton {
  height: 420px;
  border-radius: 16px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.ticket__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.ticket__state-title {
  font-size: 16px;
  font-weight: 700;
}

.ticket__state-text {
  color: #738096;
  font-size: 13px;
}

.ticket__state-button {
  min-width: 120px;
  height: 40px;
  margin-top: 8px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  line-height: 40px;
}

.ticket__status {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 4px;
  padding: 2px 16px 0;
  color: #ffffff;
}

.ticket__status-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ticket__status-title {
  font-size: 22px;
  font-weight: 700;
  line-height: 30px;
}

.ticket__status-sub {
  font-size: 13px;
  font-weight: 500;
  line-height: 19px;
}

.ticket__card {
  display: flex;
  flex: none;
  flex-direction: column;
  margin: 14px 16px 0;
  border-radius: 16px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.ticket__info {
  display: flex;
  flex-direction: column;
  padding: 16px 16px 14px;
}

.ticket__activity {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.ticket__people {
  display: flex;
  margin-top: 10px;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.ticket__person {
  height: 36px;
  padding: 0 14px;
  border: 1px solid #e5ebf3;
  border-radius: 999px;
  color: #536178;
  background: #ffffff;
  font-size: 14px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.ticket__person--on {
  border-color: #ffd8b8;
  color: #ba470b;
  background: #fff0e3;
  font-weight: 500;
}

.ticket__who-meta {
  margin-left: auto;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.ticket__facts {
  display: grid;
  margin-top: 12px;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 10px;
}

.ticket__fact {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ticket__fact-label,
.ticket__place-label {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.ticket__fact-value {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  white-space: nowrap;
}

.ticket__fact-value--live,
.ticket__fact-value--pending {
  color: #ba470b;
}

.ticket__fact-value--used {
  color: #11804a;
}

.ticket__fact-value--expired,
.ticket__fact-value--cancelled {
  color: #738096;
}

.ticket__place {
  display: flex;
  margin-top: 10px;
  gap: 8px;
  font-size: 13px;
  line-height: 19px;
}

.ticket__place-label {
  flex: none;
  font-size: 13px;
  line-height: 19px;
}

.ticket__place-text {
  min-width: 0;
}

.ticket__tear {
  position: relative;
  height: 0;
  border-top: 1.5px dashed #e3e5e8;
}

.ticket__notch {
  position: absolute;
  top: -11px;
  width: 10px;
  height: 20px;
  background: var(--ticket-notch);
}

.ticket__notch--left {
  left: 0;
  border-radius: 0 10px 10px 0;
}

.ticket__notch--right {
  right: 0;
  border-radius: 10px 0 0 10px;
}

.ticket__code-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 16px 14px;
}

.ticket__qr {
  position: relative;
  width: 168px;
  height: 168px;
}

.ticket__qr-image {
  display: block;
  width: 168px;
  height: 168px;
}

.ticket__qr-image--off {
  opacity: 0.18;
}

.ticket__qr-empty {
  display: flex;
  width: 168px;
  height: 168px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1.5px dashed #c9d3e1;
  border-radius: 12px;
  background: #f5f7fc;
  box-sizing: border-box;
}

.ticket__qr-empty-text {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.ticket__refund--pending {
  color: #ba470b;
}

.ticket__refund--done {
  color: #11804a;
}

.ticket__refund--alert {
  color: #d64f67;
}

.ticket__stamp {
  position: absolute;
  top: 42px;
  left: 42px;
  display: flex;
  width: 84px;
  height: 84px;
  align-items: center;
  justify-content: center;
  border: 2px solid #627187;
  border-radius: 50%;
  color: #627187;
  background: #ffffff;
  font-size: 17px;
  font-weight: 700;
  white-space: nowrap;
  box-sizing: border-box;
}

.ticket__stamp--used {
  border-color: #11804a;
  color: #11804a;
}

.ticket__code {
  margin-top: 8px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.ticket__hint {
  margin-top: 2px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.ticket__hint--strong {
  color: #536178;
}

.ticket__actions {
  display: grid;
  flex: none;
  margin: 12px 16px 0;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.ticket__action {
  display: flex;
  min-height: 72px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 14px;
  color: #202c42;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.ticket__action--pay {
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-weight: 500;
}

.ticket__action--off,
.ticket__action[disabled] {
  color: #91a0b4;
  background: #ffffff;
}

.ticket__next {
  display: flex;
  min-height: 56px;
  flex: none;
  margin: 12px 16px 0;
  padding: 0 14px;
  align-items: center;
  gap: 10px;
  border-radius: 14px;
  color: #202c42;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
  font-size: 15px;
  line-height: 21px;
  text-align: left;
}

.ticket__next-label {
  min-width: 0;
  flex: 1;
}

.ticket__steps {
  position: relative;
  display: grid;
  flex: none;
  margin: 12px 16px 0;
  padding: 14px 0;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.ticket__steps-line {
  position: absolute;
  top: 23px;
  right: 16.7%;
  left: 16.7%;
  height: 2px;
  background: #e5ebf3;
}

.ticket__steps-line--done {
  right: auto;
  background: #ff6a00;
}

.ticket__step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.ticket__dot {
  display: flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border: 2px solid #c9d3e1;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.ticket__dot--done {
  border-color: #ff6a00;
  background: #ff6a00;
}

.ticket__dot--ended {
  border-color: #738096;
  background: #738096;
}

.ticket__step-label {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.ticket__step-label--done {
  color: #202c42;
}

.ticket__step-label--ended {
  color: #536178;
}

.ticket__note {
  display: flex;
  flex: none;
  margin: 10px 16px 0;
  padding: 9px 12px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  color: #536178;
  background: #eef2f8;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
}

.ticket__note--live {
  color: #11804a;
  background: #e2f6ed;
}

.ticket__note--pending {
  color: #ba470b;
  background: #fff0e3;
}

.ticket__mask {
  position: fixed;
  z-index: 1200;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.45);
}

.ticket__sheet {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 10px;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  background: #ffffff;
  box-sizing: border-box;
}

.ticket__sheet-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.ticket__sheet-text {
  color: #536178;
  font-size: 13px;
  line-height: 1.6;
}

.ticket__sheet-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 14px;
  line-height: 20px;
}

.ticket__sheet-label {
  flex: none;
  color: #627187;
}

.ticket__sheet-refund {
  color: #ba470b;
  font-weight: 500;
}

.ticket__sheet-actions {
  display: flex;
  margin-top: 4px;
  gap: 10px;
}

.ticket__sheet-keep,
.ticket__sheet-confirm {
  height: 44px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
  box-sizing: border-box;
}

.ticket__sheet-keep {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
  line-height: 42px;
}

.ticket__sheet-confirm {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.ticket__sheet-all {
  height: 44px;
  color: #d64f67;
  font-size: 14px;
  line-height: 44px;
}
.ticket__place-text--old {
  color: #738096;
  text-decoration: line-through;
}

/* S5-3: 改到其他场次 / 查看签到与结果记录 under the two actions. */
.ticket__move {
  display: flex;
  flex: none;
  margin: 10px 16px 0;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 12px;
  color: #ba470b;
  background: #ffffff;
  font-size: 14px;
  line-height: 20px;
}
</style>
