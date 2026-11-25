<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { SETTINGS_PREVIEW_ENABLED } from '@/features/settings/application/settings-preview'
import { buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import { ApiRequestError } from '@/shared/http/public-api-request'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  PAYMENT_UNAVAILABLE_TEXT,
  enrollmentTotalMinor,
  resolveEnrollmentPayMode,
  type EnrollmentPayMode,
} from '../../application/activity-intent'
import {
  createEnrollmentKey,
  createEnrollmentKeyHolder,
  formatBirthMonth,
} from '../../application/enrollment-view'
import {
  LEADER_NAME_MAX,
  ROSTER_TEMPLATE,
  TEAM_NAME_MAX,
  describeRoster,
  isValidMemberName,
  parseRosterText,
  rosterEntryFromInput,
  rosterEntryFromParticipant,
  rosterIssueLine,
  rosterLine,
  teamMissingStep,
  toTeamMemberInput,
  type RosterEntry,
} from '../../application/team-roster'
import { activityApi } from '../../data/activity-api-client'
import { enrollmentApi } from '../../data/enrollment-api-client'
import type { ActivityDetail, ActivitySession } from '../../domain/activity'
import { MAX_TEAM_ROSTER_ROWS, type Participant, type TeamMemberCheck } from '../../domain/enrollment'
import {
  ACTIVITY_STATUS_LABELS,
  formatActivityAgeRange,
  formatActivityAmount,
  formatActivityShortDateTime,
} from '../shared/activity-format'

// Board TeamSignup (S5-3): a leader signs up 2–20 people for one session in one go. Who may come is
// the server's answer for each row (POST .../check); only those rows are counted and sent.

const props = defineProps<{
  activityId: string
  cityCode: string
  sessionId: string
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  // A free team sign-up: its team order.
  done: [enrollmentId: string]
  // A paid one: the trade cashier of its order.
  pay: [tradeOrderId: string]
}>()

type PageState =
  | { kind: 'loading' }
  | { kind: 'ready'; activity: ActivityDetail }
  | { kind: 'unsupported' }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<PageState>({ kind: 'loading' })
const selectedSessionId = ref<string | null>(null)
const teamName = ref('')
const leaderName = ref('')
const leaderPhone = ref('')
const entries = ref<RosterEntry[]>([])
const checks = ref<TeamMemberCheck[] | null>(null)
const checking = ref(false)
const agreed = ref(false)
const submitting = ref(false)
const sheet = ref<'' | 'import' | 'add'>('')
const people = ref<Participant[]>([])
const newName = ref('')
const newBirthMonth = ref('')
const pasteText = ref('')
const importHint = ref('')
const keys = createEnrollmentKeyHolder(createEnrollmentKey)
let requestVersion = 0
let checkVersion = 0
let checkTimer: ReturnType<typeof setTimeout> | null = null

const activity = computed(() => (state.value.kind === 'ready' ? state.value.activity : null))
const payMode = computed<EnrollmentPayMode>(() =>
  activity.value ? resolveEnrollmentPayMode(activity.value) : 'UNSUPPORTED')
const paid = computed(() => payMode.value === 'ONLINE_PAYMENT' || payMode.value === 'PAYMENT_UNAVAILABLE')
const unitAmount = computed(() => (paid.value ? activity.value?.price.amountMinor ?? 0 : 0))
// Sessions still running are listed; only OPEN ones can be picked (team sign-up has no waitlist).
const sessions = computed<ActivitySession[]>(() =>
  activity.value?.sessions.filter((session) => session.status !== 'ENDED') ?? [])
const selectedSession = computed(() =>
  sessions.value.find((session) => session.sessionId === selectedSessionId.value) ?? null)
const coverUrl = computed(() =>
  activity.value
    ? activity.value.coverImage?.url ?? null
    : null)
const ageLine = computed(() => (activity.value ? formatActivityAgeRange(activity.value.ageRange) : null))
const roster = computed(() =>
  describeRoster(entries.value, checks.value, activity.value?.ageRange ?? { minAge: null, maxAge: null }, {
    session: selectedSession.value ? `${sessionTitle(selectedSession.value)}场` : undefined,
    contest: activity.value?.type === 'CONTEST',
  }))
