<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import {
  clock,
  dayTitle,
  failureLine,
  holderLine,
  paidText,
  recentAdmissions,
  recordSession,
  refusalLine,
  scopeHint,
  sessionKey,
  sessionName,
  toSessionRow,
} from '../application/workbench-view'
import type { CheckinKind, CheckinRecord, CheckinSession } from '../domain/checkin'

import { STAFF_CROSS, STAFF_KEYBOARD, STAFF_SCAN, STAFF_SMALL_TICK, STAFF_TICK, STAFF_WARNING } from './staff-icons'

// 核销工作台 (board StaffVerify, 390 wide). The page host reads the workbench and today's records,
// scans and submits; this page draws them and keeps the manual input row.
export interface StaffVerifyContent {
  operatorName: string
  organizationName: string
  kinds: CheckinKind[]
  day: string
  sessions: CheckinSession[]
  selectedKey: string | null
  records: CheckinRecord[]
}

export type StaffVerifyResult =
  | { kind: 'ok'; record: CheckinRecord }
  | { kind: 'rejected'; record: CheckinRecord }
  | { kind: 'refused'; code: string; message?: string }

export type StaffVerifyState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'forbidden' }
  | { kind: 'ready'; content: StaffVerifyContent }

const props = defineProps<{
  state: StaffVerifyState
  result: StaffVerifyResult | null
  busy: boolean
  /** False in a browser: there is no camera scanner, the button opens the manual row instead. */
  canScan: boolean
}>()

const emit = defineEmits<{
  back: []
  exit: []
  retry: []
  select: [key: string]
  scan: []
  submit: [code: string]
  openException: [record: CheckinRecord | null]
  openRecords: []
}>()

const manual = ref(false)
const code = ref('')
const scanHint = ref('')

const content = computed(() => (props.state.kind === 'ready' ? props.state.content : null))
const rows = computed(() => content.value?.sessions.map(toSessionRow) ?? [])
const selected = computed(() =>
  content.value?.sessions.find((session) => sessionKey(session) === content.value?.selectedKey) ?? null)
const recent = computed(() => recentAdmissions(content.value?.records ?? []))
const codeEmpty = computed(() => !code.value.trim())

// A new result closes the manual row, as on the board (the result card takes its place).
watch(() => props.result, (next) => {
  if (next && next.kind !== 'refused') {
    manual.value = false
    code.value = ''
  }
})

function toggleManual(): void {
  manual.value = !manual.value
  code.value = ''
  scanHint.value = ''
}

function scan(): void {
  if (props.busy) return
  if (!props.canScan) {
    manual.value = true
    scanHint.value = '浏览器里不能调用相机扫码，请手动输入凭证号'
    return
  }
  emit('scan')
}

function onInput(event: Event): void {
  const detail = (event as unknown as { detail?: { value?: string } }).detail
  code.value = detail?.value ?? (event.target as HTMLInputElement | null)?.value ?? ''
}

function submit(value?: string): void {
  const text = (value ?? code.value).trim()
  if (!text || props.busy) return
  emit('submit', text)
}

function onConfirm(event: Event): void {
  const detail = (event as unknown as { detail?: { value?: string } }).detail
  submit(detail?.value)
}

const resultLine = computed(() => {
  const result = props.result
  const day = content.value?.day ?? ''
  if (!result) return ''
  if (result.kind === 'refused') return result.message ?? refusalLine(result.code)
  if (result.kind === 'rejected') return failureLine(result.record, day)
  return `${result.record.holderName ?? '持有人'} · ${sessionName(recordSession(result.record), day)}`
})

const resultSub = computed(() => {
  const result = props.result
  if (!result || result.kind !== 'ok') return ''
  const paid = paidText(result.record.amountMinor)
  return paid ? `${result.record.people} 人 · ${paid}` : `${result.record.people} 人`
})

const canHandle = computed(() => props.result?.kind === 'rejected' && props.result.record.allowedActions.length > 0)
</script>

