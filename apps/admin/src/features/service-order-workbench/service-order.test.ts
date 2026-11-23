import { describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'
import { buildMenu } from '@/core/navigation/module'
import { ADMIN_MODULES } from '@/modules'

import {
  createServiceOrderApi,
  type ServiceOrder,
  type ServiceOrderActions,
  type ServiceOrderStatus,
} from './service-order-api'
import {
  buildDeliveryRequest,
  buildPlanRequest,
  buildTerminateRequest,
  canEditFiles,
  cancelReasonError,
  customerLabel,
  eventLabel,
  fileProblem,
  initialPlanForm,
  isStale,
  orderButtons,
  paidMinor,
  STATUS_TABS,
  terminateMaxMinor,
} from './service-order-rules'

function json(status: number, body: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json' } })
}

function apiWith(fetchImpl: typeof fetch) {
  const http = createApiClient({ getToken: () => 'jwt', onUnauthorized: vi.fn(), fetchImpl, newRequestId: () => 'req' })
  return createServiceOrderApi(http)
}

const NO_ACTIONS: ServiceOrderActions = {
  confirmPlan: false, pay: false, cancel: false, accept: false, rework: false, issuePlan: false, start: false,
  deliver: false, terminate: false,
}

/** 服务端在各状态下给服务商的 actions（契约：服务商接口里客户的几项总是 false）。 */
const PROVIDER_ACTIONS: Record<ServiceOrderStatus, Partial<ServiceOrderActions>> = {
  AWAITING_PLAN: { issuePlan: true, cancel: true },
  PLAN_PENDING: { issuePlan: true, cancel: true },
  AWAITING_PAYMENT: { cancel: true },
  AWAITING_START: { start: true, cancel: true },
  IN_SERVICE: { deliver: true, terminate: true },
  DELIVERED: { terminate: true },
  REWORK: { deliver: true, terminate: true },
  COMPLETED: {},
  CANCELLED: {},
  TERMINATED: {},
}

function order(status: ServiceOrderStatus, overrides: Partial<ServiceOrder> = {}): ServiceOrder {
  return {
    orderId: 'o-1', orderNo: 'SV20261123000001', status, type: 'AERIAL', title: '航拍需求', demandId: 'd-1',
    customer: { kind: 'ORGANIZATION', organizationName: '示例园区', contactName: '王经理' },
    provider: { providerId: 'p-1', name: '云翼科技', qualificationReviewed: true },
    cityCode: '370500', cityName: '东营市', address: '东营区示例园区', scope: '园区宣传片', dueDate: null,
    amountMinor: 1_200_000,
    lines: [{ name: '服务费用', quantity: null, unit: null, unitAmountMinor: null, amountMinor: 1_200_000 }],
    quote: {
      quoteId: 'q-1', quoteVersion: 2, method: '多旋翼航拍', schedule: '确认后一周内', durationDays: 3, deliverables: '成片',
      team: null, nightWork: null, reportHours: null, archiveDays: null, firstVisitDate: null,
    },
    plan: null,
    payment: {
      amountMinor: 1_200_000, collectable: true, paidAt: null, pendingTradeOrderId: null, pendingPayBefore: null,
      paidTradeOrderId: null, refundedMinor: 0, refunds: [],
    },
    deliveries: [], stagedFiles: [], events: [], startedAt: null, completedAt: null, closedAt: null, closedBy: null,
    closeReason: null, actions: { ...NO_ACTIONS, ...PROVIDER_ACTIONS[status] }, createdAt: '2026-11-23T01:00:00Z',
    updatedAt: '2026-11-23T01:00:00Z', version: 4,
    ...overrides,
  }
}

const PLAN = {
  version: 1, amountMinor: 1_200_000, schedule: '11/26 上门拍摄', terms: ['3 天内交付成片'], note: null,
  issuedAt: '2026-11-23T02:00:00Z', confirmedAt: null, confirmedBy: null,
}

describe('接口拼法', () => {
  it('列表按状态查；全部不带 status', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () =>
      json(200, { items: [], page: 1, size: 20, total: 0, hasMore: false }))
    const api = apiWith(fetchImpl)
    await api.list({ status: 'IN_SERVICE' }, 2, 20)
    await api.list({ status: '' }, 1, 20)
    await api.detail('o/1')
    expect(fetchImpl.mock.calls.map(([url]) => String(url))).toEqual([
      '/api/v1/provider/service-orders?status=IN_SERVICE&page=2&size=20',
      '/api/v1/provider/service-orders?page=1&size=20',
      '/api/v1/provider/service-orders/o%2F1',
    ])
  })

  it('改状态的写操作都带 If-Match（订单版本）；上传、删除文件不带', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async (_url, init) =>
      (init?.method === 'DELETE' ? new Response(null, { status: 204 }) : json(200, {})))
    const api = apiWith(fetchImpl)
    const current = { orderId: 'o-1', version: 7 }
    await api.issuePlan(current, { schedule: 's', terms: ['t'], note: null })
    await api.start(current)
    await api.deliver(current, { note: 'n', fileIds: ['f-1'] })
    await api.cancel(current, '设备故障')
    await api.terminate(current, { refundAmountMinor: 50_000, reason: '协商一致' })
    await api.uploadFile('o-1', new File(['%PDF'], '报告.pdf', { type: 'application/pdf' }))
    await api.removeFile('o-1', 'f-1')

    const calls = fetchImpl.mock.calls.map(([url, init]) => ({ url: String(url), init: init! }))
    expect(calls.map((call) => `${call.init.method} ${call.url}`)).toEqual([
      'PUT /api/v1/provider/service-orders/o-1/plan',
      'POST /api/v1/provider/service-orders/o-1/start',
      'POST /api/v1/provider/service-orders/o-1/delivery',
      'POST /api/v1/provider/service-orders/o-1/cancel',
      'POST /api/v1/provider/service-orders/o-1/termination',
      'POST /api/v1/provider/service-orders/o-1/files',
      'DELETE /api/v1/provider/service-orders/o-1/files/f-1',
    ])
    for (const call of calls.slice(0, 5)) {
      expect((call.init.headers as Record<string, string>)['If-Match'], call.url).toBe('"7"')
    }
    for (const call of calls.slice(5)) {
      expect(call.init.headers as Record<string, string>).not.toHaveProperty('If-Match')
    }
    expect(calls[1]!.init.body).toBeUndefined()
    expect(JSON.parse(String(calls[3]!.init.body))).toEqual({ reason: '设备故障' })
    expect(JSON.parse(String(calls[4]!.init.body))).toEqual({ refundAmountMinor: 50_000, reason: '协商一致' })
    const upload = calls[5]!.init
    expect(upload.body).toBeInstanceOf(FormData)
    expect((upload.body as FormData).get('file')).toBeInstanceOf(File)
    expect(upload.headers as Record<string, string>).not.toHaveProperty('Content-Type')
  })

  it('带登录下载文件', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () => new Response('x', {
      status: 200, headers: { 'Content-Disposition': "attachment; filename*=UTF-8''%E6%88%90%E7%89%87.mp4" },
    }))
    const file = await apiWith(fetchImpl).downloadFile('o-1', 'f-2')
    expect(String(fetchImpl.mock.calls[0]![0])).toBe('/api/v1/provider/service-orders/o-1/files/f-2/content')
    expect((fetchImpl.mock.calls[0]![1]!.headers as Record<string, string>).Authorization).toBe('Bearer jwt')
    expect(file.fileName).toBe('成片.mp4')
  })

  it('错误码有中文说明；412 认作“要刷新”', () => {
    for (const code of ['SERVICE_ORDER_STATE_INVALID', 'SERVICE_ORDER_PLAN_CHANGED', 'SERVICE_ORDER_DELIVERY_CHANGED',
      'SERVICE_ORDER_PAYMENT_UNAVAILABLE', 'SERVICE_ORDER_REFUND_NOT_ALLOWED', 'SERVICE_ORDER_FILE_INVALID',
      'SERVICE_ORDER_FILE_STORE_UNAVAILABLE', 'SERVICE_ORDER_TERMS_NOT_AGREED']) {
      const message = errorMessage(new ApiRequestError(409, code, 'server text', 't'))
      expect(message, code).not.toBe('server text')
    }
    const stale = new ApiRequestError(412, 'SERVICE_ORDER_VERSION_CONFLICT', 'x', 't')
    expect(errorMessage(stale)).toContain('刷新')
    expect(isStale(stale)).toBe(true)
    expect(isStale(new ApiRequestError(409, 'SERVICE_ORDER_STATE_INVALID', 'x', 't'))).toBe(false)
  })
})

