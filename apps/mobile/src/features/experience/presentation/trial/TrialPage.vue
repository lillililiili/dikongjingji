<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import { isUnauthorizedError } from '../../application/simulation-sync'
import {
  formatTrialLength,
  formatTrialMoment,
  formatTrialRemaining,
  summariseTrial,
  trialRemainingMs,
  trialStage,
} from '../../application/trial-view'
import { experienceApi } from '../../data/experience-api-client'
import { experienceRecordApi } from '../../data/experience-record-api-client'
import { trialPicture } from '../../data/preview/simulation-preview-pictures'
import type { ExperienceItemDetail, ExperienceRecord } from '../../domain/experience-record'

// Board SoftwareTrial (390 wide): 启动中 → 试用中 → 已到期 / 已结束. The software would run in an
// isolated environment and stream to the phone (a design assumption of the board); no streaming
// service is connected yet, so the frame is a sample picture and says so. The course preview
// (board CourseTrial is empty) uses the same page with its own words.
//
// Opening the page starts a trial, or goes back to the one still running; with a record it shows
// that record. Starting and ending are recorded by the server; the countdown is the server's
// expiresAt.
const props = defineProps<{
  experienceId: string
  // A record to show (opened from 我的体验); otherwise the page starts or resumes one.
  recordId: string | null
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  openRecords: []
  openProduct: [productId: string]
  openChannel: []
}>()

type LoadState =
  | { kind: 'loading' }
  | { kind: 'guest' }
  | { kind: 'ready' }
  | { kind: 'error' }
  | { kind: 'unavailable' }

const BOOT_MS = 2400

const state = ref<LoadState>({ kind: 'loading' })
const item = ref<ExperienceItemDetail | null>(null)
const record = ref<ExperienceRecord | null>(null)
const now = ref(Date.now())
const booting = ref(false)
const bootStep = ref(0)
const sheetOpen = ref(false)
const ending = ref(false)
// The record this page is on, once known; a refresh reads it again instead of starting another.
let currentRecordId: string | null = null
let requestVersion = 0
let clock: ReturnType<typeof setInterval> | null = null
let bootTimer: ReturnType<typeof setTimeout> | null = null
let expiryRead = false

const course = computed(() => item.value?.type === 'COURSE_TRIAL')
const word = computed(() => (course.value ? '试听' : '试用'))
const pageTitle = computed(() => (course.value ? '课程试听' : '软件试用'))
const stage = computed(() => (record.value ? trialStage(record.value) : null))
const running = computed(() => stage.value === 'ACTIVE' && !booting.value)
const over = computed(() => stage.value === 'ENDED' || stage.value === 'EXPIRED')
const remaining = computed(() => (record.value ? trialRemainingMs(record.value, now.value) : 0))
const summary = computed(() => (record.value ? summariseTrial(record.value, now.value) : null))
const cardPicture = computed(() =>
  item.value?.coverImage?.url ?? trialPicture(course.value ? 'course' : 'softwareCard'))
const screenPicture = computed(() => trialPicture('softwareScreen'))
const minutesText = computed(() => (item.value?.trialMinutes ? formatTrialLength(item.value.trialMinutes * 60000) : ''))
const BOOT_STEPS = ['分配隔离环境', '加载软件', '连接画面']
const bootSteps = computed(() => BOOT_STEPS.map((label, index) => ({
  label,
  done: index < bootStep.value,
  current: index === bootStep.value,
  state: index < bootStep.value ? '已完成' : index === bootStep.value ? '进行中' : '等待中',
})))
const rules = computed(() => (course.value
  ? [
    `试听期 ${minutesText.value}（示例时长），期内可以反复看`,
    '课程素材待接入，接入后在这里观看',
    '到期或结束后可以重新开始试听',
    '试听不会自动购买课程，是否购买由你决定',
  ]
  : [
    `单次试用 ${minutesText.value}，到期自动结束（示例时长）`,
    '软件运行在隔离环境，不接触你的设备与数据',
    '试用中的作品不保存，结束后环境即销毁',
    '体验结束不会自动下单，是否购买由你决定',
  ]))
