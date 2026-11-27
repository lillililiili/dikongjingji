import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { InspectionWorkbenchApi, Reservation } from './inspection-workbench-api'

const confirmAction = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, showError, notifyRecorded: vi.fn() }))

const { default: PlanPublishDialog } = await import('./PlanPublishDialog.vue')

const RESERVATION: Reservation = {
  id: 'r-1',
  reservationNo: 'IR202611230001',
  customerId: 'c-1',
  providerId: 'p-1',
  serviceId: 's-1',
  serviceTitle: '园区安防巡检',
  cityCode: '370500',
  status: 'SUBMITTED',
  location: { address: '东营市东营区示例路 1 号' },
  preferredServiceDate: '2099-12-01',
  preferredTimeSlotCode: 'MORNING',
  inspectionItems: [{ code: 'PERIMETER', label: '周界巡检' }],
  contact: { name: '王女士', mobile: '13800000000' },
  attachments: [],
  submittedAt: '2026-11-23T01:00:00Z',
  currentPlanId: null,
  version: 4,
}

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function type(input: HTMLInputElement, value: string): void {
  input.value = value
  input.dispatchEvent(new Event('input'))
}

function publishButton(): HTMLButtonElement {
  const button = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button'))
    .find((candidate) => candidate.textContent?.includes('发布方案'))
  if (!button) throw new Error('no publish button')
  return button
}

