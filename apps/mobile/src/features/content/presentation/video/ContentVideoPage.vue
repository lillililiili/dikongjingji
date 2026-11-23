<script setup lang="ts">
import { computed, getCurrentInstance, ref } from 'vue'

import { FAVORITE_PREVIEW_ENABLED } from '@/features/favorite/application/favorite-preview'
import FavoriteButton from '@/features/favorite/presentation/FavoriteButton.vue'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import { ApiRequestError } from '@/shared/http/public-api-request'
import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { resolveContentRefs, type ContentRefView } from '../../application/content-refs'
import { articleMetaLine } from '../../application/content-view'
import { formatPlayerClock, formatVideoCount, formatVideoTags } from '../../application/video-view'
import { contentLinkedApis } from '../../data/content-linked-apis'
import type { ContentVideoApi } from '../../data/content-video-api'
import { videoPlaySource, videoPoster } from '../../data/preview/content-preview-videos'
import type { ContentVideo } from '../../domain/content'
import { buildContentVideoUrl, contentAuthorHome } from '../../navigation/content-routes'

// One video (board ContentVideo): the player, the title, the author with 进店 / 主页, the caption,
// 内容中提到 read from each module, and at the bottom 收藏 (board ContentVideo, plan 08 S5-6) and 点赞
// with the first mentions' pages. 分享 only copies the link.
const props = defineProps<{
  videoId: string
  cityCode: string
  api: Pick<ContentVideoApi, 'loadVideo' | 'setLiked'>
  accessToken: string | null
}>()

const emit = defineEmits<{
  back: []
  // Back to 发现 when the video cannot be shown here.
  browse: []
  open: [url: string]
  // 点赞 needs a login.
  login: []
  // 看更多视频: the vertical stream from this video.
  stream: [videoId: string]
}>()

type VideoState =
  | { kind: 'loading' }
  | { kind: 'ready'; video: ContentVideo }
  | { kind: 'missing' }
  | { kind: 'error' }

const PLAYER_ID = 'content-video-player'
const instance = getCurrentInstance()
const currentCityStore = useCurrentCityStore()
const state = ref<VideoState>({ kind: 'loading' })
const refs = ref<ContentRefView[] | null>(null)
const playing = ref(false)
const position = ref(0)
const length = ref(0)
const liking = ref(false)
const shareTarget = ref<ShareTarget | null>(null)
let requestVersion = 0

const video = computed(() => (state.value.kind === 'ready' ? state.value.video : null))
const source = computed(() => (video.value ? videoPlaySource(video.value) : null))
const poster = computed(() => (video.value ? videoPoster(video.value) : null))
const home = computed(() => (video.value ? contentAuthorHome(video.value.author, props.cityCode) : { kind: 'NONE' as const }))
const homeLabel = computed(() => (video.value?.author.kind === 'MERCHANT' ? '进店' : '主页'))
const metaLine = computed(() => {
  if (!video.value) return ''
  const city = currentCityStore.currentCity
  return articleMetaLine({ ...video.value, kind: 'VIDEO' }, city && city.code === props.cityCode ? city : null)
})
// The clock under the player: the clip's own length once it is known, else the listed duration.
const total = computed(() => length.value || video.value?.durationSeconds || 0)
const progress = computed(() => (total.value ? Math.min(100, (position.value / total.value) * 100) : 0))
const primaryRef = computed(() => refs.value?.[0] ?? null)
const secondaryRef = computed(() => refs.value?.[1] ?? null)
const footnote = computed(() => {
  switch (video.value?.author.kind) {
    case 'MERCHANT': return '内容由商家发布，价格和在售情况以商品页为准。'
    case 'PROVIDER': return '内容由服务商发布，实际范围、周期和费用以报价方案为准。'
    default: return '内容由城市运营发布，活动安排以详情页为准。'
  }
})