describe('状态到按钮', () => {
  it('各状态显示的按钮（服务端 actions 和状态都允许才显示）', () => {
    const expected: Record<ServiceOrderStatus, string[]> = {
      AWAITING_PLAN: ['issuePlan', 'cancel'],
      PLAN_PENDING: ['revisePlan', 'cancel'],
      AWAITING_PAYMENT: ['cancel'],
      AWAITING_START: ['start', 'cancel'],
      IN_SERVICE: ['deliver', 'terminate'],
      DELIVERED: ['terminate'],
      REWORK: ['deliver', 'terminate'],
      COMPLETED: [],
      CANCELLED: [],
      TERMINATED: [],
    }
    for (const status of Object.keys(expected) as ServiceOrderStatus[]) {
      const current = order(status, { plan: status === 'AWAITING_PLAN' ? null : PLAN })
      expect(orderButtons(current), status).toEqual(expected[status])
    }
  })

  it('服务端说不能做的不显示；状态不对时服务端给了 true 也不显示', () => {
    expect(orderButtons(order('AWAITING_START', { actions: { ...NO_ACTIONS, cancel: true } }))).toEqual(['cancel'])
    expect(orderButtons(order('COMPLETED', { actions: { ...NO_ACTIONS, terminate: true, cancel: true } }))).toEqual([])
  })

  it('上传、删除未交付文件只在服务中、整改中', () => {
    const allowed = (Object.keys(PROVIDER_ACTIONS) as ServiceOrderStatus[]).filter((status) => canEditFiles(order(status)))
    expect(allowed).toEqual(['IN_SERVICE', 'REWORK'])
  })

  it('列表页签：十个状态加“全部”', () => {
    expect(STATUS_TABS.map((tab) => tab.label)).toEqual(['待出方案', '待确认', '待付款', '待开工', '服务中', '待验收',
      '整改中', '已完成', '已取消', '已终止', '全部'])
    expect(STATUS_TABS.at(-1)!.value).toBe('')
  })
})

