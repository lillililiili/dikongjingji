<script setup lang="ts">
// What a trade page shows in a public build, where online ordering is not open yet.
</script>

<template>
  <view class="trade-unavailable" data-testid="trade-unavailable">
    <text>商品订单功能尚未开通</text>
  </view>
</template>

<style scoped>
.trade-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
