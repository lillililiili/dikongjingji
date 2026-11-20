<script lang="ts">
import {
  findCityByLocatedName as findControllerCityByLocatedName,
  type CityDirectory as ControllerCityDirectory,
  type PlatformCity as ControllerPlatformCity,
} from '@/shared/city/domain/city-directory'
import type { CitySelectionSource } from '@/shared/city/presentation/CitySelectionSheet.vue'
import type { CurrentCity as ControllerCurrentCity } from '@/shared/city/state/current-city'

export interface CatalogCityControllerOptions {
  setCurrentCity(city: ControllerCurrentCity): void
  saveRecentCity(city: ControllerPlatformCity): void
  loadRecentCities(): ControllerPlatformCity[]
  setRecentCities(cities: ControllerPlatformCity[]): void
  closeCitySheet(): void
  setPage(page: number): void
  reloadCatalog(): Promise<void>
  recordSearchSelection(cityCode: string): Promise<void>
  getDirectory(): ControllerCityDirectory | null
  isDirectoryLoading(): boolean
  loadDirectory(): Promise<void>
  isRelocating(): boolean
  setRelocating(value: boolean): void
  locateCityName(): Promise<string>
  showToast(title: string): void
}

export function createCatalogCityController(
  options: CatalogCityControllerOptions,
) {
  let locationInteractionRevision = 0

  function cancelRelocation(): void {
    locationInteractionRevision += 1
    if (options.isRelocating()) {
      options.setRelocating(false)
    }
  }

  async function applyCitySelection(payload: {
    city: ControllerPlatformCity
    source: CitySelectionSource
  }, cancelPendingRelocation = true): Promise<void> {
    if (cancelPendingRelocation) cancelRelocation()
    options.setCurrentCity({
      code: payload.city.code,
      name: payload.city.name,
      provinceCode: payload.city.provinceCode,
    })

    try {
      options.saveRecentCity(payload.city)
      options.setRecentCities(options.loadRecentCities())
    } catch {
      options.showToast('最近城市保存失败')
    }

    options.closeCitySheet()
    options.setPage(1)
    if (payload.source === 'SEARCH') {
      void options.recordSearchSelection(payload.city.code)
        .catch(() => undefined)
    }
    await options.reloadCatalog()
  }

  function closeCitySheet(): void {
    cancelRelocation()
    options.closeCitySheet()
  }

  async function relocateCity(): Promise<void> {
    if (options.isRelocating()) {
      return
    }
    if (options.isDirectoryLoading()) {
      options.showToast('城市加载中，请稍候')
      return
    }
    const relocationRevision = ++locationInteractionRevision
    if (!options.getDirectory()) {
      try {
        await options.loadDirectory()
      } catch {
        // The controlled directory prompt below covers unexpected adapters.
      }
    }
    if (relocationRevision !== locationInteractionRevision) return

    const directory = options.getDirectory()
    if (!directory) {
      options.showToast('请先重新加载城市目录')
      return
    }

    options.setRelocating(true)
    try {
      const locatedName = await options.locateCityName()
      if (relocationRevision !== locationInteractionRevision) return
      const city = findControllerCityByLocatedName(directory, locatedName)
      if (!city) {
        throw new Error('CITY_NOT_IN_DIRECTORY')
      }
      await applyCitySelection({ city, source: 'CURRENT' }, false)
    } catch {
      if (relocationRevision === locationInteractionRevision) {
        options.showToast('定位失败，请手动选择城市')
      }
    } finally {
      if (relocationRevision === locationInteractionRevision) {
        options.setRelocating(false)
      }
    }
  }

  return { applyCitySelection, relocateCity, closeCitySheet }
}

export interface LatestCatalogLoaderOptions<T> {
  getCityCode(): string | undefined | Promise<string | undefined>
  load(cityCode: string): Promise<T>
  emptyContent: T
  setContent(content: T): void
  setError(message: string): void
  setLoading(loading: boolean): void
  setRefreshing(refreshing: boolean): void
  errorMessage: string
}

export function createLatestCatalogLoader<T>(
  options: LatestCatalogLoaderOptions<T>,
): () => Promise<void> {
  let revision = 0

  return async (): Promise<void> => {
    const currentRevision = ++revision
    options.setLoading(true)
    options.setError('')

    try {
      const cityCode = await options.getCityCode()
      // No city is not a load failure: the page asks the user to choose one.
      if (!cityCode) return
      const loaded = await options.load(cityCode)
      if (currentRevision === revision) {
        options.setContent(loaded)
      }
    } catch {
      if (currentRevision === revision) {
        options.setContent(options.emptyContent)
        options.setError(options.errorMessage)
      }
    } finally {
      if (currentRevision === revision) {
        options.setLoading(false)
        options.setRefreshing(false)
      }
    }
  }
}
</script>

