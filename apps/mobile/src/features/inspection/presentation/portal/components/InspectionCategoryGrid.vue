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
  overflow: hidden;
  border: 1px solid #ebedf0;
  border-radius: clamp(11px, 2.8vw, 17px);
  background: #ffffff;
}

.category-grid__item {
  position: relative;
  display: flex;
  height: clamp(76px, 20.25vw, 121px);
  min-width: 0;
  margin: 0;
  padding:
    clamp(4px, 1.1vw, 7px)
    clamp(3px, 0.8vw, 5px)
    clamp(3px, 0.8vw, 5px);
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  border: 0;
  border-right: 1px solid #f0f1f3;
  border-bottom: 1px solid #f0f1f3;
  border-radius: 0;
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
  width: clamp(42px, 11.2vw, 67px);
  height: clamp(38px, 10.1vw, 61px);
  flex: 0 0 clamp(38px, 10.1vw, 61px);
}

.category-grid__name,
.category-grid__subtitle {
  display: block;
  width: 100%;
  overflow: hidden;
  text-align: center;
}

.category-grid__name {
  margin-top: clamp(1px, 0.3vw, 2px);
  color: #191919;
  font-size: clamp(12px, 3.1vw, 18px);
  font-weight: 700;
  white-space: nowrap;
}

.category-grid__subtitle {
  margin-top: clamp(1px, 0.3vw, 2px);
  color: #969ba3;
  font-size: clamp(8px, 2.1vw, 12px);
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 359px) {
  .category-grid__item {
    padding-right: 2px;
    padding-left: 2px;
  }

  .category-grid__icon {
    width: 38px;
    height: 36px;
  }

  .category-grid__name {
    font-size: 11px;
  }

  .category-grid__subtitle {
    font-size: 7px;
  }
}
</style>
