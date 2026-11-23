<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import { describeSearchGroups, hasSearchResults, type SearchGroupView, type SearchRow } from '../application/search-view'
import type { SearchApi } from '../data/search-api'
import { SEARCH_SCOPE_LABELS, type SearchResult, type SearchScope } from '../domain/search'
import SearchHighlight from './SearchHighlight.vue'
import SearchScopeTabs from './SearchScopeTabs.vue'

// Board MallSearch (结果页, same number as SearchPre): the keyword box that leads back to change
// it, the scope tabs, then one white card per group. 综合 shows a few of each with 查看全部 to that
// tab; one tab shows up to twenty and, for products and inspection services, a link to the
// channel's own list to filter, sort and page (the channels' 在本频道搜). Nothing found offers 发布采购需求 and 联系客服.
const props = defineProps<{
  api: SearchApi
  cityCode: string
  keyword: string
  initialScope: SearchScope
}>()

const emit = defineEmits<{
  back: []
  edit: []
  open: [url: string]
  // The mall channel or the inspection catalog with the keyword and their own filters.
  channel: [scope: 'PRODUCT' | 'SERVICE']
  demand: []
  support: []
  scopeChange: [scope: SearchScope]
  // 你是不是要搜 in the empty state: search the suggested word instead.
  correct: [keyword: string]
}>()

type State = { kind: 'loading' } | { kind: 'ready'; result: SearchResult } | { kind: 'error' }

const SEARCH_ICON = 'M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4'
const CAMERA_ICON = 'M4 8h3l2-3h6l2 3h3v11H4zM12 16.5a3.5 3.5 0 1 0 0-7a3.5 3.5 0 1 0 0 7z'

const scope = ref<SearchScope>(props.initialScope)
const state = ref<State>({ kind: 'loading' })
let revision = 0

const groups = computed<SearchGroupView[]>(() =>
  state.value.kind === 'ready' ? describeSearchGroups(state.value.result) : [])
const empty = computed(() => state.value.kind === 'ready' && !hasSearchResults(groups.value))
const correction = computed(() => state.value.kind === 'ready' ? state.value.result.correction : null)

async function load(): Promise<void> {
  const requested = ++revision
  state.value = { kind: 'loading' }
  try {
    const result = await props.api.search({ cityCode: props.cityCode, keyword: props.keyword, scope: scope.value })
    if (requested === revision) state.value = { kind: 'ready', result }
  } catch {
    if (requested === revision) state.value = { kind: 'error' }
  }
}

function selectScope(next: SearchScope): void {
  if (next === scope.value) return
  scope.value = next
  emit('scopeChange', next)
}

// 查看全部 on a group of 综合 switches to that tab (board MallSearch).
function showAll(group: SearchGroupView): void {
  selectScope(group.scope)
}

// On the 商品 and 服务 tabs the channel's own list can filter, sort and page the same words.
function moreInChannel(group: SearchGroupView): 'PRODUCT' | 'SERVICE' | null {
  if (scope.value === 'ALL' || group.rows.length === 0) return null
  return group.scope === 'PRODUCT' || group.scope === 'SERVICE' ? group.scope : null
}

function openRow(row: SearchRow): void {
  emit('open', row.url)
}

watch(scope, () => { void load() })
void load()
</script>

