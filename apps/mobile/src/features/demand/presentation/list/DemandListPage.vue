<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import { SERVICE_ORDER_PREVIEW_ENABLED } from '@/features/serviceorder/application/serviceorder-preview'
import { ApiRequestError } from '@/shared/http/public-api-request'
import LineIcon from '@/shared/ui/LineIcon.vue'
import {
  loadMoreMerged,
  mergedHasMore,
  mergedItems,
  type MergedSource,
} from '@/shared/list/merged-pages'

import {
  describeMallInquiryCard,
  type MallInquiryCardAction,
} from '../../../mall/application/inquiry-card'
import { mallInquiryCloseConfirm } from '../../../mall/application/inquiry-view'
import { mallInquiryApi } from '../../../mall/data/inquiry-api-client'
import type { MallInquiry } from '../../../mall/domain/inquiry'
import type { MallInquiryTarget } from '../../../mall/navigation/mall-routes'
import {
  describeRentalInquiryCard,
  rentalCloseConfirm,
  type RentalInquiryCardAction,
} from '../../../rental/application/rental-inquiry-card'
import { rentalApi } from '../../../rental/data/rental-api-client'
import type { RentalInquiry } from '../../../rental/domain/rental'
import type { RentalInquiryTarget } from '../../../rental/navigation/rental-routes'
import {
  DEMAND_LIST_KEYS,
  demandListItem,
  demandListSources,
  demandTabCounts,
  type DemandKindTab,
  mallInquiryListItem,
  rentalListItem,
  type DemandListItem,
} from '../../application/demand-list-sources'
import {
  DEMAND_FILTER_LABELS,
  describeDemandCard,
  type DemandCardAction,
} from '../../application/demand-view'
import { demandApi } from '../../data/demand-api-client'
import { DEMAND_PAGE_SIZE, type Demand } from '../../domain/demand'
import {
  DEMAND_LIST_FILTERS,
  type DemandListFilter,
} from '../../navigation/demand-routes'
import DemandConfirmSheet from '../shared/DemandConfirmSheet.vue'
import DemandPageHeader from '../shared/DemandPageHeader.vue'

const props = defineProps<{
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
  // Counts up when the page is scrolled to its end.
  reachBottomKey: number
  initialFilter: DemandListFilter
}>()

const emit = defineEmits<{
  back: []
  login: []
  publish: []
  edit: [demandId: string]
  view: [demandId: string]
  quotes: [demandId: string]
  republish: [demandId: string]
  // Rental inquiries (询价单, F3): the quote page, the rental order, a new inquiry for the same offer.
  rentalQuote: [inquiryId: string]
  rentalOrder: [rentalOrderId: string]
  reinquire: [target: RentalInquiryTarget]
  // Goods inquiries (询价单, F4): the quotes page, the goods order made from the chosen quote, a new
  // inquiry for the same product.
  mallQuotes: [inquiryId: string]
  goodsOrder: [orderId: string]
  mallReinquire: [target: MallInquiryTarget]
  // The service order a chosen demand made (S4-3).
  serviceOrder: [orderId: string]
}>()

type ListState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error' }

// The DemandList board's underline tabs: which kind of record. 草稿 asks the server for drafts; the
// other three narrow what the status chip loaded (inquiries have no drafts).
type KindTab = DemandKindTab
const KIND_TABS: ReadonlyArray<{ code: KindTab; label: string }> = [
  { code: 'ALL', label: '全部' },
  { code: 'DEMAND', label: '需求单' },
  { code: 'INQUIRY', label: '询价单' },
  { code: 'DRAFT', label: '草稿' },
]
const STATUS_FILTERS = DEMAND_LIST_FILTERS.filter((code) => code !== 'DRAFT')
type Asking =
  | { action: 'CANCEL' | 'DELETE'; demand: Demand }
  | { action: 'CLOSE_RENTAL'; inquiry: RentalInquiry }
  | { action: 'CLOSE_MALL'; inquiry: MallInquiry }

const filter = ref<DemandListFilter>(props.initialFilter)
const kindTab = ref<KindTab>(props.initialFilter === 'DRAFT' ? 'DRAFT' : 'ALL')
const state = ref<ListState>({ kind: 'loading' })
const sources = ref<MergedSource<DemandListItem>[]>([])
const loadingMore = ref(false)
const asking = ref<Asking | null>(null)
const acting = ref(false)
// The numbers after the tab names; null until all three totals are read (or when one failed).
const tabCounts = ref<Record<KindTab, number> | null>(null)
let requestVersion = 0
let countsVersion = 0

