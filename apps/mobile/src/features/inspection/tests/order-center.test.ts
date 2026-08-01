import { describe, expect, it } from 'vitest'

import pagesSource from '../../../pages.json?raw'
import bookingHttpClientSource from '../data/service-flow/uni-booking-http-client.ts?raw'
import orderCardSource from '../presentation/orders/components/InspectionOrderCard.vue?raw'
import orderCenterPageSource from '../presentation/orders/pages/InspectionOrderCenterPage.vue?raw'

import {
  getPreviousInspectionPageRoute,
  resolveInspectionOrderDestination,
} from '../application/service-flow/inspection-order-navigation'
import type {
  InspectionOrderPage,
  InspectionOrderStage,
  InspectionOrderSummary,
} from '../domain/service-flow/order-center'
import { createInspectionOrderApi } from '../data/service-flow/inspection-order-api'
import type { BookingHttpClient } from '../data/service-flow/inspection-booking-api'
import {
  buildInspectionOrderCenterUrl,
  normalizeInspectionReturnUrl,
} from '../navigation/inspection-routes'
import * as orderCenterView from '../presentation/orders/order-center-view'
import { getInspectionStatusLabel } from '../presentation/platform/mine/platform-mine'

const RESERVATION_ID = '40000000-0000-4000-8000-000000000001'
const PLAN_ID = '50000000-0000-4000-8000-000000000001'
const ORDER_ID = '60000000-0000-4000-8000-000000000001'

describe('order center API', () => {
  it('queries the authenticated customer collection with serialized paging', async () => {
    const calls: Array<{
      url: string
      method: string
      headers?: Record<string, string>
    }> = []
    const api = createInspectionOrderApi({
      request: async <T,>(input: {
        url: string
        method: 'GET' | 'POST'
      }) => {
        calls.push(input)
        return emptyOrderPage() as T
      },
      putFile: async () => ({ etag: 'unused' }),
    } satisfies BookingHttpClient, { getAccessToken: () => 'customer-token' })

    await api.list({ stage: 'AWAITING_CONFIRMATION', page: 2, size: 20 })

    expect(calls).toEqual([{
      method: 'GET',
      url: '/api/v1/inspection/reservations?stage=AWAITING_CONFIRMATION&page=2&size=20',
      headers: { Authorization: 'Bearer customer-token' },
    }])
  })

  it('rejects invalid order paging before making a request', async () => {
    let calls = 0
    const api = createInspectionOrderApi({
      request: async <T,>() => {
        calls += 1
        return emptyOrderPage() as T
      },
      putFile: async () => ({ etag: 'unused' }),
    } satisfies BookingHttpClient)

    await expect(api.list({ stage: 'ALL', page: 0, size: 20 }))
      .rejects.toThrow('INVALID_ORDER_PAGE')
    expect(calls).toBe(0)
  })
})

