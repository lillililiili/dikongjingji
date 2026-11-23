<script setup lang="ts">
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { computed, ref, watch } from 'vue'

import type { PlatformProvince } from '@/shared/city/domain/city-directory'
import { currentOrDefaultCity } from '@/shared/city/application/current-or-default-city'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import { ApiRequestError } from '@/shared/http/public-api-request'
import RegionPickSheet from '@/shared/region/RegionPickSheet.vue'

import { describeRegion, regionOfCity } from '../application/address-book'
import {
  EMPTY_ADDRESS_FORM,
  findAddressFormProblems,
  formFromAddress,
  formatAddressLine,
  maskPhone,
  trimAddressForm,
} from '../application/address-form'
import { addressApi } from '../data/address-api-client'
import { regionDirectoryApi } from '../data/region-api-client'
import {
  ADDRESS_DETAIL_MAX_LENGTH,
  ADDRESS_LIMIT,
  ADDRESS_NAME_MAX_LENGTH,
  type Address,
  type AddressForm,
} from '../domain/address'

const props = defineProps<{
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
  refreshKey: number
  // Opened from the confirm page: tapping an address uses it for that order.
  selecting: boolean
  selectedAddressId: string | null
}>()

const emit = defineEmits<{
  back: []
  login: []
  choose: [addressId: string]
}>()

type ListState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error' }
type SheetMode = 'closed' | 'add' | 'edit'

const state = ref<ListState>({ kind: 'loading' })
const addresses = ref<Address[]>([])
const sheet = ref<SheetMode>('closed')
const editingId = ref<string | null>(null)
const form = ref<AddressForm>({ ...EMPTY_ADDRESS_FORM })
const sheetError = ref('')
const saving = ref(false)
const deleteAsked = ref(false)
// The region picker (board RegionPick, shared/region) opens over the form.
const pickingRegion = ref(false)
const provinces = ref<PlatformProvince[]>([])
const currentCityStore = useCurrentCityStore()
let requestVersion = 0

const canSave = computed(() => !saving.value && findAddressFormProblems(form.value).length === 0)
const regionText = computed(() =>
  describeRegion(provinces.value, regionOfCity(provinces.value, form.value.cityCode)))
const editingDefault = computed(() =>
  sheet.value === 'edit' && addresses.value.find((item) => item.addressId === editingId.value)?.isDefault === true)

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError
    && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

function valueOf(event: Event): string {
  return (event as Event & { detail?: { value?: string } }).detail?.value?.toString() ?? ''
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  const requested = ++requestVersion
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const loaded = await addressApi.list(token)
    if (requested !== requestVersion) return
    addresses.value = loaded
    state.value = { kind: 'ready' }
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    state.value = { kind: 'error' }
  }
}

async function ensureProvinces(): Promise<void> {
  if (provinces.value.length > 0) return
  try {
    provinces.value = (await regionDirectoryApi.load()).provinces
  } catch {
    sheetError.value = '地区列表没有读出来，请稍后重试'
  }
}

function openAdd(): void {
  sheet.value = 'add'
  editingId.value = null
  form.value = { ...EMPTY_ADDRESS_FORM, isDefault: addresses.value.length === 0 }
  sheetError.value = ''
  deleteAsked.value = false
  pickingRegion.value = false
  void ensureProvinces()
}

function openEdit(address: Address): void {
  sheet.value = 'edit'
  editingId.value = address.addressId
  form.value = formFromAddress(address)
  sheetError.value = ''
  deleteAsked.value = false
  pickingRegion.value = false
  void ensureProvinces()
}

function closeSheet(): void {
  if (saving.value) return
  sheet.value = 'closed'
}

async function openRegionPicker(): Promise<void> {
  await ensureProvinces()
  pickingRegion.value = true
}

function pickCity(cityCode: string): void {
  form.value = { ...form.value, cityCode }
  pickingRegion.value = false
}

function setField(key: 'recipientName' | 'phone' | 'detail', value: string): void {
  form.value = { ...form.value, [key]: value }
}

