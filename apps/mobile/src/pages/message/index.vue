<script setup lang="ts">
import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
</script>

<template>
  <view class="message-page" data-testid="message-page">
    <text>消息功能尚未开通</text>
    <RootAppTabBar active="MESSAGE" />
  </view>
</template>

<style scoped>
.message-page {
  display: flex;
  min-height: 100vh;
  padding-bottom: var(--app-root-tabbar-content-offset);
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  box-sizing: border-box;
  font-size: 14px;
}
</style>
