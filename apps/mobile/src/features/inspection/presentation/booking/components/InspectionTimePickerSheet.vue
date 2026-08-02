<script setup lang="ts">
import { computed, ref, watch } from 'vue'
// @ts-expect-error @dcloudio/uni-ui does not publish a declaration for UniCalendar.
import UniCalendar from '@dcloudio/uni-ui/lib/uni-calendar/uni-calendar.vue'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import type {
  BookingAvailableDate,
  BookingAvailableTimeSlot,
} from '../../../data/service-flow/inspection-booking-api'
import {
  isBookingTimeSelectionWithinDateRange,
  type BookingTimeSelection,
} from '../../../domain/service-flow/booking'

type LoadSlots = (
  serviceId: string,
  cityCode: string,
  startDate: string,
  days: number,
) => Promise<BookingAvailableDate[]>

interface CalendarConfirmEvent {
  fulldate?: string
}

const props = defineProps<{
  visible: boolean
  serviceId: string
  cityCode: string
  modelValue: BookingTimeSelection | null
  loadSlots: LoadSlots
}>()

const emit = defineEmits<{
  close: []
  confirm: [
    selection: BookingTimeSelection,
    displayText: string,
  ]
}>()

const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000
const WEEKDAY_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const calendarRef = ref<{ open(): void } | null>(null)
const availableDates = ref<BookingAvailableDate[]>([])
const selectedDate = ref('')
const temporarySelection = ref<BookingTimeSelection | null>(null)
const isLoading = ref(false)
const loadError = ref('')
const selectionError = ref('')
const requestId = ref(0)
const lastStartDate = ref('')
const lastDays = ref(4)

const today = ref(shanghaiToday())
const lastSelectableDate = computed(() => addCalendarDays(today.value, 30))
const currentSlots = computed(
  () => availableDates.value.find(
    (date) => date.serviceDate === selectedDate.value,
  )?.slots ?? [],
)
const summaryText = computed(
  () => temporarySelection.value
    ? `已选择：${formatMonthDay(temporarySelection.value.serviceDate)} ${formatClockTime(temporarySelection.value.startTime)}–${formatClockTime(temporarySelection.value.endTime)}`
    : '请选择日期和时段',
)

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      void openSheet()
      return
    }
    requestId.value += 1
  },
  { immediate: true },
)

watch(
  [() => props.serviceId, () => props.cityCode],
  () => {
    if (props.visible) {
      void openSheet()
    }
  },
)

async function openSheet(): Promise<void> {
  today.value = shanghaiToday()
  const modelValueIsSelectable = isBookingTimeSelectionWithinDateRange(
    props.modelValue,
    today.value,
    lastSelectableDate.value,
  )
  temporarySelection.value = modelValueIsSelectable
    ? { ...props.modelValue }
    : null
  selectedDate.value = temporarySelection.value?.serviceDate ?? today.value
  selectionError.value = props.modelValue && !modelValueIsSelectable
    ? '日期已更新，请重新选择服务时间'
    : ''

  const loaded = await loadAvailability(today.value, 4, selectedDate.value)
  if (
    loaded
    && props.visible
    && temporarySelection.value
    && !availableDates.value.some(
      (date) => date.serviceDate === temporarySelection.value?.serviceDate,
    )
  ) {
    await loadAvailability(
      temporarySelection.value.serviceDate,
      1,
      temporarySelection.value.serviceDate,
    )
  }
}

async function loadAvailability(
  startDate: string,
  days: number,
  dateToSelect: string,
): Promise<boolean> {
  const currentRequestId = requestId.value + 1
  requestId.value = currentRequestId
  lastStartDate.value = startDate
  lastDays.value = days
  isLoading.value = true
  loadError.value = ''

  try {
    const dates = await props.loadSlots(
      props.serviceId,
      props.cityCode,
      startDate,
      days,
    )
    if (currentRequestId !== requestId.value || !props.visible) {
      return false
    }

    availableDates.value = dates
    selectedDate.value = dates.some(
      (date) => date.serviceDate === dateToSelect,
    )
      ? dateToSelect
      : dates[0]?.serviceDate ?? dateToSelect
    reconcileTemporarySelection(dates)
    if (dates.length === 0) {
      loadError.value = '时间加载失败'
    }
    return true
  } catch {
    if (currentRequestId !== requestId.value || !props.visible) {
      return false
    }
    loadError.value = '时间加载失败'
    return false
  } finally {
    if (currentRequestId === requestId.value) {
      isLoading.value = false
    }
  }
}

