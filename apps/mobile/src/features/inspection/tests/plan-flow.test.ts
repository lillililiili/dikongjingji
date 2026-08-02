import { describe, expect, it } from 'vitest'
import { parse } from '@vue/compiler-sfc'
import {
  ModuleKind,
  ScriptTarget,
  transpileModule,
} from 'typescript'

import pagesSource from '../../../pages.json?raw'
import planPageSource from '../presentation/plan/pages/InspectionPlanConfirmationPage.vue?raw'
import {
  PLAN_AGREEMENT_VERSION,
  formatPlanAmount,
  isPlanConfirmable,
} from '../domain/service-flow/plan'
import {
  createInspectionPlanApi,
  type InspectionPlan,
} from '../data/service-flow/inspection-plan-api'
import type { BookingHttpClient } from '../data/service-flow/inspection-booking-api'
import {
  buildInspectionFulfillmentUrl,
  buildInspectionPlanConfirmationUrl,
} from '../navigation/inspection-routes'

const PLAN_ID = '50000000-0000-4000-8000-000000000001'
const ORDER_ID = '60000000-0000-4000-8000-000000000001'

interface PlanCancellationReservation {
  id: string
  status: 'SUBMITTED' | 'PLAN_PUBLISHED' | 'CANCELLED'
  marker: string
}

interface LoadedPlan {
  reservationId: string
  marker: string
}

type PlanCancellationResult =
  | { kind: 'dismissed' }
  | { kind: 'cancelled'; reservation: PlanCancellationReservation }
  | { kind: 'refresh-required' }
  | { kind: 'failed'; message: string }

interface PlanCancellationControllerOptions {
  getReservation(): PlanCancellationReservation | null
  setReservation(reservation: PlanCancellationReservation): void
  isPlanConfirmed(): boolean
  isCancelling(): boolean
  setCancelling(value: boolean): void
  requestCancellation(
    reservation: PlanCancellationReservation,
  ): Promise<PlanCancellationResult>
  refreshReservation(reservationId: string): Promise<PlanCancellationReservation>
  reloadPlan(): Promise<boolean>
  lockActions(message: string): void
  redirectToCancelledDetail(reservationId: string): void
  showToast(message: string): void
}

interface PlanCancellationController {
  cancelOrder(): Promise<void>
}

type PlanCancellationControllerFactory = (
  options: PlanCancellationControllerOptions,
) => PlanCancellationController

type PlanPageLoaderFactory = (
  options: {
    loadPlan(planId: string): Promise<LoadedPlan>
    loadReservation(reservationId: string): Promise<PlanCancellationReservation>
    setPlan(plan: LoadedPlan): void
    setReservation(reservation: PlanCancellationReservation): void
    setLoading(value: boolean): void
    setLoadError(message: string): void
    getLoadError(error: unknown): string
  },
) => {
  loadPlan(planId: string): Promise<boolean>
}

async function loadPlanPageContract(): Promise<{
  createPlanCancellationController: PlanCancellationControllerFactory
  canConfirmInspectionPlan(
    planConfirmable: boolean,
    reservationStatus: PlanCancellationReservation['status'] | undefined,
    isCancelling: boolean,
    actionsReady?: boolean,
  ): boolean
  canCancelInspectionPlan(
    reservationStatus: PlanCancellationReservation['status'] | undefined,
    actionsReady: boolean,
  ): boolean
  createPlanPageLoader: PlanPageLoaderFactory
}> {
  const parsed = parse(planPageSource, {
    filename: 'InspectionPlanConfirmationPage.vue',
  })
  expect(parsed.descriptor.script).not.toBeNull()
  if (!parsed.descriptor.script) {
    throw new Error('Plan cancellation controller script is unavailable')
  }
  const output = transpileModule(parsed.descriptor.script.content, {
    compilerOptions: {
      esModuleInterop: true,
      module: ModuleKind.CommonJS,
      target: ScriptTarget.ES2020,
    },
  }).outputText
  const testModule: {
    exports: {
      createPlanCancellationController?: PlanCancellationControllerFactory
      canConfirmInspectionPlan?: (
        planConfirmable: boolean,
        reservationStatus: PlanCancellationReservation['status'] | undefined,
        isCancelling: boolean,
        actionsReady?: boolean,
      ) => boolean
      canCancelInspectionPlan?: (
        reservationStatus: PlanCancellationReservation['status'] | undefined,
        actionsReady: boolean,
      ) => boolean
      createPlanPageLoader?: PlanPageLoaderFactory
    }
  } = { exports: {} }
  const evaluate = new Function('require', 'module', 'exports', output)
  evaluate(
    () => { throw new Error('The plan cancellation controller has no runtime dependencies') },
    testModule,
    testModule.exports,
  )
  const factory = testModule.exports.createPlanCancellationController
  const canConfirmInspectionPlan = testModule.exports.canConfirmInspectionPlan
  const canCancelInspectionPlan = testModule.exports.canCancelInspectionPlan
  const createPlanPageLoader = testModule.exports.createPlanPageLoader
  expect(factory).toBeTypeOf('function')
  expect(canConfirmInspectionPlan).toBeTypeOf('function')
  expect(canCancelInspectionPlan).toBeTypeOf('function')
  expect(createPlanPageLoader).toBeTypeOf('function')
  if (!factory || !canConfirmInspectionPlan
    || !canCancelInspectionPlan || !createPlanPageLoader) {
    throw new Error('Plan cancellation page contract is unavailable')
  }
  return {
    createPlanCancellationController: factory,
    canConfirmInspectionPlan,
    canCancelInspectionPlan,
    createPlanPageLoader,
  }
}

