<script setup lang="ts">
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import {
  createPagedListLoader,
  emptyPagedList,
  type PagedListState,
} from '@/shared/list/paged-list-loader'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { MALL_PREVIEW_ENABLED } from '../../../mall/application/mall-preview'
import { buildMallProductUrl } from '../../../mall/navigation/mall-routes'
import { experienceApi } from '../../data/experience-api-client'
import { buildVenueDetailUrl } from '../../navigation/experience-routes'
import {
  EXPERIENCE_PAGE_SIZE,
  type VenueDevice,
  type VenueDeviceCategory,
} from '../../domain/experience'
import {
  VENUE_DEVICE_CATEGORY_LABELS,
  VENUE_DEVICE_TRIAL_LABELS,
  formatVenueDeviceOffer,
} from '../shared/experience-format'

const CATEGORY_TABS: readonly { code: VenueDeviceCategory | null; label: string }[] = [
  { code: null, label: '全部' },
  { code: 'ROBOT', label: VENUE_DEVICE_CATEGORY_LABELS.ROBOT },
  { code: 'DRONE', label: VENUE_DEVICE_CATEGORY_LABELS.DRONE },
  { code: 'PARTS', label: VENUE_DEVICE_CATEGORY_LABELS.PARTS },
]

const selectedCategory = ref<VenueDeviceCategory | null>(null)
const devices = ref<PagedListState<VenueDevice>>(emptyPagedList())
// Board VenueDevices' subtitle: "东营低空与机器人体验馆 · 6 件". Left out if the venue cannot be read.
const subtitle = ref<string | undefined>(undefined)
let venueId = ''
let cityCode = ''

async function loadSubtitle(): Promise<void> {
  try {
    const venue = await experienceApi.loadVenue(venueId, cityCode)
    subtitle.value = `${venue.name} · ${venue.deviceCount} 件`
  } catch {
    subtitle.value = undefined
  }
}

const loader = createPagedListLoader<VenueDeviceCategory | null, VenueDevice>({
  loadPage: (category, page, size) =>
    experienceApi.loadDevices({ venueId, cityCode, category }, page, size),
  getState: () => devices.value,
  setState: (state) => { devices.value = state },
  keyOf: (device) => device.deviceId,
  pageSize: EXPERIENCE_PAGE_SIZE,
})

function reload(): void {
  void loader.reload(selectedCategory.value)
}

function selectCategory(category: VenueDeviceCategory | null): void {
  if (selectedCategory.value === category) return
  selectedCategory.value = category
  reload()
}

// Board VenueDevices: a device whose product sells in this city opens the product page, where the
// customer picks a merchant. Without the mall (public build) the card stays a plain card.
const productLinks = MALL_PREVIEW_ENABLED
const notice = productLinks
  ? '到馆可看真机，想买或想租到商品页选商家'
  : '到馆可看真机，能不能试用以现场标注为准'

const offerTexts = computed(() => new Map(devices.value.items
  .filter((device) => device.product)
  .map((device) => [device.deviceId, formatVenueDeviceOffer(device.product!)])))

function openProduct(device: VenueDevice): void {
  if (!productLinks || !device.product) return
  uni.navigateTo({ url: buildMallProductUrl(device.product.productId, cityCode) })
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('EXPERIENCE')
}

// To the venue's page, where a visit is booked: back when this page was opened from it,
// otherwise in place of this page, so the device list is not left under it twice.
function openVenue(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildVenueDetailUrl(venueId, cityCode) })
}

onLoad((query) => {
  venueId = typeof query?.venueId === 'string' ? query.venueId : ''
  cityCode = typeof query?.cityCode === 'string' ? query.cityCode : ''
  reload()
  if (venueId && cityCode) void loadSubtitle()
})

onReachBottom(() => {
  void loader.loadMore()
})
</script>