describe('金额（元按字符串换成分）', () => {
  it('协商终止：0 到实付减已退，按分提交', () => {
    const paid = order('IN_SERVICE', { payment: { ...order('IN_SERVICE').payment, paidAt: '2026-11-23T03:00:00Z' } })
    expect(paidMinor(paid)).toBe(1_200_000)
    expect(terminateMaxMinor(paid)).toBe(1_200_000)
    expect(terminateMaxMinor({ payment: { ...paid.payment, refundedMinor: 200_000 } })).toBe(1_000_000)
    expect(paidMinor(order('AWAITING_PAYMENT'))).toBe(0)

    expect(buildTerminateRequest('0', '协商一致，不退款', 1_200_000))
      .toEqual({ ok: true, request: { refundAmountMinor: 0, reason: '协商一致，不退款' } })
    expect(buildTerminateRequest('4800.5', ' 按已完成部分退款 ', 1_200_000))
      .toEqual({ ok: true, request: { refundAmountMinor: 480_050, reason: '按已完成部分退款' } })
    expect(buildTerminateRequest('12000', 'x', 1_200_000).ok).toBe(true)
    expect(buildTerminateRequest('0.1', 'x', 1_200_000)).toMatchObject({ request: { refundAmountMinor: 10 } })
    for (const [yuan, reason, message] of [
      ['12000.01', 'x', '不能超过实付'],
      ['-1', 'x', '退款金额'],
      ['1.234', 'x', '退款金额'],
      ['', 'x', '退款金额'],
      ['10', '', '终止原因'],
      ['10', '原'.repeat(201), '最多 200'],
    ] as const) {
      const built = buildTerminateRequest(yuan, reason, 1_200_000)
      expect(built.ok, `${yuan}/${reason}`).toBe(false)
      if (!built.ok) expect(built.message).toContain(message)
    }
  })

  it('取消原因 1–80 字', () => {
    expect(cancelReasonError('')).toContain('请填写')
    expect(cancelReasonError('原'.repeat(81))).toContain('80')
    expect(cancelReasonError('设备故障')).toBeNull()
  })
})