<template>
  <view class="search-results" data-testid="search-results">
    <view class="search-results__top">
      <view class="search-results__bar">
        <button class="search-results__back" data-testid="search-results-back" aria-label="返回" @click="emit('back')">
          <LineIcon name="back" :size="22" color="#202c42" :stroke-width="2" />
        </button>
        <button class="search-results__box" data-testid="search-results-edit" aria-label="修改搜索词" @click="emit('edit')">
          <LineIcon :d="SEARCH_ICON" :size="18" color="#627187" :stroke-width="2" />
          <text class="search-results__keyword" data-testid="search-results-keyword">{{ keyword }}</text>
          <view class="search-results__clear-dot">
            <LineIcon name="close" :size="10" color="#ffffff" :stroke-width="2.6" />
          </view>
          <text class="search-results__submit">搜索</text>
        </button>
      </view>
      <SearchScopeTabs :scope="scope" @select="selectScope" />
    </view>

    <view class="search-results__body">
      <view v-if="state.kind === 'loading'" class="search-results__skeletons" data-testid="search-results-loading" aria-label="正在搜索">
        <view class="search-results__skeleton" />
        <view class="search-results__skeleton" />
      </view>

      <view v-else-if="state.kind === 'error'" class="search-results__state" data-testid="search-results-error">
        <text class="search-results__state-title">搜索失败</text>
        <text class="search-results__state-text">请检查网络后重试</text>
        <button class="search-results__retry" data-testid="search-results-retry" @click="load">重试</button>
      </view>

      <view v-else-if="empty && scope === 'ALL'" class="search-results__nothing" data-testid="search-results-empty">
        <text class="search-results__nothing-title">没有找到“{{ keyword }}”</text>
        <button v-if="correction" class="search-results__fix" data-testid="search-results-fix" @click="emit('correct', correction)">
          你是不是要搜：<text class="search-results__fix-word">{{ correction }}</text>
        </button>
        <text class="search-results__nothing-text">换个关键词试试，或者直接把需求发给平台，由商家报价。</text>
        <view class="search-results__nothing-actions">
          <button class="search-results__pill search-results__pill--primary" data-testid="search-results-demand" @click="emit('demand')">发布采购需求</button>
          <button class="search-results__pill" data-testid="search-results-support" @click="emit('support')">联系客服</button>
        </view>
      </view>

      <view v-else-if="empty" class="search-results__scope-empty" data-testid="search-results-scope-empty">
        <LineIcon :d="SEARCH_ICON" :size="40" color="#91a0b4" :stroke-width="1.5" />
        <text class="search-results__scope-empty-text">没有找到相关{{ SEARCH_SCOPE_LABELS[scope] }}</text>
      </view>

      <template v-else>
        <view
          v-for="group in groups.filter((item) => item.rows.length > 0)"
          :key="group.scope"
          class="search-results__group"
          :data-testid="`search-group-${group.scope.toLowerCase()}`"
        >
          <view class="search-results__group-head">
            <text class="search-results__group-title">{{ group.label }} · {{ group.total }}</text>
            <button
              v-if="scope === 'ALL' && group.total > group.rows.length"
              class="search-results__all"
              :data-testid="`search-group-${group.scope.toLowerCase()}-all`"
              @click="showAll(group)"
            >
              查看全部 ›
            </button>
          </view>

          <template v-for="row in group.rows" :key="row.key">
            <!-- 商品: a 64px picture, the name, a grey line, the price and the sellers. -->
            <button
              v-if="row.kind === 'PRODUCT'"
              class="search-results__product"
              :data-testid="`search-row-${row.key.replace(':', '-')}`"
              @click="openRow(row)"
            >
              <view class="search-results__product-picture">
                <image v-if="row.image" class="search-results__fill" :src="row.image" mode="aspectFill" :aria-label="row.imageAlt" />
                <LineIcon v-else :d="CAMERA_ICON" :size="28" color="#91a0b4" :stroke-width="1.5" />
              </view>
              <view class="search-results__product-body">
                <SearchHighlight class="search-results__name" :parts="row.title" />
                <text v-if="row.sub" class="search-results__sub">{{ row.sub }}</text>
                <view class="search-results__price-line">
                  <text class="search-results__price">{{ row.price }}</text>
                  <text class="search-results__sellers">{{ row.sellers }}</text>
                </view>
              </view>
            </button>

            <!-- 服务: picture, name and price line, 提需求 on the right. -->
            <view v-else-if="row.kind === 'INSPECTION' || row.kind === 'PRO_SERVICE'" class="search-results__service">
              <button class="search-results__service-main" :data-testid="`search-row-${row.key.replace(':', '-')}`" @click="openRow(row)">
                <view class="search-results__thumb">
                  <image v-if="row.image" class="search-results__fill" :src="row.image" mode="aspectFill" :aria-label="row.imageAlt" />
                </view>
                <view class="search-results__service-body">
                  <SearchHighlight class="search-results__name" :parts="row.title" />
                  <text class="search-results__sub">{{ row.sub }}</text>
                </view>
              </button>
              <button
                v-if="row.demandUrl"
                class="search-results__demand"
                :data-testid="`search-row-${row.key.replace(':', '-')}-demand`"
                @click="emit('open', row.demandUrl)"
              >
                <text class="search-results__demand-pill">提需求</text>
              </button>
            </view>

            <!-- 活动: picture, type tag and title, the price. -->
            <button
              v-else-if="row.kind === 'ACTIVITY'"
              class="search-results__line"
              :data-testid="`search-row-${row.key.replace(':', '-')}`"
              @click="openRow(row)"
            >
              <view class="search-results__thumb">
                <image v-if="row.image" class="search-results__fill" :src="row.image" mode="aspectFill" :aria-label="row.imageAlt" />
              </view>
              <view class="search-results__line-body">
                <view class="search-results__tag-line">
                  <text class="search-results__activity-tag">{{ row.tag }}</text>
                  <SearchHighlight class="search-results__name" :parts="row.title" />
                </view>
                <text class="search-results__price">{{ row.price }}</text>
              </view>
            </button>

            <!-- 内容: picture and "案例 · title". -->
            <button
              v-else-if="row.kind === 'CONTENT'"
              class="search-results__line"
              :data-testid="`search-row-${row.key.replace(':', '-')}`"
              @click="openRow(row)"
            >
              <view class="search-results__thumb">
                <image v-if="row.image" class="search-results__fill" :src="row.image" mode="aspectFill" :aria-label="row.imageAlt" />
              </view>
              <view class="search-results__content-title">
                <text class="search-results__content-kind">{{ row.tag }}</text>
                <text> · </text>
                <SearchHighlight :parts="row.title" />
              </view>
              <text v-if="row.sponsored" class="search-results__sponsored">广告</text>
            </button>

            <!-- 场馆: picture, name and the opening note. -->
            <button
              v-else
              class="search-results__line"
              :data-testid="`search-row-${row.key.replace(':', '-')}`"
              @click="openRow(row)"
            >
              <view class="search-results__thumb">
                <image v-if="row.image" class="search-results__fill" :src="row.image" mode="aspectFill" :aria-label="row.imageAlt" />
              </view>
              <view class="search-results__service-body">
                <SearchHighlight class="search-results__name" :parts="row.title" />
                <text v-if="row.sub" class="search-results__sub">{{ row.sub }}</text>
              </view>
            </button>
          </template>

          <button
            v-if="moreInChannel(group)"
            class="search-results__channel"
            :data-testid="`search-group-${group.scope.toLowerCase()}-channel`"
            @click="emit('channel', group.scope as 'PRODUCT' | 'SERVICE')"
          >
            {{ group.scope === 'PRODUCT' ? '到商城里筛选、排序' : '到巡检服务目录里筛选' }} ›
          </button>
        </view>
        <text v-if="scope !== 'ALL'" class="search-results__end" data-testid="search-results-end">
          {{ groups.some((group) => group.total > group.rows.length) ? '只显示最相关的 20 条' : '没有更多了' }}
        </text>
      </template>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 board MallSearch (390 wide). */
