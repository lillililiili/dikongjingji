// 我的 → 我的订单 (2026-11-14 board OrderList; plan 06 §3 R6-1): one list of every kind of order
// the account has, with four business tabs and five status chips. Only the tab and the chip travel
// in the URL; every card is read from the servers again.
export const MY_ORDERS_PATH = '/pages/mine/orders'

export const MY_ORDER_TABS = ['all', 'goods', 'service', 'enroll'] as const
export type MyOrderTab = (typeof MY_ORDER_TABS)[number]

export const MY_ORDER_TAB_LABELS: Record<MyOrderTab, string> = {
  all: '全部',
  goods: '商品',
  service: '服务',
  enroll: '报名',
}

export const MY_ORDER_CHIPS = ['pay', 'ship', 'attend', 'accept', 'refund'] as const
export type MyOrderChip = (typeof MY_ORDER_CHIPS)[number]

export const MY_ORDER_CHIP_LABELS: Record<MyOrderChip, string> = {
  pay: '待付款',
  ship: '待发货/履约',
  attend: '待参加',
  accept: '待验收',
  refund: '退款/售后',
}

export function parseMyOrderTab(value: unknown): MyOrderTab {
  return typeof value === 'string' && (MY_ORDER_TABS as readonly string[]).includes(value)
    ? value as MyOrderTab
    : 'all'
}

// No chip (null) lists every status.
export function parseMyOrderChip(value: unknown): MyOrderChip | null {
  return typeof value === 'string' && (MY_ORDER_CHIPS as readonly string[]).includes(value)
    ? value as MyOrderChip
    : null
}

export function buildMyOrdersUrl(filter: { tab?: MyOrderTab; chip?: MyOrderChip | null } = {}): string {
  const query = [
    filter.tab && filter.tab !== 'all' ? `tab=${filter.tab}` : '',
    filter.chip ? `status=${filter.chip}` : '',
  ].filter(Boolean)
  return query.length > 0 ? `${MY_ORDERS_PATH}?${query.join('&')}` : MY_ORDERS_PATH
}
