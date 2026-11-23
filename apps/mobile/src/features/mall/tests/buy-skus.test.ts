import { describe, expect, it } from 'vitest'

import type { MallOffer, MallSku } from '../domain/mall'
import {
  BUY_SKU_STATE_TEXT,
  buyMerchantLine,
  buyQuantityLimit,
  buySkuRows,
  clampBuyQuantity,
  hasSkuChoice,
  initialBuyOffer,
  merchantCount,
  merchantRowOffers,
} from '../presentation/product/buy-skus'

// The PdSku board: 示例机器人商家 sells 标准套装 (12 in stock) and 教室套装 (3), nobody sells 扩展传感器包;
// two other merchants sell only 标准套装, stock not tracked.
const SKUS: MallSku[] = [
  { skuId: 'std', name: '标准套装（1 台 + 数字课程）' },
  { skuId: 'class', name: '教室套装（6 台 + 教师端）' },
  { skuId: 'sensor', name: '扩展传感器包' },
]

function offer(offerId: string, merchantId: string, skuId: string, amountMinor: number, stockQuantity: number | null,
  overrides: Partial<MallOffer> = {}): MallOffer {
  return {
    offerId,
    skuId,
    skuName: SKUS.find((sku) => sku.skuId === skuId)?.name ?? skuId,
    merchantId,
    merchantName: merchantId === 'maker' ? '示例机器人商家' : '示例商家',
    merchantKind: 'FACTORY_DIRECT',
    amountMinor,
    unit: 'ITEM',
    deliveryNote: null,
    shippingNote: null,
    fulfilledFrom: null,
    onlinePayment: true,
    stockQuantity,
    ...overrides,
  }
}

const MAKER_STD = offer('maker-std', 'maker', 'std', 129900, 12)
const AGENT_STD = offer('agent-std', 'agent', 'std', 135000, null)
const STORE_STD = offer('store-std', 'store', 'std', 138000, null)
const MAKER_CLASS = offer('maker-class', 'maker', 'class', 738000, 3)
// Cheapest first, as the server sends them.
const OFFERS = [MAKER_STD, AGENT_STD, STORE_STD, MAKER_CLASS]

describe('规格与数量 specs', () => {
  it('lists every spec with the chosen merchant’s offer, greying the ones it does not sell', () => {
    const rows = buySkuRows(SKUS, OFFERS, MAKER_STD)
    expect(rows.map((row) => [row.skuId, row.offer?.offerId ?? null, row.state])).toEqual([
      ['std', 'maker-std', 'AVAILABLE'],
      ['class', 'maker-class', 'AVAILABLE'],
      ['sensor', null, 'NOT_SUPPLIED'],
    ])
    expect(BUY_SKU_STATE_TEXT.NOT_SUPPLIED).toBe('本商家暂不供应')
  })

  it('only offers what the chosen merchant sells', () => {
    expect(buySkuRows(SKUS, OFFERS, AGENT_STD).map((row) => row.state))
      .toEqual(['AVAILABLE', 'NOT_SUPPLIED', 'NOT_SUPPLIED'])
  })

  it('greys a sold-out spec', () => {
    const soldOutClass = { ...MAKER_CLASS, stockQuantity: 0 }
    const rows = buySkuRows(SKUS, [MAKER_STD, soldOutClass], MAKER_STD)
    expect(rows[1]).toMatchObject({ state: 'SOLD_OUT', offer: soldOutClass })
    expect(BUY_SKU_STATE_TEXT.SOLD_OUT).toBe('暂时缺货')
  })

  it('keeps the single spec of the chosen offer without spec information', () => {
    expect(buySkuRows([], [AGENT_STD], AGENT_STD)).toEqual([
      { skuId: 'std', name: '标准套装（1 台 + 数字课程）', offer: AGENT_STD, state: 'AVAILABLE' },
    ])
    expect(hasSkuChoice([])).toBe(false)
    expect(hasSkuChoice(SKUS.slice(0, 1))).toBe(false)
    expect(hasSkuChoice(SKUS)).toBe(true)
  })
})

describe('quantity limit', () => {
  it('stops at the stock, or at 99 when the stock is not tracked', () => {
    expect(buyQuantityLimit(MAKER_CLASS)).toBe(3)
    expect(buyQuantityLimit(MAKER_STD)).toBe(12)
    expect(buyQuantityLimit(AGENT_STD)).toBe(99)
    expect(buyQuantityLimit({ ...AGENT_STD, stockQuantity: 500 })).toBe(99)
    expect(buyQuantityLimit({ ...AGENT_STD, stockQuantity: 0 })).toBe(1)
  })

  it('clamps the quantity after a switch to a spec with less stock', () => {
    expect(clampBuyQuantity(10, MAKER_CLASS)).toBe(3)
    expect(clampBuyQuantity(2, MAKER_CLASS)).toBe(2)
    expect(clampBuyQuantity(0, MAKER_CLASS)).toBe(1)
  })
})

describe('merchant line', () => {
  it('says the stock in the city when it is known', () => {
    expect(buyMerchantLine(MAKER_STD, '东营市')).toBe('示例机器人商家 · 东营现货 12 件')
    expect(buyMerchantLine(MAKER_CLASS, null)).toBe('示例机器人商家 · 现货 3 件')
    expect(buyMerchantLine({ ...MAKER_CLASS, stockQuantity: 0 }, '东营市')).toBe('示例机器人商家 · 暂时缺货')
  })

  it('keeps the merchant and its kind when the stock is not tracked', () => {
    expect(buyMerchantLine(AGENT_STD, '东营市')).toBe('示例商家（厂家直营）')
  })
})

describe('选择在售商家', () => {
  it('starts on the cheapest merchant that takes payment and has stock', () => {
    expect(initialBuyOffer(OFFERS)?.offerId).toBe('maker-std')
    expect(initialBuyOffer([{ ...MAKER_STD, stockQuantity: 0 }, AGENT_STD])?.offerId).toBe('agent-std')
    expect(initialBuyOffer([{ ...MAKER_STD, onlinePayment: false }, AGENT_STD])?.offerId).toBe('agent-std')
    expect(initialBuyOffer([{ ...MAKER_STD, onlinePayment: false }])).toBeNull()
  })

  it('shows one row per merchant, on the chosen spec where the merchant sells it', () => {
    expect(merchantRowOffers(OFFERS, MAKER_STD).map((row) => row.offerId))
      .toEqual(['maker-std', 'agent-std', 'store-std'])
    expect(merchantRowOffers(OFFERS, MAKER_CLASS).map((row) => row.offerId))
      .toEqual(['maker-class', 'agent-std', 'store-std'])
    expect(merchantRowOffers(OFFERS, null).map((row) => row.offerId))
      .toEqual(['maker-std', 'agent-std', 'store-std'])
    expect(merchantCount(OFFERS)).toBe(3)
  })

  it('keeps the chosen spec when switching to a merchant that sells it', () => {
    const agentClass = offer('agent-class', 'agent', 'class', 760000, null)
    const offers = [MAKER_STD, AGENT_STD, STORE_STD, MAKER_CLASS, agentClass]
    expect(merchantRowOffers(offers, MAKER_CLASS).map((row) => row.offerId))
      .toEqual(['maker-class', 'agent-class', 'store-std'])
  })
})
