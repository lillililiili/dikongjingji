<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { AFTERSALE_TAB_LABELS, classifyAftersaleFailure } from '../../trade/application/aftersale-view'
import { aftersaleApi } from '../../trade/data/aftersale-api-client'
import {
  AFTERSALE_TABS,
  type Aftersale,
  type AftersaleCounts,
  type AftersaleTab,
} from '../../trade/domain/aftersale'
import { describeAftersaleCard } from '../application/aftersale-cards'
import type { MyOrderCardAction } from '../application/my-order-cards'
import MyOrderCard from './MyOrderCard.vue'

// 退款/售后（画板 AftersaleList）：三个页签带数量，卡片和“我的订单”同一种，底部绿条说明平台只协助沟通。
// 报名退改不在售后接口里，这页只列商品售后（报名的退款在“我的订单”的“退款/售后”胶囊下）。
const props = defineProps<{
  accessToken: string | null
  refreshKey: number
  reachBottomKey: number
  initialTab: AftersaleTab
}>()

const emit = defineEmits<{
  back: []
  login: []
  open: [url: string]
  tabChange: [tab: AftersaleTab]
  loaded: []
}>()

type ListState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error' }

const PAGE_SIZE = 10
const SHIELD = 'M12 3l7 3v5c0 4.6-3 8.4-7 10c-4-1.6-7-5.4-7-10V6zM8.5 12l2.5 2.5 4.5-5'
const EMPTY_ICON = 'M3 7l9-4 9 4v10l-9 4-9-4zM3 7l9 4 9-4M12 11v10'

const tab = ref<AftersaleTab>(props.initialTab)
const state = ref<ListState>({ kind: 'loading' })
const items = ref<Aftersale[]>([])
const counts = ref<AftersaleCounts | null>(null)
const page = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)
let requestVersion = 0

const cards = computed(() => items.value.map((aftersale) => ({ aftersale, view: describeAftersaleCard(aftersale) })))

function countOf(item: AftersaleTab): number | null {
  if (!counts.value) return null
  return item === 'DOING' ? counts.value.doing : item === 'DONE' ? counts.value.done : counts.value.closed
}

