<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  COURSE_STATUS_LABELS,
  REFUND_BLOCK_TEXTS,
  REFUND_STATUS_LABELS,
  formatCourseAmount,
  formatCourseTime,
  formatExpiry,
  formatLessonLength,
} from '../../application/course-format'
import { describeCourseFailure } from '../../application/course-failures'
import { courseApi } from '../../data/course-api-client'
import type { CourseEnrollment, CourseProgressLesson } from '../../domain/course'

// 课程学习 (board CourseDetail): the video slot, the progress strip, the outline with one chapter
// open, where the course came from, and the refund rules. Progress is the server's: a lesson starts
// when its play address is asked for and is learned when the video plays to its end.
const props = defineProps<{
  enrollmentId: string | null
  accessToken: string | null
  refreshKey: number
  // A lesson to put in the video slot first (from 我的课程 “继续学习” it is the resume lesson).
  lessonId: string | null
}>()

const emit = defineEmits<{
  back: []
  login: []
  pay: [tradeOrderId: string]
  openOrder: [orderId: string]
}>()

type State = { kind: 'loading' } | { kind: 'ready'; enrollment: CourseEnrollment } | { kind: 'missing' } | { kind: 'error' }

const state = ref<State>({ kind: 'loading' })
const currentId = ref<string | null>(null)
const openChapterId = ref<string | null>(null)
// The lesson whose play address was given and is in the player now.
const playing = ref<{ lessonId: string; src: string | null } | null>(null)
const busy = ref(false)
const notice = ref<string | null>(null)
let version = 0

const enrollment = computed(() => (state.value.kind === 'ready' ? state.value.enrollment : null))
const lessons = computed<CourseProgressLesson[]>(() => enrollment.value?.chapters.flatMap((chapter) => chapter.lessons) ?? [])
const current = computed(() => lessons.value.find((lesson) => lesson.lessonId === currentId.value) ?? lessons.value[0] ?? null)
const learnable = computed(() => enrollment.value?.status === 'ACTIVE')
const allDone = computed(() => Boolean(enrollment.value && enrollment.value.lessonCount > 0
  && enrollment.value.completedCount >= enrollment.value.lessonCount))
const segments = computed(() => lessons.value.map((lesson) => lesson.state === 'COMPLETED'))
const poster = computed(() => (enrollment.value
  ? enrollment.value.coverImage?.url ?? null
  : null))
const averageMinutes = computed(() => {
  const all = lessons.value
  if (all.length === 0) return 0
  return Math.max(1, Math.round(all.reduce((sum, lesson) => sum + lesson.durationSeconds, 0) / all.length / 60))
})
const slotKicker = computed(() => (current.value
  ? `第 ${current.value.no} 课 · ${current.value.state === 'COMPLETED' ? '已学完' : formatLessonLength(current.value.durationSeconds)}`
  : ''))
const refundLine = computed(() => {
  const value = enrollment.value
  if (!value) return ''
  if (value.refund) return `退款 ${formatCourseAmount(value.refund.amountMinor)} · ${REFUND_STATUS_LABELS[value.refund.status]}`
  if (value.refundability.refundable) {
    return `付款后 7 天内（${formatCourseTime(value.refundability.deadline)} 前）、还没开始学习付费课时，可申请全额退款，原路退回`
  }
  return value.refundability.blockedReason ? REFUND_BLOCK_TEXTS[value.refundability.blockedReason] : ''
})

async function load(): Promise<void> {
  const requested = ++version
  if (!props.enrollmentId) {
    state.value = { kind: 'missing' }
    return
  }
  if (!props.accessToken) {
    emit('login')
    return
  }
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await courseApi.loadEnrollment(props.accessToken, props.enrollmentId)
    if (requested !== version) return
    show(loaded)
  } catch (error) {
    if (requested !== version) return
    if (error instanceof ApiRequestError && error.status === 401) {
      emit('login')
      return
    }
    state.value = error instanceof ApiRequestError && (error.status === 404 || error.status === 403)
      ? { kind: 'missing' }
      : { kind: 'error' }
  }
}

