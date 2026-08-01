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
      <view class="inspection-process__note">
        <text>了解详情</text>
        <UniIcons type="right" size="11" color="#7a828d" />
      </view>
    </view>
    <view class="inspection-process__steps">
      <template v-for="(step, index) in steps" :key="step.code">
        <view class="inspection-process__step">
          <view class="inspection-process__icon">
            <UniIcons
              :type="PROCESS_ICONS[step.code] ?? 'checkbox-filled'"
              size="20"
              color="#2f80ed"
            />
          </view>
          <text class="inspection-process__label">{{ step.title }}</text>
        </view>
        <UniIcons
          v-if="index < steps.length - 1"
          class="inspection-process__arrow"
          type="right"
          size="16"
          color="#6aa4f2"
        />
      </template>
    </view>
  </view>
</template>

<style scoped>
.inspection-process {
  padding: 10px;
  border: 1px solid #dbe8fb;
  border-radius: clamp(11px, 2.8vw, 17px);
  background: linear-gradient(135deg, #ffffff, #f2f7ff);
}

.inspection-process__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.inspection-process__title {
  color: #191919;
  font-size: 17px;
  font-weight: 750;
}

.inspection-process__note {
  display: flex;
  align-items: center;
  gap: 3px;
  color: #7a828d;
  font-size: 11px;
}

.inspection-process__steps {
  display: flex;
  margin-top: 8px;
  align-items: center;
  justify-content: space-between;
}

.inspection-process__step {
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
}

.inspection-process__icon {
  display: flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border: 1px solid #c8dcfa;
  border-radius: 50%;
  color: #2f80ed;
  background: #ffffff;
  font-size: 10px;
  font-weight: 800;
  box-shadow: 0 3px 8px rgba(47, 128, 237, 0.1);
}

.inspection-process__label {
  margin-top: 4px;
  color: #3b4149;
  font-size: 10px;
  text-align: center;
  white-space: nowrap;
}

.inspection-process__arrow {
  flex: 0 0 auto;
}

@media (max-width: 359px) {
  .inspection-process {
    padding-right: 7px;
    padding-left: 7px;
  }

  .inspection-process__icon {
    width: 30px;
    height: 30px;
  }

}
</style>
