<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import { ACTIVITY_PREVIEW_ENABLED } from '../../activity/application/activity-preview'
import { enrollmentApi } from '../../activity/data/enrollment-api-client'
import { EXPERIENCE_PREVIEW_ENABLED } from '../../experience/application/experience-preview'
import { venueBookingApi } from '../../experience/data/venue-booking-api-client'
import { inspectionOrderApi } from '../../inspection/data/service-flow/uni-booking-http-client'
import { RENTAL_PREVIEW_ENABLED } from '../../rental/application/rental-preview'
import { rentalApi } from '../../rental/data/rental-api-client'
import SearchHighlight from '../../search/presentation/SearchHighlight.vue'
import { TRADE_PREVIEW_ENABLED } from '../../trade/application/trade-preview'
import { tradeApi } from '../../trade/data/trade-api-client'
import type { MyOrderTagTone, MyOrderTone } from '../application/my-order-cards'
import { loadEveryMyOrder, type MyOrderApis } from '../application/my-order-loader'
import { MY_ORDER_SEARCH_TIPS, searchMyOrders } from '../application/my-order-search'
import { myOrderSources, type MyOrderItem, type MyOrderKind } from '../application/my-order-sources'
import { serviceOrderApi } from '../../serviceorder/data/service-order-api-client'
import { SERVICE_ORDER_PREVIEW_ENABLED } from '../../serviceorder/application/serviceorder-preview'

// Board OrderSearch: the box with 搜索; with nothing typed 最近搜索 (this device only) and 可以这样搜;
// with words, the matching orders of this account with the words in orange, or 没有找到. The
// orders are read once (every list of 我的订单, to its end) and looked through here.
const props = defineProps<{
  accessToken: string | null
  recent: string[]
}>()

const emit = defineEmits<{
  back: []
  login: []
  open: [url: string]
  remember: [keyword: string]
  clearRecent: []
  support: []
}>()

const KIND_ENABLED: Record<MyOrderKind, boolean> = {
  GOODS: TRADE_PREVIEW_ENABLED,
  RENTAL: RENTAL_PREVIEW_ENABLED,
  INSPECTION: true,
  ENROLL: ACTIVITY_PREVIEW_ENABLED,
  VENUE: EXPERIENCE_PREVIEW_ENABLED,
  SERVICE: SERVICE_ORDER_PREVIEW_ENABLED,
}

const apis: MyOrderApis = {
  goods: (token, tab, page, size) => tradeApi.list(token, tab, page, size),
  rental: (token, status, page, size) => rentalApi.listOrders(token, status, page, size),
  inspection: (stage, page, size) => inspectionOrderApi.list({ stage, page, size }),
  enroll: (token, bucket, page, size) => enrollmentApi.loadEnrollments(token, bucket, page, size),
  venue: (token, bucket, page, size) => venueBookingApi.list(token, bucket, page, size),
  service: (token, status, page, size) => serviceOrderApi.list(token, status, page, size),
}

const TAG_COLORS: Record<MyOrderTagTone, { background: string; color: string }> = {
  ORANGE: { background: '#fff0e3', color: '#ba470b' },
  PURPLE: { background: '#eee7ff', color: '#6a4fe0' },
  GREEN: { background: '#e2f6ed', color: '#11804a' },
  // Course orders (as on 我的订单's own card).
  BLUE: { background: '#e6f0ff', color: '#1d63c4' },
}

const TONE_COLORS: Record<MyOrderTone, string> = {
  ORANGE: '#ba470b',
  GREEN: '#11804a',
  GREY: '#738096',
  BLUE: '#1d63c4',
  PURPLE: '#6a4fe0',
  RED: '#d64f67',
  SLATE: '#536178',
}

const SEARCH_ICON = 'M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4'
const NOT_FOUND_ICON = 'M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4M8.5 11h5'
const KEYWORD_MAX = 40

type LoadState = { kind: 'idle' } | { kind: 'loading' } | { kind: 'ready' } | { kind: 'error' }

