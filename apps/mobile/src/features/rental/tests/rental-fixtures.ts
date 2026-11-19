import type { RentalInquiry, RentalOrder, RentalQuote } from '../domain/rental'

// 2026-11-19 10:00 Beijing.
export const NOW = new Date('2026-11-19T02:00:00Z')

export function rentalQuote(overrides: Partial<RentalQuote> = {}): RentalQuote {
  return {
    version: 1,
    dailyRentMinor: 38_000,
    days: 5,
    quantity: 1,
    rentMinor: 190_000,
    fees: [
      { name: '送达与取回', note: '东营市内', amountMinor: 0 },
      { name: '保险', note: null, amountMinor: 5_000 },
    ],
    totalMinor: 195_000,
    scheduleAvailable: true,
    validUntil: '2026-11-22T10:00:00Z',
    expired: false,
    terms: '押金：当前不在线收取。\n超期：按天计费。\n设备编号交接时确定',
    quotedAt: '2026-11-19T02:20:00Z',
    ...overrides,
  }
}

export function rentalInquiry(overrides: Partial<RentalInquiry> = {}): RentalInquiry {
  return {
    inquiryId: 'i-1',
    status: 'PENDING',
    closeReason: null,
    declineReason: null,
    lessee: { kind: 'ORGANIZATION', organizationName: '东营港务物流', contactName: '王女士' },
    merchant: { merchantId: 'm-1', name: '示例机器人商家' },
    item: {
      offerId: 'o-1',
      productId: 'p-1',
      productName: '巡检机器狗',
      skuName: '标准版',
      coverUrl: null,
      referenceAmountMinor: 38_000,
      referenceUnit: 'DAY',
    },
    quantity: 1,
    startDate: '2026-11-22',
    endDate: '2026-11-26',
    days: 5,
    handoverMode: 'DELIVERY',
    returnMode: 'COLLECT',
    place: {
      addressId: 'a-1',
      recipientName: '王女士',
      phone: '13800002026',
      provinceName: '山东省',
      cityCode: '370500',
      cityName: '东营市',
      detail: '东营港 示例港路 6 号',
    },
    note: null,
    submittedAt: '2026-11-19T01:00:00Z',
    quoteDeadline: '2026-11-26T01:00:00Z',
    quote: null,
    confirmable: false,
    closable: true,
    order: null,
    updatedAt: '2026-11-19T01:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function rentalOrder(overrides: Partial<RentalOrder> = {}): RentalOrder {
  const inquiry = rentalInquiry()
  return {
    rentalOrderId: 'r-1',
    inquiryId: 'i-1',
    status: 'AWAITING_HANDOVER',
    contract: {
      contractNo: 'RC20261119048213',
      confirmedAt: '2026-11-19T02:30:00Z',
      quoteVersion: 2,
      lessee: inquiry.lessee,
      lessor: inquiry.merchant,
      productName: '巡检机器狗',
      skuName: '标准版',
      quantity: 1,
      startDate: '2026-11-22',
      endDate: '2026-11-26',
      days: 5,
      dailyRentMinor: 38_000,
      rentMinor: 190_000,
      fees: rentalQuote().fees,
      totalMinor: 195_000,
      handoverMode: 'DELIVERY',
      returnMode: 'COLLECT',
      place: { ...inquiry.place, addressId: null },
      terms: rentalQuote().terms,
    },
    payment: {
      tradeOrderId: 't-1',
      amountMinor: 195_000,
      payBefore: '2026-11-19T03:00:00Z',
      paidAt: '2026-11-19T02:35:00Z',
    },
    handoverRecord: null,
    returnRecord: null,
    rentStartedAt: null,
    endedAt: null,
    closedAt: null,
    cancelledAt: null,
    cancelReason: null,
    refund: null,
    handoverConfirmable: false,
    returnConfirmable: false,
    createdAt: '2026-11-19T02:30:00Z',
    updatedAt: '2026-11-19T02:35:00Z',
    version: 1,
    ...overrides,
  }
}
