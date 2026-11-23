<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  applicationInput,
  applyAmountMinor,
  canChooseReason,
  canSubmitApplication,
  createApplyKeyHolder,
  firstReason,
  initialChoice,
  kindChoices,
  reasonChoices,
  slotCount,
  slotRoom,
  type UploadedEvidence,
} from '../../application/aftersale-apply'
import {
  AFTERSALE_REASON_LABELS,
  AFTERSALE_SLOT_LABELS,
  aftersaleGoods,
  aftersaleHandler,
  classifyAftersaleFailure,
} from '../../application/aftersale-view'
import { formatTradeAmount, formatTradeTime } from '../../application/order-view'
import { aftersaleApi } from '../../data/aftersale-api-client'
import {
  AFTERSALE_DESCRIPTION_LIMIT,
  AFTERSALE_IMAGE_SLOTS,
  type AftersaleEligibility,
  type AftersaleImageSlot,
  type AftersaleKind,
  type AftersaleReason,
} from '../../domain/aftersale'
import { coverOfLine } from '../shared/trade-presentation'
import AftersaleThumb from './AftersaleThumb.vue'

// 申请售后（画板 AftersaleApply）。能选什么都由服务端的 eligibility 给出；提交带 Idempotency-Key，
// 成功后由页面宿主换成售后详情。
const props = defineProps<{
  orderId: string
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  // 已有没结束的售后单，或刚提交成功：进那张单的详情。
  opened: [aftersaleId: string]
}>()

type ApplyState =
  | { kind: 'loading' }
  | { kind: 'ready'; eligibility: AftersaleEligibility }
  | { kind: 'missing' }
  | { kind: 'error' }

const MAX_BYTES = 5 * 1024 * 1024
const CAMERA = 'M4 8h3l2-3h6l2 3h3v11H4zM12 16.5a3.5 3.5 0 1 0 0-7a3.5 3.5 0 1 0 0 7z'
const PLUS = 'M12 5v14M5 12h14'
const INFO = 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01'
const TICK = 'M5 12l5 5 9-10'

const state = ref<ApplyState>({ kind: 'loading' })
const kind = ref<AftersaleKind | null>(null)
const reason = ref<AftersaleReason | null>(null)
const description = ref('')
const images = ref<UploadedEvidence[]>([])
const uploading = ref<AftersaleImageSlot | null>(null)
const pickerOpen = ref(false)
const submitting = ref(false)
const keys = createApplyKeyHolder()
let requestVersion = 0

const eligibility = computed(() => (state.value.kind === 'ready' ? state.value.eligibility : null))
const kinds = computed(() => (eligibility.value ? kindChoices(eligibility.value) : []))
const reasons = computed(() => (eligibility.value ? reasonChoices(eligibility.value, kind.value) : []))
const reasonLabel = computed(() => (reason.value ? AFTERSALE_REASON_LABELS[reason.value] : '请选择'))
const goods = computed(() => (eligibility.value ? aftersaleGoods(eligibility.value.lines) : null))
const firstLine = computed(() => eligibility.value?.lines[0] ?? null)
// "实付 ¥1,299 · 11/03 已发货"
const paidLine = computed(() => {
  const loaded = eligibility.value
  if (!loaded) return ''
  const paid = `实付 ${formatTradeAmount(loaded.paidMinor)}`
  if (loaded.shippedAt) return `${paid} · ${formatTradeTime(loaded.shippedAt).slice(0, 5)} 已发货`
  return `${paid} · 未发货`
})
const canSubmit = computed(() => Boolean(eligibility.value) && canSubmitApplication({
  eligibility: eligibility.value as AftersaleEligibility,
  kind: kind.value,
  reason: reason.value,
  uploading: uploading.value !== null,
}))

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  if (!props.orderId) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await aftersaleApi.eligibility(token, props.orderId)
    if (requested !== requestVersion) return
    // 一张订单同时只有一张没结束的售后单：直接去看它。
    if (loaded.openAftersaleId) {
      emit('opened', loaded.openAftersaleId)
      return
    }
    state.value = { kind: 'ready', eligibility: loaded }
    // 还能选的话保留用户已选的；不能选了才换成第一个能选的。
    const stillKind = kind.value && loaded.kinds.some((option) => option.kind === kind.value && option.available)
    if (!stillKind) {
      const initial = initialChoice(loaded)
      kind.value = initial.kind
      reason.value = initial.reason
    } else if (!reason.value || !canChooseReason(loaded, kind.value, reason.value)) {
      reason.value = firstReason(loaded, kind.value)
    }
  } catch (error) {
    if (requested !== requestVersion) return
    const failure = classifyAftersaleFailure(error)
    if (failure.kind === 'UNAUTHORIZED') {
      emit('login')
      return
    }
    const status = (error as { status?: number }).status
    state.value = { kind: status === 404 || status === 400 ? 'missing' : 'error' }
  }
}

