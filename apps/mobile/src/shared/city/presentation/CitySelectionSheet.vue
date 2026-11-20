<script lang="ts">
export type CitySelectionSource =
  | 'CURRENT'
  | 'RECENT'
  | 'HOT'
  | 'SEARCH'
  | 'TREE'
</script>

<script setup lang="ts">
import { computed, ref } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  searchCities,
  type CityDirectory,
  type PlatformCity,
} from '../domain/city-directory'
import type { CurrentCity } from '../state/current-city'

const props = defineProps<{
  directory: CityDirectory | null
  currentCity: CurrentCity | null
  recentCities: PlatformCity[]
  loading: boolean
  error: string
  relocating: boolean
}>()

const emit = defineEmits<{
  close: []
  retry: []
  relocate: []
  select: [payload: {
    city: PlatformCity
    source: CitySelectionSource
  }]
}>()

const keyword = ref('')
const expandedProvinceCode = ref<string | null>(null)
const searchResults = computed(() =>
  props.directory ? searchCities(props.directory, keyword.value) : [])
const isSearching = computed(() => keyword.value.trim().length > 0)
const currentPlatformCity = computed(() => props.directory?.provinces
  .flatMap((province) => province.cities)
  .find((city) => city.code === props.currentCity?.code) ?? null)

function isCurrent(city: PlatformCity): boolean {
  return props.currentCity?.code === city.code
}
</script>

