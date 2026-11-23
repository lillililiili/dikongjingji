import { CART_MAX_QUANTITY } from '../../domain/cart'
import type { MallOffer, MallSku } from '../../domain/mall'
import { MALL_MERCHANT_KIND_LABELS } from '../shared/mall-format'

// 规格与数量 (board PdSku) and the merchant rows of 选择在售商家 (board PdFull), worked out from the
// product's specs (catalog 1.3.0 skus) and its offers in the city. Offers arrive cheapest first.
// Without spec information (an older server, or a single spec) the page and sheet keep one row per
// offer and the chosen offer's own spec, as before.

/** AVAILABLE: can be chosen; NOT_SUPPLIED: the chosen merchant does not sell it; SOLD_OUT: stock 0. */
export type BuySkuState = 'AVAILABLE' | 'NOT_SUPPLIED' | 'SOLD_OUT'

export interface BuySkuRow {
  skuId: string
  name: string
  // The chosen merchant's offer of this spec; null when it does not sell it.
  offer: MallOffer | null
  state: BuySkuState
}

export const BUY_SKU_STATE_TEXT: Record<Exclude<BuySkuState, 'AVAILABLE'>, string> = {
  NOT_SUPPLIED: '本商家暂不供应',
  SOLD_OUT: '暂时缺货',
}

/** The product has several specs to choose between (catalog 1.3.0). */
export function hasSkuChoice(skus: readonly MallSku[]): boolean {
  return skus.length > 1
}

function soldOut(offer: MallOffer): boolean {
  return offer.stockQuantity === 0
}

/** The merchant's offer of one spec: one in stock first, else its cheapest. */
function merchantOfferOfSku(offers: readonly MallOffer[], merchantId: string, skuId: string): MallOffer | null {
  const own = offers.filter((offer) => offer.merchantId === merchantId && offer.skuId === skuId)
  return own.find((offer) => !soldOut(offer)) ?? own[0] ?? null
}

/**
 * Every spec of the product with the chosen merchant's offer of it. With no spec list the chosen
 * offer's own spec is the only row.
 */
export function buySkuRows(skus: readonly MallSku[], offers: readonly MallOffer[], chosen: MallOffer): BuySkuRow[] {
  if (skus.length === 0) {
    return [{ skuId: chosen.skuId, name: chosen.skuName, offer: chosen, state: soldOut(chosen) ? 'SOLD_OUT' : 'AVAILABLE' }]
  }
  return skus.map((sku) => {
    const offer = sku.skuId === chosen.skuId ? chosen : merchantOfferOfSku(offers, chosen.merchantId, sku.skuId)
    const state: BuySkuState = offer === null ? 'NOT_SUPPLIED' : soldOut(offer) ? 'SOLD_OUT' : 'AVAILABLE'
    return { skuId: sku.skuId, name: sku.name, offer, state }
  })
}

/** At most 99 (the cart's limit) and never more than the merchant has; at least 1 for the stepper. */
export function buyQuantityLimit(offer: MallOffer): number {
  const stock = offer.stockQuantity
  return Math.max(1, stock === null ? CART_MAX_QUANTITY : Math.min(CART_MAX_QUANTITY, stock))
}

/** Keeps a quantity inside 1 and the offer's limit, e.g. after switching to a spec with less stock. */
export function clampBuyQuantity(quantity: number, offer: MallOffer): number {
  return Math.min(buyQuantityLimit(offer), Math.max(1, Math.floor(quantity)))
}

/**
 * The line under the price: "示例机器人商家 · 东营现货 12 件" when the stock is known (board PdSku),
 * else the merchant and its kind as before. cityName may be "东营市"; the 市 is dropped like 东营可售.
 */
export function buyMerchantLine(offer: MallOffer, cityName: string | null): string {
  if (offer.stockQuantity === null) {
    return `${offer.merchantName}（${MALL_MERCHANT_KIND_LABELS[offer.merchantKind]}）`
  }
  const city = cityName?.replace(/市$/, '') ?? ''
  return offer.stockQuantity === 0
    ? `${offer.merchantName} · 暂时缺货`
    : `${offer.merchantName} · ${city}现货 ${offer.stockQuantity} 件`
}

/** The offer that starts selected: the cheapest one that takes payment online and is not sold out. */
export function initialBuyOffer(offers: readonly MallOffer[]): MallOffer | null {
  const payable = offers.filter((offer) => offer.onlinePayment)
  return payable.find((offer) => !soldOut(offer)) ?? payable[0] ?? null
}

/**
 * 选择在售商家 with several specs: one row per merchant, in the order the offers came (cheapest
 * first). The chosen merchant's row is the chosen offer; every other row is that merchant's offer of
 * the chosen spec, or, when it does not sell that spec, its cheapest offer in stock (its cheapest at
 * all when everything is sold out). Picking a row therefore keeps the spec where it can.
 */
export function merchantRowOffers(offers: readonly MallOffer[], chosen: MallOffer | null): MallOffer[] {
  const merchants: string[] = []
  for (const offer of offers) {
    if (!merchants.includes(offer.merchantId)) merchants.push(offer.merchantId)
  }
  return merchants.map((merchantId) => {
    if (chosen && chosen.merchantId === merchantId) return chosen
    const onChosen = chosen === null ? null : merchantOfferOfSku(offers, merchantId, chosen.skuId)
    if (onChosen) return onChosen
    const own = offers.filter((offer) => offer.merchantId === merchantId)
    return own.find((offer) => !soldOut(offer)) ?? (own[0] as MallOffer)
  })
}

/** How many merchants sell the product here (an offer is one merchant × one spec). */
export function merchantCount(offers: readonly MallOffer[]): number {
  return new Set(offers.map((offer) => offer.merchantId)).size
}
