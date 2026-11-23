<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { openHomeChannel, openHomeMall } from '@/app-shell/navigation/home-entry'
import { HOME_ROOT_PATH } from '@/app-shell/navigation/root-tabs'
import type { SearchCategoryEntry } from '@/features/search/application/search-categories'
import { SEARCH_PREVIEW_ENABLED } from '@/features/search/application/search-preview'
import { searchCityOrDefault } from '@/features/search/application/search-city'
import { searchApi } from '@/features/search/data/search-api-client'
import type { SearchScope } from '@/features/search/domain/search'
import { buildSearchResultsUrl, parseSearchTarget } from '@/features/search/navigation/search-routes'
import SearchPrePage from '@/features/search/presentation/SearchPrePage.vue'
import SearchUnavailable from '@/features/search/presentation/SearchUnavailable.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import {
  GLOBAL_RECENT_SEARCH_KEY,
  createRecentSearches,
  uniSearchWordStorage,
} from '@/shared/search/recent-searches'

const cityStore = useCurrentCityStore()
const recentSearches = createRecentSearches(uniSearchWordStorage, GLOBAL_RECENT_SEARCH_KEY)
const loaded = ref(false)
const scope = ref<SearchScope>('ALL')
const keyword = ref('')
const recent = ref<string[]>([])
const city = computed(() => searchCityOrDefault(cityStore.currentCity))

onLoad((query) => {
  const target = parseSearchTarget(query)
  scope.value = target.scope
  keyword.value = target.keyword
  recent.value = recentSearches.load()
  loaded.value = true
})

// Board SearchPre: 返回 → HomeFull.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: HOME_ROOT_PATH })
}

function submit(word: string, chosen: SearchScope): void {
  recent.value = recentSearches.add(word)
  uni.navigateTo({ url: buildSearchResultsUrl(word, chosen) })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

function clearRecent(): void {
  recentSearches.clear()
  recent.value = []
}

function openCategory(entry: SearchCategoryEntry): void {
  if ('mallCategory' in entry.target) openHomeMall({ category: entry.target.mallCategory })
  else openHomeChannel(entry.target.channel)
}
</script>

<template>
  <SearchPrePage
    v-if="SEARCH_PREVIEW_ENABLED && loaded"
    :api="searchApi"
    :city-code="city?.code ?? null"
    :city-name="city?.name ?? null"
    :initial-scope="scope"
    :initial-keyword="keyword"
    :recent="recent"
    @back="goBack"
    @submit="submit"
    @open="open"
    @category="openCategory"
    @clear-recent="clearRecent"
  />
  <SearchUnavailable v-else-if="!SEARCH_PREVIEW_ENABLED" />
</template>
