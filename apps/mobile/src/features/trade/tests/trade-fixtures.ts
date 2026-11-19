import type { TradeOrder } from '../domain/trade-order'

// 2026-11-18 10:00 Beijing time.
export const NOW = Date.parse('2026-11-18T02:00:00Z')

export function tradeOrder(overrides: Partial<TradeOrder> = {}): TradeOrder {
  return {
    orderId: 'o-1',
    orderNo: 'MO2026111812345678',
    status: 'PENDING_PAYMENT',
    merchant: { organizationId: 'm-1', name: '示例机器人商家', kind: 'FACTORY_DIRECT' },
    lines: [{
      lineId: 'l-1',
      productId: 'p-1',
      offerId: 'f-1',
      productName: '编程机器人探索套装',
      skuName: '标准版',
      coverImage: null,
      unitAmountMinor: 129900,
      quantity: 1,
      subtotalMinor: 129900,
    }],
    delivery: {
      mode: 'EXPRESS',
      recipientName: '王女士',
      phoneMasked: '138****2026',
      addressLine: '山东省东营市 东营区 示例路 88 号',
      pickupPoint: null,
      pickupCode: null,
      carrierName: null,
      trackingNo: null,
      shippedAt: null,
    },
    amounts: { goodsMinor: 129900, shippingMinor: 0, payableMinor: 129900, paidMinor: 0, refundedMinor: 0 },
    buyerNote: null,
    createdAt: '2026-11-18T01:50:00Z',
    // 29:45 left at NOW.
    payBefore: '2026-11-18T02:29:45Z',
    paidAt: null,
    completedAt: null,
    closedAt: null,
    closeReason: null,
    refunds: [],
    cancellable: true,
    version: 3,
    ...overrides,
  }
}

export function paidOrder(overrides: Partial<TradeOrder> = {}): TradeOrder {
  return tradeOrder({
    status: 'PAID',
    payBefore: null,
    paidAt: '2026-11-18T01:55:00Z',
    amounts: { goodsMinor: 129900, shippingMinor: 0, payableMinor: 129900, paidMinor: 129900, refundedMinor: 0 },
    ...overrides,
  })
}
