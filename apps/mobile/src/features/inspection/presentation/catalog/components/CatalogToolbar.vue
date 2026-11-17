<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import {
  nextPriceSort,
  type CatalogCategoryOption,
  type CatalogSort,
} from '../model/inspection-catalog'

defineProps<{
  categories: readonly CatalogCategoryOption[]
  category: string
  sort: CatalogSort
  cityName: string
  /** Why the list is not sorted by distance although the customer asked for it; empty hides it. */
  distanceNote: string
}>()

defineEmits<{
  category: [value: string]
  sort: [value: CatalogSort]
  city: []
}>()
</script>

<template>
  <view class="catalog-toolbar">
    <scroll-view
      class="catalog-categories"
      scroll-x
      :show-scrollbar="false"
    >
      <view class="catalog-categories__track">
        <button
          v-for="item in categories"
          :key="item.code"
          class="catalog-categories__chip"
          :class="{
            'catalog-categories__chip--active':
              category === item.code,
          }"
          :data-testid="`catalog-category-${item.code.toLowerCase()}`"
          :aria-pressed="category === item.code ? 'true' : 'false'"
          @click="$emit('category', item.code)"
        >
          {{ item.label }}
        </button>
      </view>
    </scroll-view>

    <view class="catalog-sort">
      <button
        class="catalog-sort__item"
        :class="{
          'catalog-sort__item--active': sort === 'COMPREHENSIVE',
        }"
        data-testid="catalog-sort-comprehensive"
        :aria-pressed="sort === 'COMPREHENSIVE' ? 'true' : 'false'"
        @click="$emit('sort', 'COMPREHENSIVE')"
      >
        <text>综合排序</text>
      </button>
      <button
        class="catalog-sort__item"
        :class="{
          'catalog-sort__item--active': sort === 'DISTANCE_ASC',
        }"
        data-testid="catalog-sort-distance"
        :aria-pressed="sort === 'DISTANCE_ASC' ? 'true' : 'false'"
        @click="$emit('sort', 'DISTANCE_ASC')"
      >
        <text>距离最近</text>
      </button>
      <button
        class="catalog-sort__item"
        :class="{
          'catalog-sort__item--active':
            sort === 'PRICE_ASC' || sort === 'PRICE_DESC',
        }"
        data-testid="catalog-sort-price"
        :data-sort="sort"
        :aria-label="
          sort === 'PRICE_ASC'
            ? '价格，当前从低到高'
            : sort === 'PRICE_DESC'
              ? '价格，当前从高到低'
              : '价格'
        "
        @click="$emit('sort', nextPriceSort(sort))"
      >
        <text>价格</text>
        <UniIcons
          :type="sort === 'PRICE_ASC' ? 'arrow-up' : 'arrow-down'"
          size="11"
          color="currentColor"
        />
      </button>
      <button
        class="catalog-sort__item catalog-sort__item--city"
        data-testid="catalog-city-select"
        aria-label="切换服务城市"
        @click="$emit('city')"
      >
        <UniIcons type="location" size="14" color="#202c42" />
        <text>{{ cityName }}</text>
        <UniIcons type="down" size="10" color="#202c42" />
      </button>
    </view>
    <view v-if="distanceNote" class="catalog-toolbar__note" role="status">
      <UniIcons type="info" size="14" color="#ba470b" />
      <text data-testid="catalog-distance-note">{{ distanceNote }}</text>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspCatalog (390 wide). */
.catalog-toolbar {
  background: #ffffff;
}

.catalog-categories {
  width: 100%;
  white-space: nowrap;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.catalog-categories__track {
  display: inline-flex;
  padding: 0 16px;
  gap: 8px;
}

/* A 32-high pill inside a 44-high tap target. */
.catalog-categories__chip {
  position: relative;
  display: inline-flex;
  height: 44px;
  margin: 0;
  padding: 0 12px;
  flex: 0 0 auto;
  align-items: center;
  border: 0;
  color: #202c42;
  background: transparent;
  font-size: 14px;
  line-height: 20px;
  z-index: 0;
}

.catalog-categories__chip::after {
  border: 0;
}

.catalog-categories__chip::before {
  position: absolute;
  top: 6px;
  right: 0;
  bottom: 6px;
  left: 0;
  border-radius: 999px;
  background: #eef2f8;
  content: '';
  z-index: -1;
}

.catalog-categories__chip--active {
  color: #ffffff;
  font-weight: 500;
}

.catalog-categories__chip--active::before {
  background: #ff6a00;
}

.catalog-sort {
  display: flex;
  height: 44px;
  padding: 0 6px;
  align-items: center;
  border-bottom: 1px solid #f0f1f3;
  background: #ffffff;
}

.catalog-sort__item {
  position: relative;
  display: flex;
  height: 44px;
  margin: 0;
  padding: 0 10px;
  flex: 0 0 auto;
  align-items: center;
  gap: 3px;
  border: 0;
  color: #536178;
  background: transparent;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.catalog-sort__item::after {
  border: 0;
}

/* A hairline between the sorts, as on the board. */
.catalog-sort__item + .catalog-sort__item:not(.catalog-sort__item--city)::before {
  position: absolute;
  top: 15px;
  left: 0;
  width: 1px;
  height: 14px;
  background: #e5ebf3;
  content: '';
}

.catalog-sort__item--active {
  color: #ba470b;
  font-weight: 700;
}

.catalog-sort__item--city {
  min-width: 0;
  margin-left: auto;
  flex: 0 1 auto;
  gap: 2px;
  color: #202c42;
}

.catalog-sort__item--city text {
  overflow: hidden;
  text-overflow: ellipsis;
}

.catalog-toolbar__note {
  display: flex;
  height: 30px;
  padding: 0 16px;
  align-items: center;
  gap: 4px;
  color: #ba470b;
  background: #fffaf5;
  font-size: 12px;
  line-height: 17px;
}
</style>
