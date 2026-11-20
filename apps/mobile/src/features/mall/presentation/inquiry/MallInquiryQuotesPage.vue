<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import { computed, ref, watch } from 'vue'

import { tradeApi } from '@/features/trade/data/trade-api-client'
import { ApiRequestError } from '@/shared/http/public-api-request'

import { loadInquiryCityName } from '../../application/inquiry-city'
import { inquiryCoverUrl } from '../../application/inquiry-cover'
import { describeMallInquiryBuyer } from '../../application/inquiry-form'
import {
  MALL_INQUIRY_SORT_LABELS,
  describeChoiceConfirm,
  describeChoiceFailure,
  describeInquiryBar,
  describeInquiryMerchants,
  describeInquirySummary,
  mallInquiryCloseConfirm,
  requirementHeadline,
} from '../../application/inquiry-view'
import { openSupportChat } from '@/features/support/application/support-entry'
import { mallInquiryApi } from '../../data/inquiry-api-client'
import {
  MALL_INQUIRY_SORTS,
  type MallInquiryDetail,
  type MallInquiryQuote,
  type MallInquirySort,
} from '../../domain/inquiry'
import type { MallInquiryTarget } from '../../navigation/mall-routes'
import MallInquiryConfirmSheet from './MallInquiryConfirmSheet.vue'
import MallInquiryHeader from './MallInquiryHeader.vue'

const props = defineProps<{
  inquiryId: string | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  // 转为订单: the confirm page of the chosen quote.
  checkout: [inquiryQuoteId: string, inquiryId: string, cityCode: string]
  viewOrder: [orderId: string]
  reinquire: [target: MallInquiryTarget]
}>()

type QuotesState =
  | { kind: 'loading' }
  | { kind: 'ready'; detail: MallInquiryDetail }
  | { kind: 'missing' }
  | { kind: 'error' }

type Asking = { kind: 'CHOOSE'; quote: MallInquiryQuote } | { kind: 'CLOSE' }

const state = ref<QuotesState>({ kind: 'loading' })
const sort = ref<MallInquirySort>('ALL')
const cityName = ref<string | null>(null)
// Whether the chosen quote's latest order is still alive (not closed): then 查看订单, not a new one.
const orderOpen = ref(false)
// Why the page was read again (a revised quote, an expired one…), shown on top until the next action.
const notice = ref<string | null>(null)
const asking = ref<Asking | null>(null)
const acting = ref(false)
let requestVersion = 0

const detail = computed(() => (state.value.kind === 'ready' ? state.value.detail : null))
const inquiry = computed(() => detail.value?.inquiry ?? null)
const summary = computed(() => (inquiry.value ? describeInquirySummary(inquiry.value, cityName.value) : null))
const cards = computed(() => (detail.value ? describeInquiryMerchants(detail.value.inquiry, detail.value.quotes) : []))
const bar = computed(() => (detail.value
  ? describeInquiryBar(detail.value.inquiry, detail.value.quotes, orderOpen.value, new Date())
  : null))
const badge = computed(() => (inquiry.value ? describeMallInquiryBuyer(inquiry.value.buyer).badge : null))
const cover = computed(() => (inquiry.value
  ? inquiryCoverUrl(inquiry.value.product.coverImage, inquiry.value.product.productId)
  : null))
// The lines of the requirement after the first, which the summary line already shows.
const requirementRest = computed(() => {
  const text = inquiry.value?.requirement ?? ''
  const headline = requirementHeadline(text)
  const index = text.indexOf(headline)
  return index >= 0 ? text.slice(index + headline.length).trim() : ''
})
const closable = computed(() => Boolean(inquiry.value?.closable))
const confirmCopy = computed(() => {
  const current = asking.value
  if (!current || !inquiry.value) return null
  if (current.kind === 'CLOSE') {
    const copy = mallInquiryCloseConfirm(inquiry.value)
    return { title: copy.title, text: copy.text, keep: copy.keep, go: copy.go, rows: [] }
  }
  const copy = describeChoiceConfirm(current.quote)
  return { title: copy.title, text: copy.text, keep: '再看看', go: '确认选择', rows: copy.rows }
})

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

