import type { Page, Route } from '@playwright/test'

// Stubbed API for the general service order (S4-3, contracts/openapi-service-order.yaml 1.0.0) and
// the demand, pro-service and trade reads around it. Every response is built here from a small,
// mutable state, so a test can move the order along (the provider has no page: issuing the plan,
// starting and delivering are done by changing the state, as the provider's authorised routes
// would). Nothing reaches a real server.

export const TOKEN = 'e2e-service-order-token'
export const CITY = '370500'
export const ORDER_ID = 'c5000000-0000-4000-8000-000000000001'
export const DEMAND_ID = 'c5000000-0000-4000-8000-000000000101'
export const INSPECTION_DEMAND_ID = 'c5000000-0000-4000-8000-000000000102'
export const PROVIDER_ID = 'c5000000-0000-4000-8000-000000000201'
export const QUOTE_A = 'c5000000-0000-4000-8000-000000000301'
export const QUOTE_B = 'c5000000-0000-4000-8000-000000000302'
export const QUOTE_C = 'c5000000-0000-4000-8000-000000000303'
export const TRADE_ORDER_ID = 'c5000000-0000-4000-8000-000000000401'
export const SERVICE_ID = 'c5000000-0000-4000-8000-000000000501'
export const FILE_REPORT = 'c5000000-0000-4000-8000-000000000601'
export const FILE_PHOTOS = 'c5000000-0000-4000-8000-000000000602'

export type OrderStatus =
  | 'AWAITING_PLAN' | 'PLAN_PENDING' | 'AWAITING_PAYMENT' | 'AWAITING_START' | 'IN_SERVICE'
  | 'DELIVERED' | 'REWORK' | 'COMPLETED' | 'CANCELLED' | 'TERMINATED'

const iso = (minutesFromNow: number) => new Date(Date.now() + minutesFromNow * 60_000).toISOString()
const day = (daysFromNow: number) => new Date(Date.now() + 8 * 3_600_000 + daysFromNow * 86_400_000).toISOString().slice(0, 10)

const PROVIDER = { providerId: PROVIDER_ID, name: '鹰眼智能巡检', qualificationReviewed: true }
const CUSTOMER = { kind: 'ORGANIZATION', organizationName: '东营港务物流', contactName: '王女士' }
const LINES = [
  { name: '上门检测', quantity: 4, unit: '台', unitAmountMinor: 59_000, amountMinor: 236_000 },
  { name: '更换桨叶与电池保养', quantity: null, unit: null, unitAmountMinor: null, amountMinor: 150_000 },
]

export interface StubState {
  status: OrderStatus
  planVersion: number | null
  planConfirmed: boolean
  paid: boolean
  collectable: boolean
  deliveries: Array<Record<string, unknown>>
  refunds: Array<Record<string, unknown>>
  closedBy: 'CUSTOMER' | 'PROVIDER' | null
  // The demand behind the order: OPEN with three quotes until chosen.
  demandStatus: 'QUOTED' | 'CHOSEN'
  // Requests the page sent, for assertions.
  sent: Array<{ method: string; path: string; body: unknown; ifMatch: string | null }>
  // A one-off answer for the next command on this path (e.g. 409 PLAN_CHANGED).
  failNext: Record<string, { status: number; code: string; message: string }>
  version: number
}

export function initialState(overrides: Partial<StubState> = {}): StubState {
  return {
    status: 'AWAITING_PLAN',
    planVersion: null,
    planConfirmed: false,
    paid: false,
    collectable: true,
    deliveries: [],
    refunds: [],
    closedBy: null,
    demandStatus: 'QUOTED',
    sent: [],
    failNext: {},
    version: 1,
    ...overrides,
  }
}

export function delivery(round: number, status: 'SUBMITTED' | 'ACCEPTED' | 'RETURNED', reviewNote: string | null = null) {
  return {
    round,
    status,
    note: round === 1
      ? '4 台无人机已检测，更换桨叶 2 副、电池 1 块，附维修记录与检测报告'
      : '补充东侧机库那台的检测报告，桨叶更换照片已补齐',
    files: [
      { fileId: FILE_REPORT, name: '维修记录与检测报告.pdf', contentType: 'application/pdf', sizeBytes: 2_400_000, uploadedAt: iso(-60) },
      { fileId: FILE_PHOTOS, name: '现场照片.zip', contentType: 'application/zip', sizeBytes: 18_600_000, uploadedAt: iso(-60) },
    ],
    deliveredAt: iso(-60 + round),
    reviewNote,
    reviewedAt: status === 'SUBMITTED' ? null : iso(-30),
    reviewedBy: status === 'SUBMITTED' ? null : '王女士',
  }
}

