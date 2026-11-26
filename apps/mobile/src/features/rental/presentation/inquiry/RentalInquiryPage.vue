<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import { computed, ref, watch } from 'vue'

import { maskPhone } from '@/features/account/application/address-form'
import { addressApi } from '@/features/account/data/address-api-client'
import type { Address } from '@/features/account/domain/address'
import { ApiRequestError } from '@/shared/http/public-api-request'

import {
  RENTAL_HANDOVER_LABELS,
  RENTAL_RETURN_LABELS,
  describeRentalLessee,
  formatRentalReference,
} from '../../application/rental-format'
import {
  RENTAL_DAY_CHOICES,
  clampRentalDays,
  clampRentalQuantity,
  createRentalInquiryDraft,
  createRentalKeyHolder,
  describeRentalSubmitFailure,
  formatRentalDay,
  RENTAL_PAYMENT_UNAVAILABLE_TEXT,
  rentalDraftProblem,
  rentalEndDate,
  rentalHandoverNote,
  rentalReferenceRent,
  rentalReturnNote,
  rentalStartOptions,
  toRentalInquiryInput,
  type RentalInquiryDraft,
} from '../../application/rental-form'
import { rentalApi } from '../../data/rental-api-client'
import {
  RENTAL_MAX_DAYS,
  RENTAL_MAX_QUANTITY,
  RENTAL_MIN_DAYS,
  RENTAL_MIN_QUANTITY,
  RENTAL_NOTE_MAX_LENGTH,
  type RentalHandoverMode,
  type RentalLessee,
  type RentalOfferView,
  type RentalReturnMode,
} from '../../domain/rental'
import type { RentalInquiryTarget } from '../../navigation/rental-routes'
import RentalPageHeader from '../shared/RentalPageHeader.vue'

const props = defineProps<{
  target: RentalInquiryTarget | null
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
}>()

const emit = defineEmits<{
  back: []
  login: []
  // The inquiry is sent: its quote page takes this page's place.
  submitted: [inquiryId: string]
  // 管理地址 ›: the address book; the list is read again when the page shows again.
  manageAddresses: []
  browse: []
}>()

type OfferState =
  | { kind: 'loading' }
  | { kind: 'ready'; offer: RentalOfferView }
  | { kind: 'missing' }
  | { kind: 'error' }

const HANDOVER_MODES: RentalHandoverMode[] = ['DELIVERY', 'PICKUP']
const RETURN_MODES: RentalReturnMode[] = ['COLLECT', 'DROP_OFF']

const offerState = ref<OfferState>({ kind: 'loading' })
const addresses = ref<Address[]>([])
const lessee = ref<RentalLessee | null>(null)
const draft = ref<RentalInquiryDraft>(createRentalInquiryDraft(new Date()))
const sheet = ref<'DATE' | 'PLACE' | null>(null)
// Set when the server says this offer cannot be asked about from here (no online payment, gone).
const blocked = ref<string | null>(null)
const submitting = ref(false)
const keyHolder = createRentalKeyHolder()
let requestVersion = 0

const offer = computed(() => (offerState.value.kind === 'ready' ? offerState.value.offer : null))
const cover = computed(() => (offer.value ? offer.value.coverUrl : null))
const startOptions = computed(() => rentalStartOptions(new Date()))
const endDate = computed(() => rentalEndDate(draft.value.startDate, draft.value.days))
const reference = computed(() => (offer.value ? rentalReferenceRent(offer.value, draft.value.days, draft.value.quantity) : null))
const chosenAddress = computed(() => addresses.value.find((item) => item.addressId === draft.value.addressId) ?? null)
const lesseeView = computed(() => (lessee.value ? describeRentalLessee(lessee.value) : null))
const handoverNote = computed(() => (offer.value ? rentalHandoverNote(draft.value.handoverMode, offer.value) : ''))
const canSubmit = computed(() => Boolean(offer.value) && !blocked.value && !submitting.value)

function inCity(address: Address): boolean {
  return address.region.cityCode === props.target?.cityCode
}

