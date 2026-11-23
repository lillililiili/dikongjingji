<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'

import { ACTIVITY_PREVIEW_ENABLED } from '../../application/activity-preview'
import { canRequestRefund } from '../../application/enrollment-order-view'
import {
  ENROLLMENT_TABS,
  describeCancelConfirm,
  describeEnrollmentCard,
  describeWaitlistCard,
  enrollmentPriceText,
  paymentRemainingMs,
} from '../../application/enrollment-view'
import { enrollmentApi } from '../../data/enrollment-api-client'
import { resolveActivityPreviewCover } from '../../data/preview/activity-preview-covers'
import {
  ENROLLMENT_PAGE_SIZE,
  type Enrollment,
  type EnrollmentStatus,
  type Waitlist,
} from '../../domain/enrollment'
import { ACTIVITY_TYPE_LABELS } from '../shared/activity-format'

const props = defineProps<{
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
  // Counts up when the page is scrolled to its end.
  reachBottomKey: number
  initialStatus: EnrollmentStatus
}>()

const emit = defineEmits<{
  back: []
  login: []
  openTicket: [enrollmentId: string]
  openActivity: [activityId: string, cityCode: string]
  browse: []
  // 去付款: the trade cashier of an unpaid sign-up.
  pay: [tradeOrderId: string]
  // A paid sign-up opens on its order (board EnrollList → OrderEnroll); 申请退款 has its own page.
  openOrder: [enrollmentId: string]
  refund: [enrollmentId: string]
  // S5-3: a waitlist, a team sign-up's order, the result record and the progress of a refund or reschedule.
  openWaitlist: [waitlistId: string]
  openTeamOrder: [enrollmentId: string]
  openResult: [enrollmentId: string]
  openChange: [enrollmentId: string, changeId: string]
}>()

type ListState =
  | { kind: 'loading' }
  | { kind: 'ready' }
  | { kind: 'error' }

const tab = ref<EnrollmentStatus>(props.initialStatus)
const state = ref<ListState>({ kind: 'loading' })
const items = ref<Enrollment[]>([])
// S5-3: waitlists still queuing or holding seats sit on top of 待参加 (board EnrollList).
const waitlists = ref<Waitlist[]>([])
const page = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)
const cancellingId = ref<string | null>(null)
let requestVersion = 0

// "青少年无人机编程挑战赛（东营站）": the bracketed part never breaks across lines (board EnrollList).
function splitTitle(title: string): { main: string; tail: string } {
  const at = title.lastIndexOf('（')
  return at > 0 && title.endsWith('）') ? { main: title.slice(0, at), tail: title.slice(at) } : { main: title, tail: '' }
}

const waitlistCards = computed(() => (tab.value === 'UPCOMING' ? waitlists.value : []).map((waitlist) => ({
  waitlist,
  view: describeWaitlistCard(waitlist),
  title: splitTitle(waitlist.activity.title),
  cover: waitlist.activity.coverImage?.url ?? resolveActivityPreviewCover(waitlist.activity.activityId),
  typeLabel: ACTIVITY_TYPE_LABELS[waitlist.activity.type],
})))

