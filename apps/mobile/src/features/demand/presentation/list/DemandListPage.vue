<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'

import {
  DEMAND_FILTER_LABELS,
  describeDemandCard,
  type DemandCardAction,
} from '../../application/demand-view'
import { demandApi } from '../../data/demand-api-client'
import { DEMAND_PAGE_SIZE, type Demand } from '../../domain/demand'
import {
  DEMAND_LIST_FILTERS,
  demandStatusOfFilter,
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
}>()

type ListState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error' }

const filter = ref<DemandListFilter>(props.initialFilter)
const state = ref<ListState>({ kind: 'loading' })
const items = ref<Demand[]>([])
const page = ref(1)
const hasMore = ref(false)
const loadingMore = ref(false)
const asking = ref<{ action: 'CANCEL' | 'DELETE'; demand: Demand } | null>(null)
const acting = ref(false)
let requestVersion = 0

const cards = computed(() => items.value.map((demand) => ({ demand, view: describeDemandCard(demand) })))
const emptyText = computed(() => (filter.value === 'DRAFT' ? '还没有草稿' : '没有符合条件的记录'))
const confirmCopy = computed(() => (asking.value?.action === 'DELETE'
  ? { title: '确认删除这份草稿？', text: '删除后不能恢复，需要时请重新填写。', keep: '暂不删除', go: '确认删除' }
  : { title: '确认取消这份需求？', text: '取消后服务商不再报价，已收到的报价也会失效。', keep: '暂不取消', go: '确认取消' }))

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
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
    const loaded = await demandApi.list(token, demandStatusOfFilter(filter.value), 1, DEMAND_PAGE_SIZE)
    if (requested !== requestVersion) return
    items.value = loaded.items
    page.value = 1
    hasMore.value = loaded.hasMore
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
    const next = await demandApi.list(token, demandStatusOfFilter(filter.value), page.value + 1, DEMAND_PAGE_SIZE)
    if (requested !== requestVersion) return
    // A demand that moved up while scrolling is shown once.
    const known = new Set(items.value.map((item) => item.demandId))
    items.value = [...items.value, ...next.items.filter((item) => !known.has(item.demandId))]
    page.value += 1
    hasMore.value = next.hasMore
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

function act(action: DemandCardAction, demand: Demand): void {
  switch (action) {
    case 'EDIT': emit('edit', demand.demandId); return
    case 'VIEW': emit('view', demand.demandId); return
    case 'QUOTES': emit('quotes', demand.demandId); return
    case 'REPUBLISH': emit('republish', demand.demandId); return
    case 'CANCEL':
    case 'DELETE':
      asking.value = { action, demand }
  }
}

async function confirmAction(): Promise<void> {
  const token = props.accessToken
  const target = asking.value
  if (!token || !target || acting.value) return
  acting.value = true
  try {
    if (target.action === 'DELETE') {
      await demandApi.deleteDraft(token, target.demand.demandId)
      uni.showToast({ title: '草稿已删除', icon: 'none' })
    } else {
      await demandApi.cancel(token, target.demand.demandId)
      uni.showToast({ title: '需求已取消', icon: 'none' })
    }
  } catch (error) {
    if (isUnauthorized(error)) {
      asking.value = null
      emit('login')
      return
    }
    // The demand moved on meanwhile (chosen, submitted, closed): the list below shows where it is.
    uni.showToast({
      title: error instanceof ApiRequestError && error.code === 'DEMAND_STATE_INVALID'
        ? (target.action === 'DELETE' ? '只有草稿能删除' : '需求已选定或已关闭，不能取消')
        : '没有成功，请稍后重试',
      icon: 'none',
    })
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
      <scroll-view class="demand-list__filters" scroll-x :show-scrollbar="false">
        <view class="demand-list__filter-row" role="group" aria-label="按状态筛选">
          <button
            v-for="code in DEMAND_LIST_FILTERS"
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
        <UniIcons type="compose" size="28" color="#91a0b4" />
      </view>
      <text class="demand-list__state-text">{{ emptyText }}</text>
      <button class="demand-list__state-button" data-testid="demand-list-empty-publish" @click="emit('publish')">
        发布需求
      </button>
    </view>

    <view v-else class="demand-list__body">
      <view
        v-for="card in cards"
        :key="card.demand.demandId"
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
              <UniIcons type="compose" size="26" color="#91a0b4" />
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
</style>