function show(loaded: CourseEnrollment): void {
  state.value = { kind: 'ready', enrollment: loaded }
  const all = loaded.chapters.flatMap((chapter) => chapter.lessons)
  if (!currentId.value || !all.some((lesson) => lesson.lessonId === currentId.value)) {
    currentId.value = (props.lessonId && all.some((lesson) => lesson.lessonId === props.lessonId))
      ? props.lessonId
      : loaded.resume?.lessonId ?? all[0]?.lessonId ?? null
  }
  const chapter = loaded.chapters.find((item) => item.lessons.some((lesson) => lesson.lessonId === currentId.value))
  if (chapter && openChapterId.value === null) openChapterId.value = chapter.chapterId
}

function pick(lesson: CourseProgressLesson): void {
  currentId.value = lesson.lessonId
  if (playing.value?.lessonId !== lesson.lessonId) playing.value = null
}

function toggleChapter(chapterId: string): void {
  openChapterId.value = openChapterId.value === chapterId ? '' : chapterId
}

// Asks the server for the play address (which records that the lesson started). Starting the first
// paid lesson while a refund is still possible ends that possibility, so it asks first.
async function play(): Promise<void> {
  const value = enrollment.value
  const lesson = current.value
  if (!value || !lesson || !props.accessToken || busy.value || !learnable.value) return
  if (!lesson.trial && value.refundability.refundable) {
    const confirmed = await new Promise<boolean>((resolve) => {
      uni.showModal({
        title: '开始学习',
        content: '开始学习付费课时后，这门课就不能在线退款了。确定开始吗？',
        confirmText: '开始学习',
        cancelText: '再想想',
        success: (result) => resolve(result.confirm),
        fail: () => resolve(false),
      })
    })
    if (!confirmed) return
  }
  busy.value = true
  notice.value = null
  try {
    const playback = await courseApi.playback(props.accessToken, value.enrollmentId, lesson.lessonId)
    playing.value = { lessonId: lesson.lessonId, src: playback.playUrl }
    if (!playing.value.src) notice.value = '课程视频待授权素材，暂时不能播放'
    // The lesson now reads 学习中 and the refund rules may have changed.
    const loaded = await courseApi.loadEnrollment(props.accessToken, value.enrollmentId)
    show(loaded)
  } catch (error) {
    const failure = describeCourseFailure(error)
    if (failure.signIn) emit('login')
    else notice.value = failure.message
    if (failure.reload) void load()
  } finally {
    busy.value = false
  }
}

// The video played to its end: the lesson is learned, and the next one not yet learned comes up.
async function onEnded(): Promise<void> {
  const value = enrollment.value
  const lessonId = playing.value?.lessonId
  if (!value || !lessonId || !props.accessToken) return
  try {
    const loaded = await courseApi.complete(props.accessToken, value.enrollmentId, lessonId)
    state.value = { kind: 'ready', enrollment: loaded }
    if (loaded.resume && loaded.resume.lessonId !== lessonId) {
      currentId.value = loaded.resume.lessonId
      playing.value = null
      const chapter = loaded.chapters.find((item) => item.lessons.some((lesson) => lesson.lessonId === loaded.resume?.lessonId))
      if (chapter) openChapterId.value = chapter.chapterId
    }
  } catch (error) {
    notice.value = describeCourseFailure(error).message
  }
}

async function refund(): Promise<void> {
  const value = enrollment.value
  if (!value?.payment || !props.accessToken || busy.value) return
  const confirmed = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '申请退款',
      content: `退款 ${formatCourseAmount(value.payment?.amountMinor ?? 0)} 将原路退回，退款后课程关闭、不能再学习。确定退款吗？`,
      confirmText: '确定退款',
      cancelText: '再想想',
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false),
    })
  })
  if (!confirmed) return
  busy.value = true
  try {
    show(await courseApi.refund(props.accessToken, value.enrollmentId))
    playing.value = null
    uni.showToast({ title: '已申请退款', icon: 'none' })
  } catch (error) {
    const failure = describeCourseFailure(error)
    notice.value = failure.message
    if (failure.reload) void load()
  } finally {
    busy.value = false
  }
}

