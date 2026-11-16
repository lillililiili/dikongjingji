<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { ApiRequestError } from '@/shared/http/public-api-request'

import {
  isActivitySessionSelectable,
  resolveActivityIntent,
  resolveActivityPrimaryAction,
  type ActivityIntent,
} from '../../application/activity-intent'
import { ACTIVITY_PREVIEW_ENABLED } from '../../application/activity-preview'
import { activityApi } from '../../data/activity-api-client'
import type { ActivityDetail, ActivitySession } from '../../domain/activity'
import {
  ACTIVITY_STATUS_LABELS,
  ACTIVITY_TYPE_LABELS,
  formatActivityAgeRange,
  formatActivityDateTime,
  formatActivityPrice,
  formatActivitySessionSeats,
  formatActivityShortDateTime,
  formatActivityTime,
  isPerPersonPrice,
} from '../shared/activity-format'
import { wgs84ToGcj02 } from '../shared/map-coordinate'

type DetailState =
  | { kind: 'loading' }
  | { kind: 'ready'; activity: ActivityDetail }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<DetailState>({ kind: 'loading' })
const selectedSessionId = ref<string | null>(null)
let activityId = ''
let cityCode = ''
let requestVersion = 0

const activity = computed(() =>
  state.value.kind === 'ready' ? state.value.activity : null,
)
const selectedSession = computed<ActivitySession | null>(() =>
  activity.value?.sessions.find((session) => session.sessionId === selectedSessionId.value) ?? null,
)
const primaryAction = computed(() =>
  activity.value
    ? resolveActivityPrimaryAction(
        activity.value.enrollmentStatus,
        selectedSession.value?.status ?? null,
      )
    : null,
)
const ageText = computed(() =>
  activity.value ? formatActivityAgeRange(activity.value.ageRange) : null,
)
// Team sign-up makes no sense once nothing can be booked any more.
const teamEntryVisible = computed(() =>
  ACTIVITY_PREVIEW_ENABLED
  && Boolean(activity.value?.teamEnrollmentAllowed)
  && activity.value?.enrollmentStatus !== 'CLOSED'
  && activity.value?.enrollmentStatus !== 'ENDED',
)
const sessionNote = computed(() => {
  const session = selectedSession.value
  if (!session) return ''
  const closes = `报名截止 ${formatActivityShortDateTime(session.enrollmentClosesAt)}`
  return session.checkInOpensAt
    ? `${formatActivityTime(session.checkInOpensAt)} 开始签到 · ${closes}`
    : closes
})

