<script setup lang="ts">
import { computed, ref } from 'vue'

import type { PlatformProvince } from '../city/domain/city-directory'
import LineIcon from '../ui/LineIcon.vue'
import {
  describeRegionPick,
  locatedRegion,
  pickRegionCity,
  pickRegionProvince,
  startRegionPick,
  type RegionPickState,
} from './region-pick'

// Board RegionPick: a dialog over the form with the current city (使用 fills it in one tap), the
// 省份 / 城市 tabs, the list, 已选 and 确认. The platform directory goes down to prefecture-level
// cities; the district is written in the detailed address, which the note says (M13).
const props = withDefaults(defineProps<{
  provinces: readonly PlatformProvince[]
  cityCode: string | null
  // The App's current city, offered as 当前城市.
  currentCityCode: string | null
  loading?: boolean
  // Prefix of the test ids: `${testId}-province-…`, `${testId}-city-…`.
  testId?: string
}>(), { loading: false, testId: 'region' })

const emit = defineEmits<{
  close: []
  confirm: [cityCode: string]
}>()

const LOCATION_ICON = 'M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11zM12 7.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z'

const state = ref<RegionPickState>(startRegionPick(props.provinces, props.cityCode))
const province = computed(() => props.provinces.find((item) => item.code === state.value.provinceCode) ?? null)
const city = computed(() => province.value?.cities.find((item) => item.code === state.value.cityCode) ?? null)
const located = computed(() => locatedRegion(props.provinces, props.currentCityCode))
const picked = computed(() => describeRegionPick(props.provinces, state.value))

function useLocated(): void {
  if (!located.value) return
  const own = props.provinces.find((item) => item.cities.some((entry) => entry.code === located.value?.cityCode))
  state.value = { step: 'CITY', provinceCode: own?.code ?? null, cityCode: located.value.cityCode }
}

function selectTab(step: 'PROVINCE' | 'CITY'): void {
  // The city tab opens only after a province.
  if (step === 'CITY' && !province.value) return
  state.value = { ...state.value, step }
}

function confirm(): void {
  if (state.value.cityCode) emit('confirm', state.value.cityCode)
}
</script>

<template>
  <view class="region-pick">
    <view class="region-pick__mask" aria-label="关闭" @click="emit('close')" />
    <view class="region-pick__sheet" role="dialog" aria-modal="true" aria-label="选择所在地区" :data-testid="`${testId}-region-picker`">
      <view class="region-pick__head">
        <text class="region-pick__title">选择所在地区</text>
        <button class="region-pick__close" aria-label="关闭" :data-testid="`${testId}-region-close`" @click="emit('close')">
          <LineIcon name="close" :size="20" color="#536178" :stroke-width="2" />
        </button>
      </view>

      <view v-if="located" class="region-pick__located">
        <LineIcon :d="LOCATION_ICON" :size="20" color="#ff6a00" :stroke-width="2" />
        <view class="region-pick__located-body">
          <text class="region-pick__located-label">当前城市</text>
          <text class="region-pick__located-text">{{ located.text }}</text>
        </view>
        <button class="region-pick__use" :data-testid="`${testId}-region-use-located`" @click="useLocated">使用</button>
      </view>

      <view class="region-pick__tabs" role="tablist" aria-label="省市">
        <button
          :class="['region-pick__tab', { 'region-pick__tab--on': state.step === 'PROVINCE' }]"
          role="tab"
          :aria-selected="state.step === 'PROVINCE'"
          @click="selectTab('PROVINCE')"
        >
          {{ province?.name ?? '请选择' }}
          <view v-if="state.step === 'PROVINCE'" class="region-pick__tab-bar" />
        </button>
        <button
          :class="['region-pick__tab', { 'region-pick__tab--on': state.step === 'CITY', 'region-pick__tab--off': !province }]"
          role="tab"
          :aria-selected="state.step === 'CITY'"
          @click="selectTab('CITY')"
        >
          {{ city?.name ?? '请选择' }}
          <view v-if="state.step === 'CITY'" class="region-pick__tab-bar" />
        </button>
      </view>

      <scroll-view class="region-pick__list" scroll-y role="listbox" :aria-label="state.step === 'PROVINCE' ? '省份' : '城市'">
        <text v-if="loading && provinces.length === 0" class="region-pick__note">正在读取地区列表…</text>
        <template v-if="state.step === 'PROVINCE'">
          <button
            v-for="item in provinces"
            :key="item.code"
            :class="['region-pick__item', { 'region-pick__item--on': item.code === state.provinceCode }]"
            role="option"
            :aria-selected="item.code === state.provinceCode"
            :data-testid="`${testId}-province-${item.code}`"
            @click="state = pickRegionProvince(state, item)"
          >
            <text class="region-pick__item-name">{{ item.name }}</text>
            <LineIcon v-if="item.code === state.provinceCode" name="check" :size="18" color="#ff6a00" :stroke-width="2.6" />
          </button>
        </template>
        <template v-else-if="province">
          <button
            v-for="item in province.cities"
            :key="item.code"
            :class="['region-pick__item', { 'region-pick__item--on': item.code === state.cityCode }]"
            role="option"
            :aria-selected="item.code === state.cityCode"
            :data-testid="`${testId}-city-${item.code}`"
            @click="state = pickRegionCity(state, item.code)"
          >
            <text class="region-pick__item-name">{{ item.name }}</text>
            <LineIcon v-if="item.code === state.cityCode" name="check" :size="18" color="#ff6a00" :stroke-width="2.6" />
          </button>
        </template>
      </scroll-view>
      <text class="region-pick__note">地区选到市；区县、街道和门牌请写在详细地址里</text>
      <text class="region-pick__picked" :data-testid="`${testId}-region-picked`">已选：{{ picked }}</text>

      <button
        class="region-pick__confirm"
        :data-testid="`${testId}-region-confirm`"
        :disabled="!state.cityCode"
        @click="confirm"
      >
        {{ state.cityCode ? '确认' : '请选择到城市' }}
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 board RegionPick (390 wide). Above the address sheet
   (900) and below uni's own pickers (999). */
