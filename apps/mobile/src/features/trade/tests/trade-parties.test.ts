import { describe, expect, it } from 'vitest'

import { goodsParties, inquiryParties, rentalParties } from '../presentation/shared/trade-parties'

describe('交易主体 rows', () => {
  it('names the seller as the payee, through the platform channel (C2), and the warehouse when known', () => {
    expect(goodsParties({ merchantName: '示例机器人商家', merchantKindLabel: '厂家直营', fulfilledFrom: '示例机器人商家 东营仓' }))
      .toEqual([
        { label: '销售方', value: '示例机器人商家（厂家直营）', testId: undefined },
        { label: '收款方', value: '示例机器人商家 · 经平台支付渠道' },
        { label: '发货履约', value: '示例机器人商家 东营仓' },
      ])
    expect(goodsParties({ merchantName: '示例机器人商家', merchantKindLabel: null, fulfilledFrom: null }).map((row) => row.label))
      .toEqual(['销售方', '收款方'])
  })

  it('says 出租方 and 交接履约 for a rental', () => {
    expect(rentalParties({ merchantName: '示例机器人商家', merchantKindLabel: '厂家直营', fulfilledFrom: '示例机器人商家 东营仓' })
      .map((row) => row.label)).toEqual(['出租方', '收款方', '交接履约'])
  })

  it('leaves the seller of an inquiry product to the chosen quote', () => {
    expect(inquiryParties().map((row) => row.value)).toEqual(['你选定报价的商家', '销售方 · 经平台支付渠道', '销售方或其授权仓'])
  })
})
