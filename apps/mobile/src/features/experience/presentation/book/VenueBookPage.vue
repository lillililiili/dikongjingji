<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  VENUE_PAYMENT_UNAVAILABLE_TEXT,
  createVenueBookKeyHolder,
  describeVenueBookFailure,
  describeVenueParticipant,
  formatVenueAmount,
  formatVenueSlotLine,
  resolveVenuePayMode,
  venueBookRules,
  venueTotalMinor,
  type VenuePayMode,
} from '../../application/venue-booking-view'
import { experienceApi } from '../../data/experience-api-client'
import { venueBookingApi } from '../../data/venue-booking-api-client'
import type { VenueDetail, VenueProject, VenueSession } from '../../domain/experience'
import { MAX_VENUE_BOOKING_PEOPLE, type VenueParticipant } from '../../domain/venue-booking'
import type { VenueBookTarget } from '../../navigation/experience-routes'

const props = defineProps<{
  // Null when the URL is incomplete.
  target: VenueBookTarget | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  // Changes whenever the page is shown again, so people added on the way back appear.
  refreshKey: number
}>()

const emit = defineEmits<{
  // Also 修改场次: the venue detail is where sessions are picked.
  back: []
  login: []
  managePeople: []
  // A free booking (or a replay of one already paid): its code page.
  done: [bookingId: string]
  // A priced booking waiting for payment: the trade cashier of its order.
  pay: [tradeOrderId: string]
}>()

type FormState =
  | { kind: 'loading' }
  | { kind: 'ready'; venue: VenueDetail; project: VenueProject; session: VenueSession }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<FormState>({ kind: 'loading' })
const people = ref<VenueParticipant[]>([])
const seatsLeft = ref<number | null>(null)
const peopleFailed = ref(false)
const pickedIds = ref<string[]>([])
const agreed = ref(false)
const submitting = ref(false)
const keys = createVenueBookKeyHolder()
let requestVersion = 0
let peopleVersion = 0

const ready = computed(() => (state.value.kind === 'ready' ? state.value : null))
const payMode = computed<VenuePayMode>(() => (ready.value ? resolveVenuePayMode(ready.value.project) : 'FREE'))
const unitAmount = computed(() => ready.value?.project.priceAmountMinor ?? 0)
const rows = computed(() => people.value.map((person) => {
  const row = describeVenueParticipant(person)
  return { person, ...row, picked: row.selectable && pickedIds.value.includes(person.participantId) }
}))
const picked = computed(() => rows.value.filter((row) => row.picked).map((row) => row.person))
const hasChildren = computed(() => people.value.some((person) => person.relation === 'CHILD'))
const pickedChild = computed(() => picked.value.some((person) => person.relation === 'CHILD'))
const maxPeople = computed(() => Math.min(MAX_VENUE_BOOKING_PEOPLE, seatsLeft.value ?? MAX_VENUE_BOOKING_PEOPLE))
const tooMany = computed(() => picked.value.length > maxPeople.value)
const total = computed(() => venueTotalMinor(unitAmount.value, picked.value.length))
const totalText = computed(() => (payMode.value === 'FREE' ? '免费' : formatVenueAmount(total.value)))
const feeLine = computed(() => (payMode.value === 'FREE'
  ? '免费'
  : `${formatVenueAmount(unitAmount.value)} × ${picked.value.length} 人`))
const slotLine = computed(() => (ready.value ? formatVenueSlotLine(ready.value.session, Date.now()) : ''))
const projectMeta = computed(() => {
  const project = ready.value?.project
  if (!project) return ''
  return [
    `${project.durationMinutes} 分钟`,
    project.minAge !== null ? `${project.minAge} 岁以上` : null,
  ].filter((part): part is string => Boolean(part)).join(' · ')
})
const coverUrl = computed(() => ready.value?.venue.coverImage?.url ?? null)
// The venue's own arrival notes come first, then what this step actually does.
const venueNotice = computed(() =>
  ready.value?.venue.infoItems.find((item) => item.label === '到馆须知')?.content ?? null)
