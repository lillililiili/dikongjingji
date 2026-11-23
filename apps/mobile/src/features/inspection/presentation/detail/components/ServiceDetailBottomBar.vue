<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'

// The label comes from the page (立即预约, or 发布需求 on a project-priced service); 立即预约 by default.
withDefaults(defineProps<{
  disabled?: boolean
  label?: string
  actionKind?: string
}>(), { label: '立即预约', actionKind: 'RESERVE_SERVICE' })

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
        <LineIcon name="headset" :size="22" color="#536178" />
        <text>客服</text>
      </button>
      <!-- 收藏 of a preview build (plan 08 S5-6); the page decides whether it is there. -->
      <slot name="extra" />
      <button
        class="service-detail-bottom-bar__primary"
        :disabled="disabled"
        data-testid="service-detail-primary"
        :data-action="actionKind"
        @click="$emit('reserve')"
      >
        {{ label }}
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
  box-shadow: var(--app-bar-shadow);
}

.service-detail-bottom-bar__inner {
  display: flex;
  width: 100%;
  max-width: 600px;
  margin: 0 auto;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 10px;
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
  width: 56px;
  height: 44px;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #536178;
  background: transparent;
  font-size: 12px;
  line-height: 16px;
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
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
}

.service-detail-bottom-bar__primary[disabled] {
  color: #91a0b4;
  background: #eef0f3;
  box-shadow: none;
}
</style>
