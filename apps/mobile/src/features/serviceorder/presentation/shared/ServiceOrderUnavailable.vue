<script setup lang="ts">
// What a service-order page shows in a public build, where general service orders are not open yet.
</script>

<template>
  <view class="service-order-unavailable" data-testid="service-order-unavailable">
    <text>服务订单功能尚未开通</text>
  </view>
</template>

<style scoped>
.service-order-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
