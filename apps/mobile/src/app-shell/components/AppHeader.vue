<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed } from 'vue'

import type { PlatformWeatherPresentation } from '@/shared/weather/platform-weather'

const props = withDefaults(defineProps<{
  cityName: string
  weather: PlatformWeatherPresentation
  searchPlaceholder: string
  unreadCount?: number
  showMessage?: boolean
}>(), {
  unreadCount: 0,
  showMessage: true,
})

const emit = defineEmits<{
  location: []
  search: []
  message: []
}>()

const unreadLabel = computed(() => {
  if (!props.unreadCount || props.unreadCount < 1) return ''
  return props.unreadCount > 99 ? '99+' : String(props.unreadCount)
})
</script>

<template>
  <header
    :class="['app-header', { 'app-header--no-message': showMessage === false }]"
    data-testid="app-header"
  >
    <button
      class="app-header__city"
      data-testid="app-header-city"
      aria-label="选择城市"
      @click="emit('location')"
    >
      <view class="app-header__city-row">
        <text>{{ cityName }}</text>
        <UniIcons type="down" size="13" color="#17191d" />
      </view>
      <view class="app-header__weather">
        <text v-if="weather.available">{{ weather.text }}</text>
        <image :src="weather.iconUrl" mode="aspectFit" />
      </view>
    </button>

    <button
      class="app-header__search"
      data-testid="app-header-search"
      @click="emit('search')"
    >
      <UniIcons type="search" size="21" color="#4c535c" />
      <text>{{ searchPlaceholder }}</text>
    </button>

    <button
      v-if="showMessage !== false"
      class="app-header__message"
      data-testid="app-header-message"
      aria-label="消息"
      @click="emit('message')"
    >
      <UniIcons type="chatboxes" size="29" color="#1d2228" />
      <text v-if="unreadLabel" class="app-header__badge">
        {{ unreadLabel }}
      </text>
    </button>
  </header>
</template>

<style scoped>
.app-header {
  --app-header-height: 54px;
  --app-header-location-width: 76px;
  --app-header-search-height: 48px;
  --app-header-message-width: 42px;
  --app-header-gap: 7px;
  --app-header-horizontal-padding: 10px;
  --app-header-search-radius: 25px;
  --app-header-search-border-width: 1px;
  --app-header-search-padding-inline: 15px;

  display: grid;
  width: 100%;
  height: var(--app-header-height);
  grid-template-columns:
    var(--app-header-location-width)
    minmax(0, 1fr)
    var(--app-header-message-width);
  gap: var(--app-header-gap);
  align-items: center;
  padding: 0 var(--app-header-horizontal-padding);
  box-sizing: border-box;
}

.app-header--no-message {
  grid-template-columns:
    var(--app-header-location-width)
    minmax(0, 1fr);
}

.app-header__city,
.app-header__search,
.app-header__message {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-sizing: border-box;
  font-family: inherit;
  line-height: normal;
}

.app-header__city::after,
.app-header__search::after,
.app-header__message::after {
  border: 0;
}

.app-header__city {
  display: flex;
  width: var(--app-header-location-width);
  height: var(--app-header-height);
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  text-align: left;
}

.app-header__city-row {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 4px;
  font-size: 19px;
  font-weight: 800;
  line-height: 23px;
  white-space: nowrap;
}

.app-header__city-row text {
  white-space: nowrap;
}

.app-header__weather {
  display: flex;
  height: 18px;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
  color: #4f5966;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
}

.app-header__weather image {
  width: 18px;
  height: 18px;
}

.app-header__search {
  display: flex;
  width: 100%;
  height: var(--app-header-search-height);
  align-items: center;
  gap: 9px;
  padding: 0 var(--app-header-search-padding-inline);
  border: var(--app-header-search-border-width) solid #373a3f;
  border-radius: var(--app-header-search-radius);
  color: #68727e;
  font-size: 14px;
  line-height: 17px;
  text-align: left;
}

.app-header__search text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-header__message {
  position: relative;
  display: flex;
  width: var(--app-header-message-width);
  height: var(--app-header-search-height);
  align-items: center;
  justify-content: center;
}

.app-header__badge {
  position: absolute;
  top: -5px;
  right: -2px;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border: 2px solid #ffffff;
  border-radius: 10px;
  color: #ffffff;
  background: #ff4035;
  box-sizing: border-box;
  font-size: 10px;
  font-weight: 800;
  line-height: 14px;
}
</style>
