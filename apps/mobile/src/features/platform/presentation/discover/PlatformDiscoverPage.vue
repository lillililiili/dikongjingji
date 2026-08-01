<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import AppHeader from '@/app-shell/components/AppHeader.vue'
import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import PlatformHeroBanner from '@/shared/presentation/PlatformHeroBanner.vue'
import type { PlatformWeatherPresentation } from '@/shared/weather/platform-weather'
import type { PlatformDiscoverContent } from '../../domain/platform-discover-content'
import type { PlatformFeatureIntent } from '../../domain/platform-feature-intent'

defineProps<{
  cityName: string
  weather: PlatformWeatherPresentation
  content: PlatformDiscoverContent
}>()

const emit = defineEmits<{
  intent: [intent: PlatformFeatureIntent]
}>()

const quickActions = [
  { label: '热门活动', icon: 'fire-filled' },
  { label: '附近体验', icon: 'location-filled' },
  { label: '精彩航拍', icon: 'camera-filled' },
  { label: '低空旅游', icon: 'paperplane-filled' },
  { label: '赛事活动', icon: 'medal-filled' },
  { label: '行业案例', icon: 'folder-add-filled' },
] as const
</script>

<template>
  <view class="discover-page">
    <view class="discover-page__surface">
      <AppHeader
        :city-name="cityName"
        :weather="weather"
        @location="emit('intent', { type: 'OPEN_LOCATION' })"
        search-placeholder="搜索活动、体验或内容"
        @search="emit('intent', { type: 'SEARCH', channel: 'AERIAL' })"
        @message="emit('intent', { type: 'OPEN_MESSAGE_CENTER' })"
      />

      <PlatformHeroBanner
        :image-url="content.hero.imageUrl"
        :image-alt="`${content.hero.title}${content.hero.highlight}`"
        :title="content.hero.title"
        :highlight="content.hero.highlight"
        :description="content.hero.description"
        :highlights="content.hero.highlights"
        :action-label="content.hero.actionLabel"
        @action="emit('intent', { type: 'OPEN_LOCATION' })"
      />

      <view class="discover-page__quick">
        <button
          v-for="action in quickActions"
          :key="action.label"
          @click="emit('intent', { type: 'OPEN_ITEM', itemId: action.label, itemKind: 'CONTENT' })"
        >
          <view class="discover-page__quick-icon">
            <UniIcons :type="action.icon" size="28" color="#ff6508" />
          </view>
          <text>{{ action.label }}</text>
        </button>
      </view>

      <section class="discover-section">
        <view class="discover-section__heading">
          <text class="discover-section__title">附近热门活动</text>
          <text class="discover-section__copy">身边精彩 · 不容错过</text>
        </view>
        <scroll-view scroll-x class="discover-section__scroll">
          <view class="discover-section__activity-row">
            <button
              v-for="activity in content.activities"
              :key="activity.id"
              class="discover-activity"
              @click="emit('intent', { type: 'OPEN_ITEM', itemId: activity.id, itemKind: 'ACTIVITY' })"
            >
              <view class="discover-activity__image-wrap">
                <image :src="activity.imageUrl" mode="aspectFill" />
                <text v-if="activity.tag">{{ activity.tag }}</text>
              </view>
              <text class="discover-activity__title">{{ activity.title }}</text>
              <text class="discover-activity__subtitle">{{ activity.subtitle }}</text>
              <text class="discover-activity__meta">{{ activity.meta }}</text>
              <view class="discover-activity__footer">
                <text>{{ activity.priceLabel }}</text>
                <view class="discover-metric">
                  <UniIcons type="star-filled" size="12" color="#e18400" />
                  <text>{{ activity.metric }}</text>
                </view>
              </view>
            </button>
          </view>
        </scroll-view>
      </section>

      <section class="discover-section">
        <view class="discover-section__heading">
          <text class="discover-section__title">精彩内容 · 灵感推荐</text>
          <text class="discover-section__copy">航拍作品 · 攻略指南</text>
        </view>
        <scroll-view scroll-x class="discover-section__scroll">
          <view class="discover-section__story-row">
            <button
              v-for="story in content.stories"
              :key="story.id"
              class="discover-story"
              @click="emit('intent', { type: 'OPEN_ITEM', itemId: story.id, itemKind: 'CONTENT' })"
            >
              <view class="discover-story__image-wrap">
                <image :src="story.imageUrl" mode="aspectFill" />
                <text v-if="story.tag">{{ story.tag }}</text>
              </view>
              <text class="discover-story__title">{{ story.title }}</text>
              <view class="discover-story__footer">
                <text>{{ story.subtitle }}</text>
                <view class="discover-metric">
                  <UniIcons type="heart" size="12" color="#66707c" />
                  <text>{{ story.metric }}</text>
                </view>
              </view>
            </button>
          </view>
        </scroll-view>
      </section>

      <section class="discover-section">
        <view class="discover-section__heading">
          <text class="discover-section__title">同城圈子 · 社区动态</text>
          <text class="discover-section__copy">发现同好</text>
        </view>
        <view class="discover-community-grid">
          <button
            v-for="community in content.communities"
            :key="community.id"
            class="discover-community"
            @click="emit('intent', { type: 'OPEN_ITEM', itemId: community.id, itemKind: 'CONTENT' })"
          >
            <image :src="community.imageUrl" mode="aspectFill" />
            <view>
              <text class="discover-community__name">{{ community.name }}</text>
              <text class="discover-community__members">{{ community.memberLabel }}</text>
            </view>
            <text class="discover-community__join">加入</text>
          </button>
        </view>
      </section>
    </view>
    <RootAppTabBar active="DISCOVER" />
  </view>
