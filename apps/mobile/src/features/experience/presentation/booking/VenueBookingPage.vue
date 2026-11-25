<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import { ticketQrImage } from '@/shared/qr/ticket-qr'
import { buildBeijingDayOptions, toBeijingDate, type BeijingDayOption } from '@/shared/time/beijing-time'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  describeRescheduleSlot,
  describeVenueBooking,
  describeVenueBookingSteps,
  describeVenueCancel,
  describeVenueRefund,
  formatVenueAmount,
  formatVenueCountdown,
  formatVenueSlotLine,
  venuePaymentRemainingMs,
} from '../../application/venue-booking-view'
import { experienceApi } from '../../data/experience-api-client'
import { venueBookingApi } from '../../data/venue-booking-api-client'
import type { VenueSession } from '../../domain/experience'
import type { VenueBooking, VenueOrderInfo } from '../../domain/venue-booking'
import { formatVenueSessionTime } from '../shared/experience-format'
import { chooseNavigation, viewDestination } from '@/shared/map/destination-actions'

// Board VenueCode's 取消预约 mark: an arrow turning back.
const UNDO_ICON = 'M9 7L4 12l5 5M4 12h11a5 5 0 0 1 5 5'

const props = defineProps<{
  bookingId: string
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
  loadOrderInfo: (accessToken: string, tradeOrderId: string) => Promise<VenueOrderInfo | null>
}>()

const emit = defineEmits<{
  back: []
  login: []
  // 去付款: the trade cashier of this booking's order.
  pay: [tradeOrderId: string]
  // 再约一场: the venue detail.
  openVenue: [venueId: string, cityCode: string]
  openList: []
}>()

// Once the time to pay has run out the server closes the order within a minute; the page asks it
// again this often (and at most this many times) instead of deciding on its own that it closed.
const OVERDUE_RELOAD_MS = 10_000
const OVERDUE_RELOADS = 6
const RESCHEDULE_DAYS = 7

type PageState =
  | { kind: 'loading' }
  | { kind: 'ready'; booking: VenueBooking }
  | { kind: 'missing' }
  | { kind: 'error' }

type SlotsState =
  | { kind: 'loading' }
  | { kind: 'ready'; sessions: VenueSession[] }
  | { kind: 'error' }

const state = ref<PageState>({ kind: 'loading' })
const orderInfo = ref<VenueOrderInfo | null>(null)
const now = ref(Date.now())
const rescheduledHere = ref(false)
const cancelOpen = ref(false)
const cancelling = ref(false)
const moveOpen = ref(false)
const moving = ref(false)
const moveDays = ref<BeijingDayOption[]>([])
const moveDay = ref('')
const moveSessionId = ref<string | null>(null)
const slots = ref<SlotsState>({ kind: 'loading' })
const copied = ref(false)
let requestVersion = 0
let slotsVersion = 0
let timer: ReturnType<typeof setInterval> | null = null
let lastOverdueReload = 0
let overdueReloads = 0
let place: { latitude: number | null; longitude: number | null } | null = null

const booking = computed(() => (state.value.kind === 'ready' ? state.value.booking : null))
const view = computed(() => (booking.value ? describeVenueBooking(booking.value, now.value, rescheduledHere.value) : null))
const tone = computed(() => view.value?.tone ?? null)
const live = computed(() => tone.value === 'LIVE')
const pending = computed(() => tone.value === 'PENDING')
const headTone = computed(() => {
  if (tone.value === 'USED') return 'green'
  if (tone.value === 'PENDING' || tone.value === 'LIVE') return 'orange'
  return 'grey'
})
const qrImage = computed(() => (booking.value?.code ? ticketQrImage(booking.value.code) : ''))
const remainingMs = computed(() => (booking.value ? venuePaymentRemainingMs(booking.value, now.value) : 0))
const canPay = computed(() => pending.value && remainingMs.value > 0 && Boolean(booking.value?.payment.tradeOrderId))
const slotLine = computed(() => (booking.value ? formatVenueSlotLine(booking.value.session, now.value) : ''))
const peopleLine = computed(() => {
  const current = booking.value
  if (!current) return ''
  return `${current.participants.map((person) => person.name).join('、')} · ${current.people} 人`
})
const progress = computed(() => (booking.value ? describeVenueBookingSteps(booking.value) : { steps: [], doneShare: 0 }))
// The line runs between the centres of the first and the last step.
const stepInset = computed(() => 50 / Math.max(progress.value.steps.length, 1))
const refund = computed(() => (booking.value?.refund ? describeVenueRefund(booking.value.refund) : null))
const paidBooking = computed(() => booking.value?.payment.status !== 'FREE')
const cancelCopy = computed(() => (booking.value ? describeVenueCancel(booking.value) : { lead: '', refund: null }))
const payeeName = computed(() => orderInfo.value?.payeeName ?? booking.value?.venue.name ?? '')
const moveSlots = computed(() => {
  const current = booking.value
  if (!current || slots.value.kind !== 'ready') return []
  return slots.value.sessions.map((session) => ({ session, ...describeRescheduleSlot(session, current) }))
})
const moveTarget = computed(() => moveSlots.value.find((slot) => slot.session.sessionId === moveSessionId.value && slot.selectable) ?? null)

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

