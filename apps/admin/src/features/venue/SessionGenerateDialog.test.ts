import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { OpsVenueProject, VenueApi } from './venue-api'

const confirmAction = vi.fn()
const notifyRecorded = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, notifyRecorded, showError }))

const { default: SessionGenerateDialog } = await import('./SessionGenerateDialog.vue')

const PROJECT: OpsVenueProject = {
  projectId: 'b4000000-0000-4000-8000-000000000101',
  name: '机器人避障闯关',
  durationMinutes: 30,
  priceAmountMinor: 3900,
  minAge: 6,
  capacity: 8,
  limitNote: null,
  sortOrder: 10,
  status: 'ACTIVE',
  upcomingSessionCount: 0,
  nextSessionAt: null,
  version: 1,
}

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function footerButton(text: string): HTMLButtonElement {
  const buttons = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button'))
  const button = buttons.find((candidate) => candidate.textContent?.includes(text))
  if (!button) throw new Error(`no ${text} button`)
  return button
}

describe('按周生成场次', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    confirmAction.mockReset()
    notifyRecorded.mockReset()
    showError.mockReset()
  })

  it('先算出会生成多少场，确认后用项目每场人数提交并提示已留痕', async () => {
    const generateSessions = vi.fn().mockResolvedValue({ createdCount: 8, skippedExistingCount: 0, skippedPastCount: 0 })
    confirmAction.mockResolvedValue(true)
    const wrapper = mount(SessionGenerateDialog, {
      props: { api: { generateSessions } as unknown as VenueApi, venueId: 'v1', open: false, project: PROJECT },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await wrapper.setProps({ open: true })
    await settle()

    // 默认两周的周六、周日，每天 10:00、14:00：共 8 场。
    expect(document.body.textContent).toContain('最多生成 8 场')
    footerButton('生成').click()
    await settle()

    expect(confirmAction).toHaveBeenCalledOnce()
    expect(generateSessions).toHaveBeenCalledWith('v1', PROJECT.projectId, expect.objectContaining({
      weekdays: ['SATURDAY', 'SUNDAY'],
      startTimes: ['10:00', '14:00'],
      capacity: null,
    }))
    expect(notifyRecorded).toHaveBeenCalledOnce()
    expect(wrapper.emitted('generated')?.[0]).toEqual([{ createdCount: 8, skippedExistingCount: 0, skippedPastCount: 0 }])
    wrapper.unmount()
  })

  it('取消确认时不提交', async () => {
    const generateSessions = vi.fn()
    confirmAction.mockResolvedValue(false)
    const wrapper = mount(SessionGenerateDialog, {
      props: { api: { generateSessions } as unknown as VenueApi, venueId: 'v1', open: false, project: PROJECT },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await wrapper.setProps({ open: true })
    await settle()
    footerButton('生成').click()
    await settle()
    expect(generateSessions).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
