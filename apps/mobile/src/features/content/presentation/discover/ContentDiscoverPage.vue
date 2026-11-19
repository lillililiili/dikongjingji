<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref, watch } from 'vue'

import { activityApi } from '@/features/activity/data/activity-api-client'
import type { ActivityCard } from '@/features/activity/domain/activity'
import {
  ACTIVITY_TYPE_LABELS,
  formatActivityCardSchedule,
  formatActivityCardStatus,
  formatActivityPrice,
  isPerPersonPrice,
} from '@/features/activity/presentation/shared/activity-format'
import { platformCityApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import CitySelectionSheet from '@/shared/city/presentation/CitySelectionSheet.vue'
import { useCitySheet } from '@/shared/city/presentation/use-city-sheet'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

import {
  ACTIVITY_TAG_TONES,
  AUTHOR_AVATAR_COLORS,
  CONTENT_KIND_LABELS,
  DISCOVER_CHANNEL_LABELS,
  DISCOVER_CHANNEL_NOTES,
  DISCOVER_EMPTY_TEXT,
  articleCoverTone,
  articleEntries,
  authorInitial,
  formatContentDay,
  shortCityName,
  splitColumns,
  topicDateRange,
} from '../../application/content-view'
import {
  createDiscoverFeedLoader,
  createDiscoverFeedSource,
  emptyDiscoverFeed,
  type DiscoverFeedState,
} from '../../application/discover-feed'
import { contentApi } from '../../data/content-api-client'
import { DISCOVER_CHANNELS, type ContentArticleCard, type DiscoverChannel } from '../../domain/content'
import ContentThumb from '../shared/ContentThumb.vue'

// 发现 (board Discover): 推荐 / 赛事 / 研学 / 案例 / 知识. 推荐 is a two-column waterfall of articles
// under the city's current topics; 案例 and 知识 are rows of articles; 赛事 and 研学 are rows of real
// activities. 视频 and 直播 are not in this step.
const emit = defineEmits<{
  openArticle: [articleId: string, cityCode: string]
  openTopic: [topicId: string, cityCode: string]
  openActivity: [activityId: string, cityCode: string]
}>()

const currentCityStore = useCurrentCityStore()
const channel = ref<DiscoverChannel>('RECOMMEND')
const feed = ref<DiscoverFeedState>(emptyDiscoverFeed('RECOMMEND', ''))

const loader = createDiscoverFeedLoader({
  source: createDiscoverFeedSource({ content: contentApi, activity: activityApi }),
  getState: () => feed.value,
  setState: (state) => { feed.value = state },
})

const cityCode = computed(() => currentCityStore.currentCity?.code ?? '')
const cityLabel = computed(() => shortCityName(currentCityStore.currentCity?.name) || '选择城市')

const citySheet = useCitySheet(platformCityApi, () => refresh())
const {
  isOpen: citySheetOpen,
  directory: cityDirectory,
  isLoading: cityDirectoryLoading,
  error: cityDirectoryError,
  recentCities,
  isRelocating,
} = citySheet

const view = computed<'loading' | 'error' | 'empty' | 'list' | 'no-city'>(() => {
  if (!cityCode.value) return 'no-city'
  const status = feed.value.status
  if (status === 'idle' || status === 'loading') return 'loading'
  if (status === 'error') return 'error'
  return feed.value.entries.length || feed.value.topics.length ? 'list' : 'empty'
})
const isGrid = computed(() => feed.value.channel === 'RECOMMEND')
const articles = computed(() => articleEntries(feed.value.entries))
const columns = computed(() => splitColumns(articles.value))
const activities = computed(() => feed.value.entries.flatMap((entry) => (entry.kind === 'ACTIVITY' ? [entry.activity] : [])))
const sectionTitle = computed(() => {
  if (feed.value.channel === 'EVENTS') return '近期赛事与活动'
  if (feed.value.channel === 'STUDY') return '近期研学'
  return ''
})

function reload(): void {
  if (!cityCode.value) return
  void loader.reload(channel.value, cityCode.value)
}

/** On show: reads again when the App's city changed elsewhere, or the first time. */
function refresh(): void {
  if (!cityCode.value) return
  if (feed.value.status === 'idle' || feed.value.cityCode !== cityCode.value || feed.value.channel !== channel.value) {
    reload()
  }
}

function loadMore(): void {
  void loader.loadMore()
}

function selectChannel(next: DiscoverChannel): void {
  if (next === channel.value) return
  channel.value = next
  uni.pageScrollTo({ scrollTop: 0, duration: 0 })
  reload()
}

function activityPrice(card: ActivityCard): string {
  return formatActivityPrice(card.price)
}

function articleSub(article: ContentArticleCard): string {
  return article.author.name
}

function openArticle(article: ContentArticleCard): void {
  emit('openArticle', article.articleId, feed.value.cityCode)
}

// The App's city can change on another tab (首页, a catalog); the list follows it.
watch(cityCode, () => refresh(), { immediate: true })

defineExpose({ loadMore, refresh })
</script>

<template>
  <view class="discover" data-testid="content-discover" :data-channel="feed.channel" :data-state="feed.status">
    <view class="discover__top">
      <view class="discover__head">
        <text class="discover__title" role="heading" aria-level="1">发现</text>
        <button
          class="discover__city"
          data-testid="discover-city"
          aria-haspopup="dialog"
          :aria-label="`切换城市，当前${cityLabel}`"
          @click="citySheet.open"
        >
          <text>{{ cityLabel }}</text>
          <UniIcons type="bottom" size="12" color="#202c42" />
        </button>
      </view>
      <view class="discover__tabs" role="tablist" aria-label="内容频道">
        <button
          v-for="code in DISCOVER_CHANNELS"
          :key="code"
          :class="['discover__tab', { 'discover__tab--on': code === channel }]"
          role="tab"
          :aria-selected="code === channel"
          :data-testid="`discover-channel-${code.toLowerCase()}`"
          @click="selectChannel(code)"
        >
          <text>{{ DISCOVER_CHANNEL_LABELS[code] }}</text>
          <view v-if="code === channel" class="discover__tab-bar" />
        </button>
      </view>
    </view>

    <view class="discover__body">
      <view class="discover__note" data-testid="discover-note">
        <UniIcons type="info" size="14" color="#738096" />
        <text>{{ DISCOVER_CHANNEL_NOTES[channel] }}</text>
      </view>

      <view v-if="view === 'no-city'" class="discover__state" data-testid="discover-no-city">
        <UniIcons type="location" size="36" color="#b5bac1" />
        <text class="discover__state-title">先选择城市</text>
        <button class="discover__state-action" @click="citySheet.open">选择城市</button>
      </view>

      <template v-else-if="view === 'loading'">
        <view v-for="index in 3" :key="index" class="discover__skeleton" data-testid="discover-loading" />
      </template>

      <view v-else-if="view === 'error'" class="discover__state" data-testid="discover-error">
        <UniIcons type="info" size="36" color="#b5bac1" />
        <text class="discover__state-title">内容加载失败</text>
        <text class="discover__state-copy">请检查网络后重试</text>
        <button class="discover__state-action" data-testid="discover-retry" @click="reload">重新加载</button>
      </view>

      <view v-else-if="view === 'empty'" class="discover__state" data-testid="discover-empty">
        <UniIcons type="paperplane" size="36" color="#b5bac1" />
        <text class="discover__state-title">{{ DISCOVER_EMPTY_TEXT[feed.channel] }}</text>
        <text class="discover__state-copy">换个频道或城市看看</text>
      </view>

      <template v-else>
        <button
          v-for="topic in feed.topics"
          :key="topic.topicId"
          class="discover__topic"
          :data-testid="`discover-topic-${topic.topicId}`"
          @click="emit('openTopic', topic.topicId, feed.cityCode)"
        >
          <view class="discover__topic-copy">
            <view class="discover__topic-tags">
              <text class="discover__topic-tag">专题</text>
              <text v-if="topic.sample" class="discover__sample-tag">示例</text>
            </view>
            <text class="discover__topic-title">{{ topic.title }}</text>
            <text v-if="topic.subtitle" class="discover__topic-sub">{{ topic.subtitle }}</text>
            <text class="discover__topic-meta">{{ topic.publisher.name }} · {{ topicDateRange(topic) }}</text>
          </view>
          <image v-if="topic.coverImage" class="discover__topic-image" :src="topic.coverImage.url" mode="aspectFill" :aria-label="topic.coverImage.alt" />
        </button>

        <view v-if="isGrid" class="discover__grid" data-testid="discover-grid">
          <view v-for="(column, columnIndex) in columns" :key="columnIndex" class="discover__column">
            <button
              v-for="article in column"
              :key="article.articleId"
              class="discover__card"
              :data-testid="`discover-article-${article.articleId}`"
              @click="openArticle(article)"
            >
              <view :class="['discover__card-cover', { 'discover__card-cover--image': article.coverImage }]">
                <ContentThumb
                  :image="article.coverImage"
                  :tone="articleCoverTone(article)"
                  :tag="CONTENT_KIND_LABELS[article.kind]"
                  :title="article.title"
                  :sample="article.sample"
                />
              </view>
              <text v-if="article.coverImage" class="discover__card-title">{{ article.title }}</text>
              <view class="discover__card-meta">
                <text class="discover__avatar" :style="{ background: AUTHOR_AVATAR_COLORS[article.author.kind] }" aria-hidden="true">
                  {{ authorInitial(article.author.name) }}
                </text>
                <text class="discover__card-author">{{ article.author.name }}</text>
                <text v-if="article.sponsored" class="discover__ad" data-testid="content-sponsored">广告</text>
                <text class="discover__card-date">{{ formatContentDay(article.publishedAt) }}</text>
              </view>
            </button>
          </view>
        </view>

        <template v-else-if="feed.channel === 'CASE' || feed.channel === 'KNOWLEDGE'">
          <button
            v-for="article in articles"
            :key="article.articleId"
            class="discover__row"
            :data-testid="`discover-article-${article.articleId}`"
            @click="openArticle(article)"
          >
            <view class="discover__row-thumb">
              <ContentThumb
                :image="article.coverImage"
                :tone="articleCoverTone(article)"
                :tag="CONTENT_KIND_LABELS[article.kind]"
              />
            </view>
            <view class="discover__row-copy">
              <text class="discover__row-title">{{ article.title }}</text>
              <view class="discover__row-sub">
                <text class="discover__row-sub-text">{{ articleSub(article) }}</text>
                <text v-if="article.sponsored" class="discover__ad" data-testid="content-sponsored">广告</text>
              </view>
              <view class="discover__row-meta">
                <text class="discover__row-date">{{ formatContentDay(article.publishedAt) }}</text>
                <text v-if="article.sample" class="discover__sample-tag" data-testid="content-sample">示例</text>
              </view>
            </view>
          </button>
        </template>

        <template v-else>
          <view v-if="sectionTitle" class="discover__section">
            <view class="discover__section-bar" />
            <text class="discover__section-title">{{ sectionTitle }}</text>
          </view>
          <button
            v-for="activity in activities"
            :key="activity.activityId"
            class="discover__row"
            :data-testid="`discover-activity-${activity.activityId}`"
            @click="emit('openActivity', activity.activityId, feed.cityCode)"
          >
            <view class="discover__row-thumb">
              <ContentThumb
                :image="activity.coverImage"
                :tone="ACTIVITY_TAG_TONES[activity.type]"
                :tag="ACTIVITY_TYPE_LABELS[activity.type]"
                :tag-tone="ACTIVITY_TAG_TONES[activity.type]"
              />
            </view>
            <view class="discover__row-copy">
              <text class="discover__row-title">{{ activity.title }}</text>
              <text class="discover__row-sub-text">{{ formatActivityCardSchedule(activity) }}</text>
              <view class="discover__row-meta">
                <text class="discover__row-price">{{ activityPrice(activity) }}<text v-if="isPerPersonPrice(activity.price)" class="discover__row-unit">/人</text></text>
                <text class="discover__row-status">{{ formatActivityCardStatus(activity) }}</text>
              </view>
            </view>
          </button>
        </template>

        <view v-if="feed.loadingMore" class="discover__more" data-testid="discover-loading-more">正在加载…</view>
        <button v-else-if="feed.loadMoreFailed" class="discover__more discover__more--retry" data-testid="discover-more-retry" @click="loadMore">
          加载失败，点此重试
        </button>
        <view v-else-if="!feed.hasMore" class="discover__more" data-testid="discover-no-more">没有更多内容了</view>
      </template>
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
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Discover (390 wide). */
.discover {
  min-height: 100vh;
  padding-bottom: var(--app-root-tabbar-content-offset);
  color: #202c42;
  background: #f5f7fc;
  box-sizing: border-box;
  font-family:
    "PingFang SC", "Noto Sans SC", system-ui, -apple-system, BlinkMacSystemFont,
    "Microsoft YaHei", sans-serif;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: inherit;
  font-family: inherit;
  line-height: normal;
  text-align: left;
}

button::after {
  border: 0;
}

.discover__top {
  position: sticky;
  z-index: 10;
  top: 0;
  display: flex;
  flex-direction: column;
  padding-top: calc(var(--status-bar-height) + 6px);
  background: #ffffff;
}

.discover__head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 6px 0 16px;
}