<template>
  <view class="city-sheet__mask" @click.self="$emit('close')">
    <view
      class="city-sheet"
      role="dialog"
      aria-modal="true"
      aria-label="选择城市"
    >
      <view class="city-sheet__header">
        <text class="city-sheet__title">选择城市</text>
        <button
          class="city-sheet__close"
          aria-label="关闭"
          @click="$emit('close')"
        >
          <LineIcon name="close" :size="20" :stroke-width="2" color="#536178" />
        </button>
      </view>

      <view class="city-sheet__search">
        <LineIcon name="search" :size="18" :stroke-width="2" color="#627187" />
        <input
          v-model="keyword"
          class="city-sheet__search-input"
          type="text"
          placeholder="输入城市名"
          placeholder-class="city-sheet__search-placeholder"
          aria-label="搜索城市"
          confirm-type="search"
        >
      </view>

      <scroll-view class="city-sheet__scroll" scroll-y>
        <view class="city-sheet__content">
          <view class="city-sheet__current-card">
            <template v-if="currentCity">
              <button
                class="city-sheet__current-city"
                :disabled="!currentPlatformCity"
                @click="
                  currentPlatformCity &&
                    emit('select', {
                      city: currentPlatformCity,
                      source: 'CURRENT',
                    })
                "
              >
                <LineIcon name="pin" :size="20" :stroke-width="2" color="#ff6a00" />
                <view class="city-sheet__current-copy">
                  <text class="city-sheet__current-label">当前定位</text>
                  <text class="city-sheet__current-name">{{ currentCity.name }}</text>
                </view>
              </button>
              <button
                class="city-sheet__relocate"
                :disabled="relocating"
                @click="!relocating && emit('relocate')"
              >
                {{ relocating ? '定位中…' : '重新定位' }}
              </button>
            </template>
            <template v-else>
              <view class="city-sheet__current-empty">
                <LineIcon name="pin" :size="20" :stroke-width="2" color="#738096" />
                <view class="city-sheet__current-copy">
                  <text class="city-sheet__current-label">当前定位</text>
                  <text class="city-sheet__current-name">暂未定位</text>
                </view>
              </view>
              <button
                class="city-sheet__relocate"
                :disabled="relocating"
                @click="!relocating && emit('relocate')"
              >
                {{ relocating ? '定位中…' : '立即定位' }}
              </button>
            </template>
          </view>

          <view
            v-if="error && directory"
            class="city-sheet__notice"
            role="alert"
          >
            <text>{{ error }}</text>
            <button @click="$emit('retry')">重试</button>
          </view>

          <view
            v-if="loading && !directory"
            class="city-sheet__state"
            aria-live="polite"
          >
            <text>城市加载中</text>
          </view>
          <view
            v-else-if="error && !directory"
            class="city-sheet__state city-sheet__state--error"
            role="alert"
          >
            <text>{{ error }}</text>
            <button @click="$emit('retry')">重新加载</button>
          </view>

          <section v-else-if="isSearching" class="city-sheet__section">
            <text class="city-sheet__section-title">搜索结果</text>
            <view
              v-if="searchResults.length > 0"
              class="city-sheet__province-cities"
            >
              <button
                v-for="city in searchResults"
                :key="city.code"
                class="city-sheet__city"
                :class="{ 'city-sheet__city--selected': isCurrent(city) }"
                @click="emit('select', { city, source: 'SEARCH' })"
              >
                {{ city.name }}{{ isCurrent(city) ? ' ✓' : '' }}
              </button>
            </view>
            <text v-else class="city-sheet__empty">
              未找到相关城市
            </text>
          </section>

          <template v-else>
            <section
              v-if="recentCities.length > 0"
              class="city-sheet__section"
            >
              <text class="city-sheet__section-title">最近使用</text>
              <view class="city-sheet__recent">
                <button
                  v-for="city in recentCities"
                  :key="city.code"
                  class="city-sheet__city city-sheet__recent-city"
                  :class="{ 'city-sheet__city--selected': isCurrent(city) }"
                  @click="emit('select', { city, source: 'RECENT' })"
                >
                  {{ city.name }}{{ isCurrent(city) ? ' ✓' : '' }}
                </button>
              </view>
            </section>

            <section
              v-if="directory?.hotCities.length"
              class="city-sheet__section"
            >
              <text class="city-sheet__section-title">热门城市</text>
              <view class="city-sheet__hot">
                <button
                  v-for="city in directory.hotCities"
                  :key="city.code"
                  class="city-sheet__city"
                  :class="{ 'city-sheet__city--selected': isCurrent(city) }"
                  @click="emit('select', { city, source: 'HOT' })"
                >
                  {{ city.name }}{{ isCurrent(city) ? ' ✓' : '' }}
                </button>
              </view>
            </section>

            <section
              v-if="directory?.provinces.length"
              class="city-sheet__section city-sheet__section--more"
            >
              <text class="city-sheet__section-title">更多城市</text>
              <view
                v-for="province in directory.provinces"
                :key="province.code"
                class="city-sheet__province-group"
              >
                <button
                  class="city-sheet__province"
                  :aria-expanded="
                    expandedProvinceCode === province.code
                  "
                  @click="expandedProvinceCode =
                    expandedProvinceCode === province.code ? null : province.code"
                >
                  <text>{{ province.name }}</text>
                  <LineIcon
                    :name="expandedProvinceCode === province.code ? 'chevronUp' : 'chevronRight'"
                    :size="16"
                    :stroke-width="2"
                    color="#91a0b4"
                  />
                </button>
                <view
                  v-if="expandedProvinceCode === province.code"
                  class="city-sheet__province-cities"
                >
                  <button
                    v-for="city in province.cities"
                    :key="city.code"
                    class="city-sheet__city"
                    :class="{ 'city-sheet__city--selected': isCurrent(city) }"
                    @click="
                      emit('select', {
                        city,
                        source: 'TREE',
                      })
                    "
                  >
                    {{ city.name }}{{ isCurrent(city) ? ' ✓' : '' }}
                  </button>
                </view>
              </view>
            </section>

            <text
              v-if="
                directory &&
                  !loading &&
                  !error &&
                  recentCities.length === 0 &&
                  directory.hotCities.length === 0 &&
                  directory.provinces.length === 0
              "
              class="city-sheet__empty"
            >
              暂无可选城市
            </text>
          </template>

          <text class="city-sheet__hint">
            切换城市后，活动、场馆、本地现货和城市资讯随之变化；账号、订单和参与人不变，跨城通用。
          </text>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board CitySheet (390 wide). */
