import type {
  ProServiceCategory,
  ProServiceDetail,
  ProServiceLine,
  ProServicePrice,
  ProServicePriceMode,
  ProServiceSort,
  ProServiceType,
  ProviderPage,
} from '../domain/proservice'

// How one service type reads on the App. Section titles are App copy (the contract leaves them to
// the page); colours follow the SvcCatalog board (badge colour per type).
export interface ProServiceTypeInfo {
  type: ProServiceType
  label: string
  searchPlaceholder: string
  badgeColor: string
  tintColor: string
}

export const PRO_SERVICE_TYPE_INFO: Record<ProServiceType, ProServiceTypeInfo> = {
  AERIAL: {
    type: 'AERIAL',
    label: '航拍',
    searchPlaceholder: '搜索航拍服务、场景或关键词',
    badgeColor: '#1d63c4',
    tintColor: '#e6f0ff',
  },
  SURVEY: {
    type: 'SURVEY',
    label: '测绘',
    searchPlaceholder: '搜索测绘服务、场景或关键词',
    badgeColor: '#11804a',
    tintColor: '#e2f6ed',
  },
  MAINTENANCE: {
    type: 'MAINTENANCE',
    label: '设备维护',
    searchPlaceholder: '搜索设备维护服务、场景或关键词',
    badgeColor: '#ba470b',
    tintColor: '#fff0e3',
  },
  TECH_SUPPORT: {
    type: 'TECH_SUPPORT',
    label: '技术支持',
    searchPlaceholder: '搜索技术支持服务、场景或关键词',
    badgeColor: '#6a4fe0',
    tintColor: '#efebff',
  },
}

// No authorised photo: the card draws the scene's icon (uni-icons glyph), never a stand-in photo.
export const PRO_SERVICE_CATEGORY_ICONS: Record<ProServiceCategory, string> = {
  ENGINEERING: 'home',
  ENERGY: 'fire',
  TOURISM: 'image',
  EVENT: 'flag',
  MODELING: 'map',
  PARK: 'shop',
  TERRAIN: 'map-pin-ellipse',
  CORRIDOR: 'navigate',
  GENERAL: 'gear',
  DRONE: 'paperplane',
  ROBOT: 'settings',
  ROBOT_DOG: 'gear-filled',
  FLIGHT: 'paperplane-filled',
  SOFTWARE: 'list',
}

export interface ProServicePriceView {
  // ¥1,280 or 按项目报价.
  amount: string
  unit: string
  note: string
  quote: boolean
}

export function formatProServiceYuan(amountMinor: number): string {
  const yuan = Math.floor(amountMinor / 100)
  const fen = amountMinor % 100
  const whole = String(yuan).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return fen ? `¥${whole}.${String(fen).padStart(2, '0')}` : `¥${whole}`
}

export function formatProServicePrice(price: ProServicePrice): ProServicePriceView {
  if (price.mode === 'PRICE_FROM' && price.amountMinor !== null) {
    return {
      amount: formatProServiceYuan(price.amountMinor),
      unit: price.unit ?? '起',
      note: price.note ?? '',
      quote: false,
    }
  }
  return { amount: '按项目报价', unit: '', note: price.note ?? '方案确认后定价', quote: true }
}

// 价格 toggles: the first tap sorts low to high, the next high to low, and so on (SvcCatalog).
export function nextPriceSort(sort: ProServiceSort): ProServiceSort {
  return sort === 'PRICE_ASC' ? 'PRICE_DESC' : 'PRICE_ASC'
}

export const PRO_SERVICE_PRICE_MODE_OPTIONS: ReadonlyArray<{ code: ProServicePriceMode | null; label: string }> = [
  { code: null, label: '不限' },
  { code: 'PRICE_FROM', label: '起步价' },
  { code: 'PROJECT', label: '按项目报价' },
]

export function shortCityName(name: string | null | undefined): string {
  return (name ?? '').replace(/市$/, '')
}

