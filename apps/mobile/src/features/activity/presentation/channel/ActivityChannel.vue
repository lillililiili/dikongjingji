<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import {
  EMPTY_ACTIVITY_LIST,
  createActivityListLoader,
  type ActivityListState,
} from '../../application/activity-list-loader'
import { activityApi } from '../../data/activity-api-client'
import type { ActivityCard, ActivityType } from '../../domain/activity'
import { buildActivityDetailUrl } from '../../navigation/activity-routes'
import {
  ACTIVITY_TYPE_LABELS,
  buildActivityDateOptions,
  formatActivityAgeRange,
  formatActivityCardSchedule,
  formatActivityCardStatus,
  formatActivityPrice,
  isPerPersonPrice,
  lastActivityStripDate,
  toBeijingDate,
  type ActivityDateOption,
} from '../shared/activity-format'

const props = defineProps<{
  // False while another home channel is showing; the component stays mounted to keep its list.
  active: boolean
  cityCode: string
}>()

const TYPE_TABS: readonly { code: ActivityType | null; label: string }[] = [
  { code: null, label: '全部' },
  { code: 'CONTEST', label: ACTIVITY_TYPE_LABELS.CONTEST },
  { code: 'TRAINING', label: ACTIVITY_TYPE_LABELS.TRAINING },
  { code: 'STUDY_TOUR', label: ACTIVITY_TYPE_LABELS.STUDY_TOUR },
  { code: 'OPEN_DAY', label: ACTIVITY_TYPE_LABELS.OPEN_DAY },
]

const selectedType = ref<ActivityType | null>(null)
const selectedDate = ref<string | null>(null)
const enrollingOnly = ref(false)
const freeOnly = ref(false)
const list = ref<ActivityListState>(EMPTY_ACTIVITY_LIST)
const dateOptions = ref<ActivityDateOption[]>(buildActivityDateOptions(new Date(), []))
let datesRevision = 0

const loader = createActivityListLoader({
  loadPage: (query, page, size) => activityApi.loadPage(query, page, size),
  getState: () => list.value,
  setState: (state) => { list.value = state },
})

const hasFilters = computed(() =>
  selectedType.value !== null
  || selectedDate.value !== null
  || enrollingOnly.value
  || freeOnly.value,
)

function reloadList(): Promise<void> {
  return loader.reload({
    cityCode: props.cityCode,
    type: selectedType.value,
    date: selectedDate.value,
    enrollingOnly: enrollingOnly.value,
    freeOnly: freeOnly.value,
  })
}

// The dots are a hint only: if this request fails the strip still works, just without dots.
async function reloadDates(): Promise<void> {
  const requested = ++datesRevision
  const now = new Date()
  dateOptions.value = buildActivityDateOptions(now, [])
  try {
    const dates = await activityApi.loadSessionDates({
      cityCode: props.cityCode,
      type: selectedType.value,
      from: toBeijingDate(now),
      to: lastActivityStripDate(now),
    })
    if (requested !== datesRevision) return
    dateOptions.value = buildActivityDateOptions(now, dates)
  } catch {
    // Leave the strip without dots.
  }
}

function selectType(type: ActivityType | null): void {
  if (selectedType.value === type) return
  selectedType.value = type
  void reloadDates()
  void reloadList()
}

function selectDate(date: string | null): void {
  if (selectedDate.value === date) return
  selectedDate.value = date
  void reloadList()
}

function toggleEnrolling(): void {
  enrollingOnly.value = !enrollingOnly.value
  void reloadList()
}

function toggleFree(): void {
  freeOnly.value = !freeOnly.value
  void reloadList()
}

function clearFilters(): void {
  selectedType.value = null
  selectedDate.value = null
  enrollingOnly.value = false
  freeOnly.value = false
  void reloadDates()
  void reloadList()
}

function coverOf(card: ActivityCard): string | null {
  return card.coverImage?.url ?? null
}

function openDetail(card: ActivityCard): void {
  uni.navigateTo({ url: buildActivityDetailUrl(card.activityId, props.cityCode) })
}

