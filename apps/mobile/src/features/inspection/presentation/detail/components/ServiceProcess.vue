<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import type { DetailProcessStep } from '../../../domain/inspection-service-detail'
import { processStepIcon } from '../detail-line-icons'

defineProps<{
  steps: readonly DetailProcessStep[]
}>()
</script>

<template>
  <!-- Board InspDetail 服务流程: 28px pale-blue circles with a blue line icon, “›” between steps. -->
  <section class="detail-section service-process" data-testid="detail-process">
    <text class="detail-section__title">服务流程</text>
    <view class="service-process__track">
      <template v-for="(step, index) in steps" :key="step.code">
        <view class="service-process__step">
          <view class="service-process__marker">
            <LineIcon :d="processStepIcon(step.label, index)" :size="16" color="#2f80ed" :stroke-width="2" />
          </view>
          <text class="service-process__label">{{ step.label }}</text>
        </view>
        <view v-if="index < steps.length - 1" class="service-process__arrow" aria-hidden="true">
          <LineIcon name="chevronRight" :size="12" color="#91a0b4" :stroke-width="2.4" />
        </view>
      </template>
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
  box-shadow: var(--app-card-shadow);
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
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.service-process__marker {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #e6f0ff;
}

.service-process__arrow {
  display: flex;
  flex: none;
  margin: 8px -6px 0;
}

.service-process__label {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}
</style>
