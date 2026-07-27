<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

defineProps<{
  cityName: string
  keyword: string
  weatherLabel?: string
  unreadCount?: number
}>()

const emit = defineEmits<{
  'update:keyword': [value: string]
  search: []
  selectCity: []
  selectSibling: [label: string]
}>()

const serviceTypes = ['我要航拍', '我要巡检', '我要飞无人机', '机器人服务', '更多服务']

function handleInput(event: Event): void {
  const inputEvent = event as Event & {
    detail?: {
      value?: string
    }
  }

  emit('update:keyword', inputEvent.detail?.value ?? '')
}
</script>

<template>
  <view class="portal-header">
    <view class="portal-header__top">
      <button
        class="portal-header__city"
        aria-label="选择当前城市"
        @click="emit('selectCity')"
      >
        <view class="portal-header__city-main">
          <text class="portal-header__city-name">{{ cityName }}</text>
          <UniIcons type="down" size="10" color="#1f2328" />
        </view>
        <view v-if="weatherLabel" class="portal-header__weather-row">
          <text class="portal-header__weather">{{ weatherLabel }}</text>
          <UniIcons
            class="portal-header__weather-icon"
            type="smallcircle-filled"
            size="26"
            color="#f7b638"
          />
        </view>
      </button>

      <view class="portal-header__search">
        <UniIcons
          class="portal-header__search-icon"
          type="search"
          size="17"
          color="#4f545b"
        />
        <input
          class="portal-header__input"
          :value="keyword"
          maxlength="50"
          confirm-type="search"
          placeholder="搜索巡检服务、场景、服务商"
          @input="handleInput"
          @confirm="emit('search')"
        />
      </view>

      <button
        class="portal-header__message"
        aria-label="消息功能未接入"
        @click="emit('selectSibling', '消息功能')"
      >
        <UniIcons type="chat" size="27" color="#16191d" />
        <text v-if="unreadCount" class="portal-header__badge">
          {{ unreadCount }}
        </text>
      </button>
    </view>

    <view class="service-tabs">
      <view class="service-tabs__track">
        <button
          v-for="item in serviceTypes"
          :key="item"
          class="service-tabs__item"
          :class="{ 'service-tabs__item--active': item === '我要巡检' }"
          @click="
            item === '我要巡检'
              ? undefined
              : emit('selectSibling', item)
          "
        >
          <image
            v-if="item === '更多服务'"
            class="service-tabs__more-icon"
            src="/static/images/inspection/category-more.webp"
            mode="aspectFit"
            aria-hidden="true"
          />
          <text>{{ item }}</text>
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.portal-header {
  padding:
    calc(env(safe-area-inset-top) + clamp(4px, 1vw, 6px))
    clamp(12px, 3vw, 18px)
    clamp(2px, 0.6vw, 4px);
  background: #ffffff;
}

.portal-header__top {
  display: grid;
  grid-template-columns:
    clamp(64px, 17vw, 100px)
    minmax(0, 1fr)
    clamp(34px, 8vw, 48px);
  gap: clamp(7px, 2vw, 12px);
  align-items: center;
}

.portal-header__city,
.portal-header__message,
.service-tabs__item {
  padding: 0;
  border: 0;
  background: transparent;
  line-height: 1;
}

.portal-header__city::after,
.portal-header__message::after,
.service-tabs__item::after {
  border: 0;
}

.portal-header__city {
  display: flex;
  width: 100%;
  height: clamp(36px, 8.5vw, 50px);
  margin: 0;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  color: #191919;
}

.portal-header__city-main {
  display: flex;
  align-items: center;
  gap: clamp(5px, 1.3vw, 8px);
}

