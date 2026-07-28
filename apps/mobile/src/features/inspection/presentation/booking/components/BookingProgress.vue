<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

defineProps<{ active: 1 | 2 | 3 }>()

const steps = ['填写需求', '方案确认', '服务安排']
</script>

<template>
  <view class="booking-progress" aria-label="预约服务进度">
    <view v-for="(step, index) in steps" :key="step" class="booking-progress__item">
      <view :class="['booking-progress__dot', { 'booking-progress__dot--active': index + 1 === active, 'booking-progress__dot--complete': index + 1 < active }]">
        <UniIcons v-if="index + 1 < active" type="checkbox-filled" size="20" color="#16ad55" />
        <text v-else>{{ index + 1 }}</text>
      </view>
      <text :class="['booking-progress__label', { 'booking-progress__label--active': index + 1 === active }]">
        {{ step }}
      </text>
      <view v-if="index < steps.length - 1" :class="['booking-progress__line', { 'booking-progress__line--active': index + 1 < active }]" />
    </view>
  </view>
</template>

<style scoped>
.booking-progress { display: flex; padding: 18px 24px 14px; align-items: flex-start; justify-content: space-between; background: #fff; }
.booking-progress__item { position: relative; display: flex; min-width: 56px; flex: 1; align-items: center; flex-direction: column; }
.booking-progress__dot { z-index: 1; display: grid; width: 20px; height: 20px; place-items: center; border-radius: 50%; color: #9ba1a9; background: #edf0f2; font-size: 11px; font-weight: 700; }
.booking-progress__dot--active { color: #fff; background: #ff6a00; }
.booking-progress__dot--complete { background: transparent; }
.booking-progress__label { margin-top: 6px; color: #9ba1a9; font-size: 11px; white-space: nowrap; }
.booking-progress__label--active { color: #ff5a00; font-weight: 700; }
.booking-progress__line { position: absolute; top: 9px; left: calc(50% + 11px); width: calc(100% - 22px); height: 2px; background: #edf0f2; }
.booking-progress__line--active { background: #ffb27a; }
</style>
