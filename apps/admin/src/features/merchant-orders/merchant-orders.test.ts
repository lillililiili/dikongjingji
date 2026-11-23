import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'

import {
  canCompletePickup,
  canRefund,
  canShip,
  createMerchantOrdersApi,
  orderStatusLabel,
  pickupCodeError,
  refundableMinor,
  refundAmountError,
  refundReasonError,
  shipmentError,
  tabQuery,
  type MerchantOrdersApi,
  type TradeOrder,
  type TradeRefund,
} from './merchant-orders-api'

const confirmAction = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, showError, notifyRecorded: vi.fn() }))

const { default: RefundDialog } = await import('./RefundDialog.vue')
const { default: PickupDialog } = await import('./PickupDialog.vue')

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function apiWith(fetchImpl: typeof fetch) {
  return createMerchantOrdersApi(createApiClient({
    baseUrl: '',
    getToken: () => 't',
    onUnauthorized: vi.fn(),
    fetchImpl,
    newRequestId: () => 'req',
  }))
}

function refund(status: TradeRefund['status'], amountMinor: number): TradeRefund {
  return { refundId: `r-${status}-${amountMinor}`, amountMinor, reason: '少发配件', status,
    requestedAt: '2026-11-20T02:00:00Z', succeededAt: null }
}

function order(overrides: Partial<TradeOrder> = {}): TradeOrder {
  return {
    orderId: 'o-1',
    orderNo: 'MO202611200001',
    source: 'MALL',
    sourceRef: null,
    status: 'PAID',
    merchant: { organizationId: 'm-1', name: '示例机器人商家', kind: 'FACTORY_DIRECT' },
    lines: [{ lineId: 'l-1', productId: 'p-1', offerId: 'f-1', productName: '编程机器人探索套装', skuName: '标准版',
      coverImage: null, unitAmountMinor: 49900, quantity: 2, subtotalMinor: 99800 }],
    delivery: { mode: 'EXPRESS', recipientName: '王女士', phoneMasked: '138****6621', recipientPhone: '13812346621',
      addressLine: '山东省东营市东营区府前大街 1 号', pickupPoint: null, pickupCode: null, carrierName: null,
      trackingNo: null, shippedAt: null },
    amounts: { goodsMinor: 99800, shippingMinor: 0, payableMinor: 99800, paidMinor: 99800, refundedMinor: 0,
      currency: 'CNY' },
    buyerNote: null,
    createdAt: '2026-11-20T01:00:00Z',
    payBefore: null,
    paidAt: '2026-11-20T01:05:00Z',
    completedAt: null,
    closedAt: null,
    closeReason: null,
    refunds: [],
    cancellable: true,
    version: 3,
    ...overrides,
  }
}

