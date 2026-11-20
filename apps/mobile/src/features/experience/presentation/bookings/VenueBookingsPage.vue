<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'

import {
  VENUE_BOOKING_TABS,
  describeVenueBookingCard,
  venuePaymentRemainingMs,
} from '../../application/venue-booking-view'
import { venueBookingApi } from '../../data/venue-booking-api-client'
import {
  VENUE_BOOKING_PAGE_SIZE,
  type VenueBooking,
  type VenueBookingBucket,
} from '../../domain/venue-booking'

const props = defineProps<{
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
  // Counts up when the page is scrolled to its end.
  reachBottomKey: number
  initialBucket: VenueBookingBucket
}>()

const emit = defineEmits<{
  back: []
  login: []
  openBooking: [bookingId: string]
  // 去付款: the trade cashier of an unpaid booking.
  pay: [tradeOrderId: string]
  browse: []
}>()

type ListState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error' }

const bucket = ref<VenueBookingBucket>(props.initialBucket)
const state = ref<ListState>({ kind: 'loading' })
const items = ref<VenueBooking[]>([])
const page = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)
const now = ref(Date.now())
let requestVersion = 0
let timer: ReturnType<typeof setInterval> | null = null

const cards = computed(() => items.value.map((booking) => ({
  booking,
  view: describeVenueBookingCard(booking, now.value),
})))
const emptyText = computed(() => ({
  UPCOMING: '还没有待到馆的预约',
  FINISHED: '还没有去过的场馆',
  CANCELLED: '没有取消过的预约',
})[bucket.value])

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
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  try {
    const loaded = await venueBookingApi.list(token, bucket.value, 1, VENUE_BOOKING_PAGE_SIZE)
    if (requested !== requestVersion) return
    items.value = loaded.items
    page.value = 1
    hasMore.value = loaded.hasMore
    now.value = Date.now()
    state.value = { kind: 'ready' }
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    state.value = { kind: 'error' }
  }
}

async function loadMore(): Promise<void> {
  const token = props.accessToken
  if (!token || state.value.kind !== 'ready' || !hasMore.value || loadingMore.value) return
  const requested = requestVersion
  loadingMore.value = true
  try {
    const next = await venueBookingApi.list(token, bucket.value, page.value + 1, VENUE_BOOKING_PAGE_SIZE)
    if (requested !== requestVersion) return
    // A booking that moved between pages while scrolling is shown once.
    const known = new Set(items.value.map((item) => item.bookingId))
    items.value = [...items.value, ...next.items.filter((item) => !known.has(item.bookingId))]
    page.value += 1
    hasMore.value = next.hasMore
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({ title: '没有加载出来，请再试一次', icon: 'none' })
  } finally {
    loadingMore.value = false
  }
}

function selectBucket(next: VenueBookingBucket): void {
  if (next === bucket.value) return
  bucket.value = next
  void load()
}

