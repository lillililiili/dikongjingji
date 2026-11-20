<script setup lang="ts">
import { computed } from 'vue'

import type {
  HomeChannelCode,
  HomeChannelDefinition,
} from '@/app-shell/navigation/home-channels'
import LineIcon from '@/shared/ui/LineIcon.vue'

const props = defineProps<{
  cityName: string
  channels: readonly HomeChannelDefinition[]
  activeChannel: HomeChannelCode
  keyword: string
  searchPlaceholder: string
  showScan: boolean
}>()

const emit = defineEmits<{
  'update:keyword': [value: string]
  selectCity: []
  selectChannel: [channel: HomeChannelCode]
  search: []
  scan: []
}>()

const CHANNEL_SCROLL_CONTEXT = 2
// The boards' solid triangle after the city name (HomeFull.html).
const CITY_TRIANGLE = 'M6 9l6 6 6-6z'

// The boards write the city without its "市" ("东营"); the full name stays in the city sheet.
const shortCityName = computed(() =>
  props.cityName.length > 2 && props.cityName.endsWith('市')
    ? props.cityName.slice(0, -1)
    : props.cityName,
)

// Aligns to an earlier channel so the row only moves once the active one would leave the screen.
const channelScrollTarget = computed(() => {
  const activeIndex = props.channels
    .findIndex((channel) => channel.code === props.activeChannel)
  const target = props.channels[Math.max(0, activeIndex - CHANNEL_SCROLL_CONTEXT)]
  return target ? `home-channel-${target.code}` : ''
})

function handleKeywordInput(event: Event): void {
  const value = (
    event as Event & {
      detail?: {
        value?: string
      }
    }
  ).detail?.value

  emit('update:keyword', value?.toString() ?? '')
}

// uni-app reports typing at most once every 100ms, so right after a keystroke the bound keyword
// can be one step behind the box. The confirm event carries what the box holds.
function handleKeywordConfirm(event: Event): void {
  handleKeywordInput(event)
  emit('search')
}
</script>

<template>
  <view class="home-header" data-testid="home-header">
    <view class="home-header__channel-row">
      <button
        class="home-header__city"
        data-testid="home-city"
        aria-label="切换城市"
        @click="emit('selectCity')"
      >
        <text class="home-header__city-name">{{ shortCityName }}</text>
        <LineIcon :d="CITY_TRIANGLE" :size="14" fill="#202c42" />
      </button>
      <view class="home-header__divider" />
      <scroll-view
        class="home-header__channels"
        scroll-x
        scroll-with-animation
        :show-scrollbar="false"
        :scroll-into-view="channelScrollTarget"
      >
        <view class="home-header__channel-track">
          <button
            v-for="channel in channels"
            :id="`home-channel-${channel.code}`"
            :key="channel.code"
            :class="[
              'home-header__channel',
              {
                'home-header__channel--active':
                  channel.code === activeChannel,
              },
            ]"
            :data-testid="`home-channel-${channel.code.toLowerCase()}`"
            :aria-pressed="channel.code === activeChannel"
            @click="emit('selectChannel', channel.code)"
          >
            <text>{{ channel.label }}</text>
          </button>
        </view>
      </scroll-view>
    </view>

    <view class="home-header__search-row">
      <view :class="['home-header__search', { 'home-header__search--no-scan': !showScan }]">
        <button
          v-if="showScan"
          class="home-header__scan"
          data-testid="home-scan"
          aria-label="扫一扫"
          @click="emit('scan')"
        >
          <LineIcon name="scan" :size="20" color="#202c42" />
          <view class="home-header__scan-divider" />
        </button>
        <input
          class="home-header__input"
          data-testid="home-search-input"
          type="text"
          confirm-type="search"
          maxlength="50"
          :value="keyword"
          :placeholder="searchPlaceholder"
          placeholder-class="home-header__placeholder"
          @input="handleKeywordInput"
          @confirm="handleKeywordConfirm"
        />
        <button
          class="home-header__submit"
          data-testid="home-search-submit"
          @click="emit('search')"
        >
          搜索
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.home-header {
  display: flex;
  width: 100%;
  padding: calc(var(--status-bar-height) + 2px) 0 10px;
  flex-direction: column;
  gap: 8px;
  background: linear-gradient(180deg, #fff0e3 0%, #fff8f2 70%, #f5f7fc 100%);
  box-sizing: border-box;
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

.home-header__channel-row {
  position: relative;
  display: flex;
  align-items: center;
  padding-left: 14px;
}

/* Hints that the channel row keeps going past the screen edge. */
.home-header__channel-row::after {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 40px;
  background: linear-gradient(90deg, rgba(255, 243, 232, 0), #fff0e3);
  content: "";
  pointer-events: none;
}

.home-header__city {
  display: flex;
  height: 44px;
  flex: 0 0 auto;
  align-items: center;
  gap: 2px;
  margin-right: 6px;
  color: #202c42;
}

.home-header__city-name {
  max-width: 72px;
  overflow: hidden;
  font-size: 16px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-header__divider {
  width: 1px;
  height: 16px;
  flex: 0 0 auto;
  margin-right: 2px;
  background: #e8d5c4;
}

.home-header__channels {
  min-width: 0;
  flex: 1 1 auto;
  white-space: nowrap;
}

.home-header__channel-track {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding-right: 32px;
}

.home-header__channel {
  position: relative;
  display: inline-flex;
  height: 44px;
  flex: 0 0 auto;
  align-items: center;
  padding: 0 9px;
  color: #202c42;
  font-size: 15px;
  font-weight: 400;
  white-space: nowrap;
}

.home-header__channel--active {
  color: #ff6a00;
  font-size: 18px;
  font-weight: 700;
}

.home-header__channel--active::before {
  position: absolute;
  bottom: 5px;
  left: 50%;
  width: 16px;
  height: 3px;
  margin-left: -8px;
  border-radius: 3px;
  background: #ff6a00;
  content: "";
}

.home-header__search-row {
  padding: 0 16px;
}

.home-header__search {
  display: flex;
  height: 40px;
  align-items: center;
  gap: 8px;
  padding: 0 4px 0 0;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  background: #ffffff;
  box-sizing: border-box;
}

.home-header__search--no-scan {
  padding-left: 14px;
}

.home-header__scan {
  position: relative;
  display: flex;
  height: 44px;
  margin: -2px 0;
  padding: 0 0 0 12px;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

/* A short rule between the scan icon and the words, 16px high as on the board. */
.home-header__scan-divider {
  width: 1px;
  height: 16px;
  background: #e5ebf3;
}

.home-header__input {
  min-width: 0;
  flex: 1 1 auto;
  color: #202c42;
  font-size: 14px;
}

.home-header__placeholder {
  color: #738096;
}

.home-header__submit {
  position: relative;
  display: flex;
  height: 32px;
  flex: 0 0 auto;
  align-items: center;
  padding: 0 16px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
}

/* Extends the 32px button to the 44px touch minimum without changing its look. */
.home-header__submit::before {
  position: absolute;
  top: -6px;
  right: -4px;
  bottom: -6px;
  left: -4px;
  content: "";
}
</style>
