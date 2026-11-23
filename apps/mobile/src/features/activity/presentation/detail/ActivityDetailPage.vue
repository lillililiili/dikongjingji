<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { ApiRequestError } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'
import { wgs84ToGcj02 } from '@/shared/map/map-coordinate'

import {
  CHOOSE_SESSION_PROMPT,
  isActivitySessionSelectable,
  resolveEnrollmentPayMode,
  resolveActivityIntent,
  resolveActivityPrimaryAction,
  type ActivityIntent,
} from '../../application/activity-intent'
import { ACTIVITY_PREVIEW_ENABLED } from '../../application/activity-preview'
import { activityApi } from '../../data/activity-api-client'
import { resolveActivityPreviewCover } from '../../data/preview/activity-preview-covers'
import type { ActivityDetail, ActivitySession } from '../../domain/activity'
import {
  buildActivityDetailUrl,
  buildActivityEnrollUrl,
  buildActivityTeamSignupUrl,
} from '../../navigation/activity-routes'
import {
  ACTIVITY_TYPE_LABELS,
  formatActivityDay,
  formatActivityPrice,
  formatActivitySessionSeats,
  formatActivityShortDateTime,
  formatActivityTime,
  isPerPersonPrice,
} from '../shared/activity-format'

type DetailState =
  | { kind: 'loading' }
  | { kind: 'ready'; activity: ActivityDetail }
  | { kind: 'missing' }
  | { kind: 'error' }

// Board ActDetail's share mark: a box with an arrow up out of it.
const SHARE_ICON = 'M12 15V4M8 8l4-4 4 4M5 13v6h14v-6'

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
// The server's picture when it has one, else the sample picture of a preview build.
const coverUrl = computed(() =>
  activity.value
    ? activity.value.coverImage?.url ?? resolveActivityPreviewCover(activity.value.activityId)
    : null,
)
// Board ActDetail: the right of the price row says how many places a session has, and how many
// are taken once one is picked. Only shown when every session has the same real capacity.
const seatLine = computed(() => {
  const current = activity.value
  if (!current || current.sessions.length === 0) return null
  const session = selectedSession.value
  if (session && session.capacity > 0) {
    return `已报名 ${Math.max(0, session.capacity - session.seatsLeft)} / ${session.capacity}`
  }
  const capacities = new Set(current.sessions.map((item) => item.capacity))
  const [capacity] = [...capacities]
  return capacities.size === 1 && capacity > 0 ? `每场 ${capacity} 个名额` : null
})
// Tapped 选择场次 without a session: the line under the sessions turns into the prompt.
const askedForSession = ref(false)
// Team sign-up makes no sense once nothing can be booked any more.
const teamEntryVisible = computed(() =>
  ACTIVITY_PREVIEW_ENABLED
  && Boolean(activity.value?.teamEnrollmentAllowed)
  && activity.value?.enrollmentStatus !== 'CLOSED'
  && activity.value?.enrollmentStatus !== 'ENDED',
)
const sessionNote = computed(() => {
  const current = activity.value
  if (current?.enrollmentStatus === 'ENDED') return '本期活动已结束'
  if (current?.enrollmentStatus === 'CLOSED') return '报名已截止，下次开放请留意赛事研学频道'
  const session = selectedSession.value
  if (!session) return askedForSession.value ? CHOOSE_SESSION_PROMPT : '选好场次后显示签到和报名截止时间'
  if (session.status === 'FULL_WAITLIST') return '本场已满，加入候补后有名额会通知你'
  const closes = `报名截止 ${formatActivityShortDateTime(session.enrollmentClosesAt)}`
  return session.checkInOpensAt
    ? `${formatActivityTime(session.checkInOpensAt)} 起签到 · ${closes}`
    : closes
})
const sessionNoteAsks = computed(() => !selectedSession.value && askedForSession.value)

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
  askedForSession.value = false
}

function runIntent(intent: ActivityIntent): void {
  // 团队报名 (S5-3, preview builds): the team form, on the session picked here or the first one open.
  if (intent.type === 'TEAM_ENROLL' && ACTIVITY_PREVIEW_ENABLED && activity.value
    && resolveEnrollmentPayMode(activity.value) !== 'UNSUPPORTED') {
    const picked = selectedSession.value?.status === 'OPEN' ? selectedSession.value : null
    const session = picked ?? activity.value.sessions.find((item) => item.status === 'OPEN') ?? null
    uni.navigateTo({
      url: buildActivityTeamSignupUrl(activity.value.activityId, session?.sessionId ?? '', cityCode),
    })
    return
  }
  // 咨询 (step G1, preview builds): the organiser's conversation with a card about this activity.
  // An organiser that cannot take conversations sends the customer on to the platform support.
  if (intent.type === 'CONSULT' && SUPPORT_PREVIEW_ENABLED && activity.value) {
    openSupportChat(
      { kind: 'PROVIDER', organizationId: activity.value.organizer.organizationId },
      { type: 'ACTIVITY', refId: activity.value.activityId, title: activity.value.title, subtitle: formatActivityPrice(activity.value.price) },
    )
    return
  }
  const resolution = resolveActivityIntent(intent, ACTIVITY_PREVIEW_ENABLED)
  if (resolution.action === 'SHOW_NOTICE') {
    uni.showToast({ title: resolution.message, icon: 'none' })
  }
}