describe('F05 plan domain', () => {
  it('formats minor currency units without floating-point drift', () => {
    expect(formatPlanAmount(859770)).toBe('¥8,597.70')
    expect(formatPlanAmount(0)).toBe('¥0.00')
  })

  it('allows confirmation only for an unexpired published plan', () => {
    expect(isPlanConfirmable(plan(), new Date('2026-07-28T02:00:00Z'))).toBe(true)
    expect(isPlanConfirmable(
      { ...plan(), status: 'CONFIRMED' },
      new Date('2026-07-28T02:00:00Z'),
    )).toBe(false)
    expect(isPlanConfirmable(plan(), new Date('2026-07-30T02:00:00Z'))).toBe(false)
  })
})

describe('F05 plan API and navigation', () => {
  it('loads and confirms using stable IDs, idempotency and optimistic version', async () => {
    const calls: Array<{ url: string; method: string; headers?: Record<string, string> }> = []
    const http: BookingHttpClient = {
      request: async <T,>(input: {
        url: string
        method: 'GET' | 'POST'
        data?: unknown
        headers?: Record<string, string>
      }) => {
        calls.push(input)
        return (input.method === 'GET' ? plan() : {
          id: ORDER_ID,
          status: 'ARRANGING',
        }) as T
      },
      putFile: async () => ({ etag: 'unused' }),
    }
    const api = createInspectionPlanApi(http, {
      getAccessToken: () => 'customer-token',
    })

    await api.getPlan(PLAN_ID)
    await api.confirmPlan(PLAN_ID, 2, '70000000-0000-4000-8000-000000000001')

    expect(calls).toEqual([
      {
        url: `/api/v1/inspection/plans/${PLAN_ID}`,
        method: 'GET',
        headers: { Authorization: 'Bearer customer-token' },
      },
      {
        url: `/api/v1/inspection/plans/${PLAN_ID}/confirm`,
        method: 'POST',
        data: {
          agreementVersion: PLAN_AGREEMENT_VERSION,
          agreementAccepted: true,
        },
        headers: {
          Authorization: 'Bearer customer-token',
          'Idempotency-Key': '70000000-0000-4000-8000-000000000001',
          'If-Match': '"2"',
        },
      },
    ])
    expect(buildInspectionPlanConfirmationUrl(PLAN_ID)).toBe(
      `/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`,
    )
    expect(buildInspectionFulfillmentUrl(ORDER_ID)).toBe(
      `/pages/service/inspection/fulfillment?serviceOrderId=${ORDER_ID}`,
    )
  })

  it('registers INS-M-006 as a custom-navigation page', () => {
    const pages = JSON.parse(pagesSource) as {
      pages: Array<{ path: string; style?: Record<string, string> }>
    }
    expect(
      pages.pages.find((page) =>
        page.path === 'pages/service/inspection/plan-confirmation'),
    ).toMatchObject({ style: { navigationStyle: 'custom' } })
  })
})

