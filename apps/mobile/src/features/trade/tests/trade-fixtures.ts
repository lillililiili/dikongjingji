import type { TradeDelivery, TradeOrder } from '../domain/trade-order'

// 2026-11-18 10:00 Beijing time.
export const NOW = Date.parse('2026-11-18T02:00:00Z')

export const EXPRESS_DELIVERY: TradeDelivery = {
  mode: 'EXPRESS',
  recipientName: '王女士',
  phoneMasked: '138****2026',
  addressLine: '山东省东营市 东营区 示例路 88 号',
  pickupPoint: null,
  pickupCode: null,
  carrierName: null,
  trackingNo: null,
  shippedAt: null,
}

export function tradeOrder(overrides: Partial<TradeOrder> = {}): TradeOrder {
  return {
    orderId: 'o-1',
    orderNo: 'MO2026111812345678',
    source: 'MALL',
    sourceRef: null,
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
    delivery: EXPRESS_DELIVERY,
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
    parties: { fulfilledFrom: null, invoiceIssuer: null, aftersaleParty: null },
    channelTransactionId: null,
    participantNames: null,
    aftersale: { latestAftersaleId: null, latestStatus: null, applicable: false },
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

// A paid sign-up's order (contract 0.2.0): two people on one session, no delivery, the organiser is the payee.
export function enrollmentOrder(overrides: Partial<TradeOrder> = {}): TradeOrder {
  return tradeOrder({
    orderNo: 'AO2026111912345678',
    source: 'ACTIVITY',
    sourceRef: 'e-1',
    merchant: { organizationId: 'org-2', name: '示例研学机构', kind: null },
    lines: [{
      lineId: 'l-1',
      productId: 'a-2',
      offerId: 's-1',
      productName: '机器人研学体验',
      skuName: '11月28日 09:30 上午',
      coverImage: null,
      unitAmountMinor: 16800,
      quantity: 2,
      subtotalMinor: 33600,
    }],
    delivery: null,
    amounts: { goodsMinor: 33600, shippingMinor: 0, payableMinor: 33600, paidMinor: 0, refundedMinor: 0 },
    // 14:45 left at NOW.
    payBefore: '2026-11-18T02:14:45Z',
    ...overrides,
  })
}

// A venue booking's order (E10): one person, no delivery, the venue's operator is the payee.
export function venueOrder(overrides: Partial<TradeOrder> = {}): TradeOrder {
  return tradeOrder({
    orderNo: 'VO2026112012345678',
    source: 'VENUE',
    sourceRef: 'b-1',
    merchant: { organizationId: 'org-4', name: '东营低空与机器人体验馆', kind: null },
    lines: [{
      lineId: 'l-1',
      productId: 'p-1',
      offerId: 's-1',
      productName: '东营低空与机器人体验馆 机器人避障闯关',
      skuName: '11月21日 10:00',
      coverImage: null,
      unitAmountMinor: 3900,
      quantity: 1,
      subtotalMinor: 3900,
    }],
    delivery: null,
    amounts: { goodsMinor: 3900, shippingMinor: 0, payableMinor: 3900, paidMinor: 0, refundedMinor: 0 },
    // 14:45 left at NOW.
    payBefore: '2026-11-18T02:14:45Z',
    ...overrides,
  })
}

export function rentalTradeOrder(overrides: Partial<TradeOrder> = {}): TradeOrder {
  return tradeOrder({
    orderNo: 'RO2026111912345678',
    source: 'RENTAL',
    sourceRef: 'r-1',
    merchant: { organizationId: 'org-1', name: '示例机器人商家', kind: null },
    lines: [{
      lineId: 'l-1',
      productId: 'p-1',
      offerId: 'o-1',
      productName: '巡检机器狗',
      skuName: '11/22–11/26 共 5 天',
      coverImage: null,
      unitAmountMinor: 190000,
      quantity: 1,
      subtotalMinor: 190000,
    }],
    delivery: null,
    amounts: { goodsMinor: 195000, shippingMinor: 0, payableMinor: 195000, paidMinor: 0, refundedMinor: 0 },
    ...overrides,
  })
}

// S4-1: an inspection down payment (contract 0.4.0): the line's productId is the service order.
export function inspectionTradeOrder(overrides: Partial<TradeOrder> = {}): TradeOrder {
  return tradeOrder({
    orderNo: 'IO2026112212345678',
    source: 'INSPECTION',
    sourceRef: 'attempt-1',
    merchant: { organizationId: 'org-9', name: '鹰眼智能巡检', kind: null },
    lines: [{
      lineId: 'l-1',
      productId: 'service-order-1',
      offerId: 'attempt-1',
      productName: '物流园区季度安防巡检 · 方案 v2',
      skuName: '首付 30%',
      coverImage: null,
      unitAmountMinor: 558000,
      quantity: 1,
      subtotalMinor: 558000,
    }],
    delivery: null,
    amounts: { goodsMinor: 558000, shippingMinor: 0, payableMinor: 558000, paidMinor: 0, refundedMinor: 0 },
    ...overrides,
  })
}

export function courseTradeOrder(overrides: Partial<TradeOrder> = {}): TradeOrder {
  return tradeOrder({
    orderNo: 'CO2026112312345678',
    source: 'COURSE',
    sourceRef: 'e-1',
    merchant: { organizationId: 'org-3', name: '示例培训机构', kind: null },
    lines: [{
      lineId: 'l-1',
      productId: 'c-1',
      offerId: 'c-1',
      productName: '无人机飞行原理 线上课',
      skuName: '单人账号',
      coverImage: null,
      unitAmountMinor: 19900,
      quantity: 1,
      subtotalMinor: 19900,
    }],
    delivery: null,
    amounts: { goodsMinor: 19900, shippingMinor: 0, payableMinor: 19900, paidMinor: 0, refundedMinor: 0 },
    ...overrides,
  })
}
