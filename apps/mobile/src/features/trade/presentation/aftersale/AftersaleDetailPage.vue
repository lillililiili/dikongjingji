<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  AFTERSALE_BAR_LABELS,
  AFTERSALE_KIND_LABELS,
  AFTERSALE_PRIMARY_BUTTONS,
  AFTERSALE_REASON_LABELS,
  AFTERSALE_STEP_LOOK,
  aftersaleBarButtons,
  aftersaleGoods,
  aftersaleHandler,
  canRefreshAftersale,
  classifyAftersaleFailure,
  describeAftersaleHead,
  describeAftersaleInfo,
  describeAftersaleSteps,
  evidenceSlots,
  refuseRows,
  returnAddressText,
  returnTips,
  waybillNote,
  type AftersaleBarButton,
} from '../../application/aftersale-view'
import { formatTradeAmount, formatTradeTime } from '../../application/order-view'
import { aftersaleApi } from '../../data/aftersale-api-client'
import type { Aftersale, ReturnShipmentInput } from '../../domain/aftersale'
import { confirmDialog, copyText, coverOfLine } from '../shared/trade-presentation'
import AftersaleWaybillSheet from './AftersaleWaybillSheet.vue'

/**
 * 售后详情（画板 AftersaleDetail 和 AftersaleFlow 是同一个页面的不同状态）：状态头、退款金额与去向、
 * 寄回地址、退货物流、商家说明、你提交的凭证、售后进度、申请信息，底栏按服务端给的 actions 出按钮。
 * “刷新进度”只是再读一次服务端。
 */
const props = defineProps<{
  aftersaleId: string
  accessToken: string | null
  refreshKey: number
  // 列表卡片的“填写运单”：读完就打开运单弹层。
  openWaybill: boolean
}>()

const emit = defineEmits<{
  back: []
  login: []
  viewOrder: [orderId: string]
  reapply: [orderId: string]
}>()

type DetailState =
  | { kind: 'loading' }
  | { kind: 'ready'; aftersale: Aftersale }
  | { kind: 'missing' }
  | { kind: 'error' }

const PIN = 'M12 21s-7-6.2-7-11a7 7 0 1 1 14 0c0 4.8-7 11-7 11zM12 7.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z'
const TRUCK = 'M3 7h11v9H3zM14 10h4l3 3v3h-7M7 19a1.5 1.5 0 1 0 0-.1M17 19a1.5 1.5 0 1 0 0-.1'
const INFO = 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01'
const TICK = 'M5 12l5 5 9-10'
const CROSS = 'M6 6l12 12M18 6L6 18'
const CAMERA = 'M4 8h3l2-3h6l2 3h3v11H4zM12 16.5a3.5 3.5 0 1 0 0-7a3.5 3.5 0 1 0 0 7z'
const CHAT = 'M4 5h16v11H9l-5 4z'

const state = ref<DetailState>({ kind: 'loading' })
const busy = ref(false)
const refreshing = ref(false)
const sheetOpen = ref(false)
const copied = ref<'ADDRESS' | 'WAYBILL' | null>(null)
let requestVersion = 0
let waybillOpened = false

const aftersale = computed(() => (state.value.kind === 'ready' ? state.value.aftersale : null))
const head = computed(() => (aftersale.value ? describeAftersaleHead(aftersale.value) : null))
const info = computed(() => (aftersale.value ? describeAftersaleInfo(aftersale.value) : []))
const steps = computed(() => (aftersale.value ? describeAftersaleSteps(aftersale.value) : []))
const buttons = computed(() => (aftersale.value ? aftersaleBarButtons(aftersale.value, SUPPORT_PREVIEW_ENABLED) : []))
const goods = computed(() => (aftersale.value ? aftersaleGoods(aftersale.value.lines) : null))
const firstLine = computed(() => aftersale.value?.lines[0] ?? null)
// 寄回地址块：待寄回（能填运单）时才有；商家还没给地址时只写提示。
const showAddress = computed(() => Boolean(aftersale.value?.actions.canSubmitReturn))
const addressText = computed(() => (aftersale.value ? returnAddressText(aftersale.value) : null))
const refusal = computed(() => (aftersale.value ? refuseRows(aftersale.value) : []))
const evidence = computed(() => (aftersale.value?.status === 'REJECTED' ? evidenceSlots(aftersale.value) : []))

