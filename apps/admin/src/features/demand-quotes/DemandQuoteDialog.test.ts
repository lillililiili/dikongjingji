import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ProviderDemand } from './demand-quotes-api'

const confirmAction = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, showError, notifyRecorded: vi.fn() }))

const { default: DemandQuoteDialog } = await import('./DemandQuoteDialog.vue')

const DEMAND: ProviderDemand = {
  demandId: 'd-1',
  status: 'OPEN',
  type: 'AERIAL',
  title: '楼盘航拍宣传片',
  cityCode: '370500',
  cityName: '东营市',
  address: '东营区示例小区',
  scope: '楼盘外景',
  dueDate: null,
  budgetMinMinor: null,
  budgetMaxMinor: null,
  deliverables: [{ code: 'EDITED_FILM', label: '成片剪辑' }],
  note: null,
  invited: false,
  submittedAt: '2026-11-22T02:00:00Z',
  quoteDeadline: '2026-11-25T02:00:00Z',
  quotingOpen: true,
  myQuote: null,
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

function inputs(): HTMLInputElement[] {
  return Array.from(document.body.querySelectorAll<HTMLInputElement>('.el-dialog .el-input input'))
}

function type(input: HTMLInputElement, value: string): void {
  input.value = value
  input.dispatchEvent(new Event('input'))
}

describe('需求报价弹窗', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    confirmAction.mockReset()
    showError.mockReset()
  })

  it('做法、排期没填不提交；填齐后确认，按分提交并把新的需求交出去', async () => {
    const next: ProviderDemand = { ...DEMAND, status: 'QUOTED' }
    const quote = vi.fn().mockResolvedValue(next)
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(DemandQuoteDialog, {
      props: { api: { quote }, demand: DEMAND },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    const [amount] = inputs()
    type(amount!, '8800')
    await settle()
    button('提交报价').click()
    await settle()
    expect(document.body.textContent).toContain('请填写做法')
    expect(quote).not.toHaveBeenCalled()

    const fields = inputs()
    type(fields[1]!, '多旋翼航拍')
    type(fields[2]!, '确认后一周内')
    await settle()
    button('提交报价').click()
    await settle()

    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(quote).toHaveBeenCalledWith('d-1', expect.objectContaining({
      amountMinor: 880000, method: '多旋翼航拍', schedule: '确认后一周内', durationDays: 1, deliverables: '成片剪辑',
    }))
    expect(wrapper.emitted('changed')?.[0]).toEqual([next])
    wrapper.unmount()
  })
})