.portal-header__city-name {
  max-width: clamp(52px, 13vw, 78px);
  overflow: hidden;
  font-size: clamp(16px, 3.4vw, 20px);
  font-weight: 800;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.portal-header__weather-row {
  display: flex;
  margin-top: clamp(4px, 1.1vw, 7px);
  align-items: center;
  gap: clamp(4px, 1vw, 6px);
}

.portal-header__weather {
  color: #343940;
  font-size: clamp(11px, 2.5vw, 15px);
  line-height: 1;
}

.portal-header__weather-icon {
  font-size: clamp(22px, 4.3vw, 26px) !important;
}

.portal-header__search {
  display: flex;
  height: clamp(34px, 7.2vw, 43px);
  min-width: 0;
  align-items: center;
  padding: 0 clamp(12px, 3.2vw, 19px);
  border: 1px solid #7d838c;
  border-radius: 999px;
  background: #ffffff;
  box-sizing: border-box;
}

.portal-header__search-icon {
  margin-right: clamp(8px, 2.1vw, 13px);
  flex: 0 0 auto;
  font-size: clamp(17px, 3.5vw, 21px) !important;
}

.portal-header__input {
  width: 100%;
  min-width: 0;
  color: #191919;
  font-size: clamp(12px, 2.7vw, 16px);
}

.portal-header__message {
  position: relative;
  display: flex;
  width: clamp(34px, 8vw, 48px);
  height: clamp(36px, 8.5vw, 50px);
  align-items: center;
  justify-content: center;
  transform: translateX(clamp(6px, 1.7vw, 10px));
}

.portal-header__badge {
  position: absolute;
  top: -1px;
  right: -1px;
  display: flex;
  min-width: clamp(16px, 3.2vw, 19px);
  height: clamp(16px, 3.2vw, 19px);
  padding: 0 3px;
  align-items: center;
  justify-content: center;
  border: 2px solid #ffffff;
  border-radius: 999px;
  color: #ffffff;
  background: #ff3b30;
  box-sizing: border-box;
  font-size: clamp(9px, 1.8vw, 11px);
  font-weight: 800;
}

.service-tabs {
  width: auto;
  margin-top: clamp(8px, 1.8vw, 11px);
  margin-inline: clamp(-9px, -1.5vw, -6px);
  white-space: nowrap;
}

.service-tabs__track {
  display: grid;
  grid-template-columns: 148fr 155fr 196fr 190fr 149fr;
  width: 100%;
  gap: clamp(6px, 1.8vw, 11px);
  padding-bottom: clamp(5px, 1.2vw, 7px);
}

.service-tabs__item {
  position: relative;
  display: flex;
  width: 100%;
  min-width: 0;
  height: clamp(28px, 6vw, 36px);
  margin: 0;
  padding: 0 clamp(2px, 0.6vw, 4px);
  align-items: center;
  justify-content: center;
  gap: clamp(3px, 0.8vw, 5px);
  border-radius: clamp(9px, 2.7vw, 16px);
  color: #2d3035;
  background: #f6f6f7;
  font-size: clamp(11px, 2.55vw, 15px);
  font-weight: 500;
  white-space: nowrap;
}

.service-tabs__item--active {
  color: #ffffff;
  background: linear-gradient(135deg, #ff8a18, #ff5200);
  font-weight: 700;
  box-shadow: 0 clamp(3px, 0.8vw, 5px) clamp(8px, 2.1vw, 13px)
    rgba(255, 82, 0, 0.16);
}

.service-tabs__item--active::before {
  position: absolute;
  bottom: -6px;
  left: 50%;
  width: 46%;
  height: 9px;
  border-bottom: 3px solid #ff6a00;
  border-radius: 0 0 50% 50%;
  content: '';
  transform: translateX(-50%);
}

.service-tabs__item--active::after {
  position: absolute;
  bottom: -1px;
  left: 50%;
  width: 42%;
  height: 8px;
  border: 0;
  border-bottom: 3px solid #ffffff;
  border-radius: 0 0 50% 50%;
  content: '';
  transform: translateX(-50%);
}

.service-tabs__more-icon {
  display: block;
  width: clamp(12px, 2.4vw, 14px);
  height: clamp(12px, 2.4vw, 14px);
  flex: 0 0 auto;
}

@media (max-width: 359px) {
  .portal-header__top {
    grid-template-columns: 58px minmax(0, 1fr) 30px;
    gap: 5px;
  }

  .service-tabs__track {
    gap: 4px;
  }

  .service-tabs__item {
    font-size: 10px;
  }
}
</style>