function reconcileTemporarySelection(dates: BookingAvailableDate[]): void {
  const selection = temporarySelection.value
  if (!selection) {
    return
  }

  const refreshedSlot = dates
    .find((date) => date.serviceDate === selection.serviceDate)
    ?.slots.find((slot) => slot.slotCode === selection.slotCode)
  if (refreshedSlot && refreshedSlot.availability !== 'AVAILABLE') {
    temporarySelection.value = null
    selectionError.value = slotSelectionError(refreshedSlot)
  }
}

function slotUnavailableLabel(slot: BookingAvailableTimeSlot): string {
  switch (slot.availability) {
    case 'FULL':
      return '已约满'
    case 'TOO_SOON':
      return '需提前1小时预约'
    default:
      return ''
  }
}

function slotSelectionError(slot: BookingAvailableTimeSlot): string {
  switch (slot.availability) {
    case 'FULL':
      return '该时段已约满，请重新选择'
    case 'TOO_SOON':
      return '该时段需提前1小时预约，请重新选择'
    default:
      return ''
  }
}

function selectDate(serviceDate: string): void {
  if (isLoading.value) {
    return
  }
  selectedDate.value = serviceDate
  loadError.value = ''
  selectionError.value = ''
  if (temporarySelection.value?.serviceDate !== serviceDate) {
    temporarySelection.value = null
  }
}

function selectSlot(slot: BookingAvailableTimeSlot): void {
  if (isLoading.value || slot.availability !== 'AVAILABLE') {
    return
  }
  temporarySelection.value = {
    serviceDate: selectedDate.value,
    slotCode: slot.slotCode,
    startTime: slot.startTime,
    endTime: slot.endTime,
  }
  selectionError.value = ''
}

function openCalendar(): void {
  if (!isLoading.value) {
    calendarRef.value?.open()
  }
}

function confirmCalendarDate(event: CalendarConfirmEvent): void {
  const serviceDate = event.fulldate
  if (
    !serviceDate
    || serviceDate < today.value
    || serviceDate > lastSelectableDate.value
  ) {
    return
  }

  const existingDate = availableDates.value.find(
    (date) => date.serviceDate === serviceDate,
  )
  if (existingDate) {
    selectDate(serviceDate)
    return
  }

  temporarySelection.value = null
  selectionError.value = ''
  void loadAvailability(serviceDate, 1, serviceDate)
}

async function confirmSelection(): Promise<void> {
  const selection = temporarySelection.value
  if (!selection || isLoading.value) {
    return
  }

  const loaded = await loadAvailability(
    selection.serviceDate,
    1,
    selection.serviceDate,
  )
  if (!loaded || !temporarySelection.value) {
    return
  }

  const refreshedSlot = currentSlots.value.find(
    (slot) => slot.slotCode === selection.slotCode,
  )
  if (!refreshedSlot || refreshedSlot.availability !== 'AVAILABLE') {
    temporarySelection.value = null
    selectionError.value = refreshedSlot
      ? slotSelectionError(refreshedSlot)
      : '该时段已不可用，请重新选择'
    return
  }

  const refreshedSelection: BookingTimeSelection = {
    serviceDate: selection.serviceDate,
    slotCode: refreshedSlot.slotCode,
    startTime: refreshedSlot.startTime,
    endTime: refreshedSlot.endTime,
  }
  emit(
    'confirm',
    refreshedSelection,
    `${formatMonthDay(refreshedSelection.serviceDate)} ${formatClockTime(refreshedSelection.startTime)}–${formatClockTime(refreshedSelection.endTime)}`,
  )
}

function retryLoad(): void {
  void loadAvailability(
    lastStartDate.value || today.value,
    lastDays.value,
    selectedDate.value || today.value,
  )
}

function closeSheet(): void {
  emit('close')
}

function isSelectedSlot(slot: BookingAvailableTimeSlot): boolean {
  return temporarySelection.value?.serviceDate === selectedDate.value
    && temporarySelection.value.slotCode === slot.slotCode
}

function shanghaiToday(): string {
  return new Date(Date.now() + SHANGHAI_OFFSET_MS)
    .toISOString()
    .slice(0, 10)
}