const rules = computed(() => venueBookRules(payMode.value))
const sessionOpen = computed(() => ready.value?.session.status === 'OPEN')
// The button stays orange like the board (R5 #1, 2026-11-20); tapping it with something missing
// says what. It is only greyed when the booking cannot be paid online at all, or while sending.
const submitLocked = computed(() => payMode.value === 'PAYMENT_UNAVAILABLE' || submitting.value)
const missingStep = computed<string | null>(() => {
  if (!sessionOpen.value) return '这一场不能预约了，请换一场'
  if (picked.value.length === 0) return '请选择参与人'
  if (tooMany.value) return countLine.value
  if (!agreed.value) return '请先阅读并同意到馆须知与退改说明'
  return null
})
const canSubmit = computed(() => !submitLocked.value && missingStep.value === null)
const countLine = computed(() => {
  if (payMode.value === 'PAYMENT_UNAVAILABLE') return VENUE_PAYMENT_UNAVAILABLE_TEXT
  if (ready.value && !sessionOpen.value) {
    return ready.value.session.status === 'FULL' ? '这一场已约满，请换一场' : '这一场已经开始，请换一场'
  }
  if (tooMany.value) {
    return picked.value.length > MAX_VENUE_BOOKING_PEOPLE
      ? `一单最多 ${MAX_VENUE_BOOKING_PEOPLE} 人`
      : `本场只剩 ${seatsLeft.value} 位`
  }
  return picked.value.length > 0 ? `共 ${picked.value.length} 人` : '请选择参与人'
})
const warn = computed(() =>
  payMode.value === 'PAYMENT_UNAVAILABLE' || tooMany.value || (ready.value !== null && !sessionOpen.value))
const submitLabel = computed(() => {
  if (submitting.value) return '提交中…'
  return payMode.value === 'FREE' ? '提交预约' : '提交并支付'
})
const agreeText = computed(() =>
  `${pickedChild.value ? '我是监护人，' : '我'}已阅读并同意到馆须知与退改说明`)

