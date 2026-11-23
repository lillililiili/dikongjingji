<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { openHomeMall } from '@/app-shell/navigation/home-entry'
import { buildDemandPublishUrl } from '@/features/demand/navigation/demand-routes'
import { buildPortalSearchUrl } from '@/features/inspection/application/portal/build-portal-navigation'
import { SEARCH_PREVIEW_ENABLED } from '@/features/search/application/search-preview'
import { searchCityOrDefault } from '@/features/search/application/search-city'
import { searchApi } from '@/features/search/data/search-api-client'
import type { SearchScope } from '@/features/search/domain/search'
import {
  SEARCH_PRE_PATH,
  buildSearchPreUrl,
  buildSearchResultsUrl,
  parseSearchTarget,
} from '@/features/search/navigation/search-routes'
import SearchResultsPage from '@/features/search/presentation/SearchResultsPage.vue'
import SearchUnavailable from '@/features/search/presentation/SearchUnavailable.vue'
import { buildSupportOpenUrl } from '@/features/support/navigation/support-routes'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import {
  GLOBAL_RECENT_SEARCH_KEY,
  createRecentSearches,
  uniSearchWordStorage,
} from '@/shared/search/recent-searches'

const cityStore = useCurrentCityStore()
const loaded = ref(false)
const keyword = ref('')
const scope = ref<SearchScope>('ALL')
const cityCode = computed(() => searchCityOrDefault(cityStore.currentCity)?.code ?? null)

onLoad((query) => {
  const target = parseSearchTarget(query)
  keyword.value = target.keyword
  scope.value = target.scope
  loaded.value = true
})

function previousIsSearchPre(): boolean {
  const pages = getCurrentPages()
  const previous = pages[pages.length - 2] as { route?: string } | undefined
  return Boolean(previous?.route && `/${previous.route}` === SEARCH_PRE_PATH)
}

// Board MallSearch: 返回 and the box both lead to SearchPre, which still holds the words.
function backToSearch(): void {
  if (previousIsSearchPre()) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildSearchPreUrl({ scope: scope.value, keyword: keyword.value }) })
}

function correct(word: string): void {
  createRecentSearches(uniSearchWordStorage, GLOBAL_RECENT_SEARCH_KEY).add(word)
  uni.redirectTo({ url: buildSearchResultsUrl(word, scope.value) })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

// The channel's own list, with its filters, sorting and paging.
function openChannel(target: 'PRODUCT' | 'SERVICE'): void {
  if (target === 'PRODUCT') {
    openHomeMall({ keyword: keyword.value })
    return
  }
  uni.navigateTo({ url: buildPortalSearchUrl(cityCode.value ?? undefined, keyword.value) })
}
</script>

<template>
  <template v-if="SEARCH_PREVIEW_ENABLED && loaded">
    <SearchResultsPage
      v-if="cityCode && keyword"
      :key="`${cityCode}:${keyword}`"
      :api="searchApi"
      :city-code="cityCode"
      :keyword="keyword"
      :initial-scope="scope"
      @back="backToSearch"
      @edit="backToSearch"
      @open="open"
      @channel="openChannel"
      @demand="open(buildDemandPublishUrl())"
      @support="open(buildSupportOpenUrl({ kind: 'PLATFORM' }))"
      @scope-change="scope = $event"
      @correct="correct"
    />
    <SearchUnavailable v-else />
  </template>
  <SearchUnavailable v-else-if="!SEARCH_PREVIEW_ENABLED" />
</template>
