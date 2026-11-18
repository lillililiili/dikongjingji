<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import {
  createPagedListLoader,
  emptyPagedList,
  type PagedListState,
} from '@/shared/list/paged-list-loader'

import {
  EMPTY_MALL_FILTERS,
  cloneMallFilters,
  countMallFilters,
  describeMallFilters,
  removeMallFilterChip,
} from '../../application/mall-filters'
import { mallApi } from '../../data/mall-api-client'
import {
  MALL_PAGE_SIZE,
  type MallCategory,
  type MallFilters,
  type MallProductCard,
  type MallProductQuery,
  type MallProductSort,
} from '../../domain/mall'
import { resolveMallPreviewCover } from '../../data/preview/mall-preview-covers'
import { buildMallProductUrl } from '../../navigation/mall-routes'
import MallFilterSheet from './MallFilterSheet.vue'
import {
  MALL_ACTION_LABELS,
  MALL_BADGE_LABELS,
  MALL_CATEGORY_LABELS,
  formatMallCardPrice,
  formatMallSellers,
} from '../shared/mall-format'

const props = defineProps<{
  // False while another home channel is showing; the component stays mounted to keep its list.
  active: boolean
  cityCode: string
  // A category asked for from outside (a home king entry). The token changes on every request so
  // asking for the same category twice still selects it.
  requestedCategory: { category: MallCategory; token: number } | null
  // A search submitted in the home search box while this channel is showing; an empty keyword
  // ends the search. The token makes a repeated keyword count.
  requestedKeyword: { keyword: string; token: number } | null
}>()

const emit = defineEmits<{
  // The channel ended or changed the search itself, so the home search box must follow.
  keywordChange: [keyword: string]
}>()

const CATEGORY_TABS: readonly { code: MallCategory | null; label: string }[] = [
  { code: null, label: '全部' },
  { code: 'DRONE', label: MALL_CATEGORY_LABELS.DRONE },
  { code: 'ROBOT', label: MALL_CATEGORY_LABELS.ROBOT },
  { code: 'PARTS', label: MALL_CATEGORY_LABELS.PARTS },
  { code: 'RENTAL', label: MALL_CATEGORY_LABELS.RENTAL },
  { code: 'COURSE', label: MALL_CATEGORY_LABELS.COURSE },
]

const selectedCategory = ref<MallCategory | null>(props.requestedCategory?.category ?? null)
const sort = ref<MallProductSort>('DEFAULT')
const keyword = ref(props.requestedKeyword?.keyword.trim() ?? '')
const filters = ref<MallFilters>(cloneMallFilters(EMPTY_MALL_FILTERS))
const filterSheetOpen = ref(false)
const list = ref<PagedListState<MallProductCard>>(emptyPagedList())

const filterCount = computed(() => countMallFilters(filters.value))
// The search word first, then one chip per chosen filter; each can be removed on its own.
const chips = computed(() => [
  ...(keyword.value ? [{ key: 'keyword', label: `“${keyword.value}”` }] : []),
  ...describeMallFilters(filters.value),
])

const loader = createPagedListLoader<MallProductQuery, MallProductCard>({
  loadPage: async (query, page, size) => mallApi.loadProducts(query, page, size),
  getState: () => list.value,
  setState: (state) => { list.value = state },
  keyOf: (product) => product.productId,
  pageSize: MALL_PAGE_SIZE,
})

function currentQuery(): MallProductQuery {
  return {
    cityCode: props.cityCode,
    category: selectedCategory.value,
    sort: sort.value,
    keyword: keyword.value,
    filters: filters.value,
  }
}

function reload(): void {
  void loader.reload(currentQuery())
}

function endSearch(): void {
  if (!keyword.value) return
  keyword.value = ''
  emit('keywordChange', '')
}

function removeChip(chipKey: string): void {
  if (chipKey === 'keyword') endSearch()
  else filters.value = removeMallFilterChip(filters.value, chipKey)
  reload()
}

function clearSearchAndFilters(): void {
  endSearch()
  filters.value = cloneMallFilters(EMPTY_MALL_FILTERS)
  reload()
}

function applyFilters(next: MallFilters): void {
  filters.value = next
  filterSheetOpen.value = false
  reload()
}

// What the sheet's confirm button would show for a draft, with the category and search as they are.
async function countProducts(draft: MallFilters): Promise<number> {
  return (await mallApi.loadProducts({ ...currentQuery(), filters: draft }, 1, 1)).total
}

function selectCategory(category: MallCategory | null): void {
  if (selectedCategory.value === category) return
  selectedCategory.value = category
  reload()
}

