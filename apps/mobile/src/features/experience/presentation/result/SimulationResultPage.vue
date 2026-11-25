<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { MALL_PREVIEW_ENABLED } from '@/features/mall/application/mall-preview'
import { mallApi } from '@/features/mall/data/mall-api-client'
import type { MallProductDetail } from '@/features/mall/domain/mall'
import { formatMallAmount } from '@/features/mall/presentation/shared/mall-format'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  describeSimulationOutcome,
  outcomeOfLocal,
  outcomeOfRecord,
  type SimulationOutcome,
} from '../../application/simulation-result-view'
import { isUnauthorizedError, uploadLocalResult } from '../../application/simulation-sync'
import { experienceApi } from '../../data/experience-api-client'
import { experienceRecordApi } from '../../data/experience-record-api-client'
import { localSimulationResults } from '../../data/local-simulation-results'
import type { SimulationResultTarget } from '../../navigation/experience-routes'

// Board ExpResult (390 wide). The result is the user's own: nothing is ordered, every way out is
// the user's choice.
const props = defineProps<{
  target: SimulationResultTarget
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
  cityCode: string
}>()

const emit = defineEmits<{
  back: []
  login: []
  // A guest's result was sent after signing in: the page continues on the record.
  saved: [target: SimulationResultTarget]
  retry: [experienceId: string]
  openProduct: [productId: string]
  openVenue: [venueId: string]
  // No venue of the city has it: the experience channel's venues.
  openVenues: []
  openActivities: []
  consult: [merchantId: string, productId: string, title: string, subtitle: string]
}>()

type LoadState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'missing' } | { kind: 'error' }

const state = ref<LoadState>({ kind: 'loading' })
const outcome = ref<SimulationOutcome | null>(null)
const product = ref<MallProductDetail | null>(null)
const venueId = ref<string | null>(null)
let requestVersion = 0

const view = computed(() => (outcome.value ? describeSimulationOutcome(outcome.value) : null))
const productPicture = computed(() =>
  product.value?.coverImage?.url ?? null)
const productPrice = computed(() => {
  const amounts = (product.value?.offers ?? [])
    .map((offer) => offer.amountMinor)
    .filter((amount): amount is number => amount !== null)
  if (amounts.length === 0) return '查看商品报价'
  return `${formatMallAmount(Math.min(...amounts))}${amounts.length > 1 ? ' 起' : ''}`
})
const merchant = computed(() => product.value?.offers[0] ?? null)
const AGAIN = 'M20 11a8 8 0 0 0-14.5-4.5L4 8M4 4v4h4M4 13a8 8 0 0 0 14.5 4.5L20 16M20 20v-4h-4'
const CHECK = 'M5 12l5 5 9-10'

interface Tile {
  key: string
  label: string
  d: string
  run: () => void
}

// Board `tiles`. 收藏 and 对比 are not built yet and explain themselves; the others open real pages.
const tiles = computed<Tile[]>(() => {
  const current = outcome.value
  if (!current) return []
  const list: Tile[] = [
    { key: 'retry', label: '再试一次', d: AGAIN, run: () => emit('retry', current.experienceId) },
  ]
  if (product.value) {
    list.push(
      {
        key: 'favorite',
        label: '收藏商品',
        d: 'M12 20s-8-4.8-8-10.5A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 8 2.5C20 15.2 12 20 12 20z',
        run: () => (current.guest && !props.accessToken
          ? emit('login')
          : uni.showToast({ title: '收藏即将开放', icon: 'none' })),
      },
      {
        key: 'compare',
        label: '加入对比',
        d: 'M7 4v16M17 4v16M4 8l3-4 3 4M14 16l3 4 3-4',
        run: () => uni.showToast({ title: '对比即将开放', icon: 'none' }),
      },
    )
    if (SUPPORT_PREVIEW_ENABLED && merchant.value) {
      const offer = merchant.value
      const shown = product.value
      list.push({
        key: 'consult',
        label: '咨询商家',
        d: 'M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z',
        run: () => emit('consult', offer.merchantId, shown.productId, shown.name, productPrice.value),
      })
    }
  }
  list.push(
    {
      key: 'venue',
      label: '到馆体验',
      d: 'M4 21V5l8-2v18M12 8h8v13M2 21h20M7.5 8h1M7.5 12h1M7.5 16h1M15.5 12h1M15.5 16h1',
      run: openVenue,
    },
    {
      key: 'activity',
      label: '赛事研学',
      d: 'M7 4h10v5a5 5 0 0 1-10 0zM7 6H4a3 3 0 0 0 3 4M17 6h3a3 3 0 0 1-3 4M12 14v4M8 21h8M9.5 18h5',
      run: () => emit('openActivities'),
    },
  )
  return list
})

function openVenue(): void {
  if (venueId.value) emit('openVenue', venueId.value)
  else emit('openVenues')
}

function main(): void {
  if (!outcome.value) return
  if (view.value?.completed) openVenue()
  else emit('retry', outcome.value.experienceId)
}

