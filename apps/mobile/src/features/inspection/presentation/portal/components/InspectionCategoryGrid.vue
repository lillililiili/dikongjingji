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
      :aria-label="`${category.name}，${category.subtitle}`"
      @click="$emit('select', category)"
    >
      <image
        class="category-grid__icon"
        :src="category.imageUrl"
        mode="aspectFit"
        lazy-load
        :aria-label="category.name"
      />
      <text class="category-grid__name">{{ category.name }}</text>
      <text class="category-grid__subtitle">{{ category.subtitle }}</text>
    </button>
  </view>
</template>

<style scoped>
.category-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px 9px;
  overflow: hidden;
  padding: 10px;
  border: 1px solid #ebedf0;
  border-radius: clamp(11px, 2.8vw, 17px);
  background: #ffffff;
}

.category-grid__item {
  position: relative;
  display: flex;
  min-height: 108px;
  min-width: 0;
  margin: 0;
  padding: 0;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  border: 0;
  border-radius: 12px;
  background: #ffffff;
  line-height: 1.2;
}

.category-grid__item:nth-child(4n) {
  border-right: 0;
}

.category-grid__item:nth-last-child(-n + 4) {
  border-bottom: 0;
}

.category-grid__item::after {
  border: 0;
}

.category-grid__icon {
  display: block;
  width: 100%;
  height: 73px;
  flex: 0 0 73px;
  border-radius: 12px;
}

.category-grid__name,
.category-grid__subtitle {
  display: block;
  width: 100%;
  overflow: hidden;
  text-align: center;
}

.category-grid__name {
  margin-top: 7px;
  color: #191919;
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
}

.category-grid__subtitle {
  margin-top: 3px;
  color: #969ba3;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 359px) {
  .category-grid {
    gap: 10px 7px;
    padding: 8px;
  }

  .category-grid__icon {
    height: 64px;
    flex-basis: 64px;
  }

  .category-grid__name {
    font-size: 13px;
  }

  .category-grid__subtitle {
    font-size: 9px;
  }
}

@media (min-width: 420px) {
  .category-grid__item {
    min-height: 117px;
  }

  .category-grid__icon {
    height: 82px;
    flex-basis: 82px;
  }
}
</style>
