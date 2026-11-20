<script setup lang="ts">
import type { PlanArrangementView } from '../plan-confirmation-view'

defineProps<{ arrangement: PlanArrangementView }>()
</script>

<template>
  <view class="plan-arrangement">
    <text class="plan-arrangement__title">服务安排</text>
    <view class="plan-arrangement__rows">
      <template v-for="row in arrangement.rows" :key="row.label">
        <text class="plan-arrangement__label">{{ row.label }}</text>
        <text class="plan-arrangement__value">{{ row.value }}</text>
      </template>
    </view>
    <view class="plan-arrangement__dates" aria-label="巡检日期">
      <text
        v-for="visit in arrangement.visits"
        :key="visit.visitNo"
        class="plan-arrangement__date"
        :aria-label="visit.label"
      >{{ visit.date }}</text>
    </view>
  </view>
</template>

<style scoped>
.plan-arrangement {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #fff;
  /* The card shadow and hairline of the 2026-11-14 prototype's light theme. */
  box-shadow: var(--app-card-shadow);
}

.plan-arrangement__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.plan-arrangement__rows {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 8px 10px;
  font-size: 13px;
  line-height: 19px;
}

.plan-arrangement__label {
  color: #666b73;
  white-space: nowrap;
}

.plan-arrangement__dates {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 6px;
}

.plan-arrangement__date {
  height: 32px;
  border-radius: 8px;
  background: #f7f8fa;
  font-size: 13px;
  line-height: 32px;
  text-align: center;
  white-space: nowrap;
}
</style>
