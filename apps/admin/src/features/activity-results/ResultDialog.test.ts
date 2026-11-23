import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiRequestError } from '@/core/http/api-error'

import type { ResultRosterEntry } from './activity-results-api'

const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction: vi.fn(), showError, notifyRecorded: vi.fn() }))

const { default: ResultDialog } = await import('./ResultDialog.vue')

const ENTRY: ResultRosterEntry = {
  ticketId: 't-1',
  holderName: '王**',
  age: 11,
  codeTail: '0928',
  checkedIn: true,
  checkedInAt: '2026-11-15T00:42:00Z',
  teamName: '东营港务物流职工子女队',
  result: null,
  resultVersion: null,
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

describe('活动结果弹窗', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    showError.mockReset()
  })

  it('第一次录入：选“未完成”、填成绩，按契约提交并把新的一行交出去', async () => {
    const next: ResultRosterEntry = {
      ...ENTRY,
      result: { completion: 'INCOMPLETE', scoreText: '4/5', certificateNote: null, recordedAt: '2026-11-15T08:00:00Z' },
      resultVersion: 0,
    }
    const record = vi.fn().mockResolvedValue(next)
    const wrapper = mount(ResultDialog, {
      props: { api: { record }, entry: ENTRY },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()
    expect(document.body.textContent).toContain('东营港务物流职工子女队')

    const incomplete = Array.from(document.body.querySelectorAll<HTMLElement>('.el-dialog .el-radio'))
      .find((radio) => radio.textContent?.includes('未完成'))!
    incomplete.click()
    type(inputs()[0]!, '4/5')
    await settle()
    button('录入').click()
    await settle()

    expect(record).toHaveBeenCalledWith('t-1', {
      completion: 'INCOMPLETE', scoreText: '4/5', certificateNote: null, expectedVersion: null,
    })
    expect(wrapper.emitted('recorded')?.[0]).toEqual([next])
    wrapper.unmount()
  })

  it('修改：带读到的版本号；被别人改过时提示刷新、要求重读名单，不弹通用错误', async () => {
    const recorded: ResultRosterEntry = {
      ...ENTRY,
      result: { completion: 'COMPLETED', scoreText: '5/5', certificateNote: null, recordedAt: '2026-11-15T08:00:00Z' },
      resultVersion: 3,
    }
    const record = vi.fn().mockRejectedValue(new ApiRequestError(409, 'ACT_RESULT_VERSION_CONFLICT', 'x', 't'))
    const wrapper = mount(ResultDialog, {
      props: { api: { record }, entry: recorded },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()
    button('保存修改').click()
    await settle()

    expect(record).toHaveBeenCalledWith('t-1', expect.objectContaining({ scoreText: '5/5', expectedVersion: 3 }))
    expect(document.body.textContent).toContain('请刷新名单')
    expect(wrapper.emitted('stale')).toHaveLength(1)
    expect(wrapper.emitted('recorded')).toBeUndefined()
    expect(showError).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
