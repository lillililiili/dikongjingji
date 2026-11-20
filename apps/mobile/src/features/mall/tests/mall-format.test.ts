import { describe, expect, it } from 'vitest'

import mallFlagSource from '../application/mall-preview.ts?raw'
import platformFlagSource from '../../platform/application/platform-preview.ts?raw'
import { resolveMallIntent, resolveMallPrimaryAction } from '../application/mall-intent'
import type { MallProductCard } from '../domain/mall'
import {
  formatMallAmount,
  formatMallCardPrice,
  formatMallDetailPrice,
  formatMallOfferPrice,
  formatMallSellers,
} from '../presentation/shared/mall-format'

const mallSources = import.meta.glob('../**/*.{ts,vue}', {
  eager: true,
  import: 'default',
  query: '?raw',
}) as Record<string, string>

const card: MallProductCard = {
  productId: 'p',
  category: 'ROBOT',
  name: '编程机器人探索套装',
  coverImage: null,
  saleMode: 'BUY',
  experienceBadge: 'ONLINE_EXPERIENCE',
  price: { fromAmountMinor: 129900, unit: 'ITEM', multiplePrices: true },
  sellerCount: 3,
}

describe('mall prices', () => {
  it('formats amounts from the minor unit', () => {
    expect(formatMallAmount(129900)).toBe('¥1,299')
    expect(formatMallAmount(129950)).toBe('¥1,299.50')
    expect(formatMallAmount(980000)).toBe('¥9,800')
  })

  it('says 起 only when the price is a starting point', () => {
    expect(formatMallCardPrice(card)).toEqual({ amount: '¥1,299', unit: '', from: '起' })
    expect(formatMallCardPrice({ ...card, price: { ...card.price, multiplePrices: false } }))
      .toEqual({ amount: '¥1,299', unit: '', from: '' })
  })

  it('shows rentals with their unit and always as a starting price', () => {
    expect(formatMallCardPrice({
      ...card,
      saleMode: 'RENT',
      price: { fromAmountMinor: 38000, unit: 'DAY', multiplePrices: false },
    })).toEqual({ amount: '¥380', unit: '/天', from: '起' })
    expect(formatMallCardPrice({
      ...card,
      saleMode: 'RENT',
      price: { fromAmountMinor: 80000, unit: 'HALF_DAY', multiplePrices: false },
    })).toEqual({ amount: '¥800', unit: '/半天', from: '起' })
  })

  it('shows 询价 when no merchant lists a price', () => {
    expect(formatMallCardPrice({
      ...card,
      saleMode: 'INQUIRY',
      price: { fromAmountMinor: null, unit: 'ITEM', multiplePrices: false },
    })).toEqual({ amount: '询价', unit: '', from: '' })
    expect(formatMallOfferPrice(null, 'ITEM')).toBe('询价')
    expect(formatMallOfferPrice(38000, 'DAY')).toBe('¥380/天')
  })

  it('splits a detail price into the amount and the small unit and 起', () => {
    expect(formatMallDetailPrice(129900, 'ITEM', { saleMode: 'BUY', lowestOfSeveral: true }))
      .toEqual({ amount: '¥1,299', suffix: '起' })
    expect(formatMallDetailPrice(135000, 'ITEM', { saleMode: 'BUY', lowestOfSeveral: false }))
      .toEqual({ amount: '¥1,350', suffix: '' })
    expect(formatMallDetailPrice(38000, 'DAY', { saleMode: 'RENT', lowestOfSeveral: false }))
      .toEqual({ amount: '¥380', suffix: '/天 起' })
    expect(formatMallDetailPrice(null, 'ITEM', { saleMode: 'INQUIRY', lowestOfSeveral: false }))
      .toEqual({ amount: '询价', suffix: '按配置与数量报价' })
  })

  it('counts sellers in the words of the sale mode', () => {
    expect(formatMallSellers(card)).toBe('3 家在售')
    expect(formatMallSellers({ ...card, saleMode: 'RENT', sellerCount: 1 })).toBe('租赁 · 1 家')
    expect(formatMallSellers({ ...card, saleMode: 'INQUIRY', sellerCount: 2 })).toBe('2 家可询价')
  })
})

describe('mall actions', () => {
  it('buys listed goods and asks for a quote otherwise', () => {
    expect(resolveMallPrimaryAction('BUY', 129900))
      .toEqual({ intent: 'BUY_NOW', label: '立即购买', cartAvailable: true })
    expect(resolveMallPrimaryAction('BUY', null))
      .toEqual({ intent: 'INQUIRE', label: '联系询价', cartAvailable: false })
    expect(resolveMallPrimaryAction('RENT', 38000))
      .toEqual({ intent: 'INQUIRE', label: '联系询价', cartAvailable: false })
    expect(resolveMallPrimaryAction('INQUIRY', null))
      .toEqual({ intent: 'INQUIRE', label: '联系询价', cartAvailable: false })
  })

  it('explains in preview builds and hides in public builds', () => {
    const intent = { productId: 'p', offerId: 'o' }
    expect(resolveMallIntent({ type: 'ADD_TO_CART', ...intent }, true))
      .toEqual({ action: 'SHOW_NOTICE', message: '购物车即将开放' })
    expect(resolveMallIntent({ type: 'BUY_NOW', ...intent }, true))
      .toEqual({ action: 'SHOW_NOTICE', message: '下单功能即将开放' })
    expect(resolveMallIntent({ type: 'INQUIRE', ...intent }, true))
      .toEqual({ action: 'SHOW_NOTICE', message: '询价功能即将开放' })
    expect(resolveMallIntent({ type: 'BUY_NOW', ...intent }, false)).toEqual({ action: 'HIDE' })
  })

  // The confirm page stops before the order: submitting waits for the trade module.
  it('says why the order cannot be submitted yet and that the agreement text is missing', () => {
    expect(resolveMallIntent({ type: 'SUBMIT_ORDER' }, true))
      .toEqual({ action: 'SHOW_NOTICE', message: '下单和付款要等支付渠道确定后开放' })
    expect(resolveMallIntent({ type: 'READ_AGREEMENT', document: 'TRADE_AGREEMENT' }, true))
      .toEqual({ action: 'SHOW_NOTICE', message: '协议正文待提供' })
    expect(resolveMallIntent({ type: 'SUBMIT_ORDER' }, false)).toEqual({ action: 'HIDE' })
  })
})

describe('mall preview flag', () => {
  const expression = (source: string, name: string): string => {
    const match = new RegExp(`export const ${name} = (.+)`).exec(source)
    if (!match) throw new Error(`${name} is not declared`)
    return match[1].trim()
  }

  it('uses exactly the expression of the shared preview flag', () => {
    expect(expression(mallFlagSource, 'MALL_PREVIEW_ENABLED'))
      .toBe(expression(platformFlagSource, 'PLATFORM_PREVIEW_ENABLED'))
  })

  it('keeps the mall feature from importing the shared flag module', () => {
    const offenders = Object.entries(mallSources)
      .filter(([path]) => !path.includes('/tests/'))
      .filter(([, source]) => /from\s+'[^']*platform-preview'/.test(source))
      .map(([path]) => path)

    expect(offenders).toEqual([])
  })
})
