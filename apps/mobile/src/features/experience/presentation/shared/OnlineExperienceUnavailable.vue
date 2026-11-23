<script setup lang="ts">
// What an online experience page shows in a public build, where these pages are not open yet.
</script>

<template>
  <view class="experience-unavailable" data-testid="online-experience-unavailable">
    <text>线上体验功能尚未开通</text>
  </view>
</template>

<style scoped>
.experience-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
