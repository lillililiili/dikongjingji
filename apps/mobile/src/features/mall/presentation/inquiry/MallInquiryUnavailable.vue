<script setup lang="ts">
// What a goods inquiry page shows in a public build, where inquiry goods are not open yet.
</script>

<template>
  <view class="inquiry-unavailable" data-testid="mall-inquiry-unavailable">
    <text>询价功能尚未开通</text>
  </view>
</template>

<style scoped>
.inquiry-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
