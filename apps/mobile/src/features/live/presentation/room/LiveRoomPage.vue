<script setup lang="ts">
import { isDevelopmentMediaUrl } from '../../../../shared/http/business-media'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { contentLinkedApis } from '@/features/content/data/content-linked-apis'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { ApiRequestError } from '@/shared/http/public-api-request'
import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'
import LineIcon from '@/shared/ui/LineIcon.vue'
import {
  appKeyboardViewport,
  watchH5KeyboardViewport,
  type KeyboardViewport,
} from '@/shared/ui/keyboard-viewport'

import { currentLiveItem, liveItemPrice, resolveLiveItems, type LiveItemView } from '../../application/live-items'
import {
  addOwnComment,
  createLikeBatcher,
  createLiveKey,
  emptyCommentFeed,
  liveDeviceId,
  mergeCommentPage,
  sendableComment,
  type CommentFeed,
} from '../../application/live-room-session'
import { chatLines, formatElapsed, formatLiveCount, hostAvatarColor, hostInitial } from '../../application/live-view'
import { liveApi } from '../../data/live-api-client'
import { LIVE_COMMENT_LIMIT, LIVE_COMMENT_POLL_MS, type LiveComment, type LiveRoom } from '../../domain/live'
import { buildLiveNoticeUrl, buildLiveRoomUrl } from '../../navigation/live-routes'

// 直播间 (board LiveRoom): the picture, the host with 关注, viewers and likes, the item being
// explained, comments over the picture, 讲解 / 分享 / 点赞 on the right, up and down to the other
// live rooms of the city, and the explainer list in a bottom sheet. Nothing is paid here: the items
// open their own pages. Comments come in by polling while the page is in front.
const props = defineProps<{ roomId: string; cityCode: string }>()
const emit = defineEmits<{
  back: []
  open: [url: string]
  // Another room of the city, or this room's preview page: the host replaces the page.
  replace: [url: string]
  login: [returnUrl: string]
}>()

const ROOM_REFRESH_MS = 15_000

const status = ref<'loading' | 'ready' | 'missing' | 'error'>('loading')
const room = ref<LiveRoom | null>(null)
const items = ref<LiveItemView[]>([])
const feed = ref<CommentFeed>(emptyCommentFeed())
const mine = ref<Set<string>>(new Set())
const liveRooms = ref<string[]>([])
const following = ref(false)
const myLikes = ref(0)
const likeCount = ref(0)
const viewerCount = ref(0)
const draft = ref('')
const sending = ref(false)
const bagOpen = ref(false)
const shareTarget = ref<ShareTarget | null>(null)
const now = ref(new Date())
let commentTimer: ReturnType<typeof setInterval> | null = null
let roomTimer: ReturnType<typeof setInterval> | null = null
let clockTimer: ReturnType<typeof setInterval> | null = null
let polling = false
let viewCounted = false
// While the keyboard is open the room fills only what is visible above it, so the input bar sits on
// the keyboard instead of iOS pushing the whole page up (H5) or the webview moving (App).
const keyboard = ref<KeyboardViewport | null>(null)
let stopKeyboardWatch: () => void = () => {}
const roomStyle = computed((): Record<string, string> => (keyboard.value
  ? { position: 'fixed', top: `${keyboard.value.top}px`, left: '0', right: '0', height: `${keyboard.value.height}px` }
  : {}))

const pageUrl = computed(() => buildLiveRoomUrl(props.roomId, props.cityCode))
const live = computed(() => room.value?.status === 'LIVE')
const picture = computed(() => (room.value
  ? room.value.coverUrl ?? null
  : null))
const playUrl = computed(() => (live.value && !room.value?.streamInterrupted ? room.value?.playUrl ?? null : null))
const sample = computed(() => isDevelopmentMediaUrl(playUrl.value))
const pin = computed(() => currentLiveItem(items.value, room.value?.currentItemSeq ?? null))
const lines = computed(() => chatLines(feed.value.items, mine.value))
const at = computed(() => liveRooms.value.indexOf(props.roomId))
const prevRoom = computed(() => (at.value > 0 ? liveRooms.value[at.value - 1] : null))
const nextRoom = computed(() => (at.value >= 0 && at.value < liveRooms.value.length - 1 ? liveRooms.value[at.value + 1] : null))
const canSend = computed(() => sendableComment(draft.value) !== null && !sending.value)
const hearts = computed(() => (myLikes.value > 0
  ? [[`${18 + (myLikes.value % 3) * 2}px`, 0.9, '0'], ['16px', 0.6, '10px'], ['14px', 0.35, '-8px']]
      .map(([size, opacity, margin], index) => ({ key: index, size, opacity, margin }))
  : []))

