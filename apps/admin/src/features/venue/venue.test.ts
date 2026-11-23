import { describe, expect, it, vi } from 'vitest'

import type { ApiClient } from '@/core/http/api-client'

import { createVenueApi, deviceBody, projectBody, venueBody } from './venue-api'
import {
  addDays,
  beijingToday,
  emptyVenueDraft,
  entryRuleText,
  expandWeekTemplate,
  openingHoursProblem,
  openingNotePreview,
  projectSummaryPreview,
} from './venue-rules'

describe('周模板展开', () => {
  it('按北京时间的日期和星期展开，时间排好序', () => {
    // 2026-11-23 是周一。
    const result = expandWeekTemplate('2026-11-23', '2026-11-30', ['MONDAY', 'SATURDAY'], ['14:30', '10:00'])
    expect(result).toEqual({ slots: [
      '2026-11-23 10:00', '2026-11-23 14:30',
      '2026-11-28 10:00', '2026-11-28 14:30',
      '2026-11-30 10:00', '2026-11-30 14:30',
    ] })
  })

  it('跨度、星期、时间不合格时说明原因', () => {
    expect(expandWeekTemplate('2026-11-30', '2026-11-23', ['MONDAY'], ['10:00'])).toEqual({ problem: '结束日期不能早于开始日期' })
    expect(expandWeekTemplate('2026-11-24', '2027-01-25', ['MONDAY'], ['10:00'])).toEqual({ problem: '日期跨度不能超过 62 天' })
    expect('slots' in expandWeekTemplate('2026-11-24', '2027-01-24', ['MONDAY'], ['10:00'])).toBe(true)
    expect(expandWeekTemplate('2026-11-23', '2026-11-30', [], ['10:00'])).toEqual({ problem: '至少选一天' })
    expect(expandWeekTemplate('2026-11-23', '2026-11-30', ['MONDAY'], ['10:00', '10:00'])).toEqual({ problem: '开始时间重复了' })
    expect(expandWeekTemplate('2026-11-23', '2026-11-30', ['MONDAY'], ['9:00'])).toEqual({ problem: '开始时间写成 10:00 这样' })
    expect(expandWeekTemplate('2026-02-30', '2026-03-01', ['MONDAY'], ['10:00'])).toEqual({ problem: '请选择开始和结束日期' })
  })

  it('北京时间的今天不跟电脑时区走', () => {
    expect(beijingToday(new Date('2026-11-23T17:00:00Z'))).toBe('2026-11-24')
    expect(beijingToday(new Date('2026-11-23T15:59:00Z'))).toBe('2026-11-23')
    expect(addDays('2026-11-30', 2)).toBe('2026-12-02')
  })
})

describe('营业时间与检票规则', () => {
  it('两项一起填、开门早于关门，生成和后端一样的一行字', () => {
    expect(openingHoursProblem('', '')).toBeNull()
    expect(openingHoursProblem('09:30', '')).toBe('开门和关门时间要一起填')
    expect(openingHoursProblem('18:00', '09:30')).toBe('开门时间要早于关门时间')
    expect(openingNotePreview('09:30', '18:00', ' 周一闭馆 ')).toBe('09:30–18:00 · 周一闭馆')
    expect(openingNotePreview('09:30', '18:00', '')).toBe('09:30–18:00')
    expect(openingNotePreview('', '', '周一闭馆')).toBeNull()
  })

  it('项目摘要：填了用填的，没填用启用的前三个项目名（S4-4）', () => {
    const project = (name: string, sortOrder: number, status: 'ACTIVE' | 'DISABLED' = 'ACTIVE') =>
      ({ name, sortOrder, status })
    const projects = [project('编程体验课', 30), project('停用的', 5, 'DISABLED'), project('机器人避障闯关', 10),
      project('无人机模拟飞行', 20), project('第四个', 40)]
    expect(projectSummaryPreview(' 模拟飞行 · 厂家展区 ', projects)).toBe('模拟飞行 · 厂家展区')
    expect(projectSummaryPreview('', projects)).toBe('机器人避障闯关 · 无人机模拟飞行 · 编程体验课')
    expect(projectSummaryPreview('  ', [])).toBeNull()
  })

  it('迟到规则为空表示到场次结束', () => {
    expect(entryRuleText(30, null)).toBe('开场前 30 分钟开始检票，到场次结束都能入场')
    expect(entryRuleText(15, 10)).toBe('开场前 15 分钟开始检票，开场 10 分钟后停止入场')
  })
})