function show(loaded: VenueBooking): void {
  if (loaded.status !== 'PENDING_PAYMENT') overdueReloads = 0
  state.value = { kind: 'ready', booking: loaded }
  void loadOrderInfo(loaded)
}

async function loadOrderInfo(loaded: VenueBooking): Promise<void> {
  const token = props.accessToken
  const orderId = loaded.payment.tradeOrderId
  if (!token || !orderId || orderInfo.value) return
  try {
    orderInfo.value = await props.loadOrderInfo(token, orderId)
  } catch {
    // The number is a convenience; the booking itself is already on screen.
  }
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.bookingId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await venueBookingApi.read(token, props.bookingId)
    if (requested !== requestVersion) return
    now.value = Date.now()
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

function messageOf(error: unknown, fallback: string): string {
  return error instanceof ApiRequestError && error.message ? error.message : fallback
}

async function cancel(): Promise<void> {
  const token = props.accessToken
  const current = booking.value
  if (!token || !current || cancelling.value) return
  const wasUnpaid = current.status === 'PENDING_PAYMENT'
  cancelling.value = true
  try {
    const updated = await venueBookingApi.cancel(token, current.bookingId)
    show(updated)
    cancelOpen.value = false
    // The server found the payment while closing: nothing was cancelled, the booking stands.
    let title = '预约已取消'
    if (wasUnpaid) title = updated.status === 'CONFIRMED' ? '已经付款，预约已生效' : '预约已关闭'
    else if (updated.refund) title = '已取消，费用原路退回'
    uni.showToast({ title, icon: 'none' })
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    cancelOpen.value = false
    let title = messageOf(error, '没有取消成功，请稍后重试')
    if (error instanceof ApiRequestError && error.status === 412) title = '预约有变化，已刷新'
    if (error instanceof ApiRequestError && error.status === 503) title = '微信支付暂不可用，请稍后再试'
    uni.showToast({ title, icon: 'none' })
    void load()
  } finally {
    cancelling.value = false
  }
}

async function loadSlots(): Promise<void> {
  const current = booking.value
  if (!current) return
  const requested = ++slotsVersion
  moveSessionId.value = null
  slots.value = { kind: 'loading' }
  try {
    const sessions = await experienceApi.loadSessions({
      venueId: current.venue.venueId,
      cityCode: current.venue.cityCode,
      projectId: current.project.projectId,
      date: moveDay.value,
    })
    if (requested !== slotsVersion) return
    slots.value = { kind: 'ready', sessions }
  } catch {
    if (requested !== slotsVersion) return
    slots.value = { kind: 'error' }
  }
}

function openMove(): void {
  const current = booking.value
  if (!current?.reschedulable) return
  moveDays.value = buildBeijingDayOptions(new Date(), RESCHEDULE_DAYS)
  const sessionDay = toBeijingDate(new Date(current.session.startsAt))
  moveDay.value = moveDays.value.some((day) => day.date === sessionDay) ? sessionDay : moveDays.value[0].date
  moveOpen.value = true
  void loadSlots()
}

function pickMoveDay(day: BeijingDayOption): void {
  if (day.date === moveDay.value) return
  moveDay.value = day.date
  void loadSlots()
}

function pickMoveSlot(sessionId: string, selectable: boolean): void {
  if (selectable) moveSessionId.value = sessionId
}

async function confirmMove(): Promise<void> {
  const token = props.accessToken
  const current = booking.value
  const target = moveTarget.value
  if (!token || !current || !target || moving.value) return
  moving.value = true
  try {
    const updated = await venueBookingApi.reschedule(token, current.bookingId, current.version, target.session.sessionId)
    rescheduledHere.value = true
    show(updated)
    moveOpen.value = false
    uni.showToast({ title: '改期成功，已换新码', icon: 'none' })
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    if (error instanceof ApiRequestError && error.status === 412) {
      // Someone (another device) changed the booking since this page read it.
      moveOpen.value = false
      uni.showToast({ title: '预约有变化，已刷新，请重新选择', icon: 'none' })
      void load()
      return
    }
    uni.showToast({ title: messageOf(error, '没有改期成功，请稍后重试'), icon: 'none' })
    void loadSlots()
  } finally {
    moving.value = false
  }
}

async function openLocation(action: typeof chooseNavigation): Promise<void> {
  const current = booking.value
  if (!current) return
  if (!place) {
    try {
      const venue = await experienceApi.loadVenue(current.venue.venueId, current.venue.cityCode)
      place = { latitude: venue.latitude, longitude: venue.longitude }
    } catch {
      // Keep the booked address available even when the venue is offline.
    }
  }
  action({
    name: current.venue.name,
    address: current.venue.address,
    latitude: place?.latitude ?? null,
    longitude: place?.longitude ?? null,
  })
}

function pay(): void {
  const orderId = booking.value?.payment.tradeOrderId
  if (canPay.value && orderId) emit('pay', orderId)
}

function copyOrderNo(): void {
  const orderNo = orderInfo.value?.orderNo
  if (!orderNo) return
  uni.setClipboardData({
    data: orderNo,
    showToast: false,
    success: () => { copied.value = true },
  } as UniApp.SetClipboardDataOptions)
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

watch(() => [props.refreshKey, props.accessToken, props.bookingId], () => { void load() }, { immediate: true })

onMounted(() => {
  timer = setInterval(tick, 1000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view
    :class="['venue-code', `venue-code--${state.kind === 'ready' ? headTone : 'plain'}`]"
    data-testid="venue-booking"
  >
    <view class="venue-code__head">
      <header class="venue-code__header">
        <button class="venue-code__back" data-testid="venue-booking-back" aria-label="返回" @click="emit('back')">
          <LineIcon name="back" :size="22" :color="state.kind === 'ready' ? '#ffffff' : '#202c42'" :stroke-width="2" />
        </button>
        <text class="venue-code__header-title">到馆核销码</text>
        <view class="venue-code__back" />
      </header>
      <view v-if="view" class="venue-code__status">
        <view class="venue-code__status-line">
          <LineIcon
            :name="tone === 'PENDING' ? 'wallet' : tone === 'LIVE' || tone === 'USED' ? 'check' : 'close'"
            :size="22"
            color="#ffffff"
            :stroke-width="2.6"
          />
          <text class="venue-code__status-title" data-testid="venue-booking-title">{{ view.title }}</text>
        </view>
        <text class="venue-code__status-sub" data-testid="venue-booking-sub">{{ view.subtitle }}</text>
      </view>
    </view>

    <view v-if="state.kind === 'loading'" class="venue-code__body" data-testid="venue-booking-loading" aria-label="正在加载">
      <view class="venue-code__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="venue-code__state" data-testid="venue-booking-missing">
      <text class="venue-code__state-title">没有找到这张预约</text>
      <button class="venue-code__state-button" @click="emit('openList')">我的到馆预约</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="venue-code__state" data-testid="venue-booking-error">
      <text class="venue-code__state-title">加载失败</text>
      <text class="venue-code__state-text">请检查网络后重试</text>
      <button class="venue-code__state-button" data-testid="venue-booking-retry" @click="load">重试</button>
    </view>

    <template v-else-if="booking && view">
      <view class="venue-code__content">
        <view class="venue-code__card">
          <view class="venue-code__info">
            <text class="venue-code__project" data-testid="venue-booking-project">{{ booking.project.name }}</text>
            <view class="venue-code__facts">
              <text class="venue-code__fact-label">场次</text>
              <text class="venue-code__fact-strong" data-testid="venue-booking-slot">{{ slotLine }}</text>
              <text class="venue-code__fact-label">参与人</text>
              <text data-testid="venue-booking-people">{{ peopleLine }}</text>
              <text class="venue-code__fact-label">地点</text>
              <view class="venue-code__place" role="button" data-testid="venue-booking-map" @click="openLocation(viewDestination)">
                <text>{{ booking.venue.name }}</text>
                <text class="venue-code__muted">{{ booking.venue.address }}</text>
              </view>
            </view>
          </view>

          <view class="venue-code__tear">
            <view class="venue-code__notch venue-code__notch--left" />
            <view class="venue-code__tear-line" />
            <view class="venue-code__notch venue-code__notch--right" />
          </view>

          <view class="venue-code__code-block">
            <view class="venue-code__qr">
              <image
                v-if="qrImage"
                :class="['venue-code__qr-image', { 'venue-code__qr-image--off': !live }]"
                data-testid="venue-booking-qr"
                :src="qrImage"
                mode="aspectFit"
                :aria-label="live ? '核销二维码' : '已使用的二维码'"
              />
              <view v-else class="venue-code__qr-empty" data-testid="venue-booking-qr-empty">
                <LineIcon :name="pending ? 'wallet' : 'close'" :size="34" color="#91a0b4" />
                <text class="venue-code__qr-empty-text">{{ pending ? '付款后生成' : '核销码已失效' }}</text>
              </view>
              <text
                v-if="view.stamp"
                :class="['venue-code__stamp', { 'venue-code__stamp--used': tone === 'USED' }]"
                data-testid="venue-booking-stamp"
              >
                {{ view.stamp }}
              </text>
            </view>
            <text v-if="booking.code" class="venue-code__code">凭证号 <text data-testid="venue-booking-code">{{ booking.code }}</text></text>
            <text class="venue-code__hint" data-testid="venue-booking-hint">{{ view.hint }}</text>
          </view>
        </view>

        <view v-if="live" class="venue-code__tiles">
          <button class="venue-code__tile" data-testid="venue-booking-navigate" @click="openLocation(chooseNavigation)">
            <LineIcon name="navigate" :size="22" color="#ff6a00" />
            <text>导航到场馆</text>
          </button>
          <button
            :class="['venue-code__tile', { 'venue-code__tile--off': !booking.reschedulable }]"
            data-testid="venue-booking-reschedule"
            :disabled="!booking.reschedulable"
            @click="openMove"
          >
            <LineIcon name="calendar" :size="22" :color="booking.reschedulable ? '#ff6a00' : '#91a0b4'" />
            <text>改期</text>
          </button>
          <button
            :class="['venue-code__tile', { 'venue-code__tile--off': !booking.cancellable }]"
            data-testid="venue-booking-cancel"
            :disabled="!booking.cancellable"
            @click="cancelOpen = true"
          >
            <LineIcon :d="UNDO_ICON" :size="22" :color="booking.cancellable ? '#ff6a00' : '#91a0b4'" />
            <text>取消预约</text>
          </button>
        </view>

        <view v-else-if="pending" class="venue-code__tiles venue-code__tiles--two">
          <button
            :class="['venue-code__tile', 'venue-code__tile--pay', { 'venue-code__tile--off': !canPay }]"
            data-testid="venue-booking-pay"
            :disabled="!canPay"
            @click="pay"
          >
            <LineIcon name="wallet" :size="22" :color="canPay ? '#ffffff' : '#91a0b4'" />
            <text>{{ canPay ? `去付款 · ${formatVenueCountdown(remainingMs)}` : '付款时间已到' }}</text>
          </button>
          <button
            :class="['venue-code__tile', { 'venue-code__tile--off': !booking.cancellable }]"
            data-testid="venue-booking-cancel"
            :disabled="!booking.cancellable"
            @click="cancelOpen = true"
          >
            <LineIcon :d="UNDO_ICON" :size="22" :color="booking.cancellable ? '#ff6a00' : '#91a0b4'" />
            <text>取消预约</text>
          </button>
        </view>

        <view class="venue-code__steps" data-testid="venue-booking-steps">
          <view class="venue-code__steps-track">
            <view class="venue-code__steps-line" :style="{ left: `${stepInset}%`, right: `${stepInset}%` }" />
            <view
              class="venue-code__steps-line venue-code__steps-line--done"
              :style="{ left: `${stepInset}%`, width: `${progress.doneShare * (100 - 2 * stepInset)}%` }"
            />
            <view
              class="venue-code__steps-grid"
              :style="{ gridTemplateColumns: `repeat(${progress.steps.length}, minmax(0, 1fr))` }"
            >
              <view v-for="step in progress.steps" :key="step.label" class="venue-code__step">
                <view :class="['venue-code__dot', `venue-code__dot--${step.state.toLowerCase()}`]">
                  <LineIcon
                    v-if="step.state !== 'TODO'"
                    :name="step.state === 'DONE' ? 'check' : 'close'"
                    :size="11"
                    color="#ffffff"
                    :stroke-width="3"
                  />
                </view>
                <text :class="['venue-code__step-label', `venue-code__step-label--${step.state.toLowerCase()}`]">
                  {{ step.label }}
                </text>
              </view>
            </view>
          </view>
        </view>

        <view class="venue-code__order" data-testid="venue-booking-order">
          <text class="venue-code__order-title">{{ paidBooking ? '订单与支付' : '费用' }}</text>
          <view v-if="orderInfo" class="venue-code__order-row">
            <text class="venue-code__order-label">订单号</text>
            <text class="venue-code__order-value" data-testid="venue-booking-order-no">{{ orderInfo.orderNo }}</text>
            <button class="venue-code__copy" data-testid="venue-booking-copy" @click="copyOrderNo">
              <text :class="copied ? 'venue-code__copied' : 'venue-code__copy-text'">{{ copied ? '已复制' : '复制' }}</text>
            </button>
          </view>
          <view class="venue-code__order-row">
            <text class="venue-code__order-label">{{ tone === 'PENDING' || tone === 'CLOSED' ? '应付' : paidBooking ? '实付' : '费用' }}</text>
            <text class="venue-code__order-amount" data-testid="venue-booking-amount">
              {{ paidBooking ? formatVenueAmount(booking.payment.amountMinor) : '免费' }}
            </text>
            <text v-if="paidBooking" class="venue-code__muted">{{ booking.people }} 人 × {{ formatVenueAmount(booking.payment.unitAmountMinor) }}</text>
          </view>
          <template v-if="paidBooking">
            <view class="venue-code__order-row">
              <text class="venue-code__order-label">支付方式</text>
              <text>微信支付</text>
            </view>
            <view class="venue-code__order-row venue-code__order-row--top">
              <text class="venue-code__order-label">收款方</text>
              <view class="venue-code__place">
                <text data-testid="venue-booking-payee">{{ payeeName }}</text>
                <text class="venue-code__muted">经平台支付渠道</text>
              </view>
            </view>
          </template>
          <view v-if="refund" class="venue-code__order-row">
            <text class="venue-code__order-label">退款</text>
            <text :class="['venue-code__refund', `venue-code__refund--${refund.tone.toLowerCase()}`]" data-testid="venue-booking-refund">
              {{ refund.text }} · 原路退回
            </text>
          </view>
        </view>
      </view>

      <view v-if="!live && !pending" class="venue-code__bar">
        <text v-if="tone === 'USED'" class="venue-code__bar-note">体验结束不会自动下单，接下来由你决定</text>
        <view class="venue-code__bar-pair">
          <button class="venue-code__bar-secondary" data-testid="venue-booking-list" @click="emit('openList')">我的到馆预约</button>
          <button class="venue-code__bar-primary" data-testid="venue-booking-again" @click="emit('openVenue', booking.venue.venueId, booking.venue.cityCode)">
            再约一场
          </button>
        </view>
      </view>
    </template>

    <view v-if="cancelOpen && booking" class="venue-code__mask" @click.self="cancelOpen = false">
      <view class="venue-code__sheet" role="dialog" aria-label="确认取消这次预约" data-testid="venue-booking-cancel-sheet">
        <text class="venue-code__sheet-title">确认取消这次预约？</text>
        <view class="venue-code__sheet-grid">
          <text class="venue-code__sheet-label">场次</text>
          <text>{{ slotLine }}</text>
          <template v-if="cancelCopy.refund">
            <text class="venue-code__sheet-label">退款金额</text>
            <text class="venue-code__sheet-refund" data-testid="venue-booking-cancel-refund">{{ cancelCopy.refund }}</text>
            <text class="venue-code__sheet-label">退款方式</text>
            <text>原路退回微信支付</text>
          </template>
        </view>
        <view class="venue-code__sheet-warn">
          <LineIcon name="info" :size="16" color="#ba470b" :stroke-width="2" />
          <text class="venue-code__sheet-warn-text" data-testid="venue-booking-cancel-text">{{ cancelCopy.lead }}</text>
        </view>
        <view class="venue-code__sheet-actions">
          <button class="venue-code__sheet-keep" @click="cancelOpen = false">暂不取消</button>
          <button
            class="venue-code__sheet-confirm"
            data-testid="venue-booking-cancel-confirm"
            :disabled="cancelling"
            @click="cancel"
          >
            确认取消
          </button>
        </view>
      </view>
    </view>

    <view v-if="moveOpen && booking" class="venue-code__mask" @click.self="moveOpen = false">
      <view class="venue-code__sheet" role="dialog" aria-label="改期" data-testid="venue-booking-move-sheet">
        <view class="venue-code__sheet-head">
          <text class="venue-code__sheet-title">改期</text>
          <text class="venue-code__muted">当前场次 {{ slotLine }}</text>
        </view>
        <scroll-view class="venue-code__move-days" scroll-x :show-scrollbar="false">
          <view class="venue-code__move-day-track">
            <button
              v-for="day in moveDays"
              :key="day.date"
              :class="['venue-code__move-day', { 'venue-code__move-day--on': day.date === moveDay }]"
              :data-testid="`venue-move-day-${day.date}`"
              :aria-pressed="day.date === moveDay"
              @click="pickMoveDay(day)"
            >
              {{ day.title }} {{ day.subtitle }}
            </button>
          </view>
        </scroll-view>
        <view v-if="slots.kind === 'loading'" class="venue-code__move-state" aria-label="正在加载">正在读取场次…</view>
        <view v-else-if="slots.kind === 'error'" class="venue-code__move-state" data-testid="venue-move-error">
          <text>场次没有读出来</text>
          <button class="venue-code__text-button" @click="loadSlots">重试</button>
        </view>
        <view v-else-if="moveSlots.length === 0" class="venue-code__move-state" data-testid="venue-move-empty">
          这一天没有这个项目的场次
        </view>
        <view v-else class="venue-code__move-slots">
          <button
            v-for="slot in moveSlots"
            :key="slot.session.sessionId"
            :class="[
              'venue-code__move-slot',
              { 'venue-code__move-slot--on': slot.session.sessionId === moveSessionId, 'venue-code__move-slot--off': !slot.selectable },
            ]"
            :data-testid="`venue-move-slot-${slot.session.sessionId}`"
            :data-selectable="slot.selectable"
            :aria-pressed="slot.session.sessionId === moveSessionId"
            :aria-disabled="!slot.selectable"
            @click="pickMoveSlot(slot.session.sessionId, slot.selectable)"
          >
            <text class="venue-code__move-time">{{ formatVenueSessionTime(slot.session) }}</text>
            <text class="venue-code__move-sub">{{ slot.sub }}</text>
          </button>
        </view>
        <text class="venue-code__move-note">开场前可以改期，同项目同价。改期后旧码作废，本页换成新码。</text>
        <view class="venue-code__sheet-actions">
          <button class="venue-code__sheet-keep" @click="moveOpen = false">暂不改期</button>
          <button
            :class="['venue-code__sheet-confirm', { 'venue-code__sheet-confirm--off': !moveTarget || moving }]"
            data-testid="venue-booking-move-confirm"
            :disabled="!moveTarget || moving"
            @click="confirmMove"
          >
            确认改期
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board VenueCode (390 wide). */
.venue-code {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  padding-bottom: env(safe-area-inset-bottom);
  color: #202c42;
  background: #f5f7fc;
  box-sizing: border-box;
  --venue-head: linear-gradient(180deg, #ff6a00 0%, #ff8a3d 100%);
}

.venue-code--green {
  --venue-head: linear-gradient(180deg, #11804a 0%, #159a70 100%);
}

.venue-code--grey {
  --venue-head: linear-gradient(180deg, #627187 0%, #738096 100%);
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

.venue-code__head {
  flex: none;
  padding: calc(8px + var(--status-bar-height)) 8px 0;
  background: var(--venue-head);
}

.venue-code--plain .venue-code__head {
  background: #ffffff;
}

.venue-code__header {
  display: flex;
  align-items: center;
}

.venue-code__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.venue-code__header-title {
  flex: 1;
  color: #ffffff;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-align: center;
}

.venue-code--plain .venue-code__header-title {
  color: #202c42;
}

.venue-code__status {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 8px 52px;
  color: #ffffff;
}

.venue-code__status-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.venue-code__status-title {
  font-size: 22px;
  font-weight: 700;
  line-height: 30px;
  white-space: nowrap;
}

.venue-code__status-sub {
  font-size: 13px;
  font-weight: 500;
  line-height: 19px;
}

.venue-code__content {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  margin-top: -36px;
  padding: 0 16px 16px;
}

.venue-code__card {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.venue-code__info {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}

.venue-code__project {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.venue-code__facts {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
  line-height: 19px;
}

.venue-code__fact-label {
  color: #738096;
  white-space: nowrap;
}

.venue-code__fact-strong {
  font-weight: 500;
}

.venue-code__place {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.venue-code__muted {
  color: #627187;
}

.venue-code__tear {
  position: relative;
  height: 16px;
}

.venue-code__tear-line {
  position: absolute;
  top: 8px;
  right: 16px;
  left: 16px;
  border-top: 1px dashed #dcdfe3;
}

.venue-code__notch {
  position: absolute;
  top: 0;
  width: 8px;
  height: 16px;
  background: #f5f7fc;
}

.venue-code__notch--left {
  left: 0;
  border-radius: 0 8px 8px 0;
}

.venue-code__notch--right {
  right: 0;
  border-radius: 8px 0 0 8px;
}

.venue-code__code-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 10px 16px 16px;
}

.venue-code__qr {
  position: relative;
  width: 176px;
  height: 176px;
  margin-bottom: 6px;
}

.venue-code__qr-image {
  display: block;
  width: 176px;
  height: 176px;
}

.venue-code__qr-image--off {
  opacity: 0.18;
}

.venue-code__qr-empty {
  display: flex;
  width: 176px;
  height: 176px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1.5px dashed #c9d3e1;
  border-radius: 12px;
  background: #f5f7fc;
  box-sizing: border-box;
}

.venue-code__qr-empty-text {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.venue-code__stamp {
  position: absolute;
  top: 44px;
  left: 44px;
  display: flex;
  width: 88px;
  height: 88px;
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

.venue-code__stamp--used {
  border-color: #11804a;
  color: #11804a;
}

.venue-code__code {
  color: #536178;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.venue-code__hint {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.venue-code__tiles {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.venue-code__tiles--two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.venue-code__tile {
  display: flex;
  height: 76px;
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

.venue-code__tile--pay {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.venue-code__tile--off,
.venue-code__tile[disabled] {
  color: #91a0b4;
  background: #ffffff;
}

.venue-code__steps {
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.venue-code__steps-track {
  position: relative;
}

.venue-code__steps-line {
  position: absolute;
  top: 9px;
  height: 2px;
  background: #dcdfe3;
}

.venue-code__steps-line--done {
  right: auto;
  background: #ff6a00;
}

.venue-code__steps-grid {
  position: relative;
  display: grid;
}

.venue-code__step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.venue-code__dot {
  display: flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border: 2px solid #dcdfe3;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.venue-code__dot--done {
  border-color: #ff6a00;
  background: #ff6a00;
}

.venue-code__dot--ended {
  border-color: #738096;
  background: #738096;
}

.venue-code__step-label {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.venue-code__step-label--done {
  color: #202c42;
}

.venue-code__step-label--ended {
  color: #536178;
}

.venue-code__order {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
  font-size: 13px;
  line-height: 19px;
}

.venue-code__order-title {
  margin-bottom: 2px;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.venue-code__order-row {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

.venue-code__order-row--top {
  align-items: flex-start;
}

.venue-code__order-label {
  width: 72px;
  flex: none;
  color: #627187;
  white-space: nowrap;
}

.venue-code__order-value {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.venue-code__order-amount {
  color: #e85400;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.venue-code__copy {
  display: flex;
  height: 36px;
  flex: none;
  margin: -9px 0 -9px auto;
  padding: 0 8px;
  align-items: center;
  font-size: 13px;
  line-height: 18px;
}

.venue-code__copy-text {
  color: #ba470b;
}

.venue-code__copied {
  color: #11804a;
}

.venue-code__refund--pending {
  color: #ba470b;
}

.venue-code__refund--done {
  color: #11804a;
}

.venue-code__refund--alert {
  color: #d64f67;
}

.venue-code__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 8px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.venue-code__bar-note {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.venue-code__bar-pair {
  display: flex;
  gap: 10px;
}

.venue-code__bar-secondary,
.venue-code__bar-primary {
  height: 46px;
  min-width: 0;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 46px;
  text-align: center;
  white-space: nowrap;
  box-sizing: border-box;
}

.venue-code__bar-secondary {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
  line-height: 44px;
}

.venue-code__bar-primary {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.venue-code__skeleton {
  height: 420px;
  margin: 12px 16px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.venue-code__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.venue-code__state-title {
  font-size: 16px;
  font-weight: 700;
}

.venue-code__state-text {
  color: #738096;
  font-size: 13px;
}

.venue-code__state-button {
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

/* Below the uni picker (999) like every sheet of the project. */
.venue-code__mask {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.45);
}

.venue-code__sheet {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 12px;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  background: #ffffff;
  box-sizing: border-box;
}

.venue-code__sheet-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
  line-height: 19px;
}

.venue-code__sheet-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.venue-code__sheet-grid {
  display: grid;
  padding: 12px;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 8px 12px;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 18px;
}

.venue-code__sheet-label {
  color: #627187;
  white-space: nowrap;
}

.venue-code__sheet-refund {
  color: #e85400;
  font-weight: 700;
}

.venue-code__sheet-warn {
  display: flex;
  padding: 10px 12px;
  align-items: flex-start;
  gap: 6px;
  border-radius: 10px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  line-height: 18px;
}

.venue-code__sheet-warn-text {
  min-width: 0;
}

.venue-code__sheet-actions {
  display: flex;
  gap: 10px;
}

.venue-code__sheet-keep,
.venue-code__sheet-confirm {
  height: 44px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
  text-align: center;
  box-sizing: border-box;
}

.venue-code__sheet-keep {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
  line-height: 42px;
}

.venue-code__sheet-confirm {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.venue-code__sheet-confirm--off,
.venue-code__sheet-confirm[disabled] {
  color: #91a0b4;
  background: #eef0f3;
}

.venue-code__move-days {
  width: 100%;
  white-space: nowrap;
}

.venue-code__move-day-track {
  display: inline-flex;
  gap: 8px;
}

.venue-code__move-day {
  display: inline-flex;
  height: 36px;
  padding: 0 12px;
  flex: none;
  align-items: center;
  border-radius: 8px;
  color: #202c42;
  background: #eef2f8;
  font-size: 13px;
  white-space: nowrap;
}

.venue-code__move-day--on {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.venue-code__move-state {
  display: flex;
  min-height: 56px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #738096;
  font-size: 13px;
}

.venue-code__text-button {
  min-height: 36px;
  padding: 0 8px;
  color: #ba470b;
  font-size: 13px;
}

.venue-code__move-slots {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.venue-code__move-slot {
  display: flex;
  min-height: 56px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border: 1.5px solid #e5ebf3;
  border-radius: 10px;
  background: #ffffff;
  box-sizing: border-box;
}

.venue-code__move-time {
  color: #202c42;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.venue-code__move-sub {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.venue-code__move-slot--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.venue-code__move-slot--on .venue-code__move-time,
.venue-code__move-slot--on .venue-code__move-sub {
  color: #ba470b;
}

.venue-code__move-slot--off {
  background: #f5f7fc;
}

.venue-code__move-slot--off .venue-code__move-time {
  color: #91a0b4;
}

.venue-code__move-slot--off .venue-code__move-sub {
  color: #738096;
}

.venue-code__move-note {
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}
</style>
