<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { formatResume, progressSegments } from '../../application/course-format'
import { courseApi } from '../../data/course-api-client'
import { resolveCoursePreviewCover } from '../../data/preview/course-preview-media'
import {
  COURSE_PAGE_SIZE,
  type CourseEnrollmentBucket,
  type CourseEnrollmentCard,
  type CourseEnrollmentPage,
  type CourseTrialCard,
} from '../../domain/course'

// 我的课程 (board MyCourses): open courses with their progress, the courses that can be tried, and a
// pointer to 活动报名 for offline classes. Expired courses stay in 全部 with their own label.
const props = defineProps<{
  accessToken: string | null
  refreshKey: number
  initialBucket: CourseEnrollmentBucket
}>()

const emit = defineEmits<{
  back: []
  login: []
  learn: [enrollmentId: string, lessonId: string | null]
  trial: [courseId: string]
  enrollments: []
}>()

const CHIPS: readonly { bucket: CourseEnrollmentBucket; label: string }[] = [
  { bucket: 'ALL', label: '全部' },
  { bucket: 'LEARNING', label: '学习中' },
  { bucket: 'FINISHED', label: '已学完' },
]

const bucket = ref<CourseEnrollmentBucket>(props.initialBucket)
const status = ref<'loading' | 'ready' | 'error'>('loading')
const page = ref<CourseEnrollmentPage | null>(null)
let version = 0

const items = computed<CourseEnrollmentCard[]>(() => page.value?.items ?? [])
const trials = computed<CourseTrialCard[]>(() => (bucket.value === 'ALL' ? page.value?.trials ?? [] : []))
const empty = computed(() => status.value === 'ready' && items.value.length === 0 && trials.value.length === 0)

async function load(): Promise<void> {
  const requested = ++version
  if (!props.accessToken) {
    emit('login')
    return
  }
  status.value = 'loading'
  try {
    const loaded = await courseApi.loadEnrollments(props.accessToken, bucket.value, 1, COURSE_PAGE_SIZE)
    if (requested !== version) return
    page.value = loaded
    status.value = 'ready'
  } catch (error) {
    if (requested !== version) return
    if (error instanceof ApiRequestError && error.status === 401) {
      emit('login')
      return
    }
    status.value = 'error'
  }
}

function pick(next: CourseEnrollmentBucket): void {
  if (bucket.value === next) return
  bucket.value = next
  void load()
}

function cover(courseId: string, image: { url: string } | null): string | null {
  return image?.url ?? resolveCoursePreviewCover({ courseId })
}

function sourceLine(card: CourseEnrollmentCard): string {
  if (card.bundle) return `来源：${card.bundle.productName} · ${card.bundle.skuName.replace(/（.*）$/, '')}`
  return card.status === 'EXPIRED' ? '已过有效期' : '已购课程'
}

watch(() => [props.accessToken, props.refreshKey], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="my-courses" data-testid="my-courses">
    <AppPageHeader title="我的课程" test-id="my-courses-header" @back="emit('back')" />
    <view class="my-courses__chips" role="group" aria-label="按学习状态筛选">
      <button
        v-for="chip in CHIPS"
        :key="chip.bucket"
        :class="['my-courses__chip', { 'my-courses__chip--on': chip.bucket === bucket }]"
        :aria-pressed="chip.bucket === bucket"
        :data-testid="`my-courses-bucket-${chip.bucket.toLowerCase()}`"
        @click="pick(chip.bucket)"
      >
        {{ chip.label }}
      </button>
    </view>

    <scroll-view class="my-courses__scroll" scroll-y>
      <view class="my-courses__list">
        <view v-if="status === 'loading'" class="my-courses__empty"><text>正在加载…</text></view>
        <view v-else-if="status === 'error'" class="my-courses__empty" data-testid="my-courses-error">
          <text>加载失败</text>
          <button class="my-courses__retry" @click="load">重新加载</button>
        </view>

        <template v-else>
          <button
            v-for="card in items"
            :key="card.enrollmentId"
            class="my-courses__card"
            :data-testid="`my-course-${card.courseId}`"
            :data-status="card.status"
            @click="emit('learn', card.enrollmentId, card.resume?.lessonId ?? null)"
          >
            <view class="my-courses__top">
              <image v-if="cover(card.courseId, card.coverImage)" class="my-courses__cover" :src="cover(card.courseId, card.coverImage) ?? ''" mode="aspectFill" :alt="card.title" />
              <view v-else class="my-courses__cover" />
              <view class="my-courses__copy">
                <view class="my-courses__tag-row">
                  <text :class="['my-courses__tag', card.source === 'BUNDLE' ? 'my-courses__tag--bundle' : 'my-courses__tag--paid']">
                    {{ card.source === 'BUNDLE' ? '配套数字课程' : card.status === 'EXPIRED' ? '已过期' : '已购课程' }}
                  </text>
                  <text class="my-courses__lessons">{{ card.lessonCount }} 课时</text>
                </view>
                <text class="my-courses__title">{{ card.title }}</text>
                <text class="my-courses__source">{{ sourceLine(card) }}</text>
              </view>
            </view>
            <view class="my-courses__progress">
              <text class="my-courses__progress-text">已学 {{ card.completedCount }} / {{ card.lessonCount }} 课时</text>
              <view
                class="my-courses__bar"
                role="progressbar"
                aria-label="学习进度"
                :aria-valuemax="card.lessonCount"
                :aria-valuenow="card.completedCount"
                :style="{ gridTemplateColumns: `repeat(${Math.max(card.lessonCount, 1)}, minmax(0, 1fr))` }"
              >
                <text
                  v-for="(lit, index) in progressSegments(card.lessonCount, card.completedCount)"
                  :key="index"
                  :class="['my-courses__seg', { 'my-courses__seg--on': lit }]"
                />
              </view>
            </view>
            <view class="my-courses__foot">
              <text class="my-courses__next">{{ formatResume(card.resume) }}</text>
              <text :class="['my-courses__go', { 'my-courses__go--off': card.status === 'EXPIRED' }]">
                {{ card.status === 'EXPIRED' ? '查看' : card.resume ? '继续学习' : '回看' }}
              </text>
            </view>
          </button>

          <button
            v-for="trial in trials"
            :key="trial.courseId"
            class="my-courses__card my-courses__card--row"
            :data-testid="`my-course-trial-${trial.courseId}`"
            @click="emit('trial', trial.courseId)"
          >
            <image v-if="cover(trial.courseId, trial.coverImage)" class="my-courses__cover" :src="cover(trial.courseId, trial.coverImage) ?? ''" mode="aspectFill" :alt="trial.title" />
            <view v-else class="my-courses__cover" />
            <view class="my-courses__copy">
              <view class="my-courses__tag-row">
                <text class="my-courses__tag my-courses__tag--trial">课程试听</text>
                <text class="my-courses__lessons">试听 {{ trial.trialLessonCount }} 课</text>
              </view>
              <text class="my-courses__title">{{ trial.title.replace(/\s*线上课$/, '') }}（试听）</text>
              <view class="my-courses__tag-row">
                <text class="my-courses__source">来自体验中心“课程试听”</text>
                <text class="my-courses__try">去试听 ›</text>
              </view>
            </view>
          </button>

          <view v-if="empty" class="my-courses__empty" data-testid="my-courses-empty">
            <LineIcon name="book" :size="32" :stroke-width="1.6" color="#c9d3e1" />
            <text>{{ bucket === 'FINISHED' ? '还没有学完的课程' : bucket === 'LEARNING' ? '没有正在学的课程' : '还没有开通课程' }}</text>
          </view>
        </template>

        <button class="my-courses__offline" data-testid="my-courses-offline" @click="emit('enrollments')">
          <text>线下培训在“<text class="my-courses__offline-link">活动报名</text>”里查看</text>
          <LineIcon name="chevronRight" :size="14" :stroke-width="2" color="#ba470b" />
        </button>
      </view>
    </scroll-view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the board MyCourses (390 wide). */
button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.3;
  text-align: left;
}