async function load(manual = false): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.aftersaleId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  if (manual) refreshing.value = true
  try {
    const loaded = await aftersaleApi.read(token, props.aftersaleId)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', aftersale: loaded }
    if (props.openWaybill && !waybillOpened && loaded.actions.canSubmitReturn) {
      waybillOpened = true
      sheetOpen.value = true
    }
    if (manual) uni.showToast({ title: '已是最新进度', icon: 'none' })
  } catch (error) {
    if (requested !== requestVersion) return
    const failure = classifyAftersaleFailure(error)
    if (failure.kind === 'UNAUTHORIZED') {
      emit('login')
      return
    }
    const status = (error as { status?: number }).status
    if (state.value.kind === 'ready' && manual) {
      uni.showToast({ title: failure.message, icon: 'none' })
      return
    }
    state.value = { kind: status === 404 || status === 400 ? 'missing' : 'error' }
  } finally {
    if (requested === requestVersion) refreshing.value = false
  }
}

function contactMerchant(): void {
  const current = aftersale.value
  if (!current) return
  openSupportChat(
    { kind: 'MERCHANT', organizationId: current.merchant.organizationId },
    { type: 'MALL_ORDER', refId: current.orderId, title: goods.value?.name || '商品订单', subtitle: `售后 ${current.aftersaleNo}` },
  )
}

function contactPlatform(): void {
  const current = aftersale.value
  if (!current) return
  openSupportChat(
    { kind: 'PLATFORM' },
    { type: 'MALL_ORDER', refId: current.orderId, title: goods.value?.name || '商品订单', subtitle: `售后 ${current.aftersaleNo}` },
  )
}

async function revoke(): Promise<void> {
  const token = props.accessToken
  const current = aftersale.value
  if (!token || !current || busy.value) return
  const confirmed = await confirmDialog({
    title: '撤销申请',
    content: '撤销后这次售后申请结束；订单还能申请时可以重新申请。确定撤销吗？',
    confirmText: '撤销申请',
    cancelText: '再想想',
  })
  if (!confirmed) return
  busy.value = true
  try {
    state.value = { kind: 'ready', aftersale: await aftersaleApi.revoke(token, current.aftersaleId, current.version) }
    uni.showToast({ title: '已撤销', icon: 'none' })
  } catch (error) {
    handleCommandFailure(error)
  } finally {
    busy.value = false
  }
}

async function submitWaybill(input: ReturnShipmentInput): Promise<void> {
  const token = props.accessToken
  const current = aftersale.value
  if (!token || !current || busy.value) return
  busy.value = true
  try {
    state.value = { kind: 'ready', aftersale: await aftersaleApi.submitReturn(token, current.aftersaleId, current.version, input) }
    sheetOpen.value = false
    uni.showToast({ title: '运单已提交', icon: 'none' })
  } catch (error) {
    handleCommandFailure(error)
  } finally {
    busy.value = false
  }
}

function handleCommandFailure(error: unknown): void {
  const failure = classifyAftersaleFailure(error)
  if (failure.kind === 'UNAUTHORIZED') {
    emit('login')
    return
  }
  uni.showToast({ title: failure.message, icon: 'none' })
  if (failure.kind === 'CHANGED') {
    sheetOpen.value = false
    void load()
  }
}

function runButton(button: AftersaleBarButton): void {
  const current = aftersale.value
  if (!current) return
  switch (button) {
    case 'CONTACT_MERCHANT': contactMerchant(); return
    case 'CONTACT_PLATFORM': contactPlatform(); return
    case 'REVOKE': void revoke(); return
    case 'SUBMIT_RETURN':
    case 'EDIT_RETURN': sheetOpen.value = true; return
    case 'REAPPLY': emit('reapply', current.orderId); return
    case 'VIEW_ORDER': emit('viewOrder', current.orderId)
  }
}

