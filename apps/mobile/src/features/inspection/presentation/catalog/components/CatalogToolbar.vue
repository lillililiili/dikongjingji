<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import {
  CATALOG_CATEGORIES,
  type CatalogCategory,
  type CatalogSort,
} from '../model/inspection-catalog'

defineProps<{
  category: CatalogCategory
  sort: CatalogSort
  cityName: string
}>()

defineEmits<{
  category: [value: CatalogCategory]
  sort: [value: CatalogSort]
  city: []
}>()

function nextPriceSort(sort: CatalogSort): CatalogSort {
  return sort === 'PRICE_ASC' ? 'PRICE_DESC' : 'PRICE_ASC'
}
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
          v-for="item in CATALOG_CATEGORIES"
          :key="item.code"
          class="catalog-categories__chip"
          :class="{
            'catalog-categories__chip--active':
              category === item.code,
          }"
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
        @click="$emit('sort', 'COMPREHENSIVE')"
      >
        <text>综合排序</text>
        <UniIcons type="down" size="10" color="currentColor" />
      </button>
      <button
        class="catalog-sort__item"
        :class="{
          'catalog-sort__item--active': sort === 'DISTANCE_ASC',
        }"
        @click="$emit('sort', 'DISTANCE_ASC')"
      >
        <text>距离最近</text>
        <UniIcons type="down" size="10" color="currentColor" />
      </button>
      <button
        class="catalog-sort__item"
        :class="{
          'catalog-sort__item--active':
            sort === 'PRICE_ASC' || sort === 'PRICE_DESC',
        }"
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
        aria-label="切换服务城市"
        @click="$emit('city')"
      >
        <UniIcons type="location" size="13" color="#777d85" />
        <text>{{ cityName }}</text>
        <UniIcons type="down" size="9" color="#777d85" />
      </button>
    </view>
  </view>
</template>

<style scoped>
.catalog-toolbar {
  background: #ffffff;
}

.catalog-categories {
  width: 100%;
  padding: 3px 0 2px;
  white-space: nowrap;
  box-sizing: border-box;
}

.catalog-categories__track {
  display: inline-flex;
  min-width: max-content;
  padding: 0 clamp(12px, 3.2vw, 18px);
  gap: 8px;
  box-sizing: border-box;
}

.catalog-categories__chip {
  display: inline-flex;
  min-width: 54px;
  height: 31px;
  padding: 0 15px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 18px;
  color: #30343a;
  background: #f4f5f6;
  font-size: 13px;
  line-height: 1;
  white-space: nowrap;
}

.catalog-categories__chip::after,
.catalog-sort__item::after {
  border: 0;
}

.catalog-categories__chip--active {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 650;
}

.catalog-sort {
  display: grid;
  min-height: 34px;
  padding: 0 clamp(8px, 2.4vw, 14px);
  grid-template-columns: 1.1fr 1.08fr 0.82fr 1.1fr;
  align-items: center;
  border-bottom: 1px solid #f0f1f3;
  box-sizing: border-box;
}

.catalog-sort__item {
  display: flex;
  min-width: 0;
  height: 34px;
  padding: 0 3px;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border: 0;
  color: #4c5158;
  background: transparent;
  font-size: 12px;
  white-space: nowrap;
}

.catalog-sort__item--active {
  color: #ff5a00;
  font-weight: 650;
}

.catalog-sort__item--city {
  overflow: hidden;
}

.catalog-sort__item--city text {
  overflow: hidden;
  max-width: 64px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
