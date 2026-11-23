import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { RentalInquiry } from './rental-api'

const confirmAction = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, showError, notifyRecorded: vi.fn() }))

const { default: RentalQuoteDialog } = await import('./RentalQuoteDialog.vue')

const INQUIRY: RentalInquiry = {
  inquiryId: 'i-1',
  status: 'PENDING',
  closeReason: null,
  declineReason: null,
  lessee: { kind: 'PERSONAL', organizationName: null, contactName: '王女士' },
  merchant: { merchantId: 'm-1', name: '示例机器人商家', qualificationReviewed: true },
  item: {
    offerId: 'o-1', productId: 'p-1', productName: '巡检机器狗', skuName: '标准版', coverUrl: null,
    referenceAmountMinor: 38000, referenceUnit: 'DAY',
  },
  quantity: 2,
  startDate: '2026-11-28',
  endDate: '2026-11-30',
  days: 3,
  handoverMode: 'PICKUP',
  returnMode: 'DROP_OFF',
  place: {
    addressId: null, recipientName: '王女士', phone: '138****1234', provinceName: '山东省', cityCode: '370500',
    cityName: '东营市', detail: '东营区示例路 1 号',
  },
  note: null,
  submittedAt: '2026-11-22T02:00:00Z',
  quoteDeadline: '2026-11-29T02:00:00Z',
  quote: null,
  confirmable: false,
  closable: false,
  order: null,
  updatedAt: '2026-11-22T02:00:00Z',
  version: 0,
}

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function button(text: string): HTMLButtonElement {
  const found = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.el-dialog button'))
    .find((candidate) => candidate.textContent?.includes(text))
  if (!found) throw new Error(`no button ${text}`)
  return found
}

function type(input: HTMLInputElement, value: string): void {
  input.value = value
  input.dispatchEvent(new Event('input'))
}

describe('租赁报价弹窗', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    confirmAction.mockReset()
    showError.mockReset()
  })

  it('日租金没填不提交；填了之后合计跟着算，确认后按分提交并把新的询价单交出去', async () => {
    const next: RentalInquiry = { ...INQUIRY, status: 'QUOTED' }
    const quote = vi.fn().mockResolvedValue(next)
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(RentalQuoteDialog, {
      props: { api: { quote }, inquiry: INQUIRY },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    button('提交报价').click()
    await settle()
    expect(document.body.textContent).toContain('日租金要填大于 0 的金额')
    expect(quote).not.toHaveBeenCalled()

    type(document.body.querySelector<HTMLInputElement>('.daily-rent input')!, '380')
    type(document.body.querySelector<HTMLTextAreaElement>('textarea') as unknown as HTMLInputElement, '押金线下收取')
    await settle()
    // 询价 3 天 2 台：380 × 3 × 2。
    expect(document.body.textContent).toContain('¥2,280.00')

    button('提交报价').click()
    await settle()
    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(quote).toHaveBeenCalledWith('i-1', expect.objectContaining({
      dailyRentMinor: 38000, days: 3, quantity: 2, fees: [], scheduleAvailable: true, terms: '押金线下收取',
    }))
    expect(wrapper.emitted('changed')?.[0]).toEqual([next])
    wrapper.unmount()
  })

  it('确认时点了取消就不提交', async () => {
    const quote = vi.fn()
    confirmAction.mockResolvedValue(false)
    const wrapper = mount(RentalQuoteDialog, {
      props: {
        api: { quote },
        inquiry: {
          ...INQUIRY,
          status: 'QUOTED',
          quote: {
            version: 1, dailyRentMinor: 38000, days: 3, quantity: 2, rentMinor: 228000, fees: [], totalMinor: 228000,
            scheduleAvailable: true, validUntil: '2026-11-24T00:00:00Z', expired: false, terms: '条款',
            quotedAt: '2026-11-22T00:00:00Z',
          },
        },
      },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    button('提交改价').click()
    await settle()
    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(quote).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
