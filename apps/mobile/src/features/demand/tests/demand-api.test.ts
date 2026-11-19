import { describe, expect, it, vi } from 'vitest'

import { createDemandApi } from '../data/demand-api'
import type { DemandDraftInput } from '../domain/demand'

const TOKEN = 'token-1'
const AUTH = { Authorization: 'Bearer token-1' }

// A demand as the server sends it (contract 1.0.0: every field present, empty ones null).
const DEMAND_DTO = {
  demandId: 'd1',
  status: 'CHOSEN',
  closeReason: null,
  type: 'AERIAL',
  title: '航拍需求',
  publisher: { kind: 'ORGANIZATION', organizationName: '东营示例物流公司' },
  cityCode: '370500',
  cityName: '东营市',
  address: '东营港区',
  scope: '码头堆场',
  dueDate: null,
  budgetMinMinor: null,
  budgetMaxMinor: 500000,
  deliverables: [{ code: 'VIDEO_FOOTAGE', label: '视频素材' }],
  note: null,
  quoteScope: 'INVITE',
  invitedProviders: [{ providerId: 'p1', name: '云翼科技', certificationLabel: '企业认证', description: null }],
  submittedAt: '2026-11-19T02:00:00Z',
  quoteDeadline: '2026-11-22T02:00:00Z',
  quotingOpen: false,
  quoteCount: 1,
  chosen: { quoteId: 'q1', quoteVersion: 2, providerName: '云翼科技', amountMinor: 1860000, chosenAt: '2026-11-19T05:00:00Z' },
  editable: false,
  cancellable: false,
  deletable: false,
  createdAt: '2026-11-19T01:00:00Z',
  updatedAt: '2026-11-19T05:00:00Z',
  version: 4,
}

const INPUT: DemandDraftInput = {
  type: 'INSPECTION',
  cityCode: '370500',
  address: '  东营经济技术开发区 ',
  scope: '',
  dueDate: '2026-11-30',
  budgetMinMinor: 1000000,
  budgetMaxMinor: 2000000,
  deliverables: ['INSPECTION_REPORT'],
  note: '   ',
  quoteScope: 'MATCH',
  invitedProviderIds: ['p1'],
}

function apiReturning(body: unknown) {
  const request = vi.fn().mockResolvedValue(body)
  return { api: createDemandApi(request), request }
}

