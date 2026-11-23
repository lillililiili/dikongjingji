<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  NOTE_LIMIT,
  clock,
  exceptionChips,
  exceptionCopy,
  exceptionOptions,
  holderLine,
  resolutionLines,
  timeOnDay,
} from '../application/workbench-view'
import type { CheckinAction, CheckinRecord } from '../domain/checkin'

import { STAFF_BLOCK, STAFF_CROSS, STAFF_INFO, STAFF_SMALL_TICK, STAFF_WARNING } from './staff-icons'

// 核销异常处理 (board StaffException, 390 wide). One record at a time; the chips switch between the
// exceptions that still wait for a decision today. A record can be settled once.
export interface StaffExceptionContent {
  day: string
  operatorName: string
  records: CheckinRecord[]
  selectedId: string | null
}

export type StaffExceptionState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'forbidden' }
  | { kind: 'ready'; content: StaffExceptionContent }

const props = defineProps<{ state: StaffExceptionState; busy: boolean }>()

const emit = defineEmits<{
  back: []
  retry: []
  select: [record: CheckinRecord]
  resolve: [record: CheckinRecord, action: CheckinAction, identityChecked: boolean, note: string]
  backToScan: []
}>()

const choice = ref<CheckinAction | null>(null)
const identityChecked = ref(false)
const note = ref('')

const content = computed(() => (props.state.kind === 'ready' ? props.state.content : null))
const chips = computed(() => exceptionChips(content.value?.records ?? [], content.value?.selectedId ?? null))
const current = computed(() => content.value?.records.find((record) => record.recordId === content.value?.selectedId) ?? null)

// Switching to another record starts its form afresh (board: type switch clears choice, check and note).
watch(() => current.value?.recordId, () => {
  choice.value = null
  identityChecked.value = false
  note.value = ''
})

const copy = computed(() => (current.value && content.value ? exceptionCopy(current.value, content.value.day) : null))
const options = computed(() => (current.value && content.value ? exceptionOptions(current.value, content.value.day) : []))
const option = computed(() => options.value.find((item) => item.action === choice.value) ?? null)
const done = computed(() => (current.value && content.value ? resolutionLines(current.value, content.value.day) : null))
const canBlock = computed(() => Boolean(current.value?.allowedActions.includes('BLOCK')))
const showForm = computed(() => !done.value && options.value.length > 0)
const ready = computed(() => Boolean(option.value) && (!option.value?.needsIdentity || identityChecked.value))

const infoRows = computed(() => {
  const record = current.value
  const value = content.value
  if (!record || !value) return []
  return [
    { k: '持有人', v: record.holderName ? holderLine(record) : '—' },
    { k: '凭证尾号', v: record.codeTail },
    { k: '当前场次', v: record.currentSession ? `${timeOnDay(record.currentSession.startsAt, value.day)} ${record.currentSession.title}` : '未选场次' },
  ]
})

const hint = computed(() => {
  if (canBlock.value) return '记录拦截结果后返回核销工作台'
  if (!option.value) return '请选择处理方式'
  if (option.value.needsIdentity && !identityChecked.value) return '放行前需先勾选“已人工核验身份”'
  return '提交后记录操作人、时间与处理方式，不能重复提交'
})

function onNote(event: Event): void {
  const detail = (event as unknown as { detail?: { value?: string } }).detail
  note.value = (detail?.value ?? (event.target as HTMLInputElement | null)?.value ?? '').slice(0, NOTE_LIMIT)
}

function confirm(): void {
  const record = current.value
  if (!record || !option.value || !ready.value || props.busy) return
  emit('resolve', record, option.value.action, identityChecked.value, note.value)
}

function recordBlock(): void {
  const record = current.value
  if (!record || !canBlock.value || props.busy) return
  emit('resolve', record, 'BLOCK', false, '')
}
</script>

