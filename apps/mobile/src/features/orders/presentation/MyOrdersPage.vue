<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { mergedHasMore, mergedItems, type MergedSource } from '@/shared/list/merged-pages'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { ACTIVITY_PREVIEW_ENABLED } from '../../activity/application/activity-preview'
import { enrollmentApi } from '../../activity/data/enrollment-api-client'
import { EXPERIENCE_PREVIEW_ENABLED } from '../../experience/application/experience-preview'
import { venueBookingApi } from '../../experience/data/venue-booking-api-client'
import { resolveInspectionOrderDestination } from '../../inspection/application/service-flow/inspection-order-navigation'
import { inspectionOrderApi } from '../../inspection/data/service-flow/uni-booking-http-client'
import type { InspectionOrderSummary } from '../../inspection/domain/service-flow/order-center'
import { buildInspectionFulfillmentUrl } from '../../inspection/navigation/inspection-routes'
import InspectionOrderCard from '../../inspection/presentation/orders/components/InspectionOrderCard.vue'
import { rentalCoverUrl } from '../../rental/application/rental-cover'
import { RENTAL_PREVIEW_ENABLED } from '../../rental/application/rental-preview'
import { rentalApi } from '../../rental/data/rental-api-client'
import {
  TRADE_COMMAND_CONFIRMS,
  classifyOrderCommandFailure,
} from '../../trade/application/order-commands'
import { createTradeKey } from '../../trade/application/trade-keys'
import { TRADE_PREVIEW_ENABLED } from '../../trade/application/trade-preview'
import { tradeApi } from '../../trade/data/trade-api-client'
import type { TradeOrder } from '../../trade/domain/trade-order'
import { confirmDialog } from '../../trade/presentation/shared/trade-presentation'
import {
  describeMyOrderCard,
  type MyOrderCardAction,
} from '../application/my-order-cards'
import {
  loadFirstMyOrderPages,
  loadMoreMyOrders,
  type MyOrderApis,
} from '../application/my-order-loader'
import {
  MY_ORDER_KEYS,
  matchesMyOrderFilter,
  myOrderSources,
  type MyOrderItem,
  type MyOrderKind,
  type MyOrderSource,
} from '../application/my-order-sources'
import {
  MY_ORDER_CHIPS,
  MY_ORDER_CHIP_LABELS,
  MY_ORDER_TABS,
  MY_ORDER_TAB_LABELS,
  type MyOrderChip,
  type MyOrderTab,
} from '../application/my-orders-route'
import MyOrderCard from './MyOrderCard.vue'

const props = defineProps<{
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
  // Counts up when the page is scrolled to its end.
  reachBottomKey: number
  initialTab: MyOrderTab
  initialChip: MyOrderChip | null
}>()

const emit = defineEmits<{
  back: []
  login: []
  // A card or a button leads to another page.
  open: [url: string]
  filterChange: [tab: MyOrderTab, chip: MyOrderChip | null]
  // A reload finished (the host stops the pull-down spinner).
  loaded: []
}>()

type ListState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error' }

// Each kind is listed only where its own pages are open (all of them in a preview build).
const KIND_ENABLED: Record<MyOrderKind, boolean> = {
  GOODS: TRADE_PREVIEW_ENABLED,
  RENTAL: RENTAL_PREVIEW_ENABLED,
  INSPECTION: true,
  ENROLL: ACTIVITY_PREVIEW_ENABLED,
  VENUE: EXPERIENCE_PREVIEW_ENABLED,
}

const apis: MyOrderApis = {
  goods: (token, tab, page, size) => tradeApi.list(token, tab, page, size),
  rental: (token, status, page, size) => rentalApi.listOrders(token, status, page, size),
  inspection: (stage, page, size) => inspectionOrderApi.list({ stage, page, size }),
  enroll: (token, bucket, page, size) => enrollmentApi.loadEnrollments(token, bucket, page, size),
  venue: (token, bucket, page, size) => venueBookingApi.list(token, bucket, page, size),
}