function refresh(): void {
  void reloadDates()
  void reloadList()
}

// Seats change while the user is elsewhere, so every return to the channel reads the list again.
watch(() => props.active, (active) => {
  if (active) refresh()
}, { immediate: true })

watch(() => props.cityCode, () => {
  selectedDate.value = null
  if (props.active) refresh()
})

// The home page owns the scroll: this channel is mounted after the page is ready, and uni-app only
// delivers onReachBottom to hooks that existed when the page was created. The page calls this instead.
defineExpose({
  loadMore: (): Promise<void> => loader.loadMore(),
})
</script>

<template>
  <view class="activity-channel" data-testid="activity-channel">
    <scroll-view class="activity-channel__types" scroll-x :show-scrollbar="false">
      <view class="activity-channel__type-track">
        <button
          v-for="tab in TYPE_TABS"
          :key="tab.label"
          :class="[
            'activity-channel__type',
            { 'activity-channel__type--active': tab.code === selectedType },
          ]"
          :data-testid="`activity-type-${(tab.code ?? 'all').toLowerCase()}`"
          :aria-pressed="tab.code === selectedType"
          @click="selectType(tab.code)"
        >
          {{ tab.label }}
        </button>
      </view>
    </scroll-view>

    <scroll-view class="activity-channel__dates" scroll-x :show-scrollbar="false">
      <view class="activity-channel__date-track">
        <button
          :class="[
            'activity-channel__date',
            'activity-channel__date--all',
            { 'activity-channel__date--active': selectedDate === null },
          ]"
          data-testid="activity-date-all"
          :aria-pressed="selectedDate === null"
          @click="selectDate(null)"
        >
          <text class="activity-channel__date-title">全部</text>
          <text class="activity-channel__date-subtitle">日期</text>
        </button>
        <button
          v-for="option in dateOptions"
          :key="option.date"
          :class="[
            'activity-channel__date',
            {
              'activity-channel__date--active': option.date === selectedDate,
              'activity-channel__date--today': option.title === '今天',
            },
          ]"
          :data-testid="`activity-date-${option.date}`"
          :data-has-sessions="option.hasSessions"
          :aria-pressed="option.date === selectedDate"
          @click="selectDate(option.date)"
        >
          <text class="activity-channel__date-title">{{ option.title }}</text>
          <text class="activity-channel__date-subtitle">{{ option.subtitle }}</text>
          <view
            :class="[
              'activity-channel__date-dot',
              { 'activity-channel__date-dot--on': option.hasSessions },
            ]"
          />
        </button>
      </view>
    </scroll-view>

    <view class="activity-channel__chips">
      <button
        :class="['activity-channel__chip', { 'activity-channel__chip--active': enrollingOnly }]"
        data-testid="activity-filter-enrolling"
        :aria-pressed="enrollingOnly"
        @click="toggleEnrolling"
      >
        报名中
      </button>
      <button
        :class="['activity-channel__chip', { 'activity-channel__chip--active': freeOnly }]"
        data-testid="activity-filter-free"
        :aria-pressed="freeOnly"
        @click="toggleFree"
      >
        免费
      </button>
    </view>

    <view
      v-if="list.status === 'loading' || list.status === 'idle'"
      class="activity-channel__skeletons"
      data-testid="activity-list-loading"
      aria-label="正在加载"
    >
      <view v-for="index in 3" :key="index" class="activity-channel__skeleton" />
    </view>

    <view
      v-else-if="list.status === 'error'"
      class="activity-channel__state"
      data-testid="activity-list-error"
    >
      <text class="activity-channel__state-title">活动加载失败，请稍后重试</text>
      <button class="activity-channel__state-action" data-testid="activity-list-retry" @click="refresh">
        重新加载
      </button>
    </view>

    <view
      v-else-if="list.items.length === 0"
      class="activity-channel__state"
      data-testid="activity-list-empty"
    >
      <text class="activity-channel__state-title">
        {{ hasFilters ? '没有符合条件的活动' : '当前城市暂无活动' }}
      </text>
      <text v-if="!hasFilters" class="activity-channel__state-copy">有新活动时会在这里展示</text>
      <button
        v-if="hasFilters"
        class="activity-channel__state-action"
        data-testid="activity-clear-filters"
        @click="clearFilters"
      >
        清除筛选
      </button>
    </view>

    <view v-else class="activity-channel__list">
      <button
        v-for="card in list.items"
        :key="card.activityId"
        class="activity-card"
        :data-testid="`activity-card-${card.activityId}`"
        :data-status="card.enrollmentStatus"
        @click="openDetail(card)"
      >
        <view :class="['activity-card__cover', `activity-card__cover--${card.type.toLowerCase()}`]">
          <image
            v-if="coverOf(card)"
            class="activity-card__image"
            :src="coverOf(card) ?? ''"
            :alt="card.title"
            mode="aspectFill"
            lazy-load
          />
          <text class="activity-card__type">{{ ACTIVITY_TYPE_LABELS[card.type] }}</text>
        </view>
        <view class="activity-card__body">
          <text class="activity-card__title">{{ card.title }}</text>
          <text class="activity-card__schedule">{{ formatActivityCardSchedule(card) }}</text>
          <view class="activity-card__footer">
            <view class="activity-card__price">
              <text class="activity-card__price-value">{{ formatActivityPrice(card.price) }}</text>
              <text v-if="isPerPersonPrice(card.price)" class="activity-card__price-unit">/人</text>
              <text v-if="formatActivityAgeRange(card.ageRange)" class="activity-card__age">
                {{ formatActivityAgeRange(card.ageRange) }}
              </text>
            </view>
            <text
              :class="[
                'activity-card__status',
                `activity-card__status--${card.enrollmentStatus.toLowerCase()}`,
              ]"
            >
              {{ formatActivityCardStatus(card) }}
            </text>
          </view>
        </view>
      </button>

      <view class="activity-channel__more" data-testid="activity-list-more">
        <text v-if="list.loadingMore">正在加载…</text>
        <button
          v-else-if="list.loadMoreFailed"
          class="activity-channel__more-retry"
          data-testid="activity-more-retry"
          @click="loader.loadMore()"
        >
          加载失败，点此重试
        </button>
        <text v-else-if="!list.hasMore">没有更多活动了</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board HomeAct (390 wide). */
