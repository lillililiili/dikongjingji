import { describe, expect, it, vi } from 'vitest'

import { createAftersaleApi, toAftersale, toAftersaleEligibility, type AftersaleBinaryPort } from '../data/aftersale-api'

const DTO = {
  aftersaleId: 'as-1',
  aftersaleNo: 'AS2026110500001',
  orderId: 'o-1',
  orderNo: 'MO1',
  kind: 'RETURN_REFUND',
  reason: 'NO_REASON_7D',
  description: '  未拆封 ',
  status: 'AWAITING_RETURN',
  merchant: { organizationId: 'm-1', name: '示例机器人商家', aftersaleParty: '示例机器人商家 · 厂家保修' },
  lines: [{
    lineId: 'l-1',
    productId: 'p-1',
    offerId: 'f-1',
    productName: '编程机器人探索套装',
    skuName: '标准套装',
    coverImage: { url: 'http://insecure.example/a.jpg', alt: 'a' },
    unitAmountMinor: 129900,
    quantity: 1,
    subtotalMinor: 129900,
  }],
  paidMinor: 129900,
  requestedMinor: 129900,
  refundMinor: null,
  returnAddress: { contactName: '售后组', phone: '0546-0000000', address: ' 东营区 示例路 120 号 ', note: null },
  returnWaybill: null,
  rejectReason: null,
  merchantNote: null,
  refund: { status: 'SUCCEEDED', amountMinor: 129900, succeededAt: '2026-11-09T01:30:00Z', channelTransactionId: '4200000000' },
  images: [{ imageId: 'i-1', slot: 'PRODUCT' }, { imageId: 'i-2', slot: 'SOMETHING' }],
  dueAt: '2026-11-12T07:40:00Z',
  submittedAt: '2026-11-05T01:12:00Z',
  reviewedAt: '2026-11-05T07:40:00Z',
  returnShippedAt: null,
  receivedAt: null,
  refundedAt: null,
  closedAt: null,
  timeline: [
    { action: 'SUBMIT', status: 'PENDING_REVIEW', occurredAt: '2026-11-05T01:12:00Z' },
    { action: 'AGREE', status: 'AWAITING_RETURN', occurredAt: '2026-11-05T07:40:00Z' },
    { action: 'NEW_THING', status: 'AWAITING_RETURN', occurredAt: '2026-11-05T08:00:00Z' },
  ],
  actions: { canRevoke: true, canSubmitReturn: true, canEditReturn: false, canReapply: false, canReview: false, canConfirmReceipt: false, canRejectReceipt: false },
  version: 4,
}

function recorder(response: unknown, binary: Partial<AftersaleBinaryPort> = {}) {
  const request = vi.fn(async (_input: unknown) => response)
  const port: AftersaleBinaryPort = {
    upload: binary.upload ?? vi.fn(async () => ({ imageId: 'img-1', slot: 'PACKAGE', contentType: 'image/jpeg', sizeBytes: 10 })),
    readImage: binary.readImage ?? vi.fn(async () => 'data:image/jpeg;base64,AAA'),
  }
  return { request, port, api: createAftersaleApi(request as never, port) }
}

describe('aftersale api mapper', () => {
  it('maps a case: trims texts, keeps only HTTPS covers, drops unknown slots and steps, reads the buyer actions', () => {
    const mapped = toAftersale(DTO as never)
    expect(mapped.description).toBe('未拆封')
    expect(mapped.lines[0].coverImage).toBeNull()
    expect(mapped.merchant.aftersaleParty).toBe('示例机器人商家 · 厂家保修')
    expect(mapped.returnAddress).toEqual({ contactName: '售后组', phone: '0546-0000000', address: '东营区 示例路 120 号', note: null })
    expect(mapped.refund).toEqual({ status: 'SUCCEEDED', amountMinor: 129900, succeededAt: '2026-11-09T01:30:00Z', channelTransactionId: '4200000000' })
    expect(mapped.images).toEqual([{ imageId: 'i-1', slot: 'PRODUCT' }])
    expect(mapped.timeline.map((step) => step.action)).toEqual(['SUBMIT', 'AGREE'])
    expect(mapped.actions).toEqual({ canRevoke: true, canSubmitReturn: true, canEditReturn: false, canReapply: false })
    expect(mapped.version).toBe(4)
  })

  it('reads a missing or blank return address as none, and refuses a status it does not know', () => {
    expect(toAftersale({ ...DTO, returnAddress: { contactName: null, phone: null, address: '  ', note: null } } as never).returnAddress).toBeNull()
    expect(toAftersale({ ...DTO, returnAddress: undefined, actions: undefined } as never).actions.canSubmitReturn).toBe(false)
    expect(() => toAftersale({ ...DTO, status: 'ARBITRATING' } as never)).toThrow()
  })

  it('maps eligibility: options with their reasons, the open case and the dates', () => {
    const mapped = toAftersaleEligibility({
      orderId: 'o-1',
      orderNo: 'MO1',
      orderStatus: 'SHIPPED',
      lines: DTO.lines,
      paidMinor: 129900,
      maxRefundMinor: 100000,
      merchant: { organizationId: 'm-1', name: '示例机器人商家', aftersaleParty: null },
      kinds: [
        { kind: 'REFUND_ONLY', available: false, unavailableReason: 'ALREADY_SHIPPED' },
        { kind: 'RETURN_REFUND', available: true, unavailableReason: null },
        { kind: 'EXCHANGE', available: true, unavailableReason: null },
      ],
      reasons: [
        { reason: 'NO_REASON_7D', kind: 'RETURN_REFUND', available: false, unavailableReason: 'NO_REASON_NOT_SUPPORTED' },
        { reason: 'DAMAGED', kind: 'RETURN_REFUND', available: true, unavailableReason: null },
      ],
      openAftersaleId: null,
      shippedAt: '2026-11-03T02:24:00Z',
      completedAt: null,
    })
    expect(mapped.kinds).toEqual([
      { kind: 'REFUND_ONLY', available: false, unavailableReason: 'ALREADY_SHIPPED' },
      { kind: 'RETURN_REFUND', available: true, unavailableReason: null },
    ])
    expect(mapped.reasons[0]).toEqual({ reason: 'NO_REASON_7D', kind: 'RETURN_REFUND', available: false, unavailableReason: 'NO_REASON_NOT_SUPPORTED' })
    expect(mapped.maxRefundMinor).toBe(100000)
    expect(mapped.merchant.aftersaleParty).toBeNull()
  })
})