const cards = computed(() => items.value.map((enrollment) => {
  const view = describeEnrollmentCard(enrollment)
  // The newest change with something to follow: a refund on its way, or a reschedule.
  const progress = enrollment.changes.find((change) => change.refundStatus !== null || change.kind === 'RESCHEDULE')
    ?? null
  return {
    enrollment,
    view,
    title: splitTitle(enrollment.activity.title),
    // The board's card shows no "前可取消" line; refunds, payment and check-ins still say something.
    showNote: Boolean(view.note) && !(view.statusText === '待参加' && view.noteTone === 'PLAIN'),
    paid: ACTIVITY_PREVIEW_ENABLED && enrollment.payment.status === 'PAID',
    refundable: ACTIVITY_PREVIEW_ENABLED && canRequestRefund(enrollment),
    cover: enrollment.activity.coverImage?.url
      ?? resolveActivityPreviewCover(enrollment.activity.activityId),
    typeLabel: ACTIVITY_TYPE_LABELS[enrollment.activity.type],
    price: enrollmentPriceText(enrollment),
    unpaid: enrollment.payment.status === 'PENDING_PAYMENT',
    team: ACTIVITY_PREVIEW_ENABLED && enrollment.kind === 'TEAM',
    // 申请退改 (board EnrollList): a paid refund, or a reschedule of a free sign-up.
    changeable: ACTIVITY_PREVIEW_ENABLED && (canRequestRefund(enrollment) || enrollment.reschedulable),
    finished: ACTIVITY_PREVIEW_ENABLED && enrollment.status === 'FINISHED',
    hasResult: enrollment.tickets.some((ticket) => ticket.result !== null),
    progress: ACTIVITY_PREVIEW_ENABLED && enrollment.status === 'CANCELLED' ? progress : null,
  }
}))
const emptyText = computed(() => ({
  UPCOMING: '还没有待参加的活动',
  FINISHED: '还没有参加过的活动',
  CANCELLED: '没有取消过的报名',
})[tab.value])

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
    const [loaded, queued] = await Promise.all([
      enrollmentApi.loadEnrollments(token, tab.value, 1, ENROLLMENT_PAGE_SIZE),
      loadWaitlists(token),
    ])
    if (requested !== requestVersion) return
    items.value = loaded.items
    waitlists.value = queued
    page.value = 1
    hasMore.value = loaded.hasMore
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
    const next = await enrollmentApi.loadEnrollments(
      token, tab.value, page.value + 1, ENROLLMENT_PAGE_SIZE)
    if (requested !== requestVersion) return
    // A record that moved between pages while scrolling is shown once.
    const known = new Set(items.value.map((item) => item.enrollmentId))
    items.value = [...items.value, ...next.items.filter((item) => !known.has(item.enrollmentId))]
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

// Only the 待参加 tab shows waitlists; a server without them (before 1.4.0) or a failure leaves them out.
async function loadWaitlists(token: string): Promise<Waitlist[]> {
  if (!ACTIVITY_PREVIEW_ENABLED || tab.value !== 'UPCOMING') return []
  try {
    return (await enrollmentApi.loadWaitlists(token, true, 1, 20)).items
  } catch {
    return []
  }
}

function selectTab(status: EnrollmentStatus): void {
  if (status === tab.value) return
  tab.value = status
  void load()
}

// Before the deadline the cashier; past it the server is about to close the order, so the ticket
// page (which asks the server again) says what happened instead.
function pay(enrollment: Enrollment): void {
  const orderId = enrollment.payment.tradeOrderId
  if (orderId && paymentRemainingMs(enrollment, Date.now()) > 0) {
    emit('pay', orderId)
    return
  }
  emit('openTicket', enrollment.enrollmentId)
}

function openCard(enrollment: Enrollment, paid: boolean): void {
  if (ACTIVITY_PREVIEW_ENABLED && enrollment.kind === 'TEAM') {
    emit('openTeamOrder', enrollment.enrollmentId)
    return
  }
  if (ACTIVITY_PREVIEW_ENABLED && enrollment.status === 'FINISHED') {
    emit('openResult', enrollment.enrollmentId)
    return
  }
  if (paid) {
    emit('openOrder', enrollment.enrollmentId)
    return
  }
  emit('openTicket', enrollment.enrollmentId)
}

function askCancel(enrollment: Enrollment): void {
  const unpaid = enrollment.payment.status === 'PENDING_PAYMENT'
  const count = unpaid
    ? enrollment.tickets.length
    : enrollment.tickets.filter((ticket) => ticket.status === 'ACTIVE').length
  // Free: the E07 wording; paid: how much goes back; unpaid: nothing was charged.
  const content = enrollment.payment.status === 'FREE'
    ? `${count} 人的名额会让给别人，需要时可以重新报名。`
    : describeCancelConfirm(enrollment, count)
  uni.showModal({
    title: '取消报名？',
    content,
    confirmText: '取消报名',
    cancelText: '再想想',
    success: (result) => {
      if (result.confirm) void cancel(enrollment)
    },
  })
}

