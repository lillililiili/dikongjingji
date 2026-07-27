<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { storeToRefs } from 'pinia'

import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

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
  resolveCatalogContent,
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

const DEVELOPMENT_CITIES = [
  { code: '370500', name: '东营市' },
  { code: '370100', name: '济南市' },
  { code: '370200', name: '青岛市' },
] as const

const currentCityStore = useCurrentCityStore()
const { currentCity } = storeToRefs(currentCityStore)
const content = ref<InspectionCatalogContent>(EMPTY_CATALOG_CONTENT)
const query = ref({ ...DEFAULT_CATALOG_QUERY })
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
const hasValidLocation = ref(false)
let loadRevision = 0

const displayCityName = computed(
  () =>
    currentCity.value?.name ??
    (import.meta.env.DEV ? '东营市' : '选择城市'),
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
        )}`,
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
  restoreDevelopmentCity(routeQuery?.cityCode)
  void loadCatalogContent()
})

async function loadCatalogContent(): Promise<void> {
  const revision = ++loadRevision
  isLoading.value = true
  loadError.value = ''

  try {
    if (import.meta.env.DEV) {
      const { DEVELOPMENT_POWER_CATALOG } = await import(
        '../fixtures/power-inspection-catalog.fixture'
      )

      if (revision === loadRevision) {
        content.value = resolveCatalogContent(
          'development',
          DEVELOPMENT_POWER_CATALOG,
        )
      }
    } else {
      content.value = EMPTY_CATALOG_CONTENT
    }
  } catch {
    if (revision === loadRevision) {
      content.value = EMPTY_CATALOG_CONTENT
      loadError.value = '巡检服务加载失败，请检查网络后重试'
    }
  } finally {
    if (revision === loadRevision) {
      isLoading.value = false
      isRefreshing.value = false
    }
  }
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
  if (sort !== 'DISTANCE_ASC' || hasValidLocation.value) {
    query.value = applyCatalogSort(
      query.value,
      sort,
      hasValidLocation.value,
    ).query
    return
  }

  await requestLocationForDistanceSort()
}

function requestLocationForDistanceSort(): Promise<void> {
  return new Promise((resolve) => {
    uni.getLocation({
      type: 'gcj02',
      success: (location) => {
        hasValidLocation.value =
          Number.isFinite(location.latitude) &&
          Number.isFinite(location.longitude)
        query.value = applyCatalogSort(
          query.value,
          'DISTANCE_ASC',
          hasValidLocation.value,
        ).query
        resolve()
      },
      fail: () => {
        const result = applyCatalogSort(
          query.value,
          'DISTANCE_ASC',
          false,
        )
        query.value = result.query
        uni.showToast({
          title: result.fallbackReason ?? '距离排序暂不可用',
          icon: 'none',
        })
        resolve()
      },
    })
  })
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

function selectCity(): void {
  if (!import.meta.env.DEV) {
    uni.showToast({
      title: '城市选择能力待平台接入',
      icon: 'none',
    })
    return
  }

  uni.showActionSheet({
    itemList: DEVELOPMENT_CITIES.map((city) => city.name),
    success: ({ tapIndex }) => {
      const city = DEVELOPMENT_CITIES[tapIndex]

      if (!city) {
        return
      }

      currentCityStore.setCurrentCity({ ...city })
      query.value = {
        ...query.value,
        page: 1,
      }
      loadRevision += 1
    },
  })
}

function restoreDevelopmentCity(cityCode: string | undefined): void {
  if (!import.meta.env.DEV || currentCity.value || !cityCode) {
    return
  }

  const city = DEVELOPMENT_CITIES.find((item) => item.code === cityCode)

  if (city) {
    currentCityStore.setCurrentCity({ ...city })
  }
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
        @city="selectCity"
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
    <RootAppTabBar active="SERVICE" />
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
  height: calc(
    100vh - env(safe-area-inset-top) - 121px -
      env(safe-area-inset-bottom)
  );
  background: #f7f8fa;
}

.inspection-catalog__content {
  display: flex;
  min-height: 100%;
  padding:
    6px
    clamp(8px, 2.4vw, 14px)
    calc(18px + 52px + env(safe-area-inset-bottom));
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
