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
        <UniIcons type="left" size="20" color="#17191d" />
      </button>
      <text class="service-detail-header__title">服务详情</text>
      <button
        class="service-detail-header__action"
        aria-label="分享服务详情"
        @click="$emit('share')"
      >
        <UniIcons type="redo" size="20" color="#17191d" />
      </button>
    </view>
  </view>
</template>

<style scoped>
.service-detail-header {
  position: fixed;
  z-index: 40;
  top: 0;
  right: 0;
  left: 0;
  padding-top: env(safe-area-inset-top);
  border-bottom: 1px solid rgba(235, 237, 240, 0.74);
  background: rgba(255, 255, 255, 0.97);
  box-sizing: border-box;
}

.service-detail-header__inner {
  display: grid;
  width: 100%;
  max-width: 600px;
  height: 36px;
  margin: 0 auto;
  padding: 0 10px;
  grid-template-columns: 48px minmax(0, 1fr) 48px;
  align-items: center;
  box-sizing: border-box;
}

.service-detail-header__action {
  display: flex;
  width: 44px;
  height: 44px;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
}

.service-detail-header__action::after {
  border: 0;
}

.service-detail-header__title {
  overflow: hidden;
  color: #17191d;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.02em;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
