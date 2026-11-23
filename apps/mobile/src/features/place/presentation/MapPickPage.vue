<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import type { MapPlaceProvider } from '../application/map-place-provider'
import { distanceMetres, formatDistance, formatPoint, movePoint } from '../application/map-projection'
import {
  MAP_ADDRESS_MAX,
  MAP_PICK_TIPS,
  MAP_PICK_TITLES,
  type MapPickPurpose,
  type MapPlace,
  type MapPoint,
  type PickedPlace,
} from '../domain/map-place'

// Board MapPick: the search box, the sketch map with the pin in the middle (drag the map to move
// it, 回到我的位置 when the position is known), the chosen place, 附近地点 or the search results, and
// 确认地址. A spot picked by dragging has no name, so its address is typed by hand. Without the
// device position the page says so and starts from the city's centre (the 定位被拒 frame).
const props = defineProps<{
  provider: MapPlaceProvider
  purpose: MapPickPurpose
  city: { code: string; name: string }
}>()

const emit = defineEmits<{
  back: []
  confirm: [place: PickedPlace]
}>()

type Pick = { kind: 'PLACE'; place: MapPlace } | { kind: 'PIN' } | null

const SEARCH_ICON = 'M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4'
const PIN_ICON = 'M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11zM12 12.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 0 0 0 5z'
const INFO_ICON = 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01'
// The board's map pin (30 × 38, viewBox 24 × 30): orange with a white edge and a white dot.
const PIN_IMAGE = `data:image/svg+xml;charset=utf-8,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 30"><path d="M12 29s-9-9.5-9-17a9 9 0 0 1 18 0c0 7.5-9 17-9 17z" fill="#ff6a00" stroke="#fff" stroke-width="1.5"/><circle cx="12" cy="12" r="3.6" fill="#fff"/></svg>')}`
const LOCATE_ICON = 'M12 6a6 6 0 1 0 0 12a6 6 0 1 0 0-12zM12 3v3M12 18v3M3 12h3M18 12h3'

const pin = ref<MapPoint>(props.provider.center(props.city))
const located = ref<MapPoint | null>(null)
const locating = ref(true)
const nearby = ref<MapPlace[]>([])
const results = ref<MapPlace[]>([])
const keyword = ref('')
const pick = ref<Pick>(null)
const manualAddress = ref('')
const offset = ref({ x: 0, y: 0 })
let dragFrom: { x: number; y: number; point: MapPoint } | null = null
let searchRevision = 0

const typed = computed(() => keyword.value.trim())
const places = computed(() => (typed.value ? results.value : nearby.value))
const listTitle = computed(() => (typed.value ? '搜索结果' : located.value ? '附近地点' : '示例地点'))
const denied = computed(() => !locating.value && !located.value)
const pinLabel = computed(() => {
  if (pick.value?.kind === 'PLACE') return pick.value.place.name
  if (pick.value?.kind === 'PIN') return '图钉位置'
  return '拖动地图选点'
})
const picked = computed<PickedPlace | null>(() => {
  if (pick.value?.kind === 'PLACE') {
    const place = pick.value.place
    return { name: place.name, address: place.address, point: place.point }
  }
  if (pick.value?.kind === 'PIN') {
    const address = manualAddress.value.trim()
    return address.length >= 2 ? { name: address, address, point: pin.value } : null
  }
  return null
})
const tip = computed(() => MAP_PICK_TIPS[props.purpose])
const title = computed(() => MAP_PICK_TITLES[props.purpose])

function subLine(place: MapPlace): string {
  return located.value ? `${formatDistance(distanceMetres(pin.value, place.point))} · ${place.address}` : place.address
}

async function loadNearby(around: MapPoint): Promise<void> {
  try {
    nearby.value = await props.provider.nearby(props.city, around)
  } catch {
    nearby.value = []
  }
}

