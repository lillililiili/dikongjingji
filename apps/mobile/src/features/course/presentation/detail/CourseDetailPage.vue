<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { openSupportChat } from '@/features/support/application/support-entry'
import { ApiRequestError } from '@/shared/http/public-api-request'
import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'
import LineIcon from '@/shared/ui/LineIcon.vue'
import QualificationBadge from '@/shared/ui/QualificationBadge.vue'

import {
  COURSE_OPTION_NAME,
  courseOpeningLine,
  courseSpecRows,
  courseSummaryLine,
  formatCoursePrice,
  formatLessonLength,
  formatValidity,
} from '../../application/course-format'
import { describeCourseFailure } from '../../application/course-failures'
import { createCourseKey } from '../../application/course-keys'
import { courseApi } from '../../data/course-api-client'
import type { CourseDetail, CourseMine } from '../../domain/course'
import { buildCourseDetailUrl } from '../../navigation/course-routes'
import CourseBuySheet from './CourseBuySheet.vue'

// Course detail (board CourseFly): the product-detail layout with a course in it. One seller (the
// organization that runs the course), one 规格 (单人账号). 立即报名 opens the 报名 sheet; a course
// already open shows 去学习, one waiting for payment 继续支付.
const props = defineProps<{
  courseId: string | null
  accessToken: string | null
  // Changes whenever the page is shown again (back from the cashier or the login page).
  refreshKey: number
  // Opened from a card's 立即报名, or back from the login page: show the sheet once loaded.
  openBuy: boolean
}>()

const emit = defineEmits<{
  back: []
  login: []
  trial: [courseId: string]
  learn: [enrollmentId: string]
  pay: [tradeOrderId: string]
}>()

type State = { kind: 'loading' } | { kind: 'ready'; detail: CourseDetail } | { kind: 'missing' } | { kind: 'error' }

const state = ref<State>({ kind: 'loading' })
const mine = ref<CourseMine | null>(null)
const sheetOpen = ref(false)
const submitting = ref(false)
const buyMessage = ref<string | null>(null)
const open = ref<'spec' | 'pack' | 'intro' | ''>('spec')
const slide = ref(0)
const shareTarget = ref<ShareTarget | null>(null)
let idempotencyKey = createCourseKey()
let requestVersion = 0

const detail = computed(() => (state.value.kind === 'ready' ? state.value.detail : null))
const coverUrl = computed(() => (detail.value
  ? detail.value.coverImage?.url ?? null
  : null))
const slides = computed(() => {
  const pictures = [coverUrl.value, ...(detail.value?.gallery.map((image) => image.url) ?? [])]
  return pictures.filter((url): url is string => Boolean(url))
})
const specs = computed(() => (detail.value ? courseSpecRows(detail.value) : []))
const includeCount = computed(() => detail.value?.includes.reduce((sum, item) => sum + item.quantity, 0) ?? 0)
const tags = computed(() => {
  if (!detail.value) return []
  return [
    { text: '线上', tone: 'orange' },
    { text: `${detail.value.lessonCount} 课时`, tone: 'orange' },
    ...(detail.value.trialLessonCount > 0 ? [{ text: '支持试听', tone: 'green' }] : []),
  ]
})
const parties = computed(() => {
  if (!detail.value) return []
  const name = detail.value.organization.name
  return [
    { key: '开课方', value: name },
    { key: '收款方', value: `${name} · 经平台支付渠道` },
    { key: '开通方式', value: '线上 · 付款后立即开通' },
  ]
})
const guarantees = [
  { key: '7 天内未学可退', value: '付款后 7 天内、还没开始学习付费课时，可在线全额退款' },
  { key: '平台协助', value: '与开课方协商不成时，可联系平台客服协助沟通' },
  { key: '发票', value: '由开课方开具，平台不代开' },
]

// The main button, by what this account already has.
const action = computed<{ kind: 'buy' | 'learn' | 'pay' | 'none'; text: string }>(() => {
  if (mine.value?.status === 'ACTIVE' && mine.value.enrollmentId) return { kind: 'learn', text: '去学习' }
  if (mine.value?.status === 'PENDING_PAYMENT' && mine.value.tradeOrderId) return { kind: 'pay', text: '继续支付' }
  if (!detail.value?.price) return { kind: 'none', text: '随商品附带' }
  if (!detail.value.purchasable) return { kind: 'none', text: '暂未开通在线报名' }
  return { kind: 'buy', text: '立即报名' }
})