function pickKind(next: AftersaleKind, available: boolean): void {
  if (!available || !eligibility.value || next === kind.value) return
  kind.value = next
  reason.value = firstReason(eligibility.value, next)
}

function pickReason(next: AftersaleReason, available: boolean, note: string | null): void {
  if (!available) {
    if (note) uni.showToast({ title: note, icon: 'none' })
    return
  }
  reason.value = next
  pickerOpen.value = false
}

function chooseFiles(count: number): Promise<Array<{ path: string; size: number }>> {
  return new Promise((resolve) => {
    uni.chooseImage({
      count,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (result) => {
        const files = Array.isArray(result.tempFiles) ? result.tempFiles as Array<{ path?: string; size?: number }> : []
        const paths = Array.isArray(result.tempFilePaths) ? result.tempFilePaths : [result.tempFilePaths]
        resolve(paths.map((path, index) => ({ path, size: files[index]?.size ?? 0 })))
      },
      fail: () => resolve([]),
    })
  })
}

// 点格子选图并上传，每格最多 3 张；图片先传到服务端拿 imageId，提交申请时带上。
async function addImages(slot: AftersaleImageSlot): Promise<void> {
  const token = props.accessToken
  if (!token || uploading.value) return
  const room = slotRoom(images.value, slot)
  if (room === 0) {
    uni.showToast({ title: `${AFTERSALE_SLOT_LABELS[slot]}最多 3 张`, icon: 'none' })
    return
  }
  const files = await chooseFiles(room)
  if (files.length === 0) return
  uploading.value = slot
  try {
    for (const file of files.slice(0, room)) {
      if (file.size > MAX_BYTES) {
        uni.showToast({ title: '图片不能超过 5 MB', icon: 'none' })
        continue
      }
      const uploaded = await aftersaleApi.uploadImage(token, slot, file.path)
      images.value = [...images.value, { imageId: uploaded.imageId, slot: uploaded.slot, localPath: file.path }]
    }
  } catch (error) {
    const failure = classifyAftersaleFailure(error)
    if (failure.kind === 'UNAUTHORIZED') {
      emit('login')
      return
    }
    uni.showToast({ title: failure.kind === 'FAILED' && failure.message === '操作失败，请重试' ? '图片上传失败，请重试' : failure.message, icon: 'none' })
  } finally {
    uploading.value = null
  }
}

function removeImage(imageId: string): void {
  images.value = images.value.filter((image) => image.imageId !== imageId)
}

async function submit(): Promise<void> {
  const token = props.accessToken
  const loaded = eligibility.value
  if (!token || !loaded || !kind.value || !reason.value || submitting.value || !canSubmit.value) return
  const input = applicationInput({ kind: kind.value, reason: reason.value, description: description.value, images: images.value })
  submitting.value = true
  try {
    const created = await aftersaleApi.apply(token, props.orderId, input, keys.keyFor(props.orderId, input))
    emit('opened', created.aftersaleId)
  } catch (error) {
    const failure = classifyAftersaleFailure(error)
    if (failure.kind === 'UNAUTHORIZED') {
      emit('login')
      return
    }
    uni.showToast({ title: failure.message, icon: 'none' })
    // 订单或能申请的类型变了：再读一次 eligibility（已有售后单时直接进那张单）。
    if (failure.kind === 'CHANGED') void load()
  } finally {
    submitting.value = false
  }
}