button::after {
  border: 0;
}

.my-courses {
  display: flex;
  height: 100vh;
  flex-direction: column;
  background: #f5f7fc;
  color: #202c42;
}

.my-courses__chips {
  display: flex;
  padding: 0 16px 12px;
  gap: 8px;
  background: #ffffff;
}

.my-courses__chip {
  display: flex;
  height: 36px;
  padding: 0 16px;
  align-items: center;
  border: 1px solid #eef2f8;
  border-radius: 999px;
  color: #202c42;
  background: #eef2f8;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
  box-sizing: border-box;
}

.my-courses__chip--on {
  border-color: #ffd8b8;
  color: #ba470b;
  background: #fff0e3;
  font-weight: 500;
}

.my-courses__scroll {
  min-height: 0;
  flex: 1 1 auto;
}

.my-courses__list {
  display: flex;
  padding: 12px 16px;
  flex-direction: column;
  gap: 10px;
}

.my-courses__card {
  display: flex;
  padding: 12px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
}

.my-courses__card--row {
  flex-direction: row;
  gap: 12px;
}

.my-courses__top {
  display: flex;
  gap: 12px;
}

.my-courses__cover {
  width: 96px;
  height: 72px;
  flex-shrink: 0;
  border-radius: 10px;
  background: #eef2f8;
}

.my-courses__copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 4px;
}

.my-courses__tag-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.my-courses__tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.my-courses__tag--bundle {
  color: #1d63c4;
  background: #e6f0ff;
}

.my-courses__tag--paid {
  color: #ba470b;
  background: #fff0e3;
}

.my-courses__tag--trial {
  color: #11804a;
  background: #e2f6ed;
}

.my-courses__lessons {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.my-courses__title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.my-courses__source {
  min-width: 0;
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.my-courses__try {
  margin-left: auto;
  flex-shrink: 0;
  color: #ba470b;
  font-size: 13px;
  white-space: nowrap;
}

.my-courses__progress {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.my-courses__progress-text {
  font-size: 13px;
  line-height: 18px;
}

.my-courses__bar {
  display: grid;
  gap: 3px;
}

.my-courses__seg {
  height: 6px;
  border-radius: 3px;
  background: #e5ebf3;
}

.my-courses__seg--on {
  background: #ff6a00;
}

.my-courses__foot {
  display: flex;
  align-items: center;
  gap: 12px;
}

.my-courses__next {
  min-width: 0;
  flex: 1 1 auto;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.my-courses__go {
  display: flex;
  height: 36px;
  padding: 0 16px;
  flex-shrink: 0;
  align-items: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
}

.my-courses__go--off {
  color: #536178;
  background: #eef2f8;
}

.my-courses__empty {
  display: flex;
  height: 160px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 14px;
  color: #738096;
  background: #ffffff;
  font-size: 14px;
}

.my-courses__retry {
  min-height: 44px;
  color: #ba470b;
}

.my-courses__offline {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #627187;
  font-size: 13px;
  white-space: nowrap;
}

.my-courses__offline-link {
  color: #ba470b;
}
</style>
