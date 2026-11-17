<script setup lang="ts">
import type { DetailProcessStep } from '../../../domain/inspection-service-detail'

defineProps<{
  steps: readonly DetailProcessStep[]
}>()
</script>

<template>
  <section class="detail-section service-process">
    <text class="detail-section__title">服务流程</text>
    <view class="service-process__track">
      <view
        v-for="(step, index) in steps"
        :key="step.code"
        class="service-process__step"
      >
        <view class="service-process__marker">{{ index + 1 }}</view>
        <text class="service-process__label">{{ step.label }}</text>
      </view>
    </view>
  </section>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board InspDetail (390 wide). */
.detail-section {
  display: flex;
  margin: 0 16px;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
  box-sizing: border-box;
}

.detail-section__title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.service-process__track {
  display: flex;
  align-items: flex-start;
}

.service-process__step {
  position: relative;
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

/* A short line to the next step, in place of the board's arrow. */
.service-process__step + .service-process__step::before {
  position: absolute;
  top: 14px;
  right: calc(50% + 20px);
  left: calc(-50% + 20px);
  height: 1px;
  background: #c9d3e1;
  content: '';
}

.service-process__marker {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #1d63c4;
  background: #e6f0ff;
  font-size: 13px;
  font-weight: 700;
}

.service-process__label {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}
</style>
