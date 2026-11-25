<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { padTwo, toBeijingParts } from '@/shared/time/beijing-time'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'

import {
  outcomeOfLocal,
  outcomeOfRecord,
  summariseSimulationOutcome,
} from '../../application/simulation-result-view'
import { isUnauthorizedError, uploadPendingResults } from '../../application/simulation-sync'
import { trialRecordLine, trialStage } from '../../application/trial-view'
import { describeVenueBookingCard } from '../../application/venue-booking-view'
import { experienceRecordApi } from '../../data/experience-record-api-client'
import { localSimulationResults } from '../../data/local-simulation-results'
import { venueBookingApi } from '../../data/venue-booking-api-client'
import { EXPERIENCE_RECORD_PAGE_SIZE, type ExperienceRecord } from '../../domain/experience-record'
import type { ExperienceType } from '../../domain/experience'
import type { VenueBooking } from '../../domain/venue-booking'
import type { ExperienceRecordsTab, SimulationResultTarget } from '../../navigation/experience-routes'

// Board ExpRecords (390 wide): 全部 / 线上体验 / 到馆预约. Online records are the signed-in account's
// own; a guest sees the results kept on this device. Venue bookings need a login.
const props = defineProps<{
  accessToken: string | null
  refreshKey: number
  reachBottomKey: number
  initialTab: ExperienceRecordsTab
}>()

const emit = defineEmits<{
  back: []
  login: []
  openResult: [target: SimulationResultTarget]
  retry: [experienceId: string]
  openTrial: [type: ExperienceType, experienceId: string, recordId: string | null]
  openProduct: [productId: string]
  openBooking: [bookingId: string]
  openAllBookings: []
}>()

const TABS: ReadonlyArray<{ code: ExperienceRecordsTab; label: string }> = [
  { code: 'ALL', label: '全部' },
  { code: 'ONLINE', label: '线上体验' },
  { code: 'VENUE', label: '到馆预约' },
]

const LABELS: Record<ExperienceType, { label: string; tone: 'green' | 'blue' }> = {
  SIMULATION: { label: '虚拟仿真', tone: 'green' },
  RECORDED_DEMO: { label: '录播演示', tone: 'blue' },
  SOFTWARE_TRIAL: { label: '软件试用', tone: 'blue' },
  COURSE_TRIAL: { label: '课程试听', tone: 'green' },
}

interface Row {
  key: string
  type: ExperienceType
  title: string
  when: string
  line: string
  picture: string | null
  productId: string | null
  // 查看结果 / 查看 and the second action.
  primary: { label: string; run: () => void } | null
  secondary: { label: string; run: () => void }
}

type ListState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error' }

const tab = ref<ExperienceRecordsTab>(props.initialTab)
const state = ref<ListState>({ kind: 'loading' })
const records = ref<ExperienceRecord[]>([])
const page = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)
const bookings = ref<VenueBooking[]>([])
const localVersion = ref(0)
const now = ref(Date.now())
let requestVersion = 0
let timer: ReturnType<typeof setInterval> | null = null

const signedIn = computed(() => Boolean(props.accessToken))

