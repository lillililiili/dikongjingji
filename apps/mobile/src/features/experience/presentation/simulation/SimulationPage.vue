<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  buildSimulationResult,
  createSimulationSession,
  isTaskFinished,
  levelGoal,
  levelLabel,
  nextLevel,
  runCurrentLevel,
  withBlockAdded,
  withBlockRemoved,
  withProgramCleared,
  type SimulationSession,
} from '../../application/simulation-session'
import { uploadLocalResult } from '../../application/simulation-sync'
import { experienceApi } from '../../data/experience-api-client'
import { experienceRecordApi } from '../../data/experience-record-api-client'
import { localSimulationResults } from '../../data/local-simulation-results'
import { simulationScenePicture } from '../../data/preview/simulation-preview-pictures'
import type { ExperienceItemDetail } from '../../domain/experience-record'
import {
  BLOCK_COLOURS,
  BLOCK_TEXT,
  DRONE_CEILING_DM,
  DRONE_LEVELS,
  MAX_PROGRAM_BLOCKS,
  PALETTE,
  ROBOT_GRID_SIZE,
  ROBOT_LEVELS,
  formatMetres,
  formatSimulationClock,
  type Heading,
  type SimulationBlock,
} from '../../domain/simulation'
import type { SimulationResultTarget } from '../../navigation/experience-routes'

// Board ExpSim (390 wide): one container for both templates (机器人避障、无人机起降), only the task
// configuration changes. Sizes and colours are the board's.
const props = defineProps<{
  experienceId: string
  // Null for a guest: the task can be played, the result stays on this device.
  accessToken: string | null
}>()

const emit = defineEmits<{
  exit: []
  finished: [target: SimulationResultTarget]
}>()

type LoadState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error' } | { kind: 'unplayable' }

const STEP_MS = 450

const state = ref<LoadState>({ kind: 'loading' })
const item = ref<ExperienceItemDetail | null>(null)
const session = ref<SimulationSession | null>(null)
const startedAt = ref<Date | null>(null)
const now = ref(Date.now())
const running = ref(false)
const saving = ref(false)
// What the scene shows while a run plays (and after it, until the program changes).
const activeBlock = ref<number | null>(null)
const robotCell = ref(0)
const robotHeading = ref<Heading>('E')
const bumped = ref(false)
const droneHeight = ref(0)
// Targets already hovered at in the run on screen.
const hoveredTargets = ref(0)
let clock: ReturnType<typeof setInterval> | null = null
let stepTimer: ReturnType<typeof setTimeout> | null = null

const template = computed(() => session.value?.template ?? null)
const title = computed(() => item.value?.title ?? '虚拟仿真')
const scene = computed(() => item.value?.coverImage?.url ?? simulationScenePicture(template.value))
const clockText = computed(() => formatSimulationClock(startedAt.value ? now.value - startedAt.value.getTime() : 0))
const palette = computed(() => (template.value ? PALETTE[template.value] : []))
const full = computed(() => (session.value?.program.length ?? 0) >= MAX_PROGRAM_BLOCKS)
const finished = computed(() => (session.value ? isTaskFinished(session.value) : false))
const lastRun = computed(() => session.value?.lastRun ?? null)

const robotLevel = computed(() => ROBOT_LEVELS[Math.min(session.value?.levelIndex ?? 0, ROBOT_LEVELS.length - 1)])
const droneLevel = computed(() => DRONE_LEVELS[Math.min(session.value?.levelIndex ?? 0, DRONE_LEVELS.length - 1)])
const cells = computed(() => Array.from({ length: ROBOT_GRID_SIZE * ROBOT_GRID_SIZE }, (_, index) => ({
  index,
  kind: robotLevel.value.obstacles.includes(index) ? 'OBSTACLE' : index === robotLevel.value.goal ? 'GOAL' : 'FLOOR',
})))
const HEADING_TURNS: Record<Heading, number> = { N: -90, E: 0, S: 90, W: 180 }
const robotStyle = computed(() => {
  const row = Math.floor(robotCell.value / ROBOT_GRID_SIZE)
  const col = robotCell.value % ROBOT_GRID_SIZE
  return { left: `${col * 20}%`, top: `${row * 20}%` }
})
const robotTurn = computed(() => ({ transform: `rotate(${HEADING_TURNS[robotHeading.value]}deg)` }))
const droneStyle = computed(() => ({ bottom: `${(droneHeight.value / DRONE_CEILING_DM) * 100}%` }))
const droneTargets = computed(() => droneLevel.value.targets.map((height, index) => ({
  height,
  done: index < hoveredTargets.value,
  style: { bottom: `${(height / DRONE_CEILING_DM) * 100}%` },
})))
const TICKS = [0, 10, 20, 30, 40, 50]

