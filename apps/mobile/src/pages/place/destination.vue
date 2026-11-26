<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { locateWithUni } from '@/features/place/data/device-location'
import type { MapPoint } from '@/features/place/domain/map-place'
import PlaceUnavailable from '@/features/place/presentation/PlaceUnavailable.vue'
import DestinationMap from '@/shared/map/DestinationMap.vue'
import { markerUrl, positionOf, readDestination, type Destination } from '@/shared/map/destination'
import { chooseNavigation, copyDestination } from '@/shared/map/destination-actions'

const place = ref<Destination | null>(null)
const attempt = ref(0)
const state = ref<'loading' | 'ready' | 'error'>('loading')
const currentLocation = ref<Destination | null>(null)
const locating = ref(false)
const locationMessage = ref('')
let active = true
const mobile = ['ios', 'android'].includes(uni.getSystemInfoSync().platform)
const display = computed(() => currentLocation.value ?? place.value)
const source = computed(() => display.value ? markerUrl(display.value, mobile) : null)
const point = computed(() => display.value && positionOf(display.value))
onLoad(query => { place.value = readDestination(query?.place) })
onUnload(() => { active = false })
function retry() { state.value = 'loading'; attempt.value++ }

async function locate() {
  if (locating.value) return
  if (currentLocation.value) {
    currentLocation.value = null
    locationMessage.value = ''
    retry()
    return
  }
  locating.value = true
  locationMessage.value = ''
  let coordinates: MapPoint | null = null
  // #ifdef APP-PLUS
  coordinates = await locateWithUni()
  // #endif
  // #ifdef H5
  if (navigator.geolocation) {
    coordinates = await new Promise<MapPoint | null>(resolve => navigator.geolocation.getCurrentPosition(
      ({ coords }) => resolve({ latitude: coords.latitude, longitude: coords.longitude }), () => resolve(null),
      { timeout: 6500, maximumAge: 30000 },
    )).catch(() => null)
  }
  // #endif
  if (!active) return
  locating.value = false
  if (!coordinates) { locationMessage.value = '无法获取当前位置，请检查定位权限后重试。'; return }
  currentLocation.value = { name: '我的位置', address: '', ...coordinates }
  locationMessage.value = '地图当前显示我的位置'
  retry()
}
</script>

<template>
  <view v-if="place" class="destination" data-testid="destination-page">
    <view class="destination__map">
      <DestinationMap v-if="source && point" :source="source" :attempt="attempt" :latitude="point.latitude"
        :longitude="point.longitude" :title="display?.name ?? place.name" @state="state = $event" />
      <view v-if="!point || state === 'error'" class="destination__status" role="status">
        <text data-testid="destination-error">{{ point ? '地图加载失败' : '暂无精确位置' }}</text>
        <button v-if="point" class="destination__secondary" data-testid="destination-retry" @click="retry">重新加载</button>
      </view>
      <text v-else-if="state === 'loading'" class="destination__loading" role="status">地图加载中…</text>
    </view>
    <view class="destination__details">
      <text class="destination__name">{{ place.name }}</text>
      <text class="destination__address">{{ place.address }}</text>
      <text v-if="locationMessage" role="status" data-testid="destination-location-message" class="destination__address">{{ locationMessage }}</text>
      <view class="destination__actions">
        <button class="destination__navigate" data-testid="destination-navigate" @click="chooseNavigation(place)">导航</button>
        <button class="destination__secondary" @click="copyDestination(place)">复制地址</button>
        <button v-if="point && state === 'ready'" class="destination__secondary" @click="retry">刷新地图</button>
        <button v-if="positionOf(place)" class="destination__secondary" data-testid="destination-locate"
          :loading="locating" :disabled="locating" @click="locate">{{ currentLocation ? '返回目的地' : '定位到我' }}</button>
      </view>
    </view>
  </view>
  <PlaceUnavailable v-else message="地点信息缺失，请从详情页重新打开。" data-testid="destination-missing" />
</template>

<style scoped>
.destination { height: calc(100vh - var(--window-top, 0px) - var(--window-bottom, 0px)); min-height: 65vh; display: flex; flex-direction: column; background: #fff; color: #18283b; }
.destination__map { position: relative; flex: 1; min-height: 45vh; background: #f2f5f9; }
.destination__map > :first-child { position: absolute; inset: 0; }
.destination__status { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 20rpx; background: #f2f5f9; }
.destination__loading { position: absolute; left: 24rpx; top: 24rpx; padding: 8rpx 16rpx; border-radius: 8rpx; background: #fff; pointer-events: none; }
.destination__details { padding: 24rpx 30rpx; padding-bottom: calc(24rpx + env(safe-area-inset-bottom)); }
.destination__name, .destination__address { display: block; }
.destination__name { font-size: 34rpx; font-weight: 600; }
.destination__address { margin-top: 8rpx; color: #526275; font-size: 27rpx; }
.destination__actions { display: flex; flex-wrap: wrap; gap: 14rpx; margin-top: 20rpx; }
.destination__navigate, .destination__secondary { margin: 0; padding: 0 22rpx; line-height: 70rpx; font-size: 26rpx; border-radius: 12rpx; }
.destination__navigate { color: #fff; background: #1768af; }
.destination__secondary { background: #edf3f8; color: #27577f; }
</style>