function lessonStatus(lesson: CourseProgressLesson): { text: string; tone: string } {
  if (lesson.state === 'COMPLETED') return { text: '已学完', tone: 'done' }
  if (lesson.lessonId === enrollment.value?.resume?.lessonId) return { text: '继续学习', tone: 'resume' }
  return { text: lesson.state === 'STARTED' ? '学习中' : '未开始', tone: 'idle' }
}

watch(() => [props.enrollmentId, props.accessToken, props.refreshKey], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="course-learn" data-testid="course-learn">
    <AppPageHeader title="课程学习" test-id="course-learn-header" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="course-learn__state"><text>正在加载…</text></view>
    <view v-else-if="state.kind !== 'ready'" class="course-learn__state" data-testid="course-learn-missing">
      <text class="course-learn__state-title">{{ state.kind === 'missing' ? '没有找到这门课' : '加载失败，请稍后重试' }}</text>
      <button v-if="state.kind === 'error'" class="course-learn__state-action" @click="load">重新加载</button>
    </view>

    <template v-else-if="enrollment">
      <view class="course-learn__slot" data-testid="course-learn-slot">
        <video
          v-if="playing?.src"
          id="course-learn-video"
          class="course-learn__video"
          :src="playing.src"
          :poster="poster ?? ''"
          :autoplay="true"
          controls
          data-testid="course-learn-video"
          @ended="onEnded"
        />
        <template v-else>
          <image v-if="poster" class="course-learn__poster" :src="poster" mode="aspectFill" />
          <view class="course-learn__cover">
            <text class="course-learn__kicker">{{ slotKicker }}</text>
            <text class="course-learn__slot-title">{{ current?.title }}</text>
            <button
              v-if="learnable"
              class="course-learn__play"
              data-testid="course-learn-play"
              :disabled="busy"
              @click="play"
            >
              <LineIcon name="play" :size="18" :stroke-width="2" color="#ffffff" />
              <text>{{ current?.state === 'COMPLETED' ? '再看一遍' : '播放本课' }}</text>
            </button>
            <text v-else class="course-learn__locked">{{ COURSE_STATUS_LABELS[enrollment.status] }}，不能播放</text>
          </view>
        </template>
      </view>

      <scroll-view class="course-learn__scroll" scroll-y>
        <view v-if="notice" class="course-learn__notice" data-testid="course-learn-notice">{{ notice }}</view>

        <view class="course-learn__card">
          <view class="course-learn__title-row">
            <text class="course-learn__title">{{ enrollment.title }}</text>
            <text :class="['course-learn__source', `course-learn__source--${enrollment.source.toLowerCase()}`]">
              {{ enrollment.source === 'BUNDLE' ? '配套数字课程' : '已购课程' }}
            </text>
            <text class="course-learn__count">{{ enrollment.lessonCount }} 课时</text>
          </view>
          <view class="course-learn__grid">
            <text class="course-learn__key">提供方</text>
            <text>{{ enrollment.organization.name }}</text>
            <template v-if="enrollment.bundle">
              <text class="course-learn__key">来源订单</text>
              <button class="course-learn__order" data-testid="course-learn-order" @click="emit('openOrder', enrollment.bundle.orderId)">
                <view class="course-learn__order-copy">
                  <text class="course-learn__order-no">{{ enrollment.bundle.orderNo }}</text>
                  <text class="course-learn__order-item">{{ enrollment.bundle.productName }} · {{ enrollment.bundle.skuName }}</text>
                </view>
                <LineIcon name="chevronRight" :size="14" :stroke-width="2" color="#91a0b4" />
              </button>
            </template>
            <template v-else-if="enrollment.payment">
              <text class="course-learn__key">实付</text>
              <text>{{ formatCourseAmount(enrollment.payment.amountMinor) }}</text>
            </template>
            <text class="course-learn__key">开通时间</text>
            <text>{{ enrollment.activatedAt ? formatCourseTime(enrollment.activatedAt) : COURSE_STATUS_LABELS[enrollment.status] }}</text>
            <text class="course-learn__key">有效期</text>
            <text data-testid="course-learn-expiry">{{ formatExpiry(enrollment) }}</text>
          </view>
          <button
            v-if="enrollment.status === 'PENDING_PAYMENT' && enrollment.payment"
            class="course-learn__pay"
            data-testid="course-learn-pay"
            @click="emit('pay', enrollment.payment.tradeOrderId)"
          >
            去支付 {{ formatCourseAmount(enrollment.payment.amountMinor) }}
          </button>
        </view>

        <view class="course-learn__card" data-testid="course-learn-progress">
          <view class="course-learn__progress-head">
            <text class="course-learn__progress-title">已学 {{ enrollment.completedCount }} / {{ enrollment.lessonCount }} 课时</text>
            <text v-if="enrollment.resume && !allDone" class="course-learn__resume">继续学习：第 {{ enrollment.resume.no }} 课</text>
          </view>
          <view
            class="course-learn__bar"
            role="progressbar"
            aria-label="学习进度"
            :aria-valuemax="enrollment.lessonCount"
            :aria-valuenow="enrollment.completedCount"
            :style="{ gridTemplateColumns: `repeat(${Math.max(segments.length, 1)}, minmax(0, 1fr))` }"
          >
            <text v-for="(lit, index) in segments" :key="index" :class="['course-learn__seg', { 'course-learn__seg--on': lit }]" />
          </view>
          <view v-if="allDone" class="course-learn__done" role="status">
            <LineIcon name="check" :size="18" :stroke-width="2.4" color="#159a70" />
            <text>已学完全部课时</text>
          </view>
        </view>

        <view class="course-learn__card course-learn__card--outline">
          <view class="course-learn__outline-head">
            <text class="course-learn__progress-title">课程目录</text>
            <text class="course-learn__outline-note">{{ enrollment.chapters.length }} 章 · 每课约 {{ averageMinutes }} 分钟</text>
          </view>
          <view v-for="chapter in enrollment.chapters" :key="chapter.chapterId" class="course-learn__chapter">
            <button
              class="course-learn__chapter-head"
              :aria-expanded="openChapterId === chapter.chapterId"
              :data-testid="`course-learn-chapter-${chapter.chapterId}`"
              @click="toggleChapter(chapter.chapterId)"
            >
              <text class="course-learn__chapter-title">{{ chapter.title }}</text>
              <text
                :class="['course-learn__chapter-progress', { 'course-learn__chapter-progress--done': chapter.completedCount === chapter.lessons.length }]"
              >
                已学 {{ chapter.completedCount }} / {{ chapter.lessons.length }}
              </text>
              <LineIcon :name="openChapterId === chapter.chapterId ? 'chevronUp' : 'chevronDown'" :size="16" :stroke-width="2" color="#91a0b4" />
            </button>
            <view v-if="openChapterId === chapter.chapterId" role="group" :aria-label="chapter.title">
              <button
                v-for="(lesson, index) in chapter.lessons"
                :key="lesson.lessonId"
                :class="['course-learn__lesson', { 'course-learn__lesson--current': lesson.lessonId === current?.lessonId }]"
                :aria-current="lesson.lessonId === current?.lessonId ? 'true' : 'false'"
                :data-testid="`course-learn-lesson-${lesson.no}`"
                :data-state="lesson.state"
                @click="pick(lesson)"
              >
                <view class="course-learn__node">
                  <text v-if="index > 0" class="course-learn__line course-learn__line--up" />
                  <text v-if="index < chapter.lessons.length - 1" class="course-learn__line course-learn__line--down" />
                  <text
                    :class="[
                      'course-learn__dot',
                      { 'course-learn__dot--done': lesson.state === 'COMPLETED', 'course-learn__dot--resume': lessonStatus(lesson).tone === 'resume' },
                    ]"
                  >
                    <LineIcon v-if="lesson.state === 'COMPLETED'" name="check" :size="9" :stroke-width="4" color="#ffffff" />
                  </text>
                </view>
                <text class="course-learn__lesson-no">{{ String(lesson.no).padStart(2, '0') }}</text>
                <text class="course-learn__lesson-title">{{ lesson.title }}</text>
                <text v-if="lesson.trial" class="course-learn__lesson-trial">试听</text>
                <text :class="['course-learn__lesson-status', `course-learn__lesson-status--${lessonStatus(lesson).tone}`]">
                  {{ lessonStatus(lesson).text }}
                </text>
              </button>
            </view>
          </view>
        </view>

        <view class="course-learn__rules" data-testid="course-learn-rules">
          <LineIcon name="info" :size="14" :stroke-width="2" color="#738096" />
          <text class="course-learn__rules-copy">
            {{ enrollment.source === 'BUNDLE'
              ? '支付成功后自动开通，不发货、不走物流。课程退款随商品订单规则处理，入口在订单详情'
              : refundLine }}
          </text>
        </view>
        <button
          v-if="enrollment.refundability.refundable && enrollment.source === 'PURCHASE'"
          class="course-learn__refund"
          data-testid="course-learn-refund"
          :disabled="busy"
          @click="refund"
        >
          申请退款
        </button>
      </scroll-view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the board CourseDetail (390 wide). */
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