<template>
  <view class="staff-exception" data-testid="staff-exception-page">
    <AppPageHeader title="核销异常处理" plain test-id="staff-exception" @back="emit('back')" />

    <view v-if="state.kind === 'loading'" class="staff-exception__status"><text>正在读取核销异常…</text></view>
    <view v-else-if="state.kind === 'forbidden'" class="staff-exception__status"><text>当前账号没有核销权限</text></view>
    <view v-else-if="state.kind === 'error'" class="staff-exception__status">
      <text>{{ state.message }}</text>
      <button class="staff-exception__plain" @click="emit('retry')">重试</button>
    </view>

    <view v-else-if="content && !current" class="staff-exception__status" data-testid="staff-exception-none">
      <text class="staff-exception__status-title">今天没有待处理的核销异常</text>
      <text>扫码或输码被拦下的凭证会出现在这里</text>
      <button class="staff-exception__plain" @click="emit('backToScan')">返回核销工作台</button>
    </view>

    <template v-else-if="content && current && copy">
      <view class="staff-exception__body">
        <view class="staff-exception__types">
          <text class="staff-exception__types-label">异常类型</text>
          <scroll-view class="staff-exception__types-scroll" scroll-x :show-scrollbar="false">
            <view class="staff-exception__types-row">
              <button
                v-for="chip in chips"
                :key="chip.record.recordId"
                class="staff-exception__type"
                :class="{ 'staff-exception__type--on': chip.record.recordId === current.recordId }"
                :aria-pressed="chip.record.recordId === current.recordId"
                :data-testid="`staff-exception-chip-${chip.record.recordId}`"
                @click="emit('select', chip.record)"
              >{{ chip.label }}</button>
            </view>
          </scroll-view>
        </view>

        <view class="staff-exception__hero">
          <view class="staff-exception__hero-icon" :class="{ 'staff-exception__hero-icon--red': copy.blocked }">
            <LineIcon :d="copy.blocked ? STAFF_CROSS : STAFF_WARNING" :size="32" :color="copy.blocked ? '#d64f67' : '#ff6a00'" :stroke-width="2" />
          </view>
          <text class="staff-exception__hero-title" data-testid="staff-exception-title">{{ copy.title }}</text>
          <text class="staff-exception__hero-sub">{{ copy.sub }}</text>
        </view>

        <view class="staff-exception__card staff-exception__info">
          <view v-for="(row, index) in infoRows" :key="row.k" class="staff-exception__info-row" :class="{ 'staff-exception__info-row--first': index === 0 }">
            <text class="staff-exception__info-k">{{ row.k }}</text>
            <text class="staff-exception__info-v">{{ row.v }}</text>
          </view>
          <view class="staff-exception__fact" :class="{ 'staff-exception__fact--red': copy.blocked }" data-testid="staff-exception-fact">
            <LineIcon :d="STAFF_INFO" :size="16" :color="copy.blocked ? '#d64f67' : '#ba470b'" :stroke-width="2" />
            <text>{{ copy.fact }}</text>
          </view>
        </view>

        <view v-if="showForm" class="staff-exception__card staff-exception__form" data-testid="staff-exception-form">
          <text class="staff-exception__h2">人工处理</text>
          <text class="staff-exception__label">处理方式（必选）</text>
          <view class="staff-exception__options" role="radiogroup" aria-label="处理方式">
            <button
              v-for="item in options"
              :key="item.action"
              class="staff-exception__option"
              :class="{ 'staff-exception__option--on': choice === item.action }"
              role="radio"
              :aria-checked="choice === item.action"
              :data-testid="`staff-exception-option-${item.action}`"
              @click="choice = item.action"
            >
              <view class="staff-exception__radio" :class="{ 'staff-exception__radio--on': choice === item.action }">
                <LineIcon :d="STAFF_SMALL_TICK" :size="12" color="#ffffff" :stroke-width="3" />
              </view>
              <view class="staff-exception__option-text">
                <text class="staff-exception__option-title">{{ item.title }}</text>
                <text class="staff-exception__option-sub">{{ item.sub }}</text>
              </view>
            </button>
          </view>
          <button
            class="staff-exception__identity"
            :class="{ 'staff-exception__identity--on': identityChecked }"
            role="checkbox"
            :aria-checked="identityChecked"
            data-testid="staff-exception-identity"
            @click="identityChecked = !identityChecked"
          >
            <view class="staff-exception__check" :class="{ 'staff-exception__check--on': identityChecked }">
              <LineIcon v-if="identityChecked" :d="STAFF_SMALL_TICK" :size="14" color="#ffffff" :stroke-width="3" />
            </view>
            <view class="staff-exception__option-text">
              <text class="staff-exception__identity-title">已人工核验身份</text>
              <text class="staff-exception__option-sub">放行前核对证件姓名与凭证持有人一致</text>
            </view>
          </button>
          <input
            class="staff-exception__note"
            data-testid="staff-exception-note"
            type="text"
            :value="note"
            :maxlength="NOTE_LIMIT"
            placeholder="备注（选填），如现场情况说明"
            aria-label="备注"
            @input="onNote"
          />
        </view>

        <view v-if="!done && canBlock" class="staff-exception__notice" data-testid="staff-exception-notice">
          <LineIcon :d="STAFF_BLOCK" :size="22" color="#d64f67" :stroke-width="2" />
          <view class="staff-exception__notice-text">
            <text class="staff-exception__notice-title">{{ copy.notice }}</text>
            <text class="staff-exception__notice-sub">无需选择原因，记录后返回继续核销</text>
          </view>
        </view>

        <view v-if="done && current.resolution" class="staff-exception__card staff-exception__done" role="status" data-testid="staff-exception-done">
          <view class="staff-exception__done-head">
            <view class="staff-exception__done-dot"><LineIcon :d="STAFF_SMALL_TICK" :size="12" color="#ffffff" :stroke-width="3" /></view>
            <text class="staff-exception__done-title">已登记</text>
            <text class="staff-exception__done-note">已留痕 · 不能重复提交</text>
          </view>
          <view class="staff-exception__done-lines">
            <text>操作人 {{ content.operatorName }} · {{ clock(current.resolution.resolvedAt) }}</text>
            <text data-testid="staff-exception-done-act">处理：{{ done.act }}</text>
            <text class="staff-exception__ellipsis">原因：{{ done.reason }}</text>
            <text v-if="current.resolution.note" class="staff-exception__ellipsis">备注：{{ current.resolution.note }}</text>
          </view>
          <button class="staff-exception__done-back" data-testid="staff-exception-back-to-scan" @click="emit('backToScan')">返回核销工作台 ›</button>
        </view>
      </view>

      <view v-if="!done && (showForm || canBlock)" class="staff-exception__footer">
        <text class="staff-exception__hint" :class="{ 'staff-exception__hint--warn': !canBlock && !ready }">{{ hint }}</text>
        <view class="staff-exception__footer-actions">
          <button class="staff-exception__back" data-testid="staff-exception-scan" @click="emit('backToScan')">返回扫码</button>
          <button
            v-if="canBlock"
            class="staff-exception__confirm"
            data-testid="staff-exception-block"
            :disabled="busy"
            @click="recordBlock"
          >记录拦截</button>
          <button
            v-else
            class="staff-exception__confirm"
            :class="{ 'staff-exception__confirm--off': !ready || busy }"
            data-testid="staff-exception-confirm"
            :disabled="!ready || busy"
            @click="confirm"
          >确认处理</button>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.staff-exception {
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #202c42;
  background: #f5f7fc;
  font-family: 'PingFang SC', 'Noto Sans SC', 'Microsoft YaHei', sans-serif;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  line-height: inherit;
  text-align: left;
}

