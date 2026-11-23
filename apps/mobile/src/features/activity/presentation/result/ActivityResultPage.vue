<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { ApiRequestError } from '@/shared/http/public-api-request'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { describeEnrollmentPayState } from '../../application/enrollment-order-view'
import { describeResult } from '../../application/result-view'
import { enrollmentApi } from '../../data/enrollment-api-client'
import type { Enrollment } from '../../domain/enrollment'
import { contactOrganizer } from '../change/contact-organizer'

// 订单详情 after the day (board EnrollResult, S5-3): sign-in records while the activity runs, the
// organiser's result record once it is over, or 未到场. The certificate is the organiser's note (text);
// no file is generated. The board's 活动延期 / 活动取消 frames need an organiser-side flow that does not exist.
const props = defineProps<{
  enrollmentId: string
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  openTicket: [enrollmentId: string]
  // Before the day (after a reschedule, say): 申请退改 (board EnrollResult's kept bar).
  refund: [enrollmentId: string]
}>()

type ResultState =
  | { kind: 'loading' }
  | { kind: 'ready'; enrollment: Enrollment }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<ResultState>({ kind: 'loading' })
let requestVersion = 0

const enrollment = computed(() => (state.value.kind === 'ready' ? state.value.enrollment : null))
const view = computed(() => (enrollment.value ? describeResult(enrollment.value, Date.now()) : null))
const changeable = computed(() =>
  Boolean(enrollment.value && (enrollment.value.reschedulable || (enrollment.value.cancellable && enrollment.value.payment.status !== 'PENDING_PAYMENT'))))
const payState = computed(() => (enrollment.value ? describeEnrollmentPayState(enrollment.value) : null))

// Board EnrollResult's two arrows of 原时间 → 新时间.
const MOVE_ICON = 'M4 12h15M13 6l6 6-6 6'
// The 结果记录 person mark and the certificate mark.
const CERT_ICON = 'M6 3h9l4 4v14H6zM14 3v5h5M9 13l2 2 4-4'

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.enrollmentId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await enrollmentApi.loadEnrollment(token, props.enrollmentId)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', enrollment: loaded }
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400 || error.status === 403)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function contact(): void {
  if (enrollment.value) void contactOrganizer(enrollment.value.activity)
}