async function loadDetail(): Promise<void> {
  if (!activityId || !cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  try {
    const loaded = await activityApi.loadDetail(activityId, cityCode)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', activity: loaded }
    // A session picked before a reload stays picked only if it can still be chosen.
    const stillSelectable = loaded.sessions.some((session) =>
      session.sessionId === selectedSessionId.value
      && isActivitySessionSelectable(session.status))
    if (!stillSelectable) selectedSessionId.value = null
  } catch (error) {
    if (requested !== requestVersion) return
    const missing = error instanceof ApiRequestError
      && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function selectSession(session: ActivitySession): void {
  if (!isActivitySessionSelectable(session.status)) return
  selectedSessionId.value = session.sessionId
}

function runIntent(intent: ActivityIntent): void {
  const resolution = resolveActivityIntent(intent, ACTIVITY_PREVIEW_ENABLED)
  if (resolution.action === 'SHOW_NOTICE') {
    uni.showToast({ title: resolution.message, icon: 'none' })
  }
}

function runPrimaryAction(): void {
  const action = primaryAction.value
  const session = selectedSession.value
  if (!action?.enabled || !session || !activity.value) return
  runIntent({
    type: action.kind === 'JOIN_WAITLIST' ? 'JOIN_WAITLIST' : 'ENROLL',
    activityId: activity.value.activityId,
    sessionId: session.sessionId,
  })
}

function copyAddress(address: string): void {
  uni.setClipboardData({
    data: address,
    success: () => uni.showToast({ title: '已复制地址', icon: 'none' }),
  })
}

function openNavigation(): void {
  const location = activity.value?.location
  if (!location) return
  if (location.latitude === null || location.longitude === null) {
    copyAddress(location.address)
    return
  }
  const point = wgs84ToGcj02({ latitude: location.latitude, longitude: location.longitude })
  uni.openLocation({
    latitude: point.latitude,
    longitude: point.longitude,
    name: location.name,
    address: location.address,
    // Without a map on this device the address is still useful in another app.
    fail: () => copyAddress(location.address),
  })
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('ACTIVITY')
}

onLoad((query) => {
  activityId = typeof query?.activityId === 'string' ? query.activityId : ''
  cityCode = typeof query?.cityCode === 'string' ? query.cityCode : ''
  void loadDetail()
})
</script>

<template>
  <view class="activity-detail" data-testid="activity-detail">
    <header class="activity-detail__header">
      <button
        class="activity-detail__back"
        data-testid="activity-detail-back"
        aria-label="返回"
        @click="goBack"
      >
        <UniIcons type="left" size="24" color="#435369" />
      </button>
      <text class="activity-detail__header-title">活动详情</text>
    </header>

    <view
      v-if="state.kind === 'loading'"
      class="activity-detail__content"
      data-testid="activity-detail-loading"
      aria-label="正在加载"
    >
      <view class="activity-detail__skeleton activity-detail__skeleton--cover" />
      <view class="activity-detail__skeleton activity-detail__skeleton--line" />
      <view class="activity-detail__skeleton activity-detail__skeleton--block" />
    </view>

    <view
      v-else-if="state.kind === 'missing'"
      class="activity-detail__state"
      data-testid="activity-detail-missing"
    >
      <text class="activity-detail__state-title">活动不存在或已下线</text>
      <text class="activity-detail__state-copy">可以回到赛事研学看看其他活动</text>
      <button class="activity-detail__state-action" data-testid="activity-detail-to-channel" @click="openHomeChannel('ACTIVITY')">
        去赛事研学
      </button>
    </view>

    <view
      v-else-if="state.kind === 'error'"
      class="activity-detail__state"
      data-testid="activity-detail-error"
    >
      <text class="activity-detail__state-title">活动加载失败，请稍后重试</text>
      <button class="activity-detail__state-action" data-testid="activity-detail-retry" @click="loadDetail">
        重新加载
      </button>
    </view>

    <template v-else-if="activity">
      <view class="activity-detail__content">
        <view :class="['activity-detail__cover', `activity-detail__cover--${activity.type.toLowerCase()}`]">
          <image
            v-if="activity.coverImage"
            class="activity-detail__cover-image"
            :src="activity.coverImage.url"
            :alt="activity.coverImage.alt"
            mode="aspectFill"
          />
          <text class="activity-detail__type">{{ ACTIVITY_TYPE_LABELS[activity.type] }}</text>
        </view>

        <view class="activity-detail__card">
          <text class="activity-detail__title" data-testid="activity-detail-title">{{ activity.title }}</text>
          <text v-if="activity.subtitle" class="activity-detail__subtitle">{{ activity.subtitle }}</text>
          <view class="activity-detail__price-row">
            <text class="activity-detail__price" data-testid="activity-detail-price">
              {{ formatActivityPrice(activity.price) }}
            </text>
            <text v-if="isPerPersonPrice(activity.price)" class="activity-detail__price-unit">/人</text>
            <text v-if="ageText" class="activity-detail__age">适合 {{ ageText }}</text>
            <text
              :class="[
                'activity-detail__status',
                `activity-detail__status--${activity.enrollmentStatus.toLowerCase()}`,
              ]"
              data-testid="activity-detail-status"
            >
              {{ ACTIVITY_STATUS_LABELS[activity.enrollmentStatus] }}
            </text>
          </view>
        </view>

        <view class="activity-detail__card">
          <view class="activity-detail__section-heading">
            <text class="activity-detail__section-title">选择场次</text>
            <text v-if="activity.scheduleNote" class="activity-detail__section-note">{{ activity.scheduleNote }}</text>
          </view>
          <text v-if="activity.sessions.length === 0" class="activity-detail__text">暂无场次</text>
          <button
            v-for="session in activity.sessions"
            :key="session.sessionId"
            :class="[
              'activity-detail__session',
              {
                'activity-detail__session--selected': session.sessionId === selectedSessionId,
                'activity-detail__session--off': !isActivitySessionSelectable(session.status),
              },
            ]"
            :data-testid="`activity-session-${session.sessionId}`"
            :data-status="session.status"
            :aria-pressed="session.sessionId === selectedSessionId"
            :aria-disabled="!isActivitySessionSelectable(session.status)"
            @click="selectSession(session)"
          >
            <view class="activity-detail__session-copy">
              <text class="activity-detail__session-time">{{ formatActivityDateTime(session.startsAt) }}</text>
              <text v-if="session.label" class="activity-detail__session-label">{{ session.label }}</text>
            </view>
            <text
              :class="[
                'activity-detail__session-seats',
                `activity-detail__session-seats--${session.status.toLowerCase()}`,
              ]"
            >
              {{ formatActivitySessionSeats(session) }}
            </text>
          </button>
          <text v-if="sessionNote" class="activity-detail__session-note" data-testid="activity-session-note">
            {{ sessionNote }}
          </text>
          <text
            v-if="selectedSession?.status === 'FULL_WAITLIST'"
            class="activity-detail__session-note"
          >
            本场已满，加入候补后有名额会通知你
          </text>
        </view>

        <view class="activity-detail__card">
          <view class="activity-detail__row">
            <text class="activity-detail__row-label">主办</text>
            <text class="activity-detail__row-value">{{ activity.organizer.name }}</text>
          </view>
          <view v-if="activity.coOrganizer" class="activity-detail__row">
            <text class="activity-detail__row-label">承办</text>
            <text class="activity-detail__row-value">{{ activity.coOrganizer.name }}</text>
          </view>
          <view class="activity-detail__row activity-detail__row--location">
            <text class="activity-detail__row-label">地点</text>
            <view class="activity-detail__row-value">
              <text class="activity-detail__location-name">{{ activity.location.name }}</text>
              <text class="activity-detail__location-address">{{ activity.location.address }}</text>
            </view>
            <button
              class="activity-detail__navigate"
              data-testid="activity-detail-navigate"
              @click="openNavigation"
            >
              导航
            </button>
          </view>
        </view>

        <view v-if="activity.eligibilityNote || activity.seatNote" class="activity-detail__card">
          <text class="activity-detail__section-title">参与条件</text>
          <text v-if="activity.eligibilityNote" class="activity-detail__text">{{ activity.eligibilityNote }}</text>
          <text v-if="activity.seatNote" class="activity-detail__text">{{ activity.seatNote }}</text>
        </view>

        <view v-if="activity.refundNote" class="activity-detail__card">
          <text class="activity-detail__section-title">退改规则</text>
          <text class="activity-detail__text">{{ activity.refundNote }}</text>
        </view>

        <button
          v-if="teamEntryVisible"
          class="activity-detail__team"
          data-testid="activity-detail-team"
          @click="runIntent({ type: 'TEAM_ENROLL', activityId: activity.activityId })"
        >
          <view class="activity-detail__team-copy">
            <text class="activity-detail__team-title">团队报名</text>
            <text class="activity-detail__team-note">学校、机构多人参加</text>
          </view>
          <UniIcons type="right" size="16" color="#8a929b" />
        </button>
      </view>

      <view v-if="ACTIVITY_PREVIEW_ENABLED && primaryAction" class="activity-detail__actions">
        <button
          class="activity-detail__consult"
          data-testid="activity-detail-consult"
          @click="runIntent({ type: 'CONSULT', activityId: activity.activityId })"
        >
          咨询
        </button>
        <button
          :class="[
            'activity-detail__primary',
            { 'activity-detail__primary--off': !primaryAction.enabled },
          ]"
          data-testid="activity-detail-primary"
          :data-kind="primaryAction.kind"
          :disabled="!primaryAction.enabled"
          @click="runPrimaryAction"
        >
          {{ primaryAction.label }}
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.activity-detail {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f7f8fc;
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

.activity-detail__header {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  min-height: calc(50px + var(--status-bar-height));
  padding: var(--status-bar-height) 12px 0;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid #edf0f6;
  background: #ffffff;
  box-sizing: border-box;
}

.activity-detail__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
}

