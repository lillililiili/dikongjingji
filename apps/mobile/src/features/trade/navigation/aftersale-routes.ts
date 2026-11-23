import { AFTERSALE_TABS, type AftersaleTab } from '../domain/aftersale'

// 商品售后的三个页面（画板 AftersaleApply、AftersaleDetail / AftersaleFlow、AftersaleList）。
// 只传订单号或售后单号这种稳定 ID，页面每次从服务端重新读。
export const AFTERSALE_APPLY_PATH = '/pages/trade/aftersale-apply'
export const AFTERSALE_DETAIL_PATH = '/pages/trade/aftersale'
export const AFTERSALES_PATH = '/pages/mine/aftersales'

export function buildAftersaleApplyUrl(orderId: string): string {
  return `${AFTERSALE_APPLY_PATH}?orderId=${encodeURIComponent(orderId)}`
}

// waybill=1：列表卡片的“填写运单”，进详情就打开运单弹层。
export function buildAftersaleDetailUrl(aftersaleId: string, options: { openWaybill?: boolean } = {}): string {
  return `${AFTERSALE_DETAIL_PATH}?aftersaleId=${encodeURIComponent(aftersaleId)}${options.openWaybill ? '&waybill=1' : ''}`
}

export function buildAftersalesUrl(tab: AftersaleTab = 'DOING'): string {
  return `${AFTERSALES_PATH}?tab=${tab}`
}

export function parseAftersaleTab(value: unknown): AftersaleTab {
  return typeof value === 'string' && (AFTERSALE_TABS as readonly string[]).includes(value)
    ? value as AftersaleTab
    : 'DOING'
}
