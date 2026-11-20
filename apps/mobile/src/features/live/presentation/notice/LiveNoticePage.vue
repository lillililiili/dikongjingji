<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { contentLinkedApis } from '@/features/content/data/content-linked-apis'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { ApiRequestError } from '@/shared/http/public-api-request'
import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { openPartnerReplay } from '../../application/live-external'
import { liveItemPrice, resolveLiveItems, type LiveItemView } from '../../application/live-items'
import { formatLiveDay, formatLiveShort, hostInitial, noticeView } from '../../application/live-view'
import { liveApi } from '../../data/live-api-client'
import { resolveLivePreviewCover } from '../../data/preview/live-preview-covers'
import type { LiveRoom } from '../../domain/live'
import { buildLiveNoticeUrl, buildLiveRoomUrl } from '../../navigation/live-routes'

// 直播预告 (board LiveNotice): the time, who speaks, how to watch (预告 → 直播 → 回看), what the
// session covers and what it mentions. A preview offers 提醒我; a live room 进直播间; a finished one
// with a replay 去合作平台回看 (outside the App, after a confirmation).
const props = defineProps<{ roomId: string; cityCode: string }>()
const emit = defineEmits<{
  back: []
  open: [url: string]
  login: [returnUrl: string]
  consult: [organizationId: string]
  hostPage: [organizationId: string]
}>()

const status = ref<'loading' | 'ready' | 'missing' | 'error'>('loading')
const room = ref<LiveRoom | null>(null)
const items = ref<LiveItemView[]>([])
const reminded = ref(false)
const busy = ref(false)
const hint = ref<'REMIND' | 'EXTERNAL' | null>(null)
const externalOpen = ref(false)
const shareTarget = ref<ShareTarget | null>(null)
const now = ref(new Date())
let revision = 0

const view = computed(() => (room.value ? noticeView(room.value, now.value) : null))
const picture = computed(() => (room.value
  ? room.value.coverUrl ?? resolveLivePreviewCover(room.value.roomId)?.url ?? null
  : null))
const pageUrl = computed(() => buildLiveNoticeUrl(props.roomId, props.cityCode))

async function load(): Promise<void> {
  const requested = ++revision
  now.value = new Date()
  if (!room.value) status.value = 'loading'
  try {
    const loaded = await liveApi.loadRoom(props.roomId, props.cityCode)
    if (requested !== revision) return
    room.value = loaded
    status.value = 'ready'
    void loadReminder(loaded, requested)
    items.value = await resolveLiveItems(loaded.items, props.cityCode, contentLinkedApis)
  } catch (error) {
    if (requested !== revision) return
    status.value = error instanceof ApiRequestError && error.status === 404 ? 'missing' : 'error'
  }
}

async function loadReminder(loaded: LiveRoom, requested: number): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token || loaded.status !== 'SCHEDULED') return
  try {
    const state = await liveApi.loadViewerState(token, loaded.roomId)
    if (requested === revision) reminded.value = state.reminded
  } catch {
    // The button stays 提醒我; tapping it says what is wrong.
  }
}

async function toggleRemind(): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token) {
    emit('login', pageUrl.value)
    return
  }
  if (busy.value) return
  busy.value = true
  try {
    const state = await liveApi.setReminder(token, props.roomId, !reminded.value)
    reminded.value = state.reminded
    hint.value = state.reminded ? 'REMIND' : null
  } catch (error) {
    if (error instanceof ApiRequestError && error.status === 401) emit('login', pageUrl.value)
    else if (error instanceof ApiRequestError && error.code === 'LIVE_ROOM_NOT_SCHEDULED') void load()
    else uni.showToast({ title: '没有设置成功，请再试一次', icon: 'none' })
  } finally {
    busy.value = false
  }
}

function act(): void {
  const action = view.value?.action
  if (action === 'REMIND') void toggleRemind()
  else if (action === 'ENTER') emit('open', buildLiveRoomUrl(props.roomId, props.cityCode))
  else if (action === 'REPLAY') externalOpen.value = true
}

