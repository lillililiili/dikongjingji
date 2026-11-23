import type { FormItemRule, FormRules } from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import type { ApiClient } from '@/core/http/api-client'
import { firstError } from '@/core/ui/form-rules'

import {
  activityFormOf,
  activityRequestOf,
  beijingInputOf,
  createActivityApi,
  emptyActivityForm,
  emptySessionForm,
  isoOfBeijingInput,
  sessionRequestOf,
  sessionState,
  switchClosingNotes,
  type ActivityForm,
  type OpsActivity,
  type OpsActivitySession,
  type SessionForm,
} from './activity-api'
import { activityRules, sessionRules } from './activity-rules'

function fakeHttp() {
  const calls: Array<{ method: string, path: string, body?: unknown, query?: unknown }> = []
  const http = {
    request: vi.fn(async (method: string, path: string, options?: { query?: unknown, body?: unknown }) => {
      calls.push({ method, path, query: options?.query, body: options?.body })
      return {}
    }),
    get: vi.fn(async (path: string, query?: unknown) => {
      calls.push({ method: 'GET', path, query })
      return {}
    }),
    post: vi.fn(async (path: string, body?: unknown) => {
      calls.push({ method: 'POST', path, body })
      return {}
    }),
    put: vi.fn(async (path: string, body?: unknown) => {
      calls.push({ method: 'PUT', path, body })
      return {}
    }),
    delete: vi.fn(),
    download: vi.fn(),
  }
  return { http: http as unknown as ApiClient, calls }
}

function form(overrides: Partial<ActivityForm> = {}): ActivityForm {
  return {
    ...emptyActivityForm(),
    title: '  无人机编程挑战赛 ',
    organizerId: 'a1000000-0000-4000-8000-000000000001',
    cityCode: '370500',
    venueName: '体验馆',
    venueAddress: '示例路 1 号',
    ...overrides,
  }
}

function rulesOf(rules: FormRules, field: string): FormItemRule[] {
  const value = rules[field]
  return (Array.isArray(value) ? value : value ? [value] : []) as FormItemRule[]
}

describe('活动表单 → 请求体', () => {
  it('空白变 null，只有按人收费才带单价（元换成分）', () => {
    const free = activityRequestOf(form({ priceMode: 'FREE', priceYuan: '99', subtitle: '  ', galleryUrls: [' https://a.example.com/1.jpg ', ''] }))
    expect(free.title).toBe('无人机编程挑战赛')
    expect(free.subtitle).toBeNull()
    expect(free.priceAmountMinor).toBeNull()
    expect(free.coOrganizerId).toBeNull()
    expect(free.galleryUrls).toEqual(['https://a.example.com/1.jpg'])
    expect(free).not.toHaveProperty('version')

    const paid = activityRequestOf(form({ priceMode: 'PER_PERSON', priceYuan: '120.5' }), 3)
    expect(paid.priceAmountMinor).toBe(12050)
    expect(paid.version).toBe(3)
  })
})