async function cancel(enrollment: Enrollment): Promise<void> {
  const token = props.accessToken
  if (!token || cancellingId.value) return
  cancellingId.value = enrollment.enrollmentId
  try {
    await enrollmentApi.cancel(token, enrollment.enrollmentId)
    uni.showToast({ title: '已取消', icon: 'none' })
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({
      title: error instanceof ApiRequestError ? error.message : '没有取消成功，请稍后重试',
      icon: 'none',
    })
  } finally {
    cancellingId.value = null
    void load()
  }
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
watch(() => props.reachBottomKey, () => { void loadMore() })
</script>

<template>
  <view class="enrollments" data-testid="activity-enrollments">
    <view class="enrollments__top">
      <AppPageHeader title="我的报名" centered test-id="enrollments" @back="emit('back')">
        <template #bottom>
          <view class="enrollments__tabs" role="tablist" aria-label="报名状态">
            <button
              v-for="item in ENROLLMENT_TABS"
              :key="item.status"
              :class="['enrollments__tab', { 'enrollments__tab--on': item.status === tab }]"
              :data-testid="`enrollments-tab-${item.status.toLowerCase()}`"
              role="tab"
              :aria-selected="item.status === tab"
              @click="selectTab(item.status)"
            >
              {{ item.label }}
              <view v-if="item.status === tab" class="enrollments__tab-line" />
            </button>
          </view>
        </template>
      </AppPageHeader>
    </view>

    <view v-if="state.kind === 'loading'" class="enrollments__list" data-testid="enrollments-loading" aria-label="正在加载">
      <view class="enrollments__skeleton" />
      <view class="enrollments__skeleton" />
    </view>

    <view v-else-if="state.kind === 'error'" class="enrollments__state" data-testid="enrollments-error">
      <text class="enrollments__state-title">加载失败</text>
      <text class="enrollments__state-text">请检查网络后重试</text>
      <button class="enrollments__state-button" data-testid="enrollments-retry" @click="load">重试</button>
    </view>

    <view v-else-if="cards.length === 0 && waitlistCards.length === 0" class="enrollments__state" data-testid="enrollments-empty">
      <text class="enrollments__state-title">{{ emptyText }}</text>
      <button
        v-if="tab === 'UPCOMING'"
        class="enrollments__state-button"
        data-testid="enrollments-browse"
        @click="emit('browse')"
      >
        去看看活动
      </button>
    </view>

    <view v-else class="enrollments__list">
      <view
        v-for="card in waitlistCards"
        :key="card.waitlist.waitlistId"
        class="enrollments__card"
        :data-testid="`waitlist-${card.waitlist.waitlistId}`"
        :data-status="card.waitlist.status"
      >
        <button
          class="enrollments__main"
          :data-testid="`waitlist-open-${card.waitlist.waitlistId}`"
          @click="emit('openWaitlist', card.waitlist.waitlistId)"
        >
          <view class="enrollments__line">
            <text class="enrollments__type">{{ card.typeLabel }}</text>
            <text class="enrollments__org">{{ card.waitlist.session.venueName || card.waitlist.activity.organizerName }}</text>
            <text :class="['enrollments__status', `enrollments__status--${card.view.statusTone.toLowerCase()}`]">
              {{ card.view.statusText }}
            </text>
          </view>
          <view class="enrollments__content">
            <image
              v-if="card.cover"
              class="enrollments__cover"
              :src="card.cover"
              mode="aspectFill"
              :alt="card.waitlist.activity.title"
            />
            <view v-else class="enrollments__cover enrollments__cover--blank">
              <text>{{ card.typeLabel }}</text>
            </view>
            <view class="enrollments__text">
              <view class="enrollments__title-line">
                <text class="enrollments__activity">{{ card.title.main }}<text v-if="card.title.tail" class="enrollments__activity-tail">{{ card.title.tail }}</text></text>
                <text class="enrollments__price">{{ card.view.price }}</text>
              </view>
              <text class="enrollments__who">{{ card.view.who }}</text>
              <text class="enrollments__when">{{ card.view.when }}</text>
              <text
                v-if="card.view.note"
                :class="['enrollments__note', `enrollments__note--${card.view.noteTone.toLowerCase()}`]"
                data-testid="waitlist-note"
              >
                {{ card.view.note }}
              </text>
            </view>
          </view>
        </button>
        <view class="enrollments__actions">
          <button
            class="enrollments__action enrollments__action--outline"
            :data-testid="`waitlist-view-${card.waitlist.waitlistId}`"
            @click="emit('openWaitlist', card.waitlist.waitlistId)"
          >
            查看候补
          </button>
        </view>
      </view>

      <view
        v-for="card in cards"
        :key="card.enrollment.enrollmentId"
        class="enrollments__card"
        :data-testid="`enrollment-${card.enrollment.enrollmentId}`"
        :data-status="card.enrollment.status"
      >
        <button
          class="enrollments__main"
          :data-testid="`enrollment-open-${card.enrollment.enrollmentId}`"
          :data-opens="card.paid ? 'order' : 'ticket'"
          @click="openCard(card.enrollment, card.paid)"
        >
          <view class="enrollments__line">
            <text :class="['enrollments__type', { 'enrollments__type--team': card.team }]">{{ card.team ? '团队' : card.typeLabel }}</text>
            <text class="enrollments__org">{{ card.team ? card.enrollment.team?.teamName : card.enrollment.location.name }}</text>
            <text :class="['enrollments__status', `enrollments__status--${card.view.statusTone.toLowerCase()}`]">
              {{ card.view.statusText }}
            </text>
          </view>
          <view class="enrollments__content">
            <image
              v-if="card.cover"
              class="enrollments__cover"
              :src="card.cover"
              mode="aspectFill"
              :alt="card.enrollment.activity.title"
            />
            <view v-else class="enrollments__cover enrollments__cover--blank">
              <text>{{ card.typeLabel }}</text>
            </view>
            <view class="enrollments__text">
              <view class="enrollments__title-line">
                <text class="enrollments__activity">{{ card.title.main }}<text v-if="card.title.tail" class="enrollments__activity-tail">{{ card.title.tail }}</text></text>
                <text class="enrollments__price" data-testid="enrollment-price">{{ card.price }}</text>
              </view>
              <text class="enrollments__who">{{ card.view.who }}</text>
              <text class="enrollments__when">{{ card.view.when }}</text>
              <text
                v-if="card.showNote"
                :class="['enrollments__note', `enrollments__note--${card.view.noteTone.toLowerCase()}`]"
                data-testid="enrollment-note"
              >
                {{ card.view.note }}
              </text>
            </view>
          </view>
        </button>
        <view class="enrollments__actions">
          <template v-if="card.enrollment.status === 'CANCELLED'">
            <button
              v-if="card.progress"
              class="enrollments__action"
              :data-testid="`enrollment-progress-${card.enrollment.enrollmentId}`"
              @click="emit('openChange', card.enrollment.enrollmentId, card.progress.changeId)"
            >
              查看进度
            </button>
            <button
              class="enrollments__action"
              :data-testid="`enrollment-again-${card.enrollment.enrollmentId}`"
              @click="emit('openActivity', card.enrollment.activity.activityId, card.enrollment.activity.cityCode)"
            >
              再次报名
            </button>
          </template>
          <template v-else-if="card.finished">
            <button
              class="enrollments__action"
              :data-testid="`enrollment-again-${card.enrollment.enrollmentId}`"
              @click="emit('openActivity', card.enrollment.activity.activityId, card.enrollment.activity.cityCode)"
            >
              再次报名
            </button>
            <button
              :class="['enrollments__action', { 'enrollments__action--primary': card.hasResult }]"
              :data-testid="`enrollment-result-${card.enrollment.enrollmentId}`"
              @click="emit('openResult', card.enrollment.enrollmentId)"
            >
              {{ card.hasResult ? '查看结果' : '查看记录' }}
            </button>
          </template>
          <template v-else-if="card.team && !card.unpaid">
            <button
              class="enrollments__action enrollments__action--outline"
              :data-testid="`enrollment-team-${card.enrollment.enrollmentId}`"
              @click="emit('openTeamOrder', card.enrollment.enrollmentId)"
            >
              团队名单
            </button>
          </template>
          <template v-else>
            <button
              v-if="card.changeable"
              class="enrollments__action"
              :data-testid="`enrollment-refund-${card.enrollment.enrollmentId}`"
              @click="emit('refund', card.enrollment.enrollmentId)"
            >
              申请退改
            </button>
            <button
              v-else-if="card.enrollment.cancellable"
              class="enrollments__action"
              :data-testid="`enrollment-cancel-${card.enrollment.enrollmentId}`"
              :disabled="cancellingId !== null"
              @click="askCancel(card.enrollment)"
            >
              取消报名
            </button>
            <button
              v-if="card.unpaid"
              class="enrollments__action enrollments__action--primary"
              :data-testid="`enrollment-pay-${card.enrollment.enrollmentId}`"
              @click="pay(card.enrollment)"
            >
              去付款
            </button>
            <button
              v-else
              :class="['enrollments__action', { 'enrollments__action--primary': card.enrollment.status === 'UPCOMING' }]"
              :data-testid="`enrollment-ticket-${card.enrollment.enrollmentId}`"
              @click="emit('openTicket', card.enrollment.enrollmentId)"
            >
              查看凭证
            </button>
          </template>
        </view>
      </view>

      <button
        v-if="hasMore"
        class="enrollments__more"
        data-testid="enrollments-more"
        :disabled="loadingMore"
        @click="loadMore"
      >
        {{ loadingMore ? '加载中…' : '加载更多' }}
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board EnrollList (390 wide). */
.enrollments {
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

.enrollments__top {
  position: sticky;
  top: 0;
  z-index: 5;
  flex: none;
}

.enrollments__tabs {
  display: grid;
  padding: 0 8px 4px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.enrollments__tab {
  position: relative;
  height: 44px;
  color: #627187;
  font-size: 16px;
  line-height: 44px;
  white-space: nowrap;
}

.enrollments__tab--on {
  color: #202c42;
  font-weight: 700;
}

.enrollments__tab-line {
  position: absolute;
  bottom: 3px;
  left: 50%;
  width: 20px;
  height: 3px;
  margin-left: -10px;
  border-radius: 3px;
  background: #ff6a00;
}

.enrollments__list {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 12px 16px calc(16px + env(safe-area-inset-bottom));
}

.enrollments__skeleton {
  height: 168px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.enrollments__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.enrollments__state-title {
  font-size: 16px;
  font-weight: 700;
}

.enrollments__state-text {
  color: #738096;
  font-size: 13px;
}

.enrollments__state-button {
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

.enrollments__card {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.enrollments__main {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 12px;
  color: #202c42;
  text-align: left;
}

.enrollments__line {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
}

.enrollments__type {
  flex: none;
  padding: 0 6px;
  border-radius: 6px;
  color: #6a4fe0;
  background: #eee7ff;
  font-size: 12px;
  line-height: 20px;
}

.enrollments__type--team {
  color: #1d63c4;
  background: #e6f0ff;
}

.enrollments__org {
  min-width: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.enrollments__status {
  flex: none;
  margin-left: auto;
  font-size: 14px;
  font-weight: 500;
  line-height: 21px;
}

.enrollments__status--upcoming {
  color: #6a4fe0;
}

.enrollments__status--action {
  color: #ba470b;
}

.enrollments__status--done {
  color: #11804a;
}

.enrollments__status--muted {
  color: #536178;
}

.enrollments__status--moved {
  color: #1d63c4;
}

.enrollments__content {
  display: flex;
  width: 100%;
  gap: 12px;
}

.enrollments__cover {
  display: block;
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.enrollments__cover--blank {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6a4fe0;
  background: #eee7ff;
  font-size: 13px;
}

.enrollments__text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.enrollments__title-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.enrollments__activity {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.enrollments__activity-tail {
  display: inline-block;
  white-space: nowrap;
}

.enrollments__price {
  flex: none;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.enrollments__who {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.enrollments__when {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.enrollments__note {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.enrollments__note--pending {
  color: #ba470b;
}

.enrollments__note--done {
  color: #11804a;
}

.enrollments__note--alert {
  color: #d64f67;
}

.enrollments__note--moved {
  color: #1d63c4;
}

.enrollments__actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.enrollments__action {
  height: 36px;
  padding: 0 16px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 14px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.enrollments__action--primary {
  border-color: #ff6a00;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-weight: 500;
}

.enrollments__action--outline {
  border: 1.5px solid #ff6a00;
  color: #ba470b;
  font-weight: 500;
}

.enrollments__more {
  height: 44px;
  color: #536178;
  font-size: 13px;
  line-height: 44px;
}

</style>
