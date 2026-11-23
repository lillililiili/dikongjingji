<script setup lang="ts">
import { computed } from 'vue'

import type { InspectionOrderSummary } from '@/features/inspection/domain/service-flow/order-center'
import QualificationBadge from '@/shared/ui/QualificationBadge.vue'
import { inspectionServiceImage } from '../../../data/inspection-service-images'
import {
  formatInspectionOrderAmount,
  getInspectionOrderActionLabel,
  getInspectionOrderScheduleText,
  getInspectionOrderStageColour,
  getInspectionOrderStageText,
  hasInspectionOrderFulfillmentAction,
  isInspectionOrderActionUrgent,
} from '../order-center-view'

const props = defineProps<{
  order: InspectionOrderSummary
}>()

const emit = defineEmits<{
  select: [order: InspectionOrderSummary]
  fulfillment: [order: InspectionOrderSummary]
}>()

const picture = computed(() => inspectionServiceImage({
  coverUrl: props.order.serviceCoverUrl,
  title: props.order.serviceTitle,
}))
const stageColour = computed(() => getInspectionOrderStageColour(props.order.stage))
const showFulfillment = computed(() => hasInspectionOrderFulfillmentAction(props.order))
const urgent = computed(() => isInspectionOrderActionUrgent(props.order.stage))
</script>

<template>
  <!-- Board OrderList, the 服务 card: head row (tag, provider, 资质已审核, status), picture with title and amount,
       then the buttons on the right — solid orange only for 去验收, grey outline otherwise.
       A reservation without a provider yet shows its number in the provider's place. -->
  <view
    class="inspection-order-card"
    :data-testid="`inspection-order-${props.order.reservationId}`"
    @click="emit('select', props.order)"
  >
    <view class="inspection-order-card__head">
      <text class="inspection-order-card__tag">服务</text>
      <template v-if="props.order.provider">
        <text class="inspection-order-card__provider" data-testid="inspection-order-provider">{{ props.order.provider.name }}</text>
        <QualificationBadge v-if="props.order.provider.qualificationReviewed" />
      </template>
      <text v-else class="inspection-order-card__number">{{ props.order.reservationNo }}</text>
      <text class="inspection-order-card__stage" :style="{ color: stageColour }">{{ getInspectionOrderStageText(props.order) }}</text>
    </view>
    <view class="inspection-order-card__main">
      <image class="inspection-order-card__image" :src="picture" mode="aspectFill" />
      <view class="inspection-order-card__body">
        <view class="inspection-order-card__title-line">
          <text class="inspection-order-card__title">{{ props.order.serviceTitle }}</text>
          <text class="inspection-order-card__amount">{{ formatInspectionOrderAmount(props.order.quoteAmountMinor) }}</text>
        </view>
        <text class="inspection-order-card__meta">{{ props.order.locationSummary }}</text>
        <text class="inspection-order-card__schedule">{{ getInspectionOrderScheduleText(props.order) }}</text>
      </view>
    </view>
    <view class="inspection-order-card__actions">
      <button
        v-if="showFulfillment"
        class="inspection-order-card__secondary"
        data-testid="inspection-order-fulfillment"
        @click.stop="emit('fulfillment', props.order)"
      >
        查看履约
      </button>
      <button
        :class="urgent ? 'inspection-order-card__primary' : 'inspection-order-card__secondary'"
        data-testid="inspection-order-action"
        @click.stop="emit('select', props.order)"
      >
        {{ getInspectionOrderActionLabel(props.order.stage) }}
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board OrderList (390 wide). */
.inspection-order-card { display: flex; padding: 14px; flex-direction: column; gap: 12px; border-radius: 14px; color: #202c42; background: #fff; box-shadow: var(--app-card-shadow); }
.inspection-order-card__head { display: flex; min-width: 0; align-items: center; gap: 8px; }
.inspection-order-card__tag { flex: none; padding: 0 6px; border-radius: 6px; color: #11804a; background: #e2f6ed; font-size: 12px; line-height: 20px; }
.inspection-order-card__provider { min-width: 0; overflow: hidden; font-size: 15px; font-weight: 500; line-height: 21px; text-overflow: ellipsis; white-space: nowrap; }
.inspection-order-card__number { min-width: 0; overflow: hidden; color: #738096; font-size: 13px; line-height: 21px; text-overflow: ellipsis; white-space: nowrap; }
.inspection-order-card__stage { flex: none; margin-left: auto; font-size: 14px; font-weight: 500; line-height: 21px; white-space: nowrap; }
.inspection-order-card__main { display: flex; gap: 12px; }
.inspection-order-card__image { width: 72px; height: 72px; flex: none; border-radius: 10px; background: #eef2f8; }
.inspection-order-card__body { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 4px; }
.inspection-order-card__title-line { display: flex; align-items: flex-start; gap: 8px; }
.inspection-order-card__title { min-width: 0; flex: 1; font-size: 15px; font-weight: 700; line-height: 21px; }
.inspection-order-card__amount { flex: none; font-size: 15px; font-weight: 700; line-height: 21px; white-space: nowrap; }
.inspection-order-card__meta { overflow: hidden; color: #738096; font-size: 13px; line-height: 18px; text-overflow: ellipsis; white-space: nowrap; }
.inspection-order-card__schedule { color: #536178; font-size: 13px; line-height: 18px; }
.inspection-order-card__actions { display: flex; justify-content: flex-end; gap: 10px; }
.inspection-order-card__secondary,
.inspection-order-card__primary { height: 36px; margin: 0; border-radius: 999px; font-size: 14px; line-height: 20px; white-space: nowrap; box-sizing: border-box; display: flex; align-items: center; justify-content: center; }
.inspection-order-card__secondary { padding: 0 16px; border: 1px solid #c9d3e1; color: #202c42; background: #fff; }
.inspection-order-card__primary { padding: 0 18px; border: 0; color: #fff; background: #ff6a00; box-shadow: var(--app-primary-shadow); font-weight: 500; }
.inspection-order-card__secondary::after,
.inspection-order-card__primary::after { border: 0; }
</style>
