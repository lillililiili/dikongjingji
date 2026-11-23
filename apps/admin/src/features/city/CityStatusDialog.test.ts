import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { CityApi, OpsCity } from './city-api'

const confirmAction = vi.fn()
const notifyRecorded = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, notifyRecorded, showError }))

const { default: CityStatusDialog } = await import('./CityStatusDialog.vue')

const JINAN: OpsCity = {
  cityCode: '370100',
  cityName: '济南市',
  provinceCode: '370000',
  provinceName: '山东省',
  serviceStatus: 'PREPARING',
  serviceStatusChangedAt: null,
  version: 3,
}

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function mountDialog(api: Partial<CityApi>) {
  return mount(CityStatusDialog, {
    props: { api: api as CityApi, city: JINAN },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

function saveButton(): HTMLButtonElement {
  const buttons = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.el-dialog__footer button'))
  const save = buttons.find((button) => button.textContent?.includes('保存'))
  if (!save) throw new Error('no save button')
  return save
}

describe('修改开通状态', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    confirmAction.mockReset()
    notifyRecorded.mockReset()
    showError.mockReset()
  })

  it('没写说明不提交；写了说明、确认后带版本号提交并提示已留痕', async () => {
    const changed: OpsCity = { ...JINAN, serviceStatus: 'OPEN', version: 4 }
    const changeStatus = vi.fn().mockResolvedValue(changed)
    confirmAction.mockResolvedValue(true)
    const wrapper = mountDialog({ changeStatus })
    await settle()

    saveButton().click()
    await settle()
    // 错误提示有 100ms 防抖（Element Plus 表单项）。
    await vi.waitFor(() => expect(document.body.textContent).toContain('请填写改动说明'), { timeout: 2000 })
    expect(confirmAction).not.toHaveBeenCalled()

    const note = document.body.querySelector<HTMLTextAreaElement>('textarea')!
    note.value = '首批商家已入驻'
    note.dispatchEvent(new Event('input'))
    saveButton().click()
    await settle()

    expect(confirmAction).toHaveBeenCalledTimes(1)
    // 默认选中第一个和现在不一样的状态（已开通）。
    expect(changeStatus).toHaveBeenCalledWith('370100', 'OPEN', '首批商家已入驻', 3)
    expect(notifyRecorded).toHaveBeenCalledWith('把济南市改为已开通', '改动记录')
    expect(wrapper.emitted('changed')?.[0]).toEqual([changed])
    wrapper.unmount()
  })

  it('确认时点了取消就不提交', async () => {
    const changeStatus = vi.fn()
    confirmAction.mockResolvedValue(false)
    const wrapper = mountDialog({ changeStatus })
    await settle()

    const note = document.body.querySelector<HTMLTextAreaElement>('textarea')!
    note.value = '暂停'
    note.dispatchEvent(new Event('input'))
    saveButton().click()
    await settle()

    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(changeStatus).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
