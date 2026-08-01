<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import {
  ROOT_TABS,
  type RootTabCode,
} from '@/app-shell/navigation/root-tabs'

defineProps<{
  active: RootTabCode
}>()

const TAB_ICON_TYPES: Partial<Record<RootTabCode, string>> = {
  HOME: 'home',
  SERVICE: 'smallcircle-filled',
  DISCOVER: 'navigate',
  MALL: 'shop',
  MINE: 'person',
}

const ACTIVE_TAB_ICON_TYPES: Partial<Record<RootTabCode, string>> = {
  HOME: 'home-filled',
  DISCOVER: 'navigate-filled',
  MALL: 'shop-filled',
  MINE: 'person-filled',
}

function iconColor(code: RootTabCode, active: RootTabCode): string {
  return code === active ? '#d04700' : '#4f5863'
}

function tabIconType(code: RootTabCode, active: RootTabCode): string {
  return code === active
    ? ACTIVE_TAB_ICON_TYPES[code] ?? TAB_ICON_TYPES[code] ?? ''
    : TAB_ICON_TYPES[code] ?? ''
}

function openTab(code: RootTabCode, path: string, active: RootTabCode): void {
  if (code === active) {
    return
  }

  uni.switchTab({ url: path })
}
</script>

<template>
  <view class="root-tab-bar">
    <button
      v-for="tab in ROOT_TABS"
      :key="tab.code"
      class="root-tab-bar__item"
      :class="{ 'root-tab-bar__item--active': tab.code === active }"
      :aria-label="tab.label"
      @click="openTab(tab.code, tab.path, active)"
    >
      <view
        v-if="tab.code === 'SERVICE'"
        class="root-tab-bar__service-icon"
        aria-hidden="true"
      >
        <UniIcons
          v-for="dot in 4"
          :key="dot"
          type="smallcircle-filled"
          size="14"
          :color="iconColor(tab.code, active)"
        />
      </view>
      <UniIcons
        v-else
        class="root-tab-bar__icon"
        :type="tabIconType(tab.code, active)"
        size="30"
        :color="iconColor(tab.code, active)"
        aria-hidden="true"
      />
      <text class="root-tab-bar__label">{{ tab.label }}</text>
    </button>
  </view>
</template>

<style scoped>
.root-tab-bar {
  position: fixed;
  z-index: 20;
  right: 0;
  bottom: 0;
  left: 0;
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  min-height: calc(58px + env(safe-area-inset-bottom));
  padding: 5px 10px env(safe-area-inset-bottom);
  border-top: 1px solid #edf0f3;
  background: rgba(255, 255, 255, 0.97);
  box-sizing: border-box;
  box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.04);
}

.root-tab-bar__item {
  display: flex;
  width: 100%;
  min-width: 0;
  min-height: 48px;
  padding: 0;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 14px;
  color: #4f5863;
  background: transparent;
  box-sizing: border-box;
  line-height: 1;
}

.root-tab-bar__item::after {
  border: 0;
}

.root-tab-bar__item--active {
  color: #d04700;
  background: #fff2e8;
}

.root-tab-bar__icon {
  display: block;
  line-height: 1;
}

.root-tab-bar__service-icon {
  display: grid;
  width: 30px;
  height: 30px;
  grid-template-columns: repeat(2, 14px);
  grid-template-rows: repeat(2, 14px);
  place-content: center;
  gap: 2px;
  line-height: 1;
}

.root-tab-bar__service-icon :deep(.uni-icons) {
  display: block;
  width: 14px;
  height: 14px;
  line-height: 14px;
}

.root-tab-bar__label {
  margin-top: 4px;
  font-size: 12px;
  line-height: 14px;
}

.root-tab-bar__item--active .root-tab-bar__label {
  font-weight: 800;
}
</style>
