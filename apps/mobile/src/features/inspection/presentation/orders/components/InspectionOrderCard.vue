<script setup lang="ts">
import type { InspectionOrderSummary } from '@/features/inspection/domain/service-flow/order-center'
import {
  formatInspectionOrderAmount,
  getInspectionOrderActionLabel,
  getInspectionOrderStageLabel,
} from '../order-center-view'

const props = defineProps<{
  order: InspectionOrderSummary
}>()

const emit = defineEmits<{
  select: [order: InspectionOrderSummary]
}>()
</script>

<template>
  <button class="inspection-order-card" @click="emit('select', props.order)">
    <image
      class="inspection-order-card__image"
      :src="props.order.serviceCoverUrl || '/static/images/inspection/inspection-hero-v1.png'"
      mode="aspectFill"
    />
    <view class="inspection-order-card__body">
      <view class="inspection-order-card__title-line">
        <text>{{ props.order.serviceTitle }}</text>
        <text>{{ getInspectionOrderStageLabel(props.order.stage) }}</text>
      </view>
      <text>{{ props.order.locationSummary }}</text>
      <text>{{ props.order.preferredServiceDate }}</text>
      <text>{{ props.order.reservationNo }}</text>
    </view>
    <view class="inspection-order-card__aside">
      <text>{{ formatInspectionOrderAmount(props.order.quoteAmountMinor) }}</text>
      <text>{{ getInspectionOrderActionLabel(props.order.stage) }}</text>
    </view>
  </button>
</template>

<style scoped>
.inspection-order-card{box-sizing:border-box;display:grid;width:100%;grid-template-columns:96px minmax(0,1fr) 82px;gap:12px;align-items:center;padding:14px;border:0;border-radius:18px;background:#fff;box-shadow:0 5px 16px rgba(43,66,94,.06);text-align:left}
.inspection-order-card::after{border:0}
.inspection-order-card__image{width:96px;height:82px;border-radius:13px;background:#eef2f6}
.inspection-order-card__body{display:flex;min-width:0;flex-direction:column;gap:5px}
.inspection-order-card__title-line{display:flex;min-width:0;align-items:center;gap:6px;font-size:15px;font-weight:700}
.inspection-order-card__title-line>text:first-child{min-width:0;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.inspection-order-card__title-line>text:last-child{flex:0 0 auto;white-space:nowrap}
.inspection-order-card__body>text{overflow:hidden;color:#5f6873;font-size:13px;text-overflow:ellipsis;white-space:nowrap}
.inspection-order-card__aside{display:flex;min-width:0;flex-direction:column;align-items:flex-end;gap:10px}
.inspection-order-card__aside>text:first-child{color:#ff6508;font-size:16px;font-weight:800}
.inspection-order-card__aside>text:last-child{padding:6px 10px;border:1px solid #ff6508;border-radius:16px;color:#d64d00;font-size:13px;white-space:nowrap}
@media (max-width:380px){.inspection-order-card{grid-template-columns:84px minmax(0,1fr) 76px;gap:9px;padding:12px}.inspection-order-card__image{width:84px;height:76px}}
</style>
