<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'

import { rentalCloseConfirm } from '../../application/rental-inquiry-card'
import { openSupportChat } from '@/features/support/application/support-entry'
import { formatRentalAmount, formatRentalInstant } from '../../application/rental-format'
import {
  describeRentalConfirmFailure,
  rentalHeaderBadge,
  rentalInquiryRows,
  rentalPayNode,
  rentalQuoteBasis,
  rentalQuoteChip,
  rentalQuoteFrame,
  rentalQuoteLines,
  rentalQuoteNotice,
  rentalQuoteValidity,
  rentalScheduleRows,
  rentalTermRows,
} from '../../application/rental-quote-view'
import { rentalApi } from '../../data/rental-api-client'
import type { RentalInquiry } from '../../domain/rental'
import type { RentalInquiryTarget } from '../../navigation/rental-routes'
import RentalConfirmSheet from '../shared/RentalConfirmSheet.vue'
import RentalItemCard from '../shared/RentalItemCard.vue'
import RentalPageHeader from '../shared/RentalPageHeader.vue'

const props = defineProps<{
  inquiryId: string | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  // The contract is confirmed: pay this trade order at the cashier.
  pay: [tradeOrderId: string]
  viewOrder: [rentalOrderId: string]
  reinquire: [target: RentalInquiryTarget]
}>()

type QuoteState =
  | { kind: 'loading' }
  | { kind: 'ready'; inquiry: RentalInquiry }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<QuoteState>({ kind: 'loading' })
const agreed = ref(false)
// Why the page was read again (a revised quote, an expired one…), shown on top until the next action.
const notice = ref<string | null>(null)
const confirming = ref(false)
const askingClose = ref(false)
const closing = ref(false)
let requestVersion = 0
// The quote version the user ticked 我已核对 for; a new version clears the tick.
let agreedVersion: number | null = null

const inquiry = computed(() => (state.value.kind === 'ready' ? state.value.inquiry : null))

// 联系商家 (step G1): the lessor's conversation with a card about this inquiry.
function contactMerchant(): void {
  const current = inquiry.value
  if (!current) return
  openSupportChat(
    { kind: 'MERCHANT', organizationId: current.merchant.merchantId },
    { type: 'RENTAL_INQUIRY', refId: current.inquiryId, title: current.item.productName, subtitle: `${current.days} 天 × ${current.quantity} 台` },
  )
}
const frame = computed(() => (inquiry.value ? rentalQuoteFrame(inquiry.value) : 'WAITING'))
const quote = computed(() => inquiry.value?.quote ?? null)
const chip = computed(() => rentalQuoteChip(frame.value))
const lines = computed(() => (quote.value ? rentalQuoteLines(quote.value) : []))
const scheduleRows = computed(() => (inquiry.value ? rentalScheduleRows(inquiry.value) : []))
const waitingRows = computed(() => (inquiry.value ? rentalInquiryRows(inquiry.value) : []))
const terms = computed(() => (quote.value ? rentalTermRows(quote.value.terms) : []))
const payNode = computed(() => rentalPayNode(frame.value))
const statusBox = computed(() => (inquiry.value ? rentalQuoteNotice(inquiry.value, frame.value) : null))
const totalText = computed(() => (quote.value ? formatRentalAmount(quote.value.totalMinor) : ''))
const confirmable = computed(() => Boolean(inquiry.value?.confirmable && quote.value))
const closeCopy = computed(() => (inquiry.value ? rentalCloseConfirm(inquiry.value) : null))
const reinquirable = computed(() => ['EXPIRED', 'DECLINED', 'CLOSED', 'UNAVAILABLE'].includes(frame.value))

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.inquiryId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await rentalApi.readInquiry(token, props.inquiryId)
    if (requested !== requestVersion) return
    if (loaded.quote?.version !== agreedVersion) {
      agreed.value = false
      agreedVersion = null
    }
    state.value = { kind: 'ready', inquiry: loaded }
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    state.value = {
      kind: error instanceof ApiRequestError && (error.status === 404 || error.status === 400) ? 'missing' : 'error',
    }
  }
}

