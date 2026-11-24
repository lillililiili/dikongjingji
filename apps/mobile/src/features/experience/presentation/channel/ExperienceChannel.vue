<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import { PLAY_GAMES_PATH } from '@/app-shell/navigation/home-channels'
import { robotArtwork } from '@/app-shell/navigation/home-artwork'
import { COURSE_PREVIEW_ENABLED } from '@/features/course/application/course-preview'
import { buildCourseTrialUrl } from '@/features/course/navigation/course-routes'
import {
  createPagedListLoader,
  emptyPagedList,
  type PagedListState,
} from '@/shared/list/paged-list-loader'

import { resolveExperienceIntent } from '../../application/experience-intent'
import { EXPERIENCE_PREVIEW_ENABLED } from '../../application/experience-preview'
import { experienceApi } from '../../data/experience-api-client'
import {
  EXPERIENCE_PAGE_SIZE,
  type ExperienceItem,
  type ExperienceType,
  type VenueCard,
} from '../../domain/experience'
import { resolveExperiencePreviewCover } from '../../data/preview/experience-preview-covers'
import { buildExperienceRecordsUrl, buildVenueDetailUrl } from '../../navigation/experience-routes'
import {
  EXPERIENCE_TYPE_LABELS,
  EXPERIENCE_TYPE_TAGS,
} from '../shared/experience-format'

type Mode = 'ONLINE' | 'OFFLINE'

const props = defineProps<{
  // False while another home channel is showing; the component stays mounted to keep its lists.
  active: boolean
  cityCode: string
}>()

const TYPE_TABS: readonly { code: ExperienceType | null; label: string }[] = [
  { code: null, label: '全部' },
  { code: 'SIMULATION', label: EXPERIENCE_TYPE_LABELS.SIMULATION },
  { code: 'RECORDED_DEMO', label: EXPERIENCE_TYPE_LABELS.RECORDED_DEMO },
  { code: 'SOFTWARE_TRIAL', label: EXPERIENCE_TYPE_LABELS.SOFTWARE_TRIAL },
  { code: 'COURSE_TRIAL', label: EXPERIENCE_TYPE_LABELS.COURSE_TRIAL },
]

const mode = ref<Mode>('ONLINE')
const selectedType = ref<ExperienceType | null>(null)
const items = ref<PagedListState<ExperienceItem>>(emptyPagedList())
const venues = ref<PagedListState<VenueCard>>(emptyPagedList())

const itemLoader = createPagedListLoader<ExperienceType | null, ExperienceItem>({
  loadPage: (type, page, size) => experienceApi.loadExperienceItems(type, page, size),
  getState: () => items.value,
  setState: (state) => { items.value = state },
  keyOf: (item) => item.experienceId,
  pageSize: EXPERIENCE_PAGE_SIZE,
})
const venueLoader = createPagedListLoader<string, VenueCard>({
  loadPage: (cityCode, page, size) => experienceApi.loadVenues(cityCode, page, size),
  getState: () => venues.value,
  setState: (state) => { venues.value = state },
  keyOf: (venue) => venue.venueId,
  pageSize: EXPERIENCE_PAGE_SIZE,
})

function reloadCurrent(): void {
  if (mode.value === 'ONLINE') void itemLoader.reload(selectedType.value)
  // The online list ends with the city's first venue (ExpCenter board), so both read the venues.
  void venueLoader.reload(props.cityCode)
}

// The strip under the online list: the first venue of the city and how many sessions it has today.
const featuredVenue = computed(() =>
  venues.value.status === 'ready' ? venues.value.items[0] ?? null : null,
)

function selectMode(next: Mode): void {
  if (mode.value === next) return
  mode.value = next
  reloadCurrent()
}

function selectType(type: ExperienceType | null): void {
  if (selectedType.value === type) return
  selectedType.value = type
  void itemLoader.reload(type)
}

