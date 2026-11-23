import { describe, expect, it } from 'vitest'

import type { ApiClient } from '@/core/http/api-client'
import { ApiRequestError } from '@/core/http/api-error'
import { describeMissing } from '@/core/ops/ops-common'

import { createInspectionCatalogApi, type OpsInspectionService } from './inspection-catalog/inspection-catalog-api'
import {
  emptyForm as emptyInspectionForm,
  formFromDetail as inspectionFormFromDetail,
  PUBLISH_FIELD_LABELS as INSPECTION_PUBLISH_LABELS,
  toRequest as inspectionRequest,
} from './inspection-catalog/inspection-catalog-rules'
import { createProServiceApi } from './proservice/proservice-api'
import {
  emptyForm as emptyProServiceForm,
  PUBLISH_FIELD_LABELS as PRO_SERVICE_PUBLISH_LABELS,
  toRequest as proServiceRequest,
} from './proservice/proservice-rules'

function recordingClient() {
  const calls: Array<{ method: string, path: string, body?: unknown, query?: unknown }> = []
  const client = {
    request: async (method: string, path: string) => { calls.push({ method, path }); return {} },
    get: async (path: string, query?: unknown) => { calls.push({ method: 'GET', path, query }); return {} },
    post: async (path: string, body?: unknown) => { calls.push({ method: 'POST', path, body }); return {} },
    put: async (path: string, body?: unknown) => { calls.push({ method: 'PUT', path, body }); return {} },
    delete: async (path: string) => { calls.push({ method: 'DELETE', path }); return {} },
    download: async () => ({ blob: new Blob(), fileName: null }),
  } as unknown as ApiClient
  return { client, calls }
}

describe('巡检服务表单', () => {
  it('起步价写元提交分，单位编码转大写，按项目报价时不带金额和单位', () => {
    const form = emptyInspectionForm()
    Object.assign(form, {
      providerId: 'p-1', title: ' 光伏巡检 ', priceMode: 'PER_VISIT', startingYuan: '999.5', unitCode: 'visit',
      unitLabel: '次', cityCodes: ['370500'],
      processSteps: [{ code: 'BOOK', title: ' 预约 ', description: '', iconKey: '' },
        { code: '', title: '勘察', description: '看现场', iconKey: 'map' }],
    })
    const { request, problems } = inspectionRequest(form)
    expect(problems).toEqual({})
    expect(request.title).toBe('光伏巡检')
    expect(request.startingAmountMinor).toBe(99950)
    expect(request.unitCode).toBe('VISIT')
    expect(request.processSteps).toEqual([
      { code: 'BOOK', title: '预约', description: null, iconKey: null },
      { code: null, title: '勘察', description: '看现场', iconKey: 'map' },
    ])

    form.priceMode = 'PROJECT_QUOTE'
    const quoted = inspectionRequest(form).request
    expect(quoted.startingAmountMinor).toBeNull()
    expect(quoted.unitCode).toBeNull()
  })

  it('没选城市、单位只填一半、项目没写名称时在本地挡住', () => {
    const form = emptyInspectionForm()
    Object.assign(form, { providerId: 'p-1', title: '巡检', priceMode: 'PER_DISTANCE', startingYuan: '1.234',
      unitCode: 'KM', unitLabel: '', bookingItems: [{ code: '', title: ' ', description: '', iconKey: '' }] })
    const { problems } = inspectionRequest(form)
    expect(problems.cityCodes).toBe('至少选一个覆盖城市')
    expect(problems.startingYuan).toContain('两位小数')
    expect(problems.unit).toContain('一起填')
    expect(problems.bookingItems).toContain('没写名称')
  })

  it('详情读回表单再提交，内容不变（已有项带回原编码）', () => {
    const detail: OpsInspectionService = {
      serviceId: 's-1', serviceNo: 'INS-SVC-1', status: 'ACTIVE', version: 3, updatedAt: '2026-11-23T02:00:00Z',
      providerId: 'p-1', providerName: '鹰眼', title: '电力巡检', summary: '高精度', categoryCode: 'POWER',
      categoryName: '电力', coverUrl: 'https://cdn.example.com/a.jpg', priceMode: 'STARTING_PRICE',
      startingAmountMinor: 69900, unitCode: null, unitLabel: null, priceNote: null, cancellationNote: null,
      availability: 'RESERVABLE', primaryAction: 'RESERVE', includesReport: true, serviceMethods: ['UAV'],
      cities: [{ cityCode: '370500', cityName: '东营市' }],
      bookingItems: [{ code: 'POWER_LINE', title: '杆塔', description: null, iconKey: 'tower' }],
      priceFactors: [], capabilities: [], processSteps: [], deliverables: [], guarantees: [], completedCount: 8,
    }
    const { request } = inspectionRequest(inspectionFormFromDetail(detail))
    expect(request.startingAmountMinor).toBe(69900)
    expect(request.bookingItems).toEqual([{ code: 'POWER_LINE', title: '杆塔', description: null, iconKey: 'tower' }])
    expect(request.cityCodes).toEqual(['370500'])
  })

  it('上架缺必填项时翻成中文', () => {
    const error = new ApiRequestError(422, 'OPS_PUBLISH_INCOMPLETE', '', 't',
      { missing: ['coverUrl', 'bookingItems', 'deliverables'] })
    expect(describeMissing(error, INSPECTION_PUBLISH_LABELS)).toBe('上线前还差：封面、服务项目、交付物')
    expect(describeMissing(new ApiRequestError(422, 'OPS_PUBLISH_INCOMPLETE', '', 't', { missing: ['facets'] }),
      PRO_SERVICE_PUBLISH_LABELS)).toBe('上线前还差：筛选项')
  })
})

