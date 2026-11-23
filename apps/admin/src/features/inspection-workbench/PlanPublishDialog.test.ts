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
      props: { api: { publishPlan } as unknown as InspectionWorkbenchApi, reservation: RESERVATION, republish: false },
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

  it('再发一版没写变更说明不提交', async () => {
    const publishPlan = vi.fn()
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(PlanPublishDialog, {
      props: {
        api: { publishPlan } as unknown as InspectionWorkbenchApi,
        reservation: { ...RESERVATION, status: 'PLAN_PUBLISHED', currentPlanId: 'plan-0' },
        republish: true,
      },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()
    const price = Array.from(document.body.querySelectorAll<HTMLInputElement>('input'))
      .find((input) => input.placeholder === '单价（元）')!
    type(price, '100')
    publishButton().click()
    await settle()
    expect(publishPlan).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