async function reload(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready' || items.value.length === 0) state.value = { kind: 'loading' }
  try {
    const loaded = await aftersaleApi.list(token, tab.value, 1, PAGE_SIZE)
    if (requested !== requestVersion) return
    items.value = loaded.items
    counts.value = loaded.counts
    page.value = 1
    hasMore.value = loaded.hasMore && loaded.items.length > 0
    state.value = { kind: 'ready' }
  } catch (error) {
    if (requested !== requestVersion) return
    if (classifyAftersaleFailure(error).kind === 'UNAUTHORIZED') {
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
    const loaded = await aftersaleApi.list(token, tab.value, page.value + 1, PAGE_SIZE)
    if (requested !== requestVersion) return
    const known = new Set(items.value.map((item) => item.aftersaleId))
    items.value = [...items.value, ...loaded.items.filter((item) => !known.has(item.aftersaleId))]
    counts.value = loaded.counts
    page.value += 1
    hasMore.value = loaded.hasMore && loaded.items.length > 0
  } catch {
    if (requested === requestVersion) uni.showToast({ title: '加载失败，请重试', icon: 'none' })
  } finally {
    if (requested === requestVersion) loadingMore.value = false
  }
}

function chooseTab(next: AftersaleTab): void {
  if (next === tab.value) return
  tab.value = next
  items.value = []
  state.value = { kind: 'loading' }
  emit('tabChange', next)
  void reload()
}

function runAction(action: MyOrderCardAction): void {
  if (action.target.kind === 'URL') emit('open', action.target.url)
}

function contactPlatform(): void {
  openSupportChat({ kind: 'PLATFORM' })
}

watch(() => [props.refreshKey, props.accessToken], () => { void reload() }, { immediate: true })
watch(() => props.reachBottomKey, () => { void loadMore() })
</script>

<template>
  <view class="aftersales" data-testid="aftersales">
    <view class="aftersales__top">
      <AppPageHeader title="退款/售后" plain test-id="aftersales" @back="emit('back')">
        <template #bottom>
          <view class="aftersales__tabs" role="tablist" aria-label="售后状态">
            <button
              v-for="item in AFTERSALE_TABS"
              :key="item"
              :class="['aftersales__tab', { 'aftersales__tab--on': item === tab }]"
              role="tab"
              :aria-selected="item === tab"
              :data-testid="`aftersales-tab-${item}`"
              @click="chooseTab(item)"
            >
              {{ AFTERSALE_TAB_LABELS[item] }}{{ countOf(item) === null ? '' : ` ${countOf(item)}` }}
              <view v-if="item === tab" class="aftersales__tab-bar" />
            </button>
          </view>
        </template>
      </AppPageHeader>
    </view>

    <view v-if="state.kind === 'loading'" class="aftersales__body" data-testid="aftersales-loading" aria-label="正在加载">
      <view class="aftersales__skeleton" />
      <view class="aftersales__skeleton" />
    </view>

    <view v-else-if="state.kind === 'error'" class="aftersales__state" data-testid="aftersales-error">
      <text class="aftersales__state-title">加载失败</text>
      <text class="aftersales__state-text">请检查网络后重试</text>
      <button class="aftersales__state-button" data-testid="aftersales-retry" @click="reload">重试</button>
    </view>

    <view v-else class="aftersales__body">
      <view v-if="cards.length === 0" class="aftersales__empty" data-testid="aftersales-empty">
        <view class="aftersales__empty-icon">
          <LineIcon :d="EMPTY_ICON" :size="30" color="#91a0b4" :stroke-width="1.6" />
        </view>
        <text class="aftersales__empty-text">暂无{{ AFTERSALE_TAB_LABELS[tab] }}的售后单</text>
      </view>
      <MyOrderCard
        v-for="card in cards"
        :key="card.aftersale.aftersaleId"
        :card="card.view"
        :test-id="`aftersale-card-${card.aftersale.aftersaleNo}`"
        @open="emit('open', card.view.url)"
        @action="runAction"
      />
      <button
        v-if="hasMore"
        class="aftersales__more"
        data-testid="aftersales-more"
        :disabled="loadingMore"
        @click="loadMore"
      >
        {{ loadingMore ? '正在加载…' : '加载更多' }}
      </button>
      <view class="aftersales__note" data-testid="aftersales-note">
        <LineIcon :d="SHIELD" :size="16" color="#159a70" :stroke-width="2" />
        <text class="aftersales__note-text">售后由售后责任方处理；协商不成时，平台客服协助沟通，不裁决。</text>
        <button v-if="SUPPORT_PREVIEW_ENABLED" class="aftersales__note-link" data-testid="aftersales-contact" @click="contactPlatform">
          联系客服 ›
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours copied from the 2026-11-14 board AftersaleList (390 wide). */
.aftersales {
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

.aftersales__top {
  position: sticky;
  top: 0;
  z-index: 5;
}

.aftersales__tabs {
  display: grid;
  padding: 0 8px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.aftersales__tab {
  position: relative;
  height: 44px;
  color: #627187;
  font-size: 16px;
  font-weight: 400;
  line-height: 44px;
  white-space: nowrap;
}

.aftersales__tab--on {
  color: #202c42;
  font-weight: 700;
}

.aftersales__tab-bar {
  position: absolute;
  bottom: 3px;
  left: 50%;
  width: 20px;
  height: 3px;
  margin-left: -10px;
  border-radius: 3px;
  background: #ff6a00;
}

.aftersales__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 12px 16px;
}

.aftersales__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.aftersales__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.aftersales__state-title {
  font-size: 16px;
  font-weight: 700;
}

.aftersales__state-text {
  color: #738096;
  font-size: 13px;
}

.aftersales__state-button {
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

.aftersales__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 56px 0 32px;
}

.aftersales__empty-icon {
  display: flex;
  width: 64px;
  height: 64px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #eef0f3;
}

.aftersales__empty-text {
  color: #738096;
  font-size: 14px;
  line-height: 20px;
}

.aftersales__more {
  height: 40px;
  color: #536178;
  font-size: 14px;
  text-align: center;
}

.aftersales__note {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 4px 4px 12px;
  border-radius: 10px;
  background: #e2f6ed;
}

.aftersales__note :deep(.line-icon) {
  flex: none;
}

.aftersales__note-text {
  min-width: 0;
  flex: 1;
  padding: 8px 0;
  color: #11804a;
  font-size: 12px;
  line-height: 17px;
}

.aftersales__note-link {
  display: flex;
  min-height: 44px;
  flex: none;
  padding: 0 8px;
  align-items: center;
  color: #11804a;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}
</style>
