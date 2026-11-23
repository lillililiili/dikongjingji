<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { inspectionAccountApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import { MINE_TAB_PATH, buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { parseCheckinCode } from '@/features/staff/application/checkin-code'
import { classifyStaffFailure } from '@/features/staff/application/staff-page-errors'
import { STAFF_WORK_MODE_ENABLED } from '@/features/staff/application/staff-work-mode'
import { pickDefaultSession, sessionKey } from '@/features/staff/application/workbench-view'
import { checkinApi } from '@/features/staff/data/checkin-api-client'
import type { CheckinMethod, CheckinRecord } from '@/features/staff/domain/checkin'
import {
  buildStaffExceptionUrl,
  buildStaffRecordsUrl,
  buildStaffVerifyUrl,
} from '@/features/staff/navigation/staff-routes'
import StaffVerifyPage, {
  type StaffVerifyContent,
  type StaffVerifyResult,
  type StaffVerifyState,
} from '@/features/staff/presentation/StaffVerifyPage.vue'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'

// 核销工作台 (board StaffVerify). Reads today's sessions and the operator's own records; scans with
// the camera (uni.scanCode) in the App, and only takes a typed code in a browser.
let canScan = true
// #ifdef H5
canScan = false
// #endif

const state = ref<StaffVerifyState>({ kind: 'loading' })
const result = ref<StaffVerifyResult | null>(null)
const busy = ref(false)
let selectedKey: string | null = null
let shownBefore = false
let leaving = false

const accessToken = () => getBookingAuthContext().getAccessToken()

function openLogin(): void {
  if (leaving) return
  leaving = true
  uni.redirectTo({ url: buildInspectionLoginUrl(buildStaffVerifyUrl()) })
}

function goMine(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.switchTab({ url: MINE_TAB_PATH })
}

async function load(quiet = false): Promise<void> {
  const token = accessToken()
  if (!token) {
    openLogin()
    return
  }
  if (!quiet || state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const [workbench, records, account] = await Promise.all([
      checkinApi.workbench(token),
      checkinApi.records(token),
      inspectionAccountApi.loadCurrent(token).catch(() => null),
    ])
    if (accessToken() !== token) return
    if (!workbench.kinds.length) {
      state.value = { kind: 'forbidden' }
      return
    }
    selectedKey = pickDefaultSession(workbench.sessions, selectedKey)
    const content: StaffVerifyContent = {
      operatorName: account?.displayName || '本人',
      organizationName: account?.organizationName || '本机构',
      kinds: workbench.kinds,
      day: workbench.date,
      sessions: workbench.sessions,
      selectedKey,
      records: records.items,
    }
    state.value = { kind: 'ready', content }
  } catch (error) {
    const failure = classifyStaffFailure(error)
    if (failure.kind === 'login') openLogin()
    else state.value = failure.kind === 'forbidden' ? { kind: 'forbidden' } : { kind: 'error', message: failure.message }
  }
}

function select(key: string): void {
  selectedKey = key
  if (state.value.kind === 'ready') state.value = { kind: 'ready', content: { ...state.value.content, selectedKey: key } }
}

async function verify(text: string, method: CheckinMethod): Promise<void> {
  const token = accessToken()
  if (!token) {
    openLogin()
    return
  }
  const code = parseCheckinCode(text)
  if (!code) {
    result.value = { kind: 'refused', code: 'COMMON_INVALID_ARGUMENT' }
    return
  }
  const content = state.value.kind === 'ready' ? state.value.content : null
  const session = content?.sessions.find((item) => sessionKey(item) === content.selectedKey) ?? null
  busy.value = true
  try {
    const outcome = await checkinApi.verify(token, {
      code,
      method,
      session: session ? { kind: session.kind, sessionId: session.sessionId } : null,
    })
    result.value = outcome.kind === 'checked-in'
      ? { kind: 'ok', record: outcome.record }
      : outcome.kind === 'rejected'
        ? { kind: 'rejected', record: outcome.record }
        : { kind: 'refused', code: outcome.code }
    await load(true)
  } catch (error) {
    const failure = classifyStaffFailure(error)
    if (failure.kind === 'login') openLogin()
    else if (failure.kind === 'forbidden') state.value = { kind: 'forbidden' }
    else result.value = { kind: 'refused', code: 'NETWORK', message: failure.message }
  } finally {
    busy.value = false
  }
}

function scan(): void {
  uni.scanCode({
    onlyFromCamera: true,
    scanType: ['qrCode', 'barCode'],
    success: (response) => { void verify(response.result, 'SCAN') },
    fail: (error: { errMsg?: string }) => {
      if (!String(error?.errMsg ?? '').includes('cancel')) {
        uni.showToast({ title: '没能打开扫码，请手动输入凭证号', icon: 'none' })
      }
    },
  })
}

function openException(record: CheckinRecord | null): void {
  uni.navigateTo({ url: buildStaffExceptionUrl(record ? { kind: record.kind, recordId: record.recordId } : null) })
}

function openRecords(): void {
  uni.navigateTo({ url: buildStaffRecordsUrl() })
}

onShow(() => {
  if (!STAFF_WORK_MODE_ENABLED) return
  leaving = false
  // Back from 异常处理 or 记录: the last result may be settled by now, so the card goes and the page reads again.
  if (shownBefore) result.value = null
  shownBefore = true
  void load(state.value.kind === 'ready')
})
</script>

<template>
  <StaffVerifyPage
    v-if="STAFF_WORK_MODE_ENABLED"
    :state="state"
    :result="result"
    :busy="busy"
    :can-scan="canScan"
    @back="goMine"
    @exit="goMine"
    @retry="load()"
    @select="select"
    @scan="scan"
    @submit="(code: string) => verify(code, 'MANUAL')"
    @open-exception="openException"
    @open-records="openRecords"
  />
  <SupportUnavailable v-else text="工作模式尚未开通" />
</template>
