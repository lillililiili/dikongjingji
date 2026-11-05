export type InspectionMineStatus =
  | 'AWAITING_PLAN'
  | 'AWAITING_CONFIRMATION'
  | 'IN_PROGRESS'
  | 'AWAITING_ACCEPTANCE'
  | 'COMPLETED'

export type InspectionMineOrderStatus =
  | InspectionMineStatus
  | 'CANCELLED'

export const INSPECTION_STATUS_ITEMS: ReadonlyArray<{
  code: InspectionMineStatus
  label: string
}> = [
  { code: 'AWAITING_PLAN', label: '待方案' },
  { code: 'AWAITING_CONFIRMATION', label: '待确认' },
  { code: 'IN_PROGRESS', label: '进行中' },
  { code: 'AWAITING_ACCEPTANCE', label: '待验收' },
  { code: 'COMPLETED', label: '已完成' },
]

export type PlatformAmountLabel = '应付' | '报价'

export const isPlatformAmountLabel = (value: string): value is PlatformAmountLabel =>
  value === '应付' || value === '报价'

export interface PlatformMineUser {
  displayName: string
  avatarUrl?: string
  levelName: string
  locationText: string
  certificationLabel: string
}

export function resolveAuthenticatedMineUser(
  authenticated: boolean,
  locationText?: string,
): PlatformMineUser | undefined {
  if (!authenticated) return undefined
  return {
    displayName: '已登录用户',
    levelName: 'Lv.0',
    locationText: locationText?.trim() || '未设置所在地区',
    certificationLabel: '身份待完善',
  }
}

export interface PlatformMineOrder {
  id: string
  title: string
  status: InspectionMineOrderStatus
  statusText?: string
  imageUrl: string
  locationText: string
  scheduledAtText: string
  orderNumber: string
  amount: {
    label: PlatformAmountLabel
    text: string
  }
}

export interface PlatformMineFeatureEntry {
  code: string
  title: string
  valueText?: string
  imageUrl: string
  availability: PlatformAvailability
}

export interface PlatformMineContent {
  user?: PlatformMineUser
  statusCounts?: Record<InspectionMineStatus, number>
  recentOrders: PlatformMineOrder[]
  featureEntries: PlatformMineFeatureEntry[]
}

export type PlatformMineInput =
  | { status: 'loading' }
  | { status: 'ready'; content: PlatformMineContent }
  | { status: 'error'; message?: string }

export type PlatformMineViewState =
  | { kind: 'loading' }
  | { kind: 'ready'; content: PlatformMineContent }
  | { kind: 'empty' }
  | { kind: 'error'; message: string }

export const resolvePlatformMineState = (input: PlatformMineInput): PlatformMineViewState => {
  if (input.status === 'loading') return { kind: 'loading' }
  if (input.status === 'error') {
    return {
      kind: 'error',
      message: input.message?.trim() || '个人中心加载失败，请稍后重试',
    }
  }

  const hasContent = Boolean(
    input.content.user
      || input.content.recentOrders.length
      || input.content.featureEntries.length,
  )
  return hasContent ? { kind: 'ready', content: input.content } : { kind: 'empty' }
}

export const getInspectionStatusLabel = (status: InspectionMineOrderStatus): string =>
  status === 'CANCELLED'
    ? '已取消'
    : INSPECTION_STATUS_ITEMS.find((item) => item.code === status)?.label || status
import type { PlatformAvailability } from '../shared/platform-availability'
