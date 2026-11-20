<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import { computed } from 'vue'

import { rentalCoverUrl } from '../../application/rental-cover'
import { describeRentalLessee } from '../../application/rental-format'
import type { RentalLessee } from '../../domain/rental'

// The device card at the top of RentalQuote and OrderRental: picture, device × units with the 租赁
// tag, the lessor, and who rents it.
const props = defineProps<{
  productId: string
  productName: string
  quantity: number
  coverUrl: string | null
  merchantName: string
  lessee: RentalLessee
}>()

const cover = computed(() => rentalCoverUrl(props.coverUrl, props.productId))
const lessee = computed(() => describeRentalLessee(props.lessee))
</script>

<template>
  <view class="rental-item" data-testid="rental-item">
    <image v-if="cover" class="rental-item__image" :src="cover" mode="aspectFill" />
    <view v-else class="rental-item__image rental-item__image--empty">
      <LineIcon name="gear" :size="28" color="#91a0b4" />
    </view>
    <view class="rental-item__copy">
      <view class="rental-item__line">
        <text class="rental-item__name" data-testid="rental-item-name">{{ productName }} ×{{ quantity }}</text>
        <text class="rental-item__tag">租赁</text>
      </view>
      <text class="rental-item__merchant" data-testid="rental-item-merchant">出租方 {{ merchantName }}</text>
      <view class="rental-item__line">
        <text class="rental-item__kind">{{ lessee.tag }}</text>
        <text class="rental-item__lessee" data-testid="rental-item-lessee">{{ lessee.text }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.rental-item {
  display: flex;
  gap: 12px;
  padding: 12px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.rental-item__image {
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.rental-item__image--empty {
  display: flex;
  align-items: center;
  justify-content: center;
}

.rental-item__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.rental-item__line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.rental-item__name {
  overflow: hidden;
  min-width: 0;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rental-item__tag {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.rental-item__merchant,
.rental-item__lessee {
  overflow: hidden;
  min-width: 0;
  color: #536178;
  font-size: 13px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rental-item__kind {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  color: #1d63c4;
  background: #e6f0ff;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}
</style>
