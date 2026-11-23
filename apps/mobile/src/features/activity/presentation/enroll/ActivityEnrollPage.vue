<script setup lang="ts">
import { SETTINGS_PREVIEW_ENABLED } from '@/features/settings/application/settings-preview'
import { buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  PAYMENT_UNAVAILABLE_TEXT,
  enrollmentTotalMinor,
  resolveActivityIntent,
  resolveEnrollmentPayMode,
  type ActivityIntent,
  type EnrollmentPayMode,
} from '../../application/activity-intent'
import { ACTIVITY_PREVIEW_ENABLED } from '../../application/activity-preview'
import {
  createEnrollmentKey,
  createEnrollmentKeyHolder,
  describeParticipantAge,
  describeParticipantState,
  enrollmentNoticeLead,
} from '../../application/enrollment-view'
import { activityApi } from '../../data/activity-api-client'
import { resolveActivityPreviewCover } from '../../data/preview/activity-preview-covers'
import { enrollmentApi } from '../../data/enrollment-api-client'
import type { ActivityDetail, ActivitySession } from '../../domain/activity'
import { MAX_ENROLLMENT_PARTICIPANTS, type Participant } from '../../domain/enrollment'
import {
  ACTIVITY_STATUS_LABELS,
  ACTIVITY_TYPE_LABELS,
  formatActivityAgeRange,
  formatActivityAmount,
  formatActivityDay,
  formatActivityTime,
} from '../shared/activity-format'

const props = defineProps<{
  activityId: string
  cityCode: string
  // The session picked on the detail page; the form starts on it when it can still be booked.
  sessionId: string
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  // Changes whenever the page is shown again, so people added on the way back appear.
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  managePeople: []
  // A free sign-up (or one already paid): its ticket.
  done: [enrollmentId: string]
  // A paid sign-up waiting for payment: the trade cashier of its order.
  pay: [tradeOrderId: string]
}>()

type FormState =
  | { kind: 'loading' }
  | { kind: 'ready'; activity: ActivityDetail }
  | { kind: 'unsupported' }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<FormState>({ kind: 'loading' })
const people = ref<Participant[]>([])
const peopleFailed = ref(false)
const selectedSessionId = ref<string | null>(null)
const pickedIds = ref<string[]>([])
const agreed = ref(false)
const submitting = ref(false)
const keys = createEnrollmentKeyHolder(createEnrollmentKey)
let requestVersion = 0
let peopleVersion = 0

const activity = computed(() => state.value.kind === 'ready' ? state.value.activity : null)
const payMode = computed<EnrollmentPayMode>(() =>
  activity.value ? resolveEnrollmentPayMode(activity.value) : 'UNSUPPORTED')
const paid = computed(() => payMode.value === 'ONLINE_PAYMENT' || payMode.value === 'PAYMENT_UNAVAILABLE')
const unitAmount = computed(() => (paid.value ? activity.value?.price.amountMinor ?? 0 : 0))
// Sessions that are over are not offered; ones that cannot be booked stay visible but disabled.
const sessions = computed<ActivitySession[]>(() =>
  activity.value?.sessions.filter((session) => session.status !== 'ENDED') ?? [])
const selectedSession = computed(() =>
  sessions.value.find((session) => session.sessionId === selectedSessionId.value) ?? null)
// The server's picture when it has one, else the sample picture of a preview build.
const coverUrl = computed(() =>
  activity.value
    ? activity.value.coverImage?.url ?? resolveActivityPreviewCover(activity.value.activityId)
    : null)
// Board EventSignup's pill above the title: "研学 · 适合 6–12 岁".
const typeLine = computed(() => {
  if (!activity.value) return ''
  const age = formatActivityAgeRange(activity.value.ageRange)
  const type = ACTIVITY_TYPE_LABELS[activity.value.type]
  return age ? `${type} · 适合 ${age}` : type
})
const rows = computed(() => (activity.value
  ? people.value
      .map((person) => {
        const personState = describeParticipantState(person, activity.value!.ageRange)
        const ageText = describeParticipantAge(person)
        return {
          person,
          selectable: personState.selectable,
          stateText: personState.text,
          ageText,
          label: ageText ? `${person.name} · ${ageText}` : person.name,
          relationText: person.relation === 'SELF' ? '本人' : '子女',
          // On a children's activity the account holder only comes along; the note under the list
          // says so (board EventSignup), so that row is left out.
          escortOnly: person.relation === 'SELF' && person.eligibility?.reason === 'OVER_MAX_AGE',
          picked: personState.selectable && pickedIds.value.includes(person.participantId),
        }
      })
      .filter((row) => !row.escortOnly)
  : []))