function selectDefaultSort(): void {
  if (sort.value === 'DEFAULT') return
  sort.value = 'DEFAULT'
  reload()
}

// Price cycles: low to high, high to low, then back to low to high.
function togglePriceSort(): void {
  sort.value = sort.value === 'PRICE_ASC' ? 'PRICE_DESC' : 'PRICE_ASC'
  reload()
}

// The server's picture when it has one, else the sample picture of a preview build.
function coverOf(product: MallProductCard): { url: string; cutout: boolean } | null {
  return product.coverImage
    ? { url: product.coverImage.url, cutout: false }
    : resolveMallPreviewCover(product.productId)
}

function openProduct(product: MallProductCard): void {
  uni.navigateTo({ url: buildMallProductUrl(product.productId, props.cityCode) })
}

// Prices and sellers change while the user is elsewhere, so every return reads the list again.
watch(() => props.active, (active) => {
  if (active) reload()
}, { immediate: true })

watch(() => props.cityCode, () => {
  if (props.active) reload()
})

watch(() => props.requestedCategory, (requested) => {
  if (!requested) return
  selectedCategory.value = requested.category
  if (props.active) reload()
})

watch(() => props.requestedKeyword, (requested) => {
  if (!requested) return
  const next = requested.keyword.trim()
  if (next === keyword.value) return
  keyword.value = next
  if (props.active) reload()
})

// The home page owns the scroll and forwards its reach-bottom event (see ActivityChannel).
defineExpose({
  loadMore: (): Promise<void> => loader.loadMore(),
})
</script>