// Board OrderList: the empty state's document icon.
const EMPTY_ICON = 'M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h5'

const tab = ref<MyOrderTab>(props.initialTab)
const chip = ref<MyOrderChip | null>(props.initialChip)
const state = ref<ListState>({ kind: 'loading' })
const sources = ref<MyOrderSource[]>([])
const loaded = ref<MergedSource<MyOrderItem>[]>([])
// The device pictures come with the rental inquiries; the order alone has no product id.
const rentalCovers = ref<Record<string, string | null>>({})
const loadingMore = ref(false)
const busyId = ref<string | null>(null)
const now = ref(Date.now())
let requestVersion = 0
let timer: ReturnType<typeof setInterval> | null = null

const items = computed(() => mergedItems(loaded.value, MY_ORDER_KEYS)
  .filter((item) => matchesMyOrderFilter(item, tab.value, chip.value)))
const hasMore = computed(() => mergedHasMore(loaded.value))
const cards = computed(() => items.value.map((item) => ({
  item,
  view: describeMyOrderCard(item, now.value, rentalCovers.value),
})))

function isUnauthorized(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const failure = error as { status?: unknown; code?: unknown }
  return failure.status === 401 || failure.code === 'COMMON_UNAUTHORIZED'
}

async function loadRentalCovers(token: string): Promise<void> {
  try {
    const page = await rentalApi.listInquiries(token, 'CONFIRMED', 1, 50)
    const next: Record<string, string | null> = {}
    page.items.forEach((inquiry) => {
      next[inquiry.inquiryId] = rentalCoverUrl(inquiry.item.coverUrl, inquiry.item.productId)
    })
    rentalCovers.value = next
  } catch {
    // Pictures only.
  }
}

async function reload(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  const requested = ++requestVersion
  const wanted = myOrderSources(tab.value, chip.value, (kind) => KIND_ENABLED[kind])
  if (state.value.kind !== 'ready' || items.value.length === 0) state.value = { kind: 'loading' }
  try {
    const pages = await loadFirstMyOrderPages(apis, token, wanted)
    if (requested !== requestVersion) return
    sources.value = wanted
    loaded.value = pages
    now.value = Date.now()
    state.value = { kind: 'ready' }
    if (wanted.some((source) => source.kind === 'RENTAL')) void loadRentalCovers(token)
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    state.value = { kind: 'error' }
  } finally {
    if (requested === requestVersion) emit('loaded')
  }
}

async function loadMore(): Promise<void> {
  const token = props.accessToken
  if (!token || state.value.kind !== 'ready' || !hasMore.value || loadingMore.value) return
  const requested = requestVersion
  loadingMore.value = true
  try {
    const next = await loadMoreMyOrders(apis, token, sources.value, loaded.value)
    if (requested === requestVersion) loaded.value = next
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    uni.showToast({ title: '加载失败，请重试', icon: 'none' })
  } finally {
    if (requested === requestVersion) loadingMore.value = false
  }
}

function changeFilter(nextTab: MyOrderTab, nextChip: MyOrderChip | null): void {
  tab.value = nextTab
  chip.value = nextChip
  loaded.value = []
  state.value = { kind: 'loading' }
  emit('filterChange', nextTab, nextChip)
  void reload()
}

function chooseTab(next: MyOrderTab): void {
  if (next !== tab.value) changeFilter(next, chip.value)
}

// Board OrderList: tapping the chosen chip again shows every status.
function chooseChip(next: MyOrderChip): void {
  changeFilter(tab.value, chip.value === next ? null : next)
}

