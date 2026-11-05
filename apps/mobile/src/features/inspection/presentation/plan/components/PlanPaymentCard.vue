<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import type { PlanPaymentRow } from '../plan-confirmation-view'

defineProps<{ rows: PlanPaymentRow[] }>()
</script>

<template>
  <view class="plan-payment">
    <text class="plan-payment__title">付款节点</text>
    <view v-for="row in rows" :key="row.key" class="plan-payment__row">
      <view class="plan-payment__copy">
        <text class="plan-payment__name">{{ row.name }}</text>
        <text class="plan-payment__state" :class="{ 'plan-payment__state--due': row.isDue }">{{ row.stateText }}</text>
      </view>
      <text class="plan-payment__amount" :class="{ 'plan-payment__amount--due': row.isDue }">{{ row.amountText }}</text>
    </view>
    <view class="plan-payment__note">
      <UniIcons type="info" size="14" color="#8a8f97" />
      <text class="plan-payment__note-text">款项经平台支付渠道付给服务商；本平台不承担飞行审批与空中调度。</text>
    </view>
  </view>
</template>

<style scoped>
.plan-payment {
  display: flex;
  padding: 14px 14px 12px;
  flex-direction: column;
  border-radius: 14px;
  background: #fff;
}

.plan-payment__title {
  margin-bottom: 4px;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.plan-payment__row {
  display: flex;
  min-height: 56px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.plan-payment__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.plan-payment__name {
  font-size: 14px;
  line-height: 20px;
}

.plan-payment__state {
  color: #8a8f97;
  font-size: 12px;
  line-height: 17px;
}

.plan-payment__state--due {
  color: #d94f00;
}

.plan-payment__amount {
  flex-shrink: 0;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.plan-payment__amount--due {
  color: #e85400;
}

.plan-payment__note {
  display: flex;
  padding-top: 10px;
  gap: 6px;
  border-top: 1px solid #f0f1f3;
}

.plan-payment__note :deep(.uni-icons) {
  flex-shrink: 0;
  margin-top: 1.5px;
}

.plan-payment__note-text {
  min-width: 0;
  color: #8a8f97;
  font-size: 12px;
  line-height: 17px;
}
</style>
