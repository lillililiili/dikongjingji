import type { FormRules } from 'element-plus'

import { minorToYuanText, yuanToMinor } from '@/core/ops/ops-common'
import { charCount } from '@/core/ui/format'
import { matches, maxChars, required } from '@/core/ui/form-rules'

import type {
  InspectionAvailability,
  InspectionItemRequest,
  InspectionPrimaryAction,
  InspectionPriceMode,
  InspectionServiceMethod,
  InspectionServiceRequest,
  InspectionServiceStatus,
  OpsInspectionItem,
  OpsInspectionService,
} from './inspection-catalog-api'

/** 和后端 InspectionCatalogOpsService 的限制一致。 */
export const LIMITS = {
  title: 160,
  summary: 500,
  priceNote: 240,
  cancellationNote: 500,
  unitLabel: 40,
  bookingItems: 10,
  priceFactors: 10,
  sectionItems: 20,
  bookingItemTitle: 80,
  detailItemTitle: 120,
  itemDescription: 240,
  categoryName: 20,
  categorySubtitle: 60,
  cities: 300,
} as const

export const SERVICE_STATUS_OPTIONS: ReadonlyArray<{ value: InspectionServiceStatus, label: string, tag: 'success' | 'info' }> = [
  { value: 'ACTIVE', label: '已上架', tag: 'success' },
  { value: 'DISABLED', label: '未上架', tag: 'info' },
]

export function serviceStatus(status: InspectionServiceStatus) {
  return SERVICE_STATUS_OPTIONS.find((option) => option.value === status) ?? SERVICE_STATUS_OPTIONS[1]!
}

export const PRICE_MODE_OPTIONS: ReadonlyArray<{ value: InspectionPriceMode, label: string, hint: string }> = [
  { value: 'STARTING_PRICE', label: '起步价', hint: '显示“¥xx 起”，单位可不填' },
  { value: 'PER_DISTANCE', label: '按距离', hint: '显示“¥xx/公里起”，要填单位' },
  { value: 'PER_VISIT', label: '按次', hint: '显示“¥xx/次起”，要填单位' },
  { value: 'PROJECT_QUOTE', label: '按项目报价', hint: '没有金额，方案确认后定价' },
]

export function priceModeLabel(mode: InspectionPriceMode | null): string {
  return PRICE_MODE_OPTIONS.find((option) => option.value === mode)?.label ?? '未填'
}

export const AVAILABILITY_OPTIONS: ReadonlyArray<{ value: InspectionAvailability, label: string }> = [
  { value: 'RESERVABLE', label: '可预约' },
  { value: 'AVAILABLE', label: '可咨询' },
  { value: 'UNAVAILABLE', label: '暂不可约' },
]

export const PRIMARY_ACTION_OPTIONS: ReadonlyArray<{ value: InspectionPrimaryAction, label: string }> = [
  { value: 'RESERVE', label: '预约' },
  { value: 'CONSULT', label: '咨询' },
  { value: 'REQUEST_QUOTE', label: '获取报价' },
  { value: 'NONE', label: '不显示' },
]

export const METHOD_OPTIONS: ReadonlyArray<{ value: InspectionServiceMethod, label: string }> = [
  { value: 'UAV', label: '无人机' },
  { value: 'ROBOT', label: '机器人' },
]

/** 上架前缺的字段键（契约 OpsInspectionPublishField）→ 页面上的名字。 */
export const PUBLISH_FIELD_LABELS: Readonly<Record<string, string>> = {
  provider: '在用的服务商',
  summary: '简介',
  categoryCode: '类目',
  coverUrl: '封面',
  priceMode: '计价方式',
  startingAmountMinor: '起步价',
  unit: '计价单位',
  availability: '可预约状态',
  primaryAction: '主按钮',
  cities: '覆盖城市',
  bookingItems: '服务项目',
  processSteps: '服务流程',
  deliverables: '交付物',
}

export const CHANGE_SUBJECT_LABELS: Readonly<Record<string, string>> = { SERVICE: '服务', CATEGORY: '类目' }
export const CHANGE_ACTION_LABELS: Readonly<Record<string, string>> = {
  PUBLISH: '上架',
  OFFLINE: '下架',
  ENABLE: '启用',
  DISABLE: '停用',
}

