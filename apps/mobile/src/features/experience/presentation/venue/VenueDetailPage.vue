<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { FAVORITE_PREVIEW_ENABLED } from '@/features/favorite/application/favorite-preview'
import FavoriteButton from '@/features/favorite/presentation/FavoriteButton.vue'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { ApiRequestError } from '@/shared/http/public-api-request'
import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'
import { buildBeijingDayOptions, type BeijingDayOption } from '@/shared/time/beijing-time'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import IconTile from '@/shared/ui/IconTile.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { EXPERIENCE_PREVIEW_ENABLED } from '../../application/experience-preview'
import { experienceApi } from '../../data/experience-api-client'
import { resolveExperiencePreviewCover } from '../../data/preview/experience-preview-covers'
import type { VenueDetail, VenueDevice, VenueProject, VenueSession } from '../../domain/experience'
import { buildVenueBookUrl, buildVenueDetailUrl, buildVenueDevicesUrl } from '../../navigation/experience-routes'
import {
  formatVenuePrice,
  formatVenueProjectMeta,
  formatVenueSessionSeats,
  formatVenueSessionTime,
  toDialNumber,
} from '../shared/experience-format'
import { openVenueNavigation } from '../shared/venue-navigation'

// Board VenueDetail's marks: the share box with an arrow, and the address pin.
const SHARE_ICON = 'M12 15V4M8 8l4-4 4 4M5 13v6h14v-6'
const PIN_ICON = 'M12 21s7-6.2 7-11.5a7 7 0 0 0-14 0C5 14.8 12 21 12 21zM12 12a2.5 2.5 0 1 0 0-5a2.5 2.5 0 1 0 0 5z'
const SAMPLE_SUFFIX = '（示例）'

type DetailState =
  | { kind: 'loading' }
  | { kind: 'ready'; venue: VenueDetail }
  | { kind: 'missing' }
  | { kind: 'error' }

type SessionsState =
  | { kind: 'loading' }
  | { kind: 'ready'; sessions: VenueSession[] }
  | { kind: 'error' }

const DAY_TAB_COUNT = 7

const state = ref<DetailState>({ kind: 'loading' })
const sessionsState = ref<SessionsState>({ kind: 'loading' })
const days = ref<BeijingDayOption[]>(buildBeijingDayOptions(new Date(), DAY_TAB_COUNT))
const selectedProjectId = ref<string | null>(null)
const selectedDate = ref(days.value[0].date)
const selectedSessionId = ref<string | null>(null)
let venueId = ''
let cityCode = ''
let detailVersion = 0
let sessionsVersion = 0

const venue = computed(() => (state.value.kind === 'ready' ? state.value.venue : null))

// 分享 (step G1, preview builds): only 复制链接 of this venue in this city.
const shareTarget = ref<ShareTarget | null>(null)
function openShare(): void {
  const current = venue.value
  if (!current) return
  shareTarget.value = {
    subject: '场馆',
    title: current.name,
    subtitle: current.openingNote ?? null,
    owner: current.operatorName,
    imageUrl: coverUrl.value ?? null,
    path: buildVenueDetailUrl(venueId, cityCode),
  }
}
const selectedProject = computed<VenueProject | null>(() =>
  venue.value?.projects.find((project) => project.projectId === selectedProjectId.value) ?? null,
)
const sessions = computed(() =>
  sessionsState.value.kind === 'ready' ? sessionsState.value.sessions : [],
)
const selectedSession = computed<VenueSession | null>(() =>
  sessions.value.find((session) => session.sessionId === selectedSessionId.value) ?? null,
)
const selectedDay = computed(() =>
  days.value.find((day) => day.date === selectedDate.value) ?? days.value[0],
)
// The detail page books one person; the confirm page changes the number (board: "今天 14:00 · 1 人").
const barLine = computed(() =>
  selectedSession.value
    ? `${selectedDay.value.title} ${formatVenueSessionTime(selectedSession.value)} · 1 人`
    : '请选择场次',
)

// Board VenueDetail: "今天", "明天", then "11/07 周六".
function dayLabel(day: BeijingDayOption, index: number): string {
  return index < 2 ? day.title : `${day.subtitle} ${day.title}`
}

// Few seats left reads orange like the board; the server's wording stays.
function isTight(session: VenueSession): boolean {
  return session.status === 'OPEN' && session.seatsLeft <= 2
}