function openExperience(item: ExperienceItem): void {
  // 课程试听 (S5-5): the trial lessons of the course this entry belongs to.
  if (item.type === 'COURSE_TRIAL' && COURSE_PREVIEW_ENABLED) {
    uni.navigateTo({ url: buildCourseTrialUrl({ experienceItemId: item.experienceId }) })
    return
  }
  const resolution = resolveExperienceIntent(
    { type: 'OPEN_EXPERIENCE', experienceId: item.experienceId, experienceType: item.type },
    EXPERIENCE_PREVIEW_ENABLED,
  )
  if (resolution.action === 'NAVIGATE') uni.navigateTo({ url: resolution.url })
  else if (resolution.action === 'SHOW_NOTICE') uni.showToast({ title: resolution.message, icon: 'none' })
}

// 我的体验记录 › (board ExpCenter): the page asks for a login itself; a guest sees the results kept here.
function openRecords(): void {
  uni.navigateTo({ url: buildExperienceRecordsUrl() })
}

function openVenue(venue: VenueCard): void {
  uni.navigateTo({ url: buildVenueDetailUrl(venue.venueId, props.cityCode) })
}

// The server's picture when it has one, else the sample picture of a preview build.
function coverOf(id: string, coverImage: { url: string } | null): string | null {
  return coverImage?.url ?? resolveExperiencePreviewCover(id)
}

// The games are built into the App, so their picture ships with it (the same one the games page uses).
const GAMES_PICTURE = robotArtwork

function openGames(): void {
  uni.navigateTo({ url: PLAY_GAMES_PATH })
}

// Seats change while the user is elsewhere, so every return to the channel reads the list again.
watch(() => props.active, (active) => {
  if (active) reloadCurrent()
}, { immediate: true })

watch(() => props.cityCode, () => {
  if (props.active) void venueLoader.reload(props.cityCode)
})

// The home page owns the scroll and forwards its reach-bottom event (see ActivityChannel).
defineExpose({
  loadMore: (): Promise<void> =>
    mode.value === 'ONLINE' ? itemLoader.loadMore() : venueLoader.loadMore(),
})
</script>

