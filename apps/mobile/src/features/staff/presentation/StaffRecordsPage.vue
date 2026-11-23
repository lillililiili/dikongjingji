<script setup lang="ts">
import { computed, ref } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  dayTitle,
  daySummary,
  groupRecords,
  isAdmitted,
  isException,
  methodLabel,
  outcomeLabel,
  recordSession,
  resolutionLines,
  sessionChips,
  tabCounts,
  timeOnDay,
  holderLine,
  type RecordTab,
} from '../application/workbench-view'
import type { CheckinRecord, CheckinSession } from '../domain/checkin'

import { STAFF_CHEVRON, STAFF_SEARCH } from './staff-icons'

// 核销记录 (board StaffRecords, 390 wide): the operator's own records of one day, grouped by session.
// The day in the head opens a date picker; the page host reads that day again.
export interface StaffRecordsContent {
  day: string
  today: string
  operatorName: string
  sessions: CheckinSession[]
  records: CheckinRecord[]
}

export type StaffRecordsState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'forbidden' }
  | { kind: 'ready'; content: StaffRecordsContent }

const props = defineProps<{ state: StaffRecordsState; earliestDay: string }>()

const emit = defineEmits<{
  back: []
  retry: []
  pickDay: [day: string]
  openException: [record: CheckinRecord]
}>()

const TABS: { id: RecordTab; name: string }[] = [
  { id: 'all', name: '全部' },
  { id: 'ok', name: '已核销' },
  { id: 'bad', name: '异常' },
]

const tab = ref<RecordTab>('all')
const session = ref('')
const keyword = ref('')
const openId = ref('')

const content = computed(() => (props.state.kind === 'ready' ? props.state.content : null))
const counts = computed(() => tabCounts(content.value?.records ?? []))
const chips = computed(() => sessionChips(content.value?.sessions ?? []))
const groups = computed(() => {
  const value = content.value
  if (!value) return []
  return groupRecords(value.records, value.sessions, { tab: tab.value, session: session.value, keyword: keyword.value }, value.day)
})
const summary = computed(() => daySummary(content.value?.sessions ?? []))
const isToday = computed(() => content.value?.day === content.value?.today)

const opened = computed(() => content.value?.records.find((record) => record.recordId === openId.value) ?? null)
const sheetRows = computed(() => {
  const record = opened.value
  const value = content.value
  if (!record || !value) return []
  const ref = recordSession(record)
  const rows = [
    { k: '场次', v: ref ? `${timeOnDay(ref.startsAt, value.day)} ${ref.title}` : '—' },
    { k: '凭证尾号', v: record.codeTail },
    { k: '时间', v: `${timeOnDay(record.occurredAt, '')}` },
    { k: '方式', v: methodLabel(record) },
    { k: '操作人', v: `核销员 ${value.operatorName}` },
  ]
  if (!isException(record)) return rows
  const lines = resolutionLines(record, value.day)
  return [
    ...rows,
    { k: '异常', v: outcomeLabel(record.outcome) },
    { k: '处理', v: lines?.act ?? '待处理' },
    ...(lines ? [{ k: '原因', v: lines.reason }] : []),
    ...(record.resolution?.note ? [{ k: '备注', v: record.resolution.note }] : []),
  ]
})

function clearFilter(): void {
  tab.value = 'all'
  session.value = ''
  keyword.value = ''
}

function onKeyword(event: Event): void {
  const detail = (event as unknown as { detail?: { value?: string } }).detail
  keyword.value = detail?.value ?? (event.target as HTMLInputElement | null)?.value ?? ''
}

function onPickDay(event: Event): void {
  const detail = (event as unknown as { detail?: { value?: string } }).detail
  if (detail?.value) emit('pickDay', detail.value)
}

function openException(): void {
  const record = opened.value
  openId.value = ''
  if (record) emit('openException', record)
}
</script>