async function load(): Promise<void> {
  const version = ++requestVersion
  if (!props.courseId) {
    state.value = { kind: 'missing' }
    return
  }
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await courseApi.loadCourse(props.courseId)
    if (version !== requestVersion) return
    state.value = { kind: 'ready', detail: loaded }
  } catch (error) {
    if (version !== requestVersion) return
    state.value = error instanceof ApiRequestError && error.status === 404 ? { kind: 'missing' } : { kind: 'error' }
    return
  }
  mine.value = null
  if (props.accessToken) {
    try {
      const found = await courseApi.loadMine(props.accessToken, props.courseId)
      if (version === requestVersion) mine.value = found
    } catch {
      // Without it the page still sells the course; the server refuses a second purchase anyway.
    }
  }
  if (props.openBuy && action.value.kind === 'buy') sheetOpen.value = true
}

function onAction(): void {
  if (action.value.kind === 'learn' && mine.value?.enrollmentId) emit('learn', mine.value.enrollmentId)
  else if (action.value.kind === 'pay' && mine.value?.tradeOrderId) emit('pay', mine.value.tradeOrderId)
  else if (action.value.kind === 'buy') {
    buyMessage.value = null
    sheetOpen.value = true
  }
}

async function submit(): Promise<void> {
  const current = detail.value
  if (!current?.price?.amountMinor || submitting.value) return
  if (!props.accessToken) {
    emit('login')
    return
  }
  submitting.value = true
  buyMessage.value = null
  try {
    const enrollment = await courseApi.enroll(props.accessToken, idempotencyKey, current.courseId, current.price.amountMinor)
    idempotencyKey = createCourseKey()
    sheetOpen.value = false
    if (enrollment.status === 'ACTIVE') emit('learn', enrollment.enrollmentId)
    else if (enrollment.payment) emit('pay', enrollment.payment.tradeOrderId)
  } catch (error) {
    const failure = describeCourseFailure(error)
    if (failure.signIn) {
      emit('login')
      return
    }
    buyMessage.value = failure.message
    if (failure.reload) {
      idempotencyKey = createCourseKey()
      void load()
    }
  } finally {
    submitting.value = false
  }
}

function openTrial(): void {
  if (detail.value) emit('trial', detail.value.courseId)
}

function consult(): void {
  if (detail.value?.organization.organizationId) {
    openSupportChat({ kind: 'PROVIDER', organizationId: detail.value.organization.organizationId })
  }
}

function share(): void {
  const current = detail.value
  if (!current) return
  shareTarget.value = {
    subject: '课程',
    title: current.title,
    subtitle: `${formatCoursePrice(current.price)} · ${courseSummaryLine(current)}`,
    owner: current.organization.name,
    imageUrl: coverUrl.value,
    path: buildCourseDetailUrl(current.courseId),
  }
}

function toggle(section: 'spec' | 'pack' | 'intro'): void {
  open.value = open.value === section ? '' : section
}

