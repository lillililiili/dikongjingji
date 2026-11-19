<script setup lang="ts">
import { SETTINGS_PREVIEW_ENABLED } from '@/features/settings/application/settings-preview'
import { buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'

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
import { enrollmentApi } from '../../data/enrollment-api-client'
import type { ActivityDetail, ActivitySession } from '../../domain/activity'
import { MAX_ENROLLMENT_PARTICIPANTS, type Participant } from '../../domain/enrollment'
import {
  formatActivityAmount,
  formatActivityDay,
  formatActivitySessionSeats,
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
const organizerLine = computed(() => {
  if (!activity.value) return ''
  const host = `主办：${activity.value.organizer.name}`
  return activity.value.coOrganizer ? `${host}　承办：${activity.value.coOrganizer.name}` : host
})
const rows = computed(() => (activity.value
  ? people.value.map((person) => {
      const personState = describeParticipantState(person, activity.value!.ageRange)
      return {
        person,
        selectable: personState.selectable,
        stateText: personState.text,
        ageText: describeParticipantAge(person),
        picked: personState.selectable && pickedIds.value.includes(person.participantId),
      }
    })
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
const canSubmit = computed(() =>
  (payMode.value === 'FREE' || payMode.value === 'ONLINE_PAYMENT')
  && selectedSession.value?.status === 'OPEN'
  && picked.value.length > 0
  && picked.value.length <= MAX_ENROLLMENT_PARTICIPANTS
  && !tooMany.value
  && agreed.value
  && !submitting.value)
const countLine = computed(() => {
  if (payMode.value === 'PAYMENT_UNAVAILABLE') return PAYMENT_UNAVAILABLE_TEXT
  if (tooMany.value) return `本场只剩 ${selectedSession.value?.seatsLeft} 个名额`
  if (picked.value.length === 0) return '请选择参与人'
  return paid.value
    ? `${formatActivityAmount(unitAmount.value)} × ${picked.value.length} 人`
    : `已选 ${picked.value.length} 人`
})

function isSessionOpen(session: ActivitySession): boolean {
  return session.status === 'OPEN'
}

function sessionLabel(session: ActivitySession): string {
  const day = formatActivityDay(session.startsAt).split(' ')[0]
  return `${day} ${session.label ?? formatActivityTime(session.startsAt)}`
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
    <header class="activity-enroll__header">
      <button
        class="activity-enroll__back"
        data-testid="activity-enroll-back"
        aria-label="返回"
        @click="emit('back')"
      >
        <UniIcons type="left" size="24" color="#202c42" />
      </button>
      <text class="activity-enroll__title">填写报名</text>
    </header>

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
      <view class="activity-enroll__card activity-enroll__card--tight">
        <text class="activity-enroll__activity" data-testid="activity-enroll-title">{{ activity.title }}</text>
        <text class="activity-enroll__organizer">{{ organizerLine }}</text>
      </view>

      <view class="activity-enroll__card">
        <text class="activity-enroll__heading">选择场次</text>
        <view v-if="sessions.length > 0" class="activity-enroll__sessions">
          <button
            v-for="session in sessions"
            :key="session.sessionId"
            :class="[
              'activity-enroll__session',
              {
                'activity-enroll__session--on': session.sessionId === selectedSessionId,
                'activity-enroll__session--off': !isSessionOpen(session),
              },
            ]"
            :data-testid="`activity-enroll-session-${session.sessionId}`"
            :aria-pressed="session.sessionId === selectedSessionId"
            :aria-disabled="!isSessionOpen(session)"
            @click="selectSession(session)"
          >
            <text class="activity-enroll__session-label">{{ sessionLabel(session) }}</text>
            <text class="activity-enroll__session-sub">{{ formatActivitySessionSeats(session) }}</text>
          </button>
        </view>
        <text
          v-if="!selectedSession"
          class="activity-enroll__hint"
          data-testid="activity-enroll-no-session"
        >
          现在没有可以报名的场次
        </text>
      </view>

      <view class="activity-enroll__card">
        <view class="activity-enroll__heading-row">
          <text class="activity-enroll__heading">参与人</text>
          <text v-if="forChildren" class="activity-enroll__side-note">由监护人代为报名</text>
        </view>
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
          class="activity-enroll__person"
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
            <UniIcons v-if="row.picked" type="checkmarkempty" size="14" color="#ffffff" />
          </view>
          <text class="activity-enroll__person-name">{{ row.person.name }}</text>
          <text class="activity-enroll__tag">{{ row.person.relation === 'SELF' ? '本人' : '子女' }}</text>
          <text v-if="row.ageText" class="activity-enroll__person-age">{{ row.ageText }}</text>
          <text
            :class="[
              'activity-enroll__person-state',
              { 'activity-enroll__person-state--ok': row.selectable },
            ]"
          >
            {{ row.stateText }}
          </text>
        </button>
        <view class="activity-enroll__people-foot">
          <button
            class="activity-enroll__add"
            data-testid="activity-enroll-add-person"
            @click="emit('managePeople')"
          >
            ＋ 添加参与人
          </button>
          <text class="activity-enroll__side-note">未成年人需监护人确认</text>
        </view>
      </view>

      <view class="activity-enroll__card activity-enroll__card--rules">
        <text class="activity-enroll__heading">退改规则</text>
        <text v-if="activity.refundNote" class="activity-enroll__rule">{{ activity.refundNote }}</text>
        <text v-if="paid" class="activity-enroll__rule" data-testid="activity-enroll-paid-rule">
          场次开始前可以取消其中几位，每位按报名时的单价原路退回；已签到的不能退。提交后 15 分钟内未付款，名额自动释放。
        </text>
        <text v-else class="activity-enroll__rule">免费报名在场次开始前都可以取消，名额会让给别人。</text>
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
              <UniIcons v-if="agreed" type="checkmarkempty" size="12" color="#ffffff" />
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
        :class="['activity-enroll__submit', { 'activity-enroll__submit--off': !canSubmit }]"
        data-testid="activity-enroll-submit"
        :data-mode="payMode"
        :disabled="!canSubmit"
        @click="submit"
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

.activity-enroll__header {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  min-height: calc(60px + var(--status-bar-height));
  padding: var(--status-bar-height) 8px 0;
  align-items: center;
  gap: 4px;
  background: #ffffff;
  box-sizing: border-box;
}

.activity-enroll__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.activity-enroll__title {
  font-size: 18px;
  font-weight: 700;
}

.activity-enroll__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 16px;
}

.activity-enroll__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 12px;
  background: #ffffff;
}

.activity-enroll__card--tight {
  gap: 6px;
}

.activity-enroll__card--rules {
  gap: 8px;
}

.activity-enroll__activity {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.activity-enroll__organizer {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.activity-enroll__heading-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.activity-enroll__heading {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.activity-enroll__side-note {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.activity-enroll__sessions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.activity-enroll__session {
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

.activity-enroll__session-label {
  color: #202c42;
  font-size: 14px;
  font-weight: 500;
}

.activity-enroll__session-sub {
  color: #627187;
  font-size: 12px;
}

.activity-enroll__session--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.activity-enroll__session--on .activity-enroll__session-label {
  color: #ba470b;
}

.activity-enroll__session--off {
  background: #f5f7fc;
}

.activity-enroll__session--off .activity-enroll__session-label,
.activity-enroll__session--off .activity-enroll__session-sub {
  color: #738096;
}

.activity-enroll__hint {
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

.activity-enroll__person {
  display: flex;
  width: 100%;
  min-height: 44px;
  padding: 0 4px;
  align-items: center;
  gap: 10px;
  text-align: left;
  box-sizing: border-box;
}

.activity-enroll__box {
  display: flex;
  width: 20px;
  height: 20px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #9aa8ba;
  border-radius: 4px;
  background: #ffffff;
  box-sizing: border-box;
}

.activity-enroll__box--small {
  width: 18px;
  height: 18px;
}

.activity-enroll__box--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.activity-enroll__box--off {
  border-color: #d9e0ea;
  background: #f1f3f6;
}

.activity-enroll__person-name {
  min-width: 0;
  overflow: hidden;
  color: #202c42;
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-enroll__tag {
  flex: none;
  margin-left: -4px;
  padding: 1px 6px;
  border-radius: 6px;
  color: #536178;
  background: #eef2f8;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.activity-enroll__person-age {
  flex: none;
  color: #627187;
  font-size: 12px;
}

.activity-enroll__person-state {
  flex: none;
  margin-left: auto;
  color: #738096;
  font-size: 12px;
  white-space: nowrap;
}

.activity-enroll__person-state--ok {
  color: #11804a;
}

.activity-enroll__people-foot {
  display: flex;
  align-items: center;
  gap: 10px;
}

.activity-enroll__people-foot .activity-enroll__side-note {
  margin-left: auto;
}

.activity-enroll__add {
  display: flex;
  min-height: 36px;
  flex: none;
  padding: 0 12px;
  align-items: center;
  border: 1px dashed #c9d3e1;
  border-radius: 8px;
  color: #536178;
  background: #ffffff;
  font-size: 13px;
  white-space: nowrap;
  box-sizing: border-box;
}

.activity-enroll__rule {
  color: #536178;
  font-size: 13px;
  line-height: 1.6;
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

.activity-enroll__agree-text {
  min-width: 0;
  color: #202c42;
  font-size: 13px;
  line-height: 1.6;
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
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 12px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.activity-enroll__total {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.activity-enroll__count {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.activity-enroll__count--warn {
  color: #c0392b;
}

.activity-enroll__price {
  color: #e85400;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.activity-enroll__submit {
  width: 180px;
  height: 44px;
  flex: none;
  margin-left: auto;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 44px;
}

.activity-enroll__submit--off,
.activity-enroll__submit[disabled] {
  color: #91a0b4;
  background: #eef0f3;
}
</style>
