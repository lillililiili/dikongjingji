<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

defineEmits<{
  back: []
  share: []
}>()
</script>

<template>
  <view class="service-detail-header">
    <view class="service-detail-header__inner">
      <button
        class="service-detail-header__action"
        aria-label="返回巡检服务列表"
        @click="$emit('back')"
      >
        <UniIcons type="left" size="22" color="#202c42" />
      </button>
      <text class="service-detail-header__title">服务详情</text>
      <button
        class="service-detail-header__action"
        aria-label="分享服务详情"
        @click="$emit('share')"
      >
        <UniIcons type="redo" size="20" color="#202c42" />
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspDetail (390 wide). */
.service-detail-header {
  position: fixed;
  top: 0;
  right: 0;
  left: 0;
  z-index: 20;
  padding-top: var(--status-bar-height);
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.service-detail-header__inner {
  display: flex;
  width: 100%;
  max-width: 600px;
  height: 60px;
  margin: 0 auto;
  padding: 8px;
  align-items: center;
  gap: 4px;
  box-sizing: border-box;
}

.service-detail-header__action {
  display: flex;
  width: 44px;
  height: 44px;
  margin: 0;
  padding: 0;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
}

.service-detail-header__action::after {
  border: 0;
}

.service-detail-header__title {
  min-width: 0;
  flex: 1;
  color: #202c42;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}
</style>