const token = () => getBookingAuthContext().getAccessToken()

const likes = createLikeBatcher({
  async send(count) {
    const accessToken = token()
    if (!accessToken) return
    likeCount.value = Math.max(likeCount.value, await liveApi.like(accessToken, props.roomId, count))
  },
})

async function load(): Promise<void> {
  now.value = new Date()
  if (!room.value) status.value = 'loading'
  try {
    const loaded = await liveApi.loadRoom(props.roomId, props.cityCode)
    applyRoom(loaded)
    status.value = 'ready'
    void loadItems(loaded)
    void loadViewerState(loaded)
    void loadLiveRooms()
    if (loaded.status === 'LIVE') {
      void countView()
      void pollComments()
    }
  } catch (error) {
    status.value = error instanceof ApiRequestError && error.status === 404 ? 'missing' : 'error'
  }
}

// Counts only grow on the server; a reading older than this device's own view or likes never
// takes the number back.
function applyRoom(loaded: LiveRoom): void {
  room.value = loaded
  viewerCount.value = Math.max(loaded.viewerCount, viewerCount.value)
  likeCount.value = Math.max(loaded.likeCount, likeCount.value)
}

async function loadItems(loaded: LiveRoom): Promise<void> {
  try {
    items.value = await resolveLiveItems(loaded.items, props.cityCode, contentLinkedApis)
  } catch {
    items.value = []
  }
}

async function loadViewerState(loaded: LiveRoom): Promise<void> {
  const accessToken = token()
  if (!accessToken) return
  try {
    following.value = (await liveApi.loadViewerState(accessToken, loaded.roomId)).followingHost
  } catch {
    // The button stays 关注; tapping it says what is wrong.
  }
}

async function loadLiveRooms(): Promise<void> {
  try {
    const page = await liveApi.loadRooms(props.cityCode, 'LIVE', 1, 20)
    liveRooms.value = page.items.map((card) => card.roomId)
  } catch {
    liveRooms.value = []
  }
}

async function countView(): Promise<void> {
  if (viewCounted) return
  viewCounted = true
  try {
    viewerCount.value = Math.max(viewerCount.value, await liveApi.recordView(props.roomId, liveDeviceId()))
  } catch {
    viewCounted = false
  }
}

async function pollComments(): Promise<void> {
  if (polling) return
  polling = true
  try {
    const page = await liveApi.loadComments(props.roomId, feed.value.cursor)
    feed.value = mergeCommentPage(feed.value, page)
  } catch {
    // The next poll tries again.
  } finally {
    polling = false
  }
}

async function refreshRoom(): Promise<void> {
  try {
    const loaded = await liveApi.loadRoom(props.roomId, props.cityCode)
    const itemsChanged = JSON.stringify(loaded.items) !== JSON.stringify(room.value?.items ?? [])
    applyRoom(loaded)
    if (itemsChanged) void loadItems(loaded)
  } catch {
    // Keep what is on screen.
  }
}

/** The host page starts the timers when the page shows and stops them when it hides. */
function start(): void {
  stop()
  void load()
  commentTimer = setInterval(() => { if (live.value) void pollComments() }, LIVE_COMMENT_POLL_MS)
  roomTimer = setInterval(() => { void refreshRoom() }, ROOM_REFRESH_MS)
  clockTimer = setInterval(() => { now.value = new Date() }, 1000)
}

function stop(): void {
  for (const timer of [commentTimer, roomTimer, clockTimer]) {
    if (timer !== null) clearInterval(timer)
  }
  commentTimer = null
  roomTimer = null
  clockTimer = null
  void likes.flush()
}

// App: the input does not move the page (adjust-position off); its keyboardheightchange says how
// much of the window the keyboard takes.
function onKeyboardHeight(event: Event): void {
  const height = Number((event as Event & { detail?: { height?: number } }).detail?.height ?? 0)
  keyboard.value = appKeyboardViewport(uni.getSystemInfoSync().windowHeight, height)
}