/** 页面上编辑的一项（服务项目、详情项）。code 为空是新项，保存后服务端生成编码。 */
export interface ItemRow extends Record<string, string> {
  code: string
  title: string
  description: string
  iconKey: string
}

export type ItemSection = 'bookingItems' | 'priceFactors' | 'capabilities' | 'processSteps' | 'deliverables' | 'guarantees'

export const ITEM_SECTIONS: ReadonlyArray<{ key: ItemSection, label: string, max: number, titleMax: number, hint: string }> = [
  { key: 'bookingItems', label: '服务项目', max: LIMITS.bookingItems, titleMax: LIMITS.bookingItemTitle,
    hint: '预约页“巡检项目”的选项，也是卡片上的标签' },
  { key: 'priceFactors', label: '价格因素', max: LIMITS.priceFactors, titleMax: LIMITS.detailItemTitle,
    hint: '详情页“影响价格的因素”，如线路长度、杆塔数量' },
  { key: 'capabilities', label: '服务能力', max: LIMITS.sectionItems, titleMax: LIMITS.detailItemTitle,
    hint: '详情页的能力标签' },
  { key: 'processSteps', label: '服务流程', max: LIMITS.sectionItems, titleMax: LIMITS.detailItemTitle,
    hint: '按顺序，上架前至少一步' },
  { key: 'deliverables', label: '交付物', max: LIMITS.sectionItems, titleMax: LIMITS.detailItemTitle,
    hint: '上架前至少一项' },
  { key: 'guarantees', label: '服务保障', max: LIMITS.sectionItems, titleMax: LIMITS.detailItemTitle, hint: '' },
]

export interface InspectionServiceForm {
  providerId: string
  title: string
  summary: string
  categoryCode: string
  coverUrl: string
  priceMode: InspectionPriceMode | ''
  /** 元，最多两位小数；提交时换成分。 */
  startingYuan: string
  unitCode: string
  unitLabel: string
  priceNote: string
  cancellationNote: string
  availability: InspectionAvailability | ''
  primaryAction: InspectionPrimaryAction | ''
  includesReport: boolean
  serviceMethods: InspectionServiceMethod[]
  cityCodes: string[]
  bookingItems: ItemRow[]
  priceFactors: ItemRow[]
  capabilities: ItemRow[]
  processSteps: ItemRow[]
  deliverables: ItemRow[]
  guarantees: ItemRow[]
}

export function emptyForm(): InspectionServiceForm {
  return {
    providerId: '',
    title: '',
    summary: '',
    categoryCode: '',
    coverUrl: '',
    priceMode: '',
    startingYuan: '',
    unitCode: '',
    unitLabel: '',
    priceNote: '',
    cancellationNote: '',
    availability: 'RESERVABLE',
    primaryAction: 'RESERVE',
    includesReport: false,
    serviceMethods: [],
    cityCodes: [],
    bookingItems: [],
    priceFactors: [],
    capabilities: [],
    processSteps: [],
    deliverables: [],
    guarantees: [],
  }
}

function rows(items: OpsInspectionItem[]): ItemRow[] {
  return items.map((item) => ({ code: item.code, title: item.title, description: item.description ?? '',
    iconKey: item.iconKey ?? '' }))
}

export function formFromDetail(detail: OpsInspectionService): InspectionServiceForm {
  return {
    providerId: detail.providerId,
    title: detail.title,
    summary: detail.summary ?? '',
    categoryCode: detail.categoryCode ?? '',
    coverUrl: detail.coverUrl ?? '',
    priceMode: detail.priceMode ?? '',
    startingYuan: minorToYuanText(detail.startingAmountMinor),
    unitCode: detail.unitCode ?? '',
    unitLabel: detail.unitLabel ?? '',
    priceNote: detail.priceNote ?? '',
    cancellationNote: detail.cancellationNote ?? '',
    availability: detail.availability ?? '',
    primaryAction: detail.primaryAction ?? '',
    includesReport: detail.includesReport,
    serviceMethods: [...detail.serviceMethods],
    cityCodes: detail.cities.map((city) => city.cityCode),
    bookingItems: rows(detail.bookingItems),
    priceFactors: rows(detail.priceFactors),
    capabilities: rows(detail.capabilities),
    processSteps: rows(detail.processSteps),
    deliverables: rows(detail.deliverables),
    guarantees: rows(detail.guarantees),
  }
}

function blank(value: string): string | null {
  const text = value.trim()
  return text === '' ? null : text
}