// Keep the chosen place when it is still there; else the default address of this city, else the first one here.
function pickAddress(list: Address[]): void {
  const usable = list.filter(inCity)
  if (usable.some((item) => item.addressId === draft.value.addressId)) return
  const next = usable.find((item) => item.isDefault) ?? usable[0] ?? null
  draft.value = { ...draft.value, addressId: next?.addressId ?? null }
}

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
    offerState.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  if (offerState.value.kind !== 'ready') offerState.value = { kind: 'loading' }
  try {
    const [loadedOffer, loadedAddresses, loadedLessee] = await Promise.all([
      rentalApi.readOffer(target.productId, target.offerId, target.cityCode).catch((error: unknown) => {
        if (error instanceof ApiRequestError && (error.status === 404 || error.status === 400)) return null
        throw error
      }),
      addressApi.list(token),
      rentalApi.readLessee(token),
    ])
    if (requested !== requestVersion) return
    addresses.value = loadedAddresses
    lessee.value = loadedLessee
    pickAddress(loadedAddresses)
    offerState.value = loadedOffer ? { kind: 'ready', offer: loadedOffer } : { kind: 'missing' }
    // The detail says whether the lessor takes payment online: say so now, not after 提交.
    if (loadedOffer && !loadedOffer.onlinePayment) blocked.value = RENTAL_PAYMENT_UNAVAILABLE_TEXT
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    offerState.value = { kind: 'error' }
  }
}

function setQuantity(value: number): void {
  draft.value = { ...draft.value, quantity: clampRentalQuantity(value) }
}

function setDays(value: number): void {
  draft.value = { ...draft.value, days: clampRentalDays(value) }
}

function chooseStart(date: string): void {
  draft.value = { ...draft.value, startDate: date }
  sheet.value = null
}

function choosePlace(address: Address): void {
  if (!inCity(address)) return
  draft.value = { ...draft.value, addressId: address.addressId }
  sheet.value = null
}

function setHandover(mode: RentalHandoverMode): void {
  draft.value = { ...draft.value, handoverMode: mode }
}

function setReturn(mode: RentalReturnMode): void {
  draft.value = { ...draft.value, returnMode: mode }
}

function setNote(event: Event): void {
  const value = (event as Event & { detail?: { value?: string } }).detail?.value?.toString() ?? ''
  draft.value = { ...draft.value, note: value }
}

function manageAddresses(): void {
  sheet.value = null
  emit('manageAddresses')
}

