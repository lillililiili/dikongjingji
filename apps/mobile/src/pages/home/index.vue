<script setup lang="ts">
import { onHide, onPageScroll, onShow } from '@dcloudio/uni-app'
import { storeToRefs } from 'pinia'
import { computed, nextTick, ref, watch } from 'vue'

import HomeHeader from '@/app-shell/components/HomeHeader.vue'
import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import {
  isHomeChannelSearchable,
  resolveHomeChannelCards,
  resolveHomeKingEntries,
  resolveHomeSearchPlaceholder,
  resolveVisibleHomeChannels,
  type HomeChannelCode,
} from '@/app-shell/navigation/home-channels'
import { useHomeChannelStore } from '@/app-shell/state/home-channel'
import { buildPortalSearchUrl } from '@/features/inspection/application/portal/build-portal-navigation'
import { createPlatformHomePreviewContent } from '@/features/inspection/data/preview/platform-home-preview'
import {
  inspectionCatalogApi,
  platformCityApi,
} from '@/features/inspection/data/service-flow/uni-booking-http-client'
import PlatformHomePage from '@/features/inspection/presentation/platform/home/PlatformHomePage.vue'
import {
  createPlatformHomeContentFromPortal,
  resolvePlatformHomeState,
  type PlatformHomeFeaturedService,
  type PlatformHomeViewState,
} from '@/features/inspection/presentation/platform/home/platform-home'
import { createPlatformFeatureGateway } from '@/features/platform/application/platform-feature-gateway'
import { PLATFORM_PREVIEW_ENABLED } from '@/features/platform/application/platform-preview'
import type { PlatformFeatureIntent } from '@/features/platform/domain/platform-feature-intent'
import PlatformServiceChannel from '@/features/platform/presentation/service/PlatformServiceChannel.vue'
import { resolveCurrentCity } from '@/shared/city/application/resolve-current-city'
import CitySelectionSheet from '@/shared/city/presentation/CitySelectionSheet.vue'
import { useCitySheet } from '@/shared/city/presentation/use-city-sheet'
import {
  resolveLocatedCityName,
  useCurrentCityStore,
  type CurrentCity,
} from '@/shared/city/state/current-city'

const DEFAULT_CITY: CurrentCity = { code: '370500', name: '东营市' }

const currentCityStore = useCurrentCityStore()
const channelStore = useHomeChannelStore()
const { activeChannel } = storeToRefs(channelStore)
const state = ref<PlatformHomeViewState>({ kind: 'loading' })
const keyword = ref('')
const serviceSearchPlaceholder = ref<string | null>(null)
const scrollPositions = new Map<HomeChannelCode, number>()
let currentScrollTop = 0
let contentRequestVersion = 0

const visibleChannels = resolveVisibleHomeChannels(PLATFORM_PREVIEW_ENABLED)
const channelCards = resolveHomeChannelCards(PLATFORM_PREVIEW_ENABLED)
const kingEntries = resolveHomeKingEntries(PLATFORM_PREVIEW_ENABLED)
const serviceChannelOpened = ref(activeChannel.value === 'SERVICE')
const activeChannelLabel = computed(() =>
  visibleChannels.find((channel) => channel.code === activeChannel.value)?.label ?? '',
)
const isPlaceholderChannel = computed(() =>
  activeChannel.value !== 'RECOMMEND' && activeChannel.value !== 'SERVICE',
)
const searchPlaceholder = computed(() =>
  serviceSearchPlaceholder.value
    ?? resolveHomeSearchPlaceholder(activeChannel.value, PLATFORM_PREVIEW_ENABLED),
)
const cityName = computed(
  () => currentCityStore.currentCity?.name ?? '选择城市',
)
const featureGateway = createPlatformFeatureGateway(PLATFORM_PREVIEW_ENABLED
  ? { HOME_SEARCH: 'NEXT_PHASE', SCAN: 'NEXT_PHASE' }
  : { HOME_SEARCH: 'HIDDEN', SCAN: 'HIDDEN' })
const citySheet = useCitySheet(platformCityApi, () => {
  void loadHomeContent()
})
const {
  isOpen: citySheetOpen,
  directory: cityDirectory,
  isLoading: cityDirectoryLoading,
  error: cityDirectoryError,
  recentCities,
  isRelocating,
} = citySheet

function restoreDefaultCity(): CurrentCity {
  if (currentCityStore.currentCity) return currentCityStore.currentCity
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  const city = code && name ? { code, name } : DEFAULT_CITY
  currentCityStore.setCurrentCity(city)
  return city
}

function locateCurrentCityName(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.getLocation({
      type: 'gcj02',
      geocode: true,
      isHighAccuracy: true,
      highAccuracyExpireTime: 5000,
      success: (result) => {
        try {
          resolve(resolveLocatedCityName(result.address))
        } catch (error) {
          reject(error)
        }
      },
      fail: reject,
    })
  })
}

async function loadHomeContent(): Promise<void> {
  const requestVersion = ++contentRequestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const city = await resolveCurrentCity({
      getCurrentCity: () => currentCityStore.currentCity,
      setCurrentCity: (located) => currentCityStore.setCurrentCity(located),
      locateCityName: locateCurrentCityName,
      loadDirectory: () => platformCityApi.load(),
      restoreDefaultCity,
    })
    const portal = await inspectionCatalogApi.loadPortal(city.code)
    if (requestVersion !== contentRequestVersion) return
    state.value = resolvePlatformHomeState({
      status: 'ready',
      content: PLATFORM_PREVIEW_ENABLED
        ? createPlatformHomePreviewContent(portal)
        : createPlatformHomeContentFromPortal(portal),
    })
  } catch {
    if (requestVersion !== contentRequestVersion) return
    state.value = { kind: 'error', message: '首页内容加载失败，请稍后重试' }
  }
}

