import { describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'

import {
  canReview,
  createProviderProfileReviewApi,
  rejectReasonError,
} from '../provider-profile-review/provider-profile-review-api'
import {
  buildProfileRequest,
  createProviderProfileApi,
  initialProfileForm,
  isNoProviderProfile,
  profileValue,
  type ProfileForm,
  type ProviderProfile,
  type ProviderProfileReview,
} from './provider-profile-api'

function fakeHttp() {
  const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () =>
    new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }))
  const http = createApiClient({ getToken: () => 't', onUnauthorized: vi.fn(), fetchImpl, newRequestId: () => 'r' })
  return { http, fetchImpl }
}

const PUBLISHED: ProviderProfile = {
  legalEntity: '东营云翼科技有限公司',
  serviceModes: '无人机巡检 · 机器狗夜巡',
  authorizationUntil: '2027-06-30',
  deliverables: ['巡检报告', '隐患清单'],
  localTeamLabel: '东营本地团队',
}

function review(overrides: Partial<ProviderProfileReview> = {}): ProviderProfileReview {
  return {
    providerId: 'p-1', providerName: '云翼科技', status: 'APPROVED', published: PUBLISHED, submitted: PUBLISHED,
    rejectReason: null, submittedAt: '2026-11-20T02:00:00Z', reviewedAt: '2026-11-21T02:00:00Z', version: 3,
    ...overrides,
  }
}

function form(overrides: Partial<ProfileForm> = {}): ProfileForm {
  return { ...initialProfileForm(review()), ...overrides }
}

const TODAY = '2026-11-23'

describe('服务商资料（工作台）', () => {
  it('读、提交都是 /api/v1/provider/profile，提交带版本号', async () => {
    const { http, fetchImpl } = fakeHttp()
    const api = createProviderProfileApi(http)
    await api.get()
    const built = buildProfileRequest(form(), 3, TODAY)
    if (!built.ok) throw new Error(built.message)
    await api.submit(built.request)
    expect(fetchImpl.mock.calls.map(([url, init]) => `${init!.method} ${String(url)}`)).toEqual([
      'GET /api/v1/provider/profile',
      'PUT /api/v1/provider/profile',
    ])
    expect(JSON.parse(String(fetchImpl.mock.calls[1]![1]!.body))).toEqual({ ...PUBLISHED, version: 3 })
  })

  it('预填用最近提交的一份；从没提交过时是空的', () => {
    const submitted = { ...PUBLISHED, legalEntity: '新主体' }
    expect(initialProfileForm(review({ status: 'PENDING', submitted })).legalEntity).toBe('新主体')
    expect(initialProfileForm(review({ status: 'NONE', published: null, submitted: null }))).toEqual({
      legalEntity: '', serviceModes: '', authorizationUntil: null, deliverables: [], localTeamLabel: '',
    })
  })

  it('选填的空着提交为 null；可交付去掉首尾空格', () => {
    const built = buildProfileRequest(form({ authorizationUntil: null, localTeamLabel: ' ',
      deliverables: [{ label: ' 巡检报告 ' }] }), 0, TODAY)
    expect(built).toEqual({ ok: true, request: {
      legalEntity: PUBLISHED.legalEntity, serviceModes: PUBLISHED.serviceModes, authorizationUntil: null,
      deliverables: ['巡检报告'], localTeamLabel: null, version: 0,
    } })
    expect(buildProfileRequest(form({ authorizationUntil: TODAY }), 0, TODAY).ok).toBe(true)
  })

  it.each([
    [{ legalEntity: '' }, '经营主体'],
    [{ legalEntity: '主'.repeat(61) }, '60'],
    [{ serviceModes: ' ' }, '服务方式'],
    [{ serviceModes: '方'.repeat(41) }, '40'],
    [{ authorizationUntil: '2026-11-22' }, '不能早于今天'],
    [{ deliverables: Array.from({ length: 9 }, (_, i) => ({ label: `项${i}` })) }, '最多 8 项'],
    [{ deliverables: [{ label: '' }] }, '空的'],
    [{ deliverables: [{ label: '交'.repeat(13) }] }, '12'],
    [{ deliverables: [{ label: '巡检报告' }, { label: ' 巡检报告' }] }, '重复'],
    [{ localTeamLabel: '团'.repeat(13) }, '12'],
  ] as Array<[Partial<ProfileForm>, string]>)('不合规时给出第一条错误：%o', (overrides, message) => {
    const built = buildProfileRequest(form(overrides), 3, TODAY)
    expect(built.ok).toBe(false)
    if (!built.ok) expect(built.message).toContain(message)
  })

  it('403 是“这家机构没有服务商资料”；版本冲突有中文说明', () => {
    expect(isNoProviderProfile(new ApiRequestError(403, 'COMMON_FORBIDDEN', '', null))).toBe(true)
    expect(isNoProviderProfile(new ApiRequestError(404, 'COMMON_NOT_FOUND', '', null))).toBe(false)
    expect(errorMessage(new ApiRequestError(409, 'PRO_PROVIDER_PROFILE_VERSION_CONFLICT', 'x', 't'))).toContain('刷新')
  })

  it('显示：数组用顿号连起来，没有的是“—”', () => {
    expect(profileValue(PUBLISHED, 'deliverables')).toBe('巡检报告、隐患清单')
    expect(profileValue({ ...PUBLISHED, deliverables: [] }, 'deliverables')).toBe('—')
    expect(profileValue({ ...PUBLISHED, localTeamLabel: null }, 'localTeamLabel')).toBe('—')
    expect(profileValue(null, 'legalEntity')).toBe('—')
  })
})

describe('服务商资料审核（运营端）', () => {
  it('列表按状态筛；通过不带原因，驳回带原因，都带版本号', async () => {
    const { http, fetchImpl } = fakeHttp()
    const api = createProviderProfileReviewApi(http)
    await api.list({ status: 'PENDING' }, 1, 20)
    await api.list({ status: '' }, 2, 20)
    await api.detail('p/1')
    await api.review('p-1', 'APPROVE', '顺手写的', 5)
    await api.review('p-1', 'REJECT', ' 主体和执照不一致 ', 6)
    const calls = fetchImpl.mock.calls.map(([url, init]) => ({ url: String(url), init: init! }))
    expect(calls.map((call) => `${call.init.method} ${call.url}`)).toEqual([
      'GET /api/v1/ops/provider-profiles?status=PENDING&page=1&size=20',
      'GET /api/v1/ops/provider-profiles?page=2&size=20',
      'GET /api/v1/ops/provider-profiles/p%2F1',
      'POST /api/v1/ops/provider-profiles/p-1/review',
      'POST /api/v1/ops/provider-profiles/p-1/review',
    ])
    expect(JSON.parse(String(calls[3]!.init.body))).toEqual({ decision: 'APPROVE', reason: null, version: 5 })
    expect(JSON.parse(String(calls[4]!.init.body))).toEqual({ decision: 'REJECT', reason: '主体和执照不一致', version: 6 })
  })

  it('只有待审核的能审；驳回原因 1–200 字', () => {
    expect(canReview({ status: 'PENDING', submitted: PUBLISHED })).toBe(true)
    expect(canReview({ status: 'APPROVED', submitted: PUBLISHED })).toBe(false)
    expect(canReview({ status: 'REJECTED', submitted: PUBLISHED })).toBe(false)
    expect(rejectReasonError(' ')).toContain('驳回原因')
    expect(rejectReasonError('原'.repeat(201))).toContain('200')
    expect(rejectReasonError('主体不一致')).toBeNull()
  })
})
