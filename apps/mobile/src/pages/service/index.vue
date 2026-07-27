<script setup lang="ts">
import { navigateToInspectionPortal } from '@/app-shell/navigation/service-entry'

function openInspection(): void {
  navigateToInspectionPortal((url) => {
    uni.navigateTo({ url })
  })
}
</script>

<template>
  <view class="service-shell" data-testid="service-page">
    <text class="service-shell__title">服务</text>
    <button
      class="service-shell__entry"
      data-testid="inspection-entry"
      @click="openInspection"
    >
      我要巡检
    </button>
  </view>
</template>

<style scoped>
.service-shell {
  padding: 24px 16px;
}

.service-shell__title {
  display: block;
  color: #191919;
  font-size: 22px;
  font-weight: 700;
}

.service-shell__entry {
  min-height: 48px;
  margin-top: 20px;
  color: #ffffff;
  background: #ff6a00;
  border: 0;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
}

.service-shell__entry::after {
  border: 0;
}
</style>