function openCatalog(searchKeyword = ''): void {
  const cityCode = currentCityStore.currentCity?.code
  if (!cityCode) {
    void citySheet.open()
    return
  }

  uni.navigateTo({ url: buildPortalSearchUrl(cityCode, searchKeyword) })
}

function selectFeatured(service: PlatformHomeFeaturedService): void {
  uni.navigateTo({
    url: `/pages/service/inspection/detail?serviceId=${encodeURIComponent(service.id)}`,
  })
}

function showUnavailable(): void {
  uni.showToast({ title: '该能力将在下一阶段接入', icon: 'none' })
}

function handleIntent(intent: PlatformFeatureIntent): void {
  const resolution = featureGateway.resolve(intent)
  if (resolution.action === 'SHOW_NEXT_PHASE') {
    uni.showToast({ title: resolution.message, icon: 'none' })
  }
}

function submitSearch(): void {
  if (
    isHomeChannelSearchable(activeChannel.value)
    && serviceSearchPlaceholder.value === null
  ) {
    openCatalog(keyword.value)
    return
  }
  handleIntent({ type: 'HOME_SEARCH', channel: activeChannel.value })
}

function selectChannel(channel: HomeChannelCode): void {
  channelStore.selectChannel(channel)
}

function restoreChannelScroll(channel: HomeChannelCode): void {
  uni.pageScrollTo({
    scrollTop: scrollPositions.get(channel) ?? 0,
    duration: 0,
  })
}

function scrollToTop(): void {
  uni.pageScrollTo({ scrollTop: 0, duration: 200 })
}

watch(activeChannel, async (channel, previousChannel) => {
  scrollPositions.set(previousChannel, currentScrollTop)
  keyword.value = ''
  if (channel === 'SERVICE') serviceChannelOpened.value = true
  await nextTick()
  if (channel === activeChannel.value) restoreChannelScroll(channel)
})

onPageScroll(({ scrollTop }) => {
  currentScrollTop = scrollTop
})

onShow(() => {
  void loadHomeContent()
  void nextTick(() => restoreChannelScroll(activeChannel.value))
})

onHide(() => {
  scrollPositions.set(activeChannel.value, currentScrollTop)
})
</script>

<template>
  <view class="home-page" data-testid="home-page">
    <HomeHeader
      v-model:keyword="keyword"
      :city-name="cityName"
      :channels="visibleChannels"
      :active-channel="activeChannel"
      :search-placeholder="searchPlaceholder"
      :show-scan="PLATFORM_PREVIEW_ENABLED"
      @select-city="citySheet.open"
      @select-channel="selectChannel"
      @search="submitSearch"
      @scan="handleIntent({ type: 'SCAN' })"
    />

    <view v-show="activeChannel === 'RECOMMEND'">
      <PlatformHomePage
        :state="state"
        :cards="channelCards"
        :king-entries="kingEntries"
        @retry="loadHomeContent"
        @search="openCatalog"
        @select-channel="selectChannel"
        @select-featured-service="selectFeatured"
        @select-activity="showUnavailable"
        @select-enterprise-solution="showUnavailable"
      />
    </view>

    <!-- Stays mounted behind v-show so returning to the channel does not refetch M01. -->
    <view
      v-if="serviceChannelOpened && currentCityStore.currentCity"
      v-show="activeChannel === 'SERVICE'"
    >
      <PlatformServiceChannel
        :active="activeChannel === 'SERVICE'"
        @search-placeholder-change="serviceSearchPlaceholder = $event"
      />
    </view>
    <view
      v-else-if="activeChannel === 'SERVICE'"
      class="home-page__placeholder"
      data-testid="home-city-pending"
    >
      <text>正在确定服务城市</text>
    </view>

    <view
      v-if="isPlaceholderChannel"
      class="home-page__placeholder"
      data-testid="home-channel-placeholder"
    >
      <text>{{ activeChannelLabel }}频道建设中</text>
    </view>

    <CitySelectionSheet
      v-if="citySheetOpen"
      :directory="cityDirectory"
      :current-city="currentCityStore.currentCity"
      :recent-cities="recentCities"
      :loading="cityDirectoryLoading"
      :error="cityDirectoryError"
      :relocating="isRelocating"
      @close="citySheet.close"
      @retry="citySheet.loadDirectory"
      @relocate="citySheet.relocate"
      @select="citySheet.select"
    />
    <RootAppTabBar active="HOME" @reselect="scrollToTop" />
  </view>
</template>

<style scoped>
.home-page {
  min-height: 100vh;
  padding-bottom: var(--app-root-tabbar-content-offset);
  background: #f7f8fa;
  box-sizing: border-box;
}

.home-page__placeholder {
  display: flex;
  min-height: 360px;
  margin: 4px var(--app-page-horizontal-gutter) 0;
  align-items: center;
  justify-content: center;
  border-radius: 16px;
  color: #8a929b;
  background: #ffffff;
  font-size: 14px;
}
</style>
