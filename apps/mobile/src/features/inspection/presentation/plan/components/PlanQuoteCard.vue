<script setup lang="ts">
import type { PlanQuoteRow } from '../plan-confirmation-view'

defineProps<{
  rows: PlanQuoteRow[]
  totalText: string
}>()
</script>

<template>
  <view class="plan-quote">
    <text class="plan-quote__title">报价明细</text>
    <view v-for="row in rows" :key="row.code" class="plan-quote__row">
      <view class="plan-quote__name">
        <text class="plan-quote__name-text">{{ row.name }}</text>
        <text v-if="row.newLabel" class="plan-quote__new">{{ row.newLabel }}</text>
      </view>
      <text v-if="row.quantityText" class="plan-quote__quantity">{{ row.quantityText }}</text>
      <text class="plan-quote__amount">{{ row.amountText }}</text>
    </view>
    <view class="plan-quote__total">
      <text class="plan-quote__total-label">合计</text>
      <text class="plan-quote__total-value">{{ totalText }}</text>
    </view>
  </view>
</template>

<style scoped>
.plan-quote {
  display: flex;
  padding: 14px 14px 0;
  flex-direction: column;
  border-radius: 14px;
  background: #fff;
  /* The card shadow and hairline of the 2026-11-14 prototype's light theme. */
  box-shadow: var(--app-card-shadow);
}

.plan-quote__title {
  margin-bottom: 4px;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.plan-quote__row {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
}

.plan-quote__name {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 6px;
}

.plan-quote__name-text {
  font-size: 14px;
  line-height: 20px;
}

.plan-quote__new {
  flex-shrink: 0;
  padding: 1px 5px;
  border-radius: 6px;
  color: #d94f00;
  background: #fff3e8;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.plan-quote__quantity {
  flex-shrink: 0;
  color: #8a8f97;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.plan-quote__amount {
  flex-shrink: 0;
  min-width: 68px;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-align: right;
  white-space: nowrap;
}

.plan-quote__total {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid #f0f1f3;
}

.plan-quote__total-label {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.plan-quote__total-value {
  color: #e85400;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}
</style>
