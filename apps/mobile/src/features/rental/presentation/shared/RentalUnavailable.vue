<script setup lang="ts">
// What a rental page shows in a public build, where equipment rental is not open yet.
</script>

<template>
  <view class="rental-unavailable" data-testid="rental-unavailable">
    <text>设备租赁功能尚未开通</text>
  </view>
</template>

<style scoped>
.rental-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
