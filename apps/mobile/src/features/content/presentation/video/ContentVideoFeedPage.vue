<script setup lang="ts">
import { computed, getCurrentInstance, ref, watch } from 'vue'

import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { resolveContentRefs } from '../../application/content-refs'
import {
  createCommentSheet,
  createVideoFeedSession,
  currentVideo,
  emptyCommentSheet,
  emptyVideoFeed,
  type CommentSheetState,
  type VideoFeedState,
} from '../../application/video-feed-session'
import {
  VIDEO_AUTHOR_BADGES,
  VIDEO_AVATAR_COLOR,
  formatVideoCount,
  formatVideoTags,
  nameInitial,
  videoEntry,
  type VideoEntryView,
} from '../../application/video-view'
import { contentLinkedApis } from '../../data/content-linked-apis'
import type { ContentVideoApi } from '../../data/content-video-api'
import { videoPlaySource, videoPoster } from '../../data/preview/content-preview-videos'
import type { ContentVideo, ContentVideoFeed } from '../../domain/content'
import { buildContentVideoUrl, contentAuthorHome } from '../../navigation/content-routes'
import { SEARCH_PRE_PATH } from '../../../search/navigation/search-routes'
import VideoCommentSheet from './VideoCommentSheet.vue'

// The vertical stream (board VideoFeed): one video at a time on black, ↑ ↓ (or a swipe) to the
// previous and next, tap to pause, 静音; on the side the author with 关注, 点赞, 评论 and 分享;
// at the bottom the author, the caption and the video's entry (its first mention, read from its own
// module). 关注 / 推荐 switch the stream; the search icon opens the search page. The board's 收藏 is not
// in this step.
const props = defineProps<{
  cityCode: string
  startVideoId: string | null
  feed: ContentVideoFeed
  api: ContentVideoApi
  getAccessToken: () => string | null
  createKey: () => string
}>()

const emit = defineEmits<{
  back: []
  open: [url: string]
  // A write needs a login; the page comes back to this video and stream.
  login: [videoId: string | null, feed: ContentVideoFeed]
}>()

const PLAYER_ID = 'content-video-feed-player'
const instance = getCurrentInstance()
const state = ref<VideoFeedState>(emptyVideoFeed(props.feed, props.cityCode))
const comments = ref<CommentSheetState | null>(null)
const playing = ref(true)
const muted = ref(true)
const progress = ref(0)
const shareTarget = ref<ShareTarget | null>(null)
// The entry card of each video, read once from its module (undefined while reading, null: none).
const entries = ref<Record<string, VideoEntryView | null>>({})

function notify(message: string): void {
  uni.showToast({ title: message, icon: 'none' })
}

function askLogin(): void {
  emit('login', current.value?.videoId ?? props.startVideoId, state.value.feed)
}

const session = createVideoFeedSession({
  api: props.api,
  getAccessToken: props.getAccessToken,
  getState: () => state.value,
  setState: (next) => { state.value = next },
  onLoginRequired: askLogin,
  notify,
})

const sheet = createCommentSheet({
  api: props.api,
  getAccessToken: props.getAccessToken,
  getState: () => comments.value ?? emptyCommentSheet(''),
  setState: (next) => { if (comments.value) comments.value = next },
  createKey: props.createKey,
  onLoginRequired: askLogin,
  notify,
  onPosted: (videoId) => session.commentAdded(videoId),
})

const current = computed<ContentVideo | null>(() => currentVideo(state.value))
const source = computed(() => (current.value ? videoPlaySource(current.value) : null))
const poster = computed(() => (current.value ? videoPoster(current.value) : null))
const entry = computed(() => (current.value ? entries.value[current.value.videoId] : undefined))
const canFollow = computed(() => Boolean(current.value?.author.organizationId))
const atStart = computed(() => state.value.index === 0)
const atEnd = computed(() => state.value.index >= state.value.items.length - 1 && !state.value.nextCursor)
// "3 / 6" once the whole stream is on hand; "第 3 条" while more can come.
const position = computed(() => {
  const at = state.value.index + 1
  return state.value.nextCursor ? `第 ${at} 条` : `${at} / ${state.value.items.length}`
})

