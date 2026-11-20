<script setup lang="ts">
import { EXPERIENCE_PREVIEW_ENABLED } from '../../application/experience-preview'
import {
  productExperienceVenueDevicesUrl,
  resolveProductExperiencePill,
  type ProductExperiencePill,
  type ProductExperienceView,
} from '../../application/product-experience'

// 先体验，再决定 on a product page (R6-2). Sizes and colours follow the boards PdFull and PdRental.
// The board's "你在 11/02 体验过……" line is an experience record, which is not built; it is left out.
const props = defineProps<{
  view: ProductExperienceView
  cityCode: string
}>()

function openPill(pill: ProductExperiencePill): void {
  const action = resolveProductExperiencePill(pill, props.cityCode, EXPERIENCE_PREVIEW_ENABLED)
  if (action.kind === 'NAVIGATE') uni.navigateTo({ url: action.url })
  else if (action.kind === 'NOTICE') uni.showToast({ title: action.message, icon: 'none' })
}

function openVenueDevices(): void {
  const url = productExperienceVenueDevicesUrl(props.view, props.cityCode)
  if (url) uni.navigateTo({ url })
}
</script>

<template>
  <view class="try-first" data-testid="product-try-first">
    <view class="try-first__heading">
      <text class="try-first__title">先体验，再决定</text>
      <text class="try-first__note">体验结束不会自动下单</text>
    </view>
    <view class="try-first__ways">
      <button
        v-for="pill in view.pills"
        :key="pill.way"
        :class="['try-first__way', { 'try-first__way--highlighted': pill.highlighted }]"
        :data-testid="`product-try-first-${pill.way}`"
        @click="openPill(pill)"
      >
        {{ pill.label }}
      </button>
    </view>
    <view v-if="view.venueNote" class="try-first__venue" data-testid="product-try-first-venue">
      <text>{{ view.venueNote }} · </text>
      <text class="try-first__link" data-testid="product-try-first-devices" @click="openVenueDevices">馆内展示设备</text>
    </view>
  </view>
</template>

<style scoped>
.try-first {
  display: flex;
  margin: 0 16px;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #eefaf3;
  box-shadow: var(--app-card-shadow);
}

.try-first__heading {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.try-first__title {
  color: #11804a;
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
}

.try-first__note {
  min-width: 0;
  color: #536178;
  font-size: 12px;
}

.try-first__ways {
  display: flex;
  gap: 8px;
}

.try-first__way {
  display: flex;
  min-width: 0;
  min-height: 36px;
  margin: 0;
  padding: 0 4px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-family: inherit;
  font-size: 13px;
  font-weight: 400;
  line-height: 1.2;
  white-space: nowrap;
}

.try-first__way::after {
  border: 0;
}

.try-first__way--highlighted {
  color: #11804a;
  font-weight: 500;
}

.try-first__venue {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.try-first__link {
  color: #ba470b;
}
</style>