onMounted(() => {
  stopKeyboardWatch = watchH5KeyboardViewport((viewport) => { keyboard.value = viewport })
})
onBeforeUnmount(() => stopKeyboardWatch())

function needLogin(): boolean {
  if (token()) return false
  emit('login', pageUrl.value)
  return true
}

async function toggleFollow(): Promise<void> {
  const accessToken = token()
  if (!room.value || needLogin() || !accessToken) return
  try {
    following.value = await liveApi.setFollow(accessToken, room.value.host.organizationId, !following.value)
  } catch (error) {
    if (error instanceof ApiRequestError && error.status === 401) emit('login', pageUrl.value)
    else uni.showToast({ title: '没有关注成功，请再试一次', icon: 'none' })
  }
}

function like(): void {
  if (!live.value || needLogin()) return
  myLikes.value += 1
  likeCount.value += 1
  likes.tap()
}

function onInput(event: Event): void {
  const value = (event as Event & { detail?: { value?: string } }).detail?.value ?? ''
  draft.value = String(value).slice(0, LIVE_COMMENT_LIMIT)
}

// The input's confirm event carries the value: v-model can lag behind on H5 (input is throttled).
function onConfirm(event: Event): void {
  void send((event as Event & { detail?: { value?: string } }).detail?.value ?? draft.value)
}

async function send(value?: string): Promise<void> {
  const text = sendableComment(value ?? draft.value)
  if (!text || sending.value || needLogin()) return
  const accessToken = token()
  if (!accessToken) return
  sending.value = true
  try {
    const comment = await liveApi.sendComment(accessToken, props.roomId, createLiveKey(), text)
    mine.value = new Set([...mine.value, comment.commentId])
    feed.value = addOwnComment(feed.value, comment)
    draft.value = ''
  } catch (error) {
    const code = error instanceof ApiRequestError ? error.code : null
    if (error instanceof ApiRequestError && error.status === 401) emit('login', pageUrl.value)
    else if (code === 'LIVE_COMMENT_TOO_FAST') uni.showToast({ title: '发得太快了，稍等几秒再发', icon: 'none' })
    else if (code === 'LIVE_COMMENT_BLOCKED') uni.showToast({ title: '这条弹幕含有不允许发送的内容', icon: 'none' })
    else if (code === 'LIVE_ROOM_NOT_LIVE') void refreshRoom()
    else uni.showToast({ title: '弹幕没有发出去，请再试一次', icon: 'none' })
  } finally {
    sending.value = false
  }
}

function commentOf(key: string): LiveComment | null {
  return feed.value.items.find((comment) => comment.commentId === key) ?? null
}

/** Tapping someone else's comment offers 举报 (it goes to the platform's feedback). */
function onLine(key: string): void {
  const comment = commentOf(key)
  if (!comment || comment.side === 'HOST' || mine.value.has(comment.commentId)) return
  uni.showActionSheet({
    itemList: ['举报这条弹幕'],
    success: () => { void report(comment) },
  })
}

async function report(comment: LiveComment): Promise<void> {
  const accessToken = token()
  if (needLogin() || !accessToken) return
  try {
    await liveApi.report(accessToken, props.roomId, comment.commentId)
    uni.showToast({ title: '已收到举报，平台会核实处理', icon: 'none' })
  } catch {
    uni.showToast({ title: '没有提交成功，请再试一次', icon: 'none' })
  }
}

function switchTo(roomId: string | null): void {
  if (!roomId) return
  bagOpen.value = false
  emit('replace', buildLiveRoomUrl(roomId, props.cityCode))
}

function openItem(item: LiveItemView): void {
  bagOpen.value = false
  emit('open', item.url)
}

function openShare(): void {
  if (!room.value) return
  shareTarget.value = {
    subject: '直播',
    title: room.value.title,
    subtitle: `${room.value.host.name} · 正在直播`,
    owner: room.value.host.name,
    imageUrl: picture.value,
    path: pageUrl.value,
  }
}

// Icons copied from the board LiveRoom.
const ICONS = {
  close: 'M6 6l12 12M18 6L6 18',
  up: 'M6 15l6-6 6 6',
  down: 'M6 9l6 6 6-6',
  bag: 'M5 8h14l-1 12H6zM9 8V6a3 3 0 0 1 6 0v2',
  share: 'M14 4l8 7-8 7v-4.5C8 13.5 4.5 15.5 2 19c1-5 4-9.5 12-10.5z',
  heart: 'M12 20s-7-4.6-9-9.1C1.7 7.7 3.8 5 6.7 5c2 0 3.6 1 5.3 3 1.7-2 3.3-3 5.3-3 2.9 0 5 2.7 3.7 5.9-2 4.5-9 9.1-9 9.1z',
}

