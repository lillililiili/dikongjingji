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
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

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
</script>

<template>
  <view class="city-sheet__mask" @click.self="$emit('close')">
    <view
      class="city-sheet"
      role="dialog"
      aria-modal="true"
      aria-label="选择服务城市"
    >
      <view class="city-sheet__handle" />

      <view class="city-sheet__header">
        <text class="city-sheet__title">选择服务城市</text>
        <button
          class="city-sheet__close"
          aria-label="关闭"
          @click="$emit('close')"
        >
          <UniIcons type="closeempty" size="24" color="#292d32" />
        </button>
      </view>

      <view class="city-sheet__search">
        <UniIcons type="search" size="20" color="#8c9299" />
        <input
          v-model="keyword"
          class="city-sheet__search-input"
          type="text"
          placeholder="搜索城市"
          confirm-type="search"
        >
      </view>

      <scroll-view class="city-sheet__scroll" scroll-y>
        <view class="city-sheet__content">
          <section class="city-sheet__section">
            <text class="city-sheet__section-title">当前定位</text>
            <view v-if="currentCity" class="city-sheet__current-row">
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
                <UniIcons
                  type="location-filled"
                  size="23"
                  color="#ff5a00"
                />
                <text class="city-sheet__current-name">
                  {{ currentCity.name }}
                </text>
                <text class="city-sheet__current-label">当前定位</text>
              </button>
              <button
                class="city-sheet__relocate"
                :disabled="relocating"
                @click="!relocating && emit('relocate')"
              >
                <text>{{ relocating ? '定位中…' : '重新定位' }}</text>
                <view v-if="!relocating" class="city-sheet__selected-mark">
                  <UniIcons
                    type="checkmarkempty"
                    size="15"
                    color="#ffffff"
                  />
                </view>
              </button>
            </view>
            <view v-else class="city-sheet__current-row">
              <view class="city-sheet__current-empty">
                <UniIcons
                  type="location"
                  size="23"
                  color="#8a9097"
                />
                <text>暂未定位</text>
              </view>
              <button
                class="city-sheet__relocate"
                :disabled="relocating"
                @click="!relocating && emit('relocate')"
              >
                {{ relocating ? '定位中…' : '立即定位' }}
              </button>
            </view>
          </section>

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
                :class="{
                  'city-sheet__city--selected': currentCity?.code === city.code,
                }"
                @click="emit('select', { city, source: 'SEARCH' })"
              >
                <text>{{ city.name }}</text>
                <view
                  v-if="currentCity?.code === city.code"
                  class="city-sheet__city-mark"
                >
                  <UniIcons
                    type="checkmarkempty"
                    size="13"
                    color="#ffffff"
                  />
                </view>
              </button>
            </view>
            <text v-else class="city-sheet__empty">
              未找到匹配城市
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
                  :class="{
                    'city-sheet__city--selected':
                      currentCity?.code === city.code,
                  }"
                  @click="emit('select', { city, source: 'RECENT' })"
                >
                  {{ city.name }}
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
                  :class="{
                    'city-sheet__city--selected':
                      currentCity?.code === city.code,
                  }"
                  @click="emit('select', { city, source: 'HOT' })"
                >
                  <text>{{ city.name }}</text>
                  <view
                    v-if="currentCity?.code === city.code"
                    class="city-sheet__city-mark"
                  >
                    <UniIcons
                      type="checkmarkempty"
                      size="13"
                      color="#ffffff"
                    />
                  </view>
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
                  <UniIcons
                    :type="
                      expandedProvinceCode === province.code
                        ? 'up'
                        : 'right'
                    "
                    size="16"
                    color="#8a9097"
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
                    :class="{
                      'city-sheet__city--selected':
                        currentCity?.code === city.code,
                    }"
                    @click="
                      emit('select', {
                        city,
                        source: 'TREE',
                      })
                    "
                  >
                    {{ city.name }}
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
        </view>
      </scroll-view>

      <text class="city-sheet__hint">
        选择后自动筛选该城市服务
      </text>
    </view>
  </view>
</template>

<style scoped>
.city-sheet__mask {
  position: fixed;
  z-index: 80;
  inset: 0;
  background: rgba(0, 0, 0, 0.56);
}

.city-sheet {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  width: 100%;
  max-width: 600px;
  height: min(72vh, 760px);
  max-height: min(82vh, 760px);
  margin: 0 auto;
  padding-bottom: env(safe-area-inset-bottom);
  overflow: hidden;
  flex-direction: column;
  border-radius: 24px 24px 0 0;
  background: #fff;
  box-sizing: border-box;
}

.city-sheet__handle {
  width: 36px;
  height: 4px;
  margin: 10px auto 2px;
  flex: 0 0 auto;
  border-radius: 999px;
  background: #c9cdd2;
}

.city-sheet__header {
  display: grid;
  min-height: 54px;
  padding: 0 14px;
  flex: 0 0 auto;
  grid-template-columns: 44px minmax(0, 1fr) 44px;
  align-items: center;
}

.city-sheet__title {
  grid-column: 2;
  color: #1e2126;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.35;
  text-align: center;
}