// The product card and the venue come from the mall and the experience modules; either may be
// missing, and the page works without them.
async function loadAround(productId: string | null, requested: number): Promise<void> {
  if (!productId || !props.cityCode) return
  if (MALL_PREVIEW_ENABLED) {
    mallApi.loadProduct(productId, props.cityCode)
      .then((loaded) => { if (requested === requestVersion) product.value = loaded })
      .catch(() => {})
  }
  experienceApi.loadProductExperiences(productId, props.cityCode)
    .then((loaded) => { if (requested === requestVersion) venueId.value = loaded.venues[0]?.venueId ?? null })
    .catch(() => {})
}

async function load(): Promise<void> {
  const requested = ++requestVersion
  const target = props.target
  const token = props.accessToken
  state.value = outcome.value ? state.value : { kind: 'loading' }
  try {
    if ('recordId' in target) {
      if (!token) {
        emit('login')
        return
      }
      const record = await experienceRecordApi.read(token, target.recordId)
      if (requested !== requestVersion) return
      outcome.value = record.experience.experienceId === target.experienceId ? outcomeOfRecord(record) : null
    } else {
      const local = localSimulationResults.find(target.localId)
      if (!local || local.experienceId !== target.experienceId) {
        outcome.value = null
        state.value = { kind: 'missing' }
        return
      }
      if (token) {
        // Signed in now: send it, then show the record (登录后保存这次记录).
        try {
          const recordId = await uploadLocalResult(experienceRecordApi, localSimulationResults, token, local)
          if (requested !== requestVersion) return
          if (recordId) {
            emit('saved', { experienceId: target.experienceId, recordId })
            return
          }
        } catch (error) {
          if (isUnauthorizedError(error)) {
            emit('login')
            return
          }
          // Offline: show it from the device and try again next time.
        }
      }
      outcome.value = outcomeOfLocal(local)
    }
    if (!outcome.value) {
      state.value = { kind: 'missing' }
      return
    }
    state.value = { kind: 'ready' }
    void loadAround(outcome.value.productId, requested)
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorizedError(error)) {
      emit('login')
      return
    }
    state.value = { kind: 'error' }
  }
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="result" data-testid="simulation-result">
    <view class="result__bar">
      <button class="result__back" data-testid="simulation-result-back" aria-label="返回" @click="emit('back')">
        <LineIcon name="back" :size="22" color="#202c42" :stroke-width="2" />
      </button>
      <text class="result__title">{{ outcome?.title ?? '体验结果' }}</text>
      <view class="result__spacer" />
    </view>

    <view v-if="state.kind === 'loading'" class="result__state" aria-label="正在加载">
      <view class="result__skeleton" />
    </view>
    <view v-else-if="state.kind === 'missing'" class="result__state" data-testid="simulation-result-missing">
      <text class="result__state-title">没有找到这次体验的结果</text>
      <button class="result__state-action" @click="emit('back')">返回</button>
    </view>
    <view v-else-if="state.kind === 'error'" class="result__state" data-testid="simulation-result-error">
      <text class="result__state-title">结果没有加载出来，请稍后重试</text>
      <button class="result__state-action" @click="load">重新加载</button>
    </view>

    <template v-else-if="outcome && view">
      <view class="result__body">
        <text class="result__tag">虚拟仿真 · 不连接真实设备</text>

        <view :class="['result__hero', { 'result__hero--short': outcome.guest }]">
          <view class="result__hero-shade" />
          <text class="result__hero-tag">模拟场景</text>
          <view class="result__hero-copy">
            <view class="result__hero-title" data-testid="simulation-result-title">
              <LineIcon :d="view.completed ? CHECK : AGAIN" :size="22" color="#ffffff" :stroke-width="2.6" />
              <text>{{ view.heroTitle }}</text>
            </view>
            <text class="result__hero-sub" data-testid="simulation-result-sub">{{ view.heroSub }}</text>
          </view>
        </view>

        <view class="result__stats">
          <view
            v-for="(stat, index) in view.stats"
            :key="stat.label"
            :class="['result__stat', { 'result__stat--split': index > 0 }]"
            :data-testid="`simulation-result-stat-${index}`"
          >
            <text :class="['result__stat-value', `result__stat-value--${stat.tone.toLowerCase()}`]">{{ stat.value }}</text>
            <text class="result__stat-label">{{ stat.label }}</text>
          </view>
        </view>

        <view v-if="outcome.guest" class="result__guest" data-testid="simulation-result-guest">
          <view class="result__guest-copy">
            <text class="result__guest-title">登录后保存这次记录</text>
            <text class="result__guest-note">没登录时只存在这台设备上</text>
          </view>
          <button class="result__guest-login" data-testid="simulation-result-login" @click="emit('login')">去登录</button>
        </view>

        <view class="result__next">
          <view class="result__next-heading">
            <text class="result__next-title">接下来，由你决定</text>
            <text class="result__next-note">体验结束不会自动下单</text>
          </view>
          <view class="result__tiles">
            <button
              v-for="tile in tiles"
              :key="tile.key"
              class="result__tile"
              :data-testid="`simulation-result-tile-${tile.key}`"
              @click="tile.run"
            >
              <LineIcon :d="tile.d" :size="22" color="#ff6a00" />
              <text>{{ tile.label }}</text>
            </button>
          </view>
        </view>

        <button
          v-if="product"
          class="result__product"
          data-testid="simulation-result-product"
          @click="emit('openProduct', product.productId)"
        >
          <image
            v-if="productPicture"
            class="result__product-picture"
            :src="productPicture"
            :alt="product.name"
            mode="aspectFill"
          />
          <view v-else class="result__product-picture" />
          <view class="result__product-copy">
            <text class="result__product-name">{{ product.name }}</text>
            <text class="result__product-price">{{ productPrice }}</text>
          </view>
          <text class="result__product-go">去下单 ›</text>
        </button>
      </view>

      <view class="result__actions">
        <button
          v-if="product"
          class="result__secondary"
          data-testid="simulation-result-view-product"
          @click="emit('openProduct', product.productId)"
        >
          查看商品
        </button>
        <button class="result__primary" data-testid="simulation-result-main" @click="main">
          {{ view.completed ? '预约到馆体验' : '再试一次' }}
        </button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.result {
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

.result__bar {
  display: flex;
  margin-bottom: 10px;
  padding: calc(8px + var(--status-bar-height)) 8px 8px;
  align-items: center;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.result__back,
.result__spacer {
  display: flex;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
}

.result__title {
  flex: 1;
  overflow: hidden;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result__state {
  display: flex;
  min-height: 240px;
  margin: 0 16px;
  padding: 16px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
}

.result__skeleton {
  width: 100%;
  height: 200px;
  border-radius: 12px;
  background: #edf0f3;
}

.result__state-title {
  color: #738096;
  font-size: 14px;
}

.result__state-action {
  height: 44px;
  padding: 0 22px;
  border: 1.5px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  font-size: 14px;
}

.result__body {
  display: flex;
  padding: 0 16px 12px;
  flex: 1;
  flex-direction: column;
  gap: 10px;
}

.result__tag {
  align-self: flex-start;
  padding: 0 8px;
  border-radius: 6px;
  color: #11804a;
  background: #e2f6ed;
  font-size: 12px;
  font-weight: 500;
  line-height: 22px;
  white-space: nowrap;
}

.result__hero {
  position: relative;
  height: 216px;
  flex: 0 0 auto;
  overflow: hidden;
  border-radius: 16px;
  background: #3a3f47;
}

.result__hero--short {
  height: 144px;
}

.result__hero-picture {
  display: block;
  width: 100%;
  height: 100%;
}

.result__hero-shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0) 40%, rgba(0, 0, 0, 0.55) 100%);
}