.activity-detail__header-title {
  font-size: 18px;
  font-weight: 700;
}

.activity-detail__content {
  display: flex;
  padding: 12px 12px 20px;
  flex: 1;
  flex-direction: column;
  gap: 10px;
}

.activity-detail__skeleton {
  border-radius: 16px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
  background-size: 400% 100%;
  animation: activity-detail-skeleton 1.25s ease infinite;
}

.activity-detail__skeleton--cover {
  height: 170px;
}

.activity-detail__skeleton--line {
  height: 64px;
}

.activity-detail__skeleton--block {
  height: 180px;
}

@keyframes activity-detail-skeleton {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}

.activity-detail__state {
  display: flex;
  margin: 12px;
  padding: 48px 20px;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  border-radius: 16px;
  background: #ffffff;
}

.activity-detail__state-title {
  color: #4f5966;
  font-size: 16px;
  font-weight: 700;
}

.activity-detail__state-copy {
  color: #748197;
  font-size: 13px;
}

.activity-detail__state-action {
  display: flex;
  min-width: 120px;
  min-height: 44px;
  margin-top: 10px;
  padding: 0 18px;
  align-items: center;
  justify-content: center;
  border-radius: 22px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 600;
}

.activity-detail__cover {
  position: relative;
  height: 170px;
  overflow: hidden;
  border-radius: 18px;
}

