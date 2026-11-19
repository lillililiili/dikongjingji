<script setup lang="ts">
// What a venue booking page shows in a public build, where venue booking is not open yet.
</script>

<template>
  <view class="venue-unavailable" data-testid="venue-booking-unavailable">
    <text>到馆预约功能尚未开通</text>
  </view>
</template>

<style scoped>
.venue-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
