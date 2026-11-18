import type {
  MallExperienceBadge,
  MallFilters,
  MallMerchantKind,
  MallSaleMode,
} from '../domain/mall'

export const EMPTY_MALL_FILTERS: MallFilters = {
  saleModes: [],
  experienceBadges: [],
  merchantKinds: [],
  minPriceYuan: null,
  maxPriceYuan: null,
}

// The groups of the filter sheet, in the order of the prototype board MallFilter. Only what the
// catalog really records is offered: delivery mode, use scene and "可预约试飞" are not modelled.
export const MALL_FILTER_GROUPS = [
  {
    key: 'experienceBadges',
    title: '体验方式',
    hint: '先体验再决定',
    options: [
      { code: 'ONLINE_EXPERIENCE', label: '可线上体验' },
      { code: 'VENUE_DISPLAY', label: '体验馆可看' },
    ],
  },
  {
    key: 'saleModes',
    title: '交易方式',
    hint: '',
    options: [
      { code: 'BUY', label: '直接购买' },
      { code: 'INQUIRY', label: '询价报价' },
      { code: 'RENT', label: '租赁' },
    ],
  },
  {
    key: 'merchantKinds',
    title: '经营商家',
    hint: '均已审核资质',
    options: [
      { code: 'FACTORY_DIRECT', label: '厂家直营' },
      { code: 'AUTHORIZED_DEALER', label: '授权经销' },
      { code: 'LOCAL_STORE', label: '本地商家' },
    ],
  },
] as const satisfies readonly {
  key: MallFilterListKey
  title: string
  hint: string
  options: readonly { code: MallSaleMode | MallExperienceBadge | MallMerchantKind; label: string }[]
}[]

export type MallFilterListKey = 'saleModes' | 'experienceBadges' | 'merchantKinds'

export interface MallFilterChip {
  /** Stable key, also what removeMallFilterChip takes. */
  key: string
  label: string
}

export function cloneMallFilters(filters: MallFilters): MallFilters {
  return {
    saleModes: [...filters.saleModes],
    experienceBadges: [...filters.experienceBadges],
    merchantKinds: [...filters.merchantKinds],
    minPriceYuan: filters.minPriceYuan,
    maxPriceYuan: filters.maxPriceYuan,
  }
}

export function toggleMallFilter(
  filters: MallFilters,
  key: MallFilterListKey,
  code: string,
): MallFilters {
  const next = cloneMallFilters(filters)
  const values = next[key] as string[]
  const index = values.indexOf(code)
  if (index >= 0) values.splice(index, 1)
  else values.push(code)
  return next
}

/** Digits only, whole yuan. Anything else, or an empty box, is "no bound". */
export function parseMallPriceInput(text: string): number | null {
  const trimmed = text.trim()
  if (!/^\d{1,9}$/.test(trimmed)) return null
  return Number(trimmed)
}

export function isMallPriceRangeValid(filters: MallFilters): boolean {
  return filters.minPriceYuan === null
    || filters.maxPriceYuan === null
    || filters.minPriceYuan <= filters.maxPriceYuan
}

function priceChipLabel(filters: MallFilters): string | null {
  const { minPriceYuan: min, maxPriceYuan: max } = filters
  if (min !== null && max !== null) return `¥${min}–${max}`
  if (min !== null) return `¥${min} 以上`
  if (max !== null) return `¥${max} 以下`
  return null
}

// One chip per chosen option, in sheet order, then the price range.
export function describeMallFilters(filters: MallFilters): MallFilterChip[] {
  const chips: MallFilterChip[] = []
  for (const group of MALL_FILTER_GROUPS) {
    for (const option of group.options) {
      if ((filters[group.key] as string[]).includes(option.code)) {
        chips.push({ key: `${group.key}:${option.code}`, label: option.label })
      }
    }
  }
  const price = priceChipLabel(filters)
  if (price) chips.push({ key: 'price', label: price })
  return chips
}

export function removeMallFilterChip(filters: MallFilters, chipKey: string): MallFilters {
  if (chipKey === 'price') {
    return { ...cloneMallFilters(filters), minPriceYuan: null, maxPriceYuan: null }
  }
  const [key, code] = chipKey.split(':')
  const group = MALL_FILTER_GROUPS.find((candidate) => candidate.key === key)
  if (!group || !code || !(filters[group.key] as string[]).includes(code)) return filters
  return toggleMallFilter(filters, group.key, code)
}

export function countMallFilters(filters: MallFilters): number {
  return describeMallFilters(filters).length
}