// Sample rules of the development seed end in "（示例）"; the board greys that part.
function splitSample(content: string): { text: string; sample: boolean } {
  return content.endsWith(SAMPLE_SUFFIX)
    ? { text: content.slice(0, -SAMPLE_SUFFIX.length), sample: true }
    : { text: content, sample: false }
}

async function loadSessions(): Promise<void> {
  const project = selectedProject.value
  if (!project) return
  const requested = ++sessionsVersion
  selectedSessionId.value = null
  sessionsState.value = { kind: 'loading' }
  try {
    const loaded = await experienceApi.loadSessions({
      venueId,
      cityCode,
      projectId: project.projectId,
      date: selectedDate.value,
    })
    if (requested !== sessionsVersion) return
    sessionsState.value = { kind: 'ready', sessions: loaded }
  } catch {
    if (requested !== sessionsVersion) return
    sessionsState.value = { kind: 'error' }
  }
}

// The server's picture when it has one, else the sample picture of a preview build.
const coverUrl = computed(() =>
  venue.value
    ? venue.value.coverImage?.url ?? resolveExperiencePreviewCover(venue.value.venueId)
    : null,
)
const hasClosedSessions = computed(() =>
  sessions.value.some((session) => session.status !== 'OPEN'),
)

// A strip of up to four device pictures above the link to the device list. It is decoration:
// a failure, or devices without pictures, simply leave the strip out.
const DEVICE_STRIP_SIZE = 4
const deviceStrip = ref<Array<{ deviceId: string; name: string; url: string }>>([])

async function loadDeviceStrip(requested: number): Promise<void> {
  deviceStrip.value = []
  try {
    const page = await experienceApi.loadDevices(
      { venueId, cityCode, category: null },
      1,
      DEVICE_STRIP_SIZE,
    )
    if (requested !== detailVersion) return
    deviceStrip.value = page.items.flatMap((device: VenueDevice) => {
      const url = device.image?.url ?? resolveExperiencePreviewCover(device.deviceId)
      return url ? [{ deviceId: device.deviceId, name: device.name, url }] : []
    })
  } catch {
    // Nothing to show; the "N 件" link still opens the list.
  }
}