const hasMore = computed(() => mergedHasMore(sources.value))
function inKindTab(item: DemandListItem): boolean {
  if (kindTab.value === 'DEMAND') return item.kind === 'DEMAND' && item.demand.status !== 'DRAFT'
  if (kindTab.value === 'INQUIRY') return item.kind !== 'DEMAND'
  return true
}

const cards = computed(() => mergedItems(sources.value, DEMAND_LIST_KEYS).filter(inKindTab).map((item) => {
  switch (item.kind) {
    case 'DEMAND':
      return {
        kind: 'DEMAND' as const,
        id: item.id,
        demand: item.demand,
        view: describeDemandCard(item.demand, SERVICE_ORDER_PREVIEW_ENABLED),
      }
    case 'RENTAL':
      return {
        kind: 'RENTAL' as const,
        id: item.id,
        inquiry: item.inquiry,
        view: describeRentalInquiryCard(item.inquiry),
        cover: item.inquiry.item.coverUrl,
      }
    case 'MALL':
      return {
        kind: 'MALL' as const,
        id: item.id,
        inquiry: item.inquiry,
        view: describeMallInquiryCard(item.inquiry),
        cover: (item.inquiry.product.coverImage?.url ?? null),
      }
  }
}))
const emptyText = computed(() => (filter.value === 'DRAFT' ? '还没有草稿' : '没有符合条件的记录'))
const confirmCopy = computed(() => {
  const current = asking.value
  if (current?.action === 'CLOSE_RENTAL') return rentalCloseConfirm(current.inquiry)
  if (current?.action === 'CLOSE_MALL') return mallInquiryCloseConfirm(current.inquiry)
  return current?.action === 'DELETE'
    ? { title: '确认删除这份草稿？', text: '删除后不能恢复，需要时请重新填写。', keep: '暂不删除', go: '确认删除' }
    : { title: '确认取消这份需求？', text: '取消后服务商不再报价，已收到的报价也会失效。', keep: '暂不取消', go: '确认取消' }
})

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

// One page of the index-th source of the current filter, as list items.
async function fetchPage(token: string, index: number, page: number): Promise<{ items: DemandListItem[]; hasMore: boolean }> {
  const source = demandListSources(filter.value)[index]
  if (source.kind === 'DEMAND') {
    const loaded = await demandApi.list(token, source.status, page, DEMAND_PAGE_SIZE)
    return { items: loaded.items.map(demandListItem), hasMore: loaded.hasMore }
  }
  if (source.kind === 'RENTAL') {
    const loaded = await rentalApi.listInquiries(token, source.status, page, DEMAND_PAGE_SIZE)
    return { items: loaded.items.map(rentalListItem), hasMore: loaded.hasMore }
  }
  const loaded = await mallInquiryApi.list(token, source.status, page, DEMAND_PAGE_SIZE)
  return { items: loaded.items.map(mallInquiryListItem), hasMore: loaded.hasMore }
}

// 全部 / 需求单 / 询价单 / 草稿 counts: the demand counts and the two inquiry lists' totals, read
// alongside the list; a failure only leaves the numbers out.
async function loadCounts(token: string): Promise<void> {
  const requested = ++countsVersion
  const total = (read: Promise<{ total: number }>) => read.then((page) => page.total).catch(() => null)
  const [demands, rental, mall] = await Promise.all([
    demandApi.counts(token).catch(() => null),
    total(rentalApi.listInquiries(token, null, 1, 1)),
    total(mallInquiryApi.list(token, null, 1, 1)),
  ])
  if (requested === countsVersion) tabCounts.value = demandTabCounts(demands, rental, mall)
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  void loadCounts(token)
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const pages = await Promise.all(demandListSources(filter.value).map((_, index) => fetchPage(token, index, 1)))
    if (requested !== requestVersion) return
    sources.value = pages.map((page) => ({ items: page.items, hasMore: page.hasMore, page: 1 }))
    state.value = { kind: 'ready' }
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    state.value = { kind: 'error' }
  }
}

async function loadMore(): Promise<void> {
  const token = props.accessToken
  if (!token || state.value.kind !== 'ready' || !hasMore.value || loadingMore.value) return
  const requested = requestVersion
  loadingMore.value = true
  try {
    // A record that moved up while scrolling is shown once (the merge drops repeated ids).
    const next = await loadMoreMerged(sources.value, DEMAND_LIST_KEYS, (index, page) => fetchPage(token, index, page))
    if (requested === requestVersion) sources.value = next
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({ title: '没有加载出来，请再试一次', icon: 'none' })
  } finally {
    loadingMore.value = false
  }
}

