import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'
import { jsonBody, recordingClient } from '@/core/http/recording-client'

import {
  ACTION_LABELS,
  AFTERSALE_TABS,
  approveBody,
  createAftersaleApi,
  dueHint,
  KIND_LABELS,
  needsRefresh,
  REASON_LABELS,
  receiptAmountError,
  receiptNoteError,
  REFUND_STATUS_LABELS,
  rejectErrors,
  returnAddressErrors,
  SLOT_LABELS,
  STATUS_LABELS,
  STATUS_TAGS,
  tabStatus,
  type Aftersale,
  type AftersaleAction,
  type AftersaleApi,
  type AftersaleImageSlot,
  type AftersaleKind,
  type AftersaleReason,
  type AftersaleRefundStatus,
  type AftersaleStatus,
} from './aftersale-api'

const confirmAction = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, showError, notifyRecorded: vi.fn() }))

const { default: ReceiptDialog } = await import('./ReceiptDialog.vue')
const { default: ApproveDialog } = await import('./ApproveDialog.vue')

const ID = 'a1000000-0000-4000-8000-000000000001'
const IMAGE = 'b1000000-0000-4000-8000-000000000002'

// 契约里的全部枚举值：中文表要一个不落。
const KINDS: AftersaleKind[] = ['RETURN_REFUND', 'REFUND_ONLY']
const REASONS: AftersaleReason[] = ['NO_REASON_7D', 'NOT_AS_DESCRIBED', 'DAMAGED', 'NOT_WANTED']
const STATUSES: AftersaleStatus[] = ['PENDING_REVIEW', 'AWAITING_RETURN', 'RETURN_SHIPPED', 'REFUNDING', 'REFUNDED',
  'REFUND_FAILED', 'REJECTED', 'RECEIPT_REJECTED', 'REVOKED', 'CLOSED']
const ACTIONS: AftersaleAction[] = ['SUBMIT', 'AGREE', 'AUTO_AGREE', 'REJECT', 'REVOKE', 'SHIP_RETURN', 'EDIT_RETURN',
  'CLOSE_OVERDUE', 'CONFIRM_RECEIPT', 'AUTO_REFUND', 'REJECT_RECEIPT', 'REFUND_SUCCEEDED', 'REFUND_FAILED']
const SLOTS: AftersaleImageSlot[] = ['PRODUCT', 'PACKAGE', 'OTHER']
const REFUND_STATUSES: AftersaleRefundStatus[] = ['REQUESTED', 'PROCESSING', 'SUCCEEDED', 'CLOSED', 'ABNORMAL']

function aftersale(overrides: Partial<Aftersale> = {}): Aftersale {
  return {
    aftersaleId: ID,
    aftersaleNo: 'AS202611230001',
    orderId: 'o-1',
    orderNo: 'MO202611200001',
    kind: 'RETURN_REFUND',
    reason: 'DAMAGED',
    description: '外壳有裂纹',
    status: 'RETURN_SHIPPED',
    merchant: { organizationId: 'm-1', name: '示例机器人商家', aftersaleParty: null },
    lines: [],
    paidMinor: 99800,
    requestedMinor: 49900,
    refundMinor: null,
    returnAddress: { contactName: '售后组', phone: '0546-1234567', address: '东营市府前大街 1 号', note: null },
    returnWaybill: { carrierName: '顺丰速运', trackingNo: 'SF1234', shippedAt: '2026-11-22T02:00:00Z' },
    rejectReason: null,
    merchantNote: null,
    refund: null,
    images: [{ imageId: IMAGE, slot: 'PRODUCT' }],
    dueAt: '2026-12-02T02:00:00Z',
    submittedAt: '2026-11-20T02:00:00Z',
    reviewedAt: '2026-11-20T05:00:00Z',
    returnShippedAt: '2026-11-22T02:00:00Z',
    receivedAt: null,
    refundedAt: null,
    closedAt: null,
    timeline: [],
    actions: { canRevoke: false, canSubmitReturn: false, canEditReturn: false, canReapply: false, canReview: false,
      canConfirmReceipt: true, canRejectReceipt: true },
    version: 4,
    ...overrides,
  }
}