const CLOCK = 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2'
const CROSS = 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM9 9l6 6M15 9l-6 6'

function stopBoot(): void {
  if (bootTimer) clearTimeout(bootTimer)
  bootTimer = null
  booting.value = false
}

// Board 启动中: three steps, then the software frame. Shown only for a trial started just now.
function boot(): void {
  if (course.value) return
  booting.value = true
  bootStep.value = 0
  const advance = () => {
    bootStep.value += 1
    if (bootStep.value >= BOOT_STEPS.length) {
      stopBoot()
      return
    }
    bootTimer = setTimeout(advance, BOOT_MS / BOOT_STEPS.length)
  }
  bootTimer = setTimeout(advance, BOOT_MS / BOOT_STEPS.length)
}

async function findOrStart(token: string): Promise<{ record: ExperienceRecord; started: boolean }> {
  const target = currentRecordId ?? props.recordId
  if (target) return { record: await experienceRecordApi.read(token, target), started: false }
  const latest = (await experienceRecordApi.list(token, { experienceId: props.experienceId, page: 1, size: 1 })).items[0]
  if (latest && latest.status === 'ACTIVE') return { record: latest, started: false }
  const started = await experienceRecordApi.startTrial(token, props.experienceId)
  // A trial that began seconds ago was started by this tap (the server answers 200 for one already running).
  return { record: started, started: Date.now() - new Date(started.startedAt).getTime() < 10000 }
}

async function load(): Promise<void> {
  const requested = ++requestVersion
  if (!record.value) state.value = { kind: 'loading' }
  try {
    if (!item.value || item.value.experienceId !== props.experienceId) {
      const loaded = await experienceApi.loadExperienceItem(props.experienceId)
      if (requested !== requestVersion) return
      if ((loaded.type !== 'SOFTWARE_TRIAL' && loaded.type !== 'COURSE_TRIAL') || !loaded.trialMinutes) {
        state.value = { kind: 'unavailable' }
        return
      }
      item.value = loaded
    }
    const token = props.accessToken
    if (!token) {
      state.value = { kind: 'guest' }
      return
    }
    const found = await findOrStart(token)
    if (requested !== requestVersion) return
    record.value = found.record
    currentRecordId = found.record.recordId
    expiryRead = false
    now.value = Date.now()
    state.value = { kind: 'ready' }
    if (found.started) boot()
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorizedError(error)) {
      emit('login')
      return
    }
    state.value = { kind: 'error' }
  }
}

async function confirmEnd(): Promise<void> {
  const token = props.accessToken
  if (!token || !record.value || ending.value) return
  ending.value = true
  try {
    record.value = await experienceRecordApi.endTrial(token, record.value.recordId)
    sheetOpen.value = false
    stopBoot()
  } catch (error) {
    if (isUnauthorizedError(error)) emit('login')
    else uni.showToast({ title: '没有结束成功，请再试一次', icon: 'none' })
  } finally {
    ending.value = false
  }
}

// At zero the server is asked once: it says EXPIRED, and the page shows the end.
async function readAtExpiry(): Promise<void> {
  const token = props.accessToken
  if (!token || !record.value || expiryRead) return
  expiryRead = true
  try {
    record.value = await experienceRecordApi.read(token, record.value.recordId)
    stopBoot()
  } catch {
    // The countdown stays at zero; the next visit reads it again.
  }
}

watch(() => [props.refreshKey, props.accessToken, props.experienceId], () => { void load() }, { immediate: true })

onMounted(() => {
  clock = setInterval(() => {
    now.value = Date.now()
    if (stage.value === 'ACTIVE' && remaining.value <= 0) void readAtExpiry()
  }, 1000)
})

onBeforeUnmount(() => {
  if (clock) clearInterval(clock)
  stopBoot()
})
</script>

