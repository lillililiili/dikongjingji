<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import IconTile from '@/shared/ui/IconTile.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { EXPERIENCE_PREVIEW_ENABLED } from '../../application/experience-preview'
import { loadLastSimulationNote } from '../../application/product-last-experience'
import {
  pillOpensPicker,
  productExperienceVenueDevicesUrl,
  resolveProductExperiencePill,
  type ProductExperiencePill,
  type ProductExperienceView,
} from '../../application/product-experience'
import { experienceRecordApi } from '../../data/experience-record-api-client'
import { localSimulationResults } from '../../data/local-simulation-results'
import { buildExperienceRecordsUrl } from '../../navigation/experience-routes'

// 先体验，再决定 on a product page (R6-2). Sizes and colours follow the boards PdFull and PdRental.
// 虚拟仿真 opens the task; 录播演示, 到馆体验 and the bar's 先体验 open 选择体验方式 (board ExpPick).
const props = defineProps<{
  view: ProductExperienceView
  cityCode: string
  // The sheet's subtitle (board ExpPick: 编程机器人探索套装 · 体验结束不会自动下单).
  productName?: string
}>()

const pickerOpen = ref(false)
const lastNote = ref<string | null>(null)

// Board ExpPick `ways`: what each way is, its colours and its line icon.
const WAY_STYLES = {
  SIMULATION: { kind: '不连接真实设备', price: '免费', tone: 'green', bg: '#e2f6ed', ink: '#11804a', d: 'M4 5h16v11H4zM8 20h8M12 16v4M10 8.5l4 2.5-4 2.5z' },
  RECORDED_DEMO: { kind: '厂家录制视频', price: '免费', tone: 'blue', bg: '#e6f0ff', ink: '#1d63c4', d: 'M5 5h14v14H5zM10 9l5 3-5 3z' },
  VENUE: { kind: '真机 · 工作人员陪同', price: '看场次', tone: 'orange', bg: '#fff0e3', ink: '#ba470b', d: 'M4 20h16M6 20V9l6-4 6 4v11M10 20v-5h4v5' },
} as const

const ways = computed(() => props.view.pills.map((pill) => ({ pill, style: WAY_STYLES[pill.way] })))
const simulation = computed(() => props.view.pills.find((pill) => pill.way === 'SIMULATION') ?? null)

function go(pill: ProductExperiencePill): void {
  const action = resolveProductExperiencePill(pill, props.cityCode, EXPERIENCE_PREVIEW_ENABLED)
  if (action.kind === 'NAVIGATE') {
    pickerOpen.value = false
    uni.navigateTo({ url: action.url })
  } else if (action.kind === 'NOTICE') uni.showToast({ title: action.message, icon: 'none' })
}

function openPill(pill: ProductExperiencePill): void {
  if (EXPERIENCE_PREVIEW_ENABLED && pillOpensPicker(pill)) openPicker()
  else go(pill)
}

function openPicker(): void {
  pickerOpen.value = true
}

function openVenueDevices(): void {
  const url = productExperienceVenueDevicesUrl(props.view, props.cityCode)
  if (url) uni.navigateTo({ url })
}

function openRecords(): void {
  uni.navigateTo({ url: buildExperienceRecordsUrl('ONLINE') })
}

watch(simulation, (pill) => {
  lastNote.value = null
  if (!EXPERIENCE_PREVIEW_ENABLED || !pill) return
  void loadLastSimulationNote(
    experienceRecordApi,
    localSimulationResults,
    getBookingAuthContext().getAccessToken(),
    pill.targetId,
  ).then((note) => {
    if (simulation.value === pill) lastNote.value = note
  })
}, { immediate: true })

defineExpose({ openPicker })
</script>

