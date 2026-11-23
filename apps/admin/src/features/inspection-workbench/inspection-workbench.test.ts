import { describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'

import {
  createInspectionWorkbenchApi,
  type ServiceOrder,
  type ServiceVisit,
} from './inspection-workbench-api'
import {
  arrangementProblem,
  beijingToday,
  certifiedPilotCount,
  canArrange,
  canPublishPlan,
  deliveryMeasuresError,
  fileMatchesType,
  planTotalMinor,
  visitActions,
  visitDatesError,
} from './inspection-rules'

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}

function apiWith(fetchImpl: typeof fetch, storageFetch?: typeof fetch) {
  const http = createApiClient({ getToken: () => 'jwt', onUnauthorized: vi.fn(), fetchImpl, newRequestId: () => 'req' })
  return createInspectionWorkbenchApi(http, { fetchImpl: storageFetch, hash: async () => 'a'.repeat(64) })
}

function visit(overrides: Partial<ServiceVisit> = {}): ServiceVisit {
  return {
    id: 'v1', serviceOrderId: 'o1', visitNo: 1, plannedDate: '2026-12-01', status: 'PENDING_SCHEDULE',
    progress: [], version: 0, ...overrides,
  }
}

const TEAM = { type: 'TEAM_MEMBER' as const, resourceId: 'r1', displayName: '班组', roleLabel: null, contactMobileMasked: null }
const DEVICE = { type: 'DEVICE' as const, resourceId: 'r2', displayName: 'M350', roleLabel: null, contactMobileMasked: null }

function event(code: string) {
  return { id: code, eventCode: code, occurredAt: '2026-12-01T01:00:00Z', message: 'x' }
}