watch(() => [props.refreshKey, props.accessToken, props.orderId], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="aftersale-apply" data-testid="aftersale-apply">
    <AppPageHeader title="申请售后" plain test-id="aftersale-apply" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="aftersale-apply__body" data-testid="aftersale-apply-loading" aria-label="正在加载">
      <view class="aftersale-apply__skeleton" />
      <view class="aftersale-apply__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="aftersale-apply__state" data-testid="aftersale-apply-missing">
      <text class="aftersale-apply__state-title">没有找到这笔订单</text>
      <button class="aftersale-apply__state-button" @click="emit('back')">返回</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="aftersale-apply__state" data-testid="aftersale-apply-error">
      <text class="aftersale-apply__state-title">加载失败</text>
      <text class="aftersale-apply__state-text">请检查网络后重试</text>
      <button class="aftersale-apply__state-button" data-testid="aftersale-apply-retry" @click="load">重试</button>
    </view>

    <template v-else-if="eligibility && goods">
      <view class="aftersale-apply__body">
        <view class="aftersale-apply__card aftersale-apply__goods" data-testid="aftersale-apply-goods">
          <image
            v-if="firstLine && coverOfLine(firstLine)"
            class="aftersale-apply__cover"
            :src="coverOfLine(firstLine) ?? ''"
            mode="aspectFill"
          />
          <view v-else class="aftersale-apply__cover" />
          <view class="aftersale-apply__goods-text">
            <text class="aftersale-apply__goods-name">{{ goods.name }}</text>
            <text class="aftersale-apply__goods-line">{{ goods.spec }}</text>
            <text class="aftersale-apply__goods-line" data-testid="aftersale-apply-paid">{{ paidLine }}</text>
          </view>
        </view>

        <view class="aftersale-apply__card aftersale-apply__section">
          <text class="aftersale-apply__title">选择售后类型</text>
          <view class="aftersale-apply__kinds" role="radiogroup" aria-label="售后类型">
            <view
              v-for="choice in kinds"
              :key="choice.kind"
              :class="[
                'aftersale-apply__kind',
                { 'aftersale-apply__kind--on': choice.kind === kind, 'aftersale-apply__kind--off': !choice.available },
              ]"
              role="radio"
              :aria-checked="choice.kind === kind"
              :aria-disabled="!choice.available"
              :data-testid="`aftersale-kind-${choice.kind}`"
              :data-available="choice.available ? 'true' : 'false'"
              @click="pickKind(choice.kind, choice.available)"
            >
              <view v-if="choice.kind === kind" class="aftersale-apply__radio aftersale-apply__radio--on">
                <LineIcon :d="TICK" :size="13" color="#ffffff" :stroke-width="3" />
              </view>
              <view v-else :class="['aftersale-apply__radio', { 'aftersale-apply__radio--na': !choice.available }]" />
              <view class="aftersale-apply__kind-text">
                <text class="aftersale-apply__kind-title">{{ choice.title }}</text>
                <text class="aftersale-apply__kind-sub" :data-testid="`aftersale-kind-${choice.kind}-sub`">{{ choice.sub }}</text>
              </view>
            </view>
          </view>
        </view>

        <view class="aftersale-apply__card aftersale-apply__rows">
          <button
            class="aftersale-apply__row aftersale-apply__row--line"
            data-testid="aftersale-apply-reason"
            aria-haspopup="dialog"
            :disabled="!kind"
            @click="pickerOpen = true"
          >
            <text class="aftersale-apply__row-label">申请原因</text>
            <text class="aftersale-apply__row-value" data-testid="aftersale-apply-reason-value">{{ reasonLabel }}</text>
            <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
          <view class="aftersale-apply__row">
            <text class="aftersale-apply__row-label">申请金额</text>
            <text class="aftersale-apply__amount" data-testid="aftersale-apply-amount">{{ formatTradeAmount(applyAmountMinor(eligibility)) }}</text>
            <text class="aftersale-apply__amount-note">不超过实付金额</text>
          </view>
        </view>

        <view class="aftersale-apply__card aftersale-apply__section">
          <text class="aftersale-apply__title">补充描述和凭证</text>
          <textarea
            v-model="description"
            class="aftersale-apply__textarea"
            data-testid="aftersale-apply-description"
            aria-label="问题描述"
            placeholder="补充描述商品情况，便于商家处理"
            placeholder-class="aftersale-apply__placeholder"
            :maxlength="AFTERSALE_DESCRIPTION_LIMIT"
          />
          <view class="aftersale-apply__slots">
            <view v-for="slot in AFTERSALE_IMAGE_SLOTS" :key="slot" class="aftersale-apply__slot">
              <button
                class="aftersale-apply__slot-tile"
                :data-testid="`aftersale-slot-${slot}`"
                :disabled="uploading !== null"
                @click="addImages(slot)"
              >
                <LineIcon :d="slot === 'OTHER' ? PLUS : CAMERA" :size="22" color="#91a0b4" />
                <text>{{ uploading === slot ? '上传中…' : AFTERSALE_SLOT_LABELS[slot] }}</text>
              </button>
              <text v-if="slotCount(images, slot) > 0" class="aftersale-apply__slot-count" :data-testid="`aftersale-slot-${slot}-count`">
                已上传 {{ slotCount(images, slot) }} 张
              </text>
            </view>
          </view>
          <view v-if="images.length > 0" class="aftersale-apply__thumbs" data-testid="aftersale-apply-thumbs">
            <view v-for="image in images" :key="image.imageId" class="aftersale-apply__thumb">
              <AftersaleThumb :image-id="image.imageId" :access-token="accessToken" :fallback="image.localPath" :size="64" />
              <text class="aftersale-apply__thumb-label">{{ AFTERSALE_SLOT_LABELS[image.slot] }}</text>
              <button class="aftersale-apply__thumb-remove" aria-label="删除这张图片" @click="removeImage(image.imageId)">
                <LineIcon name="close" :size="12" color="#ffffff" :stroke-width="2.4" />
              </button>
            </view>
          </view>
        </view>

        <view class="aftersale-apply__handler" data-testid="aftersale-apply-handler">
          <LineIcon :d="INFO" :size="16" color="#ff6a00" :stroke-width="2" />
          <text>由 {{ aftersaleHandler(eligibility.merchant) }} 处理，平台协助沟通</text>
        </view>
      </view>

      <view class="aftersale-apply__bar">
        <button
          :class="['aftersale-apply__submit', { 'aftersale-apply__submit--off': !canSubmit }]"
          data-testid="aftersale-apply-submit"
          :disabled="submitting || !canSubmit"
          @click="submit"
        >
          {{ submitting ? '正在提交…' : '提交申请' }}
        </button>
      </view>

      <view v-if="pickerOpen" class="aftersale-apply__mask" data-testid="aftersale-reason-sheet">
        <button class="aftersale-apply__scrim" aria-label="关闭" @click="pickerOpen = false" />
        <view class="aftersale-apply__sheet" role="dialog" aria-label="选择申请原因">
          <view class="aftersale-apply__sheet-head">
            <text class="aftersale-apply__sheet-title">选择申请原因</text>
            <button class="aftersale-apply__sheet-close" aria-label="关闭" @click="pickerOpen = false">
              <LineIcon name="close" :size="20" color="#536178" :stroke-width="2" />
            </button>
          </view>
          <view class="aftersale-apply__reasons" role="radiogroup" aria-label="申请原因">
            <view
              v-for="choice in reasons"
              :key="choice.reason"
              :class="[
                'aftersale-apply__reason',
                { 'aftersale-apply__reason--on': choice.reason === reason, 'aftersale-apply__reason--off': !choice.available },
              ]"
              role="radio"
              :aria-checked="choice.reason === reason"
              :aria-disabled="!choice.available"
              :data-testid="`aftersale-reason-${choice.reason}`"
              :data-available="choice.available ? 'true' : 'false'"
              @click="pickReason(choice.reason, choice.available, choice.note)"
            >
              <view class="aftersale-apply__reason-text">
                <text class="aftersale-apply__reason-label">{{ choice.label }}</text>
                <text v-if="choice.note" class="aftersale-apply__reason-note">{{ choice.note }}</text>
              </view>
              <LineIcon v-if="choice.reason === reason" :d="TICK" :size="18" color="#ff6a00" :stroke-width="2.6" />
            </view>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours copied from the 2026-11-14 board AftersaleApply (390 wide). */
