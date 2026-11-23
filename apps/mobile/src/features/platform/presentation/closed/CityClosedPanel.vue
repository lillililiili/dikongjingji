<script setup lang="ts">
import { computed } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

// Board CityClosed, under the home head: the city's 筹备中 card with 切换到 a city that is open and
// 开通后通知我, then 全国可用 — 线上体验, 全国发货商品, 线上课程 — which work in every city.
const props = defineProps<{
  cityName: string
  // An open city to switch to; null hides the button.
  openCityName: string | null
}>()

const emit = defineEmits<{
  switchCity: []
  notify: []
  open: [target: 'EXPERIENCE' | 'MALL' | 'COURSE']
}>()

const HOUSE_ICON = 'M4 20h16M6 20V9l6-4 6 4v11M10 20v-5h4v5'

const NATIONWIDE = [
  { key: 'EXPERIENCE', title: '线上体验', text: '虚拟仿真、录播演示，无需到场', background: '#e2f6ed', color: '#159a70', icon: 'M4 5h16v11H4zM8 20h8M12 16v4M10 8.5l4 2.5-4 2.5z' },
  { key: 'MALL', title: '全国发货商品', text: '厂家或授权经销商直发', background: '#fff0e3', color: '#ff6a00', icon: 'M3 7h11v9H3zM14 10h4l3 3v3h-7M7 19a1.5 1.5 0 1 0 0-.1M17 19a1.5 1.5 0 1 0 0-.1' },
  { key: 'COURSE', title: '线上课程', text: '理论课与编程课，可跨城学习', background: '#e6f0ff', color: '#2f80ed', icon: 'M4 6l8-3 8 3-8 3zM7 8v5c0 1.5 2.5 3 5 3s5-1.5 5-3V8' },
] as const

const shortName = (name: string): string => (name.length > 2 && name.endsWith('市') ? name.slice(0, -1) : name)
const title = computed(() => `${shortName(props.cityName)}城市站筹备中`)
const switchLabel = computed(() => (props.openCityName ? `切换到${shortName(props.openCityName)}` : ''))
</script>

<template>
  <view class="city-closed" data-testid="city-closed">
    <view class="city-closed__card">
      <view class="city-closed__icon">
        <LineIcon :d="HOUSE_ICON" :size="32" color="#ff6a00" :stroke-width="1.8" />
      </view>
      <text class="city-closed__title" data-testid="city-closed-title">{{ title }}</text>
      <text class="city-closed__text">本地活动、场馆和本地现货暂未上线。</text>
      <text class="city-closed__text">全国发货商品和线上体验可以正常使用。</text>
      <view class="city-closed__actions">
        <button v-if="openCityName" class="city-closed__switch" data-testid="city-closed-switch" @click="emit('switchCity')">{{ switchLabel }}</button>
        <button class="city-closed__notify" data-testid="city-closed-notify" @click="emit('notify')">开通后通知我</button>
      </view>
    </view>
    <text class="city-closed__heading" role="heading">全国可用</text>
    <button
      v-for="item in NATIONWIDE"
      :key="item.key"
      class="city-closed__row"
      :data-testid="`city-closed-${item.key.toLowerCase()}`"
      @click="emit('open', item.key)"
    >
      <view class="city-closed__row-icon" :style="{ background: item.background }">
        <LineIcon :d="item.icon" :size="22" :color="item.color" :stroke-width="1.8" />
      </view>
      <view class="city-closed__row-body">
        <text class="city-closed__row-title">{{ item.title }}</text>
        <text class="city-closed__row-text">{{ item.text }}</text>
      </view>
      <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
    </button>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 board CityClosed (390 wide). */
.city-closed {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 2px var(--app-page-horizontal-gutter) 16px;
  color: #202c42;
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

.city-closed__card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 24px 20px;
  border-radius: 16px;
  background: #fff;
  text-align: center;
}

.city-closed__icon {
  display: flex;
  width: 64px;
  height: 64px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff0e3;
}

.city-closed__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.city-closed__text {
  margin-top: -8px;
  color: #627187;
  font-size: 13px;
  line-height: 1.6;
}

.city-closed__actions {
  display: flex;
  gap: 10px;
  margin-top: 6px;
}

.city-closed__switch,
.city-closed__notify {
  display: flex;
  min-height: 44px;
  align-items: center;
  padding: 0 18px;
  border-radius: 999px;
  font-size: 14px;
  white-space: nowrap;
  box-sizing: border-box;
}

.city-closed__switch {
  color: #fff;
  background: #ff6a00;
  font-weight: 500;
}

.city-closed__notify {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #fff;
}

.city-closed__heading {
  margin-top: 4px;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.city-closed__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border-radius: 12px;
  color: #202c42;
  background: #fff;
  text-align: left;
}

.city-closed__row-icon {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
}

.city-closed__row-body {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 3px;
}

.city-closed__row-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.city-closed__row-text {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}
</style>