describe('专业服务表单', () => {
  it('起步价要有金额、单位和说明；筛选项只留这一类的', () => {
    const form = emptyProServiceForm()
    Object.assign(form, { providerId: 'p-1', type: 'AERIAL', categoryCode: 'TOURISM', title: '文旅航拍',
      summary: '景区宣传片', priceYuan: '1800', priceUnit: '/次起', priceNote: '含 1 架次',
      facets: ['VIDEO_FOOTAGE', 'ON_SITE'], steps: [{ label: '沟通需求' }],
      outputs: [{ label: '成片', detail: '1080P' }] })
    const { request, problems } = proServiceRequest(form)
    expect(problems).toEqual({})
    expect(request.priceAmountMinor).toBe(180000)
    expect(request.facets).toEqual(['VIDEO_FOOTAGE'])
    expect(request.steps).toEqual(['沟通需求'])
    expect(request.outputs).toEqual([{ label: '成片', detail: '1080P' }])

    Object.assign(form, { priceNote: '', priceUnit: '', categoryCode: '', terms: [{ label: '精度', detail: '' }] })
    const missing = proServiceRequest(form).problems
    expect(missing.priceNote).toBeTruthy()
    expect(missing.priceUnit).toBeTruthy()
    expect(missing.categoryCode).toBeTruthy()
    expect(missing.terms).toContain('都要写')

    Object.assign(form, { priceMode: 'PROJECT', categoryCode: 'GENERAL', terms: [] })
    const project = proServiceRequest(form)
    expect(project.problems).toEqual({})
    expect(project.request.priceAmountMinor).toBeNull()
    expect(project.request.priceUnit).toBeNull()
    expect(project.request.warrantyText).toBeNull()

    Object.assign(form, { warrantyText: ' 维修后质保 90 天 ' })
    expect(proServiceRequest(form).request.warrantyText).toBe('维修后质保 90 天')
    Object.assign(form, { warrantyText: '质'.repeat(41) })
    expect(proServiceRequest(form).problems.warrantyText).toContain('40')
  })
})

describe('接口拼法', () => {
  it('巡检目录和专业服务目录的路径、版本号和筛选条件', async () => {
    const { client, calls } = recordingClient()
    const inspection = createInspectionCatalogApi(client)
    await inspection.list({ keyword: ' 电力 ', categoryCode: 'POWER', status: 'ACTIVE' }, 2, 10)
    await inspection.publish('s/1', 3)
    await inspection.changeCategoryStatus('POWER', 'INACTIVE', 4)
    await inspection.update('s-1', { ...inspectionRequest(emptyInspectionForm()).request }, 5)
    const pro = createProServiceApi(client)
    await pro.offline('p-1', 2)
    await pro.list({ keyword: '', type: 'SURVEY', status: '' }, 1, 20)

    expect(calls[0]).toEqual({ method: 'GET', path: '/api/v1/ops/inspection/services',
      query: { keyword: '电力', categoryCode: 'POWER', status: 'ACTIVE', page: 2, size: 10 } })
    expect(calls[1]).toEqual({ method: 'POST', path: '/api/v1/ops/inspection/services/s%2F1/publish', body: { version: 3 } })
    expect(calls[2]).toEqual({ method: 'PUT', path: '/api/v1/ops/inspection/categories/POWER/status',
      body: { status: 'INACTIVE', version: 4 } })
    expect((calls[3]!.body as { version: number }).version).toBe(5)
    expect(calls[4]).toEqual({ method: 'POST', path: '/api/v1/ops/pro-services/p-1/offline', body: { version: 2 } })
    expect(calls[5]!.query).toEqual({ keyword: '', type: 'SURVEY', status: '', page: 1, size: 20 })
  })
})
