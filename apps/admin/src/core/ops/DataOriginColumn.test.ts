// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import DataOriginColumn from './DataOriginColumn.vue'

const get = vi.hoisted(() => vi.fn())
vi.mock('@/core/http', () => ({ http: { get } }))

const global = { stubs: {
  ElTableColumn: { template: '<div><slot :row="{ id: 1 }" /><slot :row="{ id: 2 }" /></div>' },
  ElButton: { template: '<button><slot /></button>' },
} }

describe('运营资源来源列', () => {
  it('按页读取来源，已被修改的示例同时保留两项证据', async () => {
    get.mockReset().mockResolvedValue([
      { id: '1', origin: 'DEVELOPMENT_SEED', hasRecordedChanges: true },
      { id: '2', origin: 'UNVERIFIED', hasRecordedChanges: false },
    ])
    const view = mount(DataOriginColumn, {
      global, props: { resource: 'PRODUCT', rowKey: 'id', items: [{ id: 1 }, { id: 2 }] },
    })
    await flushPromises()
    expect(get).toHaveBeenCalledWith('/api/v1/ops/data-origins/PRODUCT', { ids: '1,2' }, expect.any(AbortSignal))
    expect(view.text()).toContain('预置示例')
    expect(view.text()).toContain('有变更记录')
    expect(view.text()).toContain('来源待核实')
    view.unmount()
  })

  it('请求失败后可重试，空列表清空原页来源，不再发请求', async () => {
    get.mockReset().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([
      { id: '1', origin: 'RECORDED_CREATION', hasRecordedChanges: false },
    ])
    const view = mount(DataOriginColumn, {
      global, props: { resource: 'LIVE', rowKey: 'id', items: [{ id: 1 }] },
    })
    await flushPromises()
    await view.get('button').trigger('click')
    await flushPromises()
    expect(view.text()).toContain('业务录入')
    await view.setProps({ items: [] })
    expect(get).toHaveBeenCalledTimes(2)
    expect(view.text()).not.toContain('业务录入')
    view.unmount()
  })
})
