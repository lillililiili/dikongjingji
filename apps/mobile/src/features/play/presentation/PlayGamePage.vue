<script setup lang="ts">
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

import {
  createPlaySession,
  describePlayOutcome,
  findPlayGame,
  formatPlayDuration,
  movePlaySession,
  resolvePlayCells,
  type PlayDirection,
  type PlayGame,
  type PlaySession,
} from '../domain/play-game'
import PlayPageHeader from './PlayPageHeader.vue'

const PLAY_GAMES_PATH = '/pages/play/index'
const CLOCK_TICK_MS = 300
// The robot starts as the little rover photo of the games page (GamePlay board); the drone keeps its drawing.
const AVATAR_URLS = {
  ROBOT: '/static/images/home/robot-rover.jpg',
  DRONE: '/static/images/play/avatar-drone.svg',
} as const
const PAD: readonly { direction: PlayDirection; label: string; glyph: string }[] = [
  { direction: 'UP', label: '向上', glyph: '↑' },
  { direction: 'LEFT', label: '向左', glyph: '←' },
  { direction: 'DOWN', label: '向下', glyph: '↓' },
  { direction: 'RIGHT', label: '向右', glyph: '→' },
]
const KEY_DIRECTIONS: Record<string, PlayDirection | undefined> = {
  ArrowUp: 'UP',
  ArrowDown: 'DOWN',
  ArrowLeft: 'LEFT',
  ArrowRight: 'RIGHT',
}

const game = ref<PlayGame>()
const loaded = ref(false)
const session = ref<PlaySession>(createPlaySession())
// The round clock starts with the first real move and stops at the win.
const startedAt = ref(0)
const finishedAt = ref(0)
const now = ref(0)
let clockTimer: ReturnType<typeof setInterval> | undefined

const cells = computed(() =>
  game.value ? resolvePlayCells(game.value, session.value) : [],
)
const statusText = computed(() =>
  game.value ? describePlayOutcome(game.value, session.value) : '',
)
const durationText = computed(() => {
  if (!startedAt.value) return formatPlayDuration(0)
  const end = finishedAt.value || now.value
  return formatPlayDuration(end - startedAt.value)
})
const starsText = computed(() =>
  `${session.value.collected.length} / ${game.value?.stars.length ?? 0}`,
)
const resultText = computed(() =>
  `${session.value.collected.length} 颗星星 · ${session.value.moves} 步 · ${durationText.value}`,
)

function stopClock(): void {
  if (clockTimer === undefined) return
  clearInterval(clockTimer)
  clockTimer = undefined
}

function move(direction: PlayDirection): void {
  if (!game.value || session.value.won) return
  const next = movePlaySession(game.value, session.value, direction)
  if (next.moves > 0 && !startedAt.value) {
    startedAt.value = Date.now()
    now.value = startedAt.value
    clockTimer = setInterval(() => {
      now.value = Date.now()
    }, CLOCK_TICK_MS)
  }
  if (next.won) {
    finishedAt.value = Date.now()
    stopClock()
  }
  session.value = next
  if (next.won) void revealResult()
}

// The result card renders under the pad, behind the sticky action bar on short screens.
async function revealResult(): Promise<void> {
  await nextTick()
  uni.pageScrollTo({ selector: '.play-game__success', duration: 200 })
}

function restart(): void {
  stopClock()
  startedAt.value = 0
  finishedAt.value = 0
  now.value = 0
  session.value = createPlaySession('RESTARTED')
}

function backToGames(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  // Opened by a direct link: there is no page to go back to, so the list replaces this one.
  uni.redirectTo({ url: PLAY_GAMES_PATH })
}

function handleKeydown(event: KeyboardEvent): void {
  const direction = KEY_DIRECTIONS[event.key]
  if (!direction) return
  event.preventDefault()
  move(direction)
}

onLoad((query) => {
  game.value = findPlayGame(query?.game)
  loaded.value = true
})

onMounted(() => {
  // Arrow keys only exist on H5; the App uses the on-screen pad.
  if (typeof document !== 'undefined') {
    document.addEventListener('keydown', handleKeydown)
  }
})

onBeforeUnmount(() => {
  if (typeof document !== 'undefined') {
    document.removeEventListener('keydown', handleKeydown)
  }
})

onUnload(stopClock)
</script>