const keyword = ref('')
const items = ref<MyOrderItem[]>([])
const rentalCovers = ref<Record<string, string | null>>({})
const load = ref<LoadState>({ kind: 'idle' })
let loading: Promise<void> | null = null

const typed = computed(() => keyword.value.trim())
const hits = computed(() => load.value.kind === 'ready' ? searchMyOrders(items.value, typed.value, Date.now(), rentalCovers.value) : [])

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
      next[inquiry.inquiryId] = inquiry.item.coverUrl
    })
    rentalCovers.value = next
  } catch {
    // Pictures only.
  }
}

// The orders are read the first time there is something to look for, then kept for this visit.
function ensureLoaded(): Promise<void> {
  if (load.value.kind === 'ready') return Promise.resolve()
  if (loading) return loading
  const token = props.accessToken
  if (!token) {
    emit('login')
    return Promise.resolve()
  }
  load.value = { kind: 'loading' }
  loading = (async () => {
    try {
      const sources = myOrderSources('all', null, (kind) => KIND_ENABLED[kind])
      items.value = await loadEveryMyOrder(apis, token, sources)
      load.value = { kind: 'ready' }
      if (sources.some((source) => source.kind === 'RENTAL')) void loadRentalCovers(token)
    } catch (error) {
      if (isUnauthorized(error)) {
        load.value = { kind: 'idle' }
        emit('login')
        return
      }
      load.value = { kind: 'error' }
    } finally {
      loading = null
    }
  })()
  return loading
}

function readValue(event: Event): string {
  return ((event as Event & { detail?: { value?: string } }).detail?.value ?? '').toString().slice(0, KEYWORD_MAX)
}

function onInput(event: Event): void {
  keyword.value = readValue(event)
}

// 搜索 remembers the words (at most six, newest first); the results already follow the box.
function search(word = typed.value): void {
  keyword.value = word
  const trimmed = word.trim()
  if (!trimmed) return
  emit('remember', trimmed)
  void ensureLoaded()
}

function onConfirm(event: Event): void {
  search(readValue(event))
}

function retry(): void {
  load.value = { kind: 'idle' }
  void ensureLoaded()
}

watch(typed, (word) => {
  if (word) void ensureLoaded()
})
</script>