<template>
  <view class="staff-verify" data-testid="staff-verify-page">
    <AppPageHeader title="核销工作台" plain test-id="staff-verify" @back="emit('back')">
      <template #actions>
        <button class="staff-verify__exit" data-testid="staff-verify-exit" @click="emit('exit')">退出工作模式</button>
      </template>
    </AppPageHeader>

    <view v-if="state.kind === 'loading'" class="staff-verify__status" data-testid="staff-verify-loading">
      <text>正在读取今日场次…</text>
    </view>
    <view v-else-if="state.kind === 'forbidden'" class="staff-verify__status" data-testid="staff-verify-forbidden">
      <text class="staff-verify__status-title">当前账号没有核销权限</text>
      <text>只有活动主办或承办机构、场馆运营机构的核销员可以使用工作模式</text>
      <button class="staff-verify__status-action" @click="emit('exit')">返回我的</button>
    </view>
    <view v-else-if="state.kind === 'error'" class="staff-verify__status" data-testid="staff-verify-error">
      <text class="staff-verify__status-title">{{ state.message }}</text>
      <button class="staff-verify__status-action" data-testid="staff-verify-retry" @click="emit('retry')">重试</button>
    </view>

    <view v-else-if="content" class="staff-verify__body">
      <view class="staff-verify__who">
        <text class="staff-verify__badge" data-testid="staff-verify-badge">核销员 {{ content.operatorName }} · {{ content.organizationName }}</text>
        <text class="staff-verify__scope">{{ scopeHint(content.kinds) }}</text>
      </view>

      <view class="staff-verify__card staff-verify__sessions" data-testid="staff-verify-sessions">
        <view class="staff-verify__card-head">
          <text class="staff-verify__h2">今日场次</text>
          <text class="staff-verify__day">{{ dayTitle(content.day) }}</text>
        </view>
        <view v-if="!rows.length" class="staff-verify__none" data-testid="staff-verify-no-session">今天没有场次</view>
        <template v-for="row in rows" :key="row.key">
          <view v-if="row.key === content.selectedKey" class="staff-verify__picked">
            <button
              class="staff-verify__session staff-verify__session--on"
              :data-testid="`staff-session-${row.key}`"
              aria-pressed="true"
              @click="emit('select', row.key)"
            >
              <view class="staff-verify__session-text">
                <text class="staff-verify__session-name">{{ row.name }}</text>
                <text class="staff-verify__session-sub">{{ row.sub }}</text>
              </view>
              <view class="staff-verify__session-count">
                <view class="staff-verify__count-line">
                  <text class="staff-verify__count" :style="{ color: row.tone.numberColor }">{{ row.done }}</text>
                  <text class="staff-verify__count-total"> / {{ row.total }}</text>
                </view>
                <text class="staff-verify__count-unit">{{ row.unit }}</text>
              </view>
            </button>
            <view class="staff-verify__bar"><view class="staff-verify__bar-fill" :style="{ width: row.percent, background: row.tone.barColor }" /></view>
          </view>
          <button
            v-else
            class="staff-verify__session-row"
            :data-testid="`staff-session-${row.key}`"
            aria-pressed="false"
            @click="emit('select', row.key)"
          >
            <text class="staff-verify__row-name">{{ row.time }} {{ row.name }}</text>
            <text class="staff-verify__tag" :style="{ background: row.tone.tagBg, color: row.tone.tagColor }">{{ row.tone.status }}</text>
            <text class="staff-verify__row-count">{{ row.done }} / {{ row.total }}</text>
          </button>
        </template>
      </view>

      <view class="staff-verify__scan-area">
        <button class="staff-verify__scan" data-testid="staff-verify-scan" :disabled="busy" @click="scan">
          <LineIcon :d="STAFF_SCAN" :size="34" color="#ffffff" :stroke-width="2" />
          <text class="staff-verify__scan-title">{{ busy ? '核销中…' : '扫码核销' }}</text>
          <text v-if="selected" class="staff-verify__scan-sub">{{ clock(selected.startsAt) }} 场</text>
        </button>
        <view class="staff-verify__links">
          <button class="staff-verify__link" data-testid="staff-verify-manual-toggle" :aria-expanded="manual" @click="toggleManual">
            <LineIcon :d="STAFF_KEYBOARD" :size="18" color="#536178" />
            <text>{{ manual ? '收起手动输入' : '手动输入凭证号' }}</text>
          </button>
          <view class="staff-verify__divider" />
          <button class="staff-verify__link" data-testid="staff-verify-exception-link" @click="emit('openException', null)">
            <LineIcon :d="STAFF_WARNING" :size="18" color="#536178" />
            <text>异常登记</text>
          </button>
        </view>
        <text v-if="scanHint" class="staff-verify__scan-hint" data-testid="staff-verify-scan-hint">{{ scanHint }}</text>
      </view>

      <view v-if="manual" class="staff-verify__manual" data-testid="staff-verify-manual">
        <input
          class="staff-verify__input"
          data-testid="staff-verify-code"
          :value="code"
          placeholder="输入凭证号"
          aria-label="凭证号"
          maxlength="24"
          confirm-type="done"
          @input="onInput"
          @confirm="onConfirm"
        />
        <button
          class="staff-verify__submit"
          data-testid="staff-verify-submit"
          :class="{ 'staff-verify__submit--off': codeEmpty || busy }"
          :disabled="codeEmpty || busy"
          @click="submit()"
        >核销</button>
      </view>

      <view v-if="result && result.kind === 'ok' && !manual" class="staff-verify__result staff-verify__result--ok" role="status" data-testid="staff-verify-ok">
        <view class="staff-verify__result-icon staff-verify__result-icon--ok"><LineIcon :d="STAFF_TICK" :size="20" color="#159a70" :stroke-width="2.6" /></view>
        <view class="staff-verify__result-text">
          <view class="staff-verify__result-head">
            <text class="staff-verify__result-title staff-verify__result-title--ok">核销成功</text>
            <text class="staff-verify__result-time">{{ clock(result.record.occurredAt) }}</text>
          </view>
          <text class="staff-verify__result-line">{{ resultLine }}</text>
          <text class="staff-verify__result-sub">{{ resultSub }}</text>
        </view>
      </view>

      <view v-if="result && result.kind !== 'ok' && (!manual || result.kind === 'refused')" class="staff-verify__result staff-verify__result--bad" role="status" data-testid="staff-verify-failed">
        <view class="staff-verify__result-icon staff-verify__result-icon--bad"><LineIcon :d="STAFF_CROSS" :size="20" color="#d64f67" :stroke-width="2.6" /></view>
        <view class="staff-verify__result-text">
          <view class="staff-verify__result-head">
            <text class="staff-verify__result-title staff-verify__result-title--bad">核销失败</text>
            <button
              v-if="canHandle && result.kind === 'rejected'"
              class="staff-verify__handle"
              data-testid="staff-verify-handle"
              @click="emit('openException', result.record)"
            >去异常处理 ›</button>
          </view>
          <text class="staff-verify__result-line" data-testid="staff-verify-failed-line">{{ resultLine }}</text>
        </view>
      </view>

      <view class="staff-verify__recent-head">
        <view class="staff-verify__h2 staff-verify__h2--mark"><view class="staff-verify__mark" /><text>最近核销</text></view>
        <button class="staff-verify__all" data-testid="staff-verify-records" @click="emit('openRecords')">全部记录 ›</button>
      </view>
      <view class="staff-verify__card staff-verify__recent" data-testid="staff-verify-recent">
        <view v-if="!recent.length" class="staff-verify__none">今天还没有核销记录</view>
        <view
          v-for="(record, index) in recent"
          :key="record.recordId"
          class="staff-verify__recent-row"
          :class="{ 'staff-verify__recent-row--first': index === 0 }"
          data-testid="staff-verify-recent-row"
        >
          <view class="staff-verify__dot"><LineIcon :d="STAFF_SMALL_TICK" :size="12" color="#ffffff" :stroke-width="3" /></view>
          <view class="staff-verify__recent-text">
            <text class="staff-verify__recent-title">{{ holderLine(record) }}</text>
            <text class="staff-verify__recent-sub">{{ clock(record.occurredAt) }} · {{ sessionName(recordSession(record), content.day) }}</text>
          </view>
          <text class="staff-verify__done-tag">已核销</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.staff-verify {
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

.staff-verify__exit {
  display: flex;
  min-height: 44px;
  padding: 0 8px;
  align-items: center;
  color: #536178;
  font-size: 14px;
  white-space: nowrap;
}

.staff-verify__status {
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

.staff-verify__status-title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
}

.staff-verify__status-action {
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

.staff-verify__body {
  display: flex;
  padding: 10px 16px calc(16px + env(safe-area-inset-bottom));
  flex-direction: column;
  gap: 10px;
}

.staff-verify__who {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.staff-verify__badge {
  overflow: hidden;
  max-width: 100%;
  height: 26px;
  padding: 0 10px;
  box-sizing: border-box;
  border-radius: 6px;
  background: #e2f6ed;
  color: #11804a;
  font-size: 13px;
  line-height: 26px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-verify__scope {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
}

.staff-verify__card {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  background: #fff;
}

.staff-verify__sessions {
  gap: 4px;
  padding: 2px 14px 12px;
}

.staff-verify__card-head {
  display: flex;
  min-height: 40px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.staff-verify__h2 {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.staff-verify__day {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.staff-verify__none {
  display: flex;
  min-height: 44px;
  align-items: center;
  color: #738096;
  font-size: 13px;
  line-height: 18px;
}

.staff-verify__picked {
  display: flex;
  padding: 2px 0;
  flex-direction: column;
  gap: 6px;
}

.staff-verify__session {
  display: flex;
  width: 100%;
  padding: 8px 12px;
  box-sizing: border-box;
  align-items: center;
  gap: 10px;
  border: 1.5px solid #ff6a00;
  border-radius: 12px;
  background: #fffaf5;
}

.staff-verify__session-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.staff-verify__session-name {
  overflow: hidden;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-verify__session-sub {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.staff-verify__session-count {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.staff-verify__count-line {
  display: flex;
  height: 28px;
  align-items: baseline;
  white-space: nowrap;
}

.staff-verify__count {
  font-size: 24px;
  font-weight: 700;
  line-height: 28px;
}

.staff-verify__count-total {
  color: #738096;
  font-size: 15px;
}

.staff-verify__count-unit {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.staff-verify__bar {
  overflow: hidden;
  height: 6px;
  border-radius: 3px;
  background: #f0f1f3;
}

.staff-verify__bar-fill {
  height: 6px;
  border-radius: 3px;
}

.staff-verify__session-row {
  display: flex;
  width: 100%;
  min-height: 40px;
  padding: 0 2px;
  box-sizing: border-box;
  align-items: center;
  gap: 8px;
}

.staff-verify__row-name {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-verify__tag {
  flex: none;
  height: 22px;
  padding: 0 6px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 22px;
  white-space: nowrap;
}

.staff-verify__row-count {
  min-width: 44px;
  flex: none;
  color: #536178;
  font-size: 14px;
  line-height: 20px;
  text-align: right;
  white-space: nowrap;
}

.staff-verify__scan-area {
  display: flex;
  padding-top: 4px;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.staff-verify__scan {
  display: flex;
  width: 120px;
  height: 120px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 50%;
  background: linear-gradient(160deg, #ff9a3c 0%, #ff6a00 70%);
  box-shadow: 0 10px 24px rgba(255, 106, 0, 0.28);
  color: #fff;
  text-align: center;
}

.staff-verify__scan[disabled] {
  opacity: 0.7;
}

.staff-verify__scan-title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  white-space: nowrap;
}

.staff-verify__scan-sub {
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.staff-verify__links {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.staff-verify__link {
  display: flex;
  min-height: 44px;
  padding: 0 10px;
  align-items: center;
  gap: 6px;
  color: #536178;
  font-size: 14px;
  white-space: nowrap;
}

.staff-verify__divider {
  width: 1px;
  height: 14px;
  background: #dcdfe4;
}

.staff-verify__scan-hint {
  color: #ba470b;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.staff-verify__manual {
  display: flex;
  padding: 6px;
  align-items: center;
  gap: 8px;
  border-radius: 12px;
  background: #fff;
}

.staff-verify__input {
  min-width: 0;
  height: 44px;
  flex: 1;
  padding: 0 12px;
  box-sizing: border-box;
  border: 1px solid #e5ebf3;
  border-radius: 8px;
  background: #f5f7fc;
  color: #202c42;
  font-size: 14px;
}

.staff-verify__submit {
  flex: none;
  height: 44px;
  padding: 0 18px;
  border-radius: 8px;
  background: #ff6a00;
  color: #fff;
  font-size: 15px;
  font-weight: 500;
  line-height: 44px;
  text-align: center;
  white-space: nowrap;
}

.staff-verify__submit--off {
  background: #c9d3e1;
}

.staff-verify__result {
  display: flex;
  padding: 12px 14px;
  gap: 12px;
  border-radius: 14px;
  background: #fff;
}

.staff-verify__result--ok {
  border: 1px solid #c9eed9;
}

.staff-verify__result--bad {
  border: 1px solid #f6c4c6;
}

.staff-verify__result-icon {
  display: flex;
  width: 36px;
  height: 36px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}

.staff-verify__result-icon--ok {
  background: #e2f6ed;
}

.staff-verify__result-icon--bad {
  background: #fdecec;
}

.staff-verify__result-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.staff-verify__result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.staff-verify__result-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
}

.staff-verify__result-title--ok {
  color: #11804a;
}

.staff-verify__result-title--bad {
  color: #d64f67;
}

.staff-verify__result-time {
  color: #738096;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.staff-verify__handle {
  display: flex;
  min-height: 44px;
  margin: -6px 0;
  padding-left: 8px;
  align-items: center;
  color: #ba470b;
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
}

.staff-verify__result-line {
  overflow: hidden;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-verify__result-sub {
  color: #536178;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.staff-verify__recent-head {
  display: flex;
  min-height: 36px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.staff-verify__h2--mark {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  line-height: 24px;
}

.staff-verify__mark {
  width: 4px;
  height: 16px;
  border-radius: 2px;
  background: #ff6a00;
}

.staff-verify__all {
  display: flex;
  min-height: 44px;
  margin: -4px 0;
  padding: 0 0 0 8px;
  align-items: center;
  color: #627187;
  font-size: 13px;
  white-space: nowrap;
}

.staff-verify__recent {
  padding: 0 14px;
}

.staff-verify__recent-row {
  display: flex;
  min-height: 56px;
  box-sizing: border-box;
  align-items: center;
  gap: 12px;
  border-top: 1px solid #f0f1f3;
}

.staff-verify__recent-row--first {
  border-top-color: transparent;
}

.staff-verify__dot {
  display: flex;
  width: 22px;
  height: 22px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #159a70;
}

.staff-verify__recent-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.staff-verify__recent-title {
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-verify__recent-sub {
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staff-verify__done-tag {
  flex: none;
  height: 24px;
  padding: 0 8px;
  border-radius: 6px;
  background: #e2f6ed;
  color: #11804a;
  font-size: 12px;
  line-height: 24px;
  white-space: nowrap;
}
</style>
