export type InspectionMineStatus =
  | 'AWAITING_PLAN'
  | 'AWAITING_CONFIRMATION'
  | 'IN_PROGRESS'
  | 'AWAITING_ACCEPTANCE'
  | 'COMPLETED'

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
  levelName?: string
  locationText?: string
  certificationLabel?: string
}

export interface PlatformMineOrder {
  id: string
  title: string
  status: InspectionMineStatus
  imageUrl: string
  locationText?: string
  scheduledAtText?: string
  orderNumber?: string
  amount?: {
    label: PlatformAmountLabel
    text: string
  }
}

export interface PlatformMineFeatureEntry {
  code: string
  title: string
  valueText?: string
  availability: 'AVAILABLE' | 'UNAVAILABLE'
}

export interface PlatformMineContent {
  user?: PlatformMineUser
  statusCounts: Partial<Record<InspectionMineStatus, number>>
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

export const getInspectionStatusLabel = (status: InspectionMineStatus): string =>
  INSPECTION_STATUS_ITEMS.find((item) => item.code === status)?.label || status
