<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref } from 'vue'

import AppHeader from '@/app-shell/components/AppHeader.vue'
import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import type { PlatformWeatherPresentation } from '@/shared/weather/platform-weather'
import {
  PLATFORM_SERVICE_CHANNELS,
  type PlatformChannelCode,
} from '../../domain/platform-channel'
import { resolvePlatformSwipeTarget } from '../../domain/platform-channel-swipe'

interface PlatformTouchPoint {
  clientX: number
  clientY: number
}

const props = defineProps<{
  cityName: string
  weather: PlatformWeatherPresentation
  unreadCount?: number
  showMessage?: boolean
  activeChannel: PlatformChannelCode
  visibleChannels: readonly PlatformChannelCode[]
  searchPlaceholder: string
}>()

const emit = defineEmits<{
  search: []
  message: []
  location: []
  selectChannel: [channel: PlatformChannelCode]
}>()

const channels = computed(() =>
  PLATFORM_SERVICE_CHANNELS.filter((channel) =>
    props.visibleChannels.includes(channel.code)),
)
const contentTouchStart = ref<PlatformTouchPoint | null>(null)

function firstTouch(event: TouchEvent): PlatformTouchPoint | null {
  return event.changedTouches[0] ?? event.touches[0] ?? null
}

function beginContentSwipe(event: TouchEvent): void {
  contentTouchStart.value = firstTouch(event)
}

function cancelContentSwipe(): void {
  contentTouchStart.value = null
}

function finishContentSwipe(event: TouchEvent): void {
  const start = contentTouchStart.value
  const end = firstTouch(event)
  contentTouchStart.value = null
  if (!start || !end) return

  const targetChannel = resolvePlatformSwipeTarget(
    props.activeChannel,
    end.clientX - start.clientX,
    end.clientY - start.clientY,
  )
  if (targetChannel) emit('selectChannel', targetChannel)
}
</script>

<template>
  <view class="platform-service-page">
    <view class="platform-service-page__header">
      <AppHeader
        :city-name="cityName"
        :weather="weather"
        :search-placeholder="searchPlaceholder"
        :unread-count="unreadCount"
        :show-message="showMessage"
        @location="emit('location')"
        @search="emit('search')"
        @message="emit('message')"
      />
    </view>

    <scroll-view class="platform-service-channels" scroll-x>
      <view class="platform-service-channels__row">
        <button
          v-for="channel in channels"
          :key="channel.code"
          :class="[
            'platform-service-channels__item',
            {
              'platform-service-channels__item--active':
                channel.code === activeChannel,
            },
          ]"
          @click="emit('selectChannel', channel.code)"
        >
          <UniIcons
            v-if="channel.code === 'MORE'"
            type="smallcircle"
            size="14"
            color="#30353b"
          />
          <text>{{ channel.label }}</text>
        </button>
      </view>
    </scroll-view>

    <main
      class="platform-service-page__content"
      @touchstart="beginContentSwipe"
      @touchend="finishContentSwipe"
      @touchcancel="cancelContentSwipe"
    >
      <slot />
    </main>

    <RootAppTabBar active="SERVICE" />
  </view>
</template>

<style scoped>
.platform-service-page {
  min-height: 100vh;
  padding:
    calc(var(--status-bar-height) + 10px)
    0
    var(--app-root-tabbar-content-offset);
  color: #17191d;
  background: #f7f8fa;
  box-sizing: border-box;
  font-family:
    system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC",
    "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
}

button {
  min-width: 0;
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

.platform-service-page__header {
  width: 100%;
  padding: 0 var(--app-page-horizontal-gutter);
  box-sizing: border-box;
}

.platform-service-channels {
  width: 100%;
  margin-top: 12px;
  white-space: nowrap;
}

.platform-service-channels__row {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 6px;
  padding: 0 10px 9px;
  box-sizing: border-box;
}

.platform-service-channels__item {
  position: relative;
  display: inline-flex;
  min-height: 42px;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 0 2px;
  border-radius: 18px;
  color: #30353b;
  background: #ffffff;
  font-size: 11px;
  font-weight: 650;
  white-space: nowrap;
}

.platform-service-channels__item--active {
  color: #ffffff;
  background: linear-gradient(
    90deg,
    var(--app-action-orange-start),
    var(--app-action-orange-end)
  );
  font-weight: 800;
}

.platform-service-channels__item--active::after {
  position: absolute;
  right: 30%;
  bottom: -5px;
  left: 30%;
  height: 4px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 3px 0 #ff7200;
  content: "";
}

.platform-service-page__content {
  width: 100%;
  max-width: 600px;
  margin: 0 auto;
  padding: 0 var(--app-page-horizontal-gutter) 12px;
  box-sizing: border-box;
}

</style>
