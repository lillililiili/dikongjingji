import type { FormRules } from 'element-plus'

import { minorToYuanText, yuanToMinor } from '@/core/ops/ops-common'
import { charCount } from '@/core/ui/format'
import { matches, maxChars, required } from '@/core/ui/form-rules'

import type {
  OpsProService,
  ProServiceCategory,
  ProServiceFacet,
  ProServicePriceMode,
  ProServiceRequest,
  ProServiceStatus,
  ProServiceType,
} from './proservice-api'

/** 和后端 ProServiceOpsService 的限制一致。 */
export const LIMITS = {
  title: 40,
  summary: 80,
  priceUnit: 16,
  priceNote: 40,
  label: 40,
  detail: 120,
  items: 10,
} as const

export const TYPE_OPTIONS: ReadonlyArray<{ value: ProServiceType, label: string, facetLabel: string, facets: ProServiceFacet[] }> = [
  { value: 'AERIAL', label: '航拍', facetLabel: '成果类型', facets: ['VIDEO_FOOTAGE', 'PHOTOS'] },
  { value: 'SURVEY', label: '测绘', facetLabel: '成果类型', facets: ['ORTHOPHOTO', 'MODEL_3D'] },
  { value: 'MAINTENANCE', label: '设备维护', facetLabel: '服务方式', facets: ['ON_SITE', 'MAIL_IN'] },
  { value: 'TECH_SUPPORT', label: '技术支持', facetLabel: '支持方式', facets: ['REMOTE', 'STATIONED'] },
]

export function typeOption(type: ProServiceType) {
  return TYPE_OPTIONS.find((option) => option.value === type) ?? TYPE_OPTIONS[0]!
}

export const FACET_LABELS: Readonly<Record<ProServiceFacet, string>> = {
  VIDEO_FOOTAGE: '视频素材',
  PHOTOS: '照片',
  ORTHOPHOTO: '正射影像',
  MODEL_3D: '三维模型',
  ON_SITE: '上门',
  MAIL_IN: '送修',
  REMOTE: '远程',
  STATIONED: '驻场',
}

export const CATEGORY_OPTIONS: ReadonlyArray<{ value: ProServiceCategory, label: string }> = [
  { value: 'ENGINEERING', label: '工程' },
  { value: 'ENERGY', label: '能源' },
  { value: 'TOURISM', label: '文旅' },
  { value: 'EVENT', label: '活动' },
  { value: 'MODELING', label: '建模' },
  { value: 'PARK', label: '园区' },
  { value: 'TERRAIN', label: '地形' },
  { value: 'CORRIDOR', label: '线路' },
  { value: 'GENERAL', label: '综合' },
  { value: 'DRONE', label: '无人机' },
  { value: 'ROBOT', label: '机器人' },
  { value: 'ROBOT_DOG', label: '机器狗' },
  { value: 'FLIGHT', label: '飞行' },
  { value: 'SOFTWARE', label: '软件' },
]

export const STATUS_OPTIONS: ReadonlyArray<{ value: ProServiceStatus, label: string, tag: 'success' | 'info' }> = [
  { value: 'PUBLISHED', label: '已上架', tag: 'success' },
  { value: 'UNPUBLISHED', label: '未上架', tag: 'info' },
]

export function statusOption(status: ProServiceStatus) {
  return STATUS_OPTIONS.find((option) => option.value === status) ?? STATUS_OPTIONS[1]!
}

/** 上架前缺的字段键（契约 OpsProServicePublishField）→ 页面上的名字。 */
export const PUBLISH_FIELD_LABELS: Readonly<Record<string, string>> = {
  provider: '在用的服务商',
  cities: '上架城市',
  facets: '筛选项',
  steps: '服务流程',
}

export const CHANGE_ACTION_LABELS: Readonly<Record<string, string>> = { PUBLISH: '上架', OFFLINE: '下架' }
export const CHANGE_SUBJECT_LABELS: Readonly<Record<string, string>> = { SERVICE: '服务' }