.search-results {
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
  text-align: left;
}

button::after {
  border: 0;
}

.search-results__top {
  position: sticky;
  z-index: 5;
  top: 0;
  padding-top: var(--status-bar-height);
  border-bottom: 1px solid #f0f1f3;
  background: #fff;
}

.search-results__bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 16px 0 4px;
}

.search-results__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.search-results__box {
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

.search-results__keyword {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  color: #202c42;
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-results__clear-dot {
  display: flex;
  width: 16px;
  height: 16px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #c9d3e1;
}

.search-results__submit {
  display: flex;
  height: 32px;
  flex: none;
  align-items: center;
  padding: 0 16px;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
}

.search-results__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 16px calc(16px + env(safe-area-inset-bottom));
}

.search-results__skeletons {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.search-results__skeleton {
  height: 140px;
  border-radius: 12px;
  background: linear-gradient(90deg, #eef1f6, #f7f8fb, #eef1f6);
}

.search-results__state,
.search-results__scope-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 40px 16px;
  border-radius: 12px;
  background: #fff;
}

.search-results__state-title {
  font-size: 15px;
  font-weight: 700;
}

.search-results__state-text,
.search-results__scope-empty-text {
  color: #536178;
  font-size: 14px;
  line-height: 20px;
}

.search-results__retry {
  display: flex;
  height: 36px;
  align-items: center;
  margin-top: 4px;
  padding: 0 20px;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 14px;
}

.search-results__nothing {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border: 1px dashed #c9d3e1;
  border-radius: 12px;
}

.search-results__nothing-title {
  font-size: 14px;
  line-height: 20px;
}

.search-results__nothing-text {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.search-results__fix {
  color: #536178;
  font-size: 13px;
  line-height: 20px;
}

.search-results__fix-word {
  color: #ba470b;
  font-weight: 500;
}

.search-results__nothing-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-results__pill {
  display: flex;
  height: 34px;
  margin: 5px 0;
  align-items: center;
  padding: 0 14px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  font-size: 13px;
  line-height: 18px;
  box-sizing: border-box;
  white-space: nowrap;
}

.search-results__pill--primary {
  border-color: #ff6a00;
  color: #fff;
  background: #ff6a00;
  font-weight: 500;
}

.search-results__group {
  display: flex;
  flex-direction: column;
  padding: 0 12px;
  border-radius: 12px;
  background: #fff;
}

.search-results__group-head {
  display: flex;
  height: 36px;
  align-items: center;
}

.search-results__group-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.search-results__all {
  display: flex;
  height: 44px;
  margin: -4px 0 -4px auto;
  align-items: center;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.search-results__product {
  display: flex;
  width: 100%;
  gap: 10px;
  padding: 10px 0;
  border-top: 1px solid #f0f1f3;
  color: #202c42;
}

.search-results__product-picture {
  position: relative;
  display: flex;
  width: 64px;
  height: 64px;
  flex: none;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 10px;
  background: #eef0f3;
}

.search-results__fill {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
}

.search-results__product-body,
.search-results__service-body,
.search-results__line-body {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 2px;
}

.search-results__service-body,
.search-results__line-body {
  gap: 4px;
}

.search-results__name {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-results__sub {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-results__price-line {
  display: flex;
  height: 22px;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}

.search-results__price {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.search-results__sellers {
  margin-left: auto;
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.search-results__service {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  border-top: 1px solid #f0f1f3;
}

.search-results__service-main {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  align-items: center;
  gap: 10px;
  color: #202c42;
}

.search-results__thumb {
  position: relative;
  width: 48px;
  height: 48px;
  flex: none;
  overflow: hidden;
  border-radius: 10px;
  background: #eef0f3;
}

.search-results__demand {
  display: flex;
  height: 44px;
  flex: none;
  align-items: center;
}

.search-results__demand-pill {
  display: flex;
  height: 32px;
  align-items: center;
  padding: 0 12px;
  border-radius: 999px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.search-results__line {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-top: 1px solid #f0f1f3;
  color: #202c42;
}

.search-results__tag-line {
  display: flex;
  height: 20px;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.search-results__activity-tag {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  color: #6a4fe0;
  background: #eee7ff;
  font-size: 11px;
  line-height: 16px;
}

.search-results__content-title {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-results__content-kind {
  color: #1d63c4;
  font-weight: 400;
}

.search-results__sponsored {
  flex: none;
  padding: 0 4px;
  border: 1px solid #c9d3e1;
  border-radius: 4px;
  color: #738096;
  font-size: 10px;
  line-height: 14px;
}

.search-results__channel {
  display: flex;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-top: 1px solid #f0f1f3;
  color: #ba470b;
  font-size: 13px;
}

.search-results__end {
  color: #91a0b4;
  font-size: 12px;
  text-align: center;
}
</style>