describe('候补和团队报名开关（契约 1.1.0）', () => {
  const current: OpsActivity = {
    activityId: 'a1', type: 'CONTEST', title: '挑战赛', subtitle: null, coverUrl: null, galleryUrls: [],
    organizer: { organizationId: 'o1', name: '示例机构' }, coOrganizer: null, cityCode: '370500', cityName: '东营市',
    venueName: '体验馆', venueAddress: '示例路 1 号', scheduleNote: null, priceMode: 'PER_PERSON', priceAmountMinor: 12000,
    minAge: null, maxAge: null, eligibilityNote: null, seatNote: null, refundNote: '可退', enrollmentNotice: '须知',
    waitlistAllowed: true, teamEnrollmentAllowed: true, activeWaitlistCount: 3, status: 'PUBLISHED',
    firstPublishedAt: null, hasEnrollments: true, deletable: false, publishMissing: [], sessions: [],
    createdAt: '2026-11-22T02:00:00Z', updatedAt: '2026-11-22T02:00:00Z', version: 4,
  }

  it('新建默认关；读回来的活动带着开关；请求体总是带上两个开关', () => {
    expect(activityRequestOf(form())).toMatchObject({ waitlistAllowed: false, teamEnrollmentAllowed: false })
    const loaded = activityFormOf(current)
    expect(loaded).toMatchObject({ waitlistAllowed: true, teamEnrollmentAllowed: true })
    expect(activityRequestOf(loaded, 4)).toMatchObject({ waitlistAllowed: true, teamEnrollmentAllowed: true, version: 4 })
    expect(activityRequestOf(form({ priceMode: 'FREE', waitlistAllowed: true }))).toMatchObject({
      waitlistAllowed: true, teamEnrollmentAllowed: false,
    })
  })

  it('询价活动不能在线报名：两个开关跟着关', () => {
    expect(activityRequestOf(form({ priceMode: 'INQUIRY', waitlistAllowed: true, teamEnrollmentAllowed: true })))
      .toMatchObject({ waitlistAllowed: false, teamEnrollmentAllowed: false })
  })

  it('关掉开关时提示已有的照常：排队中的候补照常转正，已有团队报名照常', () => {
    expect(switchClosingNotes(current, activityFormOf(current))).toEqual([])
    const notes = switchClosingNotes(current, { ...activityFormOf(current), waitlistAllowed: false, teamEnrollmentAllowed: false })
    expect(notes).toHaveLength(2)
    expect(notes[0]).toContain('已在排队的 3 条候补照常按顺序转正')
    expect(notes[1]).toContain('已有的团队报名照常')
    expect(switchClosingNotes({ ...current, activeWaitlistCount: 0 }, { ...activityFormOf(current), waitlistAllowed: false }))
      .toEqual(['关闭候补：App 上满员的场次不能再排队。'])
    // 改成询价等于两个都关。
    expect(switchClosingNotes(current, { ...activityFormOf(current), priceMode: 'INQUIRY' })).toHaveLength(2)
    // 本来就关着的不提示。
    expect(switchClosingNotes({ ...current, waitlistAllowed: false, teamEnrollmentAllowed: false },
      { ...activityFormOf(current), waitlistAllowed: false, teamEnrollmentAllowed: false })).toEqual([])
  })
})

describe('北京时间', () => {
  it('表单按北京时间填写，提交带 +08:00；显示也按北京时间', () => {
    expect(isoOfBeijingInput('2026-11-28 09:30')).toBe('2026-11-28T09:30:00+08:00')
    expect(isoOfBeijingInput('2026-11-28')).toBeNull()
    expect(beijingInputOf('2026-11-28T01:30:00Z')).toBe('2026-11-28 09:30')
    expect(beijingInputOf(null)).toBe('')
  })

  it('场次请求体：可不填的时间是 null，修改时带版本号', () => {
    const session: SessionForm = { ...emptySessionForm(), label: ' 上午场 ', startsAt: '2026-11-28 09:30',
      enrollmentClosesAt: '2026-11-27 18:00', capacity: 60 }
    expect(sessionRequestOf(session, 2)).toEqual({
      label: '上午场',
      startsAt: '2026-11-28T09:30:00+08:00',
      endsAt: null,
      checkInOpensAt: null,
      enrollmentClosesAt: '2026-11-27T18:00:00+08:00',
      capacity: 60,
      version: 2,
    })
    expect(() => sessionRequestOf(emptySessionForm())).toThrow()
  })
})

describe('场次在 App 上的样子', () => {
  const base: OpsActivitySession = {
    sessionId: 's', label: null, startsAt: '2026-12-01T01:00:00Z', endsAt: null, checkInOpensAt: null,
    enrollmentClosesAt: '2026-11-30T01:00:00Z', capacity: 10, enrolledCount: 3, saleStatus: 'ON_SALE',
    cancelled: false, hasEnrollments: true, started: false, version: 1,
  }
  const now = new Date('2026-11-23T00:00:00Z')

  it('在售、停售、已满、报名截止、已开始、已取消', () => {
    expect(sessionState(base, now).label).toBe('在售')
    expect(sessionState({ ...base, saleStatus: 'STOPPED' }, now).label).toBe('停售')
    expect(sessionState({ ...base, enrolledCount: 10 }, now).label).toBe('已满')
    expect(sessionState(base, new Date('2026-11-30T02:00:00Z')).label).toBe('报名截止')
    expect(sessionState({ ...base, started: true }, now).label).toBe('已开始')
    expect(sessionState({ ...base, cancelled: true }, now).label).toBe('已取消')
  })
})

