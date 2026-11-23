<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'

// The floating line above the bar when a command did not go through (as the demand pages do).
defineProps<{ text: string }>()
const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <view class="so-notice" role="status" data-testid="service-order-notice">
    <LineIcon name="info" :size="14" color="#ff6a00" :stroke-width="2" />
    <text class="so-notice__text">{{ text }}</text>
    <button class="so-notice__close" aria-label="关闭提示" @click="emit('close')">
      <LineIcon name="close" :size="14" color="#ba470b" :stroke-width="2" />
    </button>
  </view>
</template>

<style scoped>
/* PlanCompare board's hint: orange tint, 10px corners, a shadow, above the bottom bar. */
.so-notice {
  position: fixed;
  z-index: 20;
  left: 16px;
  right: 16px;
  bottom: calc(92px + env(safe-area-inset-bottom));
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid #ffd8b8;
  border-radius: 10px;
  color: #ba470b;
  background: #fff0e3;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  font-size: 12px;
  line-height: 17px;
}

.so-notice__text {
  min-width: 0;
  flex: 1;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  line-height: 1;
}

button::after {
  border: 0;
}

.so-notice__close {
  display: flex;
  width: 24px;
  height: 24px;
  margin: -4px -4px -4px 0;
  align-items: center;
  justify-content: center;
}
</style>
