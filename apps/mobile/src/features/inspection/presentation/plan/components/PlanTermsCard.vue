<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'

defineProps<{ terms: string[] }>()
</script>

<template>
  <view class="plan-terms">
    <text class="plan-terms__title">交付约定</text>
    <view v-for="term in terms" :key="term" class="plan-terms__row">
      <LineIcon name="check" :size="16" color="#159a70" :stroke-width="2.4" />
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
  box-shadow: var(--app-card-shadow);
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