function addCalendarDays(value: string, days: number): string {
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

function formatMonthDay(value: string): string {
  const [, month = '', day = ''] = value.split('-')
  return `${Number(month)}月${Number(day)}日`
}

function formatClockTime(value: string): string {
  return /^\d{2}:\d{2}:\d{2}$/.test(value) ? value.slice(0, 5) : value
}

function formatDateHeading(value: string): string {
  if (value === today.value) {
    return '今天'
  }
  if (value === addCalendarDays(today.value, 1)) {
    return '明天'
  }
  const date = new Date(`${value}T00:00:00Z`)
  return WEEKDAY_LABELS[date.getUTCDay()] ?? ''
}
</script>

<template>
  <view v-if="visible" class="inspection-time-sheet">
    <view
      class="inspection-time-sheet__mask"
      @tap.self="closeSheet"
      @touchmove.stop.prevent
    />
    <view class="inspection-time-sheet__panel" @tap.stop>
      <view class="inspection-time-sheet__handle" />
      <view class="inspection-time-sheet__header">
        <text class="inspection-time-sheet__title">选择服务时间</text>
        <button
          class="inspection-time-sheet__close"
          aria-label="关闭"
          @tap="closeSheet"
        >
          <UniIcons type="closeempty" size="26" color="#202124" />
        </button>
      </view>

      <scroll-view scroll-y class="inspection-time-sheet__body">
        <text class="inspection-time-sheet__section-title">选择日期</text>
        <scroll-view
          scroll-x
          class="inspection-time-sheet__dates-scroll"
          :show-scrollbar="false"
        >
          <view class="inspection-time-sheet__dates">
            <button
              v-for="date in availableDates"
              :key="date.serviceDate"
              :class="[
                'inspection-time-sheet__date',
                {
                  'inspection-time-sheet__date--selected':
                    selectedDate === date.serviceDate,
                },
              ]"
              :disabled="isLoading"
              @tap="selectDate(date.serviceDate)"
            >
              <text class="inspection-time-sheet__date-heading">
                {{ formatDateHeading(date.serviceDate) }}
              </text>
              <text>{{ formatMonthDay(date.serviceDate) }}</text>
            </button>
            <button
              class="inspection-time-sheet__date inspection-time-sheet__date--more"
              :disabled="isLoading"
              @tap="openCalendar"
            >
              <text>更多日期</text>
              <UniIcons type="right" size="17" color="#5f6368" />
            </button>
          </view>
        </scroll-view>

        <text class="inspection-time-sheet__section-title inspection-time-sheet__section-title--slots">
          选择时段
        </text>
        <view v-if="isLoading" class="inspection-time-sheet__state">
          <text>时间加载中…</text>
        </view>
        <view v-else-if="loadError" class="inspection-time-sheet__state">
          <text>时间加载失败</text>
          <button class="inspection-time-sheet__retry" @tap="retryLoad">
            重新加载
          </button>
        </view>
        <view v-else class="inspection-time-sheet__slots">
          <button
            v-for="slot in currentSlots"
            :key="slot.slotCode"
            :class="[
              'inspection-time-sheet__slot',
              {
                'inspection-time-sheet__slot--selected':
                  isSelectedSlot(slot),
                'inspection-time-sheet__slot--full':
                  slot.availability !== 'AVAILABLE',
              },
            ]"
            :disabled="isLoading || slot.availability !== 'AVAILABLE'"
            @tap="selectSlot(slot)"
          >
            <text v-if="slot.availability !== 'AVAILABLE'">
              {{ slotUnavailableLabel(slot) }}
            </text>
            <text v-else>
              {{ formatClockTime(slot.startTime) }}–{{ formatClockTime(slot.endTime) }}
            </text>
          </button>
        </view>
        <text class="inspection-time-sheet__duration">预计服务时长3小时</text>
        <text v-if="selectionError" class="inspection-time-sheet__error">
          {{ selectionError }}
        </text>
      </scroll-view>

      <view class="inspection-time-sheet__footer">
        <text class="inspection-time-sheet__summary">{{ summaryText }}</text>
        <button
          class="inspection-time-sheet__confirm"
          :disabled="!temporarySelection || isLoading"
          @tap="confirmSelection"
        >
          确定
        </button>
      </view>

      <UniCalendar
        ref="calendarRef"
        :insert="false"
        :date="selectedDate"
        :start-date="today"
        :end-date="lastSelectableDate"
        :clear-date="false"
        @confirm="confirmCalendarDate"
      />
    </view>
  </view>
</template>

<style scoped>
.inspection-time-sheet {
  position: fixed;
  z-index: 1000;
  inset: 0;
}

.inspection-time-sheet__mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
}

.inspection-time-sheet__panel {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  width: min(100%, 600px);
  max-height: min(86vh, 720px);
  margin: 0 auto;
  overflow: hidden;
  flex-direction: column;
  border-radius: 18px 18px 0 0;
  color: #202124;
  background: #fff;
  box-sizing: border-box;
}