function failureOf(error: unknown) {
  return error instanceof ApiRequestError
    ? { status: error.status, code: error.code, message: error.message }
    : null
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

async function loadPeople(): Promise<void> {
  const token = props.accessToken
  const target = props.target
  if (!token || !target) return
  const requested = ++peopleVersion
  peopleFailed.value = false
  try {
    const loaded = await venueBookingApi.loadParticipants(token, target.sessionId)
    if (requested !== peopleVersion) return
    people.value = loaded.items
    seatsLeft.value = loaded.seatsLeft
    // Someone who can no longer be chosen for this session drops out of the selection.
    const selectable = new Set(loaded.items.filter((person) => person.eligible).map((person) => person.participantId))
    pickedIds.value = pickedIds.value.filter((id) => selectable.has(id))
  } catch (error) {
    if (requested !== peopleVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    // A session that is gone answers 404 here too; the venue read below says so.
    people.value = []
    pickedIds.value = []
    peopleFailed.value = true
  }
}

async function load(): Promise<void> {
  const target = props.target
  if (!props.accessToken) {
    emit('login')
    return
  }
  if (!target) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const [venue, sessions] = await Promise.all([
      experienceApi.loadVenue(target.venueId, target.cityCode),
      experienceApi.loadSessions({
        venueId: target.venueId,
        cityCode: target.cityCode,
        projectId: target.projectId,
        date: target.date,
      }),
    ])
    if (requested !== requestVersion) return
    const project = venue.projects.find((item) => item.projectId === target.projectId)
    const session = sessions.find((item) => item.sessionId === target.sessionId)
    if (!project || !session) {
      state.value = { kind: 'missing' }
      return
    }
    state.value = { kind: 'ready', venue, project, session }
    await loadPeople()
  } catch (error) {
    if (requested !== requestVersion) return
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function togglePerson(personId: string, selectable: boolean): void {
  if (!selectable) return
  pickedIds.value = pickedIds.value.includes(personId)
    ? pickedIds.value.filter((id) => id !== personId)
    : [...pickedIds.value, personId]
}

function trySubmit(): void {
  if (submitLocked.value) return
  if (missingStep.value) {
    uni.showToast({ title: missingStep.value, icon: 'none' })
    return
  }
  void submit()
}

async function submit(): Promise<void> {
  const token = props.accessToken
  const current = ready.value
  if (!canSubmit.value || !token || !current) return
  const participantIds = picked.value.map((person) => person.participantId)
  const expectedAmountMinor = total.value
  submitting.value = true
  try {
    const booking = await venueBookingApi.book(
      token,
      keys.keyFor(current.session.sessionId, participantIds, expectedAmountMinor),
      { sessionId: current.session.sessionId, participantIds, expectedAmountMinor },
    )
    // Unpaid: pay now. Free, or a replay of a booking already paid or closed: its code page says which.
    if (booking.status === 'PENDING_PAYMENT' && booking.payment.tradeOrderId) {
      emit('pay', booking.payment.tradeOrderId)
      return
    }
    emit('done', booking.bookingId)
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    const failure = describeVenueBookFailure(failureOf(error))
    uni.showToast({ title: failure.message, icon: 'none', duration: 2500 })
    if (failure.reload === 'VENUE') void load()
    if (failure.reload === 'PEOPLE') void loadPeople()
  } finally {
    submitting.value = false
  }
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="venue-book" data-testid="venue-book">
    <AppPageHeader title="确认到馆预约" test-id="venue-book" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="venue-book__body" data-testid="venue-book-loading" aria-label="正在加载">
      <view class="venue-book__skeleton" />
      <view class="venue-book__skeleton venue-book__skeleton--tall" />
    </view>

    <view v-else-if="ready" class="venue-book__body">
      <view class="venue-book__card venue-book__card--lead">
        <view class="venue-book__project">
          <image
            v-if="coverUrl"
            class="venue-book__cover"
            :src="coverUrl"
            :alt="ready.project.name"
            mode="aspectFill"
          />
          <view v-else class="venue-book__cover" />
          <view class="venue-book__project-copy">
            <text class="venue-book__project-name" data-testid="venue-book-project">{{ ready.project.name }}</text>
            <text class="venue-book__project-meta">{{ projectMeta }}</text>
          </view>
        </view>
        <view class="venue-book__slot">
          <LineIcon name="calendar" :size="18" color="#ff6a00" />
          <text class="venue-book__slot-text" data-testid="venue-book-slot">{{ slotLine }}</text>
          <button class="venue-book__slot-change" data-testid="venue-book-change" @click="emit('back')">修改场次</button>
        </view>
        <view class="venue-book__facts">
          <text class="venue-book__fact-label">场馆</text>
          <text data-testid="venue-book-venue">{{ ready.venue.name }}</text>
          <text class="venue-book__fact-label">地址</text>
          <text>{{ ready.venue.address }}</text>
        </view>
      </view>

      <view class="venue-book__card venue-book__card--people">
        <view class="venue-book__heading-row">
          <text class="venue-book__heading">参与人</text>
          <text v-if="hasChildren" class="venue-book__side-note">由监护人代为预约</text>
          <text v-if="seatsLeft !== null" class="venue-book__seats" data-testid="venue-book-seats">本场余 {{ seatsLeft }} 位</text>
        </view>
        <view v-if="peopleFailed" class="venue-book__people-error">
          <text>参与人没有读出来</text>
          <button class="venue-book__text-button" data-testid="venue-book-people-retry" @click="loadPeople">重试</button>
        </view>
        <button
          v-for="row in rows"
          :key="row.person.participantId"
          class="venue-book__person"
          :data-testid="`venue-book-person-${row.person.participantId}`"
          :data-name="row.person.name"
          :data-reason="row.person.reason"
          role="checkbox"
          :aria-checked="row.picked"
          :aria-disabled="!row.selectable"
          @click="togglePerson(row.person.participantId, row.selectable)"
        >
          <view :class="['venue-book__box', { 'venue-book__box--on': row.picked, 'venue-book__box--off': !row.selectable }]">
            <LineIcon v-if="row.picked" name="check" :size="14" color="#ffffff" :stroke-width="2.6" />
          </view>
          <text class="venue-book__person-name">{{ row.person.name }}</text>
          <text v-if="row.person.relation === 'SELF'" class="venue-book__tag">本人</text>
          <text v-if="row.ageText" class="venue-book__person-age">{{ row.ageText }}</text>
          <text :class="['venue-book__person-state', { 'venue-book__person-state--ok': row.selectable }]">
            {{ row.stateText }}
          </text>
        </button>
        <button class="venue-book__add" data-testid="venue-book-add-person" @click="emit('managePeople')">
          ＋ 添加参与人
        </button>
      </view>

      <view class="venue-book__card venue-book__card--fee">
        <text class="venue-book__heading">费用</text>
        <view class="venue-book__fee-row">
          <text class="venue-book__muted">到馆体验费</text>
          <text data-testid="venue-book-fee">{{ feeLine }}</text>
        </view>
        <view class="venue-book__fee-row venue-book__fee-row--total">
          <text class="venue-book__muted">合计</text>
          <text class="venue-book__fee-total" data-testid="venue-book-total">{{ totalText }}</text>
        </view>
        <template v-if="payMode !== 'FREE'">
          <view class="venue-book__divider" />
          <view class="venue-book__fee-row">
            <text class="venue-book__muted venue-book__nowrap">收款方</text>
            <text class="venue-book__payee" data-testid="venue-book-payee">{{ ready.venue.operatorName }} · 经平台支付渠道</text>
          </view>
        </template>
        <text class="venue-book__fee-note">到馆体验单独计费，体验结束不会自动下单</text>
      </view>

      <view class="venue-book__card venue-book__card--rules">
        <text class="venue-book__heading">退改说明</text>
        <view class="venue-book__rule-lead">
          <LineIcon name="clock" :size="16" color="#159a70" :stroke-width="2" />
          <text>开场前可以改期或取消</text>
        </view>
        <view v-if="venueNotice" class="venue-book__rule">
          <view class="venue-book__dot" />
          <text class="venue-book__rule-text">{{ venueNotice }}</text>
        </view>
        <view v-for="rule in rules" :key="rule" class="venue-book__rule" data-testid="venue-book-rule">
          <view class="venue-book__dot" />
          <text class="venue-book__rule-text">{{ rule }}</text>
        </view>
        <button
          class="venue-book__agree"
          data-testid="venue-book-agree"
          role="checkbox"
          :aria-checked="agreed"
          @click="agreed = !agreed"
        >
          <view :class="['venue-book__box', 'venue-book__box--small', { 'venue-book__box--on': agreed }]">
            <LineIcon v-if="agreed" name="check" :size="12" color="#ffffff" :stroke-width="2.6" />
          </view>
          <text class="venue-book__agree-text">{{ agreeText }}</text>
        </button>
      </view>
    </view>

    <view v-else-if="state.kind === 'missing'" class="venue-book__state" data-testid="venue-book-missing">
      <text class="venue-book__state-title">这一场已下线或不存在</text>
      <text class="venue-book__state-text">回到场馆换一场看看</text>
      <button class="venue-book__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else class="venue-book__state" data-testid="venue-book-error">
      <text class="venue-book__state-title">加载失败</text>
      <text class="venue-book__state-text">请检查网络后重试</text>
      <button class="venue-book__state-button" data-testid="venue-book-retry" @click="load">重试</button>
    </view>

    <view v-if="ready" class="venue-book__bar">
      <view class="venue-book__bar-copy">
        <text :class="['venue-book__count', { 'venue-book__count--warn': warn }]" data-testid="venue-book-count">
          {{ countLine }}
        </text>
        <text class="venue-book__bar-total" data-testid="venue-book-bar-total">{{ totalText }}</text>
      </view>
      <button
        :class="['venue-book__submit', { 'venue-book__submit--off': submitLocked }]"
        data-testid="venue-book-submit"
        :data-mode="payMode"
        :data-ready="canSubmit"
        :disabled="submitLocked"
        @click="trySubmit"
      >
        {{ submitLabel }}
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board VenueBook (390 wide). */
.venue-book {
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

.venue-book__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 16px;
}

.venue-book__card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.venue-book__card--people {
  gap: 6px;
}

.venue-book__card--fee,
.venue-book__card--rules {
  gap: 8px;
  font-size: 13px;
  line-height: 19px;
}

.venue-book__project {
  display: flex;
  align-items: center;
  gap: 12px;
}

.venue-book__cover {
  width: 64px;
  height: 64px;
  flex: none;
  border-radius: 10px;
  background: #eef0f3;
}

.venue-book__project-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.venue-book__project-name {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.venue-book__project-meta {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.venue-book__slot {
  display: flex;
  min-height: 48px;
  padding: 0 12px;
  align-items: center;
  gap: 10px;
  border-radius: 10px;
  background: #fff0e3;
}

.venue-book__slot-text {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.venue-book__slot-change {
  display: flex;
  min-height: 44px;
  flex: none;
  margin-right: -4px;
  padding: 0 4px;
  align-items: center;
  color: #ba470b;
  font-size: 13px;
  white-space: nowrap;
}

.venue-book__facts {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 6px 10px;
  font-size: 13px;
  line-height: 19px;
}

.venue-book__fact-label,
.venue-book__muted {
  color: #627187;
}

.venue-book__nowrap {
  flex: none;
  white-space: nowrap;
}

.venue-book__heading-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.venue-book__heading {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.venue-book__side-note {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.venue-book__seats {
  margin-left: auto;
  color: #ba470b;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.venue-book__people-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #738096;
  font-size: 13px;
}

.venue-book__text-button {
  min-height: 36px;
  padding: 0 8px;
  color: #ba470b;
  font-size: 13px;
}

.venue-book__person {
  display: flex;
  width: 100%;
  min-height: 44px;
  padding: 0 4px;
  align-items: center;
  gap: 10px;
  box-sizing: border-box;
}

.venue-book__box {
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

.venue-book__box--small {
  width: 18px;
  height: 18px;
}

.venue-book__box--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.venue-book__box--off {
  border-color: #d9e0ea;
  background: #f1f3f6;
}

.venue-book__person-name {
  min-width: 0;
  overflow: hidden;
  font-size: 15px;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.venue-book__tag {
  flex: none;
  margin-left: -4px;
  padding: 1px 6px;
  border-radius: 6px;
  color: #536178;
  background: #eef2f8;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.venue-book__person-age {
  flex: none;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.venue-book__person-state {
  flex: none;
  margin-left: auto;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.venue-book__person-state--ok {
  color: #11804a;
}

.venue-book__add {
  display: flex;
  min-height: 36px;
  align-self: flex-start;
  padding: 0 12px;
  align-items: center;
  border: 1px dashed #c9d3e1;
  border-radius: 8px;
  color: #536178;
  background: #ffffff;
  font-size: 13px;
  white-space: nowrap;
  box-sizing: border-box;
}

.venue-book__fee-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.venue-book__fee-total {
  color: #e85400;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.venue-book__divider {
  height: 1px;
  margin: 2px 0;
  background: #f0f1f3;
}

.venue-book__payee {
  min-width: 0;
  text-align: right;
}

.venue-book__fee-note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.venue-book__rule-lead {
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

.venue-book__rule {
  display: flex;
  gap: 8px;
  color: #536178;
}

.venue-book__dot {
  width: 4px;
  height: 4px;
  flex: none;
  margin: 8px 2px 0;
  border-radius: 50%;
  background: #91a0b4;
}

.venue-book__rule-text {
  min-width: 0;
}

.venue-book__agree {
  display: flex;
  min-height: 44px;
  margin-top: 2px;
  align-items: center;
  gap: 8px;
}

.venue-book__agree-text {
  min-width: 0;
  font-size: 13px;
  line-height: 19px;
}

.venue-book__skeleton {
  height: 160px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.venue-book__skeleton--tall {
  height: 220px;
}

.venue-book__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.venue-book__state-title {
  font-size: 16px;
  font-weight: 700;
}

.venue-book__state-text {
  color: #738096;
  font-size: 13px;
}

.venue-book__state-button {
  min-width: 120px;
  height: 40px;
  margin-top: 8px;
  padding: 0 20px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  line-height: 40px;
  text-align: center;
}

.venue-book__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  flex: none;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 12px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

.venue-book__bar-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.venue-book__count {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.venue-book__count--warn {
  color: #c0392b;
}

.venue-book__bar-total {
  color: #e85400;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.venue-book__submit {
  width: 180px;
  height: 46px;
  flex: none;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
}

.venue-book__submit--off,
.venue-book__submit[disabled] {
  color: #91a0b4;
  background: #eef0f3;
  box-shadow: none;
}

/* At 320 wide the button gives way so the count line keeps a few characters. */
@media (max-width: 340px) {
  .venue-book__submit {
    width: 150px;
  }
}
</style>