describe('商城订单接口', () => {
  it('页签换成状态和配送方式两个查询参数；全部不带', async () => {
    expect(tabQuery('TO_SHIP')).toEqual({ status: 'PAID', deliveryMode: 'EXPRESS' })
    expect(tabQuery('TO_PICKUP')).toEqual({ status: 'PAID', deliveryMode: 'PICKUP' })
    expect(tabQuery('REFUNDING')).toEqual({ status: 'REFUNDING', deliveryMode: undefined })

    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () =>
      jsonResponse(200, { page: 1, size: 20, total: 0, hasMore: false, items: [] }))
    const api = apiWith(fetchImpl)
    await api.list({ tab: 'TO_PICKUP' }, 2, 20)
    await api.list({ tab: 'ALL' }, 1, 10)

    expect(fetchImpl.mock.calls[0]![0]).toBe('/api/v1/provider/trade/orders?status=PAID&deliveryMode=PICKUP&page=2&size=20')
    expect(fetchImpl.mock.calls[1]![0]).toBe('/api/v1/provider/trade/orders?page=1&size=10')
  })

  it('发货、核销带幂等键和订单版本；退款只带幂等键，文字去掉首尾空白', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () => jsonResponse(200, order()))
    const api = apiWith(fetchImpl)

    await api.ship(order(), ' 顺丰速运 ', ' SF123 ', 'k-1')
    await api.completePickup(order(), ' 123456 ', 'k-2')
    await api.refund('o-1', 4100, ' 少发一件配件 ', 'k-3')
    await api.detail('o-1')

    const [shipUrl, shipInit] = fetchImpl.mock.calls[0]!
    expect(shipUrl).toBe('/api/v1/provider/trade/orders/o-1/shipment')
    expect(shipInit?.method).toBe('POST')
    expect(shipInit?.headers).toMatchObject({ 'Idempotency-Key': 'k-1', 'If-Match': '"3"' })
    expect(shipInit?.body).toBe('{"carrierName":"顺丰速运","trackingNo":"SF123"}')

    const [pickupUrl, pickupInit] = fetchImpl.mock.calls[1]!
    expect(pickupUrl).toBe('/api/v1/provider/trade/orders/o-1/pickup')
    expect(pickupInit?.headers).toMatchObject({ 'Idempotency-Key': 'k-2', 'If-Match': '"3"' })
    expect(pickupInit?.body).toBe('{"pickupCode":"123456"}')

    const [refundUrl, refundInit] = fetchImpl.mock.calls[2]!
    expect(refundUrl).toBe('/api/v1/provider/trade/orders/o-1/refunds')
    expect(refundInit?.headers).toMatchObject({ 'Idempotency-Key': 'k-3' })
    expect(refundInit?.headers).not.toHaveProperty('If-Match')
    expect(refundInit?.body).toBe('{"amountMinor":4100,"reason":"少发一件配件"}')

    expect(fetchImpl.mock.calls[3]![0]).toBe('/api/v1/provider/trade/orders/o-1')
  })

  it('取货码不对有中文说明，提到连续输错 10 次会锁定', () => {
    expect(errorMessage(new ApiRequestError(409, 'TRADE_PICKUP_CODE_INVALID', '', 't'))).toContain('10 次')
  })
})

describe('可退金额', () => {
  it('实付减去已退到账，再减去在路上和异常的退款；成功和关闭的不再占钱', () => {
    const value = order({
      amounts: { ...order().amounts, paidMinor: 99800, refundedMinor: 10000 },
      refunds: [refund('SUCCEEDED', 10000), refund('PROCESSING', 20000), refund('REQUESTED', 5000),
        refund('ABNORMAL', 3000), refund('CLOSED', 40000)],
    })
    expect(refundableMinor(value)).toBe(99800 - 10000 - 20000 - 5000 - 3000)
  })

  it('不会是负数；退完了就不能再退', () => {
    const value = order({ amounts: { ...order().amounts, refundedMinor: 99800 } })
    expect(refundableMinor(value)).toBe(0)
    expect(canRefund(value)).toBe(false)
  })
})

describe('按状态能做什么', () => {
  const pickup = { ...order().delivery!, mode: 'PICKUP' as const, pickupPoint: '东营体验馆' }

  it('快递单已付款才能发货，自提单已付款才能核销', () => {
    expect(canShip(order())).toBe(true)
    expect(canCompletePickup(order())).toBe(false)
    expect(canShip(order({ delivery: pickup }))).toBe(false)
    expect(canCompletePickup(order({ delivery: pickup }))).toBe(true)
    for (const status of ['PENDING_PAYMENT', 'SHIPPED', 'COMPLETED', 'REFUNDING'] as const) {
      expect(canShip(order({ status })), status).toBe(false)
      expect(canCompletePickup(order({ status, delivery: pickup })), status).toBe(false)
    }
  })

  it('已付款、已发货、已完成、退款异常可以退款，其他不行', () => {
    for (const status of ['PAID', 'SHIPPED', 'COMPLETED', 'REFUND_FAILED'] as const) {
      expect(canRefund(order({ status })), status).toBe(true)
    }
    for (const status of ['PENDING_PAYMENT', 'CLOSED', 'REFUNDING', 'REFUNDED'] as const) {
      expect(canRefund(order({ status })), status).toBe(false)
    }
  })

  it('已付款按配送方式显示待发货或待自提', () => {
    expect(orderStatusLabel(order())).toBe('待发货')
    expect(orderStatusLabel(order({ delivery: pickup }))).toBe('待自提')
    expect(orderStatusLabel(order({ status: 'REFUND_FAILED' }))).toBe('退款异常')
  })
})