describe('售后接口拼法', () => {
  it('页签换成 status；全部不带', async () => {
    expect(AFTERSALE_TABS.map((tab) => tab.key)).toEqual(
      ['PENDING_REVIEW', 'AWAITING_RETURN', 'RETURN_SHIPPED', 'REFUNDING', 'ALL'])
    expect(tabStatus('ALL')).toBeUndefined()

    const { http, requests } = recordingClient({ page: 1, size: 20, total: 0, hasMore: false, items: [],
      counts: { doing: 0, done: 0, closed: 0 } })
    const api = createAftersaleApi(http)
    await api.list({ tab: 'PENDING_REVIEW' }, 1, 20)
    await api.list({ tab: 'ALL' }, 2, 10)
    await api.detail(ID)

    expect(requests[0]?.url).toBe('/api/v1/provider/trade/aftersales?status=PENDING_REVIEW&page=1&size=20')
    expect(requests[1]?.url).toBe('/api/v1/provider/trade/aftersales?page=2&size=10')
    expect(requests[2]?.url).toBe(`/api/v1/provider/trade/aftersales/${ID}`)
    expect(requests[2]?.method).toBe('GET')
  })

  it('同意、拒绝、确认收货、验货不通过都带 If-Match（售后单版本）；文字去掉首尾空白，空的写 null', async () => {
    const { http, requests } = recordingClient(aftersale())
    const api = createAftersaleApi(http)
    const target = { aftersaleId: ID, version: 4 }

    await api.approve(target, { contactName: ' 售后组 ', phone: ' 0546-1234567 ', address: ' 府前大街 1 号 ', note: ' ',
      saveAsDefault: true })
    await api.reject(target, ' 已超过七天 ', '')
    await api.confirmReceipt(target, 41500, ' 缺少充电器 ')
    await api.rejectReceipt(target, ' 已拆封使用 ', ' 3 天内原样寄回 ')

    const paths = ['approval', 'rejection', 'receipt', 'receipt-rejection']
    paths.forEach((path, index) => {
      expect(requests[index]?.url).toBe(`/api/v1/provider/trade/aftersales/${ID}/${path}`)
      expect(requests[index]?.method).toBe('POST')
      expect(requests[index]?.headers['If-Match']).toBe('"4"')
      expect(requests[index]?.headers).not.toHaveProperty('Idempotency-Key')
    })
    expect(jsonBody(requests[0])).toEqual({ contactName: '售后组', phone: '0546-1234567', address: '府前大街 1 号',
      note: null, saveAsDefault: true })
    expect(jsonBody(requests[1])).toEqual({ reason: '已超过七天', note: null })
    expect(jsonBody(requests[2])).toEqual({ refundMinor: 41500, note: '缺少充电器' })
    expect(jsonBody(requests[3])).toEqual({ reason: '已拆封使用', note: '3 天内原样寄回' })
  })

  it('凭证图片带登录取二进制；默认寄回地址 GET / PUT，没设过（404）返回 null', async () => {
    const { http, requests } = recordingClient({ contactName: '售后组', phone: '0546', address: '东营', note: null,
      version: 1, updatedAt: '2026-11-20T00:00:00Z' })
    const api = createAftersaleApi(http)
    await api.image(ID, IMAGE)
    await api.returnAddress()
    await api.saveReturnAddress({ contactName: ' 售后组 ', phone: '0546-1234567', address: ' 东营 ', note: '' })

    expect(requests[0]?.url).toBe(`/api/v1/provider/trade/aftersales/${ID}/images/${IMAGE}`)
    expect(requests[0]?.headers.Authorization).toBe('Bearer t')
    expect(requests[1]?.url).toBe('/api/v1/provider/trade/return-address')
    expect(requests[2]?.method).toBe('PUT')
    expect(jsonBody(requests[2])).toEqual({ contactName: '售后组', phone: '0546-1234567', address: '东营', note: null })

    const notFound = createAftersaleApi(createApiClient({
      baseUrl: '', getToken: () => 't', onUnauthorized: vi.fn(),
      fetchImpl: async () => new Response(JSON.stringify({ code: 'COMMON_NOT_FOUND', message: '', traceId: 't' }),
        { status: 404, headers: { 'Content-Type': 'application/json' } }),
    }))
    await expect(notFound.returnAddress()).resolves.toBeNull()
  })

  it('412、409 要刷新；新错误码都有中文说明', () => {
    expect(needsRefresh(new ApiRequestError(412, 'TRADE_AFTERSALE_VERSION_CONFLICT', '', 't'))).toBe(true)
    expect(needsRefresh(new ApiRequestError(409, 'TRADE_AFTERSALE_STATE_INVALID', '', 't'))).toBe(true)
    expect(needsRefresh(new ApiRequestError(503, 'TRADE_CHANNEL_UNAVAILABLE', '', 't'))).toBe(false)
    for (const code of ['TRADE_AFTERSALE_NOT_ELIGIBLE', 'TRADE_AFTERSALE_ALREADY_OPEN', 'TRADE_AFTERSALE_AMOUNT_EXCEEDS',
      'TRADE_AFTERSALE_STATE_INVALID', 'TRADE_AFTERSALE_VERSION_CONFLICT', 'TRADE_AFTERSALE_RETURN_ADDRESS_REQUIRED',
      'TRADE_AFTERSALE_IMAGE_INVALID', 'TRADE_AFTERSALE_UPLOAD_LIMITED', 'TRADE_AFTERSALE_STORAGE_UNAVAILABLE',
      'TRADE_CHANNEL_UNAVAILABLE']) {
      expect(errorMessage(new ApiRequestError(409, code, 'server text', 't')), code).not.toBe('server text')
    }
  })
})