async function locate(): Promise<void> {
  locating.value = true
  const point = await props.provider.locate()
  locating.value = false
  located.value = point
  if (point) {
    pin.value = point
    pick.value = null
  }
  await loadNearby(pin.value)
}

// 回到我的位置: the pin back on the position, the search cleared (board).
function relocate(): void {
  keyword.value = ''
  void locate()
}

function onInput(event: Event): void {
  keyword.value = ((event as Event & { detail?: { value?: string } }).detail?.value ?? '').toString().slice(0, 40)
  const word = keyword.value.trim()
  const requested = ++searchRevision
  if (!word) {
    results.value = []
    return
  }
  props.provider.search(props.city, word, pin.value)
    .then((found) => {
      if (requested === searchRevision) results.value = found
    })
    .catch(() => {
      if (requested === searchRevision) results.value = []
    })
}

function choose(place: MapPlace): void {
  pick.value = { kind: 'PLACE', place }
  pin.value = place.point
}

// 没有找到 a place: the typed words become the address of the pin's spot.
function useTypedAddress(): void {
  manualAddress.value = typed.value.slice(0, MAP_ADDRESS_MAX)
  pick.value = { kind: 'PIN' }
}

function setManualAddress(event: Event): void {
  manualAddress.value = ((event as Event & { detail?: { value?: string } }).detail?.value ?? '').toString().slice(0, MAP_ADDRESS_MAX)
}

type PointerEvent = Event & {
  touches?: ArrayLike<{ clientX: number; clientY: number }>
  changedTouches?: ArrayLike<{ clientX: number; clientY: number }>
  clientX?: number
  clientY?: number
}

function pointOf(event: PointerEvent): { x: number; y: number } | null {
  const touch = event.touches?.[0] ?? event.changedTouches?.[0]
  if (touch) return { x: touch.clientX, y: touch.clientY }
  return typeof event.clientX === 'number' && typeof event.clientY === 'number' ? { x: event.clientX, y: event.clientY } : null
}

// In a browser the mouse may be let go outside the map; the page listens for that while dragging.
function onWindowRelease(): void {
  endDrag()
}

function startDrag(event: Event): void {
  if (dragFrom) return
  const at = pointOf(event as PointerEvent)
  if (!at) return
  dragFrom = { ...at, point: pin.value }
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    window.addEventListener('mouseup', onWindowRelease)
    window.addEventListener('touchend', onWindowRelease)
  }
}

function moveDrag(event: Event): void {
  if (!dragFrom) return
  const at = pointOf(event as PointerEvent)
  if (!at) return
  offset.value = { x: at.x - dragFrom.x, y: at.y - dragFrom.y }
}

function endDrag(): void {
  if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
    window.removeEventListener('mouseup', onWindowRelease)
    window.removeEventListener('touchend', onWindowRelease)
  }
  if (!dragFrom) return
  const { x, y } = offset.value
  const from = dragFrom.point
  dragFrom = null
  offset.value = { x: 0, y: 0 }
  // A tap is not a drag.
  if (Math.abs(x) < 4 && Math.abs(y) < 4) return
  pin.value = movePoint(from, x, y)
  if (pick.value?.kind === 'PLACE') manualAddress.value = ''
  pick.value = { kind: 'PIN' }
}

function openSettings(): void {
  const open = (uni as unknown as { openAppAuthorizeSetting?: (options: object) => void }).openAppAuthorizeSetting
  if (typeof open === 'function') {
    open({ fail: () => uni.showToast({ title: '请在系统设置里允许定位', icon: 'none' }) })
    return
  }
  uni.showToast({ title: '请在系统设置里允许定位', icon: 'none' })
}

function confirm(): void {
  if (picked.value) emit('confirm', picked.value)
}

onMounted(() => {
  void locate()
})
</script>

