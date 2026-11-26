<script setup lang="ts">
import { computed, ref } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  loadTopicActivities,
  loadTopicMerchantItems,
  loadTopicVenues,
  type TopicActivityView,
  type TopicMerchantView,
  type TopicVenueView,
} from '../../application/content-refs'
import {
  CONTENT_KIND_LABELS,
  articleCoverTone,
  topicDateRange,
} from '../../application/content-view'
import { contentApi } from '../../data/content-api-client'
import { contentLinkedApis } from '../../data/content-linked-apis'
import type { ContentArticleCard, ContentTopic } from '../../domain/content'
import { buildContentTopicUrl } from '../../navigation/content-routes'
import ContentThumb from '../shared/ContentThumb.vue'

// A topic (board Topic): its title, publisher and days, then 本季活动, 到馆体验, 相关内容 and 商家内容
// (1.3.0: the merchants' products, marked 广告), each read from its own module.
const props = defineProps<{
  topicId: string
  cityCode: string
}>()

const emit = defineEmits<{
  back: []
  browse: []
  open: [url: string]
  openArticle: [articleId: string, cityCode: string]
  allActivities: []
}>()

type TopicState =
  | { kind: 'loading' }
  | { kind: 'ready'; topic: ContentTopic }
  | { kind: 'missing' }
  | { kind: 'error' }

const state = ref<TopicState>({ kind: 'loading' })
// null while being read.
const activities = ref<TopicActivityView[] | null>(null)
const venues = ref<TopicVenueView[] | null>(null)
const merchantItems = ref<TopicMerchantView[] | null>(null)
let requestVersion = 0

const topic = computed(() => (state.value.kind === 'ready' ? state.value.topic : null))
const heroImage = computed(() => (topic.value ? topic.value.coverImage ?? null : null))
const articleImage = (article: ContentArticleCard) => article.coverImage ?? null
const CHEVRON = 'M9 6l6 6-6 6'
const INFO = 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01'

