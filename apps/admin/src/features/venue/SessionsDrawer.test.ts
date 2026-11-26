import { flushPromises, shallowMount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import SessionsDrawer from './SessionsDrawer.vue'
import type { OpsVenueProject, OpsVenueSession, VenueApi } from './venue-api'

const project: OpsVenueProject = {
  projectId: 'one', name: '项目一', durationMinutes: 30, priceAmountMinor: 100,
  minAge: null, capacity: 8, limitNote: null, sortOrder: 0, status: 'ACTIVE',
  upcomingSessionCount: 0, nextSessionAt: null, version: 1,
}

describe('场馆项目场次抽屉', () => {
  it('项目切换后只显示该项目的场次，查询失败时隐藏操作表格', async () => {
    let resolveOld!: (rows: OpsVenueSession[]) => void
    const oldRequest = new Promise<OpsVenueSession[]>(resolve => { resolveOld = resolve })
    const session: OpsVenueSession = {
      sessionId: 'session-two', projectId: 'two', startsAt: '2027-01-01T01:00:00Z', endsAt: '2027-01-01T01:30:00Z',
      capacity: 8, bookedCount: 0, activeBookingCount: 0, status: 'ACTIVE', version: 1,
    }
    const sessions = vi.fn().mockReturnValueOnce(oldRequest).mockResolvedValueOnce([session])
      .mockRejectedValueOnce(new Error('无法读取场次'))
    const wrapper = shallowMount(SessionsDrawer, {
      props: { api: { sessions } as unknown as VenueApi, venueId: 'venue', project, open: false },
      global: { plugins: [ElementPlus], stubs: { ElDrawer: { template: '<div><slot /></div>' } } },
    })
    await wrapper.setProps({ open: true })
    await wrapper.setProps({ project: { ...project, projectId: 'two', name: '项目二' } })
    await flushPromises()
    resolveOld([])
    await flushPromises()
    expect(wrapper.getComponent({ name: 'ElTable' }).props('data')).toEqual([session])
    await wrapper.setProps({ project: { ...project, projectId: 'three', name: '项目三' } })
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ElTable' }).exists()).toBe(false)
    expect(wrapper.getComponent({ name: 'ElAlert' }).props('title')).toBe('无法读取场次')
    wrapper.unmount()
  })
})