const picked = computed(() => rows.value.filter((row) => row.picked).map((row) => row.person))
const forChildren = computed(() => {
  const maxAge = activity.value?.ageRange.maxAge ?? null
  return maxAge !== null && maxAge < 18
})
const noticeLead = computed(() => enrollmentNoticeLead(picked.value))
const tooMany = computed(() =>
  selectedSession.value !== null && picked.value.length > selectedSession.value.seatsLeft)
// What the page shows and sends as expectedAmountMinor; null for a free activity.
const totalAmount = computed(() =>
  payMode.value === 'ONLINE_PAYMENT' ? enrollmentTotalMinor(unitAmount.value, picked.value.length) : null)
const priceText = computed(() => {
  if (!paid.value) return '免费'
  return picked.value.length > 0
    ? formatActivityAmount(enrollmentTotalMinor(unitAmount.value, picked.value.length))
    : `${formatActivityAmount(unitAmount.value)}/人`
})
const submitLabel = computed(() => {
  if (submitting.value) return '提交中…'
  if (payMode.value === 'PAYMENT_UNAVAILABLE') return '暂不能报名'
  return payMode.value === 'ONLINE_PAYMENT' ? '提交并支付' : '提交报名'
})
// The button stays orange like the board (user 2026-11-20, R5 #1); tapping it with something
// missing says what is missing. It is only greyed when the form cannot be sent at all.
const submitLocked = computed(() =>
  (payMode.value !== 'FREE' && payMode.value !== 'ONLINE_PAYMENT') || submitting.value)
const missingStep = computed<string | null>(() => {
  if (selectedSession.value?.status !== 'OPEN') return '请先选择一个场次'
  if (picked.value.length === 0) return '请选择参与人'
  if (picked.value.length > MAX_ENROLLMENT_PARTICIPANTS) return `一次最多报名 ${MAX_ENROLLMENT_PARTICIPANTS} 人`
  if (tooMany.value) return `本场只剩 ${selectedSession.value?.seatsLeft} 个名额`
  if (!agreed.value) return '请先阅读并同意报名须知'
  return null
})
const canSubmit = computed(() => !submitLocked.value && missingStep.value === null)
const countLine = computed(() => {
  if (payMode.value === 'PAYMENT_UNAVAILABLE') return PAYMENT_UNAVAILABLE_TEXT
  if (tooMany.value) return `本场只剩 ${selectedSession.value?.seatsLeft} 个名额`
  if (picked.value.length === 0) return '请选择参与人'
  return paid.value
    ? `${picked.value.length} 人 × ${formatActivityAmount(unitAmount.value)}`
    : `已选 ${picked.value.length} 人`
})

function isSessionOpen(session: ActivitySession): boolean {
  return session.status === 'OPEN'
}

// "11/15 上午 09:30" like the board.
function sessionLabel(session: ActivitySession): string {
  const day = formatActivityDay(session.startsAt).split(' ')[0]
  const time = formatActivityTime(session.startsAt)
  return session.label ? `${day} ${session.label} ${time}` : `${day} ${time}`
}

