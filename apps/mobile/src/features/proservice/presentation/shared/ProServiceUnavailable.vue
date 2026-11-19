<script setup lang="ts">
// What the catalog, service and provider pages show in a public build, where the four service
// types are not open yet.
</script>

<template>
  <view class="pro-unavailable" data-testid="proservice-unavailable">
    <text>专业服务目录尚未开通</text>
  </view>
</template>

<style scoped>
.pro-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
