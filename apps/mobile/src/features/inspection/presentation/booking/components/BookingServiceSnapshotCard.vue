<script setup lang="ts">
import type { BookingServiceSnapshot } from '../../../domain/service-flow/booking'

defineProps<{ service: BookingServiceSnapshot }>()
</script>

<template>
  <view class="booking-service-card">
    <image
      class="booking-service-card__image"
      :src="service.coverImageUrl || '/static/images/inspection/inspection-hero-v1.png'"
      mode="aspectFill"
    />
    <view class="booking-service-card__copy">
      <text class="booking-service-card__category">{{ service.categoryName }}</text>
      <text class="booking-service-card__title">{{ service.title }}</text>
      <text class="booking-service-card__summary">{{ service.summary }}</text>
      <text v-if="service.priceLabel" class="booking-service-card__price">{{ service.priceLabel }}</text>
      <text class="booking-service-card__notice">最终报价以现场评估为准</text>
    </view>
  </view>
</template>

<style scoped>
.booking-service-card { display: grid; grid-template-columns: 108px minmax(0, 1fr); min-height: 106px; padding: 9px; overflow: hidden; border: 1px solid #eceef1; border-radius: 12px; background: #fff; box-sizing: border-box; }
.booking-service-card__image { width: 108px; height: 106px; border-radius: 8px; background: #edf0f2; }
.booking-service-card__copy { display: flex; min-width: 0; padding: 2px 0 1px 11px; flex-direction: column; justify-content: center; }
.booking-service-card__category { align-self: flex-start; padding: 2px 7px; border-radius: 9px; color: #fff; background: #14ae57; font-size: 10px; line-height: 15px; }
.booking-service-card__title { overflow: hidden; margin-top: 4px; color: #20242a; font-size: 16px; font-weight: 700; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; }
.booking-service-card__summary { overflow:hidden; margin-top: 3px; color:#6f767f; font-size:11px; text-overflow:ellipsis; white-space:nowrap; }
.booking-service-card__price { margin-top: 3px; color:#ff5a00; font-size:17px; font-weight:700; line-height: 1.2; }
.booking-service-card__notice { margin-top: 2px; color: #9399a1; font-size: 10px; }
</style>
