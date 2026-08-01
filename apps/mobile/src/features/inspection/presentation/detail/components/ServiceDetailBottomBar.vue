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
        <UniIcons type="headphones" size="21" color="#262a30" />
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
.service-detail-bottom-bar {
  position: fixed;
  z-index: 50;
  right: 0;
  bottom: 0;
  left: 0;
  padding-bottom: env(safe-area-inset-bottom);
  border-top: 1px solid #eceef1;
  background: rgba(255, 255, 255, 0.98);
  box-sizing: border-box;
  box-shadow: 0 -5px 18px rgba(26, 32, 40, 0.07);
}

.service-detail-bottom-bar__inner {
  display: grid;
  width: 100%;
  max-width: 600px;
  min-height: 66px;
  margin: 0 auto;
  padding: 8px 12px;
  grid-template-columns: 56px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  box-sizing: border-box;
}

.service-detail-bottom-bar__secondary {
  display: flex;
  min-width: 0;
  height: 44px;
  margin: 0;
  padding: 1px 0 0;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 3px;
  border: 0;
  color: #383d45;
  background: transparent;
  font-size: 11px;
  line-height: 1;
}

.service-detail-bottom-bar__primary {
  width: 100%;
  min-height: 48px;
  margin: 0;
  padding: 0 20px;
  border: 0;
  border-radius: 999px;
  color: #ffffff;
  background: linear-gradient(90deg, #ff8a00 0%, #ff4d00 100%);
  box-sizing: border-box;
  box-shadow: 0 5px 13px rgba(255, 91, 0, 0.24);
  font-size: 18px;
  font-weight: 700;
  line-height: 48px;
}

.service-detail-bottom-bar__secondary::after,
.service-detail-bottom-bar__primary::after {
  border: 0;
}

.service-detail-bottom-bar__primary[disabled] {
  opacity: 0.55;
}
</style>