function toggleAgree(): void {
  agreed.value = !agreed.value
  agreedVersion = agreed.value ? quote.value?.version ?? null : null
}

async function confirm(): Promise<void> {
  const token = props.accessToken
  const current = inquiry.value
  if (!token || !current?.quote || confirming.value) return
  if (!agreed.value) {
    uni.showToast({ title: '请先勾选“我已核对租期、费用与合同条款”', icon: 'none' })
    return
  }
  confirming.value = true
  notice.value = null
  try {
    const confirmation = await rentalApi.confirmContract(token, current.inquiryId, current.version, current.quote.version)
    emit('pay', confirmation.tradeOrderId)
  } catch (error) {
    const failure = describeRentalConfirmFailure(error instanceof ApiRequestError
      ? { status: error.status, code: error.code, message: error.message }
      : null)
    if (failure.unauthorized) {
      emit('login')
      return
    }
    if (failure.reload) {
      notice.value = failure.message
      agreed.value = false
      agreedVersion = null
      await load()
    } else {
      uni.showToast({ title: failure.message, icon: 'none' })
    }
  } finally {
    confirming.value = false
  }
}

async function close(): Promise<void> {
  const token = props.accessToken
  const current = inquiry.value
  if (!token || !current || closing.value) return
  closing.value = true
  try {
    const closed = await rentalApi.closeInquiry(token, current.inquiryId)
    state.value = { kind: 'ready', inquiry: closed }
    notice.value = null
    uni.showToast({ title: '询价已关闭', icon: 'none' })
  } catch (error) {
    if (isUnauthorized(error)) {
      askingClose.value = false
      emit('login')
      return
    }
    uni.showToast({
      title: error instanceof ApiRequestError && error.code === 'RENTAL_STATE_INVALID'
        ? '询价单已确认或已关闭，不能再关闭'
        : '没有关闭成功，请稍后重试',
      icon: 'none',
    })
    await load()
  } finally {
    closing.value = false
    askingClose.value = false
  }
}

function reinquire(): void {
  const current = inquiry.value
  if (!current) return
  emit('reinquire', {
    offerId: current.item.offerId,
    productId: current.item.productId,
    cityCode: current.place.cityCode,
  })
}

