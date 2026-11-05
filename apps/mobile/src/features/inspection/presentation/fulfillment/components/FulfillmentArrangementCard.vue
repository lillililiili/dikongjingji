<script setup lang="ts">
import type { FulfillmentArrangementView } from '../fulfillment-view'

defineProps<{ arrangement: FulfillmentArrangementView }>()

const emit = defineEmits<{ contactLead: [] }>()
</script>

<template>
  <view class="fulfillment-arrangement">
    <text class="fulfillment-arrangement__title">履约安排</text>
    <view v-if="arrangement.lead" class="fulfillment-arrangement__lead">
      <text class="fulfillment-arrangement__label">项目负责人</text>
      <view class="fulfillment-arrangement__person">
        <text class="fulfillment-arrangement__name">{{ arrangement.lead.name }}</text>
        <text v-if="arrangement.lead.mobileText" class="fulfillment-arrangement__mobile">{{ arrangement.lead.mobileText }}</text>
      </view>
      <button class="fulfillment-arrangement__contact" @click="emit('contactLead')">联系负责人</button>
    </view>
    <view v-for="row in arrangement.rows" :key="row.label" class="fulfillment-arrangement__row">
      <text class="fulfillment-arrangement__label">{{ row.label }}</text>
      <text class="fulfillment-arrangement__value">{{ row.value }}</text>
    </view>
    <view
      v-if="!arrangement.lead && !arrangement.rows.length"
      class="fulfillment-arrangement__row"
    >
      <text class="fulfillment-arrangement__value">服务商正在安排人员和设备</text>
    </view>
  </view>
</template>

<style scoped>
.fulfillment-arrangement {
  display: flex;
  padding: 14px 14px 4px;
  flex-direction: column;
  border-radius: 14px;
  background: #fff;
}

.fulfillment-arrangement__title {
  margin-bottom: 4px;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.fulfillment-arrangement__lead,
.fulfillment-arrangement__row {
  display: flex;
  align-items: center;
  gap: 12px;
  border-top: 1px solid #f0f1f3;
}

.fulfillment-arrangement__lead {
  min-height: 60px;
}

.fulfillment-arrangement__row {
  min-height: 44px;
}

.fulfillment-arrangement__label {
  flex-shrink: 0;
  width: 72px;
  color: #666b73;
  font-size: 13px;
  line-height: 18px;
}

.fulfillment-arrangement__person {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.fulfillment-arrangement__name {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.fulfillment-arrangement__mobile {
  color: #666b73;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.fulfillment-arrangement__contact {
  height: 36px;
  flex-shrink: 0;
  margin: 0;
  padding: 0 14px;
  box-sizing: border-box;
  border: 1px solid #ffd8b8;
  border-radius: 999px;
  color: #d94f00;
  background: #fffaf5;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
}

.fulfillment-arrangement__contact::after {
  border: 0;
}

.fulfillment-arrangement__value {
  min-width: 0;
  flex: 1;
  font-size: 14px;
  line-height: 20px;
}

@media (max-width: 340px) {
  .fulfillment-arrangement__contact {
    padding: 0 10px;
  }
}
</style>