<script setup lang="ts">
import { openSupportChat } from '@/features/support/application/support-entry'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { storeToRefs } from 'pinia'

import { createRecentCityStorage } from '@/shared/city/data/recent-city-storage'
import { locateCurrentCityName } from '@/shared/city/data/uni-city-location'
import type {
  CityDirectory,
  PlatformCity,
} from '@/shared/city/domain/city-directory'
import CitySelectionSheet from '@/shared/city/presentation/CitySelectionSheet.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

import { locateCatalogPosition } from '../../../data/catalog/uni-catalog-position'
import {
  inspectionCatalogApi,
  platformCityApi,
} from '../../../data/service-flow/uni-booking-http-client'
import type {
  ActionResult,
  InspectionHostActionPort,
} from '../../../application/ports/inspection-host-action-port'
import CatalogFilterSheet from '../components/CatalogFilterSheet.vue'
import CatalogHeader from '../components/CatalogHeader.vue'
import CatalogToolbar from '../components/CatalogToolbar.vue'
import InspectionServiceCard from '../components/InspectionServiceCard.vue'
import {
  CATALOG_PAGE_SIZE,
  DEFAULT_CATALOG_FILTERS,
  DEFAULT_CATALOG_QUERY,
  EMPTY_CATALOG_CONTENT,
  EMPTY_CATALOG_FILTER_OPTIONS,
  appendCatalogPage,
  applyCatalogSearch,
  catalogCategoryChips,
  createOpenServiceDetailIntent,
  hasCatalogFilters,
  normalizeCatalogQuery,
  type CatalogFilterOptions,
  type CatalogFilterValue,
  type CatalogPosition,
  type CatalogSort,
  type InspectionCatalogContent,
} from '../model/inspection-catalog'

interface CatalogRouteQuery {
  cityCode?: string
  categoryCode?: string
  keyword?: string
  sort?: string
  openFilter?: string
}

const DISTANCE_FALLBACK_NOTE = '未开启定位，已按综合排序'

const currentCityStore = useCurrentCityStore()
const { currentCity } = storeToRefs(currentCityStore)
const recentCityStorage = createRecentCityStorage({
  get: (key) => uni.getStorageSync(key),
  set: (key, value) => uni.setStorageSync(key, value),
})
const content = ref<InspectionCatalogContent>(EMPTY_CATALOG_CONTENT)
const query = ref({ ...DEFAULT_CATALOG_QUERY })
const routeCityCode = ref('')
const keywordInput = ref('')
const filters = ref<CatalogFilterValue>({ ...DEFAULT_CATALOG_FILTERS })
const filterOptions = ref<CatalogFilterOptions>(EMPTY_CATALOG_FILTER_OPTIONS)
// The position is asked for once, when the customer first sorts by distance, and kept for
// as long as the page lives. It is never stored and only sent with the distance sort.
const position = ref<CatalogPosition | null>(null)
const distanceNote = ref('')
const isLoading = ref(true)
const isRefreshing = ref(false)
const isLoadingMore = ref(false)
const loadMoreFailed = ref(false)
const loadError = ref('')
const filterSheetOpen = ref(false)
const citySheetOpen = ref(false)
const cityDirectory = ref<CityDirectory | null>(null)
const cityDirectoryLoading = ref(false)
const cityDirectoryError = ref('')
const recentCities = ref(loadRecentCities())
const relocating = ref(false)

// Every first-page load starts a new list. A next page that answers after that belongs to
// the old conditions and is dropped.
let listRevision = 0
let nextPage = 2
let filterOptionsCityCode = ''

const displayCityName = computed(
  () => currentCity.value?.name ?? '选择城市',
)
const categoryChips = computed(() =>
  catalogCategoryChips(filterOptions.value.categories, query.value.category),
)

const hostActionGateway: Pick<
  InspectionHostActionPort,
  'openInspectionServiceDetail'
> = {
  openInspectionServiceDetail(serviceId: string): Promise<ActionResult> {
    return new Promise((resolve) => {
      uni.navigateTo({
        url: `/pages/service/inspection/detail?serviceId=${encodeURIComponent(
          serviceId,
        )}${
          currentCity.value?.code
            ? `&cityCode=${encodeURIComponent(currentCity.value.code)}`
            : ''
        }`,
        success: () => resolve({ status: 'OPENED' }),
        fail: () =>
          resolve({
            status: 'UNAVAILABLE',
            message: '服务详情暂时无法打开',
          }),
      })
    })
  },
}