function copy(kind: 'ADDRESS' | 'WAYBILL', text: string | null | undefined): void {
  if (text) copyText(text, () => { copied.value = kind })
}

watch(() => [props.refreshKey, props.accessToken, props.aftersaleId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="aftersale" data-testid="aftersale-detail" :data-status="aftersale?.status ?? ''">
    <AppPageHeader title="售后详情" plain test-id="aftersale-detail" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="aftersale__body" data-testid="aftersale-detail-loading" aria-label="正在加载">
      <view class="aftersale__skeleton" />
      <view class="aftersale__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="aftersale__state" data-testid="aftersale-detail-missing">
      <text class="aftersale__state-title">没有找到这张售后单</text>
      <button class="aftersale__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="aftersale__state" data-testid="aftersale-detail-error">
      <text class="aftersale__state-title">加载失败</text>
      <text class="aftersale__state-text">请检查网络后重试</text>
      <button class="aftersale__state-button" data-testid="aftersale-detail-retry" @click="load()">重试</button>
    </view>

    <template v-else-if="aftersale && head && goods">
      <view class="aftersale__body">
        <view class="aftersale__card aftersale__head" data-testid="aftersale-head">
          <text class="aftersale__head-title" :style="{ color: head.color }" data-testid="aftersale-status-title">{{ head.title }}</text>
          <text class="aftersale__head-sub" data-testid="aftersale-status-sub">{{ head.sub }}</text>
          <view v-if="info.length > 0" class="aftersale__info" data-testid="aftersale-info">
            <view v-for="row in info" :key="row.key" class="aftersale__info-row">
              <text class="aftersale__info-label">{{ row.label }}</text>
              <text
                :class="['aftersale__info-value', { 'aftersale__info-value--amount': row.emphasis }]"
                :data-testid="`aftersale-info-${row.key}`"
              >
                {{ row.value }}
              </text>
            </view>
          </view>
        </view>

        <view v-if="showAddress" class="aftersale__card aftersale__section" data-testid="aftersale-address">
          <view class="aftersale__section-head">
            <text class="aftersale__title">寄回地址</text>
            <button v-if="addressText" class="aftersale__link" data-testid="aftersale-copy-address" @click="copy('ADDRESS', addressText)">
              {{ copied === 'ADDRESS' ? '已复制' : '复制地址' }}
            </button>
          </view>
          <view v-if="aftersale.returnAddress" class="aftersale__place">
            <LineIcon :d="PIN" :size="24" color="#ff6a00" />
            <view class="aftersale__place-text">
              <text class="aftersale__place-name">{{ [aftersale.returnAddress.contactName, aftersale.returnAddress.phone].filter(Boolean).join(' ') || aftersale.merchant.name }}</text>
              <text class="aftersale__place-line" data-testid="aftersale-address-line">{{ aftersale.returnAddress.address }}</text>
              <text v-if="aftersale.returnAddress.note" class="aftersale__place-note">{{ aftersale.returnAddress.note }}</text>
            </view>
          </view>
          <view v-else class="aftersale__notice" data-testid="aftersale-address-missing">
            <LineIcon :d="INFO" :size="16" color="#ff6a00" :stroke-width="2" />
            <text>商家还没提供寄回地址，请先联系商家</text>
          </view>
          <view class="aftersale__tips">
            <view v-for="tip in returnTips(aftersale)" :key="tip" class="aftersale__tip">
              <LineIcon :d="TICK" :size="14" color="#159a70" :stroke-width="2.4" />
              <text>{{ tip }}</text>
            </view>
          </view>
        </view>

        <view v-if="aftersale.returnWaybill" class="aftersale__card aftersale__section" data-testid="aftersale-waybill">
          <view class="aftersale__section-head">
            <text class="aftersale__title">退货物流</text>
            <button class="aftersale__link" data-testid="aftersale-copy-waybill" @click="copy('WAYBILL', aftersale.returnWaybill.trackingNo)">
              {{ copied === 'WAYBILL' ? '已复制' : '复制单号' }}
            </button>
          </view>
          <view class="aftersale__place">
            <LineIcon :d="TRUCK" :size="24" color="#159a70" />
            <view class="aftersale__place-text">
              <text class="aftersale__place-name" data-testid="aftersale-waybill-line">
                {{ aftersale.returnWaybill.carrierName }} · 运单号 {{ aftersale.returnWaybill.trackingNo }}
              </text>
              <text class="aftersale__place-note">{{ waybillNote(aftersale) }}</text>
            </view>
          </view>
        </view>

        <view v-if="aftersale.status === 'REJECTED' || aftersale.status === 'RECEIPT_REJECTED'" class="aftersale__card aftersale__section" data-testid="aftersale-refusal">
          <text class="aftersale__title">商家说明</text>
          <view v-if="refusal.length > 0" class="aftersale__grid">
            <template v-for="row in refusal" :key="row.label">
              <text class="aftersale__grid-label">{{ row.label }}</text>
              <text class="aftersale__grid-value">{{ row.value }}</text>
            </template>
          </view>
          <view class="aftersale__notice">
            <LineIcon :d="INFO" :size="16" color="#ff6a00" :stroke-width="2" />
            <text>你可以先联系商家协商；协商不成，可联系平台客服协助沟通，平台不裁决。</text>
          </view>
        </view>

        <view v-if="evidence.length > 0" class="aftersale__card aftersale__section" data-testid="aftersale-evidence">
          <text class="aftersale__title">你提交的凭证</text>
          <view class="aftersale__evidence">
            <view v-for="slot in evidence" :key="slot.slot" class="aftersale__evidence-slot">
              <view :class="['aftersale__evidence-tile', { 'aftersale__evidence-tile--empty': slot.count === 0 }]">
                <LineIcon :d="CAMERA" :size="22" :color="slot.count > 0 ? '#738096' : '#91a0b4'" />
                <text>{{ slot.label }}</text>
              </view>
              <text class="aftersale__evidence-note" :style="{ color: slot.noteColor }" :data-testid="`aftersale-evidence-${slot.slot}`">{{ slot.note }}</text>
            </view>
          </view>
        </view>

        <view class="aftersale__card aftersale__progress" data-testid="aftersale-progress">
          <view class="aftersale__section-head">
            <text class="aftersale__title">售后进度</text>
            <button
              v-if="canRefreshAftersale(aftersale)"
              class="aftersale__link"
              data-testid="aftersale-refresh"
              :disabled="refreshing"
              @click="load(true)"
            >
              {{ refreshing ? '正在刷新…' : '刷新进度' }}
            </button>
          </view>
          <view class="aftersale__steps" role="list" aria-label="售后进度">
            <view
              v-for="step in steps"
              :key="step.key"
              class="aftersale__step"
              role="listitem"
              :data-testid="`aftersale-step-${step.key}`"
              :data-kind="step.kind"
            >
              <view class="aftersale__step-rail">
                <view class="aftersale__step-line" :style="{ background: step.line }" />
                <view
                  class="aftersale__step-dot"
                  :style="{ borderColor: AFTERSALE_STEP_LOOK[step.kind].bd, background: AFTERSALE_STEP_LOOK[step.kind].bg }"
                >
                  <LineIcon v-if="step.kind === 'done'" :d="TICK" :size="9" color="#ffffff" :stroke-width="4" />
                  <view v-else-if="step.kind === 'cur'" class="aftersale__step-inner" />
                  <LineIcon v-else-if="step.kind === 'end'" :d="CROSS" :size="8" color="#ffffff" :stroke-width="4" />
                </view>
              </view>
              <view class="aftersale__step-text">
                <text
                  class="aftersale__step-title"
                  :style="{ color: AFTERSALE_STEP_LOOK[step.kind].tc, fontWeight: AFTERSALE_STEP_LOOK[step.kind].tw }"
                >
                  {{ step.title }}
                </text>
                <text v-if="step.sub" class="aftersale__step-sub" :style="{ color: AFTERSALE_STEP_LOOK[step.kind].sc }">{{ step.sub }}</text>
              </view>
            </view>
          </view>
        </view>

        <view class="aftersale__card aftersale__section" data-testid="aftersale-application">
          <text class="aftersale__title">申请信息</text>
          <view class="aftersale__goods">
            <image v-if="firstLine && coverOfLine(firstLine)" class="aftersale__cover" :src="coverOfLine(firstLine) ?? ''" mode="aspectFill" />
            <view v-else class="aftersale__cover" />
            <view class="aftersale__goods-text">
              <text class="aftersale__goods-name">{{ goods.name }}</text>
              <text class="aftersale__goods-line">{{ goods.spec }}</text>
              <text class="aftersale__goods-line">实付 {{ formatTradeAmount(aftersale.paidMinor) }}</text>
            </view>
          </view>
          <view class="aftersale__grid">
            <text class="aftersale__grid-label">售后类型</text><text class="aftersale__grid-value" data-testid="aftersale-kind">{{ AFTERSALE_KIND_LABELS[aftersale.kind] }}</text>
            <text class="aftersale__grid-label">申请原因</text><text class="aftersale__grid-value" data-testid="aftersale-reason">{{ AFTERSALE_REASON_LABELS[aftersale.reason] }}</text>
            <text class="aftersale__grid-label">申请金额</text><text class="aftersale__grid-value" data-testid="aftersale-requested">{{ formatTradeAmount(aftersale.requestedMinor) }}</text>
            <text class="aftersale__grid-label">处理方</text><text class="aftersale__grid-value" data-testid="aftersale-handler">{{ aftersaleHandler(aftersale.merchant) }} · 平台协助沟通</text>
            <text class="aftersale__grid-label">提交时间</text><text class="aftersale__grid-value">{{ formatTradeTime(aftersale.submittedAt) }}</text>
          </view>
        </view>
      </view>

      <view v-if="buttons.length > 0" class="aftersale__bar">
        <template v-for="button in buttons" :key="button">
          <button
            v-if="button === 'CONTACT_MERCHANT'"
            class="aftersale__contact"
            data-testid="aftersale-action-contact_merchant"
            @click="runButton(button)"
          >
            <LineIcon :d="CHAT" :size="22" color="#536178" />
            <text>联系商家</text>
          </button>
          <button
            v-else
            :class="['aftersale__button', { 'aftersale__button--primary': AFTERSALE_PRIMARY_BUTTONS.includes(button) }]"
            :data-testid="`aftersale-action-${button.toLowerCase()}`"
            :disabled="busy"
            @click="runButton(button)"
          >
            {{ AFTERSALE_BAR_LABELS[button] }}
          </button>
        </template>
      </view>

      <AftersaleWaybillSheet
        v-if="sheetOpen"
        :editing="aftersale.actions.canEditReturn && !aftersale.actions.canSubmitReturn"
        :waybill="aftersale.returnWaybill"
        :busy="busy"
        @close="sheetOpen = false"
        @submit="submitWaybill"
      />
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours copied from the 2026-11-14 boards AftersaleDetail and AftersaleFlow (390 wide). */
.aftersale {
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
  color: inherit;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.aftersale__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.aftersale__skeleton {
  height: 120px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.aftersale__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.aftersale__state-title {
  font-size: 16px;
  font-weight: 700;
}

.aftersale__state-text {
  color: #738096;
  font-size: 13px;
}

.aftersale__state-button {
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

.aftersale__card {
  border-radius: 14px;
  background: #ffffff;
}

.aftersale__head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px;
}

.aftersale__head-title {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.aftersale__head-sub {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.aftersale__info {
  display: flex;
  margin-top: 8px;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 20px;
}

.aftersale__info-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.aftersale__info-label {
  width: 56px;
  flex: none;
  color: #627187;
  white-space: nowrap;
}

.aftersale__info-value {
  min-width: 0;
  overflow-wrap: anywhere;
}

.aftersale__info-value--amount {
  color: #e85400;
  font-size: 15px;
  font-weight: 700;
}

.aftersale__section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
}

.aftersale__section-head {
  display: flex;
  min-height: 22px;
  align-items: center;
}

.aftersale__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.aftersale__link {
  display: flex;
  height: 44px;
  flex: none;
  margin: -11px 0 -11px auto;
  padding: 0 0 0 12px;
  align-items: center;
  color: #ba470b;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.aftersale__place {
  display: flex;
  gap: 10px;
}

.aftersale__place :deep(.line-icon),
.aftersale__notice :deep(.line-icon),
.aftersale__tip :deep(.line-icon) {
  flex: none;
}

.aftersale__place-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.aftersale__place-name {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.aftersale__place-line {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.aftersale__place-note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.aftersale__tips {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f5f7fc;
}

.aftersale__tip {
  display: flex;
  gap: 6px;
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.aftersale__notice {
  display: flex;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 10px;
  color: #536178;
  background: #fff0e3;
  font-size: 13px;
  line-height: 19px;
}

.aftersale__grid {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 8px 12px;
  font-size: 13px;
  line-height: 18px;
}

.aftersale__grid-label {
  color: #627187;
  white-space: nowrap;
}

.aftersale__grid-value {
  min-width: 0;
  overflow-wrap: anywhere;
}

.aftersale__evidence {
  display: flex;
  gap: 10px;
}

.aftersale__evidence-slot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.aftersale__evidence-tile {
  display: flex;
  width: 72px;
  height: 72px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 1px solid #e5ebf3;
  border-radius: 10px;
  color: #536178;
  background: #eef2f8;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
  box-sizing: border-box;
}

.aftersale__evidence-tile--empty {
  border: 1px dashed #c9d3e1;
  color: #738096;
  background: #fafafb;
}

.aftersale__evidence-note {
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.aftersale__progress {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 14px 0;
}

.aftersale__steps {
  display: flex;
  flex-direction: column;
}

.aftersale__step {
  display: flex;
  gap: 10px;
  padding-bottom: 14px;
}

.aftersale__step-rail {
  position: relative;
  width: 16px;
  flex: none;
}

.aftersale__step-line {
  position: absolute;
  top: 10px;
  bottom: -24px;
  left: 7px;
  width: 2px;
}

.aftersale__step-dot {
  position: absolute;
  top: 2px;
  left: 0;
  display: flex;
  width: 16px;
  height: 16px;
  align-items: center;
  justify-content: center;
  border: 2px solid;
  border-radius: 50%;
  box-sizing: border-box;
}

.aftersale__step-inner {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ff6a00;
}

.aftersale__step-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.aftersale__step-title {
  font-size: 14px;
  line-height: 20px;
}

.aftersale__step-sub {
  font-size: 12px;
  line-height: 17px;
}

.aftersale__goods {
  display: flex;
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f1f3;
}

.aftersale__cover {
  display: block;
  width: 56px;
  height: 56px;
  flex: none;
  border-radius: 8px;
  background: #eef2f8;
}

.aftersale__goods-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.aftersale__goods-name {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.aftersale__goods-line {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.aftersale__bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px max(24px, env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.aftersale__contact {
  display: flex;
  width: 52px;
  height: 46px;
  flex: none;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #536178;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

.aftersale__button {
  display: flex;
  height: 46px;
  min-width: 0;
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

.aftersale__button--primary {
  border: 0;
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.aftersale__button[disabled] {
  color: #202c42;
  background: #ffffff;
  opacity: 0.55;
}

.aftersale__button--primary[disabled] {
  color: #ffffff;
  background: #ff6a00;
}
</style>
