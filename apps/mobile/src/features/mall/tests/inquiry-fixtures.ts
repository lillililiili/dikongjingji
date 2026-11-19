import type { MallInquiry, MallInquiryOffer, MallInquiryQuote } from '../domain/inquiry'

export function mallInquiryOf(overrides: Partial<MallInquiry> = {}): MallInquiry {
  return {
    inquiryId: 'i-1',
    status: 'QUOTED',
    closeReason: null,
    buyer: { kind: 'ORGANIZATION', organizationName: '东营示例物流公司', contactName: '王女士' },
    product: { productId: 'p-1', name: '行业测绘无人机', coverImage: null },
    requirement: '测绘版 · RTK · 五镜头倾斜相机\n需要正射影像与三维建模',
    quantity: 2,
    cityCode: '370500',
    expectedDate: '2026-12-15',
    merchants: [
      { offerId: 'o-robot', merchant: { organizationId: 'm-1', name: '示例机器人商家', kind: 'FACTORY_DIRECT' }, status: 'QUOTED', declineReason: null },
      { offerId: 'o-agent', merchant: { organizationId: 'm-5', name: '示例行业装备代理', kind: 'AUTHORIZED_DEALER' }, status: 'QUOTED', declineReason: null },
    ],
    submittedAt: '2026-11-19T02:00:00Z',
    quoteDeadline: '2026-11-22T02:00:00Z',
    quotingOpen: true,
    quoteCount: 2,
    chosen: null,
    closable: true,
    updatedAt: '2026-11-19T03:00:00Z',
    version: 3,
    ...overrides,
  }
}

export function quoteOf(overrides: Partial<MallInquiryQuote> = {}): MallInquiryQuote {
  return {
    quoteId: 'q-robot',
    version: 1,
    offerId: 'o-robot',
    merchant: { organizationId: 'm-1', name: '示例机器人商家', kind: 'FACTORY_DIRECT' },
    status: 'ACTIVE',
    unitAmountMinor: 6_800_000,
    quantity: 2,
    totalMinor: 13_600_000,
    model: '示例 M6 测绘版',
    leadDays: 5,
    includes: '含 2 名飞手培训',
    validUntil: '2026-11-22T10:00:00Z',
    quotedAt: '2026-11-19T04:00:00Z',
    choosable: true,
    ...overrides,
  }
}

export function offerOf(overrides: Partial<MallInquiryOffer> = {}): MallInquiryOffer {
  return {
    offerId: 'o-robot',
    merchant: { organizationId: 'm-1', name: '示例机器人商家', kind: 'FACTORY_DIRECT' },
    deliveryNote: '东营交付',
    canInquire: true,
    ...overrides,
  }
}
