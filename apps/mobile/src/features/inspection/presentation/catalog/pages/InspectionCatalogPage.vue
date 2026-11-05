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
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { storeToRefs } from 'pinia'

import { createRecentCityStorage } from '@/shared/city/data/recent-city-storage'
import { locateCurrentCityName } from '@/shared/city/data/uni-city-location'
import type {
  CityDirectory,
  PlatformCity,
} from '@/shared/city/domain/city-directory'
import CitySelectionSheet from '@/shared/city/presentation/CitySelectionSheet.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

import {
  inspectionCatalogApi,
  platformCityApi,
} from '../../../data/service-flow/uni-booking-http-client'
import type {
  ActionResult,
  InspectionHostActionPort,
} from '../../../application/ports/inspection-host-action-port'
import CatalogFilterSheet, {
  type CatalogFilterValue,
} from '../components/CatalogFilterSheet.vue'
import CatalogHeader from '../components/CatalogHeader.vue'
import CatalogToolbar from '../components/CatalogToolbar.vue'
import InspectionServiceCard from '../components/InspectionServiceCard.vue'
import {
  DEFAULT_CATALOG_QUERY,
  EMPTY_CATALOG_CONTENT,
  applyCatalogCategory,
  applyCatalogSearch,
  applyCatalogSort,
  createOpenServiceDetailIntent,
  normalizeCatalogEntry,
  normalizeCatalogQuery,
  selectCatalogServices,
  type CatalogCategory,
  type CatalogSort,
  type InspectionCatalogContent,
  type InspectionCatalogService,
} from '../model/inspection-catalog'

interface CatalogRouteQuery {
  cityCode?: string
  categoryCode?: string
  keyword?: string
  sort?: string
  openFilter?: string
}

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
const filters = ref<CatalogFilterValue>({
  method: 'ALL',
  priceMode: 'ALL',
  includesReport: false,
})
const isLoading = ref(true)
const isRefreshing = ref(false)
const loadError = ref('')
const filterSheetOpen = ref(false)
const citySheetOpen = ref(false)
const cityDirectory = ref<CityDirectory | null>(null)
const cityDirectoryLoading = ref(false)
const cityDirectoryError = ref('')
const recentCities = ref(loadRecentCities())
const relocating = ref(false)

const displayCityName = computed(
  () => currentCity.value?.name ?? '选择城市',
)

const matchedServices = computed(() => {
  const selected = selectCatalogServices(content.value.services, query.value)

  return selected.filter((service) => {
    const methodMatches =
      filters.value.method === 'ALL' ||
      service.serviceMethods?.includes(filters.value.method)
    const priceMatches =
      filters.value.priceMode === 'ALL' ||
      (filters.value.priceMode === 'PROJECT_QUOTE'
        ? service.price.mode === 'PROJECT_QUOTE'
        : service.price.mode !== 'PROJECT_QUOTE')
    const reportMatches =
      !filters.value.includesReport || service.includesReport === true

    return methodMatches && priceMatches && reportMatches
  })
})

