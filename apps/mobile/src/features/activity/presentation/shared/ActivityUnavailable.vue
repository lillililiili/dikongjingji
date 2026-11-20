<script setup lang="ts">
// What a sign-up page that only preview builds open shows in a public build.
</script>

<template>
  <view class="activity-unavailable" data-testid="activity-unavailable">
    <text>活动报名功能尚未开通</text>
  </view>
</template>

<style scoped>
.activity-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