.inspection-time-sheet__handle {
  position: absolute;
  top: 10px;
  left: 50%;
  z-index: 1;
  width: min(8.45vw, 36px);
  height: 4px;
  border-radius: 2px;
  background: #d1d1d1;
  transform: translateX(-50%);
}

.inspection-time-sheet__header {
  position: relative;
  display: flex;
  min-height: 62px;
  padding: 18px 20px 0;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

.inspection-time-sheet__title {
  font-size: 20px;
  font-weight: 700;
}

.inspection-time-sheet__close {
  position: absolute;
  right: 10px;
  display: flex;
  width: 44px;
  height: 44px;
  margin: 0;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
}

.inspection-time-sheet__close::after,
.inspection-time-sheet__date::after,
.inspection-time-sheet__slot::after,
.inspection-time-sheet__retry::after,
.inspection-time-sheet__confirm::after {
  border: 0;
}

.inspection-time-sheet__body {
  max-height: 460px;
  padding: 2px 20px 10px;
  box-sizing: border-box;
}

.inspection-time-sheet__section-title {
  display: block;
  margin-bottom: 8px;
  font-size: 17px;
  font-weight: 600;
}

.inspection-time-sheet__section-title--slots {
  margin-top: 24px;
}

.inspection-time-sheet__dates-scroll {
  width: 100%;
  white-space: nowrap;
}

.inspection-time-sheet__dates {
  display: inline-flex;
  min-width: 100%;
  gap: 6px;
}

.inspection-time-sheet__date {
  display: inline-flex;
  width: min(16.8vw, 72px);
  min-width: min(16.8vw, 72px);
  min-height: 52px;
  margin: 0;
  padding: 5px 4px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 2px;
  border: 1px solid #dedede;
  border-radius: 9px;
  color: #202124;
  background: #fff;
  box-sizing: border-box;
  font-size: 13px;
  font-weight: 400;
  line-height: 1.2;
}

.inspection-time-sheet__date--selected {
  border-color: #ff5a00;
  color: #ff5a00;
  background: #fff4ef;
}

.inspection-time-sheet__date-heading {
  font-size: 14px;
}

.inspection-time-sheet__date--selected .inspection-time-sheet__date-heading {
  font-weight: 600;
}

.inspection-time-sheet__date--more {
  width: 68px;
  min-width: 68px;
}

.inspection-time-sheet__slots {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 14px;
  row-gap: 8px;
}

.inspection-time-sheet__slot {
  min-width: 0;
  min-height: 44px;
  margin: 0;
  padding: 8px;
  border: 1px solid #dedede;
  border-radius: 9px;
  color: #202124;
  background: #fff;
  box-sizing: border-box;
  font-size: 16px;
  font-weight: 400;
  line-height: 1.2;
}

.inspection-time-sheet__slot--selected {
  border-color: #ff5a00;
  color: #ff5a00;
  background: #fff4ef;
}

.inspection-time-sheet__slot--full {
  border-color: #e7e7e7;
  color: #a8a8a8;
  background: #f7f7f7;
}

.inspection-time-sheet__state {
  display: flex;
  min-height: 112px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 8px;
  color: #8a8f96;
  font-size: 14px;
}

.inspection-time-sheet__retry {
  min-width: 92px;
  min-height: 44px;
  margin: 0;
  padding: 0 16px;
  border: 1px solid #ff5a00;
  border-radius: 22px;
  color: #ff5a00;
  background: #fff;
  font-size: 14px;
  line-height: 42px;
}

.inspection-time-sheet__duration {
  display: block;
  margin-top: 8px;
  color: #777d84;
  font-size: 14px;
}

.inspection-time-sheet__error {
  display: block;
  margin-top: 8px;
  color: #d93025;
  font-size: 13px;
}

.inspection-time-sheet__footer {
  padding: 14px 20px calc(25px + env(safe-area-inset-bottom));
  border-top: 1px solid #eeeeee;
  background: #fff;
}

.inspection-time-sheet__summary {
  display: block;
  min-height: 24px;
  margin-bottom: 5px;
  overflow: hidden;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-time-sheet__confirm {
  width: 100%;
  min-height: 50px;
  margin: 0;
  padding: 0 16px;
  border: 0;
  border-radius: 12px;
  color: #fff;
  background: #ff5a00;
  font-size: 18px;
  font-weight: 700;
  line-height: 50px;
}

.inspection-time-sheet__confirm[disabled] {
  color: #fff;
  background: #ffb38a;
  opacity: 1;
}

.inspection-time-sheet :deep(.uni-calendar__mask),
.inspection-time-sheet :deep(.uni-calendar__content) {
  z-index: 1020;
}

</style>
