<script setup lang="ts">
import { computed } from 'vue'
import type { FulfillmentProgressView } from '../fulfillment-view'

const props = defineProps<{
  coverUrl: string
  title: string
  providerName: string
  // Beside the provider: 资质已审核 when the platform reviewed it (R6-4), else its own label; empty hides it.
  certificationLabel: string
  progress: FulfillmentProgressView
}>()

// A long order would run out of width with the gap a six-visit order uses.
const segmentGap = computed(() => (props.progress.segments.length > 12 ? '1px' : '4px'))
</script>

<template>
  <view class="fulfillment-summary">
    <view class="fulfillment-summary__service">
      <image v-if="coverUrl" class="fulfillment-summary__cover" :src="coverUrl" mode="aspectFill" />
      <view class="fulfillment-summary__copy">
        <text class="fulfillment-summary__title">{{ title }}</text>
        <view v-if="providerName" class="fulfillment-summary__provider">
          <text class="fulfillment-summary__provider-name">服务商 {{ providerName }}</text>
          <text v-if="certificationLabel" class="fulfillment-summary__chip">{{ certificationLabel }}</text>
        </view>
      </view>
    </view>
    <view class="fulfillment-summary__divider" />
    <view class="fulfillment-summary__progress">
      <view class="fulfillment-summary__status">
        <text
          class="fulfillment-summary__status-label"
          :class="{ 'fulfillment-summary__status-label--finished': progress.isFinished }"
        >{{ progress.statusLabel }}</text>
        <text class="fulfillment-summary__count">{{ progress.countText }}</text>
      </view>
      <view
        class="fulfillment-summary__segments"
        :style="{ gap: segmentGap }"
        aria-label="巡检完成进度"
      >
        <view
          v-for="segment in progress.segments"
          :key="segment.id"
          class="fulfillment-summary__segment"
          :class="{ 'fulfillment-summary__segment--done': segment.done }"
        />
      </view>
      <text class="fulfillment-summary__schedule">{{ progress.scheduleText }}</text>
    </view>
  </view>
</template>

<style scoped>
.fulfillment-summary {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 12px;
  border-radius: 14px;
  background: #fff;
  /* The card shadow and hairline of the 2026-11-14 prototype's light theme. */
  box-shadow: var(--app-card-shadow);
}

.fulfillment-summary__service {
  display: flex;
  align-items: center;
  gap: 12px;
}

.fulfillment-summary__cover {
  flex-shrink: 0;
  width: 56px;
  height: 56px;
  border-radius: 10px;
  background: #f2f3f5;
}

.fulfillment-summary__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.fulfillment-summary__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.fulfillment-summary__provider {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 6px;
}

.fulfillment-summary__provider-name {
  color: #555a62;
  font-size: 13px;
  line-height: 20px;
}

.fulfillment-summary__chip {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.fulfillment-summary__divider {
  height: 1px;
  background: #f0f1f3;
}

.fulfillment-summary__progress {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.fulfillment-summary__status {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.fulfillment-summary__status-label {
  color: #d94f00;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
  white-space: nowrap;
}

.fulfillment-summary__status-label--finished {
  color: #11804a;
}

.fulfillment-summary__count {
  margin-left: auto;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.fulfillment-summary__segments {
  display: flex;
}

.fulfillment-summary__segment {
  min-width: 0;
  height: 6px;
  flex: 1;
  border-radius: 3px;
  background: #ebedf0;
}

.fulfillment-summary__segment--done {
  background: #ff6a00;
}

.fulfillment-summary__schedule {
  color: #8a8f97;
  font-size: 12px;
  line-height: 17px;
}
</style>
