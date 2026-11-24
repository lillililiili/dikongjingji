<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { formatCoursePrice, formatLessonLength } from '../../application/course-format'
import { useCourseTrialState } from '../../application/course-trial-state'
import { courseApi } from '../../data/course-api-client'
import { coursePlaySource, resolveCoursePreviewCover } from '../../data/preview/course-preview-media'
import { courseTrialSimulationUrl } from '../../navigation/course-routes'

// 课程试听 (board CourseTrial, opened from 体验中心 “课程试听”, the course detail's 先试听 and
// 我的课程): the trial lessons of one course, playable without buying or signing in. Watching a
// trial is not recorded as progress. 报名 leads to the course detail with its 报名 sheet.
const props = defineProps<{
  target: { courseId: string } | { experienceItemId: string } | null
}>()

const emit = defineEmits<{
  back: []
  buy: [courseId: string]
  detail: [courseId: string]
  simulation: [url: string]
}>()

const { state, load } = useCourseTrialState(courseApi.loadTrial)
const currentId = ref<string | null>(null)
const playback = ref<'idle' | 'playing' | 'failed'>('idle')

const trial = computed(() => (state.value.kind === 'ready' ? state.value.trial : null))
const simulationUrl = computed(() => courseTrialSimulationUrl(trial.value?.simulationItemId ?? null, EXPERIENCE_PREVIEW_ENABLED))
const current = computed(() => trial.value?.lessons.find((lesson) => lesson.lessonId === currentId.value) ?? trial.value?.lessons[0] ?? null)
const source = computed(() => (trial.value && current.value
  ? coursePlaySource(trial.value.courseId, current.value.no, current.value.playUrl)
  : null))
const poster = computed(() => (trial.value
  ? trial.value.coverImage?.url ?? resolveCoursePreviewCover({ courseId: trial.value.courseId })
  : null))
const title = computed(() => (trial.value ? `${trial.value.title.replace(/\s*线上课$/, '')}试听` : '课程试听'))

watch(trial, loaded => {
  currentId.value = loaded?.lessons[0]?.lessonId ?? null
  playback.value = 'idle'
})

function pick(lessonId: string): void {
  if (currentId.value === lessonId) return
  currentId.value = lessonId
  playback.value = 'idle'
}

watch(() => props.target, target => { void load(target) }, { immediate: true })
</script>

<template>
  <view class="course-trial" data-testid="course-trial">
    <AppPageHeader :title="title" test-id="course-trial-header" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="course-trial__state"><text>正在加载…</text></view>
    <view v-else-if="state.kind !== 'ready'" class="course-trial__state" data-testid="course-trial-missing">
      <text class="course-trial__state-title">{{ state.kind === 'missing' ? '这门课暂时不能试听' : '加载失败，请稍后重试' }}</text>
      <button v-if="state.kind === 'error'" class="course-trial__state-action" @click="load(target)">重新加载</button>
    </view>

    <template v-else-if="trial">
      <view class="course-trial__slot" data-testid="course-trial-slot">
        <video
          v-if="playback === 'playing' && source"
          class="course-trial__video"
          :src="source"
          :poster="poster ?? ''"
          :autoplay="true"
          controls
          data-testid="course-trial-video"
          @error="playback = 'failed'"
        />
        <template v-else>
          <image v-if="poster" class="course-trial__poster" :src="poster" mode="aspectFill" />
          <view class="course-trial__cover">
            <text class="course-trial__kicker">试听 · 第 {{ current?.no }} 课 · {{ current ? formatLessonLength(current.durationSeconds) : '' }}</text>
            <text class="course-trial__slot-title">{{ current?.title }}</text>
            <text v-if="playback === 'failed'" role="alert">视频加载失败，请重试</text>
            <button v-if="source" class="course-trial__play" role="button" data-testid="course-trial-play" @click="playback = 'playing'">
              <LineIcon name="play" :size="18" :stroke-width="2" color="#ffffff" />
              <text>{{ playback === 'failed' ? '重新播放' : '开始试听' }}</text>
            </button>
            <text v-else class="course-trial__pending">课程视频待授权素材</text>
          </view>
        </template>
      </view>

      <scroll-view class="course-trial__scroll" scroll-y>
        <view class="course-trial__card">
          <view class="course-trial__head">
            <text class="course-trial__h2">试听课时</text>
            <text class="course-trial__note">共 {{ trial.lessonCount }} 课时，可试听 {{ trial.lessons.length }} 课</text>
          </view>
          <button
            v-for="lesson in trial.lessons"
            :key="lesson.lessonId"
            :class="['course-trial__lesson', { 'course-trial__lesson--on': lesson.lessonId === current?.lessonId }]"
            :aria-pressed="lesson.lessonId === current?.lessonId"
            :data-testid="`course-trial-lesson-${lesson.no}`"
            @click="pick(lesson.lessonId)"
          >
            <text class="course-trial__lesson-no">{{ String(lesson.no).padStart(2, '0') }}</text>
            <view class="course-trial__lesson-copy">
              <text class="course-trial__lesson-title">{{ lesson.title }}</text>
              <text class="course-trial__lesson-chapter">{{ lesson.chapterTitle }}</text>
            </view>
            <text class="course-trial__lesson-length">{{ formatLessonLength(lesson.durationSeconds) }}</text>
          </button>
        </view>

        <button class="course-trial__card course-trial__course" data-testid="course-trial-course" @click="emit('detail', trial.courseId)">
          <text class="course-trial__course-title">{{ trial.title }}</text>
          <text class="course-trial__course-meta">{{ trial.organization.name }} · {{ trial.lessonCount }} 课时</text>
          <view class="course-trial__course-foot">
            <text class="course-trial__price">{{ formatCoursePrice(trial.price) }}</text>
            <text class="course-trial__more">查看课程详情 ›</text>
          </view>
        </button>
        <text class="course-trial__fine">试听不记学习进度；试听结束不会自动下单。</text>
      </scroll-view>

      <view class="course-trial__bar">
        <button
          class="course-trial__buy"
          :class="{ 'course-trial__buy--secondary': simulationUrl }"
          data-testid="course-trial-buy"
          :disabled="!trial.purchasable"
          @click="emit('buy', trial.courseId)"
        >
          {{ trial.purchasable ? `立即报名 ${formatCoursePrice(trial.price)}` : '暂未开通在线报名' }}
        </button>
        <button
          v-if="simulationUrl"
          class="course-trial__buy"
          data-testid="course-trial-simulation"
          @click="emit('simulation', simulationUrl)"
        >试试配套仿真</button>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* The trial page follows the learning page (board CourseDetail): the same 220 high video slot and cards. */
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

