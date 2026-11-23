<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  abandonSheetText,
  describeWaitlist,
  formatWaitlistSession,
  isPaidWaitlist,
  waitlistMemberAge,
} from '../../application/waitlist-view'
import { enrollmentApi } from '../../data/enrollment-api-client'
import type { Waitlist } from '../../domain/enrollment'
import { formatActivityShortDateTime } from '../shared/activity-format'

// Board EnrollWaitlist (S5-3): one waitlist, its place in the queue, the held seats after an offer,
// and what can be done now (give up, pay or confirm, look at the ticket). Every state is the server's.

const props = defineProps<{
  waitlistId: string
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  // The trade cashier of the order a paid offer made.
  pay: [tradeOrderId: string]
  // The ticket of the sign-up the offer turned into.
  ticket: [enrollmentId: string]
  // The activity, to look at it or join again.
  activity: [activityId: string, cityCode: string]
  // Other activities: the 赛事研学 channel.
  more: []
}>()

type PageState =
  | { kind: 'loading' }
  | { kind: 'ready'; waitlist: Waitlist }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<PageState>({ kind: 'loading' })
const now = ref(Date.now())
const sheetOpen = ref(false)
const busy = ref(false)
let requestVersion = 0
let timer: ReturnType<typeof setInterval> | null = null

const waitlist = computed(() => (state.value.kind === 'ready' ? state.value.waitlist : null))
const view = computed(() => (waitlist.value ? describeWaitlist(waitlist.value, now.value) : null))
const paid = computed(() => (waitlist.value ? isPaidWaitlist(waitlist.value) : false))
const memberNames = computed(() => waitlist.value?.members.map((member) => member.name).join('、') ?? '')

const HERO_ORANGE = 'linear-gradient(180deg, #ff6a00 0%, #ff8a3d 100%)'
const HERO_GRAY = 'linear-gradient(180deg, #7d828a 0%, #91a0b4 100%)'

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

function syncTimer(): void {
  const offered = waitlist.value?.status === 'OFFERED'
  if (offered && !timer) {
    timer = setInterval(() => {
      now.value = Date.now()
      // The hold ran out: the server decides what it is now.
      if (waitlist.value?.holdUntil && Date.now() >= new Date(waitlist.value.holdUntil).getTime()) {
        void load()
      }
    }, 1000)
  } else if (!offered && timer) {
    clearInterval(timer)
    timer = null
  }
}