// The two pills at the bottom of the scene (board stat1 / stat2).
const stats = computed(() => {
  const current = session.value
  if (!current) return ['', '']
  if (current.template === 'ROBOT_OBSTACLE') {
    return [`已绕过 ${current.levelsCompleted} 个障碍`, `碰撞 ${current.mistakes} 次`]
  }
  const target = droneLevel.value.targets[Math.min(hoveredTargets.value, droneLevel.value.targets.length - 1)]
  return [
    `高度 ${formatMetres(droneHeight.value)} · 目标 ${formatMetres(target)}`,
    `偏移 ${formatMetres(Math.abs(target - droneHeight.value))}`,
  ]
})

const mainLabel = computed(() => {
  if (saving.value) return '正在保存…'
  if (finished.value) return '查看结果'
  if (lastRun.value?.passed) return '下一关'
  return running.value ? '运行中…' : '运行程序'
})

function resetScene(): void {
  robotCell.value = robotLevel.value.start
  robotHeading.value = robotLevel.value.heading
  bumped.value = false
  droneHeight.value = 0
  hoveredTargets.value = 0
  activeBlock.value = null
}

async function load(): Promise<void> {
  state.value = { kind: 'loading' }
  try {
    const loaded = await experienceApi.loadExperienceItem(props.experienceId)
    if (loaded.type !== 'SIMULATION' || !loaded.simulationTemplate) {
      state.value = { kind: 'unplayable' }
      return
    }
    item.value = loaded
    session.value = createSimulationSession(loaded.simulationTemplate)
    startedAt.value = new Date()
    now.value = Date.now()
    resetScene()
    state.value = { kind: 'ready' }
  } catch {
    state.value = { kind: 'error' }
  }
}

function add(block: SimulationBlock): void {
  if (!session.value || running.value || full.value || finished.value) return
  if (session.value.lastRun?.passed) return
  session.value = withBlockAdded(session.value, block)
  resetScene()
}

function remove(index: number): void {
  if (!session.value || running.value || session.value.lastRun?.passed) return
  session.value = withBlockRemoved(session.value, index)
  resetScene()
}

function reset(): void {
  if (!session.value || running.value || session.value.lastRun?.passed) return
  session.value = withProgramCleared(session.value)
  resetScene()
}

function stopSteps(): void {
  if (stepTimer) clearTimeout(stepTimer)
  stepTimer = null
}

// Plays the run block by block, then shows its outcome.
function play(): void {
  const before = session.value
  if (!before) return
  const after = runCurrentLevel(before)
  const outcome = after.lastRun
  if (!outcome || after === before) {
    uni.showToast({ title: '先点下面的积木，拼一段程序', icon: 'none' })
    return
  }
  resetScene()
  running.value = true
  const steps: Array<() => void> = before.template === 'ROBOT_OBSTACLE'
    ? outcome.robotSteps.map((step) => () => {
      activeBlock.value = step.blockIndex
      robotCell.value = step.cell
      robotHeading.value = step.heading
      bumped.value = step.event === 'BUMPED'
    })
    : outcome.droneSteps.map((step) => () => {
      activeBlock.value = step.blockIndex
      droneHeight.value = step.heightDm
      hoveredTargets.value = step.nextTarget
    })
  let index = 0
  const next = () => {
    if (index < steps.length) {
      steps[index]()
      index += 1
      stepTimer = setTimeout(next, STEP_MS)
      return
    }
    stepTimer = null
    running.value = false
    activeBlock.value = null
    bumped.value = false
    session.value = after
  }
  next()
}

function main(): void {
  if (!session.value || running.value || saving.value) return
  if (finished.value) {
    void finish()
    return
  }
  if (session.value.lastRun?.passed) {
    session.value = nextLevel(session.value)
    resetScene()
    return
  }
  play()
}

// Board: the result is the user's own business; leaving after playing records it as unfinished.
function exit(): void {
  if (saving.value) return
  if (!session.value || session.value.runs === 0) {
    emit('exit')
    return
  }
  if (finished.value) {
    void finish()
    return
  }
  uni.showModal({
    title: '退出这次体验？',
    content: `退出后记为未完成，停在第 ${session.value.levelIndex + 1} 关。`,
    confirmText: '退出',
    cancelText: '继续体验',
    success: (answer) => {
      if (answer.confirm) void finish()
    },
  })
}