export function closedCityText(cityName: string | null | undefined, type: ProServiceType): string {
  const city = shortCityName(cityName) || '这个城市'
  return `${city}还没有开通${PRO_SERVICE_TYPE_INFO[type].label}服务`
}

export function citiesText(cities: ReadonlyArray<{ cityName: string }>): string {
  return cities.length ? `可服务城市 ${cities.map((city) => shortCityName(city.cityName)).join('、')}` : ''
}

// The first character on the round avatar of a provider, which has no logo yet.
export function avatarText(name: string): string {
  return Array.from(name.trim())[0] ?? '服'
}

// The detail's sections, in the board order of each type. Empty sections are left out.
export type ProServiceSection =
  | { kind: 'scope'; title: string; items: string[]; note: string }
  | { kind: 'outputs'; title: string; lines: ProServiceLine[] }
  | { kind: 'modes'; title: string; lines: ProServiceLine[] }
  | { kind: 'rows'; title: string; lines: ProServiceLine[]; note: string }
  | { kind: 'badges'; title: string; lines: ProServiceLine[] }

export function buildProServiceSections(detail: ProServiceDetail): ProServiceSection[] {
  const scope = (title: string, note = ''): ProServiceSection =>
    ({ kind: 'scope', title, items: detail.scope, note })
  const outputs = (title: string): ProServiceSection => ({ kind: 'outputs', title, lines: detail.outputs })
  const modes = (title: string): ProServiceSection => ({ kind: 'modes', title, lines: detail.serviceModes })
  const sections: Record<ProServiceType, ProServiceSection[]> = {
    AERIAL: [scope('拍摄内容'), outputs('成果格式')],
    SURVEY: [
      scope('作业范围', '测区位置和面积在需求里填写'),
      { kind: 'rows', title: '精度说明', lines: detail.terms, note: '影响精度的因素：控制点、飞行高度、地形和天气' },
      outputs('交付成果'),
    ],
    MAINTENANCE: [
      scope('适用设备', '其他品牌和型号在需求里说明，服务商确认后接单'),
      modes('服务方式'),
      { kind: 'badges', title: '保修与收费边界', lines: detail.terms },
    ],
    TECH_SUPPORT: [
      scope('支持内容'),
      modes('支持方式'),
      { kind: 'rows', title: '响应说明', lines: detail.terms, note: '' },
    ],
  }
  return sections[detail.type].filter((section) =>
    section.kind === 'scope' ? section.items.length > 0 : section.lines.length > 0)
}

// Label colours of the 保修与收费边界 rows (SvcMaint board), by position.
const BADGE_TONES = [
  { background: '#e2f6ed', color: '#11804a' },
  { background: '#fff0e3', color: '#ba470b' },
  { background: '#e6f0ff', color: '#1d63c4' },
  { background: '#eef2f8', color: '#536178' },
] as const

export function badgeTone(index: number): { background: string; color: string } {
  return BADGE_TONES[index % BADGE_TONES.length]
}

// The icon above each step of 服务流程: the request first, the acceptance last.
export function stepIcon(index: number, count: number): string {
  if (index === 0) return 'compose'
  if (index === count - 1) return 'auth'
  if (index === 1) return 'tune'
  return 'gear'
}

// The round icon of a 服务方式 / 支持方式 card (SvcMaint, SvcTech boards), read from its name.
export function modeIcon(label: string): string {
  if (label.includes('远程')) return 'headphones'
  if (label.includes('驻场')) return 'person'
  if (label.includes('送修') || label.includes('寄')) return 'paperplane'
  return 'location'
}

// The demand type a provider page publishes with: inspection when it has inspection services,
// otherwise the first type it offers here. Undefined leaves the form's own default.
export function providerDemandType(page: ProviderPage): 'INSPECTION' | ProServiceType | undefined {
  if (page.inspectionServices.length) return 'INSPECTION'
  return page.proServices[0]?.type
}