// What the customer may do in each status (the server's actions).
function actionsOf(state: StubState) {
  const none = { confirmPlan: false, pay: false, cancel: false, accept: false, rework: false, issuePlan: false, start: false, deliver: false, terminate: false }
  switch (state.status) {
    case 'AWAITING_PLAN': return { ...none, cancel: true }
    case 'PLAN_PENDING': return { ...none, confirmPlan: true, cancel: true }
    case 'AWAITING_PAYMENT': return { ...none, pay: state.collectable, cancel: true }
    case 'AWAITING_START': return { ...none, cancel: true }
    case 'DELIVERED': return { ...none, accept: true, rework: true }
    default: return none
  }
}

export function orderDto(state: StubState) {
  const plan = state.planVersion
    ? {
        version: state.planVersion,
        amountMinor: 386_000,
        schedule: `${day(3).slice(5).replace('-', '/')} 上门检测，约半天`,
        terms: ['检测后 48 小时内交付维修记录与检测报告', '更换件保留旧件，随设备交还', '交付文件通过受控链接下载'],
        note: null,
        issuedAt: iso(-120),
        confirmedAt: state.planConfirmed ? iso(-90) : null,
        confirmedBy: state.planConfirmed ? '王女士' : null,
      }
    : null
  return {
    orderId: ORDER_ID,
    orderNo: 'SV20261123000001',
    status: state.status,
    type: 'MAINTENANCE',
    title: '设备维护需求',
    demandId: DEMAND_ID,
    customer: CUSTOMER,
    provider: PROVIDER,
    cityCode: CITY,
    cityName: '东营市',
    address: '东营港 示例港路 6 号 机库',
    scope: '巡检无人机 4 台',
    dueDate: day(7),
    amountMinor: 386_000,
    lines: LINES,
    quote: {
      quoteId: QUOTE_A, quoteVersion: 2, method: '上门检测 + 寄修', schedule: '共 1 次 · 首次进场后半天',
      durationDays: 5, deliverables: '维修记录、检测报告', team: '东营本地', nightWork: null,
      reportHours: 48, archiveDays: 90, firstVisitDate: day(3),
    },
    plan,
    payment: {
      amountMinor: 386_000,
      collectable: state.collectable,
      paidAt: state.paid ? iso(-80) : null,
      pendingTradeOrderId: null,
      pendingPayBefore: null,
      paidTradeOrderId: state.paid ? TRADE_ORDER_ID : null,
      refundedMinor: state.refunds.reduce((sum, refund) => sum + (refund.amountMinor as number), 0),
      refunds: state.refunds,
    },
    deliveries: state.deliveries,
    stagedFiles: [],
    events: [],
    startedAt: ['IN_SERVICE', 'DELIVERED', 'REWORK', 'COMPLETED', 'TERMINATED'].includes(state.status) ? iso(-70) : null,
    completedAt: state.status === 'COMPLETED' ? iso(-10) : null,
    closedAt: state.status === 'CANCELLED' || state.status === 'TERMINATED' ? iso(-5) : null,
    closedBy: state.closedBy,
    closeReason: null,
    actions: actionsOf(state),
    createdAt: iso(-240),
    updatedAt: iso(-1),
    version: state.version,
  }
}

function summaryDto(state: StubState) {
  const order = orderDto(state)
  return {
    orderId: order.orderId, orderNo: order.orderNo, status: order.status, type: order.type, title: order.title,
    scope: order.scope, cityName: order.cityName, customer: order.customer, provider: order.provider,
    amountMinor: order.amountMinor, planVersion: state.planVersion, paidAt: order.payment.paidAt,
    latestDeliveryAt: state.deliveries.length ? (state.deliveries[state.deliveries.length - 1].deliveredAt as string) : null,
    createdAt: order.createdAt, updatedAt: order.updatedAt,
  }
}

