<script setup lang="ts">
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

defineProps<{
  keyword: string
  /** True while a filter is in effect; the 筛选 button then carries a dot. */
  filterActive?: boolean
}>()

const emit = defineEmits<{
  back: []
  customerService: []
  'update:keyword': [value: string]
  search: [keyword: string]
  filter: []
}>()

function fieldValue(event: Event): string {
  const value = (
    event as Event & {
      detail?: {
        value?: string
      }
    }
  ).detail?.value

  return value?.toString() ?? ''
}

function handleKeywordInput(event: Event): void {
  emit('update:keyword', fieldValue(event))
}

// uni-app reports typing at most once every 100ms, so right after a keystroke the bound
// keyword can be one step behind the box. The confirm event carries what the box holds.
function handleKeywordConfirm(event: Event): void {
  const keyword = fieldValue(event)
  emit('update:keyword', keyword)
  emit('search', keyword)
}
</script>

<template>
  <!-- Board InspCatalog: the shared light page head with 客服 on the right and the search row below. -->
  <AppPageHeader class="catalog-header" title="巡检服务" test-id="catalog" @back="$emit('back')">
    <template #actions>
      <button
        class="catalog-header__service"
        aria-label="联系客服"
        @click="$emit('customerService')"
      >
        <LineIcon name="headset" :size="16" color="#202c42" />
        <text>客服</text>
      </button>
    </template>
    <template #bottom>
      <view class="catalog-header__search-row">
        <view class="catalog-search">
          <LineIcon name="search" :size="18" color="#627187" :stroke-width="2" />
          <input
            class="catalog-search__input"
            data-testid="catalog-search-input"
            type="text"
            confirm-type="search"
            maxlength="50"
            :value="keyword"
            placeholder="搜索巡检服务、场景或关键词"
            placeholder-class="catalog-search__placeholder"
            @input="handleKeywordInput"
            @confirm="handleKeywordConfirm"
          />
          <button
            v-if="keyword"
            class="catalog-search__clear"
            aria-label="清空搜索"
            @click="$emit('update:keyword', ''); $emit('search', '')"
          >
            <view class="catalog-search__clear-dot">
              <LineIcon name="close" :size="10" color="#ffffff" :stroke-width="3" />
            </view>
          </button>
        </view>
        <button
          class="catalog-header__filter"
          data-testid="catalog-filter-open"
          aria-label="打开筛选"
          @click="$emit('filter')"
        >
          <view class="catalog-header__filter-icon">
            <LineIcon name="filter" :size="18" color="#202c42" />
            <view
              v-if="filterActive"
              class="catalog-header__filter-dot"
              data-testid="catalog-filter-active"
              aria-label="已设置筛选条件"
            />
          </view>
          <text>筛选</text>
        </button>
      </view>
    </template>
  </AppPageHeader>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspCatalog (390 wide). */
.catalog-header__service,
.catalog-header__filter,
.catalog-search__clear {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  line-height: 1.2;
}

.catalog-header__service::after,
.catalog-header__filter::after,
.catalog-search__clear::after {
  border: 0;
}

/* A 34-high pill inside a 44-high tap target. */
.catalog-header__service {
  position: relative;
  display: flex;
  height: 44px;
  margin-right: 8px;
  padding: 0 12px;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
  color: #202c42;
  font-size: 13px;
  line-height: 18px;
  z-index: 0;
}

.catalog-header__service::before {
  position: absolute;
  top: 5px;
  right: 0;
  bottom: 5px;
  left: 0;
  border: 1px solid #e5ebf3;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.7);
  content: '';
  z-index: -1;
}

.catalog-header__search-row {
  display: flex;
  padding: 0 8px 4px 16px;
  align-items: center;
  gap: 2px;
}

.catalog-search {
  display: flex;
  min-width: 0;
  height: 40px;
  padding: 0 4px 0 12px;
  flex: 1;
  align-items: center;
  gap: 8px;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  background: #ffffff;
  box-sizing: border-box;
}

.catalog-search__input {
  min-width: 0;
  height: 36px;
  flex: 1;
  color: #202c42;
  font-size: 14px;
}

.catalog-search__placeholder {
  color: #738096;
}

.catalog-search__clear-dot {
  display: flex;
  width: 16px;
  height: 16px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #c9d3e1;
}

.catalog-search__clear {
  display: flex;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
}

.catalog-header__filter {
  display: flex;
  height: 44px;
  padding: 0 8px;
  flex: 0 0 auto;
  align-items: center;
  gap: 1px;
  color: #202c42;
  font-size: 14px;
  line-height: 20px;
}

.catalog-header__filter-icon {
  position: relative;
  display: flex;
  padding: 2px 3px 0 0;
}

.catalog-header__filter-dot {
  position: absolute;
  top: 0;
  right: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ff6a00;
}
</style>