async function submit(): Promise<void> {
  const token = props.accessToken
  const current = offer.value
  if (!token || !current || !canSubmit.value) return
  const problem = rentalDraftProblem(draft.value, new Date())
  if (problem) {
    uni.showToast({ title: problem, icon: 'none' })
    if (!draft.value.addressId) sheet.value = 'PLACE'
    return
  }
  const input = toRentalInquiryInput(current.offerId, draft.value)
  submitting.value = true
  try {
    const inquiry = await rentalApi.submitInquiry(token, keyHolder.keyFor(input), input)
    uni.showToast({ title: '询价已提交', icon: 'success' })
    emit('submitted', inquiry.inquiryId)
  } catch (error) {
    const failure = describeRentalSubmitFailure(error instanceof ApiRequestError
      ? { status: error.status, code: error.code, message: error.message }
      : null)
    if (failure.kind === 'UNAUTHORIZED') {
      emit('login')
    } else if (failure.kind === 'BLOCKED') {
      blocked.value = failure.message
    } else if (failure.kind === 'ADDRESS') {
      uni.showToast({ title: failure.message, icon: 'none' })
      draft.value = { ...draft.value, addressId: null }
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
  <view class="rental-inquiry" data-testid="rental-inquiry">
    <RentalPageHeader title="填写租赁询价单" @back="emit('back')" />

    <view v-if="offerState.kind === 'loading'" class="rental-inquiry__body" aria-label="正在加载">
      <view class="rental-inquiry__skeleton" />
      <view class="rental-inquiry__skeleton" />
    </view>

    <view v-else-if="offerState.kind === 'missing'" class="rental-inquiry__state" data-testid="rental-inquiry-missing">
      <text class="rental-inquiry__state-title">这件设备已下架或本城暂不出租</text>
      <button class="rental-inquiry__state-button" data-testid="rental-inquiry-browse" @click="emit('browse')">去商城看看</button>
    </view>

    <view v-else-if="offerState.kind === 'error'" class="rental-inquiry__state" data-testid="rental-inquiry-error">
      <text class="rental-inquiry__state-title">加载失败</text>
      <text class="rental-inquiry__state-text">请检查网络后重试</text>
      <button class="rental-inquiry__state-button" data-testid="rental-inquiry-retry" @click="load">重试</button>
    </view>

    <template v-else-if="offer">
      <view class="rental-inquiry__body">
        <view class="rental-inquiry__card" data-testid="rental-inquiry-device">
          <text class="rental-inquiry__card-title">设备清单</text>
          <view class="rental-inquiry__device">
            <image v-if="cover" class="rental-inquiry__image" :src="cover" mode="aspectFill" />
            <view v-else class="rental-inquiry__image rental-inquiry__image--empty">
              <LineIcon name="gear" :size="28" color="#91a0b4" />
            </view>
            <view class="rental-inquiry__device-copy">
              <text class="rental-inquiry__device-name" data-testid="rental-inquiry-name">{{ offer.productName }}</text>
              <text class="rental-inquiry__device-merchant" data-testid="rental-inquiry-merchant">出租方 {{ offer.merchantName }}</text>
              <text v-if="!offer.onlinePayment" class="rental-inquiry__device-unpaid" data-testid="rental-inquiry-unpaid">暂未开通在线支付</text>
              <view class="rental-inquiry__device-line">
                <text class="rental-inquiry__price" data-testid="rental-inquiry-price">
                  {{ formatRentalReference(offer.amountMinor, offer.unit) }}
                </text>
                <view class="rental-inquiry__stepper">
                  <button
                    class="rental-inquiry__step"
                    data-testid="rental-qty-minus"
                    aria-label="减少台数"
                    :disabled="draft.quantity <= RENTAL_MIN_QUANTITY"
                    @click="setQuantity(draft.quantity - 1)"
                  >
                    <text :class="{ 'rental-inquiry__step-off': draft.quantity <= RENTAL_MIN_QUANTITY }">−</text>
                  </button>
                  <text class="rental-inquiry__step-value" data-testid="rental-qty">{{ draft.quantity }}</text>
                  <button
                    class="rental-inquiry__step"
                    data-testid="rental-qty-plus"
                    aria-label="增加台数"
                    :disabled="draft.quantity >= RENTAL_MAX_QUANTITY"
                    @click="setQuantity(draft.quantity + 1)"
                  >
                    <text :class="{ 'rental-inquiry__step-off': draft.quantity >= RENTAL_MAX_QUANTITY }">+</text>
                  </button>
                </view>
              </view>
            </view>
          </view>
        </view>

        <view class="rental-inquiry__card">
          <view class="rental-inquiry__card-head">
            <text class="rental-inquiry__card-title">租期<text class="rental-inquiry__required">*</text></text>
            <text class="rental-inquiry__card-hint">档期由商家报价时确认</text>
          </view>
          <view class="rental-inquiry__period">
            <button class="rental-inquiry__date rental-inquiry__date--on" data-testid="rental-start" @click="sheet = 'DATE'">
              <text class="rental-inquiry__date-label">起租日</text>
              <text class="rental-inquiry__date-value" data-testid="rental-start-value">{{ formatRentalDay(draft.startDate) }}</text>
            </button>
            <text class="rental-inquiry__days" data-testid="rental-days-total">共 {{ draft.days }} 天</text>
            <view class="rental-inquiry__date">
              <text class="rental-inquiry__date-label">到期日</text>
              <text class="rental-inquiry__date-value" data-testid="rental-end">{{ formatRentalDay(endDate) }}</text>
            </view>
          </view>
          <view class="rental-inquiry__chips" role="group" aria-label="租用天数">
            <button
              v-for="choice in RENTAL_DAY_CHOICES"
              :key="choice"
              :class="['rental-inquiry__chip', { 'rental-inquiry__chip--on': choice === draft.days }]"
              :data-testid="`rental-days-${choice}`"
              :aria-pressed="choice === draft.days"
              @click="setDays(choice)"
            >
              {{ choice }} 天
            </button>
          </view>
          <view class="rental-inquiry__day-row">
            <text class="rental-inquiry__day-label">其他天数（{{ RENTAL_MIN_DAYS }}–{{ RENTAL_MAX_DAYS }} 天）</text>
            <view class="rental-inquiry__stepper">
              <button
                class="rental-inquiry__step"
                data-testid="rental-days-minus"
                aria-label="减少天数"
                :disabled="draft.days <= RENTAL_MIN_DAYS"
                @click="setDays(draft.days - 1)"
              >
                <text :class="{ 'rental-inquiry__step-off': draft.days <= RENTAL_MIN_DAYS }">−</text>
              </button>
              <text class="rental-inquiry__step-value" data-testid="rental-days">{{ draft.days }}</text>
              <button
                class="rental-inquiry__step"
                data-testid="rental-days-plus"
                aria-label="增加天数"
                :disabled="draft.days >= RENTAL_MAX_DAYS"
                @click="setDays(draft.days + 1)"
              >
                <text :class="{ 'rental-inquiry__step-off': draft.days >= RENTAL_MAX_DAYS }">+</text>
              </button>
            </view>
          </view>
          <view v-if="reference" class="rental-inquiry__reference" data-testid="rental-reference">
            <text class="rental-inquiry__reference-label">参考租金</text>
            <text class="rental-inquiry__reference-formula" data-testid="rental-reference-formula">{{ reference.formula }}</text>
            <text class="rental-inquiry__reference-amount" data-testid="rental-reference-amount">{{ reference.amount }}</text>
          </view>
        </view>

        <view class="rental-inquiry__card rental-inquiry__card--rows">
          <text class="rental-inquiry__card-title rental-inquiry__card-title--rows">使用与交接</text>
          <button class="rental-inquiry__row" data-testid="rental-place" @click="sheet = 'PLACE'">
            <LineIcon name="pin" :size="18" color="#ff6a00" />
            <text class="rental-inquiry__row-label">使用地点<text class="rental-inquiry__required">*</text></text>
            <text
              :class="['rental-inquiry__row-value', { 'rental-inquiry__row-value--empty': !chosenAddress }]"
              data-testid="rental-place-value"
            >
              {{ chosenAddress ? chosenAddress.detail : '请选择收货地址' }}
            </text>
            <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
          <view class="rental-inquiry__row rental-inquiry__row--wrap">
            <LineIcon name="swap" :size="18" color="#ff6a00" />
            <text class="rental-inquiry__row-label">交接方式<text class="rental-inquiry__required">*</text></text>
            <view class="rental-inquiry__segment" role="radiogroup" aria-label="交接方式">
              <button
                v-for="mode in HANDOVER_MODES"
                :key="mode"
                :class="['rental-inquiry__segment-item', { 'rental-inquiry__segment-item--on': mode === draft.handoverMode }]"
                role="radio"
                :aria-checked="mode === draft.handoverMode"
                :data-testid="`rental-handover-${mode.toLowerCase()}`"
                @click="setHandover(mode)"
              >
                {{ RENTAL_HANDOVER_LABELS[mode] }}
              </button>
            </view>
          </view>
          <text class="rental-inquiry__row-note" data-testid="rental-handover-note">{{ handoverNote }}</text>
          <view class="rental-inquiry__row rental-inquiry__row--wrap">
            <LineIcon d="M20 12a8 8 0 1 1-2.3-5.6M20 4v4h-4" :size="18" color="#ff6a00" />
            <text class="rental-inquiry__row-label">归还方式<text class="rental-inquiry__required">*</text></text>
            <view class="rental-inquiry__segment" role="radiogroup" aria-label="归还方式">
              <button
                v-for="mode in RETURN_MODES"
                :key="mode"
                :class="['rental-inquiry__segment-item', { 'rental-inquiry__segment-item--on': mode === draft.returnMode }]"
                role="radio"
                :aria-checked="mode === draft.returnMode"
                :data-testid="`rental-return-${mode.toLowerCase()}`"
                @click="setReturn(mode)"
              >
                {{ RENTAL_RETURN_LABELS[mode] }}
              </button>
            </view>
          </view>
          <text class="rental-inquiry__row-note">{{ rentalReturnNote(draft.returnMode) }}</text>
          <view class="rental-inquiry__row">
            <LineIcon name="person" :size="18" color="#ff6a00" />
            <text class="rental-inquiry__row-label">联系人</text>
            <text class="rental-inquiry__row-value" data-testid="rental-contact">{{ chosenAddress?.recipientName ?? '随地址填写' }}</text>
          </view>
          <view class="rental-inquiry__row">
            <LineIcon name="phone" :size="18" color="#ff6a00" />
            <text class="rental-inquiry__row-label">手机号</text>
            <text class="rental-inquiry__row-value" data-testid="rental-phone">{{ chosenAddress ? maskPhone(chosenAddress.phone) : '随地址填写' }}</text>
          </view>
          <text class="rental-inquiry__row-note rental-inquiry__row-note--last">联系人和手机号取自所选地址，商家在你付款后才能看到完整手机号</text>
        </view>

        <view class="rental-inquiry__card">
          <text class="rental-inquiry__card-title">询价身份</text>
          <view v-if="lesseeView" class="rental-inquiry__identity" data-testid="rental-lessee">
            <view class="rental-inquiry__identity-copy">
              <text class="rental-inquiry__identity-title">{{ lesseeView.tag }}</text>
              <text class="rental-inquiry__identity-text">{{ lesseeView.text }}</text>
            </view>
            <view class="rental-inquiry__identity-check"><LineIcon name="check" :size="12" color="#ffffff" :stroke-width="3" /></view>
          </view>
          <text class="rental-inquiry__card-hint rental-inquiry__card-hint--block">按账号所属组织记录，不能在这里切换</text>
        </view>

        <view class="rental-inquiry__card">
          <text class="rental-inquiry__card-title">补充说明</text>
          <textarea
            class="rental-inquiry__note"
            data-testid="rental-note"
            :value="draft.note"
            :maxlength="RENTAL_NOTE_MAX_LENGTH"
            placeholder="例如夜间使用、需要现场讲解（选填）"
            placeholder-class="rental-inquiry__placeholder"
            aria-label="补充说明"
            @input="setNote"
          />
        </view>

        <text class="rental-inquiry__footnote">
          提交后商家在 7 天内报价，可在“我的 → 需求与报价”中查看。押金和赔付以合同约定为准。
        </text>
      </view>

      <view class="rental-inquiry__bar">
        <view v-if="blocked" class="rental-inquiry__blocked" role="status" data-testid="rental-blocked">
          <LineIcon name="info" :size="16" color="#ba470b" :stroke-width="2" />
          <text>{{ blocked }}</text>
        </view>
        <view v-else class="rental-inquiry__free">
          <LineIcon name="shield" :size="14" color="#11804a" />
          <text>提交询价不产生费用，确认合同后才付款</text>
        </view>
        <button
          class="rental-inquiry__submit"
          data-testid="rental-submit"
          :disabled="!canSubmit"
          @click="submit"
        >
          {{ submitting ? '正在提交…' : '提交询价' }}
        </button>
      </view>
    </template>

    <view v-if="sheet === 'DATE'" class="rental-inquiry__sheet-wrap" data-testid="rental-start-sheet">
      <view class="rental-inquiry__mask" aria-label="关闭" @click="sheet = null" />
      <view class="rental-inquiry__sheet" role="dialog" aria-modal="true" aria-label="选择起租日">
        <view class="rental-inquiry__sheet-head">
          <view class="rental-inquiry__sheet-copy">
            <text class="rental-inquiry__sheet-title">选择起租日</text>
            <text class="rental-inquiry__card-hint">这是你的期望日期，档期由商家报价时确认</text>
          </view>
          <button class="rental-inquiry__sheet-close" aria-label="收起" @click="sheet = null">
            <LineIcon name="close" :size="20" color="#536178" :stroke-width="2" />
          </button>
        </view>
        <view class="rental-inquiry__dates" role="group" aria-label="起租日">
          <button
            v-for="option in startOptions"
            :key="option.date"
            :class="['rental-inquiry__date-option', { 'rental-inquiry__date-option--on': option.date === draft.startDate }]"
            :data-testid="`rental-start-${option.date}`"
            :aria-pressed="option.date === draft.startDate"
            @click="chooseStart(option.date)"
          >
            <text class="rental-inquiry__date-md">{{ option.monthDay }}</text>
            <text class="rental-inquiry__date-wk">{{ option.weekday }}</text>
          </button>
        </view>
      </view>
    </view>

    <view v-if="sheet === 'PLACE'" class="rental-inquiry__sheet-wrap" data-testid="rental-place-sheet">
      <view class="rental-inquiry__mask" aria-label="关闭" @click="sheet = null" />
      <view class="rental-inquiry__sheet" role="dialog" aria-modal="true" aria-label="选择使用地点">
        <view class="rental-inquiry__sheet-head">
          <text class="rental-inquiry__sheet-title">选择使用地点</text>
          <button class="rental-inquiry__sheet-close" aria-label="收起" @click="sheet = null">
            <LineIcon name="close" :size="20" color="#536178" :stroke-width="2" />
          </button>
        </view>
        <text v-if="addresses.length === 0" class="rental-inquiry__card-hint rental-inquiry__card-hint--block" data-testid="rental-place-empty">
          还没有收货地址，先添加一个
        </text>
        <button
          v-for="address in addresses"
          :key="address.addressId"
          :class="[
            'rental-inquiry__place',
            { 'rental-inquiry__place--on': address.addressId === draft.addressId, 'rental-inquiry__place--off': !inCity(address) },
          ]"
          :data-testid="`rental-place-${address.addressId}`"
          :aria-pressed="address.addressId === draft.addressId"
          :aria-disabled="!inCity(address)"
          @click="choosePlace(address)"
        >
          <view class="rental-inquiry__place-copy">
            <text class="rental-inquiry__place-name">{{ address.recipientName }} {{ maskPhone(address.phone) }}</text>
            <text class="rental-inquiry__place-text">{{ address.region.provinceName }}{{ address.region.cityName }} {{ address.detail }}</text>
            <text v-if="!inCity(address)" class="rental-inquiry__place-off-note">不在这件设备的出租城市</text>
          </view>
          <view v-if="address.addressId === draft.addressId" class="rental-inquiry__identity-check">
            <LineIcon name="check" :size="12" color="#ffffff" :stroke-width="3" />
          </view>
        </button>
        <button class="rental-inquiry__manage" data-testid="rental-place-manage" @click="manageAddresses">
          {{ addresses.length === 0 ? '新增地址 ›' : '管理地址 ›' }}
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board RentalInquiry (390 wide). */
.rental-inquiry {
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

.rental-inquiry__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}

.rental-inquiry__card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.rental-inquiry__card--rows {
  gap: 0;
  padding: 14px 14px 2px;
}

.rental-inquiry__card-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.rental-inquiry__card-title--rows {
  margin-bottom: 6px;
}

.rental-inquiry__required {
  color: #d64f67;
}

.rental-inquiry__card-head {
  display: flex;
  align-items: baseline;
}

.rental-inquiry__card-hint {
  margin-left: auto;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.rental-inquiry__card-hint--block {
  margin-left: 0;
  white-space: normal;
}

.rental-inquiry__device {
  display: flex;
  gap: 12px;
}

.rental-inquiry__image {
  width: 80px;
  height: 80px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.rental-inquiry__image--empty {
  display: flex;
  align-items: center;
  justify-content: center;
}

.rental-inquiry__device-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  justify-content: space-between;
}

.rental-inquiry__device-name {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.rental-inquiry__device-merchant {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.rental-inquiry__device-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rental-inquiry__price {
  color: #e85400;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  white-space: nowrap;
}

.rental-inquiry__stepper {
  display: flex;
  flex: none;
  margin-left: auto;
  align-items: center;
  border: 1px solid #e5ebf3;
  border-radius: 8px;
}

.rental-inquiry__step {
  display: flex;
  width: 36px;
  height: 44px;
  margin: -7px 0;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  text-align: center;
}

.rental-inquiry__step-off {
  color: #c9d3e1;
}

/* uni-button greys a disabled button's background; the board keeps the stepper flat. */
.rental-inquiry__step[disabled] {
  color: #c9d3e1;
  background: transparent;
}

.rental-inquiry__step-value {
  display: flex;
  width: 36px;
  height: 30px;
  align-items: center;
  justify-content: center;
  border-right: 1px solid #e5ebf3;
  border-left: 1px solid #e5ebf3;
  font-size: 14px;
  line-height: 30px;
  text-align: center;
}

.rental-inquiry__period {
  display: flex;
  align-items: stretch;
  gap: 8px;
}

.rental-inquiry__date {
  display: flex;
  min-width: 0;
  min-height: 60px;
  flex: 1 1 0;
  flex-direction: column;
  gap: 2px;
  padding: 8px 12px;
  border: 1.5px solid #f0f1f3;
  border-radius: 10px;
  background: #f5f7fc;
  box-sizing: border-box;
}

.rental-inquiry__date--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.rental-inquiry__date-label {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.rental-inquiry__date-value {
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  white-space: nowrap;
}

.rental-inquiry__days {
  flex: none;
  align-self: center;
  color: #ba470b;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.rental-inquiry__chips {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.rental-inquiry__chip {
  min-width: 0;
  height: 36px;
  border: 1.5px solid #e5ebf3;
  border-radius: 999px;
  background: #ffffff;
  font-size: 13px;
  line-height: 33px;
  text-align: center;
  white-space: nowrap;
  box-sizing: border-box;
}

.rental-inquiry__chip--on {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fffaf5;
  font-weight: 500;
}

.rental-inquiry__day-row {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
}

.rental-inquiry__day-label {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.rental-inquiry__reference {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  border-radius: 10px;
  background: #fff0e3;
  box-sizing: border-box;
}

.rental-inquiry__reference-label {
  flex: none;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.rental-inquiry__reference-formula {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rental-inquiry__reference-amount {
  flex: none;
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.rental-inquiry__row {
  display: flex;
  width: 100%;
  min-height: 48px;
  align-items: center;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
}

/* A narrow phone puts the two choices under the label instead of squeezing them. */
.rental-inquiry__row--wrap {
  flex-wrap: wrap;
  row-gap: 4px;
  padding: 6px 0;
  box-sizing: border-box;
}

.rental-inquiry__row-label {
  flex: none;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.rental-inquiry__row-value {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  font-size: 14px;
  line-height: 20px;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rental-inquiry__row-value--empty {
  color: #91a0b4;
}

.rental-inquiry__row-note {
  margin: -2px 0 10px 26px;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.rental-inquiry__row-note--last {
  margin-left: 0;
}

.rental-inquiry__segment {
  display: flex;
  flex: none;
  margin-left: auto;
  padding: 2px;
  border-radius: 999px;
  background: #eef2f8;
}

.rental-inquiry__segment-item {
  height: 36px;
  padding: 0 12px;
  border-radius: 999px;
  color: #536178;
  font-size: 13px;
  line-height: 36px;
  white-space: nowrap;
}

.rental-inquiry__segment-item--on {
  color: #ba470b;
  background: #ffffff;
  font-weight: 500;
}

.rental-inquiry__identity {
  display: flex;
  min-height: 60px;
  align-items: center;
  gap: 4px;
  padding: 8px 10px 8px 12px;
  border: 1.5px solid #ff6a00;
  border-radius: 12px;
  background: #fffaf5;
  box-sizing: border-box;
}

.rental-inquiry__identity-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.rental-inquiry__identity-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.rental-inquiry__identity-text {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.rental-inquiry__identity-check {
  display: flex;
  width: 18px;
  height: 18px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #ff6a00;
}

.rental-inquiry__note {
  width: 100%;
  height: 76px;
  padding: 10px 12px;
  border: 1px solid #e5ebf3;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 14px;
  line-height: 20px;
  box-sizing: border-box;
}

.rental-inquiry__placeholder {
  color: #91a0b4;
}

.rental-inquiry__footnote {
  padding: 0 2px;
  color: #627187;
  font-size: 12px;
  line-height: 19px;
}

.rental-inquiry__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  padding: 0 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.rental-inquiry__free,
.rental-inquiry__device-unpaid {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.rental-inquiry__free,
.rental-inquiry__blocked {
  display: flex;
  margin: 0 -16px 10px;
  padding: 7px 16px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 12px;
  line-height: 17px;
}

.rental-inquiry__free {
  color: #11804a;
  background: #eefaf3;
}

.rental-inquiry__blocked {
  justify-content: flex-start;
  color: #ba470b;
  background: #fff0e3;
}

.rental-inquiry__submit {
  height: 46px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
}

.rental-inquiry__submit[disabled] {
  background: #c9d3e1;
}

.rental-inquiry__sheet-wrap {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
}

.rental-inquiry__mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.rental-inquiry__sheet {
  position: relative;
  display: flex;
  width: 100%;
  max-height: 80vh;
  flex-direction: column;
  gap: 10px;
  padding: 8px 16px calc(24px + env(safe-area-inset-bottom));
  overflow-y: auto;
  border-radius: 20px 20px 0 0;
  background: #ffffff;
  box-sizing: border-box;
}

.rental-inquiry__sheet-head {
  display: flex;
  align-items: center;
}

.rental-inquiry__sheet-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.rental-inquiry__sheet-copy .rental-inquiry__card-hint {
  margin-left: 0;
  white-space: normal;
}

.rental-inquiry__sheet-title {
  flex: 1;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.rental-inquiry__sheet-close {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.rental-inquiry__dates {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.rental-inquiry__date-option {
  display: flex;
  min-width: 0;
  min-height: 56px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  padding: 6px 0;
  border: 1.5px solid #e5ebf3;
  border-radius: 10px;
  background: #ffffff;
  box-sizing: border-box;
}

.rental-inquiry__date-option--on {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fffaf5;
}

.rental-inquiry__date-md {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  white-space: nowrap;
}

.rental-inquiry__date-wk {
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.rental-inquiry__place {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1.5px solid #e5ebf3;
  border-radius: 12px;
  background: #ffffff;
  box-sizing: border-box;
}

.rental-inquiry__place--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.rental-inquiry__place--off {
  opacity: 0.55;
}

.rental-inquiry__place-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.rental-inquiry__place-name {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.rental-inquiry__place-text,
.rental-inquiry__place-off-note {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.rental-inquiry__place-off-note {
  color: #ba470b;
}

.rental-inquiry__manage {
  display: flex;
  min-height: 36px;
  align-self: flex-start;
  align-items: center;
  color: #ba470b;
  font-size: 13px;
}

.rental-inquiry__skeleton {
  height: 150px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.rental-inquiry__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 72px 32px;
}

.rental-inquiry__state-title {
  font-size: 16px;
  font-weight: 700;
  text-align: center;
}

.rental-inquiry__state-text {
  color: #627187;
  font-size: 14px;
  line-height: 20px;
}

.rental-inquiry__state-button {
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