<template>
  <view class="experience-channel" data-testid="experience-channel">
    <view class="experience-channel__modes">
      <button
        :class="['experience-channel__mode', { 'experience-channel__mode--active': mode === 'ONLINE' }]"
        data-testid="experience-mode-online"
        :aria-pressed="mode === 'ONLINE'"
        @click="selectMode('ONLINE')"
      >
        <text class="experience-channel__mode-title">线上体验</text>
        <text class="experience-channel__mode-note">不出门</text>
      </button>
      <button
        :class="['experience-channel__mode', { 'experience-channel__mode--active': mode === 'OFFLINE' }]"
        data-testid="experience-mode-offline"
        :aria-pressed="mode === 'OFFLINE'"
        @click="selectMode('OFFLINE')"
      >
        <text class="experience-channel__mode-title">线下体验</text>
        <text class="experience-channel__mode-note">到场馆</text>
      </button>
    </view>

    <button class="experience-channel__games" data-testid="experience-games" @click="openGames">
      <image
        v-if="GAMES_PICTURE"
        class="experience-channel__games-picture"
        :src="GAMES_PICTURE"
        alt="趣玩机器人"
        mode="aspectFill"
      />
      <view class="experience-channel__games-copy">
        <text class="experience-channel__games-title">趣玩挑战</text>
        <text class="experience-channel__games-note">机器人迷宫 · 无人机寻星</text>
      </view>
      <text class="experience-channel__games-action">去闯关 ›</text>
    </button>

    <template v-if="mode === 'ONLINE'">
      <scroll-view class="experience-channel__types" scroll-x :show-scrollbar="false">
        <view class="experience-channel__type-track">
          <button
            v-for="tab in TYPE_TABS"
            :key="tab.label"
            :class="[
              'experience-channel__type',
              { 'experience-channel__type--active': tab.code === selectedType },
            ]"
            :data-testid="`experience-type-${(tab.code ?? 'all').toLowerCase()}`"
            :aria-pressed="tab.code === selectedType"
            @click="selectType(tab.code)"
          >
            {{ tab.label }}
          </button>
        </view>
      </scroll-view>

      <view class="experience-channel__promise-row">
        <text class="experience-channel__promise">体验结束不会自动下单</text>
        <text
          v-if="EXPERIENCE_PREVIEW_ENABLED"
          class="experience-channel__records"
          data-testid="experience-records-link"
          @click="openRecords"
        >我的体验记录 ›</text>
      </view>

      <view
        v-if="items.status === 'loading' || items.status === 'idle'"
        class="experience-channel__list"
        data-testid="experience-list-loading"
        aria-label="正在加载"
      >
        <view v-for="index in 3" :key="index" class="experience-channel__skeleton" />
      </view>
      <view v-else-if="items.status === 'error'" class="experience-channel__state" data-testid="experience-list-error">
        <text class="experience-channel__state-title">体验项目加载失败，请稍后重试</text>
        <button class="experience-channel__state-action" data-testid="experience-list-retry" @click="reloadCurrent">
          重新加载
        </button>
      </view>
      <view v-else-if="items.items.length === 0" class="experience-channel__state" data-testid="experience-list-empty">
        <text class="experience-channel__state-title">暂无该类体验</text>
      </view>
      <view v-else class="experience-channel__list">
        <button
          v-for="item in items.items"
          :key="item.experienceId"
          class="experience-card"
          :data-testid="`experience-item-${item.experienceId}`"
          :data-type="item.type"
          @click="openExperience(item)"
        >
          <view :class="['experience-card__cover', `experience-card__cover--${item.type.toLowerCase()}`]">
            <image
              v-if="coverOf(item.experienceId, item.coverImage)"
              class="experience-card__image"
              :src="coverOf(item.experienceId, item.coverImage) ?? ''"
              :alt="item.title"
              mode="aspectFill"
              lazy-load
            />
            <text :class="['experience-card__tag', `experience-card__tag--${item.type.toLowerCase()}`]">
              {{ EXPERIENCE_TYPE_TAGS[item.type] }}
            </text>
          </view>
          <view class="experience-card__body">
            <text class="experience-card__title">{{ item.title }}</text>
            <text v-if="item.summary" class="experience-card__summary">{{ item.summary }}</text>
          </view>
        </button>
        <text v-if="items.loadingMore" class="experience-channel__more">正在加载…</text>
      </view>

      <button
        v-if="featuredVenue"
        class="experience-channel__venue-strip"
        data-testid="experience-venue-strip"
        @click="openVenue(featuredVenue)"
      >
        <image
          v-if="coverOf(featuredVenue.venueId, featuredVenue.coverImage)"
          class="experience-channel__venue-strip-picture"
          :src="coverOf(featuredVenue.venueId, featuredVenue.coverImage) ?? ''"
          :alt="featuredVenue.name"
          mode="aspectFill"
        />
        <text class="experience-channel__venue-strip-copy">
          <text class="experience-channel__venue-strip-name">{{ featuredVenue.name }}</text>
          <text v-if="featuredVenue.openSessionsToday > 0" class="experience-channel__venue-strip-seats">
            · 今日 {{ featuredVenue.openSessionsToday }} 场可约
          </text>
        </text>
        <UniIcons type="right" size="14" color="#91a0b4" />
      </button>
    </template>

    <template v-else>
      <view
        v-if="venues.status === 'loading' || venues.status === 'idle'"
        class="experience-channel__list"
        data-testid="venue-list-loading"
        aria-label="正在加载"
      >
        <view v-for="index in 2" :key="index" class="experience-channel__skeleton" />
      </view>
      <view v-else-if="venues.status === 'error'" class="experience-channel__state" data-testid="venue-list-error">
        <text class="experience-channel__state-title">场馆加载失败，请稍后重试</text>
        <button class="experience-channel__state-action" data-testid="venue-list-retry" @click="reloadCurrent">
          重新加载
        </button>
      </view>
      <view v-else-if="venues.items.length === 0" class="experience-channel__state" data-testid="venue-list-empty">
        <text class="experience-channel__state-title">当前城市暂无体验场馆</text>
        <text class="experience-channel__state-copy">可以先看看线上体验</text>
      </view>
      <view v-else class="experience-channel__list">
        <button
          v-for="venue in venues.items"
          :key="venue.venueId"
          class="venue-card"
          :data-testid="`venue-card-${venue.venueId}`"
          @click="openVenue(venue)"
        >
          <view class="venue-card__cover">
            <image
              v-if="coverOf(venue.venueId, venue.coverImage)"
              class="venue-card__image"
              :src="coverOf(venue.venueId, venue.coverImage) ?? ''"
              :alt="venue.name"
              mode="aspectFill"
              lazy-load
            />
            <UniIcons v-else type="home" size="26" color="#159a70" />
          </view>
          <view class="venue-card__body">
            <text class="venue-card__name">{{ venue.name }}</text>
            <view v-if="venue.openingNote || venue.openSessionsToday > 0" class="venue-card__note-row">
              <text v-if="venue.openingNote" class="venue-card__note">{{ venue.openingNote }}</text>
              <text v-if="venue.openSessionsToday > 0" class="venue-card__seats">
                {{ venue.openingNote ? '·' : '' }} 今日 {{ venue.openSessionsToday }} 场可约
              </text>
            </view>
          </view>
          <UniIcons type="right" size="14" color="#91a0b4" />
        </button>
        <text v-if="venues.loadingMore" class="experience-channel__more">正在加载…</text>
      </view>
    </template>

  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board ExpCenter (390 wide). */
