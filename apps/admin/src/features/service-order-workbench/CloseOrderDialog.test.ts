import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it } from 'vitest'

import CloseOrderDialog from './CloseOrderDialog.vue'
import type { ServiceOrder } from './service-order-api'

const ORDER = {
  orderId: 'o-1',
  status: 'IN_SERVICE',
  payment: {
    amountMinor: 1_200_000, collectable: true, paidAt: '2026-11-23T03:00:00Z', pendingTradeOrderId: null,
    pendingPayBefore: null, paidTradeOrderId: 't-1', refundedMinor: 0, refunds: [],
  },
} as unknown as ServiceOrder

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

function type(element: HTMLInputElement | HTMLTextAreaElement, value: string): void {
  element.value = value
  element.dispatchEvent(new Event('input'))
}

describe('关闭订单弹窗', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('协商终止：退款默认是实付，按元填、按分交出去；超过实付不交', async () => {
    const wrapper = mount(CloseOrderDialog, {
      props: { order: ORDER, mode: 'terminate', busy: false },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()
    expect(document.body.textContent).toContain('平台只协助沟通、不裁决')
    const amount = document.body.querySelector<HTMLInputElement>('.el-dialog .amount input')!
    expect(amount.value).toBe('12000.00')

    type(document.body.querySelector<HTMLTextAreaElement>('.el-dialog textarea')!, '双方同意按已完成部分退款')
    type(amount, '12000.01')
    await settle()
    button('终止订单').click()
    await settle()
    expect(document.body.textContent).toContain('不能超过实付')
    expect(wrapper.emitted('terminate')).toBeUndefined()

    type(amount, '4800.5')
    await settle()
    button('终止订单').click()
    await settle()
    expect(wrapper.emitted('terminate')?.[0]).toEqual([{ refundAmountMinor: 480_050, reason: '双方同意按已完成部分退款' }])
    wrapper.unmount()
  })

  it('开工前取消：已付款的提示整笔原路退；没写原因不交', async () => {
    const wrapper = mount(CloseOrderDialog, {
      props: { order: { ...ORDER, status: 'AWAITING_START' }, mode: 'cancel', busy: false },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()
    expect(document.body.textContent).toContain('整笔原路退回')
    button('取消订单').click()
    await settle()
    expect(wrapper.emitted('cancel')).toBeUndefined()
    type(document.body.querySelector<HTMLTextAreaElement>('.el-dialog textarea')!, ' 设备故障 ')
    await settle()
    button('取消订单').click()
    await settle()
    expect(wrapper.emitted('cancel')?.[0]).toEqual(['设备故障'])
    wrapper.unmount()
  })
})
