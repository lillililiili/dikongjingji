<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { ApiRequestError } from '@/shared/http/public-api-request'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  formatLiveCount,
  formatLiveDate,
  formatLiveDuration,
  formatLiveShort,
  hostAvatarColor,
  hostInitial,
  splitLiveChannel,
} from '../../application/live-view'
import { liveApi } from '../../data/live-api-client'
import { resolveLivePreviewCover } from '../../data/preview/live-preview-covers'
import type { LiveRoomCard } from '../../domain/live'
import { buildLiveNoticeUrl, liveCardUrl } from '../../navigation/live-routes'

// 发现 · 直播 (board Discover, channel 直播): 正在直播 as a two-column grid, 直播预告 as rows with
// 提醒我, 往期回看 as rows. Everything is read from the live API in the App's city.
const props = defineProps<{ cityCode: string }>()
const emit = defineEmits<{
  open: [url: string]
  // Not signed in: sign in, then land on this room's preview page to set the reminder there.
  login: [returnUrl: string]
}>()

const status = ref<'loading' | 'ready' | 'error'>('loading')
const cards = ref<LiveRoomCard[]>([])
const reminded = ref<Record<string, boolean>>({})
const busy = ref<Record<string, boolean>>({})
let revision = 0

const sections = computed(() => splitLiveChannel(cards.value))
const empty = computed(() => status.value === 'ready'
  && !sections.value.live.length && !sections.value.scheduled.length && !sections.value.replays.length)
const columns = computed(() => [0, 1].map((column) => sections.value.live.filter((_, index) => index % 2 === column)))

function picture(card: LiveRoomCard): string | null {
  return card.coverUrl ?? resolveLivePreviewCover(card.roomId)?.url ?? null
}

async function loadReminders(scheduled: LiveRoomCard[], requested: number): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token || !scheduled.length) return
  const states = await Promise.allSettled(scheduled.map((card) => liveApi.loadViewerState(token, card.roomId)))
  if (requested !== revision) return
  const next: Record<string, boolean> = {}
  states.forEach((state, index) => {
    if (state.status === 'fulfilled') next[scheduled[index].roomId] = state.value.reminded
  })
  reminded.value = next
}

async function refresh(): Promise<void> {
  if (!props.cityCode) return
  const requested = ++revision
  if (!cards.value.length) status.value = 'loading'
  try {
    const page = await liveApi.loadRooms(props.cityCode, null, 1, 20)
    if (requested !== revision) return
    cards.value = page.items
    status.value = 'ready'
    void loadReminders(splitLiveChannel(page.items).scheduled, requested)
  } catch {
    if (requested !== revision) return
    status.value = 'error'
  }
}

async function toggleRemind(card: LiveRoomCard): Promise<void> {
  const token = getBookingAuthContext().getAccessToken()
  if (!token) {
    emit('login', buildLiveNoticeUrl(card.roomId, props.cityCode))
    return
  }
  if (busy.value[card.roomId]) return
  busy.value = { ...busy.value, [card.roomId]: true }
  const on = !reminded.value[card.roomId]
  try {
    const state = await liveApi.setReminder(token, card.roomId, on)
    reminded.value = { ...reminded.value, [card.roomId]: state.reminded }
    uni.showToast({ title: state.reminded ? '已设提醒，开播时会在消息里通知你' : '已取消提醒', icon: 'none' })
  } catch (error) {
    if (error instanceof ApiRequestError && error.status === 401) {
      emit('login', buildLiveNoticeUrl(card.roomId, props.cityCode))
    } else if (error instanceof ApiRequestError && error.code === 'LIVE_ROOM_NOT_SCHEDULED') {
      uni.showToast({ title: '已经开播了，进直播间看看吧', icon: 'none' })
      void refresh()
    } else {
      uni.showToast({ title: '没有设置成功，请再试一次', icon: 'none' })
    }
  } finally {
    busy.value = { ...busy.value, [card.roomId]: false }
  }
}

watch(() => props.cityCode, () => {
  cards.value = []
  reminded.value = {}
  void refresh()
}, { immediate: true })

defineExpose({ refresh })
</script>

