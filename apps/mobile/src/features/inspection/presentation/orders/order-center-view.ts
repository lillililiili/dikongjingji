import type {
  InspectionOrderFilter,
  InspectionOrderPage,
  InspectionOrderStage,
  InspectionOrderSummary,
} from '../../domain/service-flow/order-center'
import type { PlatformMineOrder } from '../platform/mine/platform-mine'

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
    AWAITING_ACCEPTANCE: '查看报告',
    COMPLETED: '查看报告',
    CANCELLED: '查看订单',
  }[stage]
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
    imageUrl: order.serviceCoverUrl || '/static/images/inspection/inspection-hero-v1.png',
    locationText: order.locationSummary,
    scheduledAtText: order.preferredServiceDate,
    orderNumber: order.reservationNo,
    amount: {
      label: '报价',
      text: formatInspectionOrderAmount(order.quoteAmountMinor),
    },
  }
}