button::after {
  border: 0;
}

.staff-exception__status {
  display: flex;
  padding: 60px 24px;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  color: #738096;
  font-size: 14px;
  line-height: 20px;
  text-align: center;
}

.staff-exception__status-title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
}

.staff-exception__plain {
  height: 40px;
  margin-top: 6px;
  padding: 0 20px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  background: #fff;
  font-size: 14px;
  line-height: 38px;
  text-align: center;
}

.staff-exception__body {
  display: flex;
  flex: 1;
  padding: 12px 16px;
  flex-direction: column;
  gap: 10px;
}

.staff-exception__types {
  display: flex;
  align-items: center;
  gap: 8px;
}

.staff-exception__types-label {
  flex: none;
  color: #627187;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.staff-exception__types-scroll {
  min-width: 0;
  flex: 1;
  white-space: nowrap;
}

.staff-exception__types-row {
  display: inline-flex;
  gap: 8px;
}

.staff-exception__type {
  height: 36px;
  padding: 0 12px;
  box-sizing: border-box;
  border: 1px solid #dcdfe4;
  border-radius: 999px;
  background: #fff;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
}

.staff-exception__type--on {
  border-color: #ff6a00;
  background: #fff0e3;
  color: #ba470b;
  font-weight: 500;
}

.staff-exception__hero {
  display: flex;
  padding: 4px 0 2px;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  text-align: center;
}

.staff-exception__hero-icon {
  display: flex;
  width: 64px;
  height: 64px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff0e3;
}

.staff-exception__hero-icon--red {
  background: #fdecec;
}

.staff-exception__hero-title {
  font-size: 19px;
  font-weight: 700;
  line-height: 26px;
}

.staff-exception__hero-sub {
  color: #627187;
  font-size: 13px;
  line-height: 18px;
}

.staff-exception__card {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #fff;
}

.staff-exception__info {
  padding: 2px 14px 12px;
}

.staff-exception__info-row {
  display: flex;
  min-height: 42px;
  box-sizing: border-box;
  align-items: center;
  gap: 12px;
  border-top: 1px solid #f0f1f3;
  font-size: 14px;
  line-height: 20px;
}