watch(() => [props.refreshKey, props.accessToken, props.inquiryId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="rental-quote" data-testid="rental-quote" :data-status="inquiry?.status ?? ''" :data-frame="frame">
    <RentalPageHeader
      title="租赁报价单"
      :badge="inquiry ? rentalHeaderBadge(inquiry) : null"
      @back="emit('back')"
    />

    <view v-if="state.kind === 'loading'" class="rental-quote__body" aria-label="正在加载">
      <view class="rental-quote__skeleton" />
      <view class="rental-quote__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="rental-quote__state" data-testid="rental-quote-missing">
      <text class="rental-quote__state-title">没有找到这份询价单</text>
      <button class="rental-quote__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="rental-quote__state" data-testid="rental-quote-error">
      <text class="rental-quote__state-title">加载失败</text>
      <text class="rental-quote__state-text">请检查网络后重试</text>
      <button class="rental-quote__state-button" data-testid="rental-quote-retry" @click="load">重试</button>
    </view>

    <template v-else-if="inquiry">
      <view class="rental-quote__body">
        <view v-if="notice" class="rental-quote__notice" role="status" data-testid="rental-quote-notice">
          <LineIcon name="info" :size="16" color="#ba470b" :stroke-width="2" />
          <text>{{ notice }}</text>
        </view>

        <RentalItemCard
          :product-id="inquiry.item.productId"
          :product-name="inquiry.item.productName"
          :quantity="inquiry.quantity"
          :cover-url="inquiry.item.coverUrl"
          :merchant-name="inquiry.merchant.name"
          :merchant-reviewed="inquiry.merchant.qualificationReviewed"
          :lessee="inquiry.lessee"
        />

        <template v-if="quote">
          <view class="rental-quote__card rental-quote__head">
            <view class="rental-quote__head-line">
              <text class="rental-quote__head-title">商家报价</text>
              <text :class="['rental-quote__chip', `rental-quote__chip--${chip.tone.toLowerCase()}`]" data-testid="rental-quote-chip">
                {{ chip.label }}
              </text>
              <text class="rental-quote__head-time" data-testid="rental-quote-version">v{{ quote.version }} · {{ formatRentalInstant(quote.quotedAt) }} 报价</text>
            </view>
            <text class="rental-quote__validity" data-testid="rental-quote-validity">{{ rentalQuoteValidity(quote) }}</text>
            <view class="rental-quote__basis">
              <LineIcon name="sort" :size="15" color="#ff6a00" :stroke-width="2" />
              <text>{{ rentalQuoteBasis(inquiry, quote) }}</text>
            </view>
          </view>

          <view class="rental-quote__card rental-quote__lines" data-testid="rental-quote-lines">
            <text class="rental-quote__card-title rental-quote__card-title--rows">报价明细</text>
            <view v-for="line in lines" :key="line.key" class="rental-quote__line" :data-testid="`rental-quote-line-${line.key}`">
              <text class="rental-quote__line-name">{{ line.name }}</text>
              <text class="rental-quote__line-detail">{{ line.detail }}</text>
              <text :class="['rental-quote__line-amount', { 'rental-quote__line-amount--included': line.included }]">
                {{ line.amount }}
              </text>
            </view>
            <view class="rental-quote__total">
              <text class="rental-quote__total-label">合计</text>
              <text class="rental-quote__total-amount" data-testid="rental-quote-total">{{ totalText }}</text>
            </view>
          </view>

          <view class="rental-quote__card">
            <view class="rental-quote__card-line">
              <text class="rental-quote__card-title">租期与档期</text>
              <text
                :class="['rental-quote__schedule', { 'rental-quote__schedule--off': !quote.scheduleAvailable }]"
                data-testid="rental-quote-schedule"
              >
                {{ quote.scheduleAvailable ? '档期可用' : '档期不可用' }}
              </text>
            </view>
            <view class="rental-quote__grid">
              <template v-for="row in scheduleRows" :key="row.key">
                <text class="rental-quote__grid-label">{{ row.label }}</text>
                <text class="rental-quote__grid-value" :data-testid="`rental-quote-row-${row.key}`">{{ row.value }}</text>
              </template>
            </view>
          </view>

          <view class="rental-quote__card" data-testid="rental-quote-terms">
            <text class="rental-quote__card-title">合同条款</text>
            <view v-for="term in terms" :key="term.key" class="rental-quote__term">
              <text v-if="term.label" class="rental-quote__term-label">{{ term.label }}</text>
              <text class="rental-quote__term-text">{{ term.text }}</text>
            </view>
            <view class="rental-quote__foot">
              <LineIcon name="info" :size="14" color="#738096" :stroke-width="2" />
              <text>确认后生成租赁合同，在租赁订单的“租赁合同”里查看；合同线下签署归档。</text>
            </view>
          </view>

          <view class="rental-quote__card rental-quote__pay">
            <text class="rental-quote__card-title rental-quote__card-title--rows">付款节点</text>
            <view class="rental-quote__pay-row">
              <view class="rental-quote__pay-copy">
                <text class="rental-quote__pay-name">租金</text>
                <text :class="['rental-quote__pay-state', { 'rental-quote__pay-state--on': payNode.active }]" data-testid="rental-quote-pay-state">
                  {{ payNode.state }}
                </text>
              </view>
              <text :class="['rental-quote__pay-amount', { 'rental-quote__pay-amount--off': !payNode.active }]">{{ totalText }}</text>
            </view>
            <view class="rental-quote__foot">
              <LineIcon name="info" :size="14" color="#738096" :stroke-width="2" />
              <text>款项经平台支付渠道付给出租方；押金不在线收取。</text>
            </view>
          </view>
        </template>

        <view v-else class="rental-quote__card" data-testid="rental-quote-inquiry">
          <view class="rental-quote__card-line">
            <text class="rental-quote__card-title">询价内容</text>
            <text :class="['rental-quote__chip', `rental-quote__chip--${chip.tone.toLowerCase()}`]" data-testid="rental-quote-chip">
              {{ chip.label }}
            </text>
          </view>
          <view class="rental-quote__grid">
            <template v-for="row in waitingRows" :key="row.key">
              <text class="rental-quote__grid-label">{{ row.label }}</text>
              <text class="rental-quote__grid-value" :data-testid="`rental-quote-row-${row.key}`">{{ row.value }}</text>
            </template>
          </view>
          <view class="rental-quote__foot">
            <LineIcon name="info" :size="14" color="#738096" :stroke-width="2" />
            <text>{{ formatRentalInstant(inquiry.submittedAt) }} 提交 · 提交询价不产生费用</text>
          </view>
        </view>

        <button
          v-if="inquiry.closable"
          class="rental-quote__close"
          data-testid="rental-quote-close"
          @click="askingClose = true"
        >
          关闭询价
        </button>
      </view>

      <view class="rental-quote__bar">
        <button
          v-if="frame === 'OPEN' && confirmable"
          class="rental-quote__agree"
          role="checkbox"
          :aria-checked="agreed"
          data-testid="rental-quote-agree"
          @click="toggleAgree"
        >
          <view v-if="agreed" class="rental-quote__tick"><LineIcon name="check" :size="12" color="#ffffff" :stroke-width="3" /></view>
          <view v-else class="rental-quote__tick rental-quote__tick--off" />
          <text>我已核对租期、费用与合同条款</text>
        </button>
        <view
          v-if="statusBox"
          :class="['rental-quote__status', { 'rental-quote__status--grey': frame === 'CLOSED' || frame === 'CONFIRMED' }]"
          role="status"
          data-testid="rental-quote-status"
        >
          <text class="rental-quote__status-title">{{ statusBox.title }}</text>
          <text class="rental-quote__status-text">{{ statusBox.text }}</text>
        </view>
        <view class="rental-quote__actions">
          <button class="rental-quote__contact" data-testid="rental-quote-contact" @click="contactMerchant">
            <LineIcon name="chat" :size="20" color="#536178" />
            <text>联系商家</text>
          </button>
          <button
            v-if="frame === 'OPEN' && confirmable"
            class="rental-quote__primary"
            data-testid="rental-quote-confirm"
            :disabled="!agreed || confirming"
            @click="confirm"
          >
            {{ confirming ? '正在确认…' : `确认合同并支付 ${totalText}` }}
          </button>
          <button
            v-else-if="frame === 'CONFIRMED' && inquiry.order"
            class="rental-quote__primary"
            data-testid="rental-quote-order"
            @click="emit('viewOrder', inquiry.order.rentalOrderId)"
          >
            查看租赁订单
          </button>
          <button
            v-else-if="reinquirable"
            class="rental-quote__primary"
            data-testid="rental-quote-reinquire"
            @click="reinquire"
          >
            重新询价
          </button>
          <button v-else class="rental-quote__primary" data-testid="rental-quote-waiting" disabled>
            {{ frame === 'WAITING' ? '等待商家报价' : `确认合同并支付 ${totalText}` }}
          </button>
        </view>
      </view>
    </template>

    <RentalConfirmSheet
      v-if="askingClose && closeCopy"
      :title="closeCopy.title"
      :text="closeCopy.text"
      :keep-label="closeCopy.keep"
      :go-label="closeCopy.go"
      :busy="closing"
      @keep="!closing && (askingClose = false)"
      @go="close"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board RentalQuote (390 wide). */
.rental-quote {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
}

button::after {
  border: 0;
}

.rental-quote__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.rental-quote__notice {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 10px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  line-height: 19px;
}

.rental-quote__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.rental-quote__card-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.rental-quote__card-title--rows {
  margin-bottom: 4px;
}

.rental-quote__card-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rental-quote__head {
  padding: 12px 14px 14px;
}

.rental-quote__head-line {
  display: flex;
  min-height: 24px;
  align-items: center;
  gap: 8px;
}

.rental-quote__head-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  white-space: nowrap;
}

.rental-quote__chip {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.rental-quote__chip--action { color: #ba470b; background: #fff0e3; }
.rental-quote__chip--done { color: #11804a; background: #e2f6ed; }
.rental-quote__chip--muted { color: #536178; background: #eef2f8; }

.rental-quote__head-time {
  overflow: hidden;
  min-width: 0;
  margin-left: auto;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rental-quote__validity {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.rental-quote__basis {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 10px;
  color: #536178;
  background: #fffaf5;
  font-size: 13px;
  line-height: 19px;
}

.rental-quote__lines {
  gap: 0;
  padding: 14px 14px 0;
}

.rental-quote__line {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
}

.rental-quote__line-name {
  flex: none;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.rental-quote__line-detail {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  color: #738096;
  font-size: 13px;
  line-height: 18px;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rental-quote__line-amount {
  flex: none;
  min-width: 68px;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-align: right;
  white-space: nowrap;
}

.rental-quote__line-amount--included {
  color: #627187;
}

.rental-quote__total {
  display: flex;
  min-height: 48px;
  align-items: center;
  border-top: 1px solid #f0f1f3;
}

.rental-quote__total-label {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.rental-quote__total-amount {
  margin-left: auto;
  color: #e85400;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.rental-quote__schedule {
  flex: none;
  margin-left: auto;
  padding: 1px 8px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.rental-quote__schedule--off {
  color: #ba470b;
  background: #fff0e3;
}

.rental-quote__grid {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
  line-height: 19px;
}

.rental-quote__grid-label {
  color: #627187;
  white-space: nowrap;
}

.rental-quote__grid-value {
  min-width: 0;
  word-break: break-all;
}

.rental-quote__term {
  display: flex;
  gap: 10px;
  font-size: 13px;
  line-height: 19px;
}

.rental-quote__term-label {
  width: 60px;
  flex: none;
  color: #627187;
  white-space: nowrap;
}

.rental-quote__term-text {
  min-width: 0;
  color: #536178;
}

.rental-quote__foot {
  display: flex;
  gap: 6px;
  padding-top: 10px;
  border-top: 1px solid #f0f1f3;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.rental-quote__pay {
  gap: 0;
  padding: 14px 14px 12px;
}

.rental-quote__pay-row {
  display: flex;
  min-height: 56px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.rental-quote__pay-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.rental-quote__pay-name {
  font-size: 14px;
  line-height: 20px;
}

.rental-quote__pay-state {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.rental-quote__pay-state--on {
  color: #ba470b;
}

.rental-quote__pay-amount {
  flex: none;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.rental-quote__pay-amount--off {
  color: #738096;
}

.rental-quote__close {
  height: 44px;
  align-self: center;
  padding: 0 20px;
  color: #627187;
  font-size: 14px;
  line-height: 44px;
  white-space: nowrap;
}

.rental-quote__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 16px calc(20px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.rental-quote__agree {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.rental-quote__tick {
  display: flex;
  width: 18px;
  height: 18px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #ff6a00;
  box-sizing: border-box;
}

.rental-quote__tick--off {
  border: 1.5px solid #91a0b4;
  background: #ffffff;
}

.rental-quote__status {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff0e3;
}

.rental-quote__status--grey {
  background: #eef2f8;
}

.rental-quote__status-title {
  color: #ba470b;
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
}

.rental-quote__status--grey .rental-quote__status-title {
  color: #202c42;
}

.rental-quote__status-text {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.rental-quote__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.rental-quote__contact {
  display: flex;
  width: 68px;
  height: 44px;
  flex: none;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #536178;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.rental-quote__primary {
  overflow: hidden;
  min-width: 0;
  height: 46px;
  flex: 1;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rental-quote__primary[disabled] {
  background: #c9d3e1;
}

.rental-quote__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.rental-quote__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 32px;
}

.rental-quote__state-title {
  font-size: 16px;
  font-weight: 700;
}

.rental-quote__state-text {
  color: #627187;
  font-size: 14px;
  line-height: 20px;
}

.rental-quote__state-button {
  height: 44px;
  padding: 0 24px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
  line-height: 44px;
  white-space: nowrap;
}
</style>