// 分享 (preview builds): only 复制链接 of this activity in this city.
const shareTarget = ref<ShareTarget | null>(null)
function openShare(): void {
  const current = activity.value
  if (!current) return
  shareTarget.value = {
    subject: '活动',
    title: current.title,
    subtitle: formatActivityPrice(current.price),
    owner: current.organizer.name,
    imageUrl: coverUrl.value ?? null,
    path: buildActivityDetailUrl(current.activityId, cityCode),
  }
}

function runPrimaryAction(): void {
  const action = primaryAction.value
  if (action?.kind === 'CHOOSE_SESSION') {
    askedForSession.value = true
    uni.showToast({ title: CHOOSE_SESSION_PROMPT, icon: 'none' })
    return
  }
  const session = selectedSession.value
  if (!action?.enabled || !session || !activity.value) return
  // Free and per-person activities have a real sign-up form; the form asks for a login when it needs
  // one, and says so when the organiser cannot take payment online (plan §20.1). Quoted prices stay a prompt.
  if (action.kind === 'ENROLL' && resolveEnrollmentPayMode(activity.value) !== 'UNSUPPORTED' && ACTIVITY_PREVIEW_ENABLED) {
    uni.navigateTo({
      url: buildActivityEnrollUrl(activity.value.activityId, session.sessionId, cityCode),
    })
    return
  }
  // 加入候补 (S5-3): the sign-up form in its waitlist mode, on the full session picked here.
  if (action.kind === 'JOIN_WAITLIST' && resolveEnrollmentPayMode(activity.value) !== 'UNSUPPORTED' && ACTIVITY_PREVIEW_ENABLED) {
    uni.navigateTo({
      url: buildActivityEnrollUrl(activity.value.activityId, session.sessionId, cityCode, 'waitlist'),
    })
    return
  }
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
    <!-- With an activity on screen the back button floats on its picture instead. -->
    <AppPageHeader v-if="!activity" title="活动详情" test-id="activity-detail" @back="goBack" />

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
        <view class="activity-detail__cover">
          <image
            v-if="coverUrl"
            class="activity-detail__cover-image"
            :src="coverUrl"
            :alt="activity.title"
            mode="aspectFill"
          />
          <button
            class="activity-detail__float"
            data-testid="activity-detail-back"
            aria-label="返回"
            @click="goBack"
          >
            <view class="activity-detail__float-circle">
              <LineIcon name="back" :size="20" color="#ffffff" :stroke-width="2" />
            </view>
          </button>
          <button
            v-if="SUPPORT_PREVIEW_ENABLED"
            class="activity-detail__float activity-detail__float--share"
            data-testid="activity-detail-share"
            aria-label="分享"
            @click="openShare"
          >
            <view class="activity-detail__float-circle">
              <LineIcon :d="SHARE_ICON" :size="20" color="#ffffff" :stroke-width="2" />
            </view>
          </button>
          <text class="activity-detail__type">{{ ACTIVITY_TYPE_LABELS[activity.type] }}</text>
        </view>

        <view class="activity-detail__stack">
          <view class="activity-detail__card">
            <text class="activity-detail__title" data-testid="activity-detail-title">{{ activity.title }}</text>
            <text v-if="activity.subtitle" class="activity-detail__subtitle">{{ activity.subtitle }}</text>
            <view class="activity-detail__price-row">
              <text class="activity-detail__price" data-testid="activity-detail-price">
                {{ formatActivityPrice(activity.price) }}
              </text>
              <text v-if="isPerPersonPrice(activity.price)" class="activity-detail__price-unit">/人</text>
              <text v-if="seatLine" class="activity-detail__seats" data-testid="activity-detail-seats">{{ seatLine }}</text>
            </view>
          </view>

          <view class="activity-detail__card activity-detail__card--sessions">
            <view class="activity-detail__section-heading">
              <text class="activity-detail__section-title">选择场次</text>
              <text v-if="activity.scheduleNote" class="activity-detail__section-note">{{ activity.scheduleNote }}</text>
            </view>
            <text v-if="activity.sessions.length === 0" class="activity-detail__text">暂无场次</text>
            <view v-else class="activity-detail__sessions">
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
                <text class="activity-detail__session-day">{{ formatActivityDay(session.startsAt) }}</text>
                <text class="activity-detail__session-time">
                  {{ session.label ? `${session.label} ` : '' }}{{ formatActivityTime(session.startsAt) }}
                </text>
                <text
                  :class="[
                    'activity-detail__session-seats',
                    `activity-detail__session-seats--${session.status.toLowerCase()}`,
                  ]"
                >
                  {{ formatActivitySessionSeats(session) }}
                </text>
              </button>
            </view>
            <text
              v-if="activity.sessions.length > 0"
              :class="['activity-detail__session-note', { 'activity-detail__session-note--ask': sessionNoteAsks }]"
              data-testid="activity-session-note"
            >
              {{ sessionNote }}
            </text>
            <button
              v-if="teamEntryVisible"
              class="activity-detail__team"
              data-testid="activity-detail-team"
              @click="runIntent({ type: 'TEAM_ENROLL', activityId: activity.activityId })"
            >
              <text class="activity-detail__team-note">学校、机构多人参加</text>
              <text class="activity-detail__team-title">团队报名</text>
              <LineIcon name="forward" :size="14" color="#ba470b" :stroke-width="2" />
            </button>
          </view>

          <view class="activity-detail__card activity-detail__card--rows">
            <view class="activity-detail__row">
              <text class="activity-detail__row-label">主办</text>
              <text class="activity-detail__row-value">{{ activity.organizer.name }}</text>
            </view>
            <view v-if="activity.coOrganizer" class="activity-detail__row">
              <text class="activity-detail__row-label">承办/交付</text>
              <text class="activity-detail__row-value">{{ activity.coOrganizer.name }}</text>
            </view>
            <button
              class="activity-detail__row activity-detail__row--location"
              data-testid="activity-detail-navigate"
              aria-label="导航到活动地点"
              @click="openNavigation"
            >
              <text class="activity-detail__row-label">活动地点</text>
              <view class="activity-detail__row-value">
                <text class="activity-detail__location-name">{{ activity.location.name }}</text>
                <text class="activity-detail__location-address">{{ activity.location.address }}</text>
              </view>
              <LineIcon name="navigate" :size="18" color="#738096" />
            </button>
            <view v-if="activity.eligibilityNote || activity.seatNote" class="activity-detail__row activity-detail__row--top">
              <text class="activity-detail__row-label">参与条件</text>
              <view class="activity-detail__row-value">
                <text v-if="activity.eligibilityNote">{{ activity.eligibilityNote }}</text>
                <text v-if="activity.seatNote" class="activity-detail__row-note">{{ activity.seatNote }}</text>
              </view>
            </view>
            <view v-if="activity.refundNote" class="activity-detail__row activity-detail__row--top">
              <text class="activity-detail__row-label">退改规则</text>
              <text class="activity-detail__row-value">{{ activity.refundNote }}</text>
            </view>
          </view>
        </view>
      </view>

      <view v-if="ACTIVITY_PREVIEW_ENABLED && primaryAction" class="activity-detail__actions">
        <button
          class="activity-detail__consult"
          data-testid="activity-detail-consult"
          @click="runIntent({ type: 'CONSULT', activityId: activity.activityId })"
        >
          <LineIcon name="chat" :size="22" color="#536178" />
          <text>咨询</text>
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
      <ShareSheet :target="shareTarget" @close="shareTarget = null" />
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board ActDetail (390 wide). */
.activity-detail {
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

.activity-detail__content {
  display: flex;
  flex: 1;
  flex-direction: column;
}

.activity-detail__skeleton {
  margin: 12px 16px 0;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
  background-size: 400% 100%;
  animation: activity-detail-skeleton 1.25s ease infinite;
}

.activity-detail__skeleton--cover {
  height: 236px;
  margin: 0;
  border-radius: 0;
}

.activity-detail__skeleton--line {
  height: 96px;
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
  min-height: 300px;
  margin: 12px 16px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 14px;
  background: #ffffff;
}

.activity-detail__state-title {
  font-size: 16px;
  font-weight: 700;
}

.activity-detail__state-copy {
  color: #627187;
  font-size: 13px;
}

.activity-detail__state-action {
  display: flex;
  min-width: 108px;
  min-height: 44px;
  margin-top: 8px;
  padding: 0 18px;
  align-items: center;
  justify-content: center;
  border-radius: 22px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
}

.activity-detail__cover {
  position: relative;
  height: 236px;
  flex: 0 0 auto;
  overflow: hidden;
  background: #eef2f8;
}

.activity-detail__cover-image {
  width: 100%;
  height: 100%;
}

.activity-detail__float {
  position: absolute;
  top: calc(var(--status-bar-height) + 4px);
  left: 8px;
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.activity-detail__float--share {
  right: 8px;
  left: auto;
}

.activity-detail__float-circle {
  display: flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
}

.activity-detail__type {
  position: absolute;
  bottom: 24px;
  left: 16px;
  padding: 2px 8px;
  border-radius: 6px;
  color: #ffffff;
  background: #6a4fe0;
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

/* The cards start over the bottom edge of the picture. */
.activity-detail__stack {
  position: relative;
  display: flex;
  margin-top: -14px;
  padding: 0 16px 12px;
  flex-direction: column;
  gap: 10px;
}

.activity-detail__card {
  display: flex;
  padding: 14px;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.activity-detail__title {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.activity-detail__subtitle {
  margin-top: 2px;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.activity-detail__price-row {
  display: flex;
  margin-top: 8px;
  align-items: baseline;
  gap: 2px;
  white-space: nowrap;
}

.activity-detail__price {
  color: #e85400;
  font-size: 26px;
  font-weight: 700;
  line-height: 34px;
}

.activity-detail__price-unit {
  color: #e85400;
  font-size: 14px;
  line-height: 20px;
}

.activity-detail__seats {
  margin-left: auto;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.activity-detail__card--sessions {
  padding-bottom: 0;
}

.activity-detail__section-heading {
  display: flex;
  margin-bottom: 10px;
  align-items: baseline;
  gap: 8px;
}

.activity-detail__section-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.activity-detail__section-note {
  min-width: 0;
  margin-left: auto;
  color: #627187;
  font-size: 12px;
}

.activity-detail__text {
  padding-bottom: 12px;
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.activity-detail__sessions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.activity-detail__session {
  display: flex;
  min-height: 76px;
  padding: 8px 9px;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  border: 1.5px solid #e5ebf3;
  border-radius: 10px;
  background: #ffffff;
  box-sizing: border-box;
}

.activity-detail__session-day {
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
  white-space: nowrap;
}

.activity-detail__session-time,
.activity-detail__session-seats {
  max-width: 100%;
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* A full or closed session is dimmed like the board's; a picked one is orange. */
.activity-detail__session--off,
.activity-detail__session:not(.activity-detail__session--selected)[data-status="FULL_WAITLIST"] {
  background: #f5f7fc;
}

.activity-detail__session--off .activity-detail__session-day,
.activity-detail__session:not(.activity-detail__session--selected)[data-status="FULL_WAITLIST"] .activity-detail__session-day,
.activity-detail__session--off .activity-detail__session-seats,
.activity-detail__session:not(.activity-detail__session--selected)[data-status="FULL_WAITLIST"] .activity-detail__session-seats {
  color: #738096;
}

.activity-detail__session--off .activity-detail__session-time,
.activity-detail__session:not(.activity-detail__session--selected)[data-status="FULL_WAITLIST"] .activity-detail__session-time {
  color: #91a0b4;
}

.activity-detail__session--selected {
  border-color: #ff6a00;
  background: #fffaf5;
}

.activity-detail__session--selected .activity-detail__session-day,
.activity-detail__session--selected .activity-detail__session-seats--full_waitlist {
  color: #ba470b;
}

.activity-detail__session-note {
  margin-top: 8px;
  padding-bottom: 12px;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.activity-detail__session-note--ask {
  color: #ba470b;
}

.activity-detail__sessions:last-child {
  padding-bottom: 12px;
}

.activity-detail__team {
  display: flex;
  width: 100%;
  min-height: 46px;
  align-items: center;
  gap: 6px;
  border-top: 1px solid #f0f1f3;
}

.activity-detail__team-note {
  min-width: 0;
  flex: 1;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.activity-detail__team-title {
  flex: 0 0 auto;
  color: #ba470b;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.activity-detail__card--rows {
  padding: 0 14px;
}

.activity-detail__row {
  display: flex;
  width: 100%;
  min-height: 46px;
  align-items: center;
  gap: 12px;
  font-size: 14px;
  line-height: 20px;
  box-sizing: border-box;
}

.activity-detail__row + .activity-detail__row {
  border-top: 1px solid #f0f1f3;
}

.activity-detail__row--location {
  min-height: 62px;
  padding: 8px 0;
}

.activity-detail__row--top {
  padding: 12px 0;
  align-items: flex-start;
}

.activity-detail__row-label {
  width: 68px;
  flex: 0 0 auto;
  color: #627187;
  white-space: nowrap;
}

.activity-detail__row-value {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.activity-detail__location-address,
.activity-detail__row-note {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.activity-detail__actions {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 12px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: 0 -4px 18px rgba(38, 59, 98, 0.06);
}

.activity-detail__consult {
  display: flex;
  width: 46px;
  height: 46px;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #536178;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

.activity-detail__primary {
  display: flex;
  min-width: 0;
  height: 46px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  font-size: 16px;
  font-weight: 500;
  white-space: nowrap;
}

.activity-detail__primary--off,
.activity-detail__primary[disabled] {
  color: #91a0b4;
  background: #eef0f3;
  box-shadow: none;
}
</style>