describe('aftersale api requests', () => {
  it('applies with an Idempotency-Key, leaving out an empty description, the amount (all of it) and no pictures', async () => {
    const { request, api } = recorder(DTO)
    await api.apply('token', 'o 1', { kind: 'RETURN_REFUND', reason: 'NO_REASON_7D', description: '   ', requestedMinor: null, imageIds: [] }, 'key-1')
    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/trade/orders/o%201/aftersales',
      method: 'POST',
      data: { kind: 'RETURN_REFUND', reason: 'NO_REASON_7D' },
      headers: { Authorization: 'Bearer token', 'Idempotency-Key': 'key-1' },
    })
  })

  it('sends the description, an amount and the uploaded pictures when there are some', async () => {
    const { request, api } = recorder(DTO)
    await api.apply('token', 'o-1', { kind: 'RETURN_REFUND', reason: 'DAMAGED', description: ' 外壳裂了 ', requestedMinor: 50000, imageIds: ['i-1', 'i-2'] }, 'key-2')
    expect((request.mock.calls[0][0] as { data: unknown }).data)
      .toEqual({ kind: 'RETURN_REFUND', reason: 'DAMAGED', description: '外壳裂了', requestedMinor: 50000, imageIds: ['i-1', 'i-2'] })
  })

  it('reads eligibility and a tab of the list with its counts', async () => {
    const { request, api } = recorder({ items: [DTO], hasMore: true, total: 3, counts: { doing: 1, done: 1, closed: 1 } })
    const page = await api.list('token', 'DOING', 2, 10)
    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/trade/aftersales?tab=DOING&page=2&size=10',
      method: 'GET',
      headers: { Authorization: 'Bearer token' },
    })
    expect(page.counts).toEqual({ doing: 1, done: 1, closed: 1 })
    expect(page.hasMore).toBe(true)
    expect(page.items[0].aftersaleNo).toBe('AS2026110500001')
  })

  it('revokes and submits the waybill with the case version as If-Match', async () => {
    const { request, api } = recorder(DTO)
    await api.revoke('token', 'as-1', 4)
    expect(request.mock.calls[0][0]).toEqual({
      url: '/api/v1/trade/aftersales/as-1/revoke',
      method: 'POST',
      headers: { Authorization: 'Bearer token', 'If-Match': '"4"' },
    })
    await api.submitReturn('token', 'as-1', 5, { carrierName: ' 示例快递 ', trackingNo: ' SF2026 ' })
    expect(request.mock.calls[1][0]).toEqual({
      url: '/api/v1/trade/aftersales/as-1/return-shipment',
      method: 'POST',
      data: { carrierName: '示例快递', trackingNo: 'SF2026' },
      headers: { Authorization: 'Bearer token', 'If-Match': '"5"' },
    })
  })

  it('uploads a picture into its slot and reads it back with the token', async () => {
    const { port, api } = recorder(DTO)
    const uploaded = await api.uploadImage('token', 'PACKAGE', '/tmp/a.jpg')
    expect(port.upload).toHaveBeenCalledWith({
      url: '/api/v1/trade/aftersale-images?slot=PACKAGE',
      filePath: '/tmp/a.jpg',
      headers: { Authorization: 'Bearer token' },
    })
    expect(uploaded).toEqual({ imageId: 'img-1', slot: 'PACKAGE' })
    expect(await api.imageSource('token', 'img-1')).toBe('data:image/jpeg;base64,AAA')
    expect(port.readImage).toHaveBeenCalledWith({ url: '/api/v1/trade/aftersale-images/img-1', headers: { Authorization: 'Bearer token' } })
  })

  it('refuses an upload answer without an image id', async () => {
    const { api } = recorder(DTO, { upload: vi.fn(async () => ({})) })
    await expect(api.uploadImage('token', 'OTHER', '/tmp/a.jpg')).rejects.toThrow()
  })
})