async function runGoodsCommand(order: TradeOrder, command: 'CANCEL' | 'CONFIRM_RECEIPT'): Promise<void> {
  const token = props.accessToken
  if (!token || busyId.value) return
  const confirm = command === 'CONFIRM_RECEIPT'
    ? TRADE_COMMAND_CONFIRMS.CONFIRM_RECEIPT
    : order.status === 'PAID' ? TRADE_COMMAND_CONFIRMS.CANCEL_AND_REFUND : TRADE_COMMAND_CONFIRMS.CANCEL
  if (!(await confirmDialog(confirm))) return
  busyId.value = order.orderId
  try {
    const input = { idempotencyKey: createTradeKey(), version: order.version }
    if (command === 'CONFIRM_RECEIPT') {
      await tradeApi.confirmReceipt(token, order.orderId, input)
      uni.showToast({ title: '已确认收货', icon: 'none' })
    } else {
      await tradeApi.cancel(token, order.orderId, input)
      uni.showToast({ title: '订单已取消', icon: 'none' })
    }
  } catch (error) {
    const failure = classifyOrderCommandFailure(error)
    if (failure.kind === 'UNAUTHORIZED') {
      emit('login')
      return
    }
    uni.showToast({ title: failure.message, icon: 'none' })
  } finally {
    busyId.value = null
  }
  // The order may now belong to another chip; the list is read again either way.
  await reload()
}

function runAction(item: MyOrderItem, action: MyOrderCardAction): void {
  if (action.target.kind === 'URL') {
    emit('open', action.target.url)
    return
  }
  if (item.kind !== 'GOODS') return
  void runGoodsCommand(item.order, action.target.kind === 'GOODS_RECEIPT' ? 'CONFIRM_RECEIPT' : 'CANCEL')
}

function openInspection(order: InspectionOrderSummary): void {
  const url = resolveInspectionOrderDestination(order)
  if (url) {
    emit('open', url)
    return
  }
  uni.showToast({ title: '订单状态已更新，请重新查看', icon: 'none' })
  void reload()
}

// The grey 查看履约 next to 去验收: the visits of the service order.
function openFulfillment(order: InspectionOrderSummary): void {
  if (order.serviceOrderId) emit('open', buildInspectionFulfillmentUrl(order.serviceOrderId))
}

watch(() => [props.refreshKey, props.accessToken], () => { void reload() }, { immediate: true })
watch(() => props.reachBottomKey, () => { void loadMore() })