function searchPage(cityCode: string, page: number): Promise<InspectionCatalogContent> {
  return inspectionCatalogApi.searchServices({
    cityCode,
    query: query.value,
    filters: filters.value,
    position: position.value,
    page,
    size: CATALOG_PAGE_SIZE,
  })
}

const runLatestCatalogLoad = createLatestCatalogLoader({
  getCityCode: resolveCatalogCityCode,
  load: (cityCode) => {
    void loadFilterOptions(cityCode)
    return searchPage(cityCode, 1)
  },
  emptyContent: EMPTY_CATALOG_CONTENT,
  setContent: (loaded) => {
    content.value = loaded
  },
  setError: (message) => {
    loadError.value = message
  },
  setLoading: (loading) => {
    isLoading.value = loading
  },
  setRefreshing: (refreshing) => {
    isRefreshing.value = refreshing
  },
  errorMessage: '巡检服务加载失败，请检查网络后重试',
})

const catalogCityController = createCatalogCityController({
  setCurrentCity: (city) => currentCityStore.setCurrentCity(city),
  saveRecentCity: (city) => recentCityStorage.save(city),
  loadRecentCities: () => recentCityStorage.load(),
  setRecentCities: (cities) => {
    recentCities.value = cities
  },
  closeCitySheet: () => {
    citySheetOpen.value = false
  },
  // The list always restarts from its first page; the controller keeps this hook for its callers.
  setPage: () => undefined,
  reloadCatalog: loadCatalogContent,
  recordSearchSelection: (cityCode) =>
    platformCityApi.recordSearchSelection(cityCode),
  getDirectory: () => cityDirectory.value,
  isDirectoryLoading: () => cityDirectoryLoading.value,
  loadDirectory: loadCityDirectory,
  isRelocating: () => relocating.value,
  setRelocating: (value) => {
    relocating.value = value
  },
  locateCityName: locateCurrentCityName,
  showToast: (title) => {
    uni.showToast({ title, icon: 'none' })
  },
})

onLoad((routeQuery: CatalogRouteQuery | undefined) => {
  query.value = normalizeCatalogQuery({
    keyword: routeQuery?.keyword,
    category: routeQuery?.categoryCode,
    sort: routeQuery?.sort,
  })
  keywordInput.value = query.value.keyword
  filterSheetOpen.value = routeQuery?.openFilter === 'true'
  routeCityCode.value = routeQuery?.cityCode?.trim() ?? ''
  void loadCatalogContent()
})

// A city already in the store is the user's latest choice, so the route only
// fills an empty store. The URL carries a code; the name comes from the directory.
async function resolveCatalogCityCode(): Promise<string | undefined> {
  if (!currentCity.value && routeCityCode.value) {
    const directory = await platformCityApi.load()
    const city = directory.provinces
      .flatMap((province) => province.cities)
      .find(({ code }) => code === routeCityCode.value)
    if (city && !currentCity.value) {
      const { code, name, provinceCode } = city
      currentCityStore.setCurrentCity({ code, name, provinceCode })
    }
  }
  return currentCity.value?.code
}

/** Loads the first page for the current city and conditions; whatever was listed is replaced. */
async function loadCatalogContent(): Promise<void> {
  listRevision += 1
  nextPage = 2
  isLoadingMore.value = false
  loadMoreFailed.value = false
  await runLatestCatalogLoad()
}

// The categories and filters belong to the city. A failure leaves the list usable with
// 全部 only, so it is not reported; the next load of the list tries again.
async function loadFilterOptions(cityCode: string): Promise<void> {
  if (filterOptionsCityCode === cityCode) return
  filterOptionsCityCode = cityCode
  try {
    const options = await inspectionCatalogApi.loadFilterOptions(cityCode)
    if (filterOptionsCityCode === cityCode) filterOptions.value = options
  } catch {
    if (filterOptionsCityCode === cityCode) {
      filterOptionsCityCode = ''
      filterOptions.value = EMPTY_CATALOG_FILTER_OPTIONS
    }
  }
}

async function refreshCatalog(): Promise<void> {
  if (isRefreshing.value || isLoading.value) {
    return
  }

  isRefreshing.value = true
  await loadCatalogContent()
}