describe('F05 plan page contract', () => {
  it('initializes the plan loader before App onLoad can run synchronously', () => {
    const loaderIndex = planPageSource.indexOf('const planLoader = createPlanPageLoader')
    const onLoadIndex = planPageSource.indexOf('onLoad((query: PlanRouteQuery')

    expect(loaderIndex).toBeGreaterThanOrEqual(0)
    expect(onLoadIndex).toBeGreaterThanOrEqual(0)
    expect(loaderIndex).toBeLessThan(onLoadIndex)
  })

  it('falls back to the shared order center instead of Home without valid history', () => {
    expect(planPageSource).toContain("buildInspectionOrderCenterUrl('ALL')")
    expect(planPageSource).not.toContain("uni.reLaunch({ url: '/pages/home/index' })")
  })

  it('matches the approved pending-confirmation structure and real DTO actions', () => {
    expect(planPageSource).toContain('<BookingProgress :active="2"')
    expect(planPageSource).toContain('巡检方案已生成')
    expect(planPageSource).toContain('plan-confirmation__route-preview')
    expect(planPageSource).toContain('报价明细')
    expect(planPageSource).toContain('最终报价')
    expect(planPageSource).toContain('申请调整')
    expect(planPageSource).toContain('确认方案')
    expect(planPageSource).toContain("isConfirmed ? 'checkmarkempty' : 'info-filled'")
    expect(planPageSource).toContain('plan-confirmation__status-icon--confirmed')
    expect(planPageSource).toContain('loadPlan: inspectionPlanApi.getPlan')
    expect(planPageSource).toContain('loadReservation: inspectionBookingApi.getReservation')
    expect(planPageSource).toContain('inspectionPlanApi.confirmPlan(')
    expect(planPageSource).toContain('env(safe-area-inset-bottom)')
    expect(planPageSource).not.toContain('立即支付')
  })

  it('allows cancellation only before plan confirmation and returns to cancelled detail', () => {
    expect(planPageSource).toContain('取消订单')
    expect(planPageSource).toContain('requestInspectionOrderCancellation')
    expect(planPageSource).toContain('confirmInspectionOrderCancellation')
    expect(planPageSource).toContain('buildInspectionBookingSubmittedUrl')
    expect(planPageSource).toContain('v-if="!isConfirmed && canCancel"')
    expect(planPageSource).not.toContain('退款成功')
  })

  it('disables confirmation unless the reservation is still awaiting plan confirmation', async () => {
    const {
      canCancelInspectionPlan,
      canConfirmInspectionPlan,
    } = await loadPlanPageContract()

    expect(canConfirmInspectionPlan(true, 'PLAN_PUBLISHED', false)).toBe(true)
    expect(canConfirmInspectionPlan(true, 'SUBMITTED', false)).toBe(false)
    expect(canConfirmInspectionPlan(true, 'CANCELLED', false)).toBe(false)
    expect(canConfirmInspectionPlan(true, 'PLAN_PUBLISHED', true)).toBe(false)
    expect(canConfirmInspectionPlan(true, 'PLAN_PUBLISHED', false, false)).toBe(false)
    expect(canCancelInspectionPlan('PLAN_PUBLISHED', true)).toBe(true)
    expect(canCancelInspectionPlan('PLAN_PUBLISHED', false)).toBe(false)
  })

  it('cancels once and redirects only for the server-returned cancelled reservation', async () => {
    const { createPlanCancellationController } = await loadPlanPageContract()
    const initial = planReservation()
    const serverCancelled = planReservation({ status: 'CANCELLED', marker: 'server' })
    let current = initial
    let cancelling = false
    let cancellationCalls = 0
    let resolveCancellation: ((result: PlanCancellationResult) => void) | undefined
    const redirects: string[] = []
    const controller = createPlanCancellationController({
      getReservation: () => current,
      setReservation: (reservation) => { current = reservation },
      isPlanConfirmed: () => false,
      isCancelling: () => cancelling,
      setCancelling: (value) => { cancelling = value },
      requestCancellation: () => {
        cancellationCalls += 1
        return new Promise((resolve) => { resolveCancellation = resolve })
      },
      refreshReservation: async () => current,
      reloadPlan: async () => true,
      lockActions: () => undefined,
      redirectToCancelledDetail: (reservationId) => redirects.push(reservationId),
      showToast: () => undefined,
    })

    const first = controller.cancelOrder()
    const second = controller.cancelOrder()
    expect(cancellationCalls).toBe(1)
    expect(cancelling).toBe(true)
    resolveCancellation?.({ kind: 'cancelled', reservation: serverCancelled })
    await Promise.all([first, second])

    expect(current).toBe(serverCancelled)
    expect(redirects).toEqual([serverCancelled.id])
    expect(cancelling).toBe(false)
  })

  it('refreshes a conflict and never redirects for a non-cancelled success payload', async () => {
    const { createPlanCancellationController } = await loadPlanPageContract()
    let current = planReservation()
    let cancelling = false
    const redirects: string[] = []
    const toasts: string[] = []
    let reloads = 0
    const results: PlanCancellationResult[] = [
      { kind: 'cancelled', reservation: planReservation({ marker: 'unexpected' }) },
      { kind: 'refresh-required' },
    ]
    const controller = createPlanCancellationController({
      getReservation: () => current,
      setReservation: (reservation) => { current = reservation },
      isPlanConfirmed: () => false,
      isCancelling: () => cancelling,
      setCancelling: (value) => { cancelling = value },
      requestCancellation: async () => results.shift() ?? { kind: 'dismissed' },
      refreshReservation: async () => planReservation({ marker: 'refreshed' }),
      reloadPlan: async () => { reloads += 1; current = planReservation({ marker: 'refreshed' }); return true },
      lockActions: () => undefined,
      redirectToCancelledDetail: (reservationId) => redirects.push(reservationId),
      showToast: (message) => toasts.push(message),
    })

    await controller.cancelOrder()
    await controller.cancelOrder()

    expect(redirects).toEqual([])
    expect(current.marker).toBe('refreshed')
    expect(reloads).toBe(1)
    expect(toasts).toEqual(['订单状态异常，请刷新后重试', '订单状态已更新'])
    expect(cancelling).toBe(false)
  })

  it('does not cancel after confirmation and releases busy state after an exception', async () => {
    const { createPlanCancellationController } = await loadPlanPageContract()
    let confirmed = true
    let cancelling = false
    let requests = 0
    const toasts: string[] = []
    const controller = createPlanCancellationController({
      getReservation: () => planReservation(),
      setReservation: () => undefined,
      isPlanConfirmed: () => confirmed,
      isCancelling: () => cancelling,
      setCancelling: (value) => { cancelling = value },
      requestCancellation: async () => {
        requests += 1
        throw new Error('NETWORK_ERROR')
      },
      refreshReservation: async () => planReservation(),
      reloadPlan: async () => true,
      lockActions: () => undefined,
      redirectToCancelledDetail: () => undefined,
      showToast: (message) => toasts.push(message),
    })

    await controller.cancelOrder()
    confirmed = false
    await controller.cancelOrder()

    expect(requests).toBe(1)
    expect(cancelling).toBe(false)
    expect(toasts).toEqual(['取消失败，请检查网络后重试'])
  })

  it('locks old actions when either conflict refresh step fails', async () => {
    const {
      canCancelInspectionPlan,
      canConfirmInspectionPlan,
      createPlanCancellationController,
    } = await loadPlanPageContract()
    const scenarios = [
      'reservation-reject',
      'plan-false',
      'plan-reject',
    ] as const

    for (const scenario of scenarios) {
      let cancelling = false
      let lockedError = ''
      const redirects: string[] = []
      const toasts: string[] = []
      const controller = createPlanCancellationController({
        getReservation: () => planReservation(),
        setReservation: () => undefined,
        isPlanConfirmed: () => false,
        isCancelling: () => cancelling,
        setCancelling: (value) => { cancelling = value },
        requestCancellation: async () => ({ kind: 'refresh-required' }),
        refreshReservation: async () => {
          if (scenario === 'reservation-reject') throw new Error('GET_RESERVATION_FAILED')
          return planReservation({ marker: 'server-reservation' })
        },
        reloadPlan: async () => {
          if (scenario === 'plan-reject') throw new Error('GET_PLAN_FAILED')
          return scenario !== 'plan-false'
        },
        lockActions: (message) => { lockedError = message },
        redirectToCancelledDetail: (reservationId) => redirects.push(reservationId),
        showToast: (message) => toasts.push(message),
      })

      await controller.cancelOrder()

      expect(lockedError).toBe('状态刷新失败，请重试')
      expect(redirects).toEqual([])
      expect(toasts).not.toContain('订单状态已更新')
      expect(canConfirmInspectionPlan(true, 'PLAN_PUBLISHED', false, !lockedError)).toBe(false)
      expect(canCancelInspectionPlan('PLAN_PUBLISHED', !lockedError)).toBe(false)
      expect(cancelling).toBe(false)
    }
  })

  it('keeps only the latest complete plan and reservation load', async () => {
    const { createPlanPageLoader } = await loadPlanPageContract()
    const plans = new Map<string, ReturnType<typeof deferred<LoadedPlan>>>()
    const reservations = new Map<
      string,
      ReturnType<typeof deferred<PlanCancellationReservation>>
    >()
    for (const id of ['old-plan', 'latest-plan', 'stale-plan', 'newest-plan']) {
      plans.set(id, deferred<LoadedPlan>())
      reservations.set(`${id}-reservation`, deferred<PlanCancellationReservation>())
    }
    let currentPlan: LoadedPlan | null = null
    let currentReservation: PlanCancellationReservation | null = null
    let loading = false
    let loadError = '旧错误'
    const loader = createPlanPageLoader({
      loadPlan: (planId) => plans.get(planId)?.promise
        ?? Promise.reject(new Error('PLAN_DEFERRED_MISSING')),
      loadReservation: (reservationId) => reservations.get(reservationId)?.promise
        ?? Promise.reject(new Error('RESERVATION_DEFERRED_MISSING')),
      setPlan: (value) => { currentPlan = value },
      setReservation: (value) => { currentReservation = value },
      setLoading: (value) => { loading = value },
      setLoadError: (value) => { loadError = value },
      getLoadError: () => '方案加载失败',
    })

    const oldLoad = loader.loadPlan('old-plan')
    plans.get('old-plan')?.resolve({
      reservationId: 'old-plan-reservation',
      marker: 'old-plan',
    })
    await Promise.resolve()
    const latestLoad = loader.loadPlan('latest-plan')
    plans.get('latest-plan')?.resolve({
      reservationId: 'latest-plan-reservation',
      marker: 'latest-plan',
    })
    await Promise.resolve()
    reservations.get('old-plan-reservation')?.resolve(
      planReservation({ marker: 'old-reservation' }),
    )
    await oldLoad

    expect(loading).toBe(true)
    expect(currentPlan).toBeNull()
    expect(loadError).toBe('旧错误')

    reservations.get('latest-plan-reservation')?.resolve(
      planReservation({ marker: 'latest-reservation' }),
    )
    await latestLoad
    expect((currentPlan as LoadedPlan | null)?.marker).toBe('latest-plan')
    expect((currentReservation as PlanCancellationReservation | null)?.marker)
      .toBe('latest-reservation')
    expect(loadError).toBe('')
    expect(loading).toBe(false)

    const stalePlanLoad = loader.loadPlan('stale-plan')
    const newestLoad = loader.loadPlan('newest-plan')
    plans.get('newest-plan')?.resolve({
      reservationId: 'newest-plan-reservation',
      marker: 'newest-plan',
    })
    await Promise.resolve()
    reservations.get('newest-plan-reservation')?.resolve(
      planReservation({ marker: 'newest-reservation' }),
    )
    await newestLoad
    plans.get('stale-plan')?.resolve({
      reservationId: 'stale-plan-reservation',
      marker: 'stale-plan',
    })
    await Promise.resolve()
    reservations.get('stale-plan-reservation')?.resolve(
      planReservation({ marker: 'stale-reservation' }),
    )
    await stalePlanLoad

    expect((currentPlan as LoadedPlan | null)?.marker).toBe('newest-plan')
    expect((currentReservation as PlanCancellationReservation | null)?.marker)
      .toBe('newest-reservation')
    expect(loadError).toBe('')
    expect(loading).toBe(false)
  })
})

