<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  WEEKDAY_TITLES,
  addMonths,
  buildCalendarMonth,
  calendarMonthRange,
  compareMonths,
  dateOfSession,
  describeDaySessions,
  formatMonthTitle,
  formatPickedDay,
  isSessionPickable,
  monthOf,
  type YearMonth,
} from '../../application/calendar-view'
import { activityApi } from '../../data/activity-api-client'
import { resolveActivityPreviewCover } from '../../data/preview/activity-preview-covers'
import type { ActivityDetail } from '../../domain/activity'
import { formatActivityTime } from '../shared/activity-format'

// 选择日期 (board CalendarPick, S5-3): a whole month of one activity's sessions. Picking a day lists its
// sessions; 确定 hands the chosen session back to the page that opened this one (sign-up or reschedule).
const props = defineProps<{
  activityId: string
  cityCode: string
  // The session picked before, if any.
  sessionId: string | null
  // A reschedule leaves this session; it is not offered.
  exceptSessionId: string | null
  // How many people must fit (a reschedule moves the whole sign-up).
  people: number
}>()

const emit = defineEmits<{
  back: []
  picked: [sessionId: string]
}>()

type CalendarState =
  | { kind: 'loading' }
  | { kind: 'ready'; activity: ActivityDetail }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<CalendarState>({ kind: 'loading' })
const month = ref<YearMonth>(monthOf('2026-01-01'))
const pickedDate = ref<string | null>(null)
const pickedSessionId = ref<string | null>(null)
let requestVersion = 0

const activity = computed(() => (state.value.kind === 'ready' ? state.value.activity : null))
const now = new Date()
const range = computed(() => (activity.value ? calendarMonthRange(activity.value.sessions, now) : null))
const cells = computed(() =>
  activity.value ? buildCalendarMonth(month.value, activity.value.sessions, now, props.people, props.exceptSessionId) : [])
const canPrev = computed(() => Boolean(range.value && compareMonths(month.value, range.value.first) > 0))
const canNext = computed(() => Boolean(range.value && compareMonths(month.value, range.value.last) < 0))
const daySessions = computed(() =>
  activity.value && pickedDate.value
    ? describeDaySessions(pickedDate.value, activity.value.sessions, props.people, props.exceptSessionId)
    : [])
const pickedSession = computed(() =>
  activity.value?.sessions.find((session) => session.sessionId === pickedSessionId.value) ?? null)
const summary = computed(() => {
  if (!pickedDate.value) return '请选择日期'
  const session = pickedSession.value
  if (!session) return `已选择：${formatPickedDay(pickedDate.value)} · 请选择场次`
  return `已选择：${formatPickedDay(pickedDate.value)} ${session.label ? `${session.label} ` : ''}${formatActivityTime(session.startsAt)}`
})
const coverUrl = computed(() =>
  activity.value ? activity.value.coverImage?.url ?? resolveActivityPreviewCover(activity.value.activityId) : null)
const contextSub = computed(() =>
  props.exceptSessionId
    ? `改期按整单 · ${props.people} 人一起改 · 选日期后再选场次`
    : '选日期后再选场次')
const notes = computed(() => [
  '可选：当天有还能报名的场次；今天以前的日子不能选',
  '已满 / 已截止：当天的场次都已报满或停止报名',
  props.exceptSessionId
    ? '改期只能改到同一活动的其他场次，名额不足不能改，无需补差价'
    : '已满的场次允许候补时，可以在活动详情里加入候补',
])

function pickDay(date: string, open: boolean): void {
  if (!open || !activity.value) return
  pickedDate.value = date
  const options = describeDaySessions(date, activity.value.sessions, props.people, props.exceptSessionId)
  const pickable = options.filter((option) => option.pickable)
  pickedSessionId.value = pickable.length === 1 ? pickable[0].sessionId : null
}

function pickSession(sessionId: string, pickable: boolean): void {
  if (pickable) pickedSessionId.value = sessionId
}

function moveMonth(delta: number): void {
  if (delta < 0 ? !canPrev.value : !canNext.value) return
  month.value = addMonths(month.value, delta)
}

function confirm(): void {
  if (pickedSessionId.value) emit('picked', pickedSessionId.value)
}

