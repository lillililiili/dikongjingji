<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'

import type { InspectionPortalProcessStep } from '../../../domain/inspection-portal-content'

defineProps<{
  steps: readonly InspectionPortalProcessStep[]
}>()

// The four step icons of the HomeSvc board, keyed by the server's step codes.
const PROCESS_ICON_PATHS: Record<string, string> = {
  REQUEST: 'M7 3h7l5 5v13H7zM14 3v5h5M10 13h6M10 17h4',
  QUOTE: 'M9 4h6v3H9zM7 5H5v16h14V5h-2M9 14l2 2 4-4',
  EXECUTE: 'M12 10v4M8 12h8M5 6a2 2 0 1 0 0 .1M19 6a2 2 0 1 0 0 .1M5 18a2 2 0 1 0 0 .1M19 18a2 2 0 1 0 0 .1M7 8l3 3M17 8l-3 3M7 16l3-3M17 16l-3-3',
  REPORT: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM8.5 12l2.5 2.5 4.5-5',
}
const FALLBACK_ICON_PATH = PROCESS_ICON_PATHS.REPORT
</script>

<template>
  <view v-if="steps.length" class="inspection-process">
    <view class="inspection-process__heading">
      <text class="inspection-process__title">服务流程</text>
    </view>
    <view class="inspection-process__steps">
      <view v-for="(step, index) in steps" :key="step.code" class="inspection-process__step">
        <view class="inspection-process__icon">
          <LineIcon
            :d="PROCESS_ICON_PATHS[step.code] ?? FALLBACK_ICON_PATH"
            :size="16"
            :stroke-width="2"
            color="#2f80ed"
          />
        </view>
        <text class="inspection-process__label">{{ step.title }}</text>
        <text v-if="step.description" class="inspection-process__description">{{ step.description }}</text>
        <view v-if="index < steps.length - 1" class="inspection-process__arrow">
          <LineIcon name="chevronRight" :size="12" :stroke-width="2.4" color="#91a0b4" />
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board HomeSvc (390 wide). */
.inspection-process {
  display: flex;
  padding: 12px;
  flex-direction: column;
  gap: 8px;
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
  flex-wrap: wrap;
  row-gap: 10px;
  align-items: flex-start;
}

.inspection-process__step {
  position: relative;
  display: flex;
  min-width: 0;
  flex: 1 0 24%;
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
  max-width: 100%;
  overflow-wrap: anywhere;
  text-align: center;
}

.inspection-process__description {
  padding: 0 4px;
  color: #5c687c;
  font-size: 11px;
  text-align: center;
  overflow-wrap: anywhere;
}

.inspection-process__arrow {
  position: absolute;
  top: 8px;
  right: -6px;
  display: flex;
}
</style>