<template>
  <view class="trial" :data-testid="`trial-page-${course ? 'course' : 'software'}`">
    <view class="trial__bar">
      <button class="trial__back" data-testid="trial-back" aria-label="返回" @click="emit('back')">
        <LineIcon name="back" :size="22" color="#202c42" :stroke-width="2" />
      </button>
      <text class="trial__title">{{ pageTitle }}</text>
      <text v-if="running" class="trial__timer" role="timer" data-testid="trial-remaining">{{ formatTrialRemaining(remaining) }}</text>
    </view>

    <view class="trial__body">
      <view v-if="item" class="trial__card">
        <image v-if="cardPicture" class="trial__card-picture" :src="cardPicture" :alt="item.title" mode="aspectFill" />
        <view v-else class="trial__card-picture" />
        <view class="trial__card-copy">
          <text class="trial__card-title">{{ item.title }}</text>
          <text v-if="item.summary" class="trial__card-note">{{ item.summary }}</text>
          <view class="trial__chips">
            <text class="trial__chip trial__chip--green">{{ course ? '课程试听 · 图文预览' : '软件试用 · 隔离环境' }}</text>
            <text v-if="!course" class="trial__chip">不安装到手机</text>
          </view>
        </view>
      </view>

      <view v-if="state.kind === 'loading'" class="trial__panel trial__panel--center" aria-label="正在加载">
        <view class="trial__skeleton" />
      </view>
      <view v-else-if="state.kind === 'error'" class="trial__panel trial__panel--center" data-testid="trial-error">
        <text class="trial__note">没有加载出来，请稍后重试</text>
        <button class="trial__outline" @click="load">重新加载</button>
      </view>
      <view v-else-if="state.kind === 'unavailable'" class="trial__panel trial__panel--center" data-testid="trial-unavailable">
        <text class="trial__note">这个项目暂时不能{{ word }}</text>
      </view>
      <view v-else-if="state.kind === 'guest'" class="trial__panel trial__panel--center" data-testid="trial-guest">
        <text class="trial__big">登录后开始{{ word }}</text>
        <text class="trial__note">{{ word }}的开始和到期时间记在你的账户里</text>
        <button class="trial__primary trial__primary--inline" data-testid="trial-login" @click="emit('login')">去登录</button>
      </view>

      <template v-else-if="record">
        <template v-if="booting">
          <view class="trial__panel trial__panel--center" data-testid="trial-booting">
            <view class="trial__ring"><LineIcon d="M12 3a9 9 0 1 0 9 9" :size="28" color="#ff6a00" :stroke-width="2" /></view>
            <text class="trial__big">正在启动试用环境</text>
            <text class="trial__note">启动好后自动进入软件画面</text>
          </view>
          <view class="trial__steps">
            <view v-for="step in bootSteps" :key="step.label" class="trial__step">
              <view :class="['trial__dot', { 'trial__dot--done': step.done, 'trial__dot--current': step.current }]">
                <LineIcon v-if="step.done" name="check" :size="11" color="#ffffff" :stroke-width="4" />
              </view>
              <text :class="['trial__step-label', { 'trial__step-label--current': step.current }]">{{ step.label }}</text>
              <text :class="['trial__step-state', { 'trial__step-state--current': step.current }]">{{ step.state }}</text>
            </view>
          </view>
        </template>

        <template v-else-if="running">
          <view v-if="!course" class="trial__screen" data-testid="trial-screen">
            <image v-if="screenPicture" class="trial__screen-picture" :src="screenPicture" alt="软件画面" mode="aspectFill" />
            <text class="trial__screen-tag">软件画面 · 示例</text>
            <view class="trial__screen-foot">
              <text>串流服务待接入</text>
              <text class="trial__screen-right">作品只在本次试用内有效</text>
            </view>
          </view>
          <view v-else class="trial__panel" data-testid="trial-course-running">
            <text class="trial__big">试听中 · {{ formatTrialRemaining(remaining) }}</text>
            <text class="trial__note">{{ record.expiresAt ? `${formatTrialMoment(record.expiresAt)} 到期` : '' }}</text>
          </view>
          <view class="trial__panel">
            <text class="trial__heading">{{ word }}说明</text>
            <view v-for="rule in rules" :key="rule" class="trial__rule">
              <LineIcon name="check" :size="16" color="#159a70" :stroke-width="2.2" />
              <text>{{ rule }}</text>
            </view>
          </view>
        </template>

        <template v-else-if="over && summary">
          <view class="trial__panel trial__panel--center" data-testid="trial-over">
            <view class="trial__ring trial__ring--grey">
              <LineIcon :d="stage === 'ENDED' ? CROSS : CLOCK" :size="28" color="#738096" :stroke-width="2" />
            </view>
            <text class="trial__big" data-testid="trial-over-title">{{ stage === 'ENDED' ? `${word}已结束` : `${word}已到期` }}</text>
            <text class="trial__note">
              {{ stage === 'ENDED' ? `你已结束本次${word}` : `${minutesText}${word}时间已用完（示例时长）` }}{{ course ? '' : '，隔离环境已销毁' }}
            </text>
          </view>
          <view class="trial__panel">
            <text class="trial__heading">本次{{ word }}</text>
            <view class="trial__facts">
              <text class="trial__fact-key">开始</text><text>{{ formatTrialMoment(record.startedAt) }}</text>
              <text class="trial__fact-key">结束</text><text>{{ summary.endLine }}</text>
              <text class="trial__fact-key">时长</text><text>{{ summary.lengthText }}</text>
              <text class="trial__fact-key">再次{{ word }}</text><text>回到体验频道再点一次即可重新开始</text>
            </view>
            <text class="trial__fine">{{ course ? '体验结束不会自动下单' : '试用中的作品不保存，环境已销毁；体验结束不会自动下单' }}</text>
          </view>
        </template>
      </template>

      <button class="trial__records" data-testid="trial-records" @click="emit('openRecords')">
        <text class="trial__records-text">本次{{ word }}记入“我的体验记录”</text>
        <text class="trial__records-go">查看 ›</text>
      </button>
    </view>

    <view class="trial__actions">
      <button v-if="booting" class="trial__outline-wide" @click="emit('back')">取消启动</button>
      <button v-else-if="running" class="trial__outline-wide" data-testid="trial-end" @click="sheetOpen = true">结束{{ word }}</button>
      <button v-else class="trial__outline-wide" data-testid="trial-channel" @click="emit('openChannel')">返回体验频道</button>
      <button
        v-if="item?.productId"
        class="trial__primary"
        data-testid="trial-product"
        @click="emit('openProduct', item.productId)"
      >
        查看关联商品
      </button>
    </view>

    <template v-if="sheetOpen && running">
      <view class="trial__mask" aria-label="关闭" @click="sheetOpen = false" />
      <view class="trial__sheet" role="dialog" aria-modal="true" :aria-label="`结束${word}`" data-testid="trial-end-sheet">
        <text class="trial__sheet-title">结束这次{{ word }}？</text>
        <text class="trial__sheet-copy">
          {{ course ? `结束后要重新开始试听；剩余的时间不会保留。` : `结束后隔离环境立即销毁，试用中的作品不会保存；剩余的时间不会保留。` }}
        </text>
        <view class="trial__sheet-actions">
          <button class="trial__outline-wide" @click="sheetOpen = false">继续{{ word }}</button>
          <button class="trial__primary" data-testid="trial-end-confirm" :disabled="ending" @click="confirmEnd">结束{{ word }}</button>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.trial {
  position: relative;
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
}

