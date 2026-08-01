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
.detail-section {
  margin: 0 12px;
  padding: 13px 14px;
  border: 1px solid #f0f1f3;
  border-radius: 18px;
  background: #fff;
  box-sizing: border-box;
  box-shadow: 0 6px 20px rgba(30, 38, 48, 0.055);
}

.detail-section__title {
  display: block;
  color: #16191e;
  font-size: 17px;
  font-weight: 740;
}

.service-process__track {
  position: relative;
  display: grid;
  margin-top: 10px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.service-process__track::before {
  position: absolute;
  top: 12px;
  right: 12.5%;
  left: 12.5%;
  height: 2px;
  background: #ff6500;
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
  width: 26px;
  height: 26px;
  align-items: center;
  justify-content: center;
  border: 3px solid #fff;
  border-radius: 50%;
  color: #fff;
  background: #ff6500;
  box-sizing: border-box;
  box-shadow: 0 0 0 1px rgba(255, 101, 0, 0.16);
  font-size: 12px;
  font-weight: 700;
}

.service-process__label {
  margin-top: 5px;
  color: #3f4753;
  font-size: 11px;
  line-height: 1.35;
  text-align: center;
  white-space: nowrap;
}

@media (max-width: 359px) {
  .detail-section {
    margin-right: 9px;
    margin-left: 9px;
    padding-right: 10px;
    padding-left: 10px;
  }

  .service-process__label {
    font-size: 10px;
  }
}
</style>
