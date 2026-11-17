<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { InspectionPortalProcessStep } from '../../../domain/inspection-portal-content'

defineProps<{
  steps: readonly InspectionPortalProcessStep[]
}>()

const PROCESS_ICONS: Record<string, string> = {
  REQUEST: 'compose',
  QUOTE: 'wallet-filled',
  EXECUTE: 'paperplane-filled',
  REPORT: 'checkbox-filled',
}
</script>

<template>
  <view v-if="steps.length" class="inspection-process">
    <view class="inspection-process__heading">
      <text class="inspection-process__title">服务流程</text>
    </view>
    <view class="inspection-process__steps">
      <template v-for="(step, index) in steps" :key="step.code">
        <view class="inspection-process__step">
          <view class="inspection-process__icon">
            <UniIcons
              :type="PROCESS_ICONS[step.code] ?? 'checkbox-filled'"
              size="20"
              color="#1d63c4"
            />
          </view>
          <text class="inspection-process__label">{{ step.title }}</text>
        </view>
        <UniIcons
          v-if="index < steps.length - 1"
          class="inspection-process__arrow"
          type="right"
          size="12"
          color="#91a0b4"
        />
      </template>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board HomeSvc (390 wide). */
.inspection-process {
  display: flex;
  padding: 12px 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.inspection-process__heading {
  display: flex;
  align-items: center;
}

.inspection-process__title {
  color: #202c42;
  font-size: 15px;
  font-weight: 700;
  line-height: 20px;
}

.inspection-process__steps {
  display: flex;
  align-items: flex-start;
}

.inspection-process__step {
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.inspection-process__icon {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #e6f0ff;
}

.inspection-process__label {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.inspection-process__arrow {
  margin-top: 8px;
  flex: 0 0 auto;
}
</style>