export function demandDto(state: StubState, demandId = DEMAND_ID) {
  const inspection = demandId === INSPECTION_DEMAND_ID
  const chosen = state.demandStatus === 'CHOSEN'
  return {
    demandId,
    status: state.demandStatus,
    closeReason: null,
    type: inspection ? 'INSPECTION' : 'MAINTENANCE',
    title: inspection ? '巡检需求' : '设备维护需求',
    publisher: { kind: 'ORGANIZATION', organizationName: '东营港务物流' },
    cityCode: CITY,
    cityName: '东营市',
    address: '东营港 示例港路 6 号 机库',
    scope: '巡检无人机 4 台',
    dueDate: day(7),
    budgetMinMinor: null,
    budgetMaxMinor: 500_000,
    deliverables: [{ code: 'REPAIR_RECORD', label: '维修记录' }, { code: 'TEST_REPORT', label: '检测报告' }],
    note: null,
    quoteScope: 'MATCH',
    invitedProviders: [],
    submittedAt: iso(-2_000),
    quoteDeadline: iso(2_000),
    quotingOpen: !chosen,
    quoteCount: 3,
    chosen: chosen ? { quoteId: QUOTE_A, quoteVersion: 2, providerName: '鹰眼智能巡检', amountMinor: 386_000, chosenAt: iso(-200) } : null,
    serviceOrderId: chosen && !inspection ? ORDER_ID : null,
    editable: false,
    cancellable: !chosen,
    deletable: false,
    createdAt: iso(-2_100),
    updatedAt: iso(-200),
    version: chosen ? 5 : 4,
  }
}

const QUOTE_PROVIDERS: Record<string, string> = {
  [QUOTE_A]: PROVIDER_ID,
  [QUOTE_B]: 'c5000000-0000-4000-8000-000000000202',
  [QUOTE_C]: 'c5000000-0000-4000-8000-000000000203',
}

export function quotesDto(state: StubState, demandId = DEMAND_ID) {
  const chosen = state.demandStatus === 'CHOSEN'
  const quote = (id: string, name: string, amount: number, version: number, extra: Record<string, unknown>) => ({
    quoteId: id,
    provider: { providerId: QUOTE_PROVIDERS[id], name, certificationLabel: null, qualificationReviewed: true },
    version,
    status: chosen ? (id === QUOTE_A ? 'CHOSEN' : 'NOT_CHOSEN') : 'ACTIVE',
    amountMinor: amount,
    method: '上门检测 + 寄修',
    schedule: '共 1 次',
    durationDays: 5,
    deliverables: '维修记录、检测报告',
    validUntil: iso(1_000),
    submittedAt: iso(-1_500),
    revisedAt: iso(-1_400),
    choosable: !chosen,
    ...extra,
  })
  return {
    demandId,
    demandStatus: state.demandStatus,
    demandVersion: chosen ? 5 : 4,
    sort: 'ALL',
    items: [
      quote(QUOTE_A, '鹰眼智能巡检', 386_000, 2, {
        lines: LINES, team: '东营本地', nightWork: null, reportHours: 48, archiveDays: 90, firstVisitDate: day(3),
      }),
      quote(QUOTE_B, '黄河口飞服', 352_000, 1, {
        lines: [{ name: '寄修检测', quantity: 4, unit: '台', unitAmountMinor: 88_000, amountMinor: 352_000 }],
        team: '东营本地', nightWork: null, reportHours: 72, archiveDays: 30, firstVisitDate: day(5),
      }),
      quote(QUOTE_C, '东营安巡科技', 420_000, 1, {
        lines: [], team: null, nightWork: null, reportHours: null, archiveDays: 0, firstVisitDate: null,
      }),
    ],
  }
}

export function tradeOrderDto(state: StubState) {
  return {
    orderId: TRADE_ORDER_ID,
    orderNo: 'PS2026112300000001',
    source: 'PRO_SERVICE',
    sourceRef: 'c5000000-0000-4000-8000-000000000701',
    status: state.paid ? 'PAID' : 'PENDING_PAYMENT',
    merchant: { organizationId: PROVIDER_ID, name: '鹰眼智能巡检', kind: null },
    lines: [{
      lineId: 'c5000000-0000-4000-8000-000000000801',
      productId: ORDER_ID,
      offerId: 'c5000000-0000-4000-8000-000000000701',
      productName: '设备维护需求 · 鹰眼智能巡检',
      skuName: `方案 v${state.planVersion ?? 1}`,
      coverImage: null,
      unitAmountMinor: 386_000,
      quantity: 1,
      subtotalMinor: 386_000,
    }],
    delivery: null,
    amounts: { goodsMinor: 386_000, shippingMinor: 0, payableMinor: 386_000, paidMinor: state.paid ? 386_000 : 0, refundedMinor: 0 },
    buyerNote: null,
    createdAt: iso(-1),
    payBefore: iso(15),
    paidAt: state.paid ? iso(0) : null,
    completedAt: null,
    closedAt: null,
    closeReason: null,
    refunds: [],
    cancellable: !state.paid,
    version: 1,
  }
}