<template>
  <view class="live-channel" data-testid="live-channel" :data-state="status">
    <template v-if="status === 'loading'">
      <view v-for="index in 2" :key="index" class="live-channel__skeleton" data-testid="live-channel-loading" />
    </template>

    <view v-else-if="status === 'error'" class="live-channel__state" data-testid="live-channel-error">
      <text class="live-channel__state-title">直播加载失败</text>
      <text class="live-channel__state-copy">请检查网络后重试</text>
      <button class="live-channel__state-action" data-testid="live-channel-retry" @click="refresh">重新加载</button>
    </view>

    <view v-else-if="empty" class="live-channel__state" data-testid="live-channel-empty">
      <LineIcon name="play" :size="36" color="#b5bac1" />
      <text class="live-channel__state-title">这个城市还没有直播</text>
      <text class="live-channel__state-copy">换个城市或频道看看</text>
    </view>

    <template v-else>
      <template v-if="sections.live.length">
        <view class="live-channel__section">
          <view class="live-channel__section-bar" />
          <text class="live-channel__section-title">正在直播</text>
          <text class="live-channel__section-sub">{{ sections.live.length }} 场</text>
        </view>
        <view class="live-channel__grid">
          <view v-for="(column, columnIndex) in columns" :key="columnIndex" class="live-channel__column">
            <button
              v-for="card in column"
              :key="card.roomId"
              class="live-channel__card"
              :data-testid="`live-card-${card.roomId}`"
              @click="emit('open', liveCardUrl(card, cityCode))"
            >
              <view class="live-channel__cover">
                <image v-if="picture(card)" class="live-channel__image" :src="picture(card) ?? ''" mode="aspectFill" />
                <view class="live-channel__live-tag"><view class="live-channel__live-dot" />直播中</view>
                <text class="live-channel__corner">{{ formatLiveCount(card.viewerCount) }} 观看</text>
              </view>
              <text class="live-channel__card-title">{{ card.title }}</text>
              <view class="live-channel__card-meta">
                <text class="live-channel__avatar" :style="{ background: hostAvatarColor(card.host.name) }" aria-hidden="true">
                  {{ hostInitial(card.host.name) }}
                </text>
                <text class="live-channel__card-host">{{ card.host.name }}</text>
                <text class="live-channel__card-enter">进直播间</text>
              </view>
            </button>
          </view>
        </view>
      </template>

      <template v-if="sections.scheduled.length">
        <view class="live-channel__section">
          <view class="live-channel__section-bar" />
          <text class="live-channel__section-title">直播预告</text>
          <text class="live-channel__section-sub">可设提醒</text>
        </view>
        <view
          v-for="card in sections.scheduled"
          :key="card.roomId"
          class="live-channel__row"
          :data-testid="`live-notice-${card.roomId}`"
        >
          <button class="live-channel__row-link" @click="emit('open', liveCardUrl(card, cityCode))">
            <view class="live-channel__thumb">
              <image v-if="picture(card)" class="live-channel__image" :src="picture(card) ?? ''" mode="aspectFill" />
              <text class="live-channel__thumb-tag live-channel__thumb-tag--notice">预告</text>
            </view>
            <view class="live-channel__row-copy">
              <text class="live-channel__row-title">{{ card.title }}</text>
              <text class="live-channel__row-sub">{{ card.host.name }} · App 内直播</text>
              <view class="live-channel__row-meta">
                <text class="live-channel__row-when">{{ formatLiveShort(card.scheduledAt) }}</text>
              </view>
            </view>
          </button>
          <button
            :class="['live-channel__remind', { 'live-channel__remind--on': reminded[card.roomId] }]"
            :aria-pressed="Boolean(reminded[card.roomId])"
            :data-testid="`live-remind-${card.roomId}`"
            @click="toggleRemind(card)"
          >
            {{ reminded[card.roomId] ? '已设提醒' : '提醒我' }}
          </button>
        </view>
      </template>

      <template v-if="sections.replays.length">
        <view class="live-channel__section">
          <view class="live-channel__section-bar" />
          <text class="live-channel__section-title">往期回看</text>
        </view>
        <button
          v-for="card in sections.replays"
          :key="card.roomId"
          class="live-channel__row live-channel__row-link"
          :data-testid="`live-replay-${card.roomId}`"
          @click="emit('open', liveCardUrl(card, cityCode))"
        >
          <view class="live-channel__thumb">
            <image v-if="picture(card)" class="live-channel__image" :src="picture(card) ?? ''" mode="aspectFill" />
            <text class="live-channel__thumb-tag live-channel__thumb-tag--replay">回看</text>
          </view>
          <view class="live-channel__row-copy">
            <text class="live-channel__row-title">{{ card.title }}</text>
            <text class="live-channel__row-sub">{{ card.host.name }}{{ formatLiveDuration(card.startedAt, card.endedAt) ? ` · ${formatLiveDuration(card.startedAt, card.endedAt)}` : '' }}</text>
            <view class="live-channel__row-meta">
              <text class="live-channel__row-when">{{ formatLiveDate(card.endedAt) }}</text>
              <text class="live-channel__row-note">已结束</text>
            </view>
          </view>
        </button>
      </template>

      <text class="live-channel__more">没有更多内容了</text>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Discover, channel 直播 (390 wide). */
