// “我的” (2026-11-14 boards Mine and MineGuest). The page host reads every number from an
// existing API; a number it cannot read is left out and the page shows no badge or value for it.

/**
 * The five order stages of the board, across businesses. In a preview build each stage opens 我的订单
 * (features/orders, R6-1) on its status chip; a public build opens the existing list that holds
 * it: activity enrolments (待参加) and inspection orders (待验收).
 */
export type MineOrderStage = 'TO_PAY' | 'TO_SHIP' | 'TO_ATTEND' | 'TO_ACCEPT' | 'REFUND'

// Line icons copied from the board Mine (`orders` in renderVals).
export const MINE_ORDER_STAGES: ReadonlyArray<{ code: MineOrderStage; label: string; d: string }> = [
  { code: 'TO_PAY', label: '待付款', d: 'M4 7h16v12H4zM4 11h16M8 15h3' },
  { code: 'TO_SHIP', label: '待发货/履约', d: 'M3 7h11v9H3zM14 10h4l3 3v3h-7M7 19a1.5 1.5 0 1 0 0-.1M17 19a1.5 1.5 0 1 0 0-.1' },
  { code: 'TO_ATTEND', label: '待参加', d: 'M5 5h14v15H5zM5 9h14M9 3v4M15 3v4' },
  { code: 'TO_ACCEPT', label: '待验收', d: 'M6 4h12v16H6zM9 12l2 2 4-4' },
  { code: 'REFUND', label: '退款/售后', d: 'M4 12a8 8 0 1 0 3-6.2M4 4v4h4' },
]

// 我的参与 and the rows under it (board `joins` and `rows`), keyed by entry code.
export const MINE_ENTRY_ICONS: Record<string, string> = {
  ACTIVITY_ENROLLMENTS: 'M6 21V4M6 4h11l-2 4 2 4H6',
  VENUE_BOOKINGS: 'M5 5h14v15H5zM5 9h14M9 3v4M15 3v4M9 14l2 2 4-4',
  EXPERIENCE_RECORDS: 'M6 8h12v10H6zM9 12h.01M15 12h.01M12 4v4M10 15h4M4 12h2M18 12h2',
  RENTAL_ORDERS: 'M4 8h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zM9 8V5.5A1.5 1.5 0 0 1 10.5 4h3A1.5 1.5 0 0 1 15 5.5V8M3 13h18M10.5 13v2h3v-2',
  ACTIVITY_PARTICIPANTS: 'M9 11a3 3 0 1 0 0-6a3 3 0 1 0 0 6zM3 20c.8-3 3-5 6-5s5.2 2 6 5M17 11a2.5 2.5 0 1 0 0-5M21 19c-.4-2-1.6-3.4-3.5-4',
  DEMANDS: 'M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h5',
  FAVORITES: 'M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.5A4 4 0 0 1 19 10c0 5.5-7 10-7 10z',
  HELP: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM9.5 9.5a2.5 2.5 0 1 1 3.6 2.2c-.7.4-1.1.9-1.1 1.8M12 16.5h.01',
  SUPPORT: 'M4 14v-2a8 8 0 1 1 16 0v2M4 14h3v5H5a1 1 0 0 1-1-1zM20 14h-3v5h2a1 1 0 0 0 1-1zM17 19c0 1.5-2 2-5 2',
  ABOUT: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01',
  STAFF_WORK_MODE: 'M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4M4 12h16',
}

export const MINE_GEAR_ICON = 'M12 15a3 3 0 1 0 0-6a3 3 0 1 0 0 6zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z'

export interface PlatformMineUser {
  displayName: string
  avatarUrl?: string
  /** Shown only when the account really has one; there is no level system yet. */
  levelName?: string
  locationText: string
  /** The account's organization, when it belongs to one. */
  certificationLabel?: string
}

export interface PlatformMineAccount {
  displayName: string
  organizationName: string | null
}