// Every run is kept on this device first; a signed-in user's is sent at once.
async function finish(): Promise<void> {
  if (!session.value || !item.value || !startedAt.value || saving.value) return
  stopSteps()
  running.value = false
  saving.value = true
  const result = buildSimulationResult(session.value, item.value.experienceId, startedAt.value, new Date())
  const saved = localSimulationResults.save(result, { title: item.value.title, productId: item.value.productId })
  let recordId: string | null = null
  if (props.accessToken) {
    try {
      recordId = await uploadLocalResult(experienceRecordApi, localSimulationResults, props.accessToken, saved)
    } catch {
      // The result page tries again; the copy on the device is enough to show it.
    }
  }
  saving.value = false
  emit('finished', recordId
    ? { experienceId: item.value.experienceId, recordId }
    : { experienceId: item.value.experienceId, localId: saved.localId })
}

watch(() => props.experienceId, () => { void load() }, { immediate: true })

onMounted(() => {
  clock = setInterval(() => {
    if (!finished.value) now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (clock) clearInterval(clock)
  stopSteps()
})

defineExpose({ exit })
</script>

<template>
  <view class="sim" data-testid="simulation-page">
    <view class="sim__head">
      <view class="sim__bar">
        <button class="sim__close" data-testid="simulation-exit" aria-label="退出体验" @click="exit">
          <LineIcon name="close" :size="20" color="#202c42" :stroke-width="2" />
        </button>
        <text class="sim__title">{{ title }}</text>
        <text v-if="state.kind === 'ready'" class="sim__clock" data-testid="simulation-clock" role="timer">{{ clockText }}</text>
      </view>
      <view v-if="state.kind === 'ready' && session" class="sim__meta">
        <text class="sim__tag">虚拟仿真 · 不连接真实设备</text>
        <text class="sim__level" data-testid="simulation-level">{{ levelLabel(session) }}</text>
      </view>
    </view>

    <view v-if="state.kind === 'loading'" class="sim__state" data-testid="simulation-loading" aria-label="正在加载">
      <view class="sim__skeleton" />
    </view>
    <view v-else-if="state.kind === 'error'" class="sim__state" data-testid="simulation-error">
      <text class="sim__state-title">体验没有加载出来，请稍后重试</text>
      <button class="sim__state-action" data-testid="simulation-retry" @click="load">重新加载</button>
    </view>
    <view v-else-if="state.kind === 'unplayable'" class="sim__state" data-testid="simulation-unplayable">
      <text class="sim__state-title">这个仿真暂时不能体验</text>
      <button class="sim__state-action" @click="emit('exit')">返回</button>
    </view>

    <template v-else-if="session">
      <view class="sim__scene" data-testid="simulation-scene">
        <image v-if="scene" class="sim__scene-picture" :src="scene" alt="仿真场景" mode="aspectFill" />
        <text class="sim__scene-tag">模拟场景</text>

        <view v-if="session.template === 'ROBOT_OBSTACLE'" class="sim__grid" aria-label="5 行 5 列仿真场地">
          <view
            v-for="cell in cells"
            :key="cell.index"
            :class="['sim__cell', `sim__cell--${cell.kind.toLowerCase()}`]"
            :data-testid="`simulation-cell-${cell.index}`"
            :data-kind="cell.kind"
          >
            <text v-if="cell.kind === 'GOAL'" class="sim__goal">终点</text>
          </view>
          <view
            :class="['sim__robot', { 'sim__robot--bumped': bumped }]"
            :style="robotStyle"
            data-testid="simulation-robot"
            :data-cell="robotCell"
          >
            <view class="sim__robot-body" :style="robotTurn">
              <LineIcon name="arrowRight" :size="18" color="#ffffff" :stroke-width="2.6" />
            </view>
          </view>
        </view>

        <view v-else class="sim__sky" aria-label="高度 0 到 5 米">
          <view class="sim__scale">
            <text v-for="tick in TICKS" :key="tick" class="sim__tick" :style="{ bottom: `${(tick / DRONE_CEILING_DM) * 100}%` }">
              {{ tick / 10 }} m
            </text>
          </view>
          <view class="sim__track">
            <view
              v-for="target in droneTargets"
              :key="target.height"
              :class="['sim__target', { 'sim__target--done': target.done }]"
              :style="target.style"
            />
            <view class="sim__drone" :style="droneStyle" data-testid="simulation-drone" :data-height="droneHeight">
              <LineIcon name="drone" :size="22" color="#ffffff" :stroke-width="2" />
            </view>
          </view>
        </view>

        <view class="sim__stats">
          <text class="sim__stat" data-testid="simulation-stat-1">{{ stats[0] }}</text>
          <text class="sim__stat" data-testid="simulation-stat-2">{{ stats[1] }}</text>
        </view>
      </view>

      <view class="sim__panel">
        <view class="sim__heading">
          <text class="sim__heading-title">拼出你的程序</text>
          <text class="sim__heading-note">点积木加入，点程序里的积木删除</text>
        </view>
        <text
          :class="['sim__goal-line', { 'sim__goal-line--passed': lastRun?.passed, 'sim__goal-line--failed': lastRun && !lastRun.passed }]"
          data-testid="simulation-status"
          aria-live="polite"
        >{{ lastRun ? lastRun.message : `目标：${levelGoal(session)}` }}</text>

        <view class="sim__program" data-testid="simulation-program">
          <text v-if="session.program.length === 0" class="sim__program-empty">点下面的积木，拼出程序</text>
          <button
            v-for="(block, index) in session.program"
            :key="`${index}-${block}`"
            class="sim__program-block"
            :aria-label="`删除积木 ${BLOCK_TEXT[block]}`"
            :data-testid="`simulation-program-${index}`"
            @click="remove(index)"
          >
            <text
              :class="['sim__block', { 'sim__block--active': activeBlock === index }]"
              :style="{ background: BLOCK_COLOURS[block].fill }"
            >{{ BLOCK_TEXT[block] }}</text>
          </button>
        </view>

        <view class="sim__palette">
          <button
            v-for="entry in palette"
            :key="entry.block"
            class="sim__palette-block"
            :style="{ borderColor: BLOCK_COLOURS[entry.block].fill, color: BLOCK_COLOURS[entry.block].text, opacity: full || running || lastRun?.passed ? 0.4 : 1 }"
            :disabled="full || running || Boolean(lastRun?.passed)"
            :aria-label="`加入积木 ${entry.label}`"
            :data-testid="`simulation-add-${entry.block.toLowerCase()}`"
            @click="add(entry.block)"
          >
            {{ entry.label }}
          </button>
        </view>

        <view class="sim__actions">
          <button
            class="sim__reset"
            data-testid="simulation-reset"
            :disabled="running || Boolean(lastRun?.passed)"
            @click="reset"
          >
            重置
          </button>
          <button
            class="sim__run"
            data-testid="simulation-run"
            :disabled="running || saving"
            @click="main"
          >
            {{ mainLabel }}
          </button>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.sim {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
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

.sim__head {
  background: #ffffff;
}

/* The light page head of the boards (light-theme.css .theme-page-head); the tag row under it is white. */
.sim__bar {
  display: flex;
  padding: calc(8px + var(--status-bar-height)) 8px 8px;
  align-items: center;
  gap: 4px;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.sim__close {
  display: flex;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
}

.sim__title {
  overflow: hidden;
  font-size: 17px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sim__clock {
  margin: 0 8px 0 auto;
  font-size: 15px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.sim__meta {
  display: flex;
  padding: 6px 16px 10px;
  align-items: center;
  gap: 8px;
}

.sim__tag {
  padding: 2px 8px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 12px;
  white-space: nowrap;
}

.sim__level {
  margin-left: auto;
  color: #536178;
  font-size: 12px;
  white-space: nowrap;
}

.sim__state {
  display: flex;
  min-height: 240px;
  margin: 16px;
  padding: 16px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
}

.sim__skeleton {
  width: 100%;
  height: 200px;
  border-radius: 12px;
  background: #edf0f3;
}

.sim__state-title {
  color: #738096;
  font-size: 14px;
}

.sim__state-action {
  height: 44px;
  padding: 0 22px;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  font-size: 14px;
}

/* 330 high on the board. */
.sim__scene {
  position: relative;
  height: 330px;
  flex: 0 0 auto;
  overflow: hidden;
  background: #3a3f47;
}

.sim__scene-picture {
  display: block;
  width: 100%;
  height: 100%;
}

.sim__scene-tag {
  position: absolute;
  top: 12px;
  left: 12px;
  padding: 3px 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.9);
  font-size: 12px;
}

/* The 5 × 5 field over the picture, between the tag and the stat pills. */
.sim__grid {
  position: absolute;
  top: 44px;
  left: 50%;
  display: flex;
  width: 230px;
  height: 230px;
  flex-wrap: wrap;
  overflow: hidden;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.28);
  transform: translateX(-50%);
}

.sim__cell {
  display: flex;
  width: 20%;
  height: 20%;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.55);
  box-sizing: border-box;
}

.sim__cell--obstacle {
  background: rgba(32, 44, 66, 0.78);
}

.sim__cell--goal {
  background: rgba(21, 154, 112, 0.85);
}

.sim__goal {
  color: #ffffff;
  font-size: 12px;
  font-weight: 700;
}

.sim__robot {
  position: absolute;
  display: flex;
  width: 20%;
  height: 20%;
  align-items: center;
  justify-content: center;
  transition: left 0.35s ease, top 0.35s ease;
}

.sim__robot-body {
  display: flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border: 2px solid #ffffff;
  border-radius: 50%;
  background: #ff6a00;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.3);
  transition: transform 0.3s ease;
}

.sim__robot--bumped .sim__robot-body {
  background: #d83b2b;
}

.sim__sky {
  position: absolute;
  top: 44px;
  bottom: 56px;
  left: 50%;
  display: flex;
  width: 150px;
  gap: 10px;
  transform: translateX(-50%);
}

.sim__scale {
  position: relative;
  width: 40px;
}

.sim__tick {
  position: absolute;
  right: 0;
  padding: 1px 4px;
  border-radius: 4px;
  color: #202c42;
  background: rgba(255, 255, 255, 0.85);
  font-size: 11px;
  transform: translateY(50%);
  white-space: nowrap;
}

.sim__track {
  position: relative;
  flex: 1;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.28);
}

.sim__target {
  position: absolute;
  right: 0;
  left: 0;
  height: 6px;
  border-radius: 3px;
  background: rgba(21, 154, 112, 0.9);
  transform: translateY(50%);
}

.sim__target--done {
  background: rgba(255, 255, 255, 0.7);
}

.sim__drone {
  position: absolute;
  left: 50%;
  display: flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border: 2px solid #ffffff;
  border-radius: 50%;
  background: #2f80ed;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.3);
  transform: translate(-50%, 50%);
  transition: bottom 0.4s ease;
}

.sim__stats {
  position: absolute;
  right: 12px;
  bottom: 12px;
  left: 12px;
  display: flex;
  gap: 8px;
}

.sim__stat {
  display: flex;
  height: 34px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.92);
  font-size: 12px;
  white-space: nowrap;
}

.sim__panel {
  display: flex;
  padding: 14px 16px;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  background: #ffffff;
}

.sim__heading {
  display: flex;
  align-items: baseline;
}

.sim__heading-title {
  font-size: 15px;
  font-weight: 700;
  white-space: nowrap;
}

.sim__heading-note {
  margin-left: 8px;
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sim__goal-line {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.sim__goal-line--passed {
  color: #11804a;
  font-weight: 700;
}

.sim__goal-line--failed {
  color: #c2410c;
}

.sim__program {
  display: flex;
  min-height: 92px;
  padding: 10px;
  flex-wrap: wrap;
  align-content: flex-start;
  gap: 8px;
  border-radius: 10px;
  background: #f5f7fc;
  box-sizing: border-box;
}

.sim__program-empty {
  color: #738096;
  font-size: 13px;
  line-height: 34px;
}

/* The 4px around the 34-high block makes a 42-high tap target (board: margin -4, padding 4). */
.sim__program-block {
  display: flex;
  margin: -4px;
  padding: 4px;
}

.sim__block {
  display: flex;
  height: 34px;
  padding: 0 12px;
  align-items: center;
  border-radius: 8px;
  color: #ffffff;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  transition: transform 0.15s ease;
}

.sim__block--active {
  box-shadow: 0 0 0 3px #202c42;
  transform: scale(1.06);
}

.sim__palette {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.sim__palette-block {
  display: flex;
  height: 44px;
  align-items: center;
  justify-content: center;
  border: 1.5px solid;
  border-radius: 8px;
  background: #ffffff;
  font-size: 13px;
  box-sizing: border-box;
}

.sim__actions {
  display: flex;
  margin-top: auto;
  padding-bottom: calc(14px + env(safe-area-inset-bottom));
  gap: 10px;
}

.sim__reset {
  display: flex;
  width: 110px;
  height: 46px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  background: #ffffff;
  font-size: 15px;
  box-sizing: border-box;
}

.sim__run {
  display: flex;
  height: 46px;
  flex: 1;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
}

.sim__run[disabled] {
  opacity: 0.6;
}
</style>