function selectFilter(next: DemandListFilter): void {
  if (next === filter.value) return
  filter.value = next
  state.value = { kind: 'loading' }
  void load()
}

// A tab starts again from 全部 (as on the board); only 草稿 changes what is read.
function selectKindTab(next: KindTab): void {
  if (next === kindTab.value) return
  kindTab.value = next
  const nextFilter: DemandListFilter = next === 'DRAFT' ? 'DRAFT' : 'ALL'
  if (nextFilter !== filter.value) selectFilter(nextFilter)
}

function act(action: DemandCardAction, demand: Demand): void {
  switch (action) {
    case 'EDIT': emit('edit', demand.demandId); return
    case 'VIEW': emit('view', demand.demandId); return
    case 'QUOTES': emit('quotes', demand.demandId); return
    case 'REPUBLISH': emit('republish', demand.demandId); return
    case 'ORDER':
      if (demand.serviceOrderId) emit('serviceOrder', demand.serviceOrderId)
      return
    case 'CANCEL':
    case 'DELETE':
      asking.value = { action, demand }
  }
}

function actOnRental(action: RentalInquiryCardAction, inquiry: RentalInquiry): void {
  switch (action) {
    case 'QUOTE': emit('rentalQuote', inquiry.inquiryId); return
    case 'ORDER':
      if (inquiry.order) emit('rentalOrder', inquiry.order.rentalOrderId)
      return
    case 'REINQUIRE':
      emit('reinquire', { offerId: inquiry.item.offerId, productId: inquiry.item.productId, cityCode: inquiry.place.cityCode })
      return
    case 'CLOSE':
      asking.value = { action: 'CLOSE_RENTAL', inquiry }
  }
}

function actOnMall(action: MallInquiryCardAction, inquiry: MallInquiry): void {
  switch (action) {
    case 'QUOTES': emit('mallQuotes', inquiry.inquiryId); return
    case 'ORDER':
      if (inquiry.chosen?.orderId) emit('goodsOrder', inquiry.chosen.orderId)
      return
    case 'REINQUIRE':
      emit('mallReinquire', { productId: inquiry.product.productId, cityCode: inquiry.cityCode })
      return
    case 'CLOSE':
      asking.value = { action: 'CLOSE_MALL', inquiry }
  }
}

async function runAsked(token: string, target: Asking): Promise<void> {
  if (target.action === 'CLOSE_MALL') {
    await mallInquiryApi.close(token, target.inquiry.inquiryId)
    uni.showToast({ title: '询价已关闭', icon: 'none' })
  } else if (target.action === 'CLOSE_RENTAL') {
    await rentalApi.closeInquiry(token, target.inquiry.inquiryId)
    uni.showToast({ title: '询价已关闭', icon: 'none' })
  } else if (target.action === 'DELETE') {
    await demandApi.deleteDraft(token, target.demand.demandId)
    uni.showToast({ title: '草稿已删除', icon: 'none' })
  } else {
    await demandApi.cancel(token, target.demand.demandId)
    uni.showToast({ title: '需求已取消', icon: 'none' })
  }
}

function refusedText(target: Asking): string {
  if (target.action === 'CLOSE_RENTAL') return '询价单已确认或已关闭，不能再关闭'
  if (target.action === 'CLOSE_MALL') return '询价单已选定或已关闭，不能再关闭'
  return target.action === 'DELETE' ? '只有草稿能删除' : '需求已选定或已关闭，不能取消'
}