export type LabelList = 'tags' | 'capabilities' | 'scopes' | 'steps' | 'guarantees'
export type LineList = 'priceRows' | 'outputs' | 'serviceModes' | 'terms'

export const LABEL_LISTS: ReadonlyArray<{ key: LabelList, label: string, hint: string }> = [
  { key: 'tags', label: '卡片标签', hint: '目录卡片上的短标签，也参与关键词搜索' },
  { key: 'capabilities', label: '能力', hint: '详情页能力标签' },
  { key: 'scopes', label: '服务内容', hint: '拍摄内容 / 作业范围 / 适用设备 / 支持内容' },
  { key: 'steps', label: '服务流程', hint: '按顺序，上架前至少一步' },
  { key: 'guarantees', label: '服务保障', hint: '' },
]

export const LINE_LISTS: ReadonlyArray<{ key: LineList, label: string, hint: string }> = [
  { key: 'priceRows', label: '价格与取消', hint: '左边名称、右边说明，如“改期 · 提前 24 小时免费”' },
  { key: 'outputs', label: '成果格式', hint: '' },
  { key: 'serviceModes', label: '服务方式', hint: '' },
  { key: 'terms', label: '说明行', hint: '精度说明、保修与收费边界、响应说明' },
]

export interface ProServiceForm {
  providerId: string
  type: ProServiceType
  categoryCode: ProServiceCategory | ''
  title: string
  summary: string
  priceMode: ProServicePriceMode
  priceYuan: string
  priceUnit: string
  priceNote: string
  coverUrl: string
  sortOrder: number
  cityCodes: string[]
  facets: ProServiceFacet[]
  tags: Array<{ label: string }>
  capabilities: Array<{ label: string }>
  scopes: Array<{ label: string }>
  steps: Array<{ label: string }>
  guarantees: Array<{ label: string }>
  priceRows: Array<{ label: string, detail: string }>
  outputs: Array<{ label: string, detail: string }>
  serviceModes: Array<{ label: string, detail: string }>
  terms: Array<{ label: string, detail: string }>
}

export function emptyForm(): ProServiceForm {
  return {
    providerId: '',
    type: 'AERIAL',
    categoryCode: '',
    title: '',
    summary: '',
    priceMode: 'PRICE_FROM',
    priceYuan: '',
    priceUnit: '/次起',
    priceNote: '',
    coverUrl: '',
    sortOrder: 0,
    cityCodes: [],
    facets: [],
    tags: [],
    capabilities: [],
    scopes: [],
    steps: [],
    guarantees: [],
    priceRows: [],
    outputs: [],
    serviceModes: [],
    terms: [],
  }
}

export function formFromDetail(detail: OpsProService): ProServiceForm {
  const labels = (values: string[]) => values.map((label) => ({ label }))
  const lines = (values: Array<{ label: string, detail: string | null }>) =>
    values.map((line) => ({ label: line.label, detail: line.detail ?? '' }))
  return {
    providerId: detail.providerId,
    type: detail.type,
    categoryCode: detail.categoryCode,
    title: detail.title,
    summary: detail.summary,
    priceMode: detail.priceMode,
    priceYuan: minorToYuanText(detail.priceAmountMinor),
    priceUnit: detail.priceUnit ?? '',
    priceNote: detail.priceNote ?? '',
    coverUrl: detail.coverUrl ?? '',
    sortOrder: detail.sortOrder,
    cityCodes: detail.cities.map((city) => city.cityCode),
    facets: [...detail.facets],
    tags: labels(detail.tags),
    capabilities: labels(detail.capabilities),
    scopes: labels(detail.scopes),
    steps: labels(detail.steps),
    guarantees: labels(detail.guarantees),
    priceRows: lines(detail.priceRows),
    outputs: lines(detail.outputs),
    serviceModes: lines(detail.serviceModes),
    terms: lines(detail.terms),
  }
}

