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
        <view class="service-process__marker">
          <text>{{ index + 1 }}</text>
        </view>
        <text class="service-process__label">{{ step.label }}</text>
      </view>
    </view>
  </section>
</template>

<style scoped>
.detail-section {
  margin: 0 7px;
  padding: 6px;
  border: 1px solid #edf0f2;
  border-radius: 11px;
  background: #ffffff;
  box-sizing: border-box;
  box-shadow: 0 3px 14px rgba(28, 36, 46, 0.055);
}

.detail-section__title {
  display: block;
  color: #1d2024;
  font-size: 14px;
  font-weight: 700;
}

.service-process__track {
  position: relative;
  display: grid;
  margin-top: 3px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.service-process__track::before {
  position: absolute;
  top: 9px;
  right: 12.5%;
  left: 12.5%;
  height: 2px;
  background: #ff6a00;
  content: "";
}

.service-process__step {
  position: relative;
  z-index: 1;
  display: flex;
  min-width: 0;
  align-items: center;
  flex-direction: column;
}

.service-process__marker {
  display: flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border: 3px solid #ffffff;
  border-radius: 50%;
  color: #ffffff;
  background: #ff6a00;
  box-sizing: border-box;
  box-shadow: 0 0 0 1px rgba(255, 106, 0, 0.18);
  font-size: 11px;
  font-weight: 700;
}

.service-process__label {
  margin-top: 3px;
  color: #42474f;
  font-size: 9px;
  line-height: 1.35;
  text-align: center;
  white-space: nowrap;
}

@media (max-width: 359px) {
  .detail-section {
    margin-right: 6px;
    margin-left: 6px;
    padding-right: 7px;
    padding-left: 7px;
  }

  .service-process__label {
    font-size: 9px;
  }
}
</style>