// Before the deadline the cashier; past it the server is about to close the order, so the code
// page (which asks the server again) says what happened instead.
function pay(booking: VenueBooking): void {
  const orderId = booking.payment.tradeOrderId
  if (orderId && venuePaymentRemainingMs(booking, Date.now()) > 0) {
    emit('pay', orderId)
    return
  }
  emit('openBooking', booking.bookingId)
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
watch(() => props.reachBottomKey, () => { void loadMore() })

// The 待付款 countdown on the cards.
onMounted(() => {
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view class="venue-bookings" data-testid="venue-bookings">
    <view class="venue-bookings__top">
      <AppPageHeader title="到馆预约" test-id="venue-bookings" @back="emit('back')" />
      <view class="venue-bookings__tabs" role="tablist" aria-label="预约状态">
        <button
          v-for="tab in VENUE_BOOKING_TABS"
          :key="tab.bucket"
          :class="['venue-bookings__tab', { 'venue-bookings__tab--on': tab.bucket === bucket }]"
          :data-testid="`venue-bookings-tab-${tab.bucket.toLowerCase()}`"
          role="tab"
          :aria-selected="tab.bucket === bucket"
          @click="selectBucket(tab.bucket)"
        >
          {{ tab.label }}
        </button>
      </view>
    </view>

    <view v-if="state.kind === 'loading'" class="venue-bookings__list" data-testid="venue-bookings-loading" aria-label="正在加载">
      <view class="venue-bookings__skeleton" />
      <view class="venue-bookings__skeleton" />
    </view>

    <view v-else-if="state.kind === 'error'" class="venue-bookings__state" data-testid="venue-bookings-error">
      <text class="venue-bookings__state-title">加载失败</text>
      <text class="venue-bookings__state-text">请检查网络后重试</text>
      <button class="venue-bookings__state-button" data-testid="venue-bookings-retry" @click="load">重试</button>
    </view>

    <view v-else-if="cards.length === 0" class="venue-bookings__state" data-testid="venue-bookings-empty">
      <text class="venue-bookings__state-title">{{ emptyText }}</text>
      <button
        v-if="bucket === 'UPCOMING'"
        class="venue-bookings__state-button"
        data-testid="venue-bookings-browse"
        @click="emit('browse')"
      >
        去看看场馆
      </button>
    </view>

    <view v-else class="venue-bookings__list">
      <view
        v-for="card in cards"
        :key="card.booking.bookingId"
        class="venue-bookings__card"
        :data-testid="`venue-booking-card-${card.booking.bookingId}`"
        :data-status="card.booking.status"
      >
        <button
          class="venue-bookings__main"
          :data-testid="`venue-booking-open-${card.booking.bookingId}`"
          @click="emit('openBooking', card.booking.bookingId)"
        >
          <view class="venue-bookings__line">
            <text class="venue-bookings__tag">到馆 · 真机</text>
            <text :class="['venue-bookings__status', `venue-bookings__status--${card.view.statusTone.toLowerCase()}`]">
              {{ card.view.statusText }}
            </text>
            <text class="venue-bookings__when">{{ card.view.when }}</text>
          </view>
          <text class="venue-bookings__name">{{ card.view.title }}</text>
          <text class="venue-bookings__sub" data-testid="venue-booking-card-money">
            {{ card.booking.venue.name }} · {{ card.view.money }}
          </text>
        </button>
        <view class="venue-bookings__actions">
          <template v-if="card.booking.status === 'PENDING_PAYMENT'">
            <button
              class="venue-bookings__action"
              :data-testid="`venue-booking-detail-${card.booking.bookingId}`"
              @click="emit('openBooking', card.booking.bookingId)"
            >
              查看预约
            </button>
            <button
              class="venue-bookings__action venue-bookings__action--primary"
              :data-testid="`venue-booking-pay-${card.booking.bookingId}`"
              @click="pay(card.booking)"
            >
              去付款
            </button>
          </template>
          <template v-else-if="card.booking.status === 'CONFIRMED'">
            <button
              class="venue-bookings__action"
              :data-testid="`venue-booking-detail-${card.booking.bookingId}`"
              @click="emit('openBooking', card.booking.bookingId)"
            >
              改期 / 取消
            </button>
            <button
              class="venue-bookings__action venue-bookings__action--primary"
              :data-testid="`venue-booking-code-${card.booking.bookingId}`"
              @click="emit('openBooking', card.booking.bookingId)"
            >
              出示核销码
            </button>
          </template>
          <button
            v-else
            class="venue-bookings__action"
            :data-testid="`venue-booking-detail-${card.booking.bookingId}`"
            @click="emit('openBooking', card.booking.bookingId)"
          >
            查看详情
          </button>
        </view>
      </view>

      <button
        v-if="hasMore"
        class="venue-bookings__more"
        data-testid="venue-bookings-more"
        :disabled="loadingMore"
        @click="loadMore"
      >
        {{ loadingMore ? '正在加载…' : '加载更多' }}
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board ExpRecords (the 到馆预约 card, 390 wide). */
.venue-bookings {
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

.venue-bookings__top {
  position: sticky;
  top: 0;
  z-index: 5;
}

/* Board ExpRecords: the tabs sit on a white strip under the gradient head. */
.venue-bookings__tabs {
  display: flex;
  background: #ffffff;
  padding: 0 16px;
  gap: 22px;
  border-bottom: 1px solid #f0f1f3;
}

.venue-bookings__tab {
  padding: 10px 0;
  border-bottom: 3px solid transparent;
  color: #627187;
  font-size: 15px;
  line-height: 21px;
}

.venue-bookings__tab--on {
  border-bottom-color: #ff6a00;
  color: #202c42;
  font-weight: 700;
}

.venue-bookings__list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 24px;
}

.venue-bookings__card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.venue-bookings__main {
  display: flex;
  flex-direction: column;
  gap: 8px;
  text-align: left;
}

.venue-bookings__line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.venue-bookings__tag {
  padding: 1px 6px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.venue-bookings__status {
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.venue-bookings__status--pending {
  color: #ba470b;
}

.venue-bookings__status--live,
.venue-bookings__status--done {
  color: #11804a;
}

.venue-bookings__status--off {
  color: #738096;
}

.venue-bookings__when {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.venue-bookings__name {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.venue-bookings__sub {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.venue-bookings__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.venue-bookings__action {
  display: flex;
  min-height: 32px;
  padding: 0 12px;
  align-items: center;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  font-size: 13px;
  white-space: nowrap;
  box-sizing: border-box;
}

.venue-bookings__action--primary {
  border-color: #ff6a00;
  color: #ffffff;
  background: #ff6a00;
}

.venue-bookings__more {
  height: 44px;
  color: #536178;
  font-size: 13px;
  text-align: center;
}


.venue-bookings__skeleton {
  height: 130px;
  border-radius: 12px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.venue-bookings__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.venue-bookings__state-title {
  font-size: 16px;
  font-weight: 700;
}

.venue-bookings__state-text {
  color: #738096;
  font-size: 13px;
}

.venue-bookings__state-button {
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
</style>