.city-sheet__mask {
  position: fixed;
  z-index: 80;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.city-sheet {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  width: 100%;
  max-width: 600px;
  height: min(68vh, 760px);
  max-height: min(82vh, 760px);
  margin: 0 auto;
  padding-bottom: env(safe-area-inset-bottom);
  overflow: hidden;
  flex-direction: column;
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #fff;
  box-sizing: border-box;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

.city-sheet__header {
  display: flex;
  padding: 14px 16px 8px;
  flex: 0 0 auto;
  align-items: center;
}

.city-sheet__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.city-sheet__close {
  display: flex;
  width: 44px;
  height: 44px;
  margin: -10px -10px -10px auto;
  align-items: center;
  justify-content: center;
}

.city-sheet__search {
  display: flex;
  height: 40px;
  margin: 0 16px;
  padding: 0 14px;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  border-radius: 999px;
  background: #eef2f8;
  box-sizing: border-box;
}

.city-sheet__search-input {
  min-width: 0;
  height: 40px;
  flex: 1;
  color: #202c42;
  font-size: 14px;
  line-height: 40px;
}

.city-sheet__search-placeholder {
  color: #7d8799;
}

.city-sheet__scroll {
  min-height: 0;
  flex: 1;
}

.city-sheet__content {
  display: flex;
  padding: 16px 16px 24px;
  flex-direction: column;
  gap: 16px;
}

.city-sheet__current-card {
  display: flex;
  padding: 0 12px;
  align-items: center;
  gap: 10px;
  border: 1px solid #ffd8b8;
  border-radius: 12px;
  background: #fffaf5;
}

.city-sheet__current-city,
.city-sheet__current-empty {
  display: flex;
  min-width: 0;
  min-height: 64px;
  flex: 1;
  align-items: center;
  gap: 10px;
  color: #202c42;
  text-align: left;
}

.city-sheet__current-city[disabled] {
  opacity: 1;
}

.city-sheet__current-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.city-sheet__current-label {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.city-sheet__current-name {
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.city-sheet__relocate {
  display: flex;
  min-height: 44px;
  flex: 0 0 auto;
  align-items: center;
  color: #ba470b;
  font-size: 13px;
  white-space: nowrap;
}

.city-sheet__relocate[disabled] {
  color: #738096;
}

.city-sheet__section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.city-sheet__section-title {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.city-sheet__recent,
.city-sheet__hot,
.city-sheet__province-cities {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.city-sheet__recent .city-sheet__city,
.city-sheet__hot .city-sheet__city,
.city-sheet__province-cities .city-sheet__city {
  width: 100%;
}

/* 40 high on the board; 44 here so each city meets the tap minimum. */
.city-sheet__city {
  display: flex;
  min-width: 0;
  height: 44px;
  margin: 0;
  padding: 0 6px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1.5px solid transparent;
  border-radius: 10px;
  color: #536178;
  background: #eef2f8;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
  box-sizing: border-box;
}

.city-sheet__city--selected {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fffaf5;
  font-weight: 500;
}

.city-sheet__province-group {
  border-bottom: 1px solid #eef2f8;
}

.city-sheet__province {
  display: flex;
  width: 100%;
  min-height: 44px;
  padding: 0 2px 0 0;
  align-items: center;
  justify-content: space-between;
  color: #202c42;
  font-size: 14px;
  text-align: left;
}

.city-sheet__province-cities {
  padding: 0 0 12px;
}

.city-sheet__notice {
  display: flex;
  min-height: 44px;
  padding: 8px 10px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-radius: 10px;
  color: #7d3c19;
  background: #fff5ed;
  font-size: 13px;
  box-sizing: border-box;
}

.city-sheet__notice button {
  min-width: 56px;
  min-height: 44px;
  padding: 0 8px;
  color: #ba470b;
  font-size: 14px;
}

.city-sheet__state {
  display: flex;
  min-height: 180px;
  padding: 24px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  color: #738096;
  font-size: 14px;
  text-align: center;
  box-sizing: border-box;
}

.city-sheet__state button {
  min-width: 96px;
  min-height: 44px;
  padding: 0 18px;
  border: 1px solid #ff6a00;
  border-radius: 22px;
  color: #ba470b;
  background: #fff;
  font-size: 14px;
}

.city-sheet__state--error {
  color: #d64545;
}

.city-sheet__empty {
  display: flex;
  min-height: 120px;
  align-items: center;
  justify-content: center;
  color: #738096;
  font-size: 14px;
}

.city-sheet__hint {
  padding: 12px;
  border-radius: 12px;
  color: #536178;
  background: #f5f7fc;
  font-size: 12px;
  line-height: 1.7;
}

.city-sheet__close::after,
.city-sheet__current-city::after,
.city-sheet__relocate::after,
.city-sheet__city::after,
.city-sheet__province::after,
.city-sheet__notice button::after,
.city-sheet__state button::after {
  border: 0;
}
</style>