function planReservation(
  overrides: Partial<PlanCancellationReservation> = {},
): PlanCancellationReservation {
  return {
    id: '40000000-0000-4000-8000-000000000001',
    status: 'PLAN_PUBLISHED',
    marker: 'initial',
    ...overrides,
  }
}

function plan(): InspectionPlan {
  return {
    id: PLAN_ID,
    planNo: 'FA202607270001',
    reservationId: '40000000-0000-4000-8000-000000000001',
    providerId: '30000000-0000-4000-8000-000000000001',
    status: 'PUBLISHED',
    planVersion: 1,
    validUntil: '2026-07-29T02:00:00Z',
    serviceDate: '2026-08-02',
    summary: '无人机采集、专业人员复核',
    planItems: [{
      code: 'POWER_LINE',
      name: '巡检服务',
      quantity: 12,
      unit: '公里',
      unitPriceMinor: 69900,
      lineAmountMinor: 838800,
    }],
    deliverables: [
      { code: 'HD_MEDIA', label: '高清巡检影像' },
      { code: 'DEFECT_LIST', label: '缺陷清单' },
      { code: 'POINT_MARKS', label: '点位标注' },
      { code: 'REPORT', label: '巡检报告' },
    ],
    totalAmountMinor: 838800,
    currency: 'CNY',
    publishedAt: '2026-07-28T02:00:00Z',
    confirmedAt: null,
    serviceOrderId: null,
    version: 2,
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
  return { promise, resolve: resolvePromise }
}
