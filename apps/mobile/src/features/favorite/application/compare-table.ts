import type { MallMerchantKind, MallProductDetail } from '../../mall/domain/mall'
import {
  MALL_BADGE_LABELS,
  formatMallAmount,
  formatMallUnit,
} from '../../mall/presentation/shared/mall-format'

// The comparison table of board MallCompare, built from the mall's own product details: one column
// per product, one row per fact. A fact a product does not have is “—”. Rows whose values are not
// all the same are marked, for the highlight and for 只看不同.

export const COMPARE_MISSING = '—'

export interface CompareColumn {
  productId: string
  name: string
  // “¥1,299 起”, “¥380/天 起” or “询价”
  price: string
}

export interface CompareRow {
  key: string
  label: string
  values: string[]
  differs: boolean
}

export interface CompareTable {
  columns: CompareColumn[]
  rows: CompareRow[]
}

// Short words for the seller counts (board: “3 家（厂家 1 · 经销 2）”).
const KIND_WORDS: Record<MallMerchantKind, string> = {
  FACTORY_DIRECT: '厂家',
  AUTHORIZED_DEALER: '经销',
  LOCAL_STORE: '本地',
}
const KIND_ORDER: readonly MallMerchantKind[] = ['FACTORY_DIRECT', 'AUTHORIZED_DEALER', 'LOCAL_STORE']

const SALE_MODE_WORDS = { BUY: '购买', RENT: '租赁', INQUIRY: '询价' } as const

export function formatComparePrice(product: MallProductDetail): string {
  const priced = product.offers.filter((offer) => offer.amountMinor !== null)
  if (priced.length === 0) return '询价'
  const lowest = priced.reduce((min, offer) => ((offer.amountMinor ?? 0) < (min.amountMinor ?? 0) ? offer : min))
  const several = new Set(product.offers.map((offer) => offer.amountMinor)).size > 1
  const from = several || product.saleMode === 'RENT' ? ' 起' : ''
  return `${formatMallAmount(lowest.amountMinor ?? 0)}${formatMallUnit(lowest.unit)}${from}`
}

function sellers(product: MallProductDetail): string {
  const kinds = new Map<string, MallMerchantKind>()
  for (const offer of product.offers) kinds.set(offer.merchantId, offer.merchantKind)
  if (kinds.size === 0) return COMPARE_MISSING
  const parts = KIND_ORDER
    .map((kind) => [kind, [...kinds.values()].filter((value) => value === kind).length] as const)
    .filter(([, count]) => count > 0)
    .map(([kind, count]) => `${KIND_WORDS[kind]} ${count}`)
  return `${kinds.size} 家（${parts.join(' · ')}）`
}

function fulfilledFrom(product: MallProductDetail): string {
  const places = [...new Set(product.offers.map((offer) => offer.fulfilledFrom?.trim()).filter((place): place is string => !!place))]
  return places.length ? places.join(' / ') : COMPARE_MISSING
}

function row(key: string, label: string, values: string[]): CompareRow {
  return { key, label, values, differs: new Set(values).size > 1 }
}

export function buildCompareTable(products: readonly MallProductDetail[]): CompareTable {
  const columns = products.map((product) => ({
    productId: product.productId,
    name: product.name,
    price: formatComparePrice(product),
  }))
  const rows: CompareRow[] = [
    row('sellers', '在售商家', products.map(sellers)),
    row('saleMode', '交易方式', products.map((product) => SALE_MODE_WORDS[product.saleMode])),
    row('experience', '体验方式', products.map((product) =>
      product.experienceBadge ? MALL_BADGE_LABELS[product.experienceBadge] : COMPARE_MISSING)),
  ]
  // The parameters each merchant filled in, by label, in the order they first appear.
  const labels: string[] = []
  for (const product of products) {
    for (const spec of product.specs) {
      const label = spec.label.trim()
      if (label && !labels.includes(label)) labels.push(label)
    }
  }
  for (const label of labels) {
    rows.push(row(`spec:${label}`, label, products.map((product) =>
      product.specs.find((spec) => spec.label.trim() === label)?.value.trim() || COMPARE_MISSING)))
  }
  const from = products.map(fulfilledFrom)
  if (from.some((value) => value !== COMPARE_MISSING)) rows.push(row('fulfilledFrom', '发货地', from))
  // Every seller issues its own invoice (R5 #11): the same words for all, as on the board.
  rows.push(row('invoice', '发票', products.map(() => '由销售方开具')))
  return { columns, rows }
}

export function visibleCompareRows(table: CompareTable, onlyDifferent: boolean): CompareRow[] {
  return onlyDifferent ? table.rows.filter((entry) => entry.differs) : table.rows
}
