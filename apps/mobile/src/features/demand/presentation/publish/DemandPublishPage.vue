<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import { computed, ref, watch } from 'vue'

import { platformCityApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import CitySelectionSheet from '@/shared/city/presentation/CitySelectionSheet.vue'
import { useCitySheet } from '@/shared/city/presentation/use-city-sheet'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import { ApiRequestError } from '@/shared/http/public-api-request'
import { toBeijingDate } from '@/shared/time/beijing-time'

import {
  DEMAND_ADDRESS_MAX,
  DEMAND_BUDGET_OPTIONS,
  DEMAND_NOTE_MAX,
  DEMAND_NOTICE_TEXT,
  DEMAND_SCOPE_MAX,
  buildDemandDueOptions,
  changeDemandCity,
  createDemandForm,
  createDemandKeyHolder,
  describeDemandSaveFailure,
  findDemandFormError,
  formFromDemand,
  formatDemandBudget,
  formatDemandDueDate,
  preselectInvitedProvider,
  switchDemandType,
  toDemandDraftInput,
  toggleDemandDeliverable,
  toggleInvitedProvider,
  type DemandFieldError,
  type DemandForm,
} from '../../application/demand-form'
import {
  DEMAND_STATUS_LABELS,
  demandTone,
  describeDemandFacts,
  describeDemandLine,
  describeDemandPublisher,
} from '../../application/demand-view'
import { demandApi } from '../../data/demand-api-client'
import {
  DEMAND_TYPES,
  DEMAND_TYPE_INFO,
  DEMAND_DELIVERABLE_LABELS,
  MAX_INVITED_PROVIDERS,
  type Demand,
  type DemandDeliverableCode,
  type DemandProvider,
  type DemandPublisher,
  type DemandType,
} from '../../domain/demand'
import type { DemandListFilter, DemandPublishTarget } from '../../navigation/demand-routes'
import DemandConfirmSheet from '../shared/DemandConfirmSheet.vue'
import DemandPageHeader from '../shared/DemandPageHeader.vue'

const props = defineProps<{
  target: DemandPublishTarget
  // Null for a guest. The page host reads it again every time the page is shown.
  accessToken: string | null
}>()

const emit = defineEmits<{
  back: []
  login: []
  // A new demand went in (or a draft was submitted): the host shows the list.
  submitted: [demandId: string]
  openList: [filter: DemandListFilter]
  openQuotes: [demandId: string]
  republish: [demandId: string]
}>()

type PageState =
  | { kind: 'loading' }
  | { kind: 'form' }
  | { kind: 'view'; demand: Demand }
  | { kind: 'missing' }
  | { kind: 'error' }

type SheetKind = 'type' | 'due' | 'budget' | 'invite'

interface Notice {
  text: string
  tone: 'error' | 'info'
  // 去查看: the drafts in 我的 → 需求与报价.
  link: boolean
}

const currentCityStore = useCurrentCityStore()
const state = ref<PageState>({ kind: 'loading' })
const form = ref<DemandForm>(createDemandForm('INSPECTION', null))
// Set once the draft exists on the server; saving again replaces it.
const demandId = ref<string | null>(null)
const publisher = ref<DemandPublisher | null>(null)
const fieldError = ref<DemandFieldError | null>(null)
const notice = ref<Notice | null>(null)
const sheet = ref<SheetKind | null>(null)
const providers = ref<DemandProvider[]>([])
const providersState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const saving = ref(false)
const asking = ref<'cancel' | null>(null)
const cancelling = ref(false)
const keys = createDemandKeyHolder()
let requestVersion = 0
let providersVersion = 0

// The shared city sheet sets the App's city; the demand takes the picked city as its own.
const citySheet = useCitySheet(platformCityApi, () => {
  const city = currentCityStore.currentCity
  if (!city) return
  form.value = changeDemandCity(form.value, { code: city.code, name: city.name })
  if (fieldError.value?.field === 'cityCode') clearError()
})
const {
  isOpen: citySheetOpen,
  directory: cityDirectory,
  isLoading: cityDirectoryLoading,
  error: cityDirectoryError,
  recentCities,
  isRelocating,
} = citySheet

const info = computed(() => DEMAND_TYPE_INFO[form.value.type])
const today = computed(() => toBeijingDate(new Date()))
const dueText = computed(() => formatDemandDueDate(form.value.dueDate, new Date()))
const dueOptions = computed(() => buildDemandDueOptions(new Date()))
const budgetText = computed(() => formatDemandBudget(form.value.budgetMinMinor, form.value.budgetMaxMinor))
const cityShort = computed(() => form.value.city?.name.replace(/市$/, '') ?? '')
const publisherText = computed(() => (publisher.value ? describeDemandPublisher(publisher.value) : ''))
// The Demand board's identity line: 以 东营港务物流 身份提交 (switching identity is not built).
const identityText = computed(() => {
  const current = publisher.value
  if (!current) return ''
  const name = current.kind === 'ORGANIZATION' && current.organizationName ? current.organizationName : '个人'
  return `以 ${name} 身份提交`
})
const title = computed(() => {
  if (state.value.kind === 'view') return '需求详情'
  return demandId.value ? '继续填写需求' : '发布服务需求'
})
const view = computed(() => (state.value.kind === 'view' ? state.value.demand : null))
const viewFacts = computed(() => (view.value
  ? describeDemandFacts(view.value, formatDemandDueDate(view.value.dueDate, new Date()))
  : []))
const scopeRows = computed(() => {
  const invited = form.value.invited.map((item) => item.name)
  const inviteSub = form.value.quoteScope === 'INVITE'
    ? invited.length
      ? `已选 ${invited.length} 家 · ${invited.length > 2 ? `${invited[0]} 等` : invited.join('、')}`
      : '还没有选择服务商'
    : '只有被邀请的服务商能报价'
  return [
    {
      scope: 'MATCH' as const,
      label: cityShort.value ? `平台匹配${cityShort.value}可服务的服务商` : '平台匹配可服务的服务商',
      sub: cityShort.value ? `服务${cityShort.value}的服务商都能看到并报价` : '服务这个城市的服务商都能看到并报价',
      more: false,
    },
    { scope: 'INVITE' as const, label: '仅邀请指定服务商', sub: inviteSub, more: true },
  ]
})

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

function clearError(): void {
  fieldError.value = null
  if (notice.value?.tone === 'error') notice.value = null
}

function show(text: string, tone: Notice['tone'] = 'info', link = false): void {
  notice.value = { text, tone, link }
}

// The App's city; opened from a link before any city is known, the configured default like the
// other entry pages.
function currentFormCity() {
  const city = currentCityStore.currentCity
  if (city) return { code: city.code, name: city.name }
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  return code && name ? { code, name } : null
}

async function loadPublisher(token: string): Promise<void> {
  try {
    publisher.value = await demandApi.loadPublisher(token)
  } catch {
    // The chip is left out; the server records the publisher anyway.
    publisher.value = null
  }
}

async function load(): Promise<void> {
  const token = props.accessToken
  if (!token) {
    emit('login')
    return
  }
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  notice.value = null
  fieldError.value = null
  try {
    const source = props.target.demandId ?? props.target.from
    if (!source) {
      form.value = createDemandForm(props.target.type ?? 'INSPECTION', currentFormCity())
      demandId.value = null
      await loadPublisher(token)
      if (requested !== requestVersion) return
      state.value = { kind: 'form' }
      if (props.target.providerId) await preselectProvider(props.target.providerId, requested)
      return
    }
    const demand = await demandApi.read(token, source)
    if (requested !== requestVersion) return
    if (props.target.from) {
      // 重新发布: a new demand with the old content; a date already past goes back to 可协商.
      const copied = formFromDemand(demand)
      form.value = {
        ...copied,
        dueDate: copied.dueDate && copied.dueDate >= today.value ? copied.dueDate : null,
        city: copied.city ?? currentFormCity(),
      }
      demandId.value = null
      await loadPublisher(token)
      if (requested === requestVersion) state.value = { kind: 'form' }
      return
    }
    if (demand.status === 'DRAFT' && demand.editable) {
      form.value = { ...formFromDemand(demand) }
      if (!form.value.city) form.value = { ...form.value, city: currentFormCity() }
      demandId.value = demand.demandId
      publisher.value = demand.publisher
      state.value = { kind: 'form' }
      return
    }
    publisher.value = demand.publisher
    state.value = { kind: 'view', demand }
  } catch (error) {
    if (requested !== requestVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function pickType(type: DemandType): void {
  form.value = switchDemandType(form.value, type)
  sheet.value = null
  clearError()
}

function pickDue(date: string | null): void {
  form.value = { ...form.value, dueDate: date }
  sheet.value = null
  if (fieldError.value?.field === 'dueDate') clearError()
}

function pickDueFromPicker(event: { detail: { value: string | number } }): void {
  const value = String(event.detail.value)
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) pickDue(value)
}

function pickBudget(minMinor: number | null, maxMinor: number | null): void {
  form.value = { ...form.value, budgetMinMinor: minMinor, budgetMaxMinor: maxMinor }
  sheet.value = null
}

function budgetPicked(minMinor: number | null, maxMinor: number | null): boolean {
  return form.value.budgetMinMinor === minMinor && form.value.budgetMaxMinor === maxMinor
}

// uni's H5 input reports at most every 100 ms; blur carries the final value before a tap lands.
function readValue(event: Event): string {
  return (event as Event & { detail?: { value?: string } }).detail?.value?.toString() ?? ''
}

function setAddress(event: Event): void {
  form.value = { ...form.value, address: readValue(event) }
  if (fieldError.value?.field === 'address') clearError()
}

function setScope(event: Event): void {
  form.value = { ...form.value, scope: readValue(event) }
  if (fieldError.value?.field === 'scope') clearError()
}

function setNote(event: Event): void {
  form.value = { ...form.value, note: readValue(event) }
}

function toggleDeliverable(code: DemandDeliverableCode): void {
  form.value = toggleDemandDeliverable(form.value, code)
  if (fieldError.value?.field === 'deliverables') clearError()
}

async function loadProviders(): Promise<void> {
  const token = props.accessToken
  const city = form.value.city
  if (!token || !city) return
  const requested = ++providersVersion
  providersState.value = 'loading'
  try {
    const loaded = await demandApi.listProviders(token, city.code, form.value.type)
    if (requested !== providersVersion) return
    providers.value = loaded
    providersState.value = 'ready'
  } catch (error) {
    if (requested !== providersVersion) return
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    providersState.value = 'error'
  }
}

// Opened from a service or provider page: 仅邀请这家 when the provider can be invited for this
// city and type, which the server's inviteable list decides; 平台匹配 otherwise, with a word why.
async function preselectProvider(providerId: string, requested: number): Promise<void> {
  const city = form.value.city
  if (!city) return
  await loadProviders()
  if (requested !== requestVersion || form.value.city?.code !== city.code || form.value.invited.length) return
  if (providersState.value !== 'ready') {
    show('没能确认要邀请的服务商，报价范围先按平台匹配', 'info')
    return
  }
  const picked = preselectInvitedProvider(form.value, providers.value, providerId)
  if (!picked.found) {
    show(`这家服务商暂不接${cityShort.value}的${info.value.label}需求，报价范围先按平台匹配`, 'info')
    return
  }
  form.value = picked.form
}

function pickScope(scope: 'MATCH' | 'INVITE'): void {
  form.value = { ...form.value, quoteScope: scope }
  if (fieldError.value?.field === 'invitedProviderIds') clearError()
  if (scope === 'INVITE') openInvite()
}

function openInvite(): void {
  if (!form.value.city) {
    fieldError.value = { field: 'cityCode', message: '请先选择服务城市，再邀请服务商' }
    show(fieldError.value.message, 'error')
    return
  }
  sheet.value = 'invite'
  void loadProviders()
}

function isInvited(provider: DemandProvider): boolean {
  return form.value.invited.some((item) => item.providerId === provider.providerId)
}

function toggleProvider(provider: DemandProvider): void {
  if (!isInvited(provider) && form.value.invited.length >= MAX_INVITED_PROVIDERS) {
    show(`最多邀请 ${MAX_INVITED_PROVIDERS} 家服务商`, 'error')
    return
  }
  form.value = toggleInvitedProvider(form.value, provider)
  if (fieldError.value?.field === 'invitedProviderIds') clearError()
}

function failureOf(error: unknown) {
  return error instanceof ApiRequestError
    ? { status: error.status, code: error.code, message: error.message, details: error.details }
    : null
}

async function handleSaveFailure(error: unknown): Promise<void> {
  if (isUnauthorized(error)) {
    emit('login')
    return
  }
  const failure = describeDemandSaveFailure(failureOf(error), form.value.type)
  if (failure.newKey) keys.reset()
  if (failure.field) fieldError.value = { field: failure.field, message: failure.message }
  show(failure.message, 'error')
  if (failure.reload && demandId.value) {
    const message = failure.message
    await load()
    show(message, 'error')
  }
}

async function saveDraft(): Promise<void> {
  const token = props.accessToken
  if (saving.value) return
  if (!token) {
    emit('login')
    return
  }
  saving.value = true
  clearError()
  try {
    const input = toDemandDraftInput(form.value)
    const saved = demandId.value
      ? await demandApi.updateDraft(token, demandId.value, input)
      : await demandApi.create(token, keys.keyFor(input, false), input, false)
    demandId.value = saved.demandId
    publisher.value = saved.publisher
    keys.reset()
    show('已存为草稿，可在“我的 → 需求与报价”继续填写', 'info', true)
  } catch (error) {
    await handleSaveFailure(error)
  } finally {
    saving.value = false
  }
}

async function submit(): Promise<void> {
  const token = props.accessToken
  if (saving.value) return
  if (!token) {
    emit('login')
    return
  }
  const missing = findDemandFormError(form.value)
  if (missing) {
    fieldError.value = missing
    show(missing.message, 'error')
    return
  }
  saving.value = true
  clearError()
  try {
    const input = toDemandDraftInput(form.value)
    let submitted: Demand
    if (demandId.value) {
      // The draft takes the content on screen first, then goes in.
      await demandApi.updateDraft(token, demandId.value, input)
      submitted = await demandApi.submit(token, demandId.value)
    } else {
      submitted = await demandApi.create(token, keys.keyFor(input, true), input, true)
    }
    keys.reset()
    emit('submitted', submitted.demandId)
  } catch (error) {
    await handleSaveFailure(error)
  } finally {
    saving.value = false
  }
}

async function cancelDemand(): Promise<void> {
  const token = props.accessToken
  const demand = view.value
  if (!token || !demand || cancelling.value) return
  cancelling.value = true
  try {
    const cancelled = await demandApi.cancel(token, demand.demandId)
    asking.value = null
    state.value = { kind: 'view', demand: cancelled }
    show('需求已取消', 'info')
  } catch (error) {
    asking.value = null
    if (isUnauthorized(error)) {
      emit('login')
      return
    }
    await load()
    show(error instanceof ApiRequestError && error.code === 'DEMAND_STATE_INVALID'
      ? '需求已选定或已关闭，不能取消'
      : '没有取消成功，请稍后重试', 'error')
  } finally {
    cancelling.value = false
  }
}

const invalid = (field: DemandFieldError['field']) => fieldError.value?.field === field

const SHEET_TITLES: Record<SheetKind, string> = {
  type: '选择服务类型',
  due: '选择期望完成时间',
  budget: '选择预算区间',
  invite: '选择要邀请的服务商',
}
const sheetTitle = computed(() => (sheet.value ? SHEET_TITLES[sheet.value] : ''))

function providerLine(provider: DemandProvider): string {
  return [provider.certificationLabel, provider.description].filter(Boolean).join(' · ')
}

watch(() => [props.accessToken, props.target.demandId, props.target.from, props.target.type, props.target.providerId], () => {
  void load()
}, { immediate: true })
</script>

<template>
  <view class="demand-publish" data-testid="demand-publish" :data-mode="state.kind">
    <view class="demand-publish__top">
      <DemandPageHeader :title="title" @back="emit('back')" />
    </view>

    <view v-if="state.kind === 'loading'" class="demand-publish__body" aria-label="正在加载">
      <view class="demand-publish__skeleton" />
      <view class="demand-publish__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing' || state.kind === 'error'" class="demand-publish__state" data-testid="demand-publish-error">
      <text class="demand-publish__state-title">{{ state.kind === 'missing' ? '没有找到这份需求' : '加载失败' }}</text>
      <text class="demand-publish__state-text">
        {{ state.kind === 'missing' ? '它可能已被删除' : '请检查网络后重试' }}
      </text>
      <button
        class="demand-publish__state-button"
        @click="state.kind === 'missing' ? emit('openList', 'ALL') : load()"
      >
        {{ state.kind === 'missing' ? '看看我的需求' : '重试' }}
      </button>
    </view>

    <!-- A submitted demand: what was sent, read-only. -->
    <template v-else-if="view">
      <view class="demand-publish__body">
        <view class="demand-view__status">
          <text v-if="publisherText" class="demand-publish__publisher">{{ publisherText }}</text>
          <text
            :class="['demand-view__status-text', `demand-view__status-text--${demandTone(view.status).toLowerCase()}`]"
            data-testid="demand-view-status"
          >
            {{ DEMAND_STATUS_LABELS[view.status] }}
          </text>
        </view>
        <text class="demand-view__line" data-testid="demand-view-line">{{ describeDemandLine(view) }}</text>
        <view class="demand-publish__group">
          <view v-for="fact in viewFacts" :key="fact.label" class="demand-view__row">
            <text class="demand-publish__label">{{ fact.label }}</text>
            <text class="demand-view__value">{{ fact.value }}</text>
          </view>
        </view>
        <view v-if="view.note" class="demand-publish__card">
          <text class="demand-publish__card-title">补充说明</text>
          <text class="demand-view__note">{{ view.note }}</text>
        </view>
      </view>
      <view class="demand-publish__bar">
        <button
          v-if="view.cancellable"
          class="demand-publish__secondary"
          data-testid="demand-view-cancel"
          @click="asking = 'cancel'"
        >
          取消需求
        </button>
        <button
          v-if="view.status === 'CLOSED'"
          class="demand-publish__primary"
          data-testid="demand-view-republish"
          @click="emit('republish', view.demandId)"
        >
          重新发布
        </button>
        <button
          v-else
          class="demand-publish__primary"
          data-testid="demand-view-quotes"
          @click="emit('openQuotes', view.demandId)"
        >
          {{ view.status === 'OPEN' ? '查看报价进度' : '查看报价' }}
        </button>
      </view>
    </template>

    <template v-else>
      <view class="demand-publish__body">
        <view v-if="publisherText" class="demand-publish__identity">
          <text class="demand-publish__publisher" data-testid="demand-publisher">{{ identityText }}</text>
        </view>

        <view class="demand-publish__group">
          <button class="demand-publish__row" data-testid="demand-type" aria-haspopup="dialog" @click="sheet = 'type'">
            <text class="demand-publish__label">服务类型</text>
            <text class="demand-publish__value" data-testid="demand-type-value">{{ info.label }}</text>
            <LineIcon name="chevronRight" :size="16" color="#91a0b4" :stroke-width="2" />
          </button>
          <view class="demand-publish__row demand-publish__row--line">
            <text :class="['demand-publish__label', { 'demand-publish__label--invalid': invalid('cityCode') || invalid('address') }]">
              服务地点
            </text>
            <button
              :class="['demand-publish__city', { 'demand-publish__city--empty': !form.city }]"
              data-testid="demand-city"
              @click="citySheet.open()"
            >
              <text>{{ form.city?.name ?? '选城市' }}</text>
              <LineIcon name="chevronDown" :size="12" color="#738096" :stroke-width="2.4" />
            </button>
            <input
              class="demand-publish__input"
              data-testid="demand-address"
              :value="form.address"
              :maxlength="DEMAND_ADDRESS_MAX"
              placeholder="请填写服务地点"
              placeholder-class="demand-publish__placeholder"
              aria-label="服务地点"
              @input="setAddress"
              @blur="setAddress"
            >
          </view>
          <view class="demand-publish__row demand-publish__row--line">
            <text :class="['demand-publish__label', { 'demand-publish__label--invalid': invalid('scope') }]">
              {{ info.scopeLabel }}
            </text>
            <input
              class="demand-publish__input"
              data-testid="demand-scope"
              :value="form.scope"
              :maxlength="DEMAND_SCOPE_MAX"
              :placeholder="info.scopePlaceholder"
              placeholder-class="demand-publish__placeholder"
              :aria-label="info.scopeLabel"
              @input="setScope"
              @blur="setScope"
            >
          </view>
          <button class="demand-publish__row demand-publish__row--line" data-testid="demand-due" aria-haspopup="dialog" @click="sheet = 'due'">
            <text :class="['demand-publish__label', { 'demand-publish__label--invalid': invalid('dueDate') }]">期望完成</text>
            <text class="demand-publish__value" data-testid="demand-due-value">{{ dueText }}</text>
            <LineIcon name="calendar" :size="18" color="#738096" />
          </button>
          <button class="demand-publish__row demand-publish__row--line" data-testid="demand-budget" aria-haspopup="dialog" @click="sheet = 'budget'">
            <text class="demand-publish__label">预算区间</text>
            <text class="demand-publish__value" data-testid="demand-budget-value">{{ budgetText }}</text>
            <LineIcon name="chevronRight" :size="16" color="#91a0b4" :stroke-width="2" />
          </button>
        </view>

        <view class="demand-publish__card">
          <text :class="['demand-publish__card-title', { 'demand-publish__label--invalid': invalid('deliverables') }]">需要的交付物</text>
          <view class="demand-publish__chips" role="group" aria-label="需要的交付物">
            <button
              v-for="code in info.deliverables"
              :key="code"
              :class="['demand-publish__chip', { 'demand-publish__chip--on': form.deliverables.includes(code) }]"
              :data-testid="`demand-deliverable-${code.toLowerCase()}`"
              :aria-pressed="form.deliverables.includes(code)"
              @click="toggleDeliverable(code)"
            >
              {{ DEMAND_DELIVERABLE_LABELS[code] }}
            </button>
          </view>
        </view>

        <view class="demand-publish__card">
          <text class="demand-publish__card-title">补充说明</text>
          <textarea
            class="demand-publish__note"
            data-testid="demand-note"
            :value="form.note"
            :maxlength="DEMAND_NOTE_MAX"
            placeholder="比如现场要避开的位置、进场要求"
            placeholder-class="demand-publish__placeholder"
            aria-label="补充说明"
            @input="setNote"
            @blur="setNote"
          />
        </view>

        <view class="demand-publish__card demand-publish__card--scope">
          <text :class="['demand-publish__card-title', { 'demand-publish__label--invalid': invalid('invitedProviderIds') }]">报价范围</text>
          <view role="radiogroup" aria-label="报价范围">
            <button
              v-for="row in scopeRows"
              :key="row.scope"
              class="demand-publish__scope"
              :data-testid="`demand-scope-${row.scope.toLowerCase()}`"
              role="radio"
              :aria-checked="form.quoteScope === row.scope"
              @click="row.scope === 'INVITE' && form.quoteScope === 'INVITE' ? openInvite() : pickScope(row.scope)"
            >
              <view v-if="form.quoteScope === row.scope" class="demand-publish__radio demand-publish__radio--on">
                <LineIcon name="check" :size="12" color="#ffffff" :stroke-width="3" />
              </view>
              <view v-else class="demand-publish__radio" />
              <view class="demand-publish__scope-copy">
                <text :class="['demand-publish__scope-label', { 'demand-publish__scope-label--on': form.quoteScope === row.scope }]">
                  {{ row.label }}
                </text>
                <text
                  :class="['demand-publish__scope-sub', { 'demand-publish__scope-sub--on': form.quoteScope === 'INVITE' && row.scope === 'INVITE' }]"
                  :data-testid="`demand-scope-${row.scope.toLowerCase()}-sub`"
                >
                  {{ row.sub }}
                </text>
              </view>
              <LineIcon v-if="row.more" name="chevronRight" :size="16" color="#91a0b4" :stroke-width="2" />
            </button>
          </view>
        </view>

        <text class="demand-publish__notice-text">{{ DEMAND_NOTICE_TEXT }}</text>
      </view>

      <view class="demand-publish__bar">
        <button class="demand-publish__draft" data-testid="demand-save-draft" :disabled="saving" @click="saveDraft">
          存草稿
        </button>
        <button class="demand-publish__primary" data-testid="demand-submit" :disabled="saving" @click="submit">
          {{ saving ? '提交中…' : '提交需求' }}
        </button>
      </view>
    </template>

    <view
      v-if="notice"
      :class="['demand-publish__toast', `demand-publish__toast--${notice.tone}`]"
      role="status"
      data-testid="demand-notice"
    >
      <LineIcon name="info" :size="14" :color="notice.tone === 'error' ? '#d64f67' : '#ba470b'" :stroke-width="2" />
      <text class="demand-publish__toast-text">{{ notice.text }}</text>
      <button
        v-if="notice.link"
        class="demand-publish__toast-link"
        data-testid="demand-notice-link"
        @click="emit('openList', 'DRAFT')"
      >
        去查看
      </button>
      <button class="demand-publish__toast-close" aria-label="关闭提示" @click="notice = null">
        <LineIcon name="close" :size="14" color="#738096" :stroke-width="2" />
      </button>
    </view>

    <!-- Below the uni picker (999) like every sheet of the project, so the date picker opens above. -->
    <view v-if="sheet" class="demand-sheet" data-testid="demand-sheet" :data-sheet="sheet">
      <view class="demand-sheet__mask" aria-label="关闭" @click="sheet = null" />
      <view class="demand-sheet__panel" role="dialog" aria-modal="true">
        <view class="demand-sheet__grip" />
        <view class="demand-sheet__head">
          <text class="demand-sheet__title">{{ sheetTitle }}</text>
          <button class="demand-sheet__close" aria-label="关闭" @click="sheet = null">
            <LineIcon name="close" :size="20" color="#202c42" :stroke-width="2" />
          </button>
        </view>
        <view class="demand-sheet__options">
          <template v-if="sheet === 'type'">
            <button
              v-for="type in DEMAND_TYPES"
              :key="type"
              class="demand-sheet__option"
              :data-testid="`demand-type-option-${type.toLowerCase()}`"
              :aria-pressed="form.type === type"
              @click="pickType(type)"
            >
              <view class="demand-sheet__option-copy">
                <text :class="['demand-sheet__option-label', { 'demand-sheet__option-label--on': form.type === type }]">
                  {{ DEMAND_TYPE_INFO[type].label }}
                </text>
                <text class="demand-sheet__option-sub">{{ DEMAND_TYPE_INFO[type].description }}</text>
              </view>
              <view :class="['demand-publish__radio', { 'demand-publish__radio--on': form.type === type }]">
                <LineIcon v-if="form.type === type" name="check" :size="12" color="#ffffff" :stroke-width="3" />
              </view>
            </button>
          </template>

          <template v-else-if="sheet === 'due'">
            <button
              v-for="option in dueOptions"
              :key="option.label"
              class="demand-sheet__option"
              :data-testid="`demand-due-option-${option.date ?? 'none'}`"
              :aria-pressed="form.dueDate === option.date"
              @click="pickDue(option.date)"
            >
              <text :class="['demand-sheet__option-label', { 'demand-sheet__option-label--on': form.dueDate === option.date }]">
                {{ option.label }}
              </text>
              <view :class="['demand-publish__radio', { 'demand-publish__radio--on': form.dueDate === option.date }]">
                <LineIcon v-if="form.dueDate === option.date" name="check" :size="12" color="#ffffff" :stroke-width="3" />
              </view>
            </button>
            <picker mode="date" :start="today" :value="form.dueDate ?? today" @change="pickDueFromPicker">
              <view class="demand-sheet__option" data-testid="demand-due-other">
                <text class="demand-sheet__option-label">选择其他日期</text>
                <LineIcon name="calendar" :size="18" color="#738096" />
              </view>
            </picker>
          </template>

          <template v-else-if="sheet === 'budget'">
            <button
              v-for="option in DEMAND_BUDGET_OPTIONS"
              :key="option.label"
              class="demand-sheet__option"
              :data-testid="`demand-budget-option-${option.minMinor ?? 0}-${option.maxMinor ?? 0}`"
              :aria-pressed="budgetPicked(option.minMinor, option.maxMinor)"
              @click="pickBudget(option.minMinor, option.maxMinor)"
            >
              <text :class="['demand-sheet__option-label', { 'demand-sheet__option-label--on': budgetPicked(option.minMinor, option.maxMinor) }]">
                {{ option.label }}
              </text>
              <view :class="['demand-publish__radio', { 'demand-publish__radio--on': budgetPicked(option.minMinor, option.maxMinor) }]">
                <LineIcon v-if="budgetPicked(option.minMinor, option.maxMinor)" name="check" :size="12" color="#ffffff" :stroke-width="3" />
              </view>
            </button>
          </template>

          <template v-else>
            <text v-if="providersState === 'loading'" class="demand-sheet__hint">正在加载服务商…</text>
            <view v-else-if="providersState === 'error'" class="demand-sheet__hint">
              <text>服务商没有加载出来</text>
              <button class="demand-sheet__retry" @click="loadProviders">重试</button>
            </view>
            <text v-else-if="providers.length === 0" class="demand-sheet__hint" data-testid="demand-providers-empty">
              {{ cityShort }}还没有可以邀请的服务商，可以改用平台匹配
            </text>
            <template v-else>
              <button
                v-for="provider in providers"
                :key="provider.providerId"
                class="demand-sheet__option"
                :data-testid="`demand-provider-${provider.providerId}`"
                :aria-pressed="isInvited(provider)"
                @click="toggleProvider(provider)"
              >
                <view class="demand-sheet__option-copy">
                  <text :class="['demand-sheet__option-label', { 'demand-sheet__option-label--strong': isInvited(provider) }]">
                    {{ provider.name }}
                  </text>
                  <text v-if="providerLine(provider)" class="demand-sheet__option-sub">{{ providerLine(provider) }}</text>
                </view>
                <view :class="['demand-publish__radio', { 'demand-publish__radio--on': isInvited(provider) }]">
                  <LineIcon v-if="isInvited(provider)" name="check" :size="12" color="#ffffff" :stroke-width="3" />
                </view>
              </button>
            </template>
            <button class="demand-sheet__confirm" data-testid="demand-providers-done" @click="sheet = null">
              确定（已选 {{ form.invited.length }} 家）
            </button>
          </template>
        </view>
      </view>
    </view>

    <DemandConfirmSheet
      v-if="asking === 'cancel'"
      title="确认取消这份需求？"
      text="取消后服务商不再报价，已收到的报价也会失效。"
      keep-label="暂不取消"
      go-label="确认取消"
      :busy="cancelling"
      @keep="asking = null"
      @go="cancelDemand"
    />

    <CitySelectionSheet
      v-if="citySheetOpen"
      :directory="cityDirectory"
      :current-city="currentCityStore.currentCity"
      :recent-cities="recentCities"
      :loading="cityDirectoryLoading"
      :error="cityDirectoryError"
      :relocating="isRelocating"
      @close="citySheet.close"
      @retry="citySheet.loadDirectory"
      @relocate="citySheet.relocate"
      @select="citySheet.select"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Demand (390 wide). */
.demand-publish {
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

.demand-publish__top {
  position: sticky;
  top: 0;
  z-index: 5;
}

.demand-publish__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px 24px;
}

.demand-publish__identity {
  display: flex;
  height: 28px;
  align-items: center;
}

.demand-publish__publisher {
  overflow: hidden;
  min-width: 0;
  padding: 0 8px;
  border-radius: 6px;
  color: #1d63c4;
  background: #e6f0ff;
  font-size: 13px;
  line-height: 28px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.demand-publish__group {
  display: flex;
  flex-direction: column;
  padding: 2px 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.demand-publish__row {
  display: flex;
  width: 100%;
  min-height: 48px;
  align-items: center;
  gap: 8px;
}

.demand-publish__row--line {
  border-top: 1px solid #f0f1f3;
}

.demand-publish__label {
  width: 72px;
  flex: none;
  color: #627187;
  font-size: 14px;
  line-height: 20px;
  white-space: nowrap;
}

.demand-publish__label--invalid {
  color: #d64f67;
}

.demand-publish__value {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.demand-publish__city {
  display: flex;
  height: 44px;
  flex: none;
  align-items: center;
  gap: 2px;
  font-size: 14px;
  white-space: nowrap;
}

.demand-publish__city--empty {
  color: #ba470b;
}

.demand-publish__input {
  min-width: 0;
  height: 44px;
  flex: 1;
  font-size: 14px;
}

.demand-publish__placeholder {
  color: #91a0b4;
}

.demand-publish__card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.demand-publish__card--scope {
  gap: 4px;
  padding-bottom: 6px;
}

.demand-publish__card-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.demand-publish__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.demand-publish__chip {
  height: 36px;
  padding: 0 14px;
  border: 1.5px solid #e5ebf3;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 14px;
  line-height: 33px;
  white-space: nowrap;
  box-sizing: border-box;
}

.demand-publish__chip--on {
  border-color: #ff6a00;
  color: #ba470b;
  background: #fff0e3;
  font-weight: 500;
}

.demand-publish__note {
  width: 100%;
  height: 76px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #f5f7fc;
  font-size: 14px;
  line-height: 20px;
  box-sizing: border-box;
}

.demand-publish__scope {
  display: flex;
  width: 100%;
  min-height: 60px;
  padding: 8px 0;
  align-items: center;
  gap: 10px;
  box-sizing: border-box;
}

.demand-publish__radio {
  display: flex;
  width: 20px;
  height: 20px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #c9d3e1;
  border-radius: 50%;
  background: #ffffff;
  box-sizing: border-box;
}

.demand-publish__radio--on {
  border: 0;
  background: #ff6a00;
}

.demand-publish__scope-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.demand-publish__scope-label {
  font-size: 14px;
  line-height: 20px;
}

.demand-publish__scope-label--on {
  font-weight: 500;
}

.demand-publish__scope-sub {
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.demand-publish__scope-sub--on {
  color: #ba470b;
}

.demand-publish__notice-text {
  color: #627187;
  font-size: 12px;
  line-height: 19px;
}

.demand-publish__bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  align-items: center;
  gap: 10px;
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
  box-shadow: var(--app-bar-shadow);
}

.demand-publish__draft,
.demand-publish__secondary {
  width: 110px;
  height: 46px;
  flex: none;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 16px;
  line-height: 44px;
  text-align: center;
  white-space: nowrap;
  box-sizing: border-box;
}

.demand-publish__primary {
  height: 46px;
  flex: 1;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
  white-space: nowrap;
}

.demand-publish__toast {
  position: fixed;
  z-index: 20;
  right: 16px;
  bottom: calc(80px + env(safe-area-inset-bottom));
  left: 16px;
  display: flex;
  min-height: 44px;
  padding: 4px 4px 4px 12px;
  align-items: center;
  gap: 6px;
  border: 1px solid #ffd8b8;
  border-radius: 10px;
  background: #fff0e3;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  box-sizing: border-box;
}

.demand-publish__toast-text {
  min-width: 0;
  flex: 1;
  color: #ba470b;
  font-size: 13px;
  line-height: 18px;
}

.demand-publish__toast--error .demand-publish__toast-text {
  color: #d64f67;
}

.demand-publish__toast-link {
  height: 36px;
  flex: none;
  padding: 0 4px;
  color: #ba470b;
  font-size: 13px;
  font-weight: 500;
  line-height: 36px;
  white-space: nowrap;
}

.demand-publish__toast-close {
  display: flex;
  width: 36px;
  height: 36px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.demand-view__status {
  display: flex;
  align-items: center;
  gap: 8px;
}

.demand-view__status-text {
  margin-left: auto;
  font-size: 14px;
  font-weight: 500;
  line-height: 21px;
  white-space: nowrap;
}

.demand-view__status-text--pending { color: #ba470b; }
.demand-view__status-text--quoting { color: #1d63c4; }
.demand-view__status-text--chosen { color: #11804a; }
.demand-view__status-text--off,
.demand-view__status-text--draft { color: #738096; }

.demand-view__line {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.demand-view__row {
  display: flex;
  min-height: 48px;
  padding: 12px 0;
  align-items: flex-start;
  gap: 8px;
  border-top: 1px solid #f0f1f3;
  box-sizing: border-box;
}

.demand-view__row:first-child {
  border-top: 0;
}

.demand-view__value {
  min-width: 0;
  flex: 1;
  font-size: 14px;
  line-height: 20px;
  word-break: break-all;
}

.demand-view__note {
  color: #536178;
  font-size: 14px;
  line-height: 20px;
  white-space: pre-wrap;
}

.demand-publish__skeleton {
  height: 160px;
  border-radius: 14px;
  background: linear-gradient(90deg, #edf0f3 25%, #f7f8fa 37%, #edf0f3 63%);
}

.demand-publish__state {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 32px;
}

.demand-publish__state-title {
  font-size: 16px;
  font-weight: 700;
}

.demand-publish__state-text {
  color: #738096;
  font-size: 13px;
}

.demand-publish__state-button {
  height: 40px;
  margin-top: 8px;
  padding: 0 20px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 15px;
  line-height: 40px;
}

.demand-sheet {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
}

.demand-sheet__mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.demand-sheet__panel {
  position: relative;
  display: flex;
  width: 100%;
  max-height: 80vh;
  flex-direction: column;
  border-radius: 20px 20px 0 0;
  background: #ffffff;
}

.demand-sheet__grip {
  width: 36px;
  height: 4px;
  margin-top: 8px;
  align-self: center;
  border-radius: 2px;
  background: #c9d3e1;
}

.demand-sheet__head {
  position: relative;
  display: flex;
  height: 48px;
  align-items: center;
  justify-content: center;
}

.demand-sheet__title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  white-space: nowrap;
}

.demand-sheet__close {
  position: absolute;
  top: 2px;
  right: 6px;
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.demand-sheet__options {
  display: flex;
  overflow-y: auto;
  flex-direction: column;
  padding: 4px 16px calc(24px + env(safe-area-inset-bottom));
}

.demand-sheet__option {
  display: flex;
  width: 100%;
  min-height: 56px;
  padding: 8px 0;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
  box-sizing: border-box;
}

.demand-sheet__options > .demand-sheet__option:first-child {
  border-top-color: transparent;
}

.demand-sheet__option-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.demand-sheet__option-label {
  min-width: 0;
  flex: 1;
  font-size: 15px;
  line-height: 21px;
}

.demand-sheet__option-label--on {
  color: #ba470b;
  font-weight: 500;
}

.demand-sheet__option-label--strong {
  font-weight: 500;
}

.demand-sheet__option-sub {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.demand-sheet__hint {
  display: flex;
  padding: 20px 0;
  align-items: center;
  gap: 8px;
  color: #627187;
  font-size: 14px;
  line-height: 20px;
}

.demand-sheet__retry {
  color: #ba470b;
  font-size: 14px;
}

.demand-sheet__confirm {
  width: 100%;
  height: 46px;
  margin-top: 10px;
  border-radius: 999px;
  color: #ffffff;
  background: #ff6a00;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
  text-align: center;
  white-space: nowrap;
}
</style>