.discover__title {
  font-size: 22px;
  font-weight: 700;
  line-height: 44px;
}

.discover__city {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 2px;
  color: #202c42;
  font-size: 15px;
  font-weight: 500;
}

.discover__tabs {
  display: flex;
  align-items: center;
  padding: 0 7px 2px;
  overflow-x: auto;
  scrollbar-width: none;
}

.discover__tabs::-webkit-scrollbar {
  display: none;
}

.discover__tab {
  position: relative;
  display: flex;
  flex-shrink: 0;
  min-height: 44px;
  align-items: center;
  padding: 0 9px;
  color: #202c42;
  font-size: 15px;
  white-space: nowrap;
}

.discover__tab--on {
  color: #ff6a00;
  font-size: 18px;
  font-weight: 700;
}

.discover__tab-bar {
  position: absolute;
  bottom: 3px;
  left: 50%;
  width: 16px;
  height: 3px;
  margin-left: -8px;
  border-radius: 3px;
  background: #ff6a00;
}

.discover__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 16px 16px;
}

.discover__note {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.discover__topic {
  display: flex;
  min-height: 132px;
  overflow: hidden;
  border-radius: 14px;
  background: #ffffff;
}

.discover__topic-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  padding: 12px 14px;
  background: linear-gradient(135deg, #fff0e3 0%, #fffaf5 100%);
}

.discover__topic-tags {
  display: flex;
  gap: 6px;
}

.discover__topic-tag {
  padding: 1px 6px;
  border-radius: 6px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 11px;
  line-height: 16px;
}

.discover__topic-title {
  font-size: 19px;
  font-weight: 700;
  line-height: 25px;
}

.discover__topic-sub {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.discover__topic-meta {
  margin-top: auto;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.discover__topic-image {
  flex-shrink: 0;
  width: 132px;
  height: 132px;
}

.discover__sample-tag {
  padding: 0 5px;
  border-radius: 6px;
  color: #536178;
  background: #eef2f8;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.discover__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: 10px;
}

.discover__column {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 10px;
}

.discover__card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 12px;
  background: #ffffff;
}

.discover__card-cover {
  display: flex;
  min-height: 120px;
}

.discover__card-cover--image {
  height: 120px;
}

.discover__card-title {
  padding: 8px 10px 0;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.discover__card-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px 10px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.discover__avatar {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
}

.discover__card-author {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.discover__card-date {
  flex-shrink: 0;
  white-space: nowrap;
}

.discover__ad {
  flex-shrink: 0;
  padding: 0 4px;
  border: 1px solid #c9d3e1;
  border-radius: 4px;
  color: #627187;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

.discover__row {
  display: flex;
  gap: 12px;
  padding: 10px;
  border-radius: 12px;
  background: #ffffff;
}

.discover__row-thumb {
  flex-shrink: 0;
  width: 104px;
  height: 78px;
  overflow: hidden;
  border-radius: 10px;
}

.discover__row-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.discover__row-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.discover__row-sub {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.discover__row-sub-text {
  min-width: 0;
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.discover__row-meta {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: auto;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.discover__row-date {
  color: #536178;
  font-size: 14px;
  font-weight: 700;
}

.discover__row-price {
  color: #e85400;
  font-size: 14px;
  font-weight: 700;
}

.discover__row-unit {
  font-size: 12px;
  font-weight: 400;
}

.discover__row-status {
  color: #738096;
}

.discover__section {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}

.discover__section-bar {
  width: 4px;
  height: 16px;
  border-radius: 2px;
  background: #ff6a00;
}

.discover__section-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.discover__skeleton {
  height: 132px;
  border-radius: 12px;
  background: linear-gradient(90deg, #eef1f6 25%, #f6f8fb 50%, #eef1f6 75%);
}

.discover__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 56px 24px;
  text-align: center;
}

.discover__state-title {
  color: #202c42;
  font-size: 16px;
  font-weight: 600;
}

.discover__state-copy {
  color: #738096;
  font-size: 13px;
}

.discover__state-action {
  min-height: 40px;
  margin-top: 6px;
  padding: 0 20px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  font-size: 14px;
  line-height: 38px;
  text-align: center;
}

.discover__more {
  padding: 6px 0 2px;
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.discover__more--retry {
  min-height: 44px;
  color: #ba470b;
  text-align: center;
}
</style>
