<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { liveCityCode } from '@/features/live/application/live-city'
import { LIVE_PREVIEW_ENABLED } from '@/features/live/application/live-preview'
import { parseLiveTarget, type LiveTarget } from '@/features/live/navigation/live-routes'
import LiveNoticePage from '@/features/live/presentation/notice/LiveNoticePage.vue'
import LiveUnavailable from '@/features/live/presentation/shared/LiveUnavailable.vue'
import { buildProviderPageUrl } from '@/features/proservice/navigation/proservice-routes'
import { openSupportChat } from '@/features/support/application/support-entry'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

const DISCOVER_PATH = '/pages/discover/index'

const cityStore = useCurrentCityStore()
const target = ref<LiveTarget | null>(null)
const page = ref<{ load(): void } | null>(null)
let shown = false
const cityCode = computed(() => (target.value ? liveCityCode(target.value.cityCode, cityStore.currentCity) : ''))

// Back goes where the user came from; opened on its own (a shared link), back is 发现.
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

function login(returnUrl: string): void {
  uni.navigateTo({ url: buildInspectionLoginUrl(returnUrl) })
}

// 咨询: the conversation with the host organisation (the chat says when it cannot be reached).
function consult(organizationId: string): void {
  openSupportChat({ kind: 'PROVIDER', organizationId })
}

function hostPage(organizationId: string): void {
  if (organizationId && cityCode.value) uni.navigateTo({ url: buildProviderPageUrl(organizationId, cityCode.value) })
}

onLoad((query) => {
  target.value = parseLiveTarget(query as Record<string, unknown> | undefined)
})

// Coming back from the login page or a live room reads the room again (reminder, status).
onShow(() => {
  if (shown) page.value?.load()
  shown = true
})
</script>

<template>
  <LiveNoticePage
    v-if="LIVE_PREVIEW_ENABLED && target && cityCode"
    ref="page"
    :room-id="target.roomId"
    :city-code="cityCode"
    @back="goBack"
    @open="open"
    @login="login"
    @consult="consult"
    @host-page="hostPage"
  />
  <LiveUnavailable v-else-if="!LIVE_PREVIEW_ENABLED" />
  <view v-else class="live-missing" data-testid="live-notice-invalid">
    <text>没有找到这场直播</text>
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