describe('出方案', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    confirmAction.mockReset()
    showError.mockReset()
  })

  it('单价没填不提交；填好后带预约版本发布，单价换成分、编码和交付物自动带上', async () => {
    const publishPlan = vi.fn().mockResolvedValue({ id: 'plan-1', planNo: 'SP1', planVersion: 1, totalAmountMinor: 128050 })
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(PlanPublishDialog, {
      props: { api: { publishPlan } as unknown as InspectionWorkbenchApi, reservation: RESERVATION, previous: null },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    publishButton().click()
    await settle()
    expect(confirmAction).not.toHaveBeenCalled()
    expect(publishPlan).not.toHaveBeenCalled()

    const price = Array.from(document.body.querySelectorAll<HTMLInputElement>('input'))
      .find((input) => input.placeholder === '单价（元）')!
    type(price, '1280.5')
    await settle()
    expect(document.body.textContent).toContain('¥1,280.50')

    publishButton().click()
    await settle()

    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(publishPlan).toHaveBeenCalledTimes(1)
    const [reservation, body, key] = publishPlan.mock.calls[0]!
    expect(reservation).toMatchObject({ id: 'r-1', version: 4 })
    expect(body).toMatchObject({
      providerId: 'p-1',
      visits: [{ plannedDate: '2099-12-01' }],
      planItems: [{ code: 'PERIMETER', name: '周界巡检', quantity: 1, unit: '项', unitPriceMinor: 128050 }],
      serviceMethods: [{ code: 'DRONE', label: '无人机巡检' }],
      changeNote: null,
      currency: 'CNY',
    })
    expect(body.deliverables.map((item: { fileType: string }) => item.fileType))
      .toEqual(['IMAGE_ARCHIVE', 'DEFECT_LIST', 'POINT_KML', 'INSPECTION_REPORT'])
    expect(typeof key).toBe('string')
    expect(wrapper.emitted('published')?.[0]).toEqual([{ id: 'plan-1', planNo: 'SP1', planVersion: 1, totalAmountMinor: 128050 }])
    wrapper.unmount()
  })

  it('巡检报价预约带入选定金额，修改合计后不能发布', async () => {
    const publishPlan = vi.fn().mockResolvedValue({ planNo: 'SP1', planVersion: 1 })
    confirmAction.mockResolvedValue(true)
    const source = { demandId: 'd1', title: '巡检需求', quoteId: 'q1', quoteVersion: 2, providerId: 'p-1',
      providerName: '本机构', amountMinor: 128050, summary: '巡检方案', scope: '南区', address: '南门', cityCode: '370500' }
    const wrapper = mount(PlanPublishDialog, {
      props: { api: { publishPlan } as unknown as InspectionWorkbenchApi, reservation: { ...RESERVATION, demandSource: source }, previous: null },
      global: { plugins: [ElementPlus] }, attachTo: document.body,
    })
    await settle()
    const price = document.body.querySelector<HTMLInputElement>('input[placeholder="单价（元）"]')!
    expect(price.value).toBe('1280.50')
    type(price, '1281')
    publishButton().click()
    await settle()
    expect(confirmAction).not.toHaveBeenCalled()
    expect(publishPlan).not.toHaveBeenCalled()
    type(price, '1280.50')
    publishButton().click()
    await settle()
    expect(publishPlan.mock.calls[0]?.[1]).toMatchObject({ summary: '巡检方案', scopeSummary: '南区',
      planItems: [{ name: '巡检服务', quantity: 1, unitPriceMinor: 128050 }] })
    wrapper.unmount()
  })

  it('新版本复用现有报价和约定，自定义编码与服务方式保留，必须填写本次变更说明', async () => {
    const publishPlan = vi.fn().mockResolvedValue({ planNo: 'SP2', planVersion: 2 })
    const previous = {
      id: 'plan-0', planNo: 'SP1', planVersion: 1, totalAmountMinor: 20500, serviceDate: '2099-12-02',
      validUntil: '2099-11-30T03:00:00Z', visits: [{ plannedDate: '2099-12-02' }, { plannedDate: '2099-12-03' }],
      summary: '巡检园区南门', scopeSummary: '南门两组设备', serviceMethods: [{ code: 'VISUAL', label: '现场目检' }],
      deliveryTerms: { reportDueHours: null, mediaRetentionDays: 37 },
      planItems: [{ code: 'ITEM-2', name: '设备目检', quantity: 2, unit: '组', unitPriceMinor: 10250 }],
      deliverables: [{ code: 'D-2', label: '设备档案', fileType: null }],
    }
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(PlanPublishDialog, {
      props: {
        api: { publishPlan } as unknown as InspectionWorkbenchApi,
        reservation: { ...RESERVATION, status: 'PLAN_PUBLISHED', currentPlanId: 'plan-0' },
        previous,
      },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()
    const price = Array.from(document.body.querySelectorAll<HTMLInputElement>('input'))
      .find((input) => input.placeholder === '单价（元）')!
    expect(price.value).toBe('102.50')
    publishButton().click()
    await settle()
    expect(publishPlan).not.toHaveBeenCalled()
    const note = document.body.querySelector<HTMLInputElement>('input[placeholder="这一版改了什么，客户会看到"]')!
    type(note, '降低单价')
    type(price, '100')
    const buttons = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
    buttons.find(button => button.textContent?.trim() === '加一行')!.click()
    buttons.find(button => button.textContent?.trim() === '加一项')!.click()
    await settle()
    const names = document.body.querySelectorAll<HTMLInputElement>('input[placeholder="名称"]')
    type(names[1]!, '复核')
    type(names[3]!, '补充记录')
    type(document.body.querySelectorAll<HTMLInputElement>('input[placeholder="单价（元）"]')[1]!, '10')
    await settle()
    publishButton().click()
    await settle()
    expect(publishPlan.mock.calls[0]?.[1]).toMatchObject({
      validUntil: new Date(previous.validUntil).toISOString(), visits: previous.visits, summary: previous.summary,
      scopeSummary: previous.scopeSummary, serviceMethods: previous.serviceMethods, deliveryTerms: previous.deliveryTerms,
      changeNote: '降低单价',
      planItems: [{ ...previous.planItems[0], unitPriceMinor: 10000 }, { code: 'ITEM-1', name: '复核', unitPriceMinor: 1000 }],
      deliverables: [{ code: 'D-2', name: '设备档案', fileType: null }, { code: 'D-1', name: '补充记录', fileType: null }],
    })
    wrapper.unmount()
  })
})
