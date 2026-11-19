<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import {
  loadMoreMerged,
  mergedHasMore,
  mergedItems,
  type MergedKeys,
  type MergedSource,
} from '@/shared/list/merged-pages'

import { rentalCoverUrl } from '../../application/rental-cover'
import { describeRentalOrderCard } from '../../application/rental-order-view'
import { rentalApi } from '../../data/rental-api-client'
import { RENTAL_PAGE_SIZE, type RentalInquiry, type RentalOrder } from '../../domain/rental'
import {
  RENTAL_ORDER_TABS,
  RENTAL_ORDER_TAB_LABELS,
  rentalOrderStatusesOfTab,
  type RentalOrderTab,
} from '../../navigation/rental-routes'
import RentalPageHeader from '../shared/RentalPageHeader.vue'

const props = defineProps<{
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
  // Counts up when the page is scrolled to its end.
  reachBottomKey: number
  initialTab: RentalOrderTab
}>()

const emit = defineEmits<{
  back: []
  login: []
  open: [rentalOrderId: string]
  pay: [tradeOrderId: string]
  browse: []
}>()

type ListState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error' }

// Orders are listed newest confirmation first.
const KEYS: MergedKeys<RentalOrder> = {
  idOf: (order) => order.rentalOrderId,
  timeOf: (order) => order.createdAt,
}

const tab = ref<RentalOrderTab>(props.initialTab)
const state = ref<ListState>({ kind: 'loading' })
const sources = ref<MergedSource<RentalOrder>[]>([])
// The device pictures come with the inquiries; the order alone has no product id.
const covers = ref<Record<string, string | null>>({})
const loadingMore = ref(false)
let requestVersion = 0

const orders = computed(() => mergedItems(sources.value, KEYS))
const hasMore = computed(() => mergedHasMore(sources.value))
const cards = computed(() => orders.value.map((order) => ({
  order,
  view: describeRentalOrderCard(order, new Date()),
  cover: covers.value[order.inquiryId] ?? null,
})))

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

function fetchPage(token: string, index: number, page: number) {
  const status = rentalOrderStatusesOfTab(tab.value)[index] ?? null
  return rentalApi.listOrders(token, status, page, RENTAL_PAGE_SIZE)
}