<template>
  <view class="venue-devices" data-testid="venue-devices">
    <view class="venue-devices__top">
      <AppPageHeader title="馆内展示设备" :subtitle="subtitle" test-id="venue-devices" @back="goBack">
        <template #bottom>
          <view class="venue-devices__tabs">
            <button
              v-for="tab in CATEGORY_TABS"
              :key="tab.label"
              :class="['venue-devices__tab', { 'venue-devices__tab--active': tab.code === selectedCategory }]"
              :data-testid="`venue-devices-tab-${(tab.code ?? 'all').toLowerCase()}`"
              :aria-pressed="tab.code === selectedCategory"
              @click="selectCategory(tab.code)"
            >
              {{ tab.label }}
            </button>
          </view>
        </template>
      </AppPageHeader>
    </view>

    <view class="venue-devices__content">
      <view class="venue-devices__notice">
        <LineIcon name="info" :size="16" color="#11804a" :stroke-width="2" />
        <text>{{ notice }}</text>
      </view>

      <view
        v-if="devices.status === 'loading' || devices.status === 'idle'"
        class="venue-devices__list"
        data-testid="venue-devices-loading"
        aria-label="正在加载"
      >
        <view v-for="index in 3" :key="index" class="venue-devices__skeleton" />
      </view>
      <view v-else-if="devices.status === 'error'" class="venue-devices__state" data-testid="venue-devices-error">
        <text class="venue-devices__state-title">设备加载失败，请稍后重试</text>
        <button class="venue-devices__state-action" data-testid="venue-devices-retry" @click="reload">
          重新加载
        </button>
      </view>
      <view v-else-if="devices.items.length === 0" class="venue-devices__state" data-testid="venue-devices-empty">
        <text class="venue-devices__state-title">暂无这类设备</text>
      </view>
      <view v-else class="venue-devices__list">
        <view
          v-for="device in devices.items"
          :key="device.deviceId"
          :class="['venue-devices__card', { 'venue-devices__card--link': productLinks && device.product }]"
          :data-testid="`venue-device-${device.deviceId}`"
          :data-category="device.category"
          :role="productLinks && device.product ? 'link' : undefined"
          @click="openProduct(device)"
        >
          <view :class="['venue-devices__cover', `venue-devices__cover--${device.category.toLowerCase()}`]">
            <image
              v-if="device.image?.url"
              class="venue-devices__image"
              :src="device.image?.url ?? ''"
              :alt="device.name"
              mode="aspectFill"
              lazy-load
            />
            <text v-else class="venue-devices__cover-label">{{ VENUE_DEVICE_CATEGORY_LABELS[device.category] }}</text>
          </view>
          <view class="venue-devices__body">
            <text class="venue-devices__name">{{ device.name }}</text>
            <text v-if="device.summary" class="venue-devices__summary">{{ device.summary }}</text>
            <text
              :class="[
                'venue-devices__trial',
                `venue-devices__trial--${device.trialMode.toLowerCase()}`,
              ]"
            >
              {{ VENUE_DEVICE_TRIAL_LABELS[device.trialMode] }}
            </text>
            <view
              v-if="offerTexts.get(device.deviceId)"
              class="venue-devices__offer"
              :data-testid="`venue-device-offer-${device.deviceId}`"
            >
              <text class="venue-devices__price">{{ offerTexts.get(device.deviceId)?.price }}</text>
              <text v-if="offerTexts.get(device.deviceId)?.unit" class="venue-devices__unit">
                {{ offerTexts.get(device.deviceId)?.unit }}
              </text>
              <text class="venue-devices__sellers">{{ offerTexts.get(device.deviceId)?.sellers }}</text>
            </view>
          </view>
        </view>
        <text v-if="devices.loadingMore" class="venue-devices__more">正在加载…</text>
      </view>
    </view>

    <view v-if="venueId && cityCode" class="venue-devices__actions">
      <button class="venue-devices__primary" data-testid="venue-devices-book" @click="openVenue">
        预约到馆看真机
      </button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board VenueDevices (390 wide). */
