<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { inspectionAccountApi } from '@/features/inspection/data/service-flow/uni-booking-http-client'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { classifyStaffFailure } from '@/features/staff/application/staff-page-errors'
import { STAFF_WORK_MODE_ENABLED } from '@/features/staff/application/staff-work-mode'
import { checkinApi } from '@/features/staff/data/checkin-api-client'
import type { CheckinRecord } from '@/features/staff/domain/checkin'
import {
  buildStaffExceptionUrl,
  buildStaffRecordsUrl,
  buildStaffVerifyUrl,
  parseStaffRecordsQuery,
} from '@/features/staff/navigation/staff-routes'
import StaffRecordsPage, { type StaffRecordsState } from '@/features/staff/presentation/StaffRecordsPage.vue'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'
import { DAY_MS, toBeijingDate } from '@/shared/time/beijing-time'

// 核销记录 (board StaffRecords): one day of the operator's own records, today unless the URL or the
// date picker names another day (at most 90 days back).
const HISTORY_DAYS = 90

const state = ref<StaffRecordsState>({ kind: 'loading' })
const day = ref<string | null>(null)
const earliestDay = toBeijingDate(new Date(Date.now() - HISTORY_DAYS * DAY_MS))
let leaving = false

const accessToken = () => getBookingAuthContext().getAccessToken()

function openLogin(): void {
  if (leaving) return
  leaving = true
  uni.redirectTo({ url: buildInspectionLoginUrl(buildStaffRecordsUrl(day.value)) })
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
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
    const [workbench, records, account] = await Promise.all([
      checkinApi.workbench(token, day.value),
      checkinApi.records(token, day.value),
      inspectionAccountApi.loadCurrent(token).catch(() => null),
    ])
    if (accessToken() !== token) return
    if (!workbench.kinds.length) {
      state.value = { kind: 'forbidden' }
      return
    }
    state.value = {
      kind: 'ready',
      content: {
        day: records.date || workbench.date,
        today: toBeijingDate(new Date()),
        operatorName: account?.displayName || '本人',
        sessions: workbench.sessions,
        records: records.items,
      },
    }
  } catch (error) {
    const failure = classifyStaffFailure(error)
    if (failure.kind === 'login') openLogin()
    else state.value = failure.kind === 'forbidden' ? { kind: 'forbidden' } : { kind: 'error', message: failure.message }
  }
}

function pickDay(next: string): void {
  day.value = next
  state.value = { kind: 'loading' }
  void load()
}

function openException(record: CheckinRecord): void {
  uni.navigateTo({ url: buildStaffExceptionUrl({ kind: record.kind, recordId: record.recordId }) })
}

onLoad((query) => {
  day.value = parseStaffRecordsQuery(query as Record<string, unknown> | undefined).date
})

onShow(() => {
  if (!STAFF_WORK_MODE_ENABLED) return
  leaving = false
  void load()
})
</script>

<template>
  <StaffRecordsPage
    v-if="STAFF_WORK_MODE_ENABLED"
    :state="state"
    :earliest-day="earliestDay"
    @back="goBack"
    @retry="load"
    @pick-day="pickDay"
    @open-exception="openException"
  />
  <SupportUnavailable v-else text="工作模式尚未开通" />
</template>
