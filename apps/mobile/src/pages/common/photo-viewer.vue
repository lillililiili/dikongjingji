<script setup lang="ts">
import { computed } from 'vue'

import PhotoViewerPage from '@/shared/photo/PhotoViewerPage.vue'
import { usePhotoViewerStore } from '@/shared/photo/photo-viewer'

// Opened with openPhotoViewer() (shared/photo/photo-viewer.ts). After a reload the pictures are
// gone (they never travel in the address), so the page says so and offers to go back.
const store = usePhotoViewerStore()
const request = computed(() => store.request)

function close(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: '/pages/home/index' })
}
</script>

<template>
  <PhotoViewerPage v-if="request && request.photos.length" :request="request" @close="close" />
  <view v-else class="photo-viewer-empty" data-testid="photo-viewer-empty">
    <text>图片已关闭，请返回重新打开</text>
    <button class="photo-viewer-empty__back" @click="close">返回</button>
  </view>
</template>

<style scoped>
.photo-viewer-empty {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: #c9d3e1;
  background: #111214;
  font-size: 14px;
}

.photo-viewer-empty__back {
  height: 40px;
  padding: 0 24px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  border-radius: 999px;
  color: #fff;
  background: transparent;
  font-size: 14px;
  line-height: 38px;
}
</style>