.course-learn {
  display: flex;
  height: 100vh;
  flex-direction: column;
  background: #f5f7fc;
  color: #202c42;
}

.course-learn__state {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #627187;
}

.course-learn__state-title {
  color: #536178;
  font-size: 15px;
  font-weight: 600;
}

.course-learn__state-action {
  min-height: 44px;
  padding: 0 20px;
  border-radius: 22px;
  color: #ffffff;
  background: #ff6a00;
}

.course-learn__slot {
  position: relative;
  height: 220px;
  flex-shrink: 0;
  overflow: hidden;
  background: #2b2e33;
}

.course-learn__video,
.course-learn__poster {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 220px;
}

.course-learn__cover {
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

.course-learn__kicker {
  font-size: 13px;
  line-height: 18px;
}

.course-learn__slot-title {
  font-size: 22px;
  font-weight: 700;
  line-height: 30px;
}

.course-learn__play {
  display: flex;
  height: 40px;
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

.course-learn__locked {
  margin-top: 6px;
  padding: 2px 10px;
  border: 1px dashed rgba(255, 255, 255, 0.6);
  border-radius: 6px;
  font-size: 12px;
}

.course-learn__scroll {
  min-height: 0;
  flex: 1 1 auto;
}

.course-learn__notice {
  margin: 12px 16px 0;
  padding: 10px 12px;
  border-radius: 10px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
}

.course-learn__card {
  display: flex;
  margin: 12px 16px 0;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
}

.course-learn__title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.course-learn__title {
  min-width: 0;
  overflow: hidden;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.course-learn__source {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.course-learn__source--bundle {
  color: #1d63c4;
  background: #e6f0ff;
}

.course-learn__source--purchase {
  color: #ba470b;
  background: #fff0e3;
}

.course-learn__count {
  margin-left: auto;
  flex-shrink: 0;
  color: #627187;
  font-size: 13px;
  white-space: nowrap;
}

.course-learn__grid {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
  line-height: 19px;
}

.course-learn__key {
  color: #627187;
}

.course-learn__order {
  display: flex;
  min-height: 36px;
  align-items: center;
  gap: 8px;
}

.course-learn__order-copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
}

.course-learn__order-no {
  color: #ba470b;
}

.course-learn__order-item {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.course-learn__pay {
  display: flex;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
}

.course-learn__progress-head,
.course-learn__outline-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.course-learn__progress-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.course-learn__resume {
  margin-left: auto;
  color: #ba470b;
  font-size: 13px;
  white-space: nowrap;
}

.course-learn__bar {
  display: grid;
  gap: 3px;
}

.course-learn__seg {
  height: 6px;
  border-radius: 3px;
  background: #e5ebf3;
}

.course-learn__seg--on {
  background: #ff6a00;
}

.course-learn__done {
  display: flex;
  height: 44px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 999px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 15px;
  font-weight: 500;
}

.course-learn__card--outline {
  padding: 14px 0 4px;
  gap: 0;
}

.course-learn__outline-head {
  padding: 0 14px 10px;
}

.course-learn__outline-note {
  margin-left: auto;
  color: #738096;
  font-size: 12px;
  white-space: nowrap;
}

.course-learn__chapter {
  border-top: 1px solid #f0f1f3;
}

.course-learn__chapter-head {
  display: flex;
  width: 100%;
  min-height: 52px;
  padding: 0 14px;
  align-items: center;
  gap: 8px;
  box-sizing: border-box;
}

.course-learn__chapter-title {
  min-width: 0;
  flex: 1 1 auto;
  font-size: 15px;
  font-weight: 500;
}

.course-learn__chapter-progress {
  color: #738096;
  font-size: 12px;
  white-space: nowrap;
}

.course-learn__chapter-progress--done {
  color: #11804a;
}

.course-learn__lesson {
  display: flex;
  width: 100%;
  min-height: 48px;
  padding: 0 14px;
  align-items: center;
  gap: 10px;
  box-sizing: border-box;
}

.course-learn__lesson--current {
  background: #fff0e3;
}

.course-learn__node {
  position: relative;
  width: 16px;
  align-self: stretch;
  flex-shrink: 0;
}

.course-learn__line {
  position: absolute;
  left: 7px;
  width: 2px;
  background: #e5ebf3;
}

.course-learn__line--up {
  top: 0;
  bottom: 50%;
}

.course-learn__line--down {
  top: 50%;
  bottom: 0;
}

.course-learn__dot {
  position: absolute;
  top: 50%;
  left: 0;
  display: flex;
  width: 16px;
  height: 16px;
  margin-top: -8px;
  align-items: center;
  justify-content: center;
  border: 2px solid #c9d3e1;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.course-learn__dot--done {
  border-color: #ff6a00;
  background: #ff6a00;
}

.course-learn__dot--resume {
  border-color: #ff6a00;
}

.course-learn__lesson-no {
  color: #738096;
  font-size: 13px;
}

.course-learn__lesson--current .course-learn__lesson-no {
  color: #ba470b;
}

.course-learn__lesson-title {
  min-width: 0;
  flex: 1 1 auto;
  font-size: 14px;
  line-height: 20px;
}

.course-learn__lesson--current .course-learn__lesson-title {
  font-weight: 700;
}

.course-learn__lesson-trial {
  padding: 0 5px;
  border-radius: 4px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 11px;
}

.course-learn__lesson-status {
  flex-shrink: 0;
  font-size: 12px;
  white-space: nowrap;
}

.course-learn__lesson-status--done {
  color: #11804a;
}

.course-learn__lesson-status--resume {
  color: #ba470b;
}

.course-learn__lesson-status--idle {
  color: #738096;
}

.course-learn__rules {
  display: flex;
  margin: 12px 18px 0;
  align-items: flex-start;
  gap: 6px;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.course-learn__rules-copy {
  min-width: 0;
}

.course-learn__refund {
  display: flex;
  height: 44px;
  margin: 12px 16px 24px;
  align-items: center;
  justify-content: center;
  border: 1px solid #ffb27a;
  border-radius: 999px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 15px;
  font-weight: 500;
}
</style>
