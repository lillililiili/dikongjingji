<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import { ticketQrImage } from '@/shared/qr/ticket-qr'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  formatEnrollmentCheckIn,
  formatEnrollmentFee,
  formatEnrollmentSession,
} from '../../application/enrollment-order-view'
import {
  describeTeamOrderHead,
  teamMemberState,
  teamOrderPhase,
  teamRuleLine,
  teamRules,
  teamSignSummary,
} from '../../application/team-roster'
import { enrollmentApi } from '../../data/enrollment-api-client'
import type { Enrollment, EnrollmentTicket } from '../../domain/enrollment'
import { formatActivityAmount, formatActivityShortDateTime } from '../shared/activity-format'

// Board TeamOrder (S5-3): the leader's view of a team sign-up — the roster with each member's code,
// one code shown at a time to be scanned at the door, the payment, and per-member refunds.

const props = defineProps<{
  enrollmentId: string
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  pay: [tradeOrderId: string]
  // Refund some members (ticketId: start with that one ticked).
  refund: [ticketId: string | null]
  activity: [activityId: string, cityCode: string]
}>()

type PageState =
  | { kind: 'loading' }
  | { kind: 'ready'; enrollment: Enrollment }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<PageState>({ kind: 'loading' })
const now = ref(Date.now())
const shownTicketId = ref<string | null>(null)
let requestVersion = 0
let timer: ReturnType<typeof setInterval> | null = null

const enrollment = computed(() => (state.value.kind === 'ready' ? state.value.enrollment : null))
const head = computed(() => (enrollment.value ? describeTeamOrderHead(enrollment.value, now.value) : null))
const phase = computed(() => (enrollment.value ? teamOrderPhase(enrollment.value, now.value) : null))
// Members whose code can be shown: everyone not cancelled (a pending payment has no code yet).
const withCodes = computed(() => enrollment.value?.tickets.filter((ticket) => ticket.ticketCode) ?? [])
const shown = computed<EnrollmentTicket | null>(() =>
  withCodes.value.find((ticket) => ticket.ticketId === shownTicketId.value) ?? withCodes.value[0] ?? null)
const qrImage = computed(() => (shown.value?.ticketCode ? ticketQrImage(shown.value.ticketCode) : ''))
const paidOrder = computed(() => enrollment.value !== null && enrollment.value.payment.status !== 'FREE')
const rule = computed(() => (enrollment.value ? teamRuleLine(enrollment.value) : null))
const leaderInitial = computed(() => enrollment.value?.team?.leaderName.slice(0, 1) ?? '')
const refundedText = computed(() => {
  const refunds = enrollment.value?.tickets.filter((ticket) => ticket.refund) ?? []
  if (refunds.length === 0) return null
  const amount = refunds.reduce((sum, ticket) => sum + (ticket.refund?.amountMinor ?? 0), 0)
  return `${refunds.length} 人已退 ${formatActivityAmount(amount)}`
})

const HERO_ORANGE = 'linear-gradient(180deg, #ff6a00 0%, #ff8a3d 100%)'
const HERO_GRAY = 'linear-gradient(180deg, #7d828a 0%, #91a0b4 100%)'

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

function memberAge(ticket: EnrollmentTicket): string {
  return ticket.age === null ? '' : `${ticket.age} 岁`
}

function canRefund(ticket: EnrollmentTicket): boolean {
  return Boolean(enrollment.value?.cancellable) && ticket.status === 'ACTIVE'
}

function shownHint(ticket: EnrollmentTicket): string {
  if (ticket.status === 'CHECKED_IN') {
    return `已于 ${ticket.checkedInAt ? formatActivityShortDateTime(ticket.checkedInAt) : ''} 核销，不能重复使用`
  }
  if (ticket.status === 'EXPIRED') return '活动已结束，签到码已失效'
  return '到场出示此码签到 · 一人一码，不能代替他人使用'
}

