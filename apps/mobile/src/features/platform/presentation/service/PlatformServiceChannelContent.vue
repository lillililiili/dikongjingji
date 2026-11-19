<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type { PlatformFeatureIntent } from '../../domain/platform-feature-intent'
import type {
  PlatformServiceChannelContent,
  PlatformServiceProcessStepCode,
} from '../../domain/platform-service-content'

defineProps<{
  content: PlatformServiceChannelContent
  typeLabel: string
}>()

const emit = defineEmits<{
  intent: [intent: PlatformFeatureIntent]
}>()

const PROCESS_ICONS: Record<PlatformServiceProcessStepCode, string> = {
  REQUEST: 'compose',
  QUOTE: 'wallet-filled',
  EXECUTE: 'calendar-filled',
  ACCEPT: 'checkbox-filled',
}
</script>

<template>
  <view class="service-entry">
    <view
      v-if="content.guarantees.length"
      class="service-entry__guarantees"
      data-testid="service-entry-guarantees"
    >
      <view class="service-entry__guarantee-heading">
        <UniIcons type="auth" size="15" color="#11804a" />
        <text>服务保障</text>
      </view>
      <view
        v-for="guarantee in content.guarantees"
        :key="guarantee"
        class="service-entry__guarantee"
      >
        <UniIcons type="checkmarkempty" size="12" color="#11804a" />
        <text>{{ guarantee }}</text>
      </view>
    </view>

    <view class="service-entry__card" data-testid="service-channel-notice">
      <view class="service-entry__card-head">
        <text class="service-entry__card-title">{{ typeLabel }}服务</text>
        <button
          class="service-entry__all"
          data-testid="service-entry-all"
          @click="emit('intent', { type: 'SEARCH', channel: content.code })"
        >
          查看全部服务 ›
        </button>
      </view>
      <text class="service-entry__card-copy">
        该类服务按项目报价，发布需求后由已审核的服务商报价
      </text>
      <button
        class="service-entry__publish"
        data-testid="service-entry-publish"
        @click="emit('intent', { type: 'PUBLISH_DEMAND', channel: content.code })"
      >
        发布需求
      </button>
    </view>

    <view v-if="content.processSteps.length" class="service-entry__process">
      <text class="service-entry__process-title">服务流程</text>
      <view class="service-entry__steps">
        <template v-for="(step, index) in content.processSteps" :key="step.code">
          <view class="service-entry__step">
            <view class="service-entry__step-icon">
              <UniIcons :type="PROCESS_ICONS[step.code]" size="16" color="#2f80ed" />
            </view>
            <text class="service-entry__step-label">{{ step.title }}</text>
          </view>
          <UniIcons
            v-if="index < content.processSteps.length - 1"
            class="service-entry__arrow"
            type="right"
            size="12"
            color="#91a0b4"
          />
        </template>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board HomeSvc (390 wide), types other than inspection. */
.service-entry {
  display: flex;
  flex-direction: column;
  gap: 10px;
  color: #202c42;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
}

button::after {
  border: 0;
}

.service-entry__guarantees {
  display: flex;
  min-height: 34px;
  padding: 4px 12px;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 2px 10px;
  border-radius: 10px;
  background: #eefaf3;
  box-sizing: border-box;
}

.service-entry__guarantee-heading {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #11804a;
  font-size: 13px;
  font-weight: 700;
  line-height: 18px;
  white-space: nowrap;
}

.service-entry__guarantee {
  display: flex;
  align-items: center;
  gap: 3px;
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.service-entry__card,
.service-entry__process {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.service-entry__card {
  padding: 14px;
  align-items: flex-start;
  gap: 4px;
}

.service-entry__card-head {
  display: flex;
  height: 24px;
  align-self: stretch;
  align-items: center;
}

.service-entry__card-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  white-space: nowrap;
}

.service-entry__all {
  height: 44px;
  margin: -10px 0 -10px auto;
  color: #627187;
  font-size: 13px;
  line-height: 44px;
  white-space: nowrap;
}

.service-entry__card-copy {
  color: #627187;
  font-size: 13px;
  line-height: 19px;
}

.service-entry__publish {
  height: 44px;
  margin-top: 8px;
  padding: 0 24px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
  line-height: 44px;
  white-space: nowrap;
}

.service-entry__process {
  padding: 12px;
  gap: 8px;
}

.service-entry__process-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 20px;
}

.service-entry__steps {
  display: flex;
  align-items: flex-start;
}

.service-entry__step {
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.service-entry__step-icon {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #e6f0ff;
}

.service-entry__step-label {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.service-entry__arrow {
  margin-top: 8px;
  flex: 0 0 auto;
}
</style>
