<script setup lang="ts">
import { onReady } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { HOME_ROOT_PATH } from '@/app-shell/navigation/root-tabs'
import { readPermissionStates } from '@/features/account/application/app-permissions'
import { PLACE_PREVIEW_ENABLED } from '@/features/place/application/place-preview'
import { platformOrigins } from '@/features/scan/application/scan-target'
import ScanPage from '@/features/scan/presentation/ScanPage.vue'
import { SEARCH_PRE_PATH } from '@/features/search/navigation/search-routes'

// The system scanner is the App's (uni.scanCode); a browser types the code instead.
function hasSystemScanner(): boolean {
  try {
    return (uni.getAppBaseInfo() as { uniPlatform?: unknown } | undefined)?.uniPlatform === 'app'
  } catch {
    return false
  }
}

const canScan = hasSystemScanner()
const origins = platformOrigins()
const page = ref<{ scan(): void } | null>(null)

function goHome(): void {
  uni.switchTab({ url: HOME_ROOT_PATH })
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  goHome()
}

// A platform page replaces the scan page, so 返回 there goes back where the scan started.
function open(url: string): void {
  uni.redirectTo({ url })
}

function openSearch(): void {
  uni.redirectTo({ url: SEARCH_PRE_PATH })
}

onReady(() => {
  if (canScan && readPermissionStates().camera === 'on') page.value?.scan()
})
</script>

<template>
  <ScanPage
    v-if="PLACE_PREVIEW_ENABLED"
    ref="page"
    :can-scan="canScan"
    :origins="origins"
    @back="goBack"
    @open="open"
    @search="openSearch"
  />
  <view v-else class="scan-unavailable" data-testid="scan-unavailable">
    <text>扫一扫尚未开通</text>
  </view>
</template>

<style scoped>
.scan-unavailable {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #738096;
  background: #f5f7fc;
  font-size: 14px;
}
</style>
