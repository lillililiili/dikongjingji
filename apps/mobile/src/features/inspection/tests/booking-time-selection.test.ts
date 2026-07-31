import { describe, expect, it } from 'vitest'

import {
  createEmptyBookingDraft,
  createReservationPayload,
  validateBookingDraft,
  type BookingServiceSnapshot,
} from '../domain/service-flow/booking'
import {
  createInspectionBookingApi,
  type BookingHttpClient,
} from '../data/service-flow/inspection-booking-api'
import bookingPageSource from '../presentation/booking/pages/InspectionBookingPage.vue?raw'
import sheetSource from '../presentation/booking/components/InspectionTimePickerSheet.vue?raw'

const SERVICE_ID = '40000000-0000-4000-8000-000000000001'

describe('booking time selection', () => {
  const service = {
    serviceId: SERVICE_ID,
    cityCode: '370500',
    categoryName: '电力',
    title: '线路巡检',
    summary: '测试',
    coverImageUrl: null,
    inspectionItems: [{ code: 'TOWER', label: '杆塔' }],
    priceMode: 'PROJECT_QUOTE',
    priceAmountMinor: null,
    priceUnitLabel: null,
    priceLabel: '按项目报价',
  } satisfies BookingServiceSnapshot

  it('requires one confirmed date and slot', () => {
    const draft = createEmptyBookingDraft()

    expect(draft.preferredServiceTime).toBeNull()
    expect(validateBookingDraft(draft)).toContain('请选择期望巡检时间')
  })

  it('submits stable date and slot values without display copy', () => {
    const payload = createReservationPayload(service, {
      ...createEmptyBookingDraft(),
      locationAddress: '东营市测试路1号',
      locationLongitude: 118.5,
      locationLatitude: 37.4,
      lineLengthKm: '1',
      preferredServiceTime: {
        serviceDate: '2026-08-01',
        slotCode: 'MORNING',
        startTime: '09:00',
        endTime: '12:00',
      },
      inspectionItemCodes: ['TOWER'],
      contactName: '张三',
      contactMobile: '13800138000',
      agreementAccepted: true,
    })

    expect(payload).toMatchObject({
      preferredServiceDate: '2026-08-01',
      preferredTimeSlotCode: 'MORNING',
    })
    expect(payload).not.toHaveProperty('timeLabel')
    expect(payload).not.toHaveProperty('startTime')
    expect(payload).not.toHaveProperty('endTime')
  })

  it('requests and maps the authenticated availability response', async () => {
    const calls: Array<{ url: string; headers?: Record<string, string> }> = []
    const http: BookingHttpClient = {
      request: async <T,>(input: {
        url: string
        method: 'GET' | 'POST'
        data?: unknown
        headers?: Record<string, string>
      }) => {
        calls.push(input)
        return [{
          serviceDate: '2026-08-01',
          slots: [{
            slotCode: 'AFTERNOON',
            startTime: '14:00',
            endTime: '17:00',
            remainingCount: 3,
            availability: 'AVAILABLE',
          }, {
            slotCode: 'EVENING',
            startTime: '18:00',
            endTime: '21:00',
            remainingCount: 0,
            availability: 'FULL',
          }],
        }] as T
      },
      putFile: async () => ({ etag: 'etag' }),
    }
    const api = createInspectionBookingApi(http, {
      getAccessToken: () => 'token',
    })

    await expect(api.loadAvailableTimeSlots(
      SERVICE_ID,
      '370500',
      '2026-08-01',
      4,
    )).resolves.toEqual([{
      serviceDate: '2026-08-01',
      slots: [{
        slotCode: 'AFTERNOON',
        startTime: '14:00',
        endTime: '17:00',
        remainingCount: 3,
        availability: 'AVAILABLE',
      }, {
        slotCode: 'EVENING',
        startTime: '18:00',
        endTime: '21:00',
        remainingCount: 0,
        availability: 'FULL',
      }],
    }])
    expect(calls).toEqual([{
      url: `/api/v1/inspection/services/${SERVICE_ID}/available-time-slots?cityCode=370500&startDate=2026-08-01&days=4`,
      method: 'GET',
      headers: { Authorization: 'Bearer token' },
    }])
  })

  it('renders the approved time sheet and replaces the native date picker', () => {
    expect(sheetSource).toContain('选择服务时间')
    expect(sheetSource).toContain('选择日期')
    expect(sheetSource).toContain('选择时段')
    expect(sheetSource).toContain('预计服务时长3小时')
    expect(sheetSource).toContain('更多日期')
    expect(sheetSource).toContain('已约满')
    expect(sheetSource).toContain('formatClockTime(slot.startTime)')
    expect(sheetSource).toContain('formatClockTime(slot.endTime)')
    expect(sheetSource).toContain('<view class="inspection-time-sheet__handle" />')
    expect(sheetSource).toContain('.inspection-time-sheet__slot {\n  min-width: 0;\n  min-height: 44px;')
    expect(sheetSource).toContain('.inspection-time-sheet__date--more {\n  width: 68px;\n  min-width: 68px;')
    expect(sheetSource).toContain('min-height: 52px;\n  margin: 0;\n  padding: 5px 4px;')
    expect(sheetSource).toContain('env(safe-area-inset-bottom)')
    expect(sheetSource).toContain('UniCalendar')
    expect(bookingPageSource).toContain('<InspectionTimePickerSheet')
    expect(bookingPageSource).toContain('openTimePicker')
    expect(bookingPageSource).not.toContain('<picker mode="date"')
    expect(bookingPageSource).not.toContain('draft.preferredServiceDate')
  })

  it('refreshes Shanghai date bounds on every open and exposes a 44px time trigger', () => {
    expect(sheetSource).toContain('const today = ref(shanghaiToday())')
    expect(sheetSource).toContain('async function openSheet(): Promise<void> {\n  today.value = shanghaiToday()')
    expect(bookingPageSource).toContain('booking-row--time')
    expect(bookingPageSource).toContain('.booking-row--time{min-height:44px}')
  })
})
