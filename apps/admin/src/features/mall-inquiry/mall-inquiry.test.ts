import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'

import {
  canDecline,
  createMallInquiryApi,
  declineReasonError,
  quoteAction,
  quoteErrors,
  type MallInquiryApi,
  type MallMerchantInquiry,
  type QuoteDraft,
} from './mall-inquiry-api'

const confirmAction = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, showError, notifyRecorded: vi.fn() }))

const { default: QuoteDialog } = await import('./QuoteDialog.vue')

const NOW = new Date('2026-11-23T02:00:00Z')
const DAY = 24 * 60 * 60 * 1000

function inquiry(overrides: Partial<MallMerchantInquiry> = {}): MallMerchantInquiry {
  return {
    inquiryId: 'i-1',
    status: 'OPEN',
    product: { productId: 'p-1', name: '行业无人机 M350', coverImage: null },
    offerId: 'f-1',
    requirement: '配双光吊舱，用于管线巡检',
    quantity: 2,
    cityCode: '370500',
    expectedDate: null,
    submittedAt: '2026-11-22T02:00:00Z',
    quoteDeadline: '2026-11-25T02:00:00Z',
    quotingOpen: true,
    invitationStatus: 'INVITED',
    declineReason: null,
    myQuote: null,
    ...overrides,
  }
}

function draft(overrides: Partial<QuoteDraft> = {}): QuoteDraft {
  return {
    unitAmountMinor: 4_580_000,
    model: 'M350 RTK + H30T',
    leadDays: 7,
    includes: '',
    validUntil: new Date(NOW.getTime() + 3 * DAY).toISOString(),
    ...overrides,
  }
}

describe('询价接口', () => {
  it('列表按状态筛选；报价用 PUT，包含内容空了传 null；拒绝带原因', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () =>
      new Response(JSON.stringify(inquiry()), { status: 200 }))
    const api = createMallInquiryApi(createApiClient({ baseUrl: '', getToken: () => 't', onUnauthorized: vi.fn(), fetchImpl }))

    await api.list({ status: 'QUOTED' }, 1, 20)
    await api.list({ status: '' }, 2, 10)
    await api.quote('i-1', draft({ model: ' M350 ', includes: '  ' }))
    await api.decline('i-1', ' 缺货 ')

    expect(fetchImpl.mock.calls[0]![0]).toBe('/api/v1/provider/mall/inquiries?status=QUOTED&page=1&size=20')
    expect(fetchImpl.mock.calls[1]![0]).toBe('/api/v1/provider/mall/inquiries?page=2&size=10')
    const [quoteUrl, quoteInit] = fetchImpl.mock.calls[2]!
    expect(quoteUrl).toBe('/api/v1/provider/mall/inquiries/i-1/quote')
    expect(quoteInit?.method).toBe('PUT')
    expect(JSON.parse(String(quoteInit?.body))).toEqual({
      unitAmountMinor: 4_580_000, model: 'M350', leadDays: 7, includes: null, validUntil: draft().validUntil,
    })
    const [declineUrl, declineInit] = fetchImpl.mock.calls[3]!
    expect(declineUrl).toBe('/api/v1/provider/mall/inquiries/i-1/decline')
    expect(declineInit?.body).toBe('{"reason":"缺货"}')
  })
})

describe('能做什么', () => {
  it('能报价时第一次是报价、之后是改价；不能报价时没有按钮', () => {
    expect(quoteAction(inquiry())).toBe('报价')
    const myQuote = { quoteId: 'q-1', version: 1, status: 'ACTIVE' as const, unitAmountMinor: 100, quantity: 2,
      totalMinor: 200, model: 'x', leadDays: 1, includes: null, validUntil: '2026-11-24T00:00:00Z',
      quotedAt: '2026-11-22T00:00:00Z' }
    expect(quoteAction(inquiry({ myQuote }))).toBe('改价')
    expect(quoteAction(inquiry({ quotingOpen: false }))).toBeNull()
  })

  it('待报价、报价中且没拒绝过才能拒绝', () => {
    expect(canDecline(inquiry())).toBe(true)
    expect(canDecline(inquiry({ status: 'QUOTED', invitationStatus: 'QUOTED' }))).toBe(true)
    expect(canDecline(inquiry({ invitationStatus: 'DECLINED' }))).toBe(false)
    expect(canDecline(inquiry({ status: 'CHOSEN' }))).toBe(false)
    expect(canDecline(inquiry({ status: 'CLOSED' }))).toBe(false)
  })
})