describe('接口拼法', () => {
  it('列表按阶段和页码查本机构的预约', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () => json(200, { items: [], page: 1, size: 20, total: 0, hasMore: false, stageCounts: {} }))
    await apiWith(fetchImpl).list('AWAITING_PLAN', 2, 20)
    expect(fetchImpl.mock.calls[0]![0]).toBe('/api/v1/provider/inspection/reservations?stage=AWAITING_PLAN&page=2&size=20')
  })

  it('单条摘要读本机构接口（契约 2.3.0），不再翻列表', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () => json(200, { reservationId: 'b' }))
    expect(await apiWith(fetchImpl).summary('b/1')).toEqual({ reservationId: 'b' })
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(fetchImpl.mock.calls[0]![0]).toBe('/api/v1/provider/inspection/reservations/b%2F1')
  })

  it('出方案带预约版本 If-Match 和幂等键；排期、进度带这一次的版本', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () => json(200, {}))
    const api = apiWith(fetchImpl)
    await api.publishPlan({ id: 'r1', version: 2 }, { providerId: 'p' } as never, 'k1')
    await api.schedule({ id: 'v1', serviceOrderId: 'o1', version: 5 }, '2026-12-01T01:00:00.000Z', 'k2')
    await api.progress({ id: 'v1', serviceOrderId: 'o1', version: 6 }, 'SERVICE_STARTED', ' 到场 ', '2026-12-01T01:05:00.000Z', 'k3')
    await api.arrange({ id: 'o1', version: 3 }, [], 'k4')

    const [planUrl, plan] = fetchImpl.mock.calls[0]!
    expect(planUrl).toBe('/api/v1/provider/inspection/reservations/r1/plans')
    expect(plan!.method).toBe('POST')
    expect(plan!.headers).toMatchObject({ 'If-Match': '"2"', 'Idempotency-Key': 'k1' })
    const [scheduleUrl, schedule] = fetchImpl.mock.calls[1]!
    expect(scheduleUrl).toBe('/api/v1/provider/inspection/service-orders/o1/visits/v1/schedule')
    expect(schedule!.method).toBe('PUT')
    expect(schedule!.headers).toMatchObject({ 'If-Match': '"5"' })
    const [progressUrl, progress] = fetchImpl.mock.calls[2]!
    expect(progressUrl).toBe('/api/v1/provider/inspection/service-orders/o1/visits/v1/progress-events')
    expect(JSON.parse(progress!.body as string)).toEqual({
      eventCode: 'SERVICE_STARTED', occurredAt: '2026-12-01T01:05:00.000Z', message: '到场',
    })
    expect(progress!.headers).toMatchObject({ 'If-Match': '"6"', 'Idempotency-Key': 'k3' })
    expect(fetchImpl.mock.calls[3]![0]).toBe('/api/v1/provider/inspection/service-orders/o1/arrangement')
    expect(fetchImpl.mock.calls[3]![1]!.headers).toMatchObject({ 'If-Match': '"3"' })
  })

  it('上传交付文件：建意图 → 直传存储（不带登录令牌）→ 完成；ETag 读不到时用文件 ID 占位', async () => {
    const fetchImpl = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json(201, {
        fileId: 'f1', method: 'PUT', uploadUrl: 'https://minio.test/q/obj?sig=1',
        requiredHeaders: { 'Content-Type': 'application/pdf' }, expiresAt: '2026-12-01T00:00:00Z',
      }))
      .mockResolvedValueOnce(json(200, { id: 'f1', status: 'READY' }))
    const storage = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 200 }))
    const file = new Blob(['%PDF-1.4'], { type: 'application/pdf' })

    const id = await apiWith(fetchImpl, storage).uploadDeliveryFile('v1', {
      type: 'INSPECTION_REPORT', file, originalName: '报告.pdf', idempotencyKey: 'ik', completeKey: 'ck',
    })

    expect(id).toBe('f1')
    const [intentUrl, intent] = fetchImpl.mock.calls[0]!
    expect(intentUrl).toBe('/api/v1/inspection/file-objects/upload-intents')
    expect(JSON.parse(intent!.body as string)).toEqual({
      purpose: 'DELIVERY_ARTIFACT', ownerId: 'v1', deliveryFileType: 'INSPECTION_REPORT', originalName: '报告.pdf',
      contentType: 'application/pdf', sizeBytes: file.size, sha256: 'a'.repeat(64),
    })
    expect(intent!.headers).toMatchObject({ 'Idempotency-Key': 'ik' })
    const [putUrl, put] = storage.mock.calls[0]!
    expect(putUrl).toBe('https://minio.test/q/obj?sig=1')
    expect(put).toMatchObject({ method: 'PUT', headers: { 'Content-Type': 'application/pdf' }, body: file })
    expect(put!.headers).not.toHaveProperty('Authorization')
    const [completeUrl, complete] = fetchImpl.mock.calls[1]!
    expect(completeUrl).toBe('/api/v1/inspection/file-objects/f1/complete')
    expect(JSON.parse(complete!.body as string)).toEqual({ etag: 'admin-upload-f1', sizeBytes: file.size, sha256: 'a'.repeat(64) })
    expect(complete!.headers).toMatchObject({ 'Idempotency-Key': 'ck' })
  })

  it('存储返回 ETag 时用它；后端校验不过时报错', async () => {
    const fetchImpl = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json(201, { fileId: 'f2', method: 'PUT', uploadUrl: 'https://s/x', requiredHeaders: {}, expiresAt: '' }))
      .mockResolvedValueOnce(json(200, { id: 'f2', status: 'REJECTED', scanFailureReason: '文件内容、大小或类型校验失败' }))
    const storage = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 200, headers: { ETag: '"abc"' } }))
    await expect(apiWith(fetchImpl, storage).uploadDeliveryFile('v1', {
      type: 'POINT_KML', file: new Blob(['<kml/>']), originalName: 'a.kml', idempotencyKey: 'i', completeKey: 'c',
    })).rejects.toThrow('没通过校验')
    expect(JSON.parse(fetchImpl.mock.calls[1]![1]!.body as string).etag).toBe('"abc"')
  })

  it('巡检的业务错误码有中文说明', () => {
    expect(errorMessage(new ApiRequestError(409, 'INS_VISIT_OUT_OF_ORDER', '', 't'))).toBe('上一次巡检还没完成，不能开始这一次')
  })
})

describe('方案', () => {
  it('合计是每行数量 × 单价（分，不经过浮点数）；有一行单价不对就算不出', () => {
    expect(planTotalMinor([
      { code: 'a', name: '杆塔', quantity: 3, unit: '基', unitPrice: '19.99' },
      { code: 'b', name: '线路', quantity: 2, unit: '公里', unitPrice: '1200' },
    ])).toBe(3 * 1999 + 2 * 120000)
    expect(planTotalMinor([{ code: 'a', name: 'x', quantity: 1, unit: '项', unitPrice: '1.234' }])).toBeNull()
  })

  it('巡检日期 1–52 个、严格升序、不早于今天', () => {
    expect(visitDatesError([], '2026-11-23')).toBe('至少安排一次巡检')
    expect(visitDatesError(['2026-11-22'], '2026-11-23')).toContain('早于今天')
    expect(visitDatesError(['2026-11-23', '2026-11-23'], '2026-11-23')).toContain('严格往后排')
    expect(visitDatesError(['2026-11-23', '2026-11-30'], '2026-11-23')).toBeNull()
    expect(visitDatesError(Array.from({ length: 53 }, (_, i) => `2027-01-${String(i + 1).padStart(2, '0')}`), '2026-11-23'))
      .toBe('最多安排 52 次巡检')
    expect(beijingToday(new Date('2026-11-22T17:00:00Z'))).toBe('2026-11-23')
  })

  it('只有没取消、客户没确认时能出方案', () => {
    expect(canPublishPlan('AWAITING_PLAN', 'SUBMITTED')).toBe(true)
    expect(canPublishPlan('AWAITING_CONFIRMATION', 'PLAN_PUBLISHED')).toBe(true)
    expect(canPublishPlan('IN_PROGRESS', 'PLAN_PUBLISHED')).toBe(false)
    expect(canPublishPlan('AWAITING_PLAN', 'CANCELLED')).toBe(false)
    expect(canPublishPlan(null, 'SUBMITTED')).toBe(true)
  })
})

