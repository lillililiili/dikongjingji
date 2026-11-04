<script setup lang="ts">
import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
</script>

<template>
  <view class="cart-page" data-testid="cart-page">
    <text>购物车功能尚未开通</text>
    <RootAppTabBar active="CART" />
  </view>
</template>

<style scoped>
.cart-page {
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
