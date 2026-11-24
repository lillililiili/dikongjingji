<script setup lang="ts">
/**
 * 每日趋势：一次只画一条线（成交额、付款订单或新增客户三选一），一根纵轴，不画双轴。
 * 鼠标或手指移到图上显示那一天的三项数字。宽度跟着容器走，文字不随图缩放。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { formatBigMoney, formatCount, niceCeiling, type Dashboard } from './dashboard-api'

type Metric = 'netPaidMinor' | 'paidOrders' | 'newCustomers'

const props = defineProps<{
  daily: Dashboard['daily']
}>()

const METRICS: Array<{ key: Metric, label: string }> = [
  { key: 'netPaidMinor', label: '净成交额' },
  { key: 'paidOrders', label: '付款订单' },
  { key: 'newCustomers', label: '新增客户' },
]

const metric = ref<Metric>('netPaidMinor')
const host = ref<HTMLElement | null>(null)
const width = ref(720)
const height = 240
const padding = { top: 16, right: 16, bottom: 28, left: 64 }
const hover = ref<number | null>(null)
let observer: ResizeObserver | null = null

onMounted(() => {
  if (!host.value) return
  width.value = host.value.clientWidth || 720
  if (typeof ResizeObserver === 'undefined') return
  observer = new ResizeObserver(([entry]) => {
    if (entry) width.value = Math.max(320, Math.round(entry.contentRect.width))
  })
  observer.observe(host.value)
})

onBeforeUnmount(() => observer?.disconnect())

function format(value: number, key: Metric = metric.value): string {
  return key === 'netPaidMinor' ? formatBigMoney(value) : formatCount(value)
}

const values = computed(() => props.daily.map((day) => day[metric.value]))
const top = computed(() => niceCeiling(Math.max(0, ...values.value)))
const plotWidth = computed(() => width.value - padding.left - padding.right)
const plotHeight = height - padding.top - padding.bottom

function x(index: number): number {
  const count = props.daily.length
  return padding.left + (count <= 1 ? plotWidth.value / 2 : (index / (count - 1)) * plotWidth.value)
}

function y(value: number): number {
  return padding.top + plotHeight - (value / top.value) * plotHeight
}

const linePath = computed(() => values.value.map((value, index) =>
  `${index === 0 ? 'M' : 'L'}${x(index).toFixed(1)},${y(value).toFixed(1)}`).join(' '))

const areaPath = computed(() => {
  if (values.value.length === 0) return ''
  const baseline = padding.top + plotHeight
  return `${linePath.value} L${x(values.value.length - 1).toFixed(1)},${baseline} L${x(0).toFixed(1)},${baseline} Z`
})

const ticks = computed(() => [0, 0.25, 0.5, 0.75, 1].map((share) => ({
  value: top.value * share,
  y: y(top.value * share),
})))

/** 横轴只标头、中、尾三天，避免挤在一起。 */
const dateLabels = computed(() => {
  const count = props.daily.length
  if (count === 0) return []
  const indexes = [...new Set([0, Math.floor((count - 1) / 2), count - 1])]
  return indexes.map((index) => ({ x: x(index), text: props.daily[index]!.date.slice(5) }))
})

function onPointer(event: PointerEvent): void {
  const svg = event.currentTarget as SVGSVGElement
  const box = svg.getBoundingClientRect()
  const offset = ((event.clientX - box.left) / box.width) * width.value
  const count = props.daily.length
  if (count === 0) return
  const ratio = (offset - padding.left) / plotWidth.value
  hover.value = Math.min(count - 1, Math.max(0, Math.round(ratio * (count - 1))))
}

const hovered = computed(() => (hover.value === null ? null : props.daily[hover.value] ?? null))
const tooltipLeft = computed(() => {
  if (hover.value === null) return 0
  const position = x(hover.value)
  return Math.min(Math.max(position, 90), width.value - 90)
})
</script>

<template>
  <div class="trend">
    <div class="trend__head">
      <el-radio-group v-model="metric" size="small" data-testid="trend-metric">
        <el-radio-button v-for="item in METRICS" :key="item.key" :value="item.key">{{ item.label }}</el-radio-button>
      </el-radio-group>
    </div>
    <div ref="host" class="trend__plot">
      <svg
        :viewBox="`0 0 ${width} ${height}`"
        :width="width"
        :height="height"
        role="img"
        :aria-label="`每日${METRICS.find((item) => item.key === metric)?.label}`"
        @pointermove="onPointer"
        @pointerleave="hover = null"
      >
        <g class="trend__grid">
          <template v-for="tick in ticks" :key="tick.value">
            <line :x1="padding.left" :x2="width - padding.right" :y1="tick.y" :y2="tick.y" />
            <text :x="padding.left - 8" :y="tick.y + 4" text-anchor="end">{{ format(tick.value) }}</text>
          </template>
        </g>
        <path class="trend__area" :d="areaPath" />
        <path class="trend__line" :d="linePath" />
        <g class="trend__axis">
          <text v-for="label in dateLabels" :key="label.text" :x="label.x" :y="height - 8" text-anchor="middle">
            {{ label.text }}
          </text>
        </g>
        <template v-if="hover !== null && hovered">
          <line class="trend__cross" :x1="x(hover)" :x2="x(hover)" :y1="padding.top" :y2="padding.top + plotHeight" />
          <circle class="trend__dot" :cx="x(hover)" :cy="y(hovered[metric])" r="5" />
        </template>
      </svg>
      <div v-if="hovered" class="trend__tooltip" :style="{ left: `${tooltipLeft}px` }" data-testid="trend-tooltip">
        <strong>{{ hovered.date }}</strong>
        <span>净成交额 {{ format(hovered.netPaidMinor, 'netPaidMinor') }}</span>
        <span>付款订单 {{ formatCount(hovered.paidOrders) }}</span>
        <span>新增客户 {{ formatCount(hovered.newCustomers) }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trend__head {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}

.trend__plot {
  position: relative;
  width: 100%;
}

.trend__plot svg {
  display: block;
  max-width: 100%;
  touch-action: pan-y;
}

.trend__grid line {
  stroke: var(--admin-border);
  stroke-width: 1;
}

.trend__grid text,
.trend__axis text {
  fill: var(--admin-text-secondary);
  font-size: 12px;
}

.trend__area {
  fill: var(--el-color-primary);
  opacity: 0.1;
}

.trend__line {
  fill: none;
  stroke: var(--el-color-primary);
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.trend__cross {
  stroke: var(--admin-text-secondary);
  stroke-width: 1;
  stroke-dasharray: 3 3;
}

.trend__dot {
  fill: var(--el-color-primary);
  stroke: #fff;
  stroke-width: 2;
}

.trend__tooltip {
  position: absolute;
  top: 0;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 4px 12px rgb(32 44 66 / 10%);
  font-size: 12px;
  color: var(--admin-text);
  pointer-events: none;
  white-space: nowrap;
}
</style>