async function load(): Promise<void> {
  if (!props.topicId || !props.cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  activities.value = null
  venues.value = null
  merchantItems.value = null
  try {
    const loaded = await contentApi.loadTopic(props.topicId, props.cityCode)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', topic: loaded }
    void loadTopicActivities(loaded.activityIds, props.cityCode, contentLinkedApis).then((views) => {
      if (requested === requestVersion) activities.value = views
    })
    void loadTopicVenues(loaded.venueIds, props.cityCode, contentLinkedApis).then((views) => {
      if (requested === requestVersion) venues.value = views
    })
    void loadTopicMerchantItems(loaded.merchantItems, props.cityCode, contentLinkedApis).then((views) => {
      if (requested === requestVersion) merchantItems.value = views
    })
  } catch (error) {
    if (requested !== requestVersion) return
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

const shareTarget = ref<ShareTarget | null>(null)
function share(): void {
  const current = topic.value
  if (!current) return
  shareTarget.value = {
    subject: '专题',
    title: current.title,
    subtitle: current.subtitle,
    owner: current.publisher.name,
    imageUrl: current.coverImage?.url ?? null,
    path: buildContentTopicUrl(current.topicId, props.cityCode),
  }
}

void load()
</script>

<template>
  <view class="topic" data-testid="content-topic" :data-state="state.kind">
    <AppPageHeader class="topic__header" title="专题" test-id="content-topic" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="topic__body" aria-label="正在加载">
      <view class="topic__skeleton topic__skeleton--hero" />
      <view class="topic__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing' || state.kind === 'error'" class="topic__state" data-testid="content-topic-unavailable">
      <LineIcon :d="INFO" :size="42" color="#b5bac1" />
      <text class="topic__state-title">专题暂不可用</text>
      <text class="topic__state-copy">
        {{ state.kind === 'missing' ? '专题已下线，或不是当前城市的专题' : '专题加载失败，请检查网络后重试' }}
      </text>
      <button
        class="topic__state-action"
        data-testid="content-topic-recover"
        @click="state.kind === 'missing' ? emit('browse') : load()"
      >
        {{ state.kind === 'missing' ? '看看其他内容' : '重新加载' }}
      </button>
    </view>

    <template v-else-if="topic">
      <view class="topic__hero">
        <view class="topic__hero-copy">
          <view class="topic__hero-tags">
            <text class="topic__tag">专题</text>
          </view>
          <text class="topic__title" role="heading" aria-level="2" data-testid="content-topic-title">{{ topic.title }}</text>
          <text v-if="topic.subtitle" class="topic__subtitle">{{ topic.subtitle }}</text>
          <view class="topic__hero-meta">
            <text data-testid="content-topic-publisher">{{ topic.publisher.name }}</text>
            <text data-testid="content-topic-dates">{{ topicDateRange(topic) }}</text>
          </view>
        </view>
        <image v-if="heroImage" class="topic__hero-image" :src="heroImage.url" mode="aspectFill" :aria-label="heroImage.alt" />
      </view>

      <view class="topic__body">
        <view v-if="activities === null || activities.length" class="topic__card" data-testid="content-topic-activities">
          <text class="topic__card-title" role="heading" aria-level="3">本季活动</text>
          <view v-if="activities === null" class="topic__row-loading" />
          <button
            v-for="activity in activities ?? []"
            :key="activity.activityId"
            class="topic__activity"
            :data-testid="`content-topic-activity-${activity.activityId}`"
            @click="emit('open', activity.url)"
          >
            <view class="topic__activity-thumb">
              <ContentThumb :image="activity.imageUrl ? { url: activity.imageUrl, alt: activity.title } : null" :tone="activity.tagTone" :tag="activity.tag" :tag-tone="activity.tagTone" />
            </view>
            <view class="topic__activity-copy">
              <text class="topic__activity-title">{{ activity.title }}</text>
              <text class="topic__activity-when">{{ activity.when }}</text>
              <view class="topic__activity-meta">
                <text class="topic__activity-price">{{ activity.price.amount }}</text>
                <text v-if="activity.price.unit" class="topic__activity-unit">{{ activity.price.unit }}</text>
                <text :class="['topic__activity-status', `topic__activity-status--${activity.statusTone}`]">{{ activity.status }}</text>
              </view>
            </view>
          </button>
        </view>

        <view v-if="venues === null || venues.length" class="topic__card" data-testid="content-topic-venues">
          <text class="topic__card-title topic__card-title--tight" role="heading" aria-level="3">到馆体验</text>
          <view v-if="venues === null" class="topic__row-loading" />
          <template v-for="venue in venues ?? []" :key="venue.venueId">
            <button class="topic__venue" :data-testid="`content-topic-venue-${venue.venueId}`" @click="emit('open', venue.url)">
              <LineIcon name="pin" :size="16" color="#738096" />
              <text class="topic__venue-name">{{ venue.name }}{{ venue.openingNote ? ` · ${venue.openingNote}` : '' }}</text>
              <LineIcon :d="CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
            </button>
            <button
              v-for="session in venue.sessions"
              :key="session.key"
              class="topic__session"
              :data-testid="`content-topic-session-${session.key}`"
              @click="emit('open', venue.url)"
            >
              <view class="topic__session-copy">
                <text class="topic__session-name">{{ session.projectName }}</text>
                <text class="topic__session-when">{{ session.when }}</text>
              </view>
              <text :class="['topic__session-seats', { 'topic__session-seats--full': session.full }]">{{ session.seats }}</text>
              <LineIcon :d="CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
            </button>
            <text v-if="!venue.sessions.length" class="topic__venue-empty" data-testid="content-topic-no-session">今天没有可约的场次，进场馆看看其他日期</text>
          </template>
        </view>

        <view v-if="topic.articles.length" class="topic__card" data-testid="content-topic-articles">
          <text class="topic__card-title" role="heading" aria-level="3">相关内容</text>
          <button
            v-for="article in topic.articles"
            :key="article.articleId"
            class="topic__article"
            :data-testid="`content-topic-article-${article.articleId}`"
            @click="emit('openArticle', article.articleId, cityCode)"
          >
            <view class="topic__article-thumb">
              <ContentThumb :image="articleImage(article)" :tone="articleCoverTone(article)" :tag="CONTENT_KIND_LABELS[article.kind]" />
            </view>
            <view class="topic__article-copy">
              <text class="topic__article-title">{{ article.title }}</text>
              <view class="topic__article-meta">
                <text>{{ article.author.name }}</text>
                <text v-if="article.sponsored" class="topic__ad" data-testid="content-sponsored">广告</text>
                <text v-if="article.sample" class="topic__sample" data-testid="content-sample">示例</text>
              </view>
            </view>
          </button>
        </view>
        <!-- 1.3.0 商家内容（画板 Topic）：商家的商品，商城里读不到的不显示；整块没有就不显示。 -->
        <view
          v-if="(merchantItems === null && topic.merchantItems.length) || merchantItems?.length"
          class="topic__card"
          data-testid="content-topic-merchants"
        >
          <text class="topic__card-title" role="heading" aria-level="3">商家内容</text>
          <view v-if="merchantItems === null" class="topic__row-loading" />
          <button
            v-for="item in merchantItems ?? []"
            :key="item.productId"
            class="topic__merchant"
            :data-testid="`content-topic-merchant-${item.productId}`"
            @click="emit('open', item.url)"
          >
            <view class="topic__merchant-thumb">
              <image v-if="item.imageUrl" class="topic__merchant-image" :src="item.imageUrl" mode="aspectFill" :aria-label="item.title" />
            </view>
            <view class="topic__merchant-copy">
              <view class="topic__merchant-line">
                <text class="topic__merchant-ad" data-testid="content-sponsored">广告</text>
                <text class="topic__merchant-by">{{ item.merchantName }}发布</text>
              </view>
              <text class="topic__merchant-title">{{ item.title }}</text>
              <view class="topic__merchant-price">
                <text class="topic__merchant-amount">{{ item.amount }}</text>
                <text>{{ item.meta }}</text>
              </view>
            </view>
          </button>
        </view>
      </view>

      <view class="topic__bar">
        <button class="topic__share" data-testid="content-topic-share" aria-haspopup="dialog" @click="share">
          <LineIcon name="share" :size="18" color="#202c42" />
          <text>分享专题</text>
        </button>
        <button class="topic__all" data-testid="content-topic-all-activities" @click="emit('allActivities')">查看全部活动</button>
      </view>
    </template>

    <ShareSheet :target="shareTarget" @close="shareTarget = null" />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Topic (390 wide). */
.topic {
  min-height: 100vh;
  padding-bottom: calc(80px + env(safe-area-inset-bottom));
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

.topic__header {
  position: sticky;
  z-index: 10;
  top: 0;
}

.topic__hero {
  display: flex;
  min-height: 190px;
  background: linear-gradient(135deg, #fff0e3 0%, #fffaf5 100%);
}

.topic__hero-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 14px 6px 14px 16px;
}

.topic__hero-tags {
  display: flex;
  gap: 6px;
}

.topic__tag {
  padding: 1px 6px;
  border-radius: 6px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.topic__sample {
  color: #91a0b4;
  font-size: 11px;
  line-height: 17px;
  white-space: nowrap;
}

.topic__title {
  font-size: 22px;
  font-weight: 700;
  line-height: 30px;
}

.topic__subtitle {
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.topic__hero-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.topic__hero-image {
  flex-shrink: 0;
  width: 184px;
  height: 190px;
}

.topic__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.topic__card {
  display: flex;
  flex-direction: column;
  padding: 14px 14px 2px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.topic__card-title {
  margin-bottom: 10px;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.topic__card-title--tight {
  margin-bottom: 0;
}

.topic__row-loading {
  height: 76px;
  margin-bottom: 12px;
  border-radius: 10px;
  background: linear-gradient(90deg, #eef1f6 25%, #f6f8fb 50%, #eef1f6 75%);
}

.topic__activity {
  display: flex;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid #f0f1f3;
}

.topic__activity-thumb {
  flex-shrink: 0;
  width: 76px;
  height: 76px;
  overflow: hidden;
  border-radius: 10px;
}

.topic__activity-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.topic__activity-title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topic__activity-when {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topic__activity-meta {
  display: flex;
  align-items: baseline;
  gap: 3px;
  margin-top: auto;
  white-space: nowrap;
}

.topic__activity-price {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.topic__activity-unit {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.topic__activity-status {
  margin-left: auto;
  font-size: 12px;
  font-weight: 500;
  line-height: 17px;
}

.topic__activity-status--ok {
  color: #11804a;
}

.topic__activity-status--warn {
  color: #ba470b;
}

.topic__activity-status--off {
  color: #738096;
}

.topic__venue {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 6px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.topic__venue-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topic__venue-empty {
  padding: 12px 0 14px;
  border-top: 1px solid #f0f1f3;
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.topic__session {
  display: flex;
  min-height: 60px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
}

.topic__session-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.topic__session-name {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  white-space: nowrap;
}

.topic__session-when {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.topic__session-seats {
  flex-shrink: 0;
  color: #11804a;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.topic__session-seats--full {
  color: #ba470b;
}

.topic__article {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid #f0f1f3;
}

.topic__article-thumb {
  flex-shrink: 0;
  width: 100px;
  height: 66px;
  overflow: hidden;
  border-radius: 10px;
}

.topic__article-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.topic__article-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.topic__article-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.topic__ad {
  padding: 0 4px;
  border: 1px solid #c9d3e1;
  border-radius: 4px;
  color: #627187;
  font-size: 11px;
  line-height: 15px;
}

.topic__merchant {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid #f0f1f3;
}

.topic__merchant-thumb {
  flex-shrink: 0;
  width: 76px;
  height: 76px;
  overflow: hidden;
  border-radius: 10px;
  background: #eef2f8;
}

.topic__merchant-image {
  display: block;
  width: 100%;
  height: 100%;
}

.topic__merchant-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.topic__merchant-line {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.topic__merchant-ad {
  flex-shrink: 0;
  padding: 0 5px;
  border-radius: 6px;
  color: #536178;
  background: #eef2f8;
  font-size: 12px;
  font-weight: 500;
  line-height: 17px;
  white-space: nowrap;
}

.topic__merchant-by {
  min-width: 0;
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.topic__merchant-title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.topic__merchant-price {
  display: flex;
  align-items: baseline;
  gap: 3px;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.topic__merchant-amount {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.topic__bar {
  position: fixed;
  z-index: 15;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  gap: 10px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

.topic__share,
.topic__all {
  display: flex;
  flex: 1 1 0;
  height: 46px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 999px;
  box-sizing: border-box;
  font-size: 15px;
  white-space: nowrap;
}

.topic__share {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
}

.topic__all {
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-weight: 500;
}

.topic__skeleton {
  height: 120px;
  border-radius: 14px;
  background: linear-gradient(90deg, #eef1f6 25%, #f6f8fb 50%, #eef1f6 75%);
}

.topic__skeleton--hero {
  height: 190px;
}

.topic__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 120px 24px 24px;
  text-align: center;
}

.topic__state-title {
  font-size: 16px;
  font-weight: 600;
}

.topic__state-copy {
  color: #738096;
  font-size: 13px;
}

.topic__state-action {
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
</style>