// Icons copied from the board ContentVideo.
const ICONS = {
  back: 'M15 5l-7 7 7 7',
  share: 'M12 15V4M8 8l4-4 4 4M5 13v7h14v-7',
  play: 'M8 5l12 7-12 7z',
  pause: 'M7 5h4v14H7zM13 5h4v14h-4z',
  store: 'M4 9l2-5h12l2 5M4 9v11h16V9M4 9h16M9 20v-6h6v6',
  author: 'M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6zM9 12l2 2 4-4',
  heart: 'M12 20s-7-4.6-9-9.1C1.7 7.7 3.8 5 6.7 5c2 0 3.6 1 5.3 3 1.7-2 3.3-3 5.3-3 2.9 0 5 2.7 3.7 5.9-2 4.5-9 9.1-9 9.1z',
  info: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01',
}

async function load(): Promise<void> {
  if (!props.videoId || !props.cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  refs.value = null
  try {
    const loaded = await props.api.loadVideo(props.videoId, props.cityCode, props.accessToken)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', video: loaded }
    const views = await resolveContentRefs(loaded.refs, props.cityCode, contentLinkedApis)
    if (requested !== requestVersion) return
    refs.value = views
  } catch (error) {
    if (requested !== requestVersion) return
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function player() {
  return uni.createVideoContext(PLAYER_ID, instance?.proxy)
}

function togglePlay(): void {
  if (!source.value) return
  if (playing.value) player().pause()
  else player().play()
}

function onTime(event: Event): void {
  const detail = (event as Event & { detail?: { currentTime?: number; duration?: number } }).detail
  position.value = detail?.currentTime || 0
  if (detail?.duration && detail.duration > 0) length.value = detail.duration
}

async function toggleLike(): Promise<void> {
  const current = video.value
  if (!current || liking.value) return
  if (!props.accessToken) {
    emit('login')
    return
  }
  const want = !current.liked
  liking.value = true
  state.value = { kind: 'ready', video: { ...current, liked: want, likeCount: Math.max(0, current.likeCount + (want ? 1 : -1)) } }
  try {
    const result = await props.api.setLiked(current.videoId, want, props.accessToken)
    if (video.value) state.value = { kind: 'ready', video: { ...video.value, ...result } }
  } catch (error) {
    if (video.value) state.value = { kind: 'ready', video: { ...video.value, liked: current.liked, likeCount: current.likeCount } }
    if (error instanceof ApiRequestError && error.status === 401) emit('login')
    else uni.showToast({ title: '操作失败，请稍后重试', icon: 'none' })
  } finally {
    liking.value = false
  }
}

function openHome(): void {
  const target = home.value
  if (target.kind === 'PAGE') emit('open', target.url)
  else if (target.kind === 'NOTICE') uni.showToast({ title: target.message, icon: 'none' })
}

function share(): void {
  const current = video.value
  if (!current) return
  shareTarget.value = {
    subject: '内容',
    title: current.title,
    subtitle: '视频',
    owner: current.author.name,
    imageUrl: current.coverImage?.url ?? null,
    path: buildContentVideoUrl(current.videoId, props.cityCode),
  }
}

void load()
</script>

<template>
  <view class="video" data-testid="content-video" :data-state="state.kind">
    <view class="video__nav">
      <button class="video__nav-button" data-testid="content-video-back" aria-label="返回" @click="emit('back')">
        <view class="video__nav-circle"><LineIcon :d="ICONS.back" :size="20" color="#ffffff" :stroke-width="2" /></view>
      </button>
      <button v-if="video" class="video__nav-button" data-testid="content-video-share" aria-label="分享" aria-haspopup="dialog" @click="share">
        <view class="video__nav-circle"><LineIcon :d="ICONS.share" :size="20" color="#ffffff" /></view>
      </button>
    </view>

    <view v-if="state.kind === 'loading'" class="video__loading" aria-label="正在加载">
      <view class="video__skeleton video__skeleton--player" />
      <view class="video__skeleton" />
      <view class="video__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing' || state.kind === 'error'" class="video__state" data-testid="content-video-unavailable">
      <LineIcon :d="ICONS.info" :size="42" color="#b5bac1" />
      <text class="video__state-title">视频暂不可用</text>
      <text class="video__state-copy">
        {{ state.kind === 'missing' ? '视频已下线，或不在当前城市展示' : '视频加载失败，请检查网络后重试' }}
      </text>
      <button class="video__state-action" data-testid="content-video-recover" @click="state.kind === 'missing' ? emit('browse') : load()">
        {{ state.kind === 'missing' ? '看看其他内容' : '重新加载' }}
      </button>
    </view>

    <template v-else-if="video">
      <view class="video__player" data-testid="content-video-player">
        <video
          v-if="source"
          :id="PLAYER_ID"
          class="video__media"
          :src="source"
          :poster="poster?.url ?? ''"
          :controls="false"
          :show-center-play-btn="false"
          :show-fullscreen-btn="false"
          :enable-progress-gesture="false"
          object-fit="contain"
          @play="playing = true"
          @pause="playing = false"
          @ended="playing = false"
          @timeupdate="onTime"
        />
        <image v-else-if="poster" class="video__media" :src="poster.url" mode="aspectFill" :aria-label="poster.alt" />
        <button
          v-if="source"
          class="video__play"
          data-testid="content-video-play"
          :aria-label="playing ? '暂停' : '播放'"
          :aria-pressed="playing"
          @click="togglePlay"
        >
          <LineIcon :d="playing ? ICONS.pause : ICONS.play" :size="28" fill="#ffffff" color="#ffffff" />
        </button>
        <text v-else class="video__no-source" data-testid="content-video-no-source">暂时没有可播放的视频</text>
        <view class="video__bar">
          <text class="video__clock">{{ formatPlayerClock(position) }}</text>
          <view class="video__track"><view class="video__track-fill" :style="{ width: `${progress}%` }" /></view>
          <text class="video__clock">{{ formatPlayerClock(total) }}</text>
        </view>
      </view>

      <view class="video__main">
        <text class="video__title" role="heading" aria-level="1" data-testid="content-video-title">{{ video.title }}</text>
        <view class="video__author" data-testid="content-video-author">
          <view class="video__author-badge">
            <LineIcon :d="video.author.kind === 'MERCHANT' ? ICONS.store : ICONS.author" :size="20" color="#ff6a00" />
          </view>
          <view class="video__author-copy">
            <view class="video__author-line">
              <text class="video__author-name">{{ video.author.name }}</text>
              <text v-if="video.sponsored" class="video__ad" data-testid="content-sponsored">广告</text>
            </view>
            <view class="video__author-meta-line">
              <text class="video__author-meta">{{ metaLine }}</text>
              <text v-if="video.sample" class="video__sample" data-testid="content-sample">示例</text>
            </view>
          </view>
          <button v-if="home.kind !== 'NONE'" class="video__home" data-testid="content-video-home" @click="openHome">{{ homeLabel }}</button>
        </view>
        <text v-if="video.caption" class="video__caption" data-testid="content-video-caption">{{ video.caption }}</text>
        <text v-if="video.tags.length" class="video__tags">{{ formatVideoTags(video.tags) }}</text>
      </view>

      <view v-if="refs === null && video.refs.length" class="video__refs" aria-label="正在读取内容中提到的商品和服务">
        <text class="video__refs-title">内容中提到</text>
        <view v-for="mention in video.refs" :key="`${mention.type}:${mention.refId}`" class="video__ref video__ref--loading" />
      </view>
      <view v-else-if="refs && refs.length" class="video__refs" data-testid="content-video-refs">
        <text class="video__refs-title" role="heading" aria-level="2">内容中提到</text>
        <button
          v-for="(item, index) in refs"
          :key="item.key"
          class="video__ref"
          :data-testid="`content-ref-${item.refId}`"
          :data-type="item.type"
          @click="emit('open', item.url)"
        >
          <view
            :class="['video__ref-thumb', { 'video__ref-thumb--contain': item.imagePadded }]"
            :style="{ background: item.imagePadded ? '#fff0e3' : item.imageContain ? '#f0edff' : item.imageUrl ? '#eef2f8' : item.tagTone.background }"
          >
            <image v-if="item.imageUrl" class="video__ref-image" :src="item.imageUrl" :mode="item.imageContain ? 'aspectFit' : 'aspectFill'" :aria-label="item.title" />
            <text v-else class="video__ref-initial" :style="{ color: item.tagTone.color }">{{ item.tag }}</text>
          </view>
          <view class="video__ref-copy">
            <view class="video__ref-line">
              <text class="video__ref-tag" :style="{ background: item.tagTone.background, color: item.tagTone.color }">{{ item.tag }}</text>
              <text class="video__ref-title">{{ item.title }}</text>
            </view>
            <view v-if="item.price" class="video__ref-price">
              <text class="video__ref-amount">{{ item.price.amount }}</text>
              <text v-if="item.price.unit">{{ item.price.unit }}</text>
            </view>
          </view>
          <!-- The board: the first mention carries the orange button, the next ones an outlined one. -->
          <text :class="['video__ref-action', { 'video__ref-action--primary': index === 0 }]">{{ item.actionLabel }}</text>
        </button>
        <text class="video__footnote">{{ footnote }}</text>
      </view>

      <view class="video__bottom">
        <FavoriteButton
          v-if="FAVORITE_PREVIEW_ENABLED"
          type="CONTENT"
          :target-id="video.videoId"
          :city-code="cityCode"
          :return-url="buildContentVideoUrl(video.videoId, cityCode)"
          icon="star"
        />
        <button
          class="video__like"
          data-testid="content-video-like"
          :aria-pressed="video.liked"
          :aria-label="video.liked ? '取消点赞' : '点赞'"
          @click="toggleLike"
        >
          <LineIcon :d="ICONS.heart" :size="22" :color="video.liked ? '#ff6a00' : '#536178'" :fill="video.liked ? '#ff6a00' : undefined" />
          <text :style="{ color: video.liked ? '#ff6a00' : '#536178' }">{{ video.likeCount ? formatVideoCount(video.likeCount) : '点赞' }}</text>
        </button>
        <button v-if="secondaryRef" class="video__secondary" data-testid="content-video-secondary" @click="emit('open', secondaryRef.url)">
          {{ secondaryRef.actionLabel }}
        </button>
        <button v-if="primaryRef" class="video__primary" data-testid="content-video-primary" @click="emit('open', primaryRef.url)">
          {{ primaryRef.actionLabel }}
        </button>
        <button v-else-if="refs !== null" class="video__primary" data-testid="content-video-stream" @click="emit('stream', video.videoId)">
          看更多视频
        </button>
      </view>
    </template>

    <ShareSheet :target="shareTarget" @close="shareTarget = null" />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board ContentVideo (390 wide). */
.video {
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

.video__nav {
  position: fixed;
  z-index: 20;
  top: calc(var(--status-bar-height) + 4px);
  right: 8px;
  left: 8px;
  display: flex;
  justify-content: space-between;
  pointer-events: none;
}

.video__nav-button {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  pointer-events: auto;
}

.video__nav-circle {
  display: flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
}

.video__player {
  position: relative;
  height: calc(240px + var(--status-bar-height));
  padding-top: var(--status-bar-height);
  box-sizing: border-box;
  background: #202c42;
}

.video__media {
  display: block;
  width: 100%;
  height: 100%;
}

.video__play {
  position: absolute;
  top: calc(50% + var(--status-bar-height) / 2);
  left: 50%;
  display: flex;
  width: 64px;
  height: 64px;
  align-items: center;
  justify-content: center;
  margin: -32px 0 0 -32px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
}

.video__no-source {
  position: absolute;
  top: calc(50% + var(--status-bar-height) / 2);
  right: 16px;
  left: 16px;
  margin-top: -10px;
  color: rgba(255, 255, 255, 0.85);
  font-size: 13px;
  text-align: center;
}

.video__bar {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 20px 12px 10px;
  color: #ffffff;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0) 0%, rgba(0, 0, 0, 0.55) 100%);
  font-size: 12px;
  line-height: 17px;
  pointer-events: none;
}

.video__clock {
  flex-shrink: 0;
}

.video__track {
  flex: 1;
  min-width: 0;
  height: 3px;
  overflow: hidden;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.35);
}

.video__track-fill {
  height: 3px;
  border-radius: 2px;
  background: #ff6a00;
}

.video__main {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px 16px;
  background: #ffffff;
}

.video__title {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.video__author {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 10px;
}

.video__author-badge {
  display: flex;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff0e3;
}

.video__author-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.video__author-line,
.video__author-meta-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.video__author-name {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.video__ad {
  flex-shrink: 0;
  padding: 0 4px;
  border: 1px solid #c9d3e1;
  border-radius: 4px;
  color: #627187;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

.video__author-meta {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.video__sample {
  flex-shrink: 0;
  color: #738096;
  font-size: 11px;
  line-height: 17px;
}

.video__home {
  flex-shrink: 0;
  height: 30px;
  padding: 0 14px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  box-sizing: border-box;
  color: #ba470b;
  font-size: 13px;
  font-weight: 500;
  line-height: 28px;
  white-space: nowrap;
}

.video__caption {
  color: #536178;
  font-size: 14px;
  line-height: 24px;
}

.video__tags {
  color: #1d63c4;
  font-size: 13px;
  line-height: 20px;
}

.video__refs {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px 16px;
}

.video__refs-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.video__ref {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.video__ref--loading {
  height: 84px;
  box-sizing: border-box;
  background: linear-gradient(90deg, #eef1f6 25%, #f6f8fb 50%, #eef1f6 75%);
}

.video__ref-thumb {
  display: flex;
  flex-shrink: 0;
  width: 60px;
  height: 60px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 10px;
}

.video__ref-thumb--contain {
  padding: 10px;
  box-sizing: border-box;
}

.video__ref-image {
  width: 100%;
  height: 100%;
}

.video__ref-initial {
  font-size: 13px;
  font-weight: 700;
}

.video__ref-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
}

.video__ref-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.video__ref-tag {
  flex-shrink: 0;
  padding: 0 5px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.video__ref-title {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.video__ref-price {
  display: flex;
  align-items: baseline;
  gap: 3px;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.video__ref-amount {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.video__ref-action {
  flex-shrink: 0;
  height: 36px;
  padding: 0 14px;
  border: 1px solid #159a70;
  border-radius: 999px;
  box-sizing: border-box;
  color: #11804a;
  font-size: 14px;
  font-weight: 500;
  line-height: 34px;
  white-space: nowrap;
}

.video__ref-action--primary {
  border-color: #ff6a00;
  color: #ffffff;
  background: #ff6a00;
}

.video__footnote {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.video__bottom {
  position: fixed;
  z-index: 15;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

.video__like {
  display: flex;
  flex-shrink: 0;
  width: 46px;
  height: 46px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

.video__secondary,
.video__primary {
  display: flex;
  flex: 1 1 0;
  min-width: 0;
  height: 46px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  box-sizing: border-box;
  font-size: 16px;
  font-weight: 500;
  white-space: nowrap;
}

.video__secondary {
  border: 1.5px solid #ff6a00;
  color: #ba470b;
  background: #ffffff;
}

.video__primary {
  color: #ffffff;
  background: #ff6a00;
}

.video__loading {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.video__skeleton {
  height: 72px;
  margin: 0 16px;
  border-radius: 12px;
  background: linear-gradient(90deg, #eef1f6 25%, #f6f8fb 50%, #eef1f6 75%);
}

.video__skeleton--player {
  height: calc(240px + var(--status-bar-height));
  margin: 0;
  border-radius: 0;
}

.video__state {
  display: flex;
  min-height: 70vh;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 24px;
  text-align: center;
}

.video__state-title {
  font-size: 16px;
  font-weight: 600;
}

.video__state-copy {
  color: #738096;
  font-size: 13px;
}

.video__state-action {
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