const visibleServices = computed(() =>
  matchedServices.value.slice(0, query.value.page * query.value.size),
)
const hasMore = computed(
  () => visibleServices.value.length < matchedServices.value.length,
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

const runLatestCatalogLoad = createLatestCatalogLoader({
  getCityCode: resolveCatalogCityCode,
  load: (cityCode) => inspectionCatalogApi.loadCatalog(
    cityCode,
    query.value.sort,
  ),
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
  setPage: (page) => {
    query.value = {
      ...query.value,
      page,
    }
  },
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
  const entry = normalizeCatalogEntry(routeQuery?.categoryCode)

  if (!entry.accepted) {
    uni.showToast({
      title: '该分类暂未开放电力巡检服务',
      icon: 'none',
      duration: 1000,
    })
    setTimeout(returnToPortal, 900)
    return
  }

  query.value = normalizeCatalogQuery({
    keyword: routeQuery?.keyword,
    category: entry.category,
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

async function loadCatalogContent(): Promise<void> {
  await runLatestCatalogLoad()
}

async function refreshCatalog(): Promise<void> {
  if (isRefreshing.value || isLoading.value) {
    return
  }

  isRefreshing.value = true
  query.value = {
    ...query.value,
    page: 1,
  }
  await loadCatalogContent()
}

function loadMore(): void {
  if (isLoading.value || isRefreshing.value || !hasMore.value) {
    return
  }

  query.value = {
    ...query.value,
    page: query.value.page + 1,
  }
}

function searchCatalog(): void {
  query.value = applyCatalogSearch(query.value, keywordInput.value)
  keywordInput.value = query.value.keyword
}

function selectCategory(category: CatalogCategory): void {
  query.value = applyCatalogCategory(query.value, category)
}

async function selectSort(sort: CatalogSort): Promise<void> {
  query.value = applyCatalogSort(query.value, sort)
  await loadCatalogContent()
}

function applyFilters(value: CatalogFilterValue): void {
  filters.value = value
  query.value = {
    ...query.value,
    page: 1,
  }
  filterSheetOpen.value = false
}

function resetFilters(): void {
  filters.value = {
    method: 'ALL',
    priceMode: 'ALL',
    includesReport: false,
  }
  query.value = {
    ...query.value,
    page: 1,
  }
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

function showCustomerService(): void {
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
        @search="searchCatalog"
        @filter="filterSheetOpen = true"
      />
      <CatalogToolbar
        :category="query.category"
        :sort="query.sort"
        :city-name="displayCityName"
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
            aria-label="正在加载电力巡检服务"
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
            <UniIcons
              type="info-filled"
              size="42"
              color="#b8bdc4"
            />
            <text class="catalog-state__title">暂时无法加载</text>
            <text class="catalog-state__description">
              {{ loadError }}
            </text>
            <button class="catalog-state__action" @click="loadCatalogContent">
              重新加载
            </button>
          </view>

          <view v-else-if="!currentCity" class="catalog-state">
            <UniIcons type="location" size="42" color="#b8bdc4" />
            <text class="catalog-state__title">请先选择城市</text>
            <text class="catalog-state__description">
              选择城市后查看当地的巡检服务
            </text>
            <button class="catalog-state__action" @click="openCitySheet">
              选择城市
            </button>
          </view>

          <view
            v-else-if="visibleServices.length === 0"
            class="catalog-state"
          >
            <UniIcons type="search" size="42" color="#b8bdc4" />
            <text class="catalog-state__title">
              没有找到相关服务
            </text>
            <text class="catalog-state__description">
              请更换关键词、分类或筛选条件
            </text>
            <button
              class="catalog-state__action"
              @click="
                keywordInput = '';
                query = { ...DEFAULT_CATALOG_QUERY };
                resetFilters()
              "
            >
              清空条件
            </button>
          </view>

          <template v-else>
            <InspectionServiceCard
              v-for="service in visibleServices"
              :key="service.id"
              :service="service"
              @detail="openServiceDetail"
            />
            <text
              v-if="!hasMore"
              class="inspection-catalog__end"
            >
              没有更多服务了
            </text>
          </template>
        </main>
      </scroll-view>
    </view>

    <CatalogFilterSheet
      v-if="filterSheetOpen"
      :model-value="filters"
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
.inspection-catalog {
  min-height: 100vh;
  color: #1e2126;
  background: #f7f8fa;
  box-sizing: border-box;
}

.inspection-catalog__surface {
  width: 100%;
  max-width: 600px;
  min-height: 100vh;
  margin: 0 auto;
  background: #ffffff;
}

.inspection-catalog__scroll {
  width: 100%;
  height: calc(100vh - var(--status-bar-height) - 121px);
  background: #f7f8fa;
}

.inspection-catalog__content {
  display: flex;
  min-height: 100%;
  padding:
    6px
    clamp(8px, 2.4vw, 14px)
    calc(18px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 6px;
  background: #f7f8fa;
  box-sizing: border-box;
}

.catalog-skeleton {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.catalog-skeleton__card {
  display: grid;
  overflow: hidden;
  min-height: 88px;
  grid-template-columns: clamp(112px, 34vw, 142px) minmax(0, 1fr);
  border-radius: 12px;
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
