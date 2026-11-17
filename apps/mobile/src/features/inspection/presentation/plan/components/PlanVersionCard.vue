<script setup lang="ts">
import { ref } from 'vue'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import type {
  PlanChange,
  PlanPreviousVersion,
  PlanStatusChip,
} from '../plan-confirmation-view'

defineProps<{
  planVersion: number
  status: PlanStatusChip
  updatedText: string
  validUntilText: string
  change: PlanChange | null
  previous: PlanPreviousVersion | null
  previousVersion: number | null
}>()

const isPreviousOpen = ref(false)
</script>

<template>
  <view class="plan-version">
    <view class="plan-version__head">
      <text class="plan-version__title">方案 v{{ planVersion }}</text>
      <text class="plan-version__chip" :class="`plan-version__chip--${status.tone}`">{{ status.label }}</text>
      <text class="plan-version__updated">{{ updatedText }}</text>
      <button
        v-if="previous"
        class="plan-version__toggle"
        :aria-expanded="isPreviousOpen"
        @click="isPreviousOpen = !isPreviousOpen"
      >
        <text>{{ isPreviousOpen ? '收起' : '查看' }} v{{ previousVersion }}</text>
        <UniIcons :type="isPreviousOpen ? 'up' : 'down'" size="14" color="#8a8f97" />
      </button>
    </view>
    <text v-if="validUntilText" class="plan-version__valid">{{ validUntilText }}</text>
    <view v-if="change" class="plan-version__change">
      <UniIcons :type="change.isIncrease ? 'plusempty' : 'info'" size="14" color="#ff6a00" />
      <text class="plan-version__change-text">
        {{ change.text }}<text v-if="change.amountText" class="plan-version__change-amount">{{ change.amountText }}</text>
      </text>
    </view>
    <view v-if="previous && isPreviousOpen" class="plan-version__previous">
      <view class="plan-version__previous-head">
        <text class="plan-version__previous-title">{{ previous.title }}</text>
        <text class="plan-version__previous-total">合计 {{ previous.totalText }}</text>
      </view>
      <text class="plan-version__previous-items">{{ previous.itemsText }}</text>
    </view>
  </view>
</template>

<style scoped>
.plan-version {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px 14px;
  border-radius: 14px;
  background: #fff;
  /* The card shadow and hairline of the 2026-11-14 prototype's light theme. */
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.plan-version__head {
  display: flex;
  min-height: 32px;
  align-items: center;
  gap: 8px;
}

.plan-version__title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  white-space: nowrap;
}

.plan-version__chip {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.plan-version__chip--pending {
  color: #d94f00;
  background: #fff3e8;
}

.plan-version__chip--confirmed {
  color: #11804a;
  background: #e8f7ef;
}

.plan-version__chip--muted {
  color: #555a62;
  background: #f2f3f5;
}

.plan-version__updated {
  color: #8a8f97;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.plan-version__toggle {
  display: flex;
  height: 32px;
  flex-shrink: 0;
  margin: 0 0 0 auto;
  padding: 0 10px 0 12px;
  align-items: center;
  gap: 2px;
  box-sizing: border-box;
  border: 1px solid #ebedf0;
  border-radius: 999px;
  background: #fff;
  color: #555a62;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.plan-version__toggle::after {
  border: 0;
}

.plan-version__valid {
  color: #666b73;
  font-size: 12px;
  line-height: 17px;
}

.plan-version__change {
  display: flex;
  padding: 8px 10px;
  align-items: center;
  gap: 6px;
  border-radius: 10px;
  background: #fffaf5;
}

.plan-version__change-text {
  min-width: 0;
  color: #555a62;
  font-size: 13px;
  line-height: 19px;
}

.plan-version__change-amount {
  color: #e85400;
  font-weight: 500;
  white-space: nowrap;
}

.plan-version__previous {
  display: flex;
  padding: 8px 12px;
  flex-direction: column;
  gap: 2px;
  border-radius: 10px;
  background: #f7f8fa;
}

.plan-version__previous-head {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  line-height: 18px;
}

.plan-version__previous-title {
  font-weight: 500;
  white-space: nowrap;
}

.plan-version__previous-total {
  margin-left: auto;
  font-weight: 700;
  white-space: nowrap;
}

.plan-version__previous-items {
  color: #555a62;
  font-size: 12px;
  line-height: 17px;
}
</style>