// One page of the inquiries is enough for the pictures of a page of orders; a missing one shows the icon.
async function loadCovers(token: string): Promise<void> {
  try {
    const page = await rentalApi.listInquiries(token, 'CONFIRMED', 1, 50)
    const next: Record<string, string | null> = {}
    page.items.forEach((inquiry: RentalInquiry) => {
      next[inquiry.inquiryId] = rentalCoverUrl(inquiry.item.coverUrl, inquiry.item.productId)
    })
    covers.value = next
  } catch {
    // Pictures only.
  }
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const statuses = rentalOrderStatusesOfTab(tab.value)
    const pages = await Promise.all(statuses.map((_, index) => fetchPage(token, index, 1)))
    if (requested !== requestVersion) return
    sources.value = pages.map((page) => ({ items: page.items, hasMore: page.hasMore, page: 1 }))
    state.value = { kind: 'ready' }
    void loadCovers(token)
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
    const next = await loadMoreMerged(sources.value, KEYS, (index, page) => fetchPage(token, index, page))
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

function selectTab(next: RentalOrderTab): void {
  if (next === tab.value) return
  tab.value = next
  state.value = { kind: 'loading' }
  void load()
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
watch(() => props.reachBottomKey, () => { void loadMore() })
</script>

<template>
  <view class="rental-orders" data-testid="rental-orders">
    <view class="rental-orders__top">
      <RentalPageHeader title="租赁订单" @back="emit('back')" />
      <scroll-view class="rental-orders__tabs" scroll-x :show-scrollbar="false">
        <view class="rental-orders__tab-row" role="group" aria-label="按状态筛选">
          <button
            v-for="code in RENTAL_ORDER_TABS"
            :key="code"
            class="rental-orders__tab"
            :data-testid="`rental-orders-tab-${code.toLowerCase()}`"
            :aria-pressed="code === tab"
            @click="selectTab(code)"
          >
            <text :class="['rental-orders__tab-pill', { 'rental-orders__tab-pill--on': code === tab }]">
              {{ RENTAL_ORDER_TAB_LABELS[code] }}
            </text>
          </button>
        </view>
      </scroll-view>
    </view>

    <view v-if="state.kind === 'loading'" class="rental-orders__body" aria-label="正在加载">
      <view class="rental-orders__skeleton" />
      <view class="rental-orders__skeleton" />
    </view>

    <view v-else-if="state.kind === 'error'" class="rental-orders__state" data-testid="rental-orders-error">
      <text class="rental-orders__state-title">加载失败</text>
      <text class="rental-orders__state-text">请检查网络后重试</text>
      <button class="rental-orders__state-button" data-testid="rental-orders-retry" @click="load">重试</button>
    </view>

    <view v-else-if="cards.length === 0" class="rental-orders__state" data-testid="rental-orders-empty">
      <view class="rental-orders__empty-icon"><UniIcons type="calendar" size="28" color="#91a0b4" /></view>
      <text class="rental-orders__state-text">{{ tab === 'ALL' ? '还没有租赁订单' : '没有符合条件的订单' }}</text>
      <button class="rental-orders__state-button" data-testid="rental-orders-browse" @click="emit('browse')">去商城租设备</button>
    </view>

    <view v-else class="rental-orders__body">
      <view
        v-for="card in cards"
        :key="card.order.rentalOrderId"
        class="rental-orders__card"
        :data-testid="`rental-order-card-${card.order.rentalOrderId}`"
        :data-status="card.order.status"
      >
        <button class="rental-orders__main" :data-testid="`rental-order-card-open-${card.order.rentalOrderId}`" @click="emit('open', card.order.rentalOrderId)">
          <view class="rental-orders__head">
            <text class="rental-orders__merchant">{{ card.order.contract.lessor.name }}</text>
            <text :class="['rental-orders__status', `rental-orders__status--${card.view.tone.toLowerCase()}`]" data-testid="rental-order-card-status">
              {{ card.view.statusText }}
            </text>
          </view>
          <view class="rental-orders__content">
            <image v-if="card.cover" class="rental-orders__image" :src="card.cover" mode="aspectFill" />
            <view v-else class="rental-orders__image rental-orders__image--empty">
              <UniIcons type="gear" size="26" color="#91a0b4" />
            </view>
            <view class="rental-orders__copy">
              <text class="rental-orders__title">{{ card.view.title }}</text>
              <text class="rental-orders__meta">{{ card.view.period }}</text>
              <text class="rental-orders__line" data-testid="rental-order-card-line">{{ card.view.line }}</text>
            </view>
            <text class="rental-orders__amount">{{ card.view.amount }}</text>
          </view>
        </button>
        <view class="rental-orders__actions">
          <button
            class="rental-orders__action"
            :data-testid="`rental-order-card-view-${card.order.rentalOrderId}`"
            @click="emit('open', card.order.rentalOrderId)"
          >
            查看订单
          </button>
          <button
            v-if="card.view.pay"
            class="rental-orders__action rental-orders__action--primary"
            :data-testid="`rental-order-card-pay-${card.order.rentalOrderId}`"
            @click="emit('pay', card.order.payment.tradeOrderId)"
          >
            去付款
          </button>
        </view>
      </view>

      <button
        v-if="hasMore"
        class="rental-orders__more"
        data-testid="rental-orders-more"
        :disabled="loadingMore"
        @click="loadMore"
      >
        {{ loadingMore ? '正在加载…' : '加载更多' }}
      </button>
      <text v-else class="rental-orders__end">没有更多了</text>
    </view>
  </view>
</template>

<style scoped>
/* The card follows the order list of the 2026-11-14 prototype (OrderList board) and the demand list. */
.rental-orders {
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

.rental-orders__top {
  position: sticky;
  top: 0;
  z-index: 5;
  background: #ffffff;
}

.rental-orders__tabs {
  width: 100%;
  background: #f5f7fc;
  white-space: nowrap;
}

.rental-orders__tab-row {
  display: inline-flex;
  padding: 6px 16px 0;
  gap: 6px;
}

.rental-orders__tab {
  flex: none;
  padding: 6px 0;
}

.rental-orders__tab-pill {
  display: flex;
  height: 32px;
  padding: 0 12px;
  align-items: center;
  border-radius: 999px;
  background: #ffffff;
  font-size: 13px;
  line-height: 32px;
  white-space: nowrap;
}

.rental-orders__tab-pill--on {
  color: #ffffff;
  background: #202c42;
}

.rental-orders__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 24px;
}

.rental-orders__card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
}

.rental-orders__main {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rental-orders__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rental-orders__merchant {
  overflow: hidden;
  min-width: 0;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rental-orders__status {
  flex: none;
  margin-left: auto;
  font-size: 14px;
  font-weight: 500;
  line-height: 21px;
  white-space: nowrap;
}

.rental-orders__status--action { color: #ba470b; }
.rental-orders__status--done { color: #11804a; }
.rental-orders__status--muted { color: #738096; }

.rental-orders__content {
  display: flex;
  gap: 12px;
}

.rental-orders__image {
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.rental-orders__image--empty {
  display: flex;
  align-items: center;
  justify-content: center;
}

.rental-orders__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.rental-orders__title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.rental-orders__meta {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.rental-orders__line {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.rental-orders__amount {
  flex: none;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  white-space: nowrap;
}

.rental-orders__actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.rental-orders__action {
  height: 36px;
  padding: 0 16px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  background: #ffffff;
  font-size: 14px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.rental-orders__action--primary {
  border-color: #ff6a00;
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.rental-orders__more {
  height: 44px;
  color: #536178;
  font-size: 13px;
  text-align: center;
}

.rental-orders__end {
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.rental-orders__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.rental-orders__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 32px;
}

.rental-orders__empty-icon {
  display: flex;
  width: 64px;
  height: 64px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef2f8;
}

.rental-orders__state-title {
  font-size: 16px;
  font-weight: 700;
}

.rental-orders__state-text {
  color: #627187;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.rental-orders__state-button {
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
