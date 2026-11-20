<script setup lang="ts">
defineProps<{
  coverUrl: string
  title: string
  providerName: string
  // Beside the provider: 资质已审核 when the platform reviewed it (R6-4), else its own label; empty hides it.
  certificationLabel: string
  customer: { isOrganization: boolean; text: string } | null
}>()
</script>

<template>
  <view class="plan-summary">
    <image class="plan-summary__cover" :src="coverUrl" mode="aspectFill" />
    <view class="plan-summary__body">
      <text class="plan-summary__title">{{ title }}</text>
      <view v-if="providerName" class="plan-summary__line">
        <text class="plan-summary__text">服务商 {{ providerName }}</text>
        <text v-if="certificationLabel" class="plan-summary__chip plan-summary__chip--verified">
          {{ certificationLabel }}
        </text>
      </view>
      <view v-if="customer" class="plan-summary__line">
        <text v-if="customer.isOrganization" class="plan-summary__chip plan-summary__chip--organization">企业</text>
        <text class="plan-summary__text">{{ customer.text }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.plan-summary {
  display: flex;
  gap: 12px;
  padding: 12px;
  border-radius: 14px;
  background: #fff;
  /* The card shadow and hairline of the 2026-11-14 prototype's light theme. */
  box-shadow: var(--app-card-shadow);
}

.plan-summary__cover {
  flex-shrink: 0;
  width: 72px;
  height: 72px;
  border-radius: 10px;
  background: #f2f3f5;
}

.plan-summary__body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.plan-summary__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.plan-summary__line {
  display: flex;
  min-height: 20px;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px 6px;
}

.plan-summary__text {
  min-width: 0;
  color: #555a62;
  font-size: 13px;
  line-height: 20px;
}

.plan-summary__chip {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.plan-summary__chip--verified {
  color: #11804a;
  background: #e2f6ed;
}

.plan-summary__chip--organization {
  color: #1d63c4;
  background: #eaf2fd;
}
</style>
