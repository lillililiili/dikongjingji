<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import { SEARCH_CATEGORY_ENTRIES, SEARCH_TONES, type SearchCategoryEntry } from '../application/search-categories'
import { describeSearchSuggestions, searchCityLine, type SearchRow } from '../application/search-view'
import type { SearchApi } from '../data/search-api'
import { SEARCH_KEYWORD_MAX, type SearchResult, type SearchScope } from '../domain/search'
import SearchHighlight from './SearchHighlight.vue'
import SearchScopeTabs from './SearchScopeTabs.vue'

// Board SearchPre (APP-COM-02): the box, the six scope tabs, the city line; with nothing typed
// 最近搜索 (this device only) and 按类目找; while typing a 搜索“xx” row, 你是不是要搜 for a known
// typo and a few names that go straight to their pages. 搜索 opens the results (board MallSearch).
const props = defineProps<{
  api: SearchApi
  cityCode: string | null
  cityName: string | null
  initialScope: SearchScope
  initialKeyword: string
  recent: string[]
}>()

const emit = defineEmits<{
  back: []
  submit: [keyword: string, scope: SearchScope]
  open: [url: string]
  category: [entry: SearchCategoryEntry]
  clearRecent: []
}>()

// How long typing pauses before the names are looked up.
const SUGGEST_DELAY_MS = 250

const SEARCH_ICON = 'M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4'

const keyword = ref(props.initialKeyword)
const scope = ref<SearchScope>(props.initialScope)
const suggestion = ref<SearchResult | null>(null)
let timer: ReturnType<typeof setTimeout> | null = null
let revision = 0

// Only spaces show the clear button but the page stays as with nothing typed (board).
const typed = computed(() => keyword.value.trim())
const typing = computed(() => typed.value !== '')
const hits = computed<SearchRow[]>(() =>
  suggestion.value && suggestion.value.keyword === typed.value
    ? describeSearchSuggestions(suggestion.value, scope.value)
    : [])
const correction = computed(() =>
  suggestion.value && suggestion.value.keyword === typed.value ? suggestion.value.correction : null)
const cityLine = computed(() => searchCityLine(props.cityName))

function schedule(): void {
  if (timer) clearTimeout(timer)
  const word = typed.value
  if (!word || !props.cityCode) {
    revision += 1
    suggestion.value = null
    return
  }
  const cityCode = props.cityCode
  timer = setTimeout(() => {
    const requested = ++revision
    props.api.search({ cityCode, keyword: word, scope: 'ALL' })
      .then((result) => {
        if (requested === revision) suggestion.value = result
      })
      .catch(() => {
        // The names are a shortcut only; 搜索 still works.
        if (requested === revision) suggestion.value = null
      })
  }, SUGGEST_DELAY_MS)
}

function readValue(event: Event): string {
  return ((event as Event & { detail?: { value?: string } }).detail?.value ?? '').toString()
}

function onInput(event: Event): void {
  keyword.value = readValue(event).slice(0, SEARCH_KEYWORD_MAX)
  schedule()
}

// uni-app reports typing at most once every 100ms; the confirm event carries what the box holds.
function onConfirm(event: Event): void {
  keyword.value = readValue(event).slice(0, SEARCH_KEYWORD_MAX)
  submit()
}

function clearKeyword(): void {
  keyword.value = ''
  schedule()
}

function submit(word = typed.value): void {
  if (!word) {
    uni.showToast({ title: '请输入要搜索的内容', icon: 'none' })
    return
  }
  emit('submit', word, scope.value)
}

function applyCorrection(): void {
  if (!correction.value) return
  keyword.value = correction.value
  schedule()
}