<template>
  <view class="play-game" data-testid="play-game">
    <PlayPageHeader :title="game?.title ?? '内容暂不可用'" @back="backToGames" />

    <view
      v-if="loaded && !game"
      class="play-game__content"
      data-testid="play-game-missing"
    >
      <view class="play-game__card">
        <text class="play-game__card-title">没有找到这个关卡</text>
        <text class="play-game__card-copy">请从趣玩挑战重新选择。</text>
        <button
          class="play-game__button"
          data-testid="play-game-missing-back"
          @click="backToGames"
        >
          返回重新选择
        </button>
      </view>
    </view>

    <template v-else-if="game">
      <view class="play-game__content">
        <text class="play-game__tag">虚拟闯关 · 不连接真实设备</text>

        <view class="play-game__score">
          <view class="play-game__score-item">
            <text class="play-game__score-value" data-testid="play-score-stars">{{ starsText }}</text>
            <text class="play-game__score-label">收集星星</text>
          </view>
          <view class="play-game__score-item">
            <text class="play-game__score-value" data-testid="play-score-moves">{{ session.moves }}</text>
            <text class="play-game__score-label">移动步数</text>
          </view>
          <view class="play-game__score-item">
            <text class="play-game__score-value" data-testid="play-score-time">{{ durationText }}</text>
            <text class="play-game__score-label">本局用时</text>
          </view>
        </view>

        <view
          :class="['play-game__board', `play-game__board--${game.tone.toLowerCase()}`]"
          data-testid="play-board"
          aria-label="5 行 5 列游戏地图"
        >
          <view
            v-for="cell in cells"
            :key="cell.index"
            :class="[
              'play-game__cell',
              `play-game__cell--${cell.kind.toLowerCase()}`,
              { 'play-game__cell--goal': cell.isGoal },
            ]"
            :data-testid="`play-cell-${cell.index}`"
            :data-kind="cell.kind"
            :aria-label="cell.label"
          >
            <image
              v-if="cell.kind === 'AVATAR'"
              :class="['play-game__avatar', `play-game__avatar--${game.avatar.toLowerCase()}`]"
              :src="AVATAR_URLS[game.avatar]"
              :mode="game.avatar === 'ROBOT' ? 'aspectFill' : 'aspectFit'"
            />
            <text v-else-if="cell.kind === 'WALL'">◆</text>
            <text v-else-if="cell.kind === 'STAR'">★</text>
            <text v-else-if="cell.kind === 'GOAL'" class="play-game__goal-text">终点</text>
          </view>
        </view>

        <text class="play-game__status" data-testid="play-status" aria-live="polite">
          {{ statusText }}
        </text>

        <view class="play-game__pad" aria-label="方向控制">
          <button
            v-for="key in PAD"
            :key="key.direction"
            :class="['play-game__pad-key', `play-game__pad-key--${key.direction.toLowerCase()}`]"
            :data-testid="`play-move-${key.direction.toLowerCase()}`"
            :aria-label="key.label"
            :disabled="session.won"
            @click="move(key.direction)"
          >
            {{ key.glyph }}
          </button>
        </view>

        <view v-if="session.won" class="play-game__success" data-testid="play-success">
          <text class="play-game__success-title">挑战成功！</text>
          <text class="play-game__success-copy" data-testid="play-result">{{ resultText }}</text>
          <text class="play-game__success-copy">路线由你规划，星星都收集到了。</text>
        </view>

        <text class="play-game__note">点方向按钮移动。成绩只在本局显示，不上传。</text>
      </view>

      <view class="play-game__actions">
        <button
          class="play-game__button play-game__button--secondary"
          data-testid="play-change"
          @click="backToGames"
        >
          换个挑战
        </button>
        <button
          class="play-game__button play-game__button--violet"
          data-testid="play-restart"
          @click="restart"
        >
          重新开始
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.play-game {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
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
}

button::after {
  border: 0;
}

.play-game__content {
  display: flex;
  padding: 16px;
  flex: 1;
  flex-direction: column;
  gap: 14px;
}

.play-game__tag {
  align-self: flex-start;
  padding: 5px 9px;
  border-radius: 20px;
  color: #4479b3;
  background: #edf4ff;
  font-size: 11px;
  font-weight: 600;
  line-height: 15px;
}

.play-game__card {
  display: flex;
  padding: 16px;
  flex-direction: column;
  gap: 8px;
  border: 1px solid #ecf0f5;
  border-radius: 18px;
  background: #ffffff;
}

.play-game__card .play-game__button {
  flex: none;
}

