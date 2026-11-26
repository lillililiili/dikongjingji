<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import { computed, ref, watch } from 'vue'

import { createTradeKey } from '@/features/trade/application/trade-keys'
import { ApiRequestError } from '@/shared/http/public-api-request'
import { DAY_MS, toBeijingDate } from '@/shared/time/beijing-time'

import { loadInquiryCityName } from '../../application/inquiry-city'
import {
  clampInquiryQuantity,
  createMallInquiryDraft,
  createMallInquiryKeyHolder,
  describeMallInquiryBuyer,
  describeMallInquirySubmitFailure,
  earliestInquiryDate,
  formatExpectedDate,
  inquiryOfferNote,
  keepAskableOffers,
  latestInquiryDate,
  mallInquiryDraftProblem,
  toMallInquiryInput,
  toggleInquiryOffer,
  type MallInquiryDraft,
} from '../../application/inquiry-form'
import { mallInquiryApi } from '../../data/inquiry-api-client'
import { mallApi } from '../../data/mall-api-client'
import {
  MALL_INQUIRY_MAX_MERCHANTS,
  MALL_INQUIRY_MAX_QUANTITY,
  MALL_INQUIRY_MIN_QUANTITY,
  MALL_INQUIRY_REQUIREMENT_MAX,
  type MallInquiryBuyer,
  type MallInquiryOffer,
} from '../../domain/inquiry'
import type { MallProductDetail } from '../../domain/mall'
import type { MallInquiryTarget } from '../../navigation/mall-routes'
import { MALL_MERCHANT_KIND_LABELS } from '../shared/mall-format'
import MallInquiryHeader from './MallInquiryHeader.vue'

const props = defineProps<{
  target: MallInquiryTarget | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  // The inquiry is sent: its quotes page takes this page's place.
  submitted: [inquiryId: string]
  browse: []
}>()

type FormState =
  | { kind: 'loading' }
  | { kind: 'ready'; product: MallProductDetail; offers: MallInquiryOffer[] }
  | { kind: 'missing' }
  | { kind: 'error' }

// Quick choices of the expected-date sheet, in days from today; the picker below covers the rest.
const DATE_CHOICES = [7, 15, 30] as const

const state = ref<FormState>({ kind: 'loading' })
const buyer = ref<MallInquiryBuyer | null>(null)
const cityName = ref<string | null>(null)
const draft = ref<MallInquiryDraft | null>(null)
const dateSheet = ref(false)
// Set when the server says the product cannot be asked about from here any more.
const blocked = ref<string | null>(null)
const submitting = ref(false)
const keyHolder = createMallInquiryKeyHolder(createTradeKey)
let requestVersion = 0

const product = computed(() => (state.value.kind === 'ready' ? state.value.product : null))
const offers = computed(() => (state.value.kind === 'ready' ? state.value.offers : []))
const cover = computed(() => (product.value ? (product.value.coverImage?.url ?? null) : null))
const askableCount = computed(() => offers.value.filter((offer) => offer.canInquire).length)
const pickedCount = computed(() => draft.value?.offerIds.length ?? 0)
const buyerView = computed(() => (buyer.value ? describeMallInquiryBuyer(buyer.value) : null))
const requirementLength = computed(() => draft.value?.requirement.trim().length ?? 0)
const today = computed(() => new Date())
const earliest = computed(() => earliestInquiryDate(today.value))
const latest = computed(() => latestInquiryDate(today.value))
const dateChoices = computed(() => DATE_CHOICES.map((days) => {
  const date = toBeijingDate(new Date(today.value.getTime() + days * DAY_MS))
  return { days, date, label: formatExpectedDate(date) }
}))
const canSubmit = computed(() => Boolean(product.value && draft.value) && !blocked.value && !submitting.value)

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  const target = props.target
  if (!target) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const [loadedProduct, loadedOffers, loadedBuyer, loadedCity] = await Promise.all([
      mallApi.loadProduct(target.productId, target.cityCode).catch((error: unknown) => {
        if (error instanceof ApiRequestError && (error.status === 404 || error.status === 400)) return null
        throw error
      }),
      mallInquiryApi.listOffers(token, target.productId, target.cityCode),
      mallInquiryApi.readBuyer(token),
      loadInquiryCityName(target.cityCode),
    ])
    if (requested !== requestVersion) return
    buyer.value = loadedBuyer
    cityName.value = loadedCity
    // Only an inquiry product sold here by at least one merchant can be asked about.
    if (!loadedProduct || loadedProduct.saleMode !== 'INQUIRY' || loadedOffers.length === 0) {
      state.value = { kind: 'missing' }
      return
    }
    draft.value = draft.value ? keepAskableOffers(draft.value, loadedOffers) : createMallInquiryDraft(loadedOffers)
    state.value = { kind: 'ready', product: loadedProduct, offers: loadedOffers }
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    state.value = { kind: 'error' }
  }
}