.venue-devices {
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
  text-align: left;
}

button::after {
  border: 0;
}

.venue-devices__top {
  position: sticky;
  top: 0;
  z-index: 5;
  flex: none;
}

.venue-devices__content {
  display: flex;
  flex: 1;
  flex-direction: column;
}

.venue-devices__tabs {
  display: flex;
  padding: 2px 16px 8px;
  gap: 8px;
}

/* The pill is 36 high; the button is 44 so the tap target meets the minimum. */
.venue-devices__tab {
  position: relative;
  display: flex;
  height: 44px;
  padding: 0 14px;
  flex: 0 0 auto;
  align-items: center;
  color: #202c42;
  font-size: 14px;
  z-index: 0;
}

.venue-devices__tab::before {
  position: absolute;
  top: 4px;
  right: 0;
  bottom: 4px;
  left: 0;
  border: 1.5px solid #e5ebf3;
  border-radius: 999px;
  background: #ffffff;
  content: '';
  z-index: -1;
}

.venue-devices__tab--active {
  color: #ba470b;
  font-weight: 500;
}

.venue-devices__tab--active::before {
  border-color: #ff6a00;
  background: #fff0e3;
}

.venue-devices__notice {
  display: flex;
  min-height: 40px;
  margin: 12px 16px 0;
  padding: 0 12px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  color: #11804a;
  background: #eefaf3;
  font-size: 13px;
  line-height: 18px;
}

.venue-devices__list {
  display: flex;
  padding: 10px 16px 12px;
  flex-direction: column;
  gap: 10px;
}

.venue-devices__skeleton {
  height: 120px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
  background-size: 400% 100%;
  animation: venue-devices-skeleton 1.25s ease infinite;
}

@keyframes venue-devices-skeleton {
  0% { background-position: 100% 0; }
  100% { background-position: 0 0; }
}

.venue-devices__state {
  display: flex;
  min-height: 160px;
  margin: 10px 16px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 14px;
  background: #ffffff;
}

.venue-devices__state-title {
  color: #738096;
  font-size: 14px;
}

.venue-devices__state-action {
  display: flex;
  height: 44px;
  padding: 0 22px;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 14px;
  font-weight: 500;
  box-sizing: border-box;
}

.venue-devices__card {
  display: flex;
  padding: 12px;
  gap: 12px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
}

.venue-devices__card--link {
  cursor: pointer;
}

.venue-devices__cover {
  display: flex;
  width: 96px;
  height: 96px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 10px;
  background: #eef0f3;
}

.venue-devices__image {
  width: 100%;
  height: 100%;
}

.venue-devices__cover-label {
  color: #738096;
  font-size: 12px;
}

.venue-devices__body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.venue-devices__name {
  max-width: 100%;
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-overflow: ellipsis;
}

.venue-devices__summary {
  max-width: 100%;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.venue-devices__trial {
  padding: 1px 6px;
  border-radius: 6px;
  color: #627187;
  background: #eef2f8;
  font-size: 12px;
  line-height: 17px;
}

.venue-devices__trial--accompanied_trial {
  color: #11804a;
  background: #e2f6ed;
}

.venue-devices__actions {
  position: sticky;
  bottom: 0;
  z-index: 5;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: 0 -4px 18px rgba(38, 59, 98, 0.06);
}

.venue-devices__primary {
  display: flex;
  height: 46px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  font-size: 16px;
  font-weight: 500;
}

.venue-devices__more {
  min-height: 20px;
  color: #738096;
  font-size: 12px;
  text-align: center;
}
.venue-devices__offer {
  display: flex;
  width: 100%;
  margin-top: auto;
  align-items: baseline;
  gap: 2px;
}

.venue-devices__price {
  color: #e85400;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  white-space: nowrap;
}

.venue-devices__unit {
  color: #e85400;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.venue-devices__sellers {
  margin-left: auto;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}
</style>