// The chosen quote's latest order: alive unless it was closed without payment.
async function readOrderOpen(token: string, orderId: string | null): Promise<boolean> {
  if (!orderId) return false
  try {
    return (await tradeApi.read(token, orderId)).status !== 'CLOSED'
  } catch (error) {
    if (isUnauthorized(error)) throw error
    return false
  }
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
    const loaded = await mallInquiryApi.read(token, props.inquiryId, sort.value)
    const [open, name] = await Promise.all([
      readOrderOpen(token, loaded.inquiry.chosen?.orderId ?? null),
      cityName.value ? Promise.resolve(cityName.value) : loadInquiryCityName(loaded.inquiry.cityCode),
    ])
    if (requested !== requestVersion) return
    orderOpen.value = open
    cityName.value = name
    state.value = { kind: 'ready', detail: loaded }
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

function selectSort(next: MallInquirySort): void {
  if (next === sort.value) return
  sort.value = next
  void load()
}

function askChoose(quote: MallInquiryQuote): void {
  if (!quote.choosable) return
  notice.value = null
  asking.value = { kind: 'CHOOSE', quote }
}

async function choose(token: string, quote: MallInquiryQuote): Promise<void> {
  const current = inquiry.value
  if (!current) return
  try {
    const chosen = await mallInquiryApi.choose(token, current.inquiryId, current.version, quote.quoteId, quote.version)
    if (detail.value) state.value = { kind: 'ready', detail: { ...detail.value, inquiry: chosen } }
    uni.showToast({ title: '已选定报价', icon: 'none' })
  } catch (error) {
    const failure = describeChoiceFailure(error instanceof ApiRequestError
      ? { status: error.status, code: error.code, message: error.message }
      : null)
    if (failure.kind === 'UNAUTHORIZED') {
      emit('login')
      return
    }
    if (failure.kind === 'RELOAD') notice.value = failure.message
    else uni.showToast({ title: failure.message, icon: 'none' })
  }
}

async function close(token: string): Promise<void> {
  const current = inquiry.value
  if (!current) return
  try {
    await mallInquiryApi.close(token, current.inquiryId)
    notice.value = null
    uni.showToast({ title: '询价已关闭', icon: 'none' })
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({
      title: error instanceof ApiRequestError && error.code === 'MALL_INQUIRY_STATE_INVALID'
        ? '询价单已选定或已关闭，不能再关闭'
        : '没有关闭成功，请稍后重试',
      icon: 'none',
    })
  }
}

// Every answer is followed by a fresh read: the quotes change status with the inquiry.
async function confirmAsked(): Promise<void> {
  const token = props.accessToken
  const target = asking.value
  if (!token || !target || acting.value) return
  acting.value = true
  try {
    if (target.kind === 'CHOOSE') await choose(token, target.quote)
    else await close(token)
  } finally {
    acting.value = false
    asking.value = null
  }
  await load()
}

function toCheckout(): void {
  const current = inquiry.value
  if (!current?.chosen) return
  emit('checkout', current.chosen.quoteId, current.inquiryId, current.cityCode)
}

function reinquire(): void {
  const current = inquiry.value
  if (!current) return
  emit('reinquire', { productId: current.product.productId, cityCode: current.cityCode })
}

// 咨询 (step G1): the quoting merchant's conversation, with a card about this inquiry.
function consult(merchantId: string, total: string): void {
  const current = inquiry.value
  if (!current) return
  openSupportChat(
    { kind: 'MERCHANT', organizationId: merchantId },
    { type: 'MALL_INQUIRY', refId: current.inquiryId, title: current.product.name, subtitle: `报价 ${total}` },
  )
}

watch(() => [props.refreshKey, props.accessToken, props.inquiryId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="inquiry-quotes" data-testid="inquiry-quotes" :data-status="inquiry?.status ?? ''">
    <MallInquiryHeader title="报价比选" :badge="badge" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="inquiry-quotes__body" aria-label="正在加载">
      <view class="inquiry-quotes__skeleton" />
      <view class="inquiry-quotes__skeleton inquiry-quotes__skeleton--tall" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="inquiry-quotes__state" data-testid="inquiry-quotes-missing">
      <text class="inquiry-quotes__state-title">没有找到这份询价单</text>
      <button class="inquiry-quotes__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="inquiry-quotes__state" data-testid="inquiry-quotes-error">
      <text class="inquiry-quotes__state-title">加载失败</text>
      <text class="inquiry-quotes__state-text">请检查网络后重试</text>
      <button class="inquiry-quotes__state-button" data-testid="inquiry-quotes-retry" @click="load">重试</button>
    </view>

    <template v-else-if="inquiry && summary && bar">
      <view class="inquiry-quotes__body">
        <view v-if="notice" class="inquiry-quotes__notice" role="status" data-testid="inquiry-quotes-notice">
          <LineIcon name="info" :size="16" color="#ba470b" :stroke-width="2" />
          <text>{{ notice }}</text>
        </view>

        <view class="inquiry-quotes__summary" data-testid="inquiry-quotes-summary">
          <view class="inquiry-quotes__product">
            <image v-if="cover" class="inquiry-quotes__image" :src="cover" mode="aspectFill" />
            <view v-else class="inquiry-quotes__image inquiry-quotes__image--empty">
              <LineIcon name="image" :size="24" color="#91a0b4" />
            </view>
            <view class="inquiry-quotes__product-copy">
              <text class="inquiry-quotes__title" data-testid="inquiry-quotes-title">{{ summary.title }}</text>
              <text class="inquiry-quotes__meta" data-testid="inquiry-quotes-spec">{{ summary.spec }}</text>
              <text class="inquiry-quotes__meta" data-testid="inquiry-quotes-delivery">{{ summary.delivery }}</text>
            </view>
          </view>
          <text v-if="requirementRest" class="inquiry-quotes__requirement" data-testid="inquiry-quotes-requirement">
            {{ requirementRest }}
          </text>
          <view class="inquiry-quotes__status-row">
            <image class="inquiry-quotes__clock" src="/static/images/trade/clock-orange.svg" mode="aspectFit" />
            <text class="inquiry-quotes__status-line" data-testid="inquiry-quotes-line">
              <template v-if="summary.count !== null">已收到 <text class="inquiry-quotes__count">{{ summary.count }}</text>{{ summary.line.slice(`已收到 ${summary.count}`.length) }}</template>
              <template v-else>{{ summary.line }}</template>
            </text>
            <text
              :class="['inquiry-quotes__chip', `inquiry-quotes__chip--${summary.tone.toLowerCase()}`]"
              data-testid="inquiry-quotes-status"
            >
              {{ summary.statusText }}
            </text>
          </view>
        </view>

        <view v-if="detail && detail.quotes.length > 0" class="inquiry-quotes__sorts" role="group" aria-label="排序">
          <button
            v-for="code in MALL_INQUIRY_SORTS"
            :key="code"
            class="inquiry-quotes__sort"
            :data-testid="`inquiry-quotes-sort-${code.toLowerCase()}`"
            :aria-pressed="code === sort"
            @click="selectSort(code)"
          >
            <text :class="['inquiry-quotes__sort-pill', { 'inquiry-quotes__sort-pill--on': code === sort }]">
              {{ MALL_INQUIRY_SORT_LABELS[code] }}
            </text>
          </button>
        </view>

        <template v-for="card in cards" :key="card.key">
          <view
            v-if="card.kind === 'QUOTE'"
            :class="['inquiry-quotes__card', { 'inquiry-quotes__card--chosen': card.chosen }]"
            :data-testid="`inquiry-quote-${card.quote.offerId}`"
            :data-quote-status="card.quote.status"
          >
            <view class="inquiry-quotes__card-head">
              <text class="inquiry-quotes__name">{{ card.name }}</text>
              <text
                v-if="card.badge"
                :class="['inquiry-quotes__badge', `inquiry-quotes__badge--${card.badge.tone.toLowerCase()}`]"
                data-testid="inquiry-quote-badge"
              >
                {{ card.badge.text }}
              </text>
            </view>
            <view class="inquiry-quotes__card-lines">
              <text data-testid="inquiry-quote-model">型号：{{ card.quote.model }}</text>
              <text data-testid="inquiry-quote-version">{{ card.kindLabel }} · {{ card.versionText }}</text>
            </view>
            <view class="inquiry-quotes__grid">
              <view class="inquiry-quotes__cell">
                <text class="inquiry-quotes__cell-value inquiry-quotes__cell-value--price" data-testid="inquiry-quote-unit">{{ card.unit }}</text>
                <text class="inquiry-quotes__cell-label">单价 / 台</text>
              </view>
              <view class="inquiry-quotes__cell">
                <text class="inquiry-quotes__cell-value" data-testid="inquiry-quote-lead">{{ card.lead }}</text>
                <text class="inquiry-quotes__cell-label">交期</text>
              </view>
              <view class="inquiry-quotes__cell">
                <text class="inquiry-quotes__cell-value" data-testid="inquiry-quote-valid">{{ card.validUntil }}</text>
                <text class="inquiry-quotes__cell-label">报价有效期至</text>
              </view>
            </view>
            <text v-if="card.includes" class="inquiry-quotes__includes" data-testid="inquiry-quote-includes">含：{{ card.includes }}</text>
            <view class="inquiry-quotes__card-foot">
              <text class="inquiry-quotes__total-label">
                {{ card.totalLabel }}<text class="inquiry-quotes__total" data-testid="inquiry-quote-total">{{ card.total }}</text>
              </text>
              <view class="inquiry-quotes__card-actions">
                <button class="inquiry-quotes__consult" data-testid="inquiry-quote-consult" @click="consult(card.quote.merchant.organizationId, card.total)">咨询</button>
                <button
                  :class="[
                    'inquiry-quotes__choose',
                    { 'inquiry-quotes__choose--on': card.chosen, 'inquiry-quotes__choose--off': !card.chosen && !card.choosable },
                  ]"
                  data-testid="inquiry-quote-choose"
                  :aria-pressed="card.chosen"
                  :disabled="!card.choosable"
                  @click="askChoose(card.quote)"
                >
                  {{ card.buttonText }}
                </button>
              </view>
            </view>
          </view>

          <view
            v-else
            class="inquiry-quotes__card inquiry-quotes__card--plain"
            :data-testid="`inquiry-merchant-${card.key}`"
            :data-invitation="card.kind"
          >
            <view class="inquiry-quotes__card-head">
              <text class="inquiry-quotes__name">{{ card.name }}</text>
              <text class="inquiry-quotes__kind">{{ card.kindLabel }}</text>
              <text
                :class="['inquiry-quotes__badge', `inquiry-quotes__badge--${card.badge.tone.toLowerCase()}`]"
                data-testid="inquiry-merchant-badge"
              >
                {{ card.badge.text }}
              </text>
            </view>
            <text class="inquiry-quotes__plain-line" data-testid="inquiry-merchant-line">{{ card.line }}</text>
          </view>
        </template>

        <text class="inquiry-quotes__footnote">
          选定后转为订单，按所选报价下单付款，销售方为所选商家。报价过期后需重新询价。
        </text>

        <button
          v-if="closable"
          class="inquiry-quotes__close"
          data-testid="inquiry-quotes-close"
          @click="notice = null; asking = { kind: 'CLOSE' }"
        >
          关闭询价
        </button>
      </view>

      <view class="inquiry-quotes__bar" data-testid="inquiry-quotes-bar" :data-bar="bar.kind">
        <template v-if="bar.kind === 'ORDER' || bar.kind === 'VIEW_ORDER' || bar.kind === 'EXPIRED'">
          <view class="inquiry-quotes__bar-copy">
            <text class="inquiry-quotes__bar-note" data-testid="inquiry-quotes-chosen">
              {{ bar.kind === 'EXPIRED' ? `已选 ${bar.merchant} · 报价已过有效期` : `已选 ${bar.merchant}` }}
            </text>
            <text class="inquiry-quotes__bar-total" data-testid="inquiry-quotes-chosen-total">{{ bar.total }}</text>
          </view>
          <button
            v-if="bar.kind === 'ORDER'"
            class="inquiry-quotes__bar-button"
            data-testid="inquiry-quotes-checkout"
            @click="toCheckout"
          >
            转为订单
          </button>
          <button
            v-else-if="bar.kind === 'VIEW_ORDER'"
            class="inquiry-quotes__bar-button"
            data-testid="inquiry-quotes-view-order"
            @click="emit('viewOrder', bar.orderId)"
          >
            查看订单
          </button>
          <button v-else class="inquiry-quotes__bar-button" data-testid="inquiry-quotes-reinquire" @click="reinquire">
            重新询价
          </button>
        </template>
        <template v-else-if="bar.kind === 'REINQUIRE'">
          <text class="inquiry-quotes__bar-text" data-testid="inquiry-quotes-bar-text">{{ bar.text }}</text>
          <button class="inquiry-quotes__bar-button" data-testid="inquiry-quotes-reinquire" @click="reinquire">
            重新询价
          </button>
        </template>
        <template v-else>
          <text class="inquiry-quotes__bar-text">请选择一份报价</text>
          <button class="inquiry-quotes__bar-button inquiry-quotes__bar-button--off" data-testid="inquiry-quotes-checkout" disabled>
            转为订单
          </button>
        </template>
      </view>
    </template>

    <MallInquiryConfirmSheet
      v-if="asking && confirmCopy"
      :title="confirmCopy.title"
      :text="confirmCopy.text"
      :keep-label="confirmCopy.keep"
      :go-label="confirmCopy.go"
      :rows="confirmCopy.rows"
      :busy="acting"
      @keep="!acting && (asking = null)"
      @go="confirmAsked"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board QuoteGoods (390 wide). */
.inquiry-quotes {
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

.inquiry-quotes__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.inquiry-quotes__notice {
  display: flex;
  padding: 8px 12px;
  align-items: flex-start;
  gap: 6px;
  border-radius: 10px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  line-height: 18px;
}

.inquiry-quotes__summary {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.inquiry-quotes__product {
  display: flex;
  gap: 12px;
}

.inquiry-quotes__image {
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.inquiry-quotes__image--empty {
  display: flex;
  align-items: center;
  justify-content: center;
}

.inquiry-quotes__product-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.inquiry-quotes__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.inquiry-quotes__meta {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.inquiry-quotes__requirement {
  color: #536178;
  font-size: 13px;
  line-height: 19px;
  white-space: pre-wrap;
}

.inquiry-quotes__status-row {
  display: flex;
  padding: 7px 10px;
  align-items: center;
  gap: 6px;
  border-radius: 10px;
  color: #536178;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 18px;
}

.inquiry-quotes__status-line {
  min-width: 0;
  flex: 1;
}

.inquiry-quotes__clock {
  width: 14px;
  height: 14px;
  flex: none;
}

.inquiry-quotes__count {
  color: #e85400;
  font-weight: 700;
}

.inquiry-quotes__chip {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.inquiry-quotes__chip--pending { color: #ba470b; background: #fff0e3; }
.inquiry-quotes__chip--quoting { color: #1d63c4; background: #e6f0ff; }
.inquiry-quotes__chip--chosen { color: #11804a; background: #e2f6ed; }
.inquiry-quotes__chip--off { color: #627187; background: #eef2f8; }

.inquiry-quotes__sorts {
  display: flex;
  gap: 8px;
}

.inquiry-quotes__sort {
  margin: -6px 0;
  padding: 6px 0;
}

.inquiry-quotes__sort-pill {
  display: flex;
  height: 32px;
  padding: 0 12px;
  align-items: center;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.inquiry-quotes__sort-pill--on {
  color: #ffffff;
  background: #202c42;
}

.inquiry-quotes__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1.5px solid #ffffff;
  border-radius: 14px;
  background: #ffffff;
  box-sizing: border-box;
  box-shadow: var(--app-card-shadow);
}

.inquiry-quotes__card--chosen {
  border-color: #ff6a00;
  background: #fffdfb;
}

.inquiry-quotes__card--plain {
  gap: 6px;
}

.inquiry-quotes__card-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.inquiry-quotes__name {
  overflow: hidden;
  min-width: 0;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inquiry-quotes__kind {
  flex: none;
  padding: 0 5px;
  border: 1px solid #e5ebf3;
  border-radius: 4px;
  color: #536178;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.inquiry-quotes__badge {
  flex: none;
  margin-left: auto;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.inquiry-quotes__badge--price { color: #ba470b; background: #fff0e3; }
.inquiry-quotes__badge--lead { color: #1d63c4; background: #e6f0ff; }
.inquiry-quotes__badge--chosen { color: #11804a; background: #e2f6ed; }
.inquiry-quotes__badge--wait { color: #ba470b; background: #fff0e3; }
.inquiry-quotes__badge--off { color: #627187; background: #eef2f8; }

.inquiry-quotes__card-lines {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.inquiry-quotes__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  padding: 8px 0;
  border-radius: 10px;
  background: #f9fafb;
}

.inquiry-quotes__cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.inquiry-quotes__cell-value {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  white-space: nowrap;
}

.inquiry-quotes__cell-value--price {
  color: #e85400;
}

.inquiry-quotes__cell-label {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.inquiry-quotes__includes {
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

/* On a narrow phone the buttons move under the total instead of covering it. */
.inquiry-quotes__card-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.inquiry-quotes__total-label {
  flex: none;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.inquiry-quotes__total {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
}

.inquiry-quotes__card-actions {
  display: flex;
  margin-left: auto;
  gap: 8px;
}

.inquiry-quotes__consult {
  flex: none;
  height: 36px;
  padding: 0 14px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  background: #ffffff;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.inquiry-quotes__choose {
  flex: none;
  min-width: 93px;
  height: 36px;
  padding: 0 14px;
  border-radius: 999px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  font-weight: 500;
  line-height: 36px;
  text-align: center;
  white-space: nowrap;
}

.inquiry-quotes__choose--on {
  color: #ffffff;
  background: #ff6a00;
}

.inquiry-quotes__choose--on[disabled] {
  color: #ffffff;
  background: #ff6a00;
}

.inquiry-quotes__choose--off,
.inquiry-quotes__choose--off[disabled] {
  color: #91a0b4;
  background: #eef2f8;
}

.inquiry-quotes__plain-line {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.inquiry-quotes__footnote {
  color: #627187;
  font-size: 12px;
  line-height: 19px;
}

/* A grey text link, as 关闭询价 on the board RentalQuote: closing is not the page's main way on. */
.inquiry-quotes__close {
  align-self: center;
  height: 44px;
  padding: 0 16px;
  color: #536178;
  background: transparent;
  font-size: 14px;
  line-height: 44px;
  text-align: center;
}

.inquiry-quotes__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 12px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.inquiry-quotes__bar-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 1px;
}

.inquiry-quotes__bar-note {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inquiry-quotes__bar-total {
  color: #e85400;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
  white-space: nowrap;
}

.inquiry-quotes__bar-text {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  color: #627187;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inquiry-quotes__bar-button {
  width: 160px;
  height: 46px;
  flex: none;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
  white-space: nowrap;
}

.inquiry-quotes__bar-button--off,
.inquiry-quotes__bar-button--off[disabled] {
  color: #ffffff;
  background: #c9d3e1;
}

.inquiry-quotes__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.inquiry-quotes__skeleton--tall {
  height: 260px;
}

.inquiry-quotes__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 32px;
}

.inquiry-quotes__state-title {
  font-size: 16px;
  font-weight: 700;
  text-align: center;
}

.inquiry-quotes__state-text {
  color: #627187;
  font-size: 14px;
  line-height: 20px;
}

.inquiry-quotes__state-button {
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