describe('order center navigation', () => {
  it('reads the previous route from an App page stack without Array.at', () => {
    const pages = {
      0: { route: 'pages/mine/inspection-orders' },
      1: { route: 'pages/service/inspection/booking-submitted' },
      length: 2,
    }

    expect(getPreviousInspectionPageRoute(pages)).toBe('pages/mine/inspection-orders')
  })

  it('accepts summaries that omit absent optional server fields', () => {
    const summary = order()

    expect(summary).not.toHaveProperty('serviceCoverUrl')
    expect(summary).not.toHaveProperty('currentPlanId')
    expect(summary).not.toHaveProperty('serviceOrderId')
    expect(summary).not.toHaveProperty('serviceOrderStatus')
    expect(summary).not.toHaveProperty('quoteAmountMinor')
  })

  it('routes each stage only when its stable target id exists', () => {
    expect(resolveInspectionOrderDestination(order({
      stage: 'AWAITING_PLAN',
    }))).toBe(`/pages/service/inspection/booking-submitted?reservationId=${RESERVATION_ID}`)
    expect(resolveInspectionOrderDestination(order({
      stage: 'AWAITING_CONFIRMATION',
      currentPlanId: PLAN_ID,
    }))).toBe(`/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)
    expect(resolveInspectionOrderDestination(order({
      stage: 'IN_PROGRESS',
      serviceOrderId: ORDER_ID,
    }))).toBe(`/pages/service/inspection/fulfillment?serviceOrderId=${ORDER_ID}`)
    expect(resolveInspectionOrderDestination(order({
      stage: 'AWAITING_ACCEPTANCE',
      serviceOrderId: ORDER_ID,
    }))).toBe(`/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}`)
    expect(resolveInspectionOrderDestination(order({
      stage: 'COMPLETED',
      serviceOrderId: ORDER_ID,
    }))).toBe(`/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}`)
    expect(resolveInspectionOrderDestination(order({
      stage: 'AWAITING_CONFIRMATION',
    }))).toBeNull()
  })

  it('returns null when a stage has no stable destination id', () => {
    expect(resolveInspectionOrderDestination(order({
      stage: 'AWAITING_PLAN',
      reservationId: undefined,
    }))).toBeNull()
    expect(resolveInspectionOrderDestination(order({
      stage: 'CANCELLED',
      reservationId: undefined,
    }))).toBeNull()
    expect(resolveInspectionOrderDestination(order({
      stage: 'IN_PROGRESS',
    }))).toBeNull()
    expect(resolveInspectionOrderDestination(order({
      stage: 'AWAITING_ACCEPTANCE',
    }))).toBeNull()
    expect(resolveInspectionOrderDestination(order({
      stage: 'COMPLETED',
    }))).toBeNull()
  })

  it('keeps the order center return route with only its optional stage', () => {
    const centerUrl = buildInspectionOrderCenterUrl('IN_PROGRESS')

    expect(centerUrl).toBe('/pages/mine/inspection-orders?stage=IN_PROGRESS')
    expect(buildInspectionOrderCenterUrl()).toBe('/pages/mine/inspection-orders?stage=ALL')
    expect(normalizeInspectionReturnUrl(centerUrl)).toBe(centerUrl)
    expect(normalizeInspectionReturnUrl('/pages/mine/unknown-orders?stage=IN_PROGRESS'))
      .toBe('/pages/home/index')
  })

  it('drops unsupported order center return stages', () => {
    expect(normalizeInspectionReturnUrl('/pages/mine/inspection-orders?stage=CANCELLED'))
      .toBe('/pages/mine/inspection-orders')
    expect(normalizeInspectionReturnUrl('/pages/mine/inspection-orders?stage=UNTRUSTED'))
      .toBe('/pages/mine/inspection-orders')
  })
})

describe('order center page', () => {
  it('registers a custom-navigation order center with complete list states', () => {
    const pages = JSON.parse(pagesSource) as {
      pages: Array<{ path: string; style?: Record<string, string | boolean> }>
    }

    expect(pages.pages.find((page) =>
      page.path === 'pages/mine/inspection-orders',
    )).toMatchObject({
      style: {
        navigationStyle: 'custom',
        enablePullDownRefresh: true,
      },
    })

    expect(orderCenterPageSource).toContain('onPullDownRefresh')
    expect(orderCenterPageSource).toContain('onReachBottom')
    expect(orderCenterPageSource).toContain('createInspectionOrderCenterController')
    expect(orderCenterPageSource).toContain('api: inspectionOrderApi')
    expect(orderCenterPageSource).toContain('resolveInspectionOrderDestination')
    expect(orderCenterPageSource).toContain('订单加载失败')
    expect(orderCenterPageSource).toContain('暂无巡检订单')
    expect(orderCenterPageSource).toContain('没有更多订单')
    expect(orderCenterPageSource).toContain('env(safe-area-inset-bottom)')
    expect(orderCenterPageSource).toContain('void controller.openOrder(order)')
    expect(orderCenterPageSource).toContain('void controller.returnMine()')
    expect(orderCenterPageSource).toContain('function returnMine()')
    expect(orderCenterPageSource).toContain('uni.navigateBack({')
    expect(orderCenterPageSource).toContain('switchTab: (url) => uni.switchTab({ url })')
    expect(bookingHttpClientSource).toContain('beginBookingAuthenticationRecovery()')
    expect(bookingHttpClientSource).toContain('export const inspectionOrderApi')
    expect(orderCenterPageSource).not.toContain('立即支付')
    expect(orderCenterPageSource).not.toContain('退款')
  })
})

describe('order center narrow H5 layout', () => {
  it('keeps card labels on one line within the padded list and shows every filter', () => {
    expect(orderCardSource).toContain('.inspection-order-card{box-sizing:border-box;')
    expect(orderCardSource).toContain('.inspection-order-card__title-line>text:first-child{min-width:0;flex:1;')
    expect(orderCardSource).toContain('.inspection-order-card__title-line>text:last-child{flex:0 0 auto;white-space:nowrap}')
    expect(orderCardSource).toContain('.inspection-order-card__aside>text:last-child{padding:6px 10px;border:1px solid #ff6508;border-radius:16px;color:#d64d00;font-size:13px;white-space:nowrap}')

    expect(orderCenterPageSource).not.toContain('scroll-x')
    expect(orderCenterPageSource).toContain('<view class="inspection-orders__filters">')
    expect(orderCenterPageSource).toContain('.inspection-orders__filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));')
    expect(orderCenterPageSource).toContain('min-height:44px')
  })
})

describe('order center view logic', () => {
  it('normalizes only approved filter codes', () => {
    const normalize = getOrderCenterViewContract().normalizeInspectionOrderFilter

    expect(normalize).toBeTypeOf('function')
    if (!normalize) return
    expect(normalize('IN_PROGRESS')).toBe('IN_PROGRESS')
    expect(normalize('CANCELLED')).toBe('ALL')
    expect(normalize('untrusted')).toBe('ALL')
    expect(normalize()).toBe('ALL')
  })

  it('merges page items without existing or same-page reservation duplicates', () => {
    const merge = getOrderCenterViewContract().mergeUniqueInspectionOrders

    expect(merge).toBeTypeOf('function')
    if (!merge) return
    const first = order()
    const second = order({
      reservationId: '40000000-0000-4000-8000-000000000002',
      reservationNo: 'YY202607310002',
    })

    expect(merge([first], [first, second, second])).toEqual([first, second])
  })

  it('applies only responses for the current request version', () => {
    const shouldApply = getOrderCenterViewContract().shouldApplyInspectionOrderResponse

    expect(shouldApply).toBeTypeOf('function')
    if (!shouldApply) return
    expect(shouldApply(2, 2)).toBe(true)
    expect(shouldApply(1, 2)).toBe(false)
  })

  it('keeps cancelled order status when mapping platform order metadata', () => {
    expect(orderCenterView.toPlatformMineOrder(order({ stage: 'CANCELLED' })).status)
      .toBe('CANCELLED')
  })

  it('labels cancelled platform orders as cancelled', () => {
    expect(getInspectionStatusLabel('CANCELLED')).toBe('已取消')
  })
})

describe('order center page controller', () => {
  it('moves an initial empty response from loading to empty', async () => {
    const state = createOrderCenterState()
    const controller = createOrderCenterController({
      api: { list: async () => emptyOrderPage() },
    }, state)

    expect(getOrderCenterViewContract().getInspectionOrderListState?.(state)).toBe('loading')
    await controller.reload()

    expect(state.isLoading).toBe(false)
    expect(getOrderCenterViewContract().getInspectionOrderListState?.(state)).toBe('empty')
  })

  it('recovers from an initial failure when retry reload succeeds', async () => {
    let calls = 0
    const state = createOrderCenterState()
    const controller = createOrderCenterController({
      api: {
        list: async () => {
          calls += 1
          if (calls === 1) throw new Error('NETWORK_ERROR')
          return orderPage([order()])
        },
      },
    }, state)

    await controller.reload()
    expect(getOrderCenterViewContract().getInspectionOrderListState?.(state)).toBe('error')
    expect(state.loadError).toBe('NETWORK_ERROR')

    await controller.reload()
    expect(getOrderCenterViewContract().getInspectionOrderListState?.(state)).toBe('ready')
    expect(state.orders).toEqual([order()])
    expect(state.loadError).toBe('')
  })

  it('ignores an older reload response after a newer filter request resolves', async () => {
    const first = deferred<InspectionOrderPage>()
    const second = deferred<InspectionOrderPage>()
    const requests: string[] = []
    const state = createOrderCenterState()
    const controller = createOrderCenterController({
      api: {
        list: ({ stage }) => {
          requests.push(stage)
          return requests.length === 1 ? first.promise : second.promise
        },
      },
    }, state)

    const initial = controller.reload()
    const latest = controller.selectStage('IN_PROGRESS')
    second.resolve(orderPage([order({ reservationNo: 'LATEST' })], 1, false))
    await latest
    first.resolve(orderPage([order({ reservationNo: 'STALE' })], 1, false))
    await initial

    expect(requests).toEqual(['ALL', 'IN_PROGRESS'])
    expect(state.orders.map((item) => item.reservationNo)).toEqual(['LATEST'])
    expect(state.selectedStage).toBe('IN_PROGRESS')
  })

  it('loads the next page once and removes cross-page and same-page duplicates', async () => {
    const first = order()
    const second = order({
      reservationId: '40000000-0000-4000-8000-000000000002',
      reservationNo: 'YY202607310002',
    })
    const state = createOrderCenterState()
    const controller = createOrderCenterController({
      api: {
        list: async ({ page }) => page === 1
          ? orderPage([first], 1, true)
          : orderPage([first, second, second], 2, false),
      },
    }, state)

    await controller.reload()
    expect(state.hasMore).toBe(true)
    await controller.loadMore()

    expect(state.isLoadingMore).toBe(false)
    expect(state.hasMore).toBe(false)
    expect(state.page).toBe(2)
    expect(state.orders).toEqual([first, second])
  })

  it('reloads and prompts for a missing stable destination id, otherwise navigates', async () => {
    const navigated: string[] = []
    const prompts: string[] = []
    let reloads = 0
    const state = createOrderCenterState()
    const controller = createOrderCenterController({
      api: {
        list: async () => {
          reloads += 1
          return emptyOrderPage()
        },
      },
      navigateTo: (url) => navigated.push(url),
      showToast: (message) => prompts.push(message),
      resolveDestination: resolveInspectionOrderDestination,
    }, state)

    await controller.openOrder(order({ stage: 'AWAITING_CONFIRMATION' }))
    await controller.openOrder(order({
      stage: 'AWAITING_CONFIRMATION',
      currentPlanId: PLAN_ID,
    }))

    expect(reloads).toBe(1)
    expect(prompts).toEqual(['订单状态已更新，请重新查看'])
    expect(navigated).toEqual([`/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`])
  })

  it('falls back to my tab only when navigateBack fails', async () => {
    const switched: string[] = []
    const state = createOrderCenterState()
    let returns = false
    const controller = createOrderCenterController({
      api: { list: async () => emptyOrderPage() },
      navigateBack: async () => returns,
      switchTab: (url) => switched.push(url),
    }, state)

    await controller.returnMine()
    returns = true
    await controller.returnMine()

    expect(switched).toEqual(['/pages/mine/index'])
  })
})

interface OrderCenterViewContract {
  normalizeInspectionOrderFilter?: (value?: string) => string
  mergeUniqueInspectionOrders?: (
    existing: InspectionOrderSummary[],
    incoming: InspectionOrderSummary[],
  ) => InspectionOrderSummary[]
  shouldApplyInspectionOrderResponse?: (
    responseVersion: number,
    currentVersion: number,
  ) => boolean
  createInspectionOrderCenterState?: () => OrderCenterState
  getInspectionOrderListState?: (state: OrderCenterState) => 'loading' | 'error' | 'empty' | 'ready'
  createInspectionOrderCenterController?: (
    dependencies: OrderCenterControllerDependencies,
    state: OrderCenterState,
  ) => OrderCenterController
}

interface OrderCenterState {
  selectedStage: import('../domain/service-flow/order-center').InspectionOrderFilter
  orders: InspectionOrderSummary[]
  page: number
  hasMore: boolean
  isLoading: boolean
  isLoadingMore: boolean
  loadError: string
}

interface OrderCenterControllerDependencies {
  api: {
    list(input: {
      stage: import('../domain/service-flow/order-center').InspectionOrderFilter
      page: number
      size: number
    }): Promise<InspectionOrderPage>
  }
  resolveDestination?: typeof resolveInspectionOrderDestination
  navigateTo?: (url: string) => void
  showToast?: (message: string) => void
  navigateBack?: () => Promise<boolean>
  switchTab?: (url: string) => void
}

interface OrderCenterController {
  reload(): Promise<void>
  loadMore(): Promise<void>
  selectStage(stage: import('../domain/service-flow/order-center').InspectionOrderFilter): Promise<void>
  openOrder(order: InspectionOrderSummary): Promise<void>
  returnMine(): Promise<void>
}

function getOrderCenterViewContract(): OrderCenterViewContract {
  return orderCenterView as OrderCenterViewContract
}

function createOrderCenterState(): OrderCenterState {
  const createState = getOrderCenterViewContract().createInspectionOrderCenterState
  expect(createState).toBeTypeOf('function')
  if (!createState) throw new Error('ORDER_CENTER_STATE_FACTORY_MISSING')
  return createState()
}

function createOrderCenterController(
  dependencies: OrderCenterControllerDependencies,
  state: OrderCenterState,
): OrderCenterController {
  const createController = getOrderCenterViewContract().createInspectionOrderCenterController
  expect(createController).toBeTypeOf('function')
  if (!createController) throw new Error('ORDER_CENTER_CONTROLLER_MISSING')
  return createController(dependencies, state)
}

function order(
  overrides: Partial<InspectionOrderSummary> = {},
): InspectionOrderSummary {
  return {
    reservationId: RESERVATION_ID,
    reservationNo: 'YY202607310001',
    serviceId: '30000000-0000-4000-8000-000000000001',
    serviceTitle: '输电线路无人机巡检',
    cityCode: '370500',
    locationSummary: '东营市示范线路',
    preferredServiceDate: '2026-08-02',
    submittedAt: '2026-07-31T08:00:00Z',
    stage: 'AWAITING_PLAN',
    reservationStatus: 'SUBMITTED',
    currency: 'CNY',
    ...overrides,
  }
}

function emptyOrderPage(): InspectionOrderPage {
  const stages: InspectionOrderStage[] = [
    'AWAITING_PLAN',
    'AWAITING_CONFIRMATION',
    'IN_PROGRESS',
    'AWAITING_ACCEPTANCE',
    'COMPLETED',
  ]
  return {
    items: [],
    page: 1,
    size: 20,
    total: 0,
    hasMore: false,
    stageCounts: Object.fromEntries(stages.map((stage) => [stage, 0])) as InspectionOrderPage['stageCounts'],
  }
}

function orderPage(
  items: InspectionOrderSummary[],
  page = 1,
  hasMore = false,
): InspectionOrderPage {
  return {
    ...emptyOrderPage(),
    items,
    page,
    hasMore,
    total: items.length,
  }
}

function deferred<T>(): {
  promise: Promise<T>
  resolve(value: T): void
} {
  let resolvePromise: (value: T) => void = () => undefined
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve
  })
  return {
    promise,
    resolve(value) {
      resolvePromise(value)
    },
  }
}