<template>
  <view class="mall-channel" data-testid="mall-channel">
    <scroll-view class="mall-channel__categories" scroll-x :show-scrollbar="false">
      <view class="mall-channel__category-track">
        <button
          v-for="tab in CATEGORY_TABS"
          :key="tab.label"
          :class="[
            'mall-channel__category',
            { 'mall-channel__category--active': tab.code === selectedCategory },
          ]"
          :data-testid="`mall-category-${(tab.code ?? 'all').toLowerCase()}`"
          :aria-pressed="tab.code === selectedCategory"
          @click="selectCategory(tab.code)"
        >
          {{ tab.label }}
        </button>
      </view>
    </scroll-view>

    <view class="mall-channel__sorts">
      <button
        :class="['mall-channel__sort', { 'mall-channel__sort--active': sort === 'DEFAULT' }]"
        data-testid="mall-sort-default"
        :aria-pressed="sort === 'DEFAULT'"
        @click="selectDefaultSort"
      >
        综合 ▾
      </button>
      <button
        :class="['mall-channel__sort', { 'mall-channel__sort--active': sort !== 'DEFAULT' }]"
        data-testid="mall-sort-price"
        :data-sort="sort"
        :aria-label="sort === 'PRICE_ASC' ? '价格排序：从低到高' : sort === 'PRICE_DESC' ? '价格排序：从高到低' : '价格排序'"
        @click="togglePriceSort"
      >
        价格 {{ sort === 'PRICE_ASC' ? '↑' : sort === 'PRICE_DESC' ? '↓' : '⇅' }}
      </button>
      <button
        :class="['mall-channel__filter', { 'mall-channel__filter--active': filterCount > 0 }]"
        data-testid="mall-filter-open"
        :aria-label="filterCount > 0 ? `筛选，已选 ${filterCount} 项` : '筛选'"
        @click="filterSheetOpen = true"
      >
        <UniIcons type="settings" size="16" :color="filterCount > 0 ? '#ba470b' : '#536178'" />
        <text>筛选{{ filterCount > 0 ? ` ${filterCount}` : '' }}</text>
      </button>
    </view>

    <scroll-view
      v-if="chips.length"
      class="mall-channel__chips"
      data-testid="mall-chips"
      scroll-x
      :show-scrollbar="false"
    >
      <view class="mall-channel__chip-track">
        <button
          v-for="chip in chips"
          :key="chip.key"
          class="mall-channel__chip"
          :data-testid="`mall-chip-${chip.key.replace(':', '-').toLowerCase()}`"
          :aria-label="`移除 ${chip.label}`"
          @click="removeChip(chip.key)"
        >
          <text>{{ chip.label }}</text>
          <UniIcons type="closeempty" size="12" color="#ba470b" />
        </button>
      </view>
    </scroll-view>

    <view
      v-if="list.status === 'loading' || list.status === 'idle'"
      class="mall-channel__grid"
      data-testid="mall-list-loading"
      aria-label="正在加载"
    >
      <view v-for="index in 4" :key="index" class="mall-channel__skeleton" />
    </view>

    <view v-else-if="list.status === 'error'" class="mall-channel__state" data-testid="mall-list-error">
      <text class="mall-channel__state-title">商品加载失败，请稍后重试</text>
      <button class="mall-channel__state-action" data-testid="mall-list-retry" @click="reload">
        重新加载
      </button>
    </view>

    <view
      v-else-if="list.items.length === 0 && chips.length"
      class="mall-channel__state"
      data-testid="mall-search-empty"
    >
      <text class="mall-channel__state-title">
        {{ keyword ? `没有找到“${keyword}”` : '没有符合筛选条件的商品' }}
      </text>
      <text class="mall-channel__state-copy">换个关键词或筛选条件试试</text>
      <button class="mall-channel__state-action" data-testid="mall-clear-all" @click="clearSearchAndFilters">
        清除搜索和筛选
      </button>
    </view>

    <view v-else-if="list.items.length === 0" class="mall-channel__state" data-testid="mall-list-empty">
      <text class="mall-channel__state-title">
        {{ selectedCategory ? `暂无${MALL_CATEGORY_LABELS[selectedCategory]}商品` : '当前城市暂无在售商品' }}
      </text>
      <button
        v-if="selectedCategory"
        class="mall-channel__state-action"
        data-testid="mall-show-all"
        @click="selectCategory(null)"
      >
        看全部商品
      </button>
    </view>

    <template v-else>
      <view class="mall-channel__grid">
        <button
          v-for="product in list.items"
          :key="product.productId"
          class="mall-card"
          :data-testid="`mall-product-${product.productId}`"
          :data-sale-mode="product.saleMode"
          @click="openProduct(product)"
        >
          <view
            :class="[
              'mall-card__cover',
              { 'mall-card__cover--cutout': coverOf(product)?.cutout },
            ]"
          >
            <image
              v-if="coverOf(product)"
              class="mall-card__image"
              :src="coverOf(product)?.url"
              :alt="product.name"
              :mode="coverOf(product)?.cutout ? 'aspectFit' : 'aspectFill'"
              lazy-load
            />
            <text v-else class="mall-card__cover-label">{{ MALL_CATEGORY_LABELS[product.category] }}</text>
            <text
              v-if="product.experienceBadge"
              :class="[
                'mall-card__badge',
                `mall-card__badge--${product.experienceBadge.toLowerCase()}`,
              ]"
            >
              {{ MALL_BADGE_LABELS[product.experienceBadge] }}
            </text>
            <text v-if="product.saleMode === 'RENT'" class="mall-card__rent">租赁</text>
          </view>
          <view class="mall-card__body">
            <text class="mall-card__name">{{ product.name }}</text>
            <view class="mall-card__price">
              <text class="mall-card__amount">{{ formatMallCardPrice(product).amount }}</text>
              <text v-if="formatMallCardPrice(product).unit" class="mall-card__unit mall-card__unit--per">
                {{ formatMallCardPrice(product).unit }}
              </text>
              <text v-if="formatMallCardPrice(product).from" class="mall-card__unit">
                {{ formatMallCardPrice(product).from }}
              </text>
              <text class="mall-card__sellers">{{ formatMallSellers(product) }}</text>
            </view>
            <view class="mall-card__footer">
              <text
                :class="[
                  'mall-card__action',
                  { 'mall-card__action--inquiry': product.saleMode !== 'BUY' },
                ]"
              >
                {{ MALL_ACTION_LABELS[product.saleMode] }}
              </text>
            </view>
          </view>
        </button>
      </view>

      <view class="mall-channel__more" data-testid="mall-list-more">
        <text v-if="list.loadingMore">正在加载…</text>
        <button
          v-else-if="list.loadMoreFailed"
          class="mall-channel__more-retry"
          data-testid="mall-more-retry"
          @click="loader.loadMore()"
        >
          加载失败，点此重试
        </button>
        <text v-else-if="!list.hasMore">没有更多商品了</text>
      </view>
    </template>

    <MallFilterSheet
      v-if="filterSheetOpen"
      :filters="filters"
      :count-products="countProducts"
      @close="filterSheetOpen = false"
      @apply="applyFilters"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board MallHome (390 wide). */