async function load(): Promise<void> {
  if (!props.accessToken) {
    emit('login')
    return
  }
  if (!props.waitlistId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await enrollmentApi.loadWaitlist(props.accessToken, props.waitlistId)
    if (requested !== requestVersion) return
    now.value = Date.now()
    state.value = { kind: 'ready', waitlist: loaded }
    syncTimer()
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

function describeFailure(error: unknown): string {
  if (!(error instanceof ApiRequestError)) return '操作失败，请稍后重试'
  if (error.code === 'ACT_WAITLIST_STATE_INVALID') return '保留时间已过或状态已变化，已刷新'
  if (error.code === 'ACTIVITY_AMOUNT_CHANGED') return '报名费有调整，已更新为最新金额，请确认后再付款'
  if (error.code === 'ACTIVITY_PAYMENT_UNAVAILABLE') return '主办方暂未开通在线支付'
  if (error.status === 503 || error.code === 'TRADE_CHANNEL_UNAVAILABLE') return '微信支付暂不可用，请稍后再试'
  return error.message
}

// 去付款 / 确认报名: a paid offer that has no order yet gets one first, then goes to the cashier.
async function takeOffer(): Promise<void> {
  const current = waitlist.value
  const token = props.accessToken
  if (!current || !token || busy.value) return
  if (current.tradeOrderId) {
    emit('pay', current.tradeOrderId)
    return
  }
  busy.value = true
  try {
    const confirmed = await enrollmentApi.confirmWaitlist(
      token,
      current.waitlistId,
      paid.value && current.amountMinor !== null ? current.amountMinor : undefined,
    )
    state.value = { kind: 'ready', waitlist: confirmed }
    syncTimer()
    if (confirmed.tradeOrderId) {
      emit('pay', confirmed.tradeOrderId)
    } else if (confirmed.status === 'ENROLLED' && confirmed.enrollmentId) {
      emit('ticket', confirmed.enrollmentId)
    }
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({ title: describeFailure(error), icon: 'none', duration: 2500 })
    void load()
  } finally {
    busy.value = false
  }
}

async function confirmAbandon(): Promise<void> {
  const current = waitlist.value
  const token = props.accessToken
  if (!current || !token || busy.value) return
  busy.value = true
  try {
    const after = await enrollmentApi.abandonWaitlist(token, current.waitlistId)
    sheetOpen.value = false
    state.value = { kind: 'ready', waitlist: after }
    syncTimer()
    if (after.status === 'ENROLLED') {
      uni.showToast({ title: '已经付款成功，报名已生效', icon: 'none' })
    }
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    sheetOpen.value = false
    uni.showToast({ title: describeFailure(error), icon: 'none', duration: 2500 })
    void load()
  } finally {
    busy.value = false
  }
}

function openActivity(): void {
  if (waitlist.value) emit('activity', waitlist.value.activity.activityId, waitlist.value.activity.cityCode)
}

function lineColor(kind: string): string {
  return kind === 'done' || kind === 'cur' ? '#ff6a00' : '#e5ebf3'
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view class="waitlist" data-testid="activity-waitlist">
    <view
      class="waitlist__hero"
      :style="{ background: view?.tone === 'GRAY' ? HERO_GRAY : HERO_ORANGE }"
      data-testid="activity-waitlist-hero"
      :data-tone="view?.tone ?? 'ORANGE'"
    >
      <view class="waitlist__bar">
        <button class="waitlist__back" aria-label="返回" data-testid="activity-waitlist-back" @click="emit('back')">
          <LineIcon name="back" :size="24" color="#ffffff" />
        </button>
        <text class="waitlist__title">候补详情</text>
        <view class="waitlist__back-gap" />
      </view>
      <view v-if="view" class="waitlist__hero-text">
        <view class="waitlist__hero-title" data-testid="activity-waitlist-status">
          <LineIcon :name="view.heroIcon" :size="24" color="#ffffff" :stroke-width="2.4" />
          <text>{{ view.heroTitle }}</text>
        </view>
        <text class="waitlist__hero-sub">{{ view.heroSub }}</text>
      </view>
    </view>

    <view v-if="state.kind === 'loading'" class="waitlist__body" data-testid="activity-waitlist-loading">
      <view class="waitlist__skeleton" />
      <view class="waitlist__skeleton waitlist__skeleton--short" />
    </view>

    <view v-else-if="waitlist && view" class="waitlist__body">
      <view class="waitlist__card waitlist__card--main">
        <view class="waitlist__facts">
          <text class="waitlist__activity" data-testid="activity-waitlist-title">{{ waitlist.activity.title }}</text>
          <view class="waitlist__grid">
            <text class="waitlist__key">场次</text>
            <text class="waitlist__value waitlist__value--strong" data-testid="activity-waitlist-session">{{ formatWaitlistSession(waitlist) }}</text>
            <template v-if="waitlist.session.venueName">
              <text class="waitlist__key">场馆</text>
              <text class="waitlist__value">{{ waitlist.session.venueName }}</text>
            </template>
            <text class="waitlist__key">加入时间</text>
            <text class="waitlist__value">{{ formatActivityShortDateTime(waitlist.createdAt) }}</text>
          </view>
        </view>
        <view
          :class="['waitlist__note', { 'waitlist__note--gray': view.tone === 'GRAY' }]"
          data-testid="activity-waitlist-note"
        >
          <LineIcon :name="view.noteIcon" :size="18" :color="view.tone === 'GRAY' ? '#536178' : '#ba470b'" :stroke-width="2" />
          <text class="waitlist__note-text">{{ view.note }}</text>
        </view>
        <view class="waitlist__dash" />
        <view class="waitlist__steps" role="list" aria-label="候补进度" data-testid="activity-waitlist-steps">
          <view
            v-for="(step, index) in view.steps"
            :key="index"
            class="waitlist__step"
            role="listitem"
            :data-kind="step.kind"
          >
            <view
              class="waitlist__step-line waitlist__step-line--left"
              :style="{ background: index === 0 ? 'transparent' : lineColor(step.kind) }"
            />
            <view
              class="waitlist__step-line waitlist__step-line--right"
              :style="{ background: index === view.steps.length - 1 ? 'transparent' : lineColor(view.steps[index + 1].kind) }"
            />
            <view :class="['waitlist__dot', `waitlist__dot--${step.kind}`]">
              <LineIcon v-if="step.kind === 'done'" name="check" :size="12" color="#ffffff" :stroke-width="3" />
              <view v-else-if="step.kind === 'cur'" class="waitlist__dot-inner" />
              <LineIcon v-else-if="step.kind === 'end'" name="close" :size="10" color="#ffffff" :stroke-width="3" />
            </view>
            <text :class="['waitlist__step-label', `waitlist__step-label--${step.kind}`]">{{ step.label }}</text>
          </view>
        </view>
      </view>

      <view class="waitlist__card">
        <view class="waitlist__card-head">
          <text class="waitlist__heading">参与人</text>
          <text class="waitlist__card-aside">{{ waitlist.people }} 人一起候补</text>
        </view>
        <view
          v-for="member in waitlist.members"
          :key="member.participantId"
          class="waitlist__member"
          data-testid="activity-waitlist-member"
        >
          <view class="waitlist__avatar">
            <LineIcon name="person" :size="18" color="#738096" />
          </view>
          <text class="waitlist__member-name">{{ member.name }}</text>
          <text class="waitlist__member-age">{{ waitlistMemberAge(member.age) }}</text>
        </view>
      </view>

      <view class="waitlist__card waitlist__card--fee" data-testid="activity-waitlist-fee">
        <text class="waitlist__heading waitlist__heading--fee">费用</text>
        <view class="waitlist__row">
          <text class="waitlist__row-key">报名费</text>
          <text class="waitlist__row-value">{{ view.feeLine ?? '免费' }}</text>
        </view>
        <view class="waitlist__row waitlist__row--baseline">
          <text class="waitlist__row-key">{{ view.payLabel }}</text>
          <text
            :class="['waitlist__pay', `waitlist__pay--${view.payTone.toLowerCase()}`]"
            data-testid="activity-waitlist-pay"
          >
            {{ view.payValue }}
          </text>
        </view>
        <template v-if="paid">
          <view class="waitlist__rule-line" />
          <view class="waitlist__row">
            <text class="waitlist__row-key waitlist__row-key--fixed">收款方</text>
            <text class="waitlist__row-value waitlist__row-value--right">{{ waitlist.activity.organizerName }} · 经平台支付渠道</text>
          </view>
        </template>
      </view>

      <view class="waitlist__card">
        <text class="waitlist__heading">候补规则</text>
        <view v-for="(rule, index) in view.rules" :key="index" class="waitlist__rule">
          <view class="waitlist__rule-dot" />
          <text class="waitlist__rule-text">{{ rule }}</text>
        </view>
      </view>
    </view>

    <view v-else-if="state.kind === 'missing'" class="waitlist__state" data-testid="activity-waitlist-missing">
      <text class="waitlist__state-title">候补不存在</text>
      <button class="waitlist__state-button" @click="emit('back')">返回</button>
    </view>
    <view v-else class="waitlist__state" data-testid="activity-waitlist-error">
      <text class="waitlist__state-title">加载失败</text>
      <text class="waitlist__state-text">请检查网络后重试</text>
      <button class="waitlist__state-button" data-testid="activity-waitlist-retry" @click="load">重试</button>
    </view>

    <view v-if="waitlist && view" class="waitlist__footer" data-testid="activity-waitlist-bar" :data-bar="view.bar">
      <template v-if="view.bar === 'WAITING'">
        <button class="waitlist__ghost waitlist__ghost--grow" data-testid="activity-waitlist-abandon" @click="sheetOpen = true">放弃候补</button>
        <button class="waitlist__primary waitlist__primary--grow" data-testid="activity-waitlist-detail" @click="openActivity">查看活动详情</button>
      </template>
      <template v-else-if="view.bar === 'OFFERED'">
        <view class="waitlist__total">
          <text class="waitlist__total-line">{{ view.feeLine ?? `${waitlist.people} 人 · 免费` }}</text>
          <text class="waitlist__total-amount">{{ view.payValue }}</text>
        </view>
        <button class="waitlist__ghost" data-testid="activity-waitlist-abandon" @click="sheetOpen = true">放弃候补</button>
        <button
          class="waitlist__primary waitlist__primary--fixed"
          data-testid="activity-waitlist-take"
          :disabled="busy"
          @click="takeOffer"
        >
          {{ busy ? '请稍候…' : paid ? '去付款' : '确认报名' }}
        </button>
      </template>
      <template v-else-if="view.bar === 'ENROLLED'">
        <button class="waitlist__ghost waitlist__ghost--grow" @click="openActivity">查看活动详情</button>
        <button
          class="waitlist__primary waitlist__primary--grow"
          data-testid="activity-waitlist-ticket"
          @click="waitlist.enrollmentId && emit('ticket', waitlist.enrollmentId)"
        >
          查看凭证
        </button>
      </template>
      <template v-else>
        <button
          class="waitlist__ghost waitlist__ghost--grow"
          data-testid="activity-waitlist-left"
          @click="waitlist.status === 'EXPIRED' ? emit('more') : openActivity()"
        >
          {{ waitlist.status === 'EXPIRED' ? '看看其他活动' : '查看活动详情' }}
        </button>
        <button
          class="waitlist__primary waitlist__primary--grow"
          data-testid="activity-waitlist-right"
          @click="waitlist.status === 'EXPIRED' ? openActivity() : emit('more')"
        >
          {{ waitlist.status === 'EXPIRED' ? '重新候补' : '看看其他活动' }}
        </button>
      </template>
    </view>

    <view v-if="sheetOpen && waitlist" class="waitlist__mask" data-testid="activity-waitlist-sheet" @click.self="sheetOpen = false">
      <view class="waitlist__sheet" role="dialog" aria-label="放弃候补">
        <view class="waitlist__sheet-head">
          <text class="waitlist__sheet-title">放弃这次候补？</text>
          <text class="waitlist__sheet-text">{{ abandonSheetText(waitlist) }}</text>
        </view>
        <view class="waitlist__sheet-facts">
          <view class="waitlist__sheet-row">
            <text class="waitlist__row-key">场次</text>
            <text class="waitlist__sheet-value">{{ formatWaitlistSession(waitlist) }}</text>
          </view>
          <view class="waitlist__sheet-row">
            <text class="waitlist__row-key">参与人</text>
            <text class="waitlist__sheet-value">{{ memberNames }}</text>
          </view>
        </view>
        <view class="waitlist__sheet-actions">
          <button class="waitlist__ghost waitlist__ghost--grow" data-testid="activity-waitlist-keep" @click="sheetOpen = false">再想想</button>
          <button
            class="waitlist__primary waitlist__primary--grow"
            data-testid="activity-waitlist-abandon-confirm"
            :disabled="busy"
            @click="confirmAbandon"
          >
            确认放弃
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board EnrollWaitlist (390 wide). */
.waitlist {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  font-family: 'PingFang SC', 'Noto Sans SC', sans-serif;
}

.waitlist__hero {
  flex-shrink: 0;
  padding: calc(var(--status-bar-height) + 8px) 8px 52px;
  color: #fff;
}

.waitlist__bar {
  display: flex;
  align-items: center;
}

.waitlist__back,
.waitlist__back-gap {
  flex-shrink: 0;
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
}

.waitlist__back::after {
  border: 0;
}

.waitlist__title {
  flex-grow: 1;
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-align: center;
}

.waitlist__hero-text {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 8px 0;
}

.waitlist__hero-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 22px;
  font-weight: 700;
  line-height: 30px;
}

.waitlist__hero-sub {
  font-size: 13px;
  font-weight: 500;
  line-height: 19px;
}

.waitlist__body {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  margin-top: -36px;
  padding: 0 16px 16px;
}

.waitlist__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 14px;
  background: #fff;
}

.waitlist__card--main {
  gap: 14px;
  padding: 16px;
}

.waitlist__card--fee {
  gap: 8px;
  font-size: 13px;
  line-height: 19px;
}

.waitlist__facts {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.waitlist__activity {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.waitlist__grid {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
  line-height: 19px;
}

.waitlist__key {
  color: #738096;
}

.waitlist__value--strong {
  font-weight: 500;
}

.waitlist__note {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 10px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.waitlist__note--gray {
  color: #536178;
  background: #eef2f8;
}

.waitlist__note-text {
  min-width: 0;
}

.waitlist__dash {
  border-top: 1px dashed #e5ebf3;
}

.waitlist__steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.waitlist__step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.waitlist__step-line {
  position: absolute;
  top: 9px;
  height: 2px;
}

.waitlist__step-line--left {
  left: 0;
  right: 50%;
}

.waitlist__step-line--right {
  left: 50%;
  right: 0;
}

.waitlist__dot {
  position: relative;
  display: flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  border: 2px solid #c9d3e1;
  border-radius: 50%;
  background: #fff;
}

.waitlist__dot--done {
  border-color: #ff6a00;
  background: #ff6a00;
}

.waitlist__dot--cur {
  border-color: #ff6a00;
}

.waitlist__dot--end {
  border-color: #738096;
  background: #738096;
}

.waitlist__dot-inner {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ff6a00;
}

.waitlist__step-label {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.waitlist__step-label--done {
  color: #202c42;
  font-weight: 500;
}

.waitlist__step-label--cur {
  color: #ba470b;
  font-weight: 700;
}

.waitlist__step-label--end {
  color: #536178;
  font-weight: 700;
}

.waitlist__card-head {
  display: flex;
  align-items: baseline;
}

.waitlist__heading {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.waitlist__heading--fee {
  margin-bottom: 2px;
}

.waitlist__card-aside {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.waitlist__member {
  display: flex;
  align-items: center;
  gap: 10px;
}

.waitlist__avatar {
  display: flex;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef2f8;
}

.waitlist__member-name {
  font-size: 15px;
  line-height: 21px;
}

.waitlist__member-age {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.waitlist__row {
  display: flex;
  gap: 10px;
}

.waitlist__row--baseline {
  align-items: baseline;
}

.waitlist__row-key {
  flex-shrink: 0;
  color: #627187;
}

.waitlist__row-value {
  margin-left: auto;
}

.waitlist__row-value--right {
  min-width: 0;
  text-align: right;
}

.waitlist__pay {
  margin-left: auto;
  font-weight: 700;
}

.waitlist__pay--orange {
  color: #e85400;
  font-size: 18px;
}

.waitlist__pay--green {
  color: #11804a;
  font-size: 14px;
}

.waitlist__pay--dark {
  color: #202c42;
  font-size: 18px;
}

.waitlist__rule-line {
  height: 1px;
  margin: 4px 0;
  background: #f0f1f3;
}

.waitlist__rule {
  display: flex;
  gap: 8px;
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.waitlist__rule-dot {
  flex-shrink: 0;
  width: 4px;
  height: 4px;
  margin: 8px 2px 0;
  border-radius: 50%;
  background: #91a0b4;
}

.waitlist__rule-text {
  min-width: 0;
}

.waitlist__footer {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #fff;
}

.waitlist__ghost,
.waitlist__primary {
  height: 46px;
  margin: 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
  white-space: nowrap;
}

.waitlist__ghost::after,
.waitlist__primary::after {
  border: 0;
}

.waitlist__ghost {
  flex-shrink: 0;
  padding: 0 16px;
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #fff;
}

.waitlist__primary {
  border: 0;
  color: #fff;
  background: #ff6a00;
  font-weight: 500;
  line-height: 46px;
}

.waitlist__ghost--grow,
.waitlist__primary--grow {
  flex: 1 1 0;
  padding: 0;
}

.waitlist__primary--fixed {
  flex-shrink: 0;
  width: 132px;
  padding: 0;
  font-size: 16px;
}

.waitlist__total {
  display: flex;
  flex-grow: 1;
  min-width: 0;
  flex-direction: column;
}

.waitlist__total-line {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.waitlist__total-amount {
  color: #e85400;
  font-size: 20px;
  font-weight: 700;
  line-height: 26px;
}

.waitlist__mask {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.45);
}

.waitlist__sheet {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 14px;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  background: #fff;
  box-sizing: border-box;
}

.waitlist__sheet-head {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.waitlist__sheet-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.waitlist__sheet-text {
  color: #536178;
  font-size: 13px;
  line-height: 20px;
}

.waitlist__sheet-facts {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 19px;
}

.waitlist__sheet-row {
  display: flex;
  gap: 12px;
}

.waitlist__sheet-value {
  min-width: 0;
  margin-left: auto;
  text-align: right;
}

.waitlist__sheet-actions {
  display: flex;
  gap: 10px;
}

.waitlist__skeleton {
  height: 180px;
  border-radius: 14px;
  background: #fff;
}

.waitlist__skeleton--short {
  height: 100px;
}

.waitlist__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 48px 16px;
}

.waitlist__state-title {
  font-size: 16px;
  font-weight: 700;
}

.waitlist__state-text {
  color: #627187;
  font-size: 13px;
}

.waitlist__state-button {
  padding: 0 24px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #fff;
  font-size: 15px;
  line-height: 40px;
}
</style>