describe('中文表覆盖全部枚举值', () => {
  it('类型、原因、状态、状态颜色、进度动作、凭证格、退款状态', () => {
    expect(Object.keys(KIND_LABELS).sort()).toEqual([...KINDS].sort())
    expect(Object.keys(REASON_LABELS).sort()).toEqual([...REASONS].sort())
    expect(Object.keys(STATUS_LABELS).sort()).toEqual([...STATUSES].sort())
    expect(Object.keys(STATUS_TAGS).sort()).toEqual([...STATUSES].sort())
    expect(Object.keys(ACTION_LABELS).sort()).toEqual([...ACTIONS].sort())
    expect(Object.keys(SLOT_LABELS).sort()).toEqual([...SLOTS].sort())
    expect(Object.keys(REFUND_STATUS_LABELS).sort()).toEqual([...REFUND_STATUSES].sort())
    for (const label of [...Object.values(KIND_LABELS), ...Object.values(REASON_LABELS), ...Object.values(STATUS_LABELS),
      ...Object.values(ACTION_LABELS), ...Object.values(SLOT_LABELS), ...Object.values(REFUND_STATUS_LABELS)]) {
      expect(label).toMatch(/[一-龥]/)
    }
  })

  it('有时限的三个状态说明过了时限会怎样，其他没有', () => {
    expect(dueHint('PENDING_REVIEW')).toContain('自动同意')
    expect(dueHint('AWAITING_RETURN')).toContain('自动关闭')
    expect(dueHint('RETURN_SHIPPED')).toContain('自动退款')
    expect(dueHint('REFUNDED')).toBeNull()
  })
})

