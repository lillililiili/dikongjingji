<script setup lang="ts">
defineProps<{ message: string; testId: string }>()
</script>

<template>
  <view class="mall-unavailable" :data-testid="testId">
    <text>{{ message }}</text>
  </view>
</template>

<style scoped>
.mall-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