const count = computed(() => roster.value.eligibleCount)
const countLine = computed(() =>
  paid.value ? `${count.value} 人 × ${formatActivityAmount(unitAmount.value)}` : `${count.value} 人 · 免费`)
const totalText = computed(() =>
  paid.value ? formatActivityAmount(enrollmentTotalMinor(unitAmount.value, count.value)) : '免费')
const missingStep = computed(() => {
  if (payMode.value === 'PAYMENT_UNAVAILABLE') return PAYMENT_UNAVAILABLE_TEXT
  return teamMissingStep({
    sessionOpen: selectedSession.value?.status === 'OPEN',
    teamName: teamName.value,
    leaderName: leaderName.value,
    leaderPhone: leaderPhone.value,
    eligibleCount: count.value,
    rosterRows: entries.value.length,
    checking: checking.value,
    agreed: agreed.value,
    seatsLeft: selectedSession.value?.seatsLeft ?? null,
  })
})
const ready = computed(() => missingStep.value === null && !submitting.value)
const submitLabel = computed(() => {
  if (submitting.value) return '提交中…'
  if (payMode.value === 'PAYMENT_UNAVAILABLE') return '暂不能报名'
  return paid.value ? '提交并支付' : '提交报名'
})
// Children of the account who are not on the roster yet: picked straight into it.
const pickable = computed(() => people.value.filter((person) =>
  person.relation === 'CHILD'
  && !entries.value.some((entry) => entry.participantId === person.participantId)))
const birthRange = computed(() => {
  const end = new Date()
  const month = `${end.getFullYear()}-${String(end.getMonth() + 1).padStart(2, '0')}`
  return { start: '1940-01', end: month }
})

// "11/15 上午", with the free places or why it cannot be picked.
function sessionTitle(session: ActivitySession): string {
  const [day] = formatActivityShortDateTime(session.startsAt).split(' ')
  return session.label ? `${day} ${session.label}` : formatActivityShortDateTime(session.startsAt)
}