async function load(): Promise<void> {
  if (!props.accessToken) {
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
    const loaded = await enrollmentApi.loadEnrollment(props.accessToken, props.enrollmentId)
    if (requested !== requestVersion) return
    now.value = Date.now()
    state.value = { kind: 'ready', enrollment: loaded }
    if (loaded.payment.status === 'PENDING_PAYMENT' && !timer) {
      timer = setInterval(() => { now.value = Date.now() }, 1000)
    } else if (loaded.payment.status !== 'PENDING_PAYMENT' && timer) {
      clearInterval(timer)
      timer = null
    }
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

function runBar(): void {
  const current = enrollment.value
  if (!current) return
  if (current.payment.status === 'PENDING_PAYMENT' && current.payment.tradeOrderId) {
    emit('pay', current.payment.tradeOrderId)
    return
  }
  if (current.cancellable) {
    emit('refund', null)
    return
  }
  emit('activity', current.activity.activityId, current.activity.cityCode)
}

const barLabel = computed(() => {
  const current = enrollment.value
  if (!current) return ''
  if (current.payment.status === 'PENDING_PAYMENT') return '去付款'
  return current.cancellable ? '申请退改' : '查看活动'
})

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view class="team-order" data-testid="activity-team-order">
    <view
      class="team-order__hero"
      :style="{ background: head?.tone === 'GRAY' ? HERO_GRAY : HERO_ORANGE }"
    >
      <view class="team-order__bar-top">
        <button class="team-order__back" aria-label="返回" data-testid="activity-team-order-back" @click="emit('back')">
          <LineIcon name="back" :size="24" color="#ffffff" />
        </button>
        <text class="team-order__title">团队报名订单</text>
        <view class="team-order__back-gap" />
      </view>
      <view v-if="head" class="team-order__hero-text">
        <view class="team-order__hero-title" data-testid="activity-team-order-status" :data-phase="phase">
          <LineIcon :name="head.icon" :size="24" color="#ffffff" :stroke-width="2.4" />
          <text>{{ head.title }}</text>
        </view>
        <text class="team-order__hero-sub">{{ head.sub }}</text>
      </view>
    </view>

    <view v-if="state.kind === 'loading'" class="team-order__body" data-testid="activity-team-order-loading">
      <view class="team-order__skeleton" />
    </view>

    <view v-else-if="enrollment" class="team-order__body">
      <view class="team-order__card team-order__card--main">
        <text class="team-order__activity" data-testid="activity-team-order-title">{{ enrollment.activity.title }}</text>
        <view class="team-order__grid">
          <text class="team-order__key">场次</text>
          <text class="team-order__strong">{{ formatEnrollmentSession(enrollment) }}</text>
          <text class="team-order__key">场馆</text>
          <text>{{ enrollment.location.name }}</text>
          <text class="team-order__key">签到时间</text>
          <text>{{ formatEnrollmentCheckIn(enrollment) ?? '开始前签到' }} · 凭队员各自的码入场</text>
          <template v-if="enrollment.team">
            <text class="team-order__key">团队</text>
            <text class="team-order__team" data-testid="activity-team-order-team">{{ enrollment.team.teamName }}</text>
          </template>
        </view>
        <template v-if="enrollment.team">
          <view class="team-order__dash" />
          <view class="team-order__leader" data-testid="activity-team-order-leader">
            <view class="team-order__leader-avatar"><text>{{ leaderInitial }}</text></view>
            <view class="team-order__leader-text">
              <text class="team-order__leader-name">
                {{ enrollment.team.leaderName }}<text v-if="enrollment.team.leaderPhoneMasked" class="team-order__leader-phone">{{ enrollment.team.leaderPhoneMasked }}</text>
              </text>
              <text class="team-order__leader-note">领队 · 负责到场组织和协助签到</text>
            </view>
            <text class="team-order__tag">领队</text>
          </view>
        </template>
      </view>

      <view class="team-order__card" data-testid="activity-team-order-members">
        <view class="team-order__card-head">
          <text class="team-order__heading">队员名单</text>
          <text class="team-order__aside" data-testid="activity-team-order-summary">{{ teamSignSummary(enrollment, now) }}</text>
        </view>
        <view class="team-order__members">
          <view
            v-for="ticket in enrollment.tickets"
            :key="ticket.ticketId"
            class="team-order__member"
            data-testid="activity-team-order-member"
            :data-name="ticket.participantName"
          >
            <view :class="['team-order__avatar', `team-order__avatar--${teamMemberState(ticket).tone.toLowerCase()}`]">
              <text>{{ ticket.participantName.slice(0, 1) }}</text>
            </view>
            <view class="team-order__member-text">
              <text class="team-order__member-name">
                {{ ticket.participantName }}<text class="team-order__member-age">{{ memberAge(ticket) }}</text>
              </text>
              <text class="team-order__member-code">
                {{ ticket.ticketCode ? `凭证号 ${ticket.ticketCode}` : ticket.status === 'PENDING_PAYMENT' ? '付款后生成凭证号' : '凭证已作废' }}
              </text>
            </view>
            <view class="team-order__member-side">
              <text :class="['team-order__member-state', `team-order__member-state--${teamMemberState(ticket).tone.toLowerCase()}`]">
                {{ teamMemberState(ticket).text }}
              </text>
              <view class="team-order__member-actions">
                <button
                  v-if="canRefund(ticket)"
                  class="team-order__pill"
                  :aria-label="`为 ${ticket.participantName} 申请退改`"
                  data-testid="activity-team-order-refund"
                  @click="emit('refund', ticket.ticketId)"
                >
                  退改
                </button>
                <button
                  v-if="ticket.ticketCode"
                  :class="['team-order__pill', { 'team-order__pill--on': shown?.ticketId === ticket.ticketId }]"
                  :aria-pressed="shown?.ticketId === ticket.ticketId"
                  :aria-label="`出示 ${ticket.participantName} 的签到码`"
                  data-testid="activity-team-order-show"
                  @click="shownTicketId = ticket.ticketId"
                >
                  {{ shown?.ticketId === ticket.ticketId ? '出示中' : '出示码' }}
                </button>
              </view>
            </view>
          </view>
        </view>
        <view v-if="shown && shown.ticketCode" class="team-order__qr-box" data-testid="activity-team-order-qr">
          <text class="team-order__qr-title">{{ shown.participantName }} 的签到码</text>
          <view class="team-order__qr">
            <image
              :class="['team-order__qr-image', { 'team-order__qr-image--off': shown.status !== 'ACTIVE' }]"
              :src="qrImage"
              mode="aspectFit"
            />
            <text v-if="shown.status === 'CHECKED_IN'" class="team-order__stamp">已核销</text>
          </view>
          <text class="team-order__qr-code">凭证号 {{ shown.ticketCode }}</text>
          <text :class="['team-order__qr-hint', { 'team-order__qr-hint--done': shown.status === 'CHECKED_IN' }]">{{ shownHint(shown) }}</text>
        </view>
      </view>

      <view class="team-order__card team-order__card--assist">
        <LineIcon name="scan" :size="20" color="#ba470b" />
        <view class="team-order__assist-text">
          <text class="team-order__assist-title">协助签到</text>
          <text class="team-order__assist-note">到场后领队在本页逐个出示队员的码，由现场核销员在 App“工作模式”扫码签到。</text>
        </view>
      </view>

      <view v-if="paidOrder" class="team-order__card team-order__card--fee" data-testid="activity-team-order-pay">
        <text class="team-order__heading team-order__heading--fee">金额与支付</text>
        <view class="team-order__row">
          <text class="team-order__row-key">报名费</text>
          <text class="team-order__row-value">{{ formatEnrollmentFee(enrollment) }}</text>
        </view>
        <view v-if="enrollment.team" class="team-order__row">
          <text class="team-order__row-key">付款人</text>
          <text class="team-order__row-value">领队 {{ enrollment.team.leaderName }} · 一次付清</text>
        </view>
        <view class="team-order__row team-order__row--baseline">
          <text class="team-order__row-key">{{ enrollment.payment.status === 'PENDING_PAYMENT' ? '应付' : '实付' }}</text>
          <text class="team-order__amount">{{ enrollment.payment.amountMinor ? formatActivityAmount(enrollment.payment.amountMinor) : '' }}</text>
        </view>
        <view class="team-order__line" />
        <view class="team-order__row">
          <text class="team-order__row-key team-order__row-key--fixed">收款方</text>
          <text class="team-order__row-text">{{ enrollment.activity.organizerName }} · 经平台支付渠道</text>
        </view>
        <view class="team-order__row">
          <text class="team-order__row-key team-order__row-key--fixed">支付方式</text>
          <text class="team-order__row-text">微信支付</text>
        </view>
        <view class="team-order__row">
          <text class="team-order__row-key team-order__row-key--fixed">支付状态</text>
          <text
            :class="['team-order__row-text', enrollment.payment.status === 'PAID' ? 'team-order__ok' : 'team-order__wait']"
          >
            {{ enrollment.payment.status === 'PAID' ? '已支付 · 渠道回执已核验'
              : enrollment.payment.status === 'PENDING_PAYMENT' ? '待付款'
                : '没有付款，已关闭' }}
          </text>
        </view>
        <view v-if="refundedText" class="team-order__row">
          <text class="team-order__row-key team-order__row-key--fixed">退款</text>
          <text class="team-order__row-text">{{ refundedText }}</text>
        </view>
      </view>

      <view v-if="rule" class="team-order__card">
        <text class="team-order__heading">退改规则</text>
        <view :class="['team-order__rule-strip', { 'team-order__rule-strip--closed': !rule.open }]">
          <LineIcon :name="rule.open ? 'clock' : 'info'" :size="18" :color="rule.open ? '#159a70' : '#738096'" :stroke-width="2" />
          <text>{{ rule.text }}</text>
        </view>
        <view v-for="(text, index) in teamRules(enrollment)" :key="index" class="team-order__rule">
          <view class="team-order__rule-dot" />
          <text class="team-order__rule-text">{{ text }}</text>
        </view>
      </view>
    </view>

    <view v-else-if="state.kind === 'missing'" class="team-order__state" data-testid="activity-team-order-missing">
      <text class="team-order__state-title">报名单不存在</text>
      <button class="team-order__state-button" @click="emit('back')">返回</button>
    </view>
    <view v-else class="team-order__state" data-testid="activity-team-order-error">
      <text class="team-order__state-title">加载失败</text>
      <button class="team-order__state-button" @click="load">重试</button>
    </view>

    <view v-if="enrollment && rule" class="team-order__bar" data-testid="activity-team-order-bar">
      <text :class="['team-order__bar-note', { 'team-order__bar-note--open': rule.open }]">{{ rule.text }}</text>
      <button
        :class="['team-order__bar-button', { 'team-order__bar-button--primary': enrollment.payment.status === 'PENDING_PAYMENT' }]"
        data-testid="activity-team-order-action"
        @click="runBar"
      >
        {{ barLabel }}
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board TeamOrder (390 wide). */
.team-order {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  font-family: 'PingFang SC', 'Noto Sans SC', sans-serif;
}

.team-order__hero {
  flex-shrink: 0;
  padding: calc(var(--status-bar-height) + 8px) 8px 52px;
  color: #fff;
}

.team-order__bar-top {
  display: flex;
  align-items: center;
}

.team-order__back,
.team-order__back-gap {
  display: flex;
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
}

.team-order__back::after,
.team-order__pill::after,
.team-order__bar-button::after,
.team-order__state-button::after {
  border: 0;
}

.team-order__title {
  flex-grow: 1;
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-align: center;
}

.team-order__hero-text {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 8px 0;
}

.team-order__hero-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 22px;
  font-weight: 700;
  line-height: 30px;
}

.team-order__hero-sub {
  font-size: 13px;
  font-weight: 500;
  line-height: 19px;
}

.team-order__body {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  margin-top: -36px;
  padding: 0 16px 16px;
}

.team-order__card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: 14px;
  background: #fff;
}

