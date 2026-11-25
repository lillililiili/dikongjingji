<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { droneArtwork, robotArtwork } from '@/app-shell/navigation/home-artwork'
import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { resolveVisibleHomeChannels } from '@/app-shell/navigation/home-channels'
import { HOME_ROOT_PATH } from '@/app-shell/navigation/root-tabs'
import { PLATFORM_PREVIEW_ENABLED } from '@/features/platform/application/platform-preview'

import { usePlayGames } from '../application/use-play-games'
import type { PlayGame } from '../domain/play-game'
import PlayPageHeader from './PlayPageHeader.vue'

const { games, state, load } = usePlayGames()
onShow(load)

// The study-tour channel is not built yet, so its entry only shows where that channel is visible.
const studyTourAvailable = resolveVisibleHomeChannels(PLATFORM_PREVIEW_ENABLED)
  .some((channel) => channel.code === 'ACTIVITY')

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: HOME_ROOT_PATH })
}

function openGame(game: PlayGame): void {
  uni.navigateTo({ url: `/pages/play/game?game=${encodeURIComponent(game.id)}` })
}
</script>

<template>
  <view class="play-games" data-testid="play-games">
    <PlayPageHeader title="趣玩挑战" @back="goBack" />

    <view class="play-games__content">
      <view class="play-games__intro">
        <text class="play-games__tag">PLAY &amp; EXPLORE</text>
        <view class="play-games__intro-title">
          <text>小小挑战</text>
          <text>大大好奇心</text>
        </view>
        <view class="play-games__intro-copy">
          <text>规划路线、收集星星</text>
          <text>和机器人、无人机一起闯关</text>
        </view>
        <image
          class="play-games__intro-image"
          :src="robotArtwork"
          mode="aspectFill"
        />
      </view>

      <text class="play-games__note">虚拟小游戏 · 无需登录 · 不连接真实设备</text>

      <text v-if="state === 'loading'" role="status">正在加载挑战…</text>
      <button v-else-if="state === 'error'" @click="load">挑战加载失败，点击重试</button>
      <text v-else-if="!games.length" role="status">暂无已发布的挑战</text>
      <button
        v-for="game in games"
        :key="game.id"
        :class="['play-games__card', `play-games__card--${game.tone.toLowerCase()}`]"
        :data-testid="`play-game-${game.id}`"
        @click="openGame(game)"
      >
        <view class="play-games__cover">
          <image class="play-games__cover-image" :src="game.avatar === 'ROBOT' ? robotArtwork : droneArtwork" mode="aspectFill" />
          <text class="play-games__tag play-games__tag--cover">{{ game.skillLabel }}</text>
        </view>
        <view class="play-games__body">
          <view class="play-games__body-copy">
            <text class="play-games__title">{{ game.title }}</text>
            <text class="play-games__subtitle">{{ game.subtitle }}</text>
          </view>
          <text class="play-games__pill">开始玩 →</text>
        </view>
      </button>

      <view v-if="studyTourAvailable" class="play-games__more">
        <text class="play-games__more-title">想把挑战搬到现实？</text>
        <text class="play-games__more-copy">在老师陪伴下，试试真正的机器人编程。</text>
        <button
          class="play-games__more-action"
          data-testid="play-study-tour"
          @click="openHomeChannel('ACTIVITY')"
        >
          看看机器人研学
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.play-games {
  min-height: 100vh;
  color: #202c42;
  background: #f7f8fc;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
}

button::after {
  border: 0;
}

.play-games__content {
  display: flex;
  padding: 16px 16px calc(24px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 14px;
}

.play-games__intro {
  position: relative;
  min-height: 155px;
  padding: 20px;
  overflow: hidden;
  border-radius: 22px;
  background: linear-gradient(125deg, #eee7fc, #edf7ff);
  box-sizing: border-box;
}

.play-games__tag {
  display: inline-flex;
  padding: 5px 9px;
  border-radius: 20px;
  color: #4479b3;
  background: #edf4ff;
  font-size: 11px;
  font-weight: 600;
  line-height: 15px;
}

.play-games__intro-title {
  position: relative;
  z-index: 1;
  display: flex;
  margin: 8px 0;
  flex-direction: column;
  font-size: 27px;
  font-weight: 700;
  line-height: 35px;
}

.play-games__intro-copy {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  color: #596a81;
  font-size: 12px;
  line-height: 20px;
}

.play-games__intro-image {
  position: absolute;
  right: -25px;
  bottom: -30px;
  width: 160px;
  height: 160px;
  border-radius: 50%;
  transform: rotate(-8deg);
}

.play-games__note {
  color: #748197;
  font-size: 11px;
  line-height: 18px;
}

.play-games__card {
  display: block;
  width: 100%;
  overflow: hidden;
  border: 1px solid #e6e9f4;
  border-radius: 20px;
  color: #293c54;
  background: #ffffff;
  box-sizing: border-box;
}

.play-games__cover {
  position: relative;
  height: 155px;
  overflow: hidden;
  background: #e9e4f7;
}

.play-games__cover-image {
  width: 100%;
  height: 100%;
}

.play-games__tag--cover {
  position: absolute;
  top: 12px;
  left: 12px;
  background: rgba(255, 255, 255, 0.88);
}

.play-games__body {
  display: flex;
  padding: 14px;
  align-items: center;
  gap: 10px;
}

.play-games__body-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.play-games__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.play-games__subtitle {
  color: #596a81;
  font-size: 12px;
  line-height: 18px;
}

.play-games__pill {
  padding: 10px 12px;
  flex: 0 0 auto;
  border-radius: 20px;
  color: #7351ad;
  background: #eee7ff;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.play-games__card--sky .play-games__pill {
  color: #247f9d;
  background: #e2f5ff;
}

.play-games__more {
  display: flex;
  padding: 16px;
  flex-direction: column;
  gap: 6px;
  border: 1px solid #ecf0f5;
  border-radius: 18px;
  background: #ffffff;
}

.play-games__more-title {
  font-size: 16px;
  font-weight: 700;
}

.play-games__more-copy {
  color: #596a81;
  font-size: 14px;
  line-height: 23px;
}

.play-games__more-action {
  display: flex;
  min-height: 46px;
  margin-top: 6px;
  align-items: center;
  justify-content: center;
  border-radius: 24px;
  color: #455d80;
  background: #edf2fa;
  font-size: 14px;
  font-weight: 600;
}
</style>