watch(() => [props.refreshKey, props.accessToken, props.enrollmentId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="result" data-testid="activity-result">
    <view :class="['result__head', { 'result__head--grey': view?.grey, 'result__head--plain': !view }]">
      <view class="result__bar">
        <button class="result__back" data-testid="activity-result-back" aria-label="返回" @click="emit('back')">
          <LineIcon name="back" :size="22" :color="view ? '#ffffff' : '#202c42'" :stroke-width="2" />
        </button>
        <text class="result__title">订单详情</text>
        <view class="result__back" />
      </view>
      <view v-if="view" class="result__hero">
        <view class="result__hero-line">
          <LineIcon :name="view.heroIcon" :size="22" color="#ffffff" :stroke-width="2.6" />
          <text data-testid="activity-result-title">{{ view.heroTitle }}</text>
        </view>
        <text class="result__hero-sub" data-testid="activity-result-sub">{{ view.heroSub }}</text>
      </view>
    </view>

    <view v-if="state.kind === 'loading'" class="result__body" data-testid="activity-result-loading" aria-label="正在加载">
      <view class="result__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="result__state" data-testid="activity-result-missing">
      <text class="result__state-title">没有找到这笔报名</text>
      <button class="result__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="result__state" data-testid="activity-result-error">
      <text class="result__state-title">加载失败</text>
      <text class="result__state-text">请检查网络后重试</text>
      <button class="result__state-button" data-testid="activity-result-retry" @click="load">重试</button>
    </view>

    <view v-else-if="enrollment && view" class="result__body result__body--lifted">
      <view class="result__card result__card--main">
        <text class="result__activity">{{ enrollment.activity.title }}</text>
        <view v-if="view.move" class="result__move" data-testid="activity-result-move">
          <view class="result__move-side">
            <text class="result__move-label">原时间</text>
            <text class="result__move-old">{{ view.move.from }}</text>
          </view>
          <LineIcon :d="MOVE_ICON" :size="20" color="#ff6a00" :stroke-width="2" />
          <view class="result__move-side">
            <text class="result__move-label result__move-label--new">新时间</text>
            <text class="result__move-new">{{ view.move.to }}</text>
          </view>
        </view>
        <view class="result__facts">
          <template v-for="fact in view.facts" :key="fact.label">
            <text class="result__fact-label">{{ fact.label }}</text>
            <text :class="['result__fact-value', { 'result__fact-value--strong': fact.strong }]">{{ fact.value }}</text>
          </template>
        </view>
        <view class="result__dash" />
        <view class="result__steps" role="list" aria-label="报名进度" data-testid="activity-result-steps">
          <view
            v-for="(step, index) in view.steps"
            :key="index"
            class="result__step"
            role="listitem"
            :data-kind="step.kind"
          >
            <view
              v-if="index > 0"
              :class="['result__step-line', 'result__step-line--left', { 'result__step-line--on': step.kind === 'DONE' || step.kind === 'CURRENT' }]"
            />
            <view
              v-if="index < view.steps.length - 1"
              :class="['result__step-line', 'result__step-line--right', { 'result__step-line--on': view.steps[index + 1].kind === 'DONE' || view.steps[index + 1].kind === 'CURRENT' }]"
            />
            <view :class="['result__dot', `result__dot--${step.kind.toLowerCase()}`]">
              <LineIcon v-if="step.kind === 'DONE'" name="check" :size="11" color="#ffffff" :stroke-width="4" />
              <view v-else-if="step.kind === 'CURRENT'" class="result__dot-inner" />
              <LineIcon v-else-if="step.kind === 'END'" name="close" :size="10" color="#ffffff" :stroke-width="4" />
            </view>
            <text :class="['result__step-title', `result__step-title--${step.kind.toLowerCase()}`]">{{ step.title }}</text>
          </view>
        </view>
        <template v-if="view.phase !== 'CANCELLED'">
          <view class="result__dash" />
          <button class="result__ticket" data-testid="activity-result-ticket" @click="emit('openTicket', enrollment.enrollmentId)">
            <view class="result__ticket-icon">
              <LineIcon name="qr" :size="22" color="#ff6a00" :stroke-width="1.8" />
            </view>
            <view class="result__ticket-text">
              <text class="result__ticket-title">{{ view.ticketTitle }}</text>
              <text class="result__ticket-sub">{{ view.ticketSub }}</text>
            </view>
            <LineIcon name="forward" :size="16" color="#91a0b4" :stroke-width="2" />
          </button>
        </template>
      </view>

      <view v-if="view.showResults" class="result__card result__card--records" data-testid="activity-result-records">
        <view class="result__heading-row">
          <text class="result__heading">结果记录</text>
          <text v-if="view.publishedLine" class="result__heading-note">{{ view.publishedLine }}</text>
        </view>
        <view
          v-for="record in view.records"
          :key="record.ticketId"
          class="result__record"
          :data-testid="`activity-result-record-${record.ticketId}`"
        >
          <view class="result__record-head">
            <view class="result__avatar result__avatar--white">
              <LineIcon name="person" :size="18" color="#738096" :stroke-width="1.8" />
            </view>
            <text class="result__person-name">{{ record.name }}</text>
            <text class="result__person-age">{{ record.age }}</text>
            <text :class="['result__badge', { 'result__badge--off': !record.completed }]">{{ record.badge }}</text>
          </view>
          <view class="result__stats">
            <view v-for="stat in record.stats" :key="stat.label" class="result__stat">
              <text class="result__stat-value">{{ stat.value }}</text>
              <text class="result__stat-label">{{ stat.label }}</text>
            </view>
          </view>
        </view>
        <text v-if="view.records.length === 0" class="result__empty" data-testid="activity-result-pending">
          主办方还没有发布结果，发布后通过“消息”通知你
        </text>
        <view v-if="view.certificateNotes.length > 0" class="result__cert" data-testid="activity-result-certificate">
          <view class="result__cert-icon">
            <LineIcon :d="CERT_ICON" :size="20" color="#ff6a00" :stroke-width="1.8" />
          </view>
          <view class="result__cert-text">
            <text class="result__cert-title">证书说明</text>
            <text v-for="note in view.certificateNotes" :key="note" class="result__cert-note">{{ note }}</text>
          </view>
        </view>
      </view>

      <view v-if="view.showPeople" class="result__card result__card--people" data-testid="activity-result-people">
        <view class="result__heading-row">
          <text class="result__heading">{{ view.peopleTitle }}</text>
          <text class="result__heading-note">{{ view.peopleNote }}</text>
        </view>
        <view v-for="person in view.people" :key="person.ticketId" class="result__person">
          <view class="result__avatar">
            <LineIcon name="person" :size="18" color="#738096" :stroke-width="1.8" />
          </view>
          <text class="result__person-name">{{ person.name }}</text>
          <text class="result__person-age">{{ person.age }}</text>
          <text :class="['result__person-state', `result__person-state--${person.tone.toLowerCase()}`]">{{ person.state }}</text>
        </view>
      </view>

      <view v-if="view.noticeTitle" class="result__card result__card--notice" data-testid="activity-result-notice">
        <text class="result__heading">{{ view.noticeTitle }}</text>
        <view v-for="line in view.notices" :key="line" class="result__notice">
          <view class="result__notice-dot" />
          <text>{{ line }}</text>
        </view>
      </view>

      <view v-if="view.showNext" class="result__card result__card--next">
        <view class="result__next-head">
          <text class="result__heading">接下来，由你决定</text>
          <text class="result__next-sub">想继续练习，可以看看这些</text>
        </view>
        <view class="result__next-grid">
          <button class="result__next-link" data-testid="activity-result-experience" @click="openHomeChannel('EXPERIENCE')">
            <LineIcon name="screenPlay" :size="20" color="#ff6a00" :stroke-width="1.8" />
            <text>在线体验</text>
          </button>
          <button class="result__next-link" data-testid="activity-result-mall" @click="openHomeChannel('MALL')">
            <LineIcon d="M6 8h12l-1 12H7zM9 8V6a3 3 0 0 1 6 0v2" :size="20" color="#ff6a00" :stroke-width="1.8" />
            <text>相关器材</text>
          </button>
        </view>
      </view>

      <view class="result__card result__card--parties" data-testid="activity-result-parties">
        <text class="result__heading">交易主体</text>
        <view class="result__parties">
          <template v-for="row in view.parties" :key="row.label">
            <text class="result__party-label">{{ row.label }}</text>
            <text>{{ row.value }}</text>
          </template>
        </view>
      </view>

      <view class="result__card result__card--money" data-testid="activity-result-money">
        <text class="result__heading result__heading--money">金额与支付</text>
        <view class="result__money-row">
          <text class="result__muted">报名费</text>
          <text class="result__money-right">{{ view.feeLine }}</text>
        </view>
        <view class="result__money-row result__money-row--baseline">
          <text class="result__muted">实付</text>
          <text class="result__paid">{{ view.paidText }}</text>
        </view>
        <template v-if="view.paid">
          <view class="result__line" />
          <view class="result__pay-row">
            <text class="result__pay-label">支付方式</text>
            <text class="result__channel">微信支付</text>
          </view>
          <view v-if="payState" class="result__pay-row">
            <text class="result__pay-label">支付状态</text>
            <text :class="['result__pay-state', `result__pay-state--${payState.tone.toLowerCase()}`]">{{ payState.text }}</text>
          </view>
        </template>
      </view>
    </view>

    <view v-if="enrollment && view" class="result__actions">
      <template v-if="view.bar === 'NOTE'">
        <text class="result__actions-note">活动已开始，不再支持退改</text>
        <button class="result__outline result__outline--fixed" data-testid="activity-result-contact" @click="contact">联系主办方</button>
      </template>
      <template v-else-if="view.bar === 'UPCOMING'">
        <button
          v-if="changeable"
          class="result__outline"
          data-testid="activity-result-refund"
          @click="emit('refund', enrollment.enrollmentId)"
        >
          申请退改
        </button>
        <button v-else class="result__outline" data-testid="activity-result-contact" @click="contact">联系主办方</button>
        <button class="result__primary" data-testid="activity-result-open-ticket" @click="emit('openTicket', enrollment.enrollmentId)">
          {{ view.move ? '查看新凭证' : '查看凭证' }}
        </button>
      </template>
      <template v-else>
        <button class="result__outline" data-testid="activity-result-contact" @click="contact">联系主办方</button>
        <button class="result__primary" data-testid="activity-result-more" @click="openHomeChannel('ACTIVITY')">查看更多活动</button>
      </template>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board EnrollResult (390 wide). */
.result {
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

.result__head {
  flex: none;
  padding: calc(8px + var(--status-bar-height)) 8px 52px;
  color: #ffffff;
  background: linear-gradient(180deg, #ff6a00 0%, #ff8a3d 100%);
}

.result__head--grey {
  background: linear-gradient(180deg, #7d828a 0%, #91a0b4 100%);
}

.result__head--plain {
  padding-bottom: 8px;
  color: #202c42;
  background: var(--app-head-gradient);
}

.result__bar {
  display: flex;
  align-items: center;
}

.result__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.result__title {
  flex: 1;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-align: center;
}

.result__hero {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 8px 0;
}

.result__hero-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 22px;
  font-weight: 700;
  line-height: 30px;
}

.result__hero-sub {
  font-size: 13px;
  font-weight: 500;
  line-height: 19px;
}

.result__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 12px 16px 16px;
}

.result__body--lifted {
  position: relative;
  margin-top: -36px;
  padding-top: 0;
}

.result__skeleton {
  height: 200px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.result__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.result__state-title {
  font-size: 16px;
  font-weight: 700;
}

.result__state-text {
  color: #738096;
  font-size: 13px;
}

.result__state-button {
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

.result__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.result__card--main {
  gap: 0;
  padding: 16px 16px 6px;
}

.result__activity {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.result__move {
  display: grid;
  margin-top: 12px;
  padding: 10px 12px;
  align-items: center;
  gap: 4px;
  grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1fr);
  border-radius: 10px;
  background: #fff0e3;
}

.result__move-side {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.result__move-label {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.result__move-label--new {
  color: #ba470b;
}

.result__move-old {
  color: #738096;
  font-size: 14px;
  line-height: 20px;
  text-decoration: line-through;
  white-space: nowrap;
}

.result__move-new {
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
  white-space: nowrap;
}

.result__facts {
  display: grid;
  margin-top: 12px;
  gap: 8px 10px;
  grid-template-columns: 56px minmax(0, 1fr);
  font-size: 13px;
  line-height: 19px;
}

.result__fact-label {
  color: #738096;
}

.result__fact-value--strong {
  font-weight: 500;
}

.result__dash {
  margin-top: 14px;
  border-top: 1px dashed #e5ebf3;
}

.result__steps {
  display: grid;
  padding: 14px 0 10px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.result__step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.result__step-line {
  position: absolute;
  top: 9px;
  height: 2px;
  background: #e5ebf3;
}

.result__step-line--left {
  right: 50%;
  left: 0;
}

.result__step-line--right {
  right: 0;
  left: 50%;
}

.result__step-line--on {
  background: #ff6a00;
}

.result__dot {
  position: relative;
  display: flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border: 2px solid #c9d3e1;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.result__dot--done {
  border-color: #ff6a00;
  background: #ff6a00;
}

.result__dot--current {
  border-color: #ff6a00;
}

.result__dot--end {
  border-color: #738096;
  background: #738096;
}

.result__dot-inner {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ff6a00;
}

.result__step-title {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.result__step-title--done {
  color: #202c42;
  font-weight: 500;
}

.result__step-title--current {
  color: #ba470b;
  font-weight: 700;
}

.result__step-title--end {
  color: #536178;
  font-weight: 700;
}

.result__dash + .result__ticket {
  margin-top: 0;
}

.result__ticket {
  display: flex;
  width: 100%;
  min-height: 56px;
  align-items: center;
  gap: 10px;
  color: #202c42;
  text-align: left;
}

.result__ticket-icon,
.result__cert-icon {
  display: flex;
  width: 36px;
  height: 36px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: #fff0e3;
}

.result__ticket-text,
.result__cert-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.result__ticket-title,
.result__cert-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.result__ticket-sub,
.result__cert-note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.result__heading-row {
  display: flex;
  align-items: baseline;
}

.result__heading {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.result__heading--money {
  margin-bottom: 2px;
}

.result__heading-note {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.result__record {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border-radius: 12px;
  background: #f5f7fc;
}

.result__record-head,
.result__person {
  display: flex;
  align-items: center;
  gap: 10px;
}

.result__avatar {
  display: flex;
  width: 32px;
  height: 32px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef2f8;
}

.result__avatar--white {
  background: #ffffff;
}

.result__person-name {
  font-size: 15px;
  line-height: 21px;
}

.result__person-age {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.result__badge {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.result__badge--off {
  color: #536178;
  background: #eef2f8;
}

.result__stats {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.result__stat {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.result__stat-value {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-align: center;
  word-break: break-all;
}

.result__stat-label {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.result__empty {
  color: #738096;
  font-size: 13px;
  line-height: 19px;
}

.result__cert {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 10px;
}

.result__person-state {
  margin-left: auto;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.result__person-state--done {
  color: #11804a;
}

.result__person-state--pending {
  color: #ba470b;
}

.result__person-state--muted {
  color: #738096;
}

.result__card--notice {
  gap: 8px;
}

.result__notice {
  display: flex;
  gap: 8px;
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.result__notice-dot {
  width: 4px;
  height: 4px;
  flex: none;
  margin: 8px 2px 0;
  border-radius: 50%;
  background: #91a0b4;
}

.result__next-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.result__next-sub {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.result__next-grid {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.result__next-link {
  display: flex;
  min-height: 52px;
  padding: 0 12px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  color: #202c42;
  background: #f5f7fc;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.result__parties {
  display: grid;
  gap: 8px 10px;
  grid-template-columns: 64px minmax(0, 1fr);
  font-size: 13px;
  line-height: 19px;
}

.result__party-label,
.result__muted,
.result__pay-label {
  color: #627187;
}

.result__card--money {
  gap: 8px;
  font-size: 13px;
  line-height: 19px;
}

.result__money-row {
  display: flex;
}

.result__money-row--baseline {
  align-items: baseline;
}

.result__money-right {
  margin-left: auto;
}

.result__paid {
  margin-left: auto;
  color: #e85400;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.result__line {
  height: 1px;
  margin: 4px 0;
  background: #f0f1f3;
}

.result__pay-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.result__pay-label {
  width: 72px;
  flex: none;
}

.result__channel {
  color: #536178;
}

.result__pay-state--done {
  color: #11804a;
}

.result__pay-state--pending {
  color: #ba470b;
}

.result__pay-state--muted {
  color: #738096;
}

.result__actions {
  position: sticky;
  bottom: 0;
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.result__actions-note {
  min-width: 0;
  flex: 1;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.result__outline {
  display: flex;
  height: 46px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 15px;
  white-space: nowrap;
  box-sizing: border-box;
}

.result__outline--fixed {
  flex: none;
  padding: 0 22px;
}

.result__primary {
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
</style>
