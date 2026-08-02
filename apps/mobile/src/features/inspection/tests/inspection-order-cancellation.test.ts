import { afterEach, describe, expect, it, vi } from 'vitest'

import cancellationUiSource from '../presentation/shared/inspection-cancellation-ui.ts?raw'
import { confirmInspectionOrderCancellation } from '../presentation/shared/inspection-cancellation-ui'
import {
  createInspectionBookingApi,
  type BookingHttpClient,
  type BookingReservation,
} from '../data/service-flow/inspection-booking-api'
import { ApiRequestError } from '../data/service-flow/uni-booking-http-client'
import {
  canCancelInspectionOrder,
  requestInspectionOrderCancellation,
} from '../application/service-flow/inspection-order-cancellation'

function reservation(status: BookingReservation['status']): BookingReservation {
  return {
    id: '40000000-0000-4000-8000-000000000001',
    reservationNo: 'IR202608020001',
    customerId: '10000000-0000-4000-8000-000000000001',
    providerId: '30000000-0000-4000-8000-000000000001',
    serviceId: '33333333-3333-3333-3333-333333333333',
    serviceTitle: '电力输电线路无人机巡检',
    serviceCoverUrl: null,
    cityCode: '370500',
    status,
    location: { address: '东营市测试路', longitude: 118.5, latitude: 37.4 },
    lineLengthMeters: 1200,
    preferredServiceDate: '2026-08-03',
    preferredTimeSlotCode: 'MORNING',
    inspectionItems: [{ code: 'PIPELINE', label: '线路本体巡检' }],
    note: null,
    contact: { name: '张三', mobile: '13800138000' },
    attachments: [],
    submittedAt: '2026-08-02T02:00:00Z',
    currentPlanId: status === 'PLAN_PUBLISHED' ? '50000000-0000-4000-8000-000000000001' : null,
    version: status === 'PLAN_PUBLISHED' ? 1 : 0,
  }
}

