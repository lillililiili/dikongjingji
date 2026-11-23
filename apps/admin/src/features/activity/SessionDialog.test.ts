import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ActivityApi, OpsActivity, OpsActivitySession } from './activity-api'

const confirmAction = vi.fn()
const notifyRecorded = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, notifyRecorded, showError }))

const { default: SessionDialog } = await import('./SessionDialog.vue')

const SESSION: OpsActivitySession = {
  sessionId: 's-1',
  label: '上午场',
  startsAt: '2026-12-01T01:30:00Z',
  endsAt: '2026-12-01T04:00:00Z',
  checkInOpensAt: null,
  enrollmentClosesAt: '2026-11-30T10:00:00Z',
  capacity: 60,
  enrolledCount: 48,
  saleStatus: 'ON_SALE',
  cancelled: false,
  hasEnrollments: true,
  started: false,
  version: 7,
}

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function mountDialog(api: Partial<ActivityApi>, session: OpsActivitySession | null) {
  return mount(SessionDialog, {
    props: { api: api as ActivityApi, activityId: 'a-1', open: true, session },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

function button(text: string): HTMLButtonElement {
  const found = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button'))
    .find((candidate) => candidate.textContent?.includes(text))
  if (!found) throw new Error(`no ${text} button`)
  return found
}

describe('场次弹窗', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    confirmAction.mockReset()
    notifyRecorded.mockReset()
    showError.mockReset()
  })

  it('已有人报名：提示时间锁住，开始、结束输入框不能改；保存时按北京时间、带版本号提交', async () => {
    const saved = { activityId: 'a-1' } as OpsActivity
    const updateSession = vi.fn().mockResolvedValue(saved)
    confirmAction.mockResolvedValue(true)
    const wrapper = mountDialog({ updateSession }, SESSION)
    await settle()

    expect(document.body.textContent).toContain('这一场已有人报名')
    const disabled = Array.from(document.body.querySelectorAll<HTMLInputElement>('.el-date-editor input'))
      .filter((input) => input.disabled)
    expect(disabled).toHaveLength(2)

    button('保存').click()
    await settle()
    expect(confirmAction).toHaveBeenCalledOnce()
    expect(updateSession).toHaveBeenCalledWith('a-1', 's-1', expect.objectContaining({
      label: '上午场',
      startsAt: '2026-12-01 09:30',
      endsAt: '2026-12-01 12:00',
      enrollmentClosesAt: '2026-11-30 18:00',
      capacity: 60,
    }), 7)
    expect(notifyRecorded).toHaveBeenCalledWith('修改场次', '改动记录')
    expect(wrapper.emitted('saved')?.[0]).toEqual([saved])
    wrapper.unmount()
  })

  it('新增场次没填开始时间不提交', async () => {
    const addSession = vi.fn()
    const wrapper = mountDialog({ addSession }, null)
    await settle()
    expect(document.body.textContent).not.toContain('这一场已有人报名')
    button('保存').click()
    await settle()
    // 错误提示有 100ms 防抖（Element Plus 表单项）。
    await vi.waitFor(() => expect(document.body.textContent).toContain('请填写开始时间'), { timeout: 2000 })
    expect(confirmAction).not.toHaveBeenCalled()
    expect(addSession).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('后端拒绝时提示错误，不关弹窗', async () => {
    const updateSession = vi.fn().mockRejectedValue(new Error('名额不能少于已报名人数'))
    confirmAction.mockResolvedValue(true)
    const wrapper = mountDialog({ updateSession }, SESSION)
    await settle()
    button('保存').click()
    await settle()
    expect(showError).toHaveBeenCalledOnce()
    expect(wrapper.emitted('saved')).toBeUndefined()
    wrapper.unmount()
  })
})