describe('报价校验', () => {
  it('填对了没有错误', () => {
    expect(quoteErrors(draft(), NOW)).toEqual({})
  })

  it('单价、型号、交期、包含内容、有效期逐项检查', () => {
    expect(quoteErrors(draft({ unitAmountMinor: null }), NOW).unitAmount).toBeTruthy()
    expect(quoteErrors(draft({ unitAmountMinor: 0 }), NOW).unitAmount).toBe('单价要大于 0')
    expect(quoteErrors(draft({ unitAmountMinor: 10_000_000_001 }), NOW).unitAmount).toBe('单价超过上限')
    expect(quoteErrors(draft({ model: ' ' }), NOW).model).toBe('请填写型号配置')
    expect(quoteErrors(draft({ model: '型'.repeat(61) }), NOW).model).toContain('60')
    expect(quoteErrors(draft({ leadDays: 0 }), NOW).leadDays).toBeTruthy()
    expect(quoteErrors(draft({ leadDays: 366 }), NOW).leadDays).toBeTruthy()
    expect(quoteErrors(draft({ leadDays: null }), NOW).leadDays).toBeTruthy()
    expect(quoteErrors(draft({ includes: '含'.repeat(121) }), NOW).includes).toContain('120')
    expect(quoteErrors(draft({ validUntil: null }), NOW).validUntil).toBe('请选择报价有效期')
    expect(quoteErrors(draft({ validUntil: NOW.toISOString() }), NOW).validUntil).toBe('有效期要晚于现在')
    expect(quoteErrors(draft({ validUntil: new Date(NOW.getTime() + 7 * DAY + 60_000).toISOString() }), NOW)
      .validUntil).toBe('有效期最长 7 天')
    expect(quoteErrors(draft({ validUntil: new Date(NOW.getTime() + 7 * DAY).toISOString() }), NOW)).toEqual({})
  })

  it('拒绝原因必填、不超过 80 个字', () => {
    expect(declineReasonError('')).not.toBeNull()
    expect(declineReasonError('原'.repeat(81))).toContain('80')
    expect(declineReasonError('缺货')).toBeNull()
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

describe('报价弹窗', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    confirmAction.mockReset()
    showError.mockReset()
  })

  it('没填型号不提交；填齐后按分提交单价，合计按数量算', async () => {
    const quoted = inquiry({ status: 'QUOTED', invitationStatus: 'QUOTED' })
    const quote = vi.fn().mockResolvedValue(quoted)
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(QuoteDialog, {
      props: { api: { quote } as unknown as MallInquiryApi, inquiry: inquiry() },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    type('.quote-unit', '45800')
    type('.quote-lead', '7')
    await settle()
    expect(document.body.querySelector('.quote-total')?.textContent).toBe('¥91,600.00')

    footerButton('报价').click()
    await settle()
    await vi.waitFor(() => expect(document.body.textContent).toContain('请填写型号配置'), { timeout: 2000 })
    expect(quote).not.toHaveBeenCalled()

    type('.quote-model', 'M350 RTK + H30T')
    await settle()
    footerButton('报价').click()
    await settle()

    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(quote).toHaveBeenCalledWith('i-1', expect.objectContaining({
      unitAmountMinor: 4_580_000, model: 'M350 RTK + H30T', leadDays: 7,
    }))
    expect(wrapper.emitted('done')?.[0]).toEqual([quoted])
    wrapper.unmount()
  })
})
