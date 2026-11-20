<script setup lang="ts">
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { computed, nextTick, ref } from 'vue'

import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { liveCityCode } from '@/features/live/application/live-city'
import { LIVE_PREVIEW_ENABLED } from '@/features/live/application/live-preview'
import { parseLiveTarget, type LiveTarget } from '@/features/live/navigation/live-routes'
import LiveRoomPage from '@/features/live/presentation/room/LiveRoomPage.vue'
import LiveUnavailable from '@/features/live/presentation/shared/LiveUnavailable.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

const DISCOVER_PATH = '/pages/discover/index'

const cityStore = useCurrentCityStore()
const target = ref<LiveTarget | null>(null)
const page = ref<{ start(): void; stop(): void } | null>(null)
const cityCode = computed(() => (target.value ? liveCityCode(target.value.cityCode, cityStore.currentCity) : ''))

// Back goes where the user came from; opened on its own (a shared link, a notification), back is 发现.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: DISCOVER_PATH })
}

function open(url: string): void {
  uni.navigateTo({ url })
}

// The next or previous live room, or this room's preview page once it is over: the page is replaced
// so back still leads where the viewer came from.
function replace(url: string): void {
  page.value?.stop()
  uni.redirectTo({ url })
}

function login(returnUrl: string): void {
  page.value?.stop()
  uni.navigateTo({ url: buildInspectionLoginUrl(returnUrl) })
}

onLoad((query) => {
  target.value = parseLiveTarget(query as Record<string, unknown> | undefined)
})

// Comments and counts are polled only while the room is in front.
onShow(() => {
  void nextTick(() => page.value?.start())
})
onHide(() => page.value?.stop())
onUnload(() => page.value?.stop())
</script>

<template>
  <LiveRoomPage
    v-if="LIVE_PREVIEW_ENABLED && target && cityCode"
    ref="page"
    :room-id="target.roomId"
    :city-code="cityCode"
    @back="goBack"
    @open="open"
    @replace="replace"
    @login="login"
  />
  <LiveUnavailable v-else-if="!LIVE_PREVIEW_ENABLED" />
  <view v-else class="live-missing" data-testid="live-room-invalid">
    <text>没有找到这个直播间</text>
  </view>
</template>

<style scoped>
.live-missing {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #8a929b;
  background: #f7f8fa;
  font-size: 14px;
}
</style>
