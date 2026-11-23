<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'

// The bottom confirmation of the service-order boards: 取消订单 (PlanConfirm), 确认验收 (DeliveryOne)
// and 退回整改. Extra content (the rework reason) goes in the default slot.
withDefaults(
  defineProps<{
    title: string
    text?: string
    keepLabel: string
    goLabel: string
    busy?: boolean
    goDisabled?: boolean
    rows?: Array<{ key: string; label: string; value: string }>
    // The orange warning of DeliveryOne's sheet.
    warning?: string
    testId?: string
  }>(),
  { text: '', busy: false, goDisabled: false, rows: () => [], warning: '', testId: 'service-order-sheet' },
)

const emit = defineEmits<{
  keep: []
  go: []
}>()
</script>

<template>
  <!-- Below the uni picker (999) like every sheet of the project. -->
  <view class="so-sheet" :data-testid="testId">
    <view class="so-sheet__mask" aria-label="关闭" @click="!busy && emit('keep')" />
    <view class="so-sheet__panel" role="dialog" aria-modal="true" :aria-label="title">
      <text class="so-sheet__title">{{ title }}</text>
      <text v-if="text" class="so-sheet__text" :data-testid="`${testId}-text`">{{ text }}</text>
      <view v-if="rows.length" class="so-sheet__rows">
        <template v-for="row in rows" :key="row.key">
          <text class="so-sheet__label">{{ row.label }}</text>
          <text class="so-sheet__value">{{ row.value }}</text>
        </template>
      </view>
      <slot />
      <view v-if="warning" class="so-sheet__warning">
        <LineIcon name="warning" :size="16" color="#ff6a00" :stroke-width="2" />
        <text class="so-sheet__warning-text">{{ warning }}</text>
      </view>
      <view class="so-sheet__actions">
        <button class="so-sheet__keep" :data-testid="`${testId}-keep`" @click="!busy && emit('keep')">
          {{ keepLabel }}
        </button>
        <button
          :class="['so-sheet__go', { 'so-sheet__go--off': goDisabled }]"
          :data-testid="`${testId}-go`"
          :disabled="busy || goDisabled"
          :aria-disabled="busy || goDisabled"
          @click="!busy && !goDisabled && emit('go')"
        >
          {{ busy ? '处理中…' : goLabel }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.so-sheet {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
}

.so-sheet__mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.so-sheet__panel {
  position: relative;
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 14px;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #ffffff;
  box-sizing: border-box;
}

.so-sheet__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.so-sheet__text {
  color: #536178;
  font-size: 14px;
  line-height: 20px;
}

.so-sheet__rows {
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 8px 12px;
  padding: 12px;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 18px;
}

.so-sheet__label {
  color: #627187;
  white-space: nowrap;
}

.so-sheet__value {
  min-width: 0;
  word-break: break-all;
}

.so-sheet__warning {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 10px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 13px;
  line-height: 18px;
}

.so-sheet__warning-text {
  min-width: 0;
}

.so-sheet__actions {
  display: flex;
  gap: 10px;
}

button {
  margin: 0;
  padding: 0;
  font-family: inherit;
}

button::after {
  border: 0;
}

.so-sheet__keep,
.so-sheet__go {
  height: 44px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
}

.so-sheet__keep {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
  box-sizing: border-box;
}

.so-sheet__go {
  border: 0;
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.so-sheet__go--off {
  color: #ffffff !important;
  background: #c9d3e1;
}
</style>
