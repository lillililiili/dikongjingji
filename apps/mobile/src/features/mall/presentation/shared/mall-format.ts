import type {
  MallCategory,
  MallExperienceBadge,
  MallMerchantKind,
  MallPriceUnit,
  MallProductCard,
  MallSaleMode,
} from '../../domain/mall'

export const MALL_CATEGORY_LABELS: Record<MallCategory, string> = {
  DRONE: '无人机',
  ROBOT: '机器人',
  PARTS: '配件',
  RENTAL: '租赁',
  COURSE: '课程',
}

export function buyOfferStockText(stock: number | null): string {
  if (stock === null) return '库存不限'
  return stock === 0 ? '可售 0 件（缺货）' : `可售 ${stock} 件`
}

export const MALL_BADGE_LABELS: Record<MallExperienceBadge, string> = {
  ONLINE_EXPERIENCE: '可线上体验',
  VENUE_DISPLAY: '体验馆可看',
}

export const MALL_MERCHANT_KIND_LABELS: Record<MallMerchantKind, string> = {
  FACTORY_DIRECT: '厂家直营',
  AUTHORIZED_DEALER: '授权经销',
  LOCAL_STORE: '本地商家',
}

export const MALL_ACTION_LABELS: Record<MallSaleMode, string> = {
  BUY: '立即购买',
  RENT: '联系询价',
  INQUIRY: '联系询价',
}

const UNIT_SUFFIXES: Record<MallPriceUnit, string> = {
  ITEM: '',
  DAY: '/天',
  HALF_DAY: '/半天',
}

export function formatMallAmount(amountMinor: number): string {
  const yuan = amountMinor / 100
  const amount = Number.isInteger(yuan)
    ? yuan.toLocaleString('zh-CN')
    : yuan.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return `¥${amount}`
}

export function formatMallUnit(unit: MallPriceUnit): string {
  return UNIT_SUFFIXES[unit]
}

export interface MallCardPriceText {
  // "¥1,299" or "询价"
  amount: string
  // "/天", "/半天" or ""
  unit: string
  // "起" when the price is only a starting point
  from: string
}

// A card says 起 when the city has several listed prices, or when it is a rental (the quote depends on the period).
export function formatMallCardPrice(card: MallProductCard): MallCardPriceText {
  if (card.price.fromAmountMinor === null) return { amount: '询价', unit: '', from: '' }
  return {
    amount: formatMallAmount(card.price.fromAmountMinor),
    unit: formatMallUnit(card.price.unit),
    from: card.price.multiplePrices || card.saleMode === 'RENT' ? '起' : '',
  }
}

// "3 家在售", "租赁 · 1 家", "2 家可询价"
export function formatMallSellers(card: MallProductCard): string {
  if (card.saleMode === 'RENT') return `租赁 · ${card.sellerCount} 家`
  if (card.saleMode === 'INQUIRY') return `${card.sellerCount} 家可询价`
  return `${card.sellerCount} 家在售`
}

export function formatMallOfferPrice(amountMinor: number | null, unit: MallPriceUnit): string {
  return amountMinor === null ? '询价' : `${formatMallAmount(amountMinor)}${formatMallUnit(unit)}`
}

export interface MallDetailPriceText {
  // 26px: "¥1,299", "¥380" or "询价"
  amount: string
  // 13px after it: "起", "/天 起", "按配置与数量报价" or ""
  suffix: string
}

// The price of a product detail, in the two sizes of the board PdFull: the amount, then the unit and
// 起 in small type. 起 when the amount is a starting point: a rental (the quote depends on the period)
// or the lowest of several listed prices.
export function formatMallDetailPrice(
  amountMinor: number | null,
  unit: MallPriceUnit,
  options: { saleMode: MallSaleMode; lowestOfSeveral: boolean },
): MallDetailPriceText {
  if (amountMinor === null) {
    return { amount: '询价', suffix: options.saleMode === 'INQUIRY' ? '按配置与数量报价' : '' }
  }
  const from = options.saleMode === 'RENT' || options.lowestOfSeveral ? '起' : ''
  const suffix = [formatMallUnit(unit), from].filter(Boolean).join(' ')
  return { amount: formatMallAmount(amountMinor), suffix }
}
