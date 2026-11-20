<script setup lang="ts">
// What the live pages show in a public build, where live is not open yet.
</script>

<template>
  <view class="live-unavailable" data-testid="live-unavailable">
    <text>直播尚未开通</text>
  </view>
</template>

<style scoped>
.live-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