.activity-detail__cover--contest {
  background: linear-gradient(135deg, #e4efff, #cfe1ff);
}

.activity-detail__cover--training {
  background: linear-gradient(135deg, #fff2cd, #ffe39a);
}

.activity-detail__cover--study_tour {
  background: linear-gradient(135deg, #dff5ed, #c4ecdc);
}

.activity-detail__cover--open_day {
  background: linear-gradient(135deg, #eee7ff, #dccffa);
}

.activity-detail__cover-image {
  width: 100%;
  height: 100%;
}

.activity-detail__type {
  position: absolute;
  top: 12px;
  left: 12px;
  padding: 4px 10px;
  border-radius: 12px;
  color: #33466a;
  background: rgba(255, 255, 255, 0.92);
  font-size: 12px;
  font-weight: 600;
}

.activity-detail__card {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 8px;
  border-radius: 16px;
  background: #ffffff;
}

.activity-detail__title {
  font-size: 19px;
  font-weight: 700;
  line-height: 27px;
}

.activity-detail__subtitle {
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.activity-detail__price-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px;
}

.activity-detail__price {
  color: #e65e00;
  font-size: 24px;
  font-weight: 700;
}

.activity-detail__price-unit {
  color: #748197;
  font-size: 12px;
}

.activity-detail__age {
  margin-left: 8px;
  color: #627187;
  font-size: 12px;
}

.activity-detail__status {
  margin-left: auto;
  font-size: 12px;
  font-weight: 600;
}

.activity-detail__status--open {
  color: #159a70;
}

.activity-detail__status--full_waitlist {
  color: #ba470b;
}

.activity-detail__status--full,
.activity-detail__status--closed,
.activity-detail__status--ended {
  color: #8a929b;
}

.activity-detail__section-heading {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.activity-detail__section-title {
  font-size: 16px;
  font-weight: 700;
}

.activity-detail__section-note {
  color: #748197;
  font-size: 12px;
}

.activity-detail__text {
  color: #4e5d73;
  font-size: 14px;
  line-height: 22px;
}

.activity-detail__session {
  display: flex;
  min-height: 52px;
  padding: 8px 12px;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border: 1px solid #e4eaf3;
  border-radius: 12px;
  background: #ffffff;
  box-sizing: border-box;
}

.activity-detail__session--selected {
  border-color: #ff963f;
  background: #fff7ee;
}

.activity-detail__session--off {
  background: #f7f8fb;
}

.activity-detail__session-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.activity-detail__session-time {
  color: #202c42;
  font-size: 14px;
  font-weight: 600;
}

.activity-detail__session--off .activity-detail__session-time {
  color: #8a929b;
}

.activity-detail__session-label {
  color: #748197;
  font-size: 12px;
}

.activity-detail__session-seats {
  flex: 0 0 auto;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.activity-detail__session-seats--open {
  color: #159a70;
}

.activity-detail__session-seats--full_waitlist {
  color: #ba470b;
}

.activity-detail__session-seats--full,
.activity-detail__session-seats--closed,
.activity-detail__session-seats--ended {
  color: #8a929b;
}

.activity-detail__session-note {
  color: #70549d;
  font-size: 12px;
  line-height: 18px;
}

.activity-detail__row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  font-size: 14px;
  line-height: 22px;
}

.activity-detail__row--location {
  align-items: center;
}

.activity-detail__row-label {
  width: 32px;
  flex: 0 0 auto;
  color: #748197;
}

.activity-detail__row-value {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.activity-detail__location-name {
  font-weight: 600;
}

.activity-detail__location-address {
  color: #627187;
  font-size: 12px;
  line-height: 18px;
}

.activity-detail__navigate {
  display: flex;
  min-width: 56px;
  min-height: 44px;
  padding: 0 12px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 22px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  font-weight: 600;
}

.activity-detail__team {
  display: flex;
  min-height: 60px;
  padding: 10px 14px;
  align-items: center;
  justify-content: space-between;
  border-radius: 16px;
  background: #ffffff;
}

.activity-detail__team-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.activity-detail__team-title {
  font-size: 15px;
  font-weight: 700;
}

.activity-detail__team-note {
  color: #748197;
  font-size: 12px;
}

.activity-detail__actions {
  position: sticky;
  bottom: 0;
  z-index: 4;
  display: flex;
  padding: 10px 12px calc(12px + env(safe-area-inset-bottom));
  gap: 10px;
  border-top: 1px solid #e8edf5;
  background: rgba(255, 255, 255, 0.95);
}

.activity-detail__consult {
  display: flex;
  min-width: 84px;
  min-height: 46px;
  align-items: center;
  justify-content: center;
  border-radius: 24px;
  color: #455d80;
  background: #edf2fa;
  font-size: 14px;
  font-weight: 600;
}

.activity-detail__primary {
  display: flex;
  min-height: 46px;
  flex: 1;
  align-items: center;
  justify-content: center;
  border-radius: 24px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 700;
}

/* uni-app greys a disabled button's label to 30% black; set the colours it should really have. */
.activity-detail__primary--off,
.activity-detail__primary[disabled] {
  color: #8a929b;
  background: #e9edf3;
}
</style>