async function loadMore(): Promise<void> {
  const cityCode = currentCity.value?.code
  if (
    !cityCode ||
    isLoading.value ||
    isRefreshing.value ||
    isLoadingMore.value ||
    !content.value.hasMore
  ) {
    return
  }

  const revision = listRevision
  isLoadingMore.value = true
  loadMoreFailed.value = false
  try {
    const page = await searchPage(cityCode, nextPage)
    if (revision !== listRevision) return
    content.value = appendCatalogPage(content.value, page)
    nextPage += 1
  } catch {
    if (revision === listRevision) loadMoreFailed.value = true
  } finally {
    if (revision === listRevision) isLoadingMore.value = false
  }
}

async function searchCatalog(keyword: string): Promise<void> {
  query.value = applyCatalogSearch(query.value, keyword)
  keywordInput.value = query.value.keyword
  await loadCatalogContent()
}

async function selectCategory(category: string): Promise<void> {
  if (query.value.category === category) return
  query.value = { ...query.value, category }
  await loadCatalogContent()
}

async function selectSort(sort: CatalogSort): Promise<void> {
  if (sort === 'DISTANCE_ASC') {
    await sortByDistance()
    return
  }
  distanceNote.value = ''
  query.value = { ...query.value, sort }
  await loadCatalogContent()
}

function confirmPositionUse(): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title: '允许使用你的位置？',
      content: '用于按距离排序附近的巡检服务',
      confirmText: '允许',
      cancelText: '不允许',
      success: (result) => resolve(result.confirm === true),
      fail: () => resolve(false),
    })
  })
}

async function sortByDistance(): Promise<void> {
  if (query.value.sort === 'DISTANCE_ASC') return

  // Asked once; a refusal is asked again the next time the customer taps 距离最近.
  if (!position.value && await confirmPositionUse()) {
    try {
      position.value = await locateCatalogPosition()
    } catch {
      position.value = null
    }
  }

  if (!position.value) {
    // Without a position the list goes back to the comprehensive order, also from a price
    // sort, so that the note says what the customer is looking at.
    distanceNote.value = DISTANCE_FALLBACK_NOTE
    if (query.value.sort !== 'COMPREHENSIVE') {
      query.value = { ...query.value, sort: 'COMPREHENSIVE' }
      await loadCatalogContent()
    }
    return
  }

  distanceNote.value = ''
  query.value = { ...query.value, sort: 'DISTANCE_ASC' }
  await loadCatalogContent()
}

async function applyFilters(value: CatalogFilterValue): Promise<void> {
  filters.value = value
  filterSheetOpen.value = false
  await loadCatalogContent()
}

async function resetFilters(): Promise<void> {
  filters.value = { ...DEFAULT_CATALOG_FILTERS }
  await loadCatalogContent()
}

async function clearConditions(): Promise<void> {
  keywordInput.value = ''
  // The sort is not a condition: clearing keeps the order the customer chose.
  query.value = { ...DEFAULT_CATALOG_QUERY, sort: query.value.sort }
  filters.value = { ...DEFAULT_CATALOG_FILTERS }
  await loadCatalogContent()
}

async function openServiceDetail(serviceId: string): Promise<void> {
  const intent = createOpenServiceDetailIntent(serviceId)
  const result = await hostActionGateway.openInspectionServiceDetail(
    intent.serviceId,
  )

  if (result.status === 'UNAVAILABLE') {
    uni.showToast({
      title: result.message,
      icon: 'none',
    })
  }
}

function loadRecentCities(): PlatformCity[] {
  try {
    return recentCityStorage.load()
  } catch {
    return []
  }
}

async function loadCityDirectory(): Promise<void> {
  if (cityDirectoryLoading.value) {
    return
  }

  cityDirectoryLoading.value = true
  cityDirectoryError.value = ''

  try {
    cityDirectory.value = await platformCityApi.load()
  } catch {
    cityDirectoryError.value = '城市目录加载失败，请稍后重试'
  } finally {
    cityDirectoryLoading.value = false
  }
}

async function openCitySheet(): Promise<void> {
  citySheetOpen.value = true
  if (cityDirectory.value || cityDirectoryLoading.value) {
    return
  }
  await loadCityDirectory()
}

async function applyCitySelection(payload: {
  city: PlatformCity
  source: 'CURRENT' | 'RECENT' | 'HOT' | 'SEARCH' | 'TREE'
}): Promise<void> {
  await catalogCityController.applyCitySelection(payload)
}

async function relocateCity(): Promise<void> {
  await catalogCityController.relocateCity()
}

