<script setup lang="ts">
import type { InspectionOrderSummary } from '@/features/inspection/domain/service-flow/order-center'
import {
  formatInspectionOrderAmount,
  getInspectionOrderActionLabel,
  getInspectionOrderScheduleText,
  getInspectionOrderStageText,
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
        <text>{{ getInspectionOrderStageText(props.order) }}</text>
      </view>
      <text>{{ props.order.locationSummary }}</text>
      <text class="inspection-order-card__schedule">{{ getInspectionOrderScheduleText(props.order) }}</text>
      <text>{{ props.order.reservationNo }}</text>
    </view>
    <view class="inspection-order-card__aside">
      <text>{{ formatInspectionOrderAmount(props.order.quoteAmountMinor) }}</text>
      <text>{{ getInspectionOrderActionLabel(props.order.stage) }}</text>
    </view>
  </button>
</template>

<style scoped>
.inspection-order-card{box-sizing:border-box;display:grid;width:100%;grid-template-columns:96px minmax(0,1fr);gap:10px 12px;align-items:start;padding:14px;border:0;border-radius:18px;background:#fff;box-shadow:0 5px 16px rgba(43,66,94,.06);line-height:1.4;text-align:left}
.inspection-order-card::after{border:0}
.inspection-order-card__image{width:96px;height:82px;border-radius:13px;background:#eef2f6}
.inspection-order-card__body{display:flex;min-width:0;flex-direction:column;gap:5px}
.inspection-order-card__title-line{display:flex;min-width:0;flex-wrap:wrap;align-items:center;gap:2px 6px;font-size:15px;font-weight:700}
.inspection-order-card__title-line>text:first-child{flex:1 1 100%;min-width:0}
.inspection-order-card__title-line>text:last-child{flex:0 0 auto;white-space:nowrap}
.inspection-order-card__body>text{overflow:hidden;color:#5f6873;font-size:13px;text-overflow:ellipsis;white-space:nowrap}
.inspection-order-card__body>.inspection-order-card__schedule{overflow:visible;white-space:normal}
.inspection-order-card__schedule{color:#5f6873;font-size:13px}
.inspection-order-card__aside{display:flex;grid-column:1/-1;min-width:0;align-items:center;justify-content:space-between;gap:10px}
.inspection-order-card__aside>text:first-child{color:#ff6508;font-size:16px;font-weight:800;white-space:nowrap}
.inspection-order-card__aside>text:last-child{padding:6px 10px;border:1px solid #ff6508;border-radius:16px;color:#d64d00;font-size:13px;white-space:nowrap}
@media (max-width:380px){.inspection-order-card{grid-template-columns:84px minmax(0,1fr);gap:9px;padding:12px}.inspection-order-card__image{width:84px;height:76px}}
/* 2026-11-14 prototype (board OrderList): the card look of a service order. */
.inspection-order-card{grid-template-columns:72px minmax(0,1fr);gap:8px 12px;padding:12px;border-radius:12px;color:#202c42;box-shadow:0 3px 12px rgba(38,60,101,.06);outline:1px solid rgba(232,237,244,.5);outline-offset:-1px}
.inspection-order-card__image{width:72px;height:72px;border-radius:10px;background:#eef0f3}
.inspection-order-card__body{gap:3px}
.inspection-order-card__title-line{font-size:15px;line-height:21px}
.inspection-order-card__title-line>text:last-child{color:#ba470b;font-size:13px;font-weight:500}
.inspection-order-card__body>text,.inspection-order-card__schedule{color:#627187;font-size:12px;line-height:17px}
.inspection-order-card__aside>text:first-child{color:#e85400;font-size:15px;font-weight:700}
.inspection-order-card__aside>text:last-child{display:flex;height:32px;padding:0 14px;align-items:center;border:1.5px solid #ff6a00;border-radius:999px;color:#ba470b;font-size:13px;font-weight:500}
@media (max-width:380px){.inspection-order-card{grid-template-columns:72px minmax(0,1fr);gap:8px 10px;padding:12px}.inspection-order-card__image{width:72px;height:72px}}
</style>