describe('方案和交付表单', () => {
  it('第一次出方案用报价的排期预填；改方案用最新一版预填', () => {
    expect(initialPlanForm(order('AWAITING_PLAN'))).toEqual({ schedule: '确认后一周内', terms: [{ label: '' }], note: '' })
    expect(initialPlanForm(order('PLAN_PENDING', { plan: { ...PLAN, note: '注意' } })))
      .toEqual({ schedule: '11/26 上门拍摄', terms: [{ label: '3 天内交付成片' }], note: '注意' })
  })

  it('服务安排 ≤60、交付约定 1–5 条每条 ≤60、方案说明 ≤500', () => {
    const ok = buildPlanRequest({ schedule: ' 11/26 上门 ', terms: [{ label: ' 成片 ' }], note: '' })
    expect(ok).toEqual({ ok: true, request: { schedule: '11/26 上门', terms: ['成片'], note: null } })
    for (const [form, message] of [
      [{ schedule: '', terms: [{ label: 'a' }], note: '' }, '服务安排'],
      [{ schedule: '安'.repeat(61), terms: [{ label: 'a' }], note: '' }, '60'],
      [{ schedule: 's', terms: [], note: '' }, '至少写一条'],
      [{ schedule: 's', terms: Array.from({ length: 6 }, () => ({ label: 'a' })), note: '' }, '最多 5 条'],
      [{ schedule: 's', terms: [{ label: 'a' }, { label: ' ' }], note: '' }, '第 2 条是空的'],
      [{ schedule: 's', terms: [{ label: '约'.repeat(61) }], note: '' }, '每条最多 60'],
      [{ schedule: 's', terms: [{ label: 'a' }], note: '说'.repeat(501) }, '500'],
    ] as const) {
      const built = buildPlanRequest({ ...form, terms: [...form.terms] })
      expect(built.ok, message).toBe(false)
      if (!built.ok) expect(built.message).toContain(message)
    }
  })

  it('交付说明必填 ≤1000；文件 0–10 个', () => {
    expect(buildDeliveryRequest(' 已交付成片 ', [])).toEqual({ ok: true, request: { note: '已交付成片', fileIds: [] } })
    expect(buildDeliveryRequest('', ['f']).ok).toBe(false)
    expect(buildDeliveryRequest('说'.repeat(1001), []).ok).toBe(false)
    expect(buildDeliveryRequest('n', Array.from({ length: 11 }, (_, i) => `f${i}`)).ok).toBe(false)
  })

  it('上传前检查：类型按扩展名、≤20 MB、未交付最多 10 个', () => {
    expect(fileProblem({ name: '报告.PDF', size: 1024 }, 0)).toBeNull()
    for (const name of ['a.jpg', 'a.jpeg', 'a.png', 'a.zip', 'a.docx', 'a.xlsx', 'a.pptx', 'a.mp4']) {
      expect(fileProblem({ name, size: 1 }, 9), name).toBeNull()
    }
    expect(fileProblem({ name: 'a.doc', size: 1 }, 0)).toContain('只能上传')
    expect(fileProblem({ name: 'noext', size: 1 }, 0)).toContain('只能上传')
    expect(fileProblem({ name: 'a.pdf', size: 20 * 1024 * 1024 + 1 }, 0)).toContain('20 MB')
    expect(fileProblem({ name: 'a.pdf', size: 0 }, 0)).toContain('空')
    expect(fileProblem({ name: 'a.pdf', size: 1 }, 10)).toContain('最多 10')
  })
})

describe('显示', () => {
  it('客户：企业名 + 经办人，或个人', () => {
    expect(customerLabel({ kind: 'ORGANIZATION', organizationName: '示例园区', contactName: '王经理' }))
      .toBe('企业 · 示例园区（经办人 王经理）')
    expect(customerLabel({ kind: 'PERSONAL', organizationName: null, contactName: '李先生' })).toBe('个人 · 李先生')
  })

  it('订单记录带方案版本或第几次交付', () => {
    expect(eventLabel({ type: 'PLAN_REVISED', occurredAt: '', planVersion: 2, round: null })).toBe('改方案（方案 v2）')
    expect(eventLabel({ type: 'RETURNED', occurredAt: '', planVersion: 2, round: 1 })).toBe('客户退回整改（第 1 次交付）')
    expect(eventLabel({ type: 'PAID', occurredAt: '', planVersion: null, round: null })).toBe('客户付款到账')
  })
})

describe('菜单', () => {
  it('“服务订单”“服务商资料”只给机构操作员，“服务商资料审核”只给平台运营', () => {
    const titles = (roles: string[]) => buildMenu(ADMIN_MODULES, roles).flatMap((group) => group.items.map((item) => item.title))
    expect(titles(['PROVIDER_OPERATOR'])).toEqual(expect.arrayContaining(['服务订单', '服务商资料']))
    expect(titles(['PROVIDER_OPERATOR'])).not.toContain('服务商资料审核')
    expect(titles(['PLATFORM_OPERATOR'])).toContain('服务商资料审核')
    expect(titles(['PLATFORM_OPERATOR'])).not.toContain('服务订单')
    for (const role of ['PLATFORM_SUPPORT', 'LIVE_HOST']) {
      expect(titles([role])).not.toContain('服务订单')
      expect(titles([role])).not.toContain('服务商资料')
      expect(titles([role])).not.toContain('服务商资料审核')
    }
  })
})