.play-game__card-title {
  font-size: 18px;
  font-weight: 700;
}

.play-game__card-copy {
  color: #596a81;
  font-size: 14px;
  line-height: 23px;
}

.play-game__score {
  display: flex;
  padding: 12px 4px;
  border: 1px solid #e8edf5;
  border-radius: 16px;
  background: #ffffff;
}

.play-game__score-item {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.play-game__score-value {
  color: #6f4db1;
  font-size: 21px;
  font-weight: 700;
  line-height: 28px;
}

.play-game__score-label {
  color: #7a8599;
  font-size: 11px;
}

.play-game__board {
  display: grid;
  width: 100%;
  max-width: 280px;
  padding: 10px;
  align-self: center;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 5px;
  border-radius: 22px;
  background: #d9d2ee;
  box-sizing: border-box;
}

.play-game__board--sky {
  background: #cce9f3;
}

.play-game__cell {
  display: flex;
  aspect-ratio: 1;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  color: #edb528;
  background: #faf7ff;
  box-shadow: 0 3px 0 #bcb4d2;
  font-size: 26px;
  line-height: 1;
}

.play-game__cell--wall {
  color: #c7badf;
  background: #a99bc8;
  box-shadow: 0 3px 0 #8f80ae;
  font-size: 24px;
}

.play-game__board--sky .play-game__cell--wall {
  color: #add6e3;
  background: #77b0c3;
  box-shadow: 0 3px 0 #6097ac;
}

.play-game__cell--goal {
  background: #dff5e7;
}

.play-game__cell--avatar {
  outline: 3px solid #ffa759;
  outline-offset: -3px;
  background: #fff4dd;
}

.play-game__goal-text {
  color: #3d9673;
  font-size: 15px;
  font-weight: 700;
}

.play-game__avatar {
  width: 80%;
  height: 80%;
}

.play-game__avatar--robot {
  width: 88%;
  height: 88%;
  border-radius: 9px;
}

.play-game__status {
  min-height: 38px;
  color: #70549d;
  font-size: 12px;
  line-height: 19px;
  text-align: center;
}

.play-game__pad {
  display: grid;
  justify-content: center;
  grid-template-columns: repeat(3, 54px);
  grid-template-rows: repeat(2, 48px);
  gap: 6px;
}

.play-game__pad-key {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #dcd6eb;
  border-radius: 14px;
  color: #7052a6;
  background: #ffffff;
  box-shadow: 0 3px 0 #e5e0ed;
  font-size: 23px;
}

.play-game__pad-key--up {
  grid-column: 2;
  grid-row: 1;
}

.play-game__pad-key--left {
  grid-column: 1;
  grid-row: 2;
}

.play-game__pad-key--down {
  grid-column: 2;
  grid-row: 2;
}

.play-game__pad-key--right {
  grid-column: 3;
  grid-row: 2;
}

/* uni-app greys the label of a disabled button to 30% black; keep the pad readable but dimmed. */
.play-game__pad-key[disabled] {
  color: #7052a6;
  background: #ffffff;
  opacity: 0.45;
}

.play-game__success {
  display: flex;
  scroll-margin-top: 65px;
  padding: 20px;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  border: 1px solid #cde9d5;
  border-radius: 18px;
  background: linear-gradient(130deg, #e4f8ec, #f9f7db);
  text-align: center;
}

.play-game__success-title {
  color: #29835f;
  font-size: 18px;
  font-weight: 700;
}

.play-game__success-copy {
  color: #596a81;
  font-size: 13px;
  line-height: 21px;
}

.play-game__note {
  color: #748197;
  font-size: 11px;
  line-height: 18px;
}

.play-game__actions {
  position: sticky;
  bottom: 0;
  z-index: 4;
  display: flex;
  padding: 10px 16px calc(12px + env(safe-area-inset-bottom));
  gap: 10px;
  border-top: 1px solid #e8edf5;
  background: rgba(255, 255, 255, 0.93);
}

.play-game__button {
  display: flex;
  min-height: 46px;
  padding: 0 18px;
  flex: 1;
  align-items: center;
  justify-content: center;
  border-radius: 24px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 600;
}

.play-game__button--secondary {
  color: #455d80;
  background: #edf2fa;
}

.play-game__button--violet {
  background: #8058c5;
}

@media (min-width: 410px) {
  .play-game__board {
    max-width: 310px;
  }
}
</style>