/** 表单 → 请求体；有填错的地方（字段键 → 说明）放进 problems，页面先在本地挡住。 */
export function toRequest(form: InspectionServiceForm): { request: InspectionServiceRequest, problems: Record<string, string> } {
  const problems: Record<string, string> = {}
  const projectQuote = form.priceMode === 'PROJECT_QUOTE'
  let amount: number | null = null
  if (form.priceMode && !projectQuote && form.startingYuan.trim() !== '') {
    amount = yuanToMinor(form.startingYuan)
    if (amount === null) problems.startingYuan = '起步价写成元，最多两位小数'
  }
  const unitCode = form.priceMode && !projectQuote ? blank(form.unitCode)?.toUpperCase() ?? null : null
  const unitLabel = form.priceMode && !projectQuote ? blank(form.unitLabel) : null
  if ((unitCode === null) !== (unitLabel === null)) problems.unit = '计价单位的编码和名称要一起填'
  if (unitCode !== null && !/^[A-Z][A-Z0-9_]{0,31}$/.test(unitCode)) problems.unit = '单位编码用大写字母、数字和下划线，如 KM、VISIT'
  if (form.cityCodes.length === 0) problems.cityCodes = '至少选一个覆盖城市'
  const items = (section: ItemSection): InspectionItemRequest[] => {
    const meta = ITEM_SECTIONS.find((candidate) => candidate.key === section)!
    const list = form[section]
    if (list.length > meta.max) problems[section] = `${meta.label}最多 ${meta.max} 项`
    for (const row of list) {
      if (row.title.trim() === '') problems[section] = `${meta.label}有一项没写名称`
      else if (charCount(row.title.trim()) > meta.titleMax) problems[section] = `${meta.label}的名称最多 ${meta.titleMax} 字`
      if (charCount(row.description.trim()) > LIMITS.itemDescription) problems[section] = `${meta.label}的说明最多 ${LIMITS.itemDescription} 字`
      if (row.iconKey.trim() !== '' && !/^[a-z][a-z0-9_-]{0,63}$/.test(row.iconKey.trim())) {
        problems[section] = `${meta.label}的图标键用小写字母、数字、- 和 _`
      }
    }
    return list.map((row) => ({ code: blank(row.code), title: row.title.trim(), description: blank(row.description),
      iconKey: blank(row.iconKey) }))
  }
  const request: InspectionServiceRequest = {
    providerId: form.providerId,
    title: form.title.trim(),
    summary: blank(form.summary),
    categoryCode: blank(form.categoryCode),
    coverUrl: blank(form.coverUrl),
    priceMode: form.priceMode || null,
    startingAmountMinor: amount,
    unitCode,
    unitLabel,
    priceNote: blank(form.priceNote),
    cancellationNote: blank(form.cancellationNote),
    availability: form.availability || null,
    primaryAction: form.primaryAction || null,
    includesReport: form.includesReport,
    serviceMethods: [...form.serviceMethods],
    cityCodes: [...form.cityCodes],
    bookingItems: items('bookingItems'),
    priceFactors: items('priceFactors'),
    capabilities: items('capabilities'),
    processSteps: items('processSteps'),
    deliverables: items('deliverables'),
    guarantees: items('guarantees'),
  }
  return { request, problems }
}

export const serviceRules: FormRules = {
  providerId: [required('服务商', 'change')],
  title: [required('标题'), maxChars('标题', LIMITS.title)],
  summary: [maxChars('简介', LIMITS.summary)],
  priceNote: [maxChars('价格说明', LIMITS.priceNote)],
  cancellationNote: [maxChars('取消说明', LIMITS.cancellationNote)],
  unitLabel: [maxChars('单位名称', LIMITS.unitLabel)],
  startingYuan: [matches(/^\d{1,8}(\.\d{1,2})?$/, '起步价写成元，最多两位小数')],
  cityCodes: [required('覆盖城市', 'change')],
}

export const categoryRules: FormRules = {
  code: [required('编码'), matches(/^[A-Z][A-Z0-9_]{1,31}$/, '编码用大写字母开头，大写字母、数字和下划线，2–32 位',
    (value) => value.trim().toUpperCase())],
  name: [required('名称'), maxChars('名称', LIMITS.categoryName)],
  subtitle: [maxChars('副标题', LIMITS.categorySubtitle)],
}