export function providerPageDto(withProfile = true) {
  return {
    providerId: PROVIDER_ID,
    name: '鹰眼智能巡检',
    certificationLabel: null,
    qualificationReviewed: true,
    description: '东营本地巡检服务商',
    cityCode: CITY,
    inspectionServices: [{
      serviceId: 'c5000000-0000-4000-8000-000000000901',
      title: '园区安防巡检',
      summary: '周界、重点区域和夜间巡查',
      categoryName: '园区综合',
      price: { mode: 'PROJECT', amountMinor: null, unit: null, note: null },
    }],
    proServices: [{
      serviceId: SERVICE_ID, type: 'MAINTENANCE', category: { code: 'DRONE', label: '无人机' },
      title: '无人机检修保养', summary: '上门检测 · 寄修 · 更换件', tags: ['上门服务'],
      price: { mode: 'PRICE_FROM', amountMinor: 59_000, unit: '/台起', note: '含检测' }, coverUrl: null, sample: true,
    }],
    cities: [{ cityCode: CITY, cityName: '东营市' }, { cityCode: '370100', cityName: '济南市' }],
    profile: withProfile
      ? {
          legalEntity: '示例鹰眼智能科技有限公司',
          serviceModes: '无人机巡检 · 机器狗夜巡',
          authorizationUntil: '2027-10-31',
          deliverables: ['巡检报告', '隐患清单', '影像存档', '维修记录'],
          localTeamLabel: '东营本地团队',
        }
      : null,
  }
}

export function maintenanceDetailDto() {
  return {
    serviceId: SERVICE_ID,
    type: 'MAINTENANCE',
    category: { code: 'DRONE', label: '无人机' },
    title: '无人机检修保养',
    summary: '上门检测 · 寄修 · 更换件',
    price: { mode: 'PRICE_FROM', amountMinor: 59_000, unit: '/台起', note: '含检测，更换件另计' },
    coverUrl: null,
    sample: true,
    capabilities: ['上门检测', '寄修', '更换件'],
    priceRows: [{ label: '计价方式', detail: '按台计价，更换件按实际' }],
    scope: ['消费级无人机', '行业无人机', '机器狗'],
    outputs: [{ label: '维修记录', detail: '每台设备一份' }],
    serviceModes: [{ label: '上门服务', detail: '东营市内 24 小时内上门' }, { label: '送修服务', detail: '寄到服务商维修点' }],
    terms: [
      { label: '保修', detail: '维修部位按约定保修' },
      { label: '收费', detail: '检测费含在报价里，更换件另计' },
      { label: '不含', detail: '人为损坏、进水、坠机' },
    ],
    steps: ['提交需求', '报价比选', '上门检修', '验收'],
    guarantees: ['资质已审核', '平台协助沟通'],
    provider: { providerId: PROVIDER_ID, name: '鹰眼智能巡检', certificationLabel: null, description: null, qualificationReviewed: true },
    cities: [{ cityCode: CITY, cityName: '东营市' }],
    warrantyText: '维修后质保 90 天',
  }
}

const EMPTY_PAGE = { page: 1, size: 20, total: 0, hasMore: false, items: [] }

function fail(route: Route, status: number, code: string, message: string) {
  return route.fulfill({ status, json: { code, message, traceId: 'e2e' } })
}

/**
 * Routes every API call of the page to the state. The order commands move the state the way the
 * contract says; `failNext[path]` answers the next command on that path with an error instead.
 */
