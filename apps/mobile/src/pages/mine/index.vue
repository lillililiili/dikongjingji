<script setup lang="ts">
import { onHide, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { ROOT_TABS, type RootTabCode } from '@/app-shell/navigation/root-tabs'
import PlatformMinePage from '@/features/inspection/presentation/platform/mine/PlatformMinePage.vue'
import {
  resolvePlatformMineState,
  type PlatformMineViewState,
} from '@/features/inspection/presentation/platform/mine/platform-mine'

const state = ref<PlatformMineViewState>(resolvePlatformMineState({
  status: 'ready',
  content: {
    recentOrders: [],
    featureEntries: [
      {
        code: 'PROJECTS',
        title: '我的项目',
        imageUrl: '/static/images/inspection/platform/mine-feature-projects-v2.png',
        availability: 'UNAVAILABLE',
      },
      {
        code: 'EQUIPMENT',
        title: '我的设备',
        imageUrl: '/static/images/inspection/platform/mine-feature-equipment-v2.png',
        availability: 'UNAVAILABLE',
      },
      {
        code: 'QUALIFICATIONS',
        title: '我的资质',
        imageUrl: '/static/images/inspection/platform/mine-feature-qualifications-v2.png',
        availability: 'UNAVAILABLE',
      },
      {
        code: 'SUPPORT',
        title: '服务支持',
        imageUrl: '/static/images/inspection/platform/mine-feature-support-v2.png',
        availability: 'UNAVAILABLE',
      },
    ],
  },
}))

function selectTab(code: RootTabCode): void {
  const tab = ROOT_TABS.find((candidate) => candidate.code === code)
  if (tab && code !== 'MINE') uni.switchTab({ url: tab.path })
}

function showUnavailable(): void {
  uni.showToast({ title: '该能力将在下一阶段接入', icon: 'none' })
}

onShow(() => {
  uni.hideTabBar({ animation: false })
})
onHide(() => {
  uni.showTabBar({
    animation: false,
    fail: () => undefined,
  })
})
</script>

<template>
  <PlatformMinePage
    :state="state"
    active-tab="MINE"
    @retry="showUnavailable"
    @customer-service="showUnavailable"
    @settings="showUnavailable"
    @select-status="showUnavailable"
    @select-order="showUnavailable"
    @select-feature="showUnavailable"
    @view-all-orders="showUnavailable"
    @select-tab="selectTab"
  />
</template>
