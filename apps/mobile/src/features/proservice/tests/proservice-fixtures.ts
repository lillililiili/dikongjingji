import type { ProServiceDetail } from '../domain/proservice'

// Shapes of contracts/openapi-proservice.yaml 1.0.0 for the unit tests.
export function cardDto(overrides: Record<string, unknown> = {}) {
  return {
    serviceId: 'f2100000-0000-4000-8000-000000000001',
    type: 'AERIAL',
    category: { code: 'ENGINEERING', label: '工程' },
    title: '园区与工程航拍',
    summary: '园区全景 · 工程进度 · 宣传素材',
    tags: ['视频素材', '照片', '持证飞手'],
    price: { mode: 'PRICE_FROM', amountMinor: 128000, unit: '/次起', note: '含拍摄和素材整理' },
    coverUrl: null,
    sample: true,
    ...overrides,
  }
}

export function detailOf(overrides: Partial<ProServiceDetail> = {}): ProServiceDetail {
  return {
    serviceId: 's1',
    type: 'AERIAL',
    category: { code: 'ENGINEERING', label: '工程' },
    title: '园区与工程航拍',
    summary: '园区全景',
    price: { mode: 'PRICE_FROM', amountMinor: 128000, unit: '/次起', note: '含拍摄和素材整理' },
    coverUrl: null,
    sample: true,
    capabilities: ['视频素材'],
    priceRows: [{ label: '计价方式', detail: '按次计价' }],
    scope: ['园区全景', '工程进度'],
    outputs: [{ label: '视频素材', detail: '原始素材' }],
    serviceModes: [{ label: '上门服务', detail: '到现场' }],
    terms: [{ label: '精度指标', detail: '按项目要求约定' }],
    steps: ['提交需求', '报价比选', '现场拍摄', '成果验收'],
    guarantees: ['持证飞手'],
    provider: null,
    cities: [],
    warrantyText: null,
    ...overrides,
  }
}
