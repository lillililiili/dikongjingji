<script setup lang="ts">
import { computed } from 'vue'

import type {
  DeliveryIssueRow,
  DeliveryResultTile,
} from '../delivery-view'

const props = defineProps<{
  tiles: DeliveryResultTile[]
  issues: DeliveryIssueRow[]
}>()

// Board DeliveryOne puts three figures in one row, Delivery two: as many columns as figures, at most 3.
const tileColumns = computed(() => ({
  gridTemplateColumns: `repeat(${Math.max(1, Math.min(props.tiles.length, 3))}, minmax(0, 1fr))`,
}))
</script>

<template>
  <view class="delivery-result">
    <text class="delivery-result__title">巡检结果摘要</text>
    <view class="delivery-result__tiles" :style="tileColumns">
      <view
        v-for="tile in tiles"
        :key="tile.key"
        class="delivery-result__tile"
        :class="{ 'delivery-result__tile--issue': tile.isIssue }"
      >
        <text class="delivery-result__tile-label">{{ tile.label }}</text>
        <text class="delivery-result__tile-value">{{ tile.valueText }}</text>
      </view>
    </view>
    <view v-if="issues.length" class="delivery-result__issues" aria-label="隐患分类">
      <view v-for="issue in issues" :key="issue.key" class="delivery-result__issue">
        <view class="delivery-result__issue-dot" />
        <text class="delivery-result__issue-name">{{ issue.name }}</text>
        <text class="delivery-result__issue-count">{{ issue.countText }}</text>
      </view>
    </view>
    <text v-else class="delivery-result__empty">本次巡检未发现隐患</text>
  </view>
</template>

<style scoped>
.delivery-result {
  display: flex;
  padding: 14px 14px 4px;
  flex-direction: column;
  gap: 12px;
  border-radius: 14px;
  background: #fff;
  /* The card shadow and hairline of the 2026-11-14 prototype's light theme. */
  box-shadow: var(--app-card-shadow);
}

.delivery-result__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.delivery-result__tiles {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.delivery-result__tile {
  display: flex;
  min-width: 0;
  padding: 10px 12px;
  flex-direction: column;
  gap: 2px;
  border-radius: 10px;
  background: #f5f7fc;
}

.delivery-result__tile--issue {
  background: #fff0e3;
}

.delivery-result__tile-label {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.delivery-result__tile--issue .delivery-result__tile-label,
.delivery-result__tile--issue .delivery-result__tile-value {
  color: #ba470b;
}

.delivery-result__tile-value {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
  /* A long figure (“420,000 ㎡”) wraps at the space first, and only breaks inside the number on
     the narrowest phones, so it never runs out of a third column. */
  word-break: keep-all;
  overflow-wrap: anywhere;
}

.delivery-result__issues {
  display: flex;
  flex-direction: column;
}

.delivery-result__issue {
  display: flex;
  min-height: 40px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
  font-size: 14px;
  line-height: 20px;
}

.delivery-result__issue-dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ff6a00;
}

.delivery-result__issue-name {
  min-width: 0;
  flex: 1;
}

.delivery-result__issue-count {
  flex-shrink: 0;
  color: #536178;
  white-space: nowrap;
}

.delivery-result__empty {
  padding-bottom: 10px;
  color: #666b73;
  font-size: 13px;
  line-height: 19px;
}
</style>
