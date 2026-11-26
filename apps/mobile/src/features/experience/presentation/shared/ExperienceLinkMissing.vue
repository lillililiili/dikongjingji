<script setup lang="ts">
import { openHomeChannel } from '@/app-shell/navigation/home-entry'
</script>

<template>
  <view class="missing" data-testid="experience-link-missing">
    <text>链接缺少必要信息，请从体验频道重新进入</text>
    <button class="missing__action" role="button" @click="openHomeChannel('EXPERIENCE')">去体验频道</button>
  </view>
</template>

<style scoped>
.missing {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 64px 20px;
  color: #526275;
  font-size: 15px;
  text-align: center;
}
.missing__action {
  min-height: 44px;
  margin: 0;
  padding: 0 20px;
  border-radius: 22px;
  background: #ff6900;
  color: #fff;
  font-size: 15px;
  line-height: 44px;
}
</style>