describe('inspection order cancellation', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts cancellation with auth, idempotency and a strong reservation ETag', async () => {
    const calls: unknown[] = []
    const serverReservation = {
      ...reservation('CANCELLED'),
      reservationNo: 'IR202608020007',
      serviceTitle: '服务端确认的线路巡检',
      note: '仅由服务端返回的取消说明',
      submittedAt: '2026-08-02T03:45:00Z',
      version: 7,
    }
    const http: BookingHttpClient = {
      request: async <T,>(input: {
        url: string
        method: 'GET' | 'POST'
        data?: unknown
        headers?: Record<string, string>
      }) => {
        calls.push(input)
        return serverReservation as T
      },
      putFile: async () => ({ etag: 'unused' }),
    }
    const api = createInspectionBookingApi(http, { getAccessToken: () => 'customer-token' })

    const latestReservation = await api.cancelReservation(
      reservation('SUBMITTED').id,
      0,
      '70000000-0000-4000-8000-000000000001',
    )

    expect(latestReservation).toBe(serverReservation)

    expect(calls).toEqual([{
      url: '/api/v1/inspection/reservations/40000000-0000-4000-8000-000000000001/cancel',
      method: 'POST',
      headers: {
        Authorization: 'Bearer customer-token',
        'Idempotency-Key': '70000000-0000-4000-8000-000000000001',
        'If-Match': '"0"',
      },
    }])
  })

  it('allows only submitted or plan-published orders and respects dismissal', async () => {
    expect(canCancelInspectionOrder('SUBMITTED')).toBe(true)
    expect(canCancelInspectionOrder('PLAN_PUBLISHED')).toBe(true)
    expect(canCancelInspectionOrder('CANCELLED')).toBe(false)
    const cancelReservation = vi.fn()

    await expect(requestInspectionOrderCancellation({
      reservation: reservation('SUBMITTED'),
      cancelReservation,
      confirm: async () => false,
      idempotencyKey: () => 'unused',
    })).resolves.toEqual({ kind: 'dismissed' })

    expect(cancelReservation).not.toHaveBeenCalled()
  })

  it('does not confirm, generate a key or request cancellation for a cancelled order', async () => {
    const confirm = vi.fn(async () => true)
    const cancelReservation = vi.fn(async (): Promise<BookingReservation> => reservation('CANCELLED'))
    const idempotencyKey = vi.fn(() => '70000000-0000-4000-8000-000000000001')

    await expect(requestInspectionOrderCancellation({
      reservation: reservation('CANCELLED'),
      confirm,
      cancelReservation,
      idempotencyKey,
    })).resolves.toEqual({ kind: 'refresh-required' })

    expect(confirm).not.toHaveBeenCalled()
    expect(cancelReservation).not.toHaveBeenCalled()
    expect(idempotencyKey).not.toHaveBeenCalled()
  })

  it('returns the exact latest order and classifies status, idempotency and version conflicts', async () => {
    const submitted = reservation('SUBMITTED')
    const serverReservation = {
      ...submitted,
      status: 'CANCELLED' as const,
      reservationNo: 'IR202608020007',
      serviceTitle: '服务端确认的线路巡检',
      note: '仅由服务端返回的取消说明',
      submittedAt: '2026-08-02T03:45:00Z',
      version: 7,
    }
    const base = {
      reservation: submitted,
      confirm: async () => true,
      idempotencyKey: () => '70000000-0000-4000-8000-000000000001',
    }

    const success = await requestInspectionOrderCancellation({
      ...base,
      cancelReservation: async () => serverReservation,
    })

    expect(success).toEqual({ kind: 'cancelled', reservation: serverReservation })
    if (success.kind !== 'cancelled') throw new Error('expected cancellation success')
    expect(success.reservation).toBe(serverReservation)

    for (const error of [
      new ApiRequestError('当前订单不可取消', 409, 'INS_RESERVATION_NOT_CANCELLABLE'),
      new ApiRequestError('订单版本已变化', 412, 'INS_VERSION_CONFLICT'),
    ]) {
      await expect(requestInspectionOrderCancellation({
        ...base,
        cancelReservation: async () => { throw error },
      })).resolves.toEqual({ kind: 'refresh-required' })
    }

    await expect(requestInspectionOrderCancellation({
      ...base,
      cancelReservation: async () => {
        throw new ApiRequestError('取消请求冲突', 409, 'INS_IDEMPOTENCY_KEY_REUSED')
      },
    })).resolves.toEqual({
      kind: 'failed',
      message: '取消请求冲突，请刷新订单状态后重试',
    })

    await expect(requestInspectionOrderCancellation({
      ...base,
      cancelReservation: async () => { throw new Error('network down') },
    })).resolves.toEqual({
      kind: 'failed',
      message: '取消失败，请检查网络后重试',
    })
  })

  it('uses one cancellation-only confirmation copy without payment or refund claims', () => {
    expect(cancellationUiSource).toContain("title: '确认取消订单？'")
    expect(cancellationUiSource).toContain("content: '取消后将停止安排本次服务。'")
    expect(cancellationUiSource).not.toContain('支付')
    expect(cancellationUiSource).not.toContain('付款')
    expect(cancellationUiSource).not.toContain('退款')
  })

  it('resolves the confirmation modal for confirm, cancel and failure', async () => {
    vi.stubGlobal('uni', {
      showModal: (options: { success: (result: { confirm: boolean }) => void }) => {
        options.success({ confirm: true })
      },
    })
    await expect(confirmInspectionOrderCancellation()).resolves.toBe(true)

    vi.stubGlobal('uni', {
      showModal: (options: { success: (result: { confirm: boolean }) => void }) => {
        options.success({ confirm: false })
      },
    })
    await expect(confirmInspectionOrderCancellation()).resolves.toBe(false)

    vi.stubGlobal('uni', {
      showModal: (options: { fail: () => void }) => {
        options.fail()
      },
    })
    await expect(confirmInspectionOrderCancellation()).resolves.toBe(false)
  })
})