async function load(): Promise<void> {
  if (!props.activityId || !props.cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  try {
    const loaded = await activityApi.loadDetail(props.activityId, props.cityCode)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', activity: loaded }
    const startRange = calendarMonthRange(loaded.sessions, now)
    // Start on the month of the session picked before, else on the first month with a session to pick.
    const before = loaded.sessions.find((session) =>
      session.sessionId === props.sessionId && isSessionPickable(session, props.people, props.exceptSessionId))
    const firstOpen = loaded.sessions
      .filter((session) => isSessionPickable(session, props.people, props.exceptSessionId))
      .map(dateOfSession)
      .sort()[0]
    month.value = before ? monthOf(dateOfSession(before)) : firstOpen ? monthOf(firstOpen) : startRange.first
    if (before) {
      pickedDate.value = dateOfSession(before)
      pickedSessionId.value = before.sessionId
    }
  } catch (error) {
    if (requested !== requestVersion) return
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

watch(() => [props.activityId, props.cityCode], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="calendar" data-testid="activity-calendar">
    <AppPageHeader title="选择活动日期" test-id="activity-calendar" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="calendar__body" data-testid="activity-calendar-loading" aria-label="正在加载">
      <view class="calendar__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="calendar__state" data-testid="activity-calendar-missing">
      <text class="calendar__state-title">没有找到这个活动</text>
      <button class="calendar__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="calendar__state" data-testid="activity-calendar-error">
      <text class="calendar__state-title">加载失败</text>
      <text class="calendar__state-text">请检查网络后重试</text>
      <button class="calendar__state-button" data-testid="activity-calendar-retry" @click="load">重试</button>
    </view>

    <view v-else-if="activity" class="calendar__body">
      <view class="calendar__card calendar__card--context">
        <image v-if="coverUrl" class="calendar__cover" :src="coverUrl" alt="" mode="aspectFill" />
        <view class="calendar__context">
          <text class="calendar__context-title">{{ activity.title }}</text>
          <text class="calendar__context-sub">{{ contextSub }}</text>
        </view>
      </view>

      <view class="calendar__card calendar__card--month">
        <view class="calendar__month-bar">
          <button
            class="calendar__nav"
            aria-label="上个月"
            data-testid="activity-calendar-prev"
            :disabled="!canPrev"
            @click="moveMonth(-1)"
          >
            <LineIcon name="back" :size="20" :color="canPrev ? '#202c42' : '#c9d3e1'" :stroke-width="2" />
          </button>
          <text class="calendar__month-title" data-testid="activity-calendar-month">{{ formatMonthTitle(month) }}</text>
          <button
            class="calendar__nav"
            aria-label="下个月"
            data-testid="activity-calendar-next"
            :disabled="!canNext"
            @click="moveMonth(1)"
          >
            <LineIcon name="forward" :size="20" :color="canNext ? '#202c42' : '#c9d3e1'" :stroke-width="2" />
          </button>
        </view>
        <view class="calendar__grid">
          <text v-for="title in WEEKDAY_TITLES" :key="title" class="calendar__weekday">{{ title }}</text>
        </view>
        <view class="calendar__grid" role="grid" :aria-label="formatMonthTitle(month)">
          <template v-for="cell in cells" :key="cell.key">
            <view v-if="!cell.day" class="calendar__blank" />
            <button
              v-else
              :class="[
                'calendar__day',
                `calendar__day--${cell.day.state.toLowerCase()}`,
                { 'calendar__day--today': cell.day.today, 'calendar__day--on': cell.day.date === pickedDate },
              ]"
              role="gridcell"
              :data-testid="`activity-calendar-day-${cell.day.date}`"
              :data-state="cell.day.state"
              :aria-pressed="cell.day.date === pickedDate"
              :aria-disabled="cell.day.state !== 'OPEN'"
              @click="pickDay(cell.day.date, cell.day.state === 'OPEN')"
            >
              <text class="calendar__day-number">{{ cell.day.day }}</text>
              <text class="calendar__day-sub">{{ cell.day.sub }}</text>
            </button>
          </template>
        </view>
        <view class="calendar__legend">
          <view class="calendar__legend-item"><view class="calendar__swatch calendar__swatch--open" />可选</view>
          <view class="calendar__legend-item"><view class="calendar__swatch calendar__swatch--on" />已选</view>
          <view class="calendar__legend-item"><view class="calendar__swatch calendar__swatch--off" />已满 / 已截止</view>
        </view>
      </view>

      <view v-if="pickedDate" class="calendar__card calendar__card--sessions" data-testid="activity-calendar-sessions">
        <text class="calendar__heading">{{ formatPickedDay(pickedDate) }} 的场次</text>
        <view class="calendar__sessions">
          <button
            v-for="option in daySessions"
            :key="option.sessionId"
            :class="['calendar__session', { 'calendar__session--on': option.sessionId === pickedSessionId, 'calendar__session--off': !option.pickable }]"
            :data-testid="`activity-calendar-session-${option.sessionId}`"
            :aria-pressed="option.sessionId === pickedSessionId"
            :aria-disabled="!option.pickable"
            @click="pickSession(option.sessionId, option.pickable)"
          >
            <text class="calendar__session-title">{{ option.title }}</text>
            <text class="calendar__session-sub">{{ option.sub }}</text>
          </button>
        </view>
      </view>

      <view class="calendar__card calendar__card--notes">
        <view v-for="line in notes" :key="line" class="calendar__note">
          <view class="calendar__note-dot" />
          <text>{{ line }}</text>
        </view>
      </view>
    </view>

    <view v-if="activity" class="calendar__actions">
      <text class="calendar__summary" data-testid="activity-calendar-summary">{{ summary }}</text>
      <button
        :class="['calendar__confirm', { 'calendar__confirm--off': !pickedSessionId }]"
        data-testid="activity-calendar-confirm"
        :disabled="!pickedSessionId"
        @click="confirm"
      >
        确定
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board CalendarPick (390 wide). */
.calendar {
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

.calendar__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.calendar__skeleton {
  height: 360px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.calendar__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.calendar__state-title {
  font-size: 16px;
  font-weight: 700;
}

.calendar__state-text {
  color: #738096;
  font-size: 13px;
}

.calendar__state-button {
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

.calendar__card {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.calendar__card--context {
  flex-direction: row;
  padding: 12px 14px;
  align-items: center;
  gap: 10px;
}

.calendar__cover {
  display: block;
  width: 44px;
  height: 44px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.calendar__context {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.calendar__context-title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.calendar__context-sub {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.calendar__card--month {
  gap: 8px;
  padding: 10px 12px 12px;
}

.calendar__month-bar {
  display: flex;
  align-items: center;
}

.calendar__nav {
  display: flex;
  width: 40px;
  height: 40px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #edf3ff;
}

.calendar__nav + .calendar__month-title + .calendar__nav {
  background: #f1ecff;
}

.calendar__nav[disabled] {
  opacity: 0.55;
}

.calendar__month-title {
  flex: 1;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-align: center;
  white-space: nowrap;
}

.calendar__grid {
  display: grid;
  gap: 4px;
  grid-template-columns: repeat(7, minmax(0, 1fr));
}

.calendar__weekday {
  color: #738096;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
  white-space: nowrap;
}

.calendar__blank {
  height: 50px;
}

.calendar__day {
  display: flex;
  min-width: 0;
  height: 50px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  border: 1.5px solid transparent;
  border-radius: 10px;
  color: #202c42;
  background: #ffffff;
  box-sizing: border-box;
}

.calendar__day--open {
  box-shadow: 0 2px 6px rgba(32, 44, 66, 0.08);
}

.calendar__day--past,
.calendar__day--none {
  color: #91a0b4;
  background: transparent;
}

.calendar__day--full {
  color: #91a0b4;
  background: #eef0f3;
}

.calendar__day--today {
  border-color: #ffd8b8;
}

.calendar__day--on {
  border-color: #ff6a00;
  color: #ffffff;
  background: #ff6a00;
}

.calendar__day-number {
  font-size: 15px;
  line-height: 20px;
  white-space: nowrap;
}

.calendar__day--today .calendar__day-number,
.calendar__day--on .calendar__day-number {
  font-weight: 700;
}

.calendar__day-sub {
  min-height: 14px;
  color: #ba470b;
  font-size: 11px;
  line-height: 14px;
  white-space: nowrap;
}

.calendar__day--full .calendar__day-sub,
.calendar__day--past .calendar__day-sub,
.calendar__day--none .calendar__day-sub {
  color: #91a0b4;
}

.calendar__day--today:not(.calendar__day--full) .calendar__day-sub {
  color: #ba470b;
}

.calendar__day--on .calendar__day-sub {
  color: rgba(255, 255, 255, 0.85);
}

.calendar__legend {
  display: flex;
  padding-top: 4px;
  align-items: center;
  gap: 14px;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.calendar__legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.calendar__swatch {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  box-sizing: border-box;
}

.calendar__swatch--open {
  border: 1.5px solid #e5ebf3;
  background: #ffffff;
}

.calendar__swatch--on {
  background: #ff6a00;
}

.calendar__swatch--off {
  background: #eef0f3;
}

.calendar__card--sessions {
  gap: 10px;
  padding: 14px;
}

.calendar__heading {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.calendar__sessions {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.calendar__session {
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

.calendar__session--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.calendar__session--off {
  background: #f5f7fc;
}

.calendar__session-title {
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
  white-space: nowrap;
}

.calendar__session--on .calendar__session-title {
  color: #ba470b;
}

.calendar__session--off .calendar__session-title {
  color: #738096;
}

.calendar__session-sub {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.calendar__session--off .calendar__session-sub {
  color: #91a0b4;
}

.calendar__card--notes {
  gap: 6px;
  padding: 12px 14px;
}

.calendar__note {
  display: flex;
  gap: 8px;
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.calendar__note-dot {
  width: 4px;
  height: 4px;
  flex: none;
  margin: 7px 2px 0;
  border-radius: 50%;
  background: #91a0b4;
}

.calendar__actions {
  position: sticky;
  bottom: 0;
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.calendar__summary {
  font-size: 15px;
  line-height: 21px;
  white-space: nowrap;
}

.calendar__confirm {
  display: flex;
  height: 46px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
}

.calendar__confirm--off {
  background: #c9d3e1;
}
</style>
