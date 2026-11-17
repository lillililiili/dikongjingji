<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

defineProps<{
  disabled?: boolean
}>()

defineEmits<{
  customerService: []
  reserve: []
}>()
</script>

<template>
  <view class="service-detail-bottom-bar">
    <view class="service-detail-bottom-bar__inner">
      <button
        class="service-detail-bottom-bar__secondary"
        aria-label="联系客服"
        @click="$emit('customerService')"
      >
        <UniIcons type="headphones" size="20" color="#536178" />
        <text>客服</text>
      </button>
      <button
        class="service-detail-bottom-bar__primary"
        :disabled="disabled"
        @click="$emit('reserve')"
      >
        立即预约
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspDetail (390 wide). */
.service-detail-bottom-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 20;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: 0 -4px 18px rgba(38, 59, 98, 0.06);
}

.service-detail-bottom-bar__inner {
  display: flex;
  width: 100%;
  max-width: 600px;
  margin: 0 auto;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 12px;
  box-sizing: border-box;
}

.service-detail-bottom-bar__secondary,
.service-detail-bottom-bar__primary {
  margin: 0;
  padding: 0;
  border: 0;
  line-height: 1.2;
}

.service-detail-bottom-bar__secondary::after,
.service-detail-bottom-bar__primary::after {
  border: 0;
}

.service-detail-bottom-bar__secondary {
  display: flex;
  width: 46px;
  height: 46px;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #536178;
  background: transparent;
  font-size: 11px;
  line-height: 15px;
}

.service-detail-bottom-bar__primary {
  display: flex;
  min-width: 0;
  height: 46px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  font-size: 16px;
  font-weight: 500;
}

.service-detail-bottom-bar__primary[disabled] {
  color: #91a0b4;
  background: #eef0f3;
  box-shadow: none;
}
</style>