async function save(): Promise<void> {
  const token = props.accessToken
  if (!token || !canSave.value) return
  saving.value = true
  sheetError.value = ''
  try {
    const body = trimAddressForm(form.value)
    const saved = sheet.value === 'edit' && editingId.value
      ? await addressApi.update(token, editingId.value, body)
      : await addressApi.create(token, body)
    sheet.value = 'closed'
    await load()
    // A new address made while choosing one is the one the user wants.
    if (props.selecting) emit('choose', saved.addressId)
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    sheetError.value = error instanceof ApiRequestError ? error.message : '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

async function confirmDelete(): Promise<void> {
  const token = props.accessToken
  const target = editingId.value
  if (!token || !target || saving.value) return
  saving.value = true
  try {
    await addressApi.remove(token, target)
    sheet.value = 'closed'
    await load()
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    sheetError.value = error instanceof ApiRequestError ? error.message : '删除失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

function tapAddress(address: Address): void {
  if (props.selecting) emit('choose', address.addressId)
}

watch(() => [props.refreshKey, props.accessToken], () => { void load() }, { immediate: true })
</script>

<template>
  <view class="addresses" data-testid="address-book">
    <AppPageHeader title="地址管理" test-id="address-book" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="addresses__body" aria-label="正在加载">
      <view class="addresses__skeleton" />
    </view>

    <view v-else-if="state.kind === 'error'" class="addresses__state" data-testid="address-book-error">
      <text class="addresses__state-title">加载失败</text>
      <button class="addresses__state-button" data-testid="address-book-retry" @click="load">重试</button>
    </view>

    <view v-else class="addresses__body">
      <view v-if="selecting && addresses.length > 0" class="addresses__hint">
        <LineIcon name="info" :size="16" color="#ff6a00" :stroke-width="2" />
        <text>从确认订单进入时，点选地址即用于本单</text>
      </view>
      <view
        v-for="address in addresses"
        :key="address.addressId"
        :class="[
          'addresses__card',
          { 'addresses__card--chosen': selecting && address.addressId === selectedAddressId },
        ]"
        :data-testid="`address-${address.addressId}`"
        :data-name="address.recipientName"
      >
        <button class="addresses__main" :data-testid="`address-pick-${address.addressId}`" @click="tapAddress(address)">
          <view class="addresses__name-line">
            <text class="addresses__name">{{ address.recipientName }}</text>
            <text class="addresses__phone">{{ maskPhone(address.phone) }}</text>
            <text v-if="address.isDefault" class="addresses__default">默认</text>
          </view>
          <text class="addresses__line">{{ formatAddressLine(address) }}</text>
        </button>
        <button
          class="addresses__edit"
          :data-testid="`address-edit-${address.addressId}`"
          :aria-label="`编辑${address.recipientName}的地址`"
          @click="openEdit(address)"
        >
          <LineIcon d="M4 20h4L18.5 9.5a2.1 2.1 0 0 0-3-3L5 17zM13.5 7.5l3 3" :size="18" color="#536178" />
          <text>编辑</text>
        </button>
      </view>
      <view v-if="addresses.length === 0" class="addresses__empty" data-testid="address-book-empty">
        <text>还没有收货地址</text>
      </view>
      <text class="addresses__note">手机号仅用于配送联系，页面展示时打码</text>
    </view>

    <view v-if="state.kind === 'ready'" class="addresses__bar">
      <button
        :class="['addresses__add', { 'addresses__add--off': addresses.length >= ADDRESS_LIMIT }]"
        data-testid="address-add"
        :disabled="addresses.length >= ADDRESS_LIMIT"
        @click="openAdd"
      >
        ＋ 新增收货地址
      </button>
    </view>

    <view v-if="sheet !== 'closed'" class="addresses__mask" @click.self="closeSheet">
      <view class="addresses__sheet" role="dialog" data-testid="address-sheet">
        <view class="addresses__sheet-head">
          <text class="addresses__sheet-title">
            {{ sheet === 'edit' ? '编辑收货地址' : '新增收货地址' }}
          </text>
          <button class="addresses__sheet-close addresses__sheet-close--right" aria-label="关闭" @click="closeSheet">
            <LineIcon name="close" :size="20" color="#536178" :stroke-width="2" />
          </button>
        </view>

        <view class="addresses__fields">
          <view class="addresses__field">
            <text class="addresses__label">收货人</text>
            <input
              class="addresses__input"
              data-testid="address-name"
              type="text"
              placeholder="姓名"
              :maxlength="ADDRESS_NAME_MAX_LENGTH"
              :value="form.recipientName"
              @input="setField('recipientName', valueOf($event))"
            />
          </view>
          <view class="addresses__field">
            <text class="addresses__label">手机号</text>
            <input
              class="addresses__input"
              data-testid="address-phone"
              type="number"
              placeholder="11 位手机号"
              :maxlength="11"
              :value="form.phone"
              @input="setField('phone', valueOf($event))"
            />
          </view>
          <button class="addresses__field addresses__field--button" data-testid="address-region" @click="openRegionPicker">
            <text class="addresses__label">所在地区</text>
            <text :class="['addresses__value', { 'addresses__value--empty': !regionText }]">
              {{ regionText ?? '请选择省、市' }}
            </text>
            <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
          <view class="addresses__field">
            <text class="addresses__label">详细地址</text>
            <input
              class="addresses__input"
              data-testid="address-detail"
              type="text"
              placeholder="区县、街道、门牌号"
              :maxlength="ADDRESS_DETAIL_MAX_LENGTH"
              :value="form.detail"
              @input="setField('detail', valueOf($event))"
            />
          </view>
          <view class="addresses__field addresses__field--last">
            <text class="addresses__label addresses__label--wide">设为默认</text>
            <button
              class="addresses__switch"
              data-testid="address-default"
              role="switch"
              :aria-checked="form.isDefault || editingDefault"
              :aria-disabled="editingDefault"
              @click="!editingDefault && (form = { ...form, isDefault: !form.isDefault })"
            >
              <view :class="['addresses__track', { 'addresses__track--on': form.isDefault || editingDefault }]">
                <view class="addresses__knob" />
              </view>
            </button>
          </view>
        </view>

        <text v-if="sheetError" class="addresses__error" data-testid="address-sheet-error">{{ sheetError }}</text>

        <button
          v-if="sheet === 'edit' && !deleteAsked"
          class="addresses__delete"
          data-testid="address-delete"
          @click="deleteAsked = true"
        >
          删除地址
        </button>
        <view v-if="sheet === 'edit' && deleteAsked" class="addresses__delete-ask" role="status">
          <text class="addresses__delete-text">确定删除这条地址？</text>
          <button class="addresses__keep" @click="deleteAsked = false">保留</button>
          <button class="addresses__delete-confirm" data-testid="address-delete-confirm" @click="confirmDelete">
            确认删除
          </button>
        </view>

        <view class="addresses__actions">
          <button class="addresses__cancel" @click="closeSheet">取消</button>
          <button
            :class="['addresses__save', { 'addresses__save--off': !canSave }]"
            data-testid="address-save"
            :disabled="!canSave"
            @click="save"
          >
            {{ selecting && sheet === 'add' ? '保存并使用' : '保存' }}
          </button>
        </view>
      </view>
    </view>
    <RegionPickSheet
      v-if="sheet !== 'closed' && pickingRegion"
      :provinces="provinces"
      :city-code="form.cityCode || null"
      :current-city-code="currentOrDefaultCity(currentCityStore.currentCity)?.code ?? null"
      test-id="address"
      @close="pickingRegion = false"
      @confirm="pickCity"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype boards AddressList and RegionPick (390 wide). */
.addresses {
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




.addresses__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 16px;
}

.addresses__hint {
  display: flex;
  padding: 9px 12px;
  align-items: center;
  gap: 6px;
  border-radius: 12px;
  color: #536178;
  background: #fff0e3;
  font-size: 13px;
  line-height: 18px;
}

.addresses__note {
  padding: 0 4px;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.addresses__card {
  display: flex;
  padding: 14px 4px 14px 14px;
  align-items: center;
  gap: 8px;
  border: 1.5px solid transparent;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.addresses__card--chosen {
  border-color: #ff6a00;
  background: #fffaf5;
}

.addresses__main {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  text-align: left;
}

.addresses__name-line {
  display: flex;
  align-items: center;
  gap: 8px;
}

.addresses__name {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.addresses__phone {
  color: #536178;
  font-size: 14px;
}

.addresses__default {
  padding: 0 6px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  line-height: 18px;
}

.addresses__line {
  color: #536178;
  font-size: 13px;
  line-height: 19px;
}

.addresses__edit {
  display: flex;
  min-width: 56px;
  min-height: 44px;
  flex: none;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 0 8px;
  border-left: 1px solid #f0f1f3;
  color: #536178;
  font-size: 13px;
}

.addresses__empty {
  padding: 24px;
  border-radius: 14px;
  color: #738096;
  background: #ffffff;
  font-size: 14px;
  text-align: center;
  box-shadow: var(--app-card-shadow);
}

.addresses__skeleton {
  height: 120px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.addresses__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.addresses__state-title {
  font-size: 16px;
  font-weight: 700;
}

.addresses__state-button {
  min-width: 120px;
  height: 40px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  line-height: 40px;
}

.addresses__bar {
  position: sticky;
  bottom: 0;
  padding: 10px 16px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

.addresses__add {
  width: 100%;
  height: 46px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  box-shadow: var(--app-primary-shadow);
}

.addresses__add--off,
.addresses__add[disabled] {
  background: #c9d3e1;
}

/* Below 999, like the participants sheet, so system pickers can still open above it. */
.addresses__mask {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.45);
}

.addresses__sheet {
  display: flex;
  width: 100%;
  max-height: 86vh;
  flex-direction: column;
  gap: 10px;
  padding: 8px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  background: #ffffff;
  box-sizing: border-box;
}

.addresses__sheet-head {
  display: flex;
  min-height: 44px;
  align-items: center;
}

.addresses__sheet-title {
  font-size: 17px;
  font-weight: 700;
}

.addresses__sheet-close {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.addresses__sheet-close--right {
  margin-left: auto;
}

.addresses__fields {
  display: flex;
  flex-direction: column;
}

.addresses__field {
  display: flex;
  width: 100%;
  min-height: 48px;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid #f0f1f3;
  text-align: left;
}

.addresses__field--last {
  border-bottom: 0;
}

.addresses__label {
  width: 64px;
  flex: none;
  color: #627187;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.addresses__label--wide {
  flex: 1;
}

.addresses__input {
  min-width: 0;
  height: 44px;
  flex: 1;
  color: #202c42;
  font-size: 15px;
}

.addresses__value {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  line-height: 21px;
}

.addresses__value--empty {
  color: #91a0b4;
}

.addresses__switch {
  display: flex;
  width: 56px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: flex-end;
}

.addresses__track {
  display: flex;
  width: 44px;
  height: 26px;
  padding: 2px;
  justify-content: flex-start;
  border-radius: 999px;
  background: #c9d3e1;
  box-sizing: border-box;
}

.addresses__track--on {
  justify-content: flex-end;
  background: #ff6a00;
}

.addresses__knob {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
}

.addresses__error {
  color: #d64f67;
  font-size: 12px;
  line-height: 18px;
}

.addresses__delete {
  min-height: 44px;
  align-self: flex-start;
  margin: -4px 0;
  padding: 0 4px;
  color: #d64f67;
  font-size: 14px;
}

.addresses__delete-ask {
  display: flex;
  padding: 8px 10px;
  align-items: center;
  gap: 8px;
  border-radius: 10px;
  background: #fff0e3;
}

.addresses__delete-text {
  min-width: 0;
  flex: 1;
  color: #536178;
  font-size: 13px;
}

.addresses__keep,
.addresses__delete-confirm {
  height: 36px;
  flex: none;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
  box-sizing: border-box;
}

.addresses__keep {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
}

.addresses__delete-confirm {
  color: #ffffff;
  background: #d64f67;
}

.addresses__actions {
  display: flex;
  gap: 10px;
}

.addresses__cancel,
.addresses__save {
  height: 44px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
  box-sizing: border-box;
}

.addresses__cancel {
  border: 1px solid #c9d3e1;
  color: #202c42;
  background: #ffffff;
  line-height: 42px;
}

.addresses__save {
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.addresses__save--off,
.addresses__save[disabled] {
  color: #91a0b4;
  background: #eef0f3;
}








</style>