async function confirmAction(): Promise<void> {
  const token = props.accessToken
  const target = asking.value
  if (!token || !target || acting.value) return
  acting.value = true
  try {
    await runAsked(token, target)
  } catch (error) {
    if (isUnauthorized(error)) {
      asking.value = null
      emit('login')
      return
    }
    // The record moved on meanwhile (chosen, submitted, closed): the list below shows where it is.
    const refused = error instanceof ApiRequestError
      && ['DEMAND_STATE_INVALID', 'RENTAL_STATE_INVALID', 'MALL_INQUIRY_STATE_INVALID'].includes(error.code ?? '')
    uni.showToast({ title: refused ? refusedText(target) : '没有成功，请稍后重试', icon: 'none' })
  } finally {
    acting.value = false
    asking.value = null
  }
  await load()
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
watch(() => props.reachBottomKey, () => { void loadMore() })
</script>

<template>
  <view class="demand-list" data-testid="demand-list">
    <view class="demand-list__top">
      <DemandPageHeader title="需求与报价" @back="emit('back')">
        <button class="demand-list__publish" data-testid="demand-list-publish" @click="emit('publish')">
          <text class="demand-list__publish-pill">发布需求</text>
        </button>
      </DemandPageHeader>
      <view class="demand-list__tabs" role="tablist" aria-label="单据类型">
        <button
          v-for="item in KIND_TABS"
          :key="item.code"
          :class="['demand-list__tab', { 'demand-list__tab--on': kindTab === item.code }]"
          :data-testid="item.code === 'DRAFT' ? 'demand-filter-draft' : `demand-tab-${item.code.toLowerCase()}`"
          role="tab"
          :aria-selected="kindTab === item.code"
          :aria-pressed="kindTab === item.code"
          @click="selectKindTab(item.code)"
        >
          {{ item.label }}<text v-if="tabCounts" class="demand-list__tab-count" :data-testid="`demand-tab-count-${item.code.toLowerCase()}`">{{ tabCounts[item.code] }}</text>
        </button>
      </view>
      <scroll-view v-if="kindTab !== 'DRAFT'" class="demand-list__filters" scroll-x :show-scrollbar="false">
        <view class="demand-list__filter-row" role="group" aria-label="按状态筛选">
          <button
            v-for="code in STATUS_FILTERS"
            :key="code"
            class="demand-list__filter"
            :data-testid="`demand-filter-${code.toLowerCase()}`"
            :aria-pressed="code === filter"
            @click="selectFilter(code)"
          >
            <text :class="['demand-list__filter-pill', { 'demand-list__filter-pill--on': code === filter }]">
              {{ DEMAND_FILTER_LABELS[code] }}
            </text>
          </button>
        </view>
      </scroll-view>
    </view>

    <view v-if="state.kind === 'loading'" class="demand-list__body" aria-label="正在加载">
      <view class="demand-list__skeleton" />
      <view class="demand-list__skeleton" />
    </view>

    <view v-else-if="state.kind === 'error'" class="demand-list__state" data-testid="demand-list-error">
      <text class="demand-list__state-title">加载失败</text>
      <text class="demand-list__state-text">请检查网络后重试</text>
      <button class="demand-list__state-button" data-testid="demand-list-retry" @click="load">重试</button>
    </view>

    <view v-else-if="cards.length === 0" class="demand-list__state" data-testid="demand-list-empty">
      <view class="demand-list__empty-icon">
        <LineIcon name="doc" :size="28" color="#91a0b4" :stroke-width="1.6" />
      </view>
      <text class="demand-list__state-text">{{ emptyText }}</text>
      <button class="demand-list__state-button" data-testid="demand-list-empty-publish" @click="emit('publish')">
        发布需求
      </button>
    </view>

    <view v-else class="demand-list__body">
      <template v-for="card in cards" :key="card.id">
        <view
          v-if="card.kind === 'DEMAND'"
          class="demand-list__card"
          :data-testid="`demand-card-${card.demand.demandId}`"
          :data-status="card.demand.status"
        >
          <button
            class="demand-list__main"
            :data-testid="`demand-card-open-${card.demand.demandId}`"
            @click="act(card.view.open, card.demand)"
          >
            <view class="demand-list__head">
              <text :class="['demand-list__kind', { 'demand-list__kind--draft': card.view.kind === '草稿' }]">
                {{ card.view.kind }}
              </text>
              <text class="demand-list__publisher">{{ card.view.publisher }}</text>
              <text
                :class="['demand-list__status', `demand-list__status--${card.view.tone.toLowerCase()}`]"
                data-testid="demand-card-status"
              >
                {{ card.view.statusText }}
              </text>
            </view>
            <view class="demand-list__content">
              <view class="demand-list__image">
                <LineIcon name="doc" :size="26" color="#91a0b4" :stroke-width="1.6" />
              </view>
              <view class="demand-list__copy">
                <text class="demand-list__title">{{ card.view.title }}</text>
                <text v-if="card.view.meta" class="demand-list__meta" data-testid="demand-card-meta">{{ card.view.meta }}</text>
                <text
                  :class="['demand-list__line', `demand-list__line--${card.view.tone.toLowerCase()}`]"
                  data-testid="demand-card-line"
                >
                  {{ card.view.line }}
                </text>
              </view>
            </view>
          </button>
          <view class="demand-list__actions">
            <button
              v-for="button in card.view.buttons"
              :key="button.action"
              :class="['demand-list__action', { 'demand-list__action--primary': button.primary }]"
              :data-testid="`demand-card-${button.action.toLowerCase()}-${card.demand.demandId}`"
              @click="act(button.action, card.demand)"
            >
              {{ button.label }}
            </button>
          </view>
        </view>

        <view
          v-else-if="card.kind === 'MALL'"
          class="demand-list__card"
          :data-testid="`mall-inquiry-card-${card.inquiry.inquiryId}`"
          :data-status="card.inquiry.status"
        >
          <button
            class="demand-list__main"
            :data-testid="`mall-inquiry-card-open-${card.inquiry.inquiryId}`"
            @click="actOnMall('QUOTES', card.inquiry)"
          >
            <view class="demand-list__head">
              <text class="demand-list__kind demand-list__kind--inquiry">{{ card.view.kind }}</text>
              <text class="demand-list__publisher">{{ card.view.publisher }}</text>
              <text
                :class="['demand-list__status', `demand-list__status--${card.view.tone.toLowerCase()}`]"
                data-testid="mall-inquiry-card-status"
              >
                {{ card.view.statusText }}
              </text>
            </view>
            <view class="demand-list__content">
              <image v-if="card.cover" class="demand-list__image" :src="card.cover" mode="aspectFill" />
              <view v-else class="demand-list__image">
                <UniIcons type="shop" size="26" color="#91a0b4" />
              </view>
              <view class="demand-list__copy">
                <text class="demand-list__title">{{ card.view.title }}</text>
                <text class="demand-list__meta" data-testid="mall-inquiry-card-meta">{{ card.view.meta }}</text>
                <text
                  :class="['demand-list__line', `demand-list__line--${card.view.tone.toLowerCase()}`]"
                  data-testid="mall-inquiry-card-line"
                >
                  {{ card.view.line }}
                </text>
              </view>
            </view>
          </button>
          <view class="demand-list__actions">
            <button
              v-for="button in card.view.buttons"
              :key="button.action"
              :class="['demand-list__action', { 'demand-list__action--primary': button.primary }]"
              :data-testid="`mall-inquiry-card-${button.action.toLowerCase()}-${card.inquiry.inquiryId}`"
              @click="actOnMall(button.action, card.inquiry)"
            >
              {{ button.label }}
            </button>
          </view>
        </view>

        <view
          v-else
          class="demand-list__card"
          :data-testid="`rental-inquiry-card-${card.inquiry.inquiryId}`"
          :data-status="card.inquiry.status"
        >
          <button
            class="demand-list__main"
            :data-testid="`rental-inquiry-card-open-${card.inquiry.inquiryId}`"
            @click="actOnRental('QUOTE', card.inquiry)"
          >
            <view class="demand-list__head">
              <text class="demand-list__kind demand-list__kind--inquiry">{{ card.view.kind }}</text>
              <text class="demand-list__publisher">{{ card.view.publisher }}</text>
              <text
                :class="['demand-list__status', `demand-list__status--${card.view.tone.toLowerCase()}`]"
                data-testid="rental-inquiry-card-status"
              >
                {{ card.view.statusText }}
              </text>
            </view>
            <view class="demand-list__content">
              <image v-if="card.cover" class="demand-list__image" :src="card.cover" mode="aspectFill" />
              <view v-else class="demand-list__image">
                <UniIcons type="gear" size="26" color="#91a0b4" />
              </view>
              <view class="demand-list__copy">
                <text class="demand-list__title">{{ card.view.title }}</text>
                <text class="demand-list__meta" data-testid="rental-inquiry-card-meta">{{ card.view.meta }}</text>
                <text
                  :class="['demand-list__line', `demand-list__line--${card.view.tone.toLowerCase()}`]"
                  data-testid="rental-inquiry-card-line"
                >
                  {{ card.view.line }}
                </text>
              </view>
            </view>
          </button>
          <view class="demand-list__actions">
            <button
              v-for="button in card.view.buttons"
              :key="button.action"
              :class="['demand-list__action', { 'demand-list__action--primary': button.primary }]"
              :data-testid="`rental-inquiry-card-${button.action.toLowerCase()}-${card.inquiry.inquiryId}`"
              @click="actOnRental(button.action, card.inquiry)"
            >
              {{ button.label }}
            </button>
          </view>
        </view>
      </template>

      <button
        v-if="hasMore"
        class="demand-list__more"
        data-testid="demand-list-more"
        :disabled="loadingMore"
        @click="loadMore"
      >
        {{ loadingMore ? '正在加载…' : '加载更多' }}
      </button>
      <text v-else class="demand-list__end">没有更多了</text>
    </view>

    <DemandConfirmSheet
      v-if="asking"
      :title="confirmCopy.title"
      :text="confirmCopy.text"
      :keep-label="confirmCopy.keep"
      :go-label="confirmCopy.go"
      :busy="acting"
      @keep="!acting && (asking = null)"
      @go="confirmAction"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board DemandList (390 wide). */
.demand-list {
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

.demand-list__top {
  position: sticky;
  top: 0;
  z-index: 5;
  background: #ffffff;
}

.demand-list__publish {
  display: flex;
  height: 44px;
  flex: none;
  margin: 0 8px 0 auto;
  align-items: center;
}

.demand-list__publish-pill {
  height: 32px;
  padding: 0 12px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 13px;
  line-height: 30px;
  white-space: nowrap;
  box-sizing: border-box;
}

.demand-list__tabs {
  display: flex;
  padding: 0 16px;
  gap: 22px;
  border-bottom: 1px solid #f0f1f3;
  background: #ffffff;
}

.demand-list__tab {
  min-height: 44px;
  padding: 10px 0 7px;
  border-bottom: 3px solid transparent;
  border-radius: 0;
  color: #627187;
  font-size: 15px;
  line-height: 21px;
  white-space: nowrap;
}

.demand-list__tab--on {
  border-bottom-color: #ff6a00;
  color: #202c42;
  font-weight: 700;
}

.demand-list__filters {
  width: 100%;
  background: #f5f7fc;
  white-space: nowrap;
}

.demand-list__filter-row {
  display: inline-flex;
  padding: 6px 16px 0;
  gap: 6px;
}

.demand-list__filter {
  flex: none;
  padding: 6px 0;
}

.demand-list__filter-pill {
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

.demand-list__filter-pill--on {
  color: #ffffff;
  background: #202c42;
}

.demand-list__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 24px;
}

.demand-list__card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.demand-list__main {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.demand-list__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.demand-list__kind {
  flex: none;
  padding: 0 6px;
  border-radius: 6px;
  color: #1d63c4;
  background: #e6f0ff;
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;
}

.demand-list__kind--draft {
  color: #536178;
  background: #eef2f8;
}

.demand-list__kind--inquiry {
  color: #ba470b;
  background: #fff0e3;
}

.demand-list__publisher {
  overflow: hidden;
  min-width: 0;
  color: #738096;
  font-size: 13px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.demand-list__status {
  flex: none;
  margin-left: auto;
  font-size: 14px;
  font-weight: 500;
  line-height: 21px;
  white-space: nowrap;
}

.demand-list__status--pending { color: #ba470b; }
.demand-list__status--quoting { color: #1d63c4; }
.demand-list__status--chosen { color: #11804a; }
.demand-list__status--off { color: #738096; }
.demand-list__status--draft { color: #627187; }

.demand-list__content {
  display: flex;
  gap: 12px;
}

.demand-list__image {
  display: flex;
  width: 72px;
  height: 72px;
  flex: none;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 10px;
  background: #eef2f8;
}

.demand-list__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.demand-list__title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.demand-list__meta {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.demand-list__line {
  font-size: 13px;
  line-height: 18px;
}

.demand-list__line--pending { color: #536178; }
.demand-list__line--quoting { color: #ba470b; }
.demand-list__line--chosen { color: #11804a; }
.demand-list__line--off,
.demand-list__line--draft { color: #738096; }

.demand-list__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.demand-list__action {
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

.demand-list__action--primary {
  padding: 0 18px;
  border-color: #ff6a00;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-weight: 500;
}

.demand-list__more {
  height: 44px;
  color: #536178;
  font-size: 13px;
  text-align: center;
}

.demand-list__end {
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.demand-list__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.demand-list__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 32px;
}

.demand-list__empty-icon {
  display: flex;
  width: 64px;
  height: 64px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef2f8;
}

.demand-list__state-title {
  font-size: 16px;
  font-weight: 700;
}

.demand-list__state-text {
  color: #627187;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.demand-list__state-button {
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

/* DemandList board: "全部 6", the number after a space in the tab's own type. */
.demand-list__tab-count {
  margin-left: 4px;
}
</style>