.aftersale-apply {
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
  color: inherit;
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
}

button::after {
  border: 0;
}

.aftersale-apply__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.aftersale-apply__skeleton {
  height: 120px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.aftersale-apply__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
  text-align: center;
}

.aftersale-apply__state-title {
  font-size: 16px;
  font-weight: 700;
}

.aftersale-apply__state-text {
  color: #738096;
  font-size: 13px;
}

.aftersale-apply__state-button {
  min-width: 120px;
  height: 40px;
  margin-top: 8px;
  padding: 0 20px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  line-height: 40px;
  text-align: center;
}

.aftersale-apply__card {
  border-radius: 14px;
  background: #ffffff;
}

.aftersale-apply__goods {
  display: flex;
  gap: 12px;
  padding: 12px;
}

.aftersale-apply__cover {
  display: block;
  width: 64px;
  height: 64px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.aftersale-apply__goods-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.aftersale-apply__goods-name {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.aftersale-apply__goods-line {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.aftersale-apply__section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
}

.aftersale-apply__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.aftersale-apply__kinds {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.aftersale-apply__kind {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1.5px solid #e5ebf3;
  border-radius: 12px;
  background: #ffffff;
}

.aftersale-apply__kind--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.aftersale-apply__kind--off {
  background: #f5f7fc;
}

.aftersale-apply__radio {
  display: flex;
  width: 22px;
  height: 22px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #c9d3e1;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.aftersale-apply__radio--on {
  border: 0;
  background: #ff6a00;
}

.aftersale-apply__radio--na {
  border-color: #e5ebf3;
  background: #eef2f8;
}

.aftersale-apply__kind-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.aftersale-apply__kind-title {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.aftersale-apply__kind-sub {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.aftersale-apply__kind--off .aftersale-apply__kind-title,
.aftersale-apply__kind--off .aftersale-apply__kind-sub {
  color: #91a0b4;
}

.aftersale-apply__rows {
  display: flex;
  flex-direction: column;
  padding: 0 14px;
}

.aftersale-apply__row {
  display: flex;
  width: 100%;
  min-height: 52px;
  align-items: center;
  gap: 12px;
}

.aftersale-apply__row--line {
  border-bottom: 1px solid #f0f1f3;
}

.aftersale-apply__row-label {
  width: 60px;
  flex: none;
  color: #627187;
  font-size: 14px;
  line-height: 20px;
}

.aftersale-apply__row-value {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.aftersale-apply__amount {
  color: #e85400;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.aftersale-apply__amount-note {
  margin-left: auto;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.aftersale-apply__textarea {
  width: 100%;
  height: 76px;
  padding: 10px 12px;
  border: 1px solid #e5ebf3;
  border-radius: 10px;
  color: #202c42;
  background: #f5f7fc;
  font-size: 14px;
  line-height: 20px;
  box-sizing: border-box;
}

:deep(.aftersale-apply__placeholder) {
  color: #91a0b4;
}

.aftersale-apply__slots {
  display: flex;
  gap: 10px;
}

.aftersale-apply__slot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.aftersale-apply__slot-tile {
  display: flex;
  width: 72px;
  height: 72px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 1px dashed #c9d3e1;
  border-radius: 10px;
  color: #738096;
  background: #fafafb;
  font-size: 11px;
  line-height: 15px;
  text-align: center;
  box-sizing: border-box;
}

.aftersale-apply__slot-count {
  color: #11804a;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.aftersale-apply__thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.aftersale-apply__thumb {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.aftersale-apply__thumb-label {
  color: #738096;
  font-size: 11px;
  line-height: 15px;
}

.aftersale-apply__thumb-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  display: flex;
  width: 22px;
  height: 22px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(32, 44, 66, 0.72);
}

.aftersale-apply__handler {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 10px;
  color: #536178;
  background: #fff0e3;
  font-size: 13px;
  line-height: 18px;
}

.aftersale-apply__handler :deep(.line-icon) {
  flex: none;
}

.aftersale-apply__bar {
  position: sticky;
  bottom: 0;
  padding: 10px 16px max(24px, env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.aftersale-apply__submit {
  display: flex;
  width: 100%;
  height: 46px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  text-align: center;
}

.aftersale-apply__submit--off,
.aftersale-apply__submit[disabled] {
  color: #ffffff;
  background: #c9d3e1;
}

/* Below uni's picker (999), like the other sheets. */
.aftersale-apply__mask {
  position: fixed;
  inset: 0;
  z-index: 900;
}

.aftersale-apply__scrim {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.aftersale-apply__sheet {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 16px max(24px, env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  background: #ffffff;
}

.aftersale-apply__sheet-head {
  display: flex;
  align-items: center;
}

.aftersale-apply__sheet-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.aftersale-apply__sheet-close {
  display: flex;
  width: 44px;
  height: 44px;
  margin-right: -10px;
  margin-left: auto;
  align-items: center;
  justify-content: center;
}

.aftersale-apply__reasons {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.aftersale-apply__reason {
  display: flex;
  min-height: 48px;
  padding: 0 12px;
  align-items: center;
  gap: 10px;
  border: 1.5px solid #e5ebf3;
  border-radius: 10px;
  background: #ffffff;
  box-sizing: border-box;
}

.aftersale-apply__reason--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.aftersale-apply__reason--off {
  background: #f5f7fc;
}

.aftersale-apply__reason-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  padding: 6px 0;
}

.aftersale-apply__reason-label {
  font-size: 15px;
  line-height: 21px;
}

.aftersale-apply__reason--on .aftersale-apply__reason-label {
  color: #ba470b;
  font-weight: 500;
}

.aftersale-apply__reason--off .aftersale-apply__reason-label {
  color: #91a0b4;
}

.aftersale-apply__reason-note {
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
}
</style>
