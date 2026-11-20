<script setup lang="ts">
// What 我的订单 shows in a public build, where online ordering is not open yet.
</script>

<template>
  <view class="my-orders-unavailable" data-testid="my-orders-unavailable">
    <text>我的订单功能尚未开通</text>
  </view>
</template>

<style scoped>
.my-orders-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #738096;
  background: #f5f7fc;
  font-size: 14px;
}
</style>