.staff-exception__info-row--first {
  border-top-color: transparent;
}

.staff-exception__info-k {
  width: 64px;
  flex: none;
  color: #627187;
  white-space: nowrap;
}

.staff-exception__info-v {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-exception__fact {
  display: flex;
  margin-top: 4px;
  padding: 8px 10px;
  align-items: center;
  gap: 8px;
  border-radius: 8px;
  background: #fff0e3;
  color: #ba470b;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.staff-exception__fact--red {
  background: #fdecec;
  color: #d64f67;
}

.staff-exception__form {
  gap: 8px;
  padding: 4px 14px 14px;
}

.staff-exception__h2 {
  display: flex;
  min-height: 36px;
  align-items: center;
  font-size: 15px;
  font-weight: 700;
  line-height: 36px;
}

.staff-exception__label {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
}

.staff-exception__options {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.staff-exception__option,
.staff-exception__identity {
  display: flex;
  width: 100%;
  min-height: 60px;
  padding: 8px 12px;
  box-sizing: border-box;
  align-items: center;
  gap: 10px;
  border: 1.5px solid #e5ebf3;
  border-radius: 12px;
  background: #fff;
}

.staff-exception__identity {
  min-height: 44px;
  padding: 6px 12px;
  border-width: 1px;
  border-radius: 10px;
}

.staff-exception__option--on,
.staff-exception__identity--on {
  border-color: #ff6a00;
  background: #fffaf5;
}

.staff-exception__radio {
  display: flex;
  width: 20px;
  height: 20px;
  flex: none;
  box-sizing: border-box;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #c9d3e1;
  border-radius: 50%;
  background: #fff;
}

.staff-exception__radio--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.staff-exception__check {
  display: flex;
  width: 20px;
  height: 20px;
  flex: none;
  box-sizing: border-box;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #c9d3e1;
  border-radius: 4px;
  background: #fff;
}

.staff-exception__check--on {
  border-color: #ff6a00;
  background: #ff6a00;
}

.staff-exception__option-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.staff-exception__option-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.staff-exception__identity-title {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
}

.staff-exception__option-sub {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.staff-exception__note {
  height: 44px;
  padding: 0 12px;
  box-sizing: border-box;
  border: 1px solid #e5ebf3;
  border-radius: 10px;
  background: #f5f7fc;
  color: #202c42;
  font-size: 14px;
}

.staff-exception__notice {
  display: flex;
  padding: 12px 14px;
  align-items: flex-start;
  gap: 10px;
  border: 1px solid #f6c4c6;
  border-radius: 14px;
  background: #fdecec;
}

.staff-exception__notice-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.staff-exception__notice-title {
  color: #d64f67;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
}

.staff-exception__notice-sub {
  color: #536178;
  font-size: 12px;
  line-height: 17px;
}

.staff-exception__done {
  padding: 12px 14px 0;
}

.staff-exception__done-head {
  display: flex;
  min-height: 28px;
  align-items: center;
  gap: 8px;
}

.staff-exception__done-dot {
  display: flex;
  width: 22px;
  height: 22px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #159a70;
}

.staff-exception__done-title {
  flex: 1;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.staff-exception__done-note {
  flex: none;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.staff-exception__done-lines {
  display: flex;
  padding: 8px 0 10px 30px;
  flex-direction: column;
  gap: 4px;
  color: #536178;
  font-size: 14px;
  line-height: 20px;
}

.staff-exception__ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-exception__done-back {
  display: flex;
  min-height: 44px;
  align-items: center;
  border-top: 1px solid #f0f1f3;
  color: #ba470b;
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
}

.staff-exception__footer {
  position: sticky;
  bottom: 0;
  display: flex;
  padding: 8px 16px calc(12px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 6px;
  border-top: 1px solid #e5ebf3;
  background: #fff;
}

.staff-exception__hint {
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-exception__hint--warn {
  color: #ba470b;
}

.staff-exception__footer-actions {
  display: flex;
  gap: 10px;
}

.staff-exception__back,
.staff-exception__confirm {
  min-width: 0;
  height: 46px;
  border-radius: 999px;
  text-align: center;
  white-space: nowrap;
}

.staff-exception__back {
  flex: 1 1 0;
  box-sizing: border-box;
  border: 1px solid #c9d3e1;
  background: #fff;
  font-size: 15px;
  line-height: 44px;
}

.staff-exception__confirm {
  flex: 1.6 1 0;
  background: #ff6a00;
  color: #fff;
  font-size: 16px;
  font-weight: 500;
  line-height: 46px;
}

.staff-exception__confirm--off {
  background: #c9d3e1;
}
</style>