.course-trial {
  display: flex;
  height: 100vh;
  flex-direction: column;
  background: #f5f7fc;
  color: #202c42;
}

.course-trial__state {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #627187;
}

.course-trial__state-title {
  color: #536178;
  font-size: 15px;
  font-weight: 600;
}

.course-trial__state-action {
  min-height: 44px;
  padding: 0 20px;
  border-radius: 22px;
  color: #ffffff;
  background: #ff6a00;
}

.course-trial__slot {
  position: relative;
  height: 220px;
  flex-shrink: 0;
  overflow: hidden;
  background: #2b2e33;
}

.course-trial__video,
.course-trial__poster {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 220px;
}

.course-trial__cover {
  position: absolute;
  inset: 0;
  display: flex;
  padding: 0 24px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #ffffff;
  background: rgba(25, 25, 25, 0.66);
  text-align: center;
}

.course-trial__kicker {
  font-size: 13px;
}

.course-trial__slot-title {
  font-size: 22px;
  font-weight: 700;
  line-height: 30px;
}

.course-trial__play {
  display: flex;
  min-height: 44px;
  margin-top: 6px;
  padding: 0 18px;
  align-items: center;
  gap: 6px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
}

.course-trial__pending {
  margin-top: 6px;
  padding: 2px 10px;
  border: 1px dashed rgba(255, 255, 255, 0.6);
  border-radius: 6px;
  font-size: 12px;
}

.course-trial__scroll {
  min-height: 0;
  flex: 1 1 auto;
}

.course-trial__card {
  display: flex;
  margin: 12px 16px 0;
  padding: 14px;
  flex-direction: column;
  gap: 6px;
  border-radius: 14px;
  background: #ffffff;
}

.course-trial__head {
  display: flex;
  padding-bottom: 4px;
  align-items: baseline;
  gap: 8px;
}

.course-trial__h2 {
  font-size: 16px;
  font-weight: 700;
}

.course-trial__note {
  margin-left: auto;
  color: #738096;
  font-size: 12px;
}

.course-trial__lesson {
  display: flex;
  min-height: 52px;
  padding: 0 8px;
  align-items: center;
  gap: 10px;
  border-radius: 10px;
}

.course-trial__lesson--on {
  background: #fff0e3;
}

.course-trial__lesson-no {
  color: #738096;
  font-size: 13px;
}

.course-trial__lesson--on .course-trial__lesson-no {
  color: #ba470b;
}

.course-trial__lesson-copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
}

.course-trial__lesson-title {
  font-size: 14px;
  line-height: 20px;
}

.course-trial__lesson--on .course-trial__lesson-title {
  font-weight: 700;
}

.course-trial__lesson-chapter,
.course-trial__lesson-length {
  color: #738096;
  font-size: 12px;
}

.course-trial__course-title {
  font-size: 15px;
  font-weight: 700;
}

.course-trial__course-meta {
  color: #627187;
  font-size: 12px;
}

.course-trial__course-foot {
  display: flex;
  align-items: baseline;
}

.course-trial__price {
  color: #e85400;
  font-size: 17px;
  font-weight: 700;
}

.course-trial__more {
  margin-left: auto;
  color: #ba470b;
  font-size: 13px;
}

.course-trial__fine {
  display: block;
  padding: 12px 18px 16px;
  color: #738096;
  font-size: 12px;
}

.course-trial__bar {
  display: flex;
  gap: 8px;
  padding: 8px 16px calc(8px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.course-trial__buy {
  display: flex;
  height: 44px;
  flex: 1 1 auto;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
}

.course-trial__buy[disabled] {
  color: #738096;
  background: #eef2f8;
}

.course-trial__buy--secondary:not([disabled]) {
  color: #a74500;
  background: #fff0e3;
}
</style>