<template>
  <view class="staff-records" data-testid="staff-records-page">
    <AppPageHeader title="核销记录" plain test-id="staff-records" @back="emit('back')">
      <template #actions>
        <picker
          v-if="content"
          mode="date"
          :value="content.day"
          :start="earliestDay"
          :end="content.today"
          @change="onPickDay"
        >
          <view class="staff-records__day" data-testid="staff-records-day">{{ dayTitle(content.day) }}</view>
        </picker>
      </template>
      <template #bottom>
        <view v-if="content" class="staff-records__tabs" role="tablist" aria-label="记录类型">
          <button
            v-for="item in TABS"
            :key="item.id"
            class="staff-records__tab"
            :class="{ 'staff-records__tab--on': tab === item.id }"
            role="tab"
            :aria-selected="tab === item.id"
            :data-testid="`staff-records-tab-${item.id}`"
            @click="tab = item.id"
          >{{ item.name }} {{ counts[item.id] }}</button>
        </view>
      </template>
    </AppPageHeader>

    <view v-if="state.kind === 'loading'" class="staff-records__status"><text>正在读取核销记录…</text></view>
    <view v-else-if="state.kind === 'forbidden'" class="staff-records__status" data-testid="staff-records-forbidden">
      <text>当前账号没有核销权限</text>
    </view>
    <view v-else-if="state.kind === 'error'" class="staff-records__status">
      <text>{{ state.message }}</text>
      <button class="staff-records__retry" @click="emit('retry')">重试</button>
    </view>

    <view v-else-if="content" class="staff-records__body">
      <view class="staff-records__search">
        <view class="staff-records__search-icon"><LineIcon :d="STAFF_SEARCH" :size="18" color="#738096" /></view>
        <input
          class="staff-records__search-input"
          data-testid="staff-records-search"
          type="text"
          :value="keyword"
          placeholder="搜索姓名或凭证尾号"
          aria-label="搜索姓名或凭证尾号"
          @input="onKeyword"
        />
      </view>

      <scroll-view v-if="chips.length > 1" class="staff-records__chips" scroll-x :show-scrollbar="false">
        <view class="staff-records__chip-row" role="group" aria-label="按场次筛选">
          <button
            v-for="chip in chips"
            :key="chip.key || 'all'"
            class="staff-records__chip"
            :aria-pressed="session === chip.key"
            :data-testid="`staff-records-chip-${chip.key || 'all'}`"
            @click="session = chip.key"
          >
            <text class="staff-records__chip-text" :class="{ 'staff-records__chip-text--on': session === chip.key }">{{ chip.label }}</text>
          </button>
        </view>
      </scroll-view>

      <view class="staff-records__summary" data-testid="staff-records-summary">
        <text>{{ isToday ? '今日' : dayTitle(content.day) }}已核销 </text>
        <text class="staff-records__summary-ok">{{ summary.done }}</text>
        <text> / {{ summary.total }} 人</text>
        <text class="staff-records__summary-gap">异常 </text>
        <text class="staff-records__summary-bad">{{ counts.bad }}</text>
        <text> 条</text>
      </view>

      <view v-for="group in groups" :key="group.key || 'loose'" class="staff-records__group" data-testid="staff-records-group">
        <view class="staff-records__group-head">
          <text class="staff-records__group-title">{{ group.title }}</text>
          <text v-if="group.tone" class="staff-records__tag" :style="{ background: group.tone.tagBg, color: group.tone.tagColor }">{{ group.tone.status }}</text>
          <text v-if="group.total !== null" class="staff-records__group-count">{{ group.done }} / {{ group.total }} 人</text>
        </view>
        <button
          v-for="row in group.rows"
          :key="row.recordId"
          class="staff-records__row"
          aria-haspopup="dialog"
          :data-testid="`staff-record-${row.recordId}`"
          @click="openId = row.recordId"
        >
          <view class="staff-records__dot" :style="{ background: row.dotBg }"><LineIcon :d="row.dotPath" :size="12" color="#ffffff" :stroke-width="3" /></view>
          <view class="staff-records__row-text">
            <text class="staff-records__row-title">{{ row.title }}</text>
            <text class="staff-records__row-sub">{{ row.sub }}</text>
          </view>
          <text class="staff-records__tag" :style="{ background: row.tagBg, color: row.tagColor }">{{ row.tag }}</text>
          <LineIcon :d="STAFF_CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
        <view v-if="group.empty" class="staff-records__group-empty">还没有核销记录</view>
      </view>

      <view v-if="!groups.length" class="staff-records__empty" data-testid="staff-records-empty">
        <LineIcon :d="STAFF_SEARCH" :size="40" color="#c9d3e1" :stroke-width="1.6" />
        <text>没有符合条件的记录</text>
        <button class="staff-records__clear" data-testid="staff-records-clear" @click="clearFilter">清除筛选</button>
      </view>

      <text class="staff-records__note">仅显示本人{{ isToday ? '今日' : '当日' }}记录，操作人和时间已留存</text>
    </view>

    <template v-if="opened && content">
      <button class="staff-records__mask" aria-label="关闭" @click="openId = ''" />
      <view class="staff-records__sheet" role="dialog" aria-modal="true" aria-label="记录详情" data-testid="staff-records-sheet">
        <view class="staff-records__sheet-head">
          <text class="staff-records__sheet-title">{{ holderLine(opened) }}</text>
          <text
            class="staff-records__tag"
            :style="isAdmitted(opened) ? { background: '#e2f6ed', color: '#11804a' } : { background: '#fff0e3', color: '#ba470b' }"
          >{{ isAdmitted(opened) ? (isException(opened) ? '已放行' : '已核销') : '未核销' }}</text>
        </view>
        <view class="staff-records__sheet-grid">
          <template v-for="row in sheetRows" :key="row.k">
            <text class="staff-records__sheet-k">{{ row.k }}</text>
            <text class="staff-records__sheet-v">{{ row.v }}</text>
          </template>
        </view>
        <view class="staff-records__sheet-actions">
          <button class="staff-records__sheet-close" data-testid="staff-records-sheet-close" @click="openId = ''">关闭</button>
          <button
            v-if="isException(opened)"
            class="staff-records__sheet-primary"
            data-testid="staff-records-sheet-exception"
            @click="openException"
          >查看异常登记</button>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.staff-records {
  position: relative;
  min-height: 100vh;
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

.staff-records__day {
  padding-right: 8px;
  color: #738096;
  font-size: 13px;
  line-height: 44px;
  white-space: nowrap;
}

.staff-records__tabs {
  display: flex;
  padding: 0 16px;
  gap: 22px;
  border-bottom: 1px solid #f0f1f3;
  background: #fff;
}

.staff-records__tab {
  min-height: 44px;
  padding: 10px 0;
  box-sizing: border-box;
  border-bottom: 3px solid transparent;
  color: #627187;
  font-size: 15px;
  line-height: 21px;
  white-space: nowrap;
}

.staff-records__tab--on {
  border-bottom-color: #ff6a00;
  color: #202c42;
  font-weight: 700;
}

.staff-records__status {
  display: flex;
  padding: 60px 24px;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  color: #738096;
  font-size: 14px;
}

.staff-records__retry,
.staff-records__clear {
  height: 36px;
  padding: 0 16px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  background: #fff;
  font-size: 13px;
  line-height: 34px;
  text-align: center;
  white-space: nowrap;
}

.staff-records__body {
  display: flex;
  padding: 12px 16px calc(16px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 10px;
}

.staff-records__search {
  position: relative;
}

.staff-records__search-icon {
  position: absolute;
  top: 13px;
  left: 12px;
  z-index: 1;
}

.staff-records__search-input {
  width: 100%;
  height: 44px;
  padding: 0 12px 0 38px;
  box-sizing: border-box;
  border-radius: 999px;
  background: #fff;
  color: #202c42;
  font-size: 14px;
}

.staff-records__chips {
  width: 100%;
  white-space: nowrap;
}

.staff-records__chip-row {
  display: inline-flex;
  gap: 6px;
}

.staff-records__chip {
  padding: 0;
}

.staff-records__chip-text {
  display: flex;
  height: 32px;
  padding: 0 12px;
  align-items: center;
  border-radius: 999px;
  background: #fff;
  color: #202c42;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.staff-records__chip-text--on {
  background: #202c42;
  color: #fff;
}

.staff-records__summary {
  display: flex;
  align-items: baseline;
  gap: 4px;
  color: #536178;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.staff-records__summary-ok,
.staff-records__summary-bad {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.staff-records__summary-ok {
  color: #11804a;
}

.staff-records__summary-bad {
  color: #ba470b;
}

.staff-records__summary-gap {
  margin-left: 8px;
}

.staff-records__group {
  display: flex;
  padding: 0 14px;
  flex-direction: column;
  border-radius: 14px;
  background: #fff;
}

.staff-records__group-head {
  display: flex;
  min-height: 48px;
  align-items: center;
  gap: 8px;
}

.staff-records__group-title {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-records__tag {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.staff-records__group-count {
  min-width: 44px;
  flex: none;
  color: #536178;
  font-size: 14px;
  line-height: 20px;
  text-align: right;
  white-space: nowrap;
}

.staff-records__row {
  display: flex;
  width: 100%;
  min-height: 58px;
  box-sizing: border-box;
  align-items: center;
  gap: 10px;
  border-top: 1px solid #f0f1f3;
}

.staff-records__dot {
  display: flex;
  width: 22px;
  height: 22px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}

.staff-records__row-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.staff-records__row-title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-records__row-sub {
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-records__group-empty {
  display: flex;
  min-height: 44px;
  align-items: center;
  border-top: 1px solid #f0f1f3;
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.staff-records__empty {
  display: flex;
  padding: 40px 16px;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  border-radius: 14px;
  background: #fff;
  color: #738096;
  font-size: 14px;
  line-height: 20px;
}

.staff-records__note {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.staff-records__mask {
  position: fixed;
  inset: 0;
  z-index: 20;
  background: rgba(0, 0, 0, 0.45);
}

.staff-records__sheet {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 21;
  display: flex;
  padding: 20px 16px calc(24px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 14px;
  border-radius: 20px 20px 0 0;
  background: #fff;
}

.staff-records__sheet-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.staff-records__sheet-title {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-records__sheet-grid {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 10px;
  font-size: 14px;
  line-height: 20px;
}

.staff-records__sheet-k {
  color: #627187;
  white-space: nowrap;
}

.staff-records__sheet-actions {
  display: flex;
  gap: 10px;
}

.staff-records__sheet-close,
.staff-records__sheet-primary {
  height: 44px;
  flex: 1 1 0;
  border-radius: 999px;
  font-size: 15px;
  line-height: 44px;
  text-align: center;
  white-space: nowrap;
}

.staff-records__sheet-close {
  border: 1px solid #c9d3e1;
  background: #fff;
  line-height: 42px;
}

.staff-records__sheet-primary {
  background: #ff6a00;
  color: #fff;
  font-weight: 500;
}
</style>