<template>
  <view class="map-pick" data-testid="map-pick" :data-located="located ? 'true' : 'false'">
    <view class="map-pick__head">
      <button class="map-pick__back" data-testid="map-pick-back" aria-label="返回" @click="emit('back')">
        <LineIcon name="back" :size="22" color="#202c42" :stroke-width="2" />
      </button>
      <text class="map-pick__title" role="heading">{{ title }}</text>
    </view>
    <view class="map-pick__search-row">
      <view class="map-pick__search">
        <LineIcon :d="SEARCH_ICON" :size="18" color="#627187" :stroke-width="2" />
        <input
          class="map-pick__input"
          data-testid="map-pick-search"
          type="text"
          :value="keyword"
          placeholder="搜索小区、楼宇或街道"
          placeholder-class="map-pick__placeholder"
          aria-label="搜索地点"
          @input="onInput"
        />
        <button v-if="keyword" class="map-pick__clear" aria-label="清除" @click="onInput({ detail: { value: '' } } as unknown as Event)">
          <view class="map-pick__clear-dot">
            <LineIcon name="close" :size="10" color="#ffffff" :stroke-width="2.6" />
          </view>
        </button>
      </view>
    </view>

    <view v-if="denied" class="map-pick__denied" role="status" data-testid="map-pick-denied">
      <LineIcon :d="INFO_ICON" :size="16" color="#ff6a00" :stroke-width="2" />
      <text class="map-pick__denied-text">未开启定位，可手动搜索</text>
      <button class="map-pick__denied-go" @click="openSettings">去开启</button>
    </view>

    <view
      class="map-pick__map"
      data-testid="map-pick-map"
      role="img"
      :aria-label="`地图，图钉在 ${pinLabel}`"
      :style="{ backgroundPosition: `${offset.x}px ${offset.y}px` }"
      @touchstart="startDrag"
      @touchmove.stop.prevent="moveDrag"
      @touchend="endDrag"
      @touchcancel="endDrag"
      @mousedown="startDrag"
      @mousemove="moveDrag"
      @mouseup="endDrag"
    >
      <view class="map-pick__layer" :style="{ transform: `translate(${offset.x}px, ${offset.y}px)` }">
        <view class="map-pick__block map-pick__block--a" />
        <view class="map-pick__block map-pick__block--b" />
        <view class="map-pick__road map-pick__road--h" />
        <view class="map-pick__road map-pick__road--v" />
        <view v-if="located" class="map-pick__me" />
      </view>
      <view class="map-pick__pin" data-testid="map-pick-pin">
        <text class="map-pick__pin-label">{{ pinLabel }}</text>
        <image class="map-pick__pin-image" :src="PIN_IMAGE" />
      </view>
      <button v-if="located" class="map-pick__relocate" data-testid="map-pick-relocate" aria-label="回到我的位置" @click.stop="relocate">
        <LineIcon :d="LOCATE_ICON" :size="22" color="#202c42" :stroke-width="1.8" />
      </button>
      <text class="map-pick__sketch">示意地图 · 正式产品经地图适配器接入</text>
    </view>

    <view class="map-pick__body">
      <view class="map-pick__card" data-testid="map-pick-chosen">
        <view v-if="pick" class="map-pick__chosen">
          <view class="map-pick__chosen-icon">
            <LineIcon :d="PIN_ICON" :size="20" color="#ff6a00" :stroke-width="1.8" />
          </view>
          <view class="map-pick__chosen-body">
            <template v-if="pick.kind === 'PLACE'">
              <text class="map-pick__chosen-name">{{ pick.place.name }}</text>
              <text class="map-pick__chosen-address">{{ pick.place.address }}</text>
            </template>
            <template v-else>
              <text class="map-pick__chosen-name">图钉位置 · {{ formatPoint(pin) }}</text>
              <input
                class="map-pick__manual"
                data-testid="map-pick-manual"
                type="text"
                :value="manualAddress"
                :maxlength="MAP_ADDRESS_MAX"
                placeholder="填写详细地址（必填）"
                placeholder-class="map-pick__placeholder"
                aria-label="详细地址"
                @input="setManualAddress"
              />
            </template>
          </view>
        </view>
        <view v-else class="map-pick__chosen">
          <view class="map-pick__chosen-icon map-pick__chosen-icon--empty">
            <LineIcon :d="PIN_ICON" :size="20" color="#738096" :stroke-width="1.8" />
          </view>
          <text class="map-pick__empty">在地图上拖动选点，或搜索地址</text>
        </view>
        <text v-if="tip" class="map-pick__tip">{{ tip }}</text>
      </view>

      <view class="map-pick__card">
        <text class="map-pick__list-title">{{ listTitle }}</text>
        <button
          v-for="place in places"
          :key="place.placeId"
          class="map-pick__place"
          role="radio"
          :aria-checked="pick?.kind === 'PLACE' && pick.place.placeId === place.placeId"
          :data-testid="`map-pick-place-${place.placeId.split(':').pop()}`"
          @click="choose(place)"
        >
          <view class="map-pick__place-body">
            <text :class="['map-pick__place-name', { 'map-pick__place-name--on': pick?.kind === 'PLACE' && pick.place.placeId === place.placeId }]">{{ place.name }}</text>
            <text class="map-pick__place-sub">{{ subLine(place) }}</text>
          </view>
          <view v-if="pick?.kind === 'PLACE' && pick.place.placeId === place.placeId" class="map-pick__radio map-pick__radio--on">
            <LineIcon name="check" :size="12" color="#ffffff" :stroke-width="3.2" />
          </view>
          <view v-else class="map-pick__radio" />
        </button>
        <view v-if="typed && places.length === 0" class="map-pick__none" data-testid="map-pick-none">
          <text>没有找到“{{ typed }}”相关的地点，换个关键词试试</text>
          <button class="map-pick__use-typed" data-testid="map-pick-use-typed" @click="useTypedAddress">就用“{{ typed }}”作为图钉位置的地址</button>
        </view>
      </view>
    </view>

    <view class="map-pick__foot">
      <button class="map-pick__confirm" data-testid="map-pick-confirm" :disabled="!picked" @click="confirm">确认地址</button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 board MapPick (390 wide). */