describe('表单校验', () => {
  it('物流公司和运单号必填，各不超过 40 个字', () => {
    expect(shipmentError('', 'SF1')).toBe('请填写物流公司')
    expect(shipmentError('顺丰', '  ')).toBe('请填写运单号')
    expect(shipmentError('顺'.repeat(41), 'SF1')).toContain('40')
    expect(shipmentError('顺丰', '1'.repeat(41))).toContain('40')
    expect(shipmentError(' 顺丰 ', ' SF1 ')).toBeNull()
  })

  it('取货码是 6 位数字', () => {
    expect(pickupCodeError('')).not.toBeNull()
    expect(pickupCodeError('12345')).toBe('取货码是 6 位数字')
    expect(pickupCodeError('12345a')).toBe('取货码是 6 位数字')
    expect(pickupCodeError(' 123456 ')).toBeNull()
  })

  it('退款金额大于 0、不超过可退；原因必填、不超过 80 个字', () => {
    expect(refundAmountError(null, 100)).not.toBeNull()
    expect(refundAmountError(0, 100)).toBe('退款金额要大于 0')
    expect(refundAmountError(101, 100)).toBe('退款金额不能超过可退金额')
    expect(refundAmountError(100, 100)).toBeNull()
    expect(refundReasonError(' ')).not.toBeNull()
    expect(refundReasonError('原'.repeat(81))).toContain('80')
    expect(refundReasonError('少发配件')).toBeNull()
  })
})

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function footerButton(text: string): HTMLButtonElement {
  const buttons = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button'))
  const found = buttons.find((button) => button.textContent?.includes(text))
  if (!found) throw new Error(`no button ${text}`)
  return found
}

function type(selector: string, value: string): void {
  const input = document.body.querySelector<HTMLInputElement | HTMLTextAreaElement>(`${selector} input, ${selector} textarea`)
  if (!input) throw new Error(`no input ${selector}`)
  input.value = value
  input.dispatchEvent(new Event('input'))
}

describe('退款弹窗', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    confirmAction.mockReset()
    showError.mockReset()
  })

  it('默认填剩下的全部；超过可退不提交；改成部分金额、写了原因、确认后按分提交', async () => {
    const value = order({ refunds: [refund('PROCESSING', 9800)] })
    const created = refund('PROCESSING', 4150)
    const refundCall = vi.fn().mockResolvedValue(created)
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(RefundDialog, {
      props: { api: { refund: refundCall } as unknown as MerchantOrdersApi, order: value },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    expect(document.body.querySelector<HTMLInputElement>('.refund-amount input')!.value).toBe('900.00')

    type('.refund-amount', '900.01')
    type('.refund-reason', '少发一件配件')
    await settle()
    footerButton('确认退款').click()
    await settle()
    await vi.waitFor(() => expect(document.body.textContent).toContain('退款金额不能超过可退金额'), { timeout: 2000 })
    expect(refundCall).not.toHaveBeenCalled()

    type('.refund-amount', '41.5')
    await settle()
    footerButton('确认退款').click()
    await settle()

    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(refundCall).toHaveBeenCalledWith('o-1', 4150, '少发一件配件', expect.any(String))
    expect(wrapper.emitted('done')?.[0]).toEqual([created])
    wrapper.unmount()
  })
})

describe('核销弹窗', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    confirmAction.mockReset()
    showError.mockReset()
  })

  it('取货码不对时留在弹窗里提示，不弹通用错误', async () => {
    const pickup = { ...order().delivery!, mode: 'PICKUP' as const, pickupPoint: '东营体验馆' }
    const completePickup = vi.fn().mockRejectedValue(new ApiRequestError(409, 'TRADE_PICKUP_CODE_INVALID', '', 't'))
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(PickupDialog, {
      props: { api: { completePickup } as unknown as MerchantOrdersApi, order: order({ delivery: pickup }) },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    type('.pickup-code', '654321')
    await settle()
    footerButton('核销').click()
    await settle()

    expect(completePickup).toHaveBeenCalledWith(expect.objectContaining({ orderId: 'o-1', version: 3 }), '654321',
      expect.any(String))
    expect(showError).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(document.body.textContent).toContain('连续输错 10 次'), { timeout: 2000 })
    expect(wrapper.emitted('done')).toBeUndefined()
    wrapper.unmount()
  })
})
