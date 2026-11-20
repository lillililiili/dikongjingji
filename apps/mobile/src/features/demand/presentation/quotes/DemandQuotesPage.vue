<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'

import {
  DEMAND_SORT_LABELS,
  describeDemandPublisher,
  describeDemandQuote,
  formatDemandAmount,
  formatDemandInstant,
  resolveDemandFollowUp,
} from '../../application/demand-view'
import { demandApi } from '../../data/demand-api-client'
import {
  DEMAND_QUOTE_SORTS,
  type Demand,
  type DemandQuote,
  type DemandQuoteSort,
} from '../../domain/demand'
import DemandConfirmSheet from '../shared/DemandConfirmSheet.vue'
import DemandPageHeader from '../shared/DemandPageHeader.vue'
import DemandSummaryCard from '../shared/DemandSummaryCard.vue'
import { useDemandChoice } from '../shared/use-demand-choice'

const props = defineProps<{
  demandId: string | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  compare: [demandId: string, quoteId?: string]
  viewDemand: [demandId: string]
  republish: [demandId: string]
}>()

type PageState =
  | { kind: 'loading' }
  | { kind: 'ready'; demand: Demand; quotes: DemandQuote[]; demandVersion: number }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<PageState>({ kind: 'loading' })
const sort = ref<DemandQuoteSort>('ALL')
const notice = ref<string | null>(null)
let requestVersion = 0

const ready = computed(() => (state.value.kind === 'ready' ? state.value : null))
const demand = computed(() => ready.value?.demand ?? null)
const quotes = computed(() => ready.value?.quotes ?? [])
const cards = computed(() => quotes.value.map((quote) => ({ quote, view: describeDemandQuote(quote) })))
const chosenQuote = computed(() => quotes.value.find((quote) => quote.status === 'CHOSEN') ?? null)
// Like the board, one card stands out: the chosen one, or else the first that can be chosen.
const focusId = computed(() => chosenQuote.value?.quoteId
  ?? quotes.value.find((quote) => quote.choosable)?.quoteId
  ?? null)
const followUp = resolveDemandFollowUp()
const emptyCopy = computed(() => {
  const current = demand.value
  if (!current) return { title: '', text: '' }
  if (current.status === 'CLOSED') {
    return current.closeReason === 'NO_QUOTES'
      ? { title: '报价期内没有服务商报价', text: '可以改一改需求后重新发布' }
      : { title: '这份需求已取消', text: '需要时可以重新发布' }
  }
  return {
    title: '还没有服务商报价',
    text: current.quoteDeadline && current.quotingOpen
      ? `报价截止 ${formatDemandInstant(current.quoteDeadline)}，收到报价会通过“消息”通知你`
      : '报价已截止',
  }
})

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
      demandApi.listQuotes(token, demandId, sort.value),
    ])
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', demand: loadedDemand, quotes: list.items, demandVersion: list.demandVersion }
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

function selectSort(next: DemandQuoteSort): void {
  if (next === sort.value) return
  sort.value = next
  void load()
}

const choice = useDemandChoice({
  getAccessToken: () => props.accessToken,
  getDemandVersion: () => ready.value?.demandVersion ?? null,
  getDemandId: () => props.demandId,
  onLogin: () => emit('login'),
  reload: load,
  notify: (message) => { notice.value = message },
})
const { asking: choosing, busy: choosingBusy } = choice