.team-order__card--main {
  gap: 0;
  padding: 16px 16px 6px;
}

.team-order__activity {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.team-order__grid {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 8px 10px;
  margin-top: 12px;
  font-size: 13px;
  line-height: 19px;
}

.team-order__key {
  color: #738096;
}

.team-order__strong {
  font-weight: 500;
}

.team-order__team {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.team-order__dash {
  margin-top: 14px;
  border-top: 1px dashed #e5ebf3;
}

.team-order__leader {
  display: flex;
  min-height: 56px;
  align-items: center;
  gap: 10px;
}

.team-order__leader-avatar {
  display: flex;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #ba470b;
  background: #fff0e3;
  font-size: 15px;
  font-weight: 700;
  line-height: 1;
}

.team-order__leader-text {
  display: flex;
  min-width: 0;
  flex-grow: 1;
  flex-direction: column;
  gap: 2px;
}

.team-order__leader-name {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.team-order__leader-phone {
  margin-left: 8px;
  color: #627187;
  font-size: 13px;
  font-weight: 400;
}

.team-order__leader-note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.team-order__tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.team-order__card-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.team-order__heading {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.team-order__heading--fee {
  margin-bottom: 2px;
}

.team-order__aside {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.team-order__members {
  display: flex;
  flex-direction: column;
  margin-top: -2px;
}

.team-order__member {
  display: flex;
  min-height: 64px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.team-order__avatar {
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

.team-order__avatar--done {
  color: #11804a;
  background: #e2f6ed;
}

.team-order__avatar--muted {
  color: #91a0b4;
  background: #eef2f8;
}

.team-order__member-text {
  display: flex;
  min-width: 0;
  flex-grow: 1;
  flex-direction: column;
  gap: 2px;
}

.team-order__member-name {
  font-size: 15px;
  line-height: 21px;
  white-space: nowrap;
}

.team-order__member-age {
  margin-left: 6px;
  color: #738096;
  font-size: 13px;
}

.team-order__member-code {
  overflow: hidden;
  color: #738096;
  font-size: 11px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.team-order__member-side {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.team-order__member-state {
  font-size: 12px;
  font-weight: 500;
  line-height: 17px;
  white-space: nowrap;
}

.team-order__member-state--done {
  color: #11804a;
}

.team-order__member-state--pending {
  color: #ba470b;
}

.team-order__member-state--muted {
  color: #738096;
}

.team-order__member-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.team-order__pill {
  height: 32px;
  margin: 0;
  padding: 0 10px;
  box-sizing: border-box;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #fff;
  font-size: 13px;
  font-weight: 500;
  line-height: 30px;
  white-space: nowrap;
}

.team-order__pill--on {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fff0e3;
}

.team-order__qr-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-top: 2px;
  padding: 12px 12px 10px;
  border-radius: 12px;
  background: #f5f7fc;
}

.team-order__qr-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  white-space: nowrap;
}

.team-order__qr {
  position: relative;
  width: 148px;
  height: 148px;
  margin-top: 8px;
}

.team-order__qr-image {
  width: 148px;
  height: 148px;
}

.team-order__qr-image--off {
  opacity: 0.18;
}

.team-order__stamp {
  position: absolute;
  left: 36px;
  top: 36px;
  display: flex;
  width: 76px;
  height: 76px;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  border: 2px solid #11804a;
  border-radius: 50%;
  color: #11804a;
  background: #fff;
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
}

.team-order__qr-code {
  margin-top: 8px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.team-order__qr-hint {
  margin-top: 2px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.team-order__qr-hint--done {
  color: #536178;
}

.team-order__card--assist {
  flex-direction: row;
  gap: 8px;
  padding: 12px 14px;
}

.team-order__assist-text {
  display: flex;
  min-width: 0;
  flex-grow: 1;
  flex-direction: column;
  gap: 2px;
}

.team-order__assist-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.team-order__assist-note {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.team-order__card--fee {
  font-size: 13px;
  line-height: 19px;
}

.team-order__row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.team-order__row--baseline {
  align-items: baseline;
}

.team-order__row-key {
  color: #627187;
}

.team-order__row-key--fixed {
  flex-shrink: 0;
  width: 72px;
}

.team-order__row-value {
  margin-left: auto;
}

.team-order__row-text {
  min-width: 0;
}

.team-order__amount {
  margin-left: auto;
  color: #e85400;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.team-order__ok {
  color: #11804a;
}

.team-order__wait {
  color: #ba470b;
}

.team-order__line {
  height: 1px;
  margin: 4px 0;
  background: #f0f1f3;
}

.team-order__rule-strip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 10px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.team-order__rule-strip--closed {
  color: #536178;
  background: #eef2f8;
}

.team-order__rule {
  display: flex;
  gap: 8px;
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.team-order__rule-dot {
  flex-shrink: 0;
  width: 4px;
  height: 4px;
  margin: 8px 2px 0;
  border-radius: 50%;
  background: #91a0b4;
}

.team-order__rule-text {
  min-width: 0;
}

.team-order__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px calc(20px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #fff;
}

.team-order__bar-note {
  min-width: 0;
  flex-grow: 1;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.team-order__bar-note--open {
  color: #11804a;
}

.team-order__bar-button {
  flex-shrink: 0;
  height: 44px;
  margin: 0;
  padding: 0 22px;
  box-sizing: border-box;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #fff;
  font-size: 15px;
  line-height: 42px;
  white-space: nowrap;
}

.team-order__bar-button--primary {
  border-color: #ff6a00;
  color: #fff;
  background: #ff6a00;
}

.team-order__skeleton {
  height: 220px;
  border-radius: 14px;
  background: #fff;
}

.team-order__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 48px 16px;
}

.team-order__state-title {
  font-size: 16px;
  font-weight: 700;
}

.team-order__state-button {
  padding: 0 24px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #fff;
  font-size: 15px;
  line-height: 40px;
}
</style>