function update(patch: Partial<MallInquiryDraft>): void {
  if (draft.value) draft.value = { ...draft.value, ...patch }
}

function eventValue(event: Event): string {
  return (event as Event & { detail?: { value?: string } }).detail?.value?.toString() ?? ''
}

function setQuantity(value: number): void {
  update({ quantity: clampInquiryQuantity(value) })
}

function toggleOffer(offer: MallInquiryOffer): void {
  if (!draft.value) return
  const result = toggleInquiryOffer(draft.value, offer)
  if (result.kind === 'REFUSED') {
    uni.showToast({ title: result.message, icon: 'none' })
    return
  }
  draft.value = result.draft
}

function chooseDate(date: string | null): void {
  update({ expectedDate: date })
  dateSheet.value = false
}

function pickDate(event: Event): void {
  const value = eventValue(event)
  if (value) chooseDate(value)
}

async function submit(): Promise<void> {
  const token = props.accessToken
  const target = props.target
  const current = draft.value
  if (!token || !target || !current || !canSubmit.value) return
  const problem = mallInquiryDraftProblem(current, new Date())
  if (problem) {
    uni.showToast({ title: problem, icon: 'none' })
    return
  }
  const input = toMallInquiryInput(target.productId, target.cityCode, current)
  submitting.value = true
  try {
    const inquiry = await mallInquiryApi.submit(token, keyHolder.keyFor(input), input)
    uni.showToast({ title: '询价已提交', icon: 'success' })
    emit('submitted', inquiry.inquiryId)
  } catch (error) {
    const failure = describeMallInquirySubmitFailure(error instanceof ApiRequestError
      ? { status: error.status, code: error.code, message: error.message }
      : null)
    if (failure.kind === 'UNAUTHORIZED') {
      emit('login')
    } else if (failure.kind === 'BLOCKED') {
      blocked.value = failure.message
    } else if (failure.kind === 'MERCHANTS') {
      uni.showToast({ title: failure.message, icon: 'none' })
      void load()
    } else {
      uni.showToast({ title: failure.message, icon: 'none' })
    }
  } finally {
    submitting.value = false
  }
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="mall-inquiry" data-testid="mall-inquiry">
    <MallInquiryHeader title="填写询价单" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="mall-inquiry__body" aria-label="正在加载">
      <view class="mall-inquiry__skeleton" />
      <view class="mall-inquiry__skeleton mall-inquiry__skeleton--tall" />
    </view>

    <view v-else-if="state.kind === 'missing'" class="mall-inquiry__state" data-testid="mall-inquiry-missing">
      <text class="mall-inquiry__state-title">这件商品不能询价，或本城暂不销售</text>
      <button class="mall-inquiry__state-button" data-testid="mall-inquiry-browse" @click="emit('browse')">去商城看看</button>
    </view>

    <view v-else-if="state.kind === 'error'" class="mall-inquiry__state" data-testid="mall-inquiry-error">
      <text class="mall-inquiry__state-title">加载失败</text>
      <text class="mall-inquiry__state-text">请检查网络后重试</text>
      <button class="mall-inquiry__state-button" data-testid="mall-inquiry-retry" @click="load">重试</button>
    </view>

    <template v-else-if="product && draft">
      <view class="mall-inquiry__body">
        <view class="mall-inquiry__card mall-inquiry__product" data-testid="mall-inquiry-product">
          <image v-if="cover" class="mall-inquiry__image" :src="cover" mode="aspectFill" />
          <view v-else class="mall-inquiry__image mall-inquiry__image--empty">
            <LineIcon name="image" :size="24" color="#91a0b4" />
          </view>
          <view class="mall-inquiry__product-copy">
            <text class="mall-inquiry__product-name" data-testid="mall-inquiry-name">{{ product.name }}</text>
            <view class="mall-inquiry__price-row">
              <text class="mall-inquiry__price">询价</text>
              <text class="mall-inquiry__price-note">按配置与数量报价</text>
            </view>
            <text class="mall-inquiry__product-meta" data-testid="mall-inquiry-meta">
              {{ askableCount }} 家可报价{{ cityName ? ` · ${cityName.replace(/市$/, '')}可售` : '' }}
            </text>
          </view>
        </view>

        <view class="mall-inquiry__card mall-inquiry__fields">
          <view class="mall-inquiry__requirement">
            <view class="mall-inquiry__requirement-head">
              <text class="mall-inquiry__label">配置和用途<text class="mall-inquiry__required">*</text></text>
              <text
                :class="['mall-inquiry__counter', { 'mall-inquiry__counter--over': requirementLength > MALL_INQUIRY_REQUIREMENT_MAX }]"
                data-testid="mall-inquiry-counter"
              >
                {{ requirementLength }}/{{ MALL_INQUIRY_REQUIREMENT_MAX }}
              </text>
            </view>
            <textarea
              class="mall-inquiry__textarea"
              data-testid="mall-inquiry-requirement"
              :value="draft.requirement"
              :maxlength="MALL_INQUIRY_REQUIREMENT_MAX"
              placeholder="例如：测绘版 · RTK · 五镜头倾斜相机，需要正射影像与三维建模"
              placeholder-class="mall-inquiry__placeholder"
              aria-label="配置和用途"
              @input="update({ requirement: eventValue($event) })"
              @blur="update({ requirement: eventValue($event) })"
            />
          </view>
          <view class="mall-inquiry__row">
            <text class="mall-inquiry__label">数量<text class="mall-inquiry__required">*</text></text>
            <view class="mall-inquiry__stepper">
              <button
                class="mall-inquiry__step"
                data-testid="mall-inquiry-qty-minus"
                aria-label="减少数量"
                :disabled="draft.quantity <= MALL_INQUIRY_MIN_QUANTITY"
                @click="setQuantity(draft.quantity - 1)"
              >
                <text :class="{ 'mall-inquiry__step-off': draft.quantity <= MALL_INQUIRY_MIN_QUANTITY }">−</text>
              </button>
              <text class="mall-inquiry__step-value" data-testid="mall-inquiry-qty">{{ draft.quantity }}</text>
              <button
                class="mall-inquiry__step"
                data-testid="mall-inquiry-qty-plus"
                aria-label="增加数量"
                :disabled="draft.quantity >= MALL_INQUIRY_MAX_QUANTITY"
                @click="setQuantity(draft.quantity + 1)"
              >
                <text :class="{ 'mall-inquiry__step-off': draft.quantity >= MALL_INQUIRY_MAX_QUANTITY }">+</text>
              </button>
            </view>
            <text class="mall-inquiry__unit">台</text>
          </view>
          <view class="mall-inquiry__row">
            <text class="mall-inquiry__label">交付城市<text class="mall-inquiry__required">*</text></text>
            <text class="mall-inquiry__value" data-testid="mall-inquiry-city">{{ cityName ?? '当前城市' }}</text>
          </view>
          <button class="mall-inquiry__row" data-testid="mall-inquiry-date" @click="dateSheet = true">
            <text class="mall-inquiry__label">期望交期</text>
            <text
              :class="['mall-inquiry__value', { 'mall-inquiry__value--empty': !draft.expectedDate }]"
              data-testid="mall-inquiry-date-value"
            >
              {{ draft.expectedDate ? formatExpectedDate(draft.expectedDate) : '选填' }}
            </text>
            <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
          <view class="mall-inquiry__row mall-inquiry__row--last">
            <text class="mall-inquiry__label">询价身份<text class="mall-inquiry__required">*</text></text>
            <text class="mall-inquiry__value" data-testid="mall-inquiry-buyer">{{ buyerView?.text ?? '' }}</text>
          </view>
        </view>

        <view class="mall-inquiry__card mall-inquiry__merchants" data-testid="mall-inquiry-merchants">
          <view class="mall-inquiry__merchants-head">
            <text class="mall-inquiry__card-title">发给哪些商家</text>
            <text class="mall-inquiry__picked" data-testid="mall-inquiry-picked">已选 {{ pickedCount }} / {{ MALL_INQUIRY_MAX_MERCHANTS }}</text>
          </view>
          <button
            v-for="offer in offers"
            :key="offer.offerId"
            :class="['mall-inquiry__merchant', { 'mall-inquiry__merchant--off': !offer.canInquire }]"
            :data-testid="`mall-inquiry-offer-${offer.offerId}`"
            role="checkbox"
            :aria-checked="draft.offerIds.includes(offer.offerId)"
            :aria-disabled="!offer.canInquire"
            @click="toggleOffer(offer)"
          >
            <view :class="['mall-inquiry__box', { 'mall-inquiry__box--on': draft.offerIds.includes(offer.offerId) }]">
              <LineIcon v-if="draft.offerIds.includes(offer.offerId)" name="check" :size="12" color="#ffffff" :stroke-width="3" />
            </view>
            <view class="mall-inquiry__merchant-copy">
              <text class="mall-inquiry__merchant-name">{{ offer.merchant.name }}</text>
              <text class="mall-inquiry__merchant-note" :data-testid="`mall-inquiry-offer-note-${offer.offerId}`">
                {{ inquiryOfferNote(offer, MALL_MERCHANT_KIND_LABELS[offer.merchant.kind]) }}
              </text>
            </view>
          </button>
        </view>

        <text class="mall-inquiry__footnote" data-testid="mall-inquiry-footnote">
          提交后商家在 72 小时内报价，在“我的 → 需求与报价”中比选。提交询价不产生费用。
        </text>
      </view>

      <view class="mall-inquiry__bar">
        <view v-if="blocked" class="mall-inquiry__blocked" role="status" data-testid="mall-inquiry-blocked">
          <LineIcon name="info" :size="16" color="#ba470b" :stroke-width="2" />
          <text>{{ blocked }}</text>
        </view>
        <button
          class="mall-inquiry__submit"
          data-testid="mall-inquiry-submit"
          :disabled="!canSubmit"
          @click="submit"
        >
          {{ submitting ? '正在提交…' : '提交询价' }}
        </button>
      </view>
    </template>

    <!-- Below the uni picker (999) like every sheet of the project. -->
    <view v-if="dateSheet && draft" class="mall-inquiry__sheet-wrap" data-testid="mall-inquiry-date-sheet">
      <view class="mall-inquiry__mask" aria-label="关闭" @click="dateSheet = false" />
      <view class="mall-inquiry__sheet" role="dialog" aria-modal="true" aria-label="期望交期">
        <view class="mall-inquiry__sheet-head">
          <view class="mall-inquiry__sheet-copy">
            <text class="mall-inquiry__sheet-title">期望交期</text>
            <text class="mall-inquiry__sheet-hint">希望在这一天之前交付，交期以商家报价为准</text>
          </view>
          <button class="mall-inquiry__sheet-close" aria-label="收起" @click="dateSheet = false">
            <LineIcon name="close" :size="20" color="#536178" :stroke-width="2" />
          </button>
        </view>
        <button
          :class="['mall-inquiry__option', { 'mall-inquiry__option--on': !draft.expectedDate }]"
          data-testid="mall-inquiry-date-none"
          :aria-pressed="!draft.expectedDate"
          @click="chooseDate(null)"
        >
          <text>不限</text>
        </button>
        <button
          v-for="choice in dateChoices"
          :key="choice.days"
          :class="['mall-inquiry__option', { 'mall-inquiry__option--on': draft.expectedDate === choice.date }]"
          :data-testid="`mall-inquiry-date-${choice.days}`"
          :aria-pressed="draft.expectedDate === choice.date"
          @click="chooseDate(choice.date)"
        >
          <text>{{ choice.label }}</text>
          <text class="mall-inquiry__option-note">{{ choice.days }} 天内</text>
        </button>
        <picker mode="date" :start="earliest" :end="latest" :value="draft.expectedDate ?? earliest" @change="pickDate">
          <view class="mall-inquiry__option" data-testid="mall-inquiry-date-other">
            <text>选择其他日期</text>
            <LineIcon name="calendar" :size="18" color="#738096" />
          </view>
        </picker>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board PdInquiry (390 wide). */
.mall-inquiry {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
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

.mall-inquiry__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  padding: 12px 16px;
}

.mall-inquiry__card {
  border-radius: 12px;
  background: #ffffff;
}

.mall-inquiry__product {
  display: flex;
  gap: 12px;
  padding: 12px;
}

.mall-inquiry__image {
  width: 64px;
  height: 64px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.mall-inquiry__image--empty {
  display: flex;
  align-items: center;
  justify-content: center;
}

.mall-inquiry__product-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  gap: 2px;
}

.mall-inquiry__product-name {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.mall-inquiry__price-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}

.mall-inquiry__price {
  color: #e85400;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.mall-inquiry__price-note {
  color: #e85400;
  font-size: 12px;
  line-height: 17px;
}

.mall-inquiry__product-meta {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.mall-inquiry__fields {
  display: flex;
  flex-direction: column;
  padding: 4px 14px;
}

.mall-inquiry__requirement {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f1f3;
}

.mall-inquiry__requirement-head {
  display: flex;
  align-items: center;
}

.mall-inquiry__label {
  width: 76px;
  flex: none;
  color: #536178;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.mall-inquiry__requirement-head .mall-inquiry__label {
  width: auto;
}

.mall-inquiry__required {
  color: #d64f67;
}

.mall-inquiry__counter {
  margin-left: auto;
  color: #91a0b4;
  font-size: 12px;
  line-height: 17px;
}

.mall-inquiry__counter--over {
  color: #d64f67;
}

.mall-inquiry__textarea {
  width: 100%;
  height: 72px;
  padding: 8px 10px;
  border-radius: 8px;
  color: #202c42;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 1.5;
  box-sizing: border-box;
}

.mall-inquiry__placeholder {
  color: #91a0b4;
}

.mall-inquiry__row {
  display: flex;
  width: 100%;
  min-height: 44px;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid #f0f1f3;
}

.mall-inquiry__row--last {
  border-bottom: 0;
}

.mall-inquiry__value {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mall-inquiry__value--empty {
  color: #91a0b4;
}

.mall-inquiry__unit {
  color: #536178;
  font-size: 14px;
}

.mall-inquiry__stepper {
  display: flex;
  flex: none;
  align-items: center;
  border: 1px solid #e5ebf3;
  border-radius: 8px;
}

.mall-inquiry__step {
  display: flex;
  width: 36px;
  height: 44px;
  margin: -7px 0;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  text-align: center;
}

.mall-inquiry__step-off {
  color: #c9d3e1;
}

/* uni-button greys a disabled button's background; the stepper stays flat. */
.mall-inquiry__step[disabled] {
  color: #c9d3e1;
  background: transparent;
}

.mall-inquiry__step-value {
  display: flex;
  width: 40px;
  height: 30px;
  align-items: center;
  justify-content: center;
  border-right: 1px solid #e5ebf3;
  border-left: 1px solid #e5ebf3;
  font-size: 14px;
  line-height: 30px;
}

.mall-inquiry__merchants {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
}

.mall-inquiry__merchants-head {
  display: flex;
  align-items: baseline;
}

.mall-inquiry__card-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.mall-inquiry__picked {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.mall-inquiry__merchant {
  display: flex;
  width: 100%;
  min-height: 44px;
  margin: -4px 0;
  align-items: center;
  gap: 10px;
}

.mall-inquiry__box {
  display: flex;
  width: 18px;
  height: 18px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #c9d3e1;
  border-radius: 4px;
  background: #ffffff;
  box-sizing: border-box;
}

.mall-inquiry__box--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

/* Board PdInquiry: the merchant on the left, how it sells on the right of the same row. */
.mall-inquiry__merchant-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.mall-inquiry__merchant-name {
  flex: none;
  font-size: 15px;
  line-height: 21px;
  white-space: nowrap;
}

.mall-inquiry__merchant-note {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mall-inquiry__merchant--off .mall-inquiry__box {
  border-color: #e5ebf3;
  background: #f5f7fc;
}

.mall-inquiry__merchant--off .mall-inquiry__merchant-name,
.mall-inquiry__merchant--off .mall-inquiry__merchant-note {
  color: #a6b0bf;
}

.mall-inquiry__footnote {
  color: #627187;
  font-size: 12px;
  line-height: 1.6;
}

.mall-inquiry__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.mall-inquiry__blocked {
  display: flex;
  margin: -10px -16px 10px;
  padding: 7px 16px;
  align-items: center;
  gap: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  line-height: 17px;
}

.mall-inquiry__submit {
  height: 46px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
}

.mall-inquiry__submit[disabled] {
  background: #c9d3e1;
}

.mall-inquiry__sheet-wrap {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
}

.mall-inquiry__mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.mall-inquiry__sheet {
  position: relative;
  display: flex;
  width: 100%;
  max-height: 80vh;
  flex-direction: column;
  gap: 8px;
  padding: 8px 16px calc(24px + env(safe-area-inset-bottom));
  overflow-y: auto;
  border-radius: 20px 20px 0 0;
  background: #ffffff;
  box-sizing: border-box;
}

.mall-inquiry__sheet-head {
  display: flex;
  align-items: center;
}

.mall-inquiry__sheet-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.mall-inquiry__sheet-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.mall-inquiry__sheet-hint {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.mall-inquiry__sheet-close {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.mall-inquiry__option {
  display: flex;
  width: 100%;
  min-height: 48px;
  padding: 0 14px;
  align-items: center;
  justify-content: space-between;
  border: 1.5px solid #e5ebf3;
  border-radius: 10px;
  font-size: 15px;
  box-sizing: border-box;
}

.mall-inquiry__option--on {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fffaf5;
}

.mall-inquiry__option-note {
  color: #738096;
  font-size: 12px;
}

.mall-inquiry__skeleton {
  height: 88px;
  border-radius: 12px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.mall-inquiry__skeleton--tall {
  height: 220px;
}

.mall-inquiry__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 32px;
}

.mall-inquiry__state-title {
  font-size: 16px;
  font-weight: 700;
  text-align: center;
}

.mall-inquiry__state-text {
  color: #627187;
  font-size: 14px;
  line-height: 20px;
}

.mall-inquiry__state-button {
  height: 44px;
  padding: 0 24px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
  line-height: 44px;
  white-space: nowrap;
}
</style>