watch(() => [props.refreshKey, props.accessToken, props.demandId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="demand-quotes" data-testid="demand-quotes" :data-status="demand?.status">
    <view class="demand-quotes__top">
      <DemandPageHeader
        title="报价比选"
        :badge="demand ? describeDemandPublisher(demand.publisher) : null"
        @back="emit('back')"
      />
    </view>

    <view v-if="state.kind === 'loading'" class="demand-quotes__body" aria-label="正在加载">
      <view class="demand-quotes__skeleton" />
      <view class="demand-quotes__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing' || state.kind === 'error'" class="demand-quotes__state" data-testid="demand-quotes-error">
      <text class="demand-quotes__state-title">{{ state.kind === 'missing' ? '没有找到这份需求' : '加载失败' }}</text>
      <text class="demand-quotes__state-text">{{ state.kind === 'missing' ? '它可能已被删除' : '请检查网络后重试' }}</text>
      <button v-if="state.kind === 'error'" class="demand-quotes__state-button" @click="load">重试</button>
    </view>

    <template v-else-if="demand">
      <view class="demand-quotes__body">
        <DemandSummaryCard :demand="demand" :quote-count="quotes.length" />

        <view v-if="quotes.length" class="demand-quotes__tools">
          <view class="demand-quotes__sorts" role="group" aria-label="排序">
            <button
              v-for="code in DEMAND_QUOTE_SORTS"
              :key="code"
              class="demand-quotes__sort"
              :data-testid="`demand-sort-${code.toLowerCase()}`"
              :aria-pressed="code === sort"
              @click="selectSort(code)"
            >
              <text :class="['demand-quotes__sort-pill', { 'demand-quotes__sort-pill--on': code === sort }]">
                {{ DEMAND_SORT_LABELS[code] }}
              </text>
            </button>
          </view>
          <button class="demand-quotes__compare" data-testid="demand-compare" @click="emit('compare', demand.demandId)">
            对比明细 ›
          </button>
        </view>

        <view
          v-for="card in cards"
          :key="card.quote.quoteId"
          :class="['demand-quotes__card', { 'demand-quotes__card--focus': card.quote.quoteId === focusId }]"
          :data-testid="`demand-quote-${card.quote.quoteId}`"
          :data-status="card.quote.status"
        >
          <view class="demand-quotes__card-head">
            <text class="demand-quotes__name">{{ card.quote.provider.name }}</text>
            <text v-if="card.quote.provider.certificationLabel" class="demand-quotes__cert">
              {{ card.quote.provider.certificationLabel }}
            </text>
            <view class="demand-quotes__price">
              <text class="demand-quotes__price-label">总价</text>
              <text class="demand-quotes__price-value" data-testid="demand-quote-price">{{ card.view.price }}</text>
            </view>
          </view>
          <view class="demand-quotes__rows">
            <template v-for="row in card.view.rows" :key="row.label">
              <text class="demand-quotes__row-label">{{ row.label }}</text>
              <text class="demand-quotes__row-value">{{ row.value }}</text>
            </template>
          </view>
          <view class="demand-quotes__card-actions">
            <text
              v-if="card.view.statusTag"
              :class="['demand-quotes__tag', `demand-quotes__tag--${card.quote.status.toLowerCase()}`]"
              data-testid="demand-quote-tag"
            >
              {{ card.view.statusTag }}
            </text>
            <button
              class="demand-quotes__detail"
              :data-testid="`demand-quote-detail-${card.quote.quoteId}`"
              @click="emit('compare', demand.demandId, card.quote.quoteId)"
            >
              查看方案 v{{ card.quote.version }}
            </button>
            <button
              v-if="card.quote.choosable"
              :class="['demand-quotes__choose', { 'demand-quotes__choose--soft': card.quote.quoteId !== focusId }]"
              :data-testid="`demand-quote-choose-${card.quote.quoteId}`"
              @click="choice.ask(card.quote)"
            >
              选择此报价
            </button>
          </view>
        </view>

        <view v-if="quotes.length === 0" class="demand-quotes__empty" data-testid="demand-quotes-empty">
          <text class="demand-quotes__empty-title">{{ emptyCopy.title }}</text>
          <text class="demand-quotes__empty-text">{{ emptyCopy.text }}</text>
          <view class="demand-quotes__empty-actions">
            <button class="demand-quotes__secondary" data-testid="demand-quotes-view" @click="emit('viewDemand', demand.demandId)">
              查看需求
            </button>
            <button
              v-if="demand.status === 'CLOSED'"
              class="demand-quotes__secondary"
              data-testid="demand-quotes-republish"
              @click="emit('republish', demand.demandId)"
            >
              重新发布
            </button>
          </view>
        </view>

        <text class="demand-quotes__foot">
          选定一家后需求变为“已选定”，其余报价为“未选中”。选定报价不产生费用；本平台不承担飞行审批与空中调度。
        </text>
      </view>

      <view v-if="demand.status === 'CHOSEN'" class="demand-quotes__bar" data-testid="demand-chosen-bar">
        <view class="demand-quotes__bar-line">
          <text class="demand-quotes__bar-label">已选</text>
          <text class="demand-quotes__bar-name" data-testid="demand-chosen-name">
            {{ demand.chosen?.providerName ?? chosenQuote?.provider.name }} v{{ demand.chosen?.quoteVersion ?? chosenQuote?.version }}
          </text>
          <text v-if="demand.chosen" class="demand-quotes__bar-amount">{{ formatDemandAmount(demand.chosen.amountMinor) }}</text>
        </view>
        <view class="demand-quotes__gate" data-testid="demand-follow-up">
          <UniIcons type="info" size="14" color="#ba470b" />
          <view class="demand-quotes__gate-copy">
            <text class="demand-quotes__gate-title">{{ followUp.title }}</text>
            <text class="demand-quotes__gate-text">{{ followUp.text }}</text>
          </view>
        </view>
      </view>
    </template>

    <view v-if="notice" class="demand-quotes__toast" role="status" data-testid="demand-quotes-notice">
      <text class="demand-quotes__toast-text">{{ notice }}</text>
      <button class="demand-quotes__toast-close" aria-label="关闭提示" @click="notice = null">
        <UniIcons type="closeempty" size="14" color="#738096" />
      </button>
    </view>

    <DemandConfirmSheet
      v-if="choosing"
      :title="`选择 ${choosing.provider.name} 的报价？`"
      :text="`总价 ${formatDemandAmount(choosing.amountMinor)}（方案 v${choosing.version}）。选定后不能再选别家，其余报价会标为未选中。选定不产生费用，方案确认和付款还没有开通。`"
      keep-label="再想想"
      go-label="确认选择"
      :busy="choosingBusy"
      @keep="choice.keep"
      @go="choice.confirm"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Quote (390 wide). */
.demand-quotes {
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

.demand-quotes__top {
  position: sticky;
  top: 0;
  z-index: 5;
}

.demand-quotes__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 24px;
}

.demand-quotes__tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.demand-quotes__sorts {
  display: flex;
  gap: 8px;
}

.demand-quotes__sort {
  flex: none;
  padding: 6px 0;
  margin: -6px 0;
}

.demand-quotes__sort-pill {
  display: flex;
  height: 32px;
  padding: 0 12px;
  align-items: center;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 13px;
  line-height: 32px;
  white-space: nowrap;
}

.demand-quotes__sort-pill--on {
  color: #ffffff;
  background: #202c42;
}

.demand-quotes__compare {
  height: 44px;
  flex: none;
  margin: -6px 0 -6px auto;
  color: #627187;
  font-size: 13px;
  line-height: 44px;
  white-space: nowrap;
}

.demand-quotes__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1.5px solid #ffffff;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.demand-quotes__card--focus {
  border-color: #ff6a00;
}

.demand-quotes__card-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.demand-quotes__name {
  overflow: hidden;
  min-width: 0;
  font-size: 15px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.demand-quotes__cert {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 11px;
  white-space: nowrap;
}

.demand-quotes__price {
  display: flex;
  flex: none;
  margin-left: auto;
  align-items: baseline;
  gap: 4px;
  white-space: nowrap;
}

.demand-quotes__price-label {
  color: #738096;
  font-size: 12px;
}

.demand-quotes__price-value {
  color: #e85400;
  font-size: 18px;
  font-weight: 700;
}

.demand-quotes__rows {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 4px 8px;
  font-size: 13px;
  line-height: 19px;
}

.demand-quotes__row-label {
  color: #627187;
  white-space: nowrap;
}

.demand-quotes__row-value {
  min-width: 0;
  word-break: break-all;
}

.demand-quotes__card-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.demand-quotes__tag {
  margin-right: auto;
  font-size: 13px;
  font-weight: 500;
}

.demand-quotes__tag--chosen { color: #11804a; }
.demand-quotes__tag--not_chosen,
.demand-quotes__tag--expired,
.demand-quotes__tag--closed { color: #738096; }

.demand-quotes__detail {
  min-height: 36px;
  padding: 0 14px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.demand-quotes__choose {
  min-width: 93px;
  min-height: 36px;
  padding: 0 14px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 13px;
  font-weight: 500;
  line-height: 36px;
  white-space: nowrap;
}

.demand-quotes__choose--soft {
  color: #ba470b;
  background: #fff0e3;
  box-shadow: none;
}

.demand-quotes__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 36px 16px;
  border-radius: 14px;
  background: #ffffff;
  text-align: center;
}

.demand-quotes__empty-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.demand-quotes__empty-text {
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.demand-quotes__empty-actions {
  display: flex;
  margin-top: 6px;
  gap: 10px;
}

.demand-quotes__secondary {
  height: 36px;
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

.demand-quotes__foot {
  color: #627187;
  font-size: 12px;
  line-height: 1.6;
}

.demand-quotes__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 16px calc(14px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.demand-quotes__bar-line {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}

.demand-quotes__bar-label {
  flex: none;
  color: #627187;
  font-size: 13px;
}

.demand-quotes__bar-name {
  overflow: hidden;
  min-width: 0;
  font-size: 15px;
  font-weight: 500;
  text-overflow: ellipsis;
}

.demand-quotes__bar-amount {
  flex: none;
  margin-left: auto;
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
}

.demand-quotes__gate {
  display: flex;
  padding: 8px 10px;
  align-items: flex-start;
  gap: 6px;
  border-radius: 10px;
  background: #fff0e3;
}

.demand-quotes__gate-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.demand-quotes__gate-title {
  color: #ba470b;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
}

.demand-quotes__gate-text {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.demand-quotes__toast {
  position: fixed;
  z-index: 20;
  right: 16px;
  bottom: calc(132px + env(safe-area-inset-bottom));
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

.demand-quotes__toast-text {
  min-width: 0;
  flex: 1;
  color: #ba470b;
  font-size: 13px;
  line-height: 18px;
}

.demand-quotes__toast-close {
  display: flex;
  width: 36px;
  height: 36px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.demand-quotes__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.demand-quotes__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.demand-quotes__state-title {
  font-size: 16px;
  font-weight: 700;
}

.demand-quotes__state-text {
  color: #738096;
  font-size: 13px;
}

.demand-quotes__state-button {
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
