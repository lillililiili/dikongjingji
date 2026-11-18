<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed } from 'vue'

import {
  ROOT_TABS,
  formatRootTabBadge,
  type RootTabCode,
  type RootTabDefinition,
} from '@/app-shell/navigation/root-tabs'
import {
  refreshCartCount,
  refreshMessageUnread,
  useRootTabBadgeStore,
} from '@/app-shell/state/root-tab-badges'

const props = defineProps<{
  active: RootTabCode
  badgeCounts?: Partial<Record<RootTabCode, number>>
  newContentTabs?: readonly RootTabCode[]
}>()

const emit = defineEmits<{
  reselect: []
}>()

const TAB_ICON_TYPES: Record<RootTabCode, string> = {
  HOME: 'home',
  DISCOVER: 'navigate',
  MESSAGE: 'chat',
  CART: 'cart',
  MINE: 'person',
}
const ACTIVE_TAB_COLOR = '#ff6a00'
const INACTIVE_TAB_COLOR = '#8a8f97'

const badgeStore = useRootTabBadgeStore()
// The message tab's number comes from the server; a page may still pass its own counts.
const badgeCounts = computed<Partial<Record<RootTabCode, number>>>(() => ({
  MESSAGE: badgeStore.messageUnread,
  CART: badgeStore.cartCount,
  ...props.badgeCounts,
}))

// uni.switchTab only targets pages listed in the native tab bar, so it is hidden rather than removed.
onShow(() => {
  uni.hideTabBar({ animation: false, fail: () => undefined })
  // Every root page shows this bar, so the unread number is read again whenever one is shown.
  void refreshMessageUnread()
  void refreshCartCount()
})

function iconColor(code: RootTabCode): string {
  return code === props.active ? ACTIVE_TAB_COLOR : INACTIVE_TAB_COLOR
}

function badgeLabel(code: RootTabCode): string {
  return formatRootTabBadge(badgeCounts.value[code])
}

function showsNewContentDot(code: RootTabCode): boolean {
  return code !== props.active && Boolean(props.newContentTabs?.includes(code))
}

function openTab(tab: RootTabDefinition): void {
  if (tab.code === props.active) {
    emit('reselect')
    return
  }

  uni.switchTab({ url: tab.path })
}
</script>

<template>
  <view class="root-tab-bar">
    <button
      v-for="tab in ROOT_TABS"
      :key="tab.code"
      class="root-tab-bar__item"
      :class="{ 'root-tab-bar__item--active': tab.code === active }"
      :data-testid="`root-tab-${tab.code.toLowerCase()}`"
      :aria-label="tab.label"
      @click="openTab(tab)"
    >
      <UniIcons
        class="root-tab-bar__icon"
        :type="TAB_ICON_TYPES[tab.code]"
        size="24"
        :color="iconColor(tab.code)"
        aria-hidden="true"
      />
      <text class="root-tab-bar__label">{{ tab.label }}</text>
      <text v-if="badgeLabel(tab.code)" class="root-tab-bar__badge">
        {{ badgeLabel(tab.code) }}
      </text>
      <view v-if="showsNewContentDot(tab.code)" class="root-tab-bar__dot" />
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
  height: calc(60px + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  border-top: 1px solid #ebedf0;
  background: #ffffff;
  box-sizing: border-box;
}

.root-tab-bar__item {
  position: relative;
  display: flex;
  width: 100%;
  min-width: 0;
  height: 100%;
  margin: 0;
  padding: 0;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border: 0;
  border-radius: 0;
  color: #8a8f97;
  background: transparent;
  box-sizing: border-box;
  line-height: 1;
}

.root-tab-bar__item::after {
  border: 0;
}

.root-tab-bar__item--active {
  color: #ff6a00;
}

.root-tab-bar__icon {
  display: block;
  line-height: 1;
}

.root-tab-bar__label {
  font-size: 12px;
  line-height: 14px;
}

.root-tab-bar__item--active .root-tab-bar__label {
  font-weight: 500;
}

.root-tab-bar__badge {
  position: absolute;
  top: 4px;
  left: 50%;
  min-width: 16px;
  height: 16px;
  margin-left: 5px;
  padding: 0 4px;
  border-radius: 8px;
  color: #ffffff;
  background: #ff5200;
  box-sizing: border-box;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
}

.root-tab-bar__dot {
  position: absolute;
  top: 7px;
  left: 50%;
  width: 8px;
  height: 8px;
  margin-left: 9px;
  border-radius: 50%;
  background: #ff5200;
}
</style>