<template>
  <view class="order-search" data-testid="order-search">
    <view class="order-search__top">
      <button class="order-search__back" data-testid="order-search-back" aria-label="返回" @click="emit('back')">
        <LineIcon name="back" :size="22" color="#202c42" :stroke-width="2" />
      </button>
      <view class="order-search__box">
        <LineIcon :d="SEARCH_ICON" :size="18" color="#627187" :stroke-width="2" />
        <input
          class="order-search__input"
          data-testid="order-search-input"
          type="text"
          confirm-type="search"
          :focus="true"
          :maxlength="KEYWORD_MAX"
          :value="keyword"
          placeholder="订单号、商品名、服务商或活动名"
          placeholder-class="order-search__placeholder"
          aria-label="搜索订单"
          @input="onInput"
          @confirm="onConfirm"
        />
        <button v-if="keyword !== ''" class="order-search__clear" data-testid="order-search-clear" aria-label="清除" @click="keyword = ''">
          <view class="order-search__clear-dot">
            <LineIcon name="close" :size="10" color="#ffffff" :stroke-width="2.6" />
          </view>
        </button>
        <button class="order-search__submit" data-testid="order-search-submit" @click="search()">搜索</button>
      </view>
    </view>

    <view class="order-search__body">
      <template v-if="!typed">
        <view class="order-search__card">
          <view class="order-search__card-head">
            <text class="order-search__card-title">最近搜索</text>
            <button v-if="recent.length" class="order-search__clear-recent" data-testid="order-search-recent-clear" @click="emit('clearRecent')">清空</button>
          </view>
          <view v-if="recent.length" class="order-search__chips">
            <button v-for="word in recent" :key="word" class="order-search__chip" data-testid="order-search-recent-word" @click="search(word)">
              {{ word }}
            </button>
          </view>
          <text v-else class="order-search__none" data-testid="order-search-recent-none">还没有搜索记录</text>
        </view>
        <view class="order-search__card order-search__card--tips">
          <view class="order-search__card-head">
            <text class="order-search__card-title">可以这样搜</text>
          </view>
          <view v-for="tip in MY_ORDER_SEARCH_TIPS" :key="tip.key" class="order-search__tip">
            <text class="order-search__tip-key">{{ tip.key }}</text>
            <text class="order-search__tip-text">{{ tip.text }}</text>
          </view>
        </view>
        <text class="order-search__note">最近搜索只保存在本机，可随时清空；只能搜到当前账号下的订单</text>
      </template>

      <view v-else-if="load.kind === 'loading' || load.kind === 'idle'" class="order-search__skeletons" data-testid="order-search-loading" aria-label="正在搜索">
        <view class="order-search__skeleton" />
        <view class="order-search__skeleton" />
      </view>

      <view v-else-if="load.kind === 'error'" class="order-search__nothing" data-testid="order-search-error">
        <text class="order-search__nothing-title">订单读取失败</text>
        <text class="order-search__nothing-text">请检查网络后重试</text>
        <view class="order-search__nothing-actions">
          <button class="order-search__pill order-search__pill--primary" data-testid="order-search-retry" @click="retry">重试</button>
        </view>
      </view>

      <template v-else-if="hits.length">
        <text class="order-search__count" data-testid="order-search-count">“{{ typed }}” · 找到 {{ hits.length }} 条相关订单</text>
        <view
          v-for="hit in hits"
          :key="hit.key"
          class="order-search__hit"
          :data-testid="`order-search-hit-${hit.key.replace(':', '-')}`"
          @click="hit.url && emit('open', hit.url)"
        >
          <view class="order-search__hit-head">
            <text class="order-search__tag" :style="TAG_COLORS[hit.tag.tone]">{{ hit.tag.label }}</text>
            <SearchHighlight class="order-search__who" :parts="hit.who" />
            <text class="order-search__status" :style="{ color: hit.status.colour ?? (hit.status.tone ? TONE_COLORS[hit.status.tone] : '#536178') }">{{ hit.status.text }}</text>
          </view>
          <view class="order-search__hit-main">
            <image v-if="hit.image" class="order-search__picture" :src="hit.image" mode="aspectFill" :aria-label="hit.imageAlt" />
            <view v-else class="order-search__picture" />
            <view class="order-search__hit-body">
              <view class="order-search__title-line">
                <SearchHighlight class="order-search__title" :parts="hit.title" />
                <text class="order-search__amount">{{ hit.amount }}</text>
              </view>
              <SearchHighlight v-if="hit.sub.pre || hit.sub.hit" class="order-search__sub" :parts="hit.sub" />
              <view v-if="hit.orderNo" class="order-search__no">
                <text>订单号 </text>
                <SearchHighlight :parts="hit.orderNo" />
              </view>
            </view>
          </view>
        </view>
        <text class="order-search__end">没有更多了</text>
      </template>

      <view v-else class="order-search__empty" data-testid="order-search-empty">
        <view class="order-search__empty-icon">
          <LineIcon :d="NOT_FOUND_ICON" :size="30" color="#91a0b4" :stroke-width="1.6" />
        </view>
        <text class="order-search__empty-title">没有找到“{{ typed }}”</text>
        <text class="order-search__empty-text">换个关键词试试，或直接输入订单号</text>
        <view class="order-search__empty-actions">
          <button class="order-search__pill" data-testid="order-search-support" @click="emit('support')">联系客服</button>
          <button class="order-search__pill order-search__pill--primary" data-testid="order-search-all" @click="emit('back')">查看全部订单</button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 board OrderSearch (390 wide). */