.mall-channel {
  padding: 4px var(--app-page-horizontal-gutter) 0;
  color: #202c42;
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

.mall-channel__categories {
  width: 100%;
  white-space: nowrap;
}

.mall-channel__category-track {
  display: inline-flex;
  gap: 6px;
  /* The pill is 30 high; the padding makes the tap target 44. */
  padding: 0;
}

.mall-channel__category {
  position: relative;
  display: inline-flex;
  height: 44px;
  padding: 0 12px;
  flex: 0 0 auto;
  align-items: center;
  color: #202c42;
  font-size: 13px;
  z-index: 0;
}

.mall-channel__category::before {
  position: absolute;
  top: 7px;
  right: 0;
  bottom: 7px;
  left: 0;
  border-radius: 999px;
  background: #ffffff;
  content: '';
  z-index: -1;
}

.mall-channel__category--active {
  color: #ffffff;
  font-weight: 500;
}

.mall-channel__category--active::before {
  background: #ff6a00;
}

.mall-channel__sorts {
  display: flex;
  gap: 20px;
  padding: 0 0 4px;
}

.mall-channel__sort {
  display: flex;
  min-width: 44px;
  height: 40px;
  align-items: center;
  color: #536178;
  font-size: 14px;
  white-space: nowrap;
}

.mall-channel__sort--active {
  color: #ba470b;
  font-weight: 500;
}

.mall-channel__filter {
  display: flex;
  height: 40px;
  margin-left: auto;
  align-items: center;
  gap: 4px;
  color: #536178;
  font-size: 14px;
  white-space: nowrap;
}

.mall-channel__filter--active {
  color: #ba470b;
  font-weight: 500;
}

.mall-channel__chips {
  width: 100%;
  margin-bottom: 8px;
  white-space: nowrap;
}

.mall-channel__chip-track {
  display: inline-flex;
  gap: 8px;
}

.mall-channel__chip {
  position: relative;
  display: inline-flex;
  height: 28px;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
  padding: 0 8px 0 10px;
  border: 1px solid #ffd9bf;
  border-radius: 999px;
  color: #ba470b;
  background: #fffaf5;
  font-size: 12px;
  line-height: 26px;
  box-sizing: border-box;
}

/* Extends the 28px chip to the 44px touch minimum without changing its look. */
.mall-channel__chip::before {
  position: absolute;
  top: -8px;
  right: 0;
  bottom: -8px;
  left: 0;
  content: "";
}

.mall-channel__state-copy {
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.mall-channel__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.mall-channel__skeleton {
  height: 214px;
  border-radius: 12px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
  background-size: 400% 100%;
  animation: mall-skeleton 1.25s ease infinite;
}

@keyframes mall-skeleton {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}

.mall-channel__state {
  display: flex;
  min-height: 260px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 12px;
  background: #ffffff;
}

.mall-channel__state-title {
  color: #536178;
  font-size: 15px;
  font-weight: 600;
}

.mall-channel__state-action {
  display: flex;
  min-width: 108px;
  min-height: 44px;
  margin-top: 8px;
  padding: 0 18px;
  align-items: center;
  justify-content: center;
  border-radius: 22px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
}

.mall-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
  box-sizing: border-box;
}

.mall-card__cover {
  position: relative;
  display: flex;
  width: 100%;
  height: 104px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #eef0f3;
}

.mall-card__cover--cutout {
  background: #f0edff;
}

.mall-card__image {
  width: 100%;
  height: 100%;
}

.mall-card__cover-label {
  color: #738096;
  font-size: 12px;
}

.mall-card__badge {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 1px 6px;
  border-radius: 6px;
  color: #ffffff;
  background: #159a70;
  font-size: 11px;
  line-height: 16px;
}

.mall-card__badge--venue_display {
  background: #2f80ed;
}

.mall-card__rent {
  position: absolute;
  top: 0;
  right: 0;
  padding: 0 9px;
  border-radius: 0 0 0 10px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 12px;
  font-weight: 500;
  line-height: 22px;
  white-space: nowrap;
}

.mall-card__body {
  display: flex;
  padding: 8px 10px 2px;
  flex-direction: column;
  gap: 4px;
}

.mall-card__name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mall-card__price {
  display: flex;
  height: 24px;
  align-items: baseline;
  gap: 3px;
  white-space: nowrap;
}

.mall-card__amount {
  color: #e85400;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.mall-card__unit {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.mall-card__unit--per {
  margin-left: -3px;
  color: #e85400;
  font-size: 13px;
  font-weight: 500;
}

.mall-card__sellers {
  min-width: 0;
  margin-left: auto;
  flex: 0 1 auto;
  overflow: hidden;
  white-space: nowrap;
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
}

.mall-card__footer {
  display: flex;
  height: 44px;
  align-items: center;
}

.mall-card__action {
  display: flex;
  height: 30px;
  padding: 0 12px;
  align-items: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.mall-card__action--inquiry {
  color: #ba470b;
  background: #fff0e3;
  box-shadow: none;
}

.mall-channel__more {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  color: #738096;
  font-size: 12px;
}

.mall-channel__more-retry {
  min-height: 44px;
  padding: 0 16px;
  color: #ba470b;
  font-size: 13px;
}
</style>