.live-channel {
  display: flex;
  flex-direction: column;
  gap: 10px;
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

.live-channel__section {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}

.live-channel__section-bar {
  width: 4px;
  height: 16px;
  border-radius: 2px;
  background: #ff6a00;
}

.live-channel__section-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.live-channel__section-sub {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.live-channel__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
  gap: 10px;
}

.live-channel__column {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 10px;
}

.live-channel__card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.live-channel__cover {
  position: relative;
  height: 200px;
  background: #202c42;
}

.live-channel__image {
  display: block;
  width: 100%;
  height: 100%;
}

.live-channel__live-tag {
  position: absolute;
  top: 8px;
  left: 8px;
  display: flex;
  align-items: center;
  gap: 4px;
  height: 18px;
  padding: 0 6px;
  border-radius: 6px;
  color: #ffffff;
  background: #d64f67;
  font-size: 11px;
  line-height: 18px;
}

.live-channel__live-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #ffffff;
}

.live-channel__corner {
  position: absolute;
  right: 8px;
  bottom: 8px;
  height: 20px;
  padding: 0 7px;
  border-radius: 999px;
  color: #ffffff;
  background: rgba(0, 0, 0, 0.55);
  font-size: 11px;
  line-height: 20px;
}

.live-channel__card-title {
  padding: 8px 10px 0;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.live-channel__card-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px 10px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.live-channel__avatar {
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

.live-channel__card-host {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.live-channel__card-enter {
  flex-shrink: 0;
  white-space: nowrap;
}

.live-channel__row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.live-channel__row-link {
  display: flex;
  flex: 1;
  min-width: 0;
  gap: 12px;
}

.live-channel__thumb {
  position: relative;
  flex-shrink: 0;
  width: 104px;
  height: 78px;
  overflow: hidden;
  border-radius: 10px;
  background: #eef0f3;
}

.live-channel__thumb-tag {
  position: absolute;
  top: 4px;
  left: 4px;
  padding: 0 5px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 500;
  line-height: 17px;
  white-space: nowrap;
}

.live-channel__thumb-tag--notice {
  color: #6a4fe0;
  background: #eee7ff;
}

.live-channel__thumb-tag--replay {
  color: #ffffff;
  background: rgba(0, 0, 0, 0.5);
}

.live-channel__row-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.live-channel__row-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.live-channel__row-sub {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.live-channel__row-meta {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: auto;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.live-channel__row-when {
  color: #536178;
  font-size: 14px;
  font-weight: 700;
}

.live-channel__row-note {
  color: #738096;
}

.live-channel__remind {
  flex-shrink: 0;
  height: 36px;
  padding: 0 12px;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 13px;
  font-weight: 500;
  line-height: 33px;
  white-space: nowrap;
}

.live-channel__remind--on {
  border-color: #ffd8b8;
  background: #fff0e3;
}

.live-channel__skeleton {
  height: 132px;
  border-radius: 12px;
  background: linear-gradient(90deg, #eef1f6 25%, #f6f8fb 50%, #eef1f6 75%);
}

.live-channel__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 56px 24px;
  text-align: center;
}

.live-channel__state-title {
  color: #202c42;
  font-size: 16px;
  font-weight: 600;
}

.live-channel__state-copy {
  color: #738096;
  font-size: 13px;
}

.live-channel__state-action {
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

.live-channel__more {
  padding: 6px 0 2px;
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}
</style>
