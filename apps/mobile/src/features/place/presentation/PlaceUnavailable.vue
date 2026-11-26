<script setup lang="ts">
import { leavePlacePage } from '../navigation/place-routes'

defineProps<{ message: string }>()
const action = getCurrentPages().length > 1 ? '返回上一页' : '返回首页'
</script>

<template>
  <view class="unavailable">
    <text role="status">{{ message }}</text>
    <button data-testid="place-unavailable-back" @click="leavePlacePage">{{ action }}</button>
  </view>
</template>

<style scoped>
.unavailable { display: flex; min-height: 75vh; flex-direction: column; align-items: center; justify-content: center; gap: 24rpx; padding: 24rpx; color: #627187; font-size: 28rpx; }
.unavailable button { border-radius: 48rpx; padding: 0 36rpx; background: #ff6a00; color: #fff; font-size: 28rpx; }
</style>