function sessionSeats(session: ActivitySession): string {
  return session.status === 'OPEN' ? `余 ${session.seatsLeft} 位` : ACTIVITY_STATUS_LABELS[session.status]
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

async function loadPeople(): Promise<void> {
  const token = props.accessToken
  if (!token) return
  const requested = ++peopleVersion
  peopleFailed.value = false
  try {
    const loaded = await enrollmentApi.loadParticipants(token, selectedSessionId.value ?? undefined)
    if (requested !== peopleVersion) return
    people.value = loaded
    // Someone who can no longer be chosen for this session is dropped from the selection.
    const selectable = new Set(loaded
      .filter((person) => person.eligibility?.eligible !== false)
      .map((person) => person.participantId))
    pickedIds.value = pickedIds.value.filter((id) => selectable.has(id))
  } catch (error) {
    if (requested !== peopleVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    people.value = []
    pickedIds.value = []
    peopleFailed.value = true
  }
}

async function load(): Promise<void> {
  if (!props.accessToken) {
    emit('login')
    return
  }
  if (!props.activityId || !props.cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await activityApi.loadDetail(props.activityId, props.cityCode)
    if (requested !== requestVersion) return
    if (resolveEnrollmentPayMode(loaded) === 'UNSUPPORTED') {
      state.value = { kind: 'unsupported' }
      return
    }
    state.value = { kind: 'ready', activity: loaded }
    const open = loaded.sessions.filter(isSessionOpen)
    const wanted = selectedSessionId.value ?? props.sessionId
    selectedSessionId.value = open.find((session) => session.sessionId === wanted)?.sessionId
      ?? open[0]?.sessionId
      ?? null
    await loadPeople()
  } catch (error) {
    if (requested !== requestVersion) return
    const missing = error instanceof ApiRequestError
      && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function selectSession(session: ActivitySession): void {
  if (!isSessionOpen(session) || session.sessionId === selectedSessionId.value) return
  selectedSessionId.value = session.sessionId
  // Age and "already signed up" depend on the session.
  void loadPeople()
}

function togglePerson(personId: string, selectable: boolean): void {
  if (!selectable) return
  pickedIds.value = pickedIds.value.includes(personId)
    ? pickedIds.value.filter((id) => id !== personId)
    : [...pickedIds.value, personId]
}

function runIntent(intent: ActivityIntent): void {
  // The agreement page exists since step G1 (preview builds); its text is still a placeholder.
  if (intent.type === 'READ_AGREEMENT' && SETTINGS_PREVIEW_ENABLED) {
    uni.navigateTo({ url: buildAgreementUrl(intent.document) })
    return
  }
  const resolution = resolveActivityIntent(intent, ACTIVITY_PREVIEW_ENABLED)
  if (resolution.action === 'SHOW_NOTICE') {
    uni.showToast({ title: resolution.message, icon: 'none' })
  }
}

// The two refusals of a paid sign-up say what to do; the rest show the server's words.
function describeSubmitFailure(error: unknown): string {
  if (!(error instanceof ApiRequestError)) return '提交失败，请稍后重试'
  if (error.code === 'ACTIVITY_AMOUNT_CHANGED') return '报名费有调整，已更新为最新金额，请确认后再提交'
  if (error.code === 'ACTIVITY_PAYMENT_UNAVAILABLE') return PAYMENT_UNAVAILABLE_TEXT
  if (error.status === 503 || error.code === 'TRADE_CHANNEL_UNAVAILABLE') return '微信支付暂不可用，请稍后再试'
  return error.message
}

function trySubmit(): void {
  if (submitLocked.value) return
  if (missingStep.value) {
    uni.showToast({ title: missingStep.value, icon: 'none' })
    return
  }
  void submit()
}

async function submit(): Promise<void> {
  const token = props.accessToken
  const session = selectedSession.value
  if (!canSubmit.value || !token || !session || !activity.value) return
  const participantIds = picked.value.map((person) => person.participantId)
  const expectedAmountMinor = totalAmount.value
  submitting.value = true
  try {
    const enrollment = await enrollmentApi.enroll(
      token,
      keys.keyFor(session.sessionId, participantIds, expectedAmountMinor),
      {
        activityId: activity.value.activityId,
        sessionId: session.sessionId,
        participantIds,
        noticeAgreed: true,
        ...(expectedAmountMinor === null ? {} : { expectedAmountMinor }),
      },
    )
    // Unpaid: pay now. A replay of a sign-up already paid (or closed) shows its ticket.
    if (enrollment.payment.status === 'PENDING_PAYMENT' && enrollment.payment.tradeOrderId) {
      emit('pay', enrollment.payment.tradeOrderId)
      return
    }
    emit('done', enrollment.enrollmentId)
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({ title: describeSubmitFailure(error), icon: 'none', duration: 2500 })
    // A refusal usually means seats or eligibility changed; show what is true now.
    if (error instanceof ApiRequestError && (error.status === 409 || error.status === 422)) {
      void load()
    }
  } finally {
    submitting.value = false
  }
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="activity-enroll" data-testid="activity-enroll">
    <AppPageHeader title="填写报名预约" plain test-id="activity-enroll" @back="emit('back')" />

    <view
      v-if="state.kind === 'loading'"
      class="activity-enroll__body"
      data-testid="activity-enroll-loading"
      aria-label="正在加载"
    >
      <view class="activity-enroll__skeleton" />
      <view class="activity-enroll__skeleton activity-enroll__skeleton--tall" />
    </view>

    <view
      v-else-if="state.kind === 'ready' && activity"
      class="activity-enroll__body"
    >
      <image
        v-if="coverUrl"
        class="activity-enroll__cover"
        :src="coverUrl"
        :alt="activity.title"
        mode="aspectFill"
        data-testid="activity-enroll-cover"
      />
      <view class="activity-enroll__intro">
        <text class="activity-enroll__type" data-testid="activity-enroll-type">{{ typeLine }}</text>
        <text class="activity-enroll__activity" data-testid="activity-enroll-title">{{ activity.title }}</text>
        <text class="activity-enroll__meta">主办：{{ activity.organizer.name }}</text>
        <text class="activity-enroll__meta">地点：{{ activity.location.name }}</text>
      </view>

      <view class="activity-enroll__card">
        <text class="activity-enroll__heading">选择场次</text>
        <button
          v-for="session in sessions"
          :key="session.sessionId"
          :class="[
            'activity-enroll__choice',
            'activity-enroll__session',
            {
              'activity-enroll__choice--on': session.sessionId === selectedSessionId,
              'activity-enroll__session--on': session.sessionId === selectedSessionId,
              'activity-enroll__session--off': !isSessionOpen(session),
            },
          ]"
          :data-testid="`activity-enroll-session-${session.sessionId}`"
          :aria-pressed="session.sessionId === selectedSessionId"
          :aria-disabled="!isSessionOpen(session)"
          @click="selectSession(session)"
        >
          <view :class="['activity-enroll__radio', { 'activity-enroll__radio--on': session.sessionId === selectedSessionId }]" />
          <view class="activity-enroll__choice-text">
            <text class="activity-enroll__choice-title">{{ sessionLabel(session) }}</text>
            <text class="activity-enroll__choice-sub">{{ sessionSeats(session) }}</text>
          </view>
        </button>
        <text
          v-if="!selectedSession"
          class="activity-enroll__hint"
          data-testid="activity-enroll-no-session"
        >
          现在没有可以报名的场次
        </text>
      </view>

      <view class="activity-enroll__card">
        <text class="activity-enroll__heading">选择参与人</text>
        <view v-if="peopleFailed" class="activity-enroll__people-error">
          <text>参与人没有读出来</text>
          <button
            class="activity-enroll__text-button"
            data-testid="activity-enroll-people-retry"
            @click="loadPeople"
          >
            重试
          </button>
        </view>
        <button
          v-for="row in rows"
          :key="row.person.participantId"
          :class="[
            'activity-enroll__choice',
            'activity-enroll__person',
            { 'activity-enroll__choice--on': row.picked, 'activity-enroll__choice--off': !row.selectable },
          ]"
          :data-testid="`activity-enroll-person-${row.person.participantId}`"
          :data-name="row.person.name"
          role="checkbox"
          :aria-checked="row.picked"
          :aria-disabled="!row.selectable"
          @click="togglePerson(row.person.participantId, row.selectable)"
        >
          <view
            :class="[
              'activity-enroll__box',
              {
                'activity-enroll__box--on': row.picked,
                'activity-enroll__box--off': !row.selectable,
              },
            ]"
          >
            <LineIcon v-if="row.picked" name="check" :size="14" color="#ffffff" :stroke-width="2.4" />
          </view>
          <view class="activity-enroll__choice-text">
            <text class="activity-enroll__choice-title activity-enroll__person-name">{{ row.label }}</text>
            <text
              :class="[
                'activity-enroll__choice-sub',
                'activity-enroll__person-state',
                { 'activity-enroll__person-state--ok': row.selectable },
              ]"
            >
              {{ row.stateText }} · {{ row.relationText }}
            </text>
          </view>
        </button>
        <view class="activity-enroll__people-foot">
          <text v-if="forChildren" class="activity-enroll__note">未成年人由监护人代报名并陪同；陪同家长不占名额。</text>
          <button
            class="activity-enroll__add"
            data-testid="activity-enroll-add-person"
            @click="emit('managePeople')"
          >
            <LineIcon name="plus" :size="14" color="#ba470b" :stroke-width="2" />
            <text>添加参与人</text>
          </button>
        </view>
      </view>

      <view class="activity-enroll__agree">
        <button
          class="activity-enroll__agree-toggle"
          data-testid="activity-enroll-agree"
          role="checkbox"
          :aria-checked="agreed"
          aria-label="同意报名须知"
          @click="agreed = !agreed"
        >
          <view :class="['activity-enroll__box', 'activity-enroll__box--small', { 'activity-enroll__box--on': agreed }]">
            <LineIcon v-if="agreed" name="check" :size="12" color="#ffffff" :stroke-width="2.4" />
          </view>
        </button>
        <text class="activity-enroll__agree-text">
          <text @click="agreed = !agreed">{{ noticeLead }}</text>
          <text
            class="activity-enroll__link"
            data-testid="activity-enroll-notice"
            @click="runIntent({ type: 'READ_AGREEMENT', document: 'ENROLLMENT_NOTICE' })"
          >
            报名须知与照片使用说明
          </text>
        </text>
      </view>
      <!-- Board EventSignup has no rules card; the rules go in its note line under the consent. -->
      <view class="activity-enroll__rules" data-testid="activity-enroll-rules">
        <text v-if="activity.refundNote" class="activity-enroll__note">{{ activity.refundNote }}</text>
        <text v-if="paid" class="activity-enroll__note" data-testid="activity-enroll-paid-rule">
          场次开始前可以取消其中几位，每位按报名时的单价原路退回；已签到的不能退。提交后 15 分钟内未付款，名额自动释放。
        </text>
        <text v-else class="activity-enroll__note">免费报名在场次开始前都可以取消，名额会让给别人。</text>
      </view>
    </view>

    <view
      v-else-if="state.kind === 'unsupported'"
      class="activity-enroll__state"
      data-testid="activity-enroll-unsupported"
    >
      <text class="activity-enroll__state-title">这个活动暂不支持在线报名</text>
      <text class="activity-enroll__state-text">询价活动请先咨询主办方</text>
      <button class="activity-enroll__state-button" @click="emit('back')">返回</button>
    </view>

    <view
      v-else-if="state.kind === 'missing'"
      class="activity-enroll__state"
      data-testid="activity-enroll-missing"
    >
      <text class="activity-enroll__state-title">活动不存在或已下线</text>
      <button class="activity-enroll__state-button" @click="emit('back')">返回</button>
    </view>

    <view
      v-else
      class="activity-enroll__state"
      data-testid="activity-enroll-error"
    >
      <text class="activity-enroll__state-title">加载失败</text>
      <text class="activity-enroll__state-text">请检查网络后重试</text>
      <button
        class="activity-enroll__state-button"
        data-testid="activity-enroll-retry"
        @click="load"
      >
        重试
      </button>
    </view>

    <view v-if="state.kind === 'ready'" class="activity-enroll__bar">
      <view class="activity-enroll__total">
        <text
          :class="['activity-enroll__count', { 'activity-enroll__count--warn': tooMany || payMode === 'PAYMENT_UNAVAILABLE' }]"
          data-testid="activity-enroll-count"
        >
          {{ countLine }}
        </text>
        <text class="activity-enroll__price" data-testid="activity-enroll-price">{{ priceText }}</text>
      </view>
      <button
        :class="['activity-enroll__submit', { 'activity-enroll__submit--off': submitLocked }]"
        data-testid="activity-enroll-submit"
        :data-mode="payMode"
        :data-ready="canSubmit"
        :disabled="submitLocked"
        @click="trySubmit"
      >
        {{ submitLabel }}
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Signup (390 wide). */
.activity-enroll {
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

.activity-enroll__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
}

/* Board EventSignup: a 358×170 picture, then the activity on the page itself (no card). */
.activity-enroll__cover {
  display: block;
  width: 100%;
  height: 170px;
  border-radius: 18px;
  background: #eef2f8;
}

.activity-enroll__intro {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.activity-enroll__type {
  padding: 5px 9px;
  border-radius: 20px;
  color: #4479b3;
  background: #edf4ff;
  font-size: 11px;
  font-weight: 600;
  line-height: 16px;
  white-space: nowrap;
}

.activity-enroll__activity {
  margin: 10px 0 8px;
  font-size: 18px;
  font-weight: 750;
  line-height: 25px;
}

.activity-enroll__meta {
  color: #596a81;
  font-size: 14px;
  line-height: 23px;
}

.activity-enroll__card {
  display: flex;
  flex-direction: column;
  padding: 16px;
  border: 1px solid #ecf0f5;
  border-radius: 18px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.activity-enroll__heading {
  margin-bottom: 6px;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

/* One 58px choice per session or person, like the board's radio / checkbox rows. */
.activity-enroll__choice {
  display: flex;
  width: 100%;
  min-height: 58px;
  margin-top: 8px;
  padding: 8px 12px;
  align-items: center;
  gap: 10px;
  border: 1px solid #e4eaf3;
  border-radius: 12px;
  background: #ffffff;
  text-align: left;
  box-sizing: border-box;
}

.activity-enroll__choice--on {
  border-color: #ff963f;
  background: #fff7ee;
}

.activity-enroll__choice--off,
.activity-enroll__session--off {
  background: #f5f7fc;
}

.activity-enroll__choice-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.activity-enroll__choice-title {
  overflow: hidden;
  color: #202c42;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-enroll__choice-sub {
  color: #718099;
  font-size: 11px;
  line-height: 16px;
}

.activity-enroll__session--off .activity-enroll__choice-title,
.activity-enroll__choice--off .activity-enroll__choice-title {
  color: #738096;
}

.activity-enroll__radio {
  width: 18px;
  height: 18px;
  flex: none;
  margin-left: 5px;
  border: 1.5px solid #9aa8ba;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.activity-enroll__radio--on {
  border-color: #202c42;
  background: #ff6a00;
  box-shadow: inset 0 0 0 2px #ffffff;
}

.activity-enroll__hint {
  margin-top: 8px;
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.activity-enroll__people-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #738096;
  font-size: 13px;
}

.activity-enroll__text-button {
  min-height: 36px;
  padding: 0 8px;
  color: #ba470b;
  font-size: 13px;
}

.activity-enroll__box {
  display: flex;
  width: 18px;
  height: 18px;
  flex: none;
  margin-left: 4px;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #9aa8ba;
  border-radius: 3px;
  background: #ffffff;
  box-sizing: border-box;
}

.activity-enroll__box--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.activity-enroll__box--off {
  border-color: #d9e0ea;
  background: #f1f3f6;
}

.activity-enroll__person-state--ok {
  color: #718099;
}

.activity-enroll__people-foot {
  display: flex;
  margin-top: 6px;
  align-items: center;
  gap: 10px;
}

.activity-enroll__note {
  min-width: 0;
  flex: 1;
  color: #748197;
  font-size: 11px;
  line-height: 17.6px;
}

.activity-enroll__add {
  display: flex;
  min-height: 36px;
  flex: none;
  margin-left: auto;
  padding: 0 4px;
  align-items: center;
  gap: 2px;
  color: #ba470b;
  font-size: 13px;
  white-space: nowrap;
}

.activity-enroll__rules {
  display: flex;
  margin-top: -8px;
  padding-left: 34px;
  flex-direction: column;
  gap: 4px;
}

.activity-enroll__agree {
  display: flex;
  align-items: flex-start;
  gap: 4px;
}

.activity-enroll__agree-toggle {
  display: flex;
  width: 30px;
  height: 30px;
  flex: none;
  margin: -4px 0 -4px -6px;
  align-items: center;
  justify-content: center;
}

.activity-enroll__agree-toggle .activity-enroll__box {
  margin-left: 0;
}

.activity-enroll__agree-text {
  min-width: 0;
  color: #202c42;
  font-size: 12px;
  line-height: 20.4px;
}

.activity-enroll__link {
  color: #ba470b;
}

.activity-enroll__skeleton {
  height: 72px;
  border-radius: 12px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.activity-enroll__skeleton--tall {
  height: 220px;
}

.activity-enroll__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.activity-enroll__state-title {
  font-size: 16px;
  font-weight: 700;
}

.activity-enroll__state-text {
  color: #738096;
  font-size: 13px;
}

.activity-enroll__state-button {
  min-width: 120px;
  height: 40px;
  margin-top: 8px;
  padding: 0 20px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  line-height: 40px;
}

.activity-enroll__bar {
  position: sticky;
  bottom: 0;
  display: flex;
  flex: none;
  padding: 10px 16px calc(20px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 10px;
  border-top: 1px solid #e5ebf3;
  /* Opaque: the form scrolled under a 96% white bar showed through on iOS. */
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

.activity-enroll__total {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.activity-enroll__count {
  color: #748197;
  font-size: 11px;
  line-height: 17.6px;
}

.activity-enroll__count--warn {
  color: #c0392b;
}

.activity-enroll__price {
  color: #e65e00;
  font-size: 25px;
  font-weight: 750;
  line-height: 36px;
}

/* Board EventSignup: a 192×59 orange button with a 24px radius; the text stays 16px (R5 #2). */
.activity-enroll__submit {
  display: flex;
  width: 192px;
  max-width: 60%;
  height: 59px;
  flex: none;
  margin-left: auto;
  padding: 0 18px;
  align-items: center;
  justify-content: center;
  border-radius: 24px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 650;
  white-space: nowrap;
}

.activity-enroll__submit--off,
.activity-enroll__submit[disabled] {
  color: #91a0b4;
  background: #eef0f3;
  box-shadow: none;
}
</style>