.region-pick {
  position: fixed;
  z-index: 950;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.region-pick__mask {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  background: rgba(0, 0, 0, 0.45);
}

.region-pick__sheet {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  height: min(680px, 86vh);
  flex-direction: column;
  padding: 8px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #fff;
  box-sizing: border-box;
}

.region-pick__head {
  display: flex;
  flex: none;
  align-items: center;
}

.region-pick__title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.region-pick__close {
  display: flex;
  width: 44px;
  height: 44px;
  margin-left: auto;
  align-items: center;
  justify-content: center;
}

.region-pick__located {
  display: flex;
  min-height: 52px;
  flex: none;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  padding: 0 12px;
  border: 1px solid #ffd8b8;
  border-radius: 12px;
  background: #fffaf5;
}

.region-pick__located-body {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 1px;
}

.region-pick__located-label {
  color: #627187;
  font-size: 12px;
  line-height: 16px;
}

.region-pick__located-text {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.region-pick__use {
  height: 32px;
  flex: none;
  padding: 0 12px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #fff;
  font-size: 13px;
  line-height: 30px;
  box-sizing: border-box;
}

.region-pick__tabs {
  display: flex;
  flex: none;
  gap: 16px;
  margin-top: 6px;
  border-bottom: 1px solid #f0f1f3;
}

.region-pick__tab {
  position: relative;
  height: 44px;
  padding: 0 2px;
  color: #202c42;
  font-size: 15px;
  font-weight: 400;
  line-height: 44px;
  white-space: nowrap;
}

.region-pick__tab--on {
  color: #ff6a00;
  font-weight: 700;
}

.region-pick__tab--off {
  color: #91a0b4;
}

.region-pick__tab-bar {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 3px;
  border-radius: 3px;
  background: #ff6a00;
}

.region-pick__list {
  min-height: 0;
  flex: 1 1 auto;
}

.region-pick__item {
  display: flex;
  width: 100%;
  height: 48px;
  align-items: center;
  gap: 10px;
  padding: 0 2px;
  color: #202c42;
  font-size: 15px;
  line-height: 21px;
  text-align: left;
  white-space: nowrap;
}

.region-pick__item--on {
  color: #ba470b;
  font-weight: 500;
}

.region-pick__item-name {
  min-width: 0;
  flex: 1 1 auto;
}

.region-pick__note,
.region-pick__picked {
  flex: none;
  padding: 6px 2px 0;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.region-pick__picked {
  padding-bottom: 10px;
}

.region-pick__confirm {
  width: 100%;
  height: 46px;
  flex: none;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
}

.region-pick__confirm[disabled] {
  color: #fff;
  background: #c9d3e1;
}
</style>
