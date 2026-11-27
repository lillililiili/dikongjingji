import { isUnauthorizedFailure, readTradeFailure } from './start-payment'

export type SubmitFailure =
  | { kind: 'UNAUTHORIZED' }
  // The amount the page showed is no longer right: preview again and let the buyer look.
  | { kind: 'AMOUNT_CHANGED'; message: string }
  // The merchant has no payment account registered: nothing the buyer can do on this page.
  | { kind: 'MERCHANT_PAYMENT_UNAVAILABLE'; message: string }
  // The delivery mode or the address went away: preview again.
  | { kind: 'DELIVERY_CHANGED'; message: string }
  // A product went off sale, another merchant, not sold here: the page's 商品有变化 state.
  | { kind: 'GOODS_CHANGED'; message: string }
  | { kind: 'STOCK_CHANGED'; message: string }
  // Ordering by an inquiry quote (F4) is no longer possible: expired, not the chosen one, or it
  // already has an order that is not closed. The page leads back to the quotes.
  | { kind: 'QUOTE_UNAVAILABLE'; message: string }
  // Network or server trouble: the same content may be sent again with the same key.
  | { kind: 'RETRYABLE'; message: string }
  | { kind: 'FAILED'; message: string }

export function classifySubmitFailure(error: unknown): SubmitFailure {
  const failure = readTradeFailure(error)
  if (!failure) return { kind: 'RETRYABLE', message: '提交失败，请重试' }
  if (isUnauthorizedFailure(failure)) return { kind: 'UNAUTHORIZED' }
  switch (failure.code) {
    case 'MALL_CHECKOUT_ITEM_UNAVAILABLE':
      return { kind: 'STOCK_CHANGED', message: '商品可售数量或在售状态已变化，请重新核对商品与购买数量。' }
    case 'TRADE_AMOUNT_CHANGED':
      return { kind: 'AMOUNT_CHANGED', message: '商品价格有变化，请确认金额后再提交' }
    case 'TRADE_MERCHANT_PAYMENT_UNAVAILABLE':
      return { kind: 'MERCHANT_PAYMENT_UNAVAILABLE', message: '该商家暂未开通在线支付' }
    case 'TRADE_ADDRESS_REQUIRED':
      return { kind: 'DELIVERY_CHANGED', message: '请先添加收货地址' }
    case 'TRADE_DELIVERY_UNAVAILABLE':
      return { kind: 'DELIVERY_CHANGED', message: '配送方式有变化，请重新选择' }
    case 'MALL_INQUIRY_QUOTE_EXPIRED':
    case 'MALL_INQUIRY_QUOTE_NOT_CHOSEN':
    case 'TRADE_INQUIRY_QUOTE_ORDERED':
      return { kind: 'QUOTE_UNAVAILABLE', message: quoteUnavailableMessage(failure.code) }
    default:
      break
  }
  if (failure.code?.startsWith('MALL_CHECKOUT_') || failure.code === 'MALL_CART_NOT_PURCHASABLE') {
    return { kind: 'GOODS_CHANGED', message: failure.message || '有商品已失效' }
  }
  if (failure.status === 0 || failure.status >= 500) {
    return { kind: 'RETRYABLE', message: failure.message || '提交失败，请重试' }
  }
  return { kind: 'FAILED', message: failure.message || '提交失败，请重试' }
}

// Why an inquiry quote cannot be ordered (also used for the confirm page's preview).
export function quoteUnavailableMessage(code: string | null): string {
  switch (code) {
    case 'MALL_INQUIRY_QUOTE_EXPIRED':
      return '这份报价已过有效期，不能再按它下单，可以重新询价'
    case 'MALL_INQUIRY_QUOTE_NOT_CHOSEN':
      return '这份报价不是询价单选定的那一份，请回到报价比选查看'
    case 'TRADE_INQUIRY_QUOTE_ORDERED':
      return '这份报价已经下过单，请回到报价比选查看订单'
    default:
      return '这份报价现在不能下单，请回到报价比选查看'
  }
}

export function isQuoteUnavailableCode(code: string | null | undefined): boolean {
  return code === 'MALL_INQUIRY_QUOTE_EXPIRED'
    || code === 'MALL_INQUIRY_QUOTE_NOT_CHOSEN'
    || code === 'TRADE_INQUIRY_QUOTE_ORDERED'
}