defineExpose({ start, stop })
</script>

<template>
  <view
    :class="['room', { 'room--keyboard': keyboard }]"
    :style="roomStyle"
    data-testid="live-room"
    :data-state="status"
    :data-status="room?.status"
    :data-keyboard="keyboard ? 'open' : 'closed'"
  >
    <view v-if="status === 'loading'" class="room__state" aria-label="正在加载" />

    <view v-else-if="status === 'missing' || status === 'error'" class="room__state" :data-testid="`live-room-${status}`">
      <text class="room__state-title">{{ status === 'missing' ? '这场直播不存在，或不在当前城市' : '直播间没有加载出来，请稍后重试' }}</text>
      <button v-if="status === 'error'" class="room__state-action" data-testid="live-room-retry" @click="load">重新加载</button>
      <button class="room__state-action" @click="emit('back')">返回</button>
    </view>

    <template v-else-if="room">
      <video
        v-if="playUrl"
        id="live-room-video"
        class="room__video"
        data-testid="live-room-video"
        :src="playUrl"
        :poster="picture ?? ''"
        :autoplay="true"
        :loop="sample"
        :muted="true"
        :controls="false"
        :show-center-play-btn="false"
        :show-play-btn="false"
        :show-progress="false"
        :show-fullscreen-btn="false"
        :enable-progress-gesture="false"
        object-fit="cover"
      />
      <image v-else-if="picture" class="room__video room__video--still" :src="picture" mode="aspectFill" />
      <view class="room__shade" />

      <view v-if="!live" class="room__over" data-testid="live-room-over">
        <text class="room__over-title">{{ room.status === 'ENDED' ? '直播已结束' : '直播还没开始' }}</text>
        <button class="room__over-action" data-testid="live-room-notice" @click="emit('replace', buildLiveNoticeUrl(room.roomId, cityCode))">
          {{ room.status === 'ENDED' ? '看回看与预告' : '看直播预告' }}
        </button>
      </view>
      <view v-else-if="room.streamInterrupted" class="room__over room__over--soft" data-testid="live-room-interrupted">
        <text class="room__over-title">主播暂时离开，画面马上回来</text>
      </view>

      <view class="room__top">
        <view class="room__host">
          <text class="room__host-avatar" :style="{ background: hostAvatarColor(room.host.name) }">{{ hostInitial(room.host.name) }}</text>
          <view class="room__host-copy">
            <text class="room__host-name">{{ room.host.name }}</text>
            <text class="room__host-meta" data-testid="live-room-counts">{{ formatLiveCount(viewerCount) }} 观看 · {{ formatLiveCount(likeCount) }} 赞</text>
          </view>
          <button
            :class="['room__follow', { 'room__follow--on': following }]"
            :aria-pressed="following"
            data-testid="live-room-follow"
            @click="toggleFollow"
          >
            {{ following ? '已关注' : '关注' }}
          </button>
        </view>
        <view class="room__top-right">
          <text v-if="live" class="room__badge">直播中</text>
          <button class="room__close" aria-label="关闭直播间" data-testid="live-room-close" @click="emit('back')">
            <LineIcon :d="ICONS.close" :size="18" color="#fff" :stroke-width="2.2" />
          </button>
        </view>
      </view>
      <view v-if="live" class="room__pills">
        <text class="room__pill" data-testid="live-room-elapsed">已播 {{ formatElapsed(room.startedAt, now) }}</text>
        <text v-if="sample" class="room__pill">示例画面</text>
      </view>

      <view class="room__switch">
        <button class="room__switch-button" aria-label="上一个直播间" :disabled="!prevRoom" :style="{ opacity: prevRoom ? 1 : 0.35 }" data-testid="live-room-prev" @click="switchTo(prevRoom)">
          <LineIcon :d="ICONS.up" :size="20" color="#fff" :stroke-width="2.2" />
        </button>
        <button class="room__switch-button" aria-label="下一个直播间" :disabled="!nextRoom" :style="{ opacity: nextRoom ? 1 : 0.35 }" data-testid="live-room-next" @click="switchTo(nextRoom)">
          <LineIcon :d="ICONS.down" :size="20" color="#fff" :stroke-width="2.2" />
        </button>
      </view>

      <view class="room__side">
        <button class="room__side-button" aria-haspopup="dialog" aria-label="讲解中的商品与服务" data-testid="live-room-bag" @click="bagOpen = true">
          <view class="room__side-face"><LineIcon :d="ICONS.bag" :size="22" color="#fff" :stroke-width="2" /></view>
          <text v-if="items.length" class="room__side-count">{{ items.length }}</text>
          <text>讲解</text>
        </button>
        <button class="room__side-button" aria-label="分享直播间" data-testid="live-room-share" @click="openShare">
          <view class="room__side-face"><LineIcon :d="ICONS.share" :size="22" fill="#fff" /></view>
          <text>分享</text>
        </button>
        <button class="room__side-button" aria-label="点赞" data-testid="live-room-like" @click="like">
          <view class="room__side-face"><LineIcon :d="ICONS.heart" :size="24" fill="#ff2d55" /></view>
          <text data-testid="live-room-my-likes">{{ myLikes > 0 ? `+${myLikes}` : '点赞' }}</text>
        </button>
      </view>
      <view v-if="hearts.length" class="room__hearts" aria-hidden="true">
        <text v-for="heart in hearts" :key="heart.key" :style="{ fontSize: heart.size, opacity: heart.opacity, marginRight: heart.margin }">❤</text>
      </view>

      <button v-if="pin && live" class="room__pin" data-testid="live-room-pin" @click="openItem(pin)">
        <image v-if="pin.imageUrl" class="room__pin-image" :src="pin.imageUrl" :mode="pin.imageContain || pin.imagePadded ? 'aspectFit' : 'aspectFill'" />
        <view v-else class="room__pin-image" />
        <view class="room__pin-copy">
          <view class="room__pin-kind"><view class="room__pin-dot" />讲解中 · {{ pin.tag }}</view>
          <text class="room__pin-name">{{ pin.title }}</text>
          <text class="room__pin-price">{{ liveItemPrice(pin) }}</text>
        </view>
        <text class="room__pin-cta">{{ pin.actionLabel }}</text>
      </button>

      <view class="room__chat" role="log" aria-label="弹幕" data-testid="live-room-chat">
        <button
          v-for="line in lines"
          :key="line.key"
          class="room__line"
          :data-testid="`live-line-${line.key}`"
          @click="onLine(line.key)"
        >
          <text class="room__line-name" :style="{ color: line.color }">{{ line.name }}：</text>{{ line.text }}
        </button>
      </view>

      <view class="room__input">
        <input
          class="room__field"
          :value="draft"
          placeholder="说点什么…"
          placeholder-class="room__placeholder"
          aria-label="发弹幕"
          :maxlength="LIVE_COMMENT_LIMIT"
          confirm-type="send"
          :disabled="!live"
          :adjust-position="false"
          data-testid="live-room-input"
          @input="onInput"
          @confirm="onConfirm"
          @keyboardheightchange="onKeyboardHeight"
        >
        <button
          class="room__send"
          :style="{ background: canSend ? '#ff6a00' : 'rgba(255,255,255,0.25)' }"
          :disabled="!canSend || !live"
          data-testid="live-room-send"
          @click="send()"
        >
          发送
        </button>
        <button class="room__notice-link" data-testid="live-room-notice-link" @click="emit('open', buildLiveNoticeUrl(room.roomId, cityCode))">直播预告</button>
      </view>

      <template v-if="bagOpen">
        <view class="room__mask" aria-label="关闭讲解清单" @click="bagOpen = false" />
        <view class="room__bag" role="dialog" aria-label="讲解中的商品与服务" data-testid="live-room-bag-sheet">
          <view class="room__bag-head">
            <text class="room__bag-title">本场讲解 · {{ items.length }} 项</text>
            <button class="room__bag-close" aria-label="关闭" @click="bagOpen = false">×</button>
          </view>
          <text v-if="!items.length" class="room__bag-empty">主播还没有放讲解的商品和服务</text>
          <button
            v-for="item in items"
            :key="item.key"
            class="room__bag-item"
            :data-testid="`live-room-item-${item.seq}`"
            @click="openItem(item)"
          >
            <image v-if="item.imageUrl" class="room__bag-image" :src="item.imageUrl" :mode="item.imageContain || item.imagePadded ? 'aspectFit' : 'aspectFill'" />
            <view v-else class="room__bag-image" />
            <view class="room__bag-copy">
              <text class="room__bag-kind" :style="{ color: item.tagTone.color }">{{ item.tag }}{{ item.seq === room.currentItemSeq ? ' · 讲解中' : '' }}</text>
              <text class="room__bag-name">{{ item.title }}</text>
              <text class="room__bag-price">{{ liveItemPrice(item) }}</text>
            </view>
            <text class="room__bag-cta">{{ item.actionLabel }}</text>
          </button>
          <text class="room__bag-foot">交易在平台内完成，直播间不收款</text>
        </view>
      </template>
      <ShareSheet :target="shareTarget" @close="shareTarget = null" />
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board LiveRoom (390 × 844, dark). */
.room {
  position: relative;
  width: 100%;
  height: 100vh;
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

.room__video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.room__video--still {
  opacity: 0.85;
}

.room__shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.55) 0%, rgba(0, 0, 0, 0) 25%, rgba(0, 0, 0, 0) 50%, rgba(0, 0, 0, 0.8) 100%);
  pointer-events: none;
}

