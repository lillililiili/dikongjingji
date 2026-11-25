import { resolveBusinessMediaUrl } from '../../../../shared/http/business-media'
import type {
  InspectionOrderFilter,
  InspectionOrderPage,
  InspectionOrderStage,
  InspectionOrderSummary,
} from '../../domain/service-flow/order-center'
import type { PlatformMineOrder } from '../platform/mine/platform-mine'
import { formatMonthDay } from '../shared/service-flow-format'

export interface InspectionOrderCenterState {
  selectedStage: InspectionOrderFilter
  orders: InspectionOrderSummary[]
  page: number
  hasMore: boolean
  isLoading: boolean
  isLoadingMore: boolean
  loadError: string
}

export type InspectionOrderListState = 'loading' | 'error' | 'empty' | 'ready'

export interface InspectionOrderCenterDependencies {
  api: {
    list(input: {
      stage: InspectionOrderFilter
      page: number
      size: number
    }): Promise<InspectionOrderPage>
  }
  resolveDestination?(order: InspectionOrderSummary): string | null
  navigateTo?(url: string): void
  showToast?(message: string): void
  navigateBack?(): Promise<boolean>
  switchTab?(url: string): void
}

export const INSPECTION_ORDER_FILTER_ITEMS = [
  { code: 'ALL', label: '全部' },
  { code: 'AWAITING_PLAN', label: '待方案' },
  { code: 'AWAITING_CONFIRMATION', label: '待确认' },
  { code: 'IN_PROGRESS', label: '进行中' },
  { code: 'AWAITING_ACCEPTANCE', label: '待验收' },
  { code: 'COMPLETED', label: '已完成' },
] as const

export function normalizeInspectionOrderFilter(value?: string): InspectionOrderFilter {
  return INSPECTION_ORDER_FILTER_ITEMS.some((item) => item.code === value)
    ? value as InspectionOrderFilter
    : 'ALL'
}

export function shouldApplyInspectionOrderResponse(
  responseVersion: number,
  currentVersion: number,
): boolean {
  return responseVersion === currentVersion
}

export function mergeUniqueInspectionOrders(
  existing: InspectionOrderSummary[],
  incoming: InspectionOrderSummary[],
): InspectionOrderSummary[] {
  const known = new Set(existing.map((order) => order.reservationId))
  return [
    ...existing,
    ...incoming.filter((order) => {
      if (known.has(order.reservationId)) return false
      known.add(order.reservationId)
      return true
    }),
  ]
}

export function createInspectionOrderCenterState(): InspectionOrderCenterState {
  return {
    selectedStage: 'ALL',
    orders: [],
    page: 1,
    hasMore: false,
    isLoading: true,
    isLoadingMore: false,
    loadError: '',
  }
}

export function getInspectionOrderListState(
  state: InspectionOrderCenterState,
): InspectionOrderListState {
  if (state.isLoading) return 'loading'
  if (state.loadError) return 'error'
  return state.orders.length ? 'ready' : 'empty'
}

export function createInspectionOrderCenterController(
  dependencies: InspectionOrderCenterDependencies,
  state: InspectionOrderCenterState,
) {
  let requestVersion = 0

  async function reload(): Promise<void> {
    const version = ++requestVersion
    state.isLoading = true
    state.isLoadingMore = false
    state.loadError = ''
    try {
      const response = await dependencies.api.list({
        stage: state.selectedStage,
        page: 1,
        size: 20,
      })
      if (!shouldApplyInspectionOrderResponse(version, requestVersion)) return
      state.orders = mergeUniqueInspectionOrders([], response.items)
      state.page = response.page
      state.hasMore = response.hasMore
    } catch (error) {
      if (!shouldApplyInspectionOrderResponse(version, requestVersion)) return
      state.loadError = error instanceof Error ? error.message : '订单加载失败'
    } finally {
      if (shouldApplyInspectionOrderResponse(version, requestVersion)) state.isLoading = false
    }
  }

  async function loadMore(): Promise<void> {
    if (state.isLoading || state.isLoadingMore || !state.hasMore) return
    const version = requestVersion
    state.isLoadingMore = true
    try {
      const response = await dependencies.api.list({
        stage: state.selectedStage,
        page: state.page + 1,
        size: 20,
      })
      if (!shouldApplyInspectionOrderResponse(version, requestVersion)) return
      state.orders = mergeUniqueInspectionOrders(state.orders, response.items)
      state.page = response.page
      state.hasMore = response.hasMore
    } catch (error) {
      if (!shouldApplyInspectionOrderResponse(version, requestVersion)) return
      dependencies.showToast?.(error instanceof Error ? error.message : '订单加载失败')
    } finally {
      if (shouldApplyInspectionOrderResponse(version, requestVersion)) state.isLoadingMore = false
    }
  }

  async function selectStage(stage: InspectionOrderFilter): Promise<void> {
    if (state.selectedStage === stage) return
    state.selectedStage = stage
    await reload()
  }

  async function openOrder(order: InspectionOrderSummary): Promise<void> {
    const destination = dependencies.resolveDestination?.(order) ?? null
    if (!destination) {
      await reload()
      dependencies.showToast?.('订单状态已更新，请重新查看')
      return
    }
    dependencies.navigateTo?.(destination)
  }

  async function returnMine(): Promise<void> {
    let returned = false
    try {
      returned = await dependencies.navigateBack?.() ?? false
    } catch {
      returned = false
    }
    if (!returned) dependencies.switchTab?.('/pages/mine/index')
  }

  return {
    reload,
    loadMore,
    selectStage,
    openOrder,
    returnMine,
  }
}

