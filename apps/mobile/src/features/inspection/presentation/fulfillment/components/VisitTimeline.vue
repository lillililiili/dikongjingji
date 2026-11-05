<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import type { VisitRow } from '../fulfillment-view'

defineProps<{
  rows: VisitRow[]
  methodsText: string
}>()

const emit = defineEmits<{ openDelivery: [visitId: string] }>()
</script>

<template>
  <view class="visit-timeline">
    <view class="visit-timeline__head">
      <view class="visit-timeline__title-line">
        <text class="visit-timeline__title">巡检进度</text>
        <text class="visit-timeline__total">共 {{ rows.length }} 次</text>
      </view>
      <text v-if="methodsText" class="visit-timeline__methods">每次巡检：{{ methodsText }}</text>
    </view>
    <view class="visit-timeline__list" aria-label="巡检进度">
      <view
        v-for="(row, index) in rows"
        :key="row.id"
        class="visit-row"
        :class="[`visit-row--${row.tone}`, { 'visit-row--expanded': row.isExpanded, 'visit-row--next': row.isNext }]"
        :data-testid="`visit-row-${row.visitNo}`"
      >
        <view class="visit-row__rail">
          <view v-if="index < rows.length - 1" class="visit-row__line" />
          <view class="visit-row__dot">
            <UniIcons
              v-if="row.tone === 'delivered' || row.tone === 'accepted'"
              type="checkmarkempty"
              size="11"
              color="#ffffff"
            />
            <view v-else-if="row.tone === 'active' || row.isNext" class="visit-row__dot-core" />
          </view>
        </view>

        <view v-if="row.isExpanded" class="visit-row__card">
          <view class="visit-row__card-head">
            <view class="visit-row__copy">
              <text class="visit-row__card-title">{{ row.title }}</text>
              <text class="visit-row__status">{{ row.statusText }}</text>
            </view>
            <text v-if="row.badge" class="visit-row__badge">{{ row.badge }}</text>
          </view>
          <view v-if="row.events.length" class="visit-row__events">
            <template v-for="event in row.events" :key="event.id">
              <text class="visit-row__event-time">{{ event.time }}</text>
              <text class="visit-row__event-text">{{ event.text }}</text>
            </template>
          </view>
          <button
            v-if="row.deliveryLabel"
            class="visit-row__delivery"
            @click="emit('openDelivery', row.id)"
          >
            {{ row.deliveryLabel }} ›
          </button>
        </view>

        <view v-else class="visit-row__body">
          <view class="visit-row__copy">
            <text class="visit-row__title">{{ row.title }}</text>
            <text class="visit-row__status">{{ row.statusText }}</text>
          </view>
          <button
            v-if="row.deliveryLabel"
            class="visit-row__link"
            :aria-label="row.deliveryLabel"
            @click="emit('openDelivery', row.id)"
          >
            <text>查看交付</text>
            <UniIcons type="right" size="14" color="#a3a8b0" />
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.visit-timeline {
  display: flex;
  padding: 14px 14px 2px;
  flex-direction: column;
  gap: 12px;
  border-radius: 14px;
  background: #fff;
}

.visit-timeline__head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.visit-timeline__title-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.visit-timeline__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.visit-timeline__total {
  margin-left: auto;
  color: #8a8f97;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.visit-timeline__methods {
  color: #666b73;
  font-size: 12px;
  line-height: 17px;
}

.visit-timeline__list {
  display: flex;
  flex-direction: column;
}

.visit-row {
  display: flex;
  padding-bottom: 12px;
  gap: 10px;
}

.visit-row__rail {
  position: relative;
  flex-shrink: 0;
  width: 16px;
}

.visit-row__line {
  position: absolute;
  top: 10px;
  bottom: -22px;
  left: 7px;
  width: 2px;
  background: #ebedf0;
}

.visit-row--expanded .visit-row__line {
  top: 21px;
}

.visit-row--delivered .visit-row__line,
.visit-row--accepted .visit-row__line {
  background: #ffd8b8;
}

.visit-row__dot {
  position: absolute;
  top: 2px;
  left: 0;
  display: flex;
  width: 16px;
  height: 16px;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  border: 2px solid #c4c8ce;
  border-radius: 50%;
  background: #fff;
}

.visit-row--expanded .visit-row__dot {
  top: 13px;
}

.visit-row--next .visit-row__dot,
.visit-row--active .visit-row__dot {
  border-color: #ff6a00;
}

.visit-row--delivered .visit-row__dot {
  border-color: #ff6a00;
  background: #ff6a00;
}

.visit-row--accepted .visit-row__dot {
  border-color: #19b866;
  background: #19b866;
}

.visit-row__dot-core {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ff6a00;
}

.visit-row__card {
  display: flex;
  min-width: 0;
  flex: 1;
  padding: 10px 12px 12px;
  flex-direction: column;
  gap: 8px;
  border: 1px solid #ffd8b8;
  border-radius: 10px;
  background: #fffaf5;
}

.visit-row__card-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.visit-row__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.visit-row__card-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.visit-row__title {
  color: #555a62;
  font-size: 14px;
  line-height: 20px;
}

.visit-row--next .visit-row__title,
.visit-row--accepted .visit-row__title {
  color: #191919;
}

.visit-row--next .visit-row__title {
  font-weight: 500;
}

.visit-row__status {
  color: #8a8f97;
  font-size: 12px;
  line-height: 17px;
}

.visit-row--delivered .visit-row__status,
.visit-row--accepted .visit-row__status {
  color: #11804a;
}

.visit-row--active .visit-row__status {
  color: #d94f00;
}

.visit-row--next .visit-row__status {
  color: #1d63c4;
}

.visit-row__badge {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 6px;
  color: #fff;
  background: #ff6a00;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.visit-row__events {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 4px 8px;
  font-size: 12px;
  line-height: 17px;
}

.visit-row__event-time {
  color: #8a8f97;
  white-space: nowrap;
}

.visit-row__event-text {
  color: #555a62;
}

.visit-row__delivery {
  height: 36px;
  margin: 0 auto 0 0;
  padding: 0 14px;
  box-sizing: border-box;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #d94f00;
  background: #fff;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
}

.visit-row__body {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 8px;
}

.visit-row__link {
  display: flex;
  height: 44px;
  flex-shrink: 0;
  margin: -4px 0;
  padding: 0;
  align-items: center;
  gap: 2px;
  border: 0;
  color: #d94f00;
  background: transparent;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.visit-row__delivery::after,
.visit-row__link::after {
  border: 0;
}
</style>
