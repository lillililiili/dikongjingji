<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { SERVICE_ORDER_PREVIEW_ENABLED } from '@/features/serviceorder/application/serviceorder-preview'
import { openSupportChat } from '@/features/support/application/support-entry'
import { ApiRequestError } from '@/shared/http/public-api-request'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  buildDemandCompareRows,
  demandChoiceText,
  describeDemandPublisher,
  describeDemandQuote,
  describeDemandQuoteItems,
  describeDemandQuoteLines,
  formatDemandInstant,
  formatDemandAmount,
  resolveDemandFollowUp,
} from '../../application/demand-view'
import { demandApi } from '../../data/demand-api-client'
import type { Demand, DemandQuote } from '../../domain/demand'
import DemandConfirmSheet from '../shared/DemandConfirmSheet.vue'
import DemandPageHeader from '../shared/DemandPageHeader.vue'
import DemandSummaryCard from '../shared/DemandSummaryCard.vue'
import { useDemandChoice } from '../shared/use-demand-choice'

const props = defineProps<{
  demandId: string | null
  // The provider tab to open first (查看方案 vN on the quotes page); null opens 对比明细.
  initialQuoteId?: string | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  republish: [demandId: string]
  openProvider: [providerId: string, cityCode: string]
  // The service order the choice made (S4-3).
  serviceOrder: [orderId: string]
  inspectionBooking: [demandId: string, reservationId: string | null]
}>()

type PageState =
  | { kind: 'loading' }
  | { kind: 'ready'; demand: Demand; quotes: DemandQuote[]; demandVersion: number }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<PageState>({ kind: 'loading' })
const pickedId = ref<string | null>(null)
const notice = ref<string | null>(null)
let requestVersion = 0

const ready = computed(() => (state.value.kind === 'ready' ? state.value : null))
const demand = computed(() => ready.value?.demand ?? null)
const quotes = computed(() => ready.value?.quotes ?? [])
const rows = computed(() => buildDemandCompareRows(quotes.value))
const choosable = computed(() => quotes.value.filter((quote) => quote.choosable))
const picked = computed(() => choosable.value.find((quote) => quote.quoteId === pickedId.value) ?? null)
// Three columns fit a phone; more scroll sideways with the labels kept at the left.
const tableWidth = computed(() => `${64 + Math.max(quotes.value.length, 1) * 104}px`)
const columns = computed(() => `64px repeat(${Math.max(quotes.value.length, 1)}, minmax(0, 1fr))`)
const followUp = computed(() => resolveDemandFollowUp(demand.value, SERVICE_ORDER_PREVIEW_ENABLED))

// The PlanCompare board's tabs: 对比明细, then one tab per provider showing that quote alone.
const tab = ref<string>(props.initialQuoteId ?? 'compare')
const tabQuote = computed(() => quotes.value.find((quote) => quote.quoteId === tab.value) ?? null)
const tabView = computed(() => (tabQuote.value ? describeDemandQuote(tabQuote.value) : null))
// 1.3.0 on a provider tab: 报价明细 and the comparison items the quote fills, after 交付物.
const tabLines = computed(() => (tabQuote.value ? describeDemandQuoteLines(tabQuote.value.lines) : []))
const tabRows = computed(() => {
  if (!tabQuote.value || !tabView.value) return []
  const rows = tabView.value.rows
  return [...rows.slice(0, -1), ...describeDemandQuoteItems(tabQuote.value), ...rows.slice(-1)]
})
// What the main button would choose: the picked column, or the quote of the open provider tab.
const target = computed(() => {
  if (tab.value === 'compare') return picked.value
  return tabQuote.value?.choosable ? tabQuote.value : null
})
// 沟通 goes to the provider in view: the open tab's, else the picked column's, else the first.
const talkTo = computed(() => tabQuote.value ?? picked.value ?? quotes.value[0] ?? null)

function selectTab(next: string): void {
  tab.value = next
}