.city-sheet__close {
  grid-column: 3;
  display: flex;
  width: 44px;
  height: 44px;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
}

.city-sheet__search {
  display: flex;
  min-height: 44px;
  margin: 4px 18px 8px;
  padding: 0 14px;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
  border: 1px solid #d3d6da;
  border-radius: 24px;
  box-sizing: border-box;
}

.city-sheet__search-input {
  min-width: 0;
  height: 42px;
  flex: 1;
  color: #1e2126;
  font-size: 15px;
  line-height: 42px;
}

.city-sheet__scroll {
  min-height: 0;
  flex: 1;
}

.city-sheet__content {
  padding: 2px 18px 18px;
}

.city-sheet__section {
  padding: 12px 0 4px;
}

.city-sheet__section + .city-sheet__section {
  margin-top: 6px;
}

.city-sheet__section-title {
  display: block;
  margin-bottom: 12px;
  color: #1e2126;
  font-size: 16px;
  font-weight: 650;
  line-height: 22px;
}

.city-sheet__current-row {
  display: flex;
  min-height: 52px;
  padding-bottom: 8px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border-bottom: 1px solid #eceef0;
}

.city-sheet__current-city,
.city-sheet__current-empty {
  display: flex;
  min-width: 0;
  min-height: 44px;
  padding: 0;
  flex: 1;
  align-items: center;
  gap: 8px;
  border: 0;
  color: #1e2126;
  background: transparent;
  font-size: 15px;
  text-align: left;
}

.city-sheet__current-city[disabled] {
  opacity: 1;
}

.city-sheet__current-name {
  overflow: hidden;
  font-size: 17px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.city-sheet__current-label {
  flex: 0 0 auto;
  color: #8a9097;
  font-size: 13px;
  white-space: nowrap;
}

.city-sheet__relocate {
  display: flex;
  min-width: 94px;
  min-height: 44px;
  padding: 0 2px 0 8px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  border: 0;
  color: #ff5a00;
  background: transparent;
  font-size: 14px;
  white-space: nowrap;
}

.city-sheet__relocate[disabled] {
  color: #8a9097;
}

.city-sheet__selected-mark,
.city-sheet__city-mark {
  display: flex;
  width: 24px;
  height: 24px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #ff5a00;
}

.city-sheet__recent {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.city-sheet__hot,
.city-sheet__province-cities {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.city-sheet__hot .city-sheet__city,
.city-sheet__province-cities .city-sheet__city {
  width: 100%;
}

.city-sheet__city {
  position: relative;
  min-width: 0;
  min-height: 44px;
  margin: 0;
  padding: 0 10px;
  overflow: hidden;
  border: 1px solid #d8dadd;
  border-radius: 9px;
  color: #1e2126;
  background: #fff;
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.city-sheet__recent-city {
  min-width: 92px;
  padding: 0 18px;
}

.city-sheet__city--selected {
  border-color: #ff5a00;
  color: #ff5a00;
  background: #fff7f2;
  font-weight: 650;
}

.city-sheet__city-mark {
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: 22px;
  height: 22px;
}

.city-sheet__section--more {
  padding-bottom: 0;
}

.city-sheet__province-group {
  border-bottom: 1px solid #eceef0;
}

.city-sheet__province {
  display: flex;
  width: 100%;
  min-height: 52px;
  padding: 0 4px 0 0;
  align-items: center;
  justify-content: space-between;
  border: 0;
  color: #1e2126;
  background: transparent;
  font-size: 15px;
  font-weight: 550;
  text-align: left;
}

.city-sheet__province-cities {
  padding: 0 0 12px;
}

.city-sheet__notice {
  display: flex;
  min-height: 44px;
  margin-top: 8px;
  padding: 8px 10px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-radius: 8px;
  color: #7d3c19;
  background: #fff5ed;
  font-size: 13px;
  box-sizing: border-box;
}

.city-sheet__notice button {
  min-width: 56px;
  min-height: 44px;
  padding: 0 8px;
  border: 0;
  color: #ff5a00;
  background: transparent;
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
  color: #8a9097;
  font-size: 14px;
  text-align: center;
  box-sizing: border-box;
}

.city-sheet__state button {
  min-width: 96px;
  min-height: 44px;
  padding: 0 18px;
  border: 1px solid #ff5a00;
  border-radius: 22px;
  color: #ff5a00;
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
  color: #8a9097;
  font-size: 14px;
}

.city-sheet__hint {
  display: flex;
  min-height: 44px;
  padding: 0 18px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  color: #8a9097;
  font-size: 13px;
  line-height: 20px;
  text-align: center;
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

@media (max-width: 374px) {
  .city-sheet__search {
    margin-right: 12px;
    margin-left: 12px;
  }

  .city-sheet__content {
    padding-right: 12px;
    padding-left: 12px;
  }

  .city-sheet__hot,
  .city-sheet__province-cities {
    gap: 8px;
  }

  .city-sheet__city {
    padding-right: 6px;
    padding-left: 6px;
    font-size: 14px;
  }
}
</style>
