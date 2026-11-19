<script setup lang="ts">
// What a demand page shows in a public build, where publishing demands is not open yet.
</script>

<template>
  <view class="demand-unavailable" data-testid="demand-unavailable">
    <text>需求发布功能尚未开通</text>
  </view>
</template>

<style scoped>
.demand-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