const FEEDS: Array<{ code: ContentVideoFeed; label: string }> = [
  { code: 'FOLLOWING', label: '关注' },
  { code: 'RECOMMEND', label: '推荐' },
]

// Icons copied from the board VideoFeed.
const ICONS = {
  back: 'M15 5l-7 7 7 7',
  up: 'M6 15l6-6 6 6',
  down: 'M6 9l6 6 6-6',
  play: 'M8 5l12 7-12 7z',
  heart: 'M12 20s-7-4.6-9-9.1C1.7 7.7 3.8 5 6.7 5c2 0 3.6 1 5.3 3 1.7-2 3.3-3 5.3-3 2.9 0 5 2.7 3.7 5.9-2 4.5-9 9.1-9 9.1z',
  comment: 'M12 3C6.5 3 2 6.6 2 11c0 2.2 1.1 4.2 3 5.7L4 21l4.6-2.2c1.1.3 2.2.4 3.4.4 5.5 0 10-3.6 10-8.2S17.5 3 12 3z',
  share: 'M14 4l8 7-8 7v-4.5C8 13.5 4.5 15.5 2 19c1-5 4-9.5 12-10.5z',
  sound: 'M4 9h4l5-4v14l-5-4H4zM16.5 8.5a5 5 0 0 1 0 7',
  mute: 'M4 9h4l5-4v14l-5-4H4zM16 9.5l5 5M21 9.5l-5 5',
}

function load(startVideoId: string | null, feed: ContentVideoFeed): void {
  comments.value = null
  void session.load(feed, props.cityCode, startVideoId)
}

function switchFeed(feed: ContentVideoFeed): void {
  if (feed === state.value.feed && state.value.status !== 'error') return
  load(null, feed)
}

function next(): void {
  comments.value = null
  void session.next()
}

function prev(): void {
  comments.value = null
  session.prev()
}

// A vertical swipe of more than 60 px steps through the stream (on top of the ↑ ↓ buttons).
let touchStartY: number | null = null
function onTouchStart(event: TouchEvent): void {
  touchStartY = event.touches?.[0]?.clientY ?? null
}
function onTouchEnd(event: TouchEvent): void {
  const endY = event.changedTouches?.[0]?.clientY
  if (touchStartY === null || endY === undefined) return
  const distance = endY - touchStartY
  touchStartY = null
  if (distance < -60) next()
  else if (distance > 60) prev()
}

function player() {
  return uni.createVideoContext(PLAYER_ID, instance?.proxy)
}

function togglePlay(): void {
  if (!source.value) return
  if (playing.value) player().pause()
  else player().play()
  playing.value = !playing.value
}

function onTime(event: Event): void {
  const detail = (event as Event & { detail?: { currentTime?: number; duration?: number } }).detail
  progress.value = detail?.duration ? Math.min(100, ((detail.currentTime ?? 0) / detail.duration) * 100) : 0
}

function openAuthor(): void {
  const video = current.value
  if (!video) return
  const home = contentAuthorHome(video.author, props.cityCode)
  if (home.kind === 'PAGE') emit('open', home.url)
  else if (home.kind === 'NOTICE') notify(home.message)
}

function openDetail(): void {
  if (current.value) emit('open', buildContentVideoUrl(current.value.videoId, props.cityCode))
}

function openComments(): void {
  const video = current.value
  if (!video) return
  comments.value = emptyCommentSheet(video.videoId)
  void sheet.open(video.videoId)
}

function sendComment(text: string, done: (sent: boolean) => void): void {
  void sheet.send(text).then(done)
}