.activity-channel {
  display: flex;
  padding: 4px var(--app-page-horizontal-gutter) 0;
  flex-direction: column;
  gap: 8px;
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

.activity-channel__types,
.activity-channel__dates {
  width: 100%;
  white-space: nowrap;
}

.activity-channel__type-track {
  display: inline-flex;
  gap: 4px;
}

/* A text tab with an underline; the padding makes the tap target 44 wide and high. */
.activity-channel__type {
  position: relative;
  display: inline-flex;
  min-width: 44px;
  height: 44px;
  padding: 0 10px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  color: #536178;
  font-size: 15px;
  line-height: 22px;
  white-space: nowrap;
}

.activity-channel__type--active {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
}

/* ::before, because uni-app's button already styles its own ::after. */
.activity-channel__type--active::before {
  position: absolute;
  bottom: 5px;
  left: 50%;
  width: 16px;
  height: 3px;
  margin-left: -8px;
  border-radius: 3px;
  background: #ff6a00;
  content: '';
}

.activity-channel__date-track {
  display: inline-flex;
  gap: 6px;
}

.activity-channel__date {
  position: relative;
  display: inline-flex;
  width: 52px;
  height: 48px;
  padding: 0 0 3px;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  border-radius: 10px;
  background: #ffffff;
  box-sizing: border-box;
}

.activity-channel__date-title {
  color: #738096;
  font-size: 11px;
  line-height: 15px;
}

.activity-channel__date-subtitle {
  color: #202c42;
  font-size: 14px;
  font-weight: 500;
  line-height: 19px;
}

.activity-channel__date--today .activity-channel__date-title {
  color: #ba470b;
}

.activity-channel__date--all {
  padding: 0;
}

.activity-channel__date--all .activity-channel__date-title,
.activity-channel__date--all .activity-channel__date-subtitle {
  color: #202c42;
  font-size: 13px;
  font-weight: 500;
  line-height: 17px;
}

.activity-channel__date--active {
  background: #ff6a00;
}

.activity-channel__date--active .activity-channel__date-title,
.activity-channel__date--active .activity-channel__date-subtitle {
  color: #ffffff;
}

.activity-channel__date-dot {
  position: absolute;
  bottom: 4px;
  left: 50%;
  width: 4px;
  height: 4px;
  margin-left: -2px;
  border-radius: 50%;
}

.activity-channel__date-dot--on {
  background: #ff6a00;
}

.activity-channel__date--active .activity-channel__date-dot--on {
  background: #ffffff;
}

.activity-channel__chips {
  display: flex;
  gap: 8px;
}

/* The pill is 28 high; the transparent border area around it makes the tap target taller. */
.activity-channel__chip {
  position: relative;
  display: flex;
  height: 28px;
  margin: 8px 0;
  padding: 0 12px;
  align-items: center;
  border: 1px solid #e5ebf3;
  border-radius: 999px;
  color: #536178;
  background: #ffffff;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
  box-sizing: border-box;
}

.activity-channel__chip::before {
  position: absolute;
  top: -9px;
  right: -4px;
  bottom: -9px;
  left: -4px;
  content: '';
}

.activity-channel__chip--active {
  border-color: #ffd8b8;
  color: #ba470b;
  background: #fff0e3;
  font-weight: 500;
}

.activity-channel__skeletons,
.activity-channel__list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.activity-channel__skeleton {
  height: 96px;
  border-radius: 12px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
  background-size: 400% 100%;
  animation: activity-skeleton 1.25s ease infinite;
}

@keyframes activity-skeleton {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}

.activity-channel__state {
  display: flex;
  padding: 32px 16px;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  border-radius: 12px;
  background: #ffffff;
}

.activity-channel__state-title {
  color: #738096;
  font-size: 14px;
  line-height: 20px;
}

.activity-channel__state-copy {
  color: #738096;
  font-size: 12px;
}

.activity-channel__state-action {
  display: flex;
  height: 44px;
  padding: 0 22px;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  box-sizing: border-box;
}

.activity-card {
  display: flex;
  width: 100%;
  padding: 10px;
  gap: 12px;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
  box-sizing: border-box;
}

.activity-card__cover {
  position: relative;
  width: 76px;
  height: 76px;
  flex: 0 0 auto;
  overflow: hidden;
  border-radius: 10px;
  background: #eef0f3;
}

.activity-card__image {
  width: 100%;
  height: 100%;
}

.activity-card__type {
  position: absolute;
  top: 4px;
  left: 4px;
  padding: 0 5px;
  border-radius: 6px;
  color: #6a4fe0;
  background: #eee7ff;
  font-size: 11px;
  font-weight: 500;
  line-height: 17px;
  white-space: nowrap;
}

.activity-card__body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.activity-card__title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-card__schedule {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-card__footer {
  display: flex;
  margin-top: auto;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}

.activity-card__price {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 3px;
}

.activity-card__price-value {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.activity-card__price-unit,
.activity-card__age {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.activity-card__age::before {
  content: '· ';
}

.activity-card__status {
  margin-left: auto;
  flex: 0 0 auto;
  font-size: 12px;
  line-height: 17px;
}

.activity-card__status--open {
  color: #11804a;
}

.activity-card__status--full_waitlist {
  color: #ba470b;
}

.activity-card__status--full,
.activity-card__status--closed,
.activity-card__status--ended {
  color: #738096;
}

.activity-channel__more {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  color: #738096;
  font-size: 12px;
}

.activity-channel__more-retry {
  min-height: 44px;
  padding: 0 16px;
  color: #ba470b;
  font-size: 13px;
}
</style>