describe('表单规则', () => {
  it('寄回地址：收件人 ≤40、电话 5–20 位数字和 + -、地址 ≤200 必填，说明 ≤200', () => {
    const ok = { contactName: '售后组', phone: '+86-0546-123', address: '东营', note: '' }
    expect(returnAddressErrors(ok)).toEqual({})
    expect(returnAddressErrors({ ...ok, contactName: ' ' }).contactName).toBe('请填写寄回收件人')
    expect(returnAddressErrors({ ...ok, contactName: '人'.repeat(41) }).contactName).toContain('40')
    expect(returnAddressErrors({ ...ok, phone: '1234' }).phone).toContain('5–20')
    expect(returnAddressErrors({ ...ok, phone: '0546 123' }).phone).toContain('5–20')
    expect(returnAddressErrors({ ...ok, address: '' }).address).toBe('请填写寄回地址')
    expect(returnAddressErrors({ ...ok, address: '址'.repeat(201) }).address).toContain('200')
    expect(returnAddressErrors({ ...ok, note: '说'.repeat(201) }).note).toContain('200')
    expect(approveBody({ ...ok, saveAsDefault: false })).toEqual({ ...ok, note: null, saveAsDefault: false })
  })

  it('拒绝：原因必填 ≤100、说明可空；验货不通过：说明也必填', () => {
    expect(rejectErrors('', '', false).reason).toBeDefined()
    expect(rejectErrors('原'.repeat(101), '', false).reason).toContain('100')
    expect(rejectErrors('已超过七天', '', false)).toEqual({})
    expect(rejectErrors('已拆封', ' ', true).note).toBeDefined()
    expect(rejectErrors('已拆封', '说'.repeat(201), true).note).toContain('200')
    expect(rejectErrors('已拆封', '原样寄回', true)).toEqual({})
  })

  it('确认收货：金额大于 0、不超过申请金额；少于申请金额时说明必填', () => {
    expect(receiptAmountError(null, 100)).not.toBeNull()
    expect(receiptAmountError(0, 100)).toBe('退款金额要大于 0')
    expect(receiptAmountError(101, 100)).toBe('退款金额不能超过买家的申请金额')
    expect(receiptAmountError(100, 100)).toBeNull()
    expect(receiptNoteError(100, 100, '')).toBeNull()
    expect(receiptNoteError(90, 100, ' ')).toContain('少退')
    expect(receiptNoteError(90, 100, '缺少充电器')).toBeNull()
    expect(receiptNoteError(100, 100, '说'.repeat(201))).toContain('200')
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

describe('确认收货并退款弹窗', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    confirmAction.mockReset()
    showError.mockReset()
  })

  it('默认填申请金额；超过不提交；少退不写说明不提交；写了说明按分提交并带上版本', async () => {
    const next = aftersale({ status: 'REFUNDING' })
    const confirmReceipt = vi.fn().mockResolvedValue(next)
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(ReceiptDialog, {
      props: { api: { confirmReceipt } as unknown as AftersaleApi, aftersale: aftersale() },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    expect(document.body.querySelector<HTMLInputElement>('.receipt-amount input')!.value).toBe('499')
    expect(document.body.textContent).toContain('原路退回')

    type('.receipt-amount', '499.01')
    await settle()
    footerButton('确认收货并退款').click()
    await settle()
    await vi.waitFor(() => expect(document.body.textContent).toContain('不能超过买家的申请金额'), { timeout: 2000 })

    type('.receipt-amount', '415')
    await settle()
    footerButton('确认收货并退款').click()
    await settle()
    await vi.waitFor(() => expect(document.body.textContent).toContain('少退时要写明原因'), { timeout: 2000 })
    expect(confirmReceipt).not.toHaveBeenCalled()

    type('.receipt-note', '缺少充电器')
    await settle()
    footerButton('确认收货并退款').click()
    await settle()

    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(confirmReceipt).toHaveBeenCalledWith(expect.objectContaining({ aftersaleId: ID, version: 4 }), 41500,
      '缺少充电器')
    expect(wrapper.emitted('done')?.[0]).toEqual([next])
    wrapper.unmount()
  })

  it('版本不对（412）提示后要求页面刷新', async () => {
    const confirmReceipt = vi.fn().mockRejectedValue(new ApiRequestError(412, 'TRADE_AFTERSALE_VERSION_CONFLICT', '', 't'))
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(ReceiptDialog, {
      props: { api: { confirmReceipt } as unknown as AftersaleApi, aftersale: aftersale() },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()
    footerButton('确认收货并退款').click()
    await settle()

    expect(showError).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('stale')).toHaveLength(1)
    expect(wrapper.emitted('done')).toBeUndefined()
    wrapper.unmount()
  })
})

describe('同意退货弹窗', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    confirmAction.mockReset()
    showError.mockReset()
  })

  it('带出默认寄回地址；没设过时空着并默认勾上“存为默认”', async () => {
    const returnAddress = vi.fn().mockResolvedValue({ contactName: '售后组', phone: '0546-1234567', address: '东营',
      note: null, version: 1, updatedAt: '2026-11-20T00:00:00Z' })
    const approve = vi.fn().mockResolvedValue(aftersale({ status: 'AWAITING_RETURN' }))
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(ApproveDialog, {
      props: { api: { returnAddress, approve } as unknown as AftersaleApi,
        aftersale: aftersale({ status: 'PENDING_REVIEW' }) },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()
    expect(document.body.querySelector<HTMLInputElement>('.approve-contact input')!.value).toBe('售后组')

    footerButton('同意退货').click()
    await settle()
    expect(approve).toHaveBeenCalledWith(expect.objectContaining({ aftersaleId: ID, version: 4 }),
      expect.objectContaining({ contactName: '售后组', phone: '0546-1234567', address: '东营', saveAsDefault: false }))
    wrapper.unmount()

    document.body.innerHTML = ''
    const empty = mount(ApproveDialog, {
      props: { api: { returnAddress: vi.fn().mockResolvedValue(null), approve } as unknown as AftersaleApi,
        aftersale: aftersale({ status: 'PENDING_REVIEW' }) },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()
    expect(document.body.querySelector<HTMLInputElement>('.approve-contact input')!.value).toBe('')
    expect(document.body.querySelector<HTMLInputElement>('.approve-default input')!.checked).toBe(true)
    empty.unmount()
  })
})