if (typed.value) schedule()

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <view class="search-pre" data-testid="search-pre">
    <view class="search-pre__top">
      <view class="search-pre__bar">
        <button class="search-pre__back" data-testid="search-pre-back" aria-label="返回" @click="emit('back')">
          <LineIcon name="back" :size="22" color="#202c42" :stroke-width="2" />
        </button>
        <view class="search-pre__box">
          <LineIcon :d="SEARCH_ICON" :size="18" color="#627187" :stroke-width="2" />
          <input
            class="search-pre__input"
            data-testid="search-pre-input"
            type="text"
            confirm-type="search"
            :focus="true"
            :maxlength="SEARCH_KEYWORD_MAX"
            :value="keyword"
            placeholder="搜索设备、赛事研学或服务"
            placeholder-class="search-pre__placeholder"
            aria-label="搜索"
            @input="onInput"
            @confirm="onConfirm"
          />
          <button v-if="keyword !== ''" class="search-pre__clear" data-testid="search-pre-clear" aria-label="清除" @click="clearKeyword">
            <view class="search-pre__clear-dot">
              <LineIcon name="close" :size="10" color="#ffffff" :stroke-width="2.6" />
            </view>
          </button>
          <button class="search-pre__submit" data-testid="search-pre-submit" @click="submit()">
            <text class="search-pre__submit-pill">搜索</text>
          </button>
        </view>
      </view>
      <SearchScopeTabs :scope="scope" @select="scope = $event" />
    </view>

    <view class="search-pre__body">
      <text class="search-pre__city" data-testid="search-pre-city">{{ cityLine }}</text>

      <template v-if="!typing">
        <view class="search-pre__card" data-testid="search-recent">
          <view class="search-pre__card-head">
            <text class="search-pre__card-title">最近搜索</text>
            <button v-if="recent.length" class="search-pre__clear-recent" data-testid="search-recent-clear" @click="emit('clearRecent')">清空</button>
          </view>
          <view v-if="recent.length" class="search-pre__chips">
            <button
              v-for="word in recent"
              :key="word"
              class="search-pre__chip"
              data-testid="search-recent-word"
              @click="submit(word)"
            >
              {{ word }}
            </button>
          </view>
          <text v-else class="search-pre__none" data-testid="search-recent-none">还没有搜索记录</text>
        </view>

        <view class="search-pre__card search-pre__card--grid">
          <view class="search-pre__card-head">
            <text class="search-pre__card-title">按类目找</text>
          </view>
          <view class="search-pre__grid">
            <button
              v-for="entry in SEARCH_CATEGORY_ENTRIES"
              :key="entry.key"
              class="search-pre__entry"
              :data-testid="`search-category-${entry.key}`"
              @click="emit('category', entry)"
            >
              <view class="search-pre__entry-icon" :style="{ background: entry.background }">
                <LineIcon :d="entry.icon" :size="24" :color="entry.color" :stroke-width="1.8" />
              </view>
              <text class="search-pre__entry-label">{{ entry.label }}</text>
            </button>
          </view>
        </view>
        <text class="search-pre__note">最近搜索只保存在本机，可随时清空</text>
      </template>

      <view v-else class="search-pre__card search-pre__card--rows" data-testid="search-suggestions">
        <button class="search-pre__row" data-testid="search-pre-go" @click="submit()">
          <LineIcon :d="SEARCH_ICON" :size="18" color="#ff6a00" :stroke-width="2" />
          <text class="search-pre__row-go">搜索“{{ typed }}”</text>
          <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
        <button v-if="correction" class="search-pre__fix" data-testid="search-pre-fix" @click="applyCorrection">
          你是不是要搜：<text class="search-pre__fix-word">{{ correction }}</text>
        </button>
        <button
          v-for="hit in hits"
          :key="hit.key"
          class="search-pre__row search-pre__row--line"
          :data-testid="`search-suggestion-${hit.key.replace(':', '-')}`"
          @click="emit('open', hit.url)"
        >
          <text class="search-pre__type" :style="SEARCH_TONES[hit.tone]">{{ hit.typeLabel }}</text>
          <SearchHighlight class="search-pre__row-name" :parts="hit.title" />
          <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 board SearchPre (390 wide). */
.search-pre {
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

.search-pre__top {
  position: sticky;
  z-index: 5;
  top: 0;
  padding-top: var(--status-bar-height);
  border-bottom: 1px solid #f0f1f3;
  background: #fff;
}

.search-pre__bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 16px 0 4px;
}

.search-pre__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.search-pre__box {
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

.search-pre__input {
  min-width: 0;
  flex: 1 1 auto;
  color: #202c42;
  font-size: 15px;
}

.search-pre__placeholder {
  color: #738096;
}

.search-pre__clear {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.search-pre__clear-dot {
  display: flex;
  width: 16px;
  height: 16px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #c9d3e1;
}

.search-pre__submit {
  display: flex;
  height: 44px;
  flex: none;
  align-items: center;
}

.search-pre__submit-pill {
  display: flex;
  height: 32px;
  padding: 0 16px;
  align-items: center;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
}

.search-pre__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 16px;
}

.search-pre__city,
.search-pre__note {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.search-pre__card {
  display: flex;
  flex-direction: column;
  padding: 0 12px 12px;
  border-radius: 12px;
  background: #fff;
}

.search-pre__card--grid {
  padding-bottom: 14px;
}

.search-pre__card--rows {
  padding: 0 12px;
}

.search-pre__card-head {
  display: flex;
  height: 44px;
  align-items: center;
  justify-content: space-between;
}

.search-pre__card-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  white-space: nowrap;
}

.search-pre__clear-recent {
  display: flex;
  height: 44px;
  align-items: center;
  padding-left: 18px;
  color: #627187;
  font-size: 13px;
}

.search-pre__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.search-pre__chip {
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

.search-pre__none {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.search-pre__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px 0;
}

.search-pre__entry {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: #202c42;
}

.search-pre__entry-icon {
  display: flex;
  width: 46px;
  height: 46px;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
}

.search-pre__entry-label {
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.search-pre__row {
  display: flex;
  width: 100%;
  min-height: 48px;
  align-items: center;
  gap: 8px;
  color: #202c42;
  text-align: left;
}

.search-pre__row--line {
  border-top: 1px solid #f0f1f3;
}

.search-pre__row-go {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-pre__fix {
  display: flex;
  width: 100%;
  min-height: 48px;
  align-items: center;
  border-top: 1px solid #f0f1f3;
  color: #536178;
  font-size: 14px;
  line-height: 20px;
  text-align: left;
  white-space: nowrap;
}

.search-pre__fix-word {
  color: #ba470b;
  font-weight: 500;
}

.search-pre__type {
  flex: none;
  padding: 0 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.search-pre__row-name {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
