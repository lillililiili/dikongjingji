import type { MallSaleMode } from '../domain/mall'

// Entries that are not built. They go through this typed gate instead of ad-hoc toasts.
// Since step D the cart and the confirm page are real; ADD_TO_CART and BUY_NOW only reach this gate
// in a build where they are not shown. Since step E07 the confirm page submits real orders in
// preview builds; SUBMIT_ORDER is only left for public builds, which do not show the page.
export type MallIntent =
  | { type: 'ADD_TO_CART'; productId: string; offerId: string }
  | { type: 'BUY_NOW'; productId: string; offerId: string }
  | { type: 'INQUIRE'; productId: string; offerId: string }
  | { type: 'SUBMIT_ORDER' }
  | { type: 'READ_AGREEMENT'; document: 'TRADE_AGREEMENT' }

export type MallIntentResolution =
  | { action: 'SHOW_NOTICE'; message: string }
  | { action: 'HIDE' }

const NOTICES: Record<MallIntent['type'], string> = {
  ADD_TO_CART: '购物车即将开放',
  BUY_NOW: '下单功能即将开放',
  INQUIRE: '询价功能即将开放',
  SUBMIT_ORDER: '下单和付款要等支付渠道确定后开放',
  READ_AGREEMENT: '协议正文待提供',
}

// Preview builds explain that the entry is coming; public builds do not show it at all.
export function resolveMallIntent(
  intent: MallIntent,
  previewEnabled: boolean,
): MallIntentResolution {
  return previewEnabled
    ? { action: 'SHOW_NOTICE', message: NOTICES[intent.type] }
    : { action: 'HIDE' }
}

export interface MallPrimaryAction {
  intent: 'BUY_NOW' | 'INQUIRE'
  label: '立即购买' | '联系询价'
  // Only goods with a listed price can go into the cart.
  cartAvailable: boolean
}

/**
 * The main button follows the way the product is traded. A merchant that sells a BUY product by
 * inquiry (no listed price) is asked for a quote instead.
 */
export function resolveMallPrimaryAction(
  saleMode: MallSaleMode,
  selectedOfferAmountMinor: number | null,
): MallPrimaryAction {
  if (saleMode === 'BUY' && selectedOfferAmountMinor !== null) {
    return { intent: 'BUY_NOW', label: '立即购买', cartAvailable: true }
  }
  return { intent: 'INQUIRE', label: '联系询价', cartAvailable: false }
}
