<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { inspectionAccountApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { classifyStaffFailure, resolutionErrorText } from '@/features/staff/application/staff-page-errors'
import { STAFF_WORK_MODE_ENABLED } from '@/features/staff/application/staff-work-mode'
import { exceptionChips } from '@/features/staff/application/workbench-view'
import { checkinApi } from '@/features/staff/data/checkin-api-client'
import type { CheckinAction, CheckinRecord } from '@/features/staff/domain/checkin'
import {
  STAFF_VERIFY_PATH,
  buildStaffExceptionUrl,
  buildStaffVerifyUrl,
  parseStaffExceptionQuery,
  type StaffRecordRef,
} from '@/features/staff/navigation/staff-routes'
import StaffExceptionPage, { type StaffExceptionState } from '@/features/staff/presentation/StaffExceptionPage.vue'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'
import { toBeijingDate } from '@/shared/time/beijing-time'

// 核销异常处理 (board StaffException). Opened on one record (from the workbench's 去异常处理 or a
// record's 查看异常登记), or on the first one of today still waiting for a decision (异常登记).
const state = ref<StaffExceptionState>({ kind: 'loading' })
const busy = ref(false)
const target = ref<StaffRecordRef | null>(null)
let leaving = false

const accessToken = () => getBookingAuthContext().getAccessToken()

function openLogin(): void {
  if (leaving) return
  leaving = true
  uni.redirectTo({ url: buildInspectionLoginUrl(buildStaffExceptionUrl(target.value)) })
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildStaffVerifyUrl() })
}

// 返回扫码 / 返回核销工作台: back to the workbench underneath when it is there.
function backToScan(): void {
  const pages = getCurrentPages()
  const below = pages.length > 1 ? `/${pages[pages.length - 2].route ?? ''}` : ''
  if (below === STAFF_VERIFY_PATH) {
    uni.navigateBack()
    return
  }
  uni.redirectTo({ url: buildStaffVerifyUrl() })
}

async function load(): Promise<void> {
  const token = accessToken()
  if (!token) {
    openLogin()
    return
  }
  if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
  try {
    const wanted = target.value
    const [records, account, picked] = await Promise.all([
      checkinApi.records(token, toBeijingDate(new Date())),
      inspectionAccountApi.loadCurrent(token).catch(() => null),
      wanted ? checkinApi.record(token, wanted.kind, wanted.recordId) : Promise.resolve(null),
    ])
    if (accessToken() !== token) return
    const items: CheckinRecord[] = picked && !records.items.some((item) => item.recordId === picked.recordId)
      ? [picked, ...records.items]
      : records.items.map((item) => (picked && item.recordId === picked.recordId ? picked : item))
    const selectedId = picked?.recordId ?? exceptionChips(items, null)[0]?.record.recordId ?? null
    state.value = {
      kind: 'ready',
      content: {
        day: records.date,
        operatorName: account?.displayName || '本人',
        records: items,
        selectedId,
      },
    }
  } catch (error) {
    const failure = classifyStaffFailure(error)
    if (failure.kind === 'login') openLogin()
    else state.value = failure.kind === 'forbidden' ? { kind: 'forbidden' } : { kind: 'error', message: failure.message }
  }
}

function select(record: CheckinRecord): void {
  target.value = { kind: record.kind, recordId: record.recordId }
  if (state.value.kind === 'ready') {
    state.value = { kind: 'ready', content: { ...state.value.content, selectedId: record.recordId } }
  }
}

async function resolve(record: CheckinRecord, action: CheckinAction, identityChecked: boolean, note: string): Promise<void> {
  const token = accessToken()
  if (!token) {
    openLogin()
    return
  }
  busy.value = true
  try {
    const settled = await checkinApi.resolve(token, record.kind, record.recordId, { action, identityChecked, note })
    if (state.value.kind === 'ready') {
      const content = state.value.content
      state.value = {
        kind: 'ready',
        content: {
          ...content,
          records: content.records.map((item) => (item.recordId === settled.recordId ? settled : item)),
          selectedId: settled.recordId,
        },
      }
    }
  } catch (error) {
    const failure = classifyStaffFailure(error)
    if (failure.kind === 'login') openLogin()
    else uni.showToast({ title: resolutionErrorText(error), icon: 'none' })
  } finally {
    busy.value = false
  }
}

onLoad((query) => {
  target.value = parseStaffExceptionQuery(query as Record<string, unknown> | undefined)
})

onShow(() => {
  if (!STAFF_WORK_MODE_ENABLED) return
  leaving = false
  void load()
})
</script>

<template>
  <StaffExceptionPage
    v-if="STAFF_WORK_MODE_ENABLED"
    :state="state"
    :busy="busy"
    @back="goBack"
    @retry="load"
    @select="select"
    @resolve="resolve"
    @back-to-scan="backToScan"
  />
  <SupportUnavailable v-else text="工作模式尚未开通" />
</template>