.map-pick {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
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

.map-pick__head {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: calc(var(--status-bar-height) + 8px) 8px 8px;
  background: #fff;
}

.map-pick__back {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.map-pick__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.map-pick__search-row {
  padding: 0 16px 10px;
  background: #fff;
}

.map-pick__search {
  display: flex;
  height: 40px;
  align-items: center;
  gap: 8px;
  padding: 0 4px 0 12px;
  border-radius: 999px;
  background: #eef2f8;
  box-sizing: border-box;
}

.map-pick__input {
  min-width: 0;
  flex: 1 1 auto;
  font-size: 14px;
}

.map-pick__placeholder {
  color: #738096;
}

.map-pick__clear {
  display: flex;
  width: 44px;
  height: 40px;
  align-items: center;
  justify-content: center;
}

.map-pick__clear-dot {
  display: flex;
  width: 16px;
  height: 16px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #c9d3e1;
}

.map-pick__denied {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border-top: 1px solid #ffd8b8;
  border-bottom: 1px solid #ffd8b8;
  background: #fff0e3;
}

.map-pick__denied-text {
  min-width: 0;
  flex: 1 1 auto;
  color: #ba470b;
  font-size: 13px;
  line-height: 18px;
}

.map-pick__denied-go {
  display: flex;
  height: 32px;
  align-items: center;
  padding: 0 12px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #fff;
  font-size: 13px;
  box-sizing: border-box;
}

.map-pick__map {
  position: relative;
  height: 240px;
  flex: none;
  overflow: hidden;
  background-color: #eef0f3;
  background-image: linear-gradient(#e1e4e8 1px, transparent 1px), linear-gradient(90deg, #e1e4e8 1px, transparent 1px);
  background-size: 32px 32px;
  touch-action: none;
  user-select: none;
}

.map-pick__layer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
}

.map-pick__block {
  position: absolute;
  border-radius: 8px;
  background: #e3e7eb;
}

.map-pick__block--a {
  top: 30px;
  left: 28px;
  width: 118px;
  height: 64px;
}

.map-pick__block--b {
  top: 150px;
  left: 236px;
  width: 126px;
  height: 58px;
}

.map-pick__road {
  position: absolute;
  background: #fff;
}

.map-pick__road--h {
  top: 118px;
  right: -400px;
  left: -400px;
  height: 8px;
}

.map-pick__road--v {
  top: -400px;
  bottom: -400px;
  left: 196px;
  width: 8px;
}

.map-pick__me {
  position: absolute;
  top: 113px;
  left: 188px;
  width: 14px;
  height: 14px;
  border: 3px solid #fff;
  border-radius: 50%;
  background: #2f80ed;
  box-shadow: 0 0 0 6px rgba(47, 128, 237, 0.18);
  box-sizing: border-box;
}

.map-pick__pin {
  position: absolute;
  top: 50%;
  left: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  transform: translate(-50%, -100%);
  pointer-events: none;
}

.map-pick__pin-label {
  padding: 4px 8px;
  border-radius: 6px;
  color: #fff;
  background: #202c42;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.map-pick__pin-image {
  width: 30px;
  height: 38px;
}

.map-pick__relocate {
  position: absolute;
  right: 12px;
  bottom: 12px;
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.map-pick__sketch {
  position: absolute;
  bottom: 12px;
  left: 12px;
  padding: 2px 8px;
  border-radius: 6px;
  color: #738096;
  background: rgba(255, 255, 255, 0.88);
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.map-pick__body {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.map-pick__card {
  display: flex;
  flex-direction: column;
  padding: 0 14px;
  border-radius: 14px;
  background: #fff;
}

.map-pick__chosen {
  display: flex;
  min-height: 64px;
  align-items: center;
  gap: 10px;
}

.map-pick__chosen-icon {
  display: flex;
  width: 36px;
  height: 36px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff0e3;
}

.map-pick__chosen-icon--empty {
  background: #eef2f8;
}

.map-pick__chosen-body {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 2px;
  padding: 10px 0;
}

.map-pick__chosen-name {
  overflow: hidden;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.map-pick__chosen-address {
  overflow: hidden;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.map-pick__manual {
  height: 36px;
  margin-top: 4px;
  padding: 0 10px;
  border-radius: 8px;
  background: #f5f7fc;
  font-size: 14px;
}

.map-pick__empty {
  color: #738096;
  font-size: 14px;
  line-height: 20px;
}

.map-pick__tip {
  padding-bottom: 10px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.map-pick__list-title {
  display: flex;
  height: 40px;
  align-items: center;
  color: #627187;
  font-size: 13px;
  line-height: 40px;
}

.map-pick__place {
  display: flex;
  width: 100%;
  min-height: 50px;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
  color: #202c42;
  text-align: left;
}

.map-pick__place-body {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 1px;
}

.map-pick__place-name {
  overflow: hidden;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.map-pick__place-name--on {
  font-weight: 500;
}

.map-pick__place-sub {
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.map-pick__radio {
  width: 20px;
  height: 20px;
  flex: none;
  border: 1.5px solid #c9d3e1;
  border-radius: 50%;
  background: #fff;
  box-sizing: border-box;
}

.map-pick__radio--on {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  background: #ff6a00;
}

.map-pick__none {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 2px 0 14px;
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.map-pick__use-typed {
  display: flex;
  min-height: 36px;
  align-items: center;
  color: #ba470b;
  font-size: 13px;
  text-align: left;
}

.map-pick__foot {
  position: sticky;
  bottom: 0;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #fff;
}

.map-pick__confirm {
  width: 100%;
  height: 46px;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
}

.map-pick__confirm[disabled] {
  color: #fff;
  background: #c9d3e1;
}
</style>