describe('接口拼法', () => {
  it('列表带筛选；上线、停售、删除带版本号', async () => {
    const { http, calls } = fakeHttp()
    const api = createActivityApi(http)
    await api.list({ keyword: ' 挑战 ', cityCode: '370500', type: 'CONTEST', status: '' }, 2, 10)
    await api.publish('a/1', 4)
    await api.stopSale('a1', 's1', 5)
    await api.remove('a1', 0)
    await api.removeSession('a1', 's1', 6)
    expect(calls).toEqual([
      { method: 'GET', path: '/api/v1/ops/activities', query: { keyword: '挑战', cityCode: '370500', type: 'CONTEST', status: '', page: 2, size: 10 } },
      { method: 'POST', path: '/api/v1/ops/activities/a%2F1/publish', body: { version: 4 } },
      { method: 'POST', path: '/api/v1/ops/activities/a1/sessions/s1/stop-sale', body: { version: 5 } },
      { method: 'DELETE', path: '/api/v1/ops/activities/a1', query: { version: 0 }, body: undefined },
      { method: 'DELETE', path: '/api/v1/ops/activities/a1/sessions/s1', query: { version: 6 }, body: undefined },
    ])
  })
})

describe('表单校验', () => {
  it('名称、主办、城市、地点必填；封面只收 https；按人收费要有合法单价', async () => {
    const draft = form()
    const rules = activityRules(() => draft)
    expect(await firstError(rulesOf(rules, 'title'), ' ')).toBe('请填写活动名称')
    expect(await firstError(rulesOf(rules, 'title'), '赛'.repeat(61))).toBe('活动名称最多 60 个字')
    expect(await firstError(rulesOf(rules, 'coverUrl'), 'http://a.example.com/c.jpg')).toBe('封面只能是 https 地址')
    expect(await firstError(rulesOf(rules, 'coverUrl'), '')).toBeNull()
    expect(await firstError(rulesOf(rules, 'priceYuan'), '')).toBeNull()
    draft.priceMode = 'PER_PERSON'
    expect(await firstError(rulesOf(rules, 'priceYuan'), '')).toContain('单价')
    expect(await firstError(rulesOf(rules, 'priceYuan'), '0')).toContain('单价')
    expect(await firstError(rulesOf(rules, 'priceYuan'), '120.5')).toBeNull()
    draft.minAge = 12
    draft.maxAge = 8
    expect(await firstError(rulesOf(rules, 'maxAge'), 8)).toBe('最小年龄不能大于最大年龄')
    expect(await firstError(rulesOf(rules, 'coOrganizerId'), draft.organizerId)).toBe('承办机构不能和主办机构相同')
  })

  it('场次：开始、报名截止、名额必填；截止和签到不能晚于开始，结束要晚于开始', async () => {
    const session: SessionForm = { ...emptySessionForm(), startsAt: '2026-11-28 09:30' }
    const rules = sessionRules(() => session)
    expect(await firstError(rulesOf(rules, 'startsAt'), '')).toBe('请填写开始时间')
    expect(await firstError(rulesOf(rules, 'enrollmentClosesAt'), '2026-11-28 10:00')).toBe('报名截止不能晚于开始时间')
    expect(await firstError(rulesOf(rules, 'enrollmentClosesAt'), '2026-11-28 09:30')).toBeNull()
    expect(await firstError(rulesOf(rules, 'checkInOpensAt'), '2026-11-28 09:40')).toBe('签到开放不能晚于开始时间')
    expect(await firstError(rulesOf(rules, 'endsAt'), '2026-11-28 09:30')).toBe('结束时间要晚于开始时间')
    expect(await firstError(rulesOf(rules, 'endsAt'), '')).toBeNull()
    expect(await firstError(rulesOf(rules, 'capacity'), 0)).toContain('名额')
    expect(await firstError(rulesOf(rules, 'capacity'), 60)).toBeNull()
  })
})