describe('接口拼法', () => {
  function client() {
    const http = {
      get: vi.fn().mockResolvedValue({}),
      post: vi.fn().mockResolvedValue({}),
      put: vi.fn().mockResolvedValue({}),
      delete: vi.fn(),
      request: vi.fn(),
      download: vi.fn(),
    }
    return { http, api: createVenueApi(http as unknown as ApiClient) }
  }

  it('空白当作没填，信息条目去掉空行，新建带幂等键', async () => {
    const { http, api } = client()
    const draft = {
      ...emptyVenueDraft(),
      operatorId: 'a1000000-0000-4000-8000-000000000004',
      name: ' 体验馆 ',
      cityCode: '370500',
      address: ' 示例路 66 号 ',
      opensAt: '09:30',
      closesAt: '18:00',
      infoItems: [{ label: '入口', content: '东门' }, { label: ' ', content: '' }],
    }
    expect(venueBody(draft, 3)).toMatchObject({
      name: '体验馆', address: '示例路 66 号', coverUrl: null, phone: null, closedNote: null, projectSummary: null,
      checkinOpensMinutes: 30, lateEntryMinutes: null, infoItems: [{ label: '入口', content: '东门' }], version: 3,
    })
    await api.create(draft, 'key-1')
    expect(http.post).toHaveBeenCalledWith('/api/v1/ops/venues', expect.objectContaining({ version: null }),
      { idempotencyKey: 'key-1' })
    await api.publish('v 1', 4, '')
    expect(http.post).toHaveBeenLastCalledWith('/api/v1/ops/venues/v%201/publish', { version: 4, note: null })
  })

  it('项目、设备、场次的路由和请求体', async () => {
    const { http, api } = client()
    expect(projectBody({ name: ' 闯关 ', durationMinutes: 30, priceAmountMinor: 3900, minAge: null, capacity: 8,
      limitNote: ' ', sortOrder: 10 }, null)).toEqual({ name: '闯关', durationMinutes: 30, priceAmountMinor: 3900,
      minAge: null, capacity: 8, limitNote: null, sortOrder: 10, version: null })
    expect(deviceBody({ category: 'ROBOT', name: '机器狗', summary: '', imageUrl: '', trialMode: 'DISPLAY_ONLY',
      productId: ' ', sortOrder: 0 }, 2)).toMatchObject({ summary: null, imageUrl: null, productId: null, version: 2 })

    await api.generateSessions('v1', 'p1', { fromDate: '2026-11-24', toDate: '2026-12-07', weekdays: ['SATURDAY'],
      startTimes: ['10:00'], capacity: null })
    expect(http.post).toHaveBeenLastCalledWith('/api/v1/ops/venues/v1/projects/p1/sessions/generate',
      { fromDate: '2026-11-24', toDate: '2026-12-07', weekdays: ['SATURDAY'], startTimes: ['10:00'], capacity: null })
    await api.changeSession('v1', 's1', 'stop-sale', 5)
    expect(http.post).toHaveBeenLastCalledWith('/api/v1/ops/venues/v1/sessions/s1/stop-sale', { version: 5 })
    await api.sessions('v1', 'p1', '2026-11-24', '2026-12-07')
    expect(http.get).toHaveBeenLastCalledWith('/api/v1/ops/venues/v1/projects/p1/sessions',
      { from: '2026-11-24', to: '2026-12-07' })
    await api.projectStatus('v1', 'p1', 'DISABLED', 2)
    expect(http.put).toHaveBeenLastCalledWith('/api/v1/ops/venues/v1/projects/p1/status', { status: 'DISABLED', version: 2 })
    await api.list({ keyword: ' 馆 ', cityCode: '370500', status: '' }, 1, 20)
    expect(http.get).toHaveBeenLastCalledWith('/api/v1/ops/venues',
      { keyword: '馆', cityCode: '370500', status: '', page: 1, size: 20 })
  })
})
