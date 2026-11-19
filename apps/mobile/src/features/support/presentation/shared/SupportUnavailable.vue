<script setup lang="ts">
// What a page of step G1 shows in a public build, where these pages are not open yet.
withDefaults(defineProps<{ text?: string }>(), { text: '该功能尚未开通' })
</script>

<template>
  <view class="support-unavailable" data-testid="support-unavailable">
    <text>{{ text }}</text>
  </view>
</template>

<style scoped>
.support-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
