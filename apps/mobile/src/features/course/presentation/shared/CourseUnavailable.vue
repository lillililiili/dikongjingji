<script setup lang="ts">
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'

// Public builds without the COURSE release switch: the course pages stay in the route table (a deep
// link must not be a dead end) and say the service is not open yet.
function goBack(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: '/pages/home/index' })
}
</script>

<template>
  <view class="course-closed" data-testid="course-unavailable">
    <AppPageHeader title="课程" plain test-id="course-unavailable-header" @back="goBack" />
    <view class="course-closed__body">
      <text class="course-closed__title">线上课程尚未开通</text>
      <text class="course-closed__copy">开通后可以在这里报名、试听和学习课程</text>
    </view>
  </view>
</template>

<style scoped>
.course-closed {
  min-height: 100vh;
  background: #f5f7fc;
}

.course-closed__body {
  display: flex;
  padding: 80px 24px;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.course-closed__title {
  color: #202c42;
  font-size: 16px;
  font-weight: 600;
}

.course-closed__copy {
  color: #738096;
  font-size: 13px;
}
</style>