// 查看方案 on the comparison opens the picked provider's tab (as on the board).
function viewPlan(): void {
  const quote = picked.value ?? quotes.value[0]
  if (quote) tab.value = quote.quoteId
}

function talk(): void {
  const quote = talkTo.value
  if (quote) openSupportChat({ kind: 'PROVIDER', organizationId: quote.provider.providerId })
}

function openProvider(quote: DemandQuote): void {
  const cityCode = demand.value?.cityCode
  if (cityCode) emit('openProvider', quote.provider.providerId, cityCode)
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

async function load(): Promise<void> {
  const token = props.accessToken
  const demandId = props.demandId
  if (!token) {
    emit('login')
    return
  }
  if (!demandId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const [loadedDemand, list] = await Promise.all([
      demandApi.read(token, demandId),
      demandApi.listQuotes(token, demandId, 'ALL'),
    ])
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', demand: loadedDemand, quotes: list.items, demandVersion: list.demandVersion }
    // Keep the column the user picked while it can still be chosen, otherwise the first that can.
    if (!list.items.some((quote) => quote.choosable && quote.quoteId === pickedId.value)) {
      pickedId.value = list.items.find((quote) => quote.choosable)?.quoteId ?? null
    }
    if (tab.value !== 'compare' && !list.items.some((quote) => quote.quoteId === tab.value)) tab.value = 'compare'
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

function pick(quote: DemandQuote): void {
  if (quote.choosable) pickedId.value = quote.quoteId
}

const choice = useDemandChoice({
  getAccessToken: () => props.accessToken,
  getDemandVersion: () => ready.value?.demandVersion ?? null,
  getDemandId: () => props.demandId,
  onLogin: () => emit('login'),
  reload: load,
  notify: (message) => { notice.value = message },
  openServiceOrder: SERVICE_ORDER_PREVIEW_ENABLED ? (orderId) => emit('serviceOrder', orderId) : undefined,
})
const { asking: choosing, busy: choosingBusy } = choice

watch(() => [props.refreshKey, props.accessToken, props.demandId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="demand-compare" data-testid="demand-compare-page" :data-status="demand?.status">
    <view class="demand-compare__top">
      <DemandPageHeader
        title="方案对比"
        :badge="demand ? describeDemandPublisher(demand.publisher) : null"
        @back="emit('back')"
      >
        <template v-if="quotes.length" #bottom>
          <scroll-view class="demand-compare__tabs-scroll" scroll-x :show-scrollbar="false">
            <view class="demand-compare__tabs" role="tablist" aria-label="方案">
              <button
                :class="['demand-compare__tab', { 'demand-compare__tab--on': tab === 'compare' }]"
                data-testid="demand-compare-tab-compare"
                role="tab"
                :aria-selected="tab === 'compare'"
                @click="selectTab('compare')"
              >
                对比明细
              </button>
              <button
                v-for="quote in quotes"
                :key="quote.quoteId"
                :class="['demand-compare__tab', { 'demand-compare__tab--on': tab === quote.quoteId }]"
                :data-testid="`demand-compare-tab-${quote.quoteId}`"
                role="tab"
                :aria-selected="tab === quote.quoteId"
                @click="selectTab(quote.quoteId)"
              >
                {{ quote.provider.name }}
              </button>
            </view>
          </scroll-view>
        </template>
      </DemandPageHeader>
    </view>

    <view v-if="state.kind === 'loading'" class="demand-compare__body" aria-label="正在加载">
      <view class="demand-compare__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing' || state.kind === 'error'" class="demand-compare__state" data-testid="demand-compare-error">
      <text class="demand-compare__state-title">{{ state.kind === 'missing' ? '没有找到这份需求' : '加载失败' }}</text>
      <button v-if="state.kind === 'error'" class="demand-compare__state-button" @click="load">重试</button>
    </view>

    <template v-else-if="demand">
      <view v-if="tabQuote && tabView" class="demand-compare__body" :data-testid="`demand-compare-plan-${tabQuote.quoteId}`">
        <view class="demand-compare__plan">
          <view class="demand-compare__plan-head">
            <text class="demand-compare__plan-name">{{ tabQuote.provider.name }}</text>
            <text v-if="tabView.credential" class="demand-compare__plan-cert" data-testid="demand-compare-credential">
              {{ tabView.credential }}
            </text>
            <button
              v-if="demand.cityCode"
              class="demand-compare__plan-link"
              data-testid="demand-compare-provider"
              @click="openProvider(tabQuote)"
            >
              <text>服务商主页</text>
              <LineIcon name="forward" :size="12" color="#91a0b4" :stroke-width="2.4" />
            </button>
          </view>
          <view class="demand-compare__plan-line">
            <view class="demand-compare__plan-meta">
              <text class="demand-compare__plan-version">
                方案 v{{ tabQuote.version }} · {{ formatDemandInstant(tabQuote.revisedAt) }} {{ tabQuote.version > 1 ? '更新' : '报价' }}
              </text>
              <text v-if="tabQuote.validUntil" class="demand-compare__plan-valid">
                报价有效期至 {{ formatDemandInstant(tabQuote.validUntil) }}
              </text>
            </view>
            <text class="demand-compare__plan-price">{{ tabView.price }}</text>
          </view>
          <text v-if="tabView.statusTag" class="demand-compare__plan-tag">{{ tabView.statusTag }}</text>
        </view>
        <view v-if="tabLines.length" class="demand-compare__plan demand-compare__plan--lines" data-testid="demand-compare-lines">
          <text class="demand-compare__plan-title">报价明细</text>
          <view v-for="line in tabLines" :key="line.key" class="demand-compare__line" data-testid="demand-compare-line">
            <text class="demand-compare__line-name">{{ line.name }}</text>
            <text class="demand-compare__line-qty">{{ line.quantity }}</text>
            <text class="demand-compare__line-amount">{{ line.amount }}</text>
          </view>
        </view>
        <view class="demand-compare__plan">
          <text class="demand-compare__plan-title">方案内容</text>
          <view class="demand-compare__plan-rows">
            <template v-for="row in tabRows" :key="row.label">
              <text class="demand-compare__plan-key">{{ row.label }}</text>
              <text class="demand-compare__plan-value">{{ row.value }}</text>
            </template>
          </view>
          <text class="demand-compare__plan-foot">本平台不承担飞行审批与空中调度。</text>
        </view>
      </view>

      <view v-else class="demand-compare__body">
        <DemandSummaryCard :demand="demand" :quote-count="quotes.length" />

        <view v-if="quotes.length === 0" class="demand-compare__empty" data-testid="demand-compare-empty">
          还没有可以对比的报价
        </view>

        <scroll-view v-else class="demand-compare__scroll" scroll-x :show-scrollbar="false">
          <view
            class="demand-compare__table"
            data-testid="demand-compare-table"
            :style="{ minWidth: quotes.length > 3 ? tableWidth : '100%' }"
          >
            <view class="demand-compare__row demand-compare__row--head" role="radiogroup" aria-label="选择方案" :style="{ gridTemplateColumns: columns }">
              <text class="demand-compare__corner">对比项</text>
              <button
                v-for="quote in quotes"
                :key="quote.quoteId"
                :class="['demand-compare__head', { 'demand-compare__head--on': quote.quoteId === pickedId || quote.status === 'CHOSEN' }]"
                :data-testid="`demand-compare-head-${quote.quoteId}`"
                role="radio"
                :aria-checked="quote.quoteId === pickedId"
                :aria-disabled="!quote.choosable"
                @click="pick(quote)"
              >
                <view
                  v-if="quote.quoteId === pickedId || quote.status === 'CHOSEN'"
                  class="demand-compare__radio demand-compare__radio--on"
                >
                  <LineIcon name="check" :size="12" color="#ffffff" :stroke-width="3" />
                </view>
                <view v-else-if="quote.choosable" class="demand-compare__radio" />
                <text class="demand-compare__head-name">{{ quote.provider.name }}</text>
              </button>
            </view>
            <view
              v-for="row in rows"
              :key="row.label"
              class="demand-compare__row"
              :style="{ gridTemplateColumns: columns }"
            >
              <text class="demand-compare__label">{{ row.label }}</text>
              <text
                v-for="(cell, column) in row.cells"
                :key="quotes[column].quoteId"
                :class="[
                  'demand-compare__cell',
                  {
                    'demand-compare__cell--price': row.price,
                    'demand-compare__cell--on': quotes[column].quoteId === pickedId || quotes[column].status === 'CHOSEN',
                    'demand-compare__cell--muted': row.muted?.[column],
                  },
                ]"
              >
                {{ cell }}
              </text>
            </view>
          </view>
        </scroll-view>

        <view v-if="demand.status === 'OPEN' || demand.status === 'QUOTED'" class="demand-compare__other">
          <view class="demand-compare__other-copy">
            <text class="demand-compare__other-title">都不合适？</text>
            <text class="demand-compare__other-text">可以改一改需求后重新发布</text>
          </view>
          <button class="demand-compare__other-button" data-testid="demand-compare-republish" @click="emit('republish', demand.demandId)">
            重新发布
          </button>
        </view>
      </view>

      <view v-if="demand.status === 'CHOSEN' && followUp.kind !== 'UNAVAILABLE'" class="demand-compare__bar" data-testid="demand-chosen-bar">
        <view class="demand-compare__chosen">
          <text class="demand-compare__chosen-label">已选</text>
          <text class="demand-compare__chosen-name">{{ demand.chosen?.providerName }} v{{ demand.chosen?.quoteVersion }}</text>
        </view>
        <button v-if="followUp.kind === 'INSPECTION_BOOKING'" class="demand-compare__choose demand-compare__choose--order"
          data-testid="demand-inspection-booking" @click="emit('inspectionBooking', demand.demandId, demand.inspectionReservationId)">
          {{ followUp.label }}
        </button>
        <button v-else class="demand-compare__choose demand-compare__choose--order" data-testid="demand-open-service-order" @click="emit('serviceOrder', followUp.serviceOrderId)">
          查看订单
        </button>
      </view>
      <view v-else-if="demand.status === 'CHOSEN' && followUp.kind === 'UNAVAILABLE'" class="demand-compare__bar demand-compare__bar--gate" data-testid="demand-follow-up">
        <LineIcon name="info" :size="14" color="#ba470b" :stroke-width="2" />
        <view class="demand-compare__gate-copy">
          <text class="demand-compare__gate-title">
            已选定 {{ demand.chosen?.providerName }} · {{ followUp.title }}
          </text>
          <text class="demand-compare__gate-text">{{ followUp.text }}</text>
        </view>
      </view>
      <view v-else-if="quotes.length" class="demand-compare__bar">
        <button class="demand-compare__talk" data-testid="demand-compare-talk" @click="talk">
          <LineIcon name="chat" :size="22" color="#536178" />
          <text>沟通</text>
        </button>
        <button
          v-if="tab === 'compare'"
          class="demand-compare__view-plan"
          data-testid="demand-compare-view-plan"
          @click="viewPlan"
        >
          查看方案
        </button>
        <button
          v-if="target"
          class="demand-compare__choose"
          data-testid="demand-compare-choose"
          @click="choice.ask(target)"
        >
          确认选择 {{ target.provider.name }}
        </button>
      </view>
    </template>

    <view v-if="notice" class="demand-compare__toast" role="status" data-testid="demand-compare-notice">
      <text class="demand-compare__toast-text">{{ notice }}</text>
      <button class="demand-compare__toast-close" aria-label="关闭提示" @click="notice = null">
        <LineIcon name="close" :size="14" color="#738096" :stroke-width="2" />
      </button>
    </view>

    <DemandConfirmSheet
      v-if="choosing"
      :title="`选择 ${choosing.provider.name} 的报价？`"
      :text="demandChoiceText(demand ?? { type: 'INSPECTION' }, choosing, SERVICE_ORDER_PREVIEW_ENABLED)"
      keep-label="再想想"
      go-label="确认选择"
      :busy="choosingBusy"
      @keep="choice.keep"
      @go="choice.confirm"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board PlanCompare (对比明细, 390 wide). */
.demand-compare {
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
}

button::after {
  border: 0;
}

.demand-compare__top {
  position: sticky;
  top: 0;
  z-index: 5;
}

.demand-compare__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 24px;
}

.demand-compare__tabs-scroll {
  width: 100%;
  border-bottom: 1px solid #f0f1f3;
  background: #ffffff;
  white-space: nowrap;
}

.demand-compare__tabs {
  display: flex;
  min-width: 100%;
  padding: 0 16px;
  justify-content: space-between;
  gap: 18px;
  box-sizing: border-box;
}

.demand-compare__tab {
  min-height: 44px;
  flex: none;
  padding: 10px 0 7px;
  border-bottom: 3px solid transparent;
  border-radius: 0;
  color: #627187;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.demand-compare__tab--on {
  border-bottom-color: #ff6a00;
  color: #202c42;
  font-weight: 700;
}

.demand-compare__plan {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 8px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.demand-compare__plan-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.demand-compare__plan-name {
  overflow: hidden;
  min-width: 0;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.demand-compare__plan-cert {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.demand-compare__plan-link {
  display: flex;
  height: 44px;
  flex: none;
  margin: -10px 0 -10px auto;
  padding-left: 4px;
  align-items: center;
  gap: 2px;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.demand-compare__plan-line {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.demand-compare__plan-meta {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.demand-compare__plan-version {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.demand-compare__plan-valid {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.demand-compare__plan-price {
  flex: none;
  color: #e85400;
  font-size: 22px;
  font-weight: 700;
  line-height: 30px;
  white-space: nowrap;
}

.demand-compare__plan-tag {
  color: #11804a;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
}

.demand-compare__plan-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.demand-compare__plan-rows {
  display: grid;
  grid-template-columns: minmax(52px, max-content) minmax(0, 1fr);
  gap: 6px 8px;
  font-size: 13px;
  line-height: 19px;
}

.demand-compare__plan-key {
  color: #627187;
  white-space: nowrap;
}

.demand-compare__plan-value {
  min-width: 0;
  color: #202c42;
  word-break: break-all;
}

.demand-compare__plan--lines {
  gap: 0;
  padding-bottom: 0;
}

.demand-compare__plan--lines .demand-compare__plan-title {
  margin-bottom: 4px;
}

/* PlanCompare board 报价明细: 44px rows, the quantity in grey, the amount in a 68px column. */
.demand-compare__line {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
}

.demand-compare__line-name {
  min-width: 0;
  flex: 1;
  font-size: 14px;
  line-height: 20px;
}

.demand-compare__line-qty {
  flex: none;
  color: #738096;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.demand-compare__line-amount {
  width: 68px;
  flex: none;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-align: right;
  white-space: nowrap;
}

.demand-compare__chosen {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}

.demand-compare__chosen-label {
  flex: none;
  color: #627187;
  font-size: 13px;
}

.demand-compare__chosen-name {
  overflow: hidden;
  min-width: 0;
  font-size: 15px;
  font-weight: 500;
  text-overflow: ellipsis;
}

.demand-compare__plan-foot {
  padding-top: 10px;
  border-top: 1px solid #f0f1f3;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.demand-compare__scroll {
  width: 100%;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.demand-compare__table {
  display: flex;
  flex-direction: column;
}

.demand-compare__row {
  display: grid;
  border-top: 1px solid #f0f1f3;
}

.demand-compare__row--head {
  border-top: 0;
}

.demand-compare__corner {
  display: flex;
  padding: 0 0 8px 12px;
  align-items: flex-end;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.demand-compare__head {
  display: flex;
  min-width: 0;
  min-height: 68px;
  padding: 10px 2px 8px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: #ffffff;
  box-sizing: border-box;
}

.demand-compare__head--on {
  background: #fff0e3;
}

.demand-compare__radio {
  width: 20px;
  height: 20px;
  flex: none;
  border: 1.5px solid #c9d3e1;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.demand-compare__radio--on {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  background: #ff6a00;
}

.demand-compare__head-name {
  overflow: hidden;
  max-width: 100%;
  font-size: 13px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.demand-compare__label {
  display: flex;
  padding: 10px 0 10px 12px;
  align-items: center;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.demand-compare__cell {
  display: flex;
  min-width: 0;
  padding: 10px 6px;
  align-items: center;
  justify-content: center;
  background: #ffffff;
  color: #202c42;
  font-size: 13px;
  line-height: 18px;
  text-align: center;
  /* Chinese wraps between characters anyway; a time like 23:02 stays whole. */
  overflow-wrap: anywhere;
}

.demand-compare__cell--on {
  background: #fffaf5;
}

.demand-compare__cell--muted {
  color: #738096;
}

.demand-compare__cell--price {
  color: #e85400;
  font-size: 15px;
  font-weight: 700;
}

.demand-compare__empty {
  padding: 36px 16px;
  border-radius: 14px;
  color: #627187;
  background: #ffffff;
  font-size: 14px;
  text-align: center;
}

.demand-compare__other {
  display: flex;
  padding: 12px 14px;
  align-items: center;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.demand-compare__other-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.demand-compare__other-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.demand-compare__other-text {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.demand-compare__other-button {
  height: 36px;
  flex: none;
  padding: 0 16px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 14px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.demand-compare__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  padding: 10px 16px calc(14px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 10px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

.demand-compare__bar--gate {
  align-items: flex-start;
  gap: 6px;
  background: #fffaf5;
}

.demand-compare__gate-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.demand-compare__gate-title {
  color: #ba470b;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
}

.demand-compare__gate-text {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.demand-compare__talk {
  display: flex;
  width: 44px;
  height: 46px;
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

.demand-compare__view-plan {
  height: 46px;
  flex: none;
  padding: 0 18px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 15px;
  line-height: 44px;
  white-space: nowrap;
  box-sizing: border-box;
}

.demand-compare__choose {
  overflow: hidden;
  min-width: 0;
  height: 46px;
  flex: 1;
  padding: 0 16px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.demand-compare__choose--order {
  flex: none;
  padding: 0 22px;
}

.demand-compare__toast {
  position: fixed;
  z-index: 20;
  right: 16px;
  bottom: calc(92px + env(safe-area-inset-bottom));
  left: 16px;
  display: flex;
  min-height: 44px;
  padding: 4px 4px 4px 12px;
  align-items: center;
  gap: 6px;
  border: 1px solid #ffd8b8;
  border-radius: 10px;
  background: #fff0e3;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  box-sizing: border-box;
}

.demand-compare__toast-text {
  min-width: 0;
  flex: 1;
  color: #ba470b;
  font-size: 13px;
  line-height: 18px;
}

.demand-compare__toast-close {
  display: flex;
  width: 36px;
  height: 36px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.demand-compare__skeleton {
  height: 320px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.demand-compare__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.demand-compare__state-title {
  font-size: 16px;
  font-weight: 700;
}

.demand-compare__state-button {
  height: 40px;
  margin-top: 8px;
  padding: 0 20px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  line-height: 40px;
}
</style>
