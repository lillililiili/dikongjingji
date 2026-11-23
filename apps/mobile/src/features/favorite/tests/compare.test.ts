import { describe, expect, it } from 'vitest'

import type { MallOffer, MallProductDetail } from '../../mall/domain/mall'
import { loadCompareProducts } from '../application/compare-load'
import { MAX_COMPARE_ITEMS, createCompareSelection, type ComparePick } from '../application/compare-selection'
import { COMPARE_MISSING, buildCompareTable, formatComparePrice, visibleCompareRows } from '../application/compare-table'

function memory(initial: unknown = null) {
  let stored: unknown = initial
  return {
    read: () => stored,
    write: (value: ComparePick[]) => { stored = value },
    get stored() { return stored },
  }
}

const robot = (productId: string): ComparePick => ({ productId, category: 'ROBOT' })

describe('compare selection', () => {
  it('keeps at most three products of one category and stores them', () => {
    const storage = memory()
    const selection = createCompareSelection(storage)

    expect(selection.toggle(robot('kit'))).toBe('ADDED')
    expect(selection.toggle(robot('arm'))).toBe('ADDED')
    expect(selection.blockedReason({ productId: 'drone', category: 'DRONE' })).toBe('OTHER_CATEGORY')
    expect(selection.toggle({ productId: 'drone', category: 'DRONE' })).toBe('OTHER_CATEGORY')
    expect(selection.toggle(robot('dog'))).toBe('ADDED')
    expect(selection.toggle(robot('car'))).toBe('FULL')
    expect(selection.picks).toHaveLength(MAX_COMPARE_ITEMS)
    expect(selection.blockedReason(robot('kit'))).toBeNull()
    expect(selection.toggle(robot('kit'))).toBe('REMOVED')
    expect(selection.isPicked('kit')).toBe(false)
    expect(storage.stored).toEqual([robot('arm'), robot('dog')])

    selection.clear()
    expect(selection.toggle({ productId: 'drone', category: 'DRONE' })).toBe('ADDED')
  })

  it('reads back only a clean selection from storage', () => {
    const selection = createCompareSelection(memory([
      robot('kit'), robot('kit'), { productId: 'x', category: 'SHOES' }, { productId: 'd', category: 'DRONE' },
      robot('arm'), robot('dog'), robot('car'), 'junk',
    ]))
    expect(selection.picks.map((pick) => pick.productId)).toEqual(['kit', 'arm', 'dog'])
    expect(createCompareSelection(memory('broken')).picks).toEqual([])
  })
})

function offer(merchantId: string, kind: MallOffer['merchantKind'], amountMinor: number | null, extra: Partial<MallOffer> = {}): MallOffer {
  return {
    offerId: `${merchantId}-${amountMinor}`,
    skuId: 'sku',
    skuName: '标准套装',
    merchantId,
    merchantName: merchantId,
    merchantKind: kind,
    amountMinor,
    unit: 'ITEM',
    deliveryNote: null,
    shippingNote: null,
    fulfilledFrom: null,
    onlinePayment: true,
    ...extra,
  }
}

function product(productId: string, overrides: Partial<MallProductDetail>): MallProductDetail {
  return {
    productId,
    category: 'ROBOT',
    name: productId,
    summary: null,
    coverImage: null,
    saleMode: 'BUY',
    experienceBadge: null,
    offers: [],
    specs: [],
    packItems: [],
    ...overrides,
  }
}

const kit = product('kit', {
  name: '编程机器人探索套装',
  experienceBadge: 'ONLINE_EXPERIENCE',
  offers: [offer('f1', 'FACTORY_DIRECT', 129_900, { fulfilledFrom: '东营仓' }), offer('d1', 'AUTHORIZED_DEALER', 139_900), offer('d2', 'AUTHORIZED_DEALER', 135_000)],
  specs: [{ label: '适用年龄', value: '8 岁以上' }, { label: '编程方式', value: '图形化 / Python' }],
})
const arm = product('arm', {
  name: '桌面六轴机械臂 教学版',
  offers: [offer('f2', 'FACTORY_DIRECT', 980_000), offer('l1', 'LOCAL_STORE', 980_000)],
  specs: [{ label: '编程方式', value: '图形化 / Python / ROS' }, { label: '适用年龄', value: '8 岁以上' }, { label: '负载', value: '500 g' }],
})

describe('compare table (board MallCompare)', () => {
  it('lines the facts up row by row and marks the rows that differ', () => {
    const table = buildCompareTable([kit, arm])

    expect(table.columns).toEqual([
      { productId: 'kit', name: '编程机器人探索套装', price: '¥1,299 起' },
      { productId: 'arm', name: '桌面六轴机械臂 教学版', price: '¥9,800' },
    ])
    expect(table.rows.map((row) => [row.label, ...row.values, row.differs])).toEqual([
      ['在售商家', '3 家（厂家 1 · 经销 2）', '2 家（厂家 1 · 本地 1）', true],
      ['交易方式', '购买', '购买', false],
      ['体验方式', '可线上体验', COMPARE_MISSING, true],
      ['适用年龄', '8 岁以上', '8 岁以上', false],
      ['编程方式', '图形化 / Python', '图形化 / Python / ROS', true],
      ['负载', COMPARE_MISSING, '500 g', true],
      ['发货地', '东营仓', COMPARE_MISSING, true],
      ['发票', '由销售方开具', '由销售方开具', false],
    ])
    expect(visibleCompareRows(table, true).map((row) => row.label))
      .toEqual(['在售商家', '体验方式', '编程方式', '负载', '发货地'])
  })

  it('prices rentals from their day price and inquiries as 询价', () => {
    expect(formatComparePrice(product('dog', { saleMode: 'RENT', offers: [offer('r', 'LOCAL_STORE', 38_000, { unit: 'DAY' })] })))
      .toBe('¥380/天 起')
    expect(formatComparePrice(product('big', { saleMode: 'INQUIRY', offers: [offer('q', 'FACTORY_DIRECT', null)] }))).toBe('询价')
  })

  it('leaves out products the city no longer sells and fails on other errors', async () => {
    const notFound = Object.assign(new Error('gone'), { status: 404 })
    const result = await loadCompareProducts(['kit', 'gone', 'arm'], '370500', async (id) => {
      if (id === 'gone') throw notFound
      return id === 'kit' ? kit : arm
    })
    expect(result.products.map((item) => item.productId)).toEqual(['kit', 'arm'])
    expect(result.missing).toEqual(['gone'])
    await expect(loadCompareProducts(['kit'], '370500', async () => { throw Object.assign(new Error('down'), { status: 500 }) }))
      .rejects.toThrow('down')
  })
})