function blank(value: string): string | null {
  const text = value.trim()
  return text === '' ? null : text
}

/** 表单 → 请求体；填错的地方（字段键 → 说明）放进 problems。 */
export function toRequest(form: ProServiceForm): { request: ProServiceRequest, problems: Record<string, string> } {
  const problems: Record<string, string> = {}
  if (!form.categoryCode) problems.categoryCode = '请选场景分类'
  let amount: number | null = null
  let unit: string | null = null
  const note = blank(form.priceNote)
  if (form.priceMode === 'PRICE_FROM') {
    amount = yuanToMinor(form.priceYuan)
    if (amount === null || amount <= 0) problems.priceYuan = '起步价写成元（大于 0，最多两位小数）'
    unit = blank(form.priceUnit)
    if (unit === null) problems.priceUnit = '起步价要写单位，如“/次起”'
    if (note === null) problems.priceNote = '起步价要写一句含什么'
  }
  const allowed = typeOption(form.type).facets
  const facets = form.facets.filter((facet) => allowed.includes(facet))
  const labels = (key: LabelList, name: string): string[] => {
    const list = form[key]
    if (list.length > LIMITS.items) problems[key] = `${name}最多 ${LIMITS.items} 条`
    for (const row of list) {
      if (row.label.trim() === '') problems[key] = `${name}有一条是空的`
      else if (charCount(row.label.trim()) > LIMITS.label) problems[key] = `${name}每条最多 ${LIMITS.label} 字`
    }
    return list.map((row) => row.label.trim())
  }
  const lines = (key: LineList, name: string): Array<{ label: string, detail: string }> => {
    const list = form[key]
    if (list.length > LIMITS.items) problems[key] = `${name}最多 ${LIMITS.items} 条`
    for (const row of list) {
      if (row.label.trim() === '' || row.detail.trim() === '') problems[key] = `${name}每条的名称和说明都要写`
      else if (charCount(row.label.trim()) > LIMITS.label || charCount(row.detail.trim()) > LIMITS.detail) {
        problems[key] = `${name}名称最多 ${LIMITS.label} 字、说明最多 ${LIMITS.detail} 字`
      }
    }
    return list.map((row) => ({ label: row.label.trim(), detail: row.detail.trim() }))
  }
  const request: ProServiceRequest = {
    providerId: form.providerId,
    type: form.type,
    categoryCode: (form.categoryCode || 'GENERAL') as ProServiceCategory,
    title: form.title.trim(),
    summary: form.summary.trim(),
    priceMode: form.priceMode,
    priceAmountMinor: amount,
    priceUnit: unit,
    priceNote: note,
    coverUrl: blank(form.coverUrl),
    sortOrder: form.sortOrder,
    cityCodes: [...form.cityCodes],
    tags: labels('tags', '卡片标签'),
    facets,
    capabilities: labels('capabilities', '能力'),
    priceRows: lines('priceRows', '价格与取消'),
    scopes: labels('scopes', '服务内容'),
    outputs: lines('outputs', '成果格式'),
    serviceModes: lines('serviceModes', '服务方式'),
    terms: lines('terms', '说明行'),
    steps: labels('steps', '服务流程'),
    guarantees: labels('guarantees', '服务保障'),
  }
  return { request, problems }
}

export const proServiceRules: FormRules = {
  providerId: [required('服务商', 'change')],
  title: [required('标题'), maxChars('标题', LIMITS.title)],
  summary: [required('简介'), maxChars('简介', LIMITS.summary)],
  priceUnit: [maxChars('单位', LIMITS.priceUnit)],
  priceNote: [maxChars('起步价说明', LIMITS.priceNote)],
  priceYuan: [matches(/^\d{1,8}(\.\d{1,2})?$/, '起步价写成元，最多两位小数')],
}
