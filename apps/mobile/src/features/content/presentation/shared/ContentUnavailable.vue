<script setup lang="ts">
// What the article and topic pages show in a public build, where the discover content is not open yet.
</script>

<template>
  <view class="content-unavailable" data-testid="content-unavailable">
    <text>发现频道尚未开通</text>
  </view>
</template>

<style scoped>
.content-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