watch(() => [props.courseId, props.accessToken, props.refreshKey], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="course-detail" data-testid="course-detail">
    <view v-if="state.kind === 'loading'" class="course-detail__state" data-testid="course-detail-loading">
      <text>正在加载…</text>
    </view>
    <view v-else-if="state.kind === 'missing' || state.kind === 'error'" class="course-detail__state" data-testid="course-detail-missing">
      <text class="course-detail__state-title">{{ state.kind === 'missing' ? '课程不存在或已下架' : '课程加载失败' }}</text>
      <button v-if="state.kind === 'error'" class="course-detail__state-action" @click="load">重新加载</button>
      <button class="course-detail__state-link" @click="emit('back')">返回</button>
    </view>

    <template v-else-if="detail">
      <scroll-view class="course-detail__scroll" scroll-y>
        <view class="course-detail__hero">
          <swiper class="course-detail__swiper" :current="slide" @change="slide = $event.detail.current">
            <swiper-item v-for="url in slides" :key="url">
              <image class="course-detail__hero-image" :src="url" mode="aspectFill" :alt="detail.title" />
            </swiper-item>
          </swiper>
          <view v-if="slides.length === 0" class="course-detail__hero-empty">课程图（待授权素材）</view>
          <view class="course-detail__hero-bar">
            <button class="course-detail__round" aria-label="返回" data-testid="course-detail-back" @click="emit('back')">
              <text class="course-detail__round-dot"><LineIcon name="back" :size="20" :stroke-width="2" color="#ffffff" /></text>
            </button>
            <button class="course-detail__round course-detail__round--push" aria-label="分享" @click="share">
              <text class="course-detail__round-dot"><LineIcon name="share" :size="20" :stroke-width="2" color="#ffffff" /></text>
            </button>
          </view>
          <text v-if="slides.length > 0" class="course-detail__counter">{{ slide + 1 }}/{{ slides.length }}</text>
        </view>

        <view class="course-detail__card course-detail__card--lift">
          <view class="course-detail__price-row">
            <text class="course-detail__price" data-testid="course-detail-price">{{ formatCoursePrice(detail.price) }}</text>
            <text class="course-detail__summary">{{ courseSummaryLine(detail) }}</text>
          </view>
          <text class="course-detail__title">{{ detail.title }}</text>
          <text v-if="detail.subtitle" class="course-detail__subtitle">{{ detail.subtitle }}</text>
          <view class="course-detail__tags">
            <text v-for="tag in tags" :key="tag.text" :class="['course-detail__tag', `course-detail__tag--${tag.tone}`]">{{ tag.text }}</text>
          </view>
          <button class="course-detail__row" data-testid="course-detail-opening" @click="onAction">
            <text class="course-detail__row-key">开通</text>
            <text class="course-detail__row-value">{{ courseOpeningLine(detail) }}</text>
            <LineIcon name="chevronRight" :size="16" :stroke-width="2" color="#91a0b4" />
          </button>
        </view>

        <view v-if="detail.trialLessonCount > 0" class="course-detail__try" data-testid="course-detail-try">
          <view class="course-detail__try-head">
            <text class="course-detail__try-title">先试听，再决定</text>
            <text class="course-detail__try-note">试听不会自动下单</text>
          </view>
          <view class="course-detail__try-actions">
            <button class="course-detail__try-pill course-detail__try-pill--on" data-testid="course-detail-trial" @click="openTrial">
              课程试听
            </button>
          </view>
          <text class="course-detail__try-copy">先试看前 {{ detail.trialLessonCount }} 节课程内容，再决定是否报名</text>
        </view>

        <view class="course-detail__card">
          <view class="course-detail__head">
            <text class="course-detail__h2">开课方</text>
            <text class="course-detail__head-note">报名前可咨询</text>
          </view>
          <view class="course-detail__seller" role="radio" aria-checked="true">
            <text class="course-detail__tick">✓</text>
            <view class="course-detail__seller-copy">
              <view class="course-detail__seller-name">
                <text>{{ detail.organization.name }}</text>
                <QualificationBadge v-if="detail.organization.qualificationReviewed" />
                <text v-else-if="detail.instructor?.title" class="course-detail__kind">{{ detail.instructor.title }}</text>
              </view>
              <text class="course-detail__seller-meta">购买后{{ detail.validityDays ? ` ${detail.validityDays} 天内` : '' }}随时学</text>
            </view>
            <text class="course-detail__seller-price">{{ formatCoursePrice(detail.price) }}</text>
          </view>
        </view>

        <button class="course-detail__card course-detail__picked" @click="onAction">
          <text class="course-detail__row-key">已选</text>
          <text class="course-detail__row-value">{{ COURSE_OPTION_NAME }} · 1 份</text>
          <LineIcon name="chevronRight" :size="16" :stroke-width="2" color="#91a0b4" />
        </button>

        <view class="course-detail__card">
          <view class="course-detail__head">
            <text class="course-detail__h2">交易主体</text>
          </view>
          <view class="course-detail__grid">
            <template v-for="party in parties" :key="party.key">
              <text class="course-detail__grid-key">{{ party.key }}</text>
              <text>{{ party.value }}</text>
            </template>
          </view>
        </view>

        <view class="course-detail__card">
          <text class="course-detail__h2">售后与保障</text>
          <view v-for="item in guarantees" :key="item.key" class="course-detail__guarantee">
            <LineIcon name="checkCircle" :size="16" :stroke-width="2" color="#159a70" />
            <text><text class="course-detail__guarantee-key">{{ item.key }}</text><text class="course-detail__muted"> {{ item.value }}</text></text>
          </view>
        </view>

        <view class="course-detail__card course-detail__card--sections">
          <button class="course-detail__section" :aria-expanded="open === 'spec'" @click="toggle('spec')">
            <text class="course-detail__h2">参数</text>
            <text class="course-detail__count">{{ specs.length }} 项</text>
            <LineIcon :name="open === 'spec' ? 'chevronUp' : 'chevronDown'" :size="16" :stroke-width="2" color="#91a0b4" />
          </button>
          <view v-if="open === 'spec'" class="course-detail__section-body">
            <view class="course-detail__table">
              <template v-for="row in specs" :key="row.label">
                <text class="course-detail__table-key">{{ row.label }}</text>
                <text class="course-detail__table-value">{{ row.value }}</text>
              </template>
            </view>
            <text class="course-detail__fine">参数由开课方填报，平台审核后展示</text>
          </view>

          <button class="course-detail__section course-detail__section--line" :aria-expanded="open === 'pack'" data-testid="course-detail-pack" @click="toggle('pack')">
            <text class="course-detail__h2">课程包含</text>
            <text class="course-detail__count">{{ includeCount ? `${includeCount} 件` : `${detail.lessonCount} 节` }}</text>
            <LineIcon :name="open === 'pack' ? 'chevronUp' : 'chevronDown'" :size="16" :stroke-width="2" color="#91a0b4" />
          </button>
          <view v-if="open === 'pack'" class="course-detail__section-body">
            <view v-if="detail.includes.length" class="course-detail__list">
              <view v-for="item in detail.includes" :key="item.name" class="course-detail__list-row">
                <text class="course-detail__list-name">{{ item.name }}</text>
                <text class="course-detail__muted">×{{ item.quantity }}</text>
              </view>
            </view>
            <view v-for="chapter in detail.chapters" :key="chapter.chapterId" class="course-detail__chapter">
              <text class="course-detail__chapter-title">{{ chapter.title }}</text>
              <view v-for="lesson in chapter.lessons" :key="lesson.lessonId" class="course-detail__lesson">
                <text class="course-detail__lesson-no">{{ String(lesson.no).padStart(2, '0') }}</text>
                <text class="course-detail__lesson-title">{{ lesson.title }}</text>
                <text v-if="lesson.trial" class="course-detail__lesson-trial">试听</text>
                <text class="course-detail__muted">{{ formatLessonLength(lesson.durationSeconds) }}</text>
              </view>
            </view>
          </view>

          <button class="course-detail__section course-detail__section--line" :aria-expanded="open === 'intro'" @click="toggle('intro')">
            <text class="course-detail__h2">图文介绍</text>
            <text class="course-detail__count">{{ detail.intro.length ? `${detail.intro.length} 段` : '待开课方上传' }}</text>
            <LineIcon :name="open === 'intro' ? 'chevronUp' : 'chevronDown'" :size="16" :stroke-width="2" color="#91a0b4" />
          </button>
          <view v-if="open === 'intro'" class="course-detail__section-body">
            <text v-for="(paragraph, index) in detail.intro" :key="index" class="course-detail__paragraph">{{ paragraph }}</text>
            <view v-if="detail.intro.length === 0" class="course-detail__intro-empty">开课方图文详情（待开课方上传）</view>
          </view>
        </view>
        <text class="course-detail__validity">有效期：{{ formatValidity(detail.validityDays) }}</text>
      </scroll-view>

      <view class="course-detail__bar">
        <button class="course-detail__bar-icon" data-testid="course-detail-consult" @click="consult">
          <LineIcon name="chat" :size="22" color="#536178" />
          <text>咨询</text>
        </button>
        <button
          v-if="detail.trialLessonCount > 0"
          class="course-detail__bar-trial"
          data-testid="course-detail-bar-trial"
          @click="openTrial"
        >
          先试听
        </button>
        <button
          :class="['course-detail__bar-main', { 'course-detail__bar-main--off': action.kind === 'none' }]"
          data-testid="course-detail-action"
          :data-action="action.kind"
          :disabled="action.kind === 'none'"
          @click="onAction"
        >
          {{ action.text }}
        </button>
      </view>

      <CourseBuySheet
        v-if="sheetOpen"
        :detail="detail"
        :cover-url="coverUrl"
        :signed-in="Boolean(accessToken)"
        :submitting="submitting"
        :message="buyMessage"
        @close="sheetOpen = false"
        @submit="submit"
      />
      <ShareSheet :target="shareTarget" @close="shareTarget = null" />
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the board CourseFly (390 wide). */
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

.course-detail {
  display: flex;
  height: 100vh;
  flex-direction: column;
  background: #f5f7fc;
  color: #202c42;
}

.course-detail__state {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #627187;
}

.course-detail__state-title {
  color: #536178;
  font-size: 15px;
  font-weight: 600;
}

.course-detail__state-action {
  min-height: 44px;
  padding: 0 20px;
  border-radius: 22px;
  color: #ffffff;
  background: #ff6a00;
}

.course-detail__state-link {
  min-height: 44px;
  color: #ba470b;
}

.course-detail__scroll {
  min-height: 0;
  flex: 1 1 auto;
}

.course-detail__hero {
  position: relative;
  height: 300px;
  background: #eef0f3;
}

.course-detail__swiper,
.course-detail__hero-image {
  width: 100%;
  height: 300px;
}

.course-detail__hero-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #738096;
  font-size: 12px;
}