.result__hero-tag {
  position: absolute;
  top: 10px;
  left: 10px;
  padding: 0 8px;
  border-radius: 999px;
  color: #536178;
  background: rgba(255, 255, 255, 0.85);
  font-size: 12px;
  line-height: 22px;
  white-space: nowrap;
}

.result__hero-copy {
  position: absolute;
  right: 14px;
  bottom: 12px;
  left: 14px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: #ffffff;
}

.result__hero-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.result__hero-sub {
  font-size: 12px;
  line-height: 17px;
}

.result__stats {
  display: flex;
  padding: 14px 0;
  border-radius: 14px;
  background: #ffffff;
}

.result__stat {
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.result__stat--split {
  border-left: 1px solid #e5ebf3;
}

.result__stat-value {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
  white-space: nowrap;
}

.result__stat-value--good {
  color: #11804a;
}

.result__stat-value--warn {
  color: #e09400;
}

.result__stat-label {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.result__guest {
  display: flex;
  padding: 10px 12px 10px 14px;
  align-items: center;
  gap: 10px;
  border-radius: 14px;
  background: #fff0e3;
}

.result__guest-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.result__guest-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.result__guest-note {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

/* 36 high on the board; the padding around it keeps a 44 tap target. */
.result__guest-login {
  display: flex;
  height: 36px;
  margin: -4px 0;
  padding: 0 16px;
  align-items: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  box-shadow: 0 0 0 4px transparent;
}

.result__next {
  display: flex;
  padding: 14px;
  flex-direction: column;
  gap: 12px;
  border-radius: 14px;
  background: #ffffff;
}

.result__next-heading {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.result__next-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.result__next-note {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.result__tiles {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.result__tile {
  display: flex;
  min-height: 64px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.result__product {
  display: flex;
  padding: 12px 14px 12px 12px;
  align-items: center;
  gap: 10px;
  border-radius: 14px;
  background: #ffffff;
  text-align: left;
}

.result__product-picture {
  width: 56px;
  height: 56px;
  flex: 0 0 auto;
  border-radius: 10px;
  background: #eef2f8;
}

.result__product-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.result__product-name {
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result__product-price {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.result__product-go {
  flex: 0 0 auto;
  color: #ba470b;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.result__actions {
  position: sticky;
  bottom: 0;
  display: flex;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  gap: 10px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.result__secondary,
.result__primary {
  display: flex;
  height: 46px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
  box-sizing: border-box;
}

.result__secondary {
  border: 1.5px solid #ff6a00;
  color: #ba470b;
  background: #ffffff;
}

.result__primary {
  color: #ffffff;
  background: #ff6a00;
}
</style>
