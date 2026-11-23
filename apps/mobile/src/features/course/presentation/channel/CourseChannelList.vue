<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { buildActivityDetailUrl } from '@/features/activity/navigation/activity-routes'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import {
  createPagedListLoader,
  emptyPagedList,
  type PagedListState,
} from '@/shared/list/paged-list-loader'

import {
  COURSE_TOPIC_LABELS,
  courseCardAction,
  courseCardBadge,
  courseCardUnit,
  formatCoursePrice,
} from '../../application/course-format'
import { courseApi } from '../../data/course-api-client'
import { resolveCoursePreviewCover } from '../../data/preview/course-preview-media'
import {
  COURSE_PAGE_SIZE,
  COURSE_TOPICS,
  type CourseCard,
  type CourseCardQuery,
  type CourseSort,
  type CourseTopic,
} from '../../domain/course'
import { buildCourseDetailUrl } from '../../navigation/course-routes'

// The 课程 tab of the mall (board MallCourse): online courses sold here and offline classes that
// lead to their activity. Sub-topics, the default order and price order; the search word comes
// from the home search box through the mall channel.
const props = defineProps<{
  active: boolean
  cityCode: string
  keyword: string
}>()

const emit = defineEmits<{
  // The search chip was removed here; the home search box must follow.
  endSearch: []
}>()

const currentCity = useCurrentCityStore()
const cityName = computed(() => currentCity.currentCity?.name ?? null)

const topic = ref<CourseTopic | null>(null)
const sort = ref<CourseSort>('DEFAULT')
const list = ref<PagedListState<CourseCard>>(emptyPagedList())

const SUB_TABS: readonly { code: CourseTopic | null; label: string }[] = [
  { code: null, label: '全部' },
  ...COURSE_TOPICS.map((code) => ({ code, label: COURSE_TOPIC_LABELS[code] })),
]

const loader = createPagedListLoader<CourseCardQuery, CourseCard>({
  loadPage: (query, page, size) => courseApi.loadCourses(query, page, size),
  getState: () => list.value,
  setState: (state) => { list.value = state },
  keyOf: (card) => card.courseId,
  pageSize: COURSE_PAGE_SIZE,
})

function reload(): void {
  void loader.reload({ cityCode: props.cityCode, topic: topic.value, keyword: props.keyword, sort: sort.value })
}

function selectTopic(code: CourseTopic | null): void {
  if (topic.value === code) return
  topic.value = code
  reload()
}

function selectDefaultSort(): void {
  if (sort.value === 'DEFAULT') return
  sort.value = 'DEFAULT'
  reload()
}

// Price cycles like the goods list: low to high, high to low, then back.
function togglePriceSort(): void {
  sort.value = sort.value === 'PRICE_ASC' ? 'PRICE_DESC' : 'PRICE_ASC'
  reload()
}

function coverOf(card: CourseCard): string | null {
  return card.coverImage?.url ?? resolveCoursePreviewCover(card)
}

// An online course opens its detail; the button opens it with the 报名 sheet up. An offline class
// is an activity: both lead to the activity detail, where sign-up happens.
function openCard(card: CourseCard, buy: boolean): void {
  if (card.mode === 'OFFLINE' && card.activityId) {
    uni.navigateTo({ url: buildActivityDetailUrl(card.activityId, card.cityCode ?? props.cityCode) })
    return
  }
  uni.navigateTo({ url: buildCourseDetailUrl(card.courseId, { buy }) })
}

watch(() => props.active, (active) => {
  if (active) reload()
}, { immediate: true })

watch(() => [props.cityCode, props.keyword], () => {
  if (props.active) reload()
})

defineExpose({
  loadMore: (): Promise<void> => loader.loadMore(),
})
</script>