async function loadVenue(): Promise<void> {
  if (!venueId || !cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++detailVersion
  state.value = { kind: 'loading' }
  try {
    const loaded = await experienceApi.loadVenue(venueId, cityCode)
    if (requested !== detailVersion) return
    state.value = { kind: 'ready', venue: loaded }
    days.value = buildBeijingDayOptions(new Date(), DAY_TAB_COUNT)
    selectedDate.value = days.value[0].date
    selectedProjectId.value = loaded.projects[0]?.projectId ?? null
    void loadSessions()
    if (loaded.deviceCount > 0) void loadDeviceStrip(requested)
  } catch (error) {
    if (requested !== detailVersion) return
    const missing = error instanceof ApiRequestError
      && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function selectProject(project: VenueProject): void {
  if (selectedProjectId.value === project.projectId) return
  selectedProjectId.value = project.projectId
  void loadSessions()
}

function selectDay(day: BeijingDayOption): void {
  if (selectedDate.value === day.date) return
  selectedDate.value = day.date
  void loadSessions()
}

function selectSession(session: VenueSession): void {
  if (session.status !== 'OPEN') return
  selectedSessionId.value = session.sessionId
}

// Preview builds only (the bar is hidden otherwise): the confirm page reads the session again.
// The button stays orange like the board (R5 #1, 2026-11-20); without a session it says so.
function book(): void {
  const session = selectedSession.value
  const project = selectedProject.value
  if (!project) return
  if (!session) {
    uni.showToast({ title: '请先选择一个场次', icon: 'none' })
    return
  }
  uni.navigateTo({
    url: buildVenueBookUrl({
      venueId,
      projectId: project.projectId,
      sessionId: session.sessionId,
      date: selectedDate.value,
      cityCode,
    }),
  })
}

function openNavigation(): void {
  if (venue.value) openVenueNavigation(venue.value)
}

function callVenue(): void {
  const phone = venue.value?.phone
  if (!phone) return
  uni.makePhoneCall({ phoneNumber: toDialNumber(phone) })
}

function openDevices(): void {
  uni.navigateTo({ url: buildVenueDevicesUrl(venueId, cityCode) })
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('EXPERIENCE')
}

onLoad((query) => {
  venueId = typeof query?.venueId === 'string' ? query.venueId : ''
  cityCode = typeof query?.cityCode === 'string' ? query.cityCode : ''
  void loadVenue()
})
</script>

<template>
  <view class="venue-detail" data-testid="venue-detail">
    <!-- With a venue on screen the back button floats on its picture instead. -->
    <AppPageHeader v-if="!venue" title="场馆详情" test-id="venue-detail" @back="goBack" />

    <view
      v-if="state.kind === 'loading'"
      class="venue-detail__content"
      data-testid="venue-detail-loading"
      aria-label="正在加载"
    >
      <view class="venue-detail__skeleton venue-detail__skeleton--head" />
      <view class="venue-detail__skeleton venue-detail__skeleton--block" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="venue-detail__state" data-testid="venue-detail-missing">
      <text class="venue-detail__state-title">场馆不存在或已下线</text>
      <text class="venue-detail__state-copy">可以回到体验频道看看其他场馆</text>
      <button
        class="venue-detail__state-action"
        data-testid="venue-detail-to-channel"
        @click="openHomeChannel('EXPERIENCE')"
      >
        去体验频道
      </button>
    </view>

    <view v-else-if="state.kind === 'error'" class="venue-detail__state" data-testid="venue-detail-error">
      <text class="venue-detail__state-title">场馆加载失败，请稍后重试</text>
      <button class="venue-detail__state-action" data-testid="venue-detail-retry" @click="loadVenue">
        重新加载
      </button>
    </view>

    <template v-else-if="venue">
      <view class="venue-detail__content">
        <view class="venue-detail__cover">
          <image
            v-if="coverUrl"
            class="venue-detail__cover-image"
            :src="coverUrl"
            :alt="venue.name"
            mode="aspectFill"
          />
          <button class="venue-detail__float" data-testid="venue-detail-back" aria-label="返回" @click="goBack">
            <view class="venue-detail__float-circle">
              <LineIcon name="back" :size="20" color="#ffffff" :stroke-width="2" />
            </view>
          </button>
          <button
            v-if="SUPPORT_PREVIEW_ENABLED"
            class="venue-detail__float venue-detail__float--share"
            data-testid="venue-detail-share"
            aria-label="分享"
            @click="openShare"
          >
            <view class="venue-detail__float-circle">
              <LineIcon :d="SHARE_ICON" :size="20" color="#ffffff" :stroke-width="2" />
            </view>
          </button>
        </view>

        <view class="venue-detail__card venue-detail__card--lead">
          <text class="venue-detail__name" data-testid="venue-detail-name">{{ venue.name }}</text>
          <text v-if="venue.openingNote" class="venue-detail__opening">{{ venue.openingNote }}</text>
          <view class="venue-detail__contact">
            <LineIcon :d="PIN_ICON" :size="16" color="#738096" />
            <text class="venue-detail__address">{{ venue.address }}</text>
            <button
              class="venue-detail__icon-button"
              data-testid="venue-detail-navigate"
              aria-label="导航"
              @click="openNavigation"
            >
              <IconTile tone="blue" name="navigate" :size="40" :icon-size="22" />
            </button>
            <button
              v-if="venue.phone"
              class="venue-detail__icon-button"
              data-testid="venue-detail-call"
              :aria-label="`拨打 ${venue.phone}`"
              @click="callVenue"
            >
              <IconTile tone="violet" name="phone" :size="40" :icon-size="22" />
            </button>
          </view>
        </view>

        <view class="venue-detail__section">
          <view class="venue-detail__heading">
            <view class="venue-detail__heading-bar" />
            <text class="venue-detail__heading-title">可预约项目</text>
          </view>
          <text v-if="venue.projects.length === 0" class="venue-detail__text venue-detail__text--inset">
            暂无可预约项目
          </text>
          <scroll-view v-else class="venue-detail__projects" scroll-x :show-scrollbar="false">
            <view class="venue-detail__project-track">
              <button
                v-for="project in venue.projects"
                :key="project.projectId"
                :class="[
                  'venue-detail__project',
                  { 'venue-detail__project--selected': project.projectId === selectedProjectId },
                ]"
                :data-testid="`venue-project-${project.projectId}`"
                :aria-pressed="project.projectId === selectedProjectId"
                @click="selectProject(project)"
              >
                <text class="venue-detail__project-name">{{ project.name }}</text>
              </button>
            </view>
          </scroll-view>

          <view v-if="selectedProject" class="venue-detail__card" data-testid="venue-project-detail">
            <view class="venue-detail__project-head">
              <view class="venue-detail__project-copy">
                <text class="venue-detail__project-title">{{ selectedProject.name }}</text>
                <text class="venue-detail__project-meta">{{ formatVenueProjectMeta(selectedProject) }}</text>
              </view>
              <view class="venue-detail__project-price">
                <text class="venue-detail__price">{{ formatVenuePrice(selectedProject.priceAmountMinor) }}</text>
                <text v-if="selectedProject.priceAmountMinor > 0" class="venue-detail__price-unit"> /人</text>
              </view>
            </view>
            <view v-if="selectedProject.limitNote" class="venue-detail__limit">
              <text class="venue-detail__limit-label">项目限制</text>
              <text class="venue-detail__limit-text">{{ selectedProject.limitNote }}</text>
            </view>

            <scroll-view class="venue-detail__days" scroll-x :show-scrollbar="false">
              <view class="venue-detail__day-track">
                <button
                  v-for="(day, index) in days"
                  :key="day.date"
                  :class="['venue-detail__day', { 'venue-detail__day--active': day.date === selectedDate }]"
                  :data-testid="`venue-day-${day.date}`"
                  :aria-pressed="day.date === selectedDate"
                  @click="selectDay(day)"
                >
                  {{ dayLabel(day, index) }}
                </button>
              </view>
            </scroll-view>

            <view
              v-if="sessionsState.kind === 'loading'"
              class="venue-detail__sessions"
              data-testid="venue-sessions-loading"
              aria-label="正在加载"
            >
              <view v-for="index in 3" :key="index" class="venue-detail__skeleton venue-detail__skeleton--slot" />
            </view>
            <view v-else-if="sessionsState.kind === 'error'" class="venue-detail__inline-state" data-testid="venue-sessions-error">
              <text class="venue-detail__inline-title">场次加载失败</text>
              <button class="venue-detail__retry" data-testid="venue-sessions-retry" @click="loadSessions">重试</button>
            </view>
            <view
              v-else-if="sessions.length === 0"
              class="venue-detail__inline-state"
              data-testid="venue-sessions-empty"
            >
              <text class="venue-detail__inline-title">{{ selectedDay.title }}没有这个项目的场次</text>
              <text class="venue-detail__inline-copy">可以换一天看看</text>
            </view>
            <template v-else>
              <view class="venue-detail__sessions" data-testid="venue-sessions">
                <button
                  v-for="session in sessions"
                  :key="session.sessionId"
                  :class="[
                    'venue-detail__slot',
                    {
                      'venue-detail__slot--selected': session.sessionId === selectedSessionId,
                      'venue-detail__slot--off': session.status !== 'OPEN',
                      'venue-detail__slot--tight': isTight(session),
                    },
                  ]"
                  :data-testid="`venue-session-${session.sessionId}`"
                  :data-status="session.status"
                  :aria-pressed="session.sessionId === selectedSessionId"
                  :aria-disabled="session.status !== 'OPEN'"
                  @click="selectSession(session)"
                >
                  <text class="venue-detail__slot-time">{{ formatVenueSessionTime(session) }}</text>
                  <text class="venue-detail__slot-seats">{{ formatVenueSessionSeats(session) }}</text>
                </button>
              </view>
              <text v-if="hasClosedSessions" class="venue-detail__slot-note">
                已结束或已满的场次不能预约，可选其他场次
              </text>
            </template>
          </view>
        </view>

        <view v-if="venue.deviceCount > 0" class="venue-detail__section venue-detail__section--tight">
          <view class="venue-detail__heading">
            <view class="venue-detail__heading-bar" />
            <text class="venue-detail__heading-title">馆内展示设备</text>
            <button class="venue-detail__heading-link" data-testid="venue-detail-devices" @click="openDevices">
              <text>{{ venue.deviceCount }} 件</text>
              <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
            </button>
          </view>
          <view v-if="deviceStrip.length" class="venue-detail__device-strip">
            <button
              v-for="device in deviceStrip"
              :key="device.deviceId"
              class="venue-detail__device"
              :aria-label="device.name"
              @click="openDevices"
            >
              <image class="venue-detail__device-image" :src="device.url" :alt="device.name" mode="aspectFill" lazy-load />
            </button>
          </view>
        </view>

        <view v-if="venue.infoItems.length" class="venue-detail__card">
          <text class="venue-detail__section-title">场馆信息</text>
          <view class="venue-detail__info">
            <template v-for="item in venue.infoItems" :key="item.label">
              <text class="venue-detail__info-label">{{ item.label }}</text>
              <text class="venue-detail__info-value">{{ splitSample(item.content).text }}<text
                v-if="splitSample(item.content).sample"
                class="venue-detail__info-sample"
              >{{ SAMPLE_SUFFIX }}</text></text>
            </template>
            <text class="venue-detail__info-label">运营主体</text>
            <text class="venue-detail__info-value">{{ venue.operatorName }}</text>
          </view>
        </view>
      </view>

      <view v-if="EXPERIENCE_PREVIEW_ENABLED && selectedProject" class="venue-detail__actions">
        <!-- 收藏 on the left of the bar, drawn like the activity page's (plan 08 S5-6; board VenueDetail has none). -->
        <FavoriteButton
          v-if="FAVORITE_PREVIEW_ENABLED"
          type="VENUE"
          :target-id="venue.venueId"
          :city-code="cityCode"
          :return-url="buildVenueDetailUrl(venue.venueId, cityCode)"
        />
        <view class="venue-detail__bar-copy">
          <text class="venue-detail__bar-line" data-testid="venue-detail-bar-line">{{ barLine }}</text>
          <text class="venue-detail__bar-price">
            {{ formatVenuePrice(selectedProject.priceAmountMinor) }}
          </text>
        </view>
        <button
          class="venue-detail__primary"
          data-testid="venue-detail-book"
          :data-ready="Boolean(selectedSession)"
          @click="book"
        >
          预约到馆
        </button>
      </view>
      <ShareSheet :target="shareTarget" @close="shareTarget = null" />
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board VenueDetail (390 wide). */
.venue-detail {
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

.venue-detail__content {
  display: flex;
  padding-bottom: 16px;
  flex: 1;
  flex-direction: column;
  gap: 12px;
}

.venue-detail__skeleton {
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
  background-size: 400% 100%;
  animation: venue-detail-skeleton 1.25s ease infinite;
}

.venue-detail__skeleton--head {
  height: 230px;
  border-radius: 0;
}

.venue-detail__skeleton--block {
  height: 220px;
  margin: 0 16px;
}

.venue-detail__skeleton--slot {
  height: 56px;
  border-radius: 10px;
}

@keyframes venue-detail-skeleton {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}

.venue-detail__state {
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

.venue-detail__state-title {
  font-size: 16px;
  font-weight: 700;
}

.venue-detail__state-copy {
  color: #627187;
  font-size: 13px;
}

.venue-detail__state-action {
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

.venue-detail__cover {
  position: relative;
  height: 230px;
  flex: 0 0 auto;
  overflow: hidden;
  background: #eef0f3;
}

.venue-detail__cover-image {
  width: 100%;
  height: 100%;
}

.venue-detail__float {
  position: absolute;
  top: calc(var(--status-bar-height) + 4px);
  left: 8px;
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.venue-detail__float--share {
  right: 8px;
  left: auto;
}

.venue-detail__float-circle {
  display: flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
}

.venue-detail__card {
  display: flex;
  margin: 0 16px;
  padding: 14px;
  flex-direction: column;
  gap: 12px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

/* The first card overlaps the picture. */
.venue-detail__card--lead {
  position: relative;
  margin-top: -48px;
  padding: 14px 14px 4px;
  gap: 0;
}

.venue-detail__name {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.venue-detail__opening {
  margin-top: 6px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.venue-detail__contact {
  display: flex;
  margin-top: 10px;
  align-items: center;
  gap: 4px;
  border-top: 1px solid #f0f1f3;
}

.venue-detail__address {
  min-width: 0;
  flex: 1;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.venue-detail__icon-button {
  display: flex;
  width: 46px;
  height: 52px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
}

.venue-detail__section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.venue-detail__section--tight {
  gap: 6px;
}

.venue-detail__heading {
  display: flex;
  min-height: 24px;
  padding: 0 16px;
  align-items: center;
  gap: 8px;
}

.venue-detail__heading-bar {
  width: 4px;
  height: 16px;
  flex: 0 0 auto;
  border-radius: 2px;
  background: #ff6a00;
}

.venue-detail__heading-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.venue-detail__heading-link {
  display: flex;
  min-height: 44px;
  margin-left: auto;
  align-items: center;
  gap: 2px;
  color: #627187;
  font-size: 13px;
}

.venue-detail__projects {
  width: 100%;
  white-space: nowrap;
}

.venue-detail__project-track {
  display: inline-flex;
  min-width: 100%;
  padding: 0 16px;
  gap: 8px;
  box-sizing: border-box;
}

/* The pill is 36 high on the board; the button is 44 so the tap target meets the minimum. */
.venue-detail__project {
  position: relative;
  display: inline-flex;
  height: 44px;
  padding: 0 14px;
  flex: 1 0 auto;
  align-items: center;
  justify-content: center;
  color: #202c42;
  font-size: 14px;
  white-space: nowrap;
  z-index: 0;
}

.venue-detail__project::before {
  position: absolute;
  top: 4px;
  right: 0;
  bottom: 4px;
  left: 0;
  border: 1.5px solid #e5ebf3;
  border-radius: 999px;
  background: #ffffff;
  content: '';
  z-index: -1;
}

.venue-detail__project--selected {
  color: #ba470b;
  font-weight: 500;
}

.venue-detail__project--selected::before {
  border-color: #ff6a00;
  background: #fff0e3;
}

.venue-detail__project-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.venue-detail__project-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.venue-detail__project-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.venue-detail__project-meta {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.venue-detail__project-price {
  flex: 0 0 auto;
  white-space: nowrap;
}

.venue-detail__price {
  color: #e85400;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.venue-detail__price-unit {
  color: #627187;
  font-size: 12px;
}

.venue-detail__limit {
  display: flex;
  margin-top: -4px;
  padding: 8px 10px;
  align-items: flex-start;
  gap: 8px;
  border-radius: 8px;
  color: #536178;
  background: #f5f7fc;
  font-size: 12px;
  line-height: 17px;
}

.venue-detail__limit-label {
  flex: 0 0 auto;
  font-weight: 500;
}

.venue-detail__limit-text {
  min-width: 0;
}

.venue-detail__days {
  width: 100%;
  white-space: nowrap;
}

.venue-detail__day-track {
  display: inline-flex;
  gap: 6px;
}

.venue-detail__day {
  display: inline-flex;
  height: 36px;
  padding: 0 10px;
  flex: 0 0 auto;
  align-items: center;
  border-radius: 8px;
  color: #202c42;
  background: #eef2f8;
  font-size: 13px;
  white-space: nowrap;
}

/* Board VenueDetail: the picked day is solid orange with white text. */
.venue-detail__day--active {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.venue-detail__sessions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.venue-detail__slot {
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

.venue-detail__slot-time {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.venue-detail__slot-seats {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.venue-detail__slot--selected {
  border-color: #ff6a00;
  background: #fffaf5;
}

.venue-detail__slot--selected .venue-detail__slot-time,
.venue-detail__slot--selected .venue-detail__slot-seats,
.venue-detail__slot--tight .venue-detail__slot-seats {
  color: #ba470b;
}

.venue-detail__slot--off {
  background: #f5f7fc;
}

.venue-detail__slot--off .venue-detail__slot-time {
  color: #91a0b4;
}

.venue-detail__slot--off .venue-detail__slot-seats {
  color: #738096;
}

.venue-detail__slot-note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.venue-detail__inline-state {
  display: flex;
  padding: 20px 12px;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  border-radius: 10px;
  background: #f5f7fc;
  text-align: center;
}

.venue-detail__inline-title {
  color: #536178;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.venue-detail__inline-copy {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.venue-detail__retry {
  display: flex;
  height: 44px;
  padding: 0 22px;
  align-items: center;
  color: #ba470b;
  font-size: 14px;
  font-weight: 500;
}

.venue-detail__text {
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.venue-detail__text--inset {
  padding: 0 16px;
}

.venue-detail__device-strip {
  display: grid;
  padding: 0 16px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.venue-detail__device {
  display: block;
  height: 68px;
  overflow: hidden;
  border-radius: 10px;
  background: #eef0f3;
}

.venue-detail__device-image {
  width: 100%;
  height: 100%;
}

.venue-detail__section-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.venue-detail__info {
  display: grid;
  margin-top: -2px;
  /* 70px like the board; a longer label widens the column instead of running into the value. */
  grid-template-columns: minmax(70px, auto) minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
  line-height: 19px;
}

.venue-detail__info-label {
  color: #627187;
  white-space: nowrap;
}

.venue-detail__info-sample {
  color: #91a0b4;
}

.venue-detail__actions {
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

.venue-detail__bar-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.venue-detail__bar-line {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.venue-detail__bar-price {
  color: #e85400;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.venue-detail__primary {
  display: flex;
  width: 150px;
  height: 46px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  font-size: 16px;
  font-weight: 500;
}


</style>