function share(): void {
  const video = current.value
  if (!video) return
  shareTarget.value = {
    subject: '内容',
    title: video.title,
    subtitle: '视频',
    owner: video.author.name,
    imageUrl: video.coverImage?.url ?? null,
    path: buildContentVideoUrl(video.videoId, props.cityCode),
  }
}

// Each new video starts playing from the beginning, and its entry card is read once.
watch(() => current.value?.videoId, (videoId) => {
  progress.value = 0
  playing.value = true
  const video = current.value
  if (!videoId || !video || videoId in entries.value) return
  const first = video.refs[0]
  if (!first) {
    entries.value = { ...entries.value, [videoId]: null }
    return
  }
  void resolveContentRefs([first], props.cityCode, contentLinkedApis).then((views) => {
    entries.value = { ...entries.value, [videoId]: videoEntry(views[0]) }
  })
})

load(props.startVideoId, props.feed)
</script>

<template>
  <view
    class="feed"
    data-testid="content-video-feed"
    :data-state="state.status"
    :data-feed="state.feed"
    :data-video="current?.videoId ?? ''"
    @touchstart="onTouchStart"
    @touchend="onTouchEnd"
  >
    <template v-if="current">
      <!-- The board VideoFeed fills the screen; a landscape clip is cropped, not letterboxed. -->
      <video
        :id="PLAYER_ID"
        :key="current.videoId"
        class="feed__media"
        data-testid="video-feed-player"
        :src="source ?? ''"
        :poster="poster?.url ?? ''"
        :autoplay="true"
        :loop="true"
        :muted="muted"
        :controls="false"
        :show-center-play-btn="false"
        :show-fullscreen-btn="false"
        :enable-progress-gesture="false"
        object-fit="cover"
        @play="playing = true"
        @pause="playing = false"
        @timeupdate="onTime"
      />
      <view class="feed__shade" />
    </template>

    <view class="feed__top">
      <button class="feed__icon-button" aria-label="返回" data-testid="video-feed-back" @click="emit('back')">
        <LineIcon :d="ICONS.back" :size="22" color="#ffffff" :stroke-width="2" />
      </button>
      <view class="feed__tabs" role="tablist" aria-label="视频流">
        <button
          v-for="tab in FEEDS"
          :key="tab.code"
          :class="['feed__tab', { 'feed__tab--on': tab.code === state.feed }]"
          role="tab"
          :aria-selected="tab.code === state.feed"
          :data-testid="`video-feed-tab-${tab.code.toLowerCase()}`"
          @click="switchFeed(tab.code)"
        >
          <text>{{ tab.label }}</text>
          <view v-if="tab.code === state.feed" class="feed__tab-bar" />
        </button>
      </view>
      <!-- Board VideoFeed: the search icon opens the search page (plan 08 S5-1). -->
      <button class="feed__icon-button" aria-label="搜索" data-testid="video-feed-search" @click="emit('open', SEARCH_PRE_PATH)">
        <LineIcon name="search" :size="22" color="#ffffff" :stroke-width="2" />
      </button>
    </view>

    <template v-if="current">
      <button class="feed__tap" :aria-label="playing ? '暂停' : '播放'" data-testid="video-feed-toggle" @click="togglePlay">
        <view v-if="!playing" class="feed__paused" data-testid="video-feed-paused">
          <LineIcon :d="ICONS.play" :size="34" fill="rgba(255,255,255,0.85)" color="rgba(255,255,255,0.85)" />
        </view>
      </button>

      <view class="feed__steps">
        <button
          class="feed__step"
          aria-label="上一条"
          data-testid="video-feed-prev"
          :disabled="atStart"
          :style="{ opacity: atStart ? 0.35 : 1 }"
          @click="prev"
        >
          <LineIcon :d="ICONS.up" :size="20" color="#ffffff" :stroke-width="2.2" />
        </button>
        <button
          class="feed__step"
          aria-label="下一条"
          data-testid="video-feed-next"
          :disabled="atEnd"
          :style="{ opacity: atEnd ? 0.35 : 1 }"
          @click="next"
        >
          <LineIcon :d="ICONS.down" :size="20" color="#ffffff" :stroke-width="2.2" />
        </button>
      </view>

      <view class="feed__side">
        <view class="feed__avatar-wrap">
          <button
            class="feed__avatar"
            :style="{ background: VIDEO_AVATAR_COLOR }"
            :aria-label="`${current.author.name}主页`"
            data-testid="video-feed-author"
            @click="openAuthor"
          >
            {{ nameInitial(current.author.name) }}
          </button>
          <button
            v-if="canFollow"
            class="feed__follow"
            :style="{ background: current.followingAuthor ? '#159a70' : '#ff2d55' }"
            :aria-label="`${current.followingAuthor ? '已关注' : '关注'} ${current.author.name}`"
            :aria-pressed="current.followingAuthor"
            data-testid="video-feed-follow"
            @click="session.toggleFollow()"
          >
            {{ current.followingAuthor ? '✓' : '+' }}
          </button>
        </view>
        <button class="feed__action" aria-label="点赞" :aria-pressed="current.liked" data-testid="video-feed-like" @click="session.toggleLike()">
          <LineIcon :d="ICONS.heart" :size="34" :fill="current.liked ? '#ff2d55' : 'rgba(255,255,255,0.95)'" :color="current.liked ? '#ff2d55' : 'rgba(255,255,255,0.95)'" />
          <text data-testid="video-feed-like-count">{{ formatVideoCount(current.likeCount) }}</text>
        </button>
        <button class="feed__action" aria-label="评论" aria-haspopup="dialog" data-testid="video-feed-comments" @click="openComments">
          <LineIcon :d="ICONS.comment" :size="32" fill="#ffffff" color="#ffffff" />
          <text data-testid="video-feed-comment-count">{{ formatVideoCount(current.commentCount) }}</text>
        </button>
        <button class="feed__action" aria-label="分享" aria-haspopup="dialog" data-testid="video-feed-share" @click="share">
          <LineIcon :d="ICONS.share" :size="32" fill="#ffffff" color="#ffffff" />
          <text>分享</text>
        </button>
      </view>

      <view class="feed__info">
        <button class="feed__author" data-testid="video-feed-author-name" @click="openAuthor">
          <text>@{{ current.author.name }}</text>
          <text class="feed__badge">{{ VIDEO_AUTHOR_BADGES[current.author.kind] }}</text>
          <text v-if="current.sponsored" class="feed__badge" data-testid="content-sponsored">广告</text>
          <text v-if="current.sample" class="feed__badge" data-testid="content-sample">示例</text>
        </button>
        <button class="feed__caption" data-testid="video-feed-caption" @click="openDetail">
          <text class="feed__caption-text">{{ current.caption ?? current.title }}</text>
          <text v-if="current.tags.length" class="feed__tags">{{ formatVideoTags(current.tags) }}</text>
        </button>
        <button v-if="entry" class="feed__entry" data-testid="video-feed-entry" @click="emit('open', entry.url)">
          <view class="feed__entry-icon"><LineIcon :d="entry.icon" :size="15" color="#ffffff" :stroke-width="2" /></view>
          <view class="feed__entry-copy">
            <text class="feed__entry-kind">{{ entry.kind }}</text>
            <text class="feed__entry-text">{{ entry.text }}</text>
          </view>
          <text class="feed__entry-cta">{{ entry.cta }} ›</text>
        </button>
        <view class="feed__foot">
          <button
            class="feed__mute"
            :aria-label="muted ? '打开声音' : '静音'"
            :aria-pressed="muted"
            data-testid="video-feed-mute"
            @click="muted = !muted"
          >
            <LineIcon :d="muted ? ICONS.mute : ICONS.sound" :size="14" color="rgba(255,255,255,0.7)" :stroke-width="2" />
            <text>{{ muted ? '静音' : '有声' }}</text>
          </button>
          <text class="feed__position" data-testid="video-feed-position">{{ position }} · 上滑看下一条</text>
        </view>
      </view>

      <view class="feed__progress"><view class="feed__progress-fill" :style="{ width: `${progress}%` }" /></view>
    </template>

    <view v-else class="feed__state" data-testid="video-feed-state">
      <template v-if="state.status === 'loading'">
        <text class="feed__state-copy">正在加载…</text>
      </template>
      <template v-else-if="state.status === 'login'">
        <text class="feed__state-title">登录后看关注的作者</text>
        <text class="feed__state-copy">关注的服务商、商家发了新视频，会出现在这里</text>
        <button class="feed__state-action" data-testid="video-feed-login" @click="askLogin">去登录</button>
      </template>
      <template v-else-if="state.status === 'empty' && state.feed === 'FOLLOWING'">
        <text class="feed__state-title">还没有关注的作者</text>
        <text class="feed__state-copy">在“推荐”里点头像下的加号关注作者，他们的新视频会出现在这里</text>
        <button class="feed__state-action" data-testid="video-feed-to-recommend" @click="switchFeed('RECOMMEND')">去看推荐</button>
      </template>
      <template v-else-if="state.status === 'empty'">
        <text class="feed__state-title">这个城市还没有视频</text>
        <button class="feed__state-action" @click="emit('back')">返回</button>
      </template>
      <template v-else-if="state.status === 'missing'">
        <text class="feed__state-title">这条视频已下线</text>
        <text class="feed__state-copy">或不在当前城市展示</text>
        <button class="feed__state-action" data-testid="video-feed-from-top" @click="load(null, state.feed)">看看其他视频</button>
      </template>
      <template v-else>
        <text class="feed__state-title">视频加载失败</text>
        <text class="feed__state-copy">请检查网络后重试</text>
        <button class="feed__state-action" data-testid="video-feed-retry" @click="load(startVideoId, state.feed)">重新加载</button>
      </template>
    </view>

    <VideoCommentSheet
      v-if="comments && current"
      :state="comments"
      :count="current.commentCount"
      @close="comments = null"
      @send="sendComment"
      @more="sheet.loadMore()"
      @retry="sheet.open(comments.videoId)"
      @report="sheet.report"
    />

    <ShareSheet :target="shareTarget" @close="shareTarget = null" />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board VideoFeed (390 wide). */