export async function stubServiceOrderApi(page: Page, state: StubState): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    if (method !== 'GET') {
      let body: unknown = null
      try { body = request.postDataJSON() } catch { body = request.postData() }
      state.sent.push({ method, path, body, ifMatch: request.headers()['if-match'] ?? null })
      const planned = state.failNext[path]
      if (planned) {
        delete state.failNext[path]
        return fail(route, planned.status, planned.code, planned.message)
      }
    }
    const orderPath = `/api/v1/service-orders/${ORDER_ID}`

    // ---- service order
    if (path === '/api/v1/service-orders' && method === 'GET') {
      const status = url.searchParams.get('status')
      const items = !status || status === state.status ? [summaryDto(state)] : []
      return route.fulfill({ json: { page: 1, size: 10, total: items.length, hasMore: false, items } })
    }
    if (path === orderPath && method === 'GET') return route.fulfill({ json: orderDto(state) })
    if (path === `${orderPath}/plan-confirmation`) {
      state.planConfirmed = true
      state.status = 'AWAITING_PAYMENT'
      state.version += 1
      return route.fulfill({ json: orderDto(state) })
    }
    if (path === `${orderPath}/payments`) {
      if (!state.collectable) return fail(route, 409, 'SERVICE_ORDER_PAYMENT_UNAVAILABLE', '服务商不能在线收款')
      return route.fulfill({ json: { orderId: ORDER_ID, tradeOrderId: TRADE_ORDER_ID, amountMinor: 386_000, payBefore: iso(15) } })
    }
    if (path === `${orderPath}/cancel`) {
      if (state.paid) {
        state.refunds = [{ refundId: 'c5000000-0000-4000-8000-000000000a01', kind: 'CANCEL', amountMinor: 386_000, reason: '客户取消', status: 'PROCESSING', requestedAt: iso(0) }]
      }
      state.status = 'CANCELLED'
      state.closedBy = 'CUSTOMER'
      state.version += 1
      return route.fulfill({ json: orderDto(state) })
    }
    if (path === `${orderPath}/acceptance`) {
      state.deliveries = state.deliveries.map((item, index) =>
        index === state.deliveries.length - 1 ? { ...item, status: 'ACCEPTED', reviewedAt: iso(0), reviewedBy: '王女士' } : item)
      state.status = 'COMPLETED'
      state.version += 1
      return route.fulfill({ json: orderDto(state) })
    }
    if (path === `${orderPath}/rework`) {
      const reason = (request.postDataJSON() as { reason: string }).reason
      state.deliveries = state.deliveries.map((item, index) =>
        index === state.deliveries.length - 1 ? { ...item, status: 'RETURNED', reviewNote: reason, reviewedAt: iso(0), reviewedBy: '王女士' } : item)
      state.status = 'REWORK'
      state.version += 1
      return route.fulfill({ json: orderDto(state) })
    }
    if (path.startsWith(`${orderPath}/files/`) && path.endsWith('/download-intents')) {
      return route.fulfill({ json: { url: '/api/v1/service-order-files/c5000000-0000-4000-8000-000000000b01', expiresAt: iso(10) } })
    }
    if (path.startsWith('/api/v1/service-order-files/')) {
      return route.fulfill({ status: 200, headers: { 'Content-Disposition': 'attachment; filename="report.pdf"' }, contentType: 'application/pdf', body: '%PDF-1.4' })
    }

    // ---- trade (cashier)
    if (path === `/api/v1/trade/orders/${TRADE_ORDER_ID}` && method === 'GET') return route.fulfill({ json: tradeOrderDto(state) })

    // ---- demand
    if (path === '/api/v1/demand-counts') {
      return route.fulfill({ json: { total: 5, draft: 1, open: 1, quoted: 1, chosen: 2, closed: 0 } })
    }
    if (path === '/api/v1/demands' && method === 'GET') {
      return route.fulfill({ json: { page: 1, size: 20, total: 2, hasMore: false, items: [demandDto(state), demandDto(state, INSPECTION_DEMAND_ID)] } })
    }
    for (const demandId of [DEMAND_ID, INSPECTION_DEMAND_ID]) {
      if (path === `/api/v1/demands/${demandId}` && method === 'GET') return route.fulfill({ json: demandDto(state, demandId) })
      if (path === `/api/v1/demands/${demandId}/quotes`) return route.fulfill({ json: quotesDto(state, demandId) })
      if (path === `/api/v1/demands/${demandId}/choice`) {
        state.demandStatus = 'CHOSEN'
        return route.fulfill({ json: demandDto(state, demandId) })
      }
    }
    if (path === '/api/v1/rental-inquiries' || path === '/api/v1/mall/inquiries') {
      return route.fulfill({ json: { ...EMPTY_PAGE, total: path.includes('rental') ? 1 : 2 } })
    }

    // ---- pro service
    if (path === `/api/v1/providers/${PROVIDER_ID}`) return route.fulfill({ json: providerPageDto() })
    if (path === `/api/v1/pro-services/${SERVICE_ID}`) return route.fulfill({ json: maintenanceDetailDto() })

    // Anything else a page reads on the side (other order lists, counts): an empty answer.
    if (method === 'GET') return route.fulfill({ json: EMPTY_PAGE })
    return fail(route, 404, 'COMMON_NOT_FOUND', 'not stubbed')
  })
}

export async function signIn(page: Page): Promise<void> {
  await page.addInitScript((token) => {
    localStorage.setItem('inspection.accessToken', token)
  }, TOKEN)
}