</template>

<style scoped>
.discover-page {
  min-height: 100vh;
  padding-bottom: var(--app-root-tabbar-content-offset);
  color: #17191d;
  background: #f7f8fa;
  box-sizing: border-box;
  font-family:
    system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC",
    "Microsoft YaHei", sans-serif;
}

.discover-page__surface {
  width: 100%;
  max-width: 600px;
  margin: 0 auto;
  padding:
    calc(var(--status-bar-height) + 10px)
    var(--app-page-horizontal-gutter)
    14px;
  box-sizing: border-box;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
}

button::after {
  border: 0;
}

.discover-page__quick {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 5px;
  margin-top: 10px;
  padding: 12px 8px;
  border: 1px solid #edf0f3;
  border-radius: 16px;
  background: #ffffff;
}

.discover-page__quick button {
  color: #292e34;
  font-size: 12px;
  font-weight: 700;
  text-align: center;
}

.discover-page__quick-icon {
  display: flex;
  width: 46px;
  height: 46px;
  margin: 0 auto 6px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff2e8;
}

.discover-section {
  margin-top: 10px;
  padding: 12px 10px;
  border: 1px solid #edf0f3;
  border-radius: 16px;
  background: #ffffff;
}

.discover-section__heading {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 9px;
  margin-bottom: 10px;
}

.discover-section__title {
  font-size: 17px;
  font-weight: 850;
}

.discover-section__copy {
  overflow: hidden;
  color: var(--app-secondary-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.discover-section__scroll {
  width: 100%;
  white-space: nowrap;
}

.discover-section__activity-row,
.discover-section__story-row {
  display: grid;
  width: max-content;
  min-width: 100%;
  grid-auto-columns: calc((100vw - 49px) / 2);
  grid-auto-flow: column;
  gap: 10px;
  padding-right: 2px;
  box-sizing: border-box;
}

.discover-activity,
.discover-story {
  min-width: 0;
  text-align: left;
}

.discover-activity__image-wrap,
.discover-story__image-wrap {
  position: relative;
  height: 108px;
  overflow: hidden;
  border-radius: 11px;
}

.discover-activity__image-wrap image,
.discover-story__image-wrap image {
  width: 100%;
  height: 100%;
}

.discover-activity__image-wrap text,
.discover-story__image-wrap text {
  position: absolute;
  top: 6px;
  left: 6px;
  padding: 3px 6px;
  border-radius: 8px;
  color: #ffffff;
  background: #22b563;
  font-size: 10px;
  font-weight: 800;
}

.discover-activity__title,
.discover-story__title {
  display: block;
  margin-top: 6px;
  overflow: hidden;
  color: #24292f;
  font-size: 14px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.discover-activity__subtitle,
.discover-activity__meta {
  display: block;
  margin-top: 3px;
  overflow: hidden;
  color: var(--app-secondary-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.discover-activity__footer,
.discover-story__footer {
  display: flex;
  margin-top: 5px;
  align-items: center;
  justify-content: space-between;
  color: var(--app-secondary-text);
  font-size: 11px;
}

.discover-activity__footer text:first-child {
  color: #d84b00;
  font-size: 14px;
  font-weight: 850;
}

.discover-story__image-wrap {
  height: 118px;
}

.discover-story__title {
  height: 32px;
  white-space: normal;
}

.discover-community-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.discover-community {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 10px;
  border: 1px solid #edf0f3;
  border-radius: 12px;
  text-align: left;
}

.discover-community image {
  width: 44px;
  height: 44px;
  border-radius: 50%;
}

.discover-community__name,
.discover-community__members {
  display: block;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.discover-community__name {
  font-size: 13px;
  font-weight: 800;
}

.discover-community__members {
  color: var(--app-secondary-text);
  font-size: 11px;
}

.discover-community__join {
  display: block;
  margin-top: 0;
  padding: 0 10px;
  border-radius: 11px;
  color: #ffffff;
  background: var(--app-action-orange-end);
  font-size: 12px;
  font-weight: 800;
  line-height: 28px;
  text-align: center;
}

.discover-metric {
  display: flex;
  align-items: center;
  gap: 2px;
}
</style>