<template>
  <view class="try-first" data-testid="product-try-first">
    <view class="try-first__heading">
      <text class="try-first__title">先体验，再决定</text>
      <text class="try-first__note">体验结束不会自动下单</text>
    </view>
    <view class="try-first__ways">
      <button
        v-for="pill in view.pills"
        :key="pill.way"
        :class="['try-first__way', { 'try-first__way--highlighted': pill.highlighted }]"
        :data-testid="`product-try-first-${pill.way}`"
        @click="openPill(pill)"
      >
        {{ pill.label }}
      </button>
    </view>
    <view v-if="lastNote" class="try-first__venue" data-testid="product-try-first-last">
      <text>{{ lastNote }} · </text>
      <text class="try-first__link" data-testid="product-try-first-records" @click="openRecords">查看记录</text>
    </view>
    <view v-if="view.venueNote" class="try-first__venue" data-testid="product-try-first-venue">
      <text>{{ view.venueNote }} · </text>
      <text class="try-first__link" data-testid="product-try-first-devices" @click="openVenueDevices">馆内展示设备</text>
    </view>

    <template v-if="pickerOpen">
      <view class="pick__mask" aria-label="关闭" data-testid="experience-pick-mask" @click="pickerOpen = false" />
      <view class="pick" role="dialog" aria-modal="true" aria-label="选择体验方式" data-testid="experience-pick">
        <view class="pick__head">
          <view class="pick__titles">
            <text class="pick__title">选择体验方式</text>
            <text class="pick__subtitle">{{ productName ? `${productName} · ` : '' }}体验结束不会自动下单</text>
          </view>
          <button class="pick__close" aria-label="关闭" data-testid="experience-pick-close" @click="pickerOpen = false">
            <LineIcon name="close" :size="20" color="#536178" :stroke-width="2" />
          </button>
        </view>
        <view class="pick__ways">
          <button
            v-for="(way, index) in ways"
            :key="way.pill.way"
            :class="['pick__way', { 'pick__way--first': index === 0 }]"
            :data-testid="`experience-pick-${way.pill.way}`"
            @click="go(way.pill)"
          >
            <IconTile :bg="way.style.bg" :ink="way.style.ink" :d="way.style.d" :size="44" :icon-size="22" />
            <view class="pick__copy">
              <view class="pick__name-row">
                <text class="pick__name">{{ way.pill.label }}</text>
                <text :class="['pick__kind', `pick__kind--${way.style.tone}`]">{{ way.style.kind }}</text>
              </view>
              <text class="pick__desc">{{ way.pill.title }}</text>
              <text v-if="way.pill.meta" class="pick__meta">{{ way.pill.meta }}</text>
            </view>
            <text :class="['pick__price', { 'pick__price--paid': way.pill.way === 'VENUE' }]">{{ way.style.price }}</text>
          </button>
          <view class="pick__fine">
            <text>几种方式在页面和记录中分别标注，不混称“真机体验”。</text>
            <text>到馆体验按场馆标价单独付费，与商品订单分开。</text>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.try-first {
  display: flex;
  margin: 0 16px;
  padding: 14px;
  flex-direction: column;
  gap: 10px;
  border-radius: 14px;
  background: #eefaf3;
  box-shadow: var(--app-card-shadow);
}

.try-first__heading {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.try-first__title {
  color: #11804a;
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
}

.try-first__note {
  min-width: 0;
  color: #536178;
  font-size: 12px;
}

.try-first__ways {
  display: flex;
  gap: 8px;
}

.try-first__way {
  display: flex;
  min-width: 0;
  min-height: 36px;
  margin: 0;
  padding: 0 4px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-family: inherit;
  font-size: 13px;
  font-weight: 400;
  line-height: 1.2;
  white-space: nowrap;
}

.try-first__way::after {
  border: 0;
}

.try-first__way--highlighted {
  color: #11804a;
  font-weight: 500;
}

.try-first__venue {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.try-first__link {
  color: #ba470b;
}

/* Board ExpPick: a white sheet over a dark mask, rounded 20 at the top. */
.pick__mask {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: rgba(0, 0, 0, 0.45);
}

.pick {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 41;
  display: flex;
  max-height: 80vh;
  flex-direction: column;
  overflow-y: auto;
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #ffffff;
}

.pick__head {
  display: flex;
  padding: 14px 16px 4px;
  align-items: center;
}

.pick__titles {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.pick__title {
  font-size: 18px;
  font-weight: 700;
}

.pick__subtitle {
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pick__close {
  display: flex;
  width: 44px;
  height: 44px;
  margin: 0 0 0 auto;
  padding: 0;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
}

.pick__close::after,
.pick__way::after {
  border: 0;
}

.pick__ways {
  display: flex;
  padding: 10px 16px calc(16px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 10px;
}

.pick__way {
  display: flex;
  margin: 0;
  padding: 14px;
  gap: 12px;
  border: 1.5px solid #e5ebf3;
  border-radius: 12px;
  color: #202c42;
  background: #ffffff;
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
}

.pick__way--first {
  border-color: #ff6a00;
}

.pick__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.pick__name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pick__name {
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
}

.pick__kind {
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 11px;
  white-space: nowrap;
}

.pick__kind--green {
  color: #11804a;
  background: #e2f6ed;
}

.pick__kind--blue {
  color: #1d63c4;
  background: #e6f0ff;
}

.pick__kind--orange {
  color: #ba470b;
  background: #fff0e3;
}

.pick__desc {
  color: #536178;
  font-size: 13px;
  line-height: 1.5;
}

.pick__meta {
  color: #627187;
  font-size: 12px;
}

.pick__price {
  align-self: center;
  color: #11804a;
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
}

.pick__price--paid {
  color: #e85400;
}

.pick__fine {
  display: flex;
  padding: 12px;
  flex-direction: column;
  border-radius: 10px;
  color: #536178;
  background: #f5f7fc;
  font-size: 12px;
  line-height: 1.7;
}
</style>
