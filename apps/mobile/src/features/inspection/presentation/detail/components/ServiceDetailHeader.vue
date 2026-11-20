<script setup lang="ts">
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

defineEmits<{
  back: []
  share: []
}>()
</script>

<template>
  <!-- Board InspDetail: the shared light page head, fixed above the scrolling page. -->
  <view class="service-detail-header">
    <AppPageHeader title="服务详情" test-id="inspection-detail" @back="$emit('back')">
      <template #actions>
        <button
          class="service-detail-header__share"
          data-testid="inspection-detail-share"
          aria-label="分享服务详情"
          @click="$emit('share')"
        >
          <LineIcon name="share" :size="22" color="#202c42" />
        </button>
      </template>
    </AppPageHeader>
  </view>
</template>

<style scoped>
.service-detail-header {
  position: fixed;
  top: 0;
  right: 0;
  left: 0;
  z-index: 20;
}

.service-detail-header__share {
  display: flex;
  width: 44px;
  height: 44px;
  margin: 0;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
}

.service-detail-header__share::after {
  border: 0;
}
</style>