.experience-channel {
  display: flex;
  padding: 8px var(--app-page-horizontal-gutter) 0;
  flex-direction: column;
  gap: 10px;
  color: #202c42;
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

.experience-channel__modes {
  display: flex;
  gap: 8px;
}

/* 40 high on the board; 44 here so the tap target meets the minimum. */
.experience-channel__mode {
  display: flex;
  height: 44px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border: 1px solid #e5ebf3;
  border-radius: 10px;
  color: #536178;
  background: #ffffff;
  font-size: 15px;
  white-space: nowrap;
  box-sizing: border-box;
}

.experience-channel__mode-note::before {
  content: ' · ';
  white-space: pre;
}

.experience-channel__mode--active {
  border-color: #ffd8b8;
  color: #ba470b;
  background: #fff0e3;
  font-weight: 700;
}

.experience-channel__games {
  display: flex;
  padding: 10px 12px;
  align-items: center;
  gap: 10px;
  border: 1px solid #ffffff;
  border-radius: 16px;
  color: #28364b;
  background: linear-gradient(110deg, #eee5ff, #e3f5ff);
  box-sizing: border-box;
}

.experience-channel__games-picture {
  width: 58px;
  height: 58px;
  flex: 0 0 auto;
  border-radius: 15px;
}

.experience-channel__games-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 5px;
}

.experience-channel__games-title {
  font-size: 17px;
  font-weight: 700;
}

.experience-channel__games-note {
  color: #60708d;
  font-size: 11px;
}

.experience-channel__games-action {
  flex: 0 0 auto;
  color: #7150b6;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.experience-channel__types {
  width: 100%;
  white-space: nowrap;
}

.experience-channel__type-track {
  display: inline-flex;
  gap: 6px;
}

/* The pill is 30 high; the ::before area is the pill, the button is the 44 tap target. */
.experience-channel__type {
  position: relative;
  display: inline-flex;
  height: 44px;
  padding: 0 9px;
  flex: 0 0 auto;
  align-items: center;
  color: #202c42;
  font-size: 13px;
  white-space: nowrap;
  z-index: 0;
}

.experience-channel__type::before {
  position: absolute;
  top: 7px;
  right: 0;
  bottom: 7px;
  left: 0;
  border-radius: 999px;
  background: #ffffff;
  content: '';
  z-index: -1;
}

.experience-channel__type--active {
  color: #ffffff;
}

.experience-channel__type--active::before {
  background: #202c42;
}

/* Board ExpCenter: one 20-high row under the type pills, 我的体验记录 › on the right. */
.experience-channel__promise-row {
  display: flex;
  height: 20px;
  margin-top: -6px;
  align-items: center;
}

.experience-channel__promise {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

/* 13px text on a 44-high tap area that does not push the row taller. */
.experience-channel__records {
  margin: -12px 0 -12px auto;
  padding: 12px 0 12px 12px;
  color: #ba470b;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.experience-channel__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.experience-channel__skeleton {
  height: 120px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
  background-size: 400% 100%;
  animation: experience-skeleton 1.25s ease infinite;
}

@keyframes experience-skeleton {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}

.experience-channel__state {
  display: flex;
  min-height: 120px;
  padding: 16px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 14px;
  background: #ffffff;
  box-sizing: border-box;
}

.experience-channel__state-title {
  color: #738096;
  font-size: 14px;
  line-height: 20px;
}

.experience-channel__state-copy {
  color: #738096;
  font-size: 12px;
}

.experience-channel__state-action {
  display: flex;
  height: 44px;
  margin-top: 4px;
  padding: 0 22px;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 14px;
  font-weight: 500;
  box-sizing: border-box;
}

.experience-channel__more {
  padding: 8px 0;
  color: #738096;
  font-size: 12px;
  text-align: center;
}

.experience-card {
  display: flex;
  width: 100%;
  flex-direction: column;
  overflow: hidden;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.experience-card__cover {
  position: relative;
  width: 100%;
  height: 132px;
  flex: 0 0 auto;
  background: #eef0f3;
}

.experience-card__cover--simulation,
.experience-card__cover--course_trial {
  background: #e2f6ed;
}

.experience-card__cover--recorded_demo,
.experience-card__cover--software_trial {
  background: #e4efff;
}

.experience-card__image {
  width: 100%;
  height: 100%;
}

.experience-card__tag {
  position: absolute;
  top: 10px;
  left: 10px;
  padding: 3px 8px;
  border-radius: 6px;
  color: #11804a;
  background: #ffffff;
  font-size: 12px;
  font-weight: 500;
}

.experience-card__tag--recorded_demo,
.experience-card__tag--software_trial {
  color: #1d63c4;
}

.experience-card__body {
  display: flex;
  padding: 8px 12px 10px;
  flex-direction: column;
  gap: 3px;
}

.experience-card__title {
  font-size: 15px;
  font-weight: 700;
  line-height: 20px;
}

.experience-card__summary {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.venue-card {
  display: flex;
  width: 100%;
  padding: 10px 12px 10px 10px;
  align-items: center;
  gap: 10px;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
  box-sizing: border-box;
}

.venue-card__cover {
  display: flex;
  width: 56px;
  height: 56px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 8px;
  background: #e2f6ed;
}

.venue-card__image {
  width: 100%;
  height: 100%;
}

.venue-card__body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.venue-card__name {
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.venue-card__note {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.venue-card__note-row {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 4px;
}

.venue-card__note {
  min-width: 0;
  flex: 0 1 auto;
}

.venue-card__seats {
  flex: 0 0 auto;
  color: #11804a;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

/* ExpCenter board: the venue strip under the online list. */
.experience-channel__venue-strip {
  display: flex;
  width: 100%;
  padding: 8px 12px 8px 8px;
  align-items: center;
  gap: 10px;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
  box-sizing: border-box;
}

.experience-channel__venue-strip-picture {
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  border-radius: 8px;
}

.experience-channel__venue-strip-copy {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.experience-channel__venue-strip-name {
  font-weight: 500;
}

.experience-channel__venue-strip-seats {
  color: #11804a;
}
</style>
