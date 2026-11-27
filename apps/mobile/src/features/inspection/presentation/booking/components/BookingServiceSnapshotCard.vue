<script setup lang="ts">
import { computed } from 'vue'

import { resolveBusinessMediaUrl } from '../../../../../shared/http/business-media'
import type { BookingServiceSnapshot } from '../../../domain/service-flow/booking'
import { categoryBadgeLabel } from '../../detail/detail-line-icons'

const props = defineProps<{ service: BookingServiceSnapshot; quoted?: boolean }>()
const picture = computed(() => (resolveBusinessMediaUrl(props.service.coverImageUrl) ?? ''))
</script>

<template>
  <view class="booking-service-card">
    <image v-if="picture"
      class="booking-service-card__image"
      :src="picture"
      mode="aspectFill"
    />
    <view class="booking-service-card__copy">
      <text class="booking-service-card__category">{{ categoryBadgeLabel(service.categoryName) }}</text>
      <text class="booking-service-card__title">{{ service.title }}</text>
      <text class="booking-service-card__summary">{{ service.summary }}</text>
      <text v-if="!quoted && service.priceLabel" class="booking-service-card__price">{{ service.priceLabel }}</text>
      <text class="booking-service-card__notice">{{ quoted ? '按已选定的报价预约' : '最终报价以现场评估为准' }}</text>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspBooking (390 wide). */
.booking-service-card { display: grid; grid-template-columns: 88px minmax(0, 1fr); padding: 12px; overflow: hidden; border-radius: 14px; background: #fff; box-sizing: border-box; }
.booking-service-card__image { width: 88px; height: 88px; border-radius: 10px; background: #eef0f3; }
.booking-service-card__copy { display: flex; min-width: 0; padding-left: 12px; flex-direction: column; justify-content: space-between; }
.booking-service-card__category { align-self: flex-start; padding: 1px 6px; border-radius: 6px; color: #11804a; background: #e2f6ed; font-size: 12px; line-height: 16px; }
.booking-service-card__title { overflow: hidden; color: #202c42; font-size: 16px; font-weight: 700; line-height: 22px; text-overflow: ellipsis; white-space: nowrap; }
/* The board shows category, title, price and the notice; the summary is on the detail page. */
.booking-service-card__summary { display: none; }
.booking-service-card__price { color: #e85400; font-size: 15px; font-weight: 700; line-height: 21px; }
.booking-service-card__notice { color: #738096; font-size: 12px; line-height: 17px; }
</style>
