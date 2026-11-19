<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref } from 'vue'

import { platformCityApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import CitySelectionSheet from '@/shared/city/presentation/CitySelectionSheet.vue'
import { useCitySheet } from '@/shared/city/presentation/use-city-sheet'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import {
  createPagedListLoader,
  emptyPagedList,
  type PagedListState,
} from '@/shared/list/paged-list-loader'

import {
  PRO_SERVICE_TYPE_INFO,
  closedCityText,
  nextPriceSort,
  shortCityName,
} from '../../application/proservice-view'
import { proServiceApi } from '../../data/proservice-api-client'
import {
  PRO_SERVICE_PAGE_SIZE,
  PRO_SERVICE_TYPES,
  type ProServiceCard as ProServiceCardModel,
  type ProServiceFacetGroup,
  type ProServiceQuery,
  type ProServiceSort,
  type ProServiceType,
} from '../../domain/proservice'
import type { ProServiceCatalogTarget } from '../../navigation/proservice-routes'
import ProServiceCard from '../shared/ProServiceCard.vue'
import ProServiceFilterSheet, { type ProServiceFilters } from './ProServiceFilterSheet.vue'

const props = defineProps<{
  target: ProServiceCatalogTarget
}>()

const emit = defineEmits<{
  back: []
  openService: [serviceId: string, cityCode: string]
  publish: [type: ProServiceType]
}>()

interface CatalogMeta {
  cityName: string | null
  cityOpen: boolean
  facetGroup: ProServiceFacetGroup
}

const currentCityStore = useCurrentCityStore()
const type = ref<ProServiceType>(props.target.type)
const keywordText = ref(props.target.keyword)
const keyword = ref(props.target.keyword)
const filters = ref<ProServiceFilters>({ facet: null, priceMode: null })
const sort = ref<ProServiceSort>('DEFAULT')
const cityCode = ref(resolveStartCity())
const list = ref<PagedListState<ProServiceCardModel>>(emptyPagedList())
// Read from the latest first page: whether the city has the type at all, and its filter group.
const meta = ref<CatalogMeta | null>(null)
const filterOpen = ref(false)
let latestQuery: ProServiceQuery | null = null

// The route's city first (the channel passes the App's city), then the App's, then the default.
function resolveStartCity(): string {
  if (props.target.cityCode) return props.target.cityCode
  if (currentCityStore.currentCity) return currentCityStore.currentCity.code
  return import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim() || '370500'
}

const loader = createPagedListLoader<ProServiceQuery, ProServiceCardModel>({
  async loadPage(query, page, size) {
    const result = await proServiceApi.listServices(query, page, size)
    // Only the newest query may set the city state; an older answer is dropped by the loader too.
    if (page === 1 && query === latestQuery) {
      meta.value = { cityName: result.cityName, cityOpen: result.cityOpen, facetGroup: result.facetGroup }
    }
    return result
  },
  getState: () => list.value,
  setState: (state) => { list.value = state },
  keyOf: (service) => service.serviceId,
  pageSize: PRO_SERVICE_PAGE_SIZE,
})

const citySheet = useCitySheet(platformCityApi, () => {
  const city = currentCityStore.currentCity
  if (!city || city.code === cityCode.value) return
  cityCode.value = city.code
  meta.value = null
  reload()
})
const {
  isOpen: citySheetOpen,
  directory: cityDirectory,
  isLoading: cityDirectoryLoading,
  error: cityDirectoryError,
  recentCities,
  isRelocating,
} = citySheet

const info = computed(() => PRO_SERVICE_TYPE_INFO[type.value])
const cityLabel = computed(() => {
  const fromServer = meta.value?.cityName
  if (fromServer) return shortCityName(fromServer)
  const current = currentCityStore.currentCity
  return current?.code === cityCode.value ? shortCityName(current.name) : '选择城市'
})
const hasFilter = computed(() => filters.value.facet !== null || filters.value.priceMode !== null)
const pricing = computed(() => sort.value === 'PRICE_ASC' || sort.value === 'PRICE_DESC')
const priceAria = computed(() => {
  if (sort.value === 'PRICE_ASC') return '价格，当前从低到高'
  if (sort.value === 'PRICE_DESC') return '价格，当前从高到低'
  return '价格'
})
const view = computed<'loading' | 'error' | 'closed' | 'empty' | 'list'>(() => {
  const status = list.value.status
  if (status === 'idle' || status === 'loading') return 'loading'
  if (status === 'error') return 'error'
  if (meta.value && !meta.value.cityOpen) return 'closed'
  return list.value.items.length ? 'list' : 'empty'
})

function reload(): void {
  const query: ProServiceQuery = {
    type: type.value,
    cityCode: cityCode.value,
    keyword: keyword.value,
    facet: filters.value.facet,
    priceMode: filters.value.priceMode,
    sort: sort.value,
  }
  latestQuery = query
  void loader.reload(query)
}

function loadMore(): void {
  void loader.loadMore()
}

// Filters belong to a type (成果类型 for aerial, 服务方式 for maintenance), so they go with it.
function selectType(next: ProServiceType): void {
  if (next === type.value) return
  type.value = next
  filters.value = { facet: null, priceMode: null }
  meta.value = null
  reload()
}

function selectDefaultSort(): void {
  if (sort.value === 'DEFAULT') return
  sort.value = 'DEFAULT'
  reload()
}

function selectPriceSort(): void {
  sort.value = nextPriceSort(sort.value)
  reload()
}

function readValue(event: Event): string {
  return (event as Event & { detail?: { value?: string } }).detail?.value?.toString() ?? ''
}

// uni's H5 input reports at most every 100 ms, so the search takes the confirm event's own value.
function submitKeyword(event: Event): void {
  const value = readValue(event).trim().slice(0, 40)
  keywordText.value = value
  if (value === keyword.value) return
  keyword.value = value
  reload()
}

function clearKeyword(): void {
  keywordText.value = ''
  if (!keyword.value) return
  keyword.value = ''
  reload()
}

function applyFilters(next: ProServiceFilters): void {
  filterOpen.value = false
  filters.value = next
  reload()
}

function clearAll(): void {
  keywordText.value = ''
  keyword.value = ''
  filters.value = { facet: null, priceMode: null }
  reload()
}

function openService(serviceId: string): void {
  emit('openService', serviceId, cityCode.value)
}

function showCustomerService(): void {
  uni.showToast({ title: '客服能力由平台统一接入', icon: 'none' })
}

defineExpose({ loadMore })

reload()
</script>

<template>
  <view class="pro-catalog" data-testid="pro-catalog" :data-type="type" :data-sort="sort">
    <view class="pro-catalog__top">
      <view class="pro-catalog__header">
        <button class="pro-catalog__back" data-testid="pro-catalog-back" aria-label="返回" @click="emit('back')">
          <UniIcons type="left" size="22" color="#202c42" />
        </button>
        <text class="pro-catalog__title">服务目录</text>
        <button class="pro-catalog__service" data-testid="pro-catalog-customer-service" @click="showCustomerService">
          <view class="pro-catalog__service-pill">
            <UniIcons type="headphones" size="15" color="#202c42" />
            <text>客服</text>
          </view>
        </button>
      </view>

      <view class="pro-catalog__search-row">
        <view class="pro-catalog__search">
          <UniIcons type="search" size="18" color="#738096" />
          <input
            class="pro-catalog__input"
            data-testid="pro-catalog-search"
            type="text"
            confirm-type="search"
            :value="keywordText"
            :placeholder="info.searchPlaceholder"
            :maxlength="40"
            @input="keywordText = readValue($event)"
            @confirm="submitKeyword"
          >
          <button
            v-if="keywordText"
            class="pro-catalog__clear"
            data-testid="pro-catalog-search-clear"
            aria-label="清除搜索词"
            @click="clearKeyword"
          >
            <UniIcons type="clear" size="18" color="#91a0b4" />
          </button>
        </view>
        <button
          class="pro-catalog__filter"
          data-testid="pro-catalog-filter"
          aria-haspopup="dialog"
          :disabled="!meta"
          @click="filterOpen = true"
        >
          <view class="pro-catalog__filter-icon">
            <UniIcons type="tune" size="18" color="#202c42" />
            <view v-if="hasFilter" class="pro-catalog__filter-dot" data-testid="pro-catalog-filter-dot" aria-label="已设置筛选条件" />
          </view>
          <text>筛选</text>
        </button>
      </view>

      <view class="pro-catalog__types" role="group" aria-label="服务类型">
        <button
          v-for="code in PRO_SERVICE_TYPES"
          :key="code"
          class="pro-catalog__type"
          :data-testid="`pro-catalog-type-${code.toLowerCase()}`"
          :aria-pressed="code === type"
          @click="selectType(code)"
        >
          <text :class="['pro-catalog__type-pill', { 'pro-catalog__type-pill--on': code === type }]">
            {{ PRO_SERVICE_TYPE_INFO[code].label }}
          </text>
        </button>
      </view>

      <view class="pro-catalog__sorts">
        <button
          :class="['pro-catalog__sort', { 'pro-catalog__sort--on': sort === 'DEFAULT' }]"
          data-testid="pro-catalog-sort-default"
          :aria-pressed="sort === 'DEFAULT'"
          @click="selectDefaultSort"
        >
          综合排序
        </button>
        <view class="pro-catalog__sort-line" />
        <button
          :class="['pro-catalog__sort', { 'pro-catalog__sort--on': pricing }]"
          data-testid="pro-catalog-sort-price"
          :aria-pressed="pricing"
          :aria-label="priceAria"
          @click="selectPriceSort"
        >
          <text>价格</text>
          <view class="pro-catalog__arrows">
            <UniIcons type="top" size="9" :color="sort === 'PRICE_ASC' ? '#ba470b' : '#91a0b4'" />
            <UniIcons type="bottom" size="9" :color="sort === 'PRICE_DESC' ? '#ba470b' : '#91a0b4'" />
          </view>
        </button>
        <button
          class="pro-catalog__city"
          data-testid="pro-catalog-city"
          aria-haspopup="dialog"
          :aria-label="`切换城市，当前${cityLabel}`"
          @click="citySheet.open"
        >
          <UniIcons type="location" size="15" color="#202c42" />
          <text>{{ cityLabel }}</text>
          <UniIcons type="down" size="12" color="#738096" />
        </button>
      </view>
    </view>

    <view class="pro-catalog__list">
      <template v-if="view === 'loading'">
        <view v-for="index in 3" :key="index" class="pro-catalog__skeleton" data-testid="pro-catalog-loading" />
      </template>

      <view v-else-if="view === 'error'" class="pro-catalog__state" data-testid="pro-catalog-error">
        <UniIcons type="info" size="36" color="#b5bac1" />
        <text class="pro-catalog__state-title">服务加载失败</text>
        <text class="pro-catalog__state-copy">请检查网络后重试</text>
        <button class="pro-catalog__state-action" data-testid="pro-catalog-retry" @click="reload">重新加载</button>
      </view>

      <view v-else-if="view === 'closed'" class="pro-catalog__state" data-testid="pro-catalog-closed">
        <UniIcons type="location" size="36" color="#b5bac1" />
        <text class="pro-catalog__state-title">{{ closedCityText(meta?.cityName, type) }}</text>
        <button class="pro-catalog__state-action" data-testid="pro-catalog-closed-city" aria-haspopup="dialog" @click="citySheet.open">
          切换城市
        </button>
      </view>

      <view v-else-if="view === 'empty'" class="pro-catalog__state" data-testid="pro-catalog-empty">
        <UniIcons type="search" size="36" color="#b5bac1" />
        <text class="pro-catalog__state-title">没有找到相关服务</text>
        <text class="pro-catalog__state-copy">请更换关键词、分类或筛选条件</text>
        <button class="pro-catalog__state-action" data-testid="pro-catalog-clear-all" @click="clearAll">清空条件</button>
      </view>

      <template v-else>
        <ProServiceCard
          v-for="service in list.items"
          :key="service.serviceId"
          :service="service"
          @open="openService"
        />
        <view v-if="list.loadingMore" class="pro-catalog__more" data-testid="pro-catalog-loading-more">正在加载…</view>
        <button
          v-else-if="list.loadMoreFailed"
          class="pro-catalog__more pro-catalog__more--retry"
          data-testid="pro-catalog-more-retry"
          @click="loadMore"
        >
          加载失败，点此重试
        </button>
        <view v-else-if="!list.hasMore" class="pro-catalog__more" data-testid="pro-catalog-no-more">没有更多服务了</view>
      </template>
    </view>

    <view class="pro-catalog__bar">
      <view class="pro-catalog__bar-copy">
        <text class="pro-catalog__bar-title">非标需求？发布后多家报价</text>
        <text class="pro-catalog__bar-sub">报价版本留档，比选后再签约</text>
      </view>
      <button class="pro-catalog__publish" data-testid="pro-catalog-publish" @click="emit('publish', type)">发布需求</button>
    </view>

    <ProServiceFilterSheet
      v-if="filterOpen && meta"
      :group="meta.facetGroup"
      :filters="filters"
      @close="filterOpen = false"
      @apply="applyFilters"
    />

    <CitySelectionSheet
      v-if="citySheetOpen"
      :directory="cityDirectory"
      :current-city="currentCityStore.currentCity"
      :recent-cities="recentCities"
      :loading="cityDirectoryLoading"
      :error="cityDirectoryError"
      :relocating="isRelocating"
      @close="citySheet.close"
      @retry="citySheet.loadDirectory"
      @relocate="citySheet.relocate"
      @select="citySheet.select"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board SvcCatalog (390 wide). */
.pro-catalog {
  min-height: 100vh;
  padding-bottom: calc(80px + env(safe-area-inset-bottom));
  color: #202c42;
  background: #f5f7fc;
  box-sizing: border-box;
  font-family:
    "PingFang SC", "Noto Sans SC", system-ui, -apple-system, BlinkMacSystemFont,
    "Microsoft YaHei", sans-serif;
}

button {
  min-width: 0;
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

.pro-catalog__top {
  position: sticky;
  z-index: 10;
  top: 0;
  background: #ffffff;
}

/* The light gradient of every page head (as on the demand pages). */
.pro-catalog__header {
  display: flex;
  padding: calc(8px + var(--status-bar-height)) 16px 8px 8px;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
  align-items: center;
  gap: 4px;
}

.pro-catalog__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.pro-catalog__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  white-space: nowrap;
}

.pro-catalog__service {
  display: flex;
  height: 44px;
  margin-left: auto;
  flex: none;
  align-items: center;
}

.pro-catalog__service-pill {
  display: flex;
  height: 34px;
  padding: 0 12px;
  align-items: center;
  gap: 4px;
  border: 1px solid #e5ebf3;
  border-radius: 999px;
  color: #202c42;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
  box-sizing: border-box;
}

.pro-catalog__search-row {
  display: flex;
  padding: 0 8px 4px 16px;
  align-items: center;
  gap: 2px;
}

.pro-catalog__search {
  display: flex;
  height: 40px;
  min-width: 0;
  padding: 0 4px 0 12px;
  flex: 1 1 auto;
  align-items: center;
  gap: 8px;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  background: #ffffff;
  box-sizing: border-box;
}

.pro-catalog__input {
  height: 36px;
  min-width: 0;
  flex: 1 1 auto;
  color: #202c42;
  font-size: 14px;
}

.pro-catalog__clear {
  display: flex;
  width: 36px;
  height: 44px;
  margin: -2px 0;
  flex: none;
  align-items: center;
  justify-content: center;
}

.pro-catalog__filter {
  display: flex;
  height: 44px;
  padding: 0 8px;
  flex: none;
  align-items: center;
  gap: 1px;
  color: #202c42;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.pro-catalog__filter-icon {
  position: relative;
  display: flex;
  padding: 2px 3px 0 0;
}

.pro-catalog__filter-dot {
  position: absolute;
  top: 0;
  right: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ff6a00;
}

.pro-catalog__types {
  display: grid;
  padding: 0 16px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.pro-catalog__type {
  display: flex;
  height: 44px;
  align-items: center;
}

.pro-catalog__type-pill {
  display: flex;
  height: 32px;
  flex: 1 1 auto;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #202c42;
  background: #eef2f8;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.pro-catalog__type-pill--on {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.pro-catalog__sorts {
  display: flex;
  height: 44px;
  padding: 0 6px;
  align-items: center;
  border-bottom: 1px solid #f0f1f3;
}

.pro-catalog__sort {
  display: flex;
  height: 44px;
  padding: 0 10px;
  flex: none;
  align-items: center;
  gap: 3px;
  color: #536178;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.pro-catalog__sort--on {
  color: #ba470b;
  font-weight: 700;
}

.pro-catalog__sort-line {
  width: 1px;
  height: 14px;
  flex: none;
  background: #e5ebf3;
}

.pro-catalog__arrows {
  display: flex;
  flex-direction: column;
  line-height: 0;
}

.pro-catalog__city {
  display: flex;
  height: 44px;
  margin-left: auto;
  padding: 0 10px;
  flex: none;
  align-items: center;
  gap: 2px;
  color: #202c42;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.pro-catalog__list {
  display: flex;
  padding: 10px 16px 16px;
  flex-direction: column;
  gap: 10px;
}

.pro-catalog__skeleton {
  height: 129px;
  border-radius: 14px;
  background: linear-gradient(90deg, #ffffff, #f0f3f8, #ffffff);
}

.pro-catalog__state {
  display: flex;
  padding: 40px 16px;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  border-radius: 14px;
  background: #ffffff;
  text-align: center;
}

.pro-catalog__state-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.pro-catalog__state-copy {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.pro-catalog__state-action {
  height: 44px;
  margin-top: 8px;
  padding: 0 22px;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 14px;
  font-weight: 500;
  line-height: 41px;
  white-space: nowrap;
  box-sizing: border-box;
}

.pro-catalog__more {
  display: flex;
  height: 44px;
  align-items: center;
  justify-content: center;
  color: #738096;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.pro-catalog__more--retry {
  width: 100%;
  color: #ba470b;
}

.pro-catalog__bar {
  position: fixed;
  z-index: 20;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 12px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.pro-catalog__bar-copy {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 1px;
}

.pro-catalog__bar-title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pro-catalog__bar-sub {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pro-catalog__publish {
  height: 46px;
  padding: 0 20px;
  flex: none;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  white-space: nowrap;
}
</style>