describe('demand api (contract 1.0.0)', () => {
  it('maps a demand with its chosen quote and invited providers', async () => {
    const { api, request } = apiReturning(DEMAND_DTO)
    const demand = await api.read(TOKEN, 'd 1')

    expect(request).toHaveBeenCalledWith({ url: '/api/v1/demands/d%201', method: 'GET', headers: AUTH })
    expect(demand.chosen).toEqual({
      quoteId: 'q1', quoteVersion: 2, providerName: '云翼科技', amountMinor: 1860000, chosenAt: '2026-11-19T05:00:00Z',
    })
    expect(demand.invitedProviders).toEqual([{ providerId: 'p1', name: '云翼科技', certificationLabel: '企业认证', description: null }])
    expect(demand.publisher).toEqual({ kind: 'ORGANIZATION', organizationName: '东营示例物流公司' })
    expect(demand.dueDate).toBeNull()
    expect(demand.budgetMaxMinor).toBe(500000)
    expect(demand.version).toBe(4)
  })

  it('creates with the idempotency key, trims text, sends blanks as null and drops invitations for MATCH', async () => {
    const { api, request } = apiReturning(DEMAND_DTO)
    await api.create(TOKEN, 'key-1', INPUT, true)

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/demands',
      method: 'POST',
      data: {
        type: 'INSPECTION',
        cityCode: '370500',
        address: '东营经济技术开发区',
        scope: null,
        dueDate: '2026-11-30',
        budgetMinMinor: 1000000,
        budgetMaxMinor: 2000000,
        deliverables: ['INSPECTION_REPORT'],
        note: null,
        quoteScope: 'MATCH',
        invitedProviderIds: [],
        submit: true,
      },
      headers: { ...AUTH, 'Idempotency-Key': 'key-1' },
    })
  })

  it('replaces a draft with PUT and submits it with its own call', async () => {
    const { api, request } = apiReturning(DEMAND_DTO)
    await api.updateDraft(TOKEN, 'd1', { ...INPUT, quoteScope: 'INVITE' })
    await api.submit(TOKEN, 'd1')

    expect(request.mock.calls[0][0]).toMatchObject({
      url: '/api/v1/demands/d1',
      method: 'PUT',
      data: { quoteScope: 'INVITE', invitedProviderIds: ['p1'] },
    })
    expect(request.mock.calls[0][0].data).not.toHaveProperty('submit')
    expect(request.mock.calls[1][0]).toEqual({ url: '/api/v1/demands/d1/submit', method: 'POST', headers: AUTH })
  })

  it('lists by status, cancels, deletes drafts', async () => {
    const { api, request } = apiReturning({ page: 1, size: 20, total: 1, hasMore: false, items: [DEMAND_DTO] })
    const page = await api.list(TOKEN, 'QUOTED', 2, 20)
    await api.list(TOKEN, null, 1, 20)
    await api.cancel(TOKEN, 'd1')
    await api.deleteDraft(TOKEN, 'd1')

    expect(page.items[0].demandId).toBe('d1')
    expect(request.mock.calls.map((call) => [call[0].method, call[0].url])).toEqual([
      ['GET', '/api/v1/demands?status=QUOTED&page=2&size=20'],
      ['GET', '/api/v1/demands?page=1&size=20'],
      ['POST', '/api/v1/demands/d1/cancel'],
      ['DELETE', '/api/v1/demands/d1'],
    ])
  })

  it('reads quotes with the sort and chooses with If-Match and the quote version', async () => {
    const { api, request } = apiReturning({
      demandId: 'd1',
      demandStatus: 'QUOTED',
      demandVersion: 3,
      sort: 'PRICE',
      items: [{
        quoteId: 'q1',
        provider: { providerId: 'p1', name: '云翼科技', certificationLabel: null },
        version: 2,
        status: 'ACTIVE',
        amountMinor: 1860000,
        method: '无人机',
        schedule: '共 6 次',
        durationDays: 90,
        deliverables: '巡检报告',
        validUntil: '2026-11-26T10:00:00Z',
        submittedAt: '2026-11-19T03:00:00Z',
        revisedAt: '2026-11-19T04:00:00Z',
        choosable: true,
      }],
    })
    const list = await api.listQuotes(TOKEN, 'd1', 'PRICE')
    expect(request).toHaveBeenLastCalledWith({ url: '/api/v1/demands/d1/quotes?sort=PRICE', method: 'GET', headers: AUTH })
    expect(list.demandVersion).toBe(3)
    expect(list.items[0]).toMatchObject({ quoteId: 'q1', version: 2, choosable: true, provider: { certificationLabel: null } })

    request.mockResolvedValue(DEMAND_DTO)
    await api.choose(TOKEN, 'd1', 3, 'q1', 2)
    expect(request).toHaveBeenLastCalledWith({
      url: '/api/v1/demands/d1/choice',
      method: 'POST',
      data: { quoteId: 'q1', quoteVersion: 2 },
      headers: { ...AUTH, 'If-Match': '"3"' },
    })
  })

  it('reads the providers of a city for a type', async () => {
    const { api, request } = apiReturning({ items: [{ providerId: 'p1', name: '云翼科技', certificationLabel: '企业认证', description: '专业飞手团队' }] })
    const providers = await api.listProviders(TOKEN, '370500', 'SURVEY')

    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/demand-providers?cityCode=370500&type=SURVEY',
      method: 'GET',
      headers: AUTH,
    })
    expect(providers).toEqual([{ providerId: 'p1', name: '云翼科技', certificationLabel: '企业认证', description: '专业飞手团队' }])
  })

  it('records a customer organization as the publisher, anything else as personal', async () => {
    const organization = apiReturning({ displayName: '王女士', organizationName: '东营示例物流公司', organizationType: 'CUSTOMER' })
    expect(await organization.api.loadPublisher(TOKEN)).toEqual({ kind: 'ORGANIZATION', organizationName: '东营示例物流公司' })
    expect(organization.request).toHaveBeenCalledWith({ url: '/api/v1/auth/me', method: 'GET', headers: AUTH })

    const provider = apiReturning({ displayName: '操作员', organizationName: '云翼科技', organizationType: 'PROVIDER' })
    expect(await provider.api.loadPublisher(TOKEN)).toEqual({ kind: 'PERSONAL', organizationName: null })
    const nobody = apiReturning({ displayName: '个人', organizationName: null, organizationType: null })
    expect(await nobody.api.loadPublisher(TOKEN)).toEqual({ kind: 'PERSONAL', organizationName: null })
  })
})