onMounted(() => {
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view class="my-orders" data-testid="my-orders">
    <view class="my-orders__top">
      <!-- Board OrderList: the theme's gradient head with a centred title (the board's search is
           not built), four business tabs and five status chips. -->
      <AppPageHeader title="我的订单" centered test-id="my-orders" @back="emit('back')">
        <template #bottom>
          <view class="my-orders__tabs" role="tablist" aria-label="业务类型">
            <button
              v-for="item in MY_ORDER_TABS"
              :key="item"
              :class="['my-orders__tab', { 'my-orders__tab--on': item === tab }]"
              :data-testid="`my-orders-tab-${item}`"
              role="tab"
              :aria-selected="item === tab"
              @click="chooseTab(item)"
            >
              {{ MY_ORDER_TAB_LABELS[item] }}
              <view v-if="item === tab" class="my-orders__tab-bar" />
            </button>
          </view>
          <view class="my-orders__chips" role="group" aria-label="按状态筛选">
            <button
              v-for="item in MY_ORDER_CHIPS"
              :key="item"
              :class="['my-orders__chip', { 'my-orders__chip--on': item === chip }]"
              :data-testid="`my-orders-chip-${item}`"
              :aria-pressed="item === chip"
              @click="chooseChip(item)"
            >
              {{ MY_ORDER_CHIP_LABELS[item] }}
            </button>
          </view>
        </template>
      </AppPageHeader>
    </view>

    <view v-if="state.kind === 'loading'" class="my-orders__body" data-testid="my-orders-loading" aria-label="正在加载">
      <view class="my-orders__skeleton" />
      <view class="my-orders__skeleton" />
    </view>

    <view v-else-if="state.kind === 'error'" class="my-orders__state" data-testid="my-orders-error">
      <text class="my-orders__state-title">加载失败</text>
      <text class="my-orders__state-text">请检查网络后重试</text>
      <button class="my-orders__state-button" data-testid="my-orders-retry" @click="reload">重试</button>
    </view>

    <view v-else-if="cards.length === 0 && !hasMore" class="my-orders__empty" data-testid="my-orders-empty">
      <view class="my-orders__empty-icon">
        <LineIcon :d="EMPTY_ICON" :size="30" color="#91a0b4" :stroke-width="1.6" />
      </view>
      <text class="my-orders__empty-text">暂无相关订单</text>
    </view>

    <view v-else class="my-orders__body">
      <template v-for="card in cards" :key="card.item.id">
        <InspectionOrderCard
          v-if="card.item.kind === 'INSPECTION'"
          :order="card.item.order"
          @select="openInspection"
          @fulfillment="openFulfillment"
        />
        <MyOrderCard
          v-else-if="card.view"
          :card="card.view"
          :test-id="`my-order-${card.item.id.replace(':', '-')}`"
          :busy="busyId !== null"
          @open="emit('open', card.view.url)"
          @action="(action: MyOrderCardAction) => runAction(card.item, action)"
        />
      </template>
      <button
        v-if="hasMore"
        class="my-orders__more"
        data-testid="my-orders-more"
        :disabled="loadingMore"
        @click="loadMore"
      >
        {{ loadingMore ? '正在加载…' : '加载更多' }}
      </button>
      <text v-else class="my-orders__end" data-testid="my-orders-end">没有更多了</text>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours copied from the 2026-11-14 board OrderList (390 wide). */
.my-orders {
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

.my-orders__top {
  position: sticky;
  top: 0;
  z-index: 5;
}

.my-orders__tabs {
  display: grid;
  padding: 0 8px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.my-orders__tab {
  position: relative;
  height: 44px;
  color: #627187;
  font-size: 16px;
  font-weight: 400;
  line-height: 44px;
  white-space: nowrap;
}

.my-orders__tab--on {
  color: #202c42;
  font-weight: 700;
}

.my-orders__tab-bar {
  position: absolute;
  bottom: 3px;
  left: 50%;
  width: 20px;
  height: 3px;
  margin-left: -10px;
  border-radius: 3px;
  background: #ff6a00;
}

.my-orders__chips {
  display: flex;
  padding: 8px 16px 12px;
  gap: 6px;
  /* The five chips fill a 390 screen; a narrower one scrolls them instead of squeezing. */
  overflow-x: auto;
  scrollbar-width: none;
}

.my-orders__chips::-webkit-scrollbar {
  display: none;
}

.my-orders__chip {
  height: 36px;
  padding: 0 6px;
  flex: 1 0 auto;
  border: 1px solid #eef2f8;
  border-radius: 999px;
  color: #202c42;
  background: #eef2f8;
  font-size: 13px;
  font-weight: 400;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.my-orders__chip--on {
  border-color: #ffd8b8;
  color: #ba470b;
  background: #fff0e3;
  font-weight: 500;
}

.my-orders__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 12px 16px calc(24px + env(safe-area-inset-bottom));
}

.my-orders__skeleton {
  height: 170px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.my-orders__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.my-orders__state-title {
  font-size: 16px;
  font-weight: 700;
}

.my-orders__state-text {
  color: #738096;
  font-size: 13px;
}

.my-orders__state-button {
  min-width: 120px;
  height: 40px;
  margin-top: 8px;
  padding: 0 20px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 15px;
  line-height: 40px;
}

.my-orders__empty {
  display: flex;
  padding: 72px 0;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.my-orders__empty-icon {
  display: flex;
  width: 64px;
  height: 64px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef0f3;
}

.my-orders__empty-text {
  color: #738096;
  font-size: 14px;
  line-height: 20px;
}

.my-orders__more {
  align-self: center;
  min-height: 44px;
  padding: 0 16px;
  color: #ba470b;
  font-size: 13px;
}

.my-orders__end {
  padding: 4px 0 12px;
  color: #91a0b4;
  font-size: 12px;
  text-align: center;
}
</style>