.course-detail__hero-bar {
  position: absolute;
  top: calc(var(--status-bar-height, 0px) + 4px);
  right: 8px;
  left: 8px;
  display: flex;
  align-items: center;
}

.course-detail__round {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.course-detail__round--push {
  margin-left: auto;
}

.course-detail__round-dot {
  display: flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
}

.course-detail__counter {
  position: absolute;
  right: 12px;
  bottom: 26px;
  padding: 2px 8px;
  border-radius: 10px;
  color: #ffffff;
  background: rgba(0, 0, 0, 0.4);
  font-size: 12px;
}

.course-detail__card {
  display: flex;
  margin: 12px 16px 0;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
  box-sizing: border-box;
}

.course-detail__card--lift {
  position: relative;
  margin-top: -24px;
  gap: 6px;
}

.course-detail__price-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.course-detail__price {
  color: #e85400;
  font-size: 26px;
  font-weight: 700;
}

.course-detail__summary {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
}

.course-detail__title {
  font-size: 18px;
  font-weight: 700;
}

.course-detail__subtitle {
  color: #627187;
  font-size: 13px;
}

.course-detail__tags {
  display: flex;
  gap: 6px;
}

.course-detail__tag {
  padding: 2px 6px;
  border-radius: 6px;
  font-size: 11px;
}

.course-detail__tag--orange {
  color: #ba470b;
  background: #fff0e3;
}

.course-detail__tag--green {
  color: #11804a;
  background: #e2f6ed;
}

.course-detail__row,
.course-detail__picked {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 12px;
}

.course-detail__row {
  margin: 4px 0 -8px;
  border-top: 1px solid #f0f1f3;
}

.course-detail__picked {
  min-height: 48px;
  padding: 0 14px;
  flex-direction: row;
}

.course-detail__row-key {
  width: 40px;
  flex-shrink: 0;
  color: #627187;
  font-size: 13px;
}

.course-detail__row-value {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.course-detail__try {
  display: flex;
  margin: 12px 16px 0;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #eefaf3;
}

.course-detail__try-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.course-detail__try-title {
  color: #11804a;
  font-size: 16px;
  font-weight: 700;
}

.course-detail__try-note,
.course-detail__try-copy {
  color: #536178;
  font-size: 12px;
}

.course-detail__try-actions {
  display: flex;
  gap: 8px;
}

.course-detail__try-pill {
  display: flex;
  min-height: 36px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 13px;
}

.course-detail__try-pill--on {
  color: #11804a;
  font-weight: 500;
}

.course-detail__head {
  display: flex;
  align-items: baseline;
}

.course-detail__h2 {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.course-detail__head-note {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
}

.course-detail__seller {
  display: flex;
  padding: 12px;
  align-items: center;
  gap: 10px;
  border: 1.5px solid #ff6a00;
  border-radius: 10px;
  background: #fffaf5;
}

.course-detail__tick {
  display: flex;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #ffffff;
  background: #ff6a00;
  font-size: 12px;
}

.course-detail__seller-copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 3px;
}

.course-detail__seller-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 500;
}

.course-detail__kind {
  padding: 0 5px;
  border: 1px solid #e5ebf3;
  border-radius: 4px;
  color: #536178;
  font-size: 11px;
  font-weight: 400;
}

.course-detail__seller-meta {
  color: #627187;
  font-size: 12px;
}

.course-detail__seller-price {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
}

.course-detail__grid {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 8px 10px;
  font-size: 13px;
}

.course-detail__grid-key,
.course-detail__muted {
  color: #627187;
}

.course-detail__guarantee {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 1.5;
}

.course-detail__guarantee-key {
  font-weight: 500;
}

.course-detail__card--sections {
  padding: 0 14px;
  gap: 0;
}

.course-detail__section {
  display: flex;
  width: 100%;
  min-height: 52px;
  align-items: center;
  gap: 8px;
}

.course-detail__section--line {
  border-top: 1px solid #f0f1f3;
}

.course-detail__count {
  color: #738096;
  font-size: 13px;
  white-space: nowrap;
}

.course-detail__section-body {
  display: flex;
  padding-bottom: 14px;
  flex-direction: column;
  gap: 10px;
}

.course-detail__table {
  display: grid;
  grid-template-columns: 96px 1fr;
  overflow: hidden;
  border: 1px solid #f0f1f3;
  border-radius: 8px;
  font-size: 13px;
}

.course-detail__table-key,
.course-detail__table-value {
  padding: 8px 10px;
  border-bottom: 1px solid #f0f1f3;
}

.course-detail__table-key {
  color: #627187;
  background: #fafafb;
}

.course-detail__fine {
  color: #738096;
  font-size: 12px;
}

.course-detail__list {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #f0f1f3;
  border-radius: 8px;
  font-size: 13px;
}

.course-detail__list-row {
  display: flex;
  padding: 8px 10px;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid #f0f1f3;
}

.course-detail__list-name {
  flex: 1 1 auto;
}

.course-detail__chapter {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.course-detail__chapter-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 22px;
}

.course-detail__lesson {
  display: flex;
  min-height: 32px;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.course-detail__lesson-no {
  color: #738096;
}

.course-detail__lesson-title {
  min-width: 0;
  flex: 1 1 auto;
}

.course-detail__lesson-trial {
  padding: 0 5px;
  border-radius: 4px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 11px;
}

.course-detail__paragraph {
  font-size: 14px;
  line-height: 1.7;
}

.course-detail__intro-empty {
  display: flex;
  min-height: 160px;
  padding: 16px;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  color: #627187;
  background: #eef2f8;
  font-size: 13px;
  box-sizing: border-box;
}

.course-detail__validity {
  display: block;
  padding: 12px 16px 16px;
  color: #738096;
  font-size: 12px;
}

.course-detail__bar {
  display: flex;
  padding: 8px 12px calc(8px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 6px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.course-detail__bar-icon {
  display: flex;
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #536178;
  font-size: 11px;
  line-height: 15px;
}

.course-detail__bar-trial {
  display: flex;
  height: 44px;
  padding: 0 14px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  font-size: 15px;
  font-weight: 500;
  box-sizing: border-box;
  white-space: nowrap;
}

.course-detail__bar-main {
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

.course-detail__bar-main--off {
  color: #738096;
  background: #eef2f8;
}
</style>