export function getInspectionOrderActionLabel(stage: InspectionOrderStage): string {
  return {
    AWAITING_PLAN: '查看进度',
    AWAITING_CONFIRMATION: '确认方案',
    IN_PROGRESS: '查看进度',
    AWAITING_ACCEPTANCE: '去验收',
    COMPLETED: '查看履约',
    CANCELLED: '查看订单',
  }[stage]
}

// Board OrderList: the status at the right of the card head, coloured by stage.
export function getInspectionOrderStageColour(stage: InspectionOrderStage): string {
  return {
    AWAITING_PLAN: '#ba470b',
    AWAITING_CONFIRMATION: '#ba470b',
    IN_PROGRESS: '#1d63c4',
    AWAITING_ACCEPTANCE: '#1d63c4',
    COMPLETED: '#11804a',
    CANCELLED: '#738096',
  }[stage]
}

/**
 * Board OrderList, the service cards: only 去验收 is the solid orange button; it comes with a grey
 * outlined 查看履约 (the visits) beside it. Every other stage shows its one button outlined.
 */
export function isInspectionOrderActionUrgent(stage: InspectionOrderStage): boolean {
  return stage === 'AWAITING_ACCEPTANCE'
}

export function hasInspectionOrderFulfillmentAction(order: InspectionOrderSummary): boolean {
  return order.stage === 'AWAITING_ACCEPTANCE' && Boolean(order.serviceOrderId) && Boolean(order.pendingAcceptanceVisitId)
}

export function getInspectionOrderStageLabel(stage: InspectionOrderStage): string {
  return {
    AWAITING_PLAN: '待方案',
    AWAITING_CONFIRMATION: '待确认',
    IN_PROGRESS: '进行中',
    AWAITING_ACCEPTANCE: '待验收',
    COMPLETED: '已完成',
    CANCELLED: '已取消',
  }[stage]
}

function hasSeveralVisits(order: InspectionOrderSummary): boolean {
  return (order.visitCount ?? 0) > 1
}

/**
 * S4-1: a node to pay now, where online payment is open (preview builds): the card's 去付款 and its
 * status 待付首付 / 第 2 次待付款 / 待付款.
 */
export function getInspectionOrderPayment(
  order: InspectionOrderSummary,
  paymentOpen: boolean,
): { nodeNo: number; stageText: string } | null {
  if (!paymentOpen || !order.serviceOrderId || !order.paymentDueNodeNo) return null
  const stageText = order.paymentDueNodeType === 'DOWN_PAYMENT'
    ? '待付首付'
    : hasSeveralVisits(order) && order.paymentDueVisitNo ? `第 ${order.paymentDueVisitNo} 次待付款` : '待付款'
  return { nodeNo: order.paymentDueNodeNo, stageText }
}

export function getInspectionOrderStageText(order: InspectionOrderSummary): string {
  return order.stage === 'AWAITING_ACCEPTANCE'
    && order.pendingAcceptanceVisitNo
    && hasSeveralVisits(order)
    ? `第 ${order.pendingAcceptanceVisitNo} 次待验收`
    : getInspectionOrderStageLabel(order.stage)
}

export function getInspectionOrderScheduleText(order: InspectionOrderSummary): string {
  if (!hasSeveralVisits(order)) return order.preferredServiceDate
  const progress = `${order.visitCount} 次中已验收 ${order.acceptedVisitCount ?? 0} 次`
  return order.nextVisitDate
    ? `${progress} · 下次 ${formatMonthDay(order.nextVisitDate)}`
    : progress
}

export function formatInspectionOrderAmount(amountMinor: number | undefined): string {
  if (amountMinor == null) return '待报价'
  return `¥${(amountMinor / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

export function toPlatformMineOrder(order: InspectionOrderSummary): PlatformMineOrder {
  return {
    id: order.reservationId,
    title: order.serviceTitle,
    status: order.stage,
    statusText: getInspectionOrderStageText(order),
    imageUrl: (resolveBusinessMediaUrl(order.serviceCoverUrl) ?? ''),
    locationText: order.locationSummary,
    scheduledAtText: getInspectionOrderScheduleText(order),
    orderNumber: order.reservationNo,
    amount: {
      label: '报价',
      text: formatInspectionOrderAmount(order.quoteAmountMinor),
    },
  }
}