describe('履约动作', () => {
  const order = (visits: ServiceVisit[], assignments = [TEAM, DEVICE]): Pick<ServiceOrder, 'assignments' | 'visits'> =>
    ({ assignments, visits })

  it('待排期只能排期；已排期能改排期，有人员和设备、上一次现场完成后能开始', () => {
    const first = visit()
    expect(visitActions(order([first]), first).map((s) => [s.action, s.enabled]))
      .toEqual([['SCHEDULE', true], ['SERVICE_STARTED', false]])

    const scheduled = visit({ status: 'SCHEDULED' })
    expect(visitActions(order([scheduled]), scheduled).map((s) => s.enabled)).toEqual([true, true])
    const noDevice = visitActions(order([scheduled], [TEAM]), scheduled)[1]!
    expect(noDevice).toMatchObject({ enabled: false, reason: '先安排人员和设备（各至少一项）' })

    const earlier = visit({ id: 'v1', visitNo: 1, status: 'IN_PROGRESS', progress: [event('SERVICE_STARTED')] })
    const second = visit({ id: 'v2', visitNo: 2, status: 'SCHEDULED' })
    expect(visitActions(order([earlier, second]), second)[1]).toMatchObject({ enabled: false, reason: '第 1 次巡检现场还没完成' })
    const finished = { ...earlier, progress: [event('SERVICE_STARTED'), event('SERVICE_COMPLETED')] }
    expect(visitActions(order([finished, second]), second)[1]!.enabled).toBe(true)
  })

  it('巡检中：现场没完成时记进度或完成；完成后才能发布交付；已交付、已验收没有动作', () => {
    const running = visit({ status: 'IN_PROGRESS', progress: [event('SERVICE_STARTED')] })
    expect(visitActions(order([running]), running).map((s) => [s.action, s.enabled]))
      .toEqual([['SERVICE_PROGRESS', true], ['SERVICE_COMPLETED', true], ['DELIVER', false]])
    const done = visit({ status: 'IN_PROGRESS', progress: [event('SERVICE_STARTED'), event('SERVICE_COMPLETED')] })
    expect(visitActions(order([done]), done).map((s) => s.action)).toEqual(['DELIVER'])
    expect(visitActions(order([]), visit({ status: 'DELIVERED' }))).toEqual([])
    expect(visitActions(order([]), visit({ status: 'ACCEPTED' }))).toEqual([])
  })

  it('全部验收后不能改安排；安排至少一名人员和一台设备、手机号选填', () => {
    expect(canArrange({ status: 'IN_PROGRESS' })).toBe(true)
    expect(canArrange({ status: 'ACCEPTED' })).toBe(false)
    const team = { type: 'TEAM_MEMBER' as const, displayName: '班组', roleLabel: '', contactMobile: '', certifiedPilot: false }
    const device = { type: 'DEVICE' as const, displayName: 'M350', roleLabel: '', contactMobile: '', certifiedPilot: false }
    expect(arrangementProblem([team])).toBe('至少安排一台设备')
    expect(arrangementProblem([team, device])).toBeNull()
    expect(arrangementProblem([{ ...team, contactMobile: '12345' }, device])).toBe('第 1 项的手机号格式不对')
    // 2.5.0：持证飞手只能勾在人员上；履约页的人数只数勾了的人员。
    expect(arrangementProblem([team, { ...device, certifiedPilot: true }])).toBe('第 2 项是设备，不能标持证飞手')
    expect(certifiedPilotCount([{ ...team, certifiedPilot: true }, team, { ...device, certifiedPilot: true }])).toBe(1)
  })

  it('交付：文件扩展名对应类型；指标、塔数、长度至少一项', () => {
    expect(fileMatchesType('报告.PDF', 'pdf')).toBe(true)
    expect(fileMatchesType('报告.docx', 'pdf')).toBe(false)
    expect(deliveryMeasuresError({ towerCount: null, inspectedLengthMeters: null, metricCount: 0 })).not.toBeNull()
    expect(deliveryMeasuresError({ towerCount: 0, inspectedLengthMeters: null, metricCount: 0 })).toBeNull()
  })
})