function day(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)}`
}

function recordRow(record: ExperienceRecord): Row {
  const experience = record.experience
  const picture = experience.coverImage?.url ?? null
  const base = {
    key: record.recordId,
    type: experience.type,
    title: experience.title,
    when: day(record.startedAt),
    picture,
    productId: experience.productId,
  }
  const outcome = outcomeOfRecord(record)
  if (outcome) {
    return {
      ...base,
      line: summariseSimulationOutcome(outcome),
      primary: {
        label: '查看结果',
        run: () => emit('openResult', { experienceId: experience.experienceId, recordId: record.recordId }),
      },
      secondary: { label: '再试一次', run: () => emit('retry', experience.experienceId) },
    }
  }
  const active = trialStage(record) === 'ACTIVE'
  const course = experience.type === 'COURSE_TRIAL'
  return {
    ...base,
    line: trialRecordLine(record, now.value),
    primary: active
      ? null
      : { label: '查看', run: () => emit('openTrial', experience.type, experience.experienceId, record.recordId) },
    secondary: {
      label: active ? (course ? '继续试听' : '继续试用') : (course ? '再次试听' : '再次试用'),
      run: () => emit('openTrial', experience.type, experience.experienceId, active ? record.recordId : null),
    },
  }
}

// Results kept on this device that the server does not have (a guest's, or not sent yet).
const localRows = computed<Row[]>(() => {
  void localVersion.value
  return localSimulationResults.all()
    .filter((result) => !signedIn.value || !result.recordId)
    .map((result) => {
      const outcome = outcomeOfLocal(result)
      return {
        key: result.localId,
        type: 'SIMULATION' as const,
        title: result.title,
        when: day(result.endedAt),
        line: summariseSimulationOutcome(outcome),
        picture: null,
        productId: result.productId,
        primary: {
          label: '查看结果',
          run: () => emit('openResult', { experienceId: result.experienceId, localId: result.localId }),
        },
        secondary: { label: '再试一次', run: () => emit('retry', result.experienceId) },
      }
    })
})

const onlineRows = computed<Row[]>(() => [...localRows.value, ...records.value.map(recordRow)])
const venueCards = computed(() => bookings.value.map((booking) => ({
  booking,
  view: describeVenueBookingCard(booking, now.value),
})))
// 全部 shows the next booking on top, as the board does; 到馆预约 shows every upcoming one.
const shownVenueCards = computed(() =>
  tab.value === 'ONLINE' ? [] : tab.value === 'ALL' ? venueCards.value.slice(0, 1) : venueCards.value)
const shownRows = computed(() => (tab.value === 'VENUE' ? [] : onlineRows.value))

async function load(): Promise<void> {
  const requested = ++requestVersion
  const token = props.accessToken
  now.value = Date.now()
  localVersion.value += 1
  if (!token) {
    records.value = []
    bookings.value = []
    hasMore.value = false
    state.value = { kind: 'ready' }
    return
  }
  state.value = { kind: 'loading' }
  // Results played before signing in go to the account first, so they show once.
  const synced = await uploadPendingResults(experienceRecordApi, localSimulationResults, token)
  if (requested !== requestVersion) return
  localVersion.value += 1
  if (synced.kind === 'UNAUTHORIZED') {
    emit('login')
    return
  }
  try {
    const [loaded, upcoming] = await Promise.all([
      experienceRecordApi.list(token, { page: 1, size: EXPERIENCE_RECORD_PAGE_SIZE }),
      venueBookingApi.list(token, 'UPCOMING', 1, 20).catch((error: unknown) => {
        if (isUnauthorizedError(error)) throw error
        return { items: [] as VenueBooking[], total: 0, hasMore: false }
      }),
    ])
    if (requested !== requestVersion) return
    records.value = loaded.items
    page.value = 1
    hasMore.value = loaded.hasMore
    bookings.value = upcoming.items
    state.value = { kind: 'ready' }
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorizedError(error)) {
      emit('login')
      return
    }
    state.value = { kind: 'error' }
  }
}

async function loadMore(): Promise<void> {
  const token = props.accessToken
  if (!token || state.value.kind !== 'ready' || !hasMore.value || loadingMore.value || tab.value === 'VENUE') return
  const requested = requestVersion
  loadingMore.value = true
  try {
    const next = await experienceRecordApi.list(token, { page: page.value + 1, size: EXPERIENCE_RECORD_PAGE_SIZE })
    if (requested !== requestVersion) return
    const known = new Set(records.value.map((record) => record.recordId))
    records.value = [...records.value, ...next.items.filter((record) => !known.has(record.recordId))]
    page.value += 1
    hasMore.value = next.hasMore
  } catch (error) {
    if (isUnauthorizedError(error)) emit('login')
    else uni.showToast({ title: '没有加载出来，请再试一次', icon: 'none' })
  } finally {
    loadingMore.value = false
  }
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
watch(() => props.reachBottomKey, () => { void loadMore() })

// The trial countdowns on the rows.
onMounted(() => {
  timer = setInterval(() => { now.value = Date.now() }, 30000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view class="records" data-testid="experience-records">
    <AppPageHeader title="我的体验" test-id="experience-records" @back="emit('back')" />
    <view class="records__tabs">
      <button
        v-for="item in TABS"
        :key="item.code"
        :class="['records__tab', { 'records__tab--active': tab === item.code }]"
        :aria-pressed="tab === item.code"
        :data-testid="`experience-records-tab-${item.code.toLowerCase()}`"
        @click="tab = item.code"
      >
        {{ item.label }}
      </button>
    </view>

    <view class="records__body">
      <view v-if="!signedIn" class="records__guest" data-testid="experience-records-guest">
        <view class="records__guest-copy">
          <text class="records__guest-title">登录后查看全部体验记录</text>
          <text class="records__guest-note">到馆预约和已归档的记录要登录后才能看</text>
        </view>
        <button class="records__guest-login" data-testid="experience-records-login" @click="emit('login')">去登录</button>
      </view>

      <view v-if="state.kind === 'loading'" class="records__list" aria-label="正在加载">
        <view v-for="index in 3" :key="index" class="records__skeleton" />
      </view>
      <view v-else-if="state.kind === 'error'" class="records__state" data-testid="experience-records-error">
        <text class="records__state-title">体验记录没有加载出来，请稍后重试</text>
        <button class="records__state-action" @click="load">重新加载</button>
      </view>

      <view v-else class="records__list">
        <view
          v-for="card in shownVenueCards"
          :key="card.booking.bookingId"
          class="records__venue"
          :data-testid="`experience-records-venue-${card.booking.bookingId}`"
        >
          <view class="records__venue-top">
            <text class="records__label records__label--orange">到馆 · 真机</text>
            <text :class="['records__venue-status', `records__venue-status--${card.view.statusTone.toLowerCase()}`]">{{ card.view.statusText }}</text>
            <text class="records__when">{{ card.view.when }}</text>
          </view>
          <text class="records__venue-title">{{ card.view.title }}</text>
          <text class="records__venue-money">{{ card.booking.venue.name }} · {{ card.view.money }}</text>
          <view class="records__venue-actions">
            <button class="records__venue-secondary" @click="emit('openBooking', card.booking.bookingId)">改期 / 取消</button>
            <button class="records__venue-primary" @click="emit('openBooking', card.booking.bookingId)">出示核销码</button>
          </view>
        </view>

        <view v-if="tab === 'VENUE' && signedIn" class="records__venue-more">
          <text v-if="venueCards.length === 0" class="records__state-title">还没有待到馆的预约</text>
          <text class="records__link" data-testid="experience-records-all-bookings" @click="emit('openAllBookings')">全部到馆预约 ›</text>
        </view>

        <view
          v-for="row in shownRows"
          :key="row.key"
          class="records__row"
          :data-testid="`experience-record-${row.key}`"
        >
          <image v-if="row.picture" class="records__picture" :src="row.picture" :alt="row.title" mode="aspectFill" />
          <view v-else class="records__picture" />
          <view class="records__copy">
            <view class="records__row-top">
              <text :class="['records__label', `records__label--${LABELS[row.type].tone}`]">{{ LABELS[row.type].label }}</text>
              <text class="records__when">{{ row.when }}</text>
            </view>
            <text class="records__title">{{ row.title }}</text>
            <text class="records__line" data-testid="experience-record-line">{{ row.line }}</text>
            <view class="records__actions">
              <text v-if="row.primary" class="records__action" @click="row.primary.run">{{ row.primary.label }}</text>
              <text class="records__action" @click="row.secondary.run">{{ row.secondary.label }}</text>
              <text
                v-if="row.productId"
                class="records__action records__action--grey"
                @click="emit('openProduct', row.productId)"
              >看关联商品</text>
            </view>
          </view>
        </view>

        <view
          v-if="tab !== 'VENUE' && shownRows.length === 0"
          class="records__state"
          data-testid="experience-records-empty"
        >
          <text class="records__state-title">还没有线上体验记录</text>
        </view>
        <text v-if="loadingMore" class="records__more">正在加载…</text>
        <text class="records__foot">未登录时的体验结果只保存在本机，登录后可归档到账户</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.records {
  min-height: 100vh;
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

.records__tabs {
  display: flex;
  padding: 0 16px;
  gap: 22px;
  border-bottom: 1px solid #f0f1f3;
  background: #ffffff;
}

.records__tab {
  padding: 10px 0;
  border-bottom: 3px solid transparent;
  color: #627187;
  font-size: 15px;
}

.records__tab--active {
  border-bottom-color: #ff6a00;
  color: #202c42;
  font-weight: 700;
}

.records__body {
  display: flex;
  padding: 12px 16px calc(16px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 10px;
}

.records__guest {
  display: flex;
  padding: 10px 12px 10px 14px;
  align-items: center;
  gap: 10px;
  border-radius: 12px;
  background: #fff0e3;
}

.records__guest-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.records__guest-title {
  font-size: 14px;
  font-weight: 500;
}

.records__guest-note {
  color: #627187;
  font-size: 12px;
}

.records__guest-login {
  display: inline-flex;
  min-height: 44px;
  padding: 0 16px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  white-space: nowrap;
}

.records__list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.records__skeleton {
  height: 96px;
  border-radius: 12px;
  background: #edf0f3;
}

.records__state {
  display: flex;
  min-height: 96px;
  padding: 16px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 12px;
  background: #ffffff;
}

.records__state-title {
  color: #738096;
  font-size: 14px;
}

.records__state-action {
  height: 44px;
  padding: 0 22px;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  font-size: 14px;
}

.records__venue {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 8px;
  border-radius: 12px;
  background: #ffffff;
}

.records__venue-top,
.records__row-top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.records__label {
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 11px;
  white-space: nowrap;
}

.records__label--orange {
  color: #ba470b;
  background: #fff0e3;
}

.records__label--green {
  color: #11804a;
  background: #e2f6ed;
}

.records__label--blue {
  color: #1d63c4;
  background: #e6f0ff;
}

.records__venue-status {
  color: #11804a;
  font-size: 12px;
}

.records__venue-status--pending {
  color: #e85400;
}

.records__venue-status--off {
  color: #738096;
}

.records__when {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  white-space: nowrap;
}

.records__venue-title {
  font-size: 15px;
  font-weight: 500;
}

.records__venue-money {
  color: #627187;
  font-size: 12px;
}

.records__venue-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.records__venue-secondary,
.records__venue-primary {
  display: flex;
  min-height: 32px;
  padding: 0 12px;
  align-items: center;
  border-radius: 999px;
  font-size: 13px;
  box-sizing: border-box;
}

.records__venue-secondary {
  border: 1px solid #c9d3e1;
}

.records__venue-primary {
  color: #ffffff;
  background: #ff6a00;
}

.records__venue-more {
  display: flex;
  padding: 4px 0;
  align-items: center;
  justify-content: space-between;
}

.records__link {
  margin-left: auto;
  padding: 12px 0;
  color: #ba470b;
  font-size: 13px;
}

.records__row {
  display: flex;
  padding: 12px;
  gap: 12px;
  border-radius: 12px;
  background: #ffffff;
}

.records__picture {
  width: 72px;
  height: 72px;
  flex: 0 0 auto;
  border-radius: 10px;
  background: #eef2f8;
}

.records__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.records__title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.records__line {
  color: #536178;
  font-size: 12px;
}

.records__actions {
  display: flex;
  margin-top: 2px;
  gap: 14px;
  font-size: 13px;
}

/* 13px links on the board; the padding gives each a taller tap area without moving the row. */
.records__action {
  margin: -8px 0;
  padding: 8px 0;
  color: #ba470b;
  white-space: nowrap;
}

.records__action--grey {
  color: #536178;
}

.records__more {
  padding: 8px 0;
  color: #738096;
  font-size: 12px;
  text-align: center;
}

.records__foot {
  color: #738096;
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
}
</style>
