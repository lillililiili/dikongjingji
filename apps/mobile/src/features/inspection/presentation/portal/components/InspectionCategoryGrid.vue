<script setup lang="ts">
import type { InspectionPortalCategory } from '../../../domain/inspection-portal-content'

defineProps<{
  categories: readonly InspectionPortalCategory[]
}>()

defineEmits<{
  select: [category: InspectionPortalCategory]
}>()
</script>

<template>
  <view class="category-grid">
    <button
      v-for="category in categories"
      :key="category.code"
      class="category-grid__item"
      :data-testid="`portal-category-${category.code.toLowerCase()}`"
      :aria-label="category.subtitle ? `${category.name}，${category.subtitle}` : category.name"
      @click="$emit('select', category)"
    >
      <view class="category-grid__tile">
        <image
          class="category-grid__icon"
          :src="category.imageUrl"
          mode="aspectFit"
          lazy-load
          :aria-label="category.name"
        />
      </view>
      <text class="category-grid__name">{{ category.name }}</text>
    </button>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board HomeSvc (390 wide). */
.category-grid {
  display: grid;
  padding: 12px 4px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px 4px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.category-grid__item {
  display: flex;
  min-width: 0;
  min-height: 66px;
  margin: 0;
  padding: 0;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  border: 0;
  color: #202c42;
  background: transparent;
  line-height: 1.2;
}

.category-grid__item::after {
  border: 0;
}

/* The tile's tint goes round in fours, like the board's theme. */
.category-grid__tile {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: 15px;
  background: #e8f1ff;
  box-shadow: 0 3px 9px rgba(46, 70, 112, 0.06);
  box-sizing: border-box;
}

.category-grid__item:nth-child(4n + 2) .category-grid__tile {
  background: #e1f5ed;
}

.category-grid__item:nth-child(4n + 3) .category-grid__tile {
  background: #fff0da;
}

.category-grid__item:nth-child(4n + 4) .category-grid__tile {
  background: #eee7ff;
}

.category-grid__icon {
  width: 32px;
  height: 32px;
}

.category-grid__name {
  max-width: 100%;
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