<template>
  <view class="course-list" data-testid="course-list">
    <view class="course-list__subs" role="group" aria-label="课程子类目">
      <button
        v-for="tab in SUB_TABS"
        :key="tab.label"
        :class="['course-list__sub', { 'course-list__sub--active': tab.code === topic }]"
        :data-testid="`course-topic-${(tab.code ?? 'all').toLowerCase()}`"
        :aria-pressed="tab.code === topic"
        @click="selectTopic(tab.code)"
      >
        <text class="course-list__sub-pill">{{ tab.label }}</text>
      </button>
    </view>

    <view class="course-list__sorts">
      <button
        :class="['course-list__sort', { 'course-list__sort--active': sort === 'DEFAULT' }]"
        data-testid="course-sort-default"
        :aria-pressed="sort === 'DEFAULT'"
        @click="selectDefaultSort"
      >
        综合 ▾
      </button>
      <button
        :class="['course-list__sort', { 'course-list__sort--active': sort !== 'DEFAULT' }]"
        data-testid="course-sort-price"
        :data-sort="sort"
        :aria-label="sort === 'PRICE_ASC' ? '价格排序：从低到高' : sort === 'PRICE_DESC' ? '价格排序：从高到低' : '价格排序'"
        @click="togglePriceSort"
      >
        价格 {{ sort === 'PRICE_ASC' ? '↑' : sort === 'PRICE_DESC' ? '↓' : '⇅' }}
      </button>
    </view>

    <view v-if="keyword" class="course-list__chips">
      <button
        class="course-list__chip"
        data-testid="course-chip-keyword"
        :aria-label="`移除搜索词 ${keyword}`"
        @click="emit('endSearch')"
      >
        “{{ keyword }}” ×
      </button>
    </view>

    <view
      v-if="list.status === 'loading' || list.status === 'idle'"
      class="course-list__grid"
      data-testid="course-list-loading"
      aria-label="正在加载"
    >
      <view v-for="index in 4" :key="index" class="course-list__skeleton" />
    </view>

    <view v-else-if="list.status === 'error'" class="course-list__state" data-testid="course-list-error">
      <text class="course-list__state-title">课程加载失败，请稍后重试</text>
      <button class="course-list__state-action" data-testid="course-list-retry" @click="reload">重新加载</button>
    </view>

    <view v-else-if="list.items.length === 0" class="course-list__state" data-testid="course-list-empty">
      <text class="course-list__state-title">
        {{ keyword ? `没有找到“${keyword}”相关的课程` : topic ? `暂无${COURSE_TOPIC_LABELS[topic]}课程` : '当前城市暂无课程' }}
      </text>
      <button
        v-if="topic || keyword"
        class="course-list__state-action"
        data-testid="course-show-all"
        @click="keyword ? emit('endSearch') : selectTopic(null)"
      >
        看全部课程
      </button>
    </view>

    <template v-else>
      <view class="course-list__grid">
        <view
          v-for="card in list.items"
          :key="card.courseId"
          class="course-card"
          :data-testid="`course-card-${card.courseId}`"
          :data-mode="card.mode"
        >
          <button class="course-card__cover" :aria-label="card.title" @click="openCard(card, false)">
            <image v-if="coverOf(card)" class="course-card__image" :src="coverOf(card) ?? ''" mode="aspectFill" lazy-load />
            <text v-else class="course-card__placeholder">课程图（待授权素材）</text>
            <text
              :class="['course-card__badge', { 'course-card__badge--online': courseCardBadge(card, cityName).online }]"
            >
              {{ courseCardBadge(card, cityName).text }}
            </text>
          </button>
          <view class="course-card__body">
            <button class="course-card__name" @click="openCard(card, false)">{{ card.title }}</button>
            <view class="course-card__price">
              <text class="course-card__amount">{{ formatCoursePrice(card.price) }}</text>
              <text class="course-card__unit">{{ courseCardUnit(card) }}</text>
            </view>
            <button
              class="course-card__footer"
              :data-testid="`course-card-action-${card.courseId}`"
              @click="openCard(card, card.mode === 'ONLINE')"
            >
              <text
                :class="['course-card__action', { 'course-card__action--inquiry': courseCardAction(card).inquiry }]"
              >
                {{ courseCardAction(card).text }}
              </text>
            </button>
          </view>
        </view>
      </view>

      <view class="course-list__more" data-testid="course-list-more">
        <text v-if="list.loadingMore">正在加载…</text>
        <button v-else-if="list.loadMoreFailed" class="course-list__more-retry" @click="loader.loadMore()">
          加载失败，点此重试
        </button>
        <text v-else-if="!list.hasMore">没有更多课程了</text>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the board MallCourse (390 wide), the same card as the goods list. */
.course-list {
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

.course-list__subs {
  display: flex;
  gap: 6px;
  padding-top: 2px;
}

.course-list__sub {
  display: flex;
  height: 36px;
  flex: 0 0 auto;
  align-items: flex-end;
}

.course-list__sub-pill {
  display: flex;
  height: 32px;
  padding: 0 12px;
  align-items: center;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.course-list__sub--active .course-list__sub-pill {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.course-list__sorts {
  display: flex;
  height: 36px;
  align-items: center;
  gap: 20px;
}

.course-list__sort {
  display: flex;
  min-width: 44px;
  height: 36px;
  align-items: center;
  color: #536178;
  font-size: 14px;
  white-space: nowrap;
}

.course-list__sort--active {
  color: #ba470b;
  font-weight: 500;
}

.course-list__chips {
  display: flex;
  height: 36px;
  align-items: center;
}

.course-list__chip {
  display: flex;
  height: 28px;
  padding: 0 10px;
  align-items: center;
  border-radius: 999px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
}

.course-list__grid {
  display: grid;
  margin-top: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.course-list__skeleton {
  height: 214px;
  border-radius: 12px;
  background: #edf0f3;
}

.course-list__state {
  display: flex;
  min-height: 260px;
  margin-top: 8px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 12px;
  background: #ffffff;
}

.course-list__state-title {
  color: #536178;
  font-size: 15px;
  font-weight: 600;
}

.course-list__state-action {
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

.course-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
  border-radius: 12px;
  background: #ffffff;
  outline: 1.5px solid #ffffff;
  outline-offset: -1.5px;
}

.course-card__cover {
  position: relative;
  display: flex;
  width: 100%;
  height: 104px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #eef0f3;
}

.course-card__image {
  width: 100%;
  height: 100%;
}

.course-card__placeholder {
  color: #738096;
  font-size: 11px;
  line-height: 16px;
}

.course-card__badge {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 1px 6px;
  border-radius: 6px;
  color: #ffffff;
  background: #159a70;
  font-size: 11px;
  line-height: 16px;
}

.course-card__badge--online {
  background: #2f80ed;
}

.course-card__body {
  display: flex;
  padding: 8px 10px 2px;
  flex-direction: column;
  gap: 4px;
}

.course-card__name {
  overflow: hidden;
  color: #202c42;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.course-card__price {
  display: flex;
  height: 24px;
  align-items: baseline;
  gap: 3px;
  white-space: nowrap;
}

.course-card__amount {
  color: #e85400;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.course-card__unit {
  min-width: 0;
  margin-left: auto;
  overflow: hidden;
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
}

.course-card__footer {
  display: flex;
  height: 44px;
  align-items: center;
}

.course-card__action {
  display: flex;
  height: 30px;
  padding: 0 12px;
  align-items: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.course-card__action--inquiry {
  color: #ba470b;
  background: #fff0e3;
}

.course-list__more {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  color: #738096;
  font-size: 12px;
}

.course-list__more-retry {
  min-height: 44px;
  padding: 0 16px;
  color: #ba470b;
  font-size: 13px;
}
</style>