function leaveForReplay(): void {
  externalOpen.value = false
  if (room.value?.replayUrl && openPartnerReplay(room.value.replayUrl)) hint.value = 'EXTERNAL'
}

function openShare(): void {
  if (!room.value) return
  shareTarget.value = {
    subject: '直播',
    title: room.value.title,
    subtitle: `${formatLiveShort(room.value.scheduledAt)} · ${room.value.host.name}`,
    owner: room.value.host.name,
    imageUrl: picture.value,
    path: pageUrl.value,
  }
}

// Icons copied from the board LiveNotice.
const ICONS = {
  back: 'M15 5l-7 7 7 7',
  share: 'M12 15V4M8 8l4-4 4 4M5 13v7h14v-7',
  calendar: 'M5 5h14v15H5zM5 9h14M9 3v4M15 3v4',
  check: 'M5 12l5 5 9-10',
  chat: 'M4 5h16v11H9l-5 4z',
  bell: 'M6 16v-5a6 6 0 1 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0',
  info: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01',
  chevron: 'M9 5l7 7-7 7',
}

watch(() => [props.roomId, props.cityCode], () => {
  room.value = null
  items.value = []
  reminded.value = false
  hint.value = null
  void load()
}, { immediate: true })

defineExpose({ load })
</script>

<template>
  <view class="notice" data-testid="live-notice" :data-state="status" :data-status="room?.status">
    <view v-if="status === 'loading'" class="notice__state" aria-label="正在加载">
      <view class="notice__skeleton notice__skeleton--hero" />
      <view class="notice__skeleton" />
    </view>

    <view v-else-if="status === 'missing' || status === 'error'" class="notice__state" :data-testid="`live-notice-${status}`">
      <text class="notice__state-title">{{ status === 'missing' ? '这场直播不存在，或不在当前城市' : '直播没有加载出来，请稍后重试' }}</text>
      <button v-if="status === 'error'" class="notice__state-action" data-testid="live-notice-retry" @click="load">重新加载</button>
      <button class="notice__state-action" @click="emit('back')">返回</button>
    </view>

    <template v-else-if="room && view">
      <view class="notice__scroll">
        <view class="notice__hero">
          <image v-if="picture" class="notice__hero-image" :src="picture" mode="aspectFill" :aria-label="room.title" />
          <view class="notice__hero-bar">
            <button class="notice__round" aria-label="返回" data-testid="live-notice-back" @click="emit('back')">
              <view class="notice__round-face"><LineIcon :d="ICONS.back" :size="20" color="#fff" :stroke-width="2" /></view>
            </button>
            <button class="notice__round notice__round--right" aria-label="分享" data-testid="live-notice-share" @click="openShare">
              <view class="notice__round-face"><LineIcon :d="ICONS.share" :size="20" color="#fff" /></view>
            </button>
          </view>
          <text class="notice__tag" :style="{ background: view.tag.background }" data-testid="live-notice-tag">{{ view.tag.text }}</text>
        </view>

        <view class="notice__body">
          <view class="notice__card notice__card--head">
            <text class="notice__title" role="heading" aria-level="1">{{ room.title }}</text>
            <text v-if="room.subtitle" class="notice__subtitle">{{ room.subtitle }}</text>
            <view class="notice__time">
              <LineIcon :d="ICONS.calendar" :size="18" color="#536178" />
              <text class="notice__time-text">{{ formatLiveDay(room.scheduledAt) }}</text>
              <text class="notice__chip" :style="{ background: view.chip.background, color: view.chip.color }" data-testid="live-notice-chip">{{ view.chip.text }}</text>
            </view>
            <button class="notice__speaker" data-testid="live-notice-host" @click="emit('hostPage', room.host.organizationId)">
              <text class="notice__speaker-avatar">{{ hostInitial(room.speakerName ?? room.host.name) }}</text>
              <view class="notice__speaker-copy">
                <text class="notice__speaker-name">{{ room.speakerName ? `主讲 ${room.speakerName}` : room.host.name }}</text>
                <text class="notice__speaker-sub">{{ room.speakerName ? [room.host.name, room.speakerTitle].filter(Boolean).join(' · ') : '主办方' }}</text>
              </view>
              <text class="notice__speaker-home">主页</text>
            </button>
          </view>

          <view class="notice__card">
            <view class="notice__how">
              <text class="notice__h2" role="heading" aria-level="2">怎么看</text>
              <text v-if="view.platformName" class="notice__how-platform">{{ view.platformName }}</text>
            </view>
            <view class="notice__steps" role="list" aria-label="直播阶段">
              <view v-for="step in view.steps" :key="step.n" :class="['notice__step', `notice__step--${step.state}`]" role="listitem">
                <view :class="['notice__step-line', 'notice__step-line--left', { 'notice__step-line--hidden': step.n === 1, 'notice__step-line--done': step.n <= view.stage + 1 && step.n > 1 }]" />
                <view :class="['notice__step-line', 'notice__step-line--right', { 'notice__step-line--hidden': step.n === 3, 'notice__step-line--done': step.n < view.stage + 1 }]" />
                <text class="notice__step-n">{{ step.n }}</text>
                <text class="notice__step-title">{{ step.title }}</text>
                <text class="notice__step-note">{{ step.note }}</text>
              </view>
            </view>
            <text class="notice__watch-note" data-testid="live-notice-note">{{ view.watchNote }}</text>
          </view>

          <view v-if="room.topics.length" class="notice__card notice__card--topics">
            <text class="notice__h2" role="heading" aria-level="2">这场讲什么</text>
            <view v-for="topic in room.topics" :key="topic" class="notice__topic">
              <LineIcon :d="ICONS.check" :size="16" color="#159a70" :stroke-width="3" />
              <text>{{ topic }}</text>
            </view>
          </view>

          <template v-if="items.length">
            <text class="notice__h2 notice__h2--outside" role="heading" aria-level="2">内容中提到</text>
            <button
              v-for="item in items"
              :key="item.key"
              class="notice__item"
              :data-testid="`live-notice-item-${item.seq}`"
              @click="emit('open', item.url)"
            >
              <view :class="['notice__item-thumb', { 'notice__item-thumb--contain': item.imageContain || item.imagePadded }]">
                <image v-if="item.imageUrl" class="notice__item-image" :src="item.imageUrl" :mode="item.imageContain || item.imagePadded ? 'aspectFit' : 'aspectFill'" />
                <text v-else class="notice__item-initial" :style="{ color: item.tagTone.color }">{{ item.tag }}</text>
              </view>
              <view class="notice__item-copy">
                <view class="notice__item-line">
                  <text class="notice__item-tag" :style="{ background: item.tagTone.background, color: item.tagTone.color }">{{ item.tag }}</text>
                  <text class="notice__item-title">{{ item.title }}</text>
                </view>
                <text v-if="item.price" class="notice__item-price">{{ liveItemPrice(item) }}</text>
              </view>
              <text :class="['notice__item-action', { 'notice__item-action--primary': item.primary }]">{{ item.actionLabel }}</text>
            </button>
          </template>
        </view>
      </view>

      <view v-if="hint === 'REMIND' && reminded" class="notice__hint notice__hint--ok" role="status" data-testid="live-notice-hint">已设提醒，开播时会在消息里通知你</view>
      <view v-else-if="hint === 'EXTERNAL'" class="notice__hint notice__hint--out" role="status">已在合作平台打开回看，回到本 App 可继续浏览（合作平台在 App 外）</view>

      <view class="notice__bar">
        <button class="notice__consult" data-testid="live-notice-consult" @click="emit('consult', room.host.organizationId)">
          <LineIcon :d="ICONS.chat" :size="22" color="#536178" />
          <text>咨询</text>
        </button>
        <button
          v-if="view.action === 'REMIND'"
          :class="['notice__main', { 'notice__main--on': reminded }]"
          :aria-pressed="reminded"
          :disabled="busy"
          data-testid="live-notice-remind"
          @click="toggleRemind"
        >
          <LineIcon :d="reminded ? ICONS.check : ICONS.bell" :size="18" :color="reminded ? '#ba470b' : '#fff'" :stroke-width="reminded ? 2.2 : 2" />
          <text>{{ reminded ? '已设提醒' : '提醒我' }}</text>
        </button>
        <button v-else-if="view.action" class="notice__main" data-testid="live-notice-action" @click="act">
          <text>{{ view.actionLabel }}</text>
        </button>
      </view>

      <template v-if="externalOpen">
        <view class="notice__mask" aria-label="关闭" @click="externalOpen = false" />
        <view class="notice__sheet" role="dialog" aria-label="打开合作平台" data-testid="live-notice-external">
          <view class="notice__sheet-head">
            <text class="notice__sheet-title">离开本 App，打开合作平台</text>
            <text class="notice__sheet-sub">{{ room.title }}{{ room.replayPlatformName ? ` · ${room.replayPlatformName}` : '' }}</text>
          </view>
          <view class="notice__sheet-note">
            <LineIcon :d="ICONS.info" :size="16" color="#738096" :stroke-width="2" />
            <text>观看和回看都在合作平台完成，账号和隐私规则由对方负责</text>
          </view>
          <view class="notice__sheet-actions">
            <button class="notice__sheet-cancel" @click="externalOpen = false">取消</button>
            <button class="notice__sheet-go" data-testid="live-notice-external-go" @click="leaveForReplay">继续打开</button>
          </view>
        </view>
      </template>
      <ShareSheet :target="shareTarget" @close="shareTarget = null" />
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board LiveNotice (390 wide). */
.notice {
  min-height: 100vh;
  color: #202c42;
  background: #f5f7fc;
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

.notice__scroll {
  padding-bottom: calc(92px + env(safe-area-inset-bottom));
}

.notice__hero {
  position: relative;
  height: 236px;
  background: #eef2f8;
}

.notice__hero-image {
  display: block;
  width: 100%;
  height: 236px;
}

.notice__hero-bar {
  position: absolute;
  top: calc(var(--status-bar-height) + 4px);
  right: 8px;
  left: 8px;
  display: flex;
  align-items: center;
}

.notice__round {
  display: flex;
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.notice__round--right {
  margin-left: auto;
}

.notice__round-face {
  display: flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
}

.notice__tag {
  position: absolute;
  bottom: 24px;
  left: 16px;
  padding: 2px 8px;
  border-radius: 6px;
  color: #ffffff;
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.notice__body {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: -14px;
  padding: 0 16px 16px;
}

.notice__card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.notice__card--head {
  gap: 0;
  padding: 14px 14px 6px;
}

.notice__card--topics {
  gap: 8px;
}

.notice__title {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.notice__subtitle {
  margin-top: 2px;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.notice__time {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f5f7fc;
}

.notice__time-text {
  flex: 1;
  min-width: 0;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  white-space: nowrap;
}

.notice__chip {
  flex-shrink: 0;
  padding: 1px 8px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  line-height: 20px;
  white-space: nowrap;
}

.notice__speaker {
  display: flex;
  min-height: 60px;
  align-items: center;
  gap: 10px;
}

.notice__speaker-avatar {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  color: #ff6a00;
  background: #fff0e3;
  font-size: 15px;
  font-weight: 700;
  line-height: 36px;
  text-align: center;
}

.notice__speaker-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.notice__speaker-name {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  white-space: nowrap;
}

.notice__speaker-sub {
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notice__speaker-home {
  flex-shrink: 0;
  height: 30px;
  padding: 0 14px;
  box-sizing: border-box;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  font-size: 13px;
  font-weight: 500;
  line-height: 28px;
  white-space: nowrap;
}

.notice__how {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.notice__h2 {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.notice__h2--outside {
  margin-top: 4px;
}

.notice__how-platform {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.notice__steps {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.notice__step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.notice__step-line {
  position: absolute;
  top: 11px;
  height: 2px;
  background: #e5ebf3;
}

.notice__step-line--left {
  right: 50%;
  left: 0;
}

.notice__step-line--right {
  right: 0;
  left: 50%;
}

.notice__step-line--done {
  background: #ffd8b8;
}

.notice__step-line--hidden {
  background: transparent;
}

.notice__step-n {
  position: relative;
  width: 24px;
  height: 24px;
  margin-bottom: 6px;
  border-radius: 50%;
  color: #738096;
  background: #eef2f8;
  font-size: 12px;
  font-weight: 700;
  line-height: 24px;
  text-align: center;
}

.notice__step--past .notice__step-n {
  color: #ba470b;
  background: #fff0e3;
}

.notice__step--now .notice__step-n {
  color: #ffffff;
  background: #ff6a00;
}

.notice__step-title {
  color: #202c42;
  font-size: 13px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.notice__step--now .notice__step-title {
  color: #ba470b;
  font-weight: 700;
}

.notice__step-note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.notice__watch-note {
  color: #627187;
  font-size: 12px;
  line-height: 18px;
}

.notice__topic {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: #536178;
  font-size: 14px;
  line-height: 21px;
}

.notice__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.notice__item-thumb {
  display: flex;
  flex-shrink: 0;
  width: 60px;
  height: 60px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 10px;
  background: #eef2f8;
}

.notice__item-thumb--contain {
  padding: 10px;
  box-sizing: border-box;
  background: #fff0e3;
}

.notice__item-image {
  width: 100%;
  height: 100%;
}

.notice__item-initial {
  font-size: 13px;
  font-weight: 700;
}

.notice__item-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
}

.notice__item-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.notice__item-tag {
  flex-shrink: 0;
  padding: 0 5px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.notice__item-title {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notice__item-price {
  color: #e85400;
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
  white-space: nowrap;
}

.notice__item-action {
  flex-shrink: 0;
  height: 36px;
  padding: 0 14px;
  box-sizing: border-box;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  font-size: 14px;
  font-weight: 500;
  line-height: 34px;
  white-space: nowrap;
}

.notice__item-action--primary {
  border-color: #ff6a00;
  color: #ffffff;
  background: #ff6a00;
}

.notice__hint {
  position: fixed;
  z-index: 3;
  right: 16px;
  bottom: calc(92px + env(safe-area-inset-bottom));
  left: 16px;
  padding: 8px 10px;
  box-sizing: border-box;
  border-radius: 10px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  font-size: 12px;
  line-height: 18px;
}

.notice__hint--ok {
  border: 1px solid #c9eed9;
  color: #11804a;
  background: #e2f6ed;
}

.notice__hint--out {
  border: 1px solid #ffd8b8;
  color: #ba470b;
  background: #fff0e3;
}

.notice__bar {
  position: fixed;
  z-index: 2;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.notice__consult {
  display: flex;
  flex-shrink: 0;
  width: 46px;
  height: 46px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: #536178;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

.notice__main {
  display: flex;
  flex: 1 1 0;
  min-width: 0;
  height: 46px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  white-space: nowrap;
}

.notice__main--on {
  box-sizing: border-box;
  border: 1.5px solid #ffd8b8;
  color: #ba470b;
  background: #fff0e3;
}

.notice__mask {
  position: fixed;
  z-index: 4;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.notice__sheet {
  position: fixed;
  z-index: 5;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  background: #ffffff;
}

.notice__sheet-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.notice__sheet-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.notice__sheet-sub {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.notice__sheet-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 10px;
  color: #536178;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 18px;
}

.notice__sheet-actions {
  display: flex;
  gap: 10px;
}

.notice__sheet-cancel,
.notice__sheet-go {
  flex: 1 1 0;
  height: 44px;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
  text-align: center;
}

.notice__sheet-cancel {
  box-sizing: border-box;
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
  line-height: 42px;
}

.notice__sheet-go {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.notice__state {
  display: flex;
  min-height: 60vh;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 24px;
}

.notice__state-title {
  color: #202c42;
  font-size: 15px;
  text-align: center;
}

.notice__state-action {
  min-height: 40px;
  padding: 0 20px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  font-size: 14px;
  line-height: 38px;
  text-align: center;
}

.notice__skeleton {
  width: 100%;
  height: 120px;
  border-radius: 14px;
  background: linear-gradient(90deg, #eef1f6 25%, #f6f8fb 50%, #eef1f6 75%);
}

.notice__skeleton--hero {
  height: 236px;
  border-radius: 0;
}
</style>
