<script setup lang="ts">
// What the search pages show in a public build, where the global search is not open yet.
</script>

<template>
  <view class="search-unavailable" data-testid="search-unavailable">
    <text>搜索功能尚未开通</text>
  </view>
</template>

<style scoped>
.search-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #738096;
  background: #f5f7fc;
  font-size: 14px;
}
</style>