function sessionSub(session: ActivitySession): string {
  if (session.status === 'OPEN') return `余 ${session.seatsLeft}`
  if (session.status === 'FULL_WAITLIST' || session.status === 'FULL') return '已满'
  return ACTIVITY_STATUS_LABELS[session.status]
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

function valueOf(event: unknown): string {
  const detail = (event as { detail?: { value?: unknown } })?.detail
  return typeof detail?.value === 'string' ? detail.value : ''
}

async function loadPeople(): Promise<void> {
  const token = props.accessToken
  if (!token) return
  try {
    const loaded = await enrollmentApi.loadParticipants(token)
    people.value = loaded
    const self = loaded.find((person) => person.relation === 'SELF')
    if (self && !leaderName.value) leaderName.value = self.name.slice(0, LEADER_NAME_MAX)
  } catch (error) {
    if (isUnauthorized(error)) emit('login')
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
    if (resolveEnrollmentPayMode(loaded) === 'UNSUPPORTED' || !loaded.teamEnrollmentAllowed) {
      state.value = { kind: 'unsupported' }
      return
    }
    state.value = { kind: 'ready', activity: loaded }
    const open = loaded.sessions.filter((session) => session.status === 'OPEN')
    const wanted = selectedSessionId.value ?? props.sessionId
    selectedSessionId.value = open.find((session) => session.sessionId === wanted)?.sessionId
      ?? open[0]?.sessionId
      ?? null
    await loadPeople()
    scheduleCheck(0)
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

// The server checks the whole roster again after each change (ages are counted on the session day).
function scheduleCheck(delay = 250): void {
  if (checkTimer) clearTimeout(checkTimer)
  checks.value = null
  if (entries.value.length === 0 || !selectedSessionId.value) {
    checking.value = false
    return
  }
  checking.value = true
  checkTimer = setTimeout(() => { void runCheck() }, delay)
}

async function runCheck(): Promise<void> {
  const token = props.accessToken
  const sessionId = selectedSessionId.value
  if (!token || !sessionId || entries.value.length === 0) {
    checking.value = false
    return
  }
  const requested = ++checkVersion
  const sent = entries.value.slice(0, MAX_TEAM_ROSTER_ROWS)
  try {
    const result = await enrollmentApi.checkTeam(token, sessionId, sent.map(toTeamMemberInput))
    if (requested !== checkVersion) return
    checks.value = result.members.length === entries.value.length ? result.members : null
  } catch (error) {
    if (requested !== checkVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({ title: '名单核对失败，请稍后重试', icon: 'none' })
  } finally {
    if (requested === checkVersion) checking.value = false
  }
}

function selectSession(session: ActivitySession): void {
  if (session.status !== 'OPEN' || session.sessionId === selectedSessionId.value) return
  selectedSessionId.value = session.sessionId
  scheduleCheck(0)
}

function removeEntry(key: string): void {
  entries.value = entries.value.filter((entry) => entry.key !== key)
  scheduleCheck()
}

function addEntries(added: RosterEntry[]): void {
  const room = MAX_TEAM_ROSTER_ROWS - entries.value.length
  if (room <= 0) {
    uni.showToast({ title: `名单最多 ${MAX_TEAM_ROSTER_ROWS} 行`, icon: 'none' })
    return
  }
  entries.value = [...entries.value, ...added.slice(0, room)]
  scheduleCheck()
}

function openAdd(): void {
  newName.value = ''
  newBirthMonth.value = ''
  sheet.value = 'add'
}

function confirmAdd(): void {
  if (!isValidMemberName(newName.value)) {
    uni.showToast({ title: '请输入队员姓名', icon: 'none' })
    return
  }
  if (!newBirthMonth.value) {
    uni.showToast({ title: '请选择出生年月', icon: 'none' })
    return
  }
  addEntries([rosterEntryFromInput(newName.value, newBirthMonth.value)])
  sheet.value = ''
}

function pickPerson(person: Participant): void {
  addEntries([rosterEntryFromParticipant(person.participantId, person.name, person.birthMonth)])
  sheet.value = ''
}

function openImport(): void {
  importHint.value = ''
  pasteText.value = ''
  sheet.value = 'import'
}

function copyTemplate(): void {
  uni.setClipboardData({
    data: ROSTER_TEMPLATE,
    success: () => { importHint.value = '模板已复制，按“姓名 出生年月”每人一行填好后粘贴到上面' },
  })
}

function importPasted(): void {
  const { entries: parsed, invalidLines } = parseRosterText(pasteText.value, new Date())
  if (parsed.length === 0) {
    importHint.value = invalidLines.length > 0
      ? `没有读到名单：${invalidLines.length} 行格式不对，请按“姓名 出生年月”每人一行`
      : '请先粘贴名单'
    return
  }
  addEntries(parsed)
  sheet.value = ''
  uni.showToast({
    title: invalidLines.length > 0
      ? `已导入 ${parsed.length} 人，${invalidLines.length} 行格式不对未导入`
      : `已导入 ${parsed.length} 人，正在核对年龄和重复报名`,
    icon: 'none',
    duration: 2500,
  })
}

function readAgreement(): void {
  if (SETTINGS_PREVIEW_ENABLED) uni.navigateTo({ url: buildAgreementUrl('ENROLLMENT_NOTICE') })
}

function describeFailure(error: unknown): string {
  if (!(error instanceof ApiRequestError)) return '提交失败，请稍后重试'
  if (error.code === 'ACTIVITY_AMOUNT_CHANGED') return '报名费有调整，已更新为最新金额，请确认后再提交'
  if (error.code === 'ACTIVITY_PAYMENT_UNAVAILABLE') return PAYMENT_UNAVAILABLE_TEXT
  if (error.code === 'ACT_SESSION_FULL') return '名额不够了，请换场次或减少人数'
  if (error.status === 503 || error.code === 'TRADE_CHANNEL_UNAVAILABLE') return '微信支付暂不可用，请稍后再试'
  return error.message
}

function trySubmit(): void {
  if (submitting.value) return
  if (missingStep.value) {
    uni.showToast({ title: missingStep.value, icon: 'none' })
    return
  }
  void submit()
}

async function submit(): Promise<void> {
  const token = props.accessToken
  const session = selectedSession.value
  if (!token || !session || !activity.value) return
  const members = roster.value.rows.filter((row) => row.eligible).map((row) => toTeamMemberInput(row.entry))
  const expectedAmountMinor = payMode.value === 'ONLINE_PAYMENT'
    ? enrollmentTotalMinor(unitAmount.value, members.length)
    : null
  const signature = [
    teamName.value.trim(),
    leaderName.value.trim(),
    leaderPhone.value.trim(),
    ...members.map((member) => member.participantId ?? `${member.name}|${member.birthMonth}`),
  ]
  submitting.value = true
  try {
    const enrollment = await enrollmentApi.enrollTeam(
      token,
      keys.keyFor(session.sessionId, signature, expectedAmountMinor),
      {
        activityId: activity.value.activityId,
        sessionId: session.sessionId,
        teamName: teamName.value.trim(),
        leaderName: leaderName.value.trim(),
        leaderPhone: leaderPhone.value.trim(),
        members,
        noticeAgreed: true,
        guardianConsent: true,
        ...(expectedAmountMinor === null ? {} : { expectedAmountMinor }),
      },
    )
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
    uni.showToast({ title: describeFailure(error), icon: 'none', duration: 2500 })
    if (error instanceof ApiRequestError && (error.status === 409 || error.status === 422)) {
      void load()
    }
  } finally {
    submitting.value = false
  }
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
onBeforeUnmount(() => {
  if (checkTimer) clearTimeout(checkTimer)
})
</script>

<template>
  <view class="team" data-testid="activity-team">
    <AppPageHeader title="团队报名" test-id="activity-team" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="team__body" data-testid="activity-team-loading">
      <view class="team__skeleton" />
      <view class="team__skeleton team__skeleton--tall" />
    </view>

    <view v-else-if="state.kind === 'ready' && activity" class="team__body">
      <view class="team__card team__card--intro">
        <image v-if="coverUrl" class="team__cover" :src="coverUrl" mode="aspectFill" />
        <view v-else class="team__cover" />
        <view class="team__intro">
          <text class="team__activity" data-testid="activity-team-title">{{ activity.title }}</text>
          <text class="team__meta">{{ activity.location.name }}</text>
          <text class="team__meta">
            <text v-if="ageLine">{{ ageLine }} · </text>
            <template v-if="paid">
              <text class="team__price">{{ formatActivityAmount(unitAmount) }}</text><text> / 人</text>
            </template>
            <text v-else class="team__price">免费</text>
          </text>
        </view>
      </view>

      <view class="team__card">
        <text class="team__heading">选择场次</text>
        <view class="team__sessions">
          <button
            v-for="session in sessions"
            :key="session.sessionId"
            :class="[
              'team__session',
              {
                'team__session--on': session.sessionId === selectedSessionId,
                'team__session--off': session.status !== 'OPEN',
              },
            ]"
            :data-testid="`activity-team-session-${session.sessionId}`"
            :aria-pressed="session.sessionId === selectedSessionId"
            :aria-disabled="session.status !== 'OPEN'"
            @click="selectSession(session)"
          >
            <text class="team__session-title">{{ sessionTitle(session) }}</text>
            <text class="team__session-sub">{{ sessionSub(session) }}</text>
          </button>
        </view>
        <text class="team__hint">团队报名不支持候补，名额不足时请换场次或减少人数</text>
      </view>

      <view class="team__card team__card--form">
        <text class="team__heading team__heading--form">团队与领队</text>
        <view class="team__field">
          <text class="team__field-label">团队名称</text>
          <input
            class="team__input"
            type="text"
            :maxlength="TEAM_NAME_MAX"
            placeholder="请输入团队名称"
            :value="teamName"
            data-testid="activity-team-name"
            @input="teamName = valueOf($event)"
            @blur="teamName = valueOf($event)"
          />
        </view>
        <view class="team__field">
          <text class="team__field-label">领队姓名</text>
          <input
            class="team__input"
            type="text"
            :maxlength="LEADER_NAME_MAX"
            placeholder="请输入领队姓名"
            :value="leaderName"
            data-testid="activity-team-leader"
            @input="leaderName = valueOf($event)"
            @blur="leaderName = valueOf($event)"
          />
        </view>
        <view class="team__field">
          <text class="team__field-label">领队手机</text>
          <input
            class="team__input"
            type="number"
            maxlength="11"
            placeholder="请输入领队手机号"
            :value="leaderPhone"
            data-testid="activity-team-phone"
            @input="leaderPhone = valueOf($event)"
            @blur="leaderPhone = valueOf($event)"
          />
        </view>
        <text class="team__form-note">领队负责到场组织和协助签到，手机号只给你本人看</text>
      </view>

      <view class="team__card team__card--roster" data-testid="activity-team-roster">
        <view class="team__roster-head">
          <text class="team__heading">队员名单</text>
          <text class="team__roster-line" data-testid="activity-team-roster-line">
            {{ rosterLine(entries.length, roster.eligibleCount, roster.issueCount) }}
          </text>
        </view>
        <view class="team__roster-actions">
          <button class="team__import" data-testid="activity-team-import" @click="openImport">
            <LineIcon name="share" :size="16" color="#ba470b" :stroke-width="2" />
            <text>批量导入</text>
          </button>
          <button class="team__add" data-testid="activity-team-add" @click="openAdd">＋ 添加队员</button>
        </view>
        <view v-if="roster.issueCount > 0" class="team__issue" role="status" data-testid="activity-team-issue">
          <LineIcon name="alert" :size="16" color="#ba470b" :stroke-width="2" />
          <text class="team__issue-text">{{ rosterIssueLine(roster.issueCount) }}</text>
        </view>
        <view class="team__rows">
          <view
            v-for="row in roster.rows"
            :key="row.entry.key"
            class="team__row"
            data-testid="activity-team-member"
            :data-name="row.entry.name"
            :data-eligible="row.eligible"
          >
            <view :class="['team__avatar', { 'team__avatar--bad': row.check && !row.eligible }]">
              <text>{{ row.entry.name.slice(0, 1) }}</text>
            </view>
            <view class="team__row-text">
              <text :class="['team__row-name', { 'team__row-name--bad': row.check && !row.eligible }]">
                {{ row.entry.name }}<text class="team__row-age">{{ row.ageText }}</text>
              </text>
              <text
                :class="[
                  'team__row-state',
                  { 'team__row-state--ok': row.eligible, 'team__row-state--bad': row.check && !row.eligible },
                ]"
              >
                {{ row.stateText }}
              </text>
            </view>
            <button
              class="team__remove"
              :aria-label="`移除 ${row.entry.name}`"
              data-testid="activity-team-remove"
              @click="removeEntry(row.entry.key)"
            >
              移除
            </button>
          </view>
          <text v-if="entries.length === 0" class="team__empty" data-testid="activity-team-empty">
            名单是空的，批量导入或添加队员后再提交
          </text>
        </view>
      </view>

      <view class="team__card team__card--fee">
        <text class="team__heading team__heading--fee">费用与规则</text>
        <view class="team__fee-row">
          <text class="team__fee-key">报名费</text>
          <text class="team__fee-value" data-testid="activity-team-count">{{ countLine }}</text>
        </view>
        <view v-if="paid" class="team__fee-row">
          <text class="team__fee-key">付款方式</text>
          <text class="team__fee-value">领队一次付清 · 微信支付</text>
        </view>
        <view class="team__fee-line" />
        <text class="team__rule-text">
          {{ paid
            ? '活动开始前可在线取消（整单或按人），每位按单价原路退回；活动开始后不能在线退改。提交后 15 分钟内未付款，名额自动释放。'
            : '活动开始前可在线取消（整单或按人），名额会让给别人；活动开始后不能在线取消。' }}
        </text>
        <view class="team__agree">
          <button
            class="team__agree-toggle"
            role="checkbox"
            :aria-checked="agreed"
            aria-label="同意报名须知"
            data-testid="activity-team-agree"
            @click="agreed = !agreed"
          >
            <view :class="['team__box', { 'team__box--on': agreed }]">
              <LineIcon v-if="agreed" name="check" :size="12" color="#ffffff" :stroke-width="2.4" />
            </view>
          </button>
          <text class="team__agree-text">
            <text @click="agreed = !agreed">我已取得全部队员监护人的同意，并已阅读</text>
            <text class="team__link" @click="readAgreement">报名须知与照片使用说明</text>
          </text>
        </view>
      </view>
    </view>

    <view v-else-if="state.kind === 'unsupported'" class="team__state" data-testid="activity-team-unsupported">
      <text class="team__state-title">这个活动暂不支持团队报名</text>
      <button class="team__state-button" @click="emit('back')">返回</button>
    </view>
    <view v-else-if="state.kind === 'missing'" class="team__state" data-testid="activity-team-missing">
      <text class="team__state-title">活动不存在或已下线</text>
      <button class="team__state-button" @click="emit('back')">返回</button>
    </view>
    <view v-else class="team__state" data-testid="activity-team-error">
      <text class="team__state-title">加载失败</text>
      <text class="team__state-text">请检查网络后重试</text>
      <button class="team__state-button" @click="load">重试</button>
    </view>

    <view v-if="state.kind === 'ready'" class="team__bar">
      <view class="team__total">
        <text class="team__total-line">{{ payMode === 'PAYMENT_UNAVAILABLE' ? PAYMENT_UNAVAILABLE_TEXT : countLine }}</text>
        <text class="team__total-amount" data-testid="activity-team-total">{{ totalText }}</text>
      </view>
      <button
        :class="['team__submit', { 'team__submit--off': !ready }]"
        data-testid="activity-team-submit"
        :data-ready="ready"
        @click="trySubmit"
      >
        {{ submitLabel }}
      </button>
    </view>

    <view v-if="sheet !== ''" class="team__mask" data-testid="activity-team-sheet" @click.self="sheet = ''">
      <view v-if="sheet === 'import'" class="team__sheet" role="dialog" aria-label="批量导入名单">
        <text class="team__sheet-title">批量导入名单</text>
        <view class="team__steps">
          <view class="team__step"><text class="team__step-no">1</text><text>复制名单模板</text></view>
          <view class="team__step"><text class="team__step-no">2</text><text>按“姓名 出生年月”每人一行填写</text></view>
          <view class="team__step"><text class="team__step-no">3</text><text>粘贴到下面，导入后自动校验年龄和重复报名</text></view>
        </view>
        <textarea
          class="team__paste"
          :value="pasteText"
          :maxlength="4000"
          placeholder="例：李明轩 2014-05"
          data-testid="activity-team-paste"
          @input="pasteText = valueOf($event)"
          @blur="pasteText = valueOf($event)"
        />
        <text v-if="importHint" class="team__sheet-hint" role="status">{{ importHint }}</text>
        <view class="team__sheet-actions">
          <button class="team__ghost" data-testid="activity-team-template" @click="copyTemplate">复制模板</button>
          <button class="team__primary" data-testid="activity-team-import-confirm" @click="importPasted">导入名单</button>
        </view>
      </view>
      <view v-else class="team__sheet team__sheet--add" role="dialog" aria-label="添加队员">
        <text class="team__sheet-title">添加队员</text>
        <view v-if="pickable.length > 0" class="team__pick">
          <text class="team__pick-label">从参与人里选</text>
          <view class="team__pick-list">
            <button
              v-for="person in pickable"
              :key="person.participantId"
              class="team__chip"
              data-testid="activity-team-pick"
              @click="pickPerson(person)"
            >
              {{ person.name }}
            </button>
          </view>
        </view>
        <view class="team__sheet-fields">
          <view class="team__field team__field--sheet">
            <text class="team__field-label">姓名</text>
            <input
              class="team__input"
              type="text"
              maxlength="20"
              placeholder="请输入队员姓名"
              :value="newName"
              data-testid="activity-team-new-name"
              @input="newName = valueOf($event)"
              @blur="newName = valueOf($event)"
            />
          </view>
          <picker
            mode="date"
            fields="month"
            :value="newBirthMonth || birthRange.end"
            :start="birthRange.start"
            :end="birthRange.end"
            @change="newBirthMonth = valueOf($event)"
          >
            <view class="team__field team__field--sheet" data-testid="activity-team-new-birth">
              <text class="team__field-label">出生年月</text>
              <text :class="['team__picker-value', { 'team__picker-value--empty': !newBirthMonth }]">
                {{ newBirthMonth ? formatBirthMonth(newBirthMonth) : '请选择' }}
              </text>
              <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
            </view>
          </picker>
        </view>
        <button class="team__primary team__primary--block" data-testid="activity-team-add-confirm" @click="confirmAdd">
          加入名单
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board TeamSignup (390 wide). */
.team {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  font-family: 'PingFang SC', 'Noto Sans SC', sans-serif;
}

.team__session::after,
.team__import::after,
.team__add::after,
.team__remove::after,
.team__agree-toggle::after,
.team__submit::after,
.team__ghost::after,
.team__primary::after,
.team__chip::after,
.team__state-button::after {
  border: 0;
}

.team__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.team__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 14px;
  background: #fff;
}

.team__card--intro {
  flex-direction: row;
  gap: 12px;
  padding: 12px;
}

.team__cover {
  flex-shrink: 0;
  width: 72px;
  height: 72px;
  border-radius: 10px;
  background: #eef2f8;
}

.team__intro {
  display: flex;
  min-width: 0;
  flex-grow: 1;
  flex-direction: column;
  gap: 4px;
}

.team__activity {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.team__meta {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.team__price {
  color: #e85400;
  font-weight: 700;
}

.team__heading {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.team__heading--form {
  margin-bottom: 6px;
}

.team__heading--fee {
  margin-bottom: 2px;
}

.team__sessions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.team__session {
  display: flex;
  min-height: 56px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  margin: 0;
  padding: 6px 4px;
  border: 1.5px solid #e5ebf3;
  border-radius: 10px;
  background: #fff;
  line-height: normal;
}

.team__session--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.team__session--off {
  background: #f5f7fc;
  opacity: 0.6;
}

.team__session-title {
  color: #202c42;
  font-size: 14px;
  font-weight: 500;
}

.team__session--on .team__session-title {
  color: #ba470b;
}

.team__session--off .team__session-title,
.team__session--off .team__session-sub {
  color: #738096;
}

.team__session-sub {
  color: #627187;
  font-size: 12px;
}

.team__hint {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.team__card--form {
  gap: 0;
  padding: 14px 14px 2px;
}

.team__field {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
}

.team__field--sheet {
  min-height: 48px;
  border-top: 0;
  border-bottom: 1px solid #f0f1f3;
}

.team__field-label {
  flex-shrink: 0;
  color: #627187;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.team__input {
  flex-grow: 1;
  min-width: 0;
  height: 44px;
  color: #202c42;
  font-size: 14px;
  text-align: right;
}

.team__picker-value {
  flex-grow: 1;
  font-size: 14px;
  text-align: right;
}

.team__picker-value--empty {
  color: #91a0b4;
}

.team__form-note {
  padding: 4px 0 12px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.team__card--roster {
  padding: 14px 14px 6px;
}

.team__roster-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.team__roster-line {
  margin-left: auto;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.team__roster-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.team__import,
.team__add {
  display: flex;
  height: 40px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 0;
  border-radius: 10px;
  font-size: 14px;
  white-space: nowrap;
}

.team__import {
  border: 1px solid #ffd8b8;
  color: #ba470b;
  background: #fff0e3;
  font-weight: 500;
}

.team__add {
  border: 1px dashed #c9d3e1;
  color: #536178;
  background: #fff;
}

.team__issue {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 10px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  line-height: 19px;
}

.team__issue-text {
  min-width: 0;
}

.team__rows {
  display: flex;
  flex-direction: column;
}

.team__row {
  display: flex;
  min-height: 52px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.team__avatar {
  display: flex;
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #ba470b;
  background: #fff0e3;
  font-size: 14px;
  font-weight: 700;
  line-height: 1;
}

.team__avatar--bad {
  color: #91a0b4;
  background: #eef2f8;
}

.team__row-text {
  display: flex;
  min-width: 0;
  flex-grow: 1;
  flex-direction: column;
  gap: 1px;
}

.team__row-name {
  color: #202c42;
  font-size: 15px;
  line-height: 21px;
}

.team__row-name--bad {
  color: #738096;
}

.team__row-age {
  margin-left: 6px;
  color: #738096;
  font-size: 13px;
}

.team__row-state {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.team__row-state--ok {
  color: #11804a;
}

.team__row-state--bad {
  color: #d64f67;
}

.team__remove {
  flex-shrink: 0;
  min-height: 44px;
  margin: 0;
  padding: 0 0 0 10px;
  border: 0;
  color: #627187;
  background: none;
  font-size: 13px;
  line-height: 44px;
  white-space: nowrap;
}

.team__empty {
  padding: 16px 0 14px;
  border-top: 1px solid #f0f1f3;
  color: #738096;
  font-size: 13px;
  line-height: 19px;
  text-align: center;
}

.team__card--fee {
  gap: 8px;
  font-size: 13px;
  line-height: 19px;
}

.team__fee-row {
  display: flex;
}

.team__fee-key {
  color: #627187;
}

.team__fee-value {
  margin-left: auto;
}

.team__fee-line {
  height: 1px;
  margin: 4px 0;
  background: #f0f1f3;
}

.team__rule-text {
  color: #536178;
}

.team__agree {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding-top: 4px;
}

.team__agree-toggle {
  flex-shrink: 0;
  margin: 1px 0 0;
  padding: 0;
  border: 0;
  background: transparent;
  line-height: normal;
}

.team__box {
  display: flex;
  width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  border: 1.5px solid #c9d3e1;
  border-radius: 4px;
  background: #fff;
}

.team__box--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.team__agree-text {
  min-width: 0;
  color: #202c42;
}

.team__link {
  color: #ba470b;
}

.team__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #fff;
}

.team__total {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.team__total-line {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.team__total-amount {
  color: #e85400;
  font-size: 20px;
  font-weight: 700;
  line-height: 26px;
}

.team__submit {
  width: 180px;
  height: 46px;
  margin: 0 0 0 auto;
  border: 0;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  white-space: nowrap;
}

.team__submit--off {
  color: #91a0b4;
  background: #eef0f3;
}

.team__mask {
  position: fixed;
  /* Below 999, where the H5 date picker opens; it has to cover this sheet. */
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.45);
}

.team__sheet {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 14px;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  box-sizing: border-box;
  border-radius: 20px 20px 0 0;
  background: #fff;
}

.team__sheet--add {
  gap: 6px;
}

.team__sheet-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.team__steps {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.team__step {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #202c42;
  font-size: 14px;
  line-height: 20px;
}

.team__step-no {
  display: flex;
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  font-weight: 700;
}

.team__paste {
  width: 100%;
  height: 96px;
  padding: 10px 12px;
  box-sizing: border-box;
  border: 1px solid #e5ebf3;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 14px;
  line-height: 20px;
}

.team__sheet-hint {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.team__sheet-actions {
  display: flex;
  gap: 10px;
}

.team__ghost,
.team__primary {
  flex: 1 1 0;
  height: 46px;
  margin: 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
  white-space: nowrap;
}

.team__ghost {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #fff;
}

.team__primary {
  border: 0;
  color: #fff;
  background: #ff6a00;
  font-weight: 500;
  line-height: 46px;
}

.team__primary--block {
  flex: none;
  margin-top: 10px;
  font-size: 16px;
}

.team__pick {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 6px 0 4px;
}

.team__pick-label {
  color: #627187;
  font-size: 13px;
}

.team__pick-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.team__chip {
  margin: 0;
  padding: 0 12px;
  border: 1px solid #ffd8b8;
  border-radius: 999px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  line-height: 30px;
}

.team__sheet-fields {
  display: flex;
  flex-direction: column;
}

.team__skeleton {
  height: 96px;
  border-radius: 14px;
  background: #fff;
}

.team__skeleton--tall {
  height: 240px;
}

.team__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 48px 16px;
}

.team__state-title {
  font-size: 16px;
  font-weight: 700;
}

.team__state-text {
  color: #627187;
  font-size: 13px;
}

.team__state-button {
  padding: 0 24px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #fff;
  font-size: 15px;
  line-height: 40px;
}
</style>