.room__over {
  position: absolute;
  z-index: 3;
  top: 38%;
  right: 24px;
  left: 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 20px 16px;
  border-radius: 16px;
  background: rgba(0, 0, 0, 0.55);
}

.room__over--soft {
  gap: 0;
}

.room__over-title {
  font-size: 16px;
  font-weight: 600;
  text-align: center;
}

.room__over-action {
  height: 40px;
  padding: 0 20px;
  border-radius: 999px;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
  line-height: 40px;
  text-align: center;
}

.room__top {
  position: absolute;
  z-index: 4;
  top: calc(var(--status-bar-height) + 4px);
  right: 10px;
  left: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.room__host {
  display: flex;
  min-width: 0;
  height: 44px;
  align-items: center;
  gap: 8px;
  padding: 0 4px 0 5px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.45);
}

.room__host-avatar {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  color: #ffffff;
  font-size: 15px;
  font-weight: 700;
  line-height: 34px;
  text-align: center;
}

.room__host-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.room__host-name {
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.room__host-meta {
  color: rgba(255, 255, 255, 0.75);
  font-size: 11px;
  line-height: 14px;
  white-space: nowrap;
}

.room__follow {
  flex-shrink: 0;
  height: 36px;
  padding: 0 12px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff2d55;
  font-size: 13px;
  font-weight: 500;
  line-height: 36px;
  white-space: nowrap;
}

.room__follow--on {
  background: rgba(255, 255, 255, 0.25);
}

.room__top-right {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.room__badge {
  height: 28px;
  padding: 0 8px;
  border-radius: 999px;
  background: #d64f67;
  font-size: 11px;
  font-weight: 700;
  line-height: 28px;
  white-space: nowrap;
}

.room__close {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
}

.room__pills {
  position: absolute;
  z-index: 4;
  top: calc(var(--status-bar-height) + 56px);
  left: 10px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.room__pill {
  height: 24px;
  padding: 0 8px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.45);
  font-size: 11px;
  line-height: 24px;
  white-space: nowrap;
}

.room__switch {
  position: absolute;
  z-index: 4;
  top: 50%;
  left: 6px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: -48px;
}

.room__switch-button {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
}

.room__side {
  position: absolute;
  z-index: 4;
  right: 8px;
  bottom: calc(170px + env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.room__side-button {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  color: #ffffff;
  font-size: 11px;
  text-align: center;
}

.room__side-face {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
}

.room__side-count {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  box-sizing: border-box;
  border-radius: 9px;
  background: #ff6a00;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}

.room__hearts {
  position: absolute;
  z-index: 4;
  right: 18px;
  bottom: calc(344px + env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: #ff2d55;
  pointer-events: none;
}

.room__pin {
  position: absolute;
  z-index: 4;
  bottom: calc(236px + env(safe-area-inset-bottom));
  left: 10px;
  display: flex;
  width: 232px;
  align-items: center;
  gap: 8px;
  padding: 8px;
  box-sizing: border-box;
  border-radius: 10px;
  color: #202c42;
  background: rgba(255, 255, 255, 0.95);
}

.room__pin-image {
  flex-shrink: 0;
  width: 56px;
  height: 56px;
  border-radius: 8px;
  background: #eef0f3;
}

.room__pin-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.room__pin-kind {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #d64f67;
  font-size: 11px;
  line-height: 15px;
}

.room__pin-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #d64f67;
}

.room__pin-name {
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.room__pin-price {
  color: #e85400;
  font-size: 13px;
  font-weight: 700;
  line-height: 18px;
  white-space: nowrap;
}

.room__pin-cta {
  flex-shrink: 0;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 12px;
  font-weight: 500;
  line-height: 28px;
  white-space: nowrap;
}

.room__chat {
  position: absolute;
  z-index: 4;
  right: 76px;
  bottom: calc(84px + env(safe-area-inset-bottom));
  left: 10px;
  display: flex;
  height: 144px;
  flex-direction: column;
  justify-content: flex-end;
  gap: 6px;
  overflow: hidden;
}

.room__line {
  align-self: flex-start;
  /* A long comment wraps onto more lines instead of being squeezed into one; the column is
     bottom-aligned with overflow hidden, so the oldest comments are the ones cut off at the top. */
  flex-shrink: 0;
  max-width: 100%;
  white-space: normal;
  word-break: break-all;
  padding: 5px 10px;
  border-radius: 12px;
  color: #ffffff;
  background: rgba(0, 0, 0, 0.42);
  font-size: 13px;
  line-height: 18px;
}

.room__line-name {
  font-weight: 500;
}

.room__input {
  position: absolute;
  z-index: 4;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px calc(28px + env(safe-area-inset-bottom));
}

.room__field {
  flex: 1;
  min-width: 0;
  height: 44px;
  padding: 0 14px;
  box-sizing: border-box;
  border-radius: 999px;
  color: #ffffff;
  background: rgba(255, 255, 255, 0.18);
  font-size: 14px;
}

.room__send {
  flex-shrink: 0;
  height: 44px;
  padding: 0 16px;
  border-radius: 999px;
  color: #ffffff;
  font-size: 14px;
  font-weight: 500;
  line-height: 44px;
}

.room__notice-link {
  flex-shrink: 0;
  height: 44px;
  padding: 0 12px;
  border-radius: 999px;
  color: #ffffff;
  background: rgba(255, 255, 255, 0.18);
  font-size: 13px;
  line-height: 44px;
  white-space: nowrap;
}

/* Keyboard open: the bottom safe area belongs to the keyboard now, and the side buttons, likes and
   the pinned item step aside so a short visible area is left to the picture, comments and input. */
.room--keyboard .room__input {
  padding-bottom: 8px;
}

.room--keyboard .room__chat {
  bottom: 68px;
}

.room--keyboard .room__side,
.room--keyboard .room__hearts,
.room--keyboard .room__pin {
  display: none;
}

.room__mask {
  position: absolute;
  z-index: 8;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
}

.room__bag {
  position: absolute;
  z-index: 9;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 16px 16px 0 0;
  color: #202c42;
  background: #ffffff;
}

.room__bag-head {
  display: flex;
  align-items: center;
}

.room__bag-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.room__bag-close {
  width: 44px;
  height: 44px;
  margin-left: auto;
  color: #536178;
  font-size: 22px;
  line-height: 44px;
  text-align: center;
}

.room__bag-empty {
  padding: 12px 0;
  color: #738096;
  font-size: 13px;
}

.room__bag-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-radius: 12px;
  color: #202c42;
  background: #f5f7fc;
}

.room__bag-image {
  flex-shrink: 0;
  width: 64px;
  height: 64px;
  border-radius: 8px;
  background: #eef0f3;
}

.room__bag-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.room__bag-kind {
  font-size: 11px;
  line-height: 15px;
}

.room__bag-name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.room__bag-price {
  color: #e85400;
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
  white-space: nowrap;
}

.room__bag-cta {
  flex-shrink: 0;
  height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 13px;
  font-weight: 500;
  line-height: 32px;
  white-space: nowrap;
}

.room__bag-foot {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.room__state {
  display: flex;
  height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 24px;
}

.room__state-title {
  font-size: 15px;
  text-align: center;
}

.room__state-action {
  min-height: 40px;
  padding: 0 20px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ffb27a;
  font-size: 14px;
  line-height: 38px;
  text-align: center;
}
</style>

<style>
.room__placeholder {
  color: rgba(255, 255, 255, 0.7);
}
</style>