.order-search {
  min-height: 100vh;
  color: #202c42;
  background: #f5f7fc;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.order-search__top {
  position: sticky;
  z-index: 5;
  top: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: calc(var(--status-bar-height) + 4px) 16px 10px 4px;
  border-bottom: 1px solid #f0f1f3;
  background: #fff;
}

.order-search__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.order-search__box {
  display: flex;
  height: 40px;
  min-width: 0;
  flex: 1 1 auto;
  align-items: center;
  gap: 8px;
  padding: 0 4px 0 12px;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  background: #fff;
  box-sizing: border-box;
}

.order-search__input {
  min-width: 0;
  flex: 1 1 auto;
  color: #202c42;
  font-size: 14px;
}

.order-search__placeholder {
  color: #738096;
}

.order-search__clear {
  display: flex;
  width: 40px;
  height: 40px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.order-search__clear-dot {
  display: flex;
  width: 16px;
  height: 16px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #c9d3e1;
}

.order-search__submit {
  display: flex;
  height: 32px;
  align-items: center;
  flex: none;
  padding: 0 16px;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
}

.order-search__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 16px calc(16px + env(safe-area-inset-bottom));
}

.order-search__card {
  display: flex;
  flex-direction: column;
  padding: 0 12px 12px;
  border-radius: 12px;
  background: #fff;
}

.order-search__card--tips {
  padding-bottom: 6px;
}

.order-search__card-head {
  display: flex;
  height: 44px;
  align-items: center;
  justify-content: space-between;
}

.order-search__card-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.order-search__clear-recent {
  display: flex;
  height: 44px;
  align-items: center;
  padding-left: 18px;
  color: #627187;
  font-size: 13px;
}

.order-search__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.order-search__chip {
  display: flex;
  height: 36px;
  max-width: 100%;
  align-items: center;
  padding: 0 14px;
  overflow: hidden;
  border-radius: 999px;
  color: #202c42;
  background: #eef2f8;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-search__none,
.order-search__tip-text {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.order-search__tip {
  display: flex;
  min-height: 40px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.order-search__tip-key {
  width: 72px;
  flex: none;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.order-search__tip-text {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  color: #202c42;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-search__note,
.order-search__count {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.order-search__skeletons {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.order-search__skeleton {
  height: 132px;
  border-radius: 14px;
  background: linear-gradient(90deg, #eef1f6, #f7f8fb, #eef1f6);
}

.order-search__hit {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 14px;
  background: #fff;
}

.order-search__hit-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.order-search__tag {
  flex: none;
  padding: 0 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;
}

.order-search__who {
  min-width: 0;
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.order-search__status {
  flex: none;
  margin-left: auto;
  font-size: 14px;
  font-weight: 500;
  line-height: 21px;
  white-space: nowrap;
}

.order-search__hit-main {
  display: flex;
  gap: 12px;
}

.order-search__picture {
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.order-search__hit-body {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 4px;
}

.order-search__title-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.order-search__title {
  min-width: 0;
  flex: 1 1 auto;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.order-search__amount {
  flex: none;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  white-space: nowrap;
}

.order-search__sub {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.order-search__no {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.order-search__end {
  align-self: center;
  padding: 4px 0;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.order-search__empty,
.order-search__nothing {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 64px 24px 24px;
}

.order-search__empty-icon {
  display: flex;
  width: 64px;
  height: 64px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef0f3;
}

.order-search__empty-title,
.order-search__nothing-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-align: center;
}

.order-search__empty-text,
.order-search__nothing-text {
  color: #627187;
  font-size: 13px;
  line-height: 19px;
  text-align: center;
}

.order-search__empty-actions,
.order-search__nothing-actions {
  display: flex;
  gap: 10px;
  margin-top: 8px;
}

.order-search__pill {
  display: flex;
  height: 40px;
  align-items: center;
  padding: 0 18px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #fff;
  font-size: 14px;
  box-sizing: border-box;
  white-space: nowrap;
}

.order-search__pill--primary {
  border-color: #ff6a00;
  color: #fff;
  background: #ff6a00;
  font-weight: 500;
}
</style>