button::after {
  border: 0;
}

.trial__bar {
  display: flex;
  padding: calc(8px + var(--status-bar-height)) 8px 8px;
  align-items: center;
  gap: 4px;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.trial__back {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.trial__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.trial__timer {
  margin: 0 8px 0 auto;
  padding: 4px 10px;
  border-radius: 999px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.trial__body {
  display: flex;
  padding: 12px 16px;
  flex: 1;
  flex-direction: column;
  gap: 10px;
}

.trial__card {
  display: flex;
  padding: 12px;
  gap: 12px;
  border-radius: 14px;
  background: #ffffff;
}

.trial__card-picture {
  width: 64px;
  height: 64px;
  flex: 0 0 auto;
  border-radius: 10px;
  background: #eef2f8;
}

.trial__card-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.trial__card-title {
  overflow: hidden;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trial__card-note {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trial__chips {
  display: flex;
  gap: 6px;
}

.trial__chip {
  padding: 1px 6px;
  border-radius: 6px;
  color: #536178;
  background: #eef2f8;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.trial__chip--green {
  color: #11804a;
  background: #e2f6ed;
}

.trial__panel {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 8px;
  border-radius: 14px;
  background: #ffffff;
}

.trial__panel--center {
  padding: 28px 16px 20px;
  align-items: center;
  text-align: center;
}

.trial__skeleton {
  width: 100%;
  height: 120px;
  border-radius: 12px;
  background: #edf0f3;
}

.trial__ring {
  display: flex;
  width: 56px;
  height: 56px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff0e3;
}

.trial__ring--grey {
  background: #eef2f8;
}

.trial__big {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.trial__note {
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.trial__heading {
  margin-bottom: 2px;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.trial__steps {
  display: flex;
  padding: 4px 14px;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
}

.trial__step {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.trial__step:first-child {
  border-top: 0;
}

.trial__dot {
  display: flex;
  width: 20px;
  height: 20px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 2px solid #c9d3e1;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.trial__dot--done {
  border-color: #ff6a00;
  background: #ff6a00;
}

.trial__dot--current {
  border-color: #ff6a00;
}

.trial__step-label {
  flex: 1;
  color: #738096;
  font-size: 14px;
}

.trial__step-label--current {
  color: #202c42;
}

.trial__step-state {
  color: #738096;
  font-size: 12px;
}

.trial__step-state--current {
  color: #ba470b;
}

.trial__screen {
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 14px;
  background: #ffffff;
}

.trial__screen-picture {
  display: block;
  width: 100%;
  height: 214px;
  background: #1c1d20;
}

.trial__screen-tag {
  position: absolute;
  top: 10px;
  left: 10px;
  padding: 3px 8px;
  border-radius: 6px;
  color: #ffffff;
  background: rgba(0, 0, 0, 0.55);
  font-size: 12px;
  line-height: 17px;
}

.trial__screen-foot {
  display: flex;
  padding: 10px 12px;
  align-items: center;
  gap: 8px;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.trial__screen-right {
  margin-left: auto;
  white-space: nowrap;
}

.trial__rule {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.trial__facts {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
  line-height: 19px;
}

.trial__fact-key {
  color: #627187;
  white-space: nowrap;
}

.trial__fine {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.trial__records {
  display: flex;
  min-height: 48px;
  padding: 0 14px;
  align-items: center;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
}

.trial__records-text {
  flex: 1;
  font-size: 14px;
  line-height: 20px;
  text-align: left;
}

.trial__records-go {
  color: #627187;
  font-size: 13px;
  white-space: nowrap;
}

.trial__actions {
  position: sticky;
  bottom: 0;
  display: flex;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  gap: 10px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.trial__outline,
.trial__outline-wide {
  display: flex;
  height: 46px;
  padding: 0 22px;
  align-items: center;
  justify-content: center;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  background: #ffffff;
  font-size: 15px;
  white-space: nowrap;
  box-sizing: border-box;
}

.trial__outline-wide {
  flex: 1 1 0;
}

.trial__primary {
  display: flex;
  height: 46px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
}

.trial__primary--inline {
  flex: none;
  margin-top: 6px;
  padding: 0 28px;
}

.trial__mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  background: rgba(0, 0, 0, 0.45);
}

.trial__sheet {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 21;
  display: flex;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 14px;
  border-radius: 20px 20px 0 0;
  background: #ffffff;
}

.trial__sheet-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.trial__sheet-copy {
  color: #536178;
  font-size: 14px;
  line-height: 20px;
}

.trial__sheet-actions {
  display: flex;
  gap: 10px;
}
</style>