// Until the profile has been read (or when it cannot be read) the card only says the account is
// signed in; it never invents a name, a level or a certification.
export function resolveAuthenticatedMineUser(
  authenticated: boolean,
  locationText?: string,
  account?: PlatformMineAccount | null,
): PlatformMineUser | undefined {
  if (!authenticated) return undefined
  const organizationName = account?.organizationName?.trim()
  return {
    displayName: account?.displayName.trim() || '已登录',
    locationText: locationText?.trim() || '未设置所在地区',
    ...(organizationName ? { certificationLabel: organizationName } : {}),
  }
}

/** “常用城市 东营”: the board writes the city without 市. */
export function formatMineCity(locationText: string): string {
  const name = locationText.trim()
  if (!name || name === '未设置所在地区') return '未设置常用城市'
  return `常用城市 ${name.endsWith('市') && name.length > 2 ? name.slice(0, -1) : name}`
}

// The board's first current-item card: an enrolment that is paid (or free) and still to come.
export interface MineEnrollmentCard {
  enrollmentId: string
  title: string
  meta: string
}

// The second: an inspection service order with a report waiting for acceptance.
export interface MineAcceptanceCard {
  id: string
  title: string
  meta: string
}

// An entry that opens a page of another feature. The page host decides which ones exist and
// where they lead; this page only draws them.
export interface PlatformMineLinkEntry {
  code: string
  title: string
  /** The grey value on the right of a row (参与人 “3 人”, 需求 “1 份已选定”), only when read. */
  valueText?: string
}

export interface PlatformMineContent {
  user?: PlatformMineUser
  /** Only the stages whose number an existing API gives; the others draw no badge. */
  orderCounts: Partial<Record<MineOrderStage, number>>
  enrollmentCard?: MineEnrollmentCard
  acceptanceCard?: MineAcceptanceCard
  // 我的参与: what the user has signed up for.
  joinEntries: PlatformMineLinkEntry[]
  // Plain rows under it, such as 参与人管理.
  linkRows: PlatformMineLinkEntry[]
}

export type PlatformMineViewState =
  | { kind: 'loading' }
  | { kind: 'ready'; content: PlatformMineContent }
  | { kind: 'error'; message: string }

export const formatMineBadge = (count: number): string => (count > 99 ? '99+' : String(count))

// The inspection order center (features/inspection/presentation/orders) still describes its rows
// with the shapes below; the Mine page itself no longer lists inspection orders.
export type InspectionMineStatus =
  | 'AWAITING_PLAN'
  | 'AWAITING_CONFIRMATION'
  | 'IN_PROGRESS'
  | 'AWAITING_ACCEPTANCE'
  | 'COMPLETED'

export type InspectionMineOrderStatus = InspectionMineStatus | 'CANCELLED'

export const INSPECTION_STATUS_ITEMS: ReadonlyArray<{ code: InspectionMineStatus; label: string }> = [
  { code: 'AWAITING_PLAN', label: '待方案' },
  { code: 'AWAITING_CONFIRMATION', label: '待确认' },
  { code: 'IN_PROGRESS', label: '进行中' },
  { code: 'AWAITING_ACCEPTANCE', label: '待验收' },
  { code: 'COMPLETED', label: '已完成' },
]

export type PlatformAmountLabel = '应付' | '报价'

export const isPlatformAmountLabel = (value: string): value is PlatformAmountLabel =>
  value === '应付' || value === '报价'

export interface PlatformMineOrder {
  id: string
  title: string
  status: InspectionMineOrderStatus
  statusText?: string
  imageUrl: string
  locationText: string
  scheduledAtText: string
  orderNumber: string
  amount: { label: PlatformAmountLabel; text: string }
}

export const getInspectionStatusLabel = (status: InspectionMineOrderStatus): string =>
  status === 'CANCELLED'
    ? '已取消'
    : INSPECTION_STATUS_ITEMS.find((item) => item.code === status)?.label || status
