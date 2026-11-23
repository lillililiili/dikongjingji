<script setup lang="ts">
import { MAX_COMPARE_ITEMS } from '../application/compare-selection'

// The dark pill of boards MallHome / Mall2 / Favorites: “已选 N 件对比 · 最多 3 件 · 开始对比”.
// Shown by the page only from two picks on.
defineProps<{ count: number }>()
const emit = defineEmits<{ start: [] }>()
</script>

<template>
  <view class="compare-bar" data-testid="compare-bar" role="status">
    <text class="compare-bar__count">已选 {{ count }} 件对比</text>
    <text class="compare-bar__limit">最多 {{ MAX_COMPARE_ITEMS }} 件</text>
    <button class="compare-bar__start" data-testid="compare-bar-start" @click="emit('start')">开始对比</button>
  </view>
</template>

<style scoped>
.compare-bar {
  display: flex;
  height: 52px;
  padding: 0 6px 0 18px;
  align-items: center;
  gap: 10px;
  border-radius: 999px;
  color: #ffffff;
  background: #202c42;
  box-sizing: border-box;
  white-space: nowrap;
}

.compare-bar__count {
  font-size: 14px;
}

.compare-bar__limit {
  color: #c9d3e1;
  font-size: 12px;
}

.compare-bar__start {
  display: flex;
  height: 40px;
  margin: 0 0 0 auto;
  padding: 0 18px;
  align-items: center;
  border: 0;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-family: inherit;
  font-size: 14px;
  font-weight: 500;
  line-height: 1;
}

.compare-bar__start::after {
  border: 0;
}
</style>
