<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

defineProps<{ terms: string[] }>()
</script>

<template>
  <view class="plan-terms">
    <text class="plan-terms__title">交付约定</text>
    <view v-for="term in terms" :key="term" class="plan-terms__row">
      <UniIcons type="checkmarkempty" size="16" color="#19b866" />
      <text class="plan-terms__text">{{ term }}</text>
    </view>
  </view>
</template>

<style scoped>
.plan-terms {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 8px;
  border-radius: 14px;
  background: #fff;
  /* The card shadow and hairline of the 2026-11-14 prototype's light theme. */
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.plan-terms__title {
  margin-bottom: 2px;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.plan-terms__row {
  display: flex;
  gap: 8px;
}

.plan-terms__row :deep(.uni-icons) {
  flex-shrink: 0;
  margin-top: 2px;
}

.plan-terms__text {
  min-width: 0;
  color: #555a62;
  font-size: 13px;
  line-height: 19px;
}
</style>