function returnToPortal(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }

  uni.redirectTo({
    url: '/pages/service/inspection/index',
  })
}

// 客服 on the list: the platform support (step G1, preview builds); public builds keep the notice.
function showCustomerService(): void {
  if (SUPPORT_PREVIEW_ENABLED) {
    openSupportChat({ kind: 'PLATFORM' })
    return
  }
  uni.showToast({
    title: '客服能力由平台统一接入',
    icon: 'none',
  })
}
</script>

<template>
  <view class="inspection-catalog" data-testid="inspection-catalog">
    <view class="inspection-catalog__surface">
      <CatalogHeader
        v-model:keyword="keywordInput"
        @back="returnToPortal"
        @customer-service="showCustomerService"
        :filter-active="hasCatalogFilters(filters)"
        @search="searchCatalog"
        @filter="filterSheetOpen = true"
      />
      <CatalogToolbar
        :categories="categoryChips"
        :category="query.category"
        :sort="query.sort"
        :city-name="displayCityName"
        :distance-note="distanceNote"
        @category="selectCategory"
        @sort="selectSort"
        @city="openCitySheet"
      />

      <scroll-view
        class="inspection-catalog__scroll"
        scroll-y
        :show-scrollbar="false"
        refresher-enabled
        :refresher-triggered="isRefreshing"
        @refresherrefresh="refreshCatalog"
        @scrolltolower="loadMore"
      >
        <main class="inspection-catalog__content">
          <view
            v-if="isLoading"
            class="catalog-skeleton"
            aria-label="正在加载巡检服务"
          >
            <view
              v-for="index in 4"
              :key="index"
              class="catalog-skeleton__card"
            >
              <view class="catalog-skeleton__image" />
              <view class="catalog-skeleton__copy">
                <view class="catalog-skeleton__line catalog-skeleton__line--title" />
                <view class="catalog-skeleton__line" />
                <view class="catalog-skeleton__line catalog-skeleton__line--short" />
              </view>
            </view>
          </view>

          <view v-else-if="loadError" class="catalog-state">
            <LineIcon name="info" :size="42" color="#b8bdc4" />
            <text class="catalog-state__title">暂时无法加载</text>
            <text class="catalog-state__description">
              {{ loadError }}
            </text>
            <button class="catalog-state__action" @click="loadCatalogContent">
              重新加载
            </button>
          </view>

          <view v-else-if="!currentCity" class="catalog-state">
            <LineIcon name="pin" :size="42" color="#b8bdc4" />
            <text class="catalog-state__title">请先选择城市</text>
            <text class="catalog-state__description">
              选择城市后查看当地的巡检服务
            </text>
            <button class="catalog-state__action" @click="openCitySheet">
              选择城市
            </button>
          </view>

          <view
            v-else-if="content.cityClosed"
            class="catalog-state"
            data-testid="catalog-city-closed"
          >
            <LineIcon name="pin" :size="42" color="#b8bdc4" />
            <text class="catalog-state__title">
              {{ displayCityName }}暂未开通巡检服务
            </text>
            <text class="catalog-state__description">
              可以切换到已开通的城市查看
            </text>
            <button class="catalog-state__action" @click="openCitySheet">
              切换城市
            </button>
          </view>

          <view
            v-else-if="content.services.length === 0"
            class="catalog-state"
            data-testid="catalog-empty"
          >
            <LineIcon name="search" :size="42" color="#b8bdc4" />
            <text class="catalog-state__title">
              没有找到相关服务
            </text>
            <text class="catalog-state__description">
              请更换关键词、分类或筛选条件
            </text>
            <button
              class="catalog-state__action"
              data-testid="catalog-clear"
              @click="clearConditions"
            >
              清空条件
            </button>
          </view>

          <template v-else>
            <InspectionServiceCard
              v-for="service in content.services"
              :key="service.id"
              :service="service"
              :show-distance="query.sort === 'DISTANCE_ASC'"
              @detail="openServiceDetail"
            />
            <view class="inspection-catalog__more" data-testid="catalog-list-more">
              <text v-if="isLoadingMore" class="inspection-catalog__end">
                正在加载…
              </text>
              <button
                v-else-if="loadMoreFailed"
                class="inspection-catalog__more-action"
                data-testid="catalog-more-retry"
                @click="loadMore"
              >
                加载失败，点此重试
              </button>
              <!-- Tappable on purpose: a first page shorter than the screen cannot be scrolled,
                   so scrolling alone would never ask for the next one. -->
              <button
                v-else-if="content.hasMore"
                class="inspection-catalog__more-action"
                data-testid="catalog-more"
                @click="loadMore"
              >
                上拉或点此加载更多
              </button>
              <text v-else class="inspection-catalog__end">
                没有更多服务了
              </text>
            </view>
          </template>
        </main>
      </scroll-view>
    </view>

    <CatalogFilterSheet
      v-if="filterSheetOpen"
      :model-value="filters"
      :methods="filterOptions.serviceMethods"
      :show-report="filterOptions.supportsReportFilter"
      @close="filterSheetOpen = false"
      @apply="applyFilters"
      @reset="resetFilters"
    />
    <CitySelectionSheet
      v-if="citySheetOpen"
      :directory="cityDirectory"
      :current-city="currentCity"
      :recent-cities="recentCities"
      :loading="cityDirectoryLoading"
      :error="cityDirectoryError"
      :relocating="relocating"
      @close="catalogCityController.closeCitySheet"
      @retry="loadCityDirectory"
      @relocate="relocateCity"
      @select="applyCitySelection"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspCatalog (390 wide). */