.feed {
  position: fixed;
  inset: 0;
  overflow: hidden;
  color: #ffffff;
  background: #000000;
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

.feed__media {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.feed__shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.45) 0%, rgba(0, 0, 0, 0) 22%, rgba(0, 0, 0, 0) 55%, rgba(0, 0, 0, 0.75) 100%);
  pointer-events: none;
}

.feed__top {
  position: absolute;
  z-index: 5;
  top: var(--status-bar-height);
  right: 0;
  left: 0;
  display: flex;
  align-items: center;
  padding: 0 4px;
}

.feed__icon-button {
  display: flex;
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.feed__tabs {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 22px;
}

.feed__tab {
  position: relative;
  height: 44px;
  padding: 0 2px;
  color: rgba(255, 255, 255, 0.7);
  font-size: 16px;
  font-weight: 500;
  line-height: 44px;
  white-space: nowrap;
}

.feed__tab--on {
  color: #ffffff;
  font-size: 18px;
  font-weight: 700;
}

.feed__tab-bar {
  position: absolute;
  bottom: 6px;
  left: 50%;
  width: 18px;
  height: 3px;
  margin-left: -9px;
  border-radius: 3px;
  background: #ffffff;
}

.feed__tap {
  position: absolute;
  z-index: 2;
  top: 100px;
  right: 70px;
  bottom: 260px;
  left: 60px;
}

.feed__paused {
  position: absolute;
  top: 50%;
  left: 50%;
  display: flex;
  width: 72px;
  height: 72px;
  align-items: center;
  justify-content: center;
  margin: -36px 0 0 -36px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
}

.feed__steps {
  position: absolute;
  z-index: 3;
  top: 50%;
  left: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: -48px;
}

.feed__step {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
}

/* uni-app's own uni-button[disabled] grey would win over the class; keep the board's dark circle. */
.feed__step[disabled] {
  color: #ffffff;
  background: rgba(0, 0, 0, 0.35);
}

.feed__side {
  position: absolute;
  z-index: 3;
  right: 8px;
  bottom: calc(150px + env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
}

.feed__avatar-wrap {
  position: relative;
  width: 48px;
  height: 58px;
}

.feed__avatar {
  display: flex;
  width: 48px;
  height: 48px;
  align-items: center;
  justify-content: center;
  border: 2px solid #ffffff;
  border-radius: 50%;
  box-sizing: border-box;
  color: #ffffff;
  font-size: 18px;
  font-weight: 700;
  text-align: center;
}

.feed__follow {
  position: absolute;
  bottom: 0;
  left: 50%;
  width: 22px;
  height: 22px;
  margin-left: -11px;
  border-radius: 50%;
  color: #ffffff;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  text-align: center;
}

.feed__action {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  color: #ffffff;
  font-size: 12px;
  text-align: center;
}

.feed__info {
  position: absolute;
  z-index: 3;
  right: 74px;
  bottom: calc(36px + env(safe-area-inset-bottom));
  left: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.feed__author {
  display: flex;
  min-height: 24px;
  align-items: center;
  gap: 6px;
  color: #ffffff;
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
}

.feed__badge {
  flex-shrink: 0;
  padding: 0 5px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.18);
  font-size: 11px;
  font-weight: 400;
  line-height: 17px;
}

.feed__caption {
  display: flex;
  flex-direction: column;
  gap: 8px;
  color: #ffffff;
}

.feed__caption-text {
  font-size: 14px;
  line-height: 20px;
}

.feed__tags {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.feed__entry {
  display: flex;
  height: 44px;
  align-items: center;
  gap: 8px;
  padding: 0 10px 0 8px;
  border-radius: 8px;
  color: #ffffff;
  background: rgba(255, 255, 255, 0.16);
}

.feed__entry-icon {
  display: flex;
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: #ff6a00;
}

.feed__entry-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
}

.feed__entry-kind {
  color: rgba(255, 255, 255, 0.75);
  font-size: 11px;
  line-height: 15px;
}

.feed__entry-text {
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.feed__entry-cta {
  flex-shrink: 0;
  color: #ffb27a;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}

.feed__foot {
  display: flex;
  align-items: center;
  gap: 6px;
  color: rgba(255, 255, 255, 0.7);
  font-size: 12px;
}

.feed__mute {
  display: flex;
  min-height: 28px;
  align-items: center;
  gap: 4px;
  color: rgba(255, 255, 255, 0.7);
  font-size: 12px;
}

.feed__position {
  flex-shrink: 0;
  margin-left: auto;
  white-space: nowrap;
}

.feed__progress {
  position: absolute;
  z-index: 3;
  right: 0;
  bottom: calc(30px + env(safe-area-inset-bottom));
  left: 0;
  height: 2px;
  background: rgba(255, 255, 255, 0.25);
}

.feed__progress-fill {
  height: 100%;
  background: #ffffff;
}

.feed__state {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 0 40px;
  background: #111111;
  text-align: center;
}

.feed__state-title {
  font-size: 16px;
  font-weight: 700;
}

.feed__state-copy {
  color: rgba(255, 255, 255, 0.7);
  font-size: 13px;
  line-height: 19px;
}

.feed__state-action {
  height: 44px;
  margin-top: 6px;
  padding: 0 24px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
  line-height: 44px;
  text-align: center;
}
</style>