.inspection-catalog {
  height: 100vh;
  color: #202c42;
  background: #f5f7fc;
  box-sizing: border-box;
}

/* A column: header and toolbar take what they need, the list scrolls in the rest. A fixed
   header height used to be subtracted here and left the page 47px taller than the screen. */
.inspection-catalog__surface {
  display: flex;
  width: 100%;
  max-width: 600px;
  height: 100vh;
  margin: 0 auto;
  flex-direction: column;
  background: #f5f7fc;
}

.inspection-catalog__scroll {
  width: 100%;
  height: 0;
  min-height: 0;
  flex: 1 1 0;
  background: #f5f7fc;
}

.inspection-catalog__content {
  display: flex;
  min-height: 100%;
  padding: 10px 16px calc(16px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 10px;
  background: #f5f7fc;
  box-sizing: border-box;
}

.catalog-skeleton {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.catalog-skeleton__card {
  display: grid;
  overflow: hidden;
  min-height: 118px;
  grid-template-columns: 118px minmax(0, 1fr);
  border-radius: 14px;
  background: #ffffff;
}

.catalog-skeleton__image,
.catalog-skeleton__line {
  background: linear-gradient(
    100deg,
    #eceff2 20%,
    #f8f9fa 40%,
    #eceff2 60%
  );
  background-size: 200% 100%;
  animation: catalog-shimmer 1.2s ease-in-out infinite;
}

.catalog-skeleton__copy {
  padding: 14px 12px;
}

.catalog-skeleton__line {
  height: 12px;
  margin-top: 10px;
  border-radius: 6px;
}

.catalog-skeleton__line--title {
  width: 78%;
  height: 18px;
  margin-top: 0;
}

.catalog-skeleton__line--short {
  width: 56%;
}

.catalog-state {
  display: flex;
  min-height: 360px;
  padding: 48px 24px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  box-sizing: border-box;
  text-align: center;
}

.catalog-state__title {
  margin-top: 14px;
  color: #353940;
  font-size: 18px;
  font-weight: 700;
}

.catalog-state__description {
  margin-top: 7px;
  color: #878d95;
  font-size: 13px;
  line-height: 1.5;
}

.catalog-state__action {
  min-width: 112px;
  height: 44px;
  margin-top: 18px;
  border: 0;
  border-radius: 23px;
  color: #ffffff;
  background: #ff5a00;
  font-size: 14px;
  font-weight: 650;
}

.catalog-state__action::after {
  border: 0;
}

.inspection-catalog__more {
  display: flex;
  justify-content: center;
}

.inspection-catalog__more-action {
  min-height: 88rpx;
  margin: 0;
  padding: 0 32rpx;
  border: 0;
  background: transparent;
  color: #777d85;
  font-size: 24rpx;
  line-height: 88rpx;
}

.inspection-catalog__more-action::after {
  border: 0;
}

.inspection-catalog__end {
  display: block;
  padding: 9px 0 4px;
  color: #a1a6ad;
  font-size: 11px;
  text-align: center;
}

.inspection-catalog__scroll :deep(.uni-scroll-view::-webkit-scrollbar) {
  display: none;
}

@keyframes catalog-shimmer {
  0% {
    background-position: 120% 0;
  }

  100% {
    background-position: -120% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .catalog-skeleton__image,
  .catalog-skeleton__line {
    animation: none;
  }
}
</style>
